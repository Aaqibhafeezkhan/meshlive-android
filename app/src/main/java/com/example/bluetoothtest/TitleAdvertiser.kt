package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Public stream title (peer labels; Settings > Features "Names and titles"; naming design 3.7, R13/R15): a second legacy,
 * non-connectable, scannable advertising set whose scan response carries [TitlePageCodec] pages.
 * Same service UUID as discovery (no new scan-filter slot), 250 ms interval, one page per 1 s
 * (at least 4 advertising events per page), rotated in place with setScanResponseData.
 *
 * Graceful fallback: if the controller has no free set (probe says 0, or start fails with
 * ADVERTISE_FAILED_TOO_MANY_ADVERTISERS or any other code), nothing else changes; viewers then see
 * the title only after joining (signed copy). The stream's own advertising is never touched.
 *
 * Device-family batch A: this is the second-lowest priority set ([AdvertisingSetRole.TITLE]). It does not start
 * when [AdvertisingBudget] has learned that there is no room for it plus a spare, it stops itself when the
 * session-critical set needs its instance ([yieldForBudget]), its start cannot throw ([AdvertisingStart]), and a
 * set the stack drops on its own is restarted with back-off ([AdvertisingWatchdog]).
 */
internal class TitleAdvertiser(
    private val adapter: BluetoothAdapter,
    /** The pages rotated in place: title pages, or (Visible by name) the name pages of an open window. */
    private val pages: List<ByteArray>,
    selfRotate: Boolean,
) : Closeable {
    constructor(
        adapter: BluetoothAdapter,
        routeToken: Int,
        title: String,
        /**
         * Rotating IDs: restart this set at epoch boundaries with the same pages. An owner that rebuilds the
         * pages with a new route token at the boundary (stream sources and forwarders, phase 1b) passes false
         * and recreates the advertiser instead.
         */
        selfRotate: Boolean = RotatingDiscoveryFeature.enabled,
    ) : this(adapter, TitlePageCodec.pages(routeToken, title), selfRotate)

    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    @Volatile private var set: AdvertisingSet? = null
    private var pageIndex = 0
    private val budget get() = AdvertisingBudget.shared
    private val watchdog = AdvertisingWatchdog()

    /** The session-critical set took this one's instance: it stays off for the rest of this advertiser's life. */
    private val yielded = AtomicBoolean(false)

    /** One callback per advertising set, so a boundary restart (rotating IDs, RV6) starts a new set. */
    private inner class PageSetCallback : AdvertisingSetCallback() {
        val started = CountDownLatch(1)
        @Volatile var status = -1

        /** This advertiser asked for the stop (close, restart, yield): the stop callback is not a loss. */
        @Volatile var stopRequested = false

        /** The stack reported the set stopped: nothing is left to stop. */
        @Volatile var deadByStack = false

        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            this.status = status
            if (this === callback) set = advertisingSet
            started.countDown()
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            deadByStack = true
            budget.noteStopped(this)
            if (stopRequested || closed.get() || yielded.get() || this !== callback || status != ADVERTISE_SUCCESS) return
            // The stack dropped a set this advertiser did not stop.
            set = null
            onUnexpectedStop()
        }
    }

    @Volatile private var callback = PageSetCallback()
    private val started: CountDownLatch get() = callback.started
    private val startStatus: Int get() = callback.status

    /**
     * Experimental rotating discovery IDs (RV6): no app set keeps its address across an epoch
     * boundary. The page set stops, stays silent 1-8 s and starts again as a new set (new address).
     */
    private val boundaryListener: (Long) -> Unit = { _ -> restartOnNewSet() }
    private val rotateWithBoundaries = selfRotate
    /** One restart at a time: a second notice during the silence is coalesced, never a second set. */
    private val restarting = AtomicBoolean(false)

    /** Starts the set (blocking up to [timeoutSeconds]); returns the status code or null on timeout. */
    @SuppressLint("MissingPermission")
    fun start(timeoutSeconds: Long = 5): Int? {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return null
        // Device policy first (registry entry peer-label-title-advertising-set, off-switch included), then what this
        // process has learned about the advertising-set budget.
        val learned = if (AdvertisingPolicy.budgetOn()) budget.skipReason(AdvertisingSetRole.TITLE) else null
        val policy = TitleAdvertisingDecision.decide(PeerLabelPolicy.titleSetAllowed(), freeSets = null, budgetSkip = learned)
        if (policy is TitleAdvertisingDecision.Outcome.Skip) {
            Log.i("BluetoothVideo", "BENCHMARK peer_labels title_pages: skipped (${policy.reason})")
            return null
        }
        // Headroom: start only when the probe saw at least MIN_FREE_SETS free sets, so one instance
        // stays free for the stream offer's own stop + restart (balanced fallback, re-advertise) and
        // for OS advertisers. Unprobed (e.g. a viewer-forwarder that never streamed): probe first.
        val free = AdvertisingSetProbe.lastFree ?: runCatching { AdvertisingSetProbe.run(adapter) }.getOrDefault(0)
        if (free < MIN_FREE_SETS) {
            Log.i("BluetoothVideo", "BENCHMARK peer_labels title_pages: skipped (probe found $free free advertising sets, need $MIN_FREE_SETS)")
            return null
        }
        if (AdvertisingPolicy.stopSettleOn()) budget.awaitPendingStops()
        val ownedBefore = budget.owned()
        EncoderBenchGuard.check("TitleAdvertiser.start")
        val refused = if (AdvertisingPolicy.budgetOn() && budget.simulatedRefusal()) {
            callback.status = AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
            callback.started.countDown()
            null
        } else {
            requestSet(advertiser, scanResponse(pages[0]), callback)
        }
        if (refused != null) {
            Log.w("BluetoothVideo", "BENCHMARK peer_labels title_pages: start failed (${AdvertisingStart.describe(refused)})")
            return null
        }
        if (!started.await(timeoutSeconds, TimeUnit.SECONDS)) {
            close()
            Log.w("BluetoothVideo", "BENCHMARK peer_labels title_pages: start timeout")
            return null
        }
        val status = startStatus
        Log.i(
            "BluetoothVideo",
            "BENCHMARK peer_labels title_pages: status=$status | pages=${pages.size} | " +
                "tooMany=${status == AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS}",
        )
        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            budget.noteRefused(AdvertisingSetRole.TITLE, status, ownedBefore, android.os.Build.VERSION.SDK_INT)
            close()
            return status
        }
        budget.acquire(callback, AdvertisingSetRole.TITLE) { yieldForBudget() }
        if (pages.size > 1) handler.postDelayed(::rotate, PAGE_MS)
        if (rotateWithBoundaries) RotatingDiscovery.addBoundaryListener(boundaryListener)
        return status
    }

    /** The platform call: guarded ([AdvertisingStart]), or direct when the non-throwing entry is switched off. */
    @SuppressLint("MissingPermission")
    private fun requestSet(
        advertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        scanResponse: AdvertiseData,
        target: PageSetCallback,
    ): Int? {
        if (!AdvertisingPolicy.nonThrowingStart()) {
            return runCatching {
                advertiser.startAdvertisingSet(parameters(), primaryData(), scanResponse, null, null, target)
            }.exceptionOrNull()?.let { AdvertisingStart.STATUS_THREW }
        }
        return AdvertisingStart.request(advertiser, parameters(), primaryData(), scanResponse, target)
    }

    /** Stops [target], keeping the budget's books: its lease ends now, its instance is free when the stack confirms. */
    @SuppressLint("MissingPermission")
    private fun stopSet(target: PageSetCallback) {
        target.stopRequested = true
        budget.release(target)
        if (target.status == AdvertisingSetCallback.ADVERTISE_SUCCESS && !target.deadByStack) budget.noteStopRequested(target)
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(target) }
    }

    /**
     * The session-critical set was refused for lack of an instance and asks this one to release its own
     * ([AdvertisingBudget.preemptFor]): the title set stops and stays off. Viewers see the title after joining.
     */
    fun yieldForBudget() {
        if (!yielded.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        set = null
        stopSet(callback)
        Log.i("BluetoothVideo", "BENCHMARK peer_labels title_pages: yielded to the stream's own advertisement")
    }

    /** An unexpected stop of the running set (A-03): restart with back-off, or give up quietly (the title is optional). */
    private fun onUnexpectedStop() {
        when (val decision = watchdog.onUnexpectedStop()) {
            is AdvertisingWatchdog.Decision.Restart -> {
                Log.i("BluetoothVideo", "BENCHMARK adv_watchdog: label=title_pages | event=restart_planned | delayMs=${decision.delayMs} | enabled=${AdvertisingPolicy.watchdogOn()}")
                if (AdvertisingPolicy.watchdogOn()) recover(decision)
            }
            is AdvertisingWatchdog.Decision.GiveUp ->
                Log.i("BluetoothVideo", "BENCHMARK adv_watchdog: label=title_pages | event=give_up | reason=${decision.reason}")
            else -> Unit
        }
    }

    private fun recover(first: AdvertisingWatchdog.Decision.Restart) {
        if (!restarting.compareAndSet(false, true)) return
        Thread {
            try {
                var decision: AdvertisingWatchdog.Decision = first
                while (!closed.get() && !yielded.get()) {
                    val restart = decision as? AdvertisingWatchdog.Decision.Restart ?: break
                    runCatching { Thread.sleep(restart.delayMs) }
                    if (closed.get() || yielded.get()) break
                    if (restartBody(silenceMs = 0L)) break
                    decision = watchdog.onRestartFailed()
                }
            } finally {
                restarting.set(false)
            }
        }.apply { name = "TitlePageWatchdog" }.start()
    }

    /**
     * Experimental rotating discovery IDs (RV6): no app set keeps its address across an epoch
     * boundary. The page set stops, stays silent 1-8 s and starts again as a new set (new address).
     */
    @SuppressLint("MissingPermission")
    private fun restartOnNewSet() {
        if (closed.get() || yielded.get() || !restarting.compareAndSet(false, true)) return
        Thread {
            try {
                restartBody(RotatingDiscovery.boundarySilenceMs())
            } finally {
                restarting.set(false)
            }
        }.apply { name = "TitlePageIdRotation" }.start()
    }

    /** Stops the old set, stays silent for [silenceMs], starts a new one. True when the new set is advertising. */
    @SuppressLint("MissingPermission")
    private fun restartBody(silenceMs: Long): Boolean {
        val old = callback
        set = null
        stopSet(old)
        if (silenceMs > 0) runCatching { Thread.sleep(silenceMs) }
        if (closed.get() || yielded.get()) return false
        // A learned budget with no room, or an instance whose stop is not confirmed yet, decide before the request.
        if (AdvertisingPolicy.budgetOn()) {
            budget.skipReason(AdvertisingSetRole.TITLE)?.let { reason ->
                Log.i("BluetoothVideo", "BENCHMARK peer_labels title_pages: restart skipped ($reason)")
                return true
            }
        }
        if (AdvertisingPolicy.stopSettleOn()) budget.awaitPendingStops()
        val next = PageSetCallback()
        callback = next
        val advertiser = adapter.bluetoothLeAdvertiser ?: return false
        val ownedBefore = budget.owned()
        EncoderBenchGuard.check("TitleAdvertiser.page")
        val refused = if (AdvertisingPolicy.budgetOn() && budget.simulatedRefusal()) {
            next.status = AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
            next.started.countDown()
            null
        } else {
            requestSet(advertiser, scanResponse(pages[pageIndex % pages.size]), next)
        }
        if (refused != null) {
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK rotid_restart: role=title_pages | ok=false | ${AdvertisingStart.describe(refused)}")
            return false
        }
        val ok = next.started.await(5, TimeUnit.SECONDS) && next.status == AdvertisingSetCallback.ADVERTISE_SUCCESS
        if (ok) {
            budget.acquire(next, AdvertisingSetRole.TITLE) { yieldForBudget() }
        } else if (next.status != -1) {
            budget.noteRefused(AdvertisingSetRole.TITLE, next.status, ownedBefore, android.os.Build.VERSION.SDK_INT)
        }
        if (closed.get() || yielded.get()) stopSet(next)
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK rotid_restart: role=title_pages | ok=$ok")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun rotate() {
        if (closed.get() || yielded.get()) return
        pageIndex = (pageIndex + 1) % pages.size
        runCatching { set?.setScanResponseData(scanResponse(pages[pageIndex])) }
        handler.postDelayed(::rotate, PAGE_MS)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        if (rotateWithBoundaries) RotatingDiscovery.removeBoundaryListener(boundaryListener)
        stopSet(callback)
    }

    companion object {
        /** One page per second at a 250 ms interval: about 4 advertising events per page (R15). */
        const val PAGE_MS = 1_000L

        /** Free legacy sets the probe must have seen: one for the title, one kept spare. */
        const val MIN_FREE_SETS = 2

        internal fun parameters(): AdvertisingSetParameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()

        internal fun primaryData(): AdvertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleL2capControlProtocol.SERVICE_UUID))
            .build()

        private fun scanResponse(page: ByteArray): AdvertiseData = AdvertiseData.Builder()
            .addManufacturerData(BleL2capControlProtocol.MESH_MANUFACTURER_ID, page)
            .build()
    }
}

