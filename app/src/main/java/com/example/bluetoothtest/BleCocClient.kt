package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Looper
import android.util.Log

/**
 * Root cause (stack bug, Android 12 and 13): when we are LE central, the stack starts EATT on every
 * LE link. A peer that refuses it (the S24 refuses on an unencrypted link) fails only the first of
 * the five requested channels; the other four stay waiting for a connect response, registered to
 * EATT, which has no connect-confirm callback. Our later LE CoC response is matched to the first
 * channel with the same signal id, the stale channel can win, and the Bluetooth process jumps to a
 * null callback (M31 -> S24, xpair2-s24m31-431d40dc/0003). DeviceFamilyPolicy.LE_COC_EATT_STRANDED_CHANNELS
 * holds the sources.
 *
 * On those stacks the CoC client reads the PSM over GATT as before, closes that GATT client and waits
 * (at most [BleCocFreshAcl.ACL_DOWN_WAIT_MS]) for the ACL to drop, which also drops the stranded
 * channels. The CoC connect then creates a fresh ACL: its channel is queued before EATT starts, so the
 * response finds it first. A new GATT client is attached afterwards for link priority and PHY.
 */
internal object BleCocClient {
    /**
     * Connects an LE CoC client channel to [device]. [onControl] receives each GATT control connection
     * the caller must own and close (null while none is open).
     */
    @SuppressLint("MissingPermission")
    fun connect(
        context: Context,
        device: BluetoothDevice,
        onLinkStatus: (String) -> Unit,
        phyPreference: BlePhyPreference,
        owner: String,
        connectionPriority: BleConnectionPriority = BleConnectionPriority.HIGH,
        /** Dual listening: receives the CoC socket the moment it exists, so a deadline can close one that is still connecting. */
        onSocket: (BluetoothSocket) -> Unit = {},
        /** Relay child connect only: Experimental RelayEstablishBudget for the GATT control connect. */
        establishBudget: Boolean = false,
        /** The caller's setup deadline (a call's or message's dial, a relay helper's next hop), null when unbounded. */
        deadline: SetupDeadline? = null,
        onControl: (BleL2capControlConnection?) -> Unit,
    ): BluetoothSocket {
        val control = BleL2capPsmReader.open(
            context,
            device,
            onLinkStatus,
            phyPreference,
            connectionPriority,
            establishBudget = establishBudget,
            deadline = deadline,
        )
        onControl(control)
        val psm = control.psm
        if (!BleCocFreshAcl.enabled()) {
            // The control link is alive on this ACL and its PHY request is issued: a failure here is past establishment.
            val shared = markLinkReached({ true }) { connectChannel(device, psm, owner, onSocket, deadline = deadline) }
            // The control client stayed on this ACL and already exchanged the MTU: nothing to prime.
            if (CocSwitches.mtuPrime) Log.i("BluetoothVideo", CocMtuPrime.skippedLine(owner, "control-link-alive"))
            return markLinkReached({ true }) { verifyEcho(shared, owner) }
        }
        val watch = AclDownWatch.forDevice(context, device)
        BleCocFreshAcl.releaseControlAndAwaitAclDown(
            watch = watch,
            closeControl = {
                control.close()
                onControl(null)
            },
            nowNs = System::nanoTime,
            log = { line -> Log.i("BluetoothVideo", line) },
            owner = owner,
            mayBlock = Looper.myLooper() != Looper.getMainLooper(),
        )
        val socket = connectChannel(device, psm, owner, onSocket, deadline = deadline)
        // Default on here since Q49 (test_coc_mtu_prime=false is the DEBUG control arm): this fresh ACL has had no MTU exchange, so no data length update.
        if (CocMtuPrime.enabled()) CocMtuPrime.runOnAcl(context, device, owner)
        val attached = runCatching { BleL2capPsmReader.attach(context, device, psm, onLinkStatus, phyPreference, connectionPriority, deadline) }
            .onFailure { error ->
                onLinkStatus("BLE CoC connected; GATT control reattach failed: ${error.message}")
            }
            .getOrNull()
        onControl(attached)
        return markLinkReached({ true }) { verifyEcho(socket, owner) }
    }

