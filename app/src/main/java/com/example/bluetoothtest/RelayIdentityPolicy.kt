package com.example.bluetoothtest

/**
 * Relayed calls under the BMH3 pseudonym defaults (DECISIONS Design C "Amendment CP", review CP-1). Pure rules, JVM-tested:
 * which first records a link may carry, the per-parent SETUP limiter key, the helper's refusals, the FAIL code of a closure
 * before READY, and what the target does with a relayed call.
 */
internal object RelayIdentityPolicy {
    /** Shared limiter bucket for pseudonymous parents whose link address is unknown (never logged). */
    const val UNKNOWN_ADDRESS_BUCKET = "addr:unknown"

    /**
     * Whether a link's first record is refused before dispatch. Store-and-forward needs a stable identity on both ends, so
     * any throwaway (or a verification-code session) refuses it as before. A relay request is refused on a verification-code
     * link always, and on a link with a throwaway on either side unless pseudonymous relay edges are on and this phone is a
     * helper (its own switch decides admission next). Every other first record (an end-to-end handshake of a direct
     * stranger call included) goes on to the call handshake unchanged.
     */
    fun refusesFirstRecord(
        type: SecureRecordType,
        ownPseudonymous: Boolean,
        peerPseudonymous: Boolean,
        qrTagMatched: Boolean,
        pseudonymousEdges: Boolean,
        helperPresent: Boolean,
    ): Boolean {
        val throwaway = ownPseudonymous || peerPseudonymous
        return when (type) {
            SecureRecordType.STORE_FORWARD -> throwaway || qrTagMatched
            SecureRecordType.RELAY_SETUP -> qrTagMatched || (throwaway && !(pseudonymousEdges && helperPresent))
            else -> false
        }
    }

    /**
     * The per-parent SETUP limiter key. A real parent keeps its node prefix (null here: the caller's own key is used); a
     * pseudonymous parent mints keys for free, so it is counted by the link's remote address, and an unknown address shares
     * one bucket. The overall cap and one relay at a time are the bounds that hold either way.
     */
    fun limiterKey(pseudonymousParent: Boolean, remoteAddress: String?): String? = when {
        !pseudonymousParent -> null
        remoteAddress.isNullOrBlank() -> UNKNOWN_ADDRESS_BUCKET
        else -> "addr:" + remoteAddress.uppercase()
    }

    /**
     * A rotating helper answers a fixed-prefix SETUP (message 1) with FAIL 5 under pseudonymous edges: its target is a
     * version 2 offer, which pseudonym mode never dials with BMH2 (review CP-1 item 1).
     */
    fun refusesSetupMessage(pseudonymousEdges: Boolean, helperRotating: Boolean, setupRotating: Boolean): Boolean =
        pseudonymousEdges && helperRotating && !setupRotating

    /**
     * The FAIL code of a helper's setup that ended before READY. With pseudonymous edges an I/O end while connecting or
     * authenticating is a TIMEOUT (the pair is skipped briefly), never "no route", which would drop the table entry and
     * penalise a working pair; an identity or capability problem of the next hop stays code 3, a duty refusal code 5.
     * Without them: the earlier mapping (a failed dial was "no route").
     */
    fun failureBeforeReady(
        pseudonymousEdges: Boolean,
        dutyRefused: Boolean,
        waitingForNext: Boolean,
        authenticating: Boolean,
        ioError: Boolean,
        identityProblem: Boolean,
    ): RelaySetupFailure = when {
        dutyRefused -> RelaySetupFailure.REFUSED
        waitingForNext -> RelaySetupFailure.TIMEOUT
        authenticating && !ioError -> RelaySetupFailure.CHILD_AUTHENTICATION
        identityProblem -> RelaySetupFailure.CHILD_AUTHENTICATION
        pseudonymousEdges -> RelaySetupFailure.TIMEOUT
        else -> RelaySetupFailure.NO_ROUTE
    }

    /**
     * Whether this side of a link skips the `BDO1` opening exchange. A responder that presented a throwaway cleared link bit 3
     * and withholds its salt, and the initiator that met it skips too. A real responder answering a throwaway initiator
     * exchanges, as that initiator does (review CP-1 item 5: the responder used to skip whenever its peer was a throwaway,
     * so the initiator's BDO1 arrived as an unexpected first record).
     */
    fun skipsLinkOpening(initiator: Boolean, ownPseudonymous: Boolean, peerPseudonymous: Boolean): Boolean =
        if (initiator) peerPseudonymous else ownPseudonymous

    enum class Incoming { RING, DECLINE_RELAYING_OFF, DECLINE_STRANGER }

    /**
     * What a target does with an incoming call before it rings. A relayed call (the authenticated link peer is not the call
     * peer, or the Invite says so) rings only while this phone's relayed calls and relay consent are both on: a dishonest
     * helper can ignore the consent bit in the advert, so the target enforces it. A relayed call from a stranger (the
     * end-to-end caller is a throwaway) rings only with relayed stranger calls on; a relay can sit in the middle of such a
     * call, so the ring says "via a phone nearby".
     */
    fun incoming(
        viaRelay: Boolean,
        relayedCallsOn: Boolean,
        consentOn: Boolean,
        callerPseudonymous: Boolean,
        strangerCallsOn: Boolean,
    ): Incoming = when {
        !viaRelay -> Incoming.RING
        !(relayedCallsOn && consentOn) -> Incoming.DECLINE_RELAYING_OFF
        callerPseudonymous && !strangerCallsOn -> Incoming.DECLINE_STRANGER
        else -> Incoming.RING
    }

    /** The status line of a ring from an unverified stranger; [viaRelay] names the possible relay in the middle. */
    fun strangerRingStatus(viaRelay: Boolean): String = if (viaRelay) {
        "Incoming call from an unverified stranger via a phone nearby: if you answer, your identity is revealed to " +
            "whoever holds the call, which may be a relaying phone"
    } else {
        "Incoming call from an unverified stranger: your identity is revealed only if you answer"
    }
}
