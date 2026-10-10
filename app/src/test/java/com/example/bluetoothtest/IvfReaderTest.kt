package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IvfReaderTest {
    private fun fixture(timestamps: LongArray, rate: Int = 30, scale: Int = 1): ByteArray {
        val data = ByteBuffer.allocate(32 + timestamps.size * 13).order(ByteOrder.LITTLE_ENDIAN)
            .put("DKIF".toByteArray()).putShort(0).putShort(32).put("VP90".toByteArray())
            .putShort(640).putShort(360).putInt(rate).putInt(scale).putInt(timestamps.size).putInt(0)
        timestamps.forEach { data.putInt(1).putLong(it).put(0x80.toByte()) }
        return data.array()
    }

    @Test
    fun rejectsNegativeOrDecreasingTimestamps() {
        for (timestamps in listOf(longArrayOf(-1), longArrayOf(1, 0))) {
            assertThrows(IllegalArgumentException::class.java) { IvfReader.read(fixture(timestamps)) }
        }
        assertEquals(listOf(0L, 0L), IvfReader.read(fixture(longArrayOf(0, 0))).frames.map { it.presentationTimeUs })
    }

    @Test
    fun rejectsFpsOutsideReturnedIntegerAndNanosecondTimingOverflow() {
        assertThrows(IllegalArgumentException::class.java) { IvfReader.read(fixture(longArrayOf(0), Int.MIN_VALUE)) }
        assertThrows(IllegalArgumentException::class.java) { IvfReader.read(fixture(longArrayOf(Long.MAX_VALUE))) }
    }

    @Test
    fun preservesRepresentableTimingWhenIntermediateRationalProductWouldOverflow() {
        val stream = IvfReader.read(fixture(longArrayOf(10_000_000), 30_000_000, 1_000_000))
        assertEquals(30, stream.fps)
        assertEquals(333_333_333_333L, stream.frames.single().presentationTimeUs)
    }

    @Test
    fun rejectsMalformedHeaderTruncationAndFrameCount() {
        val valid = fixture(longArrayOf(0))
        for (invalid in listOf(
            valid.copyOf(31), valid.copyOf(valid.size - 1),
            valid.clone().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 2) },
            valid.clone().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(6, 31) },
        )) {
            assertThrows(IllegalArgumentException::class.java) { IvfReader.read(invalid) }
        }
    }

    @Test
    fun readsVp9TimingAndPayload() {
        val bytes = ByteBuffer.allocate(32 + 12 + 3 + 12 + 2 + 12 + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("DKIF".toByteArray())
            .putShort(0)
            .putShort(32)
            .put("VP90".toByteArray())
            .putShort(640)
            .putShort(360)
            .putInt(15)
            .putInt(1)
            .putInt(3)
            .putInt(0)
            .putInt(3)
            .putLong(0)
            .put(byteArrayOf(1, 2, 3))
            .putInt(2)
            .putLong(1)
            .put(byteArrayOf(0x80.toByte(), 5))
            .putInt(1)
            .putLong(2)
            .put(0x84.toByte())
            .array()

        val stream = IvfReader.read(bytes)

        assertEquals(StreamProtocol.CODEC_VP9, stream.codecId)
        assertEquals(640, stream.width)
        assertEquals(360, stream.height)
        assertEquals(15, stream.fps)
        assertEquals(0, stream.frames[0].presentationTimeUs)
        assertEquals(66_666, stream.frames[1].presentationTimeUs)
        assertTrue(stream.frames[0].keyFrame)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 5), stream.frames[1].payload)
        assertTrue(stream.frames[1].keyFrame)
        assertFalse(stream.frames[2].keyFrame)
    }
}
