package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the byte-exact vectors of `tools/messaging_v2_reference.py` (an independent implementation of the document). */
class MessagingV2VectorTest {
    private fun ident(start: Int, n: Int) = ByteArray(n) { (start + it).toByte() }
    private val created = 1_790_000_000_123L
    private val fileId = ident(0xa0, 16)
    private val fileKey = ident(0, 32)

    @Test
    fun contentVectorsEncodeAndDecodeToTheReferenceBytes() {
        val text = MessageV2(V2Kind.TEXT, 1, created, ident(1, 16), ident(0x20, 16), null, 0, "hello v2".toByteArray())
        assertEquals(MessagingV2Vectors.BSM2_TEXT, text.encode().sfHex())
        assertEquals("hello v2", String(MessageV2.decode(hexToBytes(MessagingV2Vectors.BSM2_TEXT)).body))
        val reply = MessageV2(V2Kind.TEXT, 0, created, ident(1, 16), ident(0x20, 16), ident(0x90, 16), 3600, "café".toByteArray())
        assertEquals(MessagingV2Vectors.BSM2_TEXT_REPLY, reply.encode().sfHex())
        val decoded = MessageV2.decode(hexToBytes(MessagingV2Vectors.BSM2_TEXT_REPLY))
        assertArrayEquals(ident(0x90, 16), decoded.replyTo)
        assertEquals(3600, decoded.disappearSeconds)

        val hello = "hello world".toByteArray()
        val inline = AttachmentManifest(
            fileId, Presentation.VOICE, hello.size.toLong(), sfSha256(hello), fileKey, "note.m4a", "audio/mp4", 0, 0, 4200,
            ByteArray(0), hello, emptyList(),
        )
        assertEquals(MessagingV2Vectors.MANIFEST_INLINE, inline.encode().sfHex())
        val parsed = AttachmentManifest.decode(hexToBytes(MessagingV2Vectors.MANIFEST_INLINE))
        assertArrayEquals(hello, parsed.inlineData)
        assertEquals(4200, parsed.durationMs)

        val ids = MessagingV2Vectors.MANIFEST_SEGMENT_IDS.split(",").map(::hexToBytes)
        val chunks = MessagingV2Vectors.MANIFEST_SEGMENT_CHUNKS.split(",").map(String::toInt)
        val segmented = AttachmentManifest(
            fileId, Presentation.FILE, 2_000_000, sfSha256("two segments".toByteArray()), fileKey, "archive.zip", "application/zip",
            0, 0, 0, ByteArray(0), null, ids.indices.map { SegmentRef(ids[it], chunks[it]) },
        )
        assertEquals(MessagingV2Vectors.MANIFEST_SEGMENTED, segmented.encode().sfHex())
        val back = AttachmentManifest.decode(hexToBytes(MessagingV2Vectors.MANIFEST_SEGMENTED))
        assertEquals(chunks, back.segments.map { it.chunkCount })
        assertEquals(chunks[0], SegmentMath.chunkCount(MessagingV2Format.SEGMENT_DATA_BYTES))
        assertEquals(chunks[1], SegmentMath.chunkCount(2_000_000 - MessagingV2Format.SEGMENT_DATA_BYTES))

        val receipt = ReceiptBody(ReceiptStatus.DOWNLOADED, 123_456, listOf(ident(0x10, 32), ident(0x50, 32)))
        assertEquals(MessagingV2Vectors.RECEIPT, receipt.encode().sfHex())
        assertEquals(2, ReceiptBody.decode(hexToBytes(MessagingV2Vectors.RECEIPT)).bundleIds.size)
    }

