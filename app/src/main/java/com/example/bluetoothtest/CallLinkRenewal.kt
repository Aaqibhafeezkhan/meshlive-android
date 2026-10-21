package com.example.bluetoothtest

import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Experimental in-call link renewal (Design SR revision 2; PROTOCOL "Experimental in-call link renewal"; switch
 * `test_call_link_renewal`, DEBUG, default off). The pure parts: the `RENEW` record codec, the keys and MACs, the
 * renewal-opening frames, the scope check and the two state machines. No Android calls; JVM-tested with a fake clock.
 */
internal object CallRenewalWire {
    /** End-to-end hello transport-mask bit 4: "sends and accepts content type 12 and the renewal opening". */
    const val CAPABILITY_BIT = 1 shl 4

    const val OP_OFFER = 1
    const val OP_ARMED = 2
    const val OP_REFUSED = 3
    const val OP_BIND = 4

    enum class Reason(val wire: Int) {
        NOT_DIRECT(1),
        LIMIT(2),
        SERVER_REGISTRATION(3),
        ADVERTISING(4),
        COLLISION(5),
        DISABLED(6),

        /** Sent by the offerer: it kept the old link (the peer was heard again); the armed phone disarms. */
        ABANDONED(7),
        ;

        val label: String get() = name.lowercase()

        companion object {
            fun of(wire: Int): Reason = entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("Invalid renewal reason $wire")
        }
    }

    sealed class Message {
        abstract val ticket: Long
    }

    /** [role] 0 = end-to-end initiator (caller), 1 = responder. */
    data class Offer(override val ticket: Long, val role: Int) : Message()
    data class Armed(override val ticket: Long) : Message()
    data class Refused(override val ticket: Long, val reason: Reason) : Message()

    /** [nextSequence] = the `BME1` sequence of this BIND record plus one. */
    data class Bind(override val ticket: Long, val nextSequence: Long) : Message()

    fun advertise(base: NodeCapabilities, enabled: Boolean): NodeCapabilities =
        if (enabled) base.copy(transportMask = base.transportMask or CAPABILITY_BIT) else base

    fun negotiated(enabled: Boolean, peer: NodeCapabilities): Boolean = enabled && peer.transportMask and CAPABILITY_BIT != 0

    /** Both end-to-end hellos signed bit 4: only then does the session derive `K_renew` (call sessions with renewal on). */
    fun bothSigned(local: NodeCapabilities, peer: NodeCapabilities): Boolean =
        local.transportMask and CAPABILITY_BIT != 0 && peer.transportMask and CAPABILITY_BIT != 0

    fun encode(message: Message): ByteArray {
        require(message.ticket != 0L) { "A renewal ticket is never zero" }
        return when (message) {
            is Offer -> {
                require(message.role == 0 || message.role == 1) { "Invalid renewal role" }
                ByteBuffer.allocate(10).put(OP_OFFER.toByte()).putLong(message.ticket).put(message.role.toByte()).array()
            }
            is Armed -> ByteBuffer.allocate(9).put(OP_ARMED.toByte()).putLong(message.ticket).array()
            is Refused -> ByteBuffer.allocate(10).put(OP_REFUSED.toByte()).putLong(message.ticket).put(message.reason.wire.toByte()).array()
            is Bind -> ByteBuffer.allocate(17).put(OP_BIND.toByte()).putLong(message.ticket).putLong(message.nextSequence).array()
        }
    }

    /** Exact lengths; anything else is malformed and fails closed (IllegalArgumentException), like every `BME1` record. */
    fun decode(payload: ByteArray): Message {
        require(payload.isNotEmpty()) { "Empty renewal record" }
        val buffer = ByteBuffer.wrap(payload)
        val op = buffer.get().toInt() and 0xff
        val expected = when (op) {
            OP_OFFER, OP_REFUSED -> 10
            OP_ARMED -> 9
            OP_BIND -> 17
            else -> throw IllegalArgumentException("Unknown renewal op $op")
        }
        require(payload.size == expected) { "Invalid renewal record length ${payload.size} for op $op" }
        val ticket = buffer.long
        require(ticket != 0L) { "Zero renewal ticket" }
        return when (op) {
            OP_OFFER -> {
                val role = buffer.get().toInt() and 0xff
                require(role == 0 || role == 1) { "Invalid renewal role $role" }
                Offer(ticket, role)
            }
            OP_ARMED -> Armed(ticket)
            OP_REFUSED -> Refused(ticket, Reason.of(buffer.get().toInt() and 0xff))
            else -> {
                val next = buffer.long
                require(next > 0L) { "Invalid renewal sequence" }
                Bind(ticket, next)
            }
        }
    }
}

