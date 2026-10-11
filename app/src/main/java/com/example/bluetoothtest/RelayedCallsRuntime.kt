package com.example.bluetoothtest

import android.os.SystemClock

/**
 * The route a caller asked for: [relays] helpers between it and [target], the first one being the
 * helper whose own offer has the prefix [helperPrefix]. Carried by the peer the call list built from
 * the reach table; null for every ordinary (direct or DEBUG-relay) peer.
 */
class RelayRoute(
    val target: ByteArray,
    val helperPrefix: ByteArray,
    val relays: Int,
    /** Rotating IDs: [target] and [helperPrefix] are role-0 RDIs (SETUP message 4, link openings). */
    val rotating: Boolean = false,
)

/** A helper's decision on a SETUP it read. */
sealed class RelayAdmission {
    /**
     * Take the request: connect to [nextHop] (the target itself when [nextBudget] is 0; otherwise a helper
     * whose link identity must equal [hopPrefix]) and pass [nextBudget] on.
     */
    class Accept(val nextHop: BleL2capPeer, val nextBudget: Int, val hopPrefix: ByteArray?, val rotating: Boolean = false) : RelayAdmission()

    class Refuse(val reason: RelaySetupFailure) : RelayAdmission()
}

/**
 * What a call endpoint needs from the relayed-call runtime (PLAN Design C). An endpoint created without
 * a host (feature off) never reads hop type 12 and changes no byte on air or on the link.
 */
interface RelayHelperHost {
    /** This phone's call offer carries the consent bit. */
    fun consentOn(): Boolean

    /** This phone helps right now: new link hellos sign the relay marks and SETUP may be accepted. */
    fun helperActive(): Boolean

    /** The endpoint opened (or reopened) its call offer with these bits; the host restarts it when they go stale. */
    fun offerOpened(helperActive: Boolean, consent: Boolean)

    /** Decide a SETUP read from the authenticated [parentNodeId] (admission, rate limit, table lookup). */
    fun admit(
        parentNodeId: NodeId,
        target: ByteArray,
        budget: Int,
        transport: BluetoothTransportOption,
        rotating: Boolean = false,
    ): RelayAdmission

    /** A relayed call started / ended on this phone (notification and busy state). */
    fun relayStarted(relays: Int)
    fun relayEnded()

    /** A caller's outcome for a route (table skip and protection). */
    fun routeOutcome(route: RelayRoute, ready: Boolean, reason: RelaySetupFailure?)
}

/**
 * Process-wide hooks of the relayed-call feature: the scanners feed [table] and the pages; everything
 * else asks it. All of it is inert (null table) while the experiment is off.
 */
object RelayedCallsRuntime {
    @Volatile internal var table: ReachTable<BleL2capPeer>? = null

    /** Rotating IDs + relayed calls: the version 5 / version 6 population, flushed at every boundary. */
    @Volatile internal var rotatingTable: ReachTable<BleL2capPeer>? = null

    /** Counters for the DEBUG `relayed_reach_table` line. */
    @Volatile internal var pagesSeen = 0L
    @Volatile internal var offersSeen = 0L

    /** A scanned reach page (before the per-address duplicate filter), when the experiment is on. */
    internal fun onScanPage(bytes: ByteArray, address: String): Boolean {
        if (table == null && rotatingTable == null) return false
        if (!RelayedCallsFeature.enabled || !ReachPageCodec.isReachPage(bytes)) return false
        val page = runCatching { ReachPageCodec.decode(bytes) }.getOrNull() ?: return true
        // A version 6 page means nothing to a phone without the rotating experiments: left to the decoder, as before.
        if (page.rotating && !RelayedCallsFeature.rotatingActive(RotatingDiscoveryFeature.enabled)) return false
        pagesSeen++
        (if (page.rotating) rotatingTable else table)?.observePage(page, address)
        return true
    }

