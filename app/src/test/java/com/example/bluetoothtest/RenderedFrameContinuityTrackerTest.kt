package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RenderedFrameContinuityTrackerTest {
    @Test
    fun countsOnlyUserVisibleLongGapsAsFreezes() {
        val tracker = RenderedFrameContinuityTracker(fps = 30, startupStartedAtNs = 0L)
        tracker.recordFrame(100_000_000L)
        tracker.recordFrame(133_333_333L)
        tracker.recordFrame(633_333_333L)

        val snapshot = tracker.snapshot(633_333_333L)
        assertEquals(3L, snapshot.renderedFrames)
        assertEquals(1L, snapshot.freezeEvents)
        assertEquals(466.67, snapshot.freezeDurationMs, 0.02)
        assertEquals(250.0, snapshot.freezeThresholdMs, 0.0)
        assertNotNull(snapshot.startupMs)
    }

    @Test
    fun pauseDoesNotBecomeAFreeze() {
        val tracker = RenderedFrameContinuityTracker(fps = 15, startupStartedAtNs = 0L)
        tracker.recordFrame(100_000_000L)
        tracker.setActive(false)
        tracker.setActive(true)
        tracker.recordFrame(5_000_000_000L)

        val snapshot = tracker.snapshot(5_000_000_000L)
        assertEquals(0L, snapshot.freezeEvents)
        assertEquals(0.0, snapshot.freezeRatioPercent, 0.0)
    }

    @Test
    fun includesAnOngoingFreezeInSnapshot() {
        val tracker = RenderedFrameContinuityTracker(fps = 15, startupStartedAtNs = 50_000_000L)
        tracker.recordFrame(100_000_000L)

        val snapshot = tracker.snapshot(500_000_000L)
        assertEquals(1L, snapshot.freezeEvents)
        assertEquals(333.33, snapshot.freezeDurationMs, 0.02)
        assertEquals(50.0, snapshot.startupMs ?: 0.0, 0.0)
    }

    @Test
    fun recordsSurfaceMicroJitterBelowTheFreezeThreshold() {
        val tracker = RenderedFrameContinuityTracker(fps = 15, startupStartedAtNs = 0L)
        tracker.recordFrame(100_000_000L)
        tracker.recordFrame(166_000_000L)
        tracker.recordFrame(306_000_000L)
        tracker.recordFrame(372_000_000L)

        val snapshot = tracker.snapshot(372_000_000L)
        assertEquals(0L, snapshot.freezeEvents)
        assertEquals(3, snapshot.surfaceFrameInterval.samples)
        assertEquals(66.0, snapshot.surfaceFrameInterval.p50Ms, 0.0)
        assertEquals(140.0, snapshot.surfaceFrameInterval.p95Ms, 0.0)
        assertEquals(140.0, snapshot.surfaceFrameInterval.p99Ms, 0.0)
        assertEquals(140.0, snapshot.surfaceFrameInterval.maxMs, 0.0)
    }

    @Test
    fun resetsSurfaceCadenceAndExcludesTheInactiveGap() {
        val tracker = RenderedFrameContinuityTracker(fps = 15, startupStartedAtNs = 0L)
        tracker.recordFrame(100_000_000L)
        tracker.recordFrame(240_000_000L)
        tracker.setActive(false)
        tracker.recordFrame(3_000_000_000L)
        assertEquals(0, tracker.snapshot(3_000_000_000L).surfaceFrameInterval.samples)
        tracker.setActive(true)
        tracker.recordFrame(5_000_000_000L)
        tracker.recordFrame(5_066_000_000L)

        val snapshot = tracker.snapshot(5_066_000_000L)
        assertEquals(0L, snapshot.freezeEvents)
        assertEquals(1, snapshot.surfaceFrameInterval.samples)
        assertEquals(66.0, snapshot.surfaceFrameInterval.maxMs, 0.0)
    }

    @Test
    fun everySnapshotDatesTheFirstSurfaceFrame() {
        val tracker = RenderedFrameContinuityTracker(fps = 15, startupStartedAtNs = 1_000_000_000L)
        assertNull(tracker.snapshot(1_500_000_000L).firstFrameAgoMs)
        assertEquals("", continuityFirstSurfaceSuffix(tracker.snapshot(1_500_000_000L)))

        tracker.recordFrame(1_400_000_000L)
        tracker.recordFrame(1_466_000_000L)
        val later = tracker.snapshot(6_400_000_000L)
        assertEquals(400.0, later.startupMs ?: 0.0, 0.0)
        assertEquals(5_000.0, later.firstFrameAgoMs ?: 0.0, 0.0)
        assertEquals(
            " | first surface 400.0 ms after start, 5000.0 ms ago",
            continuityFirstSurfaceSuffix(later),
        )
    }
}
