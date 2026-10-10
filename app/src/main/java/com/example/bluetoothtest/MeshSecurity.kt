package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NodeId private constructor(private val value: ByteArray) {
    fun toByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is NodeId && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = value.joinToString("") { "%02x".format(it) }

    companion object {
        const val BYTE_COUNT = 16

        fun fromBytes(bytes: ByteArray): NodeId {
            require(bytes.size == BYTE_COUNT) { "A node ID must be $BYTE_COUNT bytes" }
            return NodeId(bytes.copyOf())
        }

        fun fromPublicKey(publicKey: ByteArray): NodeId = fromBytes(
            MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(BYTE_COUNT),
        )
    }
}

interface SigningIdentity {
    val nodeId: NodeId
    val publicKeyEncoded: ByteArray
    fun sign(message: ByteArray): ByteArray
}

class KeyPairSigningIdentity(private val keyPair: KeyPair) : SigningIdentity {
    override val publicKeyEncoded: ByteArray = keyPair.public.encoded.copyOf()
    override val nodeId: NodeId = NodeId.fromPublicKey(publicKeyEncoded)

    override fun sign(message: ByteArray): ByteArray = signEcdsa(keyPair.private, message)
}

enum class HandshakeRole(val wireValue: Int) {
    INITIATOR(1),
    RESPONDER(2),
}

data class SignedHandshakeHello(
    val role: HandshakeRole,
    val nodeId: NodeId,
    val identityPublicKey: ByteArray,
    val ephemeralPublicKey: ByteArray,
    val nonce: ByteArray,
    val capabilities: NodeCapabilities,
    val signature: ByteArray,
)

class PendingHandshake internal constructor(
    val hello: SignedHandshakeHello,
    internal val ephemeralKeyPair: KeyPair,
    internal val purpose: HandshakePurpose,
)

enum class HandshakePurpose(
    internal val signatureContext: ByteArray,
    internal val keyContext: ByteArray,
) {
    LINK(
        "BluetoothVideoMesh/LinkHandshake/1".toByteArray(Charsets.US_ASCII),
        "BluetoothVideoMesh/LinkSession/1".toByteArray(Charsets.US_ASCII),
    ),
    END_TO_END(
        "BluetoothVideoMesh/EndToEndHandshake/1".toByteArray(Charsets.US_ASCII),
        "BluetoothVideoMesh/EndToEndSession/1".toByteArray(Charsets.US_ASCII),
    ),
}

data class MeshSessionKeys(
    val peerNodeId: NodeId,
    val sendKey: ByteArray,
    val receiveKey: ByteArray,
) {
    init {
        require(sendKey.size == MeshAead.KEY_BYTES)
        require(receiveKey.size == MeshAead.KEY_BYTES)
    }
}

object MeshHandshake {
    fun start(
        identity: SigningIdentity,
        role: HandshakeRole,
        capabilities: NodeCapabilities,
        secureRandom: SecureRandom = SecureRandom(),
        purpose: HandshakePurpose = HandshakePurpose.LINK,
    ): PendingHandshake {
        val ephemeral = generateEcKeyPair(secureRandom)
        val unsigned = SignedHandshakeHello(
            role = role,
            nodeId = identity.nodeId,
            identityPublicKey = identity.publicKeyEncoded,
            ephemeralPublicKey = ephemeral.public.encoded,
            nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes),
            capabilities = capabilities,
            signature = byteArrayOf(),
        )
        val signature = identity.sign(purpose.signatureContext + HandshakeHelloCodec.encodeUnsigned(unsigned))
        return PendingHandshake(unsigned.copy(signature = signature), ephemeral, purpose)
    }

    fun finish(local: PendingHandshake, remote: SignedHandshakeHello): MeshSessionKeys {
        require(local.hello.role != remote.role) { "Handshake peers must have opposite roles" }
        verify(remote, local.purpose)
        val remoteEphemeral = decodeEcPublicKey(remote.ephemeralPublicKey)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(local.ephemeralKeyPair.private)
        agreement.doPhase(remoteEphemeral, true)
        val sharedSecret = agreement.generateSecret()

        val initiator = if (local.hello.role == HandshakeRole.INITIATOR) local.hello else remote
        val responder = if (local.hello.role == HandshakeRole.RESPONDER) local.hello else remote
        val transcript = HandshakeHelloCodec.encode(initiator) + HandshakeHelloCodec.encode(responder)
        val salt = sha256(initiator.nonce + responder.nonce)
        val keyMaterial = hkdfSha256(
            sharedSecret,
            salt,
            local.purpose.keyContext + sha256(transcript),
            MeshAead.KEY_BYTES * 2,
        )
        sharedSecret.fill(0)
        val initiatorToResponder = keyMaterial.copyOfRange(0, MeshAead.KEY_BYTES)
        val responderToInitiator = keyMaterial.copyOfRange(MeshAead.KEY_BYTES, keyMaterial.size)
        keyMaterial.fill(0)
        return if (local.hello.role == HandshakeRole.INITIATOR) {
            MeshSessionKeys(remote.nodeId, initiatorToResponder, responderToInitiator)
        } else {
            MeshSessionKeys(remote.nodeId, responderToInitiator, initiatorToResponder)
        }
    }

    fun verify(
        hello: SignedHandshakeHello,
        purpose: HandshakePurpose = HandshakePurpose.LINK,
    ) {
        require(hello.nonce.size == NONCE_BYTES) { "Invalid handshake nonce" }
        require(NodeId.fromPublicKey(hello.identityPublicKey) == hello.nodeId) {
            "Node ID does not match its identity key"
        }
        val publicKey = decodeEcPublicKey(hello.identityPublicKey)
        val unsigned = hello.copy(signature = byteArrayOf())
        require(verifyEcdsa(
            publicKey,
            purpose.signatureContext + HandshakeHelloCodec.encodeUnsigned(unsigned),
            hello.signature,
        )) { "Handshake signature verification failed" }
    }

    const val NONCE_BYTES = 32
}

