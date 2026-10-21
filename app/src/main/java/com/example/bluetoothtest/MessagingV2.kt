package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.text.Normalizer

/*
 * Messaging v2 (DECISIONS Design M, PROTOCOL "Messaging v2"), phase 1a core: content `BSM2`, the
 * attachment manifest, receipts and the pure helpers around them. Experimental and default off:
 * with [MessagingV2Feature.enabled] false nothing in this file is reached and the bytes on air are
 * exactly v1's (bundle version 2, `BSM1`, HELLO version 1).
 */

/** The shipped default is one constant, flipped only after the promotion criteria in DECISIONS Design M. */
object MessagingV2Defaults {
    /**
     * Design decision 2026-10-21: with Messages on, chat uses Messaging v2. v1 history stays readable as legacy rows and unsent v1
     * messages are resealed once ([MessagingV2Migration]); a contact on an older app gets the "Their app is older" state
     * ([ConversationLinkState.PEER_APP_OLDER]). Control arm: DEBUG `test_messaging_v2=false`, or Settings "Messages v2" off.
     */
    const val ENABLED = true
}

/**
 * Master switch `test_messaging_v2` (Settings > Features "Messages v2" is phase 1c). Needs Messages. A DEBUG
 * benchmark launch reads only its own extra (default off), so a stored choice never leaks into a measured case.
 */
object MessagingV2Feature {
    const val EXTRA = "test_messaging_v2"
    const val PREF = "feature_messaging_v2"

    @Volatile var enabled = false

    /** [extra] is null when the launch did not pass `test_messaging_v2`: a benchmark launch then follows the default too. */
    internal fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean?,
        stored: (String) -> Boolean?,
        messages: Boolean,
    ): Boolean = messages && (if (benchmarkLaunch) extra(EXTRA) else stored(PREF)) ?: (messages && MessagingV2Defaults.ENABLED)
}

object MessagingV2Format {
    const val TEXT_MAX_BYTES = 8_000
    /** A file of at most this size travels inside its manifest bundle. */
    const val INLINE_MAX_BYTES = 1_000_000
    const val BLOCK_BYTES = 4_096
    const val SEGMENT_BLOCKS = 255
    /** 255 blocks of 4 KiB: block aligned in the vault and inside 128 chunks. */
    const val SEGMENT_DATA_BYTES = BLOCK_BYTES * SEGMENT_BLOCKS
    const val MAX_FILE_BYTES = 64_000_000L
    const val MAX_SEGMENTS = 62
    const val NAME_MAX_BYTES = 96
    const val MIME_MAX_BYTES = 100
    const val THUMBNAIL_MAX_BYTES = 16_384
    const val THUMBNAIL_MAX_EDGE = 320
    const val RECEIPT_MAX_IDS = 64
    const val MESSAGE_ID_BYTES = 16
    const val FILE_ID_BYTES = 16
    const val FILE_KEY_BYTES = 32
    const val MAX_DISAPPEAR_SECONDS = 31_536_000
    const val DISAPPEAR_FLOOR_SECONDS = 3_600
    /** Delivery lifetimes in hours (PROTOCOL: text and inline 3 days, segmented files 14 days). */
    const val TEXT_TTL_HOURS = 72
    const val SEGMENTED_TTL_HOURS = 14 * 24
    const val WANT_READ_RECEIPT = 1
    const val RESERVED_FLAG_MASK = 0xfe
    val SEGMENT_CONTEXT: ByteArray = "BluetoothVideoMesh/StoreForward/Segment/1".toByteArray(Charsets.US_ASCII)
    val DEFAULT_MIME = "application/octet-stream"
}

enum class V2Kind(val wireValue: Int) {
    TEXT(1),
    LOCATION(5),
    RECEIPT(6),
    ATTACHMENT(8),
    SEGMENT(9),
    /** Phase 2: known so it is refused with a reason, never stored. */
    PREKEYS(10),
    ;

    companion object {
        fun fromWire(value: Int): V2Kind =
            entries.firstOrNull { it.wireValue == value } ?: throw MessageV2Invalid("Unknown BSM2 kind $value")
    }
}

