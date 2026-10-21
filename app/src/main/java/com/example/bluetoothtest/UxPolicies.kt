package com.example.bluetoothtest

/**
 * The 2.4 GHz Wi-Fi advice (user-perspective audit 2026-10-21): asked at most once a day (the answer is kept across app
 * restarts), never before a voice call (voice fits easily in a shared radio), and the call or stream it paused goes on
 * when the user comes back from Wi-Fi settings.
 */
internal object WifiAdvicePolicy {
    const val PREF_ANSWERED_AT = "wifi_advice_answered_at_ms"
    const val QUIET_MS = 24 * 60 * 60 * 1_000L

    fun shouldAsk(voice: Boolean, nowMs: Long, answeredAtMs: Long): Boolean = when {
        voice -> false
        answeredAtMs <= 0L -> true
        nowMs < answeredAtMs -> true // the clock moved back: ask again rather than stay quiet for ever
        else -> nowMs - answeredAtMs >= QUIET_MS
    }
}

/** The one-time "Before you go live" before the first video live (audio only has its own, AudioLiveController). */
internal object LiveDisclosure {
    const val PREF_VIDEO = "video_live_disclosed"

    fun needed(disclosed: Boolean): Boolean = !disclosed
}

/** Ending a live stream asks first only while someone is watching ("End live for 3 viewers?"). */
internal object LiveEndConfirm {
    fun ask(viewers: Int): Boolean = viewers > 0
}
