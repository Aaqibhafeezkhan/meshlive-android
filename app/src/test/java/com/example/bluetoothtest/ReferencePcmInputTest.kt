package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReferencePcmInputTest {
    @Test fun nativeVideoRejectsChangedGeometryEvenWithTheSameByteCountAndRejectsChangedFps() {
        val requested = VideoSettings.DEFAULT
        for (effective in listOf(
            requested.copy(resolution = VideoResolution(360, 640, "rotated")),
            requested.copy(resolution = VideoResolution(352, 288, "negotiated")),
            requested.copy(fps = 7),
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                requireNativeRawVideoMode(requested, effective)
            }
            org.junit.Assert.assertTrue(error.message!!.contains("no conversion"))
        }
    }

    @Test fun nativeVideoDoesNotReinterpretPixelsWhenOnlyRateOrBufferNegotiationChanges() {
        val requested = VideoSettings.DEFAULT
        requireNativeRawVideoMode(requested, requested.copy(bitRate = 110_000, latencyMs = 1_000))
    }

    @Test fun referenceAudioUsesTheRawMonotonicScheduleAndSeparateWirePts() {
        val clock = RawReferenceClock(99_000_000L, 5_000_000_000L)
        assertEquals(0L, clock.duePacketSample(4_000_000_000L))
        assertEquals(0L, clock.duePacketSample(5_019_999_999L))
        assertEquals(960L, clock.duePacketSample(5_020_000_000L))
        assertEquals(5_040_000_000L, clock.packetDeadlineNs(1920))
        // A boot-clock PTS origin never changes the monotonic pacing calculation.
        assertEquals(clock.duePacketSample(5_100_000_000L), clock.copy(ptsOriginUs = 199_000_000L).duePacketSample(5_100_000_000L))
    }
    private fun wav(rate: Int = 48_000, channels: Int = 1, pcm: ByteArray = byteArrayOf(1, 2, 3, 4)): File {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
        header.putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return File.createTempFile("reference-pcm-", ".wav").apply { writeBytes(header.array() + pcm) }
    }

    @Test fun packetsCrossLoopSeamWithoutPaddingAndSeekIsExact() {
        val file = wav()
        try {
            ReferencePcmInput(file).use { source ->
                assertEquals(2L, source.sampleCount)
                val packet = ByteArray(6)
                source.readPacket(packet)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4, 1, 2), packet)
                source.seekSample(1)
                source.readPacket(packet)
                assertArrayEquals(byteArrayOf(3, 4, 1, 2, 3, 4), packet)
            }
        } finally { file.delete() }
    }

    @Test fun formatSubstitutionAndTruncatedRiffAreRejected() {
        for (file in listOf(wav(rate = 44_100), wav(channels = 2), wav().apply { appendBytes(byteArrayOf(0)) })) {
            try { assertThrows(IllegalArgumentException::class.java) { ReferencePcmInput(file) } }
            finally { file.delete() }
        }
    }

    @Test fun fractionalLoopAndWrongVideoDurationAreRejected() {
        val file = wav(pcm = ByteArray(3_200))
        try {
            ReferencePcmInput(file).use { source ->
                source.requireVideoTimeline(1, 30)
                assertThrows(IllegalArgumentException::class.java) { source.requireVideoTimeline(1, 7) }
                assertThrows(IllegalArgumentException::class.java) { source.requireVideoTimeline(2, 30) }
            }
        } finally { file.delete() }
    }
}