/** `K_renew`, the advert UUID, the opening MACs and the new link's hop keys (PROTOCOL "Renewal key", "Renewal opening"). */
internal object CallRenewalCrypto {
    private val SALT = "BluetoothVideoMesh/SR1".toByteArray(Charsets.US_ASCII)
    private val KEY_INFO = "renew-key".toByteArray(Charsets.US_ASCII)
    private val SRV = "SR1 srv".toByteArray(Charsets.US_ASCII)
    private val OFFER = "SR1 offer".toByteArray(Charsets.US_ASCII)
    private val ARMED = "SR1 armed".toByteArray(Charsets.US_ASCII)
    private val HOP = "SR1 hop".toByteArray(Charsets.US_ASCII)
    const val KEY_BYTES = 32
    const val MAC_BYTES = 32
    const val RANDOM_BYTES = 16

    /** Both endpoints get the same key from their two directional content keys, without knowing their role. */
    fun renewalKey(k1: ByteArray, k2: ByteArray): ByteArray {
        require(k1.size == KEY_BYTES && k2.size == KEY_BYTES) { "Renewal key input must be two 32-byte keys" }
        val (low, high) = if (compareUnsigned(k1, k2) <= 0) k1 to k2 else k2 to k1
        val ikm = low + high
        return try {
            hkdfSha256(ikm, SALT, KEY_INFO, KEY_BYTES)
        } finally {
            ikm.fill(0)
        }
    }

    fun advertUuid(key: ByteArray, callId: UUID, ticket: Long): UUID {
        val bytes = hmac(key, SRV, uuidBytes(callId), longBytes(ticket)).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    fun offerMac(key: ByteArray, callId: UUID, ticket: Long, challenge: ByteArray, nonce: ByteArray): ByteArray =
        hmac(key, OFFER, uuidBytes(callId), longBytes(ticket), challenge, nonce)

    fun armedMac(key: ByteArray, callId: UUID, ticket: Long, challenge: ByteArray, nonce: ByteArray): ByteArray =
        hmac(key, ARMED, uuidBytes(callId), longBytes(ticket), challenge, nonce)

    /** First: offerer -> armed; second: armed -> offerer. */
    fun hopKeys(key: ByteArray, callId: UUID, ticket: Long, challenge: ByteArray, nonce: ByteArray): Pair<ByteArray, ByteArray> {
        val material = hkdfSha256(key, challenge + nonce, HOP + uuidBytes(callId) + longBytes(ticket), KEY_BYTES * 2)
        return try {
            material.copyOfRange(0, KEY_BYTES) to material.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
        } finally {
            material.fill(0)
        }
    }

    fun macEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(::update)
        doFinal()
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return 0
    }

    private fun uuidBytes(id: UUID): ByteArray = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()

    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()
}

/** The three fixed-length frames of the renewal opening, before any `BMS1` byte on the new link. */
internal object CallRenewalFrames {
    private val CHALLENGE_MAGIC = "SR1C".toByteArray(Charsets.US_ASCII)
    private val PROOF_MAGIC = "SR1P".toByteArray(Charsets.US_ASCII)
    private val CONFIRM_MAGIC = "SR1A".toByteArray(Charsets.US_ASCII)
    const val CHALLENGE_BYTES = 4 + CallRenewalCrypto.RANDOM_BYTES
    const val PROOF_BYTES = 4 + 8 + CallRenewalCrypto.RANDOM_BYTES + CallRenewalCrypto.MAC_BYTES
    const val CONFIRM_BYTES = 4 + CallRenewalCrypto.MAC_BYTES

    data class Proof(val ticket: Long, val nonce: ByteArray, val mac: ByteArray)

    fun challenge(challenge: ByteArray): ByteArray {
        require(challenge.size == CallRenewalCrypto.RANDOM_BYTES)
        return CHALLENGE_MAGIC + challenge
    }

