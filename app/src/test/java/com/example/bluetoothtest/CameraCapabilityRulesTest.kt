package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CameraCapabilityRulesTest {
    @Test
    fun aSizeJustBeyondTheReportedMaximumIsACandidateOnlyWhenRejected() {
        // The AVC encoders that report 1920x1072 but configure 1920x1080.
        assertTrue(SizeSupportCheck.isFalseNegativeCandidate(false, 1920, 1080, 1920, 1072))
        assertFalse(SizeSupportCheck.isFalseNegativeCandidate(true, 1920, 1080, 1920, 1072))
        assertFalse("9 px beyond is outside the 8 px window", SizeSupportCheck.isFalseNegativeCandidate(false, 1920, 1081, 1920, 1072))
        assertFalse("a far larger size is not a false negative", SizeSupportCheck.isFalseNegativeCandidate(false, 3840, 2160, 1920, 1072))
        assertFalse("no reported maximum, no candidate", SizeSupportCheck.isFalseNegativeCandidate(false, 1920, 1080, null, 1072))
    }

    @Test
    fun withoutTheSwitchTheAnswerIsExactlyIsSizeSupported() {
        var probed = 0
        val off = SizeSupportCheck.decide(reportedSupported = false, candidate = true, probeEnabled = false) { probed++; true }
        assertFalse(off.allowed)
        assertTrue(off.candidate)
        assertNull(off.probed)
        assertEquals("the probe must not run while the switch is off", 0, probed)
        val supported = SizeSupportCheck.decide(true, false, false) { probed++; false }
        assertTrue(supported.allowed)
        assertEquals(0, probed)
        val notCandidate = SizeSupportCheck.decide(false, false, true) { probed++; true }
        assertFalse(notCandidate.allowed)
        assertEquals(0, probed)
    }

    @Test
    fun withTheSwitchAConfigureThatSucceedsAllowsTheSize() {
        val ok = SizeSupportCheck.decide(false, true, true) { true }
        assertTrue(ok.allowed)
        assertEquals(true, ok.probed)
        val failed = SizeSupportCheck.decide(false, true, true) { false }
        assertFalse(failed.allowed)
        assertEquals(false, failed.probed)
        val threw = SizeSupportCheck.decide(false, true, true) { throw IllegalStateException("configure failed") }
        assertFalse(threw.allowed)
    }

    @Test
    fun candidateLineStatesTheProbeAndWhatItWouldAllow() {
        val line = SizeSupportCheck.candidateLine("c2.mtk.avc.encoder", 1920, 1080, 1920, 1072, probeEnabled = false, probed = null)
        assertTrue(line, line.startsWith("BENCHMARK capability_size_candidate:") && "isSizeSupported=false" in line && "probeConfigure=not_run" in line)
    }

    @Test
    fun alignmentReportFlagsTheSixteenPixelRisk() {
        val aligned = EncoderAlignment.Report(2, 2, 640, 360)
        assertTrue(aligned.alignedToEncoder)
        assertFalse("360 is not a multiple of 16", aligned.aligned16)
        assertTrue("the encoder advertises 2 but 16 is the common assumption", aligned.risk16)
        val sixteen = EncoderAlignment.Report(16, 16, 640, 368)
        assertTrue(sixteen.aligned16)
        assertFalse(sixteen.risk16)
        val advertises16 = EncoderAlignment.Report(16, 16, 640, 360)
        assertFalse("640x360 is not aligned to an encoder that needs 16", advertises16.alignedToEncoder)
        assertFalse("an encoder that advertises 16 already says so", advertises16.risk16)
        val line = EncoderAlignment.line("c2.qti.av1.encoder", aligned)
        assertTrue(line, "action=none(coded sizes are protocol-visible)" in line && "requested=640x360" in line && "risk16=true" in line)
    }

    @Test
    fun implausiblySmallRealDisplayFallsBackToWindowMetrics() {
        assertEquals(1080 to 2400, DisplaySizeGuard.effective(16, 16, 1080, 2400))
        assertEquals(1080 to 2400, DisplaySizeGuard.effective(1080, 99, 1080, 2400))
        assertEquals(1440 to 3120, DisplaySizeGuard.effective(1440, 3120, 1080, 2400))
    }

    private fun interleavedChroma(limit: Int): ByteBuffer = ByteBuffer.allocate(64).apply {
        for (i in 0 until 64) put((i + 1).toByte())
        limit(limit)
        rewind()
    }

    @Test
    fun aPlaneShortByItsLastBytesIsCopiedWithTheTailClampedOnlyWhenAllowed() {
        // 4x2 chroma plane, row stride 8, pixel stride 2: the last sample is at byte 8 + 3 * 2 = 14, so 15 bytes are needed.
        val needed = YuvPlaneCopy.planeEnd(0, 8, 2, 0, 0, 4, 2)
        assertEquals(15L, needed)
        val region = PlaneRegion(0, 0, 4, 2)
        val strictSource = interleavedChroma(limit = 13) // two bytes short
        val strictTarget = ByteBuffer.allocate(8)
        assertFalse("the strict copy still fails closed", YuvPlaneCopy.copyPlane(strictSource, 0, 8, 2, region, strictTarget, 0, 4, 1, 4, 2))
        val clampSource = interleavedChroma(limit = 13)
        val target = ByteBuffer.allocate(8)
        assertTrue(YuvTailClamp.copyPlaneClamped(clampSource, 0, 8, 2, region, target, 0, 4, 1, 4, 2))
        // Row 0 samples: bytes 0, 2, 4, 6 (values 1, 3, 5, 7); row 1: bytes 8, 10, 12, then 14 clamped to byte 12.
        assertEquals(listOf<Byte>(1, 3, 5, 7, 9, 11, 13, 13), (0 until 8).map { target.get(it) })
    }

    @Test
    fun tailClampRefusesALargerShortfallAndAnInvalidLayout() {
        val region = PlaneRegion(0, 0, 4, 2)
        val target = ByteBuffer.allocate(8)
        // Short by 7 bytes: more than two pixel strides.
        assertFalse(YuvTailClamp.copyPlaneClamped(interleavedChroma(limit = 8), 0, 8, 2, region, target, 0, 4, 1, 4, 2))
        // A buffer that fits needs no clamp (the strict copy handles it).
        assertFalse(YuvTailClamp.copyPlaneClamped(interleavedChroma(limit = 15), 0, 8, 2, region, target, 0, 4, 1, 4, 2))
        // Row stride smaller than the row: invalid, never clamped.
        assertFalse(YuvTailClamp.copyPlaneClamped(interleavedChroma(limit = 13), 0, 4, 2, region, target, 0, 4, 1, 4, 2))
        // The target must still fit.
        assertFalse(YuvTailClamp.copyPlaneClamped(interleavedChroma(limit = 13), 0, 8, 2, region, ByteBuffer.allocate(4), 0, 4, 1, 4, 2))
        assertNotNull(YuvTailClamp.shortfall(13, 0, 8, 2, region))
        assertNull(YuvTailClamp.shortfall(13, 0, 0, 2, region))
        assertEquals(4, YuvTailClamp.maxShortfall(2))
    }

    @Test
    fun theExperimentalTailClampSwitchIsOffByDefault() {
        CameraSwitches.resetForTest()
        assertFalse(CameraSwitches.yuvTailClamp)
    }
}