    @Test
    fun segmentKeysAndPaddingMatchTheReference() {
        assertEquals(MessagingV2Vectors.SEGMENT_KEY_0, SegmentMath.segmentKey(fileKey, fileId, 0).sfHex())
        assertEquals(MessagingV2Vectors.SEGMENT_KEY_1, SegmentMath.segmentKey(fileKey, fileId, 1).sfHex())
        assertEquals(MessagingV2Vectors.SEGMENT_KEY_61, SegmentMath.segmentKey(fileKey, fileId, 61).sfHex())
        MessagingV2Vectors.PADDED_SAMPLES.split(",").forEach { sample ->
            val (n, padded) = sample.split(":").map(String::toInt)
            if (n + 4 <= StoreForwardFormat.MAX_PADDED_BYTES) {
                assertEquals("padded length of $n", padded, StoreForwardFormat.paddedLength(n))
            }
        }
        MessagingV2Vectors.SEGMENT_PADDED_SAMPLES.split(",").forEach { sample ->
            val (n, padded) = sample.split(":").map(String::toInt)
            assertEquals("segment padded length of $n", padded, SegmentMath.paddedLength(n))
        }
    }

    @Test
    fun theBulkBundleSealsToTheReferenceHeaderAndChunks() {
        val inner = hexToBytes(MessagingV2Vectors.BULK_INNER)
        val data = ByteArray(MessagingV2Vectors.BULK_DATA_LENGTH.toInt()) { ((it * 7) and 0xff).toByte() }
        val body = MessageV2.decode(inner)
        assertEquals(V2Kind.SEGMENT, body.kind)
        val segment = SegmentBody.decode(body.body)
        assertEquals(1, segment.index)
        assertArrayEquals(data, segment.data)
        val padded = MessagingV2Vectors.BULK_PADDED.toInt()
        assertEquals(padded, SegmentMath.paddedLength(data.size))
        val sealed = StoreForwardSeal.sealBulk(
            inner, padded, SegmentMath.segmentKey(fileKey, fileId, 1), ident(0xc0, 8), MessagingV2Vectors.BULK_CREATED_MS.toLong(), 336,
        )
        assertEquals(MessagingV2Vectors.BULK_HEADER, sealed.header.encoded.sfHex())
        assertEquals(MessagingV2Vectors.BULK_BUNDLE_ID, sealed.header.bundleId.sfHex())
        assertEquals(MessagingV2Vectors.BULK_CHUNKS, sealed.chunks.joinToString(",") { it.sfHex() })
        // The reference bytes decode and open in the app too.
        val header = BundleHeader.decode(hexToBytes(MessagingV2Vectors.BULK_HEADER), allowV3 = true)
        assertTrue(header.isBulk)
        val opened = StoreForwardSeal.openBulk(
            header, MessagingV2Vectors.BULK_CHUNKS.split(",").map(::hexToBytes), SegmentMath.segmentKey(fileKey, fileId, 1),
        )
        assertArrayEquals(inner, opened)
    }

    @Test
    fun wireVectorsMatchTheReference() {
        val digest = ByteArray(8) { (0x30 + it).toByte() }
        assertEquals(MessagingV2Vectors.HELLO_V1, StoreForwardWire.encode(StoreForwardMessage.Hello(1, 0x01020304, 0x0a0b0c0d, digest)).sfHex())
        assertEquals(MessagingV2Vectors.HELLO_V2, StoreForwardWire.encode(StoreForwardMessage.Hello(2, 5, 6, digest, 3, 7)).sfHex())
        assertEquals(MessagingV2Vectors.CANCEL, StoreForwardWire.encode(StoreForwardMessage.Cancel(ident(0, 32))).sfHex())
        assertEquals(MessagingV2Vectors.PING, StoreForwardWire.encode(StoreForwardMessage.Ping).sfHex())
        assertEquals(MessagingV2Vectors.DONE_IDLE, StoreForwardWire.encode(StoreForwardMessage.Done(StoreForwardDoneReason.IDLE)).sfHex())
        assertEquals(
            MessagingV2Vectors.REFUSE_PREKEY_GONE,
            StoreForwardWire.encode(StoreForwardMessage.Reply(ident(0, 32), StoreForwardRefusal.PREKEY_GONE)).sfHex(),
        )
        val hello = StoreForwardWire.decode(hexToBytes(MessagingV2Vectors.HELLO_V2), v2 = true) as StoreForwardMessage.Hello
        assertEquals(3, hello.flags)
        assertEquals(7, hello.prekeysHeld)
    }
}
