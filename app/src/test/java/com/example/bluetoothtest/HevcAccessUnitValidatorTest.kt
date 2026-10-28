package com.example.bluetoothtest

import com.example.bluetoothtest.AnnexBTestStreams.HevcSps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** HEVC VPS, SPS, PPS and slice-header parsing (rules 2, 4, 6, 7, 8, 10). */
class HevcAccessUnitValidatorTest {
    private val v = HevcAccessUnitValidator
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

    @Test
    fun parsesMainSpsSizesWithAndWithoutCropping() {
        val plain = v.parseSps(HevcSps().build(), env)
        assertEquals(640, plain.codedWidth)
        assertEquals(480, plain.visibleHeight)
        assertEquals(0, plain.vpsId)
        val odd = v.parseSps(HevcSps(width = 432, height = 240, cropRight = 3).build(), SizeEnvelope(426, 240))
        assertEquals(432, odd.codedWidth)
        assertEquals(426, odd.visibleWidth)
        assertEquals(240, odd.visibleHeight)
        val hd = v.parseSps(HevcSps(width = 1920, height = 1088, cropBottom = 4).build(), SizeEnvelope(1920, 1080))
        assertEquals(1920, hd.visibleWidth)
        assertEquals(1080, hd.visibleHeight)
        assertEquals(1088, hd.codedHeight)
    }

    @Test
    fun codingTreePaddingUpTo63IsAcceptedWhenTheCropMatchesTheHeader() {
        // Measured on Windows (NVIDIA HEVC): 426x240 coded 448x256, 640x360 coded 640x384, 854x480 coded 864x480.
        val nv240 = v.parseSps(HevcSps(width = 448, height = 256, cropRight = 11, cropBottom = 8).build(), SizeEnvelope(426, 240))
        assertEquals(426, nv240.visibleWidth)
        assertEquals(240, nv240.visibleHeight)
        assertEquals(448, nv240.codedWidth)
        assertEquals(360, v.parseSps(HevcSps(width = 640, height = 384, cropBottom = 12).build(), SizeEnvelope(640, 360)).visibleHeight)
        assertEquals(854, v.parseSps(HevcSps(width = 864, height = 480, cropRight = 5).build(), SizeEnvelope(854, 480)).visibleWidth)
        // A smaller Auto-ladder rung under a header that names the maximum size: 320x192 coded, 320x180 cropped.
        val rung = v.parseSps(HevcSps(width = 320, height = 192, cropBottom = 6).build(), SizeEnvelope(426, 240))
        assertEquals(320, rung.visibleWidth)
        assertEquals(180, rung.visibleHeight)
        // Portrait session: either orientation.
        assertEquals(426, v.parseSps(HevcSps(width = 256, height = 448, cropRight = 8, cropBottom = 11).build(), SizeEnvelope(426, 240)).visibleHeight)
    }

    @Test
    fun paddingOf64OrMoreAWrongCropOrAnOddCodedSizeIsRejected() {
        val header = SizeEnvelope(426, 240)
        // 490x240 coded for 426x240: 64 samples of padding (and not a multiple of 8).
        assertEquals(6, violation { v.parseSps(HevcSps(width = 490, height = 240, cropRight = 32).build(), header) }.rule)
        // 496 is a multiple of 8 but pads by 70.
        assertEquals(6, violation { v.parseSps(HevcSps(width = 496, height = 240, cropRight = 35).build(), header) }.rule)
        // Crop mismatch: 448x256 cropped to 428x240 is wider than the header.
        assertEquals(6, violation { v.parseSps(HevcSps(width = 448, height = 256, cropRight = 10, cropBottom = 8).build(), header) }.rule)
        // No crop at all: 448x256 shown as is is outside the header.
        assertEquals(6, violation { v.parseSps(HevcSps(width = 448, height = 256).build(), header) }.rule)
        // A coded side that is not a multiple of the minimum coding block.
        assertEquals(6, violation { v.parseSps(HevcSps(width = 436, height = 240, cropRight = 5).build(), header) }.rule)
    }

    @Test
    fun acceptsMainViaCompatibilityFlagAndOtherIds() {
        val sps = HevcSps(profileIdc = 4, compat = 0x40000000L, spsId = 15, vpsId = 15)
        val info = v.parseSps(sps.build(), env)
        assertEquals(15, info.id)
        assertEquals(15, info.vpsId)
    }