    fun parseChallenge(frame: ByteArray): ByteArray {
        require(frame.size == CHALLENGE_BYTES && frame.copyOf(4).contentEquals(CHALLENGE_MAGIC)) { "Invalid renewal challenge" }
        return frame.copyOfRange(4, CHALLENGE_BYTES)
    }

    fun proof(ticket: Long, nonce: ByteArray, mac: ByteArray): ByteArray {
        require(nonce.size == CallRenewalCrypto.RANDOM_BYTES && mac.size == CallRenewalCrypto.MAC_BYTES)
        return ByteBuffer.allocate(PROOF_BYTES).put(PROOF_MAGIC).putLong(ticket).put(nonce).put(mac).array()
    }

    fun parseProof(frame: ByteArray): Proof {
        require(frame.size == PROOF_BYTES && frame.copyOf(4).contentEquals(PROOF_MAGIC)) { "Invalid renewal proof" }
        val buffer = ByteBuffer.wrap(frame, 4, PROOF_BYTES - 4)
        val ticket = buffer.long
        require(ticket != 0L) { "Zero renewal ticket" }
        val nonce = ByteArray(CallRenewalCrypto.RANDOM_BYTES).also(buffer::get)
        val mac = ByteArray(CallRenewalCrypto.MAC_BYTES).also(buffer::get)
        return Proof(ticket, nonce, mac)
    }

    fun confirm(mac: ByteArray): ByteArray {
        require(mac.size == CallRenewalCrypto.MAC_BYTES)
        return CONFIRM_MAGIC + mac
    }

    fun parseConfirm(frame: ByteArray): ByteArray {
        require(frame.size == CONFIRM_BYTES && frame.copyOf(4).contentEquals(CONFIRM_MAGIC)) { "Invalid renewal confirmation" }
        return frame.copyOfRange(4, CONFIRM_BYTES)
    }
}

/** Which calls may renew (PROTOCOL "Scope (checked, not assumed)"), and the per-call limits. */
internal object CallRenewalScope {
    const val MIN_ACTIVE_MS = 10_000L
    const val MAX_ATTEMPTS = 2
    const val MIN_INTERVAL_MS = 60_000L

    data class Facts(
        val gattOnBothEnds: Boolean,
        val relayHops: Int,
        val relayedRoute: Boolean,
        val linkPeerIsEndpointPeer: Boolean,
        val verificationSession: Boolean,
    )

    /** A direct call: the hop peer is the end-to-end peer, over GATT, not a verification session. */
    fun direct(facts: Facts): Boolean =
        facts.gattOnBothEnds && facts.relayHops == 0 && !facts.relayedRoute && facts.linkPeerIsEndpointPeer && !facts.verificationSession

    /** Null: the renewal may run now. Otherwise the reason an armed phone answers (or an offerer does not offer). */
    fun refusal(
        enabled: Boolean,
        negotiated: Boolean,
        facts: Facts,
        phaseActive: Boolean,
        activeForMs: Long,
        attempts: Int,
        sinceLastAttemptMs: Long?,
    ): CallRenewalWire.Reason? = when {
        !enabled || !negotiated -> CallRenewalWire.Reason.DISABLED
        !direct(facts) -> CallRenewalWire.Reason.NOT_DIRECT
        !phaseActive || activeForMs < MIN_ACTIVE_MS || attempts >= MAX_ATTEMPTS -> CallRenewalWire.Reason.LIMIT
        sinceLastAttemptMs != null && sinceLastAttemptMs < MIN_INTERVAL_MS -> CallRenewalWire.Reason.LIMIT
        else -> null
    }
}

/**
 * The offerer: the phone that stopped hearing its peer while its own writes complete. It keeps the old link until it sees
 * the armed advert (review 2, finding 2), and every path ends within [HARD_BOUND_MS] of the OFFER.
 */
internal class RenewalOfferer(private val clockMs: () -> Long) {
    enum class Phase { IDLE, SCANNING, WAITING_OLD_LINK, CONNECTING, BOUND, ABANDONED, FAILED }

    enum class Tick { NONE, STOP_SCAN, END_CALL }

    @Volatile var phase = Phase.IDLE
        private set
    var ticket = 0L
        private set
    var offeredAtMs = 0L
        private set

    /** Stage A's soft state has lasted long enough to offer. */
    fun shouldTrigger(softSinceMs: Long?): Boolean =
        phase == Phase.IDLE && softSinceMs != null && clockMs() - softSinceMs >= SOFT_HOLD_MS

