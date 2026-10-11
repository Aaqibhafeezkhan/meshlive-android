package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

class CallSetupDeadlinesTest {
    @Test
    fun theHopHandshakeDeadlineIsOrdersOfMagnitudeAboveTheMeasuredAuthenticationTimeAndBelowTheEarlyLossWindow() {
        // Measured hop authentication: 71-269 ms over 16 attempts (rotid-3996bfb2-combined-privacy).
        assertTrue(CallSetupDeadlines.HOP_AUTH_MS >= 20 * 269L)
        assertTrue(CallSetupDeadlines.HOP_AUTH_MS <= AdaptivePhyMemory.EARLY_LOSS_MS)
        assertTrue(CallSetupDeadlines.HOP_AUTH_MS > AdaptivePhyMemory.SETUP_STALL_MS)
    }

    @Test
    fun theTimeoutOwnsAPendingAttemptOnlyAndNeverOneThatAuthenticatedOrWasStalled() {
        val pending = CallSetupStallState()
        assertTrue(pending.pending)
        assertTrue(pending.claimTimeout())
        assertTrue(pending.timedOut)
        assertFalse("a handshake completing after the deadline is closed, not kept", pending.authenticated())
        assertFalse(pending.claimTimeout())

        val authenticated = CallSetupStallState()
        assertTrue(authenticated.authenticated())
        assertFalse(authenticated.claimTimeout())
        assertFalse(authenticated.timedOut)
        assertFalse(authenticated.pending)

        val stalled = CallSetupStallState()
        assertTrue(stalled.claimStall())
        assertFalse(stalled.claimTimeout())
        assertFalse(stalled.timedOut)

        val ended = CallSetupStallState()
        ended.ended()
        assertFalse(ended.claimTimeout())
    }

    @Test
    fun theCallerMessagesNameTheLikelyCauseAndWhatToDo() {
        val timeout = CallSetupDeadlines.timeoutMessage()
        assertTrue("${CallSetupDeadlines.HOP_AUTH_MS / 1000} seconds" in timeout)
        assertTrue("older version" in timeout && "Update both phones" in timeout)
        val closed = CallSetupDeadlines.closedMessage()
        assertTrue("older version" in closed && "Update both phones" in closed)
        // The caller's failure text reaches the user as a normal connection failure.
        val shown = privateCallConnectionFailureMessage(
            BluetoothTransportOption.BLE_GATT,
            CallHandshakeClosedException(closed, EOFException()),
        )
        assertTrue(shown.startsWith("Call could not connect: ") && "closed the connection" in shown)
    }

    @Test
    fun theResponderLogsOneFixedReasonForARefusedOldStyleHandshakeAndNothingElse() {
        assertEquals(
            "incoming_handshake: result=failed | reason=bmh2_refused",
            CallSetupDeadlines.incomingFailureLine(Bmh3Failure("bmh2 refused")),
        )
        assertEquals(
            "incoming_handshake: result=failed | reason=EOFException",
            CallSetupDeadlines.incomingFailureLine(EOFException("a message that could carry anything")),
        )
        val deadline = CallSetupDeadlines.logLine("ab12", 10_012L)
        assertTrue(deadline.startsWith("call_setup: event=hop_auth_deadline | node=ab12 | sinceOpenMs=10012 | deadlineMs=10000"))
    }
}
