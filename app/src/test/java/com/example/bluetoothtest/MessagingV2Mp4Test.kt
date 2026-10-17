package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The post-remux scrubber: location, device and free-form atoms are renamed to `free` and zeroed in place. */
class MessagingV2Mp4Test {
    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun fourcc(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { a, c -> a + c }
        return u32(8L + payload.size) + fourcc(type) + payload
    }

    private val location = "+12.3456+098.7654/SECRET-LOCATION"
    private val ftyp = box("ftyp", fourcc("isom"), u32(0), fourcc("isom"))
    private val mdat = box("mdat", ByteArray(5000) { (it % 251).toByte() })

    private fun mp4(moovChildren: List<ByteArray>): ByteArray = ftyp + box("moov", *moovChildren.toTypedArray()) + mdat

    private val mvhd = box("mvhd", ByteArray(100))
    private val tkhd = box("tkhd", ByteArray(84))

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun aFileWithoutUserDataIsCleanAndUntouched() {
        val data = mp4(listOf(mvhd, box("trak", tkhd)))
        val copy = data.copyOf()
        val report = Mp4Scrubber.scrub(ByteArrayMp4Access(copy))
        assertTrue(report.clean)
        assertEquals(0L, report.bytesZeroed)
        assertArrayEquals(data, copy)
    }

    @Test
    fun locationDeviceAndFreeTextAtomsAreRenamedAndZeroed() {
        val udta = box(
            "udta",
            box("©xyz", fourcc(location)),
            box("©mak", fourcc("SECRET-MAKE")),
            box("©mod", fourcc("SECRET-MODEL")),
            box("loci", fourcc("SECRET-LOCI")),
        )
        val trakUdta = box("trak", tkhd, box("udta", box("name", fourcc("SECRET-TRACK-NAME"))))
        val meta = box("meta", ByteArray(4), box("keys", fourcc("mdta"), fourcc("com.apple.quicktime.location.ISO6709")), box("ilst", fourcc(location)))
        val uuid = box("uuid", ByteArray(16) { 1 }, fourcc("<x:xmpmeta>SECRET-XMP</x:xmpmeta>"))
        val data = mp4(listOf(mvhd, trakUdta, udta, meta, uuid))
        val copy = data.copyOf()
        val before = Mp4Scrubber.scan(ByteArrayMp4Access(copy))
        assertFalse(before.clean)
        assertTrue(before.parsed)
        assertEquals(listOf("udta", "udta", "meta", "uuid"), before.found)
        assertArrayEquals("a scan changes nothing", data, copy)
        val report = Mp4Scrubber.scrub(ByteArrayMp4Access(copy))
        assertTrue(report.bytesZeroed > 100)
        assertEquals(data.size, copy.size)
        assertFalse(text(copy).contains("SECRET"))
        assertFalse(text(copy).contains("quicktime.location"))
        assertFalse(text(copy).contains("12.3456"))
        // The structure is intact: same length, same media bytes, and a second scan finds nothing.
        assertTrue(Mp4Scrubber.scan(ByteArrayMp4Access(copy)).clean)
        assertArrayEquals(mdat, copy.copyOfRange(copy.size - mdat.size, copy.size))
        assertTrue(text(copy).contains("free"))
    }

    @Test
    fun atomsWithLargeAndOpenEndedSizesAreFollowed() {
        val largePayload = fourcc("SECRET-LARGE")
        val large = u32(1) + fourcc("udta") + ByteArray(8).also { it[7] = (16 + largePayload.size).toByte() } + largePayload
        val data = ftyp + box("moov", mvhd, large)
        val copy = data.copyOf()
        val report = Mp4Scrubber.scrub(ByteArrayMp4Access(copy))
        assertEquals(listOf("udta"), report.found)
        assertFalse(text(copy).contains("SECRET"))
        // Size 0 means "to the end of the file" for the last top-level box.
        val open = ftyp + box("moov", mvhd) + u32(0) + fourcc("udta") + fourcc("SECRET-OPEN")
        val copy2 = open.copyOf()
        assertEquals(listOf("udta"), Mp4Scrubber.scrub(ByteArrayMp4Access(copy2)).found)
        assertFalse(text(copy2).contains("SECRET"))
    }

    @Test
    fun aBrokenBoxStructureIsReportedAsUnparsedNeverAsClean() {
        val good = mp4(listOf(mvhd, box("udta", fourcc("SECRET"))))
        // A box that claims to run past its container.
        val bad = good.copyOf().also { it[ftyp.size] = 0x7f }
        val report = Mp4Scrubber.scan(ByteArrayMp4Access(bad))
        assertFalse(report.parsed)
        assertFalse(report.clean)
        // Truncated in the middle of a header.
        val truncated = Mp4Scrubber.scan(ByteArrayMp4Access(good.copyOf(ftyp.size + 12)))
        assertFalse(truncated.clean)
        // Random bytes are not a clean MP4 either.
        assertFalse(Mp4Scrubber.scan(ByteArrayMp4Access(ByteArray(500) { (it * 17).toByte() })).clean)
        // A box with a size smaller than its header.
        val tiny = ftyp + u32(4) + fourcc("moov") + ByteArray(20)
        assertFalse(Mp4Scrubber.scan(ByteArrayMp4Access(tiny)).parsed)
    }

    @Test
    fun nestingIsBoundedAndBoxCountIsBounded() {
        var nested = box("udta", fourcc("SECRET"))
        repeat(10) { nested = box("trak", nested) }
        val report = Mp4Scrubber.scan(ByteArrayMp4Access(ftyp + box("moov", nested)))
        // Too deep to follow: never reported as clean.
        assertFalse(report.parsed)
        assertFalse(report.clean)
        val many = ftyp + box("moov", *Array(1000) { box("free", ByteArray(4)) })
        assertTrue(Mp4Scrubber.scan(ByteArrayMp4Access(many)).clean)
    }

    @Test
    fun aFileOnDiskIsScrubbedInPlace() {
        val file = Files.createTempFile("mv2-mp4", ".mp4").toFile()
        try {
            file.writeBytes(mp4(listOf(mvhd, box("udta", box("©xyz", fourcc(location))))))
            assertFalse(Mp4Scrubber.scan(file).clean)
            val report = Mp4Scrubber.scrub(file)
            assertEquals(listOf("udta"), report.found)
            assertTrue(Mp4Scrubber.scan(file).clean)
            assertFalse(text(file.readBytes()).contains("SECRET"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun theWipeOfATemporaryFileOverwritesBeforeDeleting() {
        val file = File.createTempFile("mv2-out", ".tmp")
        file.writeBytes(ByteArray(200_000) { 0x5a })
        AttachmentPreparer.wipeFile(file)
        assertFalse(file.exists())
        // A missing file is not an error.
        AttachmentPreparer.wipeFile(file)
    }
}
