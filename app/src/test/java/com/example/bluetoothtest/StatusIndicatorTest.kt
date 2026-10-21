package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusIndicatorTest {
    private fun kind(
        readiness: HomeReadiness = HomeReadiness.READY,
        hidden: Boolean = false,
        paused: Boolean = false,
        hot: Boolean = false,
    ) = statusKind(readiness, hidden, paused, hot)

    @Test
    fun streamsExplainWhyCallsArePausedAndControlKeepsOldStatus() {
        for ((screen, expected) in listOf(ReachabilityScreen.WATCH to StatusKind.WATCHING, ReachabilityScreen.LIVE to StatusKind.LIVE)) {
            assertEquals(expected, statusKind(HomeReadiness.READY, false, false, false, screen))
            assertEquals(StatusKind.READY, statusKind(HomeReadiness.READY, false, false, false, screen, honest = false))
            assertEquals(StatusKind.PERMISSIONS, statusKind(HomeReadiness.PERMISSIONS_NEEDED, false, false, false, screen))
        }
        assertEquals(StatusKind.READY, statusKind(HomeReadiness.READY, false, false, false, ReachabilityScreen.LIVE_BROWSING))
    }

    @Test
    fun readyWhenNothingIsWrong() {
        assertEquals(StatusKind.READY, kind())
    }

    @Test
    fun bluetoothAndPermissionsComeFirst() {
        assertEquals(StatusKind.BLUETOOTH_OFF, kind(HomeReadiness.BLUETOOTH_OFF, hidden = true, paused = true, hot = true))
        assertEquals(StatusKind.PERMISSIONS, kind(HomeReadiness.PERMISSIONS_NEEDED, hidden = true))
        assertEquals(StatusKind.BLUETOOTH_UNAVAILABLE, kind(HomeReadiness.UNSUPPORTED, hot = true))
    }

    @Test
    fun problemsAreShownOneAtATimeInPriorityOrder() {
        assertEquals(StatusKind.HIDDEN, kind(hidden = true, paused = true, hot = true))
        assertEquals(StatusKind.BATTERY_SAVER, kind(paused = true, hot = true))
        assertEquals(StatusKind.HOT, kind(hot = true))
    }

    @Test
    fun onlyReadyIsNotAProblem() {
        StatusKind.entries.forEach { assertEquals(it != StatusKind.READY, it.problem) }
    }
}