/**
 * R13 probe: how many more legacy advertising sets this phone's controller accepts right now,
 * while the app's own sets run (OS advertisers share the same instances). It starts up to [max]
 * minimal non-connectable sets (no manufacturer data, so scanners ignore them), counts successes and
 * stops them all. Logged as `BENCHMARK adv_set_probe` for the capability provenance; the title
 * advertiser skips itself when the last probe found 0 free sets.
 *
 * The result also teaches [AdvertisingBudget]: a refusal after `free` successes with the app holding `owned` sets
 * is a budget of `owned + free`. The flag `isMultipleAdvertisementSupported` is only logged, never used: it is false
 * on phones where advertising works.
 */
internal object AdvertisingSetProbe {
    @Volatile var lastFree: Int? = null

    @SuppressLint("MissingPermission")
    fun run(adapter: BluetoothAdapter, max: Int = 4, timeoutSeconds: Long = 3): Int {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return 0.also { lastFree = 0 }
        val budget = AdvertisingBudget.shared
        if (AdvertisingPolicy.stopSettleOn()) budget.awaitPendingStops()
        val ownedNow = budget.owned()
        val callbacks = mutableListOf<AdvertisingSetCallback>()
        val granted = mutableListOf<AdvertisingSetCallback>()
        val statuses = mutableListOf<Int>()
        try {
            for (attempt in 0 until max) {
                val latch = CountDownLatch(1)
                var status = -1
                val callback = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, code: Int) {
                        status = code
                        latch.countDown()
                    }

                    override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                        budget.noteStopped(this)
                    }
                }
                callbacks += callback
                EncoderBenchGuard.check("TitleAdvertiser.extra")
                if (AdvertisingPolicy.budgetOn() && budget.simulatedRefusal()) {
                    status = AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
                    latch.countDown()
                } else {
                    val parameters = AdvertisingSetParameters.Builder()
                        .setLegacyMode(true)
                        .setConnectable(false)
                        .setScannable(false)
                        .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_ULTRA_LOW)
                        .build()
                    val refused = if (AdvertisingPolicy.nonThrowingStart()) {
                        AdvertisingStart.request(advertiser, parameters, AdvertiseData.Builder().build(), null, callback)
                    } else {
                        runCatching {
                            advertiser.startAdvertisingSet(parameters, AdvertiseData.Builder().build(), null, null, null, callback)
                        }.exceptionOrNull()?.let { AdvertisingStart.STATUS_THREW }
                    }
                    if (refused != null) {
                        status = refused
                        latch.countDown()
                    }
                }
                latch.await(timeoutSeconds, TimeUnit.SECONDS)
                statuses += status
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) break
                granted += callback
                budget.acquire(callback, AdvertisingSetRole.PROBE)
            }
        } finally {
            callbacks.forEach { callback ->
                budget.release(callback)
                if (callback in granted) budget.noteStopRequested(callback)
                runCatching { advertiser.stopAdvertisingSet(callback) }
            }
        }
        val free = statuses.takeWhile { it == AdvertisingSetCallback.ADVERTISE_SUCCESS }.size
        lastFree = free
        val refusedStatus = statuses.firstOrNull { it != AdvertisingSetCallback.ADVERTISE_SUCCESS }
        val tooMany = refusedStatus == AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
        if (AdvertisingPolicy.budgetOn()) {
            budget.noteProbe(free, ownedNow, refused = tooMany)
            // Refused with nothing owned: other apps or a leaked stack hold every instance.
            if (tooMany && free == 0 && ownedNow == 0) {
                budget.noteRefused(AdvertisingSetRole.PROBE, AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS, 0, android.os.Build.VERSION.SDK_INT)
            }
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adv_set_probe: free=$free | max=$max | statuses=${statuses.joinToString(",")} | owned=$ownedNow | " +
                "extAdv=${runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrNull()} | " +
                "multiAdv=${runCatching { adapter.isMultipleAdvertisementSupported }.getOrNull()} | " +
                "maxAdvData=${runCatching { adapter.leMaximumAdvertisingDataLength }.getOrNull()}",
        )
        return free
    }
}
