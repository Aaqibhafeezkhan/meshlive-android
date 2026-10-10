package com.example.bluetoothtest

/**
 * Call UX switches (2026-10-10; patterns from the calls/small-apps reports, re-implemented).
 *
 * Bug-fix level, on for user sessions: call audio focus with hold on transient loss
 * ([CallAudioFocus]) and the ringer policy that honours silent/vibrate/Do Not Disturb with a
 * fallback ringtone ([CallRinger]). Experimental, off everywhere by default: outgoing ringback,
 * CallStyle notifications, camera pause at the ear and audio starvation concealment.
 *
 * A DEBUG benchmark launch (any `test_` extra) uses only its own extras, every one default off, so
 * a measured case behaves exactly as before unless its extra opts in. Release builds never enable
 * the experimental switches (no launch extras there).
 */
internal data class CallUxSettings(
    val audioFocus: Boolean = false,
    val ringerPolicy: Boolean = false,
    val ringback: Boolean = false,
    val callStyleNotifications: Boolean = false,
    val proximityCameraPause: Boolean = false,
    val audioConcealment: Boolean = false,
) {
    companion object {
        const val EXTRA_AUDIO_FOCUS = "test_call_audio_focus"
        const val EXTRA_RINGER_POLICY = "test_call_ringer_policy"
        const val EXTRA_RINGBACK = "test_call_ringback"
        const val EXTRA_CALL_STYLE = "test_call_style_notification"
        const val EXTRA_PROXIMITY_CAMERA_PAUSE = "test_call_proximity_camera_pause"
        const val EXTRA_AUDIO_CONCEAL = "test_call_audio_conceal"

        /**
         * [debugBuild] false (release): the bug-fix switches are on and every experiment is off.
         * DEBUG benchmark launch: each switch follows only its extra (default off). DEBUG manual
         * launch without `test_` extras: like a user session.
         */
        fun resolve(
            debugBuild: Boolean,
            benchmarkLaunch: Boolean,
            extra: (String) -> Boolean,
        ): CallUxSettings = when {
            debugBuild && benchmarkLaunch -> CallUxSettings(
                audioFocus = extra(EXTRA_AUDIO_FOCUS),
                ringerPolicy = extra(EXTRA_RINGER_POLICY),
                ringback = extra(EXTRA_RINGBACK),
                callStyleNotifications = extra(EXTRA_CALL_STYLE),
                proximityCameraPause = extra(EXTRA_PROXIMITY_CAMERA_PAUSE),
                audioConcealment = extra(EXTRA_AUDIO_CONCEAL),
            )
            else -> CallUxSettings(audioFocus = true, ringerPolicy = true)
        }
    }
}

/** Process-wide effective call UX switches, set by the activity from its launch intent. */
internal object CallUx {
    @Volatile var settings = CallUxSettings(audioFocus = true, ringerPolicy = true)

    fun label(settings: CallUxSettings = this.settings): String =
        "audioFocus=${settings.audioFocus} | ringerPolicy=${settings.ringerPolicy} | " +
            "ringback=${settings.ringback} | callStyle=${settings.callStyleNotifications} | " +
            "proximityCameraPause=${settings.proximityCameraPause} | audioConceal=${settings.audioConcealment}"
}
