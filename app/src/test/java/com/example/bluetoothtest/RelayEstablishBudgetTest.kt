package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay establishment budget (demo-f22f533d-relay/-relay2, 2026-10-12): the M31 relay lost about half
 * of its child connect attempts to HCI 0x3E (GATT 133 after 0.3-0.5 s) and gave up after 4 attempts.
 */
class RelayEstablishBudgetTest {
    private val fast133 = "GATT video receiver disconnected (status 133)"
    private val failed133 = "GATT video connection failed (status 133)"

    @Test
    fun aFastConnectStageFailureIsAnEstablishmentFailure() {
        assertEquals(RelayEstablishBudget.Kind.FAST_ESTABLISHMENT, RelayEstablishBudget.classify(fast133, 330))
        assertEquals(RelayEstablishBudget.Kind.FAST_ESTABLISHMENT, RelayEstablishBudget.classify(failed133, 790))
        assertEquals(RelayEstablishBudget.Kind.FAST_ESTABLISHMENT, RelayEstablishBudget.classify("GATT video receiver disconnected (status 62)", 400))
        assertEquals(
            RelayEstablishBudget.Kind.FAST_ESTABLISHMENT,
            RelayEstablishBudget.classify("BLE control connection failed (status 133)", 360),
        )
    }

    @Test
    fun theFiveSecondCreateConnectionTimeoutIsTargetAbsent() {
        assertEquals(RelayEstablishBudget.Kind.TARGET_ABSENT, RelayEstablishBudget.classify(fast133, 5_010))
        assertEquals(RelayEstablishBudget.Kind.TARGET_ABSENT, RelayEstablishBudget.classify(failed133, 5_004))
    }

    @Test
    fun otherFailuresKeepTheOldRule() {
        // Not a connect-stage message, other statuses, or a duration that is neither fast nor the 5 s timeout.
        assertEquals(RelayEstablishBudget.Kind.OTHER, RelayEstablishBudget.classify("GATT video service discovery failed (status 133)", 300))
        assertEquals(RelayEstablishBudget.Kind.OTHER, RelayEstablishBudget.classify("GATT video receiver disconnected (status 8)", 300))
        assertEquals(RelayEstablishBudget.Kind.OTHER, RelayEstablishBudget.classify(fast133, 2_500))
        assertEquals(RelayEstablishBudget.Kind.OTHER, RelayEstablishBudget.classify(fast133, 9_000))
        assertEquals(RelayEstablishBudget.Kind.OTHER, RelayEstablishBudget.classify(null, 300))
        assertEquals(RelayEstablishBudget.Kind.OTHER, RelayEstablishBudget.classify("GATT video MTU exchange stalled", 300))
    }

    @Test
    fun fastFailuresRetryWithBoundedSpacingUntilTheBudgetEnds() {
        // The relay2 pattern: attempts of ~0.4 s, 300-900 ms apart. 12 s fits ten or more of them.
        val budget = RelayEstablishBudget(startedAtMs = 1_000L, randomBelow = { 300L })
        var now = 1_000L
        var retries = 0
        while (true) {
            now += 400L
            val decision = budget.onFailure(fast133, 400L, now)
            val delay = decision.retryAfterMs ?: break
            assertTrue(delay in RelayEstablishBudget.MIN_DELAY_MS until RelayEstablishBudget.MAX_DELAY_MS)
            now += delay
            retries++
        }
        assertTrue("only $retries in-place retries", retries >= 8)
        assertTrue(now - 1_000L <= RelayEstablishBudget.BUDGET_MS + 1_000L)
    }

    @Test
    fun theSpacingIsRandomWithinTheDocumentedRange() {
        val low = RelayEstablishBudget(0L, randomBelow = { 0L }).onFailure(fast133, 400, 400).retryAfterMs
        val high = RelayEstablishBudget(0L, randomBelow = { bound -> bound - 1 }).onFailure(fast133, 400, 400).retryAfterMs
        assertEquals(300L, low)
        assertEquals(899L, high)
        // The real generator stays inside [300, 900).
        repeat(200) {
            val delay = RelayEstablishBudget(0L).onFailure(fast133, 400, 400).retryAfterMs!!
            assertTrue(delay in 300L until 900L)
        }
    }

    @Test
    fun aRetryNeverStartsWhenItsAttemptCannotFinishInsideTheBudget() {
        val budget = RelayEstablishBudget(startedAtMs = 0L, randomBelow = { 0L })
        // 11.2 s in: 300 ms spacing plus a 600 ms attempt would end at 12.1 s.
        val decision = budget.onFailure(fast133, 400L, 11_200L)
        assertNull(decision.retryAfterMs)
        assertEquals(RelayEstablishBudget.Kind.FAST_ESTABLISHMENT, decision.kind)
        assertNotNull(RelayEstablishBudget(0L, randomBelow = { 0L }).onFailure(fast133, 400L, 11_000L).retryAfterMs)
    }

    @Test
    fun aTargetAbsentTimeoutIsRetriedAtMostOnce() {
        val budget = RelayEstablishBudget(startedAtMs = 0L, randomBelow = { 0L })
        val first = budget.onFailure(failed133, 5_010L, 5_010L)
        assertEquals(RelayEstablishBudget.Kind.TARGET_ABSENT, first.kind)
        assertEquals(300L, first.retryAfterMs)
        // A second timeout would also not fit the budget; the cap stands on its own as well.
        val second = RelayEstablishBudget(0L, randomBelow = { 0L }, budgetMs = 60_000L)
        assertNotNull(second.onFailure(failed133, 5_010L, 5_010L).retryAfterMs)
        assertNull(second.onFailure(failed133, 5_010L, 10_400L).retryAfterMs)
    }

