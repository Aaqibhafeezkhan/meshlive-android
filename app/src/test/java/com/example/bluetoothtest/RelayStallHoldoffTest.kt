package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b13-audio-chain-626c4b2c music (M31 relay, A15s child), times in ms from the guard's stall close:
 *   0       relay_child_stall stage=hop silentMs=2006 (counted as an establishment failure)
 *   +3,243  the child's advertisement is seen (its receiver is stopping: advertising set started and stopped within 6 ms)
 *   +3,247  dial 1, status 133 after 5,046 ms; internal retry, status 133 after 5,067 ms  -> second failure -> offer withdrawn 5 min
 * The child's real next request comes after its route cooldown (8 to 13 s after it lost the link, so about +11 s or later).
 */
class RelayStallHoldoffTest {
    private val child = "1c5fbbcd07e0"

    /** The relay's decision for one sighting of the child: dial (true) or skip (false), with the tracker as in ReceiverStreamForwarder. */
    private class Relay(val holdoff: RelayStallHoldoff?, val tracker: EstablishmentFailureTracker = EstablishmentFailureTracker()) {
        var dials = 0
        fun stall(key: String, nowMs: Long) {
            holdoff?.onStall(key, nowMs)
            tracker.onFailure(key, nowMs)
        }

        fun sighting(key: String, nowMs: Long): Boolean {
            if (tracker.isMarked(key, nowMs)) return false
            if (holdoff != null && holdoff.remainingMs(key, nowMs) > 0L) return false
            dials++
            return true
        }
    }

    @Test
    fun withoutTheHoldoffTheStaleDialWithdrawsTheOfferForFiveMinutes() {
        val relay = Relay(holdoff = null)
        relay.stall(child, 0)
        // The stale advertisement 3.2 s after the close is dialed; the dial ends in a 5 s connect timeout (status 133).
        assertTrue(relay.sighting(child, 3_243))
        assertTrue("the stall and the stale dial are the two failures", relay.tracker.onFailure(child, 8_300))
        assertTrue(relay.tracker.isMarked(child, 8_400))
        // The child's real request after its cooldown is then refused (skip_connect) for the whole mark.
        assertFalse(relay.sighting(child, 17_000))
        assertTrue(relay.tracker.remainingMs(child, 17_000) > 280_000L)
    }

    @Test
    fun withTheHoldoffTheStaleSightingIsSkippedAndTheRealRequestDials() {
        val relay = Relay(RelayStallHoldoff())
        relay.stall(child, 0)
        // The advertisement 3.2 s after the close belongs to the receiver that is going away.
        assertFalse(relay.sighting(child, 3_243))
        assertEquals(0, relay.dials)
        assertFalse(relay.tracker.isMarked(child, 3_300))
        // The child's request after its cooldown dials a fresh link.
        assertTrue(relay.sighting(child, 17_000))
        assertEquals(1, relay.dials)
        // Only the stall was counted so far; a failure of this real dial would be the second (threshold 2), not the stale one.
        assertTrue(relay.tracker.onFailure(child, 20_000))
    }

    @Test
    fun theHoldoffEndsAndASuccessClearsIt() {
        val holdoff = RelayStallHoldoff()
        holdoff.onStall(child, 1_000)
        assertEquals(RelayStallHoldoff.HOLDOFF_MS, holdoff.remainingMs(child, 1_000))
        assertEquals(1L, holdoff.remainingMs(child, 1_000 + RelayStallHoldoff.HOLDOFF_MS - 1))
        assertEquals(0L, holdoff.remainingMs(child, 1_000 + RelayStallHoldoff.HOLDOFF_MS))
        holdoff.onStall(child, 50_000)
        holdoff.onSuccess(child)
        assertEquals(0L, holdoff.remainingMs(child, 50_001))
    }

    @Test
    fun anotherChildIsNeverHeldOff() {
        val holdoff = RelayStallHoldoff()
        holdoff.onStall(child, 0)
        assertEquals(0L, holdoff.remainingMs("1e3f0761977b", 100))
    }

    @Test
    fun turningTheStallHoldoffOffKeepsRefusalEscalationsHoldoff() {
        val key = "7a7a00stall0a4"
        val stall = RelayStallHoldoff()
        val refusal = RelayStallHoldoff()
        fun policy(stallHoldoff: Boolean, refusalEscalation: Boolean) = RelayChildRecoveryPolicy(
            RelayRecoveryConfig.DEFAULTS.copy(stallHoldoff = stallHoldoff, refusalEscalation = refusalEscalation),
            EstablishmentFailureTracker(),
            stall,
            refusal,
        )
        refusal.onStall(key, 1_000) // a refusal round failed a dial
        // Control arm test_relay_stall_holdoff=false with refusal escalation on (its default): still deferred.
        val deferred = policy(stallHoldoff = false, refusalEscalation = true).dialDecision(key, 2_000)
        assertEquals(RelayStallHoldoff.HOLDOFF_MS - 1_000, deferred.heldMs)
        assertEquals(RelayChildDialGate.Verdict.DEFER_HOLDOFF, deferred.verdict)
        // Refusal escalation off: its hold-off is not read, and the stall hold-off alone does not see it.
        assertEquals(0L, policy(stallHoldoff = true, refusalEscalation = false).holdoffRemainingMs(key, 2_000))
        policy(stallHoldoff = true, refusalEscalation = true).onStallClosed(key, 1_500) // the stall guard closed the next link
        assertEquals(RelayStallHoldoff.HOLDOFF_MS - 500, policy(true, false).holdoffRemainingMs(key, 2_000))
        assertEquals(
            RelayChildDialGate.Verdict.DIAL,
            policy(stallHoldoff = false, refusalEscalation = false).dialDecision(key, 2_000).verdict,
        )
        policy(true, true).onHandshakeComplete(key)
        assertEquals(0L, policy(true, true).holdoffRemainingMs(key, 2_000))
    }
}
