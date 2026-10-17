package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderWatchdogTest {
    private fun ms(value: Long) = value * 1_000_000L

    /** A stand-in for the codec: counts what it was given and answers only when told to. */
    private class FakeDecoder(fps: Int) {
        val detector = DecoderStallDetector(fps)
        var nowNs = 0L

        fun feed(frames: Int, intervalMs: Long = 33, answer: Boolean) {
            repeat(frames) {
                nowNs += intervalMs * 1_000_000L
                detector.onInputQueued(nowNs)
                if (answer) detector.onOutput(nowNs + 20_000_000L)
            }
        }

        fun check(suspended: Boolean = false, waiting: Boolean = false, held: Boolean = true) =
            detector.check(nowNs, suspended, framesWaiting = waiting, inputBufferHeld = held)
    }

    @Test
    fun aDecoderThatSwallowsInputIsReportedAfterTwoSecondsOfFrames() {
        val decoder = FakeDecoder(fps = 15)
        decoder.detector.reset(0L)
        decoder.feed(frames = 29, intervalMs = 66, answer = false)
        assertNull("29 of 30 frames is not yet a stall", decoder.check())
        decoder.feed(frames = 1, intervalMs = 66, answer = false)
        val stall = decoder.check()
        assertNotNull(stall)
        assertEquals(DecoderStallKind.NO_OUTPUT, stall!!.kind)
        assertEquals(30, stall.unanswered)
        assertTrue(stall.ageMs >= DecoderWatchdogLimits.STALL_MIN_MS)
    }

    @Test
    fun aDecoderThatKeepsAnsweringNeverStalls_evenWithAHiddenSurface() {
        // A hidden surface or a paused display still gets output callbacks (the buffers are released), so
        // the watchdog, which counts callbacks and not frames on screen, stays quiet.
        val decoder = FakeDecoder(fps = 30)
        decoder.detector.reset(0L)
        repeat(20) {
            decoder.feed(frames = 15, answer = true)
            assertNull(decoder.check())
        }
    }

    @Test
    fun aPausedFeedQueuesNothingAndNeverStalls() {
        val decoder = FakeDecoder(fps = 30)
        decoder.detector.reset(0L)
        decoder.feed(frames = 5, answer = true)
        decoder.nowNs += ms(60_000)
        assertNull(decoder.check())
        // A few frames still in flight when the feed pauses are below the threshold.
        decoder.feed(frames = 5, answer = false)
        decoder.nowNs += ms(60_000)
        assertNull(decoder.check())
    }

    @Test
    fun deliberatelyBlockedOrHeldOutputSuspendsAndRebasesTheWindow() {
        val decoder = FakeDecoder(fps = 15)
        decoder.detector.reset(0L)
        decoder.feed(frames = 40, intervalMs = 66, answer = false)
        assertNull("recovery or held output in progress", decoder.check(suspended = true))
        // Re-based: the same unanswered frames need a fresh window before they count.
        assertNull(decoder.check())
        decoder.nowNs += ms(DecoderWatchdogLimits.STALL_MIN_MS + 1)
        assertNotNull(decoder.check())
    }

    @Test
    fun resetStartsACleanWindow() {
        val decoder = FakeDecoder(fps = 15)
        decoder.detector.reset(0L)
        decoder.feed(frames = 40, intervalMs = 66, answer = false)
        assertNotNull(decoder.check())
        decoder.detector.reset(decoder.nowNs)
        assertNull(decoder.check())
    }

    @Test
    fun noInputBufferForFiveSecondsWhileFramesWaitIsStarvation() {
        val detector = DecoderStallDetector(fps = 30)
        detector.reset(0L)
        detector.onInputBufferOffered(0L)
        assertNull(detector.check(ms(4_900), suspended = false, framesWaiting = true, inputBufferHeld = false))
        val stall = detector.check(ms(5_000), suspended = false, framesWaiting = true, inputBufferHeld = false)
        assertEquals(DecoderStallKind.NO_INPUT_BUFFER, stall!!.kind)
        // Holding an unused buffer (the feed is pacing itself), or nothing waiting, is not starvation.
        assertNull(detector.check(ms(9_000), suspended = false, framesWaiting = true, inputBufferHeld = true))
        assertNull(detector.check(ms(20_000), suspended = false, framesWaiting = false, inputBufferHeld = false))
        // The clock restarts when the wait starts, not from the last buffer.
        assertNull(detector.check(ms(21_000), suspended = false, framesWaiting = true, inputBufferHeld = false))
    }

    @Test
    fun stallThresholdIsTwoSecondsOfFramesAndNeverBelowEight() {
        assertEquals(8, DecoderWatchdogLimits.stallInputs(1))
        assertEquals(8, DecoderWatchdogLimits.stallInputs(4))
        assertEquals(30, DecoderWatchdogLimits.stallInputs(15))
        assertEquals(60, DecoderWatchdogLimits.stallInputs(30))
    }

    @Test
    fun escalationLogsOnlyUnlessRecoveryIsOn() {
        val off = DecoderStallEscalation()
        repeat(5) { assertEquals(DecoderStallEscalation.Action.LOG_ONLY, off.next(recoveryEnabled = false, otherCandidateExists = true)) }
    }

    @Test
    fun escalationRebuildsOnceThenExcludesThenGivesUpAndForgetsAfterHealthyFrames() {
        val escalation = DecoderStallEscalation(maxStalls = 3)
        assertEquals(DecoderStallEscalation.Action.REBUILD_SAME, escalation.next(true, otherCandidateExists = true))
        assertEquals(DecoderStallEscalation.Action.EXCLUDE_AND_REBUILD, escalation.next(true, otherCandidateExists = true))
        assertEquals(DecoderStallEscalation.Action.EXCLUDE_AND_REBUILD, escalation.next(true, otherCandidateExists = true))
        assertEquals(DecoderStallEscalation.Action.GIVE_UP, escalation.next(true, otherCandidateExists = true))
        repeat(DecoderWatchdogLimits.HEALTHY_FRAMES) { escalation.onFrameRendered() }
        assertEquals(0, escalation.stallCount)
        assertEquals(DecoderStallEscalation.Action.REBUILD_SAME, escalation.next(true, otherCandidateExists = false))
        // Without another candidate a repeat rebuilds the same component.
        assertEquals(DecoderStallEscalation.Action.REBUILD_SAME, escalation.next(true, otherCandidateExists = false))
    }

    @Test
    fun healthyFramesBelowTheLimitDoNotForgetAStall() {
        val escalation = DecoderStallEscalation()
        escalation.next(true, true)
        repeat(DecoderWatchdogLimits.HEALTHY_FRAMES - 1) { escalation.onFrameRendered() }
        assertEquals(1, escalation.stallCount)
    }

    @Test
    fun aReleaseThatHangsIsAbandonedAfterTheTimeout() {
        val gate = CountDownLatch(1)
        val startedNs = System.nanoTime()
        val finished = BoundedCodecRelease.run("test-hang", timeoutMs = 100) { gate.await(10, TimeUnit.SECONDS) }
        val waitedMs = (System.nanoTime() - startedNs) / 1_000_000L
        gate.countDown()
        assertFalse(finished)
        assertTrue("waited $waitedMs ms", waitedMs in 90..3_000)
    }

    @Test
    fun aQuickReleaseReturnsTrueAndAThrowingOneIsSwallowed() {
        var ran = false
        assertTrue(BoundedCodecRelease.run("test-ok", timeoutMs = 2_000) { ran = true })
        assertTrue(ran)
        assertTrue(BoundedCodecRelease.run("test-throw", timeoutMs = 2_000) { error("codec exploded") })
    }

    @Test
    fun configureOver800MsIsSlowAndAHangWatchFiresOnlyIfNotCancelled() {
        assertFalse(DecoderConfigureWatch.isSlow(800))
        assertTrue(DecoderConfigureWatch.isSlow(801))
        val fired = CountDownLatch(1)
        val hung = DecoderConfigureWatch.watch(50) { fired.countDown() }
        assertTrue(fired.await(3, TimeUnit.SECONDS))
        hung.cancel(false)
        val cancelledFired = CountDownLatch(1)
        DecoderConfigureWatch.watch(200) { cancelledFired.countDown() }.cancel(false)
        assertFalse(cancelledFired.await(600, TimeUnit.MILLISECONDS))
    }
}
