package com.example.bluetoothtest

import android.content.Intent

/**
 * Launch switches of the advertising batch (device-family backlog A-01..A-03). Every non-default value is
 * DEBUG-only: a launch extra from another app must not change a release build's behaviour. Defaults are the
 * shipped behaviour. The register of switches is docs/SWITCHES.md ("Experimental, alternative and optional
 * behaviour"); the matching rows are listed in the batch hand-back.
 *
 * Shipped ON (after-failure behaviour, kill switches for A/B control arms):
 *  - `test_adv_watchdog=false`: no restart of a set that stopped without being asked (A-03).
 *  - `test_adv_preempt=false`: a refused session-critical set does not release title/reach sets (A-02).
 *  - `test_adv_stop_settle=false`: a start does not wait for in-flight stops to be confirmed (A-02).
 * Experimental, default OFF:
 *  - `test_adv_budget_cap=N` (1..8): act as if the controller offered only N advertising sets, to exercise the
 *    one-instance paths on a phone that has more (there is no owned 1-slot phone).
 *  - `test_adv_quiesce_stop=true`: disable a set and let that confirm before stopping it, so a stop cannot meet the
 *    stack's address-rotation command (the Android 15 slot-leak hypothesis, ADV-03).
 */
object AdvertisingSwitches {
    /** `test_adv_watchdog` (default true; false is the control arm). */
    @Volatile var watchdogRestart: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** `test_adv_preempt` (default true; false is the control arm). */
    @Volatile var budgetPreempt: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** `test_adv_stop_settle` (default true; false is the control arm). */
    @Volatile var stopSettle: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** `test_adv_budget_cap` (Experimental, default 0 = off): simulated number of advertising sets the controller offers. */
    @Volatile var budgetCap: Int = 0
        set(value) {
            field = if (BuildConfig.DEBUG && value in 1..MAX_SIMULATED_CAP) value else 0
        }

    /** `test_adv_quiesce_stop` (Experimental, default off): disable and confirm before stopping a set. */
    @Volatile var quiesceStop: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    const val MAX_SIMULATED_CAP = 8

    /** Reads the DEBUG launch extras `test_adv_*`; release builds keep every default (the setters gate on DEBUG). */
    fun applyLaunchExtras(intent: Intent?) {
        if (intent == null) return
        applyExtras(
            boolExtra = { name, default -> if (intent.hasExtra(name)) intent.getBooleanExtra(name, default) else default },
            intExtra = { name, default -> if (intent.hasExtra(name)) intent.getIntExtra(name, default) else default },
        )
    }

    fun applyExtras(boolExtra: (String, Boolean) -> Boolean, intExtra: (String, Int) -> Int) {
        watchdogRestart = boolExtra("test_adv_watchdog", true)
        budgetPreempt = boolExtra("test_adv_preempt", true)
        stopSettle = boolExtra("test_adv_stop_settle", true)
        budgetCap = intExtra("test_adv_budget_cap", 0)
        quiesceStop = boolExtra("test_adv_quiesce_stop", false)
    }

    /** Back to the shipped defaults (tests). */
    fun reset() {
        applyExtras({ _, default -> default }, { _, default -> default })
    }
}
