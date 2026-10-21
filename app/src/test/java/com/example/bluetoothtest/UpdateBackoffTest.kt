package com.example.bluetoothtest

import org.junit.Assert.*
import org.junit.Test

class UpdateBackoffTest {
    @Test fun manualRetriesCannotBypassTheServerReset() {
        val now = 1_800_000_000_000L
        for (status in listOf(403, 429)) {
            val reset = UpdateSchedule.rateLimitReset(status, 3600, null, null, now)!!
            val state = UpdateSchedule.afterAnswer(UpdateCheckState(), now, null, reset)
            assertFalse(UpdateSchedule.manualAllowed(state, now + 600_000))
            assertEquals(3_000_000L, UpdateSchedule.manualWaitMs(state, now + 600_000))
            assertTrue(UpdateSchedule.manualAllowed(state, reset))
        }
    }
    @Test fun absurdRateHeadersCannotOverflowOrFreezeTheSchedule() {
        val now = 1_800_000_000_000L
        assertEquals(now + UpdateSchedule.DAY_MS, UpdateSchedule.rateLimitReset(429, Long.MAX_VALUE, null, null, now))
        assertEquals(now + UpdateSchedule.DAY_MS, UpdateSchedule.rateLimitReset(403, null, Long.MAX_VALUE, 0, now))
    }
}
