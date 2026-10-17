package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/*
 * Messaging v2 phase 1b: content sniffing (DECISIONS Design M3 "Formats", findings M-R13 and 10). The MIME type a sender
 * claims is only a hint; the bytes decide. The sniffed type overrides the claim for the icon, for the decode decision and
 * for the type handed to another app. Everything here is pure and bounded: it reads at most a few KiB from the start of
 * the file and at most 64 KiB from its end, and never decodes pixels, audio or video.
 */

/** Where the bytes of a file are read from (a byte array in tests, the file vault, or a seekable descriptor). */
interface SniffSource {
    val size: Long

    /** Up to [length] bytes from [offset]; fewer at the end of the file; empty past it. */
    fun read(offset: Long, length: Int): ByteArray
}

class BytesSniffSource(private val bytes: ByteArray) : SniffSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size || length <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

/** What a file is, from its bytes. */
enum class SniffCategory {
    PICTURE, AUDIO, VIDEO, PDF, TEXT,

    /** HTML, SVG, XML: text that other apps may run. */
    MARKUP,
    ARCHIVE,

    /** Office documents with macros or embedded objects, RTF. */
    OFFICE,
    INSTALLABLE, EXECUTABLE, SCRIPT, UNKNOWN,
    ;

    /** "Open" exists only for pictures, audio, video, PDF and plain text (Q-M7). */
    val openAllowed: Boolean get() = this == PICTURE || this == AUDIO || this == VIDEO || this == PDF || this == TEXT
    val dangerous: Boolean get() = this == INSTALLABLE || this == EXECUTABLE || this == SCRIPT
}

enum class SniffedType(val mime: String, val category: SniffCategory, val extension: String?) {
    JPEG("image/jpeg", SniffCategory.PICTURE, "jpg"),
    PNG("image/png", SniffCategory.PICTURE, "png"),
    GIF("image/gif", SniffCategory.PICTURE, "gif"),
    WEBP("image/webp", SniffCategory.PICTURE, "webp"),
    HEIF("image/heic", SniffCategory.PICTURE, "heic"),
    AVIF("image/avif", SniffCategory.PICTURE, "avif"),
    BMP("image/bmp", SniffCategory.PICTURE, "bmp"),
    MP3("audio/mpeg", SniffCategory.AUDIO, "mp3"),
    AAC("audio/aac", SniffCategory.AUDIO, "aac"),
    OGG("audio/ogg", SniffCategory.AUDIO, "ogg"),
    FLAC("audio/flac", SniffCategory.AUDIO, "flac"),
    WAV("audio/wav", SniffCategory.AUDIO, "wav"),
    AMR("audio/amr", SniffCategory.AUDIO, "amr"),
    MIDI("audio/midi", SniffCategory.AUDIO, "mid"),
    M4A("audio/mp4", SniffCategory.AUDIO, "m4a"),

    /** An ISO base media file (MP4, 3GP): whether it is only audio is decided by its presentation hint. */
    MP4("video/mp4", SniffCategory.VIDEO, "mp4"),
    QUICKTIME("video/quicktime", SniffCategory.VIDEO, "mov"),
    THREEGPP("video/3gpp", SniffCategory.VIDEO, "3gp"),
    WEBM("video/webm", SniffCategory.VIDEO, "webm"),
    MATROSKA("video/x-matroska", SniffCategory.VIDEO, "mkv"),
    AVI("video/x-msvideo", SniffCategory.VIDEO, "avi"),
    PDF("application/pdf", SniffCategory.PDF, "pdf"),
    TEXT("text/plain", SniffCategory.TEXT, "txt"),
    MARKUP("application/octet-stream", SniffCategory.MARKUP, null),
    ZIP("application/zip", SniffCategory.ARCHIVE, "zip"),
    GZIP("application/gzip", SniffCategory.ARCHIVE, "gz"),
    BZIP2("application/x-bzip2", SniffCategory.ARCHIVE, "bz2"),
    XZ("application/x-xz", SniffCategory.ARCHIVE, "xz"),
    SEVEN_ZIP("application/x-7z-compressed", SniffCategory.ARCHIVE, "7z"),
    RAR("application/vnd.rar", SniffCategory.ARCHIVE, "rar"),
    TAR("application/x-tar", SniffCategory.ARCHIVE, "tar"),
    ZSTD("application/zstd", SniffCategory.ARCHIVE, "zst"),
    OFFICE_OLE("application/octet-stream", SniffCategory.OFFICE, null),
    RTF("application/octet-stream", SniffCategory.OFFICE, "rtf"),
    APK("application/vnd.android.package-archive", SniffCategory.INSTALLABLE, "apk"),
    JAR("application/java-archive", SniffCategory.INSTALLABLE, "jar"),
    DEX("application/octet-stream", SniffCategory.INSTALLABLE, "dex"),
    JAVA_CLASS("application/octet-stream", SniffCategory.INSTALLABLE, "class"),
    ELF("application/octet-stream", SniffCategory.EXECUTABLE, "so"),
    PE("application/octet-stream", SniffCategory.EXECUTABLE, "exe"),
    MACHO("application/octet-stream", SniffCategory.EXECUTABLE, null),
    WINDOWS_SHORTCUT("application/octet-stream", SniffCategory.EXECUTABLE, "lnk"),
    SCRIPT("application/octet-stream", SniffCategory.SCRIPT, "sh"),
    UNKNOWN("application/octet-stream", SniffCategory.UNKNOWN, null),
}

