package com.example.bluetoothtest

/**
 * Rotating discovery IDs, Phase 1b/2 at the call endpoint (PROTOCOL "Phase 1b: call endpoints and relay
 * chains", "Recognition and presence leases"): endpoint hello bit 3, the first-record `BDX1` OPENING and
 * the OFFER / PRESENCE_LEASE session rules. Pure apart from the record I/O helpers; JVM-tested.
 */
object CallEndpointOpening {
    /** Endpoint hello transport-mask bit 3: "sends and accepts `BDX1` (BME1 type 11)". Call endpoints only. */
    const val HELLO_BIT = 1 shl 3

    /** The endpoint hello a call endpoint signs: [base] with bit 3 when the experiment is on. */
    fun advertise(base: NodeCapabilities, featureOn: Boolean): NodeCapabilities =
        if (featureOn) base.copy(transportMask = base.transportMask or HELLO_BIT) else base

    /** True when this endpoint advertised bit 3 and the peer's authenticated hello carries it too. */
    fun negotiated(featureOn: Boolean, peer: NodeCapabilities): Boolean =
        featureOn && peer.transportMask and HELLO_BIT != 0

    /**
     * RV15 at the endpoint: the callee's OPENING must open the selected version 5 offer for the callee's
     * authenticated endpoint node ID; a direct call must also have the link node equal the endpoint node.
     * A selected version 2 offer keeps the end-to-end prefix check (the caller's existing code).
     */
    fun check(
        selected: DiscoveryAnnouncement,
        endpointNodeId: NodeId,
        linkNodeId: NodeId,
        direct: Boolean,
        opening: List<OpeningEntry>?,
    ): DiscoverySelectionCheck.Result {
        val result = DiscoverySelectionCheck.check(
            selected,
            RotatingDiscoveryIds.roleFor(selected),
            endpointNodeId.toByteArray(),
            opening,
        )
        if (result != DiscoverySelectionCheck.Result.PASS || !selected.rotating) return result
        return if (direct && endpointNodeId != linkNodeId) DiscoverySelectionCheck.Result.PREFIX_MISMATCH else result
    }

    /**
     * The caller's check of the callee's FIRST end-to-end record: it must be a `BDX1` OPENING that opens the
     * selected version 5 offer ([check]). Any other first record, or an undecodable one, is a missing opening.
     */
    fun verifyFirstRecord(
        selected: DiscoveryAnnouncement,
        endpointNodeId: NodeId,
        linkNodeId: NodeId,
        direct: Boolean,
        first: EndToEndContentRecord,
    ): DiscoverySelectionCheck.Result {
        if (first.type != EndToEndContentType.DISCOVERY) return DiscoverySelectionCheck.Result.OPENING_MISSING
        val entries = (runCatching { DiscoveryExchange.decode(first.payload) }.getOrNull() as? DiscoveryExchange.Opening)?.entries
            ?: return DiscoverySelectionCheck.Result.OPENING_MISSING
        return check(selected, endpointNodeId, linkNodeId, direct, entries)
    }
}

/**
 * The one definition of "this call may exchange presence leases with this peer" (session creation, the OFFER send and
 * the log). Returns the fixed reason it may not, or null. `peerState` is the verified-contact store's answer for the
 * peer's real identity (null: no store attached).
 */
object PresenceEligibility {
    fun reason(
        vaultPresent: Boolean,
        persistent: Boolean,
        peerState: VerificationState?,
        visibleToThem: Boolean,
        /** Naming v2 ([CodeOnlyLeaseRule]): a contact only checked by code gets no new leases until scanned both ways. */
        codeOnlyUnscanned: Boolean = false,
    ): String? = when {
        !vaultPresent -> "no_vault"
        !persistent -> "vault_not_persistent"
        peerState == null -> "no_contact_store"
        peerState != VerificationState.VERIFIED -> "peer_${peerState.name.lowercase()}"
        !visibleToThem -> "contact_switch_off"
        codeOnlyUnscanned -> "code_only_not_scanned"
        else -> null
    }
}

/**
 * The OFFER / PRESENCE_LEASE rules of one call session (design 5.1-5.2). [localEligible] is decided once,
 * when the session starts: the experiment is on, the vault is persistent, the authenticated peer is a
 * verified contact and the per-contact switch is on. A lease is stored only after both sides offered.
 */
class PresenceSession(private val localEligible: Boolean) {
    sealed class Action {
        object SendOffer : Action()
        object SendLease : Action()
        class StoreLease(val lease: DiscoveryExchange.PresenceLease) : Action()
        class Reject(val reason: String) : Action()
    }

    private var started = false
    private var sentOffer = false
    private var receivedOffer = false
    private var sentLease = false
    private var receivedLease = false

    /**
     * Called once, when the call is answered. The peer may already have offered (it answered first): its
     * OFFER was recorded by [onRecord] and the lease follows our OFFER at once.
     */
    @Synchronized
    fun start(): List<Action> {
        if (!localEligible || started) return emptyList()
        started = true
        sentOffer = true
        return if (receivedOffer && !sentLease) {
            sentLease = true
            listOf(Action.SendOffer, Action.SendLease)
        } else {
            listOf(Action.SendOffer)
        }
    }

    /**
     * The OFFER from [start] could not be sent (a Forget, a switch-off or a store problem since the handshake):
     * this side never offered, so it neither sends a lease nor stores one.
     */
    @Synchronized
    fun offerWithheld() {
        sentOffer = false
        sentLease = true
    }

    /** [peerVerifiedNow]: the peer's authenticated fingerprint is VERIFIED in the store at this moment. */
    @Synchronized
    fun onRecord(record: DiscoveryExchange.Record, peerVerifiedNow: Boolean): List<Action> = when (record) {
        is DiscoveryExchange.Opening -> listOf(Action.Reject("opening repeated"))
        is DiscoveryExchange.IdentityReveal -> listOf(Action.Reject("reveal outside the reveal exchange"))
        DiscoveryExchange.RecognitionOffer -> when {
            receivedOffer -> listOf(Action.Reject("duplicate offer"))
            else -> {
                receivedOffer = true
                if (sentOffer && !sentLease) {
                    sentLease = true
                    listOf(Action.SendLease)
                } else {
                    emptyList()
                }
            }
        }
        is DiscoveryExchange.PresenceLease -> when {
            receivedLease -> listOf(Action.Reject("duplicate lease"))
            !sentOffer || !receivedOffer -> listOf(Action.Reject("lease without both offers"))
            !peerVerifiedNow -> listOf(Action.Reject("peer is not a verified contact"))
            else -> {
                receivedLease = true
                listOf(Action.StoreLease(record))
            }
        }
    }
}
