package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Experimental relayed calls (DECISIONS Design C, PROTOCOL "Reach page"): a second legacy, non-connectable,
 * scannable advertising set whose scan response carries the helper's best reach entries, two per page.
 * Modelled on [TitleAdvertiser]: the same service UUID (no new scan-filter slot), 250 ms interval, one page
 * per second (about four advertising events per page, R15), pages rotated in place with
 * setScanResponseData, and the same free-set probe before it starts.
 *
 * Graceful fallback: when the probe finds fewer than two free sets, or the start fails, nothing else
 * changes; the phone is then a helper only for callers that already know its offer. The set stops while
 * there is nothing to advertise for [EMPTY_STOP_MS], and restarts when entries appear.
 *
 * Device-family batch A: this is the [AdvertisingSetRole.REACH] set, second in priority after the session-critical
 * set. It does not start when [AdvertisingBudget] has learned there is no room for it plus a spare, it releases its
 * instance when the session-critical set needs it ([yieldForBudget]), its start cannot throw ([AdvertisingStart]),
 * a failed start backs off instead of retrying every page tick, and a set the stack drops on its own is started
 * again by the page tick after the [AdvertisingWatchdog] back-off.
 */
internal class ReachAdvertiser(
    private val adapter: BluetoothAdapter,
    /** The relay field of every page: the fixed prefix, or (rotating) this epoch's role-0 RDI, read at each page. */
    private val relayId: () -> ByteArray,
    private val transport: BluetoothTransportOption,
    private val entries: () -> List<AdvertisedReach>,
    /** Rotating IDs: version 6 pages (role-0 RDIs) instead of version 4. */
    private val rotating: Boolean = false,
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    @Volatile private var set: AdvertisingSet? = null
    private val callbacks = java.util.concurrent.CopyOnWriteArrayList<ReachSetCallback>()
    private val budget get() = AdvertisingBudget.shared
    private val watchdog = AdvertisingWatchdog()
    private var failedStarts = 0
    private var pageIndex = 0
    private var emptySinceMs = 0L
    @Volatile private var retryAtMs = 0L

    /** The last status of a set start (for the DEBUG line); null before the first start. */
    @Volatile var lastStartStatus: Int? = null
        private set

    fun start() {
        if (closed.get()) return
        handler.post(::tick)
    }

    /** One callback per set: remembers whether the stack confirmed it, and whether MeshLive asked for its stop. */
    private inner class ReachSetCallback(val ownedBefore: Int) : AdvertisingSetCallback() {
        @Volatile var startedOk = false
        @Volatile var stopRequested = false
        @Volatile var deadByStack = false

        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            lastStartStatus = status
            // A start stays pending until its callback: a second start must not overlap it.
            starting.set(false)
            if (closed.get() || stopRequested) {
                if (status == ADVERTISE_SUCCESS) {
                    budget.noteStopRequested(this)
                    runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }
                }
                return
            }
            if (status == ADVERTISE_SUCCESS) {
                startedOk = true
                failedStarts = 0
                set = advertisingSet
                budget.acquire(this, AdvertisingSetRole.REACH) { yieldForBudget() }
            } else {
                callbacks.remove(this)
                failedStarts++
                val verdict = budget.noteRefused(AdvertisingSetRole.REACH, status, ownedBefore, android.os.Build.VERSION.SDK_INT)
                retryAtMs = android.os.SystemClock.elapsedRealtime() + AdvertisingWatchdog.startFailureBackoffMs(failedStarts, verdict.tooMany)
            }
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relayed_helper: reach_pages status=$status | tooMany=${status == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS}",
            )
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            deadByStack = true
            budget.noteStopped(this)
            if (stopRequested || closed.get() || !startedOk) return
            // The stack dropped a set MeshLive did not stop: the page tick starts a new one after the back-off.
            if (callbacks.remove(this) && callbacks.isEmpty()) set = null
            val now = android.os.SystemClock.elapsedRealtime()
            when (val decision = watchdog.onUnexpectedStop()) {
                is AdvertisingWatchdog.Decision.Restart -> {
                    retryAtMs = if (AdvertisingPolicy.watchdogOn()) now + decision.delayMs else Long.MAX_VALUE
                    Log.i(
                        "BluetoothVideo",
                        "adv_watchdog: label=reach_pages | event=restart_planned | delayMs=${decision.delayMs} | enabled=${AdvertisingPolicy.watchdogOn()}",
                    )
                }
                is AdvertisingWatchdog.Decision.GiveUp -> {
                    retryAtMs = now + SKIP_RETRY_MS
                    Log.i("BluetoothVideo", "adv_watchdog: label=reach_pages | event=give_up | reason=${decision.reason}")
                }
                else -> Unit
            }
        }
    }

    /**
     * The session-critical set was refused for lack of an instance and asks this one to release its own
     * ([AdvertisingBudget.preemptFor]): the reach set stops and does not retry for [SKIP_RETRY_MS].
     */
    fun yieldForBudget() {
        retryAtMs = android.os.SystemClock.elapsedRealtime() + SKIP_RETRY_MS
        stopSet()
        Log.i("BluetoothVideo", "relayed_helper: reach_pages yielded to the stream's own advertisement")
    }

    private fun tick() {
        if (closed.get()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val current = entries()
        if (current.isEmpty()) {
            if (emptySinceMs == 0L) emptySinceMs = now
            if (set != null && now - emptySinceMs >= EMPTY_STOP_MS) stopSet()
        } else {
            emptySinceMs = 0L
            val pages = ReachPageCodec.pages(
                relayId(),
                ReachPageCodec.flagsFor(transport, idle = true),
                current.map { it.entry },
                rotating = rotating,
            )
            if (pages.isNotEmpty()) {
                val page = pages[pageIndex % pages.size]
                pageIndex++
                val running = set
                if (running != null) {
                    runCatching { running.setScanResponseData(scanResponse(page)) }
                } else if (now >= retryAtMs) {
                    startSet(page)
                }
            }
        }
        handler.postDelayed(::tick, TitleAdvertiser.PAGE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startSet(firstPage: ByteArray) {
        if (!starting.compareAndSet(false, true)) return
        thread(name = "ReachAdvertiserStart", isDaemon = true) {
            try {
                // The probe starts and stops throw-away sets; run it once per process (shared with titles).
                val learned = if (AdvertisingPolicy.budgetOn()) budget.skipReason(AdvertisingSetRole.REACH) else null
                if (learned != null) {
                    starting.set(false)
                    Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_helper: reach_pages skipped | $learned")
                    retryAtMs = android.os.SystemClock.elapsedRealtime() + SKIP_RETRY_MS
                    return@thread
                }
                val free = AdvertisingSetProbe.lastFree ?: runCatching { AdvertisingSetProbe.run(adapter) }.getOrDefault(0)
                if (closed.get()) return@thread
                if (free < TitleAdvertiser.MIN_FREE_SETS) {
                    starting.set(false)
                    Log.i(
                        "BluetoothVideo",
                        (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                            "relayed_helper: reach_pages skipped | probeFreeSets=$free need=${TitleAdvertiser.MIN_FREE_SETS}",
                    )
                    // Do not retry for a while: the probe result only changes with other advertisers.
                    retryAtMs = android.os.SystemClock.elapsedRealtime() + SKIP_RETRY_MS
                    return@thread
                }
                val advertiser = adapter.bluetoothLeAdvertiser ?: run {
                    starting.set(false)
                    return@thread
                }
                if (AdvertisingPolicy.stopSettleOn()) budget.awaitPendingStops()
                val newCallback = ReachSetCallback(ownedBefore = budget.owned())
                callbacks.add(newCallback)
                // A start whose callback never comes must not block every later attempt.
                handler.postDelayed({ starting.set(false) }, START_TIMEOUT_MS)
                EncoderBenchGuard.check("ReachAdvertiser.advertise")
                if (AdvertisingPolicy.budgetOn() && budget.simulatedRefusal()) {
                    // Experimental test_adv_budget_cap: behave like a controller whose instances are all in use.
                    handler.post {
                        newCallback.onAdvertisingSetStarted(null, 0, AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
                    }
                    return@thread
                }
                val refused = if (AdvertisingPolicy.nonThrowingStart()) {
                    AdvertisingStart.request(
                        advertiser, TitleAdvertiser.parameters(), TitleAdvertiser.primaryData(),
                        scanResponse(firstPage), newCallback,
                    )
                } else {
                    runCatching {
                        advertiser.startAdvertisingSet(
                            TitleAdvertiser.parameters(), TitleAdvertiser.primaryData(), scanResponse(firstPage), null, null, newCallback,
                        )
                    }.exceptionOrNull()?.let { AdvertisingStart.STATUS_THREW }
                }
                if (refused != null) {
                    callbacks.remove(newCallback)
                    starting.set(false)
                    failedStarts++
                    retryAtMs = android.os.SystemClock.elapsedRealtime() + AdvertisingWatchdog.startFailureBackoffMs(failedStarts, tooMany = false)
                    Log.w("BluetoothVideo", "relayed_helper: reach_pages start failed (${AdvertisingStart.describe(refused)})")
                }
            } catch (error: Exception) {
                starting.set(false)
                Log.w("BluetoothVideo", "relayed_helper: reach_pages start failed ${error.javaClass.simpleName}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopSet() {
        set = null
        // Every set this advertiser ever started, so a late callback cannot leave one transmitting.
        val started = callbacks.toList()
        callbacks.clear()
        started.forEach { callback ->
            callback.stopRequested = true
            budget.release(callback)
            if (callback.startedOk && !callback.deadByStack) budget.noteStopRequested(callback)
            runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(callback) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        stopSet()
    }

    private fun scanResponse(page: ByteArray): AdvertiseData = AdvertiseData.Builder()
        .addManufacturerData(BleL2capControlProtocol.MESH_MANUFACTURER_ID, page)
        .build()

    private companion object {
        /** A set with nothing to say is stopped after this long, which also frees the controller's instance. */
        const val EMPTY_STOP_MS = 15_000L
        const val SKIP_RETRY_MS = 300_000L
        const val START_TIMEOUT_MS = 6_000L
    }
}
