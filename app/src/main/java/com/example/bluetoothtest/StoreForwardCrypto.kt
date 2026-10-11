package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Experimental store-and-forward messages v1 (DECISIONS Design A, PROTOCOL "Store-and-forward messages").
 * Off by default (StoreForwardFeature). Nothing here changes BMH2/BMS1/BME1 or a live session.
 *
 * - Contact card `BCC1`: identity SPKI + compressed P-256 mailbox key, signed by the identity key.
 * - Bundle header `BSB1`: carrier-visible, immutable; bundle ID = SHA-256(header).
 * - Key slot: Noise-X-like seal of the sender's mailbox key/node ID (k1) and the content key (k2).
 *   The sender is authenticated to the recipient only by a static-static ECDH, so the recipient
 *   could have produced the same slot itself: deniable, no transferable signature (QA7).
 * - Content `BSM1`: Padmé-padded, split into chunks, each AES-256-GCM under the content key with
 *   nonce = chunk index and AAD = index || count. The header carries a truncated SHA-256 per
 *   ciphertext chunk, so a carrier verifies every chunk without any key.
 */
object StoreForwardFormat {
    const val CHUNK_CIPHERTEXT_BYTES = 8 * 1024
    const val CHUNK_PLAINTEXT_BYTES = CHUNK_CIPHERTEXT_BYTES - MeshAead.TAG_BYTES
    const val MAX_CHUNKS = 256
    const val MAX_PADDED_BYTES = MAX_CHUNKS * CHUNK_PLAINTEXT_BYTES
    /** Receipts and short texts share this minimum padded size, so carriers cannot tell them apart. */
    const val MIN_PADDED_BYTES = 1024
    const val MAX_HEADER_BYTES = 8 * 1024
    const val CHUNK_HASH_BYTES = 16
    const val TAG_BYTES = 8
    const val COMPRESSED_KEY_BYTES = 33
    const val SLOT_SENDER_PLAINTEXT_BYTES = COMPRESSED_KEY_BYTES + NodeId.BYTE_COUNT
    const val CONTENT_KEY_BYTES = 32
    const val SLOT_BYTES = TAG_BYTES + COMPRESSED_KEY_BYTES +
        SLOT_SENDER_PLAINTEXT_BYTES + MeshAead.TAG_BYTES + CONTENT_KEY_BYTES + MeshAead.TAG_BYTES
    const val MAX_SLOTS = 8
    const val BUNDLE_ID_BYTES = 32
    const val DEFAULT_TTL_HOURS = 72
    const val MAX_TTL_HOURS = 14 * 24
    /** Contact card format version (unchanged by the identity-bound KDF). */
    const val CARD_VERSION = 1
    /**
     * Bundle header version. 2 binds both node IDs into the pair secret, tag and slot keys
     * (second review N2); version-1 bundles are rejected.
     */
    const val BUNDLE_VERSION = 2

    val DOMAIN: ByteArray = "BluetoothVideoMesh/StoreForward/2".toByteArray(Charsets.US_ASCII)
    val PAIR_CONTEXT: ByteArray = "BluetoothVideoMesh/StoreForward/Pair/2".toByteArray(Charsets.US_ASCII)
    val SAFETY_CONTEXT: ByteArray = "BluetoothVideoMesh/StoreForward/SafetyNumber/1".toByteArray(Charsets.US_ASCII)
    val CARD_CONTEXT: ByteArray = "BluetoothVideoMesh/StoreForward/Card/1".toByteArray(Charsets.US_ASCII)

    fun hourOf(epochMs: Long): Int = (Math.floorDiv(epochMs, 3_600_000L)).toInt()
    fun dayOfHour(hour: Int): Int = Math.floorDiv(hour, 24)

    /** Padmé (Nikitin et al.): at most ~12% overhead and only O(log log n) bits of length leak. */
    fun padme(length: Int): Int {
        require(length >= 0)
        if (length < 2) return length
        val e = 31 - Integer.numberOfLeadingZeros(length)
        val s = 32 - Integer.numberOfLeadingZeros(e)
        val lastBits = e - s
        val mask = (1 shl lastBits) - 1
        return (length + mask) and mask.inv()
    }

