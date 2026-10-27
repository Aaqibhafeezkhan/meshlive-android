package com.example.bluetoothtest

import com.example.bluetoothtest.RecentSightingDial.Decision.DIAL_NOW
import com.example.bluetoothtest.RecentSightingDial.Decision.WAIT_FOR_SIGHTING
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSightingDialTest {
    private val epochSeconds = 900L
    private val midEpochWallMs = 1_792_000_000_000L - Math.floorMod(1_792_000_000_000L, 900_000L) + 450_000L

    private fun decide(
        enabled: Boolean = true,
        ageMs: Long? = 300L,
        rotating: Boolean = true,
        wallMs: Long = midEpochWallMs,
    ) = RecentSightingDial.decide(
        enabled = enabled,
        lastSeenAtMs = ageMs?.let { 100_000L - it },
        nowMs = 100_000L,
        rotating = rotating,
        wallMs = wallMs,
        epochSeconds = epochSeconds,
    )

    @Test
    fun switchOffKeepsTheFreshSightingWaitWhateverTheAge() {
        assertEquals(WAIT_FOR_SIGHTING, decide(enabled = false, ageMs = 0L))
        assertEquals(WAIT_FOR_SIGHTING, decide(enabled = false, ageMs = 300L, rotating = false))
    }

    @Test
    fun aRowHeardUnderOneAndAHalfSecondsAgoIsDialledAtOnce() {
        assertEquals(DIAL_NOW, decide(ageMs = 0L))
        assertEquals(DIAL_NOW, decide(ageMs = 1_500L))
    }

    @Test
    fun anOlderOrUnknownSightingWaitsForTheNextOne() {
        assertEquals(WAIT_FOR_SIGHTING, decide(ageMs = 1_501L))
        assertEquals(WAIT_FOR_SIGHTING, decide(ageMs = 30_000L))
        assertEquals(WAIT_FOR_SIGHTING, decide(ageMs = null))
    }

    @Test
    fun aSightingStampedAfterNowIsNotTrusted() {
        assertEquals(WAIT_FOR_SIGHTING, decide(ageMs = -5L))
    }

    @Test
    fun aRotatingIdNearAnEpochBoundaryWaitsOnEitherSide() {
        val boundary = midEpochWallMs - 450_000L
        assertEquals(WAIT_FOR_SIGHTING, decide(wallMs = boundary + 9_999L))
        assertEquals(WAIT_FOR_SIGHTING, decide(wallMs = boundary - 10_000L))
        assertEquals(WAIT_FOR_SIGHTING, decide(wallMs = boundary))
        assertEquals(DIAL_NOW, decide(wallMs = boundary + 10_000L))
        assertEquals(DIAL_NOW, decide(wallMs = boundary - 10_001L))
    }

    @Test
    fun aFixedIdIgnoresTheEpochBoundary() {
        assertEquals(DIAL_NOW, decide(rotating = false, wallMs = midEpochWallMs - 450_000L))
    }

    @Test
    fun anUnknownEpochLengthCountsAsABoundary() {
        assertEquals(true, RecentSightingDial.nearEpochBoundary(midEpochWallMs, 0L, 10_000L))
    }
}