    /** A scanned cost-0 call offer (before the per-address duplicate filter), when the experiment is on. */
    internal fun onScanOffer(peer: BleL2capPeer, address: String, rssi: Int?) {
        val current = (if (peer.announcement.rotating) rotatingTable else table) ?: return
        if (!RelayedCallsFeature.enabled) return
        offersSeen++
        current.observeOffer(peer, peer.announcement, address, rssi)
    }

    /** The call-list rows for targets known only through helpers (empty while the experiment is off). */
    fun relayedPeers(transport: BluetoothTransportOption): List<BleL2capPeer> {
        if (!RelayedCallsFeature.enabled || transport == BluetoothTransportOption.CLASSIC_RFCOMM) return emptyList()
        val accept = { offer: DiscoveryAnnouncement -> offer.transportOption() == transport }
        return listOfNotNull(table, rotatingTable).flatMap { current ->
            current.relayedTargets(accept, includeDirect = RelayedCallsFeature.requireRelays > 0)
                .mapNotNull { target -> bestPeerFor(current, target, transport, accept) }
        }
    }

    /** The freshest best route to [target] (a version 5 RDI when [rotating]) as a dialable peer, or null. */
    fun peerFor(target: ByteArray, transport: BluetoothTransportOption, rotating: Boolean = false): BleL2capPeer? {
        val current = (if (rotating) rotatingTable else table) ?: return null
        val accept = { offer: DiscoveryAnnouncement -> offer.transportOption() == transport }
        return bestPeerFor(current, target, transport, accept)
    }

    private fun bestPeerFor(
        current: ReachTable<BleL2capPeer>,
        target: ByteArray,
        transport: BluetoothTransportOption,
        accept: (DiscoveryAnnouncement) -> Boolean,
    ): BleL2capPeer? {
        val required = RelayedCallsFeature.requireRelays
        val route = current.callerRoutes(target, accept, required).firstOrNull { it.nextHop != null } ?: return null
        val first = route.hop.peer
        val announcement = RelayedCalls.routedAnnouncement(target, route.relays, route.hop.announcement)
        require(announcement.transportOption() == transport) { "Route bearer differs from the call transport" }
        return BleL2capPeer(
            device = first.device,
            label = "relayed [${target.prefixHex()}] via ${route.relays}",
            announcement = announcement,
            relayRoute = RelayRoute(target.copyOf(), route.nextHop!!.copyOf(), route.relays, rotating = current.rotating),
        )
    }

    internal fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Responder-side first-record dispatch (PROTOCOL "Experimental relayed calls and multi-hop discovery"):
 * hop type 11 (store-and-forward) and/or type 12 (relay SETUP) are accepted for this one read only.
 * Anything else is handed unchanged to the call handshake.
 */
internal fun readFirstRecordForDispatch(
    link: SecureBluetoothSession,
    acceptStoreForward: Boolean,
    acceptRelaySetup: Boolean,
): SecureRecord {
    link.reader.acceptStoreForward = acceptStoreForward
    link.reader.acceptRelaySetup = acceptRelaySetup
    try {
        return link.reader.read()
    } finally {
        link.reader.acceptStoreForward = false
        link.reader.acceptRelaySetup = false
    }
}

/** The "Stop" action of the "Helping a call nearby" notification: a not-exported broadcast carrying this process's token. */
internal object RelayedCallsNotification {
    const val ACTION = "com.example.bluetoothtest.action.RELAY_STOP"
    const val EXTRA_TOKEN = "com.example.bluetoothtest.extra.RELAY_STOP_TOKEN"
    private const val REQUEST_STOP = 21

    fun stopIntent(context: android.content.Context): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(
        context,
        REQUEST_STOP,
        android.content.Intent(ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TOKEN, CallNotificationActions.token),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun isAuthentic(token: String?): Boolean = token != null && java.security.MessageDigest.isEqual(
        token.toByteArray(Charsets.US_ASCII),
        CallNotificationActions.token.toByteArray(Charsets.US_ASCII),
    )
}