    /**
     * Experimental (test_coc_echo_verify): the new socket must round-trip a 1 KB echo before it is
     * returned. A failure closes the socket and fails the connect, so the callers' retry and GATT
     * fallback apply.
     */
    private fun verifyEcho(socket: BluetoothSocket, owner: String): BluetoothSocket {
        if (!CocEchoVerify.enabled()) return socket
        val result = CocEchoVerify.initiate(
            socket.inputStream, socket.outputStream, socket, owner, { Log.i("BluetoothVideo", it) },
        )
        if (!result.ok) {
            runCatching { socket.close() }
            throw CocEchoVerify.failure(result)
        }
        return socket
    }

    @SuppressLint("MissingPermission")
    private fun connectChannel(
        device: BluetoothDevice,
        psm: Int,
        owner: String,
        onSocket: (BluetoothSocket) -> Unit = {},
        precededByGatt: Boolean = true,
        deadline: SetupDeadline? = null,
    ): BluetoothSocket {
        EncoderBenchGuard.check("BleCocClient.connect")
        // Both callers read the PSM over a GATT connect just before; Android 10 needs that to know the address type.
        CocAddressPrecondition.check(CocPolicy.current(), precededByGatt, owner) { Log.i("BluetoothVideo", it) }
        val socket = device.createInsecureL2capChannel(psm).also { CocRegistrations.note("connect", owner) }
        onSocket(socket)
        try {
            // Bounded on every SDK (coc-connect-bounded): a watchdog closes a connect the stack never answers.
            // Experimental test_unified_connect_policy: the bound is clipped to the caller's setup deadline.
            val boundMs = CocConnectBound.currentBoundMs().let { bound ->
                if (deadline != null && ConnectRetryConfig.current().unified) {
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    UnifiedConnectRule.clipMs(bound, deadline.endMs, nowMs)
                } else {
                    bound
                }
            }
            CocConnectBound.run(socket, boundMs, owner, { Log.i("BluetoothVideo", it) }) { it.connect() }
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
        return socket
    }
}

internal object BleCocFreshAcl {
    /** GATT drops an unused LE link after 1 s (android12 gatt_api.h GATT_LINK_IDLE_TIMEOUT_WHEN_NO_APP); the one ACL-wait bound. */
    const val ACL_DOWN_WAIT_MS = GattLinkLifetime.ACL_DOWN_WAIT_MS

    /** The stack-family rule lives in DeviceFamilyPolicy.LE_COC_EATT_STRANDED_CHANNELS; DEBUG may override it. */
    fun enabledFor(fingerprint: BluetoothStackFingerprint, override: Boolean?): Boolean =
        override ?: DeviceFamilyPolicy.leCocEattStrandedChannels(fingerprint)

    fun enabled(): Boolean = enabledFor(BluetoothStackFingerprint.current(), DebugTunables.cocFreshAcl)

    /**
     * JVM-testable core: arm, close, wait (bounded), log. Returns whether the ACL was seen down. A
     * caller on the main thread ([mayBlock] false) never waits: it proceeds at once with aclDown=false.
     */
    internal fun releaseControlAndAwaitAclDown(
        watch: AclDownWatch,
        closeControl: () -> Unit,
        nowNs: () -> Long,
        log: (String) -> Unit,
        owner: String,
        mayBlock: Boolean = true,
    ): Boolean {
        val startedNs = nowNs()
        try {
            runCatching(closeControl)
            val aclDown = mayBlock && runCatching { watch.await(ACL_DOWN_WAIT_MS) }.getOrDefault(false)
            log(
                "BENCHMARK coc_fresh_acl: owner=$owner | waitedMs=${(nowNs() - startedNs) / 1_000_000L} | " +
                    "aclDown=$aclDown" + if (mayBlock) "" else " | skipped=main-thread",
            )
            return aclDown
        } finally {
            runCatching { watch.release() }
        }
    }
}
