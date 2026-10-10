package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * JVM-testable: the GATT calls and the clock are injected.
 */
internal class CocPhyAfterConnect(
    private val requestPhy: () -> Boolean,
    private val readPhy: () -> Boolean,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
) {
    private val updated = CountDownLatch(1)
    private val read = CountDownLatch(1)
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
     * Requests the PHY, waits up to [updateWaitMs] for onPhyUpdate, then reads the PHY once and waits up
     * to [readWaitMs] for onPhyRead. Never throws; returns the logged result.
     */
    fun run(
        link: String,
        requestedLabel: String,
        reason: String,
        updateWaitMs: Long = UPDATE_WAIT_MS,
        readWaitMs: Long = READ_FALLBACK_WAIT_MS,
    ): Result {
        val startedMs = nowMs()
        val queued = runCatching(requestPhy).getOrDefault(false)
        var fallback = false
        if (queued) {
            val gotUpdate = runCatching { updated.await(updateWaitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!gotUpdate) {
                fallback = true
                if (runCatching(readPhy).getOrDefault(false)) {
                    runCatching { read.await(readWaitMs, TimeUnit.MILLISECONDS) }
                }
            }
        }
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
        )
        log(result.line(link, reason))
        return result
    }

    data class Result(
        val requested: String,
        val effective: String,
        val source: String,
        val readFallback: Boolean,
        val waitedMs: Long,
    ) {
        fun line(link: String, reason: String): String =
            "BENCHMARK link_phy: link=$link | requested=$requested | effective=$effective | reason=$reason | " +
                "source=$source | readFallback=$readFallback | waitedMs=$waitedMs"
    }

    companion object {
        /** Nordic's readPhy fallback delay (Android-BLE-Library 46d17813). */
        const val UPDATE_WAIT_MS = 1_000L
        const val READ_FALLBACK_WAIT_MS = 500L

        /** The experiment's switch, read once per control connection. */
        fun enabled(): Boolean = DebugTunables.cocPhyAfterConnect
    }
}
