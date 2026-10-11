package com.example.bluetoothtest

import java.io.Closeable
import java.security.GeneralSecurityException

/**
 * The identity reveal of a pseudonymous session (PROTOCOL "Phase 2: pseudonymous stranger identities"). Pure Kotlin:
 * message construction, signature input, verification and the ordering state machine, so every rule is JVM-tested
 * against `tools/bmh3_reference.py`. The call layer decides *when* (after the user accepted) and runs the policy
 * checks that need Android state (verified-contact gate, discovery binding of the selected offer).
 */
object Bmh3Reveal {
    const val ROLE_CALLER = 1
    const val ROLE_CALLEE = 2
    val DOMAIN: ByteArray = "BluetoothVideoMesh/BMH3/Reveal/1".toByteArray(Charsets.US_ASCII)

    /** A reveal that passed the signature check; the caller still runs `PeerIdentityGate.admit` and the binding. */
    class Verified(val spki: ByteArray, val nodeId: NodeId, val entries: List<OpeningEntry>)

    private fun roleName(role: Int) = if (role == ROLE_CALLER) "Caller" else "Callee"

    private val KEY_INFO: ByteArray = "BluetoothVideoMesh/BMH3/Reveal/1 key".toByteArray(Charsets.US_ASCII)

    /**
     * What the real key signs: `D "/" Role " signature" 0x00 TH_R`, with `TH_R = SHA-256(D | 0x00 | role | K | SHA-256(own
     * pseudonym SPKI) | SHA-256(peer pseudonym SPKI) | SPKI | prior | opening body)`. `K = HKDF-Expand(EXP, "... key")` derives
     * from the end-to-end exporter (a secret both ends reach from the whole transcript, both pseudonym keys included), so
     * the exporter itself is never an input. `prior` is empty in the callee's reveal and `SHA-256(callee reveal record)` in
     * the caller's, which orders the exchange.
     */
    fun signingInput(
        role: Int,
        exporter: ByteArray,
        ownPseudonymSpki: ByteArray,
        peerPseudonymSpki: ByteArray,
        realSpki: ByteArray,
        entries: List<OpeningEntry>,
        prior: ByteArray,
    ): ByteArray {
        require(exporter.size == 32 && realSpki.size == Bmh3Wire.SPKI_BYTES && (prior.isEmpty() || prior.size == 32))
        val body = DiscoveryOpeningCodec.encode(entries).let { it.copyOfRange(DiscoveryOpeningCodec.MAGIC.size + 1, it.size) }
        val key = Bmh3Crypto.expand(exporter, KEY_INFO)
        val th = Bmh3Crypto.sha256(
            DOMAIN + byteArrayOf(0, role.toByte()) + key + Bmh3Crypto.sha256(ownPseudonymSpki) +
                Bmh3Crypto.sha256(peerPseudonymSpki) + realSpki + prior + body,
        )
        key.fill(0)
        return DOMAIN + "/${roleName(role)} signature".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + th
    }

    /** Builds this phone's reveal: signed with the real (Keystore) identity. [entries] are the callee's pinned opening entries. */
    fun build(
        role: Int,
        real: SigningIdentity,
        exporter: ByteArray,
        ownPseudonymSpki: ByteArray,
        peerPseudonymSpki: ByteArray,
        entries: List<OpeningEntry>,
        prior: ByteArray,
    ): DiscoveryExchange.IdentityReveal {
        if (!Bmh3Crypto.isCanonicalSpki(real.publicKeyEncoded)) throw Bmh3Failure("identity key is not the canonical P-256 SPKI")
        val raw = Bmh3Crypto.derToRaw(
            real.sign(signingInput(role, exporter, ownPseudonymSpki, peerPseudonymSpki, real.publicKeyEncoded, entries, prior)),
        )
        return DiscoveryExchange.IdentityReveal(role, real.publicKeyEncoded, entries, raw)
    }

    /**
     * Verifies [reveal] as the peer's: role, canonical SPKI, signature under the peer's key over this session's [exporter]
     * and the peer's own pseudonym ([peerPseudonymSpki]), and that the real key is not the pseudonym or this phone's key.
     */
    fun verify(
        reveal: DiscoveryExchange.IdentityReveal,
        expectedRole: Int,
        exporter: ByteArray,
        peerPseudonymSpki: ByteArray,
        ownPseudonymSpki: ByteArray,
        ownSpkis: List<ByteArray>,
        prior: ByteArray,
    ): Verified {
        if (reveal.role != expectedRole) throw Bmh3Failure("reveal role")
        if (!Bmh3Crypto.isCanonicalSpki(reveal.spki)) throw Bmh3Failure("reveal identity key invalid")
        if (reveal.spki.contentEquals(peerPseudonymSpki)) throw Bmh3Failure("reveal names the pseudonym")
        if (ownSpkis.any { it.contentEquals(reveal.spki) }) throw Bmh3Failure("reveal names this phone")
        val verified = try {
            verifyEcdsa(
                decodeEcPublicKey(reveal.spki),
                signingInput(expectedRole, exporter, peerPseudonymSpki, ownPseudonymSpki, reveal.spki, reveal.entries, prior),
                Bmh3Crypto.rawToDer(reveal.signature),
            )
        } catch (error: GeneralSecurityException) {
            if (error is Bmh3Failure) throw error
            false
        }
        if (!verified) throw Bmh3Failure("reveal signature")
        return Verified(reveal.spki.copyOf(), NodeId.fromPublicKey(reveal.spki), reveal.entries)
    }
}

