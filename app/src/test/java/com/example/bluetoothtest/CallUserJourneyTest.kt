package com.example.bluetoothtest

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calls from the user's side, as two people with two phones: dial, ring, answer, decline, cancel, busy, hang up, redial,
 * call back from Recent. Each action a person can take sends a real encoded signal (the wire codec validates reasons and
 * ids) which the other phone applies to its own state machine. The radio is not involved: this is the call lifecycle.
 *
 * The signal-to-event table below mirrors AndroidPrivateVideoCall.handleSignal; a change there that breaks these journeys
 * shows up as a failing journey, not a failing count.
 */
class CallUserJourneyTest {
    private class Phone(val name: String) {
        var call: PrivateCallState? = null
        val phase: PrivateCallPhase? get() = call?.phase
    }

    /** The user's buttons and the signal each one sends; the peer's phone applies it. */
    private class Line(private val a: Phone, private val b: Phone) {
        private fun other(from: Phone) = if (from === a) b else a

        private fun send(from: Phone, signal: PrivateCallSignal, reason: PrivateCallReason = PrivateCallReason.NONE, relayHops: Int = 0) {
            val id = checkNotNull(from.call ?: other(from).call).callId
            val wire = PrivateCallSignalCodec.encode(PrivateCallSignalMessage(id, signal, reason, relayHops))
            receive(other(from), PrivateCallSignalCodec.decode(wire))
        }

        private fun receive(on: Phone, message: PrivateCallSignalMessage) {
            if (message.signal == PrivateCallSignal.INVITE) {
                on.call = PrivateCallState.incoming(message.callId)
                send(on, PrivateCallSignal.RINGING)
                return
            }
            val current = on.call ?: return
            require(message.callId == current.callId) { "a signal of another call" }
            val event = when (message.signal) {
                PrivateCallSignal.RINGING -> PrivateCallEvent.REMOTE_RINGING
                PrivateCallSignal.ACCEPT -> PrivateCallEvent.REMOTE_ACCEPT
                PrivateCallSignal.DECLINE -> PrivateCallEvent.REMOTE_DECLINE
                PrivateCallSignal.CANCEL -> PrivateCallEvent.REMOTE_CANCEL
                PrivateCallSignal.HANG_UP -> PrivateCallEvent.REMOTE_HANG_UP
                else -> return // camera and microphone state do not move the call
            }
            on.call = current.transition(event)
        }

        /** A signal that arrives on [on] from outside (a late message of an earlier call, for instance). */
        fun deliver(on: Phone, message: PrivateCallSignalMessage) = receive(on, message)

        fun dial(from: Phone) {
            from.call = PrivateCallState.outgoing()
            send(from, PrivateCallSignal.INVITE)
        }

        fun answer(who: Phone) {
            who.call = who.call!!.transition(PrivateCallEvent.LOCAL_ACCEPT)
            send(who, PrivateCallSignal.ACCEPT)
        }

        fun decline(who: Phone, reason: PrivateCallReason = PrivateCallReason.REJECTED) {
            who.call = who.call!!.transition(PrivateCallEvent.LOCAL_DECLINE)
            send(who, PrivateCallSignal.DECLINE, reason)
        }

        fun cancel(who: Phone) {
            who.call = who.call!!.transition(PrivateCallEvent.LOCAL_CANCEL)
            send(who, PrivateCallSignal.CANCEL)
        }

        fun hangUp(who: Phone) {
            who.call = who.call!!.transition(PrivateCallEvent.LOCAL_HANG_UP)
            send(who, PrivateCallSignal.HANG_UP, PrivateCallReason.NORMAL)
        }

        fun mediaFlows() {
            listOf(a, b).forEach { it.call = it.call!!.transition(PrivateCallEvent.MEDIA_READY) }
        }
    }

    private val asha = Phone("Asha")
    private val bilal = Phone("Bilal")
    private val line = Line(asha, bilal)

    @Test
    fun dialRingAnswerTalkHangUp() {
        line.dial(asha)
        assertEquals("the caller sees the call being placed and then ringing", PrivateCallPhase.OUTGOING_RINGING, asha.phase)
        assertEquals("the callee's phone rings", PrivateCallPhase.INCOMING_RINGING, bilal.phase)

        line.answer(bilal)
        assertEquals(PrivateCallPhase.NEGOTIATING_MEDIA, bilal.phase)
        assertEquals(PrivateCallPhase.NEGOTIATING_MEDIA, asha.phase)
        line.mediaFlows()
        assertEquals(PrivateCallPhase.ACTIVE, asha.phase)
        assertEquals(PrivateCallPhase.ACTIVE, bilal.phase)

        line.hangUp(asha)
        assertEquals(PrivateCallPhase.ENDED, asha.phase)
        assertEquals("the other side's call ends too", PrivateCallPhase.ENDED, bilal.phase)
    }

