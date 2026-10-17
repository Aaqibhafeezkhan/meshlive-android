package com.example.bluetoothtest

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Classic RFCOMM admission refused: this phone already serves its maximum number of distinct peers. */
internal class ClassicCapacityException(val openPeers: Int, val cap: Int) :
    IOException("Classic capacity: $openPeers of $cap Bluetooth Classic peers already connected; close one first")

/**
 * C-04: a framework fault inside a Classic RFCOMM call (Briar saw a NullPointerException from
 * BluetoothSocket.connect() on a Huawei Android 9 phone) is a failed attempt, not a crash. The
 * connector's candidate loop moves on to the next candidate.
 */
internal object ClassicConnectGuard {
    /** Runs [connect]; an IOException passes through, any other exception becomes an IOException that names it. */
    fun connect(owner: String, log: (String) -> Unit = {}, connect: () -> Unit) {
        try {
            connect()
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            log("Classic connect threw ${error.javaClass.simpleName} inside the framework | owner=$owner | message=${error.message}")
            throw IOException("Classic connect failed inside the Bluetooth framework (${error.javaClass.simpleName}): ${error.message}", error)
        }
    }
}

/**
 * C-04: BR/EDR addresses whose bond-free Classic connect is in progress. Nothing in the app may call
 * `createBond` for such a peer, nor open a secure RFCOMM socket to it: that would start a pairing in
 * the middle of an insecure connect (the stack rejects a second pairing, and a bonding-API pairing
 * turns Just Works into a consent dialog). The connector pins the security mode of a whole connect
 * at its start, so a settings change mid-connect cannot switch it.
 */
internal object ClassicConnectProgress {
    private val active = ConcurrentHashMap<String, Int>()

    private fun key(address: String) = address.uppercase()

    fun begin(addresses: Collection<String>) {
        addresses.forEach { active.merge(key(it), 1, Int::plus) }
    }

    fun end(addresses: Collection<String>) {
        addresses.forEach { active.computeIfPresent(key(it)) { _, count -> if (count <= 1) null else count - 1 } }
    }

    /** Marks [addresses] in progress for the duration of [body]. */
    inline fun <T> track(addresses: Collection<String>, body: () -> T): T {
        begin(addresses)
        try {
            return body()
        } finally {
            end(addresses)
        }
    }

    fun inProgress(address: String): Boolean = active.containsKey(key(address))

    /** The guard every bonding call site should ask first. */
    fun mayCreateBond(address: String): Boolean = !inProgress(address)
}

/**
 * C-04: distinct RFCOMM peers this phone serves. The stack allows 7 remote devices with RFCOMM
 * multiplexers and 30 ports in total (btif_sock_rfc.cc), shared with the user's headset, car and other
 * apps; the cap (default 6) keeps one session free. Only a connection to a NEW peer is refused;
 * reconnecting an already connected peer is always allowed.
 */
internal class ClassicRfcommPeers {
    private class Slot(val address: String, val isOpen: () -> Boolean)

    private val slots = ArrayList<Slot>()

    /** Records an opened (connected or accepted) RFCOMM socket of [address]; [isOpen] says when it is gone. */
    @Synchronized
    fun note(address: String, isOpen: () -> Boolean) {
        prune()
        slots.add(Slot(address.uppercase(), isOpen))
    }

    /** Distinct peers with a currently open socket. */
    @Synchronized
    fun openPeerCount(): Int {
        prune()
        return slots.map { it.address }.toSet().size
    }

    /**
     * Whether a connect to one of [candidates] may start under [cap] (0 or less: no cap). Allowed
     * when below the cap, or when a candidate already has an open socket (a reconnect).
     */
    @Synchronized
    fun admit(candidates: Collection<String>, cap: Int): Boolean {
        if (cap <= 0) return true
        prune()
        val open = slots.map { it.address }.toSet()
        if (open.size < cap) return true
        return candidates.any { it.uppercase() in open }
    }

    @Synchronized
    private fun prune() {
        slots.removeAll { !runCatching(it.isOpen).getOrDefault(false) }
    }
}

/** The process-wide RFCOMM peer registry, keyed to [CocSwitches.classicRfcommCap] and its registry entry. */
internal object ClassicRfcommCap {
    val peers = ClassicRfcommPeers()

