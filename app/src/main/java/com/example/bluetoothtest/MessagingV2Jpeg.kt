package com.example.bluetoothtest

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/*
 * Messaging v2 phase 1b: "Original" photos (DECISIONS Design M3 "Photos on send"). A JPEG that is sent unchanged is first
 * passed through a segment filter: every APPn and COM segment is dropped (EXIF with its embedded thumbnail and GPS,
 * MakerNote, XMP, MPF, ICC unless asked for, comments), everything after the end-of-image marker is dropped (motion-photo
 * videos, second pictures, appended archives), and a minimal orientation tag is written back so the picture still shows
 * upright. No pixel is decoded. Pure and streaming: bounded memory whatever the size of the file.
 */

/** The bytes are not a well-formed JPEG for the purposes of the filter; the caller re-encodes the picture instead. */
class JpegFormatException(message: String) : IOException(message)

/** EXIF orientation values 1..8: how the stored pixels must be turned to be shown upright. */
object ExifOrientation {
    const val NORMAL = 1

    /** True for the orientations that swap width and height (5, 6, 7, 8). */
    fun swapsAxes(orientation: Int): Boolean = orientation in 5..8

    /**
     * Clockwise rotation in degrees applied after the mirror (EXIF 2.32: 5 is "mirror horizontal and rotate 270 CW" = a
     * transpose, 7 is "mirror horizontal and rotate 90 CW" = a transverse).
     */
    fun rotationDegrees(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        6, 7 -> 90
        5, 8 -> 270
        else -> 0
    }

    /** Mirrored left to right before the rotation (2, 4, 5, 7). */
    fun mirrored(orientation: Int): Boolean = orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7

    /**
     * Where the stored pixel (x, y) of a [width] x [height] picture lands in the upright picture: mirror first, then rotate
     * clockwise. Used by the tests as the definition the Android matrix must agree with.
     */
    fun mapPoint(orientation: Int, x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> {
        var px = x
        var py = y
        var w = width
        var h = height
        if (mirrored(orientation)) px = w - 1 - px
        when (rotationDegrees(orientation)) {
            90 -> { val nx = h - 1 - py; val ny = px; px = nx; py = ny; val t = w; w = h; h = t }
            180 -> { px = w - 1 - px; py = h - 1 - py }
            270 -> { val nx = py; val ny = w - 1 - px; px = nx; py = ny; val t = w; w = h; h = t }
        }
        return px to py
    }

    fun valid(orientation: Int): Boolean = orientation in 1..8

    /** The EXIF orientation of the first `Exif` APP1 segment before the first scan, or [NORMAL]; never throws. */
    fun readFromJpeg(input: InputStream): Int {
        try {
            val reader = JpegSegments(input)
            reader.expectSoi()
            var segments = 0
            while (segments++ < JpegSegments.MAX_SEGMENTS) {
                val marker = reader.nextMarker() ?: break
                if (marker == 0xda || marker == 0xd9) break
                if (JpegSegments.standalone(marker)) continue
                val payload = reader.readPayload()
                if (marker == 0xe1) parseExif(payload)?.let { return it }
            }
        } catch (error: IOException) {
            // Not a JPEG the reader can follow: the orientation is simply unknown.
        }
        return NORMAL
    }

    /** Orientation from an `Exif\0\0` APP1 payload (without marker and length), or null. */
    fun parseExif(payload: ByteArray): Int? {
        if (payload.size < 14 || String(payload, 0, 4, Charsets.ISO_8859_1) != "Exif" || payload[4] != 0.toByte() || payload[5] != 0.toByte()) return null
        val tiff = 6
        val bigEndian = when {
            payload[tiff] == 'M'.code.toByte() && payload[tiff + 1] == 'M'.code.toByte() -> true
            payload[tiff] == 'I'.code.toByte() && payload[tiff + 1] == 'I'.code.toByte() -> false
            else -> return null
        }
        fun u16(o: Int): Int? = if (o < 0 || o + 2 > payload.size) null else {
            val a = payload[o].toInt() and 0xff
            val b = payload[o + 1].toInt() and 0xff
            if (bigEndian) (a shl 8) or b else (b shl 8) or a
        }
        fun u32(o: Int): Long? {
            val hi = u16(o) ?: return null
            val lo = u16(o + 2) ?: return null
            return if (bigEndian) (hi.toLong() shl 16) or lo.toLong() else (lo.toLong() shl 16) or hi.toLong()
        }
        if (u16(tiff + 2) != 42) return null
        val ifd = u32(tiff + 4) ?: return null
        if (ifd < 8 || ifd > payload.size) return null
        val start = tiff + ifd.toInt()
        val count = u16(start) ?: return null
        if (count > 512) return null
        for (i in 0 until count) {
            val entry = start + 2 + i * 12
            val tag = u16(entry) ?: return null
            if (tag != 0x0112) continue
            if (u16(entry + 2) != 3 || u32(entry + 4) != 1L) return null
            val value = u16(entry + 8) ?: return null
            return value.takeIf(::valid)
        }
        return null
    }

    /** The smallest legal `Exif` APP1 segment (marker, length, TIFF header, one IFD0 entry): 36 bytes. */
    fun orientationSegment(orientation: Int): ByteArray {
        require(valid(orientation))
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // header, IFD0 at offset 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, count 1, value
            0, 0, 0, 0, // no next IFD
        )
        val payload = "Exif".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        return byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }
}

