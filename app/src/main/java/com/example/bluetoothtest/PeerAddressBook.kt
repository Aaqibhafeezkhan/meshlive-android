package com.example.bluetoothtest

import android.os.SystemClock

/**
 * Experimental (`test_peer_address_book`, default off): peers are tracked by node-ID prefix, not by
 * the random address they last advertised from. Every accepted scan result updates the freshest
 * sighting of its prefix; a connect to an address that is older than [STALE_MS], or that a newer
 * address of the same node has replaced, rescans for the prefix first.
 *
 * Why: Android rotates a resolvable private address every 7-15 minutes
 * (android11-release btm_ble_addr.cc:96-102 btm_get_next_private_addrress_interval_ms) and gives every
 * new advertising set a new one at once (btm_ble_multi_adv.cc:240-267 RegisterAdvertiser -> GenerateRpa).
 * A direct connect to an address nobody advertises any more ends only at the stack's direct-connect
 * timeout, about 30 s, as GATT_ERROR 133 (connection_manager.cc:33 in packages/modules/Bluetooth), and
 * the A15s dialled the S24's replaced address 100 ms after the replacement (voice-ptt-s24a15s-51cc8028
 * 0004, see StableAdvertisingSet).
 *
 * Pure apart from the clock; JVM-tested.
 */
internal class PeerAddressBook(private val nowMs: () -> Long, private val capacity: Int = 64) {
    data class Sighting(val address: String, val seenAtMs: Long, val routeCost: Int)

    private val freshest = LinkedHashMap<String, Sighting>()
    private val lastSeenByAddress = HashMap<String, Long>()

    @Synchronized
    fun record(prefix: String, address: String, routeCost: Int) {
        val now = nowMs()
        freshest.remove(prefix)
        freshest[prefix] = Sighting(address, now, routeCost)
        lastSeenByAddress[address] = now
        while (freshest.size > capacity) freshest.remove(freshest.keys.first())
        if (lastSeenByAddress.size > capacity * 4) {
            val keep = freshest.values.map(Sighting::address).toSet()
            lastSeenByAddress.keys.retainAll(keep)
        }
    }

    @Synchronized
    fun freshest(prefix: String): Sighting? = freshest[prefix]

    /** Why a connect to [address] for [prefix] should rescan first, or null when the address is current. */
    @Synchronized
    fun rescanReason(prefix: String, address: String, staleMs: Long = STALE_MS): String? {
        val now = nowMs()
        val latest = freshest[prefix] ?: return lastSeenByAddress[address]
            ?.let { seen -> if (now - seen > staleMs) "stale" else null }
            ?: "unseen"
        if (latest.address != address) return "replaced"
        return if (now - latest.seenAtMs > staleMs) "stale" else null
    }

    companion object {
        /** A sighting older than this is rescanned before a connect (scans report a live peer every ~1 s). */
        const val STALE_MS = 10_000L

        /** Process-wide book fed by every BleL2capDiscovery while the experiment is on. */
        val shared: PeerAddressBook by lazy { PeerAddressBook(SystemClock::elapsedRealtime) }

        fun enabled(): Boolean = DebugTunables.peerAddressBook

        fun prefixKey(prefix: ByteArray): String = prefix.joinToString("") { "%02x".format(it) }
    }
}

/** What to do after an LE connect attempt failed. */
internal enum class LeConnectFailureAction { RETRY, RESCAN, GIVE_UP }

/**
 * Classifies a failed LE connect by how long it took (experiment `test_peer_address_book`).
 * Nordic Android-BLE-Library retries a failed connect only when it failed within 20 s of the request,
 * at most twice, 300 ms apart (BleManagerHandler.java L2264-L2267 and ConnectRequest retry(2, 300) in
 * its samples); a 133 that arrives after about 30 s is the stack's direct-connect timeout on an address
 * nobody advertises (connection_manager.cc:33), so blind retries to it cannot succeed: rescan for the
 * node instead. AOSP itself retries a 0x3E failure to establish (bta_hh_le.cc 1780-1788).
 */
internal object LeConnectFailureClassifier {
    const val FAST_FAILURE_MS = 20_000L
    const val STALE_TIMEOUT_MS = 25_000L
    const val MAX_FAST_RETRIES = 2

    fun classify(elapsedMs: Long, status: Int?, fastRetriesUsed: Int): LeConnectFailureAction = when {
        elapsedMs >= STALE_TIMEOUT_MS && (status == null || status == BluetoothTransportPolicy.GATT_ERROR_133) ->
            LeConnectFailureAction.RESCAN
        elapsedMs < FAST_FAILURE_MS && fastRetriesUsed < MAX_FAST_RETRIES -> LeConnectFailureAction.RETRY
        else -> LeConnectFailureAction.GIVE_UP
    }

    fun line(link: String, elapsedMs: Long, status: Int?, action: LeConnectFailureAction): String =
        "BENCHMARK le_connect_failure: link=$link | elapsedMs=$elapsedMs | status=${status ?: "none"} | " +
            "action=${action.name.lowercase()}"
}
