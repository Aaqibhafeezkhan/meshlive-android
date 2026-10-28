package com.example.bluetoothtest

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design HC encoder eligibility from declared capabilities, the IDR request deadline and the ladder rows. */
class HcEncoderEligibilityTest {
    private val envelope = EncoderEnvelope(
        minWidth = 96, maxWidth = 1920, minHeight = 96, maxHeight = 1088, widthAlignment = 2, heightAlignment = 2,
        fpsPoints = listOf(FpsPoint(640, 360, 60.0, "pp"), FpsPoint(854, 480, 30.0, "pp"), FpsPoint(1280, 720, 24.0, "achievable")),
    )

    private fun hc(name: String, protocolId: Int, hardware: Boolean, fps: Double) = VideoCodec(
        displayName = name, mimeType = "video/avc", protocolId = protocolId, encoderName = name,
        hardwareAccelerated = hardware, usesSurfaceInput = true, bitrateMode = EncoderCapabilities.BITRATE_MODE_CBR,
        supportedBitrateModes = setOf(EncoderCapabilities.BITRATE_MODE_CBR), complexity = null,
        automaticBlock = if (hardware) null else HcEncoderEligibility.BLOCK_SOFTWARE,
        envelope = envelope.copy(fpsPoints = EncoderEnvelope.SAMPLE_SIZES.map { (w, h) -> FpsPoint(w, h, fps, "pp") }),
    )

    @Test
    fun theRequestedLevelIsTheSessionsNotTheEncodersMaximum() {
        val hevc = StreamProtocol.CODEC_HEVC
        val avc = StreamProtocol.CODEC_H264
        // Exynos and Qualcomm HEVC encoders list Main profile up to High tier level 6 (the smoke's general_level_idc 180).
        val hevcMax = CodecProfileLevel.HEVCHighTierLevel6
        val call = HcEncoderEligibility.levelFor(hevc, hevcMax, 640, 480, 15, 400_000)
        assertEquals(CodecProfileLevel.HEVCMainTierLevel3, call)
        assertEquals(90, HcEncoderEligibility.levelIdc(hevc, call))
        assertEquals(180, HcEncoderEligibility.levelIdc(hevc, hevcMax))
        assertEquals(CodecProfileLevel.HEVCMainTierLevel3, HcEncoderEligibility.levelFor(hevc, hevcMax, 854, 480, 30, 2_000_000))
        assertEquals(CodecProfileLevel.HEVCMainTierLevel31, HcEncoderEligibility.levelFor(hevc, hevcMax, 1280, 720, 30, 4_000_000))
        // The bitrate counts too: 640x480 above level 3's 6 Mbit/s needs 3.1.
        assertEquals(CodecProfileLevel.HEVCMainTierLevel31, HcEncoderEligibility.levelFor(hevc, hevcMax, 640, 480, 15, 8_000_000))
        // Never above what the encoder lists; an unknown constant is passed through.
        assertEquals(CodecProfileLevel.HEVCMainTierLevel21, HcEncoderEligibility.levelFor(hevc, CodecProfileLevel.HEVCMainTierLevel21, 1280, 720, 30, 4_000_000))
        assertEquals(0x7fff0000, HcEncoderEligibility.levelFor(hevc, 0x7fff0000, 640, 480, 15, 400_000))

        val avcMax = CodecProfileLevel.AVCLevel52
        assertEquals(CodecProfileLevel.AVCLevel22, HcEncoderEligibility.levelFor(avc, avcMax, 640, 480, 15, 400_000))
        assertEquals(CodecProfileLevel.AVCLevel31, HcEncoderEligibility.levelFor(avc, avcMax, 854, 480, 30, 1_000_000))
        assertEquals(CodecProfileLevel.AVCLevel31, HcEncoderEligibility.levelFor(avc, avcMax, 1280, 720, 30, 2_000_000))
        assertEquals(31, HcEncoderEligibility.levelIdc(avc, CodecProfileLevel.AVCLevel31))
        assertEquals(CodecProfileLevel.AVCLevel3, HcEncoderEligibility.levelFor(avc, CodecProfileLevel.AVCLevel3, 1280, 720, 30, 2_000_000))
        assertEquals(CodecProfileLevel.VP9Level5, HcEncoderEligibility.levelFor(StreamProtocol.CODEC_VP9, CodecProfileLevel.VP9Level5, 640, 480, 15, 1))
    }

