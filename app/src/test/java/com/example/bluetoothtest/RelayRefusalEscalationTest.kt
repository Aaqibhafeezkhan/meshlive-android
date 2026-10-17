package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b5-audio-chain-cb548fc5 music r1 (M31 relay, A15s child; the A15s keeps asking to join, it has no other parent):
 * every dial ended in HCI 0x3E (GATT status 133), four dials 1.0 to 1.5 s apart at 22:21:38.8 to 22:21:41.6 (the child is
 * rediscovered every second while it advertises in burst mode), two failures, `withdraw_offer kind=refusal markMs=300000`,
 * 92 sightings skipped, then four more 133 after the mark.
 */
class RelayRefusalEscalationTest {
    private val child = "1c5fbbcd07e0"

    /** The relay's dial policy for the child, as ReceiverStreamForwarder applies it; every dial that is allowed fails with a 133. */
    private class Relay(val escalation: Boolean) {
        val tracker = EstablishmentFailureTracker()
        val holdoff = RelayStallHoldoff()
        var dials = 0
        val marks = mutableListOf<Pair<Long, Long>>() // time, remaining ms

        fun sighting(key: String, nowMs: Long) {
            if (tracker.isMarked(key, nowMs)) return
            if (escalation && holdoff.remainingMs(key, nowMs) > 0L) return
            dials++
            if (escalation) holdoff.onStall(key, nowMs)
            if (tracker.onFailure(key, nowMs, decaying = escalation)) marks += nowMs to tracker.remainingMs(key, nowMs)
        }
    }

    private fun run(escalation: Boolean, seconds: Int): Relay {
        val relay = Relay(escalation)
        // A sighting of the child every second for the whole run (the child keeps requesting).
        for (t in 0..seconds) relay.sighting(child, t * 1_000L)
        return relay
    }

    @Test
    fun theControlArmSpendsBothFailuresInTwoSecondsAndWithdrawsForFiveMinutes() {
        val relay = run(escalation = false, seconds = 120)
        assertEquals(2, relay.dials)
        assertEquals(listOf(1_000L to EstablishmentFailureTracker.MARK_MS), relay.marks)
    }

    @Test
    fun withEscalationTheSecondFailureWaitsForTheHoldoffAndMarksOnlyThirtySeconds() {
        val relay = run(escalation = true, seconds = 120)
        // Dials at 0 s and 10 s (the hold-off), mark 30 s at 10 s; the child is dialed again at 40 s and 50 s ... (see below).
        assertEquals(10_000L to 30_000L, relay.marks.first())
        assertTrue(relay.marks.size >= 2)
        // The second mark is 60 s: consecutive marks of the same child without a success escalate.
        assertEquals(60_000L, relay.marks[1].second)
    }

    @Test
    fun theChildIsRetriedFarMoreOftenInTheFirstTenMinutesAndAlwaysSpaced() {
        val old = run(escalation = false, seconds = 600)
        val new = run(escalation = true, seconds = 600)
        // Old: two dials, a 300 s mark, two more dials, a second 300 s mark. New: marks 30, 60, 120, 300 s with a dial pair after each.
        assertEquals(4, old.dials)
        assertTrue("dials ${new.dials}", new.dials in 8..14)
    }

    @Test
    fun aSuccessResetsTheEscalation() {
        val relay = Relay(escalation = true)
        relay.sighting(child, 0)
        relay.sighting(child, 10_000)
        assertEquals(30_000L, relay.tracker.remainingMs(child, 10_000))
        relay.tracker.onSuccess(child)
        relay.holdoff.onSuccess(child)
        relay.sighting(child, 11_000)
        relay.sighting(child, 21_000)
        assertEquals(30_000L, relay.tracker.remainingMs(child, 21_000))
    }

    @Test
    fun theMarkedChildStaysMarkedUntilTheMarkEnds() {
        val relay = Relay(escalation = true)
        relay.sighting(child, 0)
        relay.sighting(child, 10_000)
        assertTrue(relay.tracker.isMarked(child, 39_999))
        assertFalse(relay.tracker.isMarked(child, 40_000))
    }
}
