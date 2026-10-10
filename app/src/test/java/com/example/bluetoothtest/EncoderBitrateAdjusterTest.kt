package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderBitrateAdjusterTest {
    private val fps = 15
    private val frameUs = 1_000_000L / fps

    /**
     * A simulated encoder that produces [ratio] x the bitrate it is asked for, with an optional
     * keyframe every [keyFrameEvery] frames that is [keyFrameMultiple] x a delta frame while the
     * average over the keyframe interval still equals ratio x request.
     */
    private class SimulatedEncoder(
        private val adjuster: EncoderBitrateAdjuster,
        private val ratio: Double,
        private val fps: Int = 15,
        private val keyFrameEvery: Int = 0,
        private val keyFrameMultiple: Double = 10.0,
    ) {
        var ptsUs = 0L
        var frame = 0L
        var reapplied = 0
        val requests = mutableListOf<Int>()

        fun run(seconds: Double, target: Int = adjuster.targetBps) {
            adjuster.setTarget(target)
            repeat((seconds * fps).toInt()) {
                val requested = adjuster.adjustedBps()
                requests += requested
                val averageBytes = requested * ratio / 8.0 / fps
                val bytes = if (keyFrameEvery > 0) {
                    val delta = averageBytes * keyFrameEvery / (keyFrameMultiple + keyFrameEvery - 1)
                    if (frame % keyFrameEvery == 0L) delta * keyFrameMultiple else delta
                } else {
                    averageBytes
                }
                if (adjuster.onEncodedFrame(ptsUs, bytes.toInt(), fps)) reapplied++
                ptsUs += 1_000_000L / fps
                frame++
            }
        }
    }

    @Test
    fun anOvershootingEncoderIsBroughtBackToTheTarget() {
        // M31 OMX.Exynos.VP9: 720 kbps for a 401 kbps target (xpair-s24m31-431d40dc/0002).
        val adjuster = EncoderBitrateAdjuster()
        val encoder = SimulatedEncoder(adjuster, ratio = 1.8)
        encoder.run(seconds = 3.5, target = 401_000)
        assertTrue("first judgement after 3 s lowers the request", adjuster.scaleExponent < 0)
        encoder.run(seconds = 40.0)
        val actualBps = adjuster.adjustedBps() * 1.8
        assertEquals(401_000.0, actualBps, 401_000 * 0.08)
        assertTrue(adjuster.adjustedBps() < 401_000)
        assertTrue(encoder.requests.all { it <= 401_000 })
    }

    @Test
    fun theScaleIsBoundedAtAQuarter() {
        val adjuster = EncoderBitrateAdjuster()
        SimulatedEncoder(adjuster, ratio = 10.0).run(seconds = 120.0, target = 400_000)
        assertEquals(-EncoderBitrateAdjuster.STEPS, adjuster.scaleExponent)
        assertEquals(100_000, adjuster.adjustedBps())
    }

    @Test
    fun anUndershootingEncoderIsNeverRaisedAboveTheTarget() {
        val adjuster = EncoderBitrateAdjuster()
        val encoder = SimulatedEncoder(adjuster, ratio = 0.5)
        encoder.run(seconds = 60.0, target = 400_000)
        assertEquals(0, adjuster.scaleExponent)
        assertEquals(0, encoder.reapplied)
        assertTrue(encoder.requests.all { it == 400_000 })
    }

    @Test
    fun anEarlierReductionIsGivenBackWhenTheEncoderStartsUndershooting() {
        val adjuster = EncoderBitrateAdjuster()
        SimulatedEncoder(adjuster, ratio = 1.8).run(seconds = 30.0, target = 400_000)
        val reduced = adjuster.scaleExponent
        assertTrue(reduced < -3)
        // Content becomes simple: the same encoder now produces 0.6x its request.
        val calm = SimulatedEncoder(adjuster, ratio = 0.6)
        calm.ptsUs = 30_000_000L
        calm.run(seconds = 40.0)
        assertEquals(0, adjuster.scaleExponent)
        assertEquals(400_000, adjuster.adjustedBps())
    }

    @Test
    fun aStableEncoderWithPeriodicKeyframesIsNeverTouched() {
        // Tracks its target on average; a keyframe of 10 delta frames every 2 s.
        val adjuster = EncoderBitrateAdjuster()
        val encoder = SimulatedEncoder(adjuster, ratio = 1.0, keyFrameEvery = 30, keyFrameMultiple = 10.0)
        encoder.run(seconds = 120.0, target = 400_000)
        assertEquals(0, adjuster.scaleExponent)
        assertEquals(0, encoder.reapplied)
        assertTrue(encoder.requests.all { it == 400_000 })
    }

    @Test
    fun aStableEncoderFollowsRateControllerChangesUntouched() {
        val adjuster = EncoderBitrateAdjuster()
        val encoder = SimulatedEncoder(adjuster, ratio = 1.0)
        for (target in listOf(515_000, 401_000, 461_000, 521_000, 581_000, 385_000, 445_000, 505_000)) {
            encoder.run(seconds = 3.0, target = target)
            assertEquals(target, adjuster.adjustedBps())
        }
        assertEquals(0, adjuster.adjustments)
    }

    @Test
    fun aKeyframeBelowOneSecondOfTargetBytesNeverMovesTheScale() {
        val adjuster = EncoderBitrateAdjuster()
        adjuster.setTarget(400_000)
        var ptsUs = 0L
        repeat(60) { frame ->
            // A 45 KB keyframe (0.9 s of 400 kbps) on an otherwise on-target encoder.
            val bytes = if (frame == 10) 45_000 else 400_000 / 8 / fps
            assertFalse(adjuster.onEncodedFrame(ptsUs, bytes, fps))
            ptsUs += frameUs
        }
        assertEquals(0, adjuster.scaleExponent)
    }

    @Test
    fun aLargeRecoveryKeyframeMovesAtMostTwoStepsAndIsPaidBack() {
        // M31 recovery keyframe: 106,516 bytes at a 385 kbps target, then the encoder compensates
        // (q_idx at max) with 250 kbps for the rest of the window.
        val adjuster = EncoderBitrateAdjuster()
        adjuster.setTarget(385_000)
        var ptsUs = 0L
        repeat(45) { frame ->
            val bytes = if (frame == 0) 106_516 else 250_000 / 8 / fps
            adjuster.onEncodedFrame(ptsUs, bytes, fps)
            ptsUs += frameUs
        }
        assertTrue(adjuster.scaleExponent >= -2)
        // The encoder keeps compensating briefly, then tracks its request again.
        repeat(45) {
            adjuster.onEncodedFrame(ptsUs, 250_000 / 8 / fps, fps)
            ptsUs += frameUs
        }
        // WebRTC keeps one second of excess after a step, so the step is returned once the
        // encoder, tracking the lowered request, has undershot the target by that much (~15 s).
        repeat(600) {
            adjuster.onEncodedFrame(ptsUs, adjuster.adjustedBps() / 8 / fps, fps)
            ptsUs += frameUs
        }
        assertEquals(0, adjuster.scaleExponent)
    }

    @Test
    fun aCaptureStallIsNotCountedAsUndershoot() {
        val adjuster = EncoderBitrateAdjuster()
        SimulatedEncoder(adjuster, ratio = 1.8).run(seconds = 10.0, target = 400_000)
        val reduced = adjuster.scaleExponent
        assertTrue(reduced < 0)
        // One frame 20 s later (camera stall): counts as at most 0.5 s of expected bytes.
        val bytes = (adjuster.adjustedBps() * 1.8 / 8 / fps).toInt()
        adjuster.onEncodedFrame(30_000_000L, bytes, fps)
        assertTrue(adjuster.scaleExponent <= reduced)
    }

    @Test
    fun anEncoderAtItsMaximumQuantizerIsNotWoundDown() {
        // M31 at 150 kbps 640x480: ~193 kbps at max q_idx whatever it is asked for.
        val adjuster = EncoderBitrateAdjuster()
        adjuster.setTarget(150_000)
        var ptsUs = 0L
        repeat(15 * 60) {
            adjuster.onEncodedFrame(ptsUs, 193_000 / 8 / fps, fps, atQuantizerCeiling = true)
            ptsUs += frameUs
        }
        assertEquals(0, adjuster.scaleExponent)
        assertTrue(adjuster.ceilingHolds > 0)
        // Off the ceiling the same overshoot is corrected.
        repeat(15 * 10) {
            adjuster.onEncodedFrame(ptsUs, 193_000 / 8 / fps, fps, atQuantizerCeiling = false)
            ptsUs += frameUs
        }
        assertTrue(adjuster.scaleExponent < 0)
    }

    @Test
    fun aLowerTargetRescalesTheDeviation() {
        val adjuster = EncoderBitrateAdjuster()
        adjuster.setTarget(600_000)
        var ptsUs = 0L
        repeat(20) {
            adjuster.onEncodedFrame(ptsUs, 600_000 * 2 / 8 / fps, fps)
            ptsUs += frameUs
        }
        val before = adjuster.deviationBytes
        adjuster.setTarget(300_000)
        assertEquals(before / 2, adjuster.deviationBytes, 1e-6)
    }
}
