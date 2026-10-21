package com.example.bluetoothtest

import android.os.Build
import java.util.Locale

/**
 * Re-issues this central's connection-priority request when the stack reports that the resulting
 * LE connection update failed.
 *
 * Root cause (saved Bluetooth-process logs, 2026-10-07/09 runs): `requestConnectionPriority` only
 * queues an LL connection update; nothing public reports its outcome. The ASUS (Android 11) stack
 * logged `l2cble_process_conn_update_evt` failures with status 35 (0x23, LL Procedure Collision)
 * x5 and 59 (0x3B, Unacceptable Connection Parameters) x11, `onServerConnUpdate status=35` x10,
 * and the S24 status 59 x31 and 35 x8. A failed update leaves the link at the interval it was
 * created with: 45 ms when the ASUS or M31 initiates, 48.75 ms for the A15s (S24 Bluetooth logs).
 * At 45 ms, one or two 251-octet PDUs per event is 44-88 kbps, the range the ASUS -> A15s links
 * carried (57-91 kbps GATT, 56-65 kbps CoC, mesh4-57484598 and regress-*), while the same A15s
 * carried 743-1032 kbps from the S24 and M31. Collisions come from a PHY update started beside the
 * priority update (Core v5.4 Vol 6 Part B 5.3: one instant-based procedure at a time); AOSP
 * peripherals refuse a central's parameter request with 0x3B while their own connection updates are
 * disabled (`l2cble_process_rc_param_request_evt`, e.g. during GATT discovery), and do not ask
 * again for the central.
 *
 * The keeper only repeats the same request: up to [RETRY_DELAYS_MS].size times, after the stack
 * reported a non-zero status through the hidden onConnectionUpdated callback
 * ([LeConnectionParameterLog]). No report means no retry, so stacks that never call the hidden
 * callback behave as before. DEBUG launch extra `test_le_priority_retry=false` is the control arm.
 * Line: `BENCHMARK le_priority_retry`.
 *
 * What the priority API can and cannot do (aosp-bt-versions CONN-01/02/10, BACKLOG P-05; the numbers are
 * [lePriorityParameters]):
 *  - A request carries a SUPERVISION TIMEOUT of 5 s on Android 10+ (20 s on Android 8-9), slave latency 0
 *    (2 for LOW_POWER) and connection-event length 0/0. A link silent for 5 s is dropped with HCI 0x08, so
 *    every request is logged with the timeout it sets (`BENCHMARK le_priority_request`, entry
 *    `le-priority-request-supervision-log`) and a 0x08 after 5.0 s of silence reads as this timeout.
 *  - An app cannot set an arbitrary interval, the supervision timeout, the event length or subrating:
 *    `requestLeConnectionUpdate` and `requestSubrateMode` are @hide (privileged on Android 16) and there is no
 *    public interval API in Android 14, 15 or 16 apart from DCK (API 34).
 *  - The stack adjusts the numbers (global floor sysprop on 14+, a 20 ms floor while a hearing aid is bonded) and
 *    drops a request issued while another update is pending or while service discovery runs; the later request
 *    replaces the earlier one in the single slot.
 *
 * Interval drift (Experimental `test_priority_reassert`, default off; Nordic Android-BLE-Library #537/#558): the
 * interval silently reverts toward 45 ms after discovery or when the peer asks for its own parameters. A SUCCESSFUL
 * update that reports an interval above the requested priority's maximum is always logged
 * (`BENCHMARK le_priority_drift`, at most three lines per request); with the switch on, the same priority is
 * requested again after [REASSERT_DELAYS_MS], at most twice per request, never while a relay re-anchor is changing
 * the priority on purpose ([RelayReanchor.inProgress]), and not when the second report shows the same interval
 * (a floor, not a revert).
 *
 * Serialisation (Experimental `test_ll_gate`): when a [gate] is given and enabled, every request, including a retry,
 * enters it, so a priority update never overlaps a PHY or MTU request (BACKLOG P-01); the gate is also told the
 * outcome ([LlProcedureGate.onConnectionUpdated]). Without a gate, or with the gate off, requests go straight out.
 */
