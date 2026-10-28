package com.example.bluetoothtest

/**
 * Review findings of 2026-10-19, verified against the code and fixed narrowly. Pure rules, unit-tested;
 * the call endpoint applies them.
 */

/**
 * Nothing from a caller may reach the speaker or the screen before the user answers. The endpoint used to decode a
 * `STREAM_HEADER` and play `MEDIA_PACKET`s in any phase, so a caller could send audio while this phone was still ringing
 * (or before its Invite). A legitimate peer sends its header only after the Accept on the same ordered control queue
 * (media packets go out only when that queue is empty), so honest media always arrives in NEGOTIATING_MEDIA or ACTIVE.
 */
internal object CallMediaGate {
    enum class Decision { ALLOW, DROP, REFUSE }

    val MEDIA_TYPES = setOf(
        EndToEndContentType.STREAM_HEADER,
        EndToEndContentType.MEDIA_PACKET,
        EndToEndContentType.VIDEO_FRAGMENT,
    )

    /**
     * ALLOW once answered; DROP late media after the call ended; REFUSE (ends the call) before the answer, and while
     * [peerRevealPending] (a callee that still waits for a pseudonymous caller's reveal, [CallerRevealGate]).
     */
    fun decide(phase: PrivateCallPhase?, peerRevealPending: Boolean = false): Decision = when (phase) {
        PrivateCallPhase.NEGOTIATING_MEDIA, PrivateCallPhase.ACTIVE -> if (peerRevealPending) Decision.REFUSE else Decision.ALLOW
        PrivateCallPhase.ENDED -> Decision.DROP
        else -> Decision.REFUSE
    }
}

/**
 * Review 2026-10-27 (finding 1, high): the callee of a pseudonym-pseudonym call sent its own `REVEAL` at Accept but never
 * required the caller's, so a caller could stay a throwaway for the whole call while the callee's user believed the
 * other side had been identified. A caller that declared `PSEUDONYM` speaks `REVEAL` and sends its own right after it
 * verified the callee's (before its stream header), so the callee now waits at most [DEADLINE_MS] after Accept and
 * hangs up when nothing verified arrived. Media before the reveal is refused ([CallMediaGate]).
 */
internal object CallerRevealGate {
    /**
     * The caller's side is one ECDSA verification and one Keystore signature plus a round trip; the bound is the same
     * 10 s as the hop handshake deadline ([CallSetupDeadlines.HOP_AUTH_MS]), not a measured limit.
     */
    const val DEADLINE_MS = 10_000L

    /** Extra time per relay hop, and the longest wait (owner rule 2026-10-27: a slow genuine route must not lose its call). */
    const val PER_RELAY_HOP_MS = 10_000L
    const val MAX_DEADLINE_MS = 40_000L

    const val MESSAGE = "The caller did not confirm its identity. The call was ended."

    /**
     * The wait for a call that crossed [relayHops] relays. The count is the caller's claim, so a lying caller can only
     * stretch its own wait to [MAX_DEADLINE_MS], during which its media is refused anyway: the generous bound costs the
     * attacker nothing it could use and keeps a genuine caller on a congested relayed route from being hung up on.
     */
    fun deadlineMs(relayHops: Int): Long =
        (DEADLINE_MS + PER_RELAY_HOP_MS * relayHops.coerceIn(0, 3)).coerceAtMost(MAX_DEADLINE_MS)

    /**
     * The hops the deadline grants (review 2026-10-27, finding 2): a direct call (the authenticated link peer is the call
     * peer) gets none whatever the Invite claims; a relayed call gets its claim, at least one and at most three.
     */
    fun effectiveHops(direct: Boolean, claimedHops: Int): Int = if (direct) 0 else claimedHops.coerceIn(1, 3)

    /** True when the call must end now: the reveal is still pending [deadlineMs] after [acceptedAtMs]. */
    fun expired(revealPending: Boolean, acceptedAtMs: Long, nowMs: Long, relayHops: Int = 0): Boolean =
        revealPending && nowMs - acceptedAtMs >= deadlineMs(relayHops)
}