/** What a user is told before saving a file that cannot be opened. */
enum class WarnLevel { NONE, GENERAL, DANGEROUS }

class SniffResult(val type: SniffedType, val polyglotReason: String? = null) {
    val polyglot: Boolean get() = polyglotReason != null

    /** The type handed to another app and shown by the icon: the sniffed one, never the sender's claim. */
    val mime: String get() = type.mime
    val category: SniffCategory get() = type.category

    /** Open is offered only for an allow-listed type that is not also something else. */
    val openAllowed: Boolean get() = type.category.openAllowed && !polyglot

    val warn: WarnLevel
        get() = when {
            openAllowed -> WarnLevel.NONE
            type.category.dangerous || polyglot -> WarnLevel.DANGEROUS
            else -> WarnLevel.GENERAL
        }

    /** A picture the user may ask to see (tap to decode). */
    val decodable: Boolean get() = type.category == SniffCategory.PICTURE && !polyglot

    /** The type to give another app for [presentation]: a voice note in an MP4 container is audio. */
    fun mimeForOpen(presentation: Presentation): String =
        if (presentation == Presentation.VOICE && type == SniffedType.MP4) SniffedType.M4A.mime else type.mime
}

object FileSniffer {
    const val HEAD_BYTES = 8192
    const val TAIL_BYTES = 65_557

    /** Sniffs a file read through [source]. Reads at most [HEAD_BYTES] from the start and [TAIL_BYTES] from the end. */
    fun sniff(source: SniffSource): SniffResult {
        val size = source.size
        if (size <= 0) return SniffResult(SniffedType.UNKNOWN)
        val head = source.read(0, HEAD_BYTES)
        val type = classify(head, size, source)
        if (type.category.openAllowed || type.category == SniffCategory.UNKNOWN) {
            val reason = polyglotReason(source, type)
            if (reason != null) {
                // An unknown file that is really a ZIP with prepended data is reported as a ZIP, not as unknown.
                if (type == SniffedType.UNKNOWN && reason.startsWith("zip")) return SniffResult(SniffedType.ZIP)
                if (type != SniffedType.UNKNOWN) return SniffResult(type, reason)
            }
        }
        return SniffResult(type)
    }

    fun sniff(bytes: ByteArray): SniffResult = sniff(BytesSniffSource(bytes))

    /** Head-only classification, for the sender's own file when the tail cannot be read. */
    fun classifyHead(head: ByteArray, totalSize: Long): SniffedType = classify(head, totalSize, null)

    // ---- classification -----------------------------------------------------------------------------------------

    private fun classify(head: ByteArray, total: Long, source: SniffSource?): SniffedType {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xff else -1
        fun startsWith(vararg values: Int, offset: Int = 0) = values.indices.all { at(offset + it) == values[it] }
        fun ascii(offset: Int, text: String) = text.indices.all { at(offset + it) == text[it].code }
        if (head.size < 4) return if (looksLikeText(head, total)) SniffedType.TEXT else SniffedType.UNKNOWN

        // Pictures.
        if (startsWith(0xff, 0xd8, 0xff) && at(3) in JPEG_FOLLOWERS) return SniffedType.JPEG
        if (startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) && at(11) == 13 && ascii(12, "IHDR")) return SniffedType.PNG
        if ((ascii(0, "GIF87a") || ascii(0, "GIF89a")) && head.size >= 10) return SniffedType.GIF
        if (ascii(0, "RIFF") && head.size >= 12) {
            return when {
                ascii(8, "WEBP") -> SniffedType.WEBP
                ascii(8, "WAVE") -> SniffedType.WAV
                ascii(8, "AVI ") -> SniffedType.AVI
                else -> SniffedType.UNKNOWN
            }
        }
        if (ascii(0, "BM") && head.size >= 30 && at(6) == 0 && at(7) == 0 && at(8) == 0 && at(9) == 0 &&
            le32(head, 14) in BMP_HEADER_SIZES
        ) return SniffedType.BMP

