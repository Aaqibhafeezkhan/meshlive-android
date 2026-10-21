package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F19 (ql-035b5235 asus-a15s / s22-xiaomi): an unanswered direct call rang for 6 min 6 s. */
class CallRingTimeoutTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun theCallerGivesUpAfterSixtySecondsOfRingingAndTheCalleeAfterSeventyFive() {
        val since = 1_000L
        assertFalse(CallRingTimeout.callerExpired(true, PrivateCallPhase.OUTGOING_RINGING, since, since + 59_999L))
        assertTrue(CallRingTimeout.callerExpired(true, PrivateCallPhase.OUTGOING_RINGING, since, since + 60_000L))
        assertTrue("the F19 call", CallRingTimeout.callerExpired(true, PrivateCallPhase.OUTGOING_RINGING, since, since + 366_000L))
        assertFalse("answered", CallRingTimeout.callerExpired(true, PrivateCallPhase.ACTIVE, since, since + 366_000L))
        assertFalse("control arm", CallRingTimeout.callerExpired(false, PrivateCallPhase.OUTGOING_RINGING, since, since + 366_000L))

        assertFalse(CallRingTimeout.calleeExpired(true, PrivateCallPhase.INCOMING_RINGING, since, since + 60_000L))
        assertFalse(CallRingTimeout.calleeExpired(true, PrivateCallPhase.INCOMING_RINGING, since, since + 64_999L))
        assertTrue("design decision 2026-10-21: 65 s", CallRingTimeout.calleeExpired(true, PrivateCallPhase.INCOMING_RINGING, since, since + 65_000L))
        assertFalse(CallRingTimeout.calleeExpired(true, PrivateCallPhase.NEGOTIATING_MEDIA, since, since + 65_000L))
        assertTrue("a lost Cancel ends within 5 s", CallRingTimeout.CALLEE_MS - CallRingTimeout.CALLER_MS <= 5_000L)
        assertTrue("a current caller's Cancel comes first", CallRingTimeout.CALLEE_MS > CallRingTimeout.CALLER_MS)
    }

    @Test
    fun aCancelAtTheCallersLimitIsAMissedCall() {
        assertEquals(CallRingTimeout.MISSED_MESSAGE, CallRingTimeout.cancelledMessage(true, 60_100L))
        assertEquals(CallRingTimeout.CALLER_CANCELLED_MESSAGE, CallRingTimeout.cancelledMessage(true, 12_000L))
        assertEquals(CallRingTimeout.CALLER_CANCELLED_MESSAGE, CallRingTimeout.cancelledMessage(true, null))
        assertEquals("control arm", CallRingTimeout.CALLER_CANCELLED_MESSAGE, CallRingTimeout.cancelledMessage(false, 60_100L))
    }

    @Test
    fun theTimeoutUsesExistingSignalsAndIsWiredToBothRoles() {
        // Existing wire values: Cancel (no reason) and Decline/Rejected.
        val id = java.util.UUID.randomUUID()
        PrivateCallSignalMessage(id, PrivateCallSignal.CANCEL)
        PrivateCallSignalMessage(id, PrivateCallSignal.DECLINE, PrivateCallReason.REJECTED)
        assertTrue("the ring limit ships on", DebugTunables.callRingTimeout)
    }
}
