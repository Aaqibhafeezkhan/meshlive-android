package com.example.bluetoothtest

import android.media.MediaRecorder

/*
 * Pure, JVM-tested audio capture rules (batch U). The Android-bound code in MicrophoneOpusEncoder,
 * AudioLiveEncoder and PrivateCallAudioSession only gathers platform facts and acts on these decisions.
 * Sources: docs of the registry entries in AudioPolicyEntries.
 */

// ---- Effect default-state logging (U-02) ------------------------------------------------------------

/** UUIDs of the AOSP software effects (WebRtcAudioEffects excludes them; MeshLive has no better AEC, so it keeps them). */
internal object AudioEffectUuids {
    const val AOSP_SOFTWARE_AEC = "bb392ec0-8d4d-11e0-a896-0002a5d5c51b"
    const val AOSP_SOFTWARE_NS = "c06c8400-8e06-11e0-9cb6-0002a5d5c51b"

    fun isAospSoftware(uuid: String?): Boolean =
        uuid != null && (uuid.equals(AOSP_SOFTWARE_AEC, ignoreCase = true) || uuid.equals(AOSP_SOFTWARE_NS, ignoreCase = true))
}

/** What the probe needs from an AudioEffect; an adapter wraps the real class so the logic is JVM-testable. */
internal interface EffectHandle {
    /** Descriptor uuid (the implementation, not the type). */
    fun uuid(): String?
    fun implementor(): String?
    fun hasControl(): Boolean?
    fun enabled(): Boolean?

    /** AudioEffect.setEnabled; returns the status code (SUCCESS = 0). */
    fun setEnabled(enabled: Boolean): Int?
    fun release()
}

internal data class EffectOutcome(
    val name: String,
    val available: Boolean,
    val created: Boolean,
    /** getEnabled() right after create() and BEFORE any setEnabled(true); null when unreadable. */
    val defaultEnabled: Boolean?,
    val uuid: String?,
    val implementor: String?,
    val hasControl: Boolean?,
    val setEnabledCode: Int?,
    val finalEnabled: Boolean?,
) {
    val aospSoftware: Boolean get() = AudioEffectUuids.isAospSoftware(uuid)

    /** The former `effects=` summary item, unchanged for existing readers: `aec:on(Google)`. */
    fun summary(): String = when {
        !available -> "$name:unavailable"
        !created -> "$name:create-failed"
        else -> "$name:${if (finalEnabled == true) "on" else "off"}(${(implementor ?: "?").replace('|', '/')})"
    }

    /** The new `effectState=` item: `aec{default=1,final=1,set=0,ctl=1,sw=0,uuid=...}`. */
    fun detail(): String {
        if (!available || !created) return "$name{${if (!available) "unavailable" else "create-failed"}}"
        fun flag(value: Boolean?) = when (value) {
            true -> "1"
            false -> "0"
            null -> "?"
        }
        return "$name{default=${flag(defaultEnabled)},final=${flag(finalEnabled)},set=${setEnabledCode ?: "?"}," +
            "ctl=${flag(hasControl)},sw=${if (aospSoftware) "1" else "0"},uuid=${uuid ?: "?"}}"
    }
}

internal object AudioEffectProbe {
    /**
     * Creates an effect and enables it. The default state is read before anything is changed and logged; an
     * effect that is already on is never switched off here (only a registry entry may do that), and
     * setEnabled(true) is the same call the former code made. The AOSP software AEC stays enabled.
     */
    fun enable(
        name: String,
        available: Boolean,
        create: () -> EffectHandle?,
    ): Pair<EffectOutcome, EffectHandle?> {
        if (!available) return EffectOutcome(name, false, false, null, null, null, null, null, null) to null
        val handle = runCatching { create() }.getOrNull()
            ?: return EffectOutcome(name, true, false, null, null, null, null, null, null) to null
        val defaultEnabled = runCatching { handle.enabled() }.getOrNull()
        val code = runCatching { handle.setEnabled(true) }.getOrNull()
        val outcome = EffectOutcome(
            name = name,
            available = true,
            created = true,
            defaultEnabled = defaultEnabled,
            uuid = runCatching { handle.uuid() }.getOrNull(),
            implementor = runCatching { handle.implementor() }.getOrNull(),
            hasControl = runCatching { handle.hasControl() }.getOrNull(),
            setEnabledCode = code,
            finalEnabled = runCatching { handle.enabled() }.getOrNull(),
        )
        return outcome to handle
    }

