package com.example.bluetoothtest

import android.content.Intent

/**
 * Switches of the reliability-banner and OEM-guidance batch (device-family backlog B2). The register of
 * experimental and optional behaviour is SWITCHES; this object is where the defaults live.
 *
 * Everything that is plain guidance text or a read-only diagnostic ships ON and has its own registry off-switch
 * (`test_device_policy_off=<entry id>`). Only an arm that touches a private API is a launch extra, DEBUG only (a
 * launch extra from another app must not change a release build). [applyLaunchExtras] is parsed once by the main
 * activity (root hook, like the other batches).
 *
 * - `test_miui_appops_probe=true`  Experimental, default off: ask Xiaomi's hidden AppOps (reflection on
 *   AppOpsManager) whether the "Display pop-up windows while running in the background" permission is granted
 *   ([MiuiAppOpsProbe]). Private API, fails closed (unknown), operation ids unverified on HyperOS.
 */
internal object BackgroundSwitchDefaults {
    const val MIUI_APPOPS_PROBE = false
}

internal object BackgroundSwitches {
    const val EXTRA_MIUI_APPOPS_PROBE = "test_miui_appops_probe"

    /** Experimental, default off, DEBUG only. */
    @Volatile var miuiAppOpsProbe: Boolean = BackgroundSwitchDefaults.MIUI_APPOPS_PROBE
        set(value) {
            field = if (BuildConfig.DEBUG) value else BackgroundSwitchDefaults.MIUI_APPOPS_PROBE
        }

    fun applyLaunchExtras(intent: Intent?) {
        if (intent == null || !BuildConfig.DEBUG) return
        if (intent.hasExtra(EXTRA_MIUI_APPOPS_PROBE)) {
            miuiAppOpsProbe = intent.getBooleanExtra(EXTRA_MIUI_APPOPS_PROBE, BackgroundSwitchDefaults.MIUI_APPOPS_PROBE)
        }
    }
}
