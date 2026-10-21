package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** BSM2, the attachment manifest, receipts and the segment arithmetic (PROTOCOL "Messaging v2"). */
class MessagingV2ContentTest {
    private fun id16(seed: Int) = ByteArray(16) { (seed + it).toByte() }
    private fun id32(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun message(
        kind: V2Kind = V2Kind.TEXT,
        flags: Int = 0,
        body: ByteArray = "hello".toByteArray(),
        reply: ByteArray? = null,
        disappear: Int = 0,
    ) = MessageV2(kind, flags, 1_790_000_000_000L, id16(1), id16(40), reply, disappear, body)

    private fun jpeg(width: Int, height: Int): ByteArray = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(),
        0xff.toByte(), 0xc0.toByte(), 0, 17, 8, (height ushr 8).toByte(), height.toByte(), (width ushr 8).toByte(), width.toByte(),
        3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
        0xff.toByte(), 0xd9.toByte(),
    )

    @Test
    fun bsm2RoundTripsAndHasTheDocumentedHeaderSize() {
        val original = message(reply = id16(90), disappear = 3_600, flags = MessagingV2Format.WANT_READ_RECEIPT)
        val encoded = original.encode()
        assertEquals(MessageV2.HEADER_BYTES + 5, encoded.size)
        val decoded = MessageV2.decode(encoded)
        assertEquals(V2Kind.TEXT, decoded.kind)
        assertTrue(decoded.wantsReadReceipt)
        assertArrayEquals(id16(1), decoded.messageId)
        assertArrayEquals(id16(40), decoded.conversationId)
        assertArrayEquals(id16(90), decoded.replyTo)
        assertEquals(3_600, decoded.disappearSeconds)
        assertEquals("hello", String(decoded.body))
        assertNull(MessageV2.decode(message().encode()).replyTo)
    }

    @Test
    fun bsm2RejectsMalformedInputWithoutPartialResults() {
        val good = message().encode()
        assertThrows(MessageV2Invalid::class.java) { MessageV2.decode(good.copyOf(good.size - 1)) }
        assertThrows(MessageV2Invalid::class.java) { MessageV2.decode(good + 0) }
        assertThrows(MessageV2Invalid::class.java) { MessageV2.decode(good.copyOf(30)) }
        assertThrows(MessageV2Invalid::class.java) { MessageV2.decode(good.copyOf().also { it[0] = 'X'.code.toByte() }) }
        // The legacy kinds 2, 3, 4 and 7 exist only in BSM1; an unknown kind is refused, never half shown.
        listOf(2, 3, 4, 7, 11, 255).forEach { kind ->
            assertThrows("kind $kind", MessageV2Invalid::class.java) { MessageV2.decode(good.copyOf().also { it[4] = kind.toByte() }) }
        }
        // Every reserved flag bit refuses the message (fail safe).
        for (bit in 1..7) {
            assertThrows("flag bit $bit", MessageV2Invalid::class.java) { MessageV2.decode(good.copyOf().also { it[5] = (1 shl bit).toByte() }) }
        }
        val negativeTimer = good.copyOf().also { for (i in 0 until 4) it[62 + i] = 0xff.toByte() }
        assertThrows(MessageV2Invalid::class.java) { MessageV2.decode(negativeTimer) }
        val hugeLength = good.copyOf().also { it[66] = 0x7f }
        assertThrows(MessageV2Invalid::class.java) { MessageV2.decode(hugeLength) }
    }

    @Test
    fun conversationIdIsTheSortedNodePairHashAsV1ComputesIt() {
        val a = NodeId.fromBytes(id16(1))
        val b = NodeId.fromBytes(id16(2))
        val expected = sfSha256(listOf(a.toString(), b.toString()).sorted().joinToString("").toByteArray()).copyOf(16)
        assertArrayEquals(expected, ConversationIds.of(a, b))
        assertArrayEquals(ConversationIds.of(a, b), ConversationIds.of(b, a))
        assertFalse(ConversationIds.of(a, b).contentEquals(ConversationIds.of(a, NodeId.fromBytes(id16(3)))))
    }

