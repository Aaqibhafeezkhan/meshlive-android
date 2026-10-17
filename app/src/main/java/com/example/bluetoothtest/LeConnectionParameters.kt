package com.example.bluetoothtest

import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * LE connection parameters as this phone's Bluetooth stack reports them, per link.
 *
 * No public Android API reports the connection interval, yet it decides LE throughput: a link that
 * moves one data-length PDU per connection event carries about 251 bytes per interval (67 kbps at
 * 30 ms, 134 kbps at 15 ms). The ASUS <-> A15s links ran at about 65 kbps on GATT and CoC
 * (mesh4-57484598) and the M31 relay's two links shared airtime badly (relay ceiling 625 kbps), and
 * neither could be explained without the interval of every link.
 *
 * The framework has called `BluetoothGattCallback.onConnectionUpdated(gatt, interval, latency,
 * timeout, status)` and `BluetoothGattServerCallback.onConnectionUpdated(device, ...)` for every LL
 * connection update since Android 8 (AOSP GattService `onClientConnUpdate` / `onServerConnUpdate`),
 * but both methods are `@hide`, so they are not in the SDK stubs. A callback subclass that declares a
 * public method with the same name and signature receives the call (the same technique Nordic's
 * Android-BLE-Library uses); `proguard-rules.pro` keeps those methods in release builds. Units are the
 * Bluetooth Core ones: interval x 1.25 ms, supervision timeout x 10 ms (Core v5.4 Vol 4 Part E
 * 7.7.65.3, LE Connection Update Complete event).
 *
 * This only observes: nothing here changes a request. Lines: `BENCHMARK le_conn_params`.
 */
internal data class LeConnectionParameters(
    val intervalUnits: Int,
    val peripheralLatency: Int,
    val supervisionTimeoutUnits: Int,
    val status: Int,
) {
    val intervalMs: Double get() = intervalUnits * 1.25
    val supervisionTimeoutMs: Int get() = supervisionTimeoutUnits * 10

    /**
     * Upper bound of LE payload one link can carry when its controller sends [pdusPerEvent]
     * data-length PDUs (251 octets, 247 L2CAP payload after the 4-byte basic header) per event.
     * A planning bound for diagnosis only: real events carry fewer PDUs when they collide with
     * another link, scanning or Wi-Fi.
     */
    fun payloadKbpsAt(pdusPerEvent: Int, pduPayloadBytes: Int = DLE_PDU_L2CAP_PAYLOAD_BYTES): Int {
        require(pdusPerEvent > 0 && pduPayloadBytes > 0)
        if (intervalUnits <= 0) return 0
        // bytes per millisecond x 8 = kbit/s
        return (pdusPerEvent * pduPayloadBytes * 8.0 / intervalMs).toInt()
    }

    companion object {
        /** 251-octet LL payload (data length extension) minus the 4-byte L2CAP basic header. */
        const val DLE_PDU_L2CAP_PAYLOAD_BYTES = 247
    }
}

/** Which end of the LE connection this phone is: the GATT client/initiator or the server/advertiser. */
internal enum class LeLinkRole(val label: String) {
    CENTRAL("central"),
    PERIPHERAL("peripheral"),
}

internal object LeConnectionParameterLog {
    private val latest = ConcurrentHashMap<String, LeConnectionParameters>()
    private val updatedAtMs = ConcurrentHashMap<String, Long>()

    fun record(link: String, role: LeLinkRole, address: String?, interval: Int, latency: Int, timeout: Int, status: Int) {
        val parameters = LeConnectionParameters(interval, latency, timeout, status)
        address?.let {
            latest[it] = parameters
            updatedAtMs[it] = System.nanoTime() / 1_000_000L
        }
        Log.i("BluetoothVideo", leConnectionParametersLine(link, role, address, parameters))
    }

    /** The last parameters the stack reported for [address] on any link (null before any update). */
    fun latest(address: String?): LeConnectionParameters? = address?.let(latest::get)

    /** Monotonic ms (System.nanoTime based) of the last report for [address], null before any. */
    fun latestAtMs(address: String?): Long? = address?.let(updatedAtMs::get)

    fun forget(address: String?) {
        address?.let {
            latest.remove(it)
            updatedAtMs.remove(it)
        }
    }
}

