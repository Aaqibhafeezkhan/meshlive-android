package com.example.bluetoothtest

/**
 * Hop-local admission for private-call relay chains (caller -> R1 -> ... -> Rn -> callee), default
 * on; DEBUG launch extra `test_call_relay_chain=false` (benchmark tunable `call_relay_chain`)
 * restores the one-relay rule exactly. PROTOCOL "Private-call relay chains" owns the design.
 *
 * Each relay sees only its parent and its one child. It builds downstream first: it authenticates a
 * child (BMH2 link handshake) and only then advertises the callee's offer at the child's route cost
 * + 1 for one parent. A child is either the callee itself (route cost 0: its link identity must be
 * the announced callee prefix, as before) or another relay (route cost 1..[MAX_RELAYS]-1: its link
 * identity must not be the announced callee, and its signed link hello must declare relay capacity
 * and the call-relay capability bit).
 * Nothing here is trusted for the call's identity: the caller and callee authenticate each other
 * end to end (BMH2 endpoint domain) through every relay, and relays only copy ordered ciphertext.
 */
internal object PrivateCallRelayChain {
    /**
     * Relays between caller and callee that this app builds. The wire (BMC2 relay count) allows 15;
     * the six required phones can form at most four relays between two endpoints, so longer chains
     * could never be measured. Every relay adds a radio hop of latency and airtime.
     */
    const val MAX_RELAYS = 4

    /**
     * Link-hello capability bit (transport mask bit 6) that a call relay signs with chains on. Every
     * reader tests only the transport bits it needs, so older peers ignore it. Forwarding receivers
     * and stream relays also sign maxChildren >= 1; only this bit says "private-call relay".
     */
    const val CALL_RELAY_CAPABILITY = 1 shl 6

    /**
     * Longest a relay waits for its one parent after it starts advertising. The outermost of four
     * relays is built last: an inner relay waits for up to three outer relays to scan (8 s) and
     * connect (Classic budget 30 s) plus the caller, about 150 s. Without a bound a relay whose
     * caller never came held its child, and so the callee, indefinitely.
     */
    const val PARENT_WAIT_MS = 180_000L

    /** The link capabilities a call relay signs: chains on adds [CALL_RELAY_CAPABILITY]. */
    fun relayCapabilities(base: NodeCapabilities, chainEnabled: Boolean): NodeCapabilities =
        if (chainEnabled) base.copy(transportMask = base.transportMask or CALL_RELAY_CAPABILITY) else base

    /**
     * Relays the caller records for an offer at [routeCost], or null when the offer claims more than
     * [MAX_RELAYS]: no relay of this app builds such a route, so the caller does not call through it.
     */
    fun callerRelayHops(routeCost: Int): Int? = routeCost.takeIf { it in 0..MAX_RELAYS }

    /** Classic: a relayed child is only the advertising device itself (classicExactDevicePlan). */
    fun classicExactChildOnly(offer: DiscoveryAnnouncement, chainEnabled: Boolean): Boolean =
        chainEnabled && offer.routeCost > 0

    /** Largest child route cost a relay accepts: its own offer is then at most [MAX_RELAYS]. */
    fun maxChildRouteCost(chainEnabled: Boolean): Int = if (chainEnabled) MAX_RELAYS - 1 else 0

    /**
     * Whether a relay may take this private-call [offer]. Chain off: any private-call offer, exactly
     * as before (a relay offer then fails at the identity check). Chain on: never an offer for this
     * phone itself, and only within the hop bound, so the advertised count strictly grows and stops.
     */
    fun admitsOffer(offer: DiscoveryAnnouncement, localPrefix: ByteArray, chainEnabled: Boolean): Boolean =
        offer.isPrivateCallOffer() && (
            !chainEnabled || (
                !offer.nodeIdPrefix.contentEquals(localPrefix) &&
                    offer.routeCost <= maxChildRouteCost(chainEnabled)
                )
            )

    /** Classic: a relayed offer must not reach its callee directly (ClassicConnector relayed route). */
    fun verifyChildLinkPrefix(offer: DiscoveryAnnouncement, chainEnabled: Boolean): Boolean =
        !chainEnabled || offer.routeCost == 0

    /**
     * Null when the authenticated child link is acceptable for [offer], else why not. [childNodeId]
     * and [childMaxChildren] come from the child's signed BMH2 link hello.
     */
    fun childProblem(
        offer: DiscoveryAnnouncement,
        childNodeId: ByteArray,
        childMaxChildren: Int,
        localNodeId: ByteArray,
        chainEnabled: Boolean,
        /** Transport mask of the child's signed link hello; [CALL_RELAY_CAPABILITY] marks a call relay. */
        childTransportMask: Int = CALL_RELAY_CAPABILITY,
    ): String? {
        val matchesCallee = childNodeId.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
            .contentEquals(offer.nodeIdPrefix)
        if (!chainEnabled) return if (matchesCallee) null else CALLEE_CHANGED
        return when {
            childNodeId.contentEquals(localNodeId) -> "A call relay cannot be its own child"
            offer.routeCost == 0 -> if (matchesCallee) null else CALLEE_CHANGED
            offer.routeCost > maxChildRouteCost(chainEnabled) ->
                "Relay chain would exceed $MAX_RELAYS relays"
            matchesCallee -> "A relayed offer reached its callee directly"
            childMaxChildren < 1 -> "Relay child did not authenticate as a relay"
            childTransportMask and CALL_RELAY_CAPABILITY == 0 ->
                "Relay child did not authenticate as a private-call relay"
            else -> null
        }
    }

    /**
     * Null when the authenticated parent (caller side) link is acceptable, else why not. A relay
     * never joins its child, itself, or the callee it relays for as its parent: each would close a
     * loop through a phone that is already part of this call.
     */
    fun parentProblem(
        parentNodeId: ByteArray,
        childNodeId: ByteArray,
        calleePrefix: ByteArray,
        localNodeId: ByteArray,
        chainEnabled: Boolean,
    ): String? = when {
        !chainEnabled -> null
        parentNodeId.contentEquals(localNodeId) -> "A call relay cannot be its own caller"
        parentNodeId.contentEquals(childNodeId) -> "A relay chain cannot loop back to its child"
        parentNodeId.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).contentEquals(calleePrefix) ->
            "The callee cannot call itself through its relay"
        else -> null
    }

    /** Offer this relay advertises for its parent: the callee's, one relay further away. */
    fun parentOffer(child: DiscoveryAnnouncement, transportFlag: Int): DiscoveryAnnouncement = child.copy(
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
            DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
            DiscoveryAnnouncement.FLAG_RELAY_CAPABLE or transportFlag,
        routeCost = (child.routeCost + 1).coerceAtMost(0xff),
    )

    private const val CALLEE_CHANGED = "Callee identity changed after discovery"
}
