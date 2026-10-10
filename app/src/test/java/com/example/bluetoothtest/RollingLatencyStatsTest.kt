package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RollingLatencyStatsTest {
    @Test
    fun reportsRollingPercentiles() {
        val stats = RollingLatencyStats(4)
        listOf(1L, 2L, 3L, 4L, 100L).forEach { milliseconds ->
            stats.recordNanos(milliseconds * 1_000_000)
        }

        val snapshot = stats.snapshot()
        assertEquals(4, snapshot.samples)
        assertEquals(27.25, snapshot.averageMs, 0.0)
        assertEquals(3.0, snapshot.p50Ms, 0.0)
        assertEquals(100.0, snapshot.p95Ms, 0.0)
        assertEquals(100.0, snapshot.p99Ms, 0.0)
        assertEquals(100.0, snapshot.maxMs, 0.0)
    }

    @Test
    fun healthSnapshotsContainOnlyFreshNonOverlappingSamples() {
        val stats = RollingLatencyStats(4)
        stats.recordNanos(142_000_000)
        assertNull(stats.takeSnapshot(2))
        stats.recordNanos(67_000_000)
        assertEquals(142.0, stats.takeSnapshot(2)!!.p95Ms, 0.0)
        assertNull(stats.takeSnapshot(2))
        repeat(2) { stats.recordNanos(67_000_000) }
        assertEquals(67.0, stats.takeSnapshot(2)!!.p95Ms, 0.0)
    }

    @Test
    fun resetRemovesIntervalsAcrossAnIntentionalPause() {
        val stats = RollingLatencyStats()
        stats.recordNanos(33_000_000)
        stats.reset()

        assertEquals(0, stats.snapshot().samples)
    }
}
