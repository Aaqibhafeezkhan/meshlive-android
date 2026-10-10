package com.example.bluetoothtest

import android.bluetooth.BluetoothGatt
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * DEBUG experiment (off): a relay requests an explicit connection interval on its child link through the
 * hidden `BluetoothGatt.requestLeConnectionUpdate` (AOSP since Android 8; @hide), to test whether a
 * child interval that differs from the parent link's interval lets the relay's peripheral (parent) and
 * central (child) connections interleave. `test_forwarder_child_interval_units` (1.25 ms units, 6-40).
 */
internal object LeChildInterval {
    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()

    fun register(address: String?, gatt: BluetoothGatt) {
        if (address != null) gatts[address] = gatt
    }

    fun requestLater(address: String?, units: Int, delayMs: Long = 2_000L) {
        Handler(Looper.getMainLooper()).postDelayed({ request(address, units) }, delayMs)
    }

    private fun request(address: String?, units: Int) {
        val gatt = address?.let(gatts::get)
        val queued = gatt != null && runCatching {
            val method = BluetoothGatt::class.java.getMethod(
                "requestLeConnectionUpdate",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            method.invoke(gatt, units, units, 0, 500, 0, 0) as Boolean
        }.onFailure { Log.w("BluetoothVideo", "BENCHMARK child_interval: hidden API unavailable", it) }.getOrDefault(false)
        Log.i("BluetoothVideo", "BENCHMARK child_interval: peer=...${address?.takeLast(5)} | units=$units | ms=${units * 1.25} | queued=$queued")
    }
}
