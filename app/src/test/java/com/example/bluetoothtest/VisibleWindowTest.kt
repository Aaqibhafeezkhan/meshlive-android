package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ten-minute Visible-by-name window (G-Q4): exact length, exclusion with Visible-by-handle, restart, clock steps. */
class VisibleWindowTest {
    private var elapsed = 5_000_000L
    private var wall = 1_700_000_000_000L

    private fun window() = VisibleNameWindow({ elapsed }, { wall })

    @Test
    fun theWindowLastsExactlyTenMinutesOfElapsedTime() {
        val w = window()
        assertEquals(VisibleNameWindow.Result.STARTED, w.start("Rahul", null, false))
        assertEquals("Rahul", w.name())
        assertNotNull(w.hash())
        elapsed += VisibleNameWindow.DURATION_MS - 1
        assertTrue(w.active())
        elapsed += 1
        assertFalse(w.active())
        assertNull(w.name())
        assertNull(w.hash())
    }

    @Test
    fun aWallClockMovedBackOrForwardCannotChangeItsLength() {
        val w = window()
        w.start("Rahul", null, false)
        wall -= 3_600_000L // clock set back an hour
        elapsed += 599_000L
        assertTrue(w.active())
        wall += 10 * 3_600_000L // clock jumps forward
        elapsed += 1_000L
        assertFalse(w.active())
    }

    @Test
    fun stopEndsItAtOnceAndAnAppRestartLosesItBecauseItIsInMemoryOnly() {
        val w = window()
        w.start("Rahul", null, false)
        w.stop()
        assertFalse(w.active())
        w.start("Rahul", null, false)
        assertFalse("a new process has a new object", window().active())
    }

    @Test
    fun startingAgainRestartsTheTenMinutes() {
        val w = window()
        w.start("Rahul", null, false)
        elapsed += 400_000L
        w.start("Rahul", null, false)
        elapsed += 400_000L
        assertTrue(w.active())
        elapsed += 200_001L
        assertFalse(w.active())
    }

    @Test
    fun theNameIsCheckedAndARealNameEqualToTheCallNameNeedsConfirmation() {
        val w = window()
        assertEquals(VisibleNameWindow.Result.NAME_INVALID, w.start("~Police", null, false))
        assertEquals(VisibleNameWindow.Result.NAME_INVALID, w.start("", null, false))
        assertEquals(VisibleNameWindow.Result.NAME_INVALID, w.start("a".repeat(40), null, false))
        assertEquals(VisibleNameWindow.Result.NAME_SAME_AS_CALL_NAME_UNCONFIRMED, w.start("Rahul", "Rahul", false))
        assertFalse(w.active())
        assertEquals(VisibleNameWindow.Result.STARTED, w.start("Rahul", "Rahul", true))
        assertEquals(VisibleNameWindow.Result.STARTED, window().start("Camp Rao", "Rahul", false))
    }

    @Test
    fun endsAtWallTimeIsTheRemainingElapsedTimeFromNow() {
        val w = window()
        w.start("Rahul", null, false)
        elapsed += 100_000L
        assertEquals(wall + 500_000L, w.endsAtWallMs)
    }

    @Test
    fun startingOneWindowEndsTheOtherAndNeverBothRun() {
        val w = window()
        var handle = false
        val windows = VisibleWindows(w, { handle = true }, { handle = false })
        windows.startId()
        assertTrue(handle)
        assertEquals(VisibleWindows.Mode.ID, windows.mode(handle))
        assertEquals(VisibleNameWindow.Result.STARTED, windows.startName("Rahul", null, false))
        assertFalse("starting By name ended By ID", handle)
        assertEquals(VisibleWindows.Mode.NAME, windows.mode(handle))
        windows.startId()
        assertTrue(handle)
        assertFalse("starting By ID ended By name", w.active())
        assertEquals(VisibleWindows.Mode.ID, windows.mode(handle))
        windows.stopAll()
        assertFalse(handle)
        assertEquals(VisibleWindows.Mode.NONE, windows.mode(false))
    }

    @Test
    fun aFailedNameStartKeepsTheHandleWindowEnded() {
        val windows = VisibleWindows(window(), { }, { })
        windows.startId()
        assertEquals(VisibleNameWindow.Result.NAME_INVALID, windows.startName("~", null, false))
        assertEquals(VisibleWindows.Mode.NONE, windows.mode(false))
    }

    @Test
    fun manyWindowsNeverAdvanceTheMonotonicEpochOfTheOrdinaryIds() {
        // The window object has no epoch input and no epoch output; the ordinary IDs come from DiscoveryEpochClock alone.
        val clock = DiscoveryEpochClock(EpochClockState(), "boot") { }
        val before = clock.read(wallMs = 1_700_000_000_000L, elapsedMs = 0L)
        val w = window()
        repeat(20) {
            w.start("Rahul$it", null, false)
            elapsed += VisibleNameWindow.DURATION_MS
            w.active()
        }
        val after = clock.read(wallMs = 1_700_000_000_000L + 20 * 600_000L, elapsedMs = 20 * 600_000L)
        val wallEpoch = RotatingDiscoveryIds.wallEpoch((1_700_000_000_000L + 20 * 600_000L) / 1000L)
        assertEquals("the ordinary epoch follows the wall clock, not the windows", wallEpoch, after.epoch)
        assertTrue(after.epoch - before.epoch <= 20)
    }

    @Test
    fun aWindowSpanningABoundaryChangesTheWindowIdAndKeepsTheName() {
        val node = ByteArray(16) { it.toByte() }
        val dayKey = ByteArray(32) { 5 }
        val a = RotatingDiscoveryIds.ownEntry(dayKey, node, 200, RotatingDiscoveryIds.ROLE_WINDOW_OFFER).rdi
        val b = RotatingDiscoveryIds.ownEntry(dayKey, node, 201, RotatingDiscoveryIds.ROLE_WINDOW_OFFER).rdi
        assertFalse(a.contentEquals(b))
        val w = window()
        w.start("Rahul", null, false)
        val before = NamePageCodec.pages(a, w.name()!!)!!
        val after = NamePageCodec.pages(b, w.name()!!)!!
        // Same commitment in both offers; the pages carry the new ID.
        assertEquals(before.size, after.size)
        assertEquals(NamePageCodec.nameHash("Rahul").toList(), before[0].copyOfRange(7, 11).toList())
        assertEquals(NamePageCodec.nameHash("Rahul").toList(), after[0].copyOfRange(7, 11).toList())
    }
}