    /**
     * Padded size of a 4-byte length prefix plus [innerLength]. Near the 256-chunk cap Padmé would
     * round past it, so the top band pads to exactly the cap instead.
     */
    fun paddedLength(innerLength: Int): Int {
        require(innerLength + 4 <= MAX_PADDED_BYTES) { "Message exceeds the 2 MiB bundle limit" }
        return maxOf(MIN_PADDED_BYTES, minOf(MAX_PADDED_BYTES, padme(innerLength + 4)))
    }

    fun chunkCount(paddedLength: Int): Int = (paddedLength + CHUNK_PLAINTEXT_BYTES - 1) / CHUNK_PLAINTEXT_BYTES

    fun chunkCiphertextBytes(paddedLength: Int, index: Int): Int {
        val count = chunkCount(paddedLength)
        require(index in 0 until count) { "Chunk index out of range" }
        val plain = if (index < count - 1) CHUNK_PLAINTEXT_BYTES else paddedLength - index * CHUNK_PLAINTEXT_BYTES
        return plain + MeshAead.TAG_BYTES
    }

    fun chunkHash(ciphertext: ByteArray): ByteArray = sfSha256(ciphertext).copyOf(CHUNK_HASH_BYTES)
}

/** Compressed SEC1 encoding of P-256 public keys (33 bytes), with on-curve validation. */
object P256Points {
    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun compress(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        val x = unsigned32(point.affineX)
        return byteArrayOf(if (point.affineY.testBit(0)) 3 else 2) + x
    }

    fun decompress(encoded: ByteArray): PublicKey {
        require(encoded.size == StoreForwardFormat.COMPRESSED_KEY_BYTES) { "Invalid compressed key length" }
        val prefix = encoded[0].toInt()
        require(prefix == 2 || prefix == 3) { "Invalid compressed key prefix" }
        val p = (params.curve.field as ECFieldFp).p
        val x = BigInteger(1, encoded.copyOfRange(1, encoded.size))
        require(x < p) { "Invalid compressed key coordinate" }
        val rhs = x.modPow(BigInteger.valueOf(3), p).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        require(y.multiply(y).mod(p) == rhs) { "Compressed key is not on P-256" }
        if (y.testBit(0) != (prefix == 3)) {
            require(y.signum() != 0) { "Invalid compressed key parity" }
            y = p.subtract(y)
        }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    private fun unsigned32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0].toInt() == 0 -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> error("Coordinate too large")
        }
    }
}

/** This identity's static P-256 mailbox key: Android Keystore on API 31+, a wrapped software key below. */
interface MailboxKey {
    val publicKey: PublicKey
    fun agree(peer: PublicKey): ByteArray
    val compressedPublic: ByteArray get() = P256Points.compress(publicKey)
}

enum class MailboxKeyAction { USE_KEYSTORE, USE_SOFTWARE, CREATE, RESET_AND_CREATE }

/**
 * What to do with the mailbox key at startup (N7). A recorded key that can no longer be loaded is
 * replaced, but as RESET_AND_CREATE so the user sees a "message key reset" notice: contacts hold
 * the old card and must receive the new one.
 */
object MailboxKeyPlanner {
    const val KIND_KEYSTORE = "keystore"
    const val KIND_SOFTWARE = "software"

    fun plan(recordedKind: String?, keystoreKeyLoads: Boolean, softwareKeyLoads: Boolean): MailboxKeyAction = when {
        recordedKind == null -> MailboxKeyAction.CREATE
        recordedKind == KIND_KEYSTORE && keystoreKeyLoads -> MailboxKeyAction.USE_KEYSTORE
        recordedKind == KIND_SOFTWARE && softwareKeyLoads -> MailboxKeyAction.USE_SOFTWARE
        else -> MailboxKeyAction.RESET_AND_CREATE
    }
}

/** Visible "message key reset" notice, kept until the user dismisses it (N7). */
object MailboxKeyResetNotice {
    private const val FILE = "mailbox.reset"

    fun record(dir: java.io.File, nowMs: Long) = atomicWrite(java.io.File(dir, FILE), nowMs.toString().toByteArray())

