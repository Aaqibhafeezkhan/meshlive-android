package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user-perspective audit quick wins (2026-10-21) that have a rule of their own. */
class UxPoliciesTest {
    private val hour = 60 * 60 * 1_000L

    @Test
    fun theWifiAdviceIsAskedOnceADayAndNeverBeforeAVoiceCall() {
        val answered = 1_000_000L
        assertTrue("never answered", WifiAdvicePolicy.shouldAsk(voice = false, nowMs = answered, answeredAtMs = 0L))
        assertFalse("answered an hour ago", WifiAdvicePolicy.shouldAsk(voice = false, nowMs = answered + hour, answeredAtMs = answered))
        assertTrue("a day later", WifiAdvicePolicy.shouldAsk(voice = false, nowMs = answered + 24 * hour, answeredAtMs = answered))
        assertFalse("voice fits beside 2.4 GHz Wi-Fi", WifiAdvicePolicy.shouldAsk(voice = true, nowMs = answered, answeredAtMs = 0L))
        assertTrue("the clock moved back", WifiAdvicePolicy.shouldAsk(voice = false, nowMs = answered - hour, answeredAtMs = answered))
    }

    @Test
    fun theFirstVideoLiveIsDisclosedOnce() {
        assertTrue(LiveDisclosure.needed(disclosed = false))
        assertFalse(LiveDisclosure.needed(disclosed = true))
    }

    @Test
    fun endingALiveStreamAsksOnlyWhileSomeoneWatches() {
        assertFalse(LiveEndConfirm.ask(0))
        assertTrue(LiveEndConfirm.ask(1))
        assertTrue(LiveEndConfirm.ask(3))
    }

    @Test
    fun everyUnknownEndReasonReadsCallEnded() {
        assertEquals(CallEndText.Kind.ENDED, CallEndText.kind("java.lang.IllegalStateException: socket closed"))
        assertEquals(CallEndText.Kind.ENDED, CallEndText.kind("Peer ended the call"))
        assertEquals(CallEndText.Kind.DROPPED, CallEndText.kind("Call link lost: Call peer stopped responding"))
        assertEquals(CallEndText.Kind.NO_ANSWER, CallEndText.kind(CallRingTimeout.NO_ANSWER_MESSAGE))
        assertEquals(CallEndText.Kind.BUSY, CallEndText.kind(BUSY_DECLINE_MESSAGE))
        assertEquals(CallEndText.Kind.CONNECT_FAILED, CallEndText.kind("Call could not connect: their phone did not accept the Bluetooth connection."))
    }

    @Test
    fun eachCallerGetsTheirOwnMissedCallNotificationWithACount() {
        assertNotEquals(MissedCallNotification.tag("aabbccddeeff"), MissedCallNotification.tag("112233445566"))
        assertEquals(MissedCallNotification.tag("AABBCCDDEEFF"), MissedCallNotification.tag("aabbccddeeff"))
        assertEquals(1, MissedCallNotification.nextCount(stillShowing = false, previous = 4))
        assertEquals(3, MissedCallNotification.nextCount(stillShowing = true, previous = 2))
        assertEquals(MissedCallNotification.Title("Ana", 2), MissedCallNotification.title("Ana", 2))
        assertNull("no name without one", MissedCallNotification.title("   ", 1).name)
        assertEquals(1, MissedCallNotification.title(null, 0).count)
    }
}