    @Synchronized
    fun offer(ticket: Long) {
        check(phase == Phase.IDLE) { "Renewal already running" }
        require(ticket != 0L)
        this.ticket = ticket
        offeredAtMs = clockMs()
        phase = Phase.SCANNING
    }

    /**
     * The renewal owns the call's liveness while it scans or connects: the normal stall close waits for [HARD_BOUND_MS].
     * Once the scan ended without a sighting (or the peer refused), the old link stays and the normal rules apply again.
     */
    fun ownsLiveness(): Boolean = phase == Phase.SCANNING || phase == Phase.CONNECTING || phase == Phase.BOUND

    /** A record other than `RENEW` arrived on the old link: true when the renewal is abandoned (old link kept). */
    @Synchronized
    fun onPeerHeard(): Boolean {
        if (phase != Phase.SCANNING && phase != Phase.WAITING_OLD_LINK) return false
        phase = Phase.ABANDONED
        return true
    }

    /** The armed advert was seen: true means break the old link now and connect (only inside the scan window). */
    @Synchronized
    fun onAdvertFound(): Boolean {
        if (phase != Phase.SCANNING || clockMs() - offeredAtMs >= SCAN_MS) return false
        phase = Phase.CONNECTING
        return true
    }

    /** Another connect attempt is still allowed (a failure before CONFIRM, a squatter, a copied advert). */
    fun mayRetry(): Boolean = phase == Phase.CONNECTING && clockMs() - offeredAtMs < LAST_CONNECT_MS

    /** What a connect attempt may still take (GATT open plus opening): up to [LAST_CONNECT_MS] after the OFFER, never past it. */
    fun connectBudgetMs(): Long = if (phase == Phase.CONNECTING) (offeredAtMs + LAST_CONNECT_MS - clockMs()).coerceAtLeast(0L) else 0L

    /** A collision counts only while this phone's own offer still scans; a finished scan is no longer a pending offer. */
    fun ownOfferPending(): Boolean = phase == Phase.SCANNING

    @Synchronized
    fun onBound(): Boolean {
        if (phase != Phase.CONNECTING || clockMs() - offeredAtMs >= HARD_BOUND_MS) return false
        phase = Phase.BOUND
        return true
    }

    /** The peer refused or yielded: the renewal is over and the old link stays (if the offerer still has it). */
    @Synchronized
    fun onRefused(): Boolean {
        if (phase != Phase.SCANNING && phase != Phase.WAITING_OLD_LINK) return false
        phase = Phase.WAITING_OLD_LINK
        return true
    }

    /** Collision: this phone (callee) yields its own offer and arms for the caller's. */
    @Synchronized
    fun yieldToPeer(): Boolean {
        if (phase != Phase.SCANNING) return false
        phase = Phase.ABANDONED
        return true
    }

    @Synchronized
    fun tick(): Tick {
        val elapsed = clockMs() - offeredAtMs
        return when {
            (phase == Phase.SCANNING || phase == Phase.CONNECTING) && elapsed >= HARD_BOUND_MS -> {
                phase = Phase.FAILED
                Tick.END_CALL
            }
            phase == Phase.SCANNING && elapsed >= SCAN_MS -> {
                phase = Phase.WAITING_OLD_LINK
                Tick.STOP_SCAN
            }
            else -> Tick.NONE
        }
    }

    /** A finished renewal (bound or abandoned) lets the next one start, within the call's limits. */
    @Synchronized
    fun reset() {
        if (phase == Phase.BOUND || phase == Phase.ABANDONED || phase == Phase.WAITING_OLD_LINK) phase = Phase.IDLE
    }

    companion object {
        /** Offer after 1 s of Stage A's soft state (4 s of silence): a false trigger costs a scan, not the call. */
        const val SOFT_HOLD_MS = 1_000L
        const val SCAN_MS = 6_000L
        const val LAST_CONNECT_MS = 11_000L
        const val HARD_BOUND_MS = 12_000L
    }
}

/** Collision: both phones offered. The caller (role 0) keeps its offer; the callee yields and arms. */
internal object RenewalCollision {
    fun keepOwn(ownRole: Int): Boolean = ownRole == 0
}

/**
 * The renewal opening's two halves on any byte streams (the GATT link's in production, pipes in the tests), so the
 * production path is the tested path (review of 4dbac47, item 5).
 */
