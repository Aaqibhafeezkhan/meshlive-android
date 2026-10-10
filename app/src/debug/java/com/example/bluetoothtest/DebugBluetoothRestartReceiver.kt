package com.example.bluetoothtest

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * DEBUG Bluetooth restart for runners, from adb shell only:
 *
 *     adb shell am broadcast -n com.example.bluetoothtest/.DebugBluetoothRestartReceiver
 *
 * The debug manifest protects it with `android.permission.DUMP`, which the shell holds and
 * third-party apps cannot. It replaces the `bt_restart` activity control (any app could send an
 * activity intent). Refused while a Bluetooth session exists or another restart runs; the result
 * is the `BENCHMARK bluetooth_restart` line (see BluetoothRestart).
 */
class DebugBluetoothRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        // onReceive runs on the main thread, where MainActivity's session fields are owned.
        val busy = runCatching { BluetoothRestart.busyProbe?.invoke() }.getOrElse { "probe_failed" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val refusal = when {
            adapter == null -> "no_adapter"
            else -> BluetoothRestart.refusal(Build.VERSION.SDK_INT, busy, alreadyRunning = BluetoothRestart.running)
        }
        if (refusal != null || !BluetoothRestart.tryBegin()) {
            Log.i("BluetoothVideo", "BENCHMARK bluetooth_restart: result=refused | reason=${refusal ?: "already_running"}")
            return
        }
        val pending = goAsync()
        Thread({
            try {
                BluetoothRestart.run(context, requireNotNull(adapter))
            } finally {
                BluetoothRestart.end()
                pending.finish()
            }
        }, "DebugBluetoothRestart").start()
    }
}
