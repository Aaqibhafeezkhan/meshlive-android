package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the byte-exact vectors of `tools/bmh3_reference.py` in both roles (PROTOCOL "Vectors, tests and logs"). */
class Bmh3VectorTest {
    private fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    private fun privateKey(scalarHex: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(scalarHex.removePrefix("0x"), 16), curve))

    /** The vector's ephemeral key and nonce: the public bytes come from the vector's own message. */
    private class VectorEntropy(
        private val ephemeral: Bmh3Ephemeral,
        private val nonce: ByteArray,
        private val randomTag: ByteArray,
    ) : Bmh3Entropy {
        var drawn = 0
        override fun ephemeral(): Bmh3Ephemeral = ephemeral.also { drawn++ }
        override fun nonce(): ByteArray = nonce.copyOf()
        override fun randomTag(): ByteArray = randomTag.copyOf()
    }

    /**
     * Signs nothing: it checks that the engine passes exactly the vector's signing input and returns the vector's
     * RFC 6979 signature as DER (a phone signs with a random k; the verifier accepts both).
     */
    private class VectorIdentity(
        spkiHex: String,
        private val expectedContext: ByteArray,
        private val rawSignature: ByteArray,
    ) : SigningIdentity {
        override val publicKeyEncoded: ByteArray = ByteArray(spkiHex.length / 2) { spkiHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        override val nodeId: NodeId = NodeId.fromPublicKey(publicKeyEncoded)
        var signed = 0

        override fun sign(message: ByteArray): ByteArray {
            assertArrayEquals("signing input", expectedContext, message)
            signed++
            return Bmh3Crypto.rawToDer(rawSignature)
        }
    }

    private fun purposeOf(name: String) = if (name == "Link") Bmh3Purpose.LINK else Bmh3Purpose.END_TO_END

    @Test
    fun everyVectorReplaysInBothRolesByteForByte() {
        assertEquals(5, Bmh3Vectors.all.size)
        for (vector in Bmh3Vectors.all) {
            val purpose = purposeOf(vector.purpose)
            val inputs = vector.inputs
            val trace = vector.trace
            val m = vector.messages
            val m1 = bytes(m.getValue("m1"))
            val m2 = bytes(m.getValue("m2"))

            val initiatorEntropy = VectorEntropy(
                Bmh3Ephemeral(m1.copyOfRange(9, 74), privateKey(inputs.getValue("ephemeral_I_scalar"))),
                bytes(inputs.getValue("nonce_I")),
                bytes(inputs.getValue("random_tag_I")),
            )
            val responderEntropy = VectorEntropy(
                Bmh3Ephemeral(m2.copyOfRange(8, 73), privateKey(inputs.getValue("ephemeral_R_scalar"))),
                bytes(inputs.getValue("nonce_R")),
                ByteArray(16),
            )
            val initiatorIdentity = VectorIdentity(
                inputs.getValue("identity_I_spki"),
                Bmh3Crypto.signatureContext(purpose, "Initiator", bytes(trace.getValue("i_th_sig_I"))),
                bytes(trace.getValue("i_sig_I")),
            )
            val responderIdentity = VectorIdentity(
                inputs.getValue("identity_R_spki"),
                Bmh3Crypto.signatureContext(purpose, "Responder", bytes(trace.getValue("r_th_sig_R"))),
                bytes(trace.getValue("r_sig_R")),
            )
            val initiatorExtensions = Bmh3Extensions.parse(bytes(inputs.getValue("extensions_I")))
            val responderExtensions = Bmh3Extensions.parse(bytes(inputs.getValue("extensions_R")))
            assertArrayEquals(bytes(inputs.getValue("extensions_I")), initiatorExtensions.encodeArea())
            assertArrayEquals(bytes(inputs.getValue("extensions_R")), responderExtensions.encodeArea())

            val tagKeyHex = inputs.getValue("tag_key_I")
            // A pseudonymous vector signs its blocks with the throwaway keys: they are the stranger presentation of a
            // phone whose real identity (any key) never signs (rule P, no contact tag).
            val capsI = NodeCapabilitiesCodec.decode(bytes(inputs.getValue("caps_I")))
            val capsR = NodeCapabilitiesCodec.decode(bytes(inputs.getValue("caps_R")))
            val untouchedReal = KeyPairSigningIdentity(generateEcKeyPair())
            val initiatorConfig = if (initiatorExtensions.pseudonym) {
                Bmh3Config(purpose, untouchedReal, capsI, initiatorEntropy, initiatorExtensions.buildTag, stranger = Bmh3Presentation(initiatorIdentity, capsI, true))
            } else {
                Bmh3Config(purpose, initiatorIdentity, capsI, initiatorEntropy, initiatorExtensions.buildTag)
            }
            val responderConfig = if (responderExtensions.pseudonym) {
                Bmh3Config(purpose, untouchedReal, capsR, responderEntropy, responderExtensions.buildTag, stranger = Bmh3Presentation(responderIdentity, capsR, true))
            } else {
                Bmh3Config(purpose, responderIdentity, capsR, responderEntropy, responderExtensions.buildTag)
            }
            val initiator = Bmh3Initiator(
                initiatorConfig,
                mayRevealFirst = inputs.getValue("may_reveal_first_I") == "true",
                contactTag = if (tagKeyHex.isEmpty()) null else Bmh3ContactTag(bytes(tagKeyHex), bytes(inputs.getValue("tag_binding_I"))),
                intent = initiatorExtensions.intent,
            )
            val tagChecksR = inputs.getValue("tag_checks_R").split(',').filter { it.isNotEmpty() }.map {
                val (key, binding) = it.split(':')
                Bmh3ContactTag(bytes(key), bytes(binding))
            }
            val responder = Bmh3Responder(
                responderConfig,
                mode = Bmh3ResponderMode.valueOf(inputs.getValue("mode_R")),
                tagChecks = tagChecksR,
                opening = responderExtensions.opening,
            )

            assertEquals(vector.name, m.getValue("m1"), hex(initiator.message1()))
            assertEquals(vector.name, m.getValue("m2"), hex(responder.onMessage1(m1)))
            val orderIsRFirst = vector.order == "R_FIRST"
            assertEquals(vector.name, m.getValue("m3"), hex(initiator.onMessage2(m2)))
            assertEquals(if (orderIsRFirst) Bmh3Order.R_FIRST else Bmh3Order.I_FIRST, initiator.chosenOrder)
            val m4 = responder.onMessage3(bytes(m.getValue("m3")))
            if (orderIsRFirst) {
                assertEquals(null, m4)
            } else {
                assertEquals(vector.name, m.getValue("m4"), hex(requireNotNull(m4)))
                initiator.onMessage4(m4)
            }
            assertTrue(initiator.isComplete && responder.isComplete)
            assertEquals(1, initiatorEntropy.drawn)
            assertEquals(1, responderEntropy.drawn)

            val ini = initiator.result()
            val res = responder.result()
            assertEquals(vector.name, trace.getValue("i_app_i2r"), hex(ini.sendKey))
            assertEquals(vector.name, trace.getValue("i_app_r2i"), hex(ini.receiveKey))
            assertEquals(vector.name, trace.getValue("i_exporter"), hex(ini.exporter))
            assertEquals(hex(ini.sendKey), hex(res.receiveKey))
            assertEquals(hex(ini.receiveKey), hex(res.sendKey))
            assertEquals(hex(ini.exporter), hex(res.exporter))
            assertEquals(inputs.getValue("node_id_R"), ini.peer.nodeId.toString())
            assertEquals(inputs.getValue("node_id_I"), res.peer.nodeId.toString())
            assertEquals(1, initiatorIdentity.signed)
            assertEquals(1, responderIdentity.signed)
            assertEquals(vector.name, tagChecksR.isNotEmpty() && tagKeyHex.isNotEmpty(), responder.contactTagMatched)
            assertEquals(vector.name, responderExtensions.buildTag, ini.peer.buildTag)
            assertEquals(vector.name, initiatorExtensions.buildTag, res.peer.buildTag)

            assertEquals(vector.name, initiatorExtensions.pseudonym, res.peer.pseudonymous)
            assertEquals(vector.name, responderExtensions.pseudonym, ini.peer.pseudonymous)
            assertEquals(vector.name, initiatorExtensions.pseudonym, ini.ownPseudonymous)
            if (vector.reveal.isNotEmpty()) replayReveals(vector, ini, res)

            // The first BMS1 record in each direction (sequence 0 under its own direction key).
            val record = ByteArrayOutputStream()
            SecureRecordWriter(record, ini.sendKey).write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
            assertEquals(vector.name, vector.bms1FirstRecordI2r, hex(record.toByteArray()))
            val back = ByteArrayOutputStream()
            SecureRecordWriter(back, ini.receiveKey).write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
            assertEquals(vector.name, vector.bms1FirstRecordR2i, hex(back.toByteArray()))
        }
    }

    /** Both reveal records of a pseudonymous vector: signing input, bytes and verification (PROTOCOL "REVEAL"). */
    private fun replayReveals(vector: Bmh3Vectors.Vector, ini: Bmh3Result, res: Bmh3Result) {
        val reveal = vector.reveal
        assertEquals(vector.name, reveal.getValue("exporter"), hex(ini.exporter))
        val entry = DiscoveryOpeningCodec.decode(DiscoveryOpeningCodec.MAGIC + byteArrayOf(1, 1) + bytes(Bmh3Vectors.OPENING_ENTRY)).single()
        val calleeIdentity = VectorIdentity(
            reveal.getValue("real_R_spki"),
            bytes(reveal.getValue("callee_input")),
            bytes(reveal.getValue("callee_sig")),
        )
        val callerIdentity = VectorIdentity(
            reveal.getValue("real_I_spki"),
            bytes(reveal.getValue("caller_input")),
            bytes(reveal.getValue("caller_sig")),
        )
        val callee = Bmh3Reveal.build(Bmh3Reveal.ROLE_CALLEE, calleeIdentity, ini.exporter, res.ownSpki, ini.ownSpki, listOf(entry), ByteArray(0))
        assertEquals(vector.name, reveal.getValue("callee"), hex(DiscoveryExchange.encode(callee)))
        assertEquals(reveal.getValue("callee_record_hash"), hex(Bmh3Crypto.sha256(DiscoveryExchange.encode(callee))))
        val caller = Bmh3Reveal.build(
            Bmh3Reveal.ROLE_CALLER, callerIdentity, ini.exporter, ini.ownSpki, res.ownSpki, emptyList(),
            bytes(reveal.getValue("callee_record_hash")),
        )
        assertEquals(vector.name, reveal.getValue("caller"), hex(DiscoveryExchange.encode(caller)))
        // Decode the bytes the reference produced and verify them as each receiver would.
        val decodedCallee = DiscoveryExchange.decode(bytes(reveal.getValue("callee"))) as DiscoveryExchange.IdentityReveal
        val verifiedCallee = Bmh3Reveal.verify(
            decodedCallee, Bmh3Reveal.ROLE_CALLEE, ini.exporter, res.ownSpki, ini.ownSpki, listOf(ini.ownSpki), ByteArray(0),
        )
        assertEquals(reveal.getValue("node_id_real_R"), verifiedCallee.nodeId.toString())
        val decodedCaller = DiscoveryExchange.decode(bytes(reveal.getValue("caller"))) as DiscoveryExchange.IdentityReveal
        val verifiedCaller = Bmh3Reveal.verify(
            decodedCaller, Bmh3Reveal.ROLE_CALLER, ini.exporter, ini.ownSpki, res.ownSpki, listOf(res.ownSpki),
            bytes(reveal.getValue("callee_record_hash")),
        )
        assertEquals(reveal.getValue("node_id_real_I"), verifiedCaller.nodeId.toString())
        assertEquals(1, calleeIdentity.signed)
        assertEquals(1, callerIdentity.signed)
    }

    @Test
    fun theContactTagKeyAndTheDomainsMatchTheReference() {
        assertEquals(Bmh3Vectors.K_TAG, hex(Bmh3Crypto.contactTagKey(bytes(Bmh3Vectors.LEASE_KEY))))
        assertEquals(Bmh3Vectors.BINDING, hex(Bmh3Crypto.advertBinding(bytes(Bmh3Vectors.OPENING_ENTRY).copyOf(6), 0, null)))
        assertEquals(
            "426c7565746f6f7468566964656f4d6573682f424d48332f4c696e6b",
            hex(Bmh3Purpose.LINK.domain),
        )
        assertEquals("BluetoothVideoMesh/BMH3/EndToEnd", String(Bmh3Purpose.END_TO_END.domain, Charsets.US_ASCII))
    }

    @Test
    fun theWireSizesAreTheSpecifiedOnes() {
        assertEquals(122, Bmh3Wire.MSG1_BYTES)
        assertEquals(105, Bmh3Wire.MSG2_CLEAR_BYTES)
        assertEquals(310, Bmh3Wire.BLOCK_BYTES)
        assertEquals(431, Bmh3Wire.MSG2_R_FIRST_BYTES)
        assertEquals(334, Bmh3Wire.MSG3_BYTES)
        val link = Bmh3Vectors.all.first()
        assertEquals(122, bytes(link.messages.getValue("m1")).size)
        assertEquals(431, bytes(link.messages.getValue("m2")).size)
        assertEquals(334, bytes(link.messages.getValue("m3")).size)
    }
}