internal object CallRenewalOpening {
    /** The PROOF is for this call and this ticket and was made with `K_renew` over this challenge. */
    fun proofValid(key: ByteArray, callId: UUID, ticket: Long, challenge: ByteArray, proof: CallRenewalFrames.Proof): Boolean =
        proof.ticket == ticket && CallRenewalCrypto.macEquals(proof.mac, CallRenewalCrypto.offerMac(key, callId, ticket, challenge, proof.nonce))

    /** Offerer: challenge in, proof out, confirmation in. Hop keys (to armed, from armed), or null when the peer did not prove itself. */
    fun offerer(input: InputStream, output: OutputStream, key: ByteArray, callId: UUID, ticket: Long, nonce: ByteArray): Pair<ByteArray, ByteArray>? {
        val data = DataInputStream(input)
        val challenge = CallRenewalFrames.parseChallenge(ByteArray(CallRenewalFrames.CHALLENGE_BYTES).also(data::readFully))
        output.write(CallRenewalFrames.proof(ticket, nonce, CallRenewalCrypto.offerMac(key, callId, ticket, challenge, nonce)))
        output.flush()
        val confirm = CallRenewalFrames.parseConfirm(ByteArray(CallRenewalFrames.CONFIRM_BYTES).also(data::readFully))
        if (!CallRenewalCrypto.macEquals(confirm, CallRenewalCrypto.armedMac(key, callId, ticket, challenge, nonce))) return null
        return CallRenewalCrypto.hopKeys(key, callId, ticket, challenge, nonce)
    }

    /**
     * Armed phone: challenge out, proof in, confirmation out. [admit] is the state machine's decision on the proof's ticket
     * and validity ([RenewalArmed.onProof]); false drops the connection. Hop keys (from offerer, to offerer), or null.
     */
    fun armed(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        callId: UUID,
        ticket: Long,
        challenge: ByteArray,
        admit: (proofTicket: Long, valid: Boolean) -> Boolean,
    ): Pair<ByteArray, ByteArray>? {
        output.write(CallRenewalFrames.challenge(challenge))
        output.flush()
        val proof = CallRenewalFrames.parseProof(ByteArray(CallRenewalFrames.PROOF_BYTES).also(DataInputStream(input)::readFully))
        if (!admit(proof.ticket, proofValid(key, callId, ticket, challenge, proof))) return null
        output.write(CallRenewalFrames.confirm(CallRenewalCrypto.armedMac(key, callId, ticket, challenge, proof.nonce)))
        output.flush()
        return CallRenewalCrypto.hopKeys(key, callId, ticket, challenge, proof.nonce)
    }

    /** The first record of the proved link, opened: a well-formed BIND naming its own sequence plus one, or null. */
    fun bindFrom(content: EndToEndContentRecord?): CallRenewalWire.Bind? {
        if (content == null || content.type != EndToEndContentType.RENEW) return null
        val bind = runCatching { CallRenewalWire.decode(content.payload) }.getOrNull() as? CallRenewalWire.Bind ?: return null
        return bind.takeIf { content.sequence >= 0 && it.nextSequence == content.sequence + 1 }
    }
}

/**
 * Who closes the call when its link has died and no renewal owns its liveness (review item 1): liveness was only in the
 * reader and writer threads, so a link that ended quietly while a renewal ran could leave a call that nothing ended.
 */
internal object RenewalLiveness {
    enum class Decision { NONE, END_DEAD_LINK, END_SILENCE }

    /**
     * [linkDead]: the current link's reader or writer ended quietly. [silenceEnd]: the liveness verdict is END and has been
     * for [GRACE_MS] (the writer, when it runs, acts first and sends its hang-up).
     */
    fun decide(linkDead: Boolean, renewalOwnsLiveness: Boolean, silenceEndSinceMs: Long?, nowMs: Long): Decision = when {
        renewalOwnsLiveness -> Decision.NONE
        linkDead -> Decision.END_DEAD_LINK
        silenceEndSinceMs != null && nowMs - silenceEndSinceMs >= GRACE_MS -> Decision.END_SILENCE
        else -> Decision.NONE
    }

    const val GRACE_MS = 1_000L
    const val TICK_MS = 250L
}