    fun pendingSinceMs(dir: java.io.File): Long? =
        java.io.File(dir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText().trim().toLong() }.getOrDefault(0L) }

    fun dismiss(dir: java.io.File) {
        java.io.File(dir, FILE).delete()
    }
}

class SoftwareMailboxKey(private val keyPair: KeyPair) : MailboxKey {
    override val publicKey: PublicKey = keyPair.public
    override fun agree(peer: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(keyPair.private)
        doPhase(peer, true)
        generateSecret()
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()) = SoftwareMailboxKey(generateEcKeyPair(random))
    }
}

/** `BCC1` contact card. Only identities whose card you hold can be messaged (no directory). */
class ContactCard private constructor(
    val identityPublicKey: ByteArray,
    val mailboxPublic: ByteArray,
    val createdMs: Long,
    val signature: ByteArray,
) {
    val nodeId: NodeId = NodeId.fromPublicKey(identityPublicKey)
    val mailboxKey: PublicKey by lazy { P256Points.decompress(mailboxPublic) }

    fun encode(): ByteArray = unsigned(identityPublicKey, mailboxPublic, createdMs) + ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeShort(signature.size)
            write(signature)
        }
    }.toByteArray()

    fun toText(): String = TEXT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encode())

    /**
     * Short safety number for in-person comparison (second review N6): 20 digits from SHA-256 over
     * the identity key and mailbox key. Any change to either key changes it.
     */
    fun safetyNumber(): String {
        val digest = sfSha256(
            StoreForwardFormat.SAFETY_CONTEXT + byteArrayOf((identityPublicKey.size ushr 8).toByte(), identityPublicKey.size.toByte()) +
                identityPublicKey + mailboxPublic,
        )
        val value = java.math.BigInteger(1, digest.copyOf(9)).mod(java.math.BigInteger.TEN.pow(20))
        return value.toString().padStart(20, '0').chunked(5).joinToString(" ")
    }

    companion object {
        const val TEXT_PREFIX = "BCC1:"
        private val magic = "BCC1".toByteArray(Charsets.US_ASCII)
        private const val MAX_KEY_BYTES = 512
        private const val MAX_SIGNATURE_BYTES = 256

        fun create(identity: SigningIdentity, mailbox: MailboxKey, nowMs: Long): ContactCard {
            val mailboxPublic = mailbox.compressedPublic
            val body = unsigned(identity.publicKeyEncoded, mailboxPublic, nowMs)
            val signature = identity.sign(StoreForwardFormat.CARD_CONTEXT + body)
            return ContactCard(identity.publicKeyEncoded.copyOf(), mailboxPublic, nowMs, signature)
        }

        /** Decodes and verifies the identity signature, node ID binding and mailbox point. */
        fun decode(bytes: ByteArray): ContactCard {
            require(bytes.size <= 2_048) { "Contact card is too large" }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val receivedMagic = ByteArray(magic.size).also(input::readFully)
            require(receivedMagic.contentEquals(magic)) { "Not a contact card" }
            require(input.readUnsignedByte() == StoreForwardFormat.CARD_VERSION) { "Unsupported contact card version" }
            val identitySize = input.readUnsignedShort()
            require(identitySize in 1..MAX_KEY_BYTES) { "Invalid contact card identity key" }
            val identityKey = ByteArray(identitySize).also(input::readFully)
            val mailbox = ByteArray(StoreForwardFormat.COMPRESSED_KEY_BYTES).also(input::readFully)
            val created = input.readLong()
            val signatureSize = input.readUnsignedShort()
            require(signatureSize in 1..MAX_SIGNATURE_BYTES) { "Invalid contact card signature" }
            val signature = ByteArray(signatureSize).also(input::readFully)
            require(input.available() == 0) { "Trailing contact card data" }
            P256Points.decompress(mailbox)
            require(
                verifyEcdsa(
                    decodeEcPublicKey(identityKey),
                    StoreForwardFormat.CARD_CONTEXT + unsigned(identityKey, mailbox, created),
                    signature,
                ),
            ) { "Contact card signature verification failed" }
            return ContactCard(identityKey, mailbox, created, signature)
        }

        fun fromText(text: String): ContactCard {
            val trimmed = text.trim().filterNot(Char::isWhitespace)
            require(trimmed.startsWith(TEXT_PREFIX)) { "Contact card text must start with $TEXT_PREFIX" }
            return decode(Base64.getUrlDecoder().decode(trimmed.removePrefix(TEXT_PREFIX)))
        }

        private fun unsigned(identityKey: ByteArray, mailbox: ByteArray, created: Long): ByteArray =
            ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).apply {
                    write(magic)
                    writeByte(StoreForwardFormat.CARD_VERSION)
                    writeShort(identityKey.size)
                    write(identityKey)
                    write(mailbox)
                    writeLong(created)
                }
            }.toByteArray()
    }
}