internal class LeConnectionPriorityKeeper(
    private val link: String,
    private val sendRequest: (BleConnectionPriority) -> Boolean,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val active: () -> Boolean,
    private val log: (String) -> Unit,
    private val enabled: () -> Boolean = { DebugTunables.lePriorityRetry },
    private val gate: LlProcedureGate? = null,
    private val reassertEnabled: () -> Boolean = { PhySwitches.priorityReassert },
    private val reassertSuppressed: () -> Boolean = { RelayReanchor.inProgress },
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val requestLogEnabled: () -> Boolean = {
        PhyLinkPolicy.prioritySupervisionLogActive(DeviceFamilyPolicy.switchedOff)
    },
    /** Experimental (`test_skip_redundant_priority`, default off): see [isRedundant]. */
    private val skipRedundant: () -> Boolean = { DebugTunables.skipRedundantPriority },
) {
    /** The interval of the last successful connection update (1.25 ms units; -1 before one). */
    private var lastIntervalUnits = -1
    private var priority: BleConnectionPriority? = null
    private var retries = 0
    private var generation = 0
    private var reasserts = 0
    private var driftLogs = 0
    private var lastDriftUnits = -1

    /** The app (re)requested [priority]; a later failure is retried toward it. */
    @Synchronized
    fun requested(priority: BleConnectionPriority) {
        this.priority = priority
        retries = 0
        reasserts = 0
        driftLogs = 0
        lastDriftUnits = -1
        generation++
    }

    /** Requests [priority] now and remembers it for retries. */
    fun request(priority: BleConnectionPriority): Boolean {
        val redundant = synchronized(this) { isRedundant(priority, this.priority, lastIntervalUnits, sdkInt) }
        if (redundant && skipRedundant()) {
            val window = lePriorityParameters(priority, sdkInt)
            log(
                String.format(
                    java.util.Locale.ROOT,
                    "BENCHMARK prio_request: skipped | link=%s | priority=%s | intervalMs=%.2f | window=%.2f-%.2f",
                    link, priority.label, lastIntervalUnits * 1.25, window.minIntervalMs, window.maxIntervalMs,
                ),
            )
            requested(priority)
            return true
        }
        requested(priority)
        if (requestLogEnabled()) log(lePriorityParameters(priority, sdkInt).line(link, priority, sdkInt))
        return dispatch(priority)
    }

    private fun dispatch(target: BleConnectionPriority): Boolean {
        val gate = gate ?: return sendRequest(target)
        return when (gate.submit(LlProcedure.CONNECTION_PARAMS, "priority_${target.label}") { sendRequest(target) }) {
            GateAdmission.IMMEDIATE_OK, GateAdmission.QUEUED -> true
            GateAdmission.IMMEDIATE_FAILED, GateAdmission.DROPPED_STUCK, GateAdmission.DROPPED_CLOSED -> false
        }
    }

    /**
     * The stack reported an LE connection update with [status] (0 = success) and, when known, the new
     * [intervalUnits] (1.25 ms units; -1 when the caller has none).
     */
    fun onConnectionUpdated(status: Int, intervalUnits: Int = -1) {
        gate?.onConnectionUpdated(status, intervalUnits)
        handleUpdate(status, intervalUnits)
    }

    @Synchronized
    private fun handleUpdate(status: Int, intervalUnits: Int) {
        if (status == 0 && intervalUnits > 0) lastIntervalUnits = intervalUnits
        val target = priority ?: return
        if (status == 0) {
            checkDrift(target, intervalUnits)
            return
        }
        if (!enabled()) {
            log("BENCHMARK le_priority_retry: link=$link | priority=${target.label} | status=$status | action=none (control arm)")
            return
        }
        if (retries >= RETRY_DELAYS_MS.size) {
            log("BENCHMARK le_priority_retry: link=$link | priority=${target.label} | status=$status | action=gave_up | retries=$retries")
            return
        }
        val delayMs = RETRY_DELAYS_MS[retries]
        retries++
        val expected = generation
        log(
            "BENCHMARK le_priority_retry: link=$link | priority=${target.label} | status=$status | " +
                "action=retry | attempt=$retries | delayMs=$delayMs",
        )
        schedule(delayMs) { retryIfCurrent(target, expected) }
    }

    /** Caller holds the monitor. */
    private fun checkDrift(target: BleConnectionPriority, intervalUnits: Int) {
        if (intervalUnits <= 0) return
        val params = lePriorityParameters(target, sdkInt)
        if (intervalUnits <= params.maxIntervalUnits) return
        val sameAsBefore = reasserts > 0 && lastDriftUnits == intervalUnits
        lastDriftUnits = intervalUnits
        val action = when {
            !reassertEnabled() -> "none (experiment off)"
            reassertSuppressed() -> "none (relay re-anchor in progress)"
            sameAsBefore -> "none (floor: unchanged after reassert)"
            reasserts >= REASSERT_DELAYS_MS.size -> "gave_up"
            else -> "reassert"
        }
        if (driftLogs < MAX_DRIFT_LOGS) {
            driftLogs++
            log(
                String.format(
                    Locale.ROOT,
                    "BENCHMARK le_priority_drift: link=%s | priority=%s | requestedMaxMs=%.2f | reportedMs=%.2f | " +
                        "action=%s | attempt=%d",
                    link, target.label, params.maxIntervalMs, intervalUnits * 1.25, action, reasserts + 1,
                ),
            )
        }
        if (action != "reassert") return
        val delayMs = REASSERT_DELAYS_MS[reasserts]
        reasserts++
        val expected = generation
        schedule(delayMs) { reassertIfCurrent(target, expected) }
    }

    private fun retryIfCurrent(target: BleConnectionPriority, expected: Int) {
        val current = synchronized(this) { generation == expected && priority == target }
        if (!current || !active()) return
        val queued = dispatch(target)
        log("BENCHMARK le_priority_retry: link=$link | priority=${target.label} | action=requested | queued=$queued")
    }

    private fun reassertIfCurrent(target: BleConnectionPriority, expected: Int) {
        val current = synchronized(this) { generation == expected && priority == target }
        if (!current || !active() || reassertSuppressed()) return
        val queued = dispatch(target)
        log("BENCHMARK le_priority_drift: link=$link | priority=${target.label} | action=requested | queued=$queued")
    }

    companion object {
        /**
         * Experimental: a request for the priority already requested, on a link whose last successful update already
         * put the interval inside that priority's window, would only start another connection-update procedure.
         * 2026-10-20 (a15s-asus radio join): the A15s source re-requested HIGH right after the stream handshake on a
         * link already at 15 ms; no update followed on either phone and the link dropped with a supervision timeout
         * 5.07 s later (5 s timeout). One instance only, so this is an A/B, off by default.
         */
        fun isRedundant(
            priority: BleConnectionPriority,
            currentTarget: BleConnectionPriority?,
            lastIntervalUnits: Int,
            sdkInt: Int,
        ): Boolean {
            if (currentTarget != priority || lastIntervalUnits <= 0) return false
            val window = lePriorityParameters(priority, sdkInt)
            return lastIntervalUnits in window.minIntervalUnits..window.maxIntervalUnits
        }

        /** Long enough for a colliding PHY update or the peer's GATT discovery to finish. */
        val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L, 4_000L)

        /** After discovery ends the stack applies the queued request; one more look 1 s and 3 s later. */
        val REASSERT_DELAYS_MS = longArrayOf(1_000L, 3_000L)

        const val MAX_DRIFT_LOGS = 3
    }
}
