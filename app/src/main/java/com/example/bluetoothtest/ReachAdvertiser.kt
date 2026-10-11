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
 * Experimental relayed calls (PLAN Design C, PROTOCOL "Reach page"): a second legacy, non-connectable,
 * scannable advertising set whose scan response carries the helper's best reach entries, two per page.
 * Modelled on [TitleAdvertiser]: the same service UUID (no new scan-filter slot), 250 ms interval, one page
 * per second (about four advertising events per page, R15), pages rotated in place with
 * setScanResponseData, and the same free-set probe before it starts.
 *
 * Graceful fallback: when the probe finds fewer than two free sets, or the start fails, nothing else
 * changes; the phone is then a helper only for callers that already know its offer. The set stops while
 * there is nothing to advertise for [EMPTY_STOP_MS], and restarts when entries appear.
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
    private val callbacks = java.util.concurrent.CopyOnWriteArrayList<AdvertisingSetCallback>()
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
                val newCallback = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                        lastStartStatus = status
                        // A start stays pending until its callback: a second start must not overlap it.
                        starting.set(false)
                        if (closed.get()) {
                            runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }
                            return
                        }
                        if (status == ADVERTISE_SUCCESS) {
                            set = advertisingSet
                        } else {
                            callbacks.remove(this)
                        }
                        Log.i(
                            "BluetoothVideo",
                            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                                "relayed_helper: reach_pages status=$status | tooMany=${status == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS}",
                        )
                    }
                }
                callbacks.add(newCallback)
                // A start whose callback never comes must not block every later attempt.
                handler.postDelayed({ starting.set(false) }, START_TIMEOUT_MS)
                runCatching {
                    advertiser.startAdvertisingSet(
                        TitleAdvertiser.parameters(),
                        TitleAdvertiser.primaryData(),
                        scanResponse(firstPage),
                        null,
                        null,
                        newCallback,
                    )
                }.onFailure {
                    callbacks.remove(newCallback)
                    starting.set(false)
                    Log.w("BluetoothVideo", "relayed_helper: reach_pages start threw ${it.javaClass.simpleName}")
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
        started.forEach { callback -> runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(callback) } }
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