    @Test
    fun spsRejectsUnsupportedFeaturesWithRule8() {
        fun rule(sps: HevcSps) = violation { v.parseSps(sps.build(), env) }.rule
        assertEquals(8, rule(HevcSps(maxSubLayersMinus1 = 1)))
        assertEquals(8, rule(HevcSps(profileSpace = 1)))
        assertEquals(8, rule(HevcSps(profileIdc = 2, compat = 0x20000000L))) // Main 10 only
        assertEquals(8, rule(HevcSps(chroma = 2)))
        assertEquals(8, rule(HevcSps(chroma = 3)))
        assertEquals(8, rule(HevcSps(bitDepthLuma = 10)))
        assertEquals(8, rule(HevcSps(bitDepthChroma = 10)))
        assertEquals(8, rule(HevcSps(reorder = 1, maxDecMinus1 = 2)))
        assertEquals(8, rule(HevcSps(level = 187)))
        assertEquals(8, rule(HevcSps(maxDecMinus1 = 16)))
        assertEquals(8, rule(HevcSps(spsId = 16)))
    }

    @Test
    fun spsEnvelopeAndCropFailuresUseRule6() {
        assertEquals(6, violation { v.parseSps(HevcSps(width = 656).build(), env) }.rule)
        assertEquals(6, violation { v.parseSps(HevcSps(height = 496).build(), env) }.rule)
        assertEquals(6, violation { v.parseSps(HevcSps(width = 640, height = 480, cropRight = 313, cropBottom = 0).build(), env) }.rule)
        assertEquals(6, violation { v.parseSps(HevcSps(width = 32, height = 480, cropRight = 9).build(), env) }.rule)
        assertEquals(6, violation { v.parseSps(HevcSps(width = 0, height = 480).build(), env) }.rule)
        // exactly 16 visible stays legal
        assertEquals(16, v.parseSps(HevcSps(width = 32, height = 480, cropRight = 8).build(), env).visibleWidth)
        // a crop offset of 2^32-2 cannot wrap into a legal size
        val w = TestBitWriter()
        w.u(4, 0).u(3, 0).flag(true).u(2, 0).flag(false).u(5, 1).u(32, 0x60000000L).u(4, 9).u(32, 0).u(11, 0).u(1, 0).u(8, 120)
        w.ue(0).ue(1).ue(64).ue(64).flag(true).ue(0).ue(0xFFFFFFFEL).ue(0).ue(0).ue(0).ue(0).ue(4).flag(true).ue(1).ue(0).ue(0).trailing()
        assertEquals(6, violation { v.parseSps(AnnexBTestStreams.hevcNal(33, w.bytes()), env) }.rule)
    }

    @Test
    fun unusualLevelAndDpbAreLoggedNotRejected() {
        val info = v.parseSps(HevcSps(level = 150, maxDecMinus1 = 8).build(), env)
        assertEquals(listOf("general_level_idc" to 150L, "sps_max_dec_pic_buffering_minus1" to 8L), info.unusual)
        assertTrue(v.parseSps(HevcSps(level = 123, maxDecMinus1 = 5).build(), env).unusual.isEmpty())
        assertEquals(1, v.parseSps(HevcSps(level = 124).build(), env).unusual.size)
        assertEquals(1, v.parseSps(HevcSps(level = 186).build(), env).unusual.size)
        assertEquals(1, v.parseSps(HevcSps(maxDecMinus1 = 15).build(), env).unusual.size)
    }

    @Test
    fun spsBudgetCutsOffFieldsPastFiveHundredTwelveBytes() {
        // a truncated SPS (needed field past the end) rejects
        val whole = HevcSps().build()
        val cut = whole.copyOf(14)
        assertEquals(8, violation { v.parseSps(cut, env) }.rule)
        // padding after the parsed fields is never read, even when it is huge
        assertEquals(640, v.parseSps(HevcSps(pad = 1024).build(), env).codedWidth)
    }

