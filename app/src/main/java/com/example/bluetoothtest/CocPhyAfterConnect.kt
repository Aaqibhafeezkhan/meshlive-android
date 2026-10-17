package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental (`test_coc_phy_after_connect`, default off; A/B arm against the sequenced PSM-read PHY
 * request): an LE CoC central requests the preferred PHY only after the CoC socket is connected and
 * hop authentication has finished, then waits for the PHY update before heavy traffic.
 *
 * Why: the CoC client requested 2M as soon as it had read the PSM (BleL2capPsmReader.finishWithPsm)
 * and BleCocClient connected the socket at once, so the CoC connect overlapped the PHY update; the
 * A15s -> ASUS CoC connect failed 6 ms after the 2M request while the update was in flight
 * (mesh4-c0200edf-loop7-1 0004, 363d05a). Nordic's Android-BLE-Library waits for onPhyUpdate before
 * the next queued operation and falls back to readPhy after 1 s, because some stacks never call
 * onPhyUpdate (BleManagerHandler.java L2958-2985 and L3838-3860, commit 46d17813). Both Nordic
 * libraries treat a link that fails right after an early 2M switch as a controller interop fault
 * (Kotlin-BLE-Library Peripheral.kt L1056-1074; Android-BLE-Library 2c5b148d).
 *
 * The wait is bounded and ends in a readPhy backstop (BACKLOG CONN-09): Nordic reads once after 1 s; on Android 13,
 * where the base release may never deliver onPhyUpdate (fixed in 13 QPR1, e0fd13ef08; Blessed gives up on the
 * callback after 200 ms), the [PhyWaitPlan.ANDROID_13] plan reads at 200 ms and every 400 ms to 2 s and stops at the
 * first read that shows the requested PHY. A read at 200 ms can still show the old PHY, so a read that does not match
 * ends nothing. The result says `unconfirmed` (effective = the last PHY read) instead of assuming the request worked.
 * With a [gate] (Experimental `test_ll_gate`) the request waits for its turn first; the wait for the update starts
 * when the request was actually sent.
 *
 * JVM-testable: the GATT calls and the clock are injected.
 */