/**
 * A verification-code connection is verify-only at both layers. The responder used to scope the session only when the
 * END-TO-END handshake matched the code's tag: a code holder could match it at the link layer (which also lets it past
 * "accept nothing but the code" while Allow calls is off), then run an untagged end-to-end handshake, and its Invite was
 * handled as an ordinary call. The two layers must agree.
 */
internal object QrLayerBinding {
    /** True when the connection may continue: the code matched at both layers or at neither. */
    fun consistent(linkMatched: Boolean, endpointMatched: Boolean): Boolean = linkMatched == endpointMatched
}

/**
 * Codec configuration kept until the decoder exists. The receiver appended every configuration payload while no decoder
 * existed, without a count or byte limit, so a peer could keep sending configuration and never a frame. A real encoder
 * sends a few small entries (SPS/PPS, the AV1 sequence header, the Opus header); a newer configuration supersedes an older
 * one, so the oldest entries are dropped beyond the bounds instead of ending the call.
 */
internal object CodecConfigBudget {
    const val MAX_ENTRIES = 16
    const val MAX_BYTES = 128 * 1024

    /** Appends [entry] to [list], then drops the oldest entries until both bounds hold (an oversized entry is dropped). */
    fun add(list: MutableList<ByteArray>, entry: ByteArray) {
        if (entry.size > MAX_BYTES) return
        list += entry.copyOf()
        while (list.size > MAX_ENTRIES || list.sumOf { it.size } > MAX_BYTES) list.removeAt(0)
    }
}

/**
 * Presence actions run in the order the session produced them. They used to run on separate threads per event, so the
 * LEASE answering the peer's OFFER could be queued before this side's own OFFER (whose send-time check touches the
 * Keystore), and the peer then rejected it as "lease without both offers" with no retry. Producing the actions and
 * handing them to one ordered executor happen under one lock.
 */
internal class OrderedPresenceDispatch(private val execute: (Runnable) -> Unit) {
    private val lock = Any()

    fun dispatch(produce: () -> List<PresenceSession.Action>, runIfEmpty: Boolean, consume: (List<PresenceSession.Action>) -> Unit) {
        synchronized(lock) {
            val actions = produce()
            if (actions.isEmpty() && !runIfEmpty) return
            execute(Runnable { consume(actions) })
        }
    }
}

/**
 * Review 2026-10-27 (finding 7): "accept only the code" (finding F10) held only inside the BMH3 responder. A phone that
 * is call-ready only because the Meet screen is open refuses every other incoming link at the call endpoint too, so a
 * BMH2 hello (accepted when pseudonyms are off) can no longer ring it, exchange messages or ask it to relay.
 */
internal object MeetScreenGate {
    fun refuses(screenOnlyReachable: Boolean, linkMatchedCode: Boolean): Boolean = screenOnlyReachable && !linkMatchedCode
}

/**
 * Review 2026-10-27 (finding 2, high): the one place that decides when the callee's own camera, microphone and stream
 * header start. Accept (the UI thread) and the caller's reveal (the reader thread) used to decide separately, so a blocked
 * caller's reveal could slip between them and still get media, or an honest call could stay held for ever. Every input goes
 * through this lock; media starts at most once: after Accept, and (when the caller's reveal is required) only after that
 * reveal passed the person policy ([onRevealAdmitted] is never called for a blocked person). [onBlocked] closes it for good.
 */
internal class CalleeMediaStart(private val revealRequired: Boolean) {
    private var accepted = false
    private var admitted = false
    private var blocked = false
    private var started = false

    /** True: start the local media now (exactly once). */
    @Synchronized
    fun onAccepted(): Boolean {
        accepted = true
        return decide()
    }

    /** The caller's reveal verified and passed the person policy. True: start the local media now. */
    @Synchronized
    fun onRevealAdmitted(): Boolean {
        admitted = true
        return decide()
    }

    @Synchronized
    fun onBlocked() {
        blocked = true
    }

    @get:Synchronized
    val held: Boolean get() = accepted && !started && !blocked

    private fun decide(): Boolean {
        if (started || blocked || !accepted || (revealRequired && !admitted)) return false
        started = true
        return true
    }
}