/**
 * The reveal exchange of one end-to-end session. A phone reveals only to a peer that declared a pseudonym itself (an
 * older build cannot parse a reveal), the callee only after its user accepted ([sendOwn]), the caller only after it
 * verified the callee's reveal and its binding ([callerMayReveal]). At most one reveal per direction.
 */
class Bmh3RevealSession(
    val localRole: Int,
    exporter: ByteArray,
    ownPseudonymSpki: ByteArray,
    peerPseudonymSpki: ByteArray,
    val ownPseudonymous: Boolean,
    val peerPseudonymous: Boolean,
    ownSpkis: List<ByteArray> = listOf(ownPseudonymSpki),
) : Closeable {
    private val exporter = exporter.copyOf()
    private val ownPseudonymSpki = ownPseudonymSpki.copyOf()
    private val peerPseudonymSpki = peerPseudonymSpki.copyOf()
    private val ownSpkis = ownSpkis.map(ByteArray::copyOf)
    private var ownSent = false
    private var peerVerified: Bmh3Reveal.Verified? = null
    private var failed = false

    /** SHA-256 of the callee's reveal record (sent by the callee, verified by the caller): the caller's reveal binds it. */
    private var calleeRecordHash: ByteArray? = null

    init {
        require(localRole == Bmh3Reveal.ROLE_CALLER || localRole == Bmh3Reveal.ROLE_CALLEE)
    }

    private val peerRole get() = if (localRole == Bmh3Reveal.ROLE_CALLER) Bmh3Reveal.ROLE_CALLEE else Bmh3Reveal.ROLE_CALLER

    /** True when the peer declared a pseudonym: a reveal from it is expected and one to it is understood. */
    val active: Boolean get() = peerPseudonymous

    /** The peer's verified real identity once its reveal passed, else null (the session stays on the pseudonym). */
    @get:Synchronized
    val revealed: Bmh3Reveal.Verified? get() = peerVerified

    @get:Synchronized
    val ownRevealSent: Boolean get() = ownSent

    /** The caller may reveal itself only after the callee's reveal was verified (callers also run the binding before calling this). */
    @Synchronized
    fun callerMayReveal(): Boolean =
        localRole == Bmh3Reveal.ROLE_CALLER && ownPseudonymous && peerPseudonymous && peerVerified != null && !ownSent && !failed

    /** True while a reveal from the peer is required before the call may proceed (the callee's, seen by a caller). */
    @Synchronized
    fun peerRevealRequired(): Boolean = localRole == Bmh3Reveal.ROLE_CALLER && peerPseudonymous && peerVerified == null

    /** Builds this phone's reveal; throws when the rules above do not allow it or it was already sent. */
    @Synchronized
    fun sendOwn(real: SigningIdentity, entries: List<OpeningEntry>): ByteArray {
        if (!ownPseudonymous || !peerPseudonymous) throw Bmh3Failure("no reveal to a peer that did not declare a pseudonym")
        if (ownSent) throw Bmh3Failure("reveal already sent")
        if (failed) throw Bmh3Failure("reveal exchange failed")
        if (localRole == Bmh3Reveal.ROLE_CALLER && peerVerified == null) throw Bmh3Failure("caller reveals only after the callee")
        if (localRole == Bmh3Reveal.ROLE_CALLER && entries.isNotEmpty()) throw Bmh3Failure("only the callee opens a rotating ID")
        val prior = if (localRole == Bmh3Reveal.ROLE_CALLER) requireNotNull(calleeRecordHash) else ByteArray(0)
        val record = Bmh3Reveal.build(localRole, real, exporter, ownPseudonymSpki, peerPseudonymSpki, entries, prior)
        val encoded = DiscoveryExchange.encode(record)
        if (localRole == Bmh3Reveal.ROLE_CALLEE) calleeRecordHash = Bmh3Crypto.sha256(encoded)
        ownSent = true
        return encoded
    }

    /**
     * Processes the peer's reveal record. Throws [Bmh3Failure] (the call ends) for a reveal from a peer that did not declare a
     * pseudonym, a second one, a wrong role or signature, or a caller reveal before this callee sent its own.
     */
    @Synchronized
    fun onPeerReveal(record: DiscoveryExchange.IdentityReveal): Bmh3Reveal.Verified {
        try {
            if (!peerPseudonymous) throw Bmh3Failure("reveal from a peer that did not declare a pseudonym")
            if (peerVerified != null) throw Bmh3Failure("second reveal")
            if (localRole == Bmh3Reveal.ROLE_CALLEE && !ownSent) throw Bmh3Failure("caller reveal before the callee's")
            val prior = if (localRole == Bmh3Reveal.ROLE_CALLEE) requireNotNull(calleeRecordHash) else ByteArray(0)
            val verified = Bmh3Reveal.verify(record, peerRole, exporter, peerPseudonymSpki, ownPseudonymSpki, ownSpkis, prior)
            // The caller's own reveal will bind the callee's record exactly as it was sent.
            if (localRole == Bmh3Reveal.ROLE_CALLER) calleeRecordHash = Bmh3Crypto.sha256(DiscoveryExchange.encode(record))
            peerVerified = verified
            return verified
        } catch (error: Bmh3Failure) {
            failed = true
            throw error
        }
    }

    override fun close() {
        exporter.fill(0)
    }
}