/** Marker-level reading shared by the orientation reader and the filter. */
internal class JpegSegments(private val input: InputStream) {
    private val buffer = ByteArray(65_536)
    private var position = 0
    private var limit = 0
    var offset = 0L
        private set

    private fun fill(): Boolean {
        if (position < limit) return true
        limit = input.read(buffer, 0, buffer.size)
        position = 0
        if (limit <= 0) {
            limit = 0
            return false
        }
        return true
    }

    /** Next byte, or -1 at the end of the input. */
    fun read(): Int {
        if (!fill()) return -1
        offset++
        return buffer[position++].toInt() and 0xff
    }

    fun readFully(length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            if (!fill()) throw JpegFormatException("Truncated JPEG segment")
            val take = minOf(length - done, limit - position)
            System.arraycopy(buffer, position, out, done, take)
            position += take
            offset += take
            done += take
        }
        return out
    }

    /** Reads and counts everything that is left. */
    fun drain(): Long {
        var count = 0L
        while (true) {
            if (!fill()) return count
            count += limit - position
            offset += limit - position
            position = limit
        }
    }

    fun expectSoi() {
        if (read() != 0xff || read() != 0xd8) throw JpegFormatException("Not a JPEG")
    }

    /** The next marker byte after any run of 0xFF fill bytes, or null at a clean end of input. */
    fun nextMarker(): Int? {
        var b = read()
        if (b == -1) return null
        if (b != 0xff) throw JpegFormatException("Expected a marker at $offset")
        while (b == 0xff) b = read()
        if (b == -1) throw JpegFormatException("Truncated JPEG marker")
        if (b == 0) throw JpegFormatException("Stuffed zero where a marker was expected")
        return b
    }

    /** Length-prefixed payload of the current marker (the length counts its own two bytes). */
    fun readPayload(): ByteArray {
        val high = read()
        val low = read()
        if (high < 0 || low < 0) throw JpegFormatException("Truncated JPEG segment length")
        val length = (high shl 8) or low
        if (length < 2) throw JpegFormatException("Invalid JPEG segment length")
        return readFully(length - 2)
    }

    companion object {
        const val MAX_SEGMENTS = 20_000

        /** Markers without a length: TEM, RSTn, SOI, EOI. */
        fun standalone(marker: Int): Boolean = marker == 0x01 || marker in 0xd0..0xd8 || marker == 0xd9
    }
}

object JpegMetadataFilter {
    class Report(
        val bytesOut: Long,
        val orientation: Int,
        val segmentsRemoved: Int,
        val trailingBytesDropped: Long,
    )

