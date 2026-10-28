package com.example.bluetoothtest

import com.example.bluetoothtest.AnnexBTestStreams.SC3
import com.example.bluetoothtest.AnnexBTestStreams.SC4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Annex B scanning, escapes, the bounded RBSP reader, Exp-Golomb limits and the size envelope. */
class AnnexBBitstreamTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun scanOk(payload: ByteArray, codec: AnnexBCodec = AnnexBCodec.H264): List<NalUnit> =
        (AnnexB.scan(payload, codec) as AnnexBScan.Ok).nals

    private fun scanFail(payload: ByteArray, codec: AnnexBCodec = AnnexBCodec.H264): String =
        (AnnexB.scan(payload, codec) as AnnexBScan.Fail).reason

    private fun violation(block: () -> Unit): RuleViolation {
        try {
            block()
        } catch (v: RuleViolation) {
            return v
        }
        fail("expected a RuleViolation")
        throw IllegalStateException()
    }

    @Test
    fun codecIdsMapBothWays() {
        assertEquals(AnnexBCodec.H264, AnnexBCodec.of(4))
        assertEquals(AnnexBCodec.HEVC, AnnexBCodec.of(5))
        assertNull(AnnexBCodec.of(3))
        assertEquals("HEVC", AnnexBCodec.HEVC.label)
    }

    @Test
    fun scansThreeAndFourByteStartCodesAndTypes() {
        val sps = b(0x67, 1, 2, 3)
        val pps = b(0x68, 9, 8)
        val nals = scanOk(SC4 + sps + SC3 + pps + SC4 + b(0x65, 7, 7))
        assertEquals(listOf(7, 8, 5), nals.map { it.type })
        assertArrayEquals(sps, nals[0].bytes)
        assertArrayEquals(pps, nals[1].bytes)
        val hevc = scanOk(SC4 + b(0x40, 0x01, 0xAA) + SC3 + b(0x42, 0x01, 0xBB), AnnexBCodec.HEVC)
        assertEquals(listOf(32, 33), hevc.map { it.type })
    }

    @Test
    fun trailingZerosBeforeStartCodeAndAtEndAreNotPartOfTheNal() {
        val first = b(0x67, 1, 2)
        val nals = scanOk(SC4 + first + b(0, 0, 0, 0, 0) + SC4 + b(0x68, 5) + b(0, 0, 0))
        assertEquals(2, nals.size)
        assertArrayEquals(first, nals[0].bytes)
        assertArrayEquals(b(0x68, 5), nals[1].bytes)
        // a single trailing zero byte at the very end is also dropped
        assertArrayEquals(b(0x68, 5), scanOk(SC4 + b(0x68, 5, 0))[0].bytes)
    }

    @Test
    fun escapeSequencesAreKeptInTheNalAndAllowedAtTheEnd() {
        val inside = b(0x41, 0x11, 0, 0, 3, 1, 0x22, 0, 0, 3, 0, 0, 3, 3, 0x33)
        assertArrayEquals(inside, scanOk(SC4 + inside)[0].bytes)
        val atEnd = b(0x41, 0x11, 0, 0, 3)
        assertArrayEquals(atEnd, scanOk(SC4 + atEnd)[0].bytes)
        // escaped zero before a real start code
        val two = scanOk(SC4 + atEnd + SC3 + b(0x41, 9))
        assertEquals(2, two.size)
        assertArrayEquals(atEnd, two[0].bytes)
    }

    @Test
    fun malformedEscapesAndForbiddenSequencesReject() {
        assertEquals("bad_escape", scanFail(SC4 + b(0x41, 0x11, 0, 0, 3, 4)))
        assertEquals("bad_escape", scanFail(SC4 + b(0x41, 0x11, 0, 0, 3, 0xFF)))
        assertEquals("forbidden_000002", scanFail(SC4 + b(0x41, 0x11, 0, 0, 2, 5)))
        assertEquals("forbidden_zero_run", scanFail(SC4 + b(0x41, 0x11, 0, 0, 0, 5)))
    }

    @Test
    fun framingFailures() {
        assertEquals("empty_payload", scanFail(ByteArray(0)))
        assertEquals("no_start_code", scanFail(b(0x67, 1, 2, 3)))
        assertEquals("no_start_code", scanFail(b(0, 0, 0, 0, 1, 0x67, 1)))
        assertEquals("no_start_code", scanFail(b(0, 1, 0x67, 1)))
        // an empty NAL unit (two start codes in a row) and a one-byte HEVC NAL
        assertEquals("short_nal", scanFail(SC4 + SC4 + b(0x68, 1)))
        assertEquals("short_nal", scanFail(SC4 + b(0x40), AnnexBCodec.HEVC))
        assertEquals("short_nal", scanFail(SC4))
    }

    @Test
    fun atMostEightNalUnits() {
        fun au(n: Int): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            repeat(n) {
                o.write(SC4)
                o.write(b(0x09, 0xF0))
            }
            return o.toByteArray()
        }
        assertEquals(8, scanOk(au(8)).size)
        assertEquals("too_many_nals", scanFail(au(9)))
        // a huge run of tiny NAL units stops at the limit instead of building thousands of objects
        val result = AnnexB.scan(au(50_000), AnnexBCodec.H264) as AnnexBScan.Fail
        assertEquals(8, result.partial.size)
    }

    @Test
    fun unescapeRemovesOnlyTheEscapeByteAndHonoursTheBudget() {
        val nal = b(0x67, 0xAA, 0, 0, 3, 0, 0, 0, 3, 1, 0xBB)
        assertArrayEquals(b(0xAA, 0, 0, 0, 0, 0, 1, 0xBB), AnnexB.unescape(nal, 1, 512))
        assertArrayEquals(b(0xAA, 0, 0), AnnexB.unescape(nal, 1, 3))
        assertEquals(0, AnnexB.unescape(b(0x67), 1, 512).size)
        // a trailing 00 00 03 loses its 03 (cabac_zero_words style)
        assertArrayEquals(b(0x55, 0, 0), AnnexB.unescape(b(0x67, 0x55, 0, 0, 3), 1, 512))
    }

    @Test
    fun budgetEndsAtFiveHundredTwelveAndSixtyFourBytes() {
        val nal = b(0x67) + ByteArray(700) { 0x11 }
        assertEquals(AnnexB.PARAMETER_SET_BUDGET, AnnexB.unescape(nal, 1, AnnexB.PARAMETER_SET_BUDGET).size)
        assertEquals(AnnexB.SLICE_HEADER_BUDGET, AnnexB.unescape(nal, 1, AnnexB.SLICE_HEADER_BUDGET).size)
        // 80 zero bytes then a one: the ue sits beyond the 64-byte slice budget
        val reader = AnnexB.sliceReader(b(0x41) + ByteArray(80) + b(0x80), AnnexBCodec.H264, 1)
        assertEquals(4, violation { reader.ue() }.rule)
    }

    @Test
    fun expGolombValuesAndLimits() {
        val w = TestBitWriter()
        w.ue(0).ue(1).ue(2).ue(255).se(1).se(-1).se(5).se(-127).ue(0xFFFFFFFEL)
        w.trailing()
        val r = BitReader(w.bytes(), 9)
        assertEquals(0, r.ue())
        assertEquals(1, r.ue())
        assertEquals(2, r.ue())
        assertEquals(255, r.ue())
        assertEquals(1, r.se())
        assertEquals(-1, r.se())
        assertEquals(5, r.se())
        assertEquals(-127, r.se())
        assertEquals(0xFFFFFFFEL, r.ue()) // 31 leading zeros is the longest allowed code
    }

    @Test
    fun overlongAndOverflowingExpGolombReject() {
        val thirtyTwoZeros = TestBitWriter().zeros(32).u(1, 1).u(32, 0).trailing().bytes()
        assertEquals(8, violation { BitReader(thirtyTwoZeros, 8).ue() }.rule)
        val veryLong = TestBitWriter().zeros(40).u(1, 1).u(32, 0).u(8, 0).trailing().bytes()
        assertEquals(8, violation { BitReader(veryLong, 8).se() }.rule)
        // all zeros to the end of the data
        assertEquals(10, violation { BitReader(ByteArray(2), 10).ue() }.rule)
        // ue(max)
        val big = TestBitWriter().ue(100).trailing().bytes()
        assertEquals(9, violation { BitReader(big, 9).ue(99) }.rule)
        assertEquals(100, BitReader(big, 9).ue(100))
    }

    @Test
    fun readingPastTheEndRejects() {
        val r = BitReader(b(0xFF), 8)
        assertEquals(15, r.u(4))
        assertEquals(8, violation { r.u(5) }.rule)
        assertEquals(8, violation { BitReader(b(0xFF), 8).skip(9) }.rule)
        assertEquals(4L, BitReader(b(0xFF), 8).apply { skip(4) }.bitsLeft())
    }

    @Test
    fun envelopeBoundsEachAxisInEitherOrientation() {
        val env = SizeEnvelope(640, 480)
        assertTrue(env.accepts(640, 480))
        assertTrue(env.accepts(480, 640))
        assertTrue(env.accepts(320, 240))
        assertFalse(env.accepts(656, 480))
        assertFalse(env.accepts(640, 496))
        assertFalse(env.accepts(480, 656))
        assertFalse(env.accepts(0, 480))
        // header sides round up to 16: 426x240 admits 432x240
        val odd = SizeEnvelope(426, 240)
        assertTrue(odd.accepts(432, 240))
        assertFalse(odd.accepts(448, 240))
        assertTrue(odd.accepts(240, 432))
        // larger-vs-larger and smaller-vs-smaller, not width-vs-width
        assertTrue(SizeEnvelope(1920, 1080).accepts(1080, 1920))
        assertFalse(SizeEnvelope(1920, 1080).accepts(1920, 1096))
    }

    @Test
    fun checkedCropArithmeticKeepsSixteenVisible() {
        val env = SizeEnvelope.UNBOUNDED
        assertEquals(16, env.visible(32, 4, 4, 2, "w"))
        assertEquals(6, violation { env.visible(32, 5, 5, 2, "w") }.rule)
        // offsets near 2^32 do not wrap
        assertEquals(6, violation { env.visible(64, 0xFFFFFFFEL, 0xFFFFFFFEL, 2, "w") }.rule)
    }
}
