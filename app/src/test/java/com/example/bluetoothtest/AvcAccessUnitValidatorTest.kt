package com.example.bluetoothtest

import com.example.bluetoothtest.AnnexBTestStreams.AvcSps
import com.example.bluetoothtest.AnnexBTestStreams.AvcVui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** H.264 SPS, PPS and slice-header parsing (rules 2, 4, 6, 9, 10). */
class AvcAccessUnitValidatorTest {
    private val v = AvcAccessUnitValidator
    private val env = SizeEnvelope(640, 480)

    private fun violation(block: () -> Unit): RuleViolation {
        try {
            block()
        } catch (e: RuleViolation) {
            return e
        }
        fail("expected a RuleViolation")
        throw IllegalStateException()
    }

    private fun rule9(sps: AvcSps, e: SizeEnvelope = env) = violation { v.parseSps(sps.build(), e) }.rule

    @Test
    fun acceptsBaselineMainAndHighWithPocTypeTwo() {
        for (profile in listOf(66, 77, 100)) {
            val info = v.parseSps(AvcSps(profile = profile).build(), env)
            assertEquals(640, info.visibleWidth)
            assertEquals(480, info.visibleHeight)
            assertEquals(-1, info.vpsId)
        }
    }

    @Test
    fun cropsToTheVisibleSize() {
        val sd = v.parseSps(AvcSps(widthMbs = 27, heightMbs = 15, cropRight = 3).build(), SizeEnvelope(426, 240))
        assertEquals(432, sd.codedWidth)
        assertEquals(426, sd.visibleWidth)
        assertEquals(240, sd.visibleHeight)
        val hd = v.parseSps(AvcSps(profile = 100, widthMbs = 120, heightMbs = 68, cropBottom = 4).build(), SizeEnvelope(1920, 1080))
        assertEquals(1088, hd.codedHeight)
        assertEquals(1080, hd.visibleHeight)
    }

    @Test
    fun pocTypeZeroAndOneNeedAVuiWithZeroReorder() {
        val ok = AvcVui(restriction = true, reorder = 0)
        assertEquals(640, v.parseSps(AvcSps(pocType = 0, vui = ok).build(), env).codedWidth)
        assertEquals(640, v.parseSps(AvcSps(pocType = 1, pocCycle = 3, pocOffset = -7, vui = ok).build(), env).codedWidth)
        assertEquals(9, rule9(AvcSps(pocType = 0, vui = null)))
        assertEquals(9, rule9(AvcSps(pocType = 0, vui = AvcVui(restriction = false))))
        assertEquals(9, rule9(AvcSps(pocType = 0, vui = AvcVui(restriction = true, reorder = 2))))
        assertEquals(9, rule9(AvcSps(pocType = 1, vui = AvcVui(restriction = true, reorder = 1))))
        assertEquals(9, rule9(AvcSps(pocType = 3)))
        // POC type 2 reads no VUI at all
        assertEquals(640, v.parseSps(AvcSps(pocType = 2, vui = AvcVui(restriction = false)).build(), env).codedWidth)
    }

    @Test
    fun vuiWithEveryOptionalBlockIsWalkedCorrectly() {
        val full = AvcVui(
            aspectExtended = true, signalType = true, chromaLoc = true, timing = true,
            nalHrdCpb = 2, vclHrdCpb = 3, restriction = true, reorder = 0,
        )
        assertEquals(640, v.parseSps(AvcSps(pocType = 0, vui = full).build(), env).codedWidth)
        // the same blocks followed by reorder 1 must still be read to the right field
        assertEquals(9, rule9(AvcSps(pocType = 0, vui = full.copy(reorder = 1))))
        // a 32-entry HRD is the maximum; 33 entries (cpb_cnt_minus1 = 32) reject
        assertEquals(640, v.parseSps(AvcSps(pocType = 0, vui = full.copy(nalHrdCpb = 32)).build(), env).codedWidth)
        assertEquals(9, rule9(AvcSps(pocType = 0, vui = full.copy(nalHrdCpb = -1, nalHrdCpbCntMinus1Raw = 32))))
        assertEquals(9, rule9(AvcSps(pocType = 0, vui = full.copy(nalHrdCpb = -1, nalHrdCpbCntMinus1Raw = 4_000_000_000L))))
    }

    @Test
    fun highProfileScalingListsAreParsedAndBounded() {
        assertEquals(640, v.parseSps(AvcSps(profile = 100, scalingLists = 8, scalingDelta = 1).build(), env).codedWidth)
        assertEquals(640, v.parseSps(AvcSps(profile = 100, scalingLists = 3, scalingDelta = 0).build(), env).codedWidth)
        // a delta that drives nextScale to zero ends the list early (127 reaches zero after 8 entries); so does -8 at once
        assertEquals(640, v.parseSps(AvcSps(profile = 100, scalingLists = 8, scalingDelta = -8).build(), env).codedWidth)
        assertEquals(640, v.parseSps(AvcSps(profile = 100, scalingLists = 8, scalingDelta = 127).build(), env).codedWidth)
        // delta_scale outside -128..127
        assertEquals(9, rule9(AvcSps(profile = 100, scalingLists = 1, scalingDelta = 200)))
        assertEquals(9, rule9(AvcSps(profile = 100, scalingLists = 1, scalingDelta = -129)))
    }