        // ISO base media: audio, video, HEIF/AVIF pictures all start with an `ftyp` box.
        if (ascii(4, "ftyp") && head.size >= 12) {
            val boxSize = be32(head, 0)
            if (boxSize in 12..4096 || (boxSize == 1L && head.size >= 16)) return ftypType(head)
        }
        if (startsWith(0x1a, 0x45, 0xdf, 0xa3)) return ebmlType(head)
        if (ascii(0, "OggS") && at(4) == 0) return SniffedType.OGG
        if (ascii(0, "fLaC")) return SniffedType.FLAC
        if (ascii(0, "MThd") && head.size >= 8 && be32(head, 4) == 6L) return SniffedType.MIDI
        // #!AMR before the script check: the AMR file header starts with "#!".
        if (ascii(0, "#!AMR\n") || ascii(0, "#!AMR-WB\n") || ascii(0, "#!AMR_MC1.0\n") || ascii(0, "#!AMR-WB_MC1.0\n")) return SniffedType.AMR
        if (ascii(0, "ID3") && at(3) in 2..4 && at(4) != 0xff && (6..9).all { at(it) in 0..0x7f }) return SniffedType.MP3
        mpegAudioType(head)?.let { return it }

        // Documents.
        if (ascii(0, "%PDF-") && at(5) in '1'.code..'2'.code) return SniffedType.PDF
        if (startsWith(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) return SniffedType.OFFICE_OLE
        if (ascii(0, "{\\rtf")) return SniffedType.RTF

        // Executable and installable.
        if (ascii(0, "PK") && at(2) in intArrayOf(3, 5, 7) && at(3) in intArrayOf(4, 6, 8)) return zipType(head, source, total)
        if (ascii(0, "dex\n") && at(7) == 0 && (4..6).all { at(it) in '0'.code..'9'.code }) return SniffedType.DEX
        if (startsWith(0x7f, 0x45, 0x4c, 0x46)) return SniffedType.ELF
        if (ascii(0, "MZ") && isPortableExecutable(head)) return SniffedType.PE
        if (startsWith(0xca, 0xfe, 0xba, 0xbe)) {
            return if (at(4) == 0 && at(5) == 0 && (at(6) shl 8 or at(7)) >= 45) SniffedType.JAVA_CLASS else SniffedType.MACHO
        }
        if (startsWith(0xfe, 0xed, 0xfa, 0xce) || startsWith(0xfe, 0xed, 0xfa, 0xcf) ||
            startsWith(0xce, 0xfa, 0xed, 0xfe) || startsWith(0xcf, 0xfa, 0xed, 0xfe)
        ) return SniffedType.MACHO
        if (startsWith(0x4c, 0, 0, 0, 0x01, 0x14, 0x02, 0)) return SniffedType.WINDOWS_SHORTCUT

        // Archives.
        if (startsWith(0x1f, 0x8b)) return SniffedType.GZIP
        if (ascii(0, "BZh") && at(3) in '1'.code..'9'.code) return SniffedType.BZIP2
        if (startsWith(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0)) return SniffedType.XZ
        if (startsWith(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)) return SniffedType.SEVEN_ZIP
        if (ascii(0, "Rar!") && at(4) == 0x1a && at(5) == 0x07) return SniffedType.RAR
        if (startsWith(0x28, 0xb5, 0x2f, 0xfd)) return SniffedType.ZSTD
        if (head.size >= 262 && ascii(257, "ustar")) return SniffedType.TAR

        // Text last: scripts and markup are text that something may run.
        if (looksLikeText(head, total)) return textType(head)
        return SniffedType.UNKNOWN
    }

    private val JPEG_FOLLOWERS = (0xe0..0xef).toSet() + setOf(0xdb, 0xc0, 0xc1, 0xc2, 0xc4, 0xdd, 0xfe)
    private val BMP_HEADER_SIZES = setOf(12L, 40L, 52L, 56L, 64L, 108L, 124L)

