package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DEBUG-only Bluetooth restart, the only way to release the LE CoC registrations an Android 10
 * stack leaks (CocListenerHub.kt). Runners used the ColorOS Settings UI for this. Apps may still
 * toggle the adapter through Android 12 (BLUETOOTH_ADMIN, maxSdkVersion 30, and the pre-T
 * enable/disable API); Android 13+ ignores it for this app.
 *
 * Callers: only DebugBluetoothRestartReceiver (debug manifest, `android.permission.DUMP`, so only
 * adb shell can send it). It is refused while any Bluetooth session exists (call endpoint or call,
 * broadcast, receiver, relay, call relay, raw/GATT benchmark), and one restart runs at a time.
 *
 * Verification (review 2026-10-09, finding 2): the public adapter state hides BLE_ON (Android 10
 * BluetoothAdapter.java:998-1004), and BluetoothManagerService.java:839-850 keeps the stack in
 * BLE_ON when a background BLE app is registered, so public OFF/ON alone does not prove the stack
 * restarted. The restart therefore records BLE_STATE_CHANGED (sent with the BLUETOOTH permission,
 * BluetoothManagerService.java:2115-2126) and reports `leReset=verified` only when the LE state
 * reached OFF; `le_kept_on` or `unverified` otherwise.
 */
internal object BluetoothRestart {
    private const val STATE_WAIT_MS = 15_000L
    private const val POLL_MS = 100L

    /** BluetoothAdapter.ACTION_BLE_STATE_CHANGED (@SystemApi, string stable since Android 6). */
    const val ACTION_BLE_STATE_CHANGED = "android.bluetooth.adapter.action.BLE_STATE_CHANGED"
    const val STATE_BLE_ON = 15

    private val inFlight = AtomicBoolean(false)

    /**
     * Set by MainActivity on its main thread: null when no Bluetooth session exists, otherwise the
     * reason to refuse. No activity means no sessions (they are all activity-owned).
     */
    @Volatile var busyProbe: (() -> String?)? = null

    /**
     * Why a restart must be refused now, or null when it may run. The API gate is the
     * `app-bluetooth-toggle-allowed` policy entry (apps may toggle Bluetooth up to API 32).
     */
    fun refusal(sdkInt: Int, busyReason: String?, alreadyRunning: Boolean): String? = when {
        !DeviceFamilyPolicy.resolve(DeviceTraits(hardware = "", board = "", sdkInt = sdkInt))
            .has<AppBluetoothRestartAllowed>() -> "sdk_$sdkInt"
        alreadyRunning -> "already_running"
        busyReason != null -> "busy_$busyReason"
        else -> null
    }

    /**
     * What the LE stack did: `verified` when it reached OFF, `le_kept_on` when it stopped at BLE_ON
     * (a background BLE app kept it up; BLE_ON is also passed on the way to OFF), `unverified` when
     * no LE state was observed.
     */
    fun leReset(leStatesSeen: List<Int>): String = when {
        BluetoothAdapter.STATE_OFF in leStatesSeen -> "verified"
        STATE_BLE_ON in leStatesSeen -> "le_kept_on"
        else -> "unverified"
    }

    /** Single flight: true when this caller now owns the restart. */
    fun tryBegin(): Boolean = inFlight.compareAndSet(false, true)

    fun end() = inFlight.set(false)

    val running: Boolean get() = inFlight.get()

    /** Off, wait, on, wait (off the main thread; the caller owns [tryBegin]). Logs one BENCHMARK line. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun run(context: Context, adapter: BluetoothAdapter) {
        val startMs = SystemClock.elapsedRealtime()
        val leStates = CopyOnWriteArrayList<Int>()
        val leReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                leStates += intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            }
        }
        val app = context.applicationContext
        val registered = runCatching {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(leReceiver, IntentFilter(ACTION_BLE_STATE_CHANGED))
        }.isSuccess
        val bleAlwaysScan = runCatching {
            Settings.Global.getInt(app.contentResolver, "ble_scan_always_enabled", 0)
        }.getOrDefault(-1)
        val result = try {
            runCatching {
                if (adapter.state != BluetoothAdapter.STATE_OFF && !adapter.disable()) return@runCatching "disable_refused"
                if (!waitFor(adapter, BluetoothAdapter.STATE_OFF)) return@runCatching "off_timeout"
                val offMs = SystemClock.elapsedRealtime() - startMs
                // Public OFF is announced at the intermediate BLE_ON step (BluetoothManagerService
                // 2139-2181); enabling now would keep the LE stack up. Wait for the LE state OFF.
                waitForLeOff(leStates)
                if (!adapter.enable()) return@runCatching "enable_refused | offMs=$offMs"
                if (!waitFor(adapter, BluetoothAdapter.STATE_ON)) return@runCatching "on_timeout | offMs=$offMs"
                "on | offMs=$offMs"
            }.getOrElse { "error=${it.javaClass.simpleName}: ${it.message}" }
        } finally {
            if (registered) runCatching { app.unregisterReceiver(leReceiver) }
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK bluetooth_restart: result=$result | leReset=${leReset(leStates)} | " +
                "leStates=${leStates.joinToString(",")} | bleScanAlwaysEnabled=$bleAlwaysScan | " +
                "totalMs=${SystemClock.elapsedRealtime() - startMs} | sdk=${Build.VERSION.SDK_INT}",
        )
    }

    private fun waitForLeOff(leStates: List<Int>) {
        val deadline = SystemClock.elapsedRealtime() + STATE_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (BluetoothAdapter.STATE_OFF in leStates) return
            Thread.sleep(POLL_MS)
        }
    }

    private fun waitFor(adapter: BluetoothAdapter, state: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STATE_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (adapter.state == state) return true
            Thread.sleep(POLL_MS)
        }
        return adapter.state == state
    }
}
