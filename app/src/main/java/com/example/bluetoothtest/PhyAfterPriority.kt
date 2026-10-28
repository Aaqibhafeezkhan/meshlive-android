package com.example.bluetoothtest

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds an LE central's setup PHY request while the link still sits outside the requested connection priority's window,
 * so the PHY update never starts beside the connection update that is about to land.
 *
 * Why. AOSP starts GATT service discovery on every client connect to a peer it has no cached database for, and it keeps a
 * database only for bonded peers (bta_gattc_act.cc `bta_gattc_conn`: "cache load failure, start discovery"). Discovery
 * locks connection updates and, when the app had set its own parameters, first moves the link to the 7.5 ms fast interval
 * (l2c_ble_conn_params.cc `l2cble_start_conn_update`, `BTM_BLE_CONN_INT_MIN`); when discovery ends the app's deferred
 * request is sent (`L2C_BLE_NEW_CONN_PARAM`). So a priority request becomes two updates: 7.5 ms, then the requested window
 * after discovery. Core v5.4 Vol 6 Part B 5.3 allows one instant-based procedure at a time. An LE CoC central on Android
 * 12/13 (fresh-ACL path, [BleCocFreshAcl]) reattaches GATT clients to the new ACL and requests the PHY at a fixed 600 ms;
 * whenever discovery ran past that point the PHY update landed between the two connection updates.
 *
 * Observed (relay-showcase-20261026, M31 relay as central to its callee): with the PHY update before the in-window update,
 * 4 of 8 links died by supervision timeout 5.1 s after the PHY change (A15s and ASUS callees); with the in-window update
 * first, 0 of 11 died. The phone A/B did not confirm it as the cause (phyprio-ab-20261028: child-link deaths 2 of 12 calls
 * with the hold, 1 of 11 without; every death followed the link's update to 15 ms, with or without a PHY update), so the
 * switch is Experimental and off (DECISIONS "Relay child links: the setup PHY update landed between the two priority
 * updates").
 *
 * Rule. [submit] runs the request at once (unchanged behaviour) when the switch is off, no priority was requested, no
 * connection update has been reported yet, or the last reported interval is inside the window. It holds the request
 * while the last reported interval is below the window's minimum (an update is certainly still coming), or above its
 * maximum with no report since the priority request (the request has not landed). A held request runs [gapMs] after a
 * report that settles the link (inside the window, or above it after the request: a stack floor or drift, which waiting
 * cannot change), or after [boundMs] at the latest; only once, and only while `isActive` (a PSM-read link closed for a
 * fresh ACL never sends it). DEBUG launch extra `test_phy_after_priority=true` turns it on.
 * Line: `BENCHMARK phy_after_priority: link= | event=hold|release | ...`.
 */
internal class PhyAfterPriority(
    private val link: String,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    private val enabled: () -> Boolean = { DebugTunables.phyAfterPriority },
    private val boundMs: Long = BOUND_MS,
    private val gapMs: Long = GAP_MS,
) {
    private class Held(val label: String, val sinceMs: Long, val isActive: () -> Boolean, val action: () -> Unit) {
        val done = AtomicBoolean(false)
    }

    private var window: LePriorityParameters? = null
    /** The last successfully reported interval, 1.25 ms units; -1 before the first report. */
    private var lastUnits = -1
    private var reportedSinceRequest = false
    private var held: Held? = null

    /** The app (re)requested a connection priority with these [parameters]. */
    @Synchronized
    fun onPriorityRequested(parameters: LePriorityParameters) {
        window = parameters
        reportedSinceRequest = false
    }

    /** The stack reported a connection update with [status] and the new interval [intervalUnits] (1.25 ms units). */
    fun onConnectionUpdated(status: Int, intervalUnits: Int) {
        val release = synchronized(this) {
            if (status != 0 || intervalUnits <= 0) return
            lastUnits = intervalUnits
            reportedSinceRequest = true
            val pending = held ?: return
            val target = window ?: return
            if (!settled(target, intervalUnits, sinceRequest = true)) return
            held = null
            pending
        }
        schedule(gapMs) { run(release, "settled") }
    }

    /** Runs [action] now, or holds it until the priority update lands (see the class rule). */
    fun submit(label: String, isActive: () -> Boolean, action: () -> Unit) {
        var superseded: Held? = null
        val hold = synchronized(this) {
            val target = window
            if (!enabled() || target == null || lastUnits <= 0 || settled(target, lastUnits, reportedSinceRequest)) {
                null
            } else {
                superseded = held
                Held(label, nowMs(), isActive, action).also { held = it }
            }
        }
        superseded?.let { old ->
            if (old.done.compareAndSet(false, true)) log("BENCHMARK phy_after_priority: link=$link | event=release | label=${old.label} | reason=superseded")
        }
        if (hold == null) {
            action()
            return
        }
        val target = window
        log(
            String.format(
                Locale.ROOT,
                "BENCHMARK phy_after_priority: link=%s | event=hold | label=%s | lastMs=%.2f | windowMs=%.2f-%.2f | boundMs=%d",
                link, label, lastUnits * 1.25, target?.minIntervalMs ?: 0.0, target?.maxIntervalMs ?: 0.0, boundMs,
            ),
        )
        schedule(boundMs) {
            synchronized(this) { if (held === hold) held = null }
            run(hold, "bound")
        }
    }

    private fun run(hold: Held, reason: String) {
        if (!hold.done.compareAndSet(false, true)) return
        val active = runCatching(hold.isActive).getOrDefault(false)
        val lastMs = synchronized(this) { lastUnits * 1.25 }
        log(
            String.format(
                Locale.ROOT,
                "BENCHMARK phy_after_priority: link=%s | event=release | label=%s | reason=%s | waitedMs=%d | lastMs=%.2f",
                link, hold.label, if (active) reason else "inactive", nowMs() - hold.sinceMs, lastMs,
            ),
        )
        if (active) hold.action()
    }

    companion object {
        /** The in-window update followed the 7.5 ms one by 0.2-0.6 s in the measured runs; a stack that never lands it costs this much once per link. */
        const val BOUND_MS = 1_500L

        /** A short breath after the update's completion event before the PHY request. */
        const val GAP_MS = 100L

        /** Inside the window, or above it once a report arrived after the request (a floor or drift waiting cannot change). */
        internal fun settled(window: LePriorityParameters, intervalUnits: Int, sinceRequest: Boolean): Boolean =
            intervalUnits in window.minIntervalUnits..window.maxIntervalUnits ||
                (intervalUnits > window.maxIntervalUnits && sinceRequest)
    }
}