    fun summary(outcomes: List<EffectOutcome>): String = outcomes.joinToString(",") { it.summary() }

    fun detail(outcomes: List<EffectOutcome>): String = outcomes.joinToString(",") { it.detail() }
}

// ---- Capture source chain (U-04) --------------------------------------------------------------------

internal object CaptureSourceChain {
    /** A call asks for VOICE_COMMUNICATION; the chain relaxes to MIC then CAMCORDER (loses the platform AEC). */
    private val COMMUNICATION = listOf(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER,
    )

    /** Video-stream audio starts on CAMCORDER (the camera-matched source) and can relax to MIC. */
    private val STREAM = listOf(
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.MIC,
    )

    fun forMode(communicationMode: Boolean): List<Int> = if (communicationMode) COMMUNICATION else STREAM

    /** The source after [current] in the chain, or null at the end (or when [current] is not in the chain). */
    fun next(communicationMode: Boolean, current: Int): Int? {
        val chain = forMode(communicationMode)
        val index = chain.indexOf(current)
        return if (index < 0) null else chain.getOrNull(index + 1)
    }

    fun label(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> "SOURCE_$source"
    }
}

// ---- Start retry (U-04) -----------------------------------------------------------------------------

/**
 * Starting an AudioRecord can fail transiently while another app (an assistant, a call) still holds the
 * input (Oboe #1427: HAL start_input_stream status -22): the former code threw at the first failure and the call
 * was torn down. [runFrom] retries the start up to [attempts] in total with [backoffMs] between attempts.
 * It is never used when the microphone permission is denied (the AudioRecord is then not INITIALIZED and
 * start() fails before any start attempt).
 */
internal class CaptureStartRetry(
    val attempts: Int = ATTEMPTS,
    val backoffMs: Long = BACKOFF_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /**
     * Tries [tryStart] for attempt numbers [firstAttempt]..[attempts]; returns the attempt that started, or null.
     * [keepGoing] false aborts (the encoder was closed).
     */
    fun runFrom(firstAttempt: Int, keepGoing: () -> Boolean = { true }, tryStart: (attempt: Int) -> Boolean): Int? {
        var attempt = firstAttempt
        while (attempt <= attempts && keepGoing()) {
            if (attempt > 1) sleep(backoffMs)
            if (!keepGoing()) return null
            if (tryStart(attempt)) return attempt
            attempt++
        }
        return null
    }

    companion object {
        const val ATTEMPTS = 3
        const val BACKOFF_MS = 250L
    }
}

// ---- Silence watchdog (U-04) ------------------------------------------------------------------------

internal object PcmLevel {
    /** Largest absolute PCM16 little-endian sample in [bytes] (0 = digital silence). */
    fun peak(bytes: ByteArray, length: Int = bytes.size): Int {
        var peak = 0
        var i = 0
        val end = minOf(length, bytes.size) - 1
        while (i < end) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }
}

/**
 * Digital silence for [thresholdMs] in a row (RMS 0 while the loop is reading frames). [onFrame] returns true
 * once per episode, when the threshold is first reached; a non-zero frame ends the episode.
 */
internal class CaptureSilenceDetector(
    frameMs: Long,
    thresholdMs: Long = THRESHOLD_MS,
) {
    private val framesNeeded = ((thresholdMs + frameMs - 1) / frameMs).toInt().coerceAtLeast(1)
    private var zeroFrames = 0
    private var fired = false

    fun onFrame(peak: Int): Boolean {
        if (peak != 0) {
            zeroFrames = 0
            fired = false
            return false
        }
        zeroFrames++
        if (zeroFrames >= framesNeeded && !fired) {
            fired = true
            return true
        }
        return false
    }

    fun reset() {
        zeroFrames = 0
        fired = false
    }

    companion object {
        const val THRESHOLD_MS = 2_000L
    }
}

