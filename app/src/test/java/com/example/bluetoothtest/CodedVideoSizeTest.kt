package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodedVideoSizeTest {
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun vp9KeyFrameFromRealEncodersGivesItsCodedSize() {
        // First 32 bytes of real keyframes (generated coverage input, 640x480): M31 OMX.Exynos.VP9 and S24 c2.exynos.vp9.
        listOf(
            "824983420027f01df41438241c18dc3bfdfeff7fbfdfefea3187221872118320",
            "824983426027f01df414301b877fbfdfeff7fbfdfd4630e4430e423064002108",
        ).forEach { frame ->
            assertEquals(640 to 480, CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_VP9, hex(frame)))
        }
    }

    @Test
    fun vp9KeyFramesOfEveryRungReportTheirOwnSize() {
        listOf(426 to 240, 640 to 360, 854 to 480, 960 to 540, 1280 to 720).forEach { (width, height) ->
            assertEquals(
                width to height,
                CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_VP9, SizeChangeFixtures.vp9KeyFrame(width, height)),
            )
        }
    }

    @Test
    fun vp9InterFramesAndGarbageGiveNoSize() {
        assertNull(CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_VP9, SizeChangeFixtures.vp9InterFrame()))
        assertNull(CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_VP9, byteArrayOf()))
        assertNull(CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_VP9, byteArrayOf(1, 2, 3)))
        assertNull(CodedVideoSize.ofKeyFrame(0, SizeChangeFixtures.vp9KeyFrame(640, 360)))
    }

    @Test
    fun av1KeyFrameGivesTheSequenceHeaderSize() {
        listOf(426 to 240, 640 to 360, 960 to 540, 1280 to 720).forEach { (width, height) ->
            assertEquals(
                width to height,
                CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_AV1, SizeChangeFixtures.av1KeyFrame(width, height)),
            )
        }
    }

    @Test
    fun av1WithoutAnInBandSequenceHeaderUsesTheNewestCodecConfiguration() {
        val keyFrame = SizeChangeFixtures.av1KeyFrame(640, 360, sequenceHeaderInBand = false)
        assertNull(CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_AV1, keyFrame))
        val configs = listOf(SizeChangeFixtures.av1Config(1280, 720), SizeChangeFixtures.av1Config(640, 360))
        assertEquals(640 to 360, CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_AV1, keyFrame, configs))
        assertEquals(640 to 360, CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_AV1, keyFrame, listOf(SizeChangeFixtures.av1cConfig(640, 360))))
        assertEquals(640 to 360, CodedVideoSize.ofCodecConfig(StreamProtocol.CODEC_AV1, configs))
        assertNull(CodedVideoSize.ofCodecConfig(StreamProtocol.CODEC_VP9, configs))
    }

    @Test
    fun av1InBandHeaderWinsOverStaleConfiguration() {
        val keyFrame = SizeChangeFixtures.av1KeyFrame(640, 360)
        assertEquals(
            640 to 360,
            CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_AV1, keyFrame, listOf(SizeChangeFixtures.av1Config(1280, 720))),
        )
    }

    @Test
    fun truncatedAv1DataGivesNoSize() {
        val keyFrame = SizeChangeFixtures.av1KeyFrame(640, 360)
        assertNull(CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_AV1, keyFrame.copyOf(5)))
        assertNull(CodedVideoSize.ofKeyFrame(StreamProtocol.CODEC_AV1, byteArrayOf(0xff.toByte(), 0, 0)))
    }
}
