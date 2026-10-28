package com.example.bluetoothtest

import android.content.Context
import android.content.Intent

/**
 * Switches of the session-service batch (device-family backlog B1). The register of experimental and
 * optional behaviour is SWITCHES; this object is where the defaults live.
 *
 * Every arm that differs from the shipped behaviour is DEBUG-only (a launch extra from another app must not change a
 * release build). The launch extras are parsed by [applyLaunchExtras], which the main activity calls once with the
 * launch intent (a root hook, like the other tunables).
 *
 * - `test_session_wake_lock=true`  Experimental, default off: a partial wake lock while a screen-off session forwards
 *   media ([SessionWakeLock]). Also needs the registry entry `fgs-session-wake-lock` on (its off-switch is
 *   `test_device_policy_off=fgs-session-wake-lock`).
 * - `test_session_wake_lock_cap_min=N`  daily budget of the lock in minutes (0 = no cap, for A/B runs only).
 * - `test_stop_scans_on_exit=false`  control arm: do not stop the app's BLE scanners when the session service ends
 *   or the task is removed (default true; the stop is the Android 10 scanner-leak mitigation).
 * - `test_boot_resume=true`  Experimental, default off: start the connectedDevice foreground service after the phone
 *   restarts or the app is updated ([BootResume]). A release opt-in is not built (PLAN, Q-N2).
 */
internal object SwitchDefaults {
    /** Flip to promote the wake lock; Q-N5 (sideload flavour only) decides who flips it. */
    const val SESSION_WAKE_LOCK = false

    /** Play vitals call 2 h of screen-off partial wake lock in 24 h excessive; stay well below. */
    const val SESSION_WAKE_LOCK_DAILY_CAP_MIN = 100

    const val STOP_SCANS_ON_EXIT = true
    const val BOOT_RESUME = false
}

internal object ServiceSwitches {
    const val EXTRA_SESSION_WAKE_LOCK = "test_session_wake_lock"
    const val EXTRA_SESSION_WAKE_LOCK_CAP_MIN = "test_session_wake_lock_cap_min"
    const val EXTRA_STOP_SCANS_ON_EXIT = "test_stop_scans_on_exit"
    const val EXTRA_BOOT_RESUME = "test_boot_resume"

    /** Experimental, default off. */
    @Volatile var sessionWakeLock: Boolean = SwitchDefaults.SESSION_WAKE_LOCK
        set(value) {
            field = if (BuildConfig.DEBUG) value else SwitchDefaults.SESSION_WAKE_LOCK
        }

    @Volatile var sessionWakeLockCapMinutes: Int = SwitchDefaults.SESSION_WAKE_LOCK_DAILY_CAP_MIN
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0, 24 * 60) else SwitchDefaults.SESSION_WAKE_LOCK_DAILY_CAP_MIN
        }

    @Volatile var stopScansOnExit: Boolean = SwitchDefaults.STOP_SCANS_ON_EXIT
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** DEBUG launch-extra arm of the boot-resume switch; a release build keeps it off ([BootResume]). */
    @Volatile var bootResume: Boolean = SwitchDefaults.BOOT_RESUME
        set(value) {
            field = if (BuildConfig.DEBUG) value else SwitchDefaults.BOOT_RESUME
        }

    /** Reads the DEBUG launch extras. [context] lets the boot-resume arm enable its receiver component at once. */
    fun applyLaunchExtras(intent: Intent?, context: Context? = null) {
        if (intent == null || !BuildConfig.DEBUG) return
        if (intent.hasExtra(EXTRA_SESSION_WAKE_LOCK)) sessionWakeLock = intent.getBooleanExtra(EXTRA_SESSION_WAKE_LOCK, false)
        if (intent.hasExtra(EXTRA_SESSION_WAKE_LOCK_CAP_MIN)) {
            sessionWakeLockCapMinutes = intent.getIntExtra(EXTRA_SESSION_WAKE_LOCK_CAP_MIN, SwitchDefaults.SESSION_WAKE_LOCK_DAILY_CAP_MIN)
        }
        if (intent.hasExtra(EXTRA_STOP_SCANS_ON_EXIT)) stopScansOnExit = intent.getBooleanExtra(EXTRA_STOP_SCANS_ON_EXIT, true)
        if (intent.hasExtra(EXTRA_BOOT_RESUME)) {
            bootResume = intent.getBooleanExtra(EXTRA_BOOT_RESUME, false)
            context?.let { BootResume.syncComponent(it) }
        }
    }
}