/**
 * The supervisor's state between ticks (re-review N1): the END verdict's start is forgotten while a renewal owns the
 * call's liveness (through BOUND, until the switch reset the timers), so a handback gets the full grace and a renewal
 * about to bind is never ended by a silence it was already repairing.
 */
internal class RenewalLivenessTicker {
    private var endSinceMs: Long? = null

    fun tick(verdictEnd: Boolean, linkDead: Boolean, renewalOwnsLiveness: Boolean, nowMs: Long): RenewalLiveness.Decision {
        endSinceMs = if (verdictEnd && !renewalOwnsLiveness) endSinceMs ?: nowMs else null
        return RenewalLiveness.decide(linkDead, renewalOwnsLiveness, endSinceMs, nowMs)
    }
}

/**
 * Must-deliver signals a replaced writer had taken (written or still unflushed) go back to the head of the queue (re-review
 * N2). Microphone and camera signals are not re-queued: every successful switch re-asserts their current state, and a late
 * old one would land after it.
 */
internal object RenewalRequeue {
    fun signalsToRequeue(taken: List<PrivateCallSignalMessage>): List<PrivateCallSignalMessage> = taken.filter {
        it.signal != PrivateCallSignal.MICROPHONE_MUTED && it.signal != PrivateCallSignal.MICROPHONE_UNMUTED &&
            it.signal != PrivateCallSignal.CAMERA_ON && it.signal != PrivateCallSignal.CAMERA_OFF
    }
}

/**
 * The armed phone's renewal servers (review item 2): the server of the attempt in progress and the server that carries the
 * call's live link after a bound renewal are kept apart, so abandoning a later attempt never closes the live link, and
 * teardown closes both under the same lock (review item 4).
 */
internal class RenewalServers<T : Closeable> {
    private var attempt: T? = null
    private var bound: T? = null
    private var closed = false

    /** False when the call already ended: the caller closes [server] at once. */
    @Synchronized
    fun startAttempt(server: T): Boolean {
        if (closed) return false
        attempt?.let { runCatching { it.close() } }
        attempt = server
        return true
    }

    @Synchronized
    fun attempt(): T? = attempt

    @Synchronized
    fun bound(): T? = bound

    /** The attempt failed, was refused or abandoned: close its server only. */
    @Synchronized
    fun abandonAttempt() {
        attempt?.let { runCatching { it.close() } }
        attempt = null
    }

    /** The attempt bound: its server now carries the live link; the previous live server (an older renewal's) is returned to close after the switch. */
    @Synchronized
    fun promote(): T? {
        val previous = bound
        bound = attempt
        attempt = null
        return previous
    }

    @Synchronized
    fun closeAll() {
        closed = true
        attempt?.let { runCatching { it.close() } }
        bound?.let { runCatching { it.close() } }
        attempt = null
        bound = null
    }
}

/**
 * The armed phone: it still hears its peer but its own sending stalled. One attempt per OFFER: a dedicated server and
 * advert, a challenge per connection, one consumed ticket, one BIND on the proved connection.
 */
internal class RenewalArmed(private val clockMs: () -> Long) {
    enum class Phase { IDLE, ARMING, ARMED, BOUND, DISARMED, FAILED }

    enum class Tick { NONE, REFUSE_REGISTRATION, STOP_ADVERT, DISARM, END_CALL }

    sealed class Admission {
        data class Challenge(val connection: Long) : Admission()
        object Drop : Admission()
    }

    enum class ProofResult { CONFIRM, DROP }

    enum class BindResult { ACCEPT, IGNORE }

    @Volatile var phase = Phase.IDLE
        private set
    var ticket = 0L
        private set
    var receivedAtMs = 0L
        private set
    private var advertising = false
    private var connections = 0
    private val challengedAtMs = HashMap<Long, Long>()
    private var provedConnection: Long? = null
    private var ticketUsed = false

    /** OFFER arrived; [refusal] is the scope answer ([CallRenewalScope.refusal]). True: arm (register and advertise). */
    @Synchronized
    fun onOffer(ticket: Long, refusal: CallRenewalWire.Reason?): Boolean {
        if (refusal != null || phase == Phase.ARMING || phase == Phase.ARMED) return false
        this.ticket = ticket
        receivedAtMs = clockMs()
        phase = Phase.ARMING
        advertising = false
        connections = 0
        challengedAtMs.clear()
        provedConnection = null
        ticketUsed = false
        return true
    }

