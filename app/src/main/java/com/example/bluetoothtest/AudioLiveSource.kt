package com.example.bluetoothtest

import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * The broadcaster side of an audio-only live stream (Design AL): microphone, Opus, batching, source
 * signatures and the hand-off to [BluetoothVideoBroadcaster.sendAudioLiveRecord]. The activity owns one for
 * the life of a radio broadcast; it replaces the camera and microphone encoders of a video stream and does
 * not touch them.
 *
 * Life cycle: [prepare] (off the main thread: the identity key certifies a fresh stream key once), [start]
 * once the first listener is connected, [mute]/[unmute] (microphone stays open), [pause]/[resume] (the
 * microphone is released and the lifecycle state travels through the broadcaster), [stop].
 * Any capture, codec or service failure ends the broadcast visibly through [onError]; a silent "live"
 * stream is never kept.
 */
class AudioLiveSource private constructor(
    val sendConfig: AudioLiveSendConfig,
    private val useNative: Boolean,
    private val debugReferenceFile: File?,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
    private val encoderFactory: AudioLiveEncoderFactory,
    private val nowUs: () -> Long,
) : Closeable {
    private var broadcaster: AudioLiveSink? = null
    private var encoder: AudioLiveEncoderPort? = null
    private var batcher: AudioLiveBatcher? = null
    private var signedConfig: ByteArray? = null
    private var stopped = false

    val preset: Int get() = sendConfig.preset

    /** Whether Mute is on (it survives a pause and resume). */
    val muted: Boolean get() = mutedWhileIdle

    @Volatile private var mutedWhileIdle = false

    private var lastConfigRecord: AudioLiveSignedRecord? = null
    private var userPaused = false

    /**
     * Connects this source to [target]: repeats the signed Opus configuration (a new broadcaster after every
     * listener left has none) and starts capture unless the user paused. Called when the first listener of
     * a broadcaster is connected; the signer, stream ID and sequence continue across broadcasters.
     */
    @Synchronized
    fun attach(target: AudioLiveSink) {
        if (stopped) return
        broadcaster = target
        lastConfigRecord?.let(target::sendAudioLiveRecord)
        if (!userPaused && encoder == null) {
            // A microphone or codec that cannot start ends the broadcast visibly, with its stack in the log.
            runCatching { startCapture(initial = lastConfigRecord == null) }.onFailure {
                fail(it.message ?: it.javaClass.simpleName, cause = it)
            }
        }
    }

    /** Every listener left: release the microphone (no one hears it) but keep the stream key. */
    fun detach() {
        synchronized(this) { broadcaster = null }
        releaseCapture()
    }

    @Synchronized
    private fun startCapture(initial: Boolean) {
        if (stopped) return
        val target = checkNotNull(broadcaster)
        val newBatcher = AudioLiveBatcher(sendConfig.framesPerRecord) { flags, pts, body ->
            // One signature per record, shared by every listener's queue.
            val signed = sendConfig.signer.sign(flags, pts, body)
            if (AudioLiveTunables.unitDigests) {
                val seq = java.nio.ByteBuffer.wrap(signed.payload, 0, AudioLive.SEQ_BYTES).int.toLong() and 0xffff_ffffL
                AudioLiveLog.info(
                    AudioLiveDigest.line(
                        "tx", sendConfig.signer.streamId.toString().take(8), seq, pts,
                        AudioLiveDigest.unitsOf(flags, pts, body),
                    ),
                )
            }
            target.sendAudioLiveRecord(signed)
        }
        newBatcher.anchor(nowUs())
        if (mutedWhileIdle) newBatcher.mute()
        batcher = newBatcher
        val newEncoder = encoderFactory.create(
            preset,
            useNative,
            { config -> onConfiguration(config) },
            { frame -> onEncodedFrame(newBatcher, frame) },
            { level -> if (!newBatcher.muted) onLevel(level) else onLevel(0f) },
            { message -> fail(message) }, // the encoder logged the stack and the BENCHMARK failure line
            debugReferenceFile,
        )
        encoder = newEncoder
        try {
            newEncoder.start()
        } catch (error: Exception) {
            encoder = null
            batcher = null
            throw error
        }
        AudioLiveLog.info("BENCHMARK audio_live_source: event=${if (initial) "start" else "resume"} | preset=$preset | " +
                "kbps=${sendConfig.kbps} | frames=${sendConfig.framesPerRecord} | encoder=${newEncoder.encoderName} | " +
                "ageBound=${sendConfig.ageBound} | maxChildren=${sendConfig.maxChildren}",
        )
    }

    private fun onConfiguration(config: ByteArray) {
        val target = broadcaster ?: return
        synchronized(this) {
            // A resume after a pause builds a new encoder with the same configuration: sign it once.
            if (signedConfig?.contentEquals(config) == true) return
            if (!AudioLiveOpus.isMonoConfig(config)) {
                fail("The Opus encoder produced a configuration this stream cannot carry.", log = true)
                return
            }
            signedConfig = config.copyOf()
        }
        val record = sendConfig.signer.sign(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0, config)
        synchronized(this) { lastConfigRecord = record }
        target.sendAudioLiveRecord(record)
    }

    private fun onEncodedFrame(forBatcher: AudioLiveBatcher, frame: ByteArray) {
        if (!AudioLiveOpus.isSingle20msMonoFrame(frame)) {
            // Receivers reject anything else; send nothing the stream rules forbid.
            fail("The Opus encoder produced a packet that is not one 20 ms mono frame (first byte ${frame.firstOrNull()}).", log = true)
            return
        }
        forBatcher.add(frame)
    }

    /** Mute keeps the stream and its cadence running and sends silence; nothing recorded before the tap goes out. */
    @Synchronized
    fun mute() {
        mutedWhileIdle = true
        batcher?.mute()
        val dropped = broadcaster?.discardQueuedAudio() ?: 0
        AudioLiveLog.info("BENCHMARK audio_live_source: event=mute | discardedQueuedRecords=$dropped")
    }

    @Synchronized
    fun unmute() {
        mutedWhileIdle = false
        batcher?.unmute()
        AudioLiveLog.info("BENCHMARK audio_live_source: event=unmute")
    }

    /** Pause releases the microphone (the Android indicator goes off) after sending the partial batch. */
    fun pause() {
        synchronized(this) { userPaused = true }
        releaseCapture()
        AudioLiveLog.info("BENCHMARK audio_live_source: event=pause")
    }

    fun resume() {
        synchronized(this) {
            userPaused = false
            if (stopped || broadcaster == null || encoder != null) return
            runCatching { startCapture(initial = false) }.onFailure {
                fail(it.message ?: it.javaClass.simpleName, cause = it)
            }
        }
    }

    private fun releaseCapture() {
        val (oldBatcher, oldEncoder) = synchronized(this) {
            (batcher to encoder).also {
                batcher = null
                encoder = null
            }
        }
        oldBatcher?.flush()
        oldEncoder?.close() // joins the capture threads outside the lock
        onLevel(0f)
    }

    /** The foreground service no longer holds the microphone type: a background recording would be silent. */
    fun onMicrophoneServiceChanged(holdsMicrophoneType: Boolean) {
        if (AudioLiveMicrophonePolicy.mustStop(holdsMicrophoneType)) fail(AudioLiveMicrophonePolicy.ERROR_TEXT, log = true)
    }

    private fun fail(message: String, log: Boolean = false, cause: Throwable? = null) {
        if (log || cause != null) AudioLiveLog.failure("source", cause, message)
        val wasStopped = synchronized(this) {
            val was = stopped
            stopped = true
            was
        }
        if (wasStopped) return
        AudioLiveLog.info("BENCHMARK audio_live_source: event=error | message=${message.take(120)}")
        onError(message)
    }

    fun stop() {
        val (oldBatcher, oldEncoder) = synchronized(this) {
            stopped = true
            (batcher to encoder).also {
                batcher = null
                encoder = null
            }
        }
        oldBatcher?.flush()
        oldEncoder?.close()
        onLevel(0f)
    }

    override fun close() = stop()

    companion object {
        /**
         * Builds the source: generates the per-stream key and certifies it with this phone's identity key
         * (one Keystore signature). Call off the main thread. [chosenPreset] is the user's choice; a
         * DEBUG override may replace it.
         */
        fun prepare(
            chosenPreset: Int,
            onLevel: (Float) -> Unit,
            onError: (String) -> Unit,
            debugReferenceFile: File? = null,
        ): AudioLiveSource {
            val preset = AudioLiveTunables.effectivePreset(chosenPreset)
            val signer = AudioLiveSigner(AndroidNodeIdentityStore().getOrCreate(), UUID.randomUUID(), preset)
            val config = AudioLiveSendConfig(
                signer = signer,
                ageBound = AudioLiveTunables.ageBound,
                maxChildren = AudioLiveTunables.maxChildren,
                framesPerRecord = AudioLiveTunables.batchFrames,
            )
            return AudioLiveSource(
                config, AudioLiveTunables.nativeOpus, debugReferenceFile, onLevel, onError,
                AudioLiveEncoderFactory.DEFAULT, { SystemClock.elapsedRealtimeNanos() / 1_000L },
            )
        }

        /** Test seam: a fake encoder and a fake clock; nothing Android is touched. */
        internal fun forTest(
            sendConfig: AudioLiveSendConfig,
            encoderFactory: AudioLiveEncoderFactory,
            onLevel: (Float) -> Unit = {},
            onError: (String) -> Unit = {},
            nowUs: () -> Long = { System.nanoTime() / 1_000L },
        ): AudioLiveSource = AudioLiveSource(sendConfig, false, null, onLevel, onError, encoderFactory, nowUs)
    }
}

/** Builds the microphone and encoder of an [AudioLiveSource]; the app's [AudioLiveEncoder] by default. */
fun interface AudioLiveEncoderFactory {
    fun create(
        preset: Int,
        useNative: Boolean,
        onConfig: (ByteArray) -> Unit,
        onFrame: (ByteArray) -> Unit,
        onLevel: (Float) -> Unit,
        onError: (String) -> Unit,
        reference: File?,
    ): AudioLiveEncoderPort

    companion object {
        val DEFAULT = AudioLiveEncoderFactory { preset, useNative, onConfig, onFrame, onLevel, onError, reference ->
            AudioLiveEncoder(preset, useNative, onConfig, onFrame, onLevel, onError, reference)
        }
    }
}
