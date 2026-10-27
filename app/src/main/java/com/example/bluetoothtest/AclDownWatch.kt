package com.example.bluetoothtest

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One remote's LE ACL going down (`ACTION_ACL_DISCONNECTED`), the single watcher every break-before-make wait uses: the GATT
 * redial wait ([GattLinkLifetime.awaitAclDown]), the CoC fresh-ACL wait ([BleCocFreshAcl]) and the call's 1M retry after a
 * stall ([SetupRetryAclWait]). Arm it before the last holder of the link is released, so the broadcast cannot be missed.
 * Simplification pass A (2026-10-27) merged the two former copies (GattLinkLifetime and BleCocClient); both waited on the same
 * broadcast with the same bound and the same "unregistered = wait the full bound, report not dropped" result.
 */
internal interface AclDownWatch {
    /** True when the ACL went down within [timeoutMs]. */
    fun await(timeoutMs: Long): Boolean

    fun release()

    companion object {
        /** Registers for [device]'s ACL_DISCONNECTED now. */
        fun forDevice(context: Context, device: BluetoothDevice): AclDownWatch =
            AndroidAclDownWatch(context.applicationContext, device)
    }
}

private class AndroidAclDownWatch(
    private val context: Context,
    private val device: BluetoothDevice,
) : AclDownWatch {
    private val latch = CountDownLatch(1)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val changed: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (changed?.address == device.address) latch.countDown()
        }
    }
    private val registered: Boolean = runCatching {
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }.isSuccess

    // Not registered: the latch never opens, so the wait runs its full bound and reports "not dropped" (both former copies).
    override fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun release() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
    }
}