    @Test
    fun theCallerCancelsWhileItRingsAndTheOtherPhoneStopsRinging() {
        line.dial(asha)
        line.cancel(asha)
        assertEquals(PrivateCallPhase.ENDED, asha.phase)
        assertEquals("no ringing phone is left behind", PrivateCallPhase.ENDED, bilal.phase)
    }

    @Test
    fun theCallerCanCancelBeforeTheFirstRingToo() {
        val id = PrivateCallState.outgoing()
        assertEquals(PrivateCallPhase.OUTGOING_INVITE, id.phase)
        assertEquals(PrivateCallPhase.ENDED, id.transition(PrivateCallEvent.LOCAL_CANCEL).phase)
    }

    @Test
    fun theCalleeDeclinesAndTheCallerIsToldItWasDeclinedNotBusy() {
        line.dial(asha)
        line.decline(bilal, PrivateCallReason.REJECTED)
        assertEquals(PrivateCallPhase.ENDED, bilal.phase)
        assertEquals(PrivateCallPhase.ENDED, asha.phase)
        // The two reasons are different on the wire, so the caller can say "declined" or "busy".
        assertNotEquals(PrivateCallReason.REJECTED.wireValue, PrivateCallReason.BUSY.wireValue)
    }

    @Test
    fun aPhoneThatIsAlreadyDiallingSomeoneElseAnswersBusyAndTheOwnDialGoesOn() {
        val dialling = OwnDial(advertKey = "c0ffee000001", targetPrefix = "c0ffee000001", name = "Chen")
        val strangerPrefix = "a1b2c3d4e5f6"
        assertEquals(DialCollision.Action.DECLINE_BUSY, DialCollision.decide(dialling, strangerPrefix))
        // The person being dialled calling first is the same conversation: it rings and the two dials meet.
        assertEquals(DialCollision.Action.RING, DialCollision.decide(dialling, "c0ffee000001"))
        assertEquals("nobody dialled: it rings as always", DialCollision.Action.RING, DialCollision.decide(null, strangerPrefix))

        // The busy answer ends the caller's call with the existing DECLINE reason.
        line.dial(asha)
        line.decline(bilal, PrivateCallReason.BUSY)
        assertEquals(PrivateCallPhase.ENDED, asha.phase)
    }

    @Test
    fun redialAfterAnEndedCallIsAFreshCallAndTheOldOneCannotBeActedOn() {
        line.dial(asha)
        val first = asha.call!!.callId
        line.cancel(asha)

        assertThrows("an ended call takes no more actions", IllegalStateException::class.java) {
            asha.call!!.transition(PrivateCallEvent.LOCAL_HANG_UP)
        }
        line.dial(asha)
        assertNotEquals("a redial is a new call", first, asha.call!!.callId)
        assertEquals(PrivateCallPhase.OUTGOING_RINGING, asha.phase)
        assertEquals(PrivateCallPhase.INCOMING_RINGING, bilal.phase)
        assertEquals(bilal.call!!.callId, asha.call!!.callId)
    }

    @Test
    fun aLateSignalOfAnEarlierCallNeverTouchesTheNewCall() {
        line.dial(asha)
        val old = asha.call!!.callId
        line.cancel(asha)
        line.dial(asha)
        val late = PrivateCallSignalCodec.decode(PrivateCallSignalCodec.encode(PrivateCallSignalMessage(old, PrivateCallSignal.HANG_UP, PrivateCallReason.NORMAL)))
        assertNotEquals(late.callId, bilal.call!!.callId)
        assertThrows(IllegalArgumentException::class.java) { line.deliver(bilal, late) }
        assertEquals("the new call is still ringing", PrivateCallPhase.INCOMING_RINGING, bilal.phase)
    }

