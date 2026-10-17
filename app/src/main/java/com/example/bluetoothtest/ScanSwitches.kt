package com.example.bluetoothtest

import android.content.Intent

/**
 * Switches of batch S (BLE scanning and adapter state). Every arm is DEBUG-only: a launch extra from
 * another app must not change a release build, so each setter keeps the shipped default there. The
 * register of switches is docs/SWITCHES.md ("Experimental, alternative and optional behaviour").
 *
 * Shipped ON (no switch here, off through `test_device_policy_off=<id>`): the Location-switch
 * diagnostics (`android10-11-location-switch-scan-gate`), the SDK-aware scan restart interval, the
 * scan-silence log line (`scan-silence-watchdog`), the Bluetooth state receiver
 * (`bluetooth-process-restart-recovery`) and stopping scans on exit (`android10-scanner-leak-on-process-death`).
 * Everything below is Experimental and default off.
 */
internal object ScanSwitches {
    /** `test_scan_renewal_legacy`: the relay-helper scan restarts every 20 min as before (control arm of S-02). */
    @Volatile var renewalLegacy: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_scan_renewal_all` (Experimental, S-03): every scanner of the app stops together and restarts. */
    @Volatile var renewalAll: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_scan_stale_restart` (Experimental, S-04): a stale-scan verdict requests one gated restart. */
    @Volatile var staleRestart: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_scan_sw_filter_fallback` (Experimental, S-06): unfiltered scan + software filter while the screen is on. */
    @Volatile var softwareFilterFallback: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_scan_pause_during_connect` (Experimental, S-07): discovery scans pause while the app dials a link. */
    @Volatile var pauseDuringConnect: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_scan_probe_ungated` (control arm of the quota fix): the DEBUG probes (GapNameProbe, InboundProbe) start
     * scans without taking a scan-start gate slot, as before 2026-10-14. The shipped behaviour gates them: the
     * stack drops a 6th start in 30 s SILENTLY (no onScanFailed), so the app's budget is the only protection.
     */
    @Volatile var probeUngated: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Reads the DEBUG launch extras `test_scan_*`. A null intent keeps every default. */
    fun applyLaunchExtras(intent: Intent?) {
        apply { key, default -> intent?.getBooleanExtra(key, default) ?: default }
    }

    /** The extras reader, separated so JVM tests need no Intent. */
    fun apply(boolExtra: (String, Boolean) -> Boolean) {
        renewalLegacy = boolExtra("test_scan_renewal_legacy", false)
        renewalAll = boolExtra("test_scan_renewal_all", false)
        staleRestart = boolExtra("test_scan_stale_restart", false)
        softwareFilterFallback = boolExtra("test_scan_sw_filter_fallback", false)
        pauseDuringConnect = boolExtra("test_scan_pause_during_connect", false)
        probeUngated = boolExtra("test_scan_probe_ungated", false)
    }

    /** Back to the shipped defaults (tests). */
    fun reset() = apply { _, default -> default }
}