object HandshakeHelloCodec {
    private val magic = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'H'.code.toByte(), '2'.code.toByte())
    private const val MAX_KEY_BYTES = 512
    private const val MAX_SIGNATURE_BYTES = 256

    fun encode(value: SignedHandshakeHello): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(encodeUnsigned(value))
            require(value.signature.size in 1..MAX_SIGNATURE_BYTES) { "Invalid handshake signature length" }
            output.writeShort(value.signature.size)
            output.write(value.signature)
        }
    }.toByteArray()

    internal fun encodeUnsigned(value: SignedHandshakeHello): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            require(value.identityPublicKey.size in 1..MAX_KEY_BYTES)
            require(value.ephemeralPublicKey.size in 1..MAX_KEY_BYTES)
            require(value.nonce.size == MeshHandshake.NONCE_BYTES)
            output.write(magic)
            output.writeByte(value.role.wireValue)
            output.write(value.nodeId.toByteArray())
            output.writeShort(value.identityPublicKey.size)
            output.write(value.identityPublicKey)
            output.writeShort(value.ephemeralPublicKey.size)
            output.write(value.ephemeralPublicKey)
            output.write(value.nonce)
            output.write(NodeCapabilitiesCodec.encode(value.capabilities))
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): SignedHandshakeHello {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid handshake message" }
        val role = when (input.readUnsignedByte()) {
            HandshakeRole.INITIATOR.wireValue -> HandshakeRole.INITIATOR
            HandshakeRole.RESPONDER.wireValue -> HandshakeRole.RESPONDER
            else -> error("Invalid handshake role")
        }
        val nodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT).also(input::readFully))
        val identityKey = input.readBoundedBytes(MAX_KEY_BYTES, "identity key")
        val ephemeralKey = input.readBoundedBytes(MAX_KEY_BYTES, "ephemeral key")
        val nonce = ByteArray(MeshHandshake.NONCE_BYTES).also(input::readFully)
        val capabilities = NodeCapabilitiesCodec.decode(
            ByteArray(NodeCapabilitiesCodec.ENCODED_BYTES).also(input::readFully),
        )
        val signature = input.readBoundedBytes(MAX_SIGNATURE_BYTES, "signature")
        require(input.available() == 0) { "Trailing handshake data" }
        return SignedHandshakeHello(
            role,
            nodeId,
            identityKey,
            ephemeralKey,
            nonce,
            capabilities,
            signature,
        )
    }

    private fun DataInputStream.readBoundedBytes(maxBytes: Int, label: String): ByteArray {
        val size = readUnsignedShort()
        require(size in 1..maxBytes) { "Invalid $label length" }
        return ByteArray(size).also(::readFully)
    }
}

object MeshAead {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8

    fun encrypt(key: ByteArray, sequence: Long, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        crypt(Cipher.ENCRYPT_MODE, key, sequence, plaintext, associatedData)

    fun decrypt(key: ByteArray, sequence: Long, ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        crypt(Cipher.DECRYPT_MODE, key, sequence, ciphertext, associatedData)

    private fun crypt(
        mode: Int,
        key: ByteArray,
        sequence: Long,
        input: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 requires a $KEY_BYTES-byte key" }
        require(sequence >= 0) { "Sequence numbers must not be negative" }
        val nonce = ByteArray(NONCE_BYTES)
        for (index in 0 until Long.SIZE_BYTES) {
            nonce[NONCE_BYTES - 1 - index] = (sequence ushr (index * 8)).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(input)
    }
}

internal fun generateEcKeyPair(secureRandom: SecureRandom = SecureRandom()): KeyPair =
    KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        generateKeyPair()
    }

internal fun signEcdsa(privateKey: PrivateKey, message: ByteArray): ByteArray =
    Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(message)
        sign()
    }

internal fun verifyEcdsa(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
    Signature.getInstance("SHA256withECDSA").run {
        initVerify(publicKey)
        update(message)
        verify(signature)
    }

internal fun decodeEcPublicKey(encoded: ByteArray): PublicKey = KeyFactory
    .getInstance("EC")
    .generatePublic(X509EncodedKeySpec(encoded))

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun hkdfSha256(
    inputKeyMaterial: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    outputBytes: Int,
): ByteArray {
    require(outputBytes in 1..(255 * 32))
    val extract = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(salt, "HmacSHA256"))
        doFinal(inputKeyMaterial)
    }
    val output = ByteArrayOutputStream(outputBytes)
    var previous = byteArrayOf()
    var counter = 1
    while (output.size() < outputBytes) {
        previous = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(extract, "HmacSHA256"))
            update(previous)
            update(info)
            update(counter.toByte())
            doFinal()
        }
        output.write(previous)
        counter++
    }
    extract.fill(0)
    previous.fill(0)
    return output.toByteArray().copyOf(outputBytes)
}
