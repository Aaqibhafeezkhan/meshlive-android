package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Messages screen support (StoreForwardUi.kt): unread, the quiet notification rule, voice-note gate, statuses. */
class StoreForwardUiTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sf-ui").toFile()
    }

    @After
    fun tearDown() {
        VoiceNoteGate.callPhase = null
        dir.deleteRecursively()
    }

    private fun summary(id: String, direction: MessageDirection, receivedMs: Long) = ArchivedSummary(
        id, direction, "peer", receivedMs, receivedMs - 1_000, MessageKind.TEXT, "text/plain", 12, 99_999, "hi",
    )

    @Test
    fun onlyReceivedMessagesNewerThanTheLastVisitAreUnread() {
        val messages = listOf(
            summary("a", MessageDirection.IN, 1_000),
            summary("b", MessageDirection.IN, 2_000),
            summary("c", MessageDirection.IN, 3_000),
            summary("d", MessageDirection.OUT, 4_000),
        )
        assertEquals(3, UnreadMessages.count(messages, seenMs = 0))
        assertEquals(1, UnreadMessages.count(messages, seenMs = 2_000))
        assertEquals("a message received at exactly the visit time was seen", 0, UnreadMessages.count(messages, seenMs = 3_000))
        assertFalse("own messages are never unread", UnreadMessages.isUnread(messages[3], seenMs = 0))
    }

    @Test
    fun theSeenTimeOnlyMovesForwardAndSurvivesAReopen() {
        val store = InboxSeenStore.forRoot(dir)
        assertEquals(0L, store.read())
        store.write(5_000)
        store.write(4_000) // an older visit never moves it back
        assertEquals(5_000L, InboxSeenStore.forRoot(dir).read())
        store.write(6_000)
        assertEquals(6_000L, InboxSeenStore.forRoot(dir).read())
        assertTrue("it lives in the store-and-forward directory the emergency wipe deletes", File(dir, InboxSeenStore.FILE_NAME).exists())
    }

    @Test
    fun aDamagedSeenFileCountsAsNeverVisited() {
        File(dir, InboxSeenStore.FILE_NAME).writeText("not a number")
        assertEquals(0L, InboxSeenStore.forRoot(dir).read())
    }

    @Test
    fun theNotificationIsPostedOnlyForUnreadMessagesWhileNothingIsShowingThem() {
        assertTrue(MessageNotifications.shouldPost(unread = 2, messagesScreenVisible = false, appInForeground = false))
        assertFalse(MessageNotifications.shouldPost(unread = 0, messagesScreenVisible = false, appInForeground = false))
        assertFalse(MessageNotifications.shouldPost(unread = 2, messagesScreenVisible = true, appInForeground = false))
        assertFalse("the Home tile already shows the count", MessageNotifications.shouldPost(2, false, appInForeground = true))
    }

    @Test
    fun messageNotificationsNeverShowContentAndStaySecretUnlessNamesAreAllowed() {
        val secret = androidx.core.app.NotificationCompat.VISIBILITY_SECRET
        val private = androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE
        assertEquals(secret, NotificationPrivacy.visibility(NotificationKind.MESSAGE, peerNamesOptIn = false))
        assertEquals(private, NotificationPrivacy.visibility(NotificationKind.MESSAGE, peerNamesOptIn = true))
        assertEquals("New message", NotificationPrivacy.publicText(NotificationKind.MESSAGE))
    }

    @Test
    fun aVoiceNoteIsRefusedWhileACallOrASessionHoldsTheMicrophone() {
        assertFalse(VoiceNoteGate.blocked(callPhase = null, sessionHoldsMicrophone = false))
        assertFalse(VoiceNoteGate.blocked(callPhase = PrivateCallPhase.ENDED, sessionHoldsMicrophone = false))
        for (phase in listOf(
            PrivateCallPhase.OUTGOING_INVITE, PrivateCallPhase.OUTGOING_RINGING, PrivateCallPhase.INCOMING_RINGING,
            PrivateCallPhase.NEGOTIATING_MEDIA, PrivateCallPhase.ACTIVE,
        )) {
            assertTrue(phase.name, VoiceNoteGate.blocked(phase, sessionHoldsMicrophone = false))
        }
        assertTrue("a live stream's microphone also counts", VoiceNoteGate.blocked(null, sessionHoldsMicrophone = true))
    }

    @Test
    fun theCallScreenFlagFeedsTheGate() {
        VoiceNoteGate.callPhase = PrivateCallPhase.ACTIVE
        assertTrue(VoiceNoteGate.blockedNow())
        VoiceNoteGate.callPhase = null
        assertFalse(VoiceNoteGate.blockedNow())
    }

    @Test
    fun ownMessageStatusesAreReadableForEveryState() {
        val max = 8
        assertEquals(R.string.messages_status_waiting, OutgoingMessageStatus.of(BundleState.QUEUED, null, false, max).stringRes)
        assertEquals(
            OutgoingStatus(R.string.messages_status_attempt, listOf(3, max)),
            OutgoingMessageStatus.of(BundleState.QUEUED, OutboxStatus.QUEUED to 3, false, max),
        )
        assertEquals(
            OutgoingStatus(R.string.messages_status_failed_attempts, listOf(8)),
            OutgoingMessageStatus.of(BundleState.QUEUED, OutboxStatus.FAILED to 8, false, max),
        )
        assertEquals(R.string.messages_status_courier, OutgoingMessageStatus.of(BundleState.QUEUED, OutboxStatus.CARRIED to 1, false, max).stringRes)
        assertEquals(R.string.messages_status_handed, OutgoingMessageStatus.of(BundleState.HANDED, null, false, max).stringRes)
        assertEquals(R.string.messages_status_delivered, OutgoingMessageStatus.of(BundleState.DELIVERED, null, false, max).stringRes)
        assertEquals(R.string.messages_status_damaged, OutgoingMessageStatus.of(BundleState.FAILED, null, false, max).stringRes)
        assertEquals(R.string.messages_status_damaged, OutgoingMessageStatus.of(null, null, true, max).stringRes)
        assertEquals(R.string.messages_status_expired, OutgoingMessageStatus.of(null, null, false, max).stringRes)
    }
}