    private fun be32(b: ByteArray, o: Int): Long =
        if (o + 4 > b.size) -1 else ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    private fun le32(b: ByteArray, o: Int): Long =
        if (o + 4 > b.size) -1 else ((b[o + 3].toLong() and 0xff) shl 24) or ((b[o + 2].toLong() and 0xff) shl 16) or
            ((b[o + 1].toLong() and 0xff) shl 8) or (b[o].toLong() and 0xff)

    private fun ftypType(head: ByteArray): SniffedType {
        val brand = String(head, 8, 4, Charsets.ISO_8859_1)
        return when {
            brand in HEIF_BRANDS -> SniffedType.HEIF
            brand in AVIF_BRANDS -> SniffedType.AVIF
            brand == "M4A " || brand == "M4B " || brand == "M4P " -> SniffedType.M4A
            brand == "qt  " -> SniffedType.QUICKTIME
            brand.startsWith("3gp") || brand.startsWith("3g2") -> SniffedType.THREEGPP
            else -> SniffedType.MP4
        }
    }

    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
    private val AVIF_BRANDS = setOf("avif", "avis")

    private fun ebmlType(head: ByteArray): SniffedType {
        val window = String(head, 0, minOf(head.size, 64), Charsets.ISO_8859_1)
        return when {
            window.contains("webm") -> SniffedType.WEBM
            window.contains("matroska") -> SniffedType.MATROSKA
            else -> SniffedType.UNKNOWN
        }
    }

    /** MPEG audio frame sync (MP3) or ADTS (AAC), validated by header fields and by a second frame where the head allows. */
    private fun mpegAudioType(head: ByteArray): SniffedType? {
        val b0 = head[0].toInt() and 0xff
        val b1 = head[1].toInt() and 0xff
        if (b0 != 0xff || b1 and 0xe0 != 0xe0) return null
        if (b1 and 0xf6 == 0xf0) {
            // ADTS: layer 00, sampling index below 13, frame length in 13 bits.
            val sampling = (head[2].toInt() and 0x3c) shr 2
            val length = ((head[3].toInt() and 0x03) shl 11) or ((head[4].toInt() and 0xff) shl 3) or ((head[5].toInt() and 0xe0) shr 5)
            if (sampling >= 13 || length < 7) return null
            return if (head.size >= length + 2) {
                if ((head[length].toInt() and 0xff) == 0xff && (head[length + 1].toInt() and 0xf6) == 0xf0) SniffedType.AAC else null
            } else SniffedType.AAC
        }
        val length = mp3FrameLength(head, 0) ?: return null
        return if (head.size >= length + 4) {
            if (mp3FrameLength(head, length) != null) SniffedType.MP3 else null
        } else SniffedType.MP3
    }

    private val MP3_BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MP3_BITRATES_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
    private val MP3_BITRATES_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
    private val MP3_BITRATES_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
    private val MP3_BITRATES_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val MP3_RATES = mapOf(3 to intArrayOf(44100, 48000, 32000), 2 to intArrayOf(22050, 24000, 16000), 0 to intArrayOf(11025, 12000, 8000))

    /** Frame length in bytes of an MPEG audio frame header at [offset], or null when it is not a valid header. */
    private fun mp3FrameLength(b: ByteArray, offset: Int): Int? {
        if (offset + 4 > b.size) return null
        val h1 = b[offset].toInt() and 0xff
        val h2 = b[offset + 1].toInt() and 0xff
        val h3 = b[offset + 2].toInt() and 0xff
        if (h1 != 0xff || h2 and 0xe0 != 0xe0) return null
        val version = (h2 shr 3) and 3
        val layer = (h2 shr 1) and 3
        if (version == 1 || layer == 0) return null
        val bitrateIndex = h3 shr 4
        val rateIndex = (h3 shr 2) and 3
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val bitrate = when {
            version == 3 && layer == 3 -> MP3_BITRATES_V1_L1[bitrateIndex]
            version == 3 && layer == 2 -> MP3_BITRATES_V1_L2[bitrateIndex]
            version == 3 -> MP3_BITRATES_V1_L3[bitrateIndex]
            layer == 3 -> MP3_BITRATES_V2_L1[bitrateIndex]
            else -> MP3_BITRATES_V2_L23[bitrateIndex]
        } * 1000
        val rate = MP3_RATES.getValue(version)[rateIndex]
        val padding = (h3 shr 1) and 1
        val length = if (layer == 3) (12 * bitrate / rate + padding) * 4 else (if (version != 3 && layer == 1) 72 else 144) * bitrate / rate + padding
        return length.takeIf { it in 24..4096 }
    }