/** Pairwise secret and daily recipient tags. Both members of a pair compute the same values. */
object StoreForwardTags {
    /**
     * Both node IDs (sorted) are bound into the info, so the same mailbox key under another identity
     * yields different tags and a different secret (unknown-key-share closed, second review N2).
     */
    fun pairSecret(local: MailboxKey, localNode: NodeId, peerMailbox: PublicKey, peerNode: NodeId): ByteArray {
        val shared = local.agree(peerMailbox)
        val a = local.compressedPublic
        val b = P256Points.compress(peerMailbox)
        val ordered = if (compareUnsigned(a, b) <= 0) a + b else b + a
        val x = localNode.toByteArray()
        val y = peerNode.toByteArray()
        val nodes = if (compareUnsigned(x, y) <= 0) x + y else y + x
        return sfHkdf(shared, sfSha256(ordered), StoreForwardFormat.PAIR_CONTEXT + nodes, 32).also { shared.fill(0) }
    }

    /** Carrier-visible 8-byte tag: pairwise and daily, so tags cannot be linked across pairs or days. */
    fun tag(pairSecret: ByteArray, utcDay: Int): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(pairSecret, "HmacSHA256"))
        update("tag".toByteArray(Charsets.US_ASCII))
        update(ByteArray(4) { (utcDay ushr (24 - 8 * it)).toByte() })
        doFinal()
    }.copyOf(StoreForwardFormat.TAG_BYTES)

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}

enum class MessageKind(val wireValue: Int, val priorityClass: Int, val label: String) {
    TEXT(1, 0, "text"),
    VOICE(2, 1, "voice"),
    PHOTO(3, 2, "photo"),
    VIDEO(4, 3, "video"),
    LOCATION(5, 0, "location"),
    RECEIPT(6, 0, "receipt"),
    CONTACT_CARD(7, 0, "contact card"),
    ;

    companion object {
        fun fromWire(value: Int): MessageKind =
            entries.firstOrNull { it.wireValue == value } ?: throw IllegalArgumentException("Unknown message kind $value")
    }
}

/** `BSM1` plaintext inside the content key. The sender identity is in the slot, not here. */
data class BundleMessage(
    val kind: MessageKind,
    val createdMs: Long,
    val conversationId: ByteArray,
    val replyTo: ByteArray?,
    val mime: String,
    val body: ByteArray,
) {
    init {
        require(conversationId.size == 16) { "Conversation ID must be 16 bytes" }
        require(replyTo == null || replyTo.size == StoreForwardFormat.BUNDLE_ID_BYTES) { "Invalid reply-to" }
        require(mime.toByteArray(Charsets.UTF_8).size <= 255) { "MIME type is too long" }
    }

    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            write(MAGIC)
            writeByte(kind.wireValue)
            writeLong(createdMs)
            write(conversationId)
            write(replyTo ?: ByteArray(StoreForwardFormat.BUNDLE_ID_BYTES))
            val mimeBytes = mime.toByteArray(Charsets.UTF_8)
            writeByte(mimeBytes.size)
            write(mimeBytes)
            writeInt(body.size)
            write(body)
        }
    }.toByteArray()

    companion object {
        private val MAGIC = "BSM1".toByteArray(Charsets.US_ASCII)
        const val OVERHEAD_BYTES = 4 + 1 + 8 + 16 + 32 + 1 + 4
        /** Largest body (with an empty MIME type) that still fits the 256-chunk bundle limit. */
        val MAX_BODY_BYTES = StoreForwardFormat.MAX_PADDED_BYTES - 4 - OVERHEAD_BYTES

        fun decode(bytes: ByteArray): BundleMessage {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(4).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Invalid message magic" }
            val kind = MessageKind.fromWire(input.readUnsignedByte())
            val created = input.readLong()
            val conversation = ByteArray(16).also(input::readFully)
            val reply = ByteArray(StoreForwardFormat.BUNDLE_ID_BYTES).also(input::readFully)
            val mime = String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
            val bodySize = input.readInt()
            require(bodySize in 0..input.available()) { "Invalid message body length" }
            val body = ByteArray(bodySize).also(input::readFully)
            require(input.available() == 0) { "Trailing message data" }
            return BundleMessage(kind, created, conversation, reply.takeUnless { r -> r.all { it.toInt() == 0 } }, mime, body)
        }

        fun receipt(bundleId: ByteArray, receivedHour: Int, nowMs: Long): BundleMessage = BundleMessage(
            MessageKind.RECEIPT,
            nowMs,
            ByteArray(16),
            bundleId.copyOf(),
            "",
            ByteArray(4) { (receivedHour ushr (24 - 8 * it)).toByte() },
        )
    }
}