internal class CocPhyAfterConnect(
    private val requestPhy: () -> Boolean,
    private val readPhy: () -> Boolean,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    /** The PHY value (1/2/3) the request asks for; derived from the requested label when null. */
    private val expectedTx: Int? = null,
    private val gate: LlProcedureGate? = null,
) {
    private val updated = CountDownLatch(1)
    @Volatile private var read = CountDownLatch(1)
    @Volatile private var effectiveTx: Int? = null
    @Volatile private var effectiveStatus: Int? = null
    @Volatile private var source: String = "none"

    /** onPhyUpdate (fromRead=false) or onPhyRead (fromRead=true) for this link. */
    fun onPhy(txPhy: Int, status: Int, fromRead: Boolean) {
        if (!fromRead || updated.count > 0L) {
            effectiveTx = txPhy
            effectiveStatus = status
            source = if (fromRead) "read" else "update"
        }
        if (fromRead) read.countDown() else updated.countDown()
    }

    /**
     * Requests the PHY, waits up to the first poll offset for onPhyUpdate, then reads the PHY and waits up
     * to [readWaitMs] for onPhyRead (more polls per [plan] when a PHY is expected and the read did not show
     * it). [updateWaitMs] overrides the plan's first offset (and then no extra poll is made). Never throws;
     * returns the logged result.
     */
    fun run(
        link: String,
        requestedLabel: String,
        reason: String,
        updateWaitMs: Long? = null,
        readWaitMs: Long = READ_FALLBACK_WAIT_MS,
        plan: PhyWaitPlan = PhyWaitPlan.current(),
    ): Result {
        val startedMs = nowMs()
        val expected = expectedTx ?: phyValueForLabel(requestedLabel)
        val firstWaitMs = updateWaitMs ?: plan.firstPollMs
        val extraPolls = if (updateWaitMs == null && expected != null) plan.pollOffsets().size - 1 else 0
        val queued = requestThroughGate(requestedLabel, expected)
        var fallback = false
        var polls = 0
        if (queued) {
            val gotUpdate = runCatching { updated.await(firstWaitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!gotUpdate) {
                fallback = true
                while (true) {
                    polls++
                    read = CountDownLatch(1)
                    if (runCatching(readPhy).getOrDefault(false)) {
                        runCatching { read.await(readWaitMs, TimeUnit.MILLISECONDS) }
                    }
                    val shows = expected != null && effectiveStatus == 0 && effectiveTx == expected
                    if (updated.count == 0L || shows || polls > extraPolls) break
                    if (runCatching { updated.await(plan.pollIntervalMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)) break
                }
            }
        }
        val confirmed = expected != null && effectiveStatus == 0 && effectiveTx == expected
        val result = Result(
            requested = requestedLabel,
            effective = when {
                !queued -> "not_queued"
                effectiveStatus == null -> "unknown"
                effectiveStatus != 0 -> "failed(status ${effectiveStatus})"
                else -> lePhyLabel(effectiveTx ?: 0)
            },
            source = if (queued) source else "none",
            readFallback = fallback,
            waitedMs = nowMs() - startedMs,
            polls = polls,
            unconfirmed = queued && fallback && expected != null && !confirmed && updated.count > 0L,
        )
        log(result.line(link, reason))
        return result
    }

    private fun requestThroughGate(requestedLabel: String, expected: Int?): Boolean {
        val gate = gate ?: return runCatching(requestPhy).getOrDefault(false)
        val dispatched = CountDownLatch(1)
        val startedOk = AtomicBoolean(false)
        val admission = gate.submit(LlProcedure.PHY, "coc_after_connect_${requestedLabel.lowercase()}", expectedTx = expected) {
            val ok = runCatching(requestPhy).getOrDefault(false)
            startedOk.set(ok)
            dispatched.countDown()
            ok
        }
        return when (admission) {
            GateAdmission.IMMEDIATE_OK -> true
            GateAdmission.QUEUED -> {
                runCatching { dispatched.await(GATE_WAIT_MS, TimeUnit.MILLISECONDS) }
                startedOk.get()
            }
            else -> false
        }
    }

    data class Result(
        val requested: String,
        val effective: String,
        val source: String,
        val readFallback: Boolean,
        val waitedMs: Long,
        val polls: Int = if (readFallback) 1 else 0,
        /** No update and no read showed the requested PHY: [effective] is only the last PHY read. */
        val unconfirmed: Boolean = false,
    ) {
        fun line(link: String, reason: String): String =
            "BENCHMARK link_phy: link=$link | requested=$requested | effective=$effective | reason=$reason | " +
                "source=$source | readFallback=$readFallback | waitedMs=$waitedMs" +
                (if (polls > 1) " | polls=$polls" else "") +
                (if (unconfirmed) " | unconfirmed=true" else "")
    }

    companion object {
        /** Nordic's readPhy fallback delay (Android-BLE-Library 46d17813). */
        const val UPDATE_WAIT_MS = 1_000L
        const val READ_FALLBACK_WAIT_MS = 500L

        /** Longest a gated request waits for its turn before the update wait starts. */
        const val GATE_WAIT_MS = 6_000L

        /** The experiment's switch, read once per control connection. */
        fun enabled(): Boolean = DebugTunables.cocPhyAfterConnect
    }
}

/** The PHY value a requested-PHY label names ("2M", "1M (adaptive)", "Coded S2 (experimental)"), or null. */
internal fun phyValueForLabel(label: String): Int? = when {
    label.startsWith("2M") -> LePhy.TWO_M
    label.startsWith("1M") -> LePhy.ONE_M
    label.startsWith("Coded") -> LePhy.CODED
    else -> null
}
