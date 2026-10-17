package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Link lifetime rules for the GATT paths (G-04).
 *
 * Break-before-make (a). The stack drops an LE ACL one second after its last local user leaves (GATT_LINK_IDLE_TIMEOUT_
 * WHEN_NO_APP, gatt_main.cc:413-430), and on Android 14+ also after the last dynamic channel of a link that had a local
 * client closes (l2c_utils.cc:2696-2741). A redial straight after a close can therefore connect onto the dying ACL and
 * be dropped about a second later (status 22/0x16), and a stuck per-peer write permit (Android 13, AOSP 67158cb957) or a
 * wedged client lives exactly as long as the ACL it belongs to. Two ways around it: wait for `ACTION_ACL_DISCONNECTED`
 * (bounded 2 s) before the redial, or keep a holder across the swap. [awaitAclDown] is the wait; [requireAclDrop]
 * records that the next connect to a peer must use it (set when a client is declared wedged); the Experimental
 * `test_gatt_acl_wait` makes every GATT redial wait.
 *
 * Server hold (b). On Android 13 QPR2+ and Android 14 before android14-qpr2 a link used only as GATT server loses its
 * ATT bearer's reason to live when the central's EATT or CoC channel closes (AOSP 545bc4e427). `BluetoothGattServer
 * .connect(device, false)` marks the link in use by this app; [holdServerLink] does that behind `test_gatt_server_hold`
 * and the caller's `cancelConnection` releases it.
 */
internal object GattLinkLifetime {
    /** The bounded wait for an ACL to drop (the same 2 s as the CoC fresh-ACL wait). */
    const val ACL_DOWN_WAIT_MS = 2_000L

    private val dropPending = ConcurrentHashMap<String, String>()

    /** The next connect to [address] must wait for its ACL to go down first. [reason] is for the log. */
    fun requireAclDrop(address: String?, reason: String) {
        if (address != null) dropPending[address] = reason
    }

    /** Consumes the requirement: the reason when a drop was required, else null. */
    fun takeAclDropRequirement(address: String?): String? = address?.let { dropPending.remove(it) }

    private val lastClosedAtMs = ConcurrentHashMap<String, Long>()

    /** A client to [address] was just closed (its ACL may still be dying for up to 1 s, 2 s on some stacks). */
    fun noteClosed(address: String?, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (address != null) lastClosedAtMs[address] = nowMs
    }

    /**
     * Why a connect to [address] must wait for the previous ACL to drop, or null for no wait. A wedged client always
     * waits (consumed); with the Experimental `test_gatt_acl_wait` a redial within [RECENT_CLOSE_MS] of a close waits.
     */
    fun redialWaitReason(
        address: String?,
        aclWaitOnRedial: Boolean = GattSwitches.aclWaitOnRedial,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): String? {
        takeAclDropRequirement(address)?.let { return it }
        if (!aclWaitOnRedial || address == null) return null
        val closedAt = lastClosedAtMs[address] ?: return null
        return if (nowMs - closedAt in 0..RECENT_CLOSE_MS) "recent-close" else null
    }

    /** A redial within this long of a close may land on the dying ACL. */
    const val RECENT_CLOSE_MS = 3_000L

    fun aclDropRequired(address: String?): Boolean = address != null && dropPending.containsKey(address)

    /** A device's ACL going down; armed before the last holder is released so the broadcast cannot be missed. */
    interface AclDownWatch {
        fun await(timeoutMs: Long): Boolean
        fun release()
    }

    /**
     * Waits for [watch] to report the ACL down for at most [timeoutMs]. Returns true when it dropped. Never throws;
     * the watch is always released. [mayBlock] false (main thread) skips the wait.
     */
    fun awaitAclDown(
        watch: AclDownWatch,
        timeoutMs: Long = ACL_DOWN_WAIT_MS,
        mayBlock: Boolean = true,
        /** False when the stack no longer lists the peer as GATT connected: the ACL is already down, do not wait. */
        stillUp: () -> Boolean = { true },
        nowMs: () -> Long = SystemClock::elapsedRealtime,
        log: (String) -> Unit = GattResourceRegistry.Companion::logQuietly,
        reason: String = "redial",
    ): Boolean {
        if (!mayBlock) {
            watch.release()
            log("GATT link lifetime: ACL wait skipped on the main thread | reason=$reason")
            return false
        }
        val startedMs = nowMs()
        if (!runCatching(stillUp).getOrDefault(true)) {
            watch.release()
            log("GATT link lifetime: ACL wait | reason=$reason | dropped=true | waitedMs=0 | already down")
            return true
        }
        val dropped = try {
            runCatching { watch.await(timeoutMs) }.getOrDefault(false)
        } finally {
            runCatching { watch.release() }
        }
        log("GATT link lifetime: ACL wait | reason=$reason | dropped=$dropped | waitedMs=${nowMs() - startedMs} | boundMs=$timeoutMs")
        return dropped
    }

    /**
     * Whether the stack still lists [address] as GATT connected (client or server). True when unreadable, so a failed
     * read waits rather than skips.
     */
    @SuppressLint("MissingPermission")
    fun isGattConnected(manager: BluetoothManager?, address: String): Boolean = runCatching {
        val connected = (manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() +
            manager?.getConnectedDevices(BluetoothProfile.GATT_SERVER).orEmpty())
        connected.any { it.address == address }
    }.getOrDefault(true)

    /** Registers for ACTION_ACL_DISCONNECTED of [device] now; [AclDownWatch.await] completes when it arrives. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun watchAclDown(context: Context, device: BluetoothDevice): AclDownWatch {
        val appContext = context.applicationContext
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val source = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (source?.address == device.address) latch.countDown()
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        runCatching { appContext.registerReceiver(receiver, filter) }
        return object : AclDownWatch {
            override fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            override fun release() {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
        }
    }

    /**
     * Marks [device]'s link as in use by this app's GATT server (Experimental `test_gatt_server_hold`). Returns whether
     * the call was made. The server's `cancelConnection(device)` at teardown releases the hold.
     */
    @SuppressLint("MissingPermission")
    fun holdServerLink(
        server: BluetoothGattServer?,
        device: BluetoothDevice,
        enabled: Boolean = GattSwitches.serverHold,
        log: (String) -> Unit = GattResourceRegistry.Companion::logQuietly,
    ): Boolean {
        if (!enabled || server == null) return false
        val ok = runCatching { server.connect(device, false) }.getOrDefault(false)
        log("GATT link lifetime: server hold | peer=...${device.address.takeLast(5)} | requested=$ok")
        return true
    }
}