/**
 * F18 (2026-10-20): when 2 s of exact digital zeros really mean a dead capture path. Samsung's VOICE_COMMUNICATION
 * capture (S24 and M31, noise suppression in the framework) outputs exact zeros for 2 s or more every 3-40 s in a quiet
 * room and recovers by itself (2026-10-20 voice and video calls between them: 16-34 episodes per phone per call of
 * 3.5-4.5 min, 6-8 "not picking up any sound" notices each, all `clientSilenced=false`); a Windows DSP noise gate did
 * the same. A dead path (Oboe #2123, #1526) never recovers. So a
 * blank capture is confirmed only when the stream has carried no signal at all since the recorder started
 * ([NEVER_SIGNAL_MS]) or one zero run lasts [PERSISTENT_MS]. The platform's own silencing (isClientSilenced) needs no
 * such wait. [onFrame] returns a verdict once per zero run.
 */
internal class BlankCaptureEvidence(
    private val frameMs: Long,
    private val neverSignalMs: Long = NEVER_SIGNAL_MS,
    private val persistentMs: Long = PERSISTENT_MS,
) {
    enum class Verdict { NONE, NEVER_SIGNAL, PERSISTENT }

    private var everSignal = false
    private var zeroFrames = 0L
    private var fired = false

    /** Length of the current zero run. */
    val zeroMs: Long get() = zeroFrames * frameMs

    fun onFrame(peak: Int): Verdict {
        if (peak != 0) {
            everSignal = true
            zeroFrames = 0
            fired = false
            return Verdict.NONE
        }
        zeroFrames++
        if (fired) return Verdict.NONE
        val verdict = when {
            !everSignal && zeroMs >= neverSignalMs -> Verdict.NEVER_SIGNAL
            zeroMs >= persistentMs -> Verdict.PERSISTENT
            else -> Verdict.NONE
        }
        if (verdict != Verdict.NONE) fired = true
        return verdict
    }

    /** A new recorder (source switch or rebuild): its stream has not carried signal yet. */
    fun reset() {
        everSignal = false
        zeroFrames = 0
        fired = false
    }

    companion object {
        const val NEVER_SIGNAL_MS = 8_000L
        const val PERSISTENT_MS = 30_000L
    }
}

internal enum class SilenceCause {
    /** The platform silenced this client (another app has priority, a call, or the privacy toggle). Changing the source cannot help. */
    SYSTEM_SILENCED,

    /** Frames arrive but are all zero while the platform reports no silencing: a blank capture path (Oboe #2123, #1526). */
    BLANK_CAPTURE,
}

enum class AudioCaptureNotice(val text: String) {
    CAPTURE_SILENT("Your microphone is not picking up any sound. Another app or an assistant may be using it. Check that it is not covered."),
    MIC_BLOCKED("Android is blocking this app's microphone right now (another app has priority, or the microphone privacy switch is off)."),
    MIC_PRIVACY_SWITCH("The microphone privacy switch may be off. Turn it on in the quick settings so the other person can hear you."),
    ECHO_RISK("This phone has no echo cancellation. Use the earpiece or a headset so the other person does not hear themselves."),
    SOURCE_FALLBACK("Switched the microphone mode to recover sound."),
}

internal object SilenceClassifier {
    /**
     * [clientSilenced] is AudioRecordingConfiguration.isClientSilenced for this recorder (null before the first
     * callback). [micToggleSupported] is SensorPrivacyManager.supportsSensorToggle(MICROPHONE).
     */
    fun classify(clientSilenced: Boolean?): SilenceCause =
        if (clientSilenced == true) SilenceCause.SYSTEM_SILENCED else SilenceCause.BLANK_CAPTURE

    /** The notice for a confirmed silence. A system-silenced client with a privacy toggle gets its own message. */
    fun notice(cause: SilenceCause, micToggleSupported: Boolean): AudioCaptureNotice = when (cause) {
        SilenceCause.SYSTEM_SILENCED -> if (micToggleSupported) AudioCaptureNotice.MIC_PRIVACY_SWITCH else AudioCaptureNotice.MIC_BLOCKED
        SilenceCause.BLANK_CAPTURE -> AudioCaptureNotice.CAPTURE_SILENT
    }

