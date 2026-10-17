package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The stream's own (non-connectable) discovery advertisement: the session-critical [AdvertisingSetRole.STABLE] set.
 * Starting it never throws a platform exception into the caller and a refused start releases a title or reach set
 * first ([StableAdvertisingSet], device-family batch A). Advertising is never gated on
 * `isMultipleAdvertisementSupported()`, which is false on phones where advertising works.
 */
class BleDiscoveryAdvertiser(
    adapter: BluetoothAdapter,
    private val announcement: DiscoveryAnnouncement,
    private val onStatus: (String) -> Unit,
    private val startLowLatency: Boolean = true,
) : Closeable {
    init {
        if (adapter.bluetoothLeAdvertiser == null) throw IOException("This phone cannot advertise over BLE")
    }
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    /** Keeps its random address through the low-latency -> balanced step (StableAdvertisingSet). */
    private val advertisement = StableAdvertisingSet(
        adapter,
        connectable = false,
        data = advertisingData(),
        scanResponse = scanResponse(),
        label = "Discovery",
        onStatus = onStatus,
        onLost = ::onAdvertisingLost,
    )

    /** A failed fallback left nothing advertising: clear the state and start once more. */
    private fun onAdvertisingLost(reason: String) {
        running.set(false)
        if (closed.get()) return
        thread(name = "DiscoveryReadvertise") {
            val status = runCatching { advertisement.start(lowLatency = false, START_TIMEOUT_SECONDS) }.getOrNull()
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS && !closed.get()) {
                running.set(true)
            } else {
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) advertisement.stop()
                onStatus("Discovery is not advertising ($reason); restart failed (code ${status ?: "timeout"})")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        check(!closed.get()) { "Discovery advertiser is closed" }
        check(!running.get()) { "Discovery advertising is already active" }
        val status = advertisement.start(startLowLatency, START_TIMEOUT_SECONDS)
        if (status == null) {
            close()
            throw IOException("Timed out advertising the stream")
        }
        if (closed.get()) {
            advertisement.stop()
            throw IOException("Stream advertising was cancelled")
        }
        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            // The platform failure in words (and the stuck-slots remedy when MeshLive owns no set and still gets none).
            val stuck = AdvertisingBudget.shared.stuck()?.takeIf { AdvertisingPolicy.stuckReportOn() }
            throw IOException("Stream advertising failed (code $status: ${AdvertisingStart.describe(status)})" + (stuck?.let { ". ${it.message}" } ?: ""))
        }
        running.set(true)
        if (startLowLatency) {
            handler.postDelayed(::switchToBalancedAdvertising, LOW_LATENCY_ADVERTISE_BURST_MS)
        }
        onStatus("Stream available over BLE; token ${announcement.streamToken.toUInt().toString(16)}")
    }

    @SuppressLint("MissingPermission")
    private fun switchToBalancedAdvertising() {
        if (closed.get() || !running.get()) return
        // Same set, same address (StableAdvertisingSet); the control arm restarts the set as before.
        if (DebugTunables.stableAdvertisingAddress && advertisement.switchToBalanced()) return
        thread(name = "DiscoveryAdvertiseBalance") {
            advertisement.stop()
            if (closed.get()) return@thread
            val status = runCatching { advertisement.start(lowLatency = false, START_TIMEOUT_SECONDS) }.getOrNull()
            if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                running.set(false)
                onStatus("Balanced BLE advertising failed (code ${status ?: "timeout"})")
            }
        }
    }

    private fun advertisingData(): AdvertiseData = AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(BleL2capControlProtocol.SERVICE_UUID))
        .build()

    private fun scanResponse(): AdvertiseData = AdvertiseData.Builder()
        .addManufacturerData(
            BleL2capControlProtocol.MESH_MANUFACTURER_ID,
            DiscoveryAnnouncementCodec.encode(announcement),
        )
        .build()

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        running.set(false)
        advertisement.stop()
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 8L
        const val LOW_LATENCY_ADVERTISE_BURST_MS = 10_000L
    }
}