    @Test
    fun segmentArithmeticMatchesTheDocumentedSizes() {
        assertEquals(1_044_480, MessagingV2Format.SEGMENT_DATA_BYTES)
        assertEquals(255, MessagingV2Format.SEGMENT_BLOCKS)
        assertEquals(62, SegmentMath.count(MessagingV2Format.MAX_FILE_BYTES))
        assertEquals(1, SegmentMath.count(1_000_001))
        assertEquals(2, SegmentMath.count(1_044_481))
        assertEquals(1_044_480, SegmentMath.dataLength(0, 64_000_000))
        assertEquals((64_000_000L - 61L * 1_044_480).toInt(), SegmentMath.dataLength(61, 64_000_000))
        // A full segment: u32 length + 70-byte BSM2 header + file ID + index + data, unpadded, in 128 chunks.
        assertEquals(1_044_572, SegmentMath.paddedLength(1_044_480))
        assertEquals(128, SegmentMath.chunkCount(1_044_480))
        assertTrue(SegmentMath.chunkCount(1) >= 1)
        assertEquals(StoreForwardFormat.chunkCount(StoreForwardFormat.paddedLength(70 + 18 + 1)), SegmentMath.chunkCount(1))
        assertThrows(IllegalArgumentException::class.java) { SegmentMath.dataLength(62, 64_000_000) }
        assertThrows(IllegalArgumentException::class.java) { SegmentMath.segmentKey(ByteArray(32), ByteArray(16), 62) }
    }

    @Test
    fun segmentKeysMatchTheReferenceVectorAndDifferPerIndexAndFile() {
        val fileKey = ByteArray(32) { it.toByte() }
        val fileId = ByteArray(16) { (0xa0 + it).toByte() }
        assertEquals("cf6be4ac0c090df7ff506602b70a76a3177245acd216240d8cbe17824709af35", SegmentMath.segmentKey(fileKey, fileId, 0).sfHex())
        assertEquals("14205e650f592b95b660e2fb4465d6d35f02c9cc735e23a6c987d52ba503ea15", SegmentMath.segmentKey(fileKey, fileId, 1).sfHex())
        assertEquals("c4fbec228ef330904fbfd61ca1e12948d6040dbe3bfde2500418d9f1a5211e8d", SegmentMath.segmentKey(fileKey, fileId, 61).sfHex())
        assertNotEquals(SegmentMath.segmentKey(fileKey, fileId, 0).sfHex(), SegmentMath.segmentKey(fileKey, ByteArray(16), 0).sfHex())
        assertNotEquals(SegmentMath.segmentKey(fileKey, fileId, 0).sfHex(), SegmentMath.segmentKey(ByteArray(32), fileId, 0).sfHex())
        assertEquals(255, SegmentMath.firstBlock(1))
    }

    private fun manifest(
        total: Long = 500,
        segments: Int = 0,
        name: String = "plan.pdf",
        mime: String = "application/pdf",
        thumbnail: ByteArray = ByteArray(0),
        data: ByteArray? = null,
    ): AttachmentManifest {
        val refs = List(segments) { index -> SegmentRef(id32(index * 3), SegmentMath.chunkCount(SegmentMath.dataLength(index, total))) }
        return AttachmentManifest(
            id16(5), Presentation.FILE, total, id32(100), id32(7), name, mime, 640, 480, 1234, thumbnail,
            if (segments == 0) (data ?: ByteArray(total.toInt()) { it.toByte() }) else null, refs,
        )
    }

    @Test
    fun inlineAndSegmentedManifestsRoundTrip() {
        val inline = AttachmentManifest.decode(manifest().encode())
        assertTrue(inline.inline)
        assertEquals(500, inline.inlineData!!.size)
        assertEquals("plan.pdf", inline.name)
        assertEquals("application/pdf", inline.mime)
        assertEquals(Presentation.FILE, inline.presentation)
        // An inline manifest never reveals a usable file key: it is zero on the wire.
        assertTrue(inline.fileKey.all { it.toInt() == 0 })
        val total = 2_500_000L
        val segmented = AttachmentManifest.decode(manifest(total = total, segments = 3).encode())
        assertFalse(segmented.inline)
        assertEquals(3, segmented.segments.size)
        assertArrayEquals(id32(7), segmented.fileKey)
        assertEquals(SegmentMath.chunkCount(SegmentMath.dataLength(2, total)), segmented.segments[2].chunkCount)
    }