/** A BSM2 / manifest / receipt that violates the format: the bundle is refused `invalid`, nothing is stored. */
class MessageV2Invalid(message: String) : IllegalArgumentException(message)

/**
 * `BSM2 | kind | flags | created ms | message ID | conversation ID | reply-to message ID | disappear seconds |
 * body length | body` (PROTOCOL "Content BSM2"). The sender is authenticated by the key slot, not named here.
 */
class MessageV2(
    val kind: V2Kind,
    val flags: Int,
    val createdMs: Long,
    val messageId: ByteArray,
    val conversationId: ByteArray,
    val replyTo: ByteArray?,
    val disappearSeconds: Int,
    val body: ByteArray,
) {
    init {
        require(flags in 0..0xff) { "Invalid flags" }
        require(messageId.size == MessagingV2Format.MESSAGE_ID_BYTES) { "Message ID must be 16 bytes" }
        require(conversationId.size == 16) { "Conversation ID must be 16 bytes" }
        require(replyTo == null || replyTo.size == MessagingV2Format.MESSAGE_ID_BYTES) { "Invalid reply-to" }
        require(disappearSeconds in 0..MessagingV2Format.MAX_DISAPPEAR_SECONDS) { "Invalid disappear timer" }
    }

    val wantsReadReceipt: Boolean get() = flags and MessagingV2Format.WANT_READ_RECEIPT != 0
    val messageIdHex: String get() = messageId.sfHex()

    fun encode(): ByteArray = ByteArrayOutputStream(HEADER_BYTES + body.size).also { bytes ->
        DataOutputStream(bytes).apply {
            write(MAGIC)
            writeByte(kind.wireValue)
            writeByte(flags)
            writeLong(createdMs)
            write(messageId)
            write(conversationId)
            write(replyTo ?: ByteArray(MessagingV2Format.MESSAGE_ID_BYTES))
            writeInt(disappearSeconds)
            writeInt(body.size)
            write(body)
        }
    }.toByteArray()

    companion object {
        private val MAGIC = "BSM2".toByteArray(Charsets.US_ASCII)
        /** 4 + 1 + 1 + 8 + 16 + 16 + 16 + 4 + 4. */
        const val HEADER_BYTES = 70

        /** Strict decode. A set reserved flag bit refuses the whole message (fail safe). */
        fun decode(bytes: ByteArray): MessageV2 {
            if (bytes.size < HEADER_BYTES) throw MessageV2Invalid("BSM2 is too short")
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(4).also(input::readFully)
            if (!magic.contentEquals(MAGIC)) throw MessageV2Invalid("Invalid BSM2 magic")
            val kind = V2Kind.fromWire(input.readUnsignedByte())
            val flags = input.readUnsignedByte()
            if (flags and MessagingV2Format.RESERVED_FLAG_MASK != 0) throw MessageV2Invalid("Reserved BSM2 flag bits set")
            val created = input.readLong()
            val messageId = ByteArray(MessagingV2Format.MESSAGE_ID_BYTES).also(input::readFully)
            val conversation = ByteArray(16).also(input::readFully)
            val reply = ByteArray(MessagingV2Format.MESSAGE_ID_BYTES).also(input::readFully)
            val disappear = input.readInt()
            if (disappear !in 0..MessagingV2Format.MAX_DISAPPEAR_SECONDS) throw MessageV2Invalid("Invalid disappear timer")
            val bodySize = input.readInt()
            if (bodySize < 0 || bodySize != input.available()) throw MessageV2Invalid("Invalid BSM2 body length")
            val body = ByteArray(bodySize).also(input::readFully)
            return MessageV2(
                kind, flags, created, messageId, conversation, reply.takeUnless { r -> r.all { it.toInt() == 0 } },
                disappear, body,
            )
        }
    }
}

/** Conversation ID: `SHA-256(sorted hex node IDs)[0:16]`, as v1 derives it. */
object ConversationIds {
    fun of(a: NodeId, b: NodeId): ByteArray =
        sfSha256(listOf(a.toString(), b.toString()).sorted().joinToString("").toByteArray(Charsets.US_ASCII)).copyOf(16)
}

