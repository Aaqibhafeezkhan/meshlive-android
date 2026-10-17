package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Original" photos: the JPEG segment filter drops every metadata segment and any trailer, and keeps the picture. */
class MessagingV2JpegTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return b(0xff, marker, length ushr 8, length and 0xff) + payload
    }

    /** An EXIF payload (big endian) with a make string, an orientation and a GPS pointer. */
    private fun exif(orientation: Int, littleEndian: Boolean = false): ByteArray {
        val make = ascii("SECRET-CAMERA-MAKE\u0000")
        fun u16(v: Int) = if (littleEndian) b(v and 0xff, v ushr 8) else b(v ushr 8, v and 0xff)
        fun u32(v: Int) = if (littleEndian) b(v and 0xff, v ushr 8, v ushr 16, v ushr 24) else b(v ushr 24, v ushr 16, v ushr 8, v and 0xff)
        // IFD0 at 8: three entries, data after the IFD (8 + 2 + 36 + 4 = 50).
        val entries = u16(0x010f) + u16(2) + u32(make.size) + u32(50) +
            u16(0x0112) + u16(3) + u32(1) + u16(orientation) + b(0, 0) +
            u16(0x8825) + u16(4) + u32(1) + u32(70)
        val tiff = (if (littleEndian) ascii("II") else ascii("MM")) + u16(42) + u32(8) + u16(3) + entries + u32(0) + make +
            ascii("GPS-LAT-12.345-LON-67.890-SECRET")
        return ascii("Exif") + b(0, 0) + tiff
    }

    private val entropy = b(0x12, 0x34, 0xff, 0x00, 0x56, 0xff, 0xd0, 0x78, 0x9a, 0xff, 0xd1, 0xbc)
    private val dqt = seg(0xdb, ByteArray(65) { (it + 1).toByte() })
    private val sof = seg(0xc0, b(8, 0, 16, 0, 16, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))
    private val dht = seg(0xc4, ByteArray(29) { 9 })
    private val sos = seg(0xda, b(3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0))
    private val adobe = seg(0xee, ascii("Adobe") + b(0, 100, 0, 0, 0, 1, 0))
    private val trailer = ascii("TRAILER-SECRET-MOTION-PHOTO") + ascii("\u0000\u0000\u0000\u0018ftypmp42") + ByteArray(100)

    private fun metadataSegments(orientation: Int = 6) =
        seg(0xe0, ascii("JFIF") + b(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)) +
            seg(0xe1, exif(orientation)) +
            seg(0xe1, ascii("http://ns.adobe.com/xap/1.0/") + b(0) + ascii("<x:xmpmeta>SECRET-XMP</x:xmpmeta>")) +
            seg(0xe2, ascii("ICC_PROFILE") + b(0, 1, 1) + ByteArray(40) { 7 }) +
            seg(0xe2, ascii("MPF") + b(0) + ascii("SECRET-MPF")) +
            seg(0xed, ascii("Photoshop 3.0") + b(0) + ascii("SECRET-IPTC")) +
            seg(0xfe, ascii("SECRET-COMMENT"))

    private fun jpeg(orientation: Int = 6, withTrailer: Boolean = true, withAdobe: Boolean = true): ByteArray =
        b(0xff, 0xd8) + metadataSegments(orientation) + (if (withAdobe) adobe else ByteArray(0)) + dqt + sof + dht + sos + entropy +
            b(0xff, 0xd9) + (if (withTrailer) trailer else ByteArray(0))

    private fun run(data: ByteArray, keepIcc: Boolean = false): Pair<ByteArray, JpegMetadataFilter.Report> {
        val out = ByteArrayOutputStream()
        val report = JpegMetadataFilter.filter({ ByteArrayInputStream(data) }, out, keepIcc)
        return out.toByteArray() to report
    }

    private fun contains(haystack: ByteArray, needle: String) = String(haystack, Charsets.ISO_8859_1).contains(needle)

    // ---- what is removed --------------------------------------------------------------------------------------------

    @Test
    fun everyMetadataSegmentAndTheTrailerAreGoneAndThePictureIsIdentical() {
        val (out, report) = run(jpeg(orientation = 1))
        for (secret in listOf("SECRET", "GPS-LAT", "JFIF", "Exif", "xmpmeta", "ICC_PROFILE", "Photoshop", "TRAILER", "ftyp")) {
            assertFalse("still contains $secret", contains(out, secret))
        }
        // What is left is exactly SOI, the colour-transform flag, the picture's tables, the scan and the end marker.
        assertArrayEquals(b(0xff, 0xd8) + adobe + dqt + sof + dht + sos + entropy + b(0xff, 0xd9), out)
        assertEquals(out.size.toLong(), report.bytesOut)
        assertEquals(7, report.segmentsRemoved)
        assertEquals(trailer.size.toLong(), report.trailingBytesDropped)
        assertEquals(1, report.orientation)
    }

    @Test
    fun theOrientationSurvivesAsAMinimalTagAndNothingElseFromExif() {
        for (orientation in 2..8) {
            val (out, report) = run(jpeg(orientation = orientation))
            assertEquals(orientation, report.orientation)
            assertEquals(orientation, ExifOrientation.readFromJpeg(ByteArrayInputStream(out)))
            assertFalse(contains(out, "SECRET"))
            assertFalse(contains(out, "GPS"))
            // SOI, then exactly one APP1 of 36 bytes: the filtered file is the stripped one plus that tag.
            assertArrayEquals(b(0xff, 0xd8) + ExifOrientation.orientationSegment(orientation) + adobe + dqt + sof + dht + sos + entropy + b(0xff, 0xd9), out)
        }
    }

    @Test
    fun aLittleEndianExifOrientationIsReadToo() {
        val data = b(0xff, 0xd8) + seg(0xe1, exif(8, littleEndian = true)) + dqt + sof + sos + entropy + b(0xff, 0xd9)
        assertEquals(8, ExifOrientation.readFromJpeg(ByteArrayInputStream(data)))
        val (out, _) = run(data)
        assertEquals(8, ExifOrientation.readFromJpeg(ByteArrayInputStream(out)))
        assertFalse(contains(out, "SECRET"))
    }

    @Test
    fun theIccProfileIsKeptOnlyWhenAsked() {
        val (without, _) = run(jpeg(orientation = 1))
        assertFalse(contains(without, "ICC_PROFILE"))
        val (kept, report) = run(jpeg(orientation = 1), keepIcc = true)
        assertTrue(contains(kept, "ICC_PROFILE"))
        assertEquals(6, report.segmentsRemoved)
        assertFalse(contains(kept, "SECRET"))
    }

    @Test
    fun filteringTwiceChangesNothingFurther() {
        val (once, _) = run(jpeg(orientation = 6))
        val (twice, report) = run(once)
        assertArrayEquals(once, twice)
        // The only segment dropped is the orientation tag, which is written back identically.
        assertEquals(1, report.segmentsRemoved)
        assertEquals(0L, report.trailingBytesDropped)
    }

    @Test
    fun theSizeCanBeCountedWithoutKeepingTheBytes() {
        val data = jpeg()
        val (out, _) = run(data)
        assertEquals(out.size.toLong(), JpegMetadataFilter.filteredSize({ ByteArrayInputStream(data) }))
    }

    // ---- structure -------------------------------------------------------------------------------------------------

    @Test
    fun progressiveScansAndRestartMarkersAreCopiedAndMetadataBetweenScansIsDropped() {
        val second = seg(0xda, b(1, 1, 0, 1, 63, 0x10)) + b(0x01, 0xff, 0x00, 0xff, 0xd3, 0x02)
        val data = b(0xff, 0xd8) + dqt + sof + dht + sos + entropy + seg(0xfe, ascii("SECRET-BETWEEN")) + dht + second + b(0xff, 0xd9)
        val (out, report) = run(data)
        assertEquals(1, report.segmentsRemoved)
        assertArrayEquals(b(0xff, 0xd8) + dqt + sof + dht + sos + entropy + dht + second + b(0xff, 0xd9), out)
    }

    @Test
    fun fillBytesBeforeAMarkerAreAccepted() {
        val data = b(0xff, 0xd8, 0xff, 0xff) + dqt.copyOfRange(1, dqt.size) + sof + sos + entropy + b(0xff, 0xff, 0xff, 0xd9)
        val (out, _) = run(data)
        assertArrayEquals(b(0xff, 0xd8) + dqt + sof + sos + entropy + b(0xff, 0xd9), out)
    }

    @Test
    fun aHugePictureIsFilteredInStreamingFashion() {
        val big = ByteArray(6 * 1024 * 1024) { (it % 250 + 1).toByte() } // never 0xff, so no marker ends the scan
        val data = b(0xff, 0xd8) + metadataSegments(1) + dqt + sof + sos + big + b(0xff, 0xd9) + ByteArray(1_000_000) { 1 }
        val (out, report) = run(data)
        assertEquals(b(0xff, 0xd8).size + dqt.size + sof.size + sos.size + big.size + 2L, report.bytesOut)
        assertEquals(1_000_000L, report.trailingBytesDropped)
        assertEquals(out.size.toLong(), report.bytesOut)
    }

    @Test
    fun anythingThatIsNotACompleteJpegIsRefused() {
        val good = jpeg(withTrailer = false)
        val endOfImage = good.size
        // Every cut before the end-of-image marker is complete is refused, wherever it falls.
        for (cut in 0 until endOfImage) {
            assertThrows("cut at $cut", JpegFormatException::class.java) { run(good.copyOf(cut)) }
        }
        assertThrows(JpegFormatException::class.java) { run(ascii("GIF89a not a jpeg")) }
        assertThrows(JpegFormatException::class.java) { run(ByteArray(0)) }
        // A stray byte where a marker belongs.
        assertThrows(JpegFormatException::class.java) { run(b(0xff, 0xd8) + b(0x41) + dqt + sof + sos + entropy + b(0xff, 0xd9)) }
        // A segment whose length is shorter than its own length field, or longer than the file.
        assertThrows(JpegFormatException::class.java) { run(b(0xff, 0xd8, 0xff, 0xe1, 0, 1) + dqt + sof + sos + entropy + b(0xff, 0xd9)) }
        assertThrows(JpegFormatException::class.java) { run(b(0xff, 0xd8, 0xff, 0xe1, 0xff, 0xff) + ByteArray(100)) }
        // An end of image with no scan at all.
        assertThrows(JpegFormatException::class.java) { run(b(0xff, 0xd8) + dqt + sof + b(0xff, 0xd9)) }
        // A stuffed zero where a marker should be.
        assertThrows(JpegFormatException::class.java) { run(b(0xff, 0xd8, 0xff, 0x00) + dqt) }
    }

    @Test
    fun aMillionTinySegmentsAreNotFollowedForever() {
        val out = ByteArrayOutputStream()
        out.write(b(0xff, 0xd8))
        repeat(JpegSegments.MAX_SEGMENTS + 10) { out.write(seg(0xfe, b(1))) }
        out.write(dqt + sof + sos + entropy + b(0xff, 0xd9))
        assertThrows(JpegFormatException::class.java) { run(out.toByteArray()) }
    }

    // ---- EXIF parsing ----------------------------------------------------------------------------------------------

    @Test
    fun hostileExifNeverThrowsAndNeverInventsAnOrientation() {
        assertNull(ExifOrientation.parseExif(ByteArray(0)))
        assertNull(ExifOrientation.parseExif(ascii("Exif") + b(0, 0) + ascii("XX") + ByteArray(20)))
        assertNull(ExifOrientation.parseExif(exif(0)))
        assertNull(ExifOrientation.parseExif(exif(9)))
        assertEquals(6, ExifOrientation.parseExif(exif(6)))
        // Entry count far beyond the payload, or an IFD offset pointing outside it.
        val huge = ascii("Exif") + b(0, 0) + ascii("MM") + b(0, 42, 0, 0, 0, 8, 0xff, 0xff) + ByteArray(8)
        assertNull(ExifOrientation.parseExif(huge))
        val outside = ascii("Exif") + b(0, 0) + ascii("MM") + b(0, 42, 0x7f, 0xff, 0xff, 0xff) + ByteArray(8)
        assertNull(ExifOrientation.parseExif(outside))
        // Truncated at every length.
        val full = exif(6)
        for (cut in full.indices) ExifOrientation.parseExif(full.copyOf(cut))
        // A JPEG whose structure cannot be followed has the normal orientation.
        assertEquals(1, ExifOrientation.readFromJpeg(ByteArrayInputStream(ascii("nonsense"))))
        assertEquals(1, ExifOrientation.readFromJpeg(ByteArrayInputStream(b(0xff, 0xd8, 0xff, 0xe1, 0xff))))
    }

    @Test
    fun theMinimalOrientationSegmentIsTheDocumentedThirtySixBytes() {
        for (orientation in 1..8) {
            val segment = ExifOrientation.orientationSegment(orientation)
            assertEquals(36, segment.size)
            assertEquals(orientation, ExifOrientation.parseExif(segment.copyOfRange(4, segment.size)))
        }
        assertThrows(IllegalArgumentException::class.java) { ExifOrientation.orientationSegment(0) }
        assertThrows(IllegalArgumentException::class.java) { ExifOrientation.orientationSegment(9) }
    }

    @Test
    fun orientationMathMatchesTheExifDefinitions() {
        // A 3 x 2 picture: where do the corners go? (EXIF 2.32, "Orientation".)
        val w = 3
        val h = 2
        val expected = mapOf(
            1 to listOf((0 to 0) to (0 to 0), (2 to 0) to (2 to 0)),
            2 to listOf((0 to 0) to (2 to 0), (2 to 0) to (0 to 0)),
            3 to listOf((0 to 0) to (2 to 1), (2 to 1) to (0 to 0)),
            4 to listOf((0 to 0) to (0 to 1), (2 to 1) to (2 to 0)),
            5 to listOf((0 to 0) to (0 to 0), (2 to 0) to (0 to 2)),
            6 to listOf((0 to 0) to (1 to 0), (2 to 0) to (1 to 2)),
            7 to listOf((0 to 0) to (1 to 2), (2 to 0) to (1 to 0)),
            8 to listOf((0 to 0) to (0 to 2), (2 to 0) to (0 to 0)),
        )
        for ((orientation, points) in expected) {
            for ((from, to) in points) {
                assertEquals("orientation $orientation from $from", to, ExifOrientation.mapPoint(orientation, from.first, from.second, w, h))
            }
        }
        assertEquals(setOf(5, 6, 7, 8), (1..8).filter(ExifOrientation::swapsAxes).toSet())
        // Every orientation is a bijection on the pixels.
        for (orientation in 1..8) {
            val seen = HashSet<Pair<Int, Int>>()
            for (x in 0 until w) for (y in 0 until h) seen += ExifOrientation.mapPoint(orientation, x, y, w, h)
            assertEquals(w * h, seen.size)
            val (nw, nh) = if (ExifOrientation.swapsAxes(orientation)) h to w else w to h
            assertTrue(seen.all { it.first in 0 until nw && it.second in 0 until nh })
        }
    }
}
