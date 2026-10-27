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
 *
 * Registration wait (SDK 34 and lower, registry entry gatt-close-before-registration-leak; AOSP f036938ce8 fixed it in
 * android14-qpr2): a BluetoothGatt closed before `onClientRegistered` leaves its registration behind, and repeated
 * early cancels drain the 32-slot pool. A client that has not delivered any callback is disconnected at once but closed
 * when its first callback arrives or after [REGISTRATION_WAIT_MS]. DEBUG `test_gatt_close_deferral=false` closes at once.
 * Every close releases the client's [GattResourceRegistry] entry and notes the peer for [GattLinkLifetime].
 */
internal object GattClientTeardown {
    const val DISCONNECT_WAIT_MS = 2_000L

    /** The bound on waiting for a client's first callback before closing it (backlog G-02). */
    const val REGISTRATION_WAIT_MS = 2_000L

    /** The stack-family rule lives in DeviceFamilyPolicy.GATT_CONTEXT_MAP_RACE; DEBUG may override it. */
    fun deferredFor(fingerprint: BluetoothStackFingerprint, override: Boolean?): Boolean =
        override ?: DeviceFamilyPolicy.gattContextMapRace(fingerprint)

    fun deferred(): Boolean = deferredFor(BluetoothStackFingerprint.current(), DebugTunables.deferredGattClose)

    /**
     * Whether a client with no callback yet must wait for its first one before close (registry entry + switch). Review T4
     * (2026-10-27): a client older than [REGISTRATION_WAIT_MS] registered long ago (registration takes milliseconds; it is
     * only waiting for a connection that never came), so it closes without the wait.
     */
    fun registrationWaitFor(signal: GattTeardownSignal?, nowNs: Long = System.nanoTime()): Boolean =
        signal != null && !signal.callbackSeen && signal.ageMs(nowNs) < REGISTRATION_WAIT_MS && GattSwitches.closeDeferral &&
            runCatching { GattLinkPolicy.closeAfterRegistration() != null }.getOrDefault(false)

    /**
     * Review T4: the disconnect wait protects the stack's connection list while a CONNECTED link is removed. A client that
     * never saw CONNECTED has no entry there, so it closes without waiting. No signal: wait as before.
     */
    fun disconnectWaitFor(deferred: Boolean, signal: GattTeardownSignal?): Boolean =
        deferred && (signal == null || signal.connectedSeen)

    /**
     * Review T4: deferred closes still running, per remote address. A retry dialling the same remote waits (bounded by
     * [PREVIOUS_CLOSE_WAIT_MS]) for the previous client's close, so the stack never holds two clients to one remote
     * (`gatt_dial_check: warnings=second_client_to_same_remote`).
     */
    val pendingCloses = PendingGattCloses()
    const val PREVIOUS_CLOSE_WAIT_MS = 2_500L

    @SuppressLint("MissingPermission")
    fun disconnectAndClose(gatt: BluetoothGatt, signal: GattTeardownSignal? = null, owner: String = "gatt") {
        GattResourceRegistry.shared.markClosing(gatt)
        val address = runCatching { gatt.device?.address }.getOrNull()
        LeChildInterval.unregister(address, gatt)
        val pending = address?.let { pendingCloses.begin(it) }
        run(
            deferred = disconnectWaitFor(deferred(), signal),
            disconnect = { gatt.disconnect() },
            awaitDisconnected = { timeoutMs -> signal?.await(timeoutMs) ?: false },
            close = {
                try {
                    gatt.close()
                    GattResourceRegistry.shared.releaseObject(gatt)?.peer?.let(GattLinkLifetime::noteClosed)
                } finally {
                    if (pending != null && address != null) pendingCloses.end(address, pending)
                }
            },
            async = { body -> thread(name = "GattTeardown-$owner", isDaemon = true) { body() } },
            log = { Log.i("BluetoothVideo", it) },
            owner = owner,
            awaitRegistration = if (registrationWaitFor(signal)) { timeoutMs -> signal?.awaitCallback(timeoutMs) ?: false } else null,
        )
    }

    /**
     * JVM-testable core: the same steps with injected platform calls. [awaitRegistration] non-null means the client
     * has delivered no callback yet: the close then waits (off the caller's thread) for the first callback, bounded by
     * [REGISTRATION_WAIT_MS], before the optional disconnect wait.
     */
    internal fun run(
        deferred: Boolean,
        disconnect: () -> Unit,
        awaitDisconnected: (Long) -> Boolean,
        close: () -> Unit,
        async: (() -> Unit) -> Unit,
        log: (String) -> Unit,
        owner: String,
        awaitRegistration: ((Long) -> Boolean)? = null,
    ) {
        runCatching(disconnect)
        if (!deferred && awaitRegistration == null) {
            runCatching(close)
            return
        }
        async {
            val startedNs = System.nanoTime()
            val registered = awaitRegistration?.let { runCatching { it(REGISTRATION_WAIT_MS) }.getOrDefault(false) }
            val confirmed = if (deferred) runCatching { awaitDisconnected(DISCONNECT_WAIT_MS) }.getOrDefault(false) else null
            runCatching(close)
            log(
                "GATT client closed after ${if (registered != null) "registration" else ""}${if (registered != null && confirmed != null) "+" else ""}" +
                    "${if (confirmed != null) "disconnect" else ""}: owner=$owner | registered=${registered ?: "n/a"} | " +
                    "confirmed=${confirmed ?: "n/a"} | waitedMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
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
    fun onConnectionState(disconnected: Boolean, connected: Boolean = false): Boolean {
        teardown.onCallback()
        if (connected) teardown.onConnected()
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

/** Review T4: one latch per deferred close in flight, by remote address; [await] bounds a redial's wait for it. */
internal class PendingGattCloses {
    private val byAddress = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()

    fun begin(address: String): CountDownLatch = CountDownLatch(1).also { byAddress[address] = it }

    fun end(address: String, closed: CountDownLatch) {
        closed.countDown()
        byAddress.remove(address, closed)
    }

    /** True when no close to [address] is pending or it finished within [boundMs]. */
    fun await(address: String, boundMs: Long): Boolean =
        byAddress[address]?.await(boundMs, TimeUnit.MILLISECONDS) ?: true

    fun pending(address: String): Boolean = byAddress[address]?.count?.let { it > 0L } == true
}

/** Completed by a GATT client's callback when the stack reports the link disconnected. */
internal class GattTeardownSignal(private val createdAtNs: Long = System.nanoTime()) {
    private val latch = CountDownLatch(1)
    private val firstCallback = CountDownLatch(1)
    @Volatile private var connected = false

    fun ageMs(nowNs: Long = System.nanoTime()): Long = (nowNs - createdAtNs) / 1_000_000L

    /** Review T4: the client reported CONNECTED at least once. */
    fun onConnected() {
        connected = true
    }

    val connectedSeen: Boolean get() = connected

    fun onDisconnected() {
        firstCallback.countDown()
        latch.countDown()
    }

    /** Any callback of the client was delivered: the stack finished registering it, so close() is safe (SDK 34 and lower). */
    fun onCallback() = firstCallback.countDown()

    val callbackSeen: Boolean get() = firstCallback.count == 0L

    fun awaitCallback(timeoutMs: Long): Boolean = firstCallback.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** True once the stack reported the link disconnected. */
    val disconnected: Boolean get() = latch.count == 0L

    fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