enum class Presentation(val wireValue: Int) {
    FILE(0),
    PICTURE(1),
    VOICE(2),
    VIDEO(3),
    ;

    /** Priority class of an inline attachment (PROTOCOL: voice 1, picture 2, video 3, other files 2). */
    val inlineClass: Int
        get() = when (this) {
            VOICE -> 1
            PICTURE, FILE -> 2
            VIDEO -> 3
        }

    companion object {
        fun fromWire(value: Int): Presentation =
            entries.firstOrNull { it.wireValue == value } ?: throw MessageV2Invalid("Unknown presentation $value")
    }
}

/** One segment listed by a manifest: the class 4 bundle's ID and the chunk count it must have. */
class SegmentRef(val bundleId: ByteArray, val chunkCount: Int) {
    init {
        require(bundleId.size == StoreForwardFormat.BUNDLE_ID_BYTES)
    }

    val idHex: String get() = bundleId.sfHex()
}

/**
 * Segment arithmetic (PROTOCOL "ATTACHMENT body", "SEGMENT body"): a full segment is exactly 1,044,480 data bytes
 * and is not padded; the last one is padded like any message.
 */
object SegmentMath {
    /** `file ID (16) + index (2)` in front of the data. */
    const val SEGMENT_PREFIX_BYTES = MessagingV2Format.FILE_ID_BYTES + 2

    fun count(total: Long): Int = ((total + MessagingV2Format.SEGMENT_DATA_BYTES - 1) / MessagingV2Format.SEGMENT_DATA_BYTES).toInt()

    fun dataLength(index: Int, total: Long): Int {
        val segments = count(total)
        require(index in 0 until segments) { "Segment index out of range" }
        return if (index < segments - 1) MessagingV2Format.SEGMENT_DATA_BYTES
        else (total - index.toLong() * MessagingV2Format.SEGMENT_DATA_BYTES).toInt()
    }

    fun innerLength(dataLength: Int): Int = MessageV2.HEADER_BYTES + SEGMENT_PREFIX_BYTES + dataLength

    fun paddedLength(dataLength: Int): Int =
        if (dataLength == MessagingV2Format.SEGMENT_DATA_BYTES) innerLength(dataLength) + 4
        else StoreForwardFormat.paddedLength(innerLength(dataLength))

    fun chunkCount(dataLength: Int): Int = StoreForwardFormat.chunkCount(paddedLength(dataLength))

    /** `HKDF-SHA256(file key, salt = file ID, info = ".../Segment/1" || index)`. */
    fun segmentKey(fileKey: ByteArray, fileId: ByteArray, index: Int): ByteArray {
        require(fileKey.size == MessagingV2Format.FILE_KEY_BYTES && fileId.size == MessagingV2Format.FILE_ID_BYTES)
        require(index in 0 until MessagingV2Format.MAX_SEGMENTS)
        return sfHkdf(fileKey, fileId, MessagingV2Format.SEGMENT_CONTEXT + byteArrayOf((index ushr 8).toByte(), index.toByte()), 32)
    }

    /** Block (4 KiB) index of segment [index] in the vault file. */
    fun firstBlock(index: Int): Int = index * MessagingV2Format.SEGMENT_BLOCKS
}

/** Display-safe names and MIME claims (PROTOCOL "ATTACHMENT body"). */
object V2Text {
    private val MIME = Regex("[a-z0-9][a-z0-9!#\$&^_.+-]*/[a-z0-9][a-z0-9!#\$&^_.+-]*")