    @Test
    fun profilesFollowTheRecordOrder() {
        val hevcLevels = listOf(CodecProfileLevel.HEVCProfileMain10 to 1, CodecProfileLevel.HEVCProfileMain to 4, CodecProfileLevel.HEVCProfileMain to 16)
        assertEquals(CodecProfileLevel.HEVCProfileMain to 16, HcEncoderEligibility.profileFor(StreamProtocol.CODEC_HEVC, hevcLevels))
        assertNull(HcEncoderEligibility.profileFor(StreamProtocol.CODEC_HEVC, listOf(CodecProfileLevel.HEVCProfileMain10 to 1)))
        val avc = listOf(CodecProfileLevel.AVCProfileBaseline to 8, CodecProfileLevel.AVCProfileMain to 8, CodecProfileLevel.AVCProfileHigh to 16)
        assertEquals(CodecProfileLevel.AVCProfileHigh to 16, HcEncoderEligibility.profileFor(StreamProtocol.CODEC_H264, avc))
        assertEquals(
            CodecProfileLevel.AVCProfileConstrainedBaseline to 4,
            HcEncoderEligibility.profileFor(
                StreamProtocol.CODEC_H264,
                listOf(CodecProfileLevel.AVCProfileBaseline to 8, CodecProfileLevel.AVCProfileConstrainedBaseline to 4),
            ),
        )
        assertNull(HcEncoderEligibility.profileFor(StreamProtocol.CODEC_VP9, avc))
    }

    @Test
    fun theRateModeTableAndCqOnly() {
        val cbr = EncoderCapabilities.BITRATE_MODE_CBR
        val vbr = EncoderCapabilities.BITRATE_MODE_VBR
        val cbrFd = EncoderCapabilities.BITRATE_MODE_CBR_FD
        assertEquals(cbr, HcBitrateModes(cbr = true, cbrFd = true, vbr = true, cq = true).automaticMode())
        assertEquals(cbrFd, HcBitrateModes(cbr = false, cbrFd = true, vbr = true, cq = false).automaticMode())
        assertEquals(vbr, HcBitrateModes(cbr = false, cbrFd = false, vbr = true, cq = true).automaticMode())
        // The Exynos OMX encoders declare no mode: no KEY_BITRATE_MODE, the encoder's default.
        val none = HcBitrateModes(cbr = false, cbrFd = false, vbr = false, cq = false)
        assertNull(none.automaticMode())
        assertEquals(null, HcEncoderEligibility.staticBlock(true, none, hasProfile = true))
        // CQ-only (the ASUS .hevc.cq) is queried explicitly and never mistaken for "no modes".
        val cqOnly = HcBitrateModes(cbr = false, cbrFd = false, vbr = false, cq = true)
        assertEquals(HcEncoderEligibility.BLOCK_CQ_ONLY, HcEncoderEligibility.staticBlock(true, cqOnly, hasProfile = true))
        assertEquals(setOf<Int>(), cqOnly.declared())
        assertEquals(HcEncoderEligibility.BLOCK_PROFILE, HcEncoderEligibility.staticBlock(true, none, hasProfile = false))
        assertEquals(HcEncoderEligibility.BLOCK_SOFTWARE, HcEncoderEligibility.staticBlock(false, none, hasProfile = true))
        assertEquals("encoder_default", HcBitrateModes.label(null))
    }

    @Test
    fun sessionSizeAndFrameRateChecks() {
        val codec = hc("OMX.qcom.video.encoder.hevc", StreamProtocol.CODEC_HEVC, true, 30.0).copy(envelope = envelope)
        assertNull(HcEncoderEligibility.sessionBlock(codec, 640, 360, 30))
        assertNull(HcEncoderEligibility.sessionBlock(codec, 360, 640, 30)) // either orientation
        assertNull(HcEncoderEligibility.sessionBlock(codec, 854, 480, 30))
        assertEquals(HcEncoderEligibility.BLOCK_FPS, HcEncoderEligibility.sessionBlock(codec, 1280, 720, 30))
        assertEquals(HcEncoderEligibility.BLOCK_SIZE, HcEncoderEligibility.sessionBlock(codec, 2560, 1440, 15))
        assertEquals(HcEncoderEligibility.BLOCK_SIZE, HcEncoderEligibility.sessionBlock(codec, 641, 360, 15)) // alignment
        assertEquals(HcEncoderEligibility.BLOCK_SIZE, HcEncoderEligibility.sessionBlock(codec.copy(envelope = null), 640, 360, 15))
        assertNull(HcEncoderEligibility.sessionBlock(codec.copy(protocolId = StreamProtocol.CODEC_VP9, envelope = null), 640, 360, 15))
    }

