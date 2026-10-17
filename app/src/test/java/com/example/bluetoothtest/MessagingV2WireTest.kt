package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bundle header version 3 (class 4, exactly one slot), BSF1 version 2 messages, sealing and its immutability rules. */
class MessagingV2WireTest {
    private val now = 1_790_000_000_000L
    private val random = SecureRandom()

    private class Party {
        val identity = KeyPairSigningIdentity(generateEcKeyPair())
        val mailbox = SoftwareMailboxKey.generate()
    }

    private val alice = Party()
    private val bob = Party()

    private fun pair() = StoreForwardTags.pairSecret(alice.mailbox, alice.identity.nodeId, bob.mailbox.publicKey, bob.identity.nodeId)
    private fun knownAlice(node: NodeId, mailbox: ByteArray) =
        node == alice.identity.nodeId && mailbox.contentEquals(alice.mailbox.compressedPublic)

    private fun sealV3(inner: ByteArray = "inner bytes".toByteArray(), cls: Int = 0, ttl: Int = 72) = StoreForwardSeal.sealV3(
        inner, cls, alice.mailbox, alice.identity.nodeId, bob.mailbox.publicKey, bob.identity.nodeId, pair(), now, ttl, random,
    )

    private fun segmentKey(seed: Int = 1) = ByteArray(32) { (seed + it).toByte() }
    private fun pairTag() = StoreForwardTags.tag(pair(), StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(now)))

    // ---- header version 3 ------------------------------------------------------------------------------------------

    @Test
    fun aVersion3SlotBundleRoundTripsWithExactlyOneSlot() {
        val sealed = sealV3("a version 3 payload".toByteArray(), cls = 2)
        val header = BundleHeader.decode(sealed.header.encoded, allowV3 = true)
        assertEquals(3, header.version)
        assertEquals(1, header.slots.size)
        assertEquals(2, header.priorityClass)
        assertFalse(header.isBulk)
        assertEquals(StoreForwardFormat.SLOT_BYTES, header.slots[0].size)
        val opened = StoreForwardSeal.openRaw(header, sealed.chunks, bob.mailbox, bob.identity.nodeId, ::knownAlice)
        assertEquals(alice.identity.nodeId, opened.senderNodeId)
        assertEquals("a version 3 payload", String(opened.inner))
        assertThrows("v1 open refuses a version 3 header", IllegalArgumentException::class.java) {
            StoreForwardSeal.open(header, sealed.chunks, bob.mailbox, bob.identity.nodeId, ::knownAlice)
        }
    }

    @Test
    fun theSwitchOffReaderRejectsEveryVersion3Header() {
        val encoded = sealV3().header.encoded
        assertEquals("Unsupported bundle version", assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(encoded) }.message)
        val bulk = StoreForwardSeal.sealBulk("seg".toByteArray(), 1024, segmentKey(), pairTag(), now, 336)
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(bulk.header.encoded) }
    }

    private fun rebuild(version: Int, cls: Int, slots: Int, tag: Boolean, base: BundleHeader): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply {
            write("BSB1".toByteArray())
            writeByte(version)
            writeInt(base.createdHour)
            writeInt(base.expiryHour)
            writeByte(cls)
            writeInt(base.paddedLength)
            writeShort(base.chunkCount)
            writeByte(slots)
            repeat(slots) { write(base.slots.firstOrNull() ?: ByteArray(StoreForwardFormat.SLOT_BYTES)) }
            if (tag) write(ByteArray(8))
            base.chunkHashes.forEach(::write)
        }
    }.toByteArray()

    @Test
    fun versions3To5NeedExactlyOneSlotAndClass4NeedsNone() {
        val base = BundleHeader.decode(sealV3().header.encoded, allowV3 = true)
        // Two slots in a version 3 header: refused (a slot does not authenticate the other slots).
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(rebuild(3, 0, 2, false, base), allowV3 = true) }
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(rebuild(3, 0, 0, false, base), allowV3 = true) }
        assertNotNull(BundleHeader.decode(rebuild(3, 0, 1, false, base), allowV3 = true))
        // Version 2 still allows up to 8 slots (unchanged v1 rule) but never class 4.
        val v2 = rebuild(2, 0, 3, false, base)
        assertEquals(3, BundleHeader.decode(v2).slots.size)
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(rebuild(2, 4, 0, true, base), allowV3 = true) }
        // Class 4 in version 3: slot count 0 then the pair tag; a slot or a missing tag is malformed.
        assertNotNull(BundleHeader.decode(rebuild(3, 4, 0, true, base), allowV3 = true))
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(rebuild(3, 4, 1, false, base), allowV3 = true) }
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(rebuild(3, 4, 0, false, base), allowV3 = true) }
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(rebuild(3, 5, 1, false, base), allowV3 = true) }
        assertThrows(IllegalArgumentException::class.java) { BundleHeader.decode(rebuild(4, 0, 1, false, base), allowV3 = true) }
    }

    @Test
    fun aBulkBundleCarriesOnlyThePairTagAndOpensUnderTheSegmentKey() {
        val inner = ByteArray(300_000) { (it * 7).toByte() }
        val padded = StoreForwardFormat.paddedLength(inner.size)
        val sealed = StoreForwardSeal.sealBulk(inner, padded, segmentKey(), pairTag(), now, 336)
        val header = BundleHeader.decode(sealed.header.encoded, allowV3 = true)
        assertTrue(header.isBulk)
        assertEquals(4, header.priorityClass)
        assertEquals(0, header.slots.size)
        assertArrayEquals(pairTag(), header.bulkTag)
        assertArrayEquals(pairTag(), header.tags.single())
        assertArrayEquals(inner, StoreForwardSeal.openBulk(header, sealed.chunks, segmentKey()))
        // Wrong key, another segment's key, a damaged chunk and a missing chunk all fail.
        assertThrows(Exception::class.java) { StoreForwardSeal.openBulk(header, sealed.chunks, segmentKey(2)) }
        val damaged = sealed.chunks.toMutableList().also { it[1] = it[1].copyOf().also { c -> c[5] = (c[5].toInt() xor 1).toByte() } }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardSeal.openBulk(header, damaged, segmentKey()) }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardSeal.openBulk(header, sealed.chunks.drop(1), segmentKey()) }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardSeal.openRaw(header, sealed.chunks, bob.mailbox, bob.identity.nodeId, ::knownAlice) }
    }

    @Test
    fun aFullSegmentIsNotPaddedAndSealsToExactly128Chunks() {
        val data = ByteArray(MessagingV2Format.SEGMENT_DATA_BYTES) { it.toByte() }
        val message = MessageV2(V2Kind.SEGMENT, 0, now, ByteArray(16), ByteArray(16), null, 0, SegmentBody(ByteArray(16), 0, data).encode())
        val inner = message.encode()
        assertEquals(1_044_568, inner.size)
        val sealed = StoreForwardSeal.sealBulk(inner, SegmentMath.paddedLength(data.size), segmentKey(), pairTag(), now, 336)
        assertEquals(1_044_572, sealed.header.paddedLength)
        assertEquals(128, sealed.header.chunkCount)
        assertEquals(128, sealed.chunks.size)
        val reopened = StoreForwardSeal.openBulk(sealed.header, sealed.chunks, segmentKey())
        assertArrayEquals(inner, reopened)
        // The last chunk is the short one (the plaintext is not a multiple of 8176).
        assertEquals(StoreForwardFormat.chunkCiphertextBytes(1_044_572, 127), sealed.chunks[127].size)
    }

    @Test
    fun sealingTheSameContentTwiceNeverRepeatsKeysOrCiphertext() {
        val a = sealV3("same content".toByteArray())
        val b = sealV3("same content".toByteArray())
        assertNotEquals(a.header.bundleId.sfHex(), b.header.bundleId.sfHex())
        assertFalse(a.chunks[0].contentEquals(b.chunks[0]))
        assertFalse("the slot (ephemeral key) differs", a.header.slots[0].contentEquals(b.header.slots[0]))
        // The content key is fresh per bundle: chunk 0 of one cannot be opened under the other's slot.
        val crossed = StoreForwardSeal.runCatching { openRaw(a.header, b.chunks, bob.mailbox, bob.identity.nodeId, ::knownAlice) }
        assertTrue(crossed.isFailure)
    }

    @Test
    fun theSlotAuthenticatesTheVersionAndAnEditedHeaderFails() {
        val sealed = sealV3("authenticated".toByteArray())
        val encoded = sealed.header.encoded
        val downgraded = encoded.copyOf().also { it[4] = 2 }
        // Same bytes with the version byte flipped to 2 is a different bundle ID and its slot AAD no longer matches.
        val header = BundleHeader.decode(downgraded)
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.authenticate(header, bob.mailbox, bob.identity.nodeId, ::knownAlice)
        }
        val laterExpiry = encoded.copyOf().also { it[12] = (it[12] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            StoreForwardSeal.authenticate(BundleHeader.decode(laterExpiry, allowV3 = true), bob.mailbox, bob.identity.nodeId, ::knownAlice)
        }
    }

    @Test
    fun aVersion2SealIsUnchangedByTheRefactor() {
        val message = BundleMessage(MessageKind.TEXT, now, ByteArray(16), null, "text/plain", "legacy".toByteArray())
        val sealed = StoreForwardSeal.seal(message, alice.mailbox, alice.identity.nodeId, bob.mailbox.publicKey, bob.identity.nodeId, pair(), now, 72, random)
        assertEquals(2, sealed.header.encoded[4].toInt())
        val header = BundleHeader.decode(sealed.header.encoded)
        assertEquals(2, header.version)
        assertEquals(0, header.priorityClass)
        assertEquals("legacy", String(StoreForwardSeal.open(header, sealed.chunks, bob.mailbox, bob.identity.nodeId, ::knownAlice).message.body))
        assertNull(header.bulkTag)
    }

    // ---- BSF1 version 2 --------------------------------------------------------------------------------------------

    private val id = ByteArray(32) { it.toByte() }

    @Test
    fun helloVersion1KeepsItsExactSeventeenByteLayout() {
        val digest = ByteArray(8) { (0x30 + it).toByte() }
        val encoded = StoreForwardWire.encode(StoreForwardMessage.Hello(1, 0x01020304, 0x0a0b0c0d, digest))
        val expected = "42534631" + "01" + "00000011" + "01" + "01020304" + "0a0b0c0d" + digest.sfHex()
        assertEquals(expected, encoded.sfHex())
        val decoded = StoreForwardWire.decode(encoded) as StoreForwardMessage.Hello
        assertEquals(1, decoded.version)
        assertEquals(0, decoded.flags)
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.encode(StoreForwardMessage.Hello(1, 0, 0, digest, flags = 1)) }
    }

    @Test
    fun helloVersion2AddsFlagsAndPrekeysHeldAndIsReadOnlyWhenOptedIn() {
        val hello = StoreForwardMessage.Hello(2, 5, 6, ByteArray(8) { 1 }, StoreForwardHelloFlags.CHAT or StoreForwardHelloFlags.FILES, 7)
        val encoded = StoreForwardWire.encode(hello)
        assertEquals(9 + 19, encoded.size)
        val decoded = StoreForwardWire.decode(encoded, v2 = true) as StoreForwardMessage.Hello
        assertEquals(2, decoded.version)
        assertEquals(3, decoded.flags)
        assertEquals(7, decoded.prekeysHeld)
        // A v1 reader (switch off) rejects the 19-byte body.
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(encoded, v2 = false) }
        // Unknown flag bits are refused.
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(encoded.copyOf().also { it[encoded.size - 2] = 0x08 }, v2 = true) }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.encode(StoreForwardMessage.Hello(2, 0, 0, ByteArray(8), flags = 0x80)) }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(encoded + 0, v2 = true) }
    }

    @Test
    fun cancelPingIdleAndPrekeyGoneExistOnlyForAV2Reader() {
        val cancel = StoreForwardWire.encode(StoreForwardMessage.Cancel(id))
        val ping = StoreForwardWire.encode(StoreForwardMessage.Ping)
        val idle = StoreForwardWire.encode(StoreForwardMessage.Done(StoreForwardDoneReason.IDLE))
        val gone = StoreForwardWire.encode(StoreForwardMessage.Reply(id, StoreForwardRefusal.PREKEY_GONE))
        assertEquals(11, cancel[4].toInt())
        assertEquals(12, ping[4].toInt())
        assertEquals(9 + 0, ping.size)
        assertArrayEquals(id, (StoreForwardWire.decode(cancel, true) as StoreForwardMessage.Cancel).bundleId)
        assertTrue(StoreForwardWire.decode(ping, true) === StoreForwardMessage.Ping)
        assertEquals(StoreForwardDoneReason.IDLE, (StoreForwardWire.decode(idle, true) as StoreForwardMessage.Done).reason)
        assertEquals(StoreForwardRefusal.PREKEY_GONE, (StoreForwardWire.decode(gone, true) as StoreForwardMessage.Reply).reason)
        listOf(cancel, ping, idle, gone).forEach { record ->
            assertThrows("a switch-off reader refuses it", IllegalArgumentException::class.java) { StoreForwardWire.decode(record, false) }
        }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(cancel + 0, true) }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(cancel.copyOf(cancel.size - 1), true) }
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(ping + 0, true) }
    }

    @Test
    fun headerMessagesDecodeVersion3OnlyWhenOptedIn() {
        val header = sealV3().header
        val message = StoreForwardWire.encode(StoreForwardMessage.Header(header, 0))
        assertEquals(3, (StoreForwardWire.decode(message, true) as StoreForwardMessage.Header).header.version)
        assertThrows(IllegalArgumentException::class.java) { StoreForwardWire.decode(message, false) }
    }

    @Test
    fun allV1WireMessagesStillRoundTripUnderBothReaders() {
        val bits = java.util.BitSet().apply { set(0); set(9) }
        val messages = listOf(
            StoreForwardMessage.Summary(listOf(SummaryEntry(id, ByteArray(8) { 2 }, 1, 999, 10, 7)), last = true),
            StoreForwardMessage.Want(id, true, 10, bits),
            StoreForwardMessage.Chunk(id, 3, ByteArray(100) { 4 }),
            StoreForwardMessage.Reply(id, StoreForwardRefusal.QUOTA),
            StoreForwardMessage.DeliveredLocal(id),
            StoreForwardMessage.Done(StoreForwardDoneReason.SUSPEND),
        )
        messages.forEach { m ->
            val bytes = StoreForwardWire.encode(m)
            assertArrayEquals(bytes, StoreForwardWire.encode(StoreForwardWire.decode(bytes, false)))
            assertArrayEquals(bytes, StoreForwardWire.encode(StoreForwardWire.decode(bytes, true)))
        }
    }
}