    /**
     * NFC, no path separators, NUL, control (Cc) or format (Cf) characters (bidirectional embeddings, overrides and
     * isolates, zero-width), no leading dots, at most 96 UTF-8 bytes; an empty result is "file". A label only: files
     * are stored under a local random ID.
     */
    fun sanitizeName(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val builder = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            index += Character.charCount(codePoint)
            val type = Character.getType(codePoint)
            val forbidden = codePoint == '/'.code || codePoint == '\\'.code || codePoint == 0 ||
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                type == Character.UNASSIGNED.toInt() || type == Character.SURROGATE.toInt() || type == Character.PRIVATE_USE.toInt()
            if (!forbidden) builder.appendCodePoint(codePoint)
        }
        var text = builder.toString().trimStart('.', ' ')
        while (text.toByteArray(Charsets.UTF_8).size > MessagingV2Format.NAME_MAX_BYTES) {
            text = text.substring(0, text.offsetByCodePoints(text.length, -1))
        }
        return text.trimEnd(' ').ifEmpty { "file" }
    }

    /** The sender's claimed type if it is `type/subtype` in the allowed alphabet, otherwise octet-stream. */
    fun sanitizeMime(raw: ByteArray): String {
        if (raw.isEmpty() || raw.size > MessagingV2Format.MIME_MAX_BYTES || raw.any { it < 0x21 || it > 0x7e }) {
            return MessagingV2Format.DEFAULT_MIME
        }
        val text = String(raw, Charsets.US_ASCII)
        return if (MIME.matches(text)) text else MessagingV2Format.DEFAULT_MIME
    }
}

