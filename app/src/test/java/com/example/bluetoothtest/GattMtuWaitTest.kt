package com.example.bluetoothtest

import com.example.bluetoothtest.GattMtuWait.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved-log cases behind GattMtuWait: a request queued behind the platform's service discovery
 * (A15s 1.3 s median, S24 relay 3.2 s+) must outlive the old 3 s verdict, a dropped request
 * (GattService "configureMTU() - No connection", ASUS and A15s) must not.
 */
class GattMtuWaitTest {
    private fun wait(probe: Boolean = true) = GattMtuWait(probeEnabled = probe).also { it.onRequested(10_000L) }

    @Test
    fun aFastCallbackNeverProbes() {
        val wait = wait()
        assertEquals(Verdict.WAIT, wait.poll(10_700L))
        wait.onMtuCallback()
        assertEquals(Verdict.WAIT, wait.poll(11_000L))
        assertFalse(wait.probeSent)
    }

    @Test
    fun theProbeIsRequestedOnceAfterTheTypicalWait() {
        val wait = wait()
        assertEquals(Verdict.WAIT, wait.poll(10_749L))
        assertEquals(Verdict.SEND_PROBE, wait.poll(10_750L))
        wait.onProbeSent(10_750L)
        assertTrue(wait.probeSent)
        assertEquals(Verdict.WAIT, wait.poll(11_000L))
    }

    @Test
    fun aPlatformAnswerKeepsAQueuedRequestAliveWellPastTheOldVerdict() {
        // relayfix-0725a213-graceB1 0002 (S24): queued at 25.754, still behind discovery 3.2 s later.
        val wait = wait()
        wait.onProbeSent(10_750L)
        wait.onPlatformCallback(10_900L)
        assertTrue(wait.platformAlive)
        assertEquals(Verdict.WAIT, wait.poll(13_000L))
        assertEquals(Verdict.WAIT, wait.poll(13_300L))
        assertEquals(Verdict.WAIT, wait.poll(17_999L))
        assertEquals(Verdict.FAIL_QUEUED_TOO_LONG, wait.poll(18_000L))
    }

    @Test
    fun aQueuedRequestThatCompletesAfterTheOldVerdictIsASuccess() {
        val wait = wait()
        wait.onProbeSent(10_750L)
        wait.onPlatformCallback(10_800L)
        assertEquals(Verdict.WAIT, wait.poll(13_500L))
        wait.onMtuCallback()
        assertEquals(Verdict.WAIT, wait.poll(30_000L))
    }

    @Test
    fun anUnansweredProbeKeepsTheOldThreeSecondVerdict() {
        // quick-baseline-2d89aea9 / a15s run2c: "configureMTU() - No connection", nothing ever follows.
        val wait = wait()
        wait.onProbeSent(10_750L)
        assertEquals(Verdict.WAIT, wait.poll(12_999L))
        assertEquals(Verdict.FAIL_DROPPED, wait.poll(13_000L))
    }

    @Test
    fun aSlowProbeAnswerCannotMakeTheVerdictEarlierThanBefore() {
        val wait = wait()
        wait.onProbeSent(10_750L)
        // The reply window (1 s) ends at 11.75 s, but the old verdict was 13 s and stays the earliest.
        assertEquals(Verdict.WAIT, wait.poll(11_750L))
        assertEquals(Verdict.WAIT, wait.poll(12_900L))
    }

    @Test
    fun aLateAnswerRescuesAWaitThatWasHeadedForTheDroppedVerdict() {
        val wait = wait()
        wait.onProbeSent(10_750L)
        wait.onPlatformCallback(12_950L)
        assertEquals(Verdict.WAIT, wait.poll(13_100L))
    }

    @Test
    fun aPhyCallbackFromThePeerCountsAsPlatformLifeEvenBeforeTheProbe() {
        val wait = wait()
        wait.onPlatformCallback(10_300L)
        assertEquals(Verdict.WAIT, wait.poll(10_800L))
        assertFalse(wait.probeSent)
        assertEquals(Verdict.FAIL_QUEUED_TOO_LONG, wait.poll(18_000L))
    }

    @Test
    fun aProbeThatCannotBeSentFallsBackToTheOldVerdict() {
        val wait = wait()
        wait.onProbeUnavailable()
        assertEquals(Verdict.WAIT, wait.poll(12_999L))
        assertEquals(Verdict.FAIL_FIXED_TIMEOUT, wait.poll(13_000L))
    }

    @Test
    fun theControlArmIsTheOldFixedThreeSeconds() {
        val wait = wait(probe = false)
        assertEquals(Verdict.WAIT, wait.poll(10_800L))
        assertEquals(Verdict.WAIT, wait.poll(12_999L))
        wait.onPlatformCallback(11_000L)
        assertEquals(Verdict.FAIL_FIXED_TIMEOUT, wait.poll(13_000L))
        assertFalse(wait.probeSent)
    }

    @Test
    fun aNewRequestStartsAFreshWait() {
        val wait = wait()
        wait.onProbeSent(10_750L)
        wait.onPlatformCallback(10_800L)
        wait.onMtuCallback()
        wait.onRequested(40_000L)
        assertFalse(wait.platformAlive)
        assertFalse(wait.probeSent)
        assertEquals(Verdict.WAIT, wait.poll(40_300L))
        assertEquals(Verdict.SEND_PROBE, wait.poll(40_800L))
    }

    @Test
    fun noWaitIsReportedBeforeAnyRequest() {
        val wait = GattMtuWait(probeEnabled = true)
        assertEquals(Verdict.WAIT, wait.poll(1_000_000L))
        wait.onPlatformCallback(5L)
        assertFalse(wait.platformAlive)
    }

    @Test
    fun theQueuedBudgetLeavesRoomInTheSetupDeadline() {
        // MTU is requested within about a second of connecting; services and the subscription then need
        // tens of milliseconds, and the whole setup has 12 s.
        assertTrue(1_000L + GattMtuWait.QUEUED_BUDGET_MS + 3_000L <= 12_000L)
    }
}
