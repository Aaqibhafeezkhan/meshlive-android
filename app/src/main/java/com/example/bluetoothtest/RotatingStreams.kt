package com.example.bluetoothtest

import java.security.MessageDigest

/**
 * Rotating discovery IDs, Phase 1b (PROTOCOL "Experimental rotating discovery IDs"): the pure,
 * JVM-tested parts for stream offers, viewer requests and forwarders on LE CoC and GATT. Off
 * (`RotatingDiscoveryFeature.enabled` false): none of this is reached and every byte is unchanged.
 */

/**
 * The route tokens a source or forwarder accepts in a viewer request: the current one, and each one it
 * replaced less than [graceMs] earlier (at most [maxRetired]), so a request that copied an offer's token
 * just before a boundary still matches (design 7.1). One window per advertised stream.
 *
 * [advance] keeps the grace only for the same logical stream; [reset] (a new stream, a stopped one)
 * drops every old token at once, which is exactly the old single-token behaviour.
 */
class RouteTokenWindow(
    private val graceMs: Long = GRACE_MS,
    private val maxRetired: Int = MAX_RETIRED,
) {
    private class Retired(val token: Int, val retiredAtMs: Long)

    private var logical = 0
    private var current = 0
    private val retired = ArrayDeque<Retired>()

    @Synchronized
    fun current(): Int = current

    /** A new stream (or the first token): only [token] is accepted. */
    @Synchronized
    fun reset(logicalToken: Int, token: Int) {
        logical = logicalToken
        current = token
        retired.clear()
    }

    /** The same stream's token changed (a boundary, a Visible-by-handle window): the old one gets the grace. */
    @Synchronized
    fun advance(logicalToken: Int, token: Int, nowMs: Long) {
        if (current == 0 || logicalToken != logical) {
            reset(logicalToken, token)
            return
        }
        if (token == current) return
        retired.addFirst(Retired(current, nowMs))
        while (retired.size > maxRetired) retired.removeLast()
        retired.removeAll { it.token == token }
        current = token
    }

    @Synchronized
    fun clear() {
        logical = 0
        current = 0
        retired.clear()
    }

    @Synchronized
    fun accepts(token: Int, nowMs: Long): Boolean {
        if (token == 0 || current == 0) return false
        if (token == current) return true
        return retired.any { it.token == token && nowMs - it.retiredAtMs < graceMs }
    }

    companion object {
        const val GRACE_MS = 60_000L
        const val MAX_RETIRED = 2
    }
}

/** Announcement construction for the rotating stream roles (design 4.1, 7.1). */
object RotatingStreamAnnouncements {
    /**
     * A source's or forwarder's stream offer with [entry] (its role-1 RDI) in place of the node prefix.
     * The route token is `logical XOR mask(RDI)`, the unchanged [StreamRouteToken] arithmetic over the six
     * advertised bytes; [base] carries every other field.
     */
    fun streamOffer(base: DiscoveryAnnouncement, entry: OpeningEntry, logicalToken: Int): DiscoveryAnnouncement {
        require(entry.role == RotatingDiscoveryIds.ROLE_STREAM_OFFER) { "A stream offer uses the role-1 RDI" }
        return RotatingAdvertFields.forStreamOffer(
            base.copy(
                nodeIdPrefix = entry.rdi,
                version = DiscoveryAnnouncement.ROTATING_VERSION,
                streamToken = StreamRouteToken.forAdvertiser(logicalToken, entry.rdi),
            ),
        )
    }

    /**
     * A viewer request with [entry] (its role-2 RDI). The route token and the recovery bytes are the
     * caller's: the token is copied from the selected offer, and [recoveryHint] (or null) replaces the
     * failed parent's prefix.
     */
    fun viewerRequest(base: DiscoveryAnnouncement, entry: OpeningEntry, recoveryHint: ByteArray?): DiscoveryAnnouncement {
        require(entry.role == RotatingDiscoveryIds.ROLE_VIEWER_REQUEST) { "A viewer request uses the role-2 RDI" }
        val flags = (base.flags and DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY.inv()) or
            (if (recoveryHint != null) DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY else 0)
        return RotatingAdvertFields.forViewerRequest(
            base.copy(
                nodeIdPrefix = entry.rdi,
                version = DiscoveryAnnouncement.ROTATING_VERSION,
                flags = flags,
                recoveryFromNodeIdPrefix = recoveryHint?.copyOf(),
            ),
        )
    }

    /**
     * Whether the viewer's request is version 5: it mirrors the selected offer (a version 2 source cannot
     * decode version 5), and only on an LE transport with the experiment on.
     */
    fun viewerRequestRotates(featureOn: Boolean, transport: BluetoothTransportOption, selectedOfferRotating: Boolean): Boolean =
        featureOn && transport != BluetoothTransportOption.CLASSIC_RFCOMM && selectedOfferRotating