    @Test
    fun componentsOfOneCodecArePickedByTheFrameRateTheyReach() {
        // The ASUS Codec2 H.264 encoder measured 26-56 FPS at 720p against about 200 for its OMX one.
        val codec2 = hc("c2.qti.avc.encoder", StreamProtocol.CODEC_H264, true, 40.0)
        val omx = hc("OMX.qcom.video.encoder.avc", StreamProtocol.CODEC_H264, true, 200.0)
        val software = hc("c2.android.avc.encoder", StreamProtocol.CODEC_H264, false, 300.0)
        val vp9 = VideoCodec("VP9", "video/x-vnd.on2.vp9", StreamProtocol.CODEC_VP9, "c2.android.vp9.encoder", false, false, null, emptySet(), null)
        val ordered = HcEncoderEligibility.componentOrder(listOf(codec2, vp9, omx, software))
        assertEquals(listOf(omx, vp9, codec2, software), ordered)
        assertEquals(listOf(omx, software), HcEncoderEligibility.bestPerCodec(listOf(omx, codec2, software)))
        assertEquals(listOf(vp9), HcEncoderEligibility.bestPerCodec(listOf(vp9)))
    }

    @Test
    fun decoderEnvelopeNeedsEveryH264ProfileWeSend() {
        val all = setOf(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCProfileHigh)
        assertEquals(true, HcDecoderEnvelope.profilesCovered(StreamProtocol.CODEC_H264, all))
        assertEquals(false, HcDecoderEnvelope.profilesCovered(StreamProtocol.CODEC_H264, all - CodecProfileLevel.AVCProfileHigh))
        assertEquals(true, HcDecoderEnvelope.profilesCovered(StreamProtocol.CODEC_HEVC, setOf(CodecProfileLevel.HEVCProfileMain)))
        assertEquals(false, HcDecoderEnvelope.profilesCovered(StreamProtocol.CODEC_HEVC, setOf(CodecProfileLevel.HEVCProfileMain10)))
    }

    @Test
    fun aKeyframeRequestNeedsAnIdrWithinOneSecondAndThreeMissesEndVideo() {
        val deadline = IdrRequestDeadline()
        assertEquals(IdrRequestDeadline.Action.NONE, deadline.poll(0))
        deadline.onRequest(0)
        deadline.onAccessUnit(idr = false) // a P frame is no answer
        assertEquals(IdrRequestDeadline.Action.NONE, deadline.poll(999))
        assertEquals(IdrRequestDeadline.Action.REPEAT, deadline.poll(1_000))
        deadline.onRequest(1_000) // the repeat keeps the new deadline
        assertEquals(IdrRequestDeadline.Action.REPEAT, deadline.poll(2_000))
        assertEquals(IdrRequestDeadline.Action.GIVE_UP, deadline.poll(3_000))
        assertEquals(false, deadline.pending)

        val answered = IdrRequestDeadline()
        answered.onRequest(0)
        assertEquals(IdrRequestDeadline.Action.REPEAT, answered.poll(1_000))
        answered.onAccessUnit(idr = true) // the repeat was answered: misses reset
        assertEquals(0, answered.misses)
        assertEquals(IdrRequestDeadline.Action.NONE, answered.poll(5_000))
    }

    @Test
    fun ladderRowsHevcUsesVp9AndH264NeedsThirtyPercentMore() {
        val classes = AutoEncoderClass.entries
        for (encoderClass in classes) {
            assertEquals(AutoQualityLadder.rungs(StreamProtocol.CODEC_VP9, encoderClass), AutoQualityLadder.rungs(StreamProtocol.CODEC_HEVC, encoderClass))
            val vp9 = AutoQualityLadder.rungs(StreamProtocol.CODEC_VP9, AutoEncoderClass.STANDARD)
            val h264 = AutoQualityLadder.rungs(StreamProtocol.CODEC_H264, encoderClass)
            assertEquals(vp9.map { (it.minVideoKbps * 1.3).toInt() }, h264.map { it.minVideoKbps })
        }
        assertEquals(AutoQualityLadder.ACCEPTABLE_BPPF_VP9, AutoQualityLadder.acceptableBppf(StreamProtocol.CODEC_HEVC), 1e-9)
        assertEquals(AutoQualityLadder.ACCEPTABLE_BPPF_VP9 * 1.3, AutoQualityLadder.acceptableBppf(StreamProtocol.CODEC_H264), 1e-9)
        val r360 = VideoResolution(640, 360, "360p")
        assertEquals(CallResolutionLadder.minStartBps(StreamProtocol.CODEC_VP9, r360), CallResolutionLadder.minStartBps(StreamProtocol.CODEC_HEVC, r360))
        assertEquals((CallResolutionLadder.minStartBps(StreamProtocol.CODEC_VP9, r360) * 1.3).toInt(), CallResolutionLadder.minStartBps(StreamProtocol.CODEC_H264, r360))
    }
}
