package com.example.bluetoothtest

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import java.io.Closeable

class DebugBenchmarkCoordinator(
    activity: Activity,
    onStatus: (String) -> Unit,
    onFinished: () -> Unit,
) : Closeable {
    fun runOffline(intent: Intent) = Unit

    fun runTransport(
        intent: Intent,
        adapter: BluetoothAdapter,
        transport: BluetoothTransportOption,
    ) = Unit

    override fun close() = Unit
}