/** Header-only JPEG check for the manifest thumbnail: never decodes pixels. */
object JpegHeader {
    /** Width and height from the first SOF marker, or null when it is not a well-formed JPEG header. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return null
        var offset = 2
        while (offset + 4 <= bytes.size) {
            if (bytes[offset] != 0xff.toByte()) return null
            val marker = bytes[offset + 1].toInt() and 0xff
            if (marker == 0xff) {
                offset++
                continue
            }
            if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) {
                offset += 2
                continue
            }
            val length = ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
            if (length < 2 || offset + 2 + length > bytes.size) return null
            val isSof = marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
            if (isSof) {
                if (length < 8) return null
                val height = ((bytes[offset + 5].toInt() and 0xff) shl 8) or (bytes[offset + 6].toInt() and 0xff)
                val width = ((bytes[offset + 7].toInt() and 0xff) shl 8) or (bytes[offset + 8].toInt() and 0xff)
                return if (width > 0 && height > 0) width to height else null
            }
            if (marker == 0xda || marker == 0xd9) return null
            offset += 2 + length
        }
        return null
    }

    fun acceptableThumbnail(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MessagingV2Format.THUMBNAIL_MAX_BYTES) return false
        val (w, h) = dimensions(bytes) ?: return false
        return w <= MessagingV2Format.THUMBNAIL_MAX_EDGE && h <= MessagingV2Format.THUMBNAIL_MAX_EDGE
    }
}

/**
 * ATTACHMENT body (PROTOCOL): inline data, or a list of the class 4 segment bundles. The file key is the root of
 * every segment key and belongs in a separate transfer record, never in the archived manifest.
 */
class AttachmentManifest(
    val fileId: ByteArray,
    val presentation: Presentation,
    val totalSize: Long,
    val sha256: ByteArray,
    val fileKey: ByteArray,
    val name: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val thumbnail: ByteArray,
    /** Non-null exactly when the file is inline. */
    val inlineData: ByteArray?,
    val segments: List<SegmentRef>,
) {
    init {
        require(fileId.size == MessagingV2Format.FILE_ID_BYTES) { "File ID must be 16 bytes" }
        require(sha256.size == 32) { "SHA-256 must be 32 bytes" }
        require(fileKey.size == MessagingV2Format.FILE_KEY_BYTES) { "File key must be 32 bytes" }
        require(totalSize in 1..MessagingV2Format.MAX_FILE_BYTES) { "Invalid total size" }
        if (inlineData != null) {
            require(totalSize <= MessagingV2Format.INLINE_MAX_BYTES && inlineData.size.toLong() == totalSize && segments.isEmpty()) {
                "Invalid inline attachment"
            }
        } else {
            require(segments.size == SegmentMath.count(totalSize)) { "Segment count does not match the size" }
        }
    }

    val inline: Boolean get() = inlineData != null
    val fileIdHex: String get() = fileId.sfHex()

    fun encode(): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        require(nameBytes.size <= MessagingV2Format.NAME_MAX_BYTES && mimeBytes.size <= MessagingV2Format.MIME_MAX_BYTES)
        require(thumbnail.size <= MessagingV2Format.THUMBNAIL_MAX_BYTES)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                write(fileId)
                writeByte(presentation.wireValue)
                writeLong(totalSize)
                writeByte(segments.size)
                write(sha256)
                write(if (inline) ByteArray(MessagingV2Format.FILE_KEY_BYTES) else fileKey)
                writeByte(nameBytes.size)
                write(nameBytes)
                writeByte(mimeBytes.size)
                write(mimeBytes)
                writeShort(width.coerceIn(0, 0xffff))
                writeShort(height.coerceIn(0, 0xffff))
                writeInt(durationMs.coerceIn(0, MAX_DURATION_MS).toInt())
                writeShort(thumbnail.size)
                write(thumbnail)
                if (inline) write(requireNotNull(inlineData)) else segments.forEach {
                    write(it.bundleId)
                    writeShort(it.chunkCount)
                }
            }
        }.toByteArray()
    }

    companion object {
        const val MAX_DURATION_MS = 86_400_000L

        /** Strict decode of an ATTACHMENT body; throws [MessageV2Invalid] for anything off-spec. */
        fun decode(body: ByteArray): AttachmentManifest {
            val input = DataInputStream(ByteArrayInputStream(body))
            try {
                val fileId = ByteArray(MessagingV2Format.FILE_ID_BYTES).also(input::readFully)
                val presentation = Presentation.fromWire(input.readUnsignedByte())
                val total = input.readLong()
                if (total !in 1..MessagingV2Format.MAX_FILE_BYTES) throw MessageV2Invalid("Attachment size out of range")
                val segmentCount = input.readUnsignedByte()
                val sha = ByteArray(32).also(input::readFully)
                val key = ByteArray(MessagingV2Format.FILE_KEY_BYTES).also(input::readFully)
                val nameLength = input.readUnsignedByte()
                if (nameLength > MessagingV2Format.NAME_MAX_BYTES) throw MessageV2Invalid("Attachment name too long")
                val rawName = ByteArray(nameLength).also(input::readFully)
                val mimeLength = input.readUnsignedByte()
                if (mimeLength > MessagingV2Format.MIME_MAX_BYTES) throw MessageV2Invalid("Attachment MIME too long")
                val rawMime = ByteArray(mimeLength).also(input::readFully)
                val width = input.readUnsignedShort()
                val height = input.readUnsignedShort()
                val duration = input.readInt().toLong() and 0xffffffffL
                val thumbLength = input.readUnsignedShort()
                if (thumbLength > MessagingV2Format.THUMBNAIL_MAX_BYTES) throw MessageV2Invalid("Attachment thumbnail too large")
                val rawThumb = ByteArray(thumbLength).also(input::readFully)
                val inlineData: ByteArray?
                val segments: List<SegmentRef>
                if (segmentCount == 0) {
                    if (total > MessagingV2Format.INLINE_MAX_BYTES) throw MessageV2Invalid("Inline attachment is too large")
                    if (input.available().toLong() != total) throw MessageV2Invalid("Inline data length mismatch")
                    inlineData = ByteArray(total.toInt()).also(input::readFully)
                    segments = emptyList()
                } else {
                    if (segmentCount != SegmentMath.count(total)) throw MessageV2Invalid("Segment count does not match the size")
                    if (input.available() != segmentCount * 34) throw MessageV2Invalid("Segment list length mismatch")
                    inlineData = null
                    segments = List(segmentCount) { index ->
                        val id = ByteArray(StoreForwardFormat.BUNDLE_ID_BYTES).also(input::readFully)
                        val chunks = input.readUnsignedShort()
                        if (chunks != SegmentMath.chunkCount(SegmentMath.dataLength(index, total))) {
                            throw MessageV2Invalid("Segment $index chunk count does not match")
                        }
                        SegmentRef(id, chunks)
                    }
                    if (segments.map { it.idHex }.toSet().size != segments.size) throw MessageV2Invalid("Duplicate segment IDs")
                }
                if (input.available() != 0) throw MessageV2Invalid("Trailing manifest data")
                return AttachmentManifest(
                    fileId, presentation, total, sha, key,
                    V2Text.sanitizeName(String(rawName, Charsets.UTF_8)),
                    V2Text.sanitizeMime(rawMime),
                    width, height, duration.coerceAtMost(MAX_DURATION_MS),
                    // A thumbnail that fails any check is ignored, not an error (PROTOCOL).
                    if (JpegHeader.acceptableThumbnail(rawThumb)) rawThumb else ByteArray(0),
                    inlineData, segments,
                )
            } catch (error: MessageV2Invalid) {
                throw error
            } catch (error: java.io.IOException) {
                throw MessageV2Invalid("Truncated manifest")
            } catch (error: IllegalArgumentException) {
                throw MessageV2Invalid(error.message ?: "Invalid manifest")
            }
        }
    }
}