/** `BSB1`: immutable, carrier-visible header. The raw [encoded] bytes are the bundle identity. */
class BundleHeader private constructor(
    val encoded: ByteArray,
    val createdHour: Int,
    val expiryHour: Int,
    val priorityClass: Int,
    val paddedLength: Int,
    val chunkCount: Int,
    val slots: List<ByteArray>,
    val chunkHashes: List<ByteArray>,
) {
    val bundleId: ByteArray = sfSha256(encoded)
    val idPrefix: ByteArray get() = bundleId.copyOf(8)
    val tags: List<ByteArray> get() = slots.map { it.copyOf(StoreForwardFormat.TAG_BYTES) }
    val createdDay: Int get() = StoreForwardFormat.dayOfHour(createdHour)

    /** Ciphertext bytes this bundle occupies on a carrier. */
    val storedBytes: Long get() = paddedLength.toLong() + chunkCount.toLong() * MeshAead.TAG_BYTES

    fun chunkCiphertextBytes(index: Int) = StoreForwardFormat.chunkCiphertextBytes(paddedLength, index)

    /** Carrier-side check: index-bound, truncated SHA-256 match, exact size. No key needed. */
    fun verifyChunk(index: Int, ciphertext: ByteArray): Boolean =
        index in 0 until chunkCount &&
            ciphertext.size == chunkCiphertextBytes(index) &&
            MessageDigest.isEqual(StoreForwardFormat.chunkHash(ciphertext), chunkHashes[index])

    fun slotAad(slotIndex: Int): ByteArray =
        fixedFields(createdHour, expiryHour, priorityClass, paddedLength, chunkCount, slots.size, chunkHashes) +
            byteArrayOf(slotIndex.toByte()) + slots[slotIndex].copyOf(StoreForwardFormat.TAG_BYTES)

    companion object {
        private val MAGIC = "BSB1".toByteArray(Charsets.US_ASCII)
        private const val FIXED_BYTES = 4 + 1 + 4 + 4 + 1 + 4 + 2 + 1

        internal fun fixedFields(
            created: Int,
            expiry: Int,
            priorityClass: Int,
            paddedLength: Int,
            chunkCount: Int,
            slotCount: Int,
            hashes: List<ByteArray>,
        ): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                write(MAGIC)
                writeByte(StoreForwardFormat.BUNDLE_VERSION)
                writeInt(created)
                writeInt(expiry)
                writeByte(priorityClass)
                writeInt(paddedLength)
                writeShort(chunkCount)
                writeByte(slotCount)
                hashes.forEach(::write)
            }
        }.toByteArray()

        internal fun build(
            created: Int,
            expiry: Int,
            priorityClass: Int,
            paddedLength: Int,
            slots: List<ByteArray>,
            hashes: List<ByteArray>,
        ): BundleHeader = decode(
            ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).apply {
                    write(MAGIC)
                    writeByte(StoreForwardFormat.BUNDLE_VERSION)
                    writeInt(created)
                    writeInt(expiry)
                    writeByte(priorityClass)
                    writeInt(paddedLength)
                    writeShort(hashes.size)
                    writeByte(slots.size)
                    slots.forEach(::write)
                    hashes.forEach(::write)
                }
            }.toByteArray(),
        )

        /** Structural validation only (no key, no clock); fails closed on anything unexpected. */
        fun decode(bytes: ByteArray): BundleHeader {
            require(bytes.size in FIXED_BYTES..StoreForwardFormat.MAX_HEADER_BYTES) { "Invalid bundle header size" }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(4).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Invalid bundle magic" }
            require(input.readUnsignedByte() == StoreForwardFormat.BUNDLE_VERSION) { "Unsupported bundle version" }
            val created = input.readInt()
            val expiry = input.readInt()
            require(created >= 0 && expiry > created) { "Invalid bundle lifetime" }
            require(expiry - created <= StoreForwardFormat.MAX_TTL_HOURS) { "Bundle lifetime exceeds the maximum" }
            val priorityClass = input.readUnsignedByte()
            require(priorityClass in 0..3) { "Invalid priority class" }
            val padded = input.readInt()
            require(padded in StoreForwardFormat.MIN_PADDED_BYTES..StoreForwardFormat.MAX_PADDED_BYTES) {
                "Invalid padded length"
            }
            val count = input.readUnsignedShort()
            require(count == StoreForwardFormat.chunkCount(padded)) { "Chunk count does not match the padded length" }
            val slotCount = input.readUnsignedByte()
            require(slotCount in 1..StoreForwardFormat.MAX_SLOTS) { "Invalid slot count" }
            require(bytes.size == FIXED_BYTES + slotCount * StoreForwardFormat.SLOT_BYTES +
                count * StoreForwardFormat.CHUNK_HASH_BYTES) { "Bundle header length mismatch" }
            val slots = List(slotCount) { ByteArray(StoreForwardFormat.SLOT_BYTES).also(input::readFully) }
            val hashes = List(count) { ByteArray(StoreForwardFormat.CHUNK_HASH_BYTES).also(input::readFully) }
            return BundleHeader(bytes.copyOf(), created, expiry, priorityClass, padded, count, slots, hashes)
        }
    }
}

