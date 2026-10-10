package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OpusCodecConfigTest {
    private fun opusHead(preSkip: Int): ByteArray = ByteArray(19).also { head ->
        "OpusHead".forEachIndexed { index, c -> head[index] = c.code.toByte() }
        head[8] = 1
        head[9] = 1
        head[10] = (preSkip and 0xff).toByte()
        head[11] = (preSkip shr 8).toByte()
    }

    private fun long(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun marker(name: String, value: ByteArray) =
        name.toByteArray(Charsets.US_ASCII) + long(value.size.toLong()) + value

    @Test
    fun aBareOpusHeadGetsTheDocumentedDelayAndPreroll() {
        // 312 samples of pre-skip at 48 kHz = 6.5 ms.
        val head = opusHead(312)
        val config = OpusCodecConfig.normalize(listOf(head))
        assertEquals(3, config.size)
        assertArrayEquals(head, config[0])
        assertArrayEquals(long(6_500_000L), config[1])
        assertArrayEquals(long(OpusCodecConfig.DEFAULT_PREROLL_NS), config[2])
    }

    @Test
    fun aCombinedMarkerBlockIsSplitIntoThreeBuffers() {
        val head = opusHead(312)
        val block = marker("AOPUSHDR", head) + marker("AOPUSDLY", long(6_500_000L)) + marker("AOPUSPRL", long(80_000_000L))
        val config = OpusCodecConfig.normalize(listOf(block))
        assertArrayEquals(head, config[0])
        assertArrayEquals(long(6_500_000L), config[1])
        assertArrayEquals(long(80_000_000L), config[2])
    }

    @Test
    fun threeDocumentedBuffersPassThroughUnchanged() {
        val parts = listOf(opusHead(0), long(1L), long(2L))
        assertEquals(parts, OpusCodecConfig.normalize(parts))
    }
}
