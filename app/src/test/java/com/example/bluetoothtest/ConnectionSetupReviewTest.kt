package com.example.bluetoothtest

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Connection-setup review fixes 2026-10-27 (A3, A4, A6, T1, T3, T4). */
class ConnectionSetupReviewTest {
    @Test
    fun callerStopsWaitingForAnAnswerThatNeverComes() {
        val call = UUID.randomUUID()
        assertEquals(15_000L, CallSetupDeadlines.callerAnswerMs(relayed = false))
        // A relayed caller keeps the whole SETUP budget plus time for the end-to-end handshake through the chain.
        assertTrue(CallSetupDeadlines.callerAnswerMs(relayed = true) > RelayedCalls.SETUP_DEADLINE_MS)
        assertTrue(CallSetupDeadlines.callerAnswerExpired(PrivateCallPhase.OUTGOING_INVITE, call, call))
        // RINGING (or an Accept) arrived: the deadline has nothing to end.
        assertFalse(CallSetupDeadlines.callerAnswerExpired(PrivateCallPhase.OUTGOING_RINGING, call, call))
        assertFalse(CallSetupDeadlines.callerAnswerExpired(PrivateCallPhase.NEGOTIATING_MEDIA, call, call))
        // A new attempt is never ended by an old attempt's deadline.
        assertFalse(CallSetupDeadlines.callerAnswerExpired(PrivateCallPhase.OUTGOING_INVITE, UUID.randomUUID(), call))
    }

    @Test
    fun relayedCalleeKeepsTheIncomingDeadlineAfterTheFirstRecord() {
        assertTrue(CallSetupDeadlines.incomingDeadlineAfterDispatch(relayedCallsOn = true, allEndpoints = true))
        // Without relayed calls the ordinary deadline was armed at accept already; the control arm keeps none.
        assertFalse(CallSetupDeadlines.incomingDeadlineAfterDispatch(relayedCallsOn = false, allEndpoints = true))
        assertFalse(CallSetupDeadlines.incomingDeadlineAfterDispatch(relayedCallsOn = true, allEndpoints = false))
    }

    @Test
    fun unansweredRelayedCallEndsAsNoAnswerNotDeclined() {
        // The caller's "No answer" (60 s after the RINGING it received) must come before the callee's relayed cap, which
        // starts earlier (when the callee began ringing): the cap may not fire before the callee's own 65 s ring timeout.
        assertTrue(RelayedCalls.relayedRingingCapMs() >= CallRingTimeout.CALLEE_MS)
        assertTrue(RelayedCalls.relayedRingingCapMs() > CallRingTimeout.CALLER_MS)
    }

    @Test
    fun onlyAStallOrEarlyLossMovesAPairTo1m() {
        assertTrue(TwoMRetryMemory.remembers(firstStalled = true, firstHopAuthenticated = false))
        assertTrue(TwoMRetryMemory.remembers(firstStalled = false, firstHopAuthenticated = true))
        // GATT 133 / discovery / "no channel" before the hop authenticated: 1M would have failed the same way.
        assertFalse(TwoMRetryMemory.remembers(firstStalled = false, firstHopAuthenticated = false))
    }

    @Test
    fun legacyStacksDoNotWaitFiveSecondsForABroadcastTheyNeverSend() {
        assertEquals(1_500L, SetupRetryAclWait.boundMs(29))
        assertEquals(1_500L, SetupRetryAclWait.boundMs(30))
        assertEquals(SetupRetryAclWait.BOUND_MS, SetupRetryAclWait.boundMs(31))
    }

    @Test
    fun oldOrNeverConnectedClientsCloseWithoutWaiting() {
        val created = 1_000_000_000L
        val fresh = GattTeardownSignal(createdAtNs = created)
        assertEquals(0L, fresh.ageMs(created))
        assertEquals(2_500L, fresh.ageMs(created + TimeUnit.MILLISECONDS.toNanos(2_500L)))
        // Disconnect wait: only a client that reached CONNECTED has a connection-list entry to protect.
        assertFalse(GattClientTeardown.disconnectWaitFor(deferred = true, signal = fresh))
        fresh.onConnected()
        assertTrue(GattClientTeardown.disconnectWaitFor(deferred = true, signal = fresh))
        assertFalse(GattClientTeardown.disconnectWaitFor(deferred = false, signal = fresh))
        assertTrue(GattClientTeardown.disconnectWaitFor(deferred = true, signal = null))
        // Registration wait: a client older than the bound registered long ago.
        val old = GattTeardownSignal(createdAtNs = created)
        assertFalse(GattClientTeardown.registrationWaitFor(old, nowNs = created + TimeUnit.MILLISECONDS.toNanos(2_001L)))
    }

    @Test
    fun aRedialWaitsForThePreviousCloseToTheSameRemote() {
        val closes = PendingGattCloses()
        assertTrue(closes.await("AA", 10L))
        val latch = closes.begin("AA")
        assertTrue(closes.pending("AA"))
        assertFalse(closes.pending("BB"))
        assertFalse(closes.await("AA", 10L))
        Thread { Thread.sleep(30L); closes.end("AA", latch) }.start()
        assertTrue(closes.await("AA", 2_000L))
        assertFalse(closes.pending("AA"))
    }

    @Test
    fun fastFailuresAreRedialedInsideOneBudgetAndSlowOnesEnd() {
        val fast = ConnectDurationClass.FAST_ESTABLISH_FAILURE
        val noJitter = { from: Long, _: Long -> from }
        assertEquals(null, FastFailRedial.nextDelayMs(enabled = false, failureClass = fast, elapsedMs = 0L, outerRemainingMs = null, random = noJitter))
        assertEquals(300L, FastFailRedial.nextDelayMs(true, fast, elapsedMs = 2_000L, outerRemainingMs = null, random = noJitter))
        // Out of range (own deadline, stack timeout): the ordinary rule ends it.
        assertEquals(null, FastFailRedial.nextDelayMs(true, ConnectDurationClass.OWN_DEADLINE, 2_000L, null, noJitter))
        assertEquals(null, FastFailRedial.nextDelayMs(true, ConnectDurationClass.STACK_30S_TIMEOUT, 2_000L, null, noJitter))
        // The next dial must still fit the budget and any outer setup budget.
        assertEquals(null, FastFailRedial.nextDelayMs(true, fast, elapsedMs = 10_200L, outerRemainingMs = null, random = noJitter))
        assertEquals(null, FastFailRedial.nextDelayMs(true, fast, elapsedMs = 1_000L, outerRemainingMs = 800L, random = noJitter))
        // The delay stays inside 300..800 ms.
        val seen = (1..200).mapNotNull { FastFailRedial.nextDelayMs(true, fast, 0L, null) }
        assertTrue(seen.all { it in FastFailRedial.MIN_DELAY_MS..FastFailRedial.MAX_DELAY_MS })
    }

    @Test
    fun stallRetryOwnBudgetStartsAtTheRetry() {
        assertEquals(25_000L, StallRetryOwnBudget.deadlineMs(enabled = true, retryStartMs = 15_000L, earlyLossDeadlineMs = 16_000L))
        assertEquals(16_000L, StallRetryOwnBudget.deadlineMs(enabled = false, retryStartMs = 15_000L, earlyLossDeadlineMs = 16_000L))
        assertFalse(StallRetryOwnBudget.waitsForAcl(enabled = true))
        assertTrue(StallRetryOwnBudget.waitsForAcl(enabled = false))
    }
}
