package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

// Fixed 360p/300 kbps fixture: these expectations must not follow the user default (VideoSettings.DEFAULT).
private val SETTINGS_360P = VideoSettings(VideoSettings.RESOLUTIONS[1], fps = 15, bitRate = 300_000, latencyMs = 500)

class StreamProtocolTest {
    @Test
    fun writesHeaderInNetworkByteOrder() {
        val settings = VideoSettings(
            VideoResolution(1280, 720, "720p"),
            fps = 24,
            bitRate = 1_200_000,
            latencyMs = 1_500,
        )
        val bytes = ByteArrayOutputStream().also {
            StreamProtocol.writeHeader(it, 3, settings, rotationDegrees = 90, includeAudio = true)
        }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))

        assertEquals("BTV3", String(input.readNBytes(4), Charsets.US_ASCII))
        assertEquals(1280, input.readUnsignedShort())
        assertEquals(720, input.readUnsignedShort())
        assertEquals(24, input.readUnsignedByte())
        assertEquals(3, input.readUnsignedByte())
        assertEquals(1, input.readUnsignedByte())
        assertEquals(StreamProtocol.AUDIO_CODEC_OPUS, input.readUnsignedByte())
        assertEquals(StreamProtocol.AUDIO_SAMPLE_RATE, input.readUnsignedShort())
        assertEquals(StreamProtocol.AUDIO_CHANNEL_COUNT, input.readUnsignedByte())
    }

    @Test
    fun sizesQueueFromLatencyBudget() {
        assertEquals(20, SETTINGS_360P.copy(fps = 30, latencyMs = 250).queueCapacity)
        assertEquals(210, SETTINGS_360P.copy(fps = 20, latencyMs = 3_000).queueCapacity)
        assertEquals(33, SETTINGS_360P.queueCapacity)
        assertEquals(60, SETTINGS_360P.keyFrameIntervalSeconds)
    }

    @Test
    fun writesFramedPacket() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x65)
        val bytes = ByteArrayOutputStream().also {
            StreamProtocol.writePacket(it, StreamProtocol.FLAG_KEY_FRAME, 42L, payload)
        }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))

        assertEquals(payload.size, input.readInt())
        assertEquals(StreamProtocol.FLAG_KEY_FRAME, input.readUnsignedByte())
        assertEquals(42L, input.readLong())
        assertArrayEquals(payload, input.readNBytes(payload.size))
    }

    @Test
    fun readsHeaderAndPacketWrittenBySender() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = ByteArrayOutputStream().also {
            StreamProtocol.writeHeader(
                it,
                StreamProtocol.CODEC_AV1,
                SETTINGS_360P,
                rotationDegrees = 270,
                includeAudio = true,
            )
            StreamProtocol.writePacket(it, StreamProtocol.FLAG_KEY_FRAME, 123_456L, payload)
        }.toByteArray()
        val input = ByteArrayInputStream(bytes)

        assertEquals(
            StreamProtocol.Header(
                640,
                360,
                15,
                StreamProtocol.CODEC_AV1,
                270,
                StreamProtocol.AUDIO_CODEC_OPUS,
                StreamProtocol.AUDIO_SAMPLE_RATE,
                StreamProtocol.AUDIO_CHANNEL_COUNT,
            ),
            StreamProtocol.readHeader(input),
        )
        val packet = StreamProtocol.readPacket(input)
        assertEquals(StreamProtocol.FLAG_KEY_FRAME, packet.flags)
        assertEquals(123_456L, packet.presentationTimeUs)
        assertArrayEquals(payload, packet.payload)
    }

    @Test
    fun rejectsRemovedLegacyHeader() {
        val bytes = byteArrayOf(
            'B'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(),
            0x02, 0x80.toByte(), 0x01, 0x68, 15, StreamProtocol.CODEC_VP9.toByte(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            StreamProtocol.readHeader(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun carriesDisplayRotationChangesWithoutVideoReencoding() {
        val payload = StreamProtocol.encodeDisplayRotation(90)
        val packet = StreamProtocol.Packet(StreamProtocol.FLAG_DISPLAY_ROTATION, 0, payload)

        assertEquals(90, StreamProtocol.decodeDisplayRotation(packet))
    }

    @Test
    fun distinguishesAudioPacketsInTheSameMediaStream() {
        val packet = StreamProtocol.Packet(
            StreamProtocol.FLAG_AUDIO,
            123_000,
            byteArrayOf(1, 2, 3),
        )

        assertEquals(true, packet.isAudio)
    }

    @Test
    fun pairsTwoOpusAccessUnitsWithoutChangingTheirTimingOrBytes() {
        val first = StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 1_000_000, byteArrayOf(1, 2, 3))
        val second = StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 1_020_000, byteArrayOf(4, 5))

        val paired = StreamProtocol.pairAudioPackets(first, second)
        val split = StreamProtocol.splitAudioPair(paired)

        assertEquals(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR, paired.flags)
        assertEquals(first.flags, split[0].flags)
        assertEquals(first.presentationTimeUs, split[0].presentationTimeUs)
        assertArrayEquals(first.payload, split[0].payload)
        assertEquals(second.flags, split[1].flags)
        assertEquals(second.presentationTimeUs, split[1].presentationTimeUs)
        assertArrayEquals(second.payload, split[1].payload)
    }

    @Test
    fun batchesTwentyOpusAccessUnitsWithoutChangingTheirTimingOrBytes() {
        val packets = List(20) { index ->
            StreamProtocol.Packet(
                StreamProtocol.FLAG_AUDIO,
                1_000_000L + index * 20_000L,
                byteArrayOf(index.toByte(), (index + 1).toByte()),
            )
        }

        val batched = StreamProtocol.batchAudioPackets(packets)
        val split = StreamProtocol.splitAudioPair(batched)

        assertEquals(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR, batched.flags)
        assertEquals(packets.size, split.size)
        packets.zip(split).forEach { (expected, actual) ->
            assertEquals(expected.flags, actual.flags)
            assertEquals(expected.presentationTimeUs, actual.presentationTimeUs)
            assertArrayEquals(expected.payload, actual.payload)
        }
    }

    @Test
    fun rejectsTruncatedOrNonAudioPairs() {
        val first = StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 1_000_000, byteArrayOf(1, 2, 3))
        val second = StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 1_020_000, byteArrayOf(4, 5))
        val paired = StreamProtocol.pairAudioPackets(first, second)

        assertThrows(IllegalArgumentException::class.java) {
            StreamProtocol.pairAudioPackets(first.copy(flags = 0), second)
        }
        assertThrows(Exception::class.java) {
            StreamProtocol.splitAudioPair(paired.copy(payload = paired.payload.copyOf(paired.payload.size - 1)))
        }
    }

    @Test
    fun provesAndRestoresExactEncodedAccessUnit() {
        val original = StreamProtocol.Packet(
            StreamProtocol.FLAG_KEY_FRAME,
            456_000,
            byteArrayOf(4, 8, 15, 16, 23, 42),
        )

        val protected = StreamProtocol.attachDebugIntegrity(original)
        val result = StreamProtocol.verifyAndStripDebugIntegrity(protected)

        assertEquals(true, result.verified)
        assertEquals(original.flags, result.packet.flags)
        assertEquals(original.presentationTimeUs, result.packet.presentationTimeUs)
        assertArrayEquals(original.payload, result.packet.payload)
    }

    @Test
    fun rejectsChangedEncodedAccessUnit() {
        val protected = StreamProtocol.attachDebugIntegrity(
            StreamProtocol.Packet(0, 123_000, byteArrayOf(1, 2, 3, 4)),
        )
        protected.payload[protected.payload.lastIndex] = 5

        assertThrows(IllegalArgumentException::class.java) {
            StreamProtocol.verifyAndStripDebugIntegrity(protected)
        }
    }
}