    @Test
    fun fastFailuresStillRetryAfterOneAbsentRetry() {
        val budget = RelayEstablishBudget(0L, randomBelow = { 0L })
        assertNotNull(budget.onFailure(failed133, 5_010L, 5_010L).retryAfterMs)
        assertNotNull(budget.onFailure(fast133, 400L, 5_800L).retryAfterMs)
    }

    @Test
    fun anOtherFailureNeverRetriesThroughTheBudget() {
        val decision = RelayEstablishBudget(0L).onFailure("GATT video MTU exchange stalled", 800L, 800L)
        assertEquals(RelayEstablishBudget.Kind.OTHER, decision.kind)
        assertNull(decision.retryAfterMs)
    }

    @Test
    fun theAttemptCounterFollowsEveryFailure() {
        val budget = RelayEstablishBudget(0L, randomBelow = { 0L })
        budget.onFailure(fast133, 400, 400)
        budget.onFailure(fast133, 400, 1_300)
        assertEquals(2, budget.attemptCount)
        assertEquals(2, budget.onFailure(fast133, 400, 1_300).attempt - 1)
    }

    @Test
    fun theLogLineNamesTheAttemptOnce() {
        val budget = RelayEstablishBudget(0L, randomBelow = { 0L })
        val retry = budget.onFailure(fast133, 331L, 331L)
        val line = RelayEstablishBudget.logLine("gatt", 1, 331L, fast133, retry, 331L)
        assertEquals(
            "BENCHMARK relay_child_connect: link=gatt | attempt=1 | durationMs=331 | status=133 | " +
                "kind=fast_establishment | result=retry | delayMs=300 | elapsedMs=331 | budgetMs=12000",
            line,
        )
        assertTrue(RelayEstablishBudget.logLine("gatt", 2, 440L, null, null, 1_100L).contains("result=ok"))
        val exhausted = RelayEstablishBudget(0L).onFailure(fast133, 400L, 11_900L)
        assertTrue(RelayEstablishBudget.logLine("gatt", 9, 400L, fast133, exhausted, 11_900L).contains("result=exhausted"))
        val other = RelayEstablishBudget(0L).onFailure("GATT video MTU exchange stalled", 800L, 800L)
        assertTrue(RelayEstablishBudget.logLine("gatt", 1, 800L, "GATT video MTU exchange stalled", other, 800L).contains("result=legacy"))
    }

    @Test
    fun exhaustionIsOneEstablishmentFailureForTheFallback() {
        val error = EstablishmentBudgetExhausted(fast133, attempts = 9, elapsedMs = 11_800L, cause = null)
        assertTrue(isRelayEstablishmentFailure(hopAuthenticated = false, message = error.message))
        assertEquals(9, error.attempts)
    }

    // ---- decaying mark -------------------------------------------------------------------------

    @Test
    fun theControlArmKeepsTheFlatFiveMinuteMark() {
        val tracker = EstablishmentFailureTracker()
        assertFalse(tracker.onFailure("a15s", 0L))
        assertTrue(tracker.onFailure("a15s", 1_000L))
        assertTrue(tracker.isMarked("a15s", 1_000L + 299_000L))
        assertFalse(tracker.isMarked("a15s", 1_000L + 300_000L))
        // A second mark is again 300 s.
        assertFalse(tracker.onFailure("a15s", 400_000L))
        assertTrue(tracker.onFailure("a15s", 401_000L))
        assertTrue(tracker.isMarked("a15s", 401_000L + 299_000L))
        assertEquals(300_000L, tracker.remainingMs("a15s", 401_000L))
    }

    @Test
    fun theDecayingMarkEscalatesThirtySixtyOneTwentyThreeHundredSeconds() {
        val tracker = EstablishmentFailureTracker()
        var now = 0L
        val expected = listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L)
        for (markMs in expected) {
            assertFalse(tracker.onFailure("a15s", now, decaying = true))
            now += 1_000L
            assertTrue(tracker.onFailure("a15s", now, decaying = true))
            assertEquals(markMs, tracker.remainingMs("a15s", now))
            assertTrue(tracker.isMarked("a15s", now + markMs - 1))
            now += markMs
            assertFalse(tracker.isMarked("a15s", now))
        }
    }

    @Test
    fun aSuccessResetsTheEscalationAndOtherPeersAreIndependent() {
        val tracker = EstablishmentFailureTracker()
        tracker.onFailure("a15s", 0L, decaying = true)
        assertTrue(tracker.onFailure("a15s", 1L, decaying = true))
        assertEquals(30_000L, tracker.remainingMs("a15s", 1L))
        assertFalse(tracker.isMarked("asus", 1L))
        tracker.onSuccess("a15s")
        assertFalse(tracker.isMarked("a15s", 2L))
        tracker.onFailure("a15s", 100_000L, decaying = true)
        assertTrue(tracker.onFailure("a15s", 100_001L, decaying = true))
        assertEquals(30_000L, tracker.remainingMs("a15s", 100_001L))
    }

    @Test
    fun theMarkLevelsAreTheDocumentedOnes() {
        assertEquals(30_000L, EstablishmentFailureTracker.decayingMarkMs(0))
        assertEquals(60_000L, EstablishmentFailureTracker.decayingMarkMs(1))
        assertEquals(120_000L, EstablishmentFailureTracker.decayingMarkMs(2))
        assertEquals(300_000L, EstablishmentFailureTracker.decayingMarkMs(3))
        assertEquals(300_000L, EstablishmentFailureTracker.decayingMarkMs(42))
    }
}
