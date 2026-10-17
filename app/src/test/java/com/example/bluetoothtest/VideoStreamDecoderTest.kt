package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStreamDecoderTest {
    @Test
    fun av1UsesOnlyTheMostRecentCodecConfiguration() {
        val first = byteArrayOf(1, 2)
        val final = byteArrayOf(3, 4)

        val selected = decoderCodecSpecificData(StreamProtocol.CODEC_AV1, listOf(first, final))

        assertEquals(1, selected.size)
        assertArrayEquals(final, selected.single())
    }

    @Test
    fun vp9PreservesCodecConfigurationOrder() {
        val configuration = listOf(byteArrayOf(1), byteArrayOf(2))

        assertEquals(configuration, decoderCodecSpecificData(StreamProtocol.CODEC_VP9, configuration))
    }

    @Test
    fun vp9DoesNotSubmitTheSameConfigurationFromBothEncoderCallbacks() {
        val configuration = byteArrayOf(1, 2, 3)
        val selected = decoderCodecSpecificData(
            StreamProtocol.CODEC_VP9,
            listOf(configuration, configuration.copyOf()),
        )

        assertEquals(1, selected.size)
        assertArrayEquals(configuration, selected.single())
    }

    @Test
    fun aTransientEmptyOutputSizeIsIgnoredAndRealSizesPass() {
        assertEquals(null, validOutputSize(0, 0))
        assertEquals(null, validOutputSize(640, 0))
        assertEquals(null, validOutputSize(0, 480))
        assertEquals(null, validOutputSize(-1, 480))
        assertEquals(640 to 480, validOutputSize(640, 480))
        // A crop rectangle that collapses (right < left) is not a picture either.
        assertEquals(null, validOutputSize(10 - 20 + 1, 100))
    }
}
