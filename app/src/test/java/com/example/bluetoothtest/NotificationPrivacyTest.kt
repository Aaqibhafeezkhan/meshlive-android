package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyTest {
    // android.app.Notification: VISIBILITY_PUBLIC 1, VISIBILITY_PRIVATE 0, VISIBILITY_SECRET -1.
    private val public = 1
    private val private = 0
    private val secret = -1

    @Test
    fun sessionNotificationsAreHiddenOnTheLockScreenUntilNamedAndOptedIn() {
        // The service passes peerNamesOptIn = (a name is carried && the user allowed names on the lock screen).
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.SESSION, peerNamesOptIn = false))
        assertEquals(public, NotificationPrivacy.visibility(NotificationKind.SESSION, peerNamesOptIn = true))
        // Audio-only live is never named and never public.
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.AUDIO_LISTEN, peerNamesOptIn = true))
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.AUDIO_BROADCAST, peerNamesOptIn = true))
    }

    @Test
    fun anOngoingCallShowsThePeerNameOnlyInTheFullNotification() {
        assertEquals("Asha", NotificationPrivacy.ongoingPersonName("Encrypted private video call", " Asha "))
        assertEquals("Encrypted private video call", NotificationPrivacy.ongoingPersonName("Encrypted private video call", null))
        assertEquals("Encrypted private video call", NotificationPrivacy.ongoingPersonName("Encrypted private video call", "  "))
        assertEquals(CallNotificationActions.NEUTRAL_PERSON, NotificationPrivacy.ongoingPersonName(null, null))
        assertEquals(CallNotificationActions.NEUTRAL_PERSON, NotificationPrivacy.ongoingPersonName(" ", ""))
        // The CallStyle title is the name, so the text stays the status.
        assertEquals("Encrypted private voice call", NotificationPrivacy.sessionContentText("Encrypted private voice call", "Asha", true))
    }

    @Test
    fun aStreamViewerNotificationPutsTheNameInFrontOfTheStatus() {
        assertEquals("Bluetooth stream connected", NotificationPrivacy.sessionContentText("Bluetooth stream connected", null, false))
        assertEquals("Bluetooth stream connected", NotificationPrivacy.sessionContentText("Bluetooth stream connected", "  ", false))
        assertEquals("Asha \u00B7 Bluetooth stream connected", NotificationPrivacy.sessionContentText("Bluetooth stream connected", "Asha", false))
    }

    @Test
    fun aPeerNameIsCleanedAndCutBeforeItReachesTheShade() {
        assertEquals("Asha", NotificationPrivacy.sessionPeerName("\u0000As\nha\t"))
        assertNull(NotificationPrivacy.sessionPeerName("\u0007 \n"))
        assertNull(NotificationPrivacy.sessionPeerName(null))
        val cut = NotificationPrivacy.sessionPeerName("x".repeat(100) + "   ")!!
        assertEquals(NotificationPrivacy.MAX_PEER_NAME_CHARS, cut.length)
    }

    @Test
    fun incomingCallsKeepTheNameOffTheLockScreenUntilOptIn() {
        // Q16-A: the full notification (unlocked phone) names the caller; the lock screen gets the public version.
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.INCOMING_CALL, false))
        assertEquals(public, NotificationPrivacy.visibility(NotificationKind.INCOMING_CALL, true))
        assertEquals("Asha [abcd]", NotificationPrivacy.incomingCallText("Asha [abcd]"))
        assertEquals(NotificationPrivacy.HIDDEN_PEER_TEXT, NotificationPrivacy.incomingCallText("  "))
        assertEquals(NotificationPrivacy.HIDDEN_PEER_TEXT, NotificationPrivacy.incomingCallText(null))
        assertEquals("Incoming call", NotificationPrivacy.publicText(NotificationKind.INCOMING_CALL))
    }

    @Test
    fun theCallScreenHidesTheNameOnlyOverASecureLockScreenWithoutOptIn() {
        assertTrue(LockScreenNames.masked(keyguardLocked = true, keyguardSecure = true, namesAllowed = false))
        assertFalse(LockScreenNames.masked(keyguardLocked = true, keyguardSecure = true, namesAllowed = true))
        // An unlocked phone, and a swipe-only keyguard that protects nothing, show the name.
        assertFalse(LockScreenNames.masked(keyguardLocked = false, keyguardSecure = true, namesAllowed = false))
        assertFalse(LockScreenNames.masked(keyguardLocked = true, keyguardSecure = false, namesAllowed = false))
    }

    @Test
    fun messagesAreSecretByDefault() {
        assertEquals(secret, NotificationPrivacy.visibility(NotificationKind.MESSAGE, false))
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.MESSAGE, true))
    }

    @Test
    fun publicVersionsNameOnlyTheApp() {
        NotificationKind.values().forEach { kind ->
            val text = NotificationPrivacy.publicText(kind)
            assertFalse(text.contains("encrypted", ignoreCase = true))
            assertFalse(text.contains("video call", ignoreCase = true))
        }
        assertEquals("Bluetooth Video", NotificationPrivacy.PUBLIC_TITLE)
    }
}
