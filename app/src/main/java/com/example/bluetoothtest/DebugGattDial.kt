package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.Locale
import kotlin.concurrent.thread

/**
 * DEBUG only, Design QV gate W2: dial one exact BLE address through the production [GattVideoClient] path (LE 1M, the
 * same connect, MTU, service discovery and notification subscribe a stream source uses), write one test chunk, then
 * close. Launch extras: `test_gatt_dial_address=<MAC>`, optional `test_gatt_dial_service=<uuid>` (default and only
 * supported value: the GATT data service c8d5a105) and `test_gatt_dial_address_type=random|public` (default random, as a
 * Windows provider advertises; Android 13+ only, older phones dial the address as Android resolves it).
 *
 * Logs `BENCHMARK debug_gatt_dial: stage=connect|mtu|services|subscribe|first_write | result=ok|failed|... | pdu=...`.
 * A release build never parses the extras ([request] returns null), so nothing here can run there.
 */
internal object DebugGattDial {
    const val EXTRA_ADDRESS = "test_gatt_dial_address"
    const val EXTRA_SERVICE = "test_gatt_dial_service"
    const val EXTRA_ADDRESS_TYPE = "test_gatt_dial_address_type"
    const val DATA_SERVICE = "c8d5a105-9f88-4f65-a03d-7f5a2fb921fa"

    private val MAC = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

    data class Request(val address: String, val service: String, val randomAddress: Boolean)

    /** The dial a launch asks for, or null (no or malformed address, or not a DEBUG build). */
    fun request(address: String?, service: String?, addressType: String?, debug: Boolean = BuildConfig.DEBUG): Request? {
        if (!debug) return null
        val mac = address?.trim()?.uppercase(Locale.US)?.takeIf { MAC.matches(it) } ?: return null
        val uuid = service?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: DATA_SERVICE
        return Request(mac, uuid, randomAddress = !addressType.equals("public", ignoreCase = true))
    }

    /** The stages in order; what the production client reports through its status callback marks progress. */
    enum class Stage(val label: String) { CONNECT("connect"), MTU("mtu"), SERVICES("services"), SUBSCRIBE("subscribe"), FIRST_WRITE("first_write") }

    /** Follows the client's status lines; pure, so the stage logic is tested on a JVM. */
    class Progress {
        var mtu: Int? = null
            private set
        var reached: Stage? = null
            private set

        fun onStatus(message: String) {
            Regex("GATT video MTU (\\d+)").find(message)?.let {
                mtu = it.groupValues[1].toInt()
                reached = Stage.MTU
            }
        }

        /** The client's open returned: the link is up, the data service found and its notifications subscribed. */
        fun opened() {
            reached = Stage.SUBSCRIBE
        }

        fun wrote() {
            reached = Stage.FIRST_WRITE
        }

        /** The stage a failure belongs to: the one after the last reached. */
        fun failedStage(): Stage = when (reached) {
            null -> Stage.CONNECT
            Stage.CONNECT -> Stage.MTU
            Stage.MTU -> Stage.SERVICES
            Stage.SERVICES -> Stage.SUBSCRIBE
            Stage.SUBSCRIBE, Stage.FIRST_WRITE -> Stage.FIRST_WRITE
        }

        fun pdu(): String = mtu?.let { (it - 3).toString() } ?: "unknown"
    }

    fun line(stage: Stage, result: String, pdu: String, extra: String = ""): String =
        "BENCHMARK debug_gatt_dial: stage=${stage.label} | result=$result | pdu=$pdu" + (if (extra.isEmpty()) "" else " | $extra")

    /** Runs [request] on its own thread. Never call outside a DEBUG build. */
    @SuppressLint("MissingPermission")
    fun start(context: Context, adapter: BluetoothAdapter?, request: Request) {
        if (!BuildConfig.DEBUG) return
        thread(name = "DebugGattDial", isDaemon = true) {
            val progress = Progress()
            fun log(stage: Stage, result: String, extra: String = "") = Log.i("BluetoothVideo", line(stage, result, progress.pdu(), extra))
            if (request.service != DATA_SERVICE) {
                log(Stage.CONNECT, "unsupported_service", "service=${request.service}")
                return@thread
            }
            if (adapter == null || !adapter.isEnabled) {
                log(Stage.CONNECT, "bluetooth_off")
                return@thread
            }
            val device: BluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                adapter.getRemoteLeDevice(
                    request.address,
                    if (request.randomAddress) BluetoothDevice.ADDRESS_TYPE_RANDOM else BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                )
            } else {
                adapter.getRemoteDevice(request.address)
            }
            val startedMs = android.os.SystemClock.elapsedRealtime()
            val connection = try {
                GattVideoClient.open(context, device, BlePhyPreference.ONE_M_ADAPTIVE, onStatus = progress::onStatus)
            } catch (error: Exception) {
                log(progress.failedStage(), "failed", "error=${error.message ?: error.javaClass.simpleName}")
                return@thread
            }
            progress.opened()
            val openMs = android.os.SystemClock.elapsedRealtime() - startedMs
            log(Stage.CONNECT, "ok", "ms=$openMs")
            log(Stage.MTU, if (progress.mtu != null) "ok" else "not_reported")
            log(Stage.SERVICES, "ok")
            log(Stage.SUBSCRIBE, "ok")
            try {
                connection.outputStream.write(TEST_WRITE)
                connection.outputStream.flush()
                progress.wrote()
                log(Stage.FIRST_WRITE, "ok", "bytes=${TEST_WRITE.size}")
            } catch (error: Exception) {
                log(Stage.FIRST_WRITE, "failed", "error=${error.message ?: error.javaClass.simpleName}")
            } finally {
                runCatching { connection.close() }
                Log.i("BluetoothVideo", "BENCHMARK debug_gatt_dial: event=closed")
            }
        }
    }

    /** One small test chunk ("W2"), never a valid record: a peer that parses it closes, which is fine here. */
    private val TEST_WRITE = byteArrayOf(0x57, 0x32)
}
