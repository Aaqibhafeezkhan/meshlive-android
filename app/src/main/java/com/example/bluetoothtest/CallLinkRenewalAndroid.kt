package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android side of the Experimental in-call link renewal (Design SR, [CallRenewalWire]): the scan for the armed phone's
 * advert and the renewal opening's byte exchange. The call wiring lives in AndroidPrivateVideoCall.
 */
internal object CallRenewalAndroid {
    private val random = SecureRandom()

    fun ticket(): Long {
        var value = 0L
        while (value == 0L) value = random.nextLong()
        return value
    }

    fun randomBytes(): ByteArray = ByteArray(CallRenewalCrypto.RANDOM_BYTES).also(random::nextBytes)

    fun log(role: String, phase: String, detail: String = "") {
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_renewal: role=$role | phase=$phase" +
                (if (detail.isEmpty()) "" else " | $detail"),
        )
    }

    /**
     * DEBUG log of what kind of address the renewal advert used, from its two top bits (Core Vol 6 Part B 1.3): rpa (01),
     * static (11) or other (00: non-resolvable private; a public address cannot be told apart here). Never the address.
     */
    fun addressKind(address: String?): String {
        val top = address?.take(2)?.toIntOrNull(16) ?: return "unknown"
        return when (top shr 6) {
            0b01 -> "rpa"
            0b11 -> "static"
            else -> "other"
        }
    }
}

/** One timed scan for the armed phone's per-attempt service UUID (through the app's scan-start gate). */
@SuppressLint("MissingPermission")
internal class CallRenewalScanner(private val adapter: BluetoothAdapter) {
    private val running = AtomicBoolean(false)
    @Volatile private var callback: ScanCallback? = null

    /** False when no scan could start (no scanner, or the scan-start gate refused it). */
    fun start(uuid: UUID, onFound: (BluetoothDevice) -> Unit): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        if (!running.compareAndSet(false, true)) return true
        if (!ScanPolicy.reserveBlocking(ScanStartGate.shared, runCatching { BluetoothTransportPolicy.scanBudget() }.getOrNull(), maxWaitMs = 2_000L)) {
            running.set(false)
            return false
        }
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (running.get()) onFound(result.device)
            }
        }
        callback = scanCallback
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        EncoderBenchGuard.check("CallRenewalScanner.start")
        return try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            ScanRegistry.register(REGISTRY_NAME, stop = { stop() }, start = {}, renewable = false, restartable = false)
            true
        } catch (error: RuntimeException) {
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        callback?.let { active -> runCatching { adapter.bluetoothLeScanner?.stopScan(active) } }
        callback = null
        ScanRegistry.unregister(REGISTRY_NAME)
    }

    private companion object {
        const val REGISTRY_NAME = "call-renewal-scan"
    }
}
