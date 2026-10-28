package com.example.bluetoothtest

import android.content.Context

/** Whether the first-run screens were passed (kept apart from [OnboardingActivity] so it is JVM-testable). */
internal object OnboardingState {
    private const val PREF_ONBOARDING_DONE = "onboarding_done_v1"

    /** In this process, the first-run screens are in front: the main screen waits ([RuntimePermissionPlan.mainScreenWaits]). */
    @Volatile
    var open: Boolean = false

    /** What the user refused on the permission page in this process; the screen after onboarding does not ask again. */
    @Volatile
    private var refused: Set<String> = emptySet()

    fun recordAnswers(granted: Map<String, Boolean>) {
        refused = granted.filterValues { !it }.keys
    }

    fun takeRefused(): Set<String> = refused.also { refused = emptySet() }

    /** UI-only preferences file; listed in EmergencyWipe.KNOWN_PREFERENCES. */
    const val PREFERENCES = "bluetooth_video_ui"

    fun isDone(context: Context): Boolean = prefs(context).getBoolean(PREF_ONBOARDING_DONE, false)

    fun markDone(context: Context) {
        prefs(context).edit().putBoolean(PREF_ONBOARDING_DONE, true).apply()
    }

    /** After an emergency wipe the app starts from onboarding again. */
    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