    private fun zipType(head: ByteArray, source: SniffSource?, total: Long): SniffedType {
        val window = StringBuilder(String(head, Charsets.ISO_8859_1))
        if (source != null && total > head.size) {
            // Entry names sit in the central directory at the end: read its first 16 KiB.
            val eocd = findEocd(source)
            if (eocd != null) window.append(String(source.read(eocd.cdStart, 16_384), Charsets.ISO_8859_1))
        }
        val text = window.toString()
        return when {
            text.contains("AndroidManifest.xml") || text.contains("classes.dex") -> SniffedType.APK
            text.contains("META-INF/MANIFEST.MF") -> SniffedType.JAR
            else -> SniffedType.ZIP
        }
    }

    /** `MZ` with a PE header where the DOS header says (a bare `MZ` at the start of a text is not an executable). */
    private fun isPortableExecutable(head: ByteArray): Boolean {
        if (head.size < 0x40) return false
        val offset = le32(head, 0x3c)
        return offset in 0x40L..(head.size - 4L) &&
            head[offset.toInt()] == 'P'.code.toByte() && head[offset.toInt() + 1] == 'E'.code.toByte() &&
            head[offset.toInt() + 2] == 0.toByte() && head[offset.toInt() + 3] == 0.toByte()
    }

    // ---- text ---------------------------------------------------------------------------------------------------

    private fun stripBom(head: ByteArray): ByteArray =
        if (head.size >= 3 && head[0] == 0xef.toByte() && head[1] == 0xbb.toByte() && head[2] == 0xbf.toByte()) head.copyOfRange(3, head.size) else head

    /** Valid UTF-8 without NUL or control characters other than tab, line feed, form feed and carriage return. */
    fun looksLikeText(head: ByteArray, total: Long): Boolean {
        val bytes = stripBom(head)
        if (bytes.isEmpty()) return false
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val out = java.nio.CharBuffer.allocate(bytes.size + 1)
        val result = decoder.decode(input, out, total <= head.size)
        // An incomplete sequence at the cut of the head is fine when the file goes on.
        if (result.isError) return false
        if (result.isUnderflow && input.hasRemaining() && total <= head.size) return false
        if (result.isUnderflow && input.remaining() > 3) return false
        out.flip()
        for (i in 0 until out.length) {
            val c = out[i]
            if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\u000c') return false
            if (c.code == 0x7f || c.code in 0x80..0x9f) return false
        }
        return true
    }

    private fun textType(head: ByteArray): SniffedType {
        val text = String(stripBom(head), Charsets.UTF_8).trimStart().take(256).lowercase()
        return when {
            text.startsWith("#!") -> SniffedType.SCRIPT
            text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("<?xml") || text.startsWith("<svg") ||
                text.startsWith("<script") || text.startsWith("<body") || text.startsWith("<head") || text.startsWith("<iframe") ||
                text.startsWith("<meta") || text.startsWith("<!--") || text.startsWith("<?php") || text.startsWith("<math") -> SniffedType.MARKUP
            else -> SniffedType.TEXT
        }
    }

    // ---- polyglots ----------------------------------------------------------------------------------------------

    /**
     * A file that is a valid picture, audio, video, PDF or text and also something else is not opened: a ZIP central
     * directory behind the first format (an archive appended to a picture, a prefixed self-extractor) or a polyglot that
     * keeps an APK and a media file in one. Returns the reason, or null.
     */
    private fun polyglotReason(source: SniffSource, type: SniffedType): String? {
        findEocd(source) ?: return null
        return if (type == SniffedType.UNKNOWN) "zip archive with prefixed data" else "zip archive appended to the ${type.name.lowercase()} file"
    }

    /** A ZIP end-of-central-directory record in the last 64 KiB whose central directory really starts where it says. */
    private class Eocd(val cdStart: Long, val cdSize: Long)