    @Test
    fun vpsRequiresSingleLayerAndSubLayer() {
        assertEquals(3, v.parseVps(AnnexBTestStreams.hevcVps(vpsId = 3)))
        assertEquals(7, violation { v.parseVps(AnnexBTestStreams.hevcVps(maxLayersMinus1 = 1)) }.rule)
        assertEquals(7, violation { v.parseVps(AnnexBTestStreams.hevcVps(maxSubLayersMinus1 = 1)) }.rule)
        assertEquals(7, violation { v.parseVps(AnnexBTestStreams.hevcNal(32, byteArrayOf(0x0C))) }.rule)
    }

    @Test
    fun ppsReadsIdsAndExtraSliceHeaderBits() {
        val pps = v.parsePps(AnnexBTestStreams.hevcPps(ppsId = 63, spsId = 15, extraBits = 5, dependent = true, outputFlag = true))
        assertEquals(63, pps.id)
        assertEquals(15, pps.spsId)
        assertEquals(5, pps.numExtraSliceHeaderBits)
        assertEquals(10, violation { v.parsePps(AnnexBTestStreams.hevcPps(ppsId = 64)) }.rule)
        assertEquals(10, violation { v.parsePps(AnnexBTestStreams.hevcPps(spsId = 16)) }.rule)
    }

    @Test
    fun sliceHeaderFields() {
        val idr = AnnexBTestStreams.hevcSlice(idr = true, ppsId = 7)
        val i = v.parseSlice(idr, 19, 0)
        assertEquals(7, i.ppsId)
        assertTrue(i.intra)
        val p = v.parseSlice(AnnexBTestStreams.hevcSlice(idr = false, ppsId = 2), 1, 0)
        assertFalse(p.intra)
        // intra picture that is not an IDR is allowed
        assertTrue(v.parseSlice(AnnexBTestStreams.hevcSlice(idr = false, sliceType = 2), 1, 0).intra)
        // IDR_N_LP
        assertTrue(v.parseSlice(AnnexBTestStreams.hevcSlice(idr = true, nalType = 20), 20, 0).intra)
        // reserved extra header bits come from the PPS
        assertEquals(1, v.parseSlice(AnnexBTestStreams.hevcSlice(idr = false, ppsId = 1, extraBits = 3), 1, 3).ppsId)
    }

    @Test
    fun sliceHeaderRejectsWithRule4() {
        fun rule(nal: ByteArray, type: Int, extra: Int = 0) = violation { v.parseSlice(nal, type, extra) }.rule
        assertEquals(4, rule(AnnexBTestStreams.hevcSlice(idr = false, firstSlice = false), 1))
        assertEquals(4, rule(AnnexBTestStreams.hevcSlice(idr = false, sliceType = 0), 1)) // B
        assertEquals(4, rule(AnnexBTestStreams.hevcSlice(idr = true, sliceType = 1), 19)) // P in an IDR
        assertEquals(4, rule(AnnexBTestStreams.hevcSlice(idr = false, sliceType = 3), 1))
        assertEquals(4, rule(AnnexBTestStreams.hevcSlice(idr = false, ppsId = 64), 1))
        // wrong extra-bit count shifts slice_type out of place: here the bits read as slice_type 15 (rejected)
        assertEquals(4, rule(AnnexBTestStreams.hevcSlice(idr = false, extraBits = 3), 1, 0))
    }

    @Test
    fun nalHeaderRules() {
        assertTrue(v.headerValid(AnnexBTestStreams.hevcNal(1, byteArrayOf(1))))
        assertFalse(v.headerValid(AnnexBTestStreams.hevcNal(1, byteArrayOf(1), layerId = 1)))
        assertFalse(v.headerValid(AnnexBTestStreams.hevcNal(1, byteArrayOf(1), layerId = 32)))
        assertFalse(v.headerValid(AnnexBTestStreams.hevcNal(1, byteArrayOf(1), tidPlus1 = 2)))
        assertFalse(v.headerValid(AnnexBTestStreams.hevcNal(1, byteArrayOf(1), tidPlus1 = 0)))
        assertFalse(v.headerValid(byteArrayOf(0x82.toByte(), 1)))
        assertFalse(v.headerValid(byteArrayOf(2)))
    }

    @Test
    fun allowedTypesExcludeCraAndEverythingUnlisted() {
        val allowed = (0..63).filter { v.isAllowed(it) }
        assertEquals(listOf(0, 1, 19, 20, 32, 33, 34, 35, 38, 39, 40), allowed)
        assertFalse(v.isAllowed(21))
    }
}