    @Test
    fun noPhaseTrapsTheUserTheyCanAlwaysLeaveAndALostLinkAlwaysEndsTheCall() {
        val id = UUID.randomUUID()
        val states = listOf(
            PrivateCallState(id, PrivateCallRole.CALLER, PrivateCallPhase.OUTGOING_INVITE),
            PrivateCallState(id, PrivateCallRole.CALLER, PrivateCallPhase.OUTGOING_RINGING),
            PrivateCallState(id, PrivateCallRole.CALLEE, PrivateCallPhase.INCOMING_RINGING),
            PrivateCallState(id, PrivateCallRole.CALLER, PrivateCallPhase.NEGOTIATING_MEDIA),
            PrivateCallState(id, PrivateCallRole.CALLEE, PrivateCallPhase.ACTIVE),
        )
        val ways = listOf(PrivateCallEvent.LOCAL_CANCEL, PrivateCallEvent.LOCAL_DECLINE, PrivateCallEvent.LOCAL_HANG_UP)
        states.forEach { state ->
            val leaves = ways.filter { runCatching { state.transition(it) }.map { next -> next.phase == PrivateCallPhase.ENDED }.getOrDefault(false) }
            assertTrue("${state.phase}: the user has a way to end it", leaves.isNotEmpty())
            assertEquals("${state.phase}: a lost link ends the call", PrivateCallPhase.ENDED, state.transition(PrivateCallEvent.LINK_LOST).phase)
        }
    }

    @Test
    fun anUnansweredCallEndsOnBothPhonesWithoutAnyoneTouchingIt() {
        line.dial(asha)
        val since = 1_000L
        assertFalse(CallRingTimeout.callerExpired(true, asha.call!!.phase, since, since + 59_000L))
        assertTrue("the caller gives up", CallRingTimeout.callerExpired(true, asha.call!!.phase, since, since + CallRingTimeout.CALLER_MS))
        line.cancel(asha)
        assertEquals("the callee's ringing stops with it", PrivateCallPhase.ENDED, bilal.phase)
        // That cancel at the limit is a missed call for the callee, not a caller who changed their mind.
        assertEquals(CallRingTimeout.MISSED_MESSAGE, CallRingTimeout.cancelledMessage(true, CallRingTimeout.CALLER_MS + 100L))
        assertEquals(CallRingTimeout.CALLER_CANCELLED_MESSAGE, CallRingTimeout.cancelledMessage(true, 5_000L))
    }

    @Test
    fun anAnsweredCallIsNeverTimedOutByTheRingingRule() {
        line.dial(asha)
        line.answer(bilal)
        assertFalse(CallRingTimeout.callerExpired(true, asha.call!!.phase, 0L, 10 * 60_000L))
        assertFalse(CallRingTimeout.calleeExpired(true, bilal.call!!.phase, 0L, 10 * 60_000L))
    }

    // ---- Recent and calling back ----

    private val bilalPrefix = "0123456789ab"
    private val chenPrefix = "fedcba987654"

    @Test
    fun afterCallsEachCallIsInRecentNewestFirstAndCanBeCalledBack() {
        val phone = FakePhone(Files.createTempDirectory("recent-flow").toFile())
        try {
            val context = phone.start()
            RecentCalls.record(context, RecentCall(bilalPrefix, video = false, atMs = 1_000L))
            RecentCalls.record(context, RecentCall(chenPrefix, video = true, atMs = 2_000L))
            RecentCalls.record(context, RecentCall(bilalPrefix, video = true, atMs = 3_000L))

            // Restart: the list is read back from storage.
            val recent = RecentCalls.load(phone.start())
            assertEquals("every call is kept, the latest on top", listOf(bilalPrefix, chenPrefix, bilalPrefix), recent.map { it.nodePrefixHex })
            assertTrue("the newest row is the latest (video) call", recent.first().video && !recent.last().video)
            // A row with a node prefix is what the Call-back tap dials.
            assertTrue(recent.all { it.nodePrefixHex.matches(Regex("[0-9a-f]{12}")) })
        } finally {
            phone.root.deleteRecursively()
        }
    }

    @Test
    fun aBlockedCallAndAStrangerCannotBeCalledBackFromRecent() {
        val phone = FakePhone(Files.createTempDirectory("recent-flow").toFile())
        try {
            val context = phone.start()
            RecentCalls.recordBlocked(context, atMs = 5_000L)
            RecentCalls.record(context, RecentCall("", video = false, atMs = 6_000L, strangerName = "gold fox"))
            val rows = RecentCalls.load(phone.start())
            assertEquals(2, rows.size)
            rows.forEach { assertTrue("a row with no node prefix has nobody to dial", it.nodePrefixHex.isEmpty()) }
            assertTrue(rows.any { it.blocked })
            assertNull("a stranger's real identity was never stored", rows.firstOrNull { it.strangerName.isNotEmpty() }?.fingerprintHex?.takeIf { it.isNotEmpty() })
        } finally {
            phone.root.deleteRecursively()
        }
    }
}
