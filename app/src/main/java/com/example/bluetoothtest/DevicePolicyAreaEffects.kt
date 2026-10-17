package com.example.bluetoothtest

/*
 * Effect types for the CAMERA and AUDIO policy areas (BACKLOG R-04). They are plain descriptions of
 * what a matching entry asks for; no Android type appears here so the registry stays JVM-testable.
 * The camera batch (CameraPolicy.kt / CameraSession.kt) and the audio batch (AudioCapturePolicy.kt)
 * attach them to entries with `area = PolicyArea.CAMERA` / `PolicyArea.AUDIO` and read them through
 * [ResolvedDevicePolicy.effects]; the resolver itself knows nothing about them.
 */

/** How the camera session is rebuilt after a pause or a viewfinder change. */
enum class CameraRebuildMode {
    /** Close the capture session and create the next one on the same CameraDevice (current behaviour). */
    SESSION_ONLY,

    /** As [SESSION_ONLY], but create the next session only after the old session's onClosed. */
    WAIT_SESSION_CLOSED,

    /** Close and reopen the CameraDevice (costs 300-800 ms and flickers the privacy indicator). */
    REOPEN_DEVICE,
}

internal data class CameraSessionRebuildMode(val mode: CameraRebuildMode) : DevicePolicyEffect {
    override fun describe(): String = "camera session rebuild: ${mode.name}"
}

/** Which Camera2 request template the repeating capture request is built from. */
enum class CameraTemplate { RECORD, PREVIEW }

internal data class CameraCaptureIntent(val template: CameraTemplate) : DevicePolicyEffect {
    override fun describe(): String = "camera capture request template: ${template.name}"
}

/** What to do with a platform audio effect (echo canceller, noise suppressor, gain control). */
enum class AudioEffectStance { LEAVE_DEFAULT, ENABLE, DISABLE }

/** Voice-capture effect handling; [LEAVE_DEFAULT] keeps what the platform enabled. */
internal data class AudioEffectPolicy(
    val echoCanceler: AudioEffectStance = AudioEffectStance.LEAVE_DEFAULT,
    val noiseSuppressor: AudioEffectStance = AudioEffectStance.LEAVE_DEFAULT,
    val automaticGainControl: AudioEffectStance = AudioEffectStance.LEAVE_DEFAULT,
) : DevicePolicyEffect {
    override fun describe(): String = "audio effects: AEC=${echoCanceler.name}, NS=${noiseSuppressor.name}, AGC=${automaticGainControl.name}"
}

/** AudioSource constants by name, in the order a capture start tries them. */
enum class AudioCaptureSource { VOICE_COMMUNICATION, MIC, VOICE_RECOGNITION, UNPROCESSED, CAMCORDER, DEFAULT }

internal data class AudioCaptureSourceChain(val sources: List<AudioCaptureSource>) : DevicePolicyEffect {
    init {
        require(sources.isNotEmpty()) { "an audio capture source chain needs at least one source" }
        require(sources.size == sources.toSet().size) { "an audio capture source chain lists each source once: $sources" }
    }

    override fun describe(): String = "audio capture source order: ${sources.joinToString(" > ") { it.name }}"
}
