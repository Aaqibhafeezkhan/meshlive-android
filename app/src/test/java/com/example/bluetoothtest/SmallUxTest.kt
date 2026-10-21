package com.example.bluetoothtest

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small UX fixes of 2026-10-21 (rename, call failure text, missed calls, startup timing). */
class SmallUxTest {
    @Test
    fun renamingToAnEmptyNameExplainsItselfInsteadOfADeadButton() {
        assertTrue("Save stays pressable", ContactNameRules.buttonEnabled(ContactNameCheck.Blank, allowBlank = false))
        assertEquals(ContactNameRules.SaveAction.SHOW_EMPTY_ERROR, ContactNameRules.onSave(ContactNameCheck.Blank, allowBlank = false))
        assertEquals(ContactNameRules.SaveAction.SAVE_BLANK, ContactNameRules.onSave(ContactNameCheck.Blank, allowBlank = true))
        assertEquals(ContactNameRules.SaveAction.SAVE, ContactNameRules.onSave(ContactNameCheck.Valid("Ana"), allowBlank = false))
        assertFalse(ContactNameRules.buttonEnabled(ContactNameCheck.Invalid("control"), allowBlank = false))
        assertEquals(ContactNameRules.SaveAction.NONE, ContactNameRules.onSave(ContactNameCheck.Invalid("control"), allowBlank = true))
    }

    @Test
    fun onlyAConnectFailureBecomesCouldntReach() {
        assertTrue(CallEndText.isConnectFailure("Call could not connect: their phone did not accept the Bluetooth connection."))
        assertFalse(CallEndText.isConnectFailure("Call ended"))
        assertFalse(CallEndText.isConnectFailure(CallRingTimeout.NO_ANSWER_MESSAGE))
    }

    @Test
    fun technicalCallEndReasonsAreNotShownToTheUser() {
        val technical = listOf(
            "Call link lost: Call peer stopped responding",
            "Call send failed: java.io.IOException: Broken pipe",
            "Peer ended the call",
            "Incoming call failed: java.lang.IllegalStateException",
        )
        // Each maps to a plain line of its own (a string resource), never to the raw text.
        assertEquals(
            listOf(CallEndText.Kind.DROPPED, CallEndText.Kind.DROPPED, CallEndText.Kind.ENDED, CallEndText.Kind.INCOMING_FAILED),
            technical.map(CallEndText::kind),
        )
        assertEquals(CallEndText.Kind.NO_ANSWER, CallEndText.kind(CallRingTimeout.NO_ANSWER_MESSAGE))
        assertEquals(CallEndText.Kind.ENDED, CallEndText.kind("Call ended"))
    }

    @Test
    fun anUnansweredIncomingCallIsAMissedCall() {
        assertTrue(MissedCallNotification.isMissed(CallRingTimeout.MISSED_MESSAGE))
        assertTrue("the caller cancelled", MissedCallNotification.isMissed(CallRingTimeout.CALLER_CANCELLED_MESSAGE))
        assertFalse(MissedCallNotification.isMissed("Peer ended the call"))
        assertFalse(MissedCallNotification.isMissed(CallRingTimeout.NO_ANSWER_MESSAGE))
        assertEquals("Ana", MissedCallNotification.title("Ana", 1).name)
        assertNull(MissedCallNotification.title(null, 1).name)
        assertNull(MissedCallNotification.title("   ", 1).name)
    }

    @Test
    fun theLockScreenShowsNoNameWithoutTheOptIn() {
        assertEquals("Missed call", NotificationPrivacy.publicText(NotificationKind.MISSED_CALL))
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, NotificationPrivacy.visibility(NotificationKind.MISSED_CALL, peerNamesOptIn = false))
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, NotificationPrivacy.visibility(NotificationKind.MISSED_CALL, peerNamesOptIn = true))
    }

    @Test
    fun noNotificationWithoutPermissionAndNoCallBackWithoutTheToken() {
        assertFalse(MissedCallNotification.allowed(33, granted = false))
        assertTrue(MissedCallNotification.allowed(33, granted = true))
        assertTrue("before Android 13 no permission is needed", MissedCallNotification.allowed(32, granted = false))
        assertTrue(MissedCallNotification.tokenMatches("abc", "abc"))
        assertFalse(MissedCallNotification.tokenMatches("abc", "abd"))
        assertFalse("another app's intent", MissedCallNotification.tokenMatches(null, "abc"))
        assertFalse(MissedCallNotification.tokenMatches("abc", null))
    }

    @Test
    fun startupSectionsAreLoggedInOrder() {
        var now = 1_000L
        val timer = StartupTimer { now }
        now += 40
        timer.mark("super")
        now += 300
        timer.mark("layout")
        now += 5
        timer.mark("rest")
        assertEquals(345L, timer.totalMs())
        assertEquals("startup_main_thread_ms: phase=onCreate | ms=345 | sections=super:40,layout:300,rest:5", timer.line("onCreate"))
    }
}
