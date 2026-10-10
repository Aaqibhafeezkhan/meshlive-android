package com.example.bluetoothtest

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
 */
internal class LeConnectionPriorityKeeper(
    private val link: String,
    private val sendRequest: (BleConnectionPriority) -> Boolean,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val active: () -> Boolean,
    private val log: (String) -> Unit,
    private val enabled: () -> Boolean = { DebugTunables.lePriorityRetry },
) {
    private var priority: BleConnectionPriority? = null
    private var retries = 0
    private var generation = 0

    /** The app (re)requested [priority]; a later failure is retried toward it. */
    @Synchronized
    fun requested(priority: BleConnectionPriority) {
        this.priority = priority
        retries = 0
        generation++
    }

    /** Requests [priority] now and remembers it for retries. */
    fun request(priority: BleConnectionPriority): Boolean {
        requested(priority)
        return sendRequest(priority)
    }

    /** The stack reported an LE connection update with [status] (0 = success). */
    @Synchronized
    fun onConnectionUpdated(status: Int) {
        val target = priority ?: return
        if (status == 0) return
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

    private fun retryIfCurrent(target: BleConnectionPriority, expected: Int) {
        val current = synchronized(this) { generation == expected && priority == target }
        if (!current || !active()) return
        val queued = sendRequest(target)
        log("BENCHMARK le_priority_retry: link=$link | priority=${target.label} | action=requested | queued=$queued")
    }

    companion object {
        /** Long enough for a colliding PHY update or the peer's GATT discovery to finish. */
        val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L, 4_000L)
    }
}
