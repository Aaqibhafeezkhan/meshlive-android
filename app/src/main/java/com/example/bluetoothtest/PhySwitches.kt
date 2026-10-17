package com.example.bluetoothtest

import android.content.Intent

/**
 * Switches of device-family batch P (PHY and link-layer procedures). Every arm is DEBUG-only (a launch
 * extra from another app must not change a release build) and default OFF: they are Experimental and
 * listed in docs/SWITCHES.md ("Experimental, alternative and optional behaviour"). Behaviour that ships
 * ON in this batch (the Android 13 PHY readPhy backstop, the priority-request supervision log, the
 * dual-role probe log) is controlled by its device-policy entry instead: `test_device_policy_off=<id>`.
 *
 * Root wires [applyLaunchExtras] once, next to the other launch-extra parsing in MainActivity.
 */
internal object PhySwitches {
    /**
     * `test_ll_gate=true` (Experimental, BACKLOG P-01): [LlProcedureGate] serialises MTU, discovery, PHY and
     * connection-update requests on one link (no overlap, settle gaps, a peripheral waits for the central's
     * first connection update, one 1M retry on 0x23/0x2A). Off: every request goes straight to the stack as
     * before. Delays the 2M start by seconds.
     */
    @Volatile var llGate: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_link_change_rollback=true` (Experimental, BACKLOG P-02): a link lost with a fault status within
     * 10 s of an MTU, PHY or priority change remembers that change as bad for the peer (persisted, expires
     * with a Bluetooth restart, a security-patch change or 7 days), so the next connection skips it.
     */
    @Volatile var linkChangeRollback: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_priority_reassert=true` (Experimental, aosp-bt-versions CONN-02 and Nordic Android-BLE-Library
     * #537/#558): when the stack reports a connection update whose interval is above the requested
     * priority's maximum (the interval silently reverted toward 45 ms), the same priority is requested again,
     * at most twice per request. Off: the drift is only logged (`BENCHMARK le_priority_drift`).
     */
    @Volatile var priorityReassert: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Reads the launch extras; the testable form of [applyLaunchExtras]. */
    fun applyFlags(flag: (name: String) -> Boolean) {
        llGate = flag("test_ll_gate")
        linkChangeRollback = flag("test_link_change_rollback")
        priorityReassert = flag("test_priority_reassert")
    }

    fun applyLaunchExtras(intent: Intent) = applyFlags { name -> intent.getBooleanExtra(name, false) }
}
