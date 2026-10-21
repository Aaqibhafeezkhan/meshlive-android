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

    /** ALLOW once answered; DROP late media after the call ended; REFUSE (ends the call) before the answer. */
    fun decide(phase: PrivateCallPhase?): Decision = when (phase) {
        PrivateCallPhase.NEGOTIATING_MEDIA, PrivateCallPhase.ACTIVE -> Decision.ALLOW
        PrivateCallPhase.ENDED -> Decision.DROP
        else -> Decision.REFUSE
    }
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