    @Test
    fun manifestDecodeRejectsEverythingOffSpec() {
        val total = 2_500_000L
        val good = manifest(total = total, segments = 3).encode()
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(good + 0) }
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(good.copyOf(good.size - 1)) }
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(good.copyOf(20)) }
        fun patched(offset: Int, value: Int) = good.copyOf().also { it[offset] = value.toByte() }
        assertThrows("presentation", MessageV2Invalid::class.java) { AttachmentManifest.decode(patched(16, 9)) }
        assertThrows("segment count", MessageV2Invalid::class.java) { AttachmentManifest.decode(patched(25, 4)) }
        // Total size 0 and above 64,000,000.
        val sizeZero = good.copyOf().also { for (i in 17..24) it[i] = 0 }
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(sizeZero) }
        val tooBig = good.copyOf().also { for (i in 17..24) it[i] = 0; it[18] = 0x7f }
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(tooBig) }
        // Segment chunk count that does not follow from the size.
        val afterNames = good.size - 3 * 34 + 33
        assertThrows("chunk count", MessageV2Invalid::class.java) { AttachmentManifest.decode(good.copyOf().also { it[afterNames] = (it[afterNames] + 1).toByte() }) }
        // Inline: data shorter or longer than the declared size.
        val inline = manifest().encode()
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(inline.copyOf(inline.size - 1)) }
        // Inline above the 1,000,000 byte limit.
        val inlineTooBig = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                write(id16(1)); writeByte(0); writeLong(1_000_001); writeByte(0); write(id32(1)); write(ByteArray(32))
                writeByte(0); writeByte(0); writeShort(0); writeShort(0); writeInt(0); writeShort(0); write(ByteArray(1_000_001))
            }
        }.toByteArray()
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(inlineTooBig) }
        // 63 segments or duplicate IDs.
        val duplicate = manifest(total = total, segments = 3)
        val dup = AttachmentManifest(
            duplicate.fileId, duplicate.presentation, total, duplicate.sha256, duplicate.fileKey, "x", "a/b", 0, 0, 0, ByteArray(0), null,
            listOf(duplicate.segments[0], duplicate.segments[0], duplicate.segments[2]),
        )
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(dup.encode()) }
    }

    @Test
    fun overlongNamesAndMimeTypesRejectTheManifest() {
        val good = manifest().encode()
        val nameLengthOffset = 16 + 1 + 8 + 1 + 32 + 32
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(good.copyOf().also { it[nameLengthOffset] = 97.toByte() }) }
        val mimeLengthOffset = nameLengthOffset + 1 + "plan.pdf".length
        assertThrows(MessageV2Invalid::class.java) { AttachmentManifest.decode(good.copyOf().also { it[mimeLengthOffset] = 101.toByte() }) }
    }

    @Test
    fun aThumbnailThatFailsAnyCheckIsIgnoredNotFatal() {
        assertEquals(Pair(320, 200), JpegHeader.dimensions(jpeg(320, 200)))
        val ok = AttachmentManifest.decode(manifest(thumbnail = jpeg(320, 320)).encode())
        assertEquals(jpeg(320, 320).size, ok.thumbnail.size)
        // Larger than 320 px, not a JPEG, truncated header, or empty: ignored.
        listOf(jpeg(321, 100), "GIF89a....".toByteArray(), jpeg(10, 10).copyOf(8), ByteArray(0)).forEach { thumb ->
            assertEquals(0, AttachmentManifest.decode(manifest(thumbnail = thumb).encode()).thumbnail.size)
        }
        assertNull(JpegHeader.dimensions(jpeg(0, 0)))
        assertFalse(JpegHeader.acceptableThumbnail(ByteArray(MessagingV2Format.THUMBNAIL_MAX_BYTES + 1)))
    }

    @Test
    fun hostileFileNamesAreMadeSafeForDisplay() {
        assertEquals("etcpasswd", V2Text.sanitizeName("../../etc/passwd"))
        assertEquals("name.txt", V2Text.sanitizeName("name\u0000.txt"))
        assertEquals("filegpj.exe", V2Text.sanitizeName("file\u202Egpj.exe"))
        assertEquals("ab", V2Text.sanitizeName("a\u200Bb"))
        assertEquals("ab", V2Text.sanitizeName("a\u2066b\u2069"))
        assertEquals("file", V2Text.sanitizeName("..."))
        assertEquals("file", V2Text.sanitizeName("\u0007\u0001"))
        assertEquals("ab", V2Text.sanitizeName("a\tb"))
        assertEquals("a b", V2Text.sanitizeName("a b"))
        assertEquals("caf\u00e9", V2Text.sanitizeName("cafe\u0301"))
        assertEquals("C:Windowsx", V2Text.sanitizeName("C:\\Windows\\x"))
        val long = V2Text.sanitizeName("x".repeat(10_000))
        assertTrue(long.toByteArray().size <= MessagingV2Format.NAME_MAX_BYTES)
        val multibyte = V2Text.sanitizeName("\u00e9".repeat(200))
        assertTrue(multibyte.toByteArray(Charsets.UTF_8).size <= MessagingV2Format.NAME_MAX_BYTES)
        assertTrue(multibyte.all { it == '\u00e9' })
    }

    @Test
    fun aLyingMimeTypeFallsBackToOctetStream() {
        assertEquals("image/png", V2Text.sanitizeMime("image/png".toByteArray()))
        assertEquals("application/vnd.android.package-archive", V2Text.sanitizeMime("application/vnd.android.package-archive".toByteArray()))
        listOf("", "png", "IMAGE/PNG", "image/", "/png", "image/png; charset=x", "image /png", "a/b/c", "\u00e9/x").forEach {
            assertEquals(it, MessagingV2Format.DEFAULT_MIME, V2Text.sanitizeMime(it.toByteArray(Charsets.UTF_8)))
        }
        assertEquals(MessagingV2Format.DEFAULT_MIME, V2Text.sanitizeMime(("a/" + "b".repeat(100)).toByteArray()))
        assertEquals(MessagingV2Format.DEFAULT_MIME, V2Text.sanitizeMime(byteArrayOf(0x69, 0x2f, 0x01)))
    }

    @Test
    fun receiptBodiesHoldOneStatusAndUpToSixtyFourIds() {
        val body = ReceiptBody(ReceiptStatus.DOWNLOADED, 123_456, listOf(id32(1), id32(2)))
        val decoded = ReceiptBody.decode(body.encode())
        assertEquals(ReceiptStatus.DOWNLOADED, decoded.status)
        assertEquals(123_456, decoded.receivedHour)
        assertEquals(2, decoded.bundleIds.size)
        assertEquals(ReceiptBody(ReceiptStatus.READ, 1, List(64) { id32(it) }).encode().size, 6 + 64 * 32)
        assertThrows(IllegalArgumentException::class.java) { ReceiptBody(ReceiptStatus.READ, 1, List(65) { id32(it) }) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptBody(ReceiptStatus.READ, 1, emptyList()) }
        val good = body.encode()
        assertThrows(MessageV2Invalid::class.java) { ReceiptBody.decode(good + 0) }
        assertThrows(MessageV2Invalid::class.java) { ReceiptBody.decode(good.copyOf(good.size - 1)) }
        assertThrows(MessageV2Invalid::class.java) { ReceiptBody.decode(good.copyOf().also { it[0] = 9 }) }
        assertThrows(MessageV2Invalid::class.java) { ReceiptBody.decode(good.copyOf().also { it[1] = 0 }) }
        assertThrows(MessageV2Invalid::class.java) { ReceiptBody.decode(good.copyOf().also { it[1] = 65 }) }
        assertEquals(ReceiptStatus.KEY_GONE, ReceiptBody.decode(good.copyOf().also { it[0] = 4 }).status)
    }

    @Test
    fun segmentBodiesCarryTheFileIdAndIndex() {
        val data = ByteArray(100) { it.toByte() }
        val decoded = SegmentBody.decode(SegmentBody(id16(3), 7, data).encode())
        assertArrayEquals(id16(3), decoded.fileId)
        assertEquals(7, decoded.index)
        assertArrayEquals(data, decoded.data)
        assertThrows(MessageV2Invalid::class.java) { SegmentBody.decode(ByteArray(18)) }
    }

    @Test
    fun theFeatureResolverReadsOnlyItsExtraInABenchmarkLaunchAndNeedsMessages() {
        val extraOn = { key: String -> if (key == MessagingV2Feature.EXTRA) true else null }
        val extraOff = { _: String -> false }
        val noExtra = { _: String -> null as Boolean? }
        assertTrue("design decision 2026-10-21: Messages on means v2", MessagingV2Defaults.ENABLED)
        assertTrue(MessagingV2Feature.resolve(true, extraOn, { true }, messages = true))
        assertFalse("the DEBUG control arm test_messaging_v2=false is v1", MessagingV2Feature.resolve(true, extraOff, { true }, messages = true))
        assertTrue("a benchmark launch without the extra follows the default", MessagingV2Feature.resolve(true, noExtra, { false }, messages = true))
        assertFalse("needs Messages", MessagingV2Feature.resolve(true, extraOn, { null }, messages = false))
        assertFalse("needs Messages, also by default", MessagingV2Feature.resolve(false, noExtra, { null }, messages = false))
        assertTrue("shipped default", MessagingV2Feature.resolve(false, { true }, { null }, messages = true))
        assertFalse("a user's own Messages v2 off is kept", MessagingV2Feature.resolve(false, { true }, { false }, messages = true))
    }
}