    /**
     * The notice for a 2 s silence episode. With [evidence] on (F18, default) a blank capture waits for
     * [BlankCaptureEvidence]; the platform's own silencing is told at once.
     */
    fun episodeNotice(cause: SilenceCause, micToggleSupported: Boolean, evidence: Boolean): AudioCaptureNotice? =
        if (evidence && cause == SilenceCause.BLANK_CAPTURE) null else notice(cause, micToggleSupported)

    /** Only a blank capture can be helped by relaxing the source; the privacy toggle or another app's priority cannot. */
    fun mayChangeSource(cause: SilenceCause): Boolean = cause == SilenceCause.BLANK_CAPTURE
}

/** Rate-limits notices so a flapping silence episode does not nag. */
internal class NoticeLimiter(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private val lastAt = HashMap<AudioCaptureNotice, Long>()

    fun allow(notice: AudioCaptureNotice, nowMs: Long): Boolean {
        val last = lastAt[notice]
        if (last != null && nowMs - last < minIntervalMs) return false
        lastAt[notice] = nowMs
        return true
    }

    companion object {
        const val MIN_INTERVAL_MS = 30_000L
    }
}

// ---- Echo risk (U-03) -------------------------------------------------------------------------------

internal object EchoRisk {
    /**
     * True when the call is on the loudspeaker without an effective platform AEC. Effective: the app's own
     * AcousticEchoCanceler is enabled, or the framework reports an AEC in the recording's effect list. CDD 5.4.4
     * only says an AEC SHOULD exist; Oboe #1405 lists phones without a working one. No software AEC is
     * implemented (user question Q-N4): this only produces the one-time hint to use the earpiece or a headset.
     */
    fun atRisk(
        communicationMode: Boolean,
        aecEnabledByApp: Boolean?,
        aecInFramework: Boolean?,
        loudspeaker: Boolean,
    ): Boolean = communicationMode && loudspeaker && aecEnabledByApp != true && aecInFramework != true
}

// ---- Buffer fallback message (U-10) -----------------------------------------------------------------

internal object MinBufferCheck {
    /** Null when [minBufferBytes] is usable, otherwise the plain message (getMinBufferSize returns <= 0 on error). */
    fun captureProblem(minBufferBytes: Int): String? =
        if (minBufferBytes > 0) null
        else "This phone's microphone does not report a usable capture buffer for 48 kHz mono audio " +
            "(getMinBufferSize=$minBufferBytes). Resampling is not implemented; please report the phone."

    fun playbackProblem(minBufferBytes: Int, sampleRate: Int): String? =
        if (minBufferBytes > 0) null
        else "This phone's audio output does not report a usable buffer for $sampleRate Hz audio " +
            "(getMinBufferSize=$minBufferBytes). Resampling is not implemented; please report the phone."
}

// ---- Opus complexity governor (U-09, Experimental) --------------------------------------------------

/**
 * Steps the libopus complexity down 10 -> 8 -> 5 (WebRTC's mobile default is 5) when the average encode time of
 * a window of frames exceeds [highFraction] of the frame duration. Never steps back up during a call (no flapping),
 * never touches the bitrate. [onFrame] returns the new complexity when it steps, else null.
 */
internal class OpusComplexityGovernor(
    private val frameBudgetNs: Long,
    private val windowFrames: Int = WINDOW_FRAMES,
    private val highFraction: Double = HIGH_FRACTION,
    private val ladder: List<Int> = LADDER,
) {
    private var index = 0
    private var frames = 0
    private var totalNs = 0L

    val complexity: Int get() = ladder[index]

    fun onFrame(encodeNs: Long): Int? {
        frames++
        totalNs += encodeNs
        if (frames < windowFrames) return null
        val averageNs = totalNs / frames
        frames = 0
        totalNs = 0
        if (averageNs > frameBudgetNs * highFraction && index < ladder.lastIndex) {
            index++
            return ladder[index]
        }
        return null
    }

    companion object {
        val LADDER = listOf(10, 8, 5)
        const val WINDOW_FRAMES = 100
        const val HIGH_FRACTION = 0.25
    }
}
