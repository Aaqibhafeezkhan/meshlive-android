package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattWriteTimingTest {
    @Test fun callbackDuringApiDoesNotProduceNegativeWaitOrDoubleCountApiTime() {
        val intervals = gattWriteIntervals(100, 400, 300, 500)
        assertEquals(300L, intervals.apiNs)
        assertEquals(0L, intervals.callbackAfterApiNs)
        assertEquals(100L, intervals.wakeupNs)
        assertEquals(400L, intervals.totalNs)
        assertTrue(intervals.callbackDuringApi)
    }

    @Test fun delayedCallbackSeparatesOperationWaitFromThreadWakeup() {
        val intervals = gattWriteIntervals(100, 200, 900, 1_100)
        assertEquals(100L, intervals.apiNs)
        assertEquals(700L, intervals.callbackAfterApiNs)
        assertEquals(200L, intervals.wakeupNs)
        assertEquals(1_000L, intervals.totalNs)
    }

    @Test fun absentOrPreviousAttemptCallbackCannotInventSuccessfulCallbackTiming() {
        for (callback in listOf(null, 50L)) {
            val intervals = gattWriteIntervals(100, 200, callback, 1_100)
            assertNull(intervals.callbackAfterApiNs)
            assertNull(intervals.wakeupNs)
        }
        val lines = mutableListOf<String>()
        val timing = GattWriteTiming(lines::add)
        timing.record(512, 100, 200, null, 1_100, "rejected")
        timing.record(512, 200, 300, null, 5_000_000_200, "timeout")
        timing.record(512, 5_000_000_300, 5_000_000_400, null, 5_000_000_500, "rejected")
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("rejected=1 timedOut=1 failed=2 missingCallback=2"))
        assertTrue(lines.single().contains("callback-after-api UNMEASURED (0 samples)"))
        assertTrue(lines.single().contains("await-wakeup UNMEASURED (0 samples)"))
    }
}