class SealedBundle(val header: BundleHeader, val chunks: List<ByteArray>) {
    val bundleId: ByteArray get() = header.bundleId
}

class OpenedBundle(val senderNodeId: NodeId, val senderMailbox: ByteArray, val message: BundleMessage)

/** A key slot that opened for this recipient and names a held contact. */
class AuthenticatedSlot(val senderNodeId: NodeId, val senderMailbox: ByteArray, val contentKey: ByteArray)

/** End-to-end seal/open. Carriers never call these; they only use [BundleHeader.verifyChunk]. */
object StoreForwardSeal {
    private val K1_INFO = StoreForwardFormat.DOMAIN + "/sender".toByteArray(Charsets.US_ASCII)
    private val K2_INFO = StoreForwardFormat.DOMAIN + "/content-key".toByteArray(Charsets.US_ASCII)

    fun seal(
        message: BundleMessage,
        sender: MailboxKey,
        senderNodeId: NodeId,
        recipientMailbox: PublicKey,
        recipientNodeId: NodeId,
        pairSecret: ByteArray,
        nowMs: Long,
        ttlHours: Int = StoreForwardFormat.DEFAULT_TTL_HOURS,
        random: SecureRandom = SecureRandom(),
    ): SealedBundle {
        require(ttlHours in 1..StoreForwardFormat.MAX_TTL_HOURS) { "Invalid message lifetime" }
        val inner = message.encode()
        val padded = StoreForwardFormat.paddedLength(inner.size)
        require(padded <= StoreForwardFormat.MAX_PADDED_BYTES) { "Message exceeds the 2 MiB bundle limit" }
        val plaintext = ByteArray(padded)
        writeInt(plaintext, 0, inner.size)
        System.arraycopy(inner, 0, plaintext, 4, inner.size)
        val count = StoreForwardFormat.chunkCount(padded)
        val cek = ByteArray(StoreForwardFormat.CONTENT_KEY_BYTES).also(random::nextBytes)
        val chunks = List(count) { index ->
            val start = index * StoreForwardFormat.CHUNK_PLAINTEXT_BYTES
            val end = minOf(padded, start + StoreForwardFormat.CHUNK_PLAINTEXT_BYTES)
            MeshAead.encrypt(cek, index.toLong(), plaintext.copyOfRange(start, end), chunkAad(index, count))
        }
        plaintext.fill(0)
        val hashes = chunks.map(StoreForwardFormat::chunkHash)
        val created = StoreForwardFormat.hourOf(nowMs)
        val expiry = created + ttlHours
        val tag = StoreForwardTags.tag(pairSecret, StoreForwardFormat.dayOfHour(created))
        val aad = BundleHeader.fixedFields(created, expiry, message.kind.priorityClass, padded, count, 1, hashes) +
            byteArrayOf(0) + tag
        val slot = sealSlot(tag, aad, sender, senderNodeId, recipientMailbox, recipientNodeId, cek, random)
        cek.fill(0)
        val header = BundleHeader.build(created, expiry, message.kind.priorityClass, padded, listOf(slot), hashes)
        return SealedBundle(header, chunks)
    }

