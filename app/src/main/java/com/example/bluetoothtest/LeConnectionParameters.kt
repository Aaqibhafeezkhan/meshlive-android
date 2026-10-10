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

    fun record(link: String, role: LeLinkRole, address: String?, interval: Int, latency: Int, timeout: Int, status: Int) {
        val parameters = LeConnectionParameters(interval, latency, timeout, status)
        address?.let { latest[it] = parameters }
        Log.i("BluetoothVideo", leConnectionParametersLine(link, role, address, parameters))
    }

    /** The last parameters the stack reported for [address] on any link (null before any update). */
    fun latest(address: String?): LeConnectionParameters? = address?.let(latest::get)

    fun forget(address: String?) {
        address?.let(latest::remove)
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
