package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design HC with both masters off: every VP9/AV1 decision, summary byte and header check is exactly today's. The
 * two-codec functions ([AutomaticEncoderChoice], [privateCallCodecForPeer]) are the oracle.
 */
class HcLegacyEquivalenceTest {
    @After
    fun reset() {
        VideoCodecSwitches.hevcOverride = null
        VideoCodecSwitches.h264Override = null
    }

    private fun codec(name: String, protocolId: Int, hardware: Boolean, backend: VideoEncoderBackend = VideoEncoderBackend.MEDIA_CODEC) =
        VideoCodec(
            displayName = name, mimeType = "video/x", protocolId = protocolId, encoderName = name,
            hardwareAccelerated = hardware, usesSurfaceInput = hardware, bitrateMode = null, supportedBitrateModes = emptySet(),
            complexity = null, backend = backend,
        )

    private val hwAv1 = codec("c2.vendor.av1.encoder", StreamProtocol.CODEC_AV1, true)
    private val hwVp9 = codec("c2.exynos.vp9.encoder", StreamProtocol.CODEC_VP9, true)
    private val libaom = codec("libaom-native", StreamProtocol.CODEC_AV1, false, VideoEncoderBackend.NATIVE_LIBAOM)
    private val swAv1 = codec("c2.android.av1.encoder", StreamProtocol.CODEC_AV1, false)
    private val swVp9 = codec("c2.android.vp9.encoder", StreamProtocol.CODEC_VP9, false)

    private val phones = listOf(
        listOf(hwAv1, hwVp9, libaom, swAv1),
        listOf(hwVp9, libaom, swAv1),
        listOf(libaom, swAv1, swVp9),
        listOf(hwAv1, libaom),
        listOf(libaom),
    ).map { it.sortedBy(VideoCodec::selectionPriority) }

    private val peerMasks = (0..3).flatMap { codecs ->
        listOf(codecs, codecs or NodeCapabilities.DECODE_PREFERS_VP9)
    }

    @Test
    fun callAndStreamChoicesAreTodaysWithBothMastersOff() {
        for (phone in phones) for (prefer in listOf(true, false)) for (excludeAv1 in listOf(false, true)) {
            val switches = CodecChoiceSwitches.LEGACY.copy(preferHwAv1 = prefer)
            val byName: (String) -> Boolean = { name -> excludeAv1 && name == hwAv1.encoderName }
            val byCodec: (VideoCodec) -> Boolean = { byName(it.encoderName) }
            val oldOrder = AutomaticEncoderChoice.order(phone, prefer, byName)
            assertEquals(oldOrder, VideoCodecChoice.pickerOrder(phone, switches, CodecSession(640, 360, 15), byCodec))
            assertEquals(oldOrder, VideoCodecChoice.automatic(phone, CodecChoiceMode.CALL, switches, null, byCodec))
            for (peer in peerMasks) for (picked in listOf<VideoCodec?>(null) + phone) {
                val requested = picked ?: oldOrder.first()
                val label = "phone=${phone.map { it.encoderName }} prefer=$prefer exclude=$excludeAv1 peer=$peer picked=${picked?.encoderName}"
                val oldSent = privateCallCodecForPeer(requested, phone, peer)
                val choice = VideoCodecChoice.forCall(requested, picked != null, phone, peer, switches, familyVp9 = true, session = null, excluded = byCodec)
                assertEquals(label, oldSent, choice.sent)
                if (oldSent != null) {
                    val oldReason = AutomaticEncoderChoice.reason(oldSent, requested, phone, picked != null, prefer, peer, byName)
                    assertEquals(label, oldReason.label, choice.reason)
                    assertEquals(
                        label,
                        AutomaticEncoderChoice.logLine("call", oldSent, oldReason, prefer, peer),
                        VideoCodecChoice.logLine("call", choice, switches, peer),
                    )
                }
            }
            val stream = VideoCodecChoice.forStream(oldOrder.first(), false, phone, switches, null, byCodec)
            val oldStreamReason = AutomaticEncoderChoice.reason(oldOrder.first(), oldOrder.first(), phone, false, prefer, null, byName)
            assertEquals(
                AutomaticEncoderChoice.logLine("stream", oldOrder.first(), oldStreamReason, prefer, null),
                VideoCodecChoice.logLine("stream", stream, switches, null),
            )
        }
    }

    @Test
    fun summariesAndDecodabilityAreTodaysForTwoCodecMasks() {
        for (encode in 0..3) for (decode in 0..3) {
            assertEquals(encode and decode, VideoCodecMap.helloMaskToSummary(encode and decode))
        }
        for (summary in 0..0xff) for (local in 0..3) {
            val old = summary != 0 && summary and local != 0
            assertEquals("summary=$summary local=$local", old, VideoCodecMap.summaryDecodable(summary, local))
        }
        assertEquals(NodeCapabilities.CODEC_VP9, codecCapabilityMask(StreamProtocol.CODEC_VP9))
        assertEquals(NodeCapabilities.CODEC_AV1, codecCapabilityMask(StreamProtocol.CODEC_AV1))
        assertEquals(0, codecCapabilityMask(7))
        // The endpoint decode mask is unchanged when bit 4 is disabled (masters off).
        for (mask in 0..0xff) assertEquals(mask, withAv1SoftwareDecodeBit(mask, av1DecoderHardware = false, enabled = false))
    }

    @Test
    fun theHeaderCheckAcceptsCodecs4And5OnlyWithTheirMasters() {
        fun header(codecId: Int): ByteArray = ByteArrayOutputStream().also {
            StreamProtocol.writeHeader(it, codecId, VideoSettings.DEFAULT)
        }.toByteArray()
        VideoCodecSwitches.hevcOverride = false
        VideoCodecSwitches.h264Override = false
        for (codecId in listOf(StreamProtocol.CODEC_H264, StreamProtocol.CODEC_HEVC, 1, 6)) {
            val error = runCatching { StreamProtocol.readHeader(ByteArrayInputStream(header(codecId))) }.exceptionOrNull()
            assertTrue("codec $codecId", error is IllegalArgumentException && error.message == "Unsupported codec ID $codecId")
        }
        assertEquals(StreamProtocol.CODEC_VP9, StreamProtocol.readHeader(ByteArrayInputStream(header(StreamProtocol.CODEC_VP9))).codecId)
        VideoCodecSwitches.hevcOverride = true
        assertEquals(StreamProtocol.CODEC_HEVC, StreamProtocol.readHeader(ByteArrayInputStream(header(StreamProtocol.CODEC_HEVC))).codecId)
        assertTrue(runCatching { StreamProtocol.readHeader(ByteArrayInputStream(header(StreamProtocol.CODEC_H264))) }.isFailure)
        VideoCodecSwitches.h264Override = true
        assertEquals(StreamProtocol.CODEC_H264, StreamProtocol.readHeader(ByteArrayInputStream(header(StreamProtocol.CODEC_H264))).codecId)
    }
}
