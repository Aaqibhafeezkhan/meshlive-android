package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationPrivacyTest {
    // android.app.Notification: VISIBILITY_PUBLIC 1, VISIBILITY_PRIVATE 0, VISIBILITY_SECRET -1.
    private val public = 1
    private val private = 0
    private val secret = -1

    @Test
    fun sessionNotificationsArePrivateWhateverTheOptIn() {
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.SESSION, peerNamesOptIn = false))
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.SESSION, peerNamesOptIn = true))
    }

    @Test
    fun incomingCallsShowTheNameOnlyAfterOptIn() {
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.INCOMING_CALL, false))
        assertEquals(public, NotificationPrivacy.visibility(NotificationKind.INCOMING_CALL, true))
        assertEquals(NotificationPrivacy.HIDDEN_PEER_TEXT, NotificationPrivacy.incomingCallText("Asha [abcd]", false))
        assertEquals("Asha [abcd]", NotificationPrivacy.incomingCallText("Asha [abcd]", true))
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
