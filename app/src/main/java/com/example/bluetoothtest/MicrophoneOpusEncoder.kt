package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MicrophoneOpusEncoder(
    private val onPacket: (flags: Int, timeUs: Long, bytes: ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val communicationMode: Boolean = false,
    private val bitRate: Int = StreamProtocol.AUDIO_BIT_RATE,
    private val debugReferenceFile: File? = null,
    private val debugReferenceVideoFrames: Long = 0,
    private val debugReferenceVideoFps: Int = 0,
    private val debugReferenceClock: () -> RawReferenceClock? = { null },
    private val debugTraceContext: Context? = null,
    private val debugTraceSession: String? = null,
) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var nativeEncoder: NativeOpusEncoder? = null
    @Volatile private var captureWorker: Thread? = null
    @Volatile private var encoderWorker: Thread? = null
    private val pcmFrames = ArrayBlockingQueue<PcmFrame>(PCM_QUEUE_CAPACITY)
    private val audioEffects = mutableListOf<AudioEffect>()
    private var effectSummary = ""
    private var referenceInput: ReferencePcmInput? = null
    private var debugAudioTrace: DebugAudioTrace? = null

    private data class PcmFrame(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
    )

    @SuppressLint("MissingPermission")
    fun start() {
        check(running.compareAndSet(false, true)) { "Microphone encoder is already running" }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            StreamProtocol.AUDIO_SAMPLE_RATE,
            StreamProtocol.AUDIO_CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BYTES_PER_FRAME)
            // AOSP c2.android.opus.encoder defaults to 10, but media_codecs_google_c2_audio.xml
            // advertises default 5; libopus below 7 skips its speech/music analysis and costs about
            // 0.4-0.6 PESQ-WB on speech-v1 at 24-32 kbps (offline sweep 2026-10-09). Ask for 10.
            setInteger(MediaFormat.KEY_COMPLEXITY, OPUS_COMPLEXITY)
        }
        try {
            check(debugReferenceFile == null || BuildConfig.DEBUG) { "Reference audio is debug-only" }
            referenceInput = debugReferenceFile?.let { file ->
                ReferencePcmInput(file).also { input ->
                    try { input.requireVideoTimeline(debugReferenceVideoFrames, debugReferenceVideoFps) }
                    catch (error: Throwable) { input.close(); throw error }
                }
            }
            val activeNative = if (communicationMode && DebugTunables.callNativeOpus) startNativeEncoder() else null
            nativeEncoder = activeNative
            val encoderName: String
            val activeCodec: MediaCodec?
            if (activeNative != null) {
                encoderName = "libopus ${NativeOpusBridge.nativeOpusVersion()}"
                activeCodec = null
            } else {
                encoderName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format)
                    ?: error("This phone has no Opus audio encoder")
                activeCodec = MediaCodec.createByCodecName(encoderName).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                codec = activeCodec
                if (communicationMode) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK audio_codec: role=encoder | impl=mediacodec | " +
                            "requested=${if (DebugTunables.callNativeOpus) "native" else "mediacodec"} | " +
                            "name=$encoderName | fec=off | plc=n/a | kbps=${bitRate / 1_000}",
                    )
                }
            }
            val startEncoding: () -> Unit = {
                if (activeNative != null) nativeEncodeLoop(activeNative) else encodeLoop(checkNotNull(activeCodec))
            }

            debugAudioTrace = DebugAudioTrace.startIfRequested(debugTraceContext, debugTraceSession, "sender")
            referenceInput?.let { input ->
                onStatus("BENCHMARK reference audio: ${debugReferenceFile?.name}, ${input.sampleCount} samples, PCM16 mono 48000 Hz through $encoderName at ${bitRate / 1_000} kbps; microphone/acoustics unmeasured")
                captureWorker = thread(name = "ReferencePcmCapture") { referenceCaptureLoop(input) }
                encoderWorker = thread(name = "ReferenceOpusEncoder") { startEncoding() }
                return
            }

            val minimumBuffer = AudioRecord.getMinBufferSize(
                StreamProtocol.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minimumBuffer > 0) { "Microphone does not support 48 kHz mono PCM" }
            val activeRecorder = AudioRecord.Builder()
                .setAudioSource(
                    if (communicationMode) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else MediaRecorder.AudioSource.CAMCORDER,
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(StreamProtocol.AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimumBuffer, PCM_BYTES_PER_FRAME * 4))
                .build()
            require(activeRecorder.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone could not be initialized"
            }
            recorder = activeRecorder
            if (communicationMode) enableCallAudioEffects(activeRecorder.audioSessionId)
            activeRecorder.startRecording()
            require(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not start recording"
            }
            onStatus(
                "Microphone audio: Opus mono at ${bitRate / 1_000} kbps " +
                    "via $encoderName" + if (communicationMode) " with call echo control" else "",
            )
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_capture: source=${if (communicationMode) "VOICE_COMMUNICATION" else "CAMCORDER"} | " +
                    "encoder=$encoderName | kbps=${bitRate / 1_000} | " +
                    "complexity=${activeCodec?.let(::encoderComplexity) ?: OPUS_COMPLEXITY.toString()} | " +
                    "effects=${effectSummary.ifEmpty { "none-requested" }}",
            )

            captureWorker = thread(name = "MicrophoneCapture") {
                captureLoop(activeRecorder)
            }
            encoderWorker = thread(name = "MicrophoneOpusEncoder") {
                startEncoding()
            }
        } catch (error: Exception) {
            running.set(false)
            releaseResources()
            throw error
        }
    }

    private fun referenceCaptureLoop(input: ReferencePcmInput) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            var origin: RawReferenceClock? = null
            while (running.get() && origin == null) {
                origin = debugReferenceClock()
                if (origin == null) Thread.sleep(1)
            }
            val clock = origin ?: return
            val originUs = clock.ptsOriginUs
            Log.i("BluetoothVideo", "BENCHMARK reference audio clock: origin_us=$originUs monotonic_origin_ns=${clock.monotonicOriginNs}")
            var observedVideoOrigin = originUs
            var samples = 0L
            while (running.get()) {
                debugReferenceClock()?.let { videoClock ->
                    if (videoClock.ptsOriginUs != observedVideoOrigin) {
                        observedVideoOrigin = videoClock.ptsOriginUs
                        Log.i("BluetoothVideo", "BENCHMARK reference audio independent video epoch: audio_origin_us=$originUs video_origin_us=$observedVideoOrigin; A/V reference pairing requires an explicit phase mapping")
                    }
                }
                val nowNs = System.nanoTime()
                val dueSamples = clock.duePacketSample(nowNs)
                if (dueSamples > samples) {
                    Log.i("BluetoothVideo", "BENCHMARK reference audio input gap: first_sample=$samples end_sample=$dueSamples origin_us=$originUs")
                    samples = dueSamples
                    input.seekSample(samples)
                }
                val ptsUs = originUs + samples * 1_000_000L / StreamProtocol.AUDIO_SAMPLE_RATE
                val remainingNs = clock.packetDeadlineNs(samples) - nowNs
                if (remainingNs > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(minOf(remainingNs, 20_000_000L))
                    continue
                }
                val bytes = ByteArray(PCM_BYTES_PER_FRAME)
                input.readPacket(bytes)
                debugAudioTrace?.record("reference_input", ptsUs, bytes)
                val frame = PcmFrame(bytes, ptsUs)
                if (!pcmFrames.offer(frame)) {
                    pcmFrames.poll()?.let { Log.i("BluetoothVideo", "BENCHMARK reference audio queue drop: dropped_pts_us=${it.presentationTimeUs}") }
                    if (!pcmFrames.offer(frame)) Log.i("BluetoothVideo", "BENCHMARK reference audio queue rejected: pts_us=$ptsUs sample=$samples")
                }
                samples += SAMPLES_PER_FRAME
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (error: Exception) { if (running.get()) fail(error) }
        finally { captureWorker = null }
    }

    private fun captureLoop(activeRecorder: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var firstPresentationTimeUs = 0L
        var capturedSamples = 0L
        val captureTimestamp = AudioTimestamp()
        var captureTimestampLogged = false
        var captureTimestampAttempts = 0
        try {
            while (running.get()) {
                val bytes = ByteArray(PCM_BYTES_PER_FRAME)
                var frameBytes = 0
                while (running.get() && frameBytes < PCM_BYTES_PER_FRAME) {
                    val bytesRead = activeRecorder.read(
                        bytes,
                        frameBytes,
                        PCM_BYTES_PER_FRAME - frameBytes,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (bytesRead < 0) error("Microphone read failed ($bytesRead)")
                    frameBytes += bytesRead
                }
                if (frameBytes != PCM_BYTES_PER_FRAME) continue
                if (firstPresentationTimeUs == 0L) {
                    firstPresentationTimeUs =
                        SystemClock.elapsedRealtimeNanos() / 1_000L - FRAME_DURATION_US
                }
                val presentationTimeUs = firstPresentationTimeUs +
                    capturedSamples * 1_000_000L / StreamProtocol.AUDIO_SAMPLE_RATE
                if (!captureTimestampLogged && captureTimestampAttempts < 20) {
                    val status = runCatching {
                        activeRecorder.getTimestamp(captureTimestamp, AudioTimestamp.TIMEBASE_BOOTTIME)
                    }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
                    captureTimestampAttempts++
                    if (status == AudioRecord.SUCCESS) {
                        val hardwareCaptureNs = captureTimestamp.nanoTime +
                            (capturedSamples - captureTimestamp.framePosition) * 1_000_000_000L / StreamProtocol.AUDIO_SAMPLE_RATE
                        Log.i("BluetoothVideo", "Microphone clock: BOOTTIME frame=${captureTimestamp.framePosition}, nano=${captureTimestamp.nanoTime}, read sample=$capturedSamples, assigned-minus-hardware=${(presentationTimeUs * 1_000L - hardwareCaptureNs) / 1_000_000.0} ms")
                        captureTimestampLogged = true
                    } else if (captureTimestampAttempts == 1) {
                        Log.i("BluetoothVideo", "Microphone hardware timestamp unavailable (status $status); waiting for first valid BOOTTIME sample")
                    } else if (captureTimestampAttempts == 20) {
                        Log.i("BluetoothVideo", "Microphone hardware timestamp unavailable after 20 startup samples (status $status); stopping diagnostic probes")
                    }
                }
                capturedSamples += frameBytes / BYTES_PER_SAMPLE
                val frame = PcmFrame(bytes, presentationTimeUs)
                debugAudioTrace?.record("microphone_input", presentationTimeUs, bytes)
                if (!pcmFrames.offer(frame)) {
                    pcmFrames.poll()
                    pcmFrames.offer(frame)
                }
            }
        } catch (error: Exception) {
            fail(error)
        } finally {
            captureWorker = null
        }
    }

    private fun encodeLoop(activeCodec: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var codecConfigSent = false
        var pendingFrame: PcmFrame? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                if (pendingFrame == null) {
                    pendingFrame = pcmFrames.poll(ACTIVE_POLL_MS, TimeUnit.MILLISECONDS)
                }
                val frame = pendingFrame
                if (frame != null) {
                    val inputIndex = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(activeCodec.getInputBuffer(inputIndex))
                        input.clear()
                        require(input.remaining() >= frame.bytes.size) {
                            "Opus encoder input is smaller than one audio frame"
                        }
                        input.put(frame.bytes)
                        activeCodec.queueInputBuffer(
                            inputIndex,
                            0,
                            frame.bytes.size,
                            frame.presentationTimeUs,
                            0,
                        )
                        pendingFrame = null
                    }
                }

                while (running.get()) {
                    val outputIndex = activeCodec.dequeueOutputBuffer(info, 0)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!codecConfigSent) {
                                val config = activeCodec.outputFormat.getByteBuffer("csd-0")
                                    ?.duplicate()
                                    ?: error("Opus encoder did not provide codec configuration")
                                val bytes = ByteArray(config.remaining()).also(config::get)
                                onPacket(
                                    StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG,
                                    0,
                                    bytes,
                                )
                                codecConfigSent = true
                            }
                        }
                        outputIndex >= 0 -> {
                            val output = checkNotNull(activeCodec.getOutputBuffer(outputIndex))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also(output::get)
                            val isConfiguration =
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (isConfiguration && !codecConfigSent) {
                                onPacket(
                                    StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG,
                                    info.presentationTimeUs,
                                    bytes,
                                )
                                codecConfigSent = true
                            } else if (!isConfiguration && bytes.isNotEmpty()) {
                                check(codecConfigSent) { "Opus audio arrived before codec configuration" }
                                onPacket(StreamProtocol.FLAG_AUDIO, info.presentationTimeUs, bytes)
                            }
                            activeCodec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            fail(error)
        } finally {
            encoderWorker = null
        }
    }

    /**
     * Experimental native libopus call encoder (DEBUG `test_call_native_opus`). Null, with the reason
     * logged as `impl=mediacodec | requested=native`, when the library or encoder is unavailable, so
     * the call keeps its audio and the effective codec stays visible.
     */
    private fun startNativeEncoder(): NativeOpusEncoder? {
        val settings = DebugTunables.nativeOpusSettings()
        val failure = NativeOpusBridge.loadError ?: run {
            val encoder = runCatching {
                NativeOpusEncoder(
                    StreamProtocol.AUDIO_SAMPLE_RATE,
                    StreamProtocol.AUDIO_CHANNEL_COUNT,
                    bitRate,
                    OPUS_COMPLEXITY,
                    settings,
                )
            }
            encoder.getOrNull()?.let { active ->
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK audio_codec: role=encoder | impl=native | requested=native | " +
                        "name=libopus ${NativeOpusBridge.nativeOpusVersion()} | application=voip | " +
                        "fec=${if (settings.fec) "on" else "off"} | loss_pct=${settings.lossPercent} | " +
                        "dtx=${if (settings.dtx) "on" else "off"} | plc=n/a | kbps=${bitRate / 1_000} | " +
                        "complexity=$OPUS_COMPLEXITY | vbr=constrained | frame_ms=${StreamProtocol.AUDIO_PACKET_DURATION_MS} | " +
                        "pre_skip=${active.lookaheadSamples}",
                )
                return active
            }
            encoder.exceptionOrNull()
        }
        Log.w(
            "BluetoothVideo",
            "BENCHMARK audio_codec: role=encoder | impl=mediacodec | requested=native | " +
                "reason=native_unavailable | error=${failure?.message ?: failure?.javaClass?.simpleName}",
        )
        return null
    }

    private fun nativeEncodeLoop(encoder: NativeOpusEncoder) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var packets = 0L
        var dtxSkipped = 0L
        try {
            onPacket(
                StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG,
                0,
                NativeOpusConfig.codec2Block(
                    StreamProtocol.AUDIO_CHANNEL_COUNT,
                    encoder.lookaheadSamples,
                    StreamProtocol.AUDIO_SAMPLE_RATE,
                ),
            )
            while (running.get()) {
                val frame = pcmFrames.poll(ACTIVE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                val bytes = encoder.encode(frame.bytes, SAMPLES_PER_FRAME)
                // DTX (sub-switch, default off): a 1-2 byte packet means "no transmission needed".
                if (encoder.settings.dtx && bytes.size <= DTX_SILENT_PACKET_BYTES) {
                    dtxSkipped++
                    continue
                }
                if (bytes.isNotEmpty()) {
                    onPacket(StreamProtocol.FLAG_AUDIO, frame.presentationTimeUs, bytes)
                    packets++
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            fail(error)
        } finally {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_codec_stats: role=encoder | impl=native | packets=$packets | dtx_skipped=$dtxSkipped",
            )
            encoderWorker = null
        }
    }

    private fun fail(error: Throwable) {
        if (!running.getAndSet(false)) return
        runCatching { recorder?.stop() }
        captureWorker?.interrupt()
        encoderWorker?.interrupt()
        onError(error.message ?: error.javaClass.simpleName)
    }

    override fun close() {
        running.set(false)
        runCatching { recorder?.stop() }
        captureWorker?.interrupt()
        encoderWorker?.interrupt()
        runCatching { captureWorker?.join(1_000) }
        runCatching { encoderWorker?.join(1_000) }
        pcmFrames.clear()
        releaseResources()
    }

    @Synchronized
    private fun releaseResources() {
        audioEffects.forEach { effect -> runCatching { effect.release() } }
        audioEffects.clear()
        runCatching { referenceInput?.close() }
        referenceInput = null
        debugAudioTrace?.close()
        debugAudioTrace = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { nativeEncoder?.close() }
        nativeEncoder = null
    }

    /**
     * The platform voice-communication pre-processing (CDD 5.4.4: an AEC SHOULD be applied to
     * VOICE_COMMUNICATION capture and is STRONGLY RECOMMENDED to be exposed via
     * AcousticEchoCanceler.isAvailable()). Each effect's availability, enabled state after the request
     * and implementor go to the `BENCHMARK audio_capture` line, so per-OEM behaviour is evidence, not
     * an assumption. No family-specific policy: no vendor documents a reason to skip one.
     */
    private fun enableCallAudioEffects(audioSessionId: Int) {
        val summary = mutableListOf<String>()
        fun add(name: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) {
                summary += "$name:unavailable"
                return
            }
            val effect = runCatching { create() }.getOrNull()
            if (effect == null) {
                summary += "$name:create-failed"
                return
            }
            runCatching { effect.enabled = true }
            audioEffects += effect
            val enabled = runCatching { effect.enabled }.getOrDefault(false)
            val implementor = runCatching { effect.descriptor.implementor }.getOrNull()?.replace('|', '/') ?: "?"
            summary += "$name:${if (enabled) "on" else "off"}($implementor)"
        }
        add("aec", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(audioSessionId) }
        add("ns", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(audioSessionId) }
        add("agc", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(audioSessionId) }
        effectSummary = summary.joinToString(",")
    }

    private fun encoderComplexity(activeCodec: MediaCodec): String = runCatching {
        val format = activeCodec.inputFormat
        if (format.containsKey(MediaFormat.KEY_COMPLEXITY)) format.getInteger(MediaFormat.KEY_COMPLEXITY).toString()
        else "requested-$OPUS_COMPLEXITY"
    }.getOrDefault("requested-$OPUS_COMPLEXITY")

    private companion object {
        const val FRAME_DURATION_US = StreamProtocol.AUDIO_PACKET_DURATION_MS * 1_000L
        const val SAMPLES_PER_FRAME = StreamProtocol.AUDIO_SAMPLE_RATE *
            StreamProtocol.AUDIO_PACKET_DURATION_MS / 1_000
        const val BYTES_PER_SAMPLE = 2 * StreamProtocol.AUDIO_CHANNEL_COUNT
        const val PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE
        const val PCM_QUEUE_CAPACITY = 5
        const val OPUS_COMPLEXITY = 10
        const val ACTIVE_POLL_MS = 20L
        const val INPUT_TIMEOUT_US = 2_000L
        const val DTX_SILENT_PACKET_BYTES = 2
    }
}