/** Receipt statuses (PROTOCOL "RECEIPT"). */
enum class ReceiptStatus(val wireValue: Int) {
    DELIVERED(1),
    READ(2),
    DOWNLOADED(3),
    /** Phase 2: the recipient's key for the bundle was already deleted. */
    KEY_GONE(4),
    ;

    companion object {
        fun fromWire(value: Int): ReceiptStatus =
            entries.firstOrNull { it.wireValue == value } ?: throw MessageV2Invalid("Unknown receipt status $value")
    }
}

/** `status (1) | count (1) | received hour (4) | bundle ID (32) x count`. */
class ReceiptBody(val status: ReceiptStatus, val receivedHour: Int, val bundleIds: List<ByteArray>) {
    init {
        require(bundleIds.size in 1..MessagingV2Format.RECEIPT_MAX_IDS) { "A receipt names 1 to 64 bundles" }
        require(bundleIds.all { it.size == StoreForwardFormat.BUNDLE_ID_BYTES })
    }

    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeByte(status.wireValue)
            writeByte(bundleIds.size)
            writeInt(receivedHour)
            bundleIds.forEach(::write)
        }
    }.toByteArray()

    companion object {
        fun decode(body: ByteArray): ReceiptBody {
            if (body.size < 6) throw MessageV2Invalid("Receipt is too short")
            val input = DataInputStream(ByteArrayInputStream(body))
            val status = ReceiptStatus.fromWire(input.readUnsignedByte())
            val count = input.readUnsignedByte()
            if (count !in 1..MessagingV2Format.RECEIPT_MAX_IDS) throw MessageV2Invalid("Receipt count out of range")
            val hour = input.readInt()
            if (input.available() != count * StoreForwardFormat.BUNDLE_ID_BYTES) throw MessageV2Invalid("Receipt length mismatch")
            return ReceiptBody(status, hour, List(count) { ByteArray(StoreForwardFormat.BUNDLE_ID_BYTES).also(input::readFully) })
        }
    }
}

/** SEGMENT body: `file ID (16) | index (2) | data`. */
class SegmentBody(val fileId: ByteArray, val index: Int, val data: ByteArray) {
    fun encode(): ByteArray = ByteArrayOutputStream(SegmentMath.SEGMENT_PREFIX_BYTES + data.size).also { bytes ->
        DataOutputStream(bytes).apply {
            write(fileId)
            writeShort(index)
            write(data)
        }
    }.toByteArray()

    companion object {
        fun decode(body: ByteArray): SegmentBody {
            if (body.size < SegmentMath.SEGMENT_PREFIX_BYTES + 1) throw MessageV2Invalid("Segment is too short")
            val fileId = body.copyOf(MessagingV2Format.FILE_ID_BYTES)
            val index = ((body[16].toInt() and 0xff) shl 8) or (body[17].toInt() and 0xff)
            return SegmentBody(fileId, index, body.copyOfRange(SegmentMath.SEGMENT_PREFIX_BYTES, body.size))
        }
    }
}
