package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayChildHealthPolicyTest {
    private val second = 1_000_000_000L

    @Test
    fun aBurstTheChildCanAbsorbIsNeverWithdrawn() {
        val policy = RelayChildHealthPolicy()
        for (t in 0 until 20) assertFalse(policy.shouldWithdraw(backlogAgeMs = 1_800, nowNs = t * second))
    }

    @Test
    fun aChildStayingBehindLongerThanItsBufferIsWithdrawnToReroute() {
        val policy = RelayChildHealthPolicy()
        assertFalse(policy.shouldWithdraw(2_600, 0))
        assertFalse(policy.shouldWithdraw(2_900, 2 * second))
        assertTrue(policy.shouldWithdraw(3_200, 3 * second))
    }

    @Test
    fun recoveringResetsTheClockAndAHugeBacklogWithdrawsAtOnce() {
        val policy = RelayChildHealthPolicy()
        policy.shouldWithdraw(2_600, 0)
        assertFalse(policy.shouldWithdraw(1_000, 2 * second))
        assertFalse(policy.shouldWithdraw(1_200, 3 * second))
        assertFalse(policy.shouldWithdraw(1_300, 5 * second))
        assertTrue(RelayChildHealthPolicy().shouldWithdraw(6_000, 0))
    }

    @Test
    fun withRelayChildSheddingTheGrowthTrendNoLongerWithdrawsAJoiningChild() {
        // mesh4-041da09f 0004: 4 s after joining the A15s was 1.64 s behind and still growing; the
        // trend rule withdrew it before the 1.75-2 s shed budget could act.
        val growth = listOf(300L, 700L, 1_100L, 1_400L, 1_640L)
        val noLoss = RelayChildHealthPolicy()
        assertTrue(growth.withIndex().any { (t, ms) -> noLoss.shouldWithdraw(ms, t * second) })
        val shedding = RelayChildHealthPolicy(sheddingActive = true)
        assertFalse(growth.withIndex().any { (t, ms) -> shedding.shouldWithdraw(ms, t * second) })
        // What shedding cannot remove still withdraws: sustained beyond 2.5 s, or 6 s at once.
        assertFalse(shedding.shouldWithdraw(2_600, 10 * second))
        assertTrue(shedding.shouldWithdraw(3_000, 13 * second))
        assertTrue(RelayChildHealthPolicy(sheddingActive = true).shouldWithdraw(6_000, 0))
    }

    @Test
    fun aStalledChildIsReleasedOnTheTimerInsteadOfWhenItsWriteFinallyReturns() {
        // mesh4-7a6c401c 0001: ASUS -> M31 carried 8 kbps; one write blocked until 12.7 s behind.
        // Judged every 0.5 s (the stall timer), the same policy releases it at the hard limit or
        // sooner, about half the time, so the child can reroute.
        val policy = RelayChildHealthPolicy(sheddingActive = true)
        val firstMs = (1..30).map { it * 500L }.first { ms -> policy.shouldWithdraw(ms, ms * 1_000_000L) }
        assertTrue("released at $firstMs ms", firstMs <= RelayChildHealthPolicy.HARD_BACKLOG_MS)
        // Judged only when the blocked write returned, it is 12.7 s behind before anything happens.
        assertTrue(RelayChildHealthPolicy(sheddingActive = true).shouldWithdraw(12_744, 12_744_000_000L))
    }

    @Test
    fun aJoinKeyframeThatDrainsIsKept() {
        // 55 KB join keyframe on a ~250 kbps child link: ~1.8 s behind, then draining.
        val policy = RelayChildHealthPolicy()
        val backlog = listOf(200L, 1_000L, 1_800L, 1_500L, 1_000L, 600L, 300L, 200L)
        backlog.forEachIndexed { t, ms -> assertFalse(policy.shouldWithdraw(ms, t * second)) }
    }

    @Test
    fun aChildLinkThatKeepsFallingBehindIsWithdrawnEarly() {
        // gradient-9c76a1cc chain: M31 -> ASUS carried 209-278 kbps of a ~330 kbps stream and fell
        // 1.5 -> 4 -> 5 s behind; the sustained rule withdrew it only after 15 s.
        val policy = RelayChildHealthPolicy()
        assertFalse(policy.shouldWithdraw(1_000, 0))
        assertFalse(policy.shouldWithdraw(1_500, 2 * second))
        assertTrue(policy.shouldWithdraw(1_900, 3 * second))
    }
}