internal fun leConnectionParametersLine(
    link: String,
    role: LeLinkRole,
    address: String?,
    parameters: LeConnectionParameters,
): String = String.format(
    Locale.ROOT,
    "BENCHMARK le_conn_params: link=%s | role=%s | peer=...%s | intervalMs=%.2f | latency=%d | " +
        "supervisionMs=%d | status=%d | bound1PduKbps=%d | bound2PduKbps=%d",
    link,
    role.label,
    address?.takeLast(5) ?: "unknown",
    parameters.intervalMs,
    parameters.peripheralLatency,
    parameters.supervisionTimeoutMs,
    parameters.status,
    parameters.payloadKbpsAt(1),
    parameters.payloadKbpsAt(2),
)

/**
 * What `requestConnectionPriority` asks the controller for, per Android release (aosp-bt-versions CONN-01:
 * `res/values/config.xml` and `GattService.connectionParameterUpdate()` of every release 8 to 16).
 * Intervals are in 1.25 ms units, the supervision timeout in ms. The public Javadoc quotes older numbers;
 * this table is the code's.
 *
 *  - HIGH 9-12 (11.25-15 ms), latency 0. BALANCED 24-40 (30-50 ms), latency 0. LOW_POWER 80-100
 *    (100-125 ms), latency 2. DCK (API 34+) 24-24 (30 ms), latency 0; below API 34 DCK is sent as HIGH
 *    ([BleConnectionPriority.requestValue]).
 *  - The request carries a SUPERVISION TIMEOUT: 20 s on Android 8-9 (`timeout = 2000`), 5 s from Android
 *    10 (`timeout = 500`; d32b2d461 "Lower the BLE connection supervision timeout to 5s"). Any silence of
 *    5 s on a link whose parameters were set by a priority request therefore drops it with HCI 0x08
 *    (GATT 8 / 133): this is the cause of the "0x08 after 5.0 s of silence" failures, not a stack timer.
 *  - Not settable by an app: slave latency beyond the table, supervision timeout, connection-event length
 *    (min/max CE are always 0/0), subrating (`requestSubrateMode` is @hide and privileged on Android 16)
 *    and an arbitrary interval (`requestLeConnectionUpdate` is @hide; blocked for apps from Android 11).
 *    Android 14 to 16 have no public interval API apart from CONNECTION_PRIORITY_DCK (CONN-10).
 *  - The stack may apply other numbers: it raises the interval to the global floor sysprop (Android 14+),
 *    to 20 ms while a hearing aid is bonded (CONN-02), and drops a request issued while another update is
 *    pending or while service discovery runs (before Android 16, da2a7e6233 serialises them).
 */
internal data class LePriorityParameters(
    val minIntervalUnits: Int,
    val maxIntervalUnits: Int,
    val latency: Int,
    val supervisionTimeoutMs: Int,
) {
    val minIntervalMs: Double get() = minIntervalUnits * 1.25
    val maxIntervalMs: Double get() = maxIntervalUnits * 1.25

    fun line(link: String, priority: BleConnectionPriority, sdkInt: Int): String = String.format(
        Locale.ROOT,
        "BENCHMARK le_priority_request: link=%s | priority=%s | requestedIntervalMs=%.2f-%.2f | latency=%d | " +
            "supervisionMs=%d | sdk=%d",
        link, priority.label, minIntervalMs, maxIntervalMs, latency, supervisionTimeoutMs, sdkInt,
    )
}

internal fun lePriorityParameters(priority: BleConnectionPriority, sdkInt: Int): LePriorityParameters {
    val supervision = if (sdkInt >= 29) 5_000 else 20_000
    return when {
        priority == BleConnectionPriority.HIGH -> LePriorityParameters(9, 12, 0, supervision)
        priority == BleConnectionPriority.BALANCED -> LePriorityParameters(24, 40, 0, supervision)
        priority == BleConnectionPriority.LOW -> LePriorityParameters(80, 100, 2, supervision)
        // DCK: the platform sends HIGH below API 34.
        sdkInt >= 34 -> LePriorityParameters(24, 24, 0, supervision)
        else -> LePriorityParameters(9, 12, 0, supervision)
    }
}
