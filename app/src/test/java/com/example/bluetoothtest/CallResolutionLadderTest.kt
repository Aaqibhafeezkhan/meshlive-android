package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallResolutionLadderTest {
    private fun r(w: Int, h: Int) = VideoResolution(w, h, "${h}p")
    private val p360 = r(640, 360)
    private val p270 = r(480, 270)
    private val p240 = r(426, 240)
    private val p180 = r(320, 180)
    private val offered = listOf(r(1280, 720), p360, p270, p240, p180, r(256, 144), r(640, 480), r(320, 240))
    private val vp9 = StreamProtocol.CODEC_VP9

    private fun ladder(codec: Int = vp9) = CallResolutionLadder(p360, offered, codec)

    @Test
    fun webRtcSinglecastStartLimits() {
        assertEquals(190_000, CallResolutionLadder.minStartBps(vp9, p360))
        assertEquals(120_000, CallResolutionLadder.minStartBps(vp9, p270))
        assertEquals(120_000, CallResolutionLadder.minStartBps(vp9, p240)) // smallest entry at least as large
        assertEquals(0, CallResolutionLadder.minStartBps(vp9, p180))
        assertEquals(350_000, CallResolutionLadder.minStartBps(vp9, r(854, 480)))
        assertEquals(256_000, CallResolutionLadder.minStartBps(StreamProtocol.CODEC_AV1, p360))
        assertEquals(176_000, CallResolutionLadder.minStartBps(StreamProtocol.CODEC_AV1, p270))
    }

    @Test
    fun rungsKeepAspectStayBelowTheStartGeometryAndAboveTheFloorOnePerBucket() {
        // 1280x720 is larger, 4:3 sizes differ in aspect, 256x144 is under the floor, 426x240 shares 480x270's bucket.
        assertEquals(listOf(p360, p270, p180), ladder().rungs)
        assertEquals(listOf(p240, p180), CallResolutionLadder(p240, offered, vp9).rungs)
        assertFalse(CallResolutionLadder(p180, offered, vp9).active)
        assertEquals(listOf(r(640, 480), r(320, 240)), CallResolutionLadder(r(640, 480), offered, vp9).rungs)
    }

    @Test
    fun startChoosesTheLargestRungTheStartRateSupports() {
        assertEquals(p360, ladder().start(300_000, 0).resolution)
        assertEquals(p270, ladder().start(150_000, 0).resolution)
        assertEquals(p180, ladder().start(80_000, 0).resolution)
    }

    @Test
    fun stepsDownOnlyAfterTheTargetStaysLowForTheHoldTime() {
        val l = ladder()
        l.start(300_000, 0)
        assertNull(l.onTarget(150_000, 10_000))
        assertNull(l.onTarget(150_000, 12_000))
        // A recovery inside the hold resets it.
        assertNull(l.onTarget(200_000, 12_500))
        assertNull(l.onTarget(150_000, 13_000))
        assertNull(l.onTarget(150_000, 15_999))
        val down = l.onTarget(150_000, 16_000)
        assertEquals(p270, down?.resolution)
        assertTrue(down!!.reason.startsWith("weak_link(targetKbps=150"))
    }

    @Test
    fun aVeryLowTargetJumpsStraightToTheFloor() {
        val l = ladder()
        l.start(300_000, 0)
        l.onTarget(60_000, 10_000)
        assertEquals(p180, l.onTarget(60_000, 13_000)?.resolution)
        assertEquals(p180, l.current)
    }

    @Test
    fun stepsUpOneRungWithHeadroomHoldAndMinimumInterval() {
        val l = ladder()
        l.start(100_000, 0) // 320x180
        assertEquals(p180, l.current)
        // 140 kbps meets 480x270's 120 kbps but not 1.25 x 120 = 150: no step (hysteresis).
        for (t in 0..60_000 step 1_000) assertNull(l.onTarget(140_000, 20_000L + t))
        // 160 kbps for 10 s: one rung up.
        assertNull(l.onTarget(160_000, 100_000))
        assertNull(l.onTarget(160_000, 109_999))
        assertEquals(p270, l.onTarget(160_000, 110_000)?.resolution)
        // Plenty of rate: still one rung at a time, and not before the 15 s minimum interval.
        assertNull(l.onTarget(500_000, 111_000))
        assertNull(l.onTarget(500_000, 121_000))
        assertNull(l.onTarget(500_000, 124_999))
        assertEquals(p360, l.onTarget(500_000, 125_000)?.resolution)
        assertNull(l.onTarget(500_000, 200_000)) // already at the start geometry, never above it
    }

    @Test
    fun noOscillationAroundOneLimit() {
        val l = ladder()
        l.start(300_000, 0)
        var changes = 0
        var t = 10_000L
        repeat(300) { i ->
            // The target wobbles 170-210 kbps around 640x360's 190 kbps limit.
            val target = if (i % 2 == 0) 170_000 else 210_000
            if (l.onTarget(target, t) != null) changes++
            t += 1_000
        }
        assertEquals(0, changes)
    }

    @Test
    fun benchmarkLaunchesAreOffUnlessTheExtraTurnsTheLadderOn() {
        val spec = CallResolutionLadderFeature.spec
        val absent = { _: String -> null as Boolean? }
        assertTrue(FeatureCatalog.resolve(spec, false, absent, { null }))
        assertFalse(FeatureCatalog.resolve(spec, true, absent, { null }))
        assertTrue(FeatureCatalog.resolve(spec, true, { true }, { null }))
        assertFalse(FeatureCatalog.resolve(spec, true, { false }, { null }))
        // A constant: no stored choice reaches it.
        assertTrue(FeatureCatalog.resolve(spec, false, absent, { false }))
    }
}