    @Test
    fun spsRejectsUnsupportedFeatures() {
        assertEquals(9, rule9(AvcSps(profile = 88)))
        assertEquals(9, rule9(AvcSps(profile = 110)))
        assertEquals(9, rule9(AvcSps(profile = 100, chroma = 2)))
        assertEquals(9, rule9(AvcSps(profile = 100, chroma = 3)))
        assertEquals(9, rule9(AvcSps(profile = 100, bitDepth = 10)))
        assertEquals(9, rule9(AvcSps(frameMbsOnly = false)))
        assertEquals(9, rule9(AvcSps(spsId = 32)))
        assertEquals(9, rule9(AvcSps(level = 63)))
        assertEquals(9, rule9(AvcSps(maxRef = 17)))
    }

    @Test
    fun unusualLevelAndReferenceCountAreLoggedNotRejected() {
        assertTrue(v.parseSps(AvcSps(level = 41, maxRef = 4).build(), env).unusual.isEmpty())
        val info = v.parseSps(AvcSps(level = 42, maxRef = 5).build(), env)
        assertEquals(listOf("level_idc" to 42L, "max_num_ref_frames" to 5L), info.unusual)
        assertEquals(1, v.parseSps(AvcSps(level = 62).build(), env).unusual.size)
        assertEquals(1, v.parseSps(AvcSps(maxRef = 16).build(), env).unusual.size)
    }

    @Test
    fun envelopeAndCropFailuresUseRule6() {
        assertEquals(6, violation { v.parseSps(AvcSps(widthMbs = 41).build(), env) }.rule)
        assertEquals(6, violation { v.parseSps(AvcSps(heightMbs = 31).build(), env) }.rule)
        assertEquals(6, violation { v.parseSps(AvcSps(widthMbs = 2, cropRight = 9).build(), env) }.rule)
        assertEquals(16, v.parseSps(AvcSps(widthMbs = 2, cropRight = 8).build(), env).visibleWidth)
        // rotated coded size 480x640
        assertEquals(480, v.parseSps(AvcSps(widthMbs = 30, heightMbs = 40).build(), env).codedWidth)
    }

    @Test
    fun overlongPocCycleStopsAtTheBudget() {
        // 255 offsets of about 63 bits each cannot fit 512 bytes: the SPS rejects, it is not read to the end
        val sps = AvcSps(pocType = 1, pocCycle = 255, pocOffset = 0x7FFFFFFFL, vui = AvcVui()).build()
        assertEquals(9, violation { v.parseSps(sps, env) }.rule)
        // a cycle count above 255 rejects at once
        assertEquals(9, rule9(AvcSps(pocType = 1, pocCycle = 256)))
    }

    @Test
    fun ppsReadsFieldsUpToTheRedundantPictureFlag() {
        val pps = v.parsePps(AnnexBTestStreams.avcPps(ppsId = 255, spsId = 31))
        assertEquals(255, pps.id)
        assertEquals(31, pps.spsId)
        assertEquals(10, violation { v.parsePps(AnnexBTestStreams.avcPps(sliceGroupsMinus1 = 1)) }.rule)
        assertEquals(10, violation { v.parsePps(AnnexBTestStreams.avcPps(redundantPic = true)) }.rule)
        assertEquals(10, violation { v.parsePps(AnnexBTestStreams.avcPps(ppsId = 256)) }.rule)
        assertEquals(10, violation { v.parsePps(AnnexBTestStreams.avcPps(spsId = 32)) }.rule)
    }

    @Test
    fun sliceHeaderIdrIsIntraAndNonIdrIsPredicted() {
        val idr = v.parseSlice(AnnexBTestStreams.avcSlice(idr = true, ppsId = 9), 5, 0)
        assertEquals(9, idr.ppsId)
        assertTrue(idr.intra)
        assertTrue(v.parseSlice(AnnexBTestStreams.avcSlice(idr = true, sliceType = 2), 5, 0).intra)
        assertFalse(v.parseSlice(AnnexBTestStreams.avcSlice(idr = false, sliceType = 0), 1, 0).intra)
        assertFalse(v.parseSlice(AnnexBTestStreams.avcSlice(idr = false, sliceType = 5), 1, 0).intra)
    }

    @Test
    fun sliceHeaderRejectsWithRule4() {
        fun rule(nal: ByteArray, type: Int) = violation { v.parseSlice(nal, type, 0) }.rule
        assertEquals(4, rule(AnnexBTestStreams.avcSlice(idr = false, firstMb = 1), 1))
        assertEquals(4, rule(AnnexBTestStreams.avcSlice(idr = false, sliceType = 1), 1)) // B
        assertEquals(4, rule(AnnexBTestStreams.avcSlice(idr = false, sliceType = 6), 1))
        assertEquals(4, rule(AnnexBTestStreams.avcSlice(idr = false, sliceType = 2), 1)) // I outside an IDR
        assertEquals(4, rule(AnnexBTestStreams.avcSlice(idr = true, sliceType = 0), 5)) // P in an IDR
        assertEquals(4, rule(AnnexBTestStreams.avcSlice(idr = false, sliceType = 3), 1)) // SP
        assertEquals(4, rule(AnnexBTestStreams.avcSlice(idr = false, ppsId = 256), 1))
    }

    @Test
    fun nalHeaderAndTypeRules() {
        assertTrue(v.headerValid(AnnexBTestStreams.avcNal(1, byteArrayOf(1))))
        assertFalse(v.headerValid(byteArrayOf(0x81.toByte(), 1)))
        assertEquals(listOf(1, 5, 6, 7, 8, 9, 12), (0..31).filter { v.isAllowed(it) })
    }
}
