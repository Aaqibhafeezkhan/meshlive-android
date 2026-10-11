package com.example.bluetoothtest

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class DecoderPipelineStats(
    val queue: EncodedFrameQueueStats,
    val queuedToDecoderFrames: Int,
    val submittedForRenderFrames: Int,
    val renderedFrames: Int,
    val decoderWait: LatencySnapshot,
    val scheduledRenderDelay: LatencySnapshot,
    val renderedFrameInterval: LatencySnapshot,
)

internal fun decoderCodecSpecificData(
    codecId: Int,
    codecSpecificData: List<ByteArray>,
): List<ByteArray> = if (codecId == StreamProtocol.CODEC_AV1) {
    codecSpecificData.takeLast(1)
} else {
    codecSpecificData.fold(mutableListOf<ByteArray>()) { selected, bytes ->
        if (selected.none { it.contentEquals(bytes) }) selected.add(bytes)
        selected
    }
}

class VideoStreamDecoder(
    private val header: StreamProtocol.Header,
    private val surface: Surface,
    codecSpecificData: List<ByteArray>,
    preferLowLatency: Boolean = true,
    private val playbackDelayProfile: PlaybackDelayProfile = PlaybackDelayProfile.BALANCED,
    private val playoutClock: MediaPlayoutClock = playbackDelayProfile.newPlayoutClock(),
    preferredDecoderName: String? = null,
    /** Why [preferredDecoderName] is named; an explicit (test_decoder) request is never substituted. */
    decoderRequest: DecoderRequest = if (preferredDecoderName != null) DecoderRequest.EXPLICIT else DecoderRequest.NONE,
    queueCapacity: Int = playbackDelayProfile.videoQueueCapacity(header.fps),
    debugPresentationTiming: Boolean = false,
    debugReleaseAtPlayout: Boolean = true,
    private val onRecoveryStateChanged: (Boolean) -> Unit = {},
    debugTraceSession: String? = null,
    /**
     * Experimental first call frame on arrival (DebugTunables.callFirstFrameNow, calls only): the
     * first frame is fed and released at once instead of at its playout target. False: unchanged.
     */
    private val presentFirstFrameImmediately: Boolean = false,
    /**
     * Experimental viewer frame age (null = off): the source timestamp of each frame the codec
     * rendered to the surface. Called on the codec worker thread; it must only record.
     */
    private val onFrameRendered: ((Long) -> Unit)? = null,
) : Closeable {
    private val running = AtomicBoolean(true)
    private val debugStageTrace = DebugStageTrace.startIfRequested(debugTraceSession, "video_decoder")
    private val failure = AtomicReference<Throwable?>()
    private val workerThread = HandlerThread("VideoDecoder").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val availableInputBuffers = ArrayDeque<Int>()
    /**
     * Identity of the codec instance whose callbacks count. Each initialisation attempt has its own
     * number, so a late callback (onError) from a released attempt cannot touch the working codec.
     */
    @Volatile private var activeAttempt = 0
    private val selectedCodecSpecificData =
        decoderCodecSpecificData(header.codecId, codecSpecificData).map(ByteArray::copyOf)
    private val pendingCodecSpecificData = ArrayDeque<ByteArray>()
    private val encodedFrames = EncodedFrameQueue(
        queueCapacity,
        requireInitialKeyFrame = true,
        thinDiscardable = DebugTunables.experimentalTemporalLayers,
    )
    private val decoderWait = RollingLatencyStats()
    private val scheduledRenderDelay = RollingLatencyStats()
    private val renderedFrameInterval = RollingLatencyStats()
    private val forwardingFrameInterval = RollingLatencyStats()
    private val queuedToDecoderFrames = AtomicInteger()
    private val submittedForRenderFrames = AtomicInteger()
    private val actuallyRenderedFrames = AtomicInteger()
    private val resetAtRecoveryKeyFrame = AtomicBoolean(false)
    private val decoderInputBlocked = AtomicBoolean(false)
    private val decoderRestartScheduled = AtomicBoolean(false)
    private val recovering = AtomicBoolean(false)
    private val recoveryPresentationTimeUs = AtomicLong(Long.MAX_VALUE)
    private var lastRenderedAtNs = 0L
    private var delayedDrainPending = false
    // Worker-thread only: queueInputBuffer time per PTS, consumed at decoder output.
    private val decoderInputNs = LinkedHashMap<Long, Long>()
    private val decodeLatency = DecoderLatencyWindow()
    @Volatile private var feedAheadMs = DECODER_FEED_AHEAD_MS
    // Releasing each frame at its playout moment (instead of handing the display a timestamp) took
    // ASUS->S24 Classic 1000 kbps from p95/p99 83.7/284 ms to 67.7/84.0 ms at 15.0 FPS (sweep1).
    private val releaseAtPlayout = debugReleaseAtPlayout
    @Volatile private var deferredOutputGeneration = 1L
    @Volatile private var deferredOutputCount = 0
    @Volatile private var deferredOutputHighWater = 0
    @Volatile private var deferredOutputDiscarded = 0L
    private val presentationTiming = if (BuildConfig.DEBUG && debugPresentationTiming) {
        VideoPresentationTiming(generation = 1, capacity = 512)
    } else null
    private val presentationScopeNs = if (presentationTiming != null) System.nanoTime() else 0L
    @Volatile private var presentationGeneration = 1L
    private var presentationLastReportNs = 0L
    private var presentationReport = 0L
    private var presentationFinalReported = false
    private val codec: MediaCodec

    val decoderName: String
    val lowLatencyEnabled: Boolean
    val renderedFrames: Int
        get() = actuallyRenderedFrames.get()

    init {
        val mime = when (header.codecId) {
            StreamProtocol.CODEC_VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
            StreamProtocol.CODEC_AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
            else -> error("Unsupported codec ID ${header.codecId}")
        }
        val format = MediaFormat.createVideoFormat(mime, header.width, header.height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, header.fps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, StreamProtocol.MAX_PAYLOAD_BYTES)
            selectedCodecSpecificData.forEachIndexed { index, bytes ->
                setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
            }
            if (header.codecId == StreamProtocol.CODEC_AV1 && codecSpecificData.isNotEmpty()) {
                Log.i(
                    "BluetoothVideo",
                    "Using the final AV1 codec-config packet as csd-0; discarded " +
                        "${codecSpecificData.size - selectedCodecSpecificData.size} earlier packet(s).",
                )
            }
        }
        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        // Every non-secure decoder that supports the format, in MediaCodecList order (aliases included,
        // as the former requested-decoder lookup); the planner drops aliases only when it falls back.
        val supporting = codecInfos.filter { info ->
            !info.isEncoder && !info.name.endsWith(".secure") &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching { info.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
        }.map { DecoderOption(it.name, it.isAlias, it.isSoftwareOnly, it.isHardwareAccelerated) }
        val frameworkChoice = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
        val fallbackEnabled = CodecPolicy.decoderInitFallbackEnabled(DeviceTraits.current())
        // Bounded (CodecPolicy.MAX_DECODER_INIT_ATTEMPTS): callers build this decoder inside their packet
        // path (PrivateCallMedia.offer is synchronized with audio), so a failing start can delay audio by at
        // most three create/configure/start attempts, and only after the first one already failed.
        val candidates = CodecPolicy.decoderInitCandidates(
            preferredDecoderName, decoderRequest, frameworkChoice, supporting, fallbackEnabled,
        )
        val requestedLabel = preferredDecoderName ?: "none"
        fun logSelection(effective: String?, attempt: Int) = Log.i(
            "BluetoothVideo",
            "BENCHMARK decoder requested=$requestedLabel effective=${effective ?: "none"} " +
                "reason=${CodecPolicy.decoderSelectionReason(preferredDecoderName, decoderRequest, effective, attempt)} " +
                "kind=${decoderRequest.label} fallback=${if (fallbackEnabled) "on" else "off"} " +
                "tried=${if (effective == null) candidates.size else attempt + 1} mime=$mime",
        )
        fun giveUp(error: Throwable): Nothing {
            activeAttempt = -1
            workerThread.quitSafely()
            debugStageTrace?.close()
            throw error
        }
        if (candidates.isEmpty()) {
            logSelection(null, 0)
            giveUp(
                IllegalStateException(
                    if (preferredDecoderName != null && fallbackEnabled) {
                        "Requested $mime decoder $preferredDecoderName cannot decode ${header.width}x${header.height}"
                    } else {
                        "This phone has no $mime decoder for ${header.width}x${header.height}"
                    },
                ),
            )
        }
        var firstFailure: Exception? = null
        var created: MediaCodec? = null
        var createdIndex = 0
        var createdLowLatency = false
        for ((index, candidate) in candidates.withIndex()) {
            val candidateFormat = MediaFormat(format)
            val candidateLowLatency = preferLowLatency && runCatching {
                codecInfos.first { it.name == candidate }.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
            if (candidateLowLatency) candidateFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val attemptId = index + 1
            activeAttempt = attemptId
            var attempt: MediaCodec? = null
            try {
                attempt = MediaCodec.createByCodecName(candidate)
                attempt.setCallback(createCodecCallback(attemptId), worker)
                traceDecoderOperation("video.decode.configure") { attempt.configure(candidateFormat, surface, null, 0) }
                attempt.setOnFrameRenderedListener(createFrameRenderedListener(presentationGeneration), worker)
                traceDecoderOperation("video.decode.start") { attempt.start() }
                created = attempt
                createdIndex = index
                createdLowLatency = candidateLowLatency
                break
            } catch (error: Exception) {
                activeAttempt = -1
                runCatching { attempt?.release() }
                if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
                Log.w(
                    "BluetoothVideo",
                    "BENCHMARK decoder_init_fallback: failed=$candidate | next=${candidates.getOrNull(index + 1) ?: "none"} | " +
                        "mime=$mime | error=${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }
        if (created == null) {
            logSelection(null, candidates.size)
            giveUp(checkNotNull(firstFailure))
        }
        codec = created
        decoderName = candidates[createdIndex]
        lowLatencyEnabled = createdLowLatency
        logSelection(decoderName, createdIndex)
    }

    fun offer(packet: StreamProtocol.Packet): Boolean {
        checkFailure()
        if (!running.get()) return false
        val isDiscontinuityKeyFrame =
            packet.flags and StreamProtocol.FLAG_DISCONTINUITY != 0 &&
                packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0
        if (isDiscontinuityKeyFrame) encodedFrames.resetForDiscontinuity()
        val accepted = encodedFrames.offer(packet)
        if (!accepted) {
            if (encodedFrames.stats().needsKeyFrame) {
                resetAtRecoveryKeyFrame.set(true)
                decoderInputBlocked.set(true)
                beginRecovery()
            }
            return false
        }
        val isRecoveryKeyFrame = isDiscontinuityKeyFrame ||
            packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0 &&
            resetAtRecoveryKeyFrame.getAndSet(false)
        if (isRecoveryKeyFrame) {
            decoderInputBlocked.set(true)
            beginRecovery()
            recoveryPresentationTimeUs.set(packet.presentationTimeUs)
        }
        if (isRecoveryKeyFrame) {
            if (decoderRestartScheduled.compareAndSet(false, true)) {
                worker.post(::restartAtRecoveryKeyFrame)
            }
        } else {
            worker.post(::drainInput)
        }
        return accepted
    }

    fun stats(): DecoderPipelineStats {
        if (presentationTiming != null) reportPresentationTiming()
        return DecoderPipelineStats(
            queue = encodedFrames.stats(),
            queuedToDecoderFrames = queuedToDecoderFrames.get(),
            submittedForRenderFrames = submittedForRenderFrames.get(),
            renderedFrames = actuallyRenderedFrames.get(),
            decoderWait = decoderWait.snapshot(),
            scheduledRenderDelay = scheduledRenderDelay.snapshot(),
            renderedFrameInterval = renderedFrameInterval.snapshot(),
        )
    }

    fun recordSurfaceUpdate(timestampNs: Long, callbackNowNs: Long) {
        if (!running.get()) return
        presentationTiming?.recordTexture(presentationGeneration, timestampNs, callbackNowNs)
        // Texture timestamp identity is reported independently; it is not assumed to equal source PTS.
        debugStageTrace?.record("texture_notification", timestampNs / 1_000, atNs = callbackNowNs)
    }

    @Synchronized
    private fun reportPresentationTiming(final: Boolean = false) {
        val timing = presentationTiming ?: return
        if (presentationFinalReported) return
        val nowNs = System.nanoTime()
        if (!final && presentationLastReportNs != 0L && nowNs - presentationLastReportNs < 5_000_000_000L) return
        presentationLastReportNs = nowNs
        presentationFinalReported = final
        Log.i(
            "BluetoothVideo",
            "BENCHMARK decoder output pacing: scope=$presentationScopeNs final=$final " +
                "enabled=$releaseAtPlayout generation=$deferredOutputGeneration pending=$deferredOutputCount " +
                "highWater=$deferredOutputHighWater discarded=$deferredOutputDiscarded " +
                "feedAheadMs=$feedAheadMs decodeP99Ms=${decodeLatency.p99Ms()} targetDelayMs=${playbackDelayProfile.delayMs}",
        )
        timing.snapshot().traceLines(presentationScopeNs, ++presentationReport, final, decoderName)
            .forEach { Log.i("BluetoothVideo", it) }
    }

    /**
     * Never call back into MediaCodec from this listener: below API 30 that can deadlock (Media3
     * MediaCodecVideoRenderer.OnFrameRenderedListener, AOSP change 1156807; API 29 is our minSdk).
     */
    private fun createFrameRenderedListener(observedGeneration: Long) =
        MediaCodec.OnFrameRenderedListener { _, presentationTimeUs, renderedAtNs ->
            debugStageTrace?.record("surface_ack", presentationTimeUs, atNs = renderedAtNs)
            presentationTiming?.recordCodec(observedGeneration, presentationTimeUs, renderedAtNs, System.nanoTime())
            val previousRenderedAtNs = lastRenderedAtNs
            if (previousRenderedAtNs > 0 && renderedAtNs > previousRenderedAtNs) {
                renderedFrameInterval.recordNanos(renderedAtNs - previousRenderedAtNs)
                forwardingFrameInterval.recordNanos(renderedAtNs - previousRenderedAtNs)
            }
            lastRenderedAtNs = renderedAtNs
            actuallyRenderedFrames.incrementAndGet()
            onFrameRendered?.invoke(presentationTimeUs)
            val recoveryPtsUs = recoveryPresentationTimeUs.get()
            if (recoveryPtsUs != Long.MAX_VALUE && presentationTimeUs >= recoveryPtsUs &&
                recovering.compareAndSet(true, false)
            ) {
                recoveryPresentationTimeUs.set(Long.MAX_VALUE)
                onRecoveryStateChanged(false)
            }
        }

    fun checkFailure() {
        failure.get()?.let { throw IllegalStateException("Decoder failed", it) }
    }

    fun resetForwardingCadence() = forwardingFrameInterval.reset()

    fun resetRenderedCadence() {
        worker.post {
            lastRenderedAtNs = 0L
            renderedFrameInterval.reset()
            forwardingFrameInterval.reset()
        }
    }

    fun takeForwardingCadence(minimumSamples: Int): LatencySnapshot? =
        forwardingFrameInterval.takeSnapshot(minimumSamples)

    private fun beginRecovery() {
        if (recovering.compareAndSet(false, true)) {
            recoveryPresentationTimeUs.set(Long.MAX_VALUE)
            onRecoveryStateChanged(true)
        }
    }

    private inline fun <T> traceDecoderOperation(name: String, operation: () -> T): T {
        if (!BuildConfig.DEBUG) return operation()
        Trace.beginSection(name)
        return try {
            operation()
        } finally {
            Trace.endSection()
        }
    }

    private fun createCodecCallback(attempt: Int): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (attempt != activeAttempt || !running.get() || decoderInputBlocked.get()) return
            availableInputBuffers.addLast(index)
            drainInput()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (attempt != activeAttempt || !running.get() || decoderInputBlocked.get()) return
            try {
                val render = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                if (render) {
                    val nowNs = System.nanoTime()
                    val presentationTimeUs = info.presentationTimeUs
                    debugStageTrace?.record("decoder_output", presentationTimeUs, info.size)
                    recordDecodeLatency(presentationTimeUs, nowNs)
                    val renderTimeNs = if (presentFirstFrameImmediately && submittedForRenderFrames.get() == 0) {
                        nowNs
                    } else {
                        playoutClock.renderTimeNs(presentationTimeUs, nowNs)
                    }
                    scheduledRenderDelay.recordNanos(renderTimeNs - nowNs)
                    val observedGeneration = presentationGeneration
                    val waitMs = if (releaseAtPlayout) {
                        decoderFeedDelayMs(renderTimeNs, System.nanoTime(), decodeAheadMs = 0L)
                    } else 0L
                    if (waitMs > 0L) {
                        val outputGeneration = deferredOutputGeneration
                        deferredOutputCount++
                        deferredOutputHighWater = maxOf(deferredOutputHighWater, deferredOutputCount)
                        worker.postDelayed(object : Runnable {
                            override fun run() {
                                if (outputGeneration != deferredOutputGeneration) return
                                if (!running.get() || decoderInputBlocked.get() || failure.get() != null) {
                                    deferredOutputCount--
                                    deferredOutputDiscarded++
                                    return
                                }
                                val remainingMs = decoderFeedDelayMs(renderTimeNs, System.nanoTime(), decodeAheadMs = 0L)
                                if (remainingMs > 0L) {
                                    worker.postDelayed(this, remainingMs)
                                    return
                                }
                                deferredOutputCount--
                                try {
                                    releaseVideoOutput(codec, index, presentationTimeUs, renderTimeNs, observedGeneration)
                                } catch (error: Exception) {
                                    fail(error)
                                }
                            }
                        }, waitMs)
                    } else {
                        releaseVideoOutput(codec, index, presentationTimeUs, renderTimeNs, observedGeneration)
                    }
                } else {
                    traceDecoderOperation("video.decode.release-output") { codec.releaseOutputBuffer(index, false) }
                }
            } catch (error: Exception) {
                fail(error)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Evidence for in-band size changes (call resolution ladder): the header gives the maximum.
            if (attempt != activeAttempt) return
            val (width, height) = decodedOutputSize(format) ?: return
            if (width == header.width && height == header.height) return
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "decoder_output_size: decoded=${width}x$height | header=${header.width}x${header.height} | decoder=$decoderName",
            )
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (attempt == activeAttempt) fail(error)
        }
    }

    private fun releaseVideoOutput(
        activeCodec: MediaCodec,
        index: Int,
        presentationTimeUs: Long,
        renderTimeNs: Long,
        observedGeneration: Long,
    ) {
        submittedForRenderFrames.incrementAndGet()
        val releaseCallNs = if (presentationTiming != null) System.nanoTime() else 0L
        traceDecoderOperation("video.decode.release-output") { activeCodec.releaseOutputBuffer(index, renderTimeNs) }
        debugStageTrace?.record("release", presentationTimeUs)
        presentationTiming?.recordRelease(observedGeneration, presentationTimeUs, renderTimeNs, releaseCallNs)
    }

    private fun drainInput() {
        if (!running.get() || failure.get() != null) return
        if (decoderInputBlocked.get()) return
        if (delayedDrainPending) return
        try {
            while (availableInputBuffers.isNotEmpty()) {
                val index = availableInputBuffers.removeFirst()
                val codecConfig = if (pendingCodecSpecificData.isEmpty()) {
                    null
                } else {
                    pendingCodecSpecificData.removeFirst()
                }
                if (codecConfig != null) {
                    val input = checkNotNull(traceDecoderOperation("video.decode.get-input") { codec.getInputBuffer(index) })
                    input.clear()
                    require(codecConfig.size <= input.remaining()) {
                        "Codec configuration is too large for decoder input (${codecConfig.size} bytes)"
                    }
                    input.put(codecConfig)
                    traceDecoderOperation("video.decode.queue-config") {
                        codec.queueInputBuffer(
                            index,
                            0,
                            codecConfig.size,
                            0,
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                        )
                    }
                    continue
                }
                val nextPacket = encodedFrames.peek()
                if (nextPacket == null) {
                    availableInputBuffers.addFirst(index)
                    return
                }
                val nowNs = System.nanoTime()
                val aheadMs = adaptiveDecoderFeedAheadMs(decodeLatency.p99Ms(), playoutClock.currentDelayMs)
                feedAheadMs = aheadMs
                val feedDelayMs = if (presentFirstFrameImmediately && queuedToDecoderFrames.get() == 0) {
                    0L
                } else {
                    videoPacketFeedTarget(nextPacket, playoutClock, nowNs)?.let {
                        decoderFeedDelayMs(it.targetTimeNs, nowNs, aheadMs)
                    } ?: 0L
                }
                if (feedDelayMs > 0L) {
                    availableInputBuffers.addFirst(index)
                    delayedDrainPending = true
                    worker.postDelayed(
                        {
                            delayedDrainPending = false
                            drainInput()
                        },
                        feedDelayMs,
                    )
                    return
                }
                val queued = encodedFrames.poll()
                if (queued == null) {
                    availableInputBuffers.addFirst(index)
                    return
                }
                val (packet, waitedNs) = queued
                val input = checkNotNull(traceDecoderOperation("video.decode.get-input") { codec.getInputBuffer(index) })
                input.clear()
                require(packet.payload.size <= input.remaining()) {
                    "Compressed frame is too large for decoder input (${packet.payload.size} bytes)"
                }
                input.put(packet.payload)
                val codecFlags = if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                } else {
                    0
                }
                traceDecoderOperation("video.decode.queue-input") {
                    debugStageTrace?.record("decoder_input", packet.presentationTimeUs, packet.payload.size)
                    codec.queueInputBuffer(index, 0, packet.payload.size, packet.presentationTimeUs, codecFlags)
                }
                if (codecFlags == 0) {
                    decoderInputNs[packet.presentationTimeUs] = System.nanoTime()
                    if (decoderInputNs.size > MAX_TRACKED_DECODER_INPUTS) {
                        decoderInputNs.remove(decoderInputNs.keys.first())
                    }
                }
                decoderWait.recordNanos(waitedNs)
                if (codecFlags == 0) queuedToDecoderFrames.incrementAndGet()
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    /** Input->output time of one decoded frame; inputs the decoder skipped are discarded. */
    private fun recordDecodeLatency(presentationTimeUs: Long, outputNs: Long) {
        val inputNs = decoderInputNs.remove(presentationTimeUs) ?: return
        val iterator = decoderInputNs.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key < presentationTimeUs) iterator.remove()
        }
        decodeLatency.record((outputNs - inputNs) / 1_000_000L)
    }

    private fun restartAtRecoveryKeyFrame() {
        if (!running.get() || failure.get() != null) return
        try {
            availableInputBuffers.clear()
            decoderInputNs.clear()
            delayedDrainPending = false
            pendingCodecSpecificData.clear()
            pendingCodecSpecificData.addAll(selectedCodecSpecificData)
            if (releaseAtPlayout) {
                deferredOutputGeneration++
                deferredOutputDiscarded += deferredOutputCount
                deferredOutputCount = 0
            }
            if (presentationTiming != null) {
                presentationGeneration++
                presentationTiming.beginGeneration(presentationGeneration)
            }
            traceDecoderOperation("video.decode.flush") { codec.flush() }
            worker.post(::resumeAfterRecoveryFlush)
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun resumeAfterRecoveryFlush() {
        if (!running.get() || failure.get() != null) return
        try {
            // A flush can leave already-posted asynchronous callbacks on vendor codec stacks.
            // Let those callbacks drain while input is blocked, then start a clean generation.
            availableInputBuffers.clear()
            if (presentationTiming != null || debugStageTrace != null) {
                codec.setOnFrameRenderedListener(createFrameRenderedListener(presentationGeneration), worker)
            }
            traceDecoderOperation("video.decode.start") { codec.start() }
            decoderRestartScheduled.set(false)
            decoderInputBlocked.set(false)
            Log.i(
                "BluetoothVideo",
                "Decoder recovery restored ${pendingCodecSpecificData.size} codec-config buffer(s) before the keyframe.",
            )
            drainInput()
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        if (failure.compareAndSet(null, error)) {
            Log.e("BluetoothVideo", "Video decoder $decoderName failed", error)
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        if (recovering.getAndSet(false)) onRecoveryStateChanged(false)
        encodedFrames.clear()
        worker.removeCallbacksAndMessages(null)
        val release = {
            if (releaseAtPlayout) {
                deferredOutputGeneration++
                deferredOutputDiscarded += deferredOutputCount
                deferredOutputCount = 0
            }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (presentationTiming != null) reportPresentationTiming(final = true)
        }
        if (Looper.myLooper() == workerThread.looper) {
            release()
        } else {
            val released = CountDownLatch(1)
            worker.post {
                release()
                released.countDown()
            }
            runCatching { released.await(2, TimeUnit.SECONDS) }
        }
        workerThread.quitSafely()
        debugStageTrace?.close()
    }
}

internal fun videoPacketPlayoutTiming(
    packet: StreamProtocol.Packet,
    playoutClock: MediaPlayoutClock,
    nowNs: Long,
    /** Experimental speech-led call playout only (see [MediaPlayoutClock.timing]); null: unchanged. */
    growthLimitMs: Long? = null,
): MediaPlayoutTiming? = if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
    null
} else {
    playoutClock.timing(packet.presentationTimeUs, nowNs, growthLimitMs)
}

/** Post-arrival (decoder feed) lookup: never moves the clock; see [MediaPlayoutClock.targetFor]. */
internal fun videoPacketFeedTarget(
    packet: StreamProtocol.Packet,
    playoutClock: MediaPlayoutClock,
    nowNs: Long,
): MediaPlayoutTiming? = if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
    null
} else {
    playoutClock.targetFor(packet.presentationTimeUs, nowNs)
}

/**
 * How far ahead of its render time a frame is fed: the measured decoder input->output p99 plus a
 * margin, never below [DECODER_FEED_AHEAD_MS] and never past the current playout delay minus
 * [DECODER_FEED_AHEAD_SAFETY_MS]. A decoder with periodic stalls (S24 c2.exynos.av1.decoder,
 * 163-179 ms every 61 frames) then gets slack instead of late output. The margin stays below one
 * frame interval (33 ms at 30 FPS), so output back-pressure from held frames, which makes measured
 * latency trail the lead by about a frame per held buffer, converges instead of ratcheting.
 */
internal fun adaptiveDecoderFeedAheadMs(decodeP99Ms: Long?, playoutDelayMs: Long): Long {
    val wantedMs = (decodeP99Ms ?: 0L) + DECODER_FEED_AHEAD_MARGIN_MS
    val ceilingMs = playoutDelayMs - DECODER_FEED_AHEAD_SAFETY_MS
    return maxOf(DECODER_FEED_AHEAD_MS, minOf(wantedMs, ceilingMs))
}

/** Rolling decoder input->output latency (last [capacity] frames). Not thread-safe. */
internal class DecoderLatencyWindow(private val capacity: Int = 128) {
    private val samples = LongArray(capacity)
    private var count = 0
    private var next = 0
    private var cachedP99: Long? = null
    private var dirty = false

    fun record(latencyMs: Long) {
        samples[next] = latencyMs.coerceAtLeast(0L)
        next = (next + 1) % capacity
        if (count < capacity) count++
        dirty = true
    }

    /** p99 once [MIN_DECODE_LATENCY_SAMPLES] frames were measured, else null (fixed lead). */
    fun p99Ms(): Long? {
        if (count < MIN_DECODE_LATENCY_SAMPLES) return null
        if (dirty) {
            val sorted = samples.copyOf(count).also { it.sort() }
            cachedP99 = sorted[(count * 99 + 99) / 100 - 1] // nearest rank
            dirty = false
        }
        return cachedP99
    }
}

internal fun decoderFeedDelayMs(
    targetTimeNs: Long,
    nowNs: Long,
    decodeAheadMs: Long = DECODER_FEED_AHEAD_MS,
): Long {
    val waitNs = targetTimeNs - nowNs - decodeAheadMs * 1_000_000L
    return if (waitNs <= 0L) 0L else (waitNs + 999_999L) / 1_000_000L
}

internal fun plannedDecoderQueueDepth(fps: Int, playbackDelayMs: Long): Int =
    (kotlin.math.ceil(
        fps * (playbackDelayMs - DECODER_FEED_AHEAD_MS).coerceAtLeast(0L) / 1_000.0,
    ).toInt() + PlaybackDelayProfile.VIDEO_QUEUE_HEADROOM_FRAMES)

internal const val DECODER_FEED_AHEAD_MS = 80L
internal const val DECODER_FEED_AHEAD_MARGIN_MS = 20L
internal const val DECODER_FEED_AHEAD_SAFETY_MS = 50L
private const val MIN_DECODE_LATENCY_SAMPLES = 8
private const val MAX_TRACKED_DECODER_INPUTS = 64

/** The decoded picture size of a decoder output format (crop rectangle when present), or null. */
internal fun decodedOutputSize(format: MediaFormat): Pair<Int, Int>? = runCatching {
    if (format.containsKey("crop-right") && format.containsKey("crop-left") &&
        format.containsKey("crop-bottom") && format.containsKey("crop-top")
    ) {
        (format.getInteger("crop-right") - format.getInteger("crop-left") + 1) to
            (format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1)
    } else {
        format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT)
    }
}.getOrNull()
