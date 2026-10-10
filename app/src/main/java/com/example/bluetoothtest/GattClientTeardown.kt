package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Root cause (stack bug, Android 10 and 11): GattService `ContextMap.removeConnectionsByAppId`
 * iterates `mConnections` without the lock that `addConnection`/`removeConnection` hold (Android 12
 * added it). BluetoothGatt.close() straight after disconnect() runs `unregisterClient` on a binder
 * thread while the stack's disconnect callback removes the same connection. The A15s threw
 * ConcurrentModificationException at ContextMap.java:300 22 ms after `clientDisconnect`
 * (bmaraw-ab-stream-9c121b30-s24a15s-run2c case 0017, 11:06:44); the exception aborts
 * `unregisterClient` half way (GattService.java:2190-2191), so the app entry and the native client
 * stay registered. AOSP 1a2c1e97d203 (Android 12) added the lock. GATT discovery between that phone
 * pair later failed until the Bluetooth process was replaced (run3c/run3d), but that the exception
 * caused the outage is unproven. The ASUS (Android 11) logged the same class of exception.
 * The stack-family rule is DeviceFamilyPolicy.GATT_CONTEXT_MAP_RACE.
 *
 * On those stacks the app waits for its disconnect to finish before closing: disconnect() now, then
 * close() when the client's callback reports DISCONNECTED ([GattTeardownSignal]) or after
 * [DISCONNECT_WAIT_MS], on a background thread. Owners still mark themselves cancelled first, so
 * late callbacks are ignored as before. Android 12+ closes immediately (unchanged). DEBUG launch
 * extra `test_deferred_gatt_close=true|false` forces either behaviour on any phone.
 */
internal object GattClientTeardown {
    const val DISCONNECT_WAIT_MS = 2_000L

    /** The stack-family rule lives in DeviceFamilyPolicy.GATT_CONTEXT_MAP_RACE; DEBUG may override it. */
    fun deferredFor(fingerprint: BluetoothStackFingerprint, override: Boolean?): Boolean =
        override ?: DeviceFamilyPolicy.gattContextMapRace(fingerprint)

    fun deferred(): Boolean = deferredFor(BluetoothStackFingerprint.current(), DebugTunables.deferredGattClose)

    @SuppressLint("MissingPermission")
    fun disconnectAndClose(gatt: BluetoothGatt, signal: GattTeardownSignal? = null, owner: String = "gatt") {
        run(
            deferred = deferred(),
            disconnect = { gatt.disconnect() },
            awaitDisconnected = { timeoutMs -> signal?.await(timeoutMs) ?: false },
            close = { gatt.close() },
            async = { body -> thread(name = "GattTeardown-$owner", isDaemon = true) { body() } },
            log = { Log.i("BluetoothVideo", it) },
            owner = owner,
        )
    }

    /** JVM-testable core: the same steps with injected platform calls. */
    internal fun run(
        deferred: Boolean,
        disconnect: () -> Unit,
        awaitDisconnected: (Long) -> Boolean,
        close: () -> Unit,
        async: (() -> Unit) -> Unit,
        log: (String) -> Unit,
        owner: String,
    ) {
        runCatching(disconnect)
        if (!deferred) {
            runCatching(close)
            return
        }
        async {
            val startedNs = System.nanoTime()
            val confirmed = runCatching { awaitDisconnected(DISCONNECT_WAIT_MS) }.getOrDefault(false)
            runCatching(close)
            log(
                "GATT client closed after disconnect: owner=$owner | confirmed=$confirmed | " +
                    "waitedMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
            )
        }
    }
}

/**
 * One GATT client attempt (review 2026-10-09, finding 5): its callbacks act only until [cancel], so
 * an expired attempt whose close is deferred cannot request priority, PHY, discovery or reads while
 * a replacement attempt runs. The disconnect signal still fires after cancel.
 */
internal class GattAttempt {
    val teardown = GattTeardownSignal()
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    val active: Boolean get() = !cancelled.get()

    fun cancel() = cancelled.set(true)

    /** Call first in onConnectionStateChange: records a disconnect, then says whether to act. */
    fun onConnectionState(disconnected: Boolean): Boolean {
        if (disconnected) teardown.onDisconnected()
        return active
    }

    /** Cancels the attempt, then disconnects and closes (deferred on racy stacks). */
    @SuppressLint("MissingPermission")
    fun disconnectAndClose(gatt: BluetoothGatt, owner: String) {
        cancel()
        GattClientTeardown.disconnectAndClose(gatt, teardown, owner)
    }
}

/** Completed by a GATT client's callback when the stack reports the link disconnected. */
internal class GattTeardownSignal {
    private val latch = CountDownLatch(1)

    fun onDisconnected() = latch.countDown()

    /** True once the stack reported the link disconnected. */
    val disconnected: Boolean get() = latch.count == 0L

    fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