    /**
     * Authenticates the sender of [header] for [recipient] from the key slot alone (no chunks are
     * needed). The sender is resolved by the node ID inside the slot, never by a mailbox-key search:
     * [isKnownSender] must confirm that a held card for that node ID carries exactly that mailbox
     * key, so a second card reusing someone's mailbox key cannot shadow them. Unknown senders fail
     * (contacts only, QA2). The returned content key must be wiped by the caller.
     */
    fun authenticate(
        header: BundleHeader,
        recipient: MailboxKey,
        recipientNodeId: NodeId,
        isKnownSender: (NodeId, ByteArray) -> Boolean,
    ): AuthenticatedSlot {
        var lastError: Exception? = null
        for (slotIndex in header.slots.indices) {
            val opened = try {
                openSlot(header, slotIndex, recipient, recipientNodeId)
            } catch (error: Exception) {
                lastError = error
                continue
            }
            val (senderMailbox, senderNode, cek) = opened
            if (!isKnownSender(senderNode, senderMailbox)) {
                cek.fill(0)
                throw IllegalArgumentException("Sender is not a known contact")
            }
            return AuthenticatedSlot(senderNode, senderMailbox, cek)
        }
        throw IllegalArgumentException("No key slot opens for this recipient", lastError)
    }

    /**
     * Opens a complete bundle for [recipient] (see [authenticate] for sender resolution).
     * Every failure (wrong recipient, tampered header/slot/chunk, padding, unknown sender) throws.
     */
    fun open(
        header: BundleHeader,
        chunks: List<ByteArray>,
        recipient: MailboxKey,
        recipientNodeId: NodeId,
        isKnownSender: (NodeId, ByteArray) -> Boolean,
    ): OpenedBundle {
        require(chunks.size == header.chunkCount) { "Bundle is incomplete" }
        chunks.forEachIndexed { index, chunk -> require(header.verifyChunk(index, chunk)) { "Chunk $index failed its hash" } }
        val slot = authenticate(header, recipient, recipientNodeId, isKnownSender)
        val cek = slot.contentKey
        try {
            val plaintext = ByteArrayOutputStream(header.paddedLength)
            chunks.forEachIndexed { index, chunk ->
                plaintext.write(MeshAead.decrypt(cek, index.toLong(), chunk, chunkAad(index, header.chunkCount)))
            }
            val padded = plaintext.toByteArray()
            val innerLength = readInt(padded, 0)
            require(innerLength in 0..padded.size - 4) { "Invalid padded content length" }
            for (i in 4 + innerLength until padded.size) require(padded[i].toInt() == 0) { "Invalid padding" }
            return OpenedBundle(slot.senderNodeId, slot.senderMailbox, BundleMessage.decode(padded.copyOfRange(4, 4 + innerLength)))
        } finally {
            cek.fill(0)
        }
    }

