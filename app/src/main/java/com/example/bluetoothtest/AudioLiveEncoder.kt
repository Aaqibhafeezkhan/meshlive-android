package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Microphone to 20 ms Opus frames for the audio-only live stream (Design AL). A separate class from the call
 * and video-stream encoder (`MicrophoneOpusEncoder`) on purpose: it has its own native entry point and
 * switch, a radio-appropriate source and no call echo control, and nothing here changes calls or video streams.
 *
 *  - Capture: `AudioSource.MIC` with the platform noise suppressor and gain control when present. No echo
 *    canceller (the broadcaster does not play its own stream). Acoustic quality stays UNMEASURED.
 *  - Encoder: libopus through [NativeOpusEncoder] with the audio-only application (Voice and Low data:
 *    VOIP with a speech hint; Music: AUDIO), constrained VBR, complexity 10; or MediaCodec
 *    `c2.android.opus.encoder` when `test_audio_live_native_opus` is off or libopus is unavailable.
 *  - DEBUG only: [debugReferenceFile] replaces the microphone with reference PCM through the real encoder.
 *
 * One instance runs once: pause releases it (the Android microphone indicator goes off) and resume builds a
 * new one. [onFrame] receives each encoded frame in order; the source stamps and batches them.
 */
class AudioLiveEncoder(
    private val preset: Int,
    private val useNative: Boolean,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
    private val debugReferenceFile: File? = null,
) : AudioLiveEncoderPort {
    private val running = AtomicBoolean(false)
    private val kbps = AudioLive.presetKbps(preset)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var nativeEncoder: NativeOpusEncoder? = null
    @Volatile private var captureWorker: Thread? = null
    private var effectDetail = ""
    @Volatile private var encoderWorker: Thread? = null
    private val pcmFrames = ArrayBlockingQueue<ByteArray>(PCM_QUEUE_CAPACITY)
    private val audioEffects = mutableListOf<AudioEffect>()
    private var referenceInput: ReferencePcmInput? = null

    /** Which encoder runs ("libopus 1.x" or the MediaCodec name), for the status line and the benchmark line. */
    @Volatile override var encoderName: String = ""
        private set

    @SuppressLint("MissingPermission")
    override fun start() {
        check(running.compareAndSet(false, true)) { "The audio encoder is already running" }
        try {
            check(debugReferenceFile == null || BuildConfig.DEBUG) { "Reference audio is debug-only" }
            referenceInput = debugReferenceFile?.let { ReferencePcmInput(it) }
            val native = if (useNative) startNativeEncoder() else null
            nativeEncoder = native
            val mediaCodec = if (native == null) startMediaCodecEncoder() else null
            Log.i(
                "BluetoothVideo",
                AudioLiveCodecChoice.line(
                    "encoder",
                    if (native != null) "native" else "mediacodec",
                    if (useNative) "native" else "mediacodec",
                    encoderName,
                    reason = if (native == null && useNative) "native_unavailable" else null,
                ),
            )
            encoderWorker = thread(name = "AudioLiveEncoder") {
                if (native != null) nativeEncodeLoop(native) else encodeLoop(checkNotNull(mediaCodec))
            }
            val reference = referenceInput
            if (reference != null) {
                captureWorker = thread(name = "AudioLiveReferenceCapture") { referenceCaptureLoop(reference) }
                return
            }
            val minimumBuffer = AudioRecord.getMinBufferSize(
                StreamProtocol.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            MinBufferCheck.captureProblem(minimumBuffer)?.let { error(it) }
            val activeRecorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(StreamProtocol.AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimumBuffer, PCM_BYTES_PER_FRAME * 8))
                .build()
            require(activeRecorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized" }
            recorder = activeRecorder
            val effects = enableCaptureEffects(activeRecorder.audioSessionId)
            if (!AudioSwitches.policyActive(EFFECT_LOGGING_ENTRY_ID)) effectDetail = ""
            activeRecorder.startRecording()
            require(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not start recording"
            }
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_live_capture: source=MIC | encoder=$encoderName | kbps=$kbps | preset=$preset | " +
                    "complexity=$OPUS_COMPLEXITY | effects=${effects.ifEmpty { "none-requested" }} | " +
                    "effectState=${effectDetail.ifEmpty { "none-requested" }}",
            )
            captureWorker = thread(name = "AudioLiveCapture") { captureLoop(activeRecorder) }
        } catch (error: Exception) {
            running.set(false)
            releaseResources()
            throw error
        }
    }

    private fun startNativeEncoder(): NativeOpusEncoder? {
        val failure = NativeOpusBridge.loadError ?: run {
            val encoder = runCatching {
                NativeOpusEncoder(
                    StreamProtocol.AUDIO_SAMPLE_RATE,
                    StreamProtocol.AUDIO_CHANNEL_COUNT,
                    kbps * 1_000,
                    OPUS_COMPLEXITY,
                    NativeOpusSettings(
                        fec = AudioLiveTunables.fec,
                        lossPercent = NativeOpusSettings.DEFAULT_LOSS_PERCENT,
                        dtx = AudioLiveTunables.dtx,
                    ),
                    application = if (preset == AudioLive.PRESET_MUSIC) NativeOpusEncoder.APPLICATION_AUDIO
                    else NativeOpusEncoder.APPLICATION_VOIP,
                )
            }
            encoder.getOrNull()?.let { active ->
                encoderName = "libopus ${NativeOpusBridge.nativeOpusVersion()}"
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK audio_live_codec: role=encoder | impl=native | name=$encoderName | " +
                        "application=${if (preset == AudioLive.PRESET_MUSIC) "audio" else "voip+speech"} | " +
                        "kbps=$kbps | vbr=constrained | complexity=$OPUS_COMPLEXITY | frame_ms=${AudioLive.FRAME_MS} | " +
                        "fec=${if (AudioLiveTunables.fec) "on" else "off"} | dtx=${if (AudioLiveTunables.dtx) "on" else "off"} | " +
                        "pre_skip=${active.lookaheadSamples}",
                )
                return active
            }
            encoder.exceptionOrNull()
        }
        Log.w(
            "BluetoothVideo",
            "BENCHMARK audio_live_codec: role=encoder | impl=mediacodec | requested=native | reason=native_unavailable | " +
                "error=${failure?.message ?: failure?.javaClass?.simpleName}",
        )
        return null
    }

    private fun startMediaCodecEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS, StreamProtocol.AUDIO_SAMPLE_RATE, StreamProtocol.AUDIO_CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1_000)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BYTES_PER_FRAME)
            setInteger(MediaFormat.KEY_COMPLEXITY, OPUS_COMPLEXITY)
        }
        val name = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format)
            ?: error("This phone has no Opus audio encoder")
        encoderName = name
        return MediaCodec.createByCodecName(name).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
            codec = this
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_live_codec: role=encoder | impl=mediacodec | name=$name | kbps=$kbps | " +
                    "vbr=vbr | complexity=$OPUS_COMPLEXITY | frame_ms=${AudioLive.FRAME_MS}",
            )
        }
    }

    private fun referenceCaptureLoop(input: ReferencePcmInput) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            val startNs = System.nanoTime()
            var frames = 0L
            while (running.get()) {
                val dueNs = startNs + frames * FRAME_NS
                val waitNs = dueNs - System.nanoTime()
                if (waitNs > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(waitNs)
                    continue
                }
                val bytes = ByteArray(PCM_BYTES_PER_FRAME)
                input.readPacket(bytes)
                offerPcm(bytes)
                frames++
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (running.get()) fail(error)
        } finally {
            captureWorker = null
        }
    }

    private fun captureLoop(activeRecorder: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            while (running.get()) {
                val bytes = ByteArray(PCM_BYTES_PER_FRAME)
                var filled = 0
                while (running.get() && filled < PCM_BYTES_PER_FRAME) {
                    val read = activeRecorder.read(bytes, filled, PCM_BYTES_PER_FRAME - filled, AudioRecord.READ_BLOCKING)
                    if (read < 0) error("Microphone read failed ($read)")
                    filled += read
                }
                if (filled == PCM_BYTES_PER_FRAME) offerPcm(bytes)
            }
        } catch (error: Exception) {
            if (running.get()) fail(error)
        } finally {
            captureWorker = null
        }
    }

    private fun offerPcm(bytes: ByteArray) {
        onLevel(AudioLiveLevel.rms(bytes))
        if (!pcmFrames.offer(bytes)) {
            pcmFrames.poll()
            pcmFrames.offer(bytes)
        }
    }

    private fun nativeEncodeLoop(encoder: NativeOpusEncoder) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var packets = 0L
        var dtxSkipped = 0L
        try {
            onConfig(
                NativeOpusConfig.codec2Block(
                    StreamProtocol.AUDIO_CHANNEL_COUNT, encoder.lookaheadSamples, StreamProtocol.AUDIO_SAMPLE_RATE,
                ),
            )
            while (running.get()) {
                val pcm = pcmFrames.poll(ACTIVE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                val bytes = encoder.encode(pcm, AudioLive.FRAME_SAMPLES)
                // DTX (Experimental): a 1-2 byte packet means "no transmission needed"; the cadence changes.
                if (encoder.settings.dtx && bytes.size <= DTX_SILENT_PACKET_BYTES) {
                    dtxSkipped++
                    continue
                }
                if (bytes.isNotEmpty()) {
                    onFrame(bytes)
                    packets++
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (running.get()) fail(error)
        } finally {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK audio_live_codec_stats: role=encoder | impl=native | packets=$packets | dtx_skipped=$dtxSkipped",
            )
            encoderWorker = null
        }
    }

    private fun encodeLoop(activeCodec: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var configSent = false
        var pending: ByteArray? = null
        var inputUs = 0L
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                if (pending == null) pending = pcmFrames.poll(ACTIVE_POLL_MS, TimeUnit.MILLISECONDS)
                val frame = pending
                if (frame != null) {
                    val inputIndex = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(activeCodec.getInputBuffer(inputIndex))
                        input.clear()
                        input.put(frame)
                        activeCodec.queueInputBuffer(inputIndex, 0, frame.size, inputUs, 0)
                        inputUs += AudioLive.FRAME_US
                        pending = null
                    }
                }
                while (running.get()) {
                    val outputIndex = activeCodec.dequeueOutputBuffer(info, 0)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!configSent) {
                                val config = activeCodec.outputFormat.getByteBuffer("csd-0")?.duplicate()
                                    ?: error("Opus encoder did not provide codec configuration")
                                onConfig(ByteArray(config.remaining()).also(config::get))
                                configSent = true
                            }
                        }
                        outputIndex >= 0 -> {
                            val output = checkNotNull(activeCodec.getOutputBuffer(outputIndex))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also(output::get)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                if (!configSent) {
                                    onConfig(bytes)
                                    configSent = true
                                }
                            } else if (bytes.isNotEmpty()) {
                                check(configSent) { "Opus audio arrived before codec configuration" }
                                onFrame(bytes)
                            }
                            activeCodec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (running.get()) fail(error)
        } finally {
            encoderWorker = null
        }
    }

    private fun fail(error: Throwable) {
        if (!running.getAndSet(false)) return
        AudioLiveLog.failure("encoder:${Thread.currentThread().name}", error)
        runCatching { recorder?.stop() }
        captureWorker?.interrupt()
        encoderWorker?.interrupt()
        onError(error.message ?: error.javaClass.simpleName)
    }

    override fun close() {
        running.set(false)
        // A timed, serialized audio command; `test_audio_off_main` runs it off the caller's main thread.
        AudioCommandQueue.shared.offload("audio_live_encoder_close") {
            runCatching { recorder?.stop() }
            captureWorker?.interrupt()
            encoderWorker?.interrupt()
            runCatching { captureWorker?.join(1_000) }
            runCatching { encoderWorker?.join(1_000) }
            pcmFrames.clear()
            releaseResources()
        }
    }

    @Synchronized
    private fun releaseResources() {
        audioEffects.forEach { effect -> runCatching { effect.release() } }
        audioEffects.clear()
        runCatching { referenceInput?.close() }
        referenceInput = null
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
     * Platform noise suppression and gain control when present (no echo canceller; see class note). The default
     * state of each effect is read before it is enabled and logged in [effectDetail] (U-02); the returned summary
     * keeps its former format.
     */
    private fun enableCaptureEffects(audioSessionId: Int): String {
        val outcomes = mutableListOf<EffectOutcome>()
        fun add(name: String, available: Boolean, create: () -> AudioEffect?) {
            val (outcome, handle) = AudioEffectProbe.enable(name, available) { create()?.let(::PlatformEffectHandle) }
            outcomes += outcome
            (handle as? PlatformEffectHandle)?.let { audioEffects += it.effect }
        }
        add("ns", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(audioSessionId) }
        add("agc", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(audioSessionId) }
        effectDetail = AudioEffectProbe.detail(outcomes)
        return outcomes.joinToString(",") { outcome ->
            when {
                !outcome.available -> "${outcome.name}:unavailable"
                !outcome.created -> "${outcome.name}:create-failed"
                else -> "${outcome.name}:${if (outcome.finalEnabled == true) "on" else "off"}"
            }
        }
    }

    companion object {
        private const val FRAME_NS = AudioLive.FRAME_US * 1_000L
        private const val BYTES_PER_SAMPLE = 2 * StreamProtocol.AUDIO_CHANNEL_COUNT
        private const val PCM_BYTES_PER_FRAME = AudioLive.FRAME_SAMPLES * BYTES_PER_SAMPLE
        private const val PCM_QUEUE_CAPACITY = 8
        private const val OPUS_COMPLEXITY = 10
        private const val ACTIVE_POLL_MS = 20L
        private const val INPUT_TIMEOUT_US = 2_000L
        private const val DTX_SILENT_PACKET_BYTES = 2
    }
}
