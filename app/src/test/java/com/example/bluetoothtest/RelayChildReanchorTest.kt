package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayChildReanchorTest {
    private val tick = 500_000_000L

    /** Ticks every 0.5 s at [kbps] delivered with [backlogMs] waiting; returns the first starved tick. */
    private fun firstStarvedTick(kbps: Int, backlogMs: Long, ticks: Int = 20): Pair<Int, Int>? {
        val starvation = RelayChildStarvation()
        var bytes = 0L
        for (i in 0..ticks) {
            starvation.onTick(i * tick, bytes, backlogMs)?.let { return i to it }
            bytes += kbps * 1_000L / 8 / 2
        }
        return null
    }

    @Test
    fun aChildStarvedForTwoSecondsWithMediaWaitingIsDetected() {
        // relaylink snoop: an anchor inside the parent's multi-packet window carried 4-37 kbps.
        val (atTick, kbps) = requireNotNull(firstStarvedTick(kbps = 8, backlogMs = 1_500))
        assertEquals(4, atTick) // two seconds of 0.5 s ticks
        assertTrue(kbps < RelayChildStarvation.THRESHOLD_KBPS)
    }

    @Test
    fun aHealthyAnchorOrAQuietLinkIsNotStarved() {
        // Otherwise the same relay carried 349-378 kbps.
        assertNull(firstStarvedTick(kbps = 350, backlogMs = 1_500))
        // Nothing waiting (a paused stream) is not starvation even at 0 kbps.
        assertNull(firstStarvedTick(kbps = 0, backlogMs = 0))
    }

    @Test
    fun theBacklogMustLastTheWholeWindow() {
        val starvation = RelayChildStarvation()
        assertNull(starvation.onTick(0, 0, 800))
        assertNull(starvation.onTick(tick, 0, 800))
        assertNull(starvation.onTick(2 * tick, 0, 0)) // drained: start over
        assertNull(starvation.onTick(3 * tick, 0, 800))
        assertNull(starvation.onTick(6 * tick, 0, 800))
        assertEquals(0, starvation.onTick(7 * tick, 0, 800))
    }

    @Test
    fun atMostTwoReanchorsPerChildPerMinuteThenTheWithdrawalPolicyDecides() {
        val budget = RelayReanchorBudget()
        val second = 1_000_000_000L
        assertEquals(1, budget.tryAttempt("m31", 0))
        assertEquals(2, budget.tryAttempt("m31", 5 * second))
        assertNull(budget.tryAttempt("m31", 30 * second))
        assertEquals(1, budget.tryAttempt("a15s", 30 * second)) // per child
        assertEquals(2, budget.tryAttempt("m31", 61 * second)) // the first attempt aged out
    }

    @Test
    fun aChildRetriesItsRelayParentFirstOnlyAfterALinkLossOfAnActiveRelayedRoute() {
        val relay = byteArrayOf(1, 2, 3)
        val source = byteArrayOf(9, 9, 9)
        val preferred = recoveryParentPreference(wasActive = true, linkLoss = true, parentRouteCost = 1, parentPrefix = relay, enabled = true)
        assertArrayEquals(relay, preferred)
        assertEquals(0, preferredRecoveryRank(relay, preferred))
        assertEquals(1, preferredRecoveryRank(source, preferred))
        // A withdrawal, an inactive route, a direct source parent or the switch off keep today's ranking.
        assertNull(recoveryParentPreference(true, linkLoss = false, parentRouteCost = 1, parentPrefix = relay, enabled = true))
        assertNull(recoveryParentPreference(false, true, 1, relay, true))
        assertNull(recoveryParentPreference(true, true, 0, relay, true))
        assertNull(recoveryParentPreference(true, true, 1, relay, enabled = false))
        assertEquals(1, preferredRecoveryRank(relay, null))
    }

    @Test
    fun reanchoringIsOnByDefault() = assertTrue(DebugTunables.relayChildReanchor)

    @Test
    fun afterAReanchorTheChildWaitsForItsRelayInsteadOfTakingTheFirstOfferHeard() {
        // A15s 2026-10-10 09:52:10: the S24 source (full) was heard 147 ms after the ASUS re-anchor and won.
        val relay = byteArrayOf(1, 2, 3)
        val source = byteArrayOf(9, 9, 9)
        val lostAtMs = 10_000L
        val graceUntil = preferredParentGraceDeadline(relay, lostAtMs, enabled = true)
        assertEquals(lostAtMs + PREFERRED_PARENT_GRACE_MS, graceUntil)
        val onlySource = listOf(source)
        assertTrue(recoveryCandidatesDuringGrace(onlySource, { it }, relay, graceUntil, lostAtMs + 147).isEmpty())
        // Once the relay's fresh offer arrives, the usual ordering decides among everything heard.
        val both = listOf(source, relay)
        assertEquals(both, recoveryCandidatesDuringGrace(both, { it }, relay, graceUntil, lostAtMs + 300))
        // The relay never returns: after the grace the source is allowed again.
        assertEquals(onlySource, recoveryCandidatesDuringGrace(onlySource, { it }, relay, graceUntil, graceUntil))
    }

    @Test
    fun withoutAPreferredParentOrWithTheGraceOffNothingIsHeld() {
        val source = byteArrayOf(9, 9, 9)
        assertEquals(0L, preferredParentGraceDeadline(null, 10_000L, enabled = true))
        assertEquals(0L, preferredParentGraceDeadline(byteArrayOf(1), 10_000L, enabled = false))
        assertEquals(listOf(source), recoveryCandidatesDuringGrace(listOf(source), { it }, null, 12_000L, 10_100L))
        // Control arm: deadline 0 means the grace has already ended.
        assertEquals(listOf(source), recoveryCandidatesDuringGrace(listOf(source), { it }, byteArrayOf(1), 0L, 10_100L))
        assertTrue(DebugTunables.relayRecoveryGrace)
    }
}
