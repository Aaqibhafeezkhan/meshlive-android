package com.example.bluetoothtest

/**
 * Call UX switches (2026-10-10; patterns from the calls/small-apps reports, re-implemented).
 *
 * Bug-fix level, on for user sessions: call audio focus with hold on transient loss
 * ([CallAudioFocus]) and the ringer policy that honours silent/vibrate/Do Not Disturb with a
 * fallback ringtone ([CallRinger]). Promoted 2026-10-11 to Settings > Features switches (defaults in
 * [CallFeatureDefaults], one constant each): outgoing ringback, CallStyle notifications, the call
 * screen over the lock screen and camera pause at the ear. Audio starvation concealment stays a
 * launch-extra experiment.
 *
 * A DEBUG benchmark launch (any `test_` extra) uses only its own extras, every one default off, so
 * a measured case behaves exactly as before unless its extra opts in. A release build ignores launch
 * extras; its switches follow the user's explicit choice or the default.
 */
internal data class CallUxSettings(
    val audioFocus: Boolean = false,
    val ringerPolicy: Boolean = false,
    val ringback: Boolean = false,
    val callStyleNotifications: Boolean = false,
    val proximityCameraPause: Boolean = false,
    val audioConcealment: Boolean = false,
    /** Call screen over the lock screen, screen on for an incoming call, and the incoming-call alert. */
    val lockScreen: Boolean = false,
) {
    companion object {
        const val EXTRA_AUDIO_FOCUS = "test_call_audio_focus"
        const val EXTRA_RINGER_POLICY = "test_call_ringer_policy"
        const val EXTRA_RINGBACK = "test_call_ringback"
        const val EXTRA_CALL_STYLE = "test_call_style_notification"
        const val EXTRA_PROXIMITY_CAMERA_PAUSE = "test_call_proximity_camera_pause"
        const val EXTRA_AUDIO_CONCEAL = "test_call_audio_conceal"
        const val EXTRA_LOCK_SCREEN = "test_call_lock_screen"

        /**
         * DEBUG benchmark launch: each switch follows only its extra (default off). Anything else is a
         * user session: audio focus and the ringer policy are on, the promoted features follow the
         * user's explicit choice ([stored]) or their default, and concealment is off.
         */
        fun resolve(
            debugBuild: Boolean,
            benchmarkLaunch: Boolean,
            stored: (String) -> Boolean? = { null },
            extra: (String) -> Boolean,
        ): CallUxSettings = when {
            debugBuild && benchmarkLaunch -> CallUxSettings(
                audioFocus = extra(EXTRA_AUDIO_FOCUS),
                ringerPolicy = extra(EXTRA_RINGER_POLICY),
                ringback = extra(EXTRA_RINGBACK),
                callStyleNotifications = extra(EXTRA_CALL_STYLE),
                proximityCameraPause = extra(EXTRA_PROXIMITY_CAMERA_PAUSE),
                audioConcealment = extra(EXTRA_AUDIO_CONCEAL),
                lockScreen = extra(EXTRA_LOCK_SCREEN),
            )
            else -> {
                fun on(feature: CallFeature) = CallFeatureResolver.enabled(feature, false, extra, stored)
                CallUxSettings(
                    audioFocus = true,
                    ringerPolicy = true,
                    ringback = on(CallFeature.RINGBACK),
                    callStyleNotifications = on(CallFeature.CALL_NOTIFICATIONS),
                    proximityCameraPause = on(CallFeature.EAR_CAMERA_PAUSE),
                    lockScreen = on(CallFeature.LOCK_SCREEN_CALLS),
                )
            }
        }
    }
}

/** Process-wide effective call UX switches, set by the activity from its launch intent. */
internal object CallUx {
    @Volatile var settings = CallUxSettings(audioFocus = true, ringerPolicy = true)

    fun label(settings: CallUxSettings = this.settings): String =
        "audioFocus=${settings.audioFocus} | ringerPolicy=${settings.ringerPolicy} | " +
            "ringback=${settings.ringback} | callStyle=${settings.callStyleNotifications} | " +
            "proximityCameraPause=${settings.proximityCameraPause} | audioConceal=${settings.audioConcealment} | " +
            "lockScreen=${settings.lockScreen}"
}
