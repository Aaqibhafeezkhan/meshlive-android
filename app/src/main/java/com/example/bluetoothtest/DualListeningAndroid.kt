package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Android side of dual listening (DualListening.kt holds the rules): the second GATT call service of a CoC
 * endpoint and the process-wide helpers. Nothing here runs with the switch off.
 */
internal object DualListeningRuntime {
    /** Spaces the closing and opening of the second GATT servers of successive availabilities (DualSettleGate). */
    val settle = DualSettleGate(SystemClock::elapsedRealtime)

    /** BENCHMARK-prefixed in DEBUG builds, like every other experiment line; release builds strip `Log.i`. */
    fun log(line: String) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + line)
    }
}

/**
 * The GATT call service ([GattVideoServer] with `advertise = false`) as the second listener of a CoC
 * endpoint. It registers the existing service `c8d5a105-...` in its own `BluetoothGattServer`; the CoC
 * control server's connectable set stays the only advertising set, so a GATT central finds both services
 * at the one advertised address. Until a link is admitted its status messages go to the log only (they
 * would otherwise write GATT RTT/PHY lines into a CoC endpoint's status line); after admission they are the
 * endpoint's own status messages, as for a GATT endpoint.
 */
internal class GattDualSecondaryServer(
    context: Context,
    adapter: BluetoothAdapter,
    announcement: DiscoveryAnnouncement,
    phyPreference: BlePhyPreference,
    private val onStatus: (String) -> Unit,
    onConnection: (GattVideoConnection) -> Unit,
) : DualSecondaryServer {
    @Volatile private var admitted = false

    private val server = GattVideoServer(
        context = context,
        adapter = adapter,
        announcement = announcement,
        phyPreference = phyPreference,
        onStatus = { message -> if (admitted) onStatus(message) else Log.i("BluetoothVideo", message) },
        onConnection = { connection ->
            admitted = true
            onConnection(connection)
        },
    ).also { it.advertise = false }

    override fun start() = server.start()

    override fun close() = server.close()
}