    private fun findEocd(source: SniffSource): Eocd? {
        val size = source.size
        val tailLength = minOf(size, TAIL_BYTES.toLong()).toInt()
        if (tailLength < 22) return null
        val tailStart = size - tailLength
        val tail = source.read(tailStart, tailLength)
        var index = tail.size - 22
        while (index >= 0) {
            if (tail[index] == 0x50.toByte() && tail[index + 1] == 0x4b.toByte() && tail[index + 2] == 5.toByte() && tail[index + 3] == 6.toByte()) {
                val commentLength = (tail[index + 20].toInt() and 0xff) or ((tail[index + 21].toInt() and 0xff) shl 8)
                if (index + 22 + commentLength == tail.size) {
                    val cdSize = le32(tail, index + 12)
                    val eocdPosition = tailStart + index
                    val cdStart = eocdPosition - cdSize
                    if (cdSize in 1..eocdPosition && cdStart >= 0) {
                        val probe = source.read(cdStart, 4)
                        if (probe.size == 4 && probe[0] == 0x50.toByte() && probe[1] == 0x4b.toByte() && probe[2] == 1.toByte() && probe[3] == 2.toByte()) {
                            return Eocd(cdStart, cdSize)
                        }
                    }
                }
            }
            index--
        }
        return null
    }
}

/**
 * Picture dimensions from the header alone (no pixel is decoded), for the header limit before any decode (M3: 40 megapixels).
 * Hint fields in a manifest never size anything; these come from the bytes about to be decoded.
 */
object ImageHeader {
    const val MAX_PIXELS = 40_000_000L

    fun dimensions(type: SniffedType, head: ByteArray): Pair<Int, Int>? = try {
        when (type) {
            SniffedType.JPEG -> jpeg(head)
            SniffedType.PNG -> if (head.size >= 24) be(head, 16) to be(head, 20) else null
            SniffedType.GIF -> if (head.size >= 10) le16(head, 6) to le16(head, 8) else null
            SniffedType.BMP -> bmp(head)
            SniffedType.WEBP -> webp(head)
            SniffedType.HEIF, SniffedType.AVIF -> ispe(head)
            else -> null
        }?.takeIf { (w, h) -> w > 0 && h > 0 }
    } catch (error: IndexOutOfBoundsException) {
        null
    }

    fun withinLimit(width: Int, height: Int): Boolean = width.toLong() * height <= MAX_PIXELS

    /** Dimensions and the 40 megapixel check in one: null when unknown or too large. */
    fun checked(type: SniffedType, head: ByteArray): Pair<Int, Int>? = dimensions(type, head)?.takeIf { (w, h) -> withinLimit(w, h) }

    private fun jpeg(head: ByteArray): Pair<Int, Int>? = JpegHeader.dimensions(head)

    private fun be(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun bmp(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 26) return null
        val headerSize = le32(head, 14)
        return if (headerSize == 12) le16(head, 18) to le16(head, 20)
        else {
            val width = le32(head, 18)
            val height = le32(head, 22)
            if (height == Int.MIN_VALUE) null else width to kotlin.math.abs(height)
        }
    }

    private fun webp(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 30) return null
        return when (String(head, 12, 4, Charsets.ISO_8859_1)) {
            "VP8 " -> if (head[23] == 0x9d.toByte() && head[24] == 0x01.toByte() && head[25] == 0x2a.toByte()) {
                (le16(head, 26) and 0x3fff) to (le16(head, 28) and 0x3fff)
            } else null
            "VP8L" -> if (head[20] == 0x2f.toByte()) {
                val bits = le32(head, 21)
                ((bits and 0x3fff) + 1) to (((bits shr 14) and 0x3fff) + 1)
            } else null
            "VP8X" -> {
                val w = 1 + ((head[24].toInt() and 0xff) or ((head[25].toInt() and 0xff) shl 8) or ((head[26].toInt() and 0xff) shl 16))
                val h = 1 + ((head[27].toInt() and 0xff) or ((head[28].toInt() and 0xff) shl 8) or ((head[29].toInt() and 0xff) shl 16))
                w to h
            }
            else -> null
        }
    }

    /** The largest `ispe` (image spatial extents) property in the head of a HEIF/AVIF file. */
    private fun ispe(head: ByteArray): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var i = 0
        while (i + 20 <= head.size) {
            if (head[i] == 'i'.code.toByte() && head[i + 1] == 's'.code.toByte() && head[i + 2] == 'p'.code.toByte() && head[i + 3] == 'e'.code.toByte()) {
                val w = be(head, i + 8)
                val h = be(head, i + 12)
                if (w > 0 && h > 0 && (best == null || w.toLong() * h > best.first.toLong() * best.second)) best = w to h
                i += 4
            } else i++
        }
        return best
    }
}
