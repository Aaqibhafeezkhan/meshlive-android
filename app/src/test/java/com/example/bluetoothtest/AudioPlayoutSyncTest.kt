package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPlayoutSyncTest {
    private fun warmedUp(baselineMs: Long = 30L) = AudioPlayoutSync(baselineSamples = 3).also { sync ->
        repeat(3) { assertNull(sync.onSample(baselineMs)) }
    }

    @Test
    fun theDeviceOutputLatencyIsTheBaselineNotAnError() {
        val sync = warmedUp(baselineMs = 120)
        assertNull(sync.onSample(140))
        assertEquals(1.0f, sync.speed)
    }

    @Test
    fun audioBehindTheClockSpeedsUpWithinLimitsAndReturnsToNormal() {
        val sync = warmedUp()
        // 200 ms behind the clock (after a shrink): 5% faster.
        assertEquals(1.05f, sync.onSample(230)!!, 1e-4f)
        // Far behind: capped at 6%.
        assertEquals(1.06f, sync.onSample(2_030)!!, 1e-4f)
        // Back within the dead band: normal speed.
        assertEquals(1.0f, sync.onSample(50)!!, 1e-4f)
    }

    @Test
    fun audioAheadOfAGrownDelaySlowsSlightly() {
        val sync = warmedUp()
        assertEquals(0.96f, sync.onSample(-470)!!, 1e-4f)
    }
}
