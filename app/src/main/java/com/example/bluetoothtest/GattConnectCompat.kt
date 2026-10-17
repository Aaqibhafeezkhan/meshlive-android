package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The one place a GATT client is created (G-06, fixed interface of the backlog, section 8):
 * `GattConnectCompat.connect(device, context, callback, transport): BluetoothGatt?`.
 *
 * Two arms. Worker (default): `connectGatt` runs on the calling thread, as every caller did before. Main looper
 * (Experimental `test_connect_gatt_main`): the call is posted to the main looper and the caller waits for the result on
 * its own thread, never blocking the main thread. Reports of Huawei P20 Lite (Android-BLE-Library#159: no callback
 * unless `connectGatt` runs on the main thread), Gadgetbridge ("main thread appears to fix Samsung") and Berty (posts
 * connectGatt to a main handler under a 15 s timer) motivate the arm; it is unmeasured on the MeshLive phones.
 *
 * Every client created here is entered in the [GattResourceRegistry] (owner, peer) with a closer, so a leaked client
 * shows in the counts and an exhausted pool can close idle ones. [GattClientTeardown] releases it on close.
 */
internal object GattConnectCompat {
    /** How long a caller waits for a connect posted to the main looper (Berty uses 15 s for its whole connect). */
    const val MAIN_POST_TIMEOUT_MS = 5_000L

    @SuppressLint("MissingPermission")
    fun connect(
        device: BluetoothDevice,
        context: Context,
        callback: BluetoothGattCallback,
        transport: Int = BluetoothDevice.TRANSPORT_LE,
        owner: String = "gatt",
        autoConnect: Boolean = false,
        /** Initiating PHY mask for the five-argument `connectGatt` (API 26+); null uses the four-argument form. */
        phyMask: Int? = null,
    ): BluetoothGatt? {
        val arm = if (GattSwitches.connectOnMain) Arm.MAIN_LOOPER else Arm.WORKER
        val appContext = context.applicationContext
        val gatt = connectOn(
            arm = arm,
            onMainThread = Looper.myLooper() == Looper.getMainLooper(),
            post = { action -> Handler(Looper.getMainLooper()).post(action) },
            call = {
                if (phyMask != null) {
                    device.connectGatt(appContext, autoConnect, callback, transport, phyMask)
                } else {
                    device.connectGatt(appContext, autoConnect, callback, transport)
                }
            },
            onLate = { late -> runCatching { late.disconnect() }; runCatching { late.close() } },
            log = GattResourceRegistry.Companion::logQuietly,
        )
        if (gatt != null) {
            GattResourceRegistry.shared.registerObject(
                gatt, owner, GattResourceRegistry.Kind.CLIENT, device.address,
                closer = { runCatching { gatt.disconnect() }; runCatching { gatt.close() } },
            )
        }
        return gatt
    }

    enum class Arm(val label: String) { WORKER("worker"), MAIN_LOOPER("main") }

    /**
     * JVM-testable core. [post] hands [call] to the main looper; the caller waits at most [timeoutMs] for it. A call that
     * never runs (main looper blocked) returns null so the caller's own attempt deadline reports the failure, and the
     * late result, if any, is disconnected and closed by [onLate] rather than leaked.
     */
    internal fun <T : Any> connectOn(
        arm: Arm,
        onMainThread: Boolean,
        post: (() -> Unit) -> Boolean,
        call: () -> T?,
        timeoutMs: Long = MAIN_POST_TIMEOUT_MS,
        onLate: (T) -> Unit = {},
        log: (String) -> Unit = {},
    ): T? {
        if (arm == Arm.WORKER || onMainThread) {
            log("GATT connect: arm=${arm.label} | ran=${if (onMainThread) "main" else "worker"}")
            return call()
        }
        val result = AtomicReference<T?>()
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = CountDownLatch(1)
        val posted = post {
            val created = runCatching(call).getOrNull()
            if (abandoned.get() && created != null) {
                onLate(created)
            } else {
                result.set(created)
            }
            done.countDown()
        }
        if (!posted) {
            log("GATT connect: arm=main | post refused, ran on worker")
            return call()
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            abandoned.set(true)
            // The posted call may have finished between the timeout and the flag: take its result then.
            val late = result.getAndSet(null)
            if (late != null) return late
            log("GATT connect: arm=main | main looper did not run the call within $timeoutMs ms")
            return null
        }
        log("GATT connect: arm=main | ran=main")
        return result.get()
    }
}
