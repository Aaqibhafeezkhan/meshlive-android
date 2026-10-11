package com.example.bluetoothtest

/**
 * How long one GATT client setup waits for `onMtuChanged` (GattVideoTransport.openOnce).
 *
 * Why a single timer is wrong. `BluetoothGatt.requestMtu` returns true when the binder call was made,
 * not when the exchange is scheduled. Saved platform logs show two different ways the callback does
 * not arrive, and only one of them is a dead request:
 *
 *  - Queued behind the platform's own service discovery. On connect the stack enters its discovery
 *    state on its own; AOSP `bta_gattc_main.cc` queues `BTA_GATTC_API_CFG_MTU_EVT` there and runs it
 *    when discovery ends. Median request-to-callback on the A15s is 1.3 s (87 of 123 attempts over
 *    1 s, max 2.3 s). The S24 relay connecting a 5th child sat in that state for 3.2 s
 *    (relayfix-0725a213-graceB1 0002, s24-bluetooth-2452.log 13:52:25.754 queued, 13:52:28.930
 *    discovery ended by our own close); closing there cancels the discovery, so the retry pays it again.
 *  - Dropped. `GattService.configureMTU` finds no client record for the new client and logs "No
 *    connection" (ASUS 6 of 6 stalls, A15s 5 of 7; Android 10/11 ContextMap race family).
 *
 * `readPhy` tells them apart. It reaches the controller through `BTM_BleReadPhy`, not through the BTA
 * queue (btif_gatt_client.cc), and both `clientReadPhy` and `onClientPhyRead` require the same client
 * record that `configureMTU` needs. So a PHY callback proves "the platform still owns this connection
 * and the controller answers": the MTU request is then only queued, and the wait runs to
 * [QUEUED_BUDGET_MS]. No answer inside [PROBE_REPLY_MS] keeps the old 3 s verdict. The probe can only
 * lengthen a wait for a link the platform has proven alive, never shorten one, so a stack that never
 * answers `readPhy` behaves exactly as before. Control arm: DEBUG `test_gatt_mtu_probe=false`.
 */
internal class GattMtuWait(
    private val probeEnabled: Boolean,
    private val probeAfterMs: Long = PROBE_AFTER_MS,
    private val probeReplyMs: Long = PROBE_REPLY_MS,
    private val legacyTimeoutMs: Long = LEGACY_TIMEOUT_MS,
    private val queuedBudgetMs: Long = QUEUED_BUDGET_MS,
) {
    enum class Verdict {
        /** Keep waiting. */
        WAIT,

        /** Send the PHY read now (once) and call [onProbeSent], or [onProbeUnavailable] if it cannot be sent. */
        SEND_PROBE,

        /** Probe disabled or unavailable: the old fixed timeout expired. */
        FAIL_FIXED_TIMEOUT,

        /** No platform answer to the probe by the fixed timeout: the request was most likely dropped. */
        FAIL_DROPPED,

        /** The platform answered, but the MTU never came within [QUEUED_BUDGET_MS]. */
        FAIL_QUEUED_TOO_LONG,
    }

    @Volatile private var requestedAtMs = NONE
    @Volatile private var probeSentAtMs = NONE
    @Volatile private var aliveAtMs = NONE
    @Volatile private var probeUnavailable = false
    @Volatile private var finished = false

    /** The platform answered a PHY call for this connection after the MTU request: it is queued, not dropped. */
    val platformAlive: Boolean get() = aliveAtMs != NONE

    val probeSent: Boolean get() = probeSentAtMs != NONE

    fun onRequested(nowMs: Long) {
        requestedAtMs = nowMs
        probeSentAtMs = NONE
        aliveAtMs = NONE
        probeUnavailable = false
        finished = false
    }

    /** `onMtuChanged` arrived (any status): nothing is left to wait for. */
    fun onMtuCallback() {
        finished = true
    }

    fun onProbeSent(nowMs: Long) {
        if (probeSentAtMs == NONE) probeSentAtMs = nowMs
    }

    fun onProbeUnavailable() {
        probeUnavailable = true
    }

    /** Any PHY callback (read or update, any status) for this connection after the request. */
    fun onPlatformCallback(nowMs: Long) {
        if (requestedAtMs != NONE && aliveAtMs == NONE) aliveAtMs = nowMs
    }

    fun poll(nowMs: Long): Verdict {
        val requested = requestedAtMs
        if (requested == NONE || finished) return Verdict.WAIT
        val waited = nowMs - requested
        if (!probeEnabled || probeUnavailable) {
            return if (waited >= legacyTimeoutMs) Verdict.FAIL_FIXED_TIMEOUT else Verdict.WAIT
        }
        if (aliveAtMs != NONE) {
            return if (waited >= queuedBudgetMs) Verdict.FAIL_QUEUED_TOO_LONG else Verdict.WAIT
        }
        val sentAt = probeSentAtMs
        if (sentAt == NONE) {
            if (waited >= legacyTimeoutMs) return Verdict.FAIL_DROPPED
            return if (waited >= probeAfterMs) Verdict.SEND_PROBE else Verdict.WAIT
        }
        // Never earlier than the old verdict, so a slow PHY answer cannot make a good wait fail sooner.
        val unanswered = nowMs - sentAt >= probeReplyMs && waited >= legacyTimeoutMs
        return if (unanswered) Verdict.FAIL_DROPPED else Verdict.WAIT
    }

    companion object {
        private const val NONE = Long.MIN_VALUE

        /** Typical request-to-callback is well under this on every phone but the A15s (median 1.3 s). */
        const val PROBE_AFTER_MS = 750L

        /** A controller read normally answers within one connection event; this is generous. */
        const val PROBE_REPLY_MS = 1_000L

        /** The pre-probe verdict (GattVideoTransport's former fixed MTU_CALLBACK_TIMEOUT_MS). */
        const val LEGACY_TIMEOUT_MS = 3_000L

        /**
         * Longest wait for a platform-proven-alive queue. Observed successes end by 2.3 s and the S24 stall
         * was already past 3.2 s; 8 s leaves 3-4 s of the 12 s setup deadline for services and the
         * subscription, which take about 10-60 ms once the queue drains.
         */
        const val QUEUED_BUDGET_MS = 8_000L
    }
}
