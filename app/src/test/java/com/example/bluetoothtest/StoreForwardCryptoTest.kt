package com.example.bluetoothtest

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreForwardCryptoTest {
    private val now = 1_790_000_000_000L
    private val random = SecureRandom()

    private class Party {
        val identity = KeyPairSigningIdentity(generateEcKeyPair())
        val mailbox = SoftwareMailboxKey.generate()
        fun card(now: Long) = ContactCard.create(identity, mailbox, now)
    }

    private val alice = Party()
    private val bob = Party()
    private val carol = Party()

    private fun pair(a: Party, b: Party) =
        StoreForwardTags.pairSecret(a.mailbox, a.identity.nodeId, b.mailbox.publicKey, b.identity.nodeId)

    private fun text(body: String) = BundleMessage(
        MessageKind.TEXT, now, ByteArray(16) { 1 }, null, "text/plain", body.toByteArray(),
    )

    private fun sealAliceToBob(message: BundleMessage = text("hello bob"), ttl: Int = 72) = StoreForwardSeal.seal(
        message, alice.mailbox, alice.identity.nodeId, bob.mailbox.publicKey, bob.identity.nodeId, pair(alice, bob), now, ttl, random,
    )

    private fun aliceKnownToBob(node: NodeId, mailbox: ByteArray): Boolean =
        node == alice.identity.nodeId && mailbox.contentEquals(alice.mailbox.compressedPublic)

    @Test
    fun contactCardRoundTripsAsTextAndVerifies() {
        val card = alice.card(now)
        val text = card.toText()
        assertTrue(text.startsWith("BCC1:"))
        assertTrue("card text stays QR-sized: ${text.length}", text.length < 400)
        val decoded = ContactCard.fromText(text)
        assertEquals(alice.identity.nodeId, decoded.nodeId)
        assertArrayEquals(alice.mailbox.compressedPublic, decoded.mailboxPublic)
        assertEquals(alice.mailbox.publicKey, decoded.mailboxKey)
    }

    @Test
    fun tamperedContactCardIsRejected() {
        val bytes = alice.card(now).encode()
        for (index in listOf(10, bytes.size - 80, bytes.size - 1)) {
            val tampered = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java) { ContactCard.decode(tampered) }
        }
        // A card whose mailbox key is replaced (another valid point) but signature kept must fail.
        val mailboxOffset = 4 + 1 + 2 + alice.identity.publicKeyEncoded.size
        val forged = bytes.copyOf().also {
            System.arraycopy(bob.mailbox.compressedPublic, 0, it, mailboxOffset, StoreForwardFormat.COMPRESSED_KEY_BYTES)
        }
        assertThrows(IllegalArgumentException::class.java) { ContactCard.decode(forged) }
    }

    @Test
    fun compressedPointsRoundTripAndRejectInvalidPoints() {
        repeat(8) {
            val key = generateEcKeyPair().public
            assertEquals(key, P256Points.decompress(P256Points.compress(key)))
        }
        val invalid = ByteArray(33).also { it[0] = 2; it[32] = 5 }
        // x = 5 is not the x of any P-256 point with this prefix, or the point check rejects it.
        val result = runCatching { P256Points.decompress(invalid) }
        if (result.isSuccess) assertEquals(invalid.toList(), P256Points.compress(result.getOrThrow()).toList())
        assertThrows(IllegalArgumentException::class.java) { P256Points.decompress(ByteArray(33) { 0xff.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { P256Points.decompress(ByteArray(33).also { it[0] = 4 }) }
    }

    @Test
    fun sealOpenRoundTripAuthenticatesTheSender() {
        val sealed = sealAliceToBob()
        val opened = StoreForwardSeal.open(sealed.header, sealed.chunks, bob.mailbox, bob.identity.nodeId, ::aliceKnownToBob)
        assertEquals(alice.identity.nodeId, opened.senderNodeId)
        assertEquals("hello bob", String(opened.message.body))
        assertEquals(MessageKind.TEXT, opened.message.kind)
        assertEquals(StoreForwardFormat.MIN_PADDED_BYTES, sealed.header.paddedLength)
        assertEquals(1, sealed.header.chunkCount)
    }

    @Test
    fun largeVoiceBundleSpansManyChunksAndOpens() {
        val body = ByteArray(1_000_000).also(random::nextBytes)
        val message = BundleMessage(MessageKind.VOICE, now, ByteArray(16), null, "audio/mp4", body)
        val sealed = sealAliceToBob(message)
        assertEquals(1, sealed.header.priorityClass)
        assertTrue(sealed.header.chunkCount > 100)
        assertTrue(sealed.header.encoded.size <= StoreForwardFormat.MAX_HEADER_BYTES)
        sealed.chunks.dropLast(1).forEach { assertEquals(StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES, it.size) }
        val opened = StoreForwardSeal.open(sealed.header, sealed.chunks, bob.mailbox, bob.identity.nodeId, ::aliceKnownToBob)
        assertArrayEquals(body, opened.message.body)
    }

    @Test
    fun maximumBundleIsEnforced() {
        val tooLarge = BundleMessage(MessageKind.VIDEO, now, ByteArray(16), null, "", ByteArray(BundleMessage.MAX_BODY_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { sealAliceToBob(tooLarge) }
        val largest = BundleMessage(MessageKind.VIDEO, now, ByteArray(16), null, "", ByteArray(BundleMessage.MAX_BODY_BYTES))
        assertEquals(StoreForwardFormat.MAX_CHUNKS, sealAliceToBob(largest).header.chunkCount)
    }

    @Test
    fun wrongRecipientAndUnknownSenderFailClosed() {
        val sealed = sealAliceToBob()
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.open(sealed.header, sealed.chunks, carol.mailbox, carol.identity.nodeId) { _, _ -> true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.open(sealed.header, sealed.chunks, bob.mailbox, bob.identity.nodeId) { _, _ -> false }
        }
        // A contact entry binding the mailbox to a different node ID is also refused.
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.open(sealed.header, sealed.chunks, bob.mailbox, bob.identity.nodeId) { node, _ -> node == carol.identity.nodeId }
        }
    }

    @Test
    fun tamperedHeaderSlotOrChunkIsRejected() {
        val sealed = sealAliceToBob(text("x".repeat(20_000)))
        val encoded = sealed.header.encoded
        // Expiry hour (bytes 9..12): changing it keeps the header parseable but breaks the slot AAD.
        val laterExpiry = encoded.copyOf().also { it[12] = (it[12] + 1).toByte() }
        val tamperedHeader = BundleHeader.decode(laterExpiry)
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.open(tamperedHeader, sealed.chunks, bob.mailbox, bob.identity.nodeId, ::aliceKnownToBob)
        }
        // Slot ciphertext byte.
        val slotByte = 21 + 60
        val tamperedSlot = BundleHeader.decode(encoded.copyOf().also { it[slotByte] = (it[slotByte].toInt() xor 1).toByte() })
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.open(tamperedSlot, sealed.chunks, bob.mailbox, bob.identity.nodeId, ::aliceKnownToBob)
        }
        // Chunk byte: rejected by the carrier-visible hash list before any decryption.
        val chunks = sealed.chunks.toMutableList()
        chunks[1] = chunks[1].copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertFalse(sealed.header.verifyChunk(1, chunks[1]))
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.open(sealed.header, chunks, bob.mailbox, bob.identity.nodeId, ::aliceKnownToBob)
        }
        // Swapped chunks fail their index-bound hashes.
        val swapped = sealed.chunks.toMutableList().also { val first = it[0]; it[0] = it[1]; it[1] = first }
        assertFalse(sealed.header.verifyChunk(0, swapped[0]))
    }

    @Test
    fun chunkHashListIsVerifiableWithoutKeys() {
        val sealed = sealAliceToBob(text("y".repeat(50_000)))
        val header = BundleHeader.decode(sealed.header.encoded)
        assertArrayEquals(sealed.bundleId, header.bundleId)
        sealed.chunks.forEachIndexed { index, chunk -> assertTrue(header.verifyChunk(index, chunk)) }
        assertFalse(header.verifyChunk(header.chunkCount, sealed.chunks[0]))
        assertFalse(header.verifyChunk(0, sealed.chunks[0].copyOf(100)))
        assertEquals(StoreForwardFormat.CHUNK_HASH_BYTES * header.chunkCount, header.chunkHashes.sumOf { it.size })
    }

    @Test
    fun padmeHidesExactSizeWithBoundedOverhead() {
        var previous = 0
        for (length in listOf(0, 1, 10, 900, 1_020, 1_021, 5_000, 70_000, 300_000, 1_500_000, 2_000_000)) {
            val padded = StoreForwardFormat.paddedLength(length)
            assertTrue(padded >= length + 4)
            assertTrue(padded >= StoreForwardFormat.MIN_PADDED_BYTES)
            if (length + 4 > StoreForwardFormat.MIN_PADDED_BYTES) {
                assertTrue("overhead for $length", padded <= (length + 4) * 1.12 + 1)
            }
            assertTrue(padded >= previous)
            previous = padded
        }
        // Receipts and short texts land in the same padded size.
        assertEquals(StoreForwardFormat.paddedLength(40), StoreForwardFormat.paddedLength(800))
    }

    @Test
    fun recipientTagIsPairwiseAndRotatesDaily() {
        val ab = pair(alice, bob)
        val ba = pair(bob, alice)
        assertArrayEquals(ab, ba)
        val ac = pair(alice, carol)
        val day = StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(now))
        assertArrayEquals(StoreForwardTags.tag(ab, day), StoreForwardTags.tag(ba, day))
        assertFalse(StoreForwardTags.tag(ab, day).contentEquals(StoreForwardTags.tag(ab, day + 1)))
        assertFalse(StoreForwardTags.tag(ab, day).contentEquals(StoreForwardTags.tag(ac, day)))
        assertEquals(StoreForwardFormat.TAG_BYTES, StoreForwardTags.tag(ab, day).size)
        val sealed = sealAliceToBob()
        assertArrayEquals(StoreForwardTags.tag(ab, day), sealed.header.tags.single())
    }

    @Test
    fun headerRejectsInvalidLifetimeAndLayout() {
        assertThrows(IllegalArgumentException::class.java) { sealAliceToBob(ttl = StoreForwardFormat.MAX_TTL_HOURS + 1) }
        val encoded = sealAliceToBob(ttl = 72).header.encoded
        // Expiry far beyond the 14-day maximum.
        val tooLong = encoded.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(tooLong) }
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(encoded + 0) }
        val badMagic = encoded.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(badMagic) }
    }

    @Test
    fun bundleIdChangesWithEverySeal() {
        val first = sealAliceToBob()
        val second = sealAliceToBob()
        assertNotEquals(first.bundleId.toList(), second.bundleId.toList())
        assertArrayEquals(sfSha256(first.header.encoded), first.bundleId)
    }

    @Test
    fun receiptMessageRoundTrips() {
        val id = ByteArray(32) { it.toByte() }
        val receipt = BundleMessage.receipt(id, 12345, now)
        val decoded = BundleMessage.decode(receipt.encode())
        assertEquals(MessageKind.RECEIPT, decoded.kind)
        assertArrayEquals(id, decoded.replyTo)
        assertEquals(0, MessageKind.RECEIPT.priorityClass)
    }
}
