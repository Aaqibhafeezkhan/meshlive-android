package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallRediscoverTest {
    private val off = StallRediscover.DEFAULT_MS
    private val arm = 20_000L

    @Test
    fun armOffKeepsTheEarlierClampExactly() {
        // 3 s stall + retry start: 12 s of the early-loss window left -> the 8 s cap; later starts shrink it.
        assertEquals(8_000L, StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L))
        assertEquals(5_000L, StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 5_000L))
        assertEquals(0L, StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = -3_000L))
        // A value below the default is the default too.
        assertEquals(8_000L, StallRediscover.budgetMs(1_000L, stalled = true, earlyLossRemainingMs = 12_000L))
        // A found address at 11 s misses the 8 s budget: the stale address is dialed, as measured.
        assertFalse(StallRediscover.dialsFresh(8_000L, 11_000L))
    }

    @Test
    fun armOnOnlyChangesTheStalledPath() {
        assertEquals(8_000L, StallRediscover.budgetMs(arm, stalled = false, earlyLossRemainingMs = 12_000L))
        assertEquals(5_000L, StallRediscover.budgetMs(arm, stalled = false, earlyLossRemainingMs = 5_000L))
        assertEquals(20_000L, StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L))
        // Not bound by the early-loss window, nor by an exhausted one.
        assertEquals(20_000L, StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = -3_000L))
        assertEquals(StallRediscover.MAX_MS, StallRediscover.budgetMs(10_000_000L, stalled = true, earlyLossRemainingMs = 0L))
    }

    @Test
    fun foundAtThreeSecondsDialsTheFreshAddress() {
        val budget = StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L)
        assertTrue(StallRediscover.dialsFresh(budget, 3_000L))
    }

    @Test
    fun foundAtElevenSecondsIsDialedOnlyWithTheArmOn() {
        // ASUS re-advertised 8.6-12.6 s after the close when the A15s LL_TERMINATE never arrived.
        val on = StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L)
        val offBudget = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L)
        assertTrue(StallRediscover.dialsFresh(on, 11_000L))
        assertFalse(StallRediscover.dialsFresh(offBudget, 11_000L))
    }

    @Test
    fun nothingInTwentySecondsDialsTheStaleAddress() {
        val budget = StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L)
        assertFalse(StallRediscover.dialsFresh(budget, null))
        assertFalse(StallRediscover.dialsFresh(budget, 20_001L))
        assertTrue(StallRediscover.dialsFresh(budget, 20_000L))
    }

    @Test
    fun theBenchmarkLineNamesBudgetWaitFoundAddressAndDial() {
        assertEquals(
            "BENCHMARK call_rediscover: budgetMs=20000 | waitedMs=11000 | found=true | sameAddress=false | dialed=fresh | " +
                "stalled=true | armMs=20000",
            StallRediscover.line(20_000L, 11_000L, found = true, sameAddress = false, stalled = true, armMs = arm),
        )
        assertTrue(
            StallRediscover.line(8_000L, 8_000L, found = false, sameAddress = false, stalled = true, armMs = off)
                .contains("dialed=stale"),
        )
    }
}