    private fun sealSlot(
        tag: ByteArray,
        aad: ByteArray,
        sender: MailboxKey,
        senderNodeId: NodeId,
        recipientMailbox: PublicKey,
        recipientNodeId: NodeId,
        cek: ByteArray,
        random: SecureRandom,
    ): ByteArray {
        val ephemeral = generateEcKeyPair(random)
        val ePub = P256Points.compress(ephemeral.public)
        val salt = slotSalt(ePub, P256Points.compress(recipientMailbox))
        val es = SoftwareMailboxKey(ephemeral).agree(recipientMailbox)
        val k1 = sfHkdf(es, salt, K1_INFO + recipientNodeId.toByteArray(), 32)
        val part1 = MeshAead.encrypt(k1, 0, sender.compressedPublic + senderNodeId.toByteArray(), aad)
        val ss = sender.agree(recipientMailbox)
        val k2 = sfHkdf(k1 + ss, salt, K2_INFO + senderNodeId.toByteArray() + recipientNodeId.toByteArray(), 32)
        val part2 = MeshAead.encrypt(k2, 0, cek, aad + part1)
        listOf(es, k1, ss, k2).forEach { it.fill(0) }
        return tag + ePub + part1 + part2
    }

    private fun openSlot(
        header: BundleHeader,
        slotIndex: Int,
        recipient: MailboxKey,
        recipientNodeId: NodeId,
    ): Triple<ByteArray, NodeId, ByteArray> {
        val slot = header.slots[slotIndex]
        var offset = StoreForwardFormat.TAG_BYTES
        val ePub = slot.copyOfRange(offset, offset + StoreForwardFormat.COMPRESSED_KEY_BYTES)
        offset += StoreForwardFormat.COMPRESSED_KEY_BYTES
        val part1Size = StoreForwardFormat.SLOT_SENDER_PLAINTEXT_BYTES + MeshAead.TAG_BYTES
        val part1 = slot.copyOfRange(offset, offset + part1Size)
        offset += part1Size
        val part2 = slot.copyOfRange(offset, slot.size)
        val aad = header.slotAad(slotIndex)
        val salt = slotSalt(ePub, recipient.compressedPublic)
        val es = recipient.agree(P256Points.decompress(ePub))
        val k1 = sfHkdf(es, salt, K1_INFO + recipientNodeId.toByteArray(), 32)
        val senderPlain = MeshAead.decrypt(k1, 0, part1, aad)
        val senderMailbox = senderPlain.copyOf(StoreForwardFormat.COMPRESSED_KEY_BYTES)
        val senderNode = NodeId.fromBytes(senderPlain.copyOfRange(StoreForwardFormat.COMPRESSED_KEY_BYTES, senderPlain.size))
        val ss = recipient.agree(P256Points.decompress(senderMailbox))
        val k2 = sfHkdf(k1 + ss, salt, K2_INFO + senderNode.toByteArray() + recipientNodeId.toByteArray(), 32)
        val cek = MeshAead.decrypt(k2, 0, part2, aad + part1)
        listOf(es, k1, ss, k2).forEach { it.fill(0) }
        return Triple(senderMailbox, senderNode, cek)
    }

    private fun slotSalt(ePub: ByteArray, recipientPub: ByteArray) =
        sfSha256(StoreForwardFormat.DOMAIN + ePub + recipientPub)

    private fun chunkAad(index: Int, count: Int) =
        byteArrayOf((index ushr 8).toByte(), index.toByte(), (count ushr 8).toByte(), count.toByte())

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = (value ushr (24 - 8 * i)).toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { acc, i -> (acc shl 8) or (source[offset + i].toInt() and 0xff) }
}

internal fun sfSha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun sfHkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..(255 * 32))
    val prk = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(salt, "HmacSHA256"))
        doFinal(ikm)
    }
    val output = ByteArrayOutputStream(length)
    var previous = byteArrayOf()
    var counter = 1
    while (output.size() < length) {
        previous = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(previous)
            update(info)
            update(counter.toByte())
            doFinal()
        }
        output.write(previous)
        counter++
    }
    prk.fill(0)
    return output.toByteArray().copyOf(length)
}

internal fun ByteArray.sfHex(): String = joinToString("") { "%02x".format(it) }

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Invalid hex" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
