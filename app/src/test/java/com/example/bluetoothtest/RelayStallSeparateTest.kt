package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b14-audio-chain-a5a8f056 music r1 (M31 relay, A15s child), `relay_child_stall stage=hop` times in ms from 20:45:04.196:
 * 0, 15,922 (outside the 10 s hold-off), then after the 5 minute mark: 323,165 and 338,206. Each pair withdrew the offer.
 */
class RelayStallSeparateTest {
    private val child = "a15a15a15a15"
    private val stallTimes = longArrayOf(0L, 15_922L, 323_165L, 338_206L)

    private fun replay(separate: Boolean): List<Boolean> {
        val tracker = EstablishmentFailureTracker()
        return stallTimes.map { at ->
            when (classifyRelayChildFailure(hopAuthenticated = false, message = "EOFException", stalled = true, stallSeparate = separate)) {
                RelayChildFailureKind.STALL -> tracker.onStall(child, at)
                RelayChildFailureKind.REFUSAL -> tracker.onFailure(child, at)
                RelayChildFailureKind.NONE -> false
            }
        }
    }

    @Test
    fun theControlArmReproducesTheTwoFiveMinuteWithdrawals() {
        assertEquals(listOf(false, true, false, true), replay(separate = false))
    }

    @Test
    fun separateStallsNeverWithdrawTheOfferForTwoDeadAnchorsInARow() {
        assertEquals(listOf(false, false, false, false), replay(separate = true))
    }

    @Test
    fun theTrackerStaysUnmarkedSoTheNextRequestDials() {
        val tracker = EstablishmentFailureTracker()
        tracker.onStall(child, 0L)
        tracker.onStall(child, 15_922L)
        assertFalse(tracker.isMarked(child, 16_000L))
    }

    @Test
    fun threeStallsInTwoMinutesMarkForThirtySecondsThenEscalate() {
        val tracker = EstablishmentFailureTracker()
        assertFalse(tracker.onStall(child, 0L))
        assertFalse(tracker.onStall(child, 16_000L))
        assertTrue(tracker.onStall(child, 32_000L))
        assertEquals(30_000L, tracker.remainingMs(child, 32_000L))
        assertFalse(tracker.isMarked(child, 62_000L))
        // Three more after the mark expired: the second mark is 60 s.
        assertFalse(tracker.onStall(child, 70_000L))
        assertFalse(tracker.onStall(child, 85_000L))
        assertTrue(tracker.onStall(child, 100_000L))
        assertEquals(60_000L, tracker.remainingMs(child, 100_000L))
        // A completed handshake resets the level.
        tracker.onSuccess(child)
        assertFalse(tracker.isMarked(child, 100_001L))
        assertFalse(tracker.onStall(child, 200_000L))
        assertFalse(tracker.onStall(child, 210_000L))
        assertTrue(tracker.onStall(child, 220_000L))
        assertEquals(30_000L, tracker.remainingMs(child, 220_000L))
    }

    @Test
    fun stallsOutsideTheWindowDoNotAccumulate() {
        val tracker = EstablishmentFailureTracker()
        tracker.onStall(child, 0L)
        tracker.onStall(child, 60_000L)
        assertFalse(tracker.onStall(child, 130_000L))
    }

    @Test
    fun refusalsKeepTheirTwoFailureFiveMinuteRule() {
        val tracker = EstablishmentFailureTracker()
        assertFalse(tracker.onFailure(child, 0L))
        assertTrue(tracker.onFailure(child, 20_000L))
        assertEquals(EstablishmentFailureTracker.MARK_MS, tracker.remainingMs(child, 20_000L))
        // Stalls do not add to the refusal count.
        val other = EstablishmentFailureTracker()
        other.onStall(child, 0L)
        assertFalse(other.onFailure(child, 5_000L))
    }

    @Test
    fun aStalledAttemptIsAStallEvenIfItsMessageNamesA133() {
        assertEquals(
            RelayChildFailureKind.STALL,
            classifyRelayChildFailure(false, "GATT video receiver disconnected (status 133)", stalled = true, stallSeparate = true),
        )
        assertEquals(
            RelayChildFailureKind.REFUSAL,
            classifyRelayChildFailure(false, "GATT video receiver disconnected (status 133)", stalled = false, stallSeparate = true),
        )
        assertEquals(RelayChildFailureKind.NONE, classifyRelayChildFailure(true, "status 133", stalled = true, stallSeparate = true))
        assertEquals(RelayChildFailureKind.NONE, classifyRelayChildFailure(false, "EOFException", stalled = false, stallSeparate = true))
    }
}
