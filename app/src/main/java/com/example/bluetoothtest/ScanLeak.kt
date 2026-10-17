package com.example.bluetoothtest

import java.io.File

/**
 * Android 10-11 scanner leak on process death (batch S-09; SDK 30 added after the lane11 ASUS dumpsys). On stock Android 10 a scanner whose app process
 * dies is never unregistered (AOSP 8471db4566, b/143588356, fixed in Android 11): the shared 32-slot
 * scanner/GATT-client pool drains, then `startScan` fails with code 2 and `connectGatt` reports 257 for every
 * app until Bluetooth is toggled. Stopping scans in the service teardown is [ScanRegistry.stopAll]; this
 * file keeps the evidence: a marker that exists while any scanner is registered, so a marker found at the
 * next start means the previous process died with a scanner registered.
 */
internal interface ScanMarkerStore {
    fun markerExists(): Boolean
    fun writeMarker()
    fun clearMarker()

    /** Unclean exits counted in the current Bluetooth session (reset by a reboot or a Bluetooth restart). */
    fun uncleanExits(): Int
    fun setUncleanExits(count: Int)
}

internal class ScanMarkerTracker(private val store: ScanMarkerStore) {
    /** Process start: a marker left behind is one unclean exit. Returns the running count. */
    fun onProcessStart(): Int {
        var count = store.uncleanExits()
        if (store.markerExists()) {
            count++
            store.setUncleanExits(count)
            store.clearMarker()
        }
        return count
    }

    fun onScannerRegistered() = store.writeMarker()

    /** The last scanner left cleanly (stopAll or every owner closed). */
    fun onAllScannersGone() = store.clearMarker()

    /** Bluetooth was restarted: the leaked slots are gone with it. */
    fun onBluetoothRestarted() = store.setUncleanExits(0)
}

internal object ScanLeakClassifier {
    const val SCAN_REGISTRATION_FAILED = 2
    const val GATT_ERROR_257 = 257
    const val LABEL = "android10_scanner_leak"

    /** SDK 30 (lane11 ASUS dumpsys): same symptom, same cause suspected; the stack's own fix did not hold for every kill. */
    const val LABEL_SDK30 = "android11_scanner_leak_suspected"

    /**
     * Names the failure for the diagnostic line. Only SDK 29 (stock Android 10) and SDK 30 (Android 11, measured on the
     * ASUS) and only after at least one unclean exit this session: [LABEL] / [LABEL_SDK30]; the same codes without an
     * unclean exit are "pool_exhausted_other".
     */
    fun classify(sdk: Int, scanErrorCode: Int?, gattStatus: Int?, uncleanExits: Int): String? {
        if (sdk != 29 && sdk != 30) return null
        val hit = scanErrorCode == SCAN_REGISTRATION_FAILED || gattStatus == GATT_ERROR_257
        if (!hit) return null
        return if (uncleanExits < 1) "pool_exhausted_other" else if (sdk == 30) LABEL_SDK30 else LABEL
    }
}

/** Files under the app's private files directory; the count carries the boot count so a reboot resets it. */
internal class FileScanMarkerStore(private val dir: File, private val bootCount: Int?) : ScanMarkerStore {
    private val marker = File(dir, "scan_registered.marker")
    private val counter = File(dir, "scan_unclean_exits.count")

    override fun markerExists(): Boolean = marker.exists()

    override fun writeMarker() {
        if (marker.exists()) return
        runCatching {
            dir.mkdirs()
            marker.writeText("registered")
        }
    }

    override fun clearMarker() {
        runCatching { marker.delete() }
    }

    override fun uncleanExits(): Int = runCatching {
        val parts = counter.readText().split(';')
        if (parts.firstOrNull() != "boot=${bootCount ?: "?"}") 0 else parts.getOrNull(1)?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    override fun setUncleanExits(count: Int) {
        runCatching {
            dir.mkdirs()
            counter.writeText("boot=${bootCount ?: "?"};$count")
        }
    }
}