    /** The server and the advert are up. False: too late (refused already) or not arming; close what was opened. */
    @Synchronized
    fun onServerReady(): Boolean {
        if (phase != Phase.ARMING || clockMs() - receivedAtMs >= REGISTRATION_MS) return false
        phase = Phase.ARMED
        advertising = true
        return true
    }

    @Synchronized
    fun onServerFailed() {
        if (phase == Phase.ARMING) phase = Phase.DISARMED
    }

    /** Liveness hang-up and old-link failures are part of the renewal while this holds. */
    fun suspendsLiveness(): Boolean = phase == Phase.ARMING || phase == Phase.ARMED || phase == Phase.BOUND

    @Synchronized
    fun onConnection(connection: Long): Admission {
        if (phase != Phase.ARMED || provedConnection != null || connections >= MAX_CONNECTIONS) return Admission.Drop
        connections++
        challengedAtMs[connection] = clockMs()
        return Admission.Challenge(connection)
    }

    /** True when [connection] has had its challenge for [PROOF_MS] without a valid proof: drop it (squatter). */
    @Synchronized
    fun proofOverdue(connection: Long): Boolean {
        val at = challengedAtMs[connection] ?: return false
        return provedConnection != connection && clockMs() - at >= PROOF_MS
    }

    @Synchronized
    fun onProof(connection: Long, ticket: Long, valid: Boolean): ProofResult {
        val at = challengedAtMs[connection] ?: return ProofResult.DROP
        if (!valid || ticket != this.ticket || ticketUsed || phase != Phase.ARMED || clockMs() - at >= PROOF_MS) {
            challengedAtMs.remove(connection)
            return ProofResult.DROP
        }
        ticketUsed = true
        provedConnection = connection
        return ProofResult.CONFIRM
    }

    @Synchronized
    fun onConnectionClosed(connection: Long) {
        challengedAtMs.remove(connection)
    }

    /** BIND only as the first record of the proved connection, with the current ticket, once. */
    @Synchronized
    fun onBind(connection: Long?, ticket: Long, firstRecord: Boolean): BindResult {
        if (phase != Phase.ARMED || connection == null || connection != provedConnection || !firstRecord || ticket != this.ticket) {
            return BindResult.IGNORE
        }
        phase = Phase.BOUND
        advertising = false
        return BindResult.ACCEPT
    }

    /** The offerer abandoned (REFUSED abandoned): stop advertising, resume liveness. */
    @Synchronized
    fun onAbandoned(ticket: Long): Boolean {
        if (ticket != this.ticket || !suspendsLiveness()) return false
        phase = Phase.DISARMED
        advertising = false
        return true
    }

    /** [oldLinkAlive]: the old link still carries records; then the bound disarms instead of ending the call. */
    @Synchronized
    fun tick(oldLinkAlive: Boolean): Tick {
        val elapsed = clockMs() - receivedAtMs
        return when {
            phase == Phase.ARMING && elapsed >= REGISTRATION_MS -> {
                phase = Phase.DISARMED
                Tick.REFUSE_REGISTRATION
            }
            phase == Phase.ARMED && elapsed >= HARD_BOUND_MS -> if (oldLinkAlive) {
                phase = Phase.DISARMED
                advertising = false
                Tick.DISARM
            } else {
                phase = Phase.FAILED
                advertising = false
                Tick.END_CALL
            }
            phase == Phase.ARMED && advertising && elapsed >= ADVERT_MS -> {
                advertising = false
                Tick.STOP_ADVERT
            }
            else -> Tick.NONE
        }
    }

    @Synchronized
    fun reset() {
        if (phase == Phase.BOUND || phase == Phase.DISARMED) phase = Phase.IDLE
    }

    companion object {
        const val REGISTRATION_MS = 3_000L
        const val PROOF_MS = 1_000L
        const val ADVERT_MS = 7_000L
        const val HARD_BOUND_MS = 12_000L
        const val MAX_CONNECTIONS = 8
    }
}

/** Per-call attempt limits shared by both roles ([CallRenewalScope]). */
internal class RenewalAttempts {
    var count = 0
        private set
    private var lastAtMs: Long? = null

    fun sinceLast(nowMs: Long): Long? = lastAtMs?.let { nowMs - it }

    fun record(nowMs: Long) {
        count++
        lastAtMs = nowMs
    }
}