    /** The enforced cap: 0 (no refusal) unless the Experimental `test_classic_rfcomm_cap=<n>` is set and the entry is on. */
    fun capFor(policy: ResolvedDevicePolicy, configured: Int): Int {
        if (policy.effects<ClassicRfcommPeerCap>().firstOrNull() == null) return 0
        return configured.coerceAtLeast(0)
    }

    fun currentCap(): Int = capFor(CocPolicy.current(), CocSwitches.classicRfcommCap)

    /** The count at which a log-only `classic_rfcomm_cap` line is written (the entry's value; 0 when the entry is off). */
    fun watchCap(policy: ResolvedDevicePolicy = CocPolicy.current()): Int =
        policy.effects<ClassicRfcommPeerCap>().firstOrNull()?.maxPeers ?: 0

    /**
     * Throws [ClassicCapacityException] when a new peer may not be added and the refusal arm is on. By default
     * (cap 0) nothing is refused: a seventh distinct peer only writes a log-only line. [candidates] are the BR/EDR
     * addresses about to be paged.
     */
    fun admitOrThrow(
        candidates: Collection<String>,
        cap: Int = currentCap(),
        watchCap: Int = watchCap(),
        log: (String) -> Unit = {},
    ) {
        if (cap <= 0) {
            if (watchCap > 0 && !peers.admit(candidates, watchCap)) {
                log("BENCHMARK classic_rfcomm_cap: log_only would_refuse | openPeers=${peers.openPeerCount()} | cap=$watchCap | candidates=${candidates.size} | enforced=false")
            }
            return
        }
        if (peers.admit(candidates, cap)) return
        val open = peers.openPeerCount()
        log("BENCHMARK classic_rfcomm_cap: refused | openPeers=$open | cap=$cap | candidates=${candidates.size} | enforced=true")
        throw ClassicCapacityException(open, cap)
    }

    /** Registers an opened RFCOMM socket (outgoing connect, or accepted by a listener). */
    @android.annotation.SuppressLint("MissingPermission")
    fun note(socket: BluetoothSocket) {
        val address = runCatching { socket.remoteDevice.address }.getOrNull() ?: return
        peers.note(address) { socket.isConnected }
    }
}

/** C-04: one log line per pairing-related broadcast seen during a Classic connect. */
internal object ClassicPairingLog {
    fun requestLine(role: String, addressTail: String, variant: Int): String =
        "BENCHMARK classic_pairing: event=pairing_request | role=$role | address=...$addressTail | variant=$variant | " +
            "meaning=remote_stack_wants_pairing_during_bond_free_connect"

    fun bondLine(role: String, addressTail: String, state: Int, previous: Int): String =
        "BENCHMARK classic_pairing: event=bond_state | role=$role | address=...$addressTail | " +
            "state=${bondName(state)} | previous=${bondName(previous)}"

    fun bondName(state: Int): String = when (state) {
        10 -> "none"
        11 -> "bonding"
        12 -> "bonded"
        else -> "unknown($state)"
    }
}

/**
 * Registers receivers for ACTION_PAIRING_REQUEST and ACTION_BOND_STATE_CHANGED while a Classic
 * connect runs. They only log: no abortBroadcast, no setPairingConfirmation, no createBond.
 */
internal class ClassicPairingWatch private constructor(
    private val context: Context,
    private val receiver: BroadcastReceiver?,
) : Closeable {
    override fun close() {
        if (receiver != null) runCatching { context.unregisterReceiver(receiver) }
    }

    companion object {
        fun start(context: Context, role: String): ClassicPairingWatch {
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val tail = runCatching { device?.address?.takeLast(5) }.getOrNull() ?: "?"
                    val line = when (intent.action) {
                        BluetoothDevice.ACTION_PAIRING_REQUEST ->
                            ClassicPairingLog.requestLine(role, tail, intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1))
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> ClassicPairingLog.bondLine(
                            role,
                            tail,
                            intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1),
                            intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1),
                        )
                        else -> return
                    }
                    Log.i("BluetoothVideo", line)
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            // Protected system broadcasts; no other app can send them.
            val registered = runCatching {
                ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            }.isSuccess
            return ClassicPairingWatch(app, if (registered) receiver else null)
        }
    }
}
