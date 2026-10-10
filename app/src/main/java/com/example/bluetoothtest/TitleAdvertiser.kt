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
 * Experimental public stream title (peer labels; naming design 3.7, R13/R15): a second legacy,
 * non-connectable, scannable advertising set whose scan response carries [TitlePageCodec] pages.
 * Same service UUID as discovery (no new scan-filter slot), 250 ms interval, one page per 1 s
 * (at least 4 advertising events per page), rotated in place with setScanResponseData.
 *
 * Graceful fallback: if the controller has no free set (probe says 0, or start fails with
 * ADVERTISE_FAILED_TOO_MANY_ADVERTISERS or any other code), nothing else changes; viewers then see
 * the title only after joining (signed copy). The stream's own advertising is never touched.
 */
internal class TitleAdvertiser(
    private val adapter: BluetoothAdapter,
    routeToken: Int,
    title: String,
) : Closeable {
    private val pages = TitlePageCodec.pages(routeToken, title)
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    @Volatile private var set: AdvertisingSet? = null
    private var pageIndex = 0

    /** One callback per advertising set, so a boundary restart (rotating IDs, RV6) starts a new set. */
    private inner class PageSetCallback : AdvertisingSetCallback() {
        val started = CountDownLatch(1)
        @Volatile var status = -1

        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            this.status = status
            if (this === callback) set = advertisingSet
            started.countDown()
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
    private val rotateWithBoundaries = RotatingDiscoveryFeature.enabled
    /** One restart at a time: a second notice during the silence is coalesced, never a second set. */
    private val restarting = AtomicBoolean(false)

    /** Starts the set (blocking up to [timeoutSeconds]); returns the status code or null on timeout. */
    @SuppressLint("MissingPermission")
    fun start(timeoutSeconds: Long = 5): Int? {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return null
        // Headroom: start only when the probe saw at least MIN_FREE_SETS free sets, so one instance
        // stays free for the stream offer's own stop + restart (balanced fallback, re-advertise) and
        // for OS advertisers. Unprobed (e.g. a viewer-forwarder that never streamed): probe first.
        val free = AdvertisingSetProbe.lastFree ?: runCatching { AdvertisingSetProbe.run(adapter) }.getOrDefault(0)
        if (free < MIN_FREE_SETS) {
            Log.i("BluetoothVideo", "BENCHMARK peer_labels title_pages: skipped (probe found $free free advertising sets, need $MIN_FREE_SETS)")
            return null
        }
        runCatching {
            advertiser.startAdvertisingSet(parameters(), primaryData(), scanResponse(pages[0]), null, null, callback)
        }.onFailure {
            Log.w("BluetoothVideo", "BENCHMARK peer_labels title_pages: start threw ${it.javaClass.simpleName}")
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
            close()
            return status
        }
        if (pages.size > 1) handler.postDelayed(::rotate, PAGE_MS)
        if (rotateWithBoundaries) RotatingDiscovery.addBoundaryListener(boundaryListener)
        return status
    }

    @SuppressLint("MissingPermission")
    private fun restartOnNewSet() {
        if (closed.get() || !restarting.compareAndSet(false, true)) return
        Thread {
            try {
                restartBody()
            } finally {
                restarting.set(false)
            }
        }.apply { name = "TitlePageIdRotation" }.start()
    }

    @SuppressLint("MissingPermission")
    private fun restartBody() {
        run {
            val old = callback
            set = null
            runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(old) }
            runCatching { Thread.sleep(RotatingDiscovery.boundarySilenceMs()) }
            if (closed.get()) return
            val next = PageSetCallback()
            callback = next
            val advertiser = adapter.bluetoothLeAdvertiser ?: return
            runCatching {
                advertiser.startAdvertisingSet(parameters(), primaryData(), scanResponse(pages[pageIndex % pages.size]), null, null, next)
            }
            val ok = next.started.await(5, TimeUnit.SECONDS) && next.status == AdvertisingSetCallback.ADVERTISE_SUCCESS
            if (closed.get()) runCatching { advertiser.stopAdvertisingSet(next) }
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK rotid_restart: role=title_pages | ok=$ok")
        }
    }

    @SuppressLint("MissingPermission")
    private fun rotate() {
        if (closed.get()) return
        pageIndex = (pageIndex + 1) % pages.size
        runCatching { set?.setScanResponseData(scanResponse(pages[pageIndex])) }
        handler.postDelayed(::rotate, PAGE_MS)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        if (rotateWithBoundaries) RotatingDiscovery.removeBoundaryListener(boundaryListener)
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(callback) }
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
 */
internal object AdvertisingSetProbe {
    @Volatile var lastFree: Int? = null

    @SuppressLint("MissingPermission")
    fun run(adapter: BluetoothAdapter, max: Int = 4, timeoutSeconds: Long = 3): Int {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return 0.also { lastFree = 0 }
        val callbacks = mutableListOf<AdvertisingSetCallback>()
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
                }
                callbacks += callback
                runCatching {
                    advertiser.startAdvertisingSet(
                        AdvertisingSetParameters.Builder()
                            .setLegacyMode(true)
                            .setConnectable(false)
                            .setScannable(false)
                            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_ULTRA_LOW)
                            .build(),
                        AdvertiseData.Builder().build(),
                        null,
                        null,
                        null,
                        callback,
                    )
                }
                latch.await(timeoutSeconds, TimeUnit.SECONDS)
                statuses += status
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) break
            }
        } finally {
            callbacks.forEach { runCatching { advertiser.stopAdvertisingSet(it) } }
        }
        val free = statuses.takeWhile { it == AdvertisingSetCallback.ADVERTISE_SUCCESS }.size
        lastFree = free
        Log.i(
            "BluetoothVideo",
            "BENCHMARK adv_set_probe: free=$free | max=$max | statuses=${statuses.joinToString(",")} | " +
                "extAdv=${runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrNull()} | " +
                "multiAdv=${runCatching { adapter.isMultipleAdvertisementSupported }.getOrNull()} | " +
                "maxAdvData=${runCatching { adapter.leMaximumAdvertisingDataLength }.getOrNull()}",
        )
        return free
    }
}
