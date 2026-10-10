package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class SceneCutTest {
    private val width = 160
    private val height = 120

    private fun flat(level: Int) = ByteArray(width * height) { level.toByte() }

    /** Fine detail: per-pixel noise around [mean] (like the coverage input's fine_detail_cuts). */
    private fun detailed(seed: Int, mean: Int = 128, amplitude: Int = 100): ByteArray {
        val random = Random(seed)
        return ByteArray(width * height) { (mean + random.nextInt(-amplitude, amplitude + 1)).coerceIn(0, 255).toByte() }
    }

    private fun SceneCutDetector.feed(frame: ByteArray) = analyze(lumaPlaneOf(frame, 0, width))

    @Test
    fun theFirstFrameHasNothingToCompare() {
        assertNull(SceneCutDetector(width, height).feed(flat(90)))
    }

    @Test
    fun aCutFromAnEasyPictureToFineDetailIsCostly() {
        val detector = SceneCutDetector(width, height)
        detector.feed(flat(90))
        repeat(10) { assertFalse(detector.feed(flat(90))!!.cut) }
        val cut = detector.feed(detailed(1))!!
        assertTrue(cut.cut)
        assertTrue(cut.detailRise)
        assertTrue(cut.changedFraction >= 0.9)
    }

    @Test
    fun aCutToASimplerPictureIsNotCostly() {
        // Coverage frame 150: fine detail -> a flat fade, cheap to code (5 KB at q238 on the M31).
        val detector = SceneCutDetector(width, height)
        detector.feed(detailed(1))
        repeat(5) { detector.feed(detailed(1)) }
        val cut = detector.feed(flat(30))!!
        assertTrue(cut.changedFraction >= 0.9)
        assertFalse(cut.detailRise)
    }

    @Test
    fun independentNoiseEveryFrameIsMotionNotACut() {
        // Coverage entropy_complex: 55-63% of pixels change every frame, but cell means barely move.
        val detector = SceneCutDetector(width, height)
        detector.feed(detailed(0))
        for (frame in 1..20) assertFalse("frame $frame", detector.feed(detailed(frame))!!.cut)
    }

    @Test
    fun aRepeatedPartialChangeAgainstHeavyMotionIsNotACut() {
        // Inside fine_detail_cuts, 39% of the picture changes every 5 frames; the median of recent
        // changes is already high, so only a change well above it counts.
        val detector = SceneCutDetector(width, height)
        val base = detailed(3)
        detector.feed(base)
        for (frame in 1..20) {
            val changed = base.copyOf()
            val rows = height * 2 / 5
            val shift = if (frame % 2 == 0) 60 else -60
            for (index in 0 until width * rows) changed[index] = ((changed[index].toInt() and 0xff) + shift).coerceIn(0, 255).toByte()
            assertFalse("frame $frame", detector.feed(changed)!!.cut)
        }
    }

    @Test
    fun aGlobalBrightnessJumpIsACutWithoutADetailRise() {
        val detector = SceneCutDetector(width, height)
        repeat(5) { detector.feed(flat(40)) }
        val cut = detector.feed(flat(200))!!
        assertTrue(cut.cut)
        assertFalse(cut.detailRise)
    }

    @Test
    fun cameraPlanesWithStridesReadTheSameLuma() {
        val frame = detailed(9)
        val rowStride = width * 2 + 16
        val buffer = ByteBuffer.allocateDirect(rowStride * height)
        for (y in 0 until height) for (x in 0 until width) buffer.put(y * rowStride + x * 2, frame[y * width + x])
        val fromArray = SceneCutDetector(width, height).apply { feed(flat(90)) }.feed(frame)!!
        val fromBuffer = SceneCutDetector(width, height).apply { feed(flat(90)) }
            .analyze(lumaPlaneOf(buffer, 0, rowStride, 2))!!
        assertEquals(fromArray, fromBuffer)
    }

    private val costlyCut = SceneCutResult(
        cut = true,
        detailRise = true,
        changedFraction = 0.93,
        meanDelta = 37.5,
        recentMedianDelta = 1.0,
        detail = 113.5,
        previousDetail = 2.4,
    )

    @Test
    fun shapingIsOffByDefault() {
        val shaper = SceneCutShaper(SceneCutShapingMode.OFF)
        assertNull(shaper.plan(0, 0, costlyCut, lastQIdx = 12, linkKbps = 600, targetBps = 600_000, fps = 15))
        assertEquals("experiment off", shaper.skipReason)
        assertFalse(shaper.pending)
    }

    @Test
    fun aCostlyCutLowersTheTargetToTheLinkBudget() {
        // 600 kbps link: 250 ms = 18.75 KB. Prior cut cost 20 average frames of 5 KB = 100 KB.
        val shaper = SceneCutShaper(SceneCutShapingMode.SYNC)
        val plan = shaper.plan(5_000_000, 0, costlyCut, lastQIdx = 12, linkKbps = 600, targetBps = 600_000, fps = 15)!!
        assertEquals(18_750, plan.budgetBytes)
        assertEquals(100_000, plan.expectedBytes)
        assertEquals(112_500, plan.shapedBps)
        assertTrue(plan.requestSync)
        assertTrue(shaper.pending)
        assertFalse(SceneCutShaper(SceneCutShapingMode.TARGET)
            .plan(5_000_000, 0, costlyCut, lastQIdx = 12, linkKbps = 600, targetBps = 600_000, fps = 15)!!.requestSync)
    }

    @Test
    fun cheapCutsAreLeftAlone() {
        val shaper = SceneCutShaper(SceneCutShapingMode.SYNC)
        // Coverage frames 150/225: the quantizer was already 206-238 and the cut cost 5-11 KB.
        assertNull(shaper.plan(0, 0, costlyCut, lastQIdx = 206, linkKbps = 600, targetBps = 600_000, fps = 15))
        assertTrue(shaper.skipReason!!.startsWith("quantizer already high"))
        assertNull(shaper.plan(0, 0, costlyCut.copy(detailRise = false), 12, 600, 600_000, 15))
        assertNull(shaper.plan(0, 0, costlyCut.copy(cut = false), 12, 600, 600_000, 15))
        // A fast link already carries the expected cut within the budget.
        assertNull(shaper.plan(0, 0, costlyCut, 12, linkKbps = 4_000, targetBps = 150_000, fps = 15))
        assertEquals("expected cut fits the link", shaper.skipReason)
        assertFalse(shaper.pending)
    }

    @Test
    fun theTargetReturnsWhenTheShapedFrameIsEncodedAndTheCostIsLearned() {
        val shaper = SceneCutShaper(SceneCutShapingMode.TARGET)
        val plan = shaper.plan(5_000_000, 0, costlyCut, 12, 600, 600_000, 15)!!
        // Another cut while the first is still encoding is not shaped.
        assertNull(shaper.plan(5_066_000, 66_000_000, costlyCut, 12, 600, 600_000, 15))
        assertEquals("previous cut still encoding", shaper.skipReason)
        // An earlier frame coming out does not end the shaping.
        assertFalse(shaper.onEncoded(4_933_000, 5_000, 15))
        // The encoder ignored the lowered target: 100 KB at 112.5 kbps is 107 average frames.
        assertTrue(shaper.onEncoded(5_000_000, 100_000, 15))
        assertFalse(shaper.pending)
        val learned = shaper.cutFrames
        assertEquals((20.0 + 100_000 / (plan.shapedBps / 8.0 / 15)) / 2, learned, 1e-9)
        // Cooldown, then a deeper cut next time.
        assertNull(shaper.plan(10_000_000, 1_000_000_000, costlyCut, 12, 600, 600_000, 15))
        assertEquals("cooldown", shaper.skipReason)
        val next = shaper.plan(20_000_000, 3_000_000_000, costlyCut, 12, 600, 600_000, 15)!!
        assertTrue(next.shapedBps < plan.shapedBps)
    }

    @Test
    fun anEncoderThatIgnoresTheTargetBottomsOutAtTheMinimumFraction() {
        val shaper = SceneCutShaper(SceneCutShapingMode.SYNC, cooldownNs = 0)
        var plan: SceneCutShaper.Plan? = null
        repeat(6) { round ->
            plan = shaper.plan(round * 1_000_000L, round * 1_000L, costlyCut, 12, 600, 600_000, 15)
            shaper.onEncoded(round * 1_000_000L, 135_000, 15)
        }
        assertEquals((600_000 * SceneCutShaper.MIN_FRACTION).toInt(), plan!!.shapedBps)
    }

    @Test
    fun aLostShapedFrameRestoresAfterTheTimeout() {
        val shaper = SceneCutShaper(SceneCutShapingMode.SYNC)
        assertNotNull(shaper.plan(0, 0, costlyCut, 12, 600, 600_000, 15))
        assertFalse(shaper.timedOut(500_000_000))
        assertTrue(shaper.timedOut(1_000_000_000))
        assertFalse(shaper.pending)
        // A later frame also ends it (the encoder merged or skipped the shaped one).
        assertNotNull(shaper.plan(0, 5_000_000_000, costlyCut, 12, 600, 600_000, 15))
        assertTrue(shaper.onEncoded(66_000, 9_000, 15))
        assertEquals(SceneCutShaper.INITIAL_CUT_FRAMES, shaper.cutFrames, 1e-9)
    }

    @Test
    fun theBudgetUsesTheStreamRateBeforeTheLinkIsMeasured() {
        assertEquals(9_375, SceneCutShaper.cutBudgetBytes(0, 300_000))
        assertEquals(18_750, SceneCutShaper.cutBudgetBytes(600, 300_000))
    }

    @Test
    fun quantizerHeadroomLowersTheTargetWhileTheQuantizerIsLowAndReleasesItAboveTheFloor() {
        val governor = QuantizerHeadroomGovernor(floorQIdx = 100)
        val second = 1_000_000_000L
        var now = 0L
        fun window(qIdx: Int): Double? {
            var change: Double? = null
            repeat(15) {
                governor.onFrame(qIdx, now)?.let { change = it }
                now += second / 15 + 1
            }
            governor.onFrame(qIdx, now)?.let { change = it }
            return change
        }
        // Easy content at the full target: q_idx 12 (M31 at 600 kbps).
        assertEquals(0.8, window(12)!!, 1e-9)
        assertEquals(0.64, window(12)!!, 1e-9)
        repeat(5) { window(12) }
        assertEquals(QuantizerHeadroomGovernor.MIN_FACTOR, governor.factor, 1e-9)
        // Inside the hysteresis band the factor holds.
        assertNull(window(120))
        // Hard content: the quantizer is well above the floor, so the target comes back.
        repeat(20) { window(220) }
        assertEquals(1.0, governor.factor, 1e-9)
        assertNull(governor.onFrame(-1, now))
    }
}
