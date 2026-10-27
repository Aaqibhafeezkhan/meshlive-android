package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastEstablishDialsTest {
    private val fast = ConnectDurationClass.FAST_ESTABLISH_FAILURE

    @Test
    fun outsideItsScopeTheOrdinaryRuleDecides() {
        // Switch off means no first-dial deadline (SetupDeadline.extraDials), so nothing changes for any connect path.
        assertEquals(
            FastEstablishDials.Verdict.NotApplicable,
            FastEstablishDials.next(fast, attemptsUsed = 1, nowMs = 0L, deadlineMs = null, jitterMs = null),
        )
    }

    @Test
    fun otherFailureClassesKeepTheOrdinaryRule() {
        ConnectDurationClass.values().filter { it != fast }.forEach { other ->
            assertEquals(
                FastEstablishDials.Verdict.NotApplicable,
                FastEstablishDials.next(other, attemptsUsed = 1, nowMs = 0L, deadlineMs = 15_000L, jitterMs = 0L),
            )
        }
    }

    @Test
    fun aRunOfMissedConnectIndsGetsFourJitteredDialsInsideTheWindow() {
        // Two fast failures in a row ended the call before (the ordinary rule redials once).
        val deadline = 15_000L
        var now = 1_000L
        var attempts = 1
        val delays = mutableListOf<Long>()
        val jitters = listOf(0L, 250L, 500L, 999L)
        while (true) {
            val verdict = FastEstablishDials.next(fast, attempts, now, deadline, jitters[(attempts - 1) % jitters.size])
            if (verdict !is FastEstablishDials.Verdict.Dial) {
                assertEquals(FastEstablishDials.Verdict.Stop, verdict)
                break
            }
            delays += verdict.delayMs
            now += verdict.delayMs + 400L // each dial fails 0.4 s later (Connection Complete, then 0x3E)
            attempts += 1
        }
        assertEquals(FastEstablishDials.MAX_ATTEMPTS, attempts)
        // 0.3 s plus jitter, capped at 0.8 s.
        assertEquals(listOf(300L, 550L, 800L), delays)
        assertTrue(now < deadline)
    }

    @Test
    fun noDialStartsAfterTheEarlyLossDeadline() {
        assertEquals(
            FastEstablishDials.Verdict.Stop,
            FastEstablishDials.next(fast, attemptsUsed = 1, nowMs = 14_800L, deadlineMs = 15_000L, jitterMs = 0L),
        )
    }
}