    /**
     * Copies the JPEG from [source] to [output] without any APPn or COM segment and without anything after the end of
     * image. Two segments stay because they are not metadata about the person or the device: the `Adobe` APP14 segment
     * (12 bytes: a colour-transform flag some CMYK and YCCK pictures need) and, with [keepIcc], the ICC profile. When
     * the picture had an orientation other than 1, a minimal orientation tag is written first. Throws
     * [JpegFormatException] for anything that is not a complete JPEG; the output is then unusable and the caller
     * re-encodes instead.
     */
    fun filter(source: () -> InputStream, output: OutputStream, keepIcc: Boolean = false): Report {
        val orientation = source().use { ExifOrientation.readFromJpeg(it) }
        val counting = CountingOutput(output)
        var removed = 0
        var trailing = 0L
        source().use { raw ->
            val reader = JpegSegments(raw)
            reader.expectSoi()
            counting.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
            if (orientation != ExifOrientation.NORMAL) counting.write(ExifOrientation.orientationSegment(orientation))
            var segments = 0
            var sawScan = false
            var marker = reader.nextMarker() ?: throw JpegFormatException("Empty JPEG")
            while (true) {
                if (segments++ > JpegSegments.MAX_SEGMENTS) throw JpegFormatException("Too many JPEG segments")
                if (marker == 0xd9) {
                    if (!sawScan) throw JpegFormatException("JPEG without image data")
                    counting.write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
                    // Everything after the first complete image is dropped, counting it for the report.
                    trailing = reader.drain()
                    break
                }
                if (JpegSegments.standalone(marker)) {
                    if (marker != 0xd8) counting.write(byteArrayOf(0xff.toByte(), marker.toByte()))
                    marker = reader.nextMarker() ?: throw JpegFormatException("JPEG without end of image")
                    continue
                }
                val payload = reader.readPayload()
                val drop = isMetadata(marker, payload, keepIcc)
                if (drop) removed++ else {
                    val length = payload.size + 2
                    counting.write(byteArrayOf(0xff.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()))
                    counting.write(payload)
                }
                if (marker == 0xda) {
                    sawScan = true
                    marker = copyEntropyData(reader, counting)
                } else {
                    marker = reader.nextMarker() ?: throw JpegFormatException("JPEG without end of image")
                }
            }
        }
        counting.flush()
        return Report(counting.count, orientation, removed, trailing)
    }

    /** The size [filter] would write, without keeping the bytes. */
    fun filteredSize(source: () -> InputStream, keepIcc: Boolean = false): Long =
        filter(source, object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }, keepIcc).bytesOut

    private fun isMetadata(marker: Int, payload: ByteArray, keepIcc: Boolean): Boolean = when {
        marker == 0xfe -> true
        marker in 0xe0..0xef -> {
            val adobe = marker == 0xee && payload.size == 12 && String(payload, 0, 5, Charsets.ISO_8859_1) == "Adobe"
            val icc = marker == 0xe2 && payload.size > 12 && String(payload, 0, 11, Charsets.ISO_8859_1) == "ICC_PROFILE"
            !(adobe || (keepIcc && icc))
        }
        else -> false
    }

    /**
     * Copies the entropy-coded data of one scan up to the next real marker (byte stuffing and restart markers stay) and
     * returns that marker.
     */
    private fun copyEntropyData(reader: JpegSegments, out: CountingOutput): Int {
        val chunk = ByteArray(4096)
        var used = 0
        fun flush() {
            if (used > 0) {
                out.write(chunk, 0, used)
                used = 0
            }
        }
        while (true) {
            val b = reader.read()
            if (b == -1) throw JpegFormatException("Truncated JPEG scan")
            if (b != 0xff) {
                if (used == chunk.size) flush()
                chunk[used++] = b.toByte()
                continue
            }
            var next = reader.read()
            while (next == 0xff) next = reader.read()
            if (next == -1) throw JpegFormatException("Truncated JPEG scan")
            if (next == 0 || next in 0xd0..0xd7) {
                if (used + 2 > chunk.size) flush()
                chunk[used++] = 0xff.toByte()
                chunk[used++] = next.toByte()
                continue
            }
            flush()
            return next
        }
    }

    private class CountingOutput(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
    }
}
