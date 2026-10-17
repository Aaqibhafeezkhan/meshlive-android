package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Content sniffing (PROTOCOL / DECISIONS Design M3 "Formats"): the bytes decide, the sender's claim never does. */
class MessagingV2SnifferTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun pad(prefix: ByteArray, size: Int = 600): ByteArray = prefix + ByteArray(maxOf(0, size - prefix.size)) { (it * 7 % 251).toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    /** Text padded with text (the random padding of [pad] is binary). */
    private fun text(prefix: String, size: Int = 600): ByteArray = (prefix + " ".repeat(maxOf(0, size - prefix.length))).toByteArray(Charsets.ISO_8859_1)

    private fun u32be(v: Int) = bytes(v ushr 24, v ushr 16, v ushr 8, v)

    private fun u32le(v: Int) = bytes(v, v ushr 8, v ushr 16, v ushr 24)

    fun jpeg(extra: Int = 600) = pad(bytes(0xff, 0xd8, 0xff, 0xe0, 0, 16) + ascii("JFIF") + bytes(0), extra)

    fun png(w: Int = 4, h: Int = 4) = pad(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13) + ascii("IHDR") + u32be(w) + u32be(h) + bytes(8, 6, 0, 0, 0))

    private fun ftyp(brand: String) = u32be(24) + ascii("ftyp") + ascii(brand) + u32be(0) + ascii(brand) + ascii("mp42")

    private fun riff(kind: String) = ascii("RIFF") + u32le(1000) + ascii(kind) + ByteArray(600)

    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sniff(data: ByteArray) = FileSniffer.sniff(data)

    // ---- the table ---------------------------------------------------------------------------------------------

    @Test
    fun everyKnownSignatureIsRecognisedFromItsBytes() {
        val mp3Frame = bytes(0xff, 0xfb, 0x90, 0x00) // MPEG1 layer III, 128 kbps, 44.1 kHz: 417 bytes
        val mp3 = ByteArray(1200).also { data ->
            mp3Frame.copyInto(data, 0)
            mp3Frame.copyInto(data, 417)
            mp3Frame.copyInto(data, 834)
        }
        val adts = ByteArray(600).also { data ->
            // ADTS, 44.1 kHz, frame length 100.
            bytes(0xff, 0xf1, 0x50, 0x80, 0x0c, 0x80, 0xfc).copyInto(data, 0)
            bytes(0xff, 0xf1, 0x50, 0x80, 0x0c, 0x80, 0xfc).copyInto(data, 100)
        }
        val cases = listOf<Pair<ByteArray, SniffedType>>(
            jpeg() to SniffedType.JPEG,
            png() to SniffedType.PNG,
            pad(ascii("GIF89a") + bytes(4, 0, 4, 0)) to SniffedType.GIF,
            pad(ascii("GIF87a") + bytes(4, 0, 4, 0)) to SniffedType.GIF,
            riff("WEBP") to SniffedType.WEBP,
            riff("WAVE") to SniffedType.WAV,
            riff("AVI ") to SniffedType.AVI,
            pad(ascii("BM") + bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) + u32le(40) + ByteArray(20)) to SniffedType.BMP,
            pad(ftyp("heic")) to SniffedType.HEIF,
            pad(ftyp("mif1")) to SniffedType.HEIF,
            pad(ftyp("avif")) to SniffedType.AVIF,
            pad(ftyp("isom")) to SniffedType.MP4,
            pad(ftyp("mp42")) to SniffedType.MP4,
            pad(ftyp("M4A ")) to SniffedType.M4A,
            pad(ftyp("qt  ")) to SniffedType.QUICKTIME,
            pad(ftyp("3gp4")) to SniffedType.THREEGPP,
            pad(bytes(0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42, 0x82, 0x84) + ascii("webm")) to SniffedType.WEBM,
            pad(bytes(0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42, 0x82, 0x88) + ascii("matroska")) to SniffedType.MATROSKA,
            pad(ascii("OggS") + bytes(0)) to SniffedType.OGG,
            pad(ascii("fLaC")) to SniffedType.FLAC,
            pad(ascii("ID3") + bytes(3, 0, 0, 0, 0, 0x10, 0)) to SniffedType.MP3,
            mp3 to SniffedType.MP3,
            adts to SniffedType.AAC,
            pad(ascii("#!AMR\n")) to SniffedType.AMR,
            pad(ascii("#!AMR-WB\n")) to SniffedType.AMR,
            pad(ascii("MThd") + u32be(6)) to SniffedType.MIDI,
            pad(ascii("%PDF-1.7\n")) to SniffedType.PDF,
            zip("a.txt") to SniffedType.ZIP,
            zip("AndroidManifest.xml", "classes.dex") to SniffedType.APK,
            zip("META-INF/MANIFEST.MF", "A.class") to SniffedType.JAR,
            pad(ascii("dex\n035") + bytes(0)) to SniffedType.DEX,
            pad(bytes(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1)) to SniffedType.ELF,
            pad(bytes(0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 52)) to SniffedType.JAVA_CLASS,
            pad(bytes(0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 2)) to SniffedType.MACHO,
            pad(bytes(0xcf, 0xfa, 0xed, 0xfe)) to SniffedType.MACHO,
            text("#!/bin/sh\necho hi\n") to SniffedType.SCRIPT,
            text("<!DOCTYPE html><html></html>") to SniffedType.MARKUP,
            text("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>") to SniffedType.MARKUP,
            text("  \n<?xml version=\"1.0\"?><a/>") to SniffedType.MARKUP,
            pad(ascii("{\\rtf1\\ansi")) to SniffedType.RTF,
            pad(bytes(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) to SniffedType.OFFICE_OLE,
            pad(bytes(0x1f, 0x8b, 8, 0)) to SniffedType.GZIP,
            pad(ascii("7z") + bytes(0xbc, 0xaf, 0x27, 0x1c)) to SniffedType.SEVEN_ZIP,
            pad(ascii("Rar!") + bytes(0x1a, 0x07, 0)) to SniffedType.RAR,
            text("Hello, this is plain text.\nSecond line.\n") to SniffedType.TEXT,
            ByteArray(600) { (it * 31 + 5).toByte() }.also { it[0] = 0x01; it[1] = 0x02 } to SniffedType.UNKNOWN,
        )
        for ((data, expected) in cases) {
            assertEquals("${expected.name} from ${data.take(8).joinToString { "%02x".format(it) }}", expected, sniff(data).type)
        }
    }

    @Test
    fun onlyPicturesAudioVideoPdfAndPlainTextMayBeOpened() {
        val open = setOf(
            SniffedType.JPEG, SniffedType.PNG, SniffedType.GIF, SniffedType.WEBP, SniffedType.HEIF, SniffedType.AVIF, SniffedType.BMP,
            SniffedType.MP3, SniffedType.AAC, SniffedType.OGG, SniffedType.FLAC, SniffedType.WAV, SniffedType.AMR, SniffedType.MIDI,
            SniffedType.M4A, SniffedType.MP4, SniffedType.QUICKTIME, SniffedType.THREEGPP, SniffedType.WEBM, SniffedType.MATROSKA,
            SniffedType.AVI, SniffedType.PDF, SniffedType.TEXT,
        )
        for (type in SniffedType.entries) {
            assertEquals(type.name, type in open, type.category.openAllowed)
        }
        // Everything installable or executable carries the strong warning; the rest of the save-only types the plain one.
        assertEquals(WarnLevel.DANGEROUS, SniffResult(SniffedType.APK).warn)
        assertEquals(WarnLevel.DANGEROUS, SniffResult(SniffedType.ELF).warn)
        assertEquals(WarnLevel.DANGEROUS, SniffResult(SniffedType.SCRIPT).warn)
        assertEquals(WarnLevel.GENERAL, SniffResult(SniffedType.ZIP).warn)
        assertEquals(WarnLevel.GENERAL, SniffResult(SniffedType.UNKNOWN).warn)
        assertEquals(WarnLevel.GENERAL, SniffResult(SniffedType.MARKUP).warn)
        assertEquals(WarnLevel.NONE, SniffResult(SniffedType.JPEG).warn)
    }

    @Test
    fun theSniffedTypeIsTheOnlyTypeHandedToAnotherApp() {
        // A program that claims to be a picture is a program: the claim never reaches the result.
        val elf = sniff(pad(bytes(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1)))
        assertFalse(elf.openAllowed)
        assertEquals("application/octet-stream", elf.mime)
        assertEquals("image/jpeg", sniff(jpeg()).mime)
        // A voice note is an MP4 container: audio when it is presented as one.
        val mp4 = sniff(pad(ftyp("isom")))
        assertEquals("video/mp4", mp4.mimeForOpen(Presentation.VIDEO))
        assertEquals("audio/mp4", mp4.mimeForOpen(Presentation.VOICE))
        assertEquals("video/mp4", mp4.mimeForOpen(Presentation.FILE))
    }

    // ---- magic numbers that are not enough ----------------------------------------------------------------------

    @Test
    fun aSignatureWithoutTheStructureBehindItIsNotThatType() {
        // PNG signature but no IHDR.
        assertEquals(SniffedType.UNKNOWN, sniff(pad(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 5) + ascii("junk!"))).type)
        // JPEG start of image followed by something that is not a marker.
        assertEquals(SniffedType.UNKNOWN, sniff(pad(bytes(0xff, 0xd8, 0xff, 0x00, 1, 2, 3, 4))).type)
        // ftyp box with an impossible size.
        assertEquals(SniffedType.UNKNOWN, sniff(pad(u32be(0x7fffffff) + ascii("ftyp") + ascii("isom"))).type)
        // ID3 tag whose syncsafe size bytes are not syncsafe.
        assertEquals(SniffedType.UNKNOWN, sniff(pad(ascii("ID3") + bytes(3, 0, 0, 0xff, 0xff, 0xff, 0xff))).type)
        // An MPEG frame header with no second frame behind it, in a head long enough to show one.
        assertEquals(SniffedType.UNKNOWN, sniff(pad(bytes(0xff, 0xfb, 0x90, 0x00), 4000).also { it[417] = 0; it[418] = 0 }).type)
        // GIF too short for a header, BMP with a wrong DIB size, RIFF of an unknown form.
        assertNotEquals(SniffedType.GIF, sniff(ascii("GIF89a")).type)
        assertEquals(SniffedType.UNKNOWN, sniff(pad(ascii("BM") + ByteArray(40))).type)
        assertEquals(SniffedType.UNKNOWN, sniff(riff("QLCM")).type)
        // "MZ" at the start of a text is not a program; a real DOS header with a PE signature is.
        assertEquals(SniffedType.TEXT, sniff(ascii("MZ is a prefix of this sentence.\n")).type)
        val pe = ByteArray(300).also { data ->
            data[0] = 'M'.code.toByte(); data[1] = 'Z'.code.toByte()
            data[0x3c] = 0x80.toByte()
            data[0x80] = 'P'.code.toByte(); data[0x81] = 'E'.code.toByte()
        }
        assertEquals(SniffedType.PE, sniff(pe).type)
    }

    @Test
    fun hostileSignatureOrderingNeverTurnsTheStrongerTypeIntoTheWeakerOne() {
        // An AMR header starts with "#!" and must not be read as a script; a script that merely begins with "#!AMR" text
        // without the newline is a script.
        assertEquals(SniffedType.SCRIPT, sniff(text("#!AMRfoo\nrm -rf /\n")).type)
        assertEquals(SniffedType.AMR, sniff(pad(ascii("#!AMR\n"))).type)
        // A script that starts with a UTF-8 byte order mark is still a script.
        assertEquals(SniffedType.SCRIPT, sniff(bytes(0xef, 0xbb, 0xbf) + text("#!/bin/sh\nid\n")).type)
        // HTML that hides behind leading whitespace and upper case.
        assertEquals(SniffedType.MARKUP, sniff(text("\n\t  <HTML><SCRIPT>alert(1)</SCRIPT>")).type)
        // A PDF header that is not at byte 0 is not a PDF.
        assertEquals(SniffedType.TEXT, sniff(text("junk %PDF-1.4 more text\n")).type)
        // Two signatures in one file: the first one wins, and the second shows up as a polyglot when it is a ZIP.
        assertEquals(SniffedType.JPEG, sniff(jpeg() + ascii("%PDF-1.4")).type)
    }

    // ---- polyglots -----------------------------------------------------------------------------------------------

    @Test
    fun aPictureWithAnArchiveBehindItIsAPolyglotAndIsNotOpened() {
        val polyglot = jpeg(3000) + zip("AndroidManifest.xml", "classes.dex")
        val result = sniff(polyglot)
        assertEquals(SniffedType.JPEG, result.type)
        assertTrue(result.polyglot)
        assertFalse(result.openAllowed)
        assertFalse(result.decodable)
        assertEquals(WarnLevel.DANGEROUS, result.warn)
        assertNotNull(result.polyglotReason)
        // The same for a PDF, a plain text and a video.
        assertTrue(sniff(pad(ascii("%PDF-1.4\n"), 2000) + zip("a")).polyglot)
        assertTrue(sniff(text("hello world\n", 20_000) + zip("a")).polyglot)
        // Text followed by an archive inside the head is not text at all: it is reported as the archive.
        assertEquals(SniffedType.ZIP, sniff(text("hello world\n", 2000) + zip("a")).type)
        assertTrue(sniff(pad(ftyp("isom"), 2000) + zip("a")).polyglot)
    }

    @Test
    fun anArchiveWithPrefixedDataIsReportedAsAnArchive() {
        val result = sniff(ByteArray(900) { (it * 13 + 3).toByte() }.also { it[0] = 1 } + zip("a.bin"))
        assertEquals(SniffedType.ZIP, result.type)
        assertFalse(result.openAllowed)
    }

    @Test
    fun aStrayEndOfArchiveSignatureInsideAPictureIsNotAPolyglot() {
        val data = jpeg(5000)
        // "PK\5\6" with garbage after it, then the real end of the file: the record does not describe a directory that exists.
        val tail = ascii("PK") + bytes(5, 6) + ByteArray(18)
        val result = sniff(data + tail)
        assertEquals(SniffedType.JPEG, result.type)
        assertFalse(result.polyglot)
        assertTrue(result.openAllowed)
    }

    // ---- text ----------------------------------------------------------------------------------------------------

    @Test
    fun textMustBeValidUtf8WithoutControlCharacters() {
        assertEquals(SniffedType.TEXT, sniff("café 世界 مرحبا\nline two\r\n".toByteArray()).type)
        assertEquals(SniffedType.TEXT, sniff(bytes(0xef, 0xbb, 0xbf) + "with a byte order mark\n".toByteArray()).type)
        assertEquals(SniffedType.UNKNOWN, sniff(bytes(0xc3, 0x28, 0x41, 0x42, 0x43)).type)
        assertEquals(SniffedType.UNKNOWN, sniff("text with a nul\u0000 inside".toByteArray()).type)
        assertEquals(SniffedType.UNKNOWN, sniff("escape \u001b[31m sequence".toByteArray()).type)
        assertEquals(SniffedType.UNKNOWN, sniff(ByteArray(0)).type)
        // Right-to-left override characters are legal text (the name sanitiser handles names, not contents).
        assertEquals(SniffedType.TEXT, sniff("abc‮def\n".toByteArray()).type)
    }

    @Test
    fun aMultibyteCharacterCutByTheHeadLimitDoesNotTurnTextIntoBinary() {
        val text = ByteArrayOutputStream()
        while (text.size() < FileSniffer.HEAD_BYTES - 1) text.write('a'.code)
        text.write("€".toByteArray()) // three bytes: the third falls beyond the head
        text.write("tail of the file\n".toByteArray())
        assertEquals(SniffedType.TEXT, sniff(text.toByteArray()).type)
    }

    // ---- bounded reads ---------------------------------------------------------------------------------------------

    @Test
    fun sniffingReadsOnlyTheStartAndTheEndOfAHugeFile() {
        var total = 0L
        val source = object : SniffSource {
            override val size: Long = 64_000_000L

            override fun read(offset: Long, length: Int): ByteArray {
                total += length
                return if (offset == 0L) jpeg(8192).copyOf(length) else ByteArray(length)
            }
        }
        val result = FileSniffer.sniff(source)
        assertEquals(SniffedType.JPEG, result.type)
        assertTrue("read $total bytes", total <= FileSniffer.HEAD_BYTES + FileSniffer.TAIL_BYTES + 8)
    }

    // ---- image headers ----------------------------------------------------------------------------------------------

    @Test
    fun theHeaderSizeIsReadWithoutDecodingAndTheFortyMegapixelLimitHolds() {
        assertEquals(4000 to 3000, ImageHeader.dimensions(SniffedType.PNG, png(4000, 3000)))
        assertEquals(1 to 2, ImageHeader.dimensions(SniffedType.GIF, pad(ascii("GIF89a") + bytes(1, 0, 2, 0))))
        // BMP: width 300, height -200 (top-down).
        assertEquals(300 to 200, ImageHeader.dimensions(SniffedType.BMP, pad(ascii("BM") + ByteArray(12) + u32le(40) + u32le(300) + u32le(-200) + ByteArray(20))))
        // WebP: lossy, lossless and extended.
        val vp8 = pad(ascii("RIFF") + u32le(100) + ascii("WEBPVP8 ") + u32le(50) + bytes(0, 0, 0, 0x9d, 0x01, 0x2a, 0x40, 0x01, 0xf0, 0x00))
        assertEquals(320 to 240, ImageHeader.dimensions(SniffedType.WEBP, vp8))
        val width = 640 - 1
        val height = 480 - 1
        val bits = width or (height shl 14)
        val vp8l = pad(ascii("RIFF") + u32le(100) + ascii("WEBPVP8L") + u32le(50) + bytes(0x2f) + u32le(bits))
        assertEquals(640 to 480, ImageHeader.dimensions(SniffedType.WEBP, vp8l))
        val vp8x = pad(ascii("RIFF") + u32le(100) + ascii("WEBPVP8X") + u32le(10) + u32le(0) + bytes(0xff, 0x03, 0, 0xff, 0x01, 0))
        assertEquals(1024 to 512, ImageHeader.dimensions(SniffedType.WEBP, vp8x))
        // HEIF: the largest `ispe` wins over a thumbnail's.
        val ispe = { w: Int, h: Int -> u32be(20) + ascii("ispe") + u32be(0) + u32be(w) + u32be(h) }
        assertEquals(4032 to 3024, ImageHeader.dimensions(SniffedType.HEIF, pad(ftyp("heic") + ispe(160, 120) + ispe(4032, 3024))))
        // JPEG: the size sits behind a large APP1.
        val app1 = bytes(0xff, 0xe1, 0xff, 0xff) + ByteArray(65533)
        val sof = bytes(0xff, 0xc0, 0, 17, 8, 0x0b, 0xb8, 0x0f, 0xa0, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        assertEquals(4000 to 3000, ImageHeader.dimensions(SniffedType.JPEG, bytes(0xff, 0xd8) + app1 + sof + bytes(0xff, 0xd9)))
        // The 40 megapixel limit is on the header, before any allocation.
        assertNotNull(ImageHeader.checked(SniffedType.PNG, png(6000, 6000)))
        assertNull(ImageHeader.checked(SniffedType.PNG, png(7000, 6000)))
        assertNull(ImageHeader.checked(SniffedType.PNG, png(40_000, 40_000)))
        // Unreadable, zero or negative sizes are unknown, never a number to allocate from.
        assertNull(ImageHeader.dimensions(SniffedType.PNG, png(0, 5)))
        assertNull(ImageHeader.dimensions(SniffedType.PNG, png(-1, 5)))
        assertNull(ImageHeader.dimensions(SniffedType.PNG, ByteArray(10)))
        assertNull(ImageHeader.dimensions(SniffedType.ZIP, png()))
    }

    // ---- names for the Save dialog ----------------------------------------------------------------------------------

    @Test
    fun theSaveNameFollowsTheBytesNotTheLabel() {
        assertEquals("photo.jpg", SaveName.suggest("photo.jpg", sniff(jpeg())))
        assertEquals("photo.jpeg", SaveName.suggest("photo.jpeg", sniff(jpeg())))
        // A program named like a picture is saved as a program, and a picture named like a program as a picture.
        val elf = sniff(pad(bytes(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1)))
        assertEquals("holiday.jpg.so", SaveName.suggest("holiday.jpg", elf))
        assertEquals("setup.apk.jpg", SaveName.suggest("setup.apk", sniff(jpeg())))
        assertEquals("notes.txt", SaveName.suggest("notes.txt", sniff(ascii("hello\n"))))
        assertEquals("run.sh.txt", SaveName.suggest("run.sh", sniff(ascii("hello\n"))))
        assertEquals("file.jpg", SaveName.suggest("", sniff(jpeg())))
        // A type with no extension of its own keeps harmless labels and neutralises ones that claim a type.
        val unknown = sniff(ByteArray(600) { (it * 31 + 5).toByte() }.also { it[0] = 1 })
        assertEquals("backup", SaveName.suggest("backup", unknown))
        assertEquals("photo.jpg.bin", SaveName.suggest("photo.jpg", unknown))
        // A long label keeps the added extension within the 96-byte name limit.
        val long = SaveName.suggest("x".repeat(200) + ".jpg", elf)
        assertTrue(long.toByteArray().size <= MessagingV2Format.NAME_MAX_BYTES)
        assertTrue(long.endsWith(".so"))
        // Control and bidirectional characters never survive.
        assertEquals("abc.jpg", SaveName.suggest("a\u202eb\u0000c.jpg", sniff(jpeg())))
    }
}