    /** Source and forwarder offers rotate on LE only; Classic roles keep the fixed prefix (RV2). */
    fun offerRotates(featureOn: Boolean, transport: BluetoothTransportOption): Boolean =
        featureOn && transport != BluetoothTransportOption.CLASSIC_RFCOMM
}

/** Link hello and opening rules for a stream hop (PROTOCOL Phase 1b item 4). */
object StreamHopOpening {
    /**
     * The link hello a stream hop signs: bit 3 only with the experiment on, on LE, and never for a `BGK1`
     * group hop (local `maxChildren` above 1). Otherwise [base] unchanged.
     */
    fun linkCapabilities(base: NodeCapabilities, featureOn: Boolean, transport: BluetoothTransportOption): NodeCapabilities =
        if (featureOn && transport != BluetoothTransportOption.CLASSIC_RFCOMM && base.maxChildren <= 1) {
            base.copy(transportMask = base.transportMask or RotatingDiscoveryIds.LINK_HELLO_BIT)
        } else {
            base
        }

    /**
     * The check a hop applies to the advert it selected (RV15). A version 2 selection uses the prefix
     * check (whatever the hello says); a version 5 selection needs an opening entry of [role] that
     * recomputes to the selected bytes from the authenticated link node. [endpointNodeId] must equal the
     * link node for a version 5 selection (the existing hop-local rule); null skips that comparison.
     */
    fun check(
        selected: DiscoveryAnnouncement,
        role: Int,
        linkNodeId: NodeId,
        opening: List<OpeningEntry>?,
        endpointNodeId: NodeId? = null,
    ): DiscoverySelectionCheck.Result {
        val result = DiscoverySelectionCheck.check(selected, role, linkNodeId.toByteArray(), opening)
        if (result != DiscoverySelectionCheck.Result.PASS || !selected.rotating) return result
        return if (endpointNodeId == null || endpointNodeId == linkNodeId) result else DiscoverySelectionCheck.Result.PREFIX_MISMATCH
    }
}

/** The recovery hint of a viewer that lost a forwarder parent, and the source's matching (design 4.3, RV18). */
object RecoveryMatching {
    /** A child the source holds an end-to-end sealer key for: [key] is its admission key, [mediaKey] the forwarded-stream key. */
    class Child(val key: String, val nodeId: NodeId, mediaKey: ByteArray) {
        val mediaKey: ByteArray = mediaKey.copyOf()
    }

    /**
     * The hint a viewer puts in its request: the failed parent, under the forwarded-stream key it received,
     * bound to the request's own role-2 RDI and route token (RD3).
     */
    fun hintFor(mediaKey: ByteArray, failedParent: NodeId, logicalToken: Int, viewerRdi: ByteArray, routeToken: Int): ByteArray =
        RecoveryHint.hint(RecoveryHint.hintKey(mediaKey), failedParent.toByteArray(), logicalToken, viewerRdi, routeToken)

    /** The admission key of the child whose hint equals [hint] for this request, or null (an ordinary request). */
    fun match(hint: ByteArray, logicalToken: Int, viewerRdi: ByteArray, routeToken: Int, children: Collection<Child>): String? {
        if (hint.size != RotatingDiscoveryIds.ID_BYTES || viewerRdi.size != RotatingDiscoveryIds.ID_BYTES) return null
        return children.firstOrNull { child ->
            MessageDigest.isEqual(hintFor(child.mediaKey, child.nodeId, logicalToken, viewerRdi, routeToken), hint)
        }?.key
    }
}

/**
 * The forwarded-stream key a viewer received from its last forwarder parent, kept in memory only so that a
 * recovery request after losing that parent can carry the RV18 hint. A link to a source directly clears it
 * (a source parent has no such key and sends no hint); a new forwarded link replaces it.
 */
object ViewerRecoveryMemory {
    private var failedParent: NodeId? = null
    private var mediaKey: ByteArray? = null
    private var logical = 0

    @Synchronized
    fun remember(parent: NodeId, key: ByteArray, logicalToken: Int) {
        mediaKey?.fill(0)
        failedParent = parent
        mediaKey = key.copyOf()
        logical = logicalToken
    }

    @Synchronized
    fun clear() {
        mediaKey?.fill(0)
        mediaKey = null
        failedParent = null
        logical = 0
    }

    /**
     * The hint for the last forwarder parent of stream [logicalToken], bound to the request about to be
     * advertised ([viewerRdi], [routeToken]); null means no hint (an ordinary request).
     */
    @Synchronized
    fun hint(logicalToken: Int, viewerRdi: ByteArray, routeToken: Int): ByteArray? {
        val parent = failedParent ?: return null
        val key = mediaKey ?: return null
        if (logicalToken == 0 || logicalToken != logical) return null
        return RecoveryMatching.hintFor(key, parent, logicalToken, viewerRdi, routeToken)
    }
}
