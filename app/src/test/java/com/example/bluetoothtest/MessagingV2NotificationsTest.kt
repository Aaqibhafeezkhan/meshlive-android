package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messaging v2 notifications: what each version says, when one is shown, and where a tap goes (DECISIONS Design M). */
class MessagingV2NotificationsTest {
    private val text = XmlThreadText
    private val rules = ConversationNotificationRules
    private val app = "MeshLive"
    private val peerHex = "ab".repeat(16)
    private val selfHex = "cd".repeat(16)
    private val self = NodeId.fromBytes(hexToBytes(selfHex))
    private val peer = NodeId.fromBytes(hexToBytes(peerHex))

    private fun item(body: String = "see you at the bridge", timed: Boolean = false, kind: UnreadKind = UnreadKind.TEXT) =
        UnreadSummary(kind, if (kind == UnreadKind.TEXT) body else "", timed)

    private fun content(
        unread: List<UnreadSummary>,
        label: String? = "Asha",
        legacy: Int = 0,
        names: Boolean = false,
    ) = rules.content(label, unread, legacy, names, app, text)

    // ---- content: the lock screen --------------------------------------------------------------------------------------------

    @Test
    fun aLockedPhoneShowsOnlyTheAppNameAndNewMessageByDefault() {
        val c = content(listOf(item()))!!
        assertEquals(app, c.publicTitle)
        assertEquals("New message", c.publicText)
        // The unlocked phone has the name and the text.
        assertEquals("Asha", c.title)
        assertEquals("see you at the bridge", c.text)
    }

    @Test
    fun theLockScreenNamesTheContactOnlyWhenTheUserAllowedNamesThere() {
        val c = content(listOf(item()), names = true)!!
        assertEquals("Asha", c.publicTitle)
        // Even then the text never reaches the lock screen.
        assertEquals("New message", c.publicText)
        assertFalse(c.publicText.contains("bridge"))
        assertFalse(c.publicTitle.contains("bridge"))
    }

    @Test
    fun severalUnreadMessagesAreCountedNeverQuoted() {
        val c = content(listOf(item("first secret"), item("second secret")))!!
        assertEquals("2 new messages", c.text)
        assertEquals("2 new messages", c.publicText)
        assertEquals(2, c.count)
        assertFalse(c.text.contains("secret"))
        assertFalse(c.markReadAllowed)
    }

    @Test
    fun aDisappearingMessageNeverPutsItsTextInANotification() {
        val c = content(listOf(item("burn after reading", timed = true)))!!
        assertEquals("New message", c.text)
        assertEquals("Asha", c.title)
        assertFalse(c.text.contains("burn"))
        assertFalse(c.showsText)
        assertFalse(c.markReadAllowed)
    }

    @Test
    fun aFileShowsItsKindAndNeverItsName() {
        val photo = content(listOf(item(kind = UnreadKind.PICTURE)))!!
        assertEquals("Photo", photo.text)
        assertEquals("Voice note", content(listOf(item(kind = UnreadKind.VOICE)))!!.text)
        assertEquals("File", content(listOf(item(kind = UnreadKind.FILE)))!!.text)
        assertEquals("Video", content(listOf(item(kind = UnreadKind.VIDEO)))!!.text)
        assertEquals("New message", content(listOf(item(kind = UnreadKind.OTHER)))!!.text)
        // A file was not read in the notification: no Mark as read.
        assertFalse(photo.markReadAllowed)
    }

    @Test
    fun aMessageFromAnOlderAppShowsNoTextAtAll() {
        val c = content(emptyList(), legacy = 1)!!
        assertEquals("New message", c.text)
        assertEquals("Asha", c.title)
        assertFalse(c.markReadAllowed)
        assertEquals("2 new messages", content(listOf(item()), legacy = 1)!!.text)
    }

    @Test
    fun nothingUnreadMeansNoNotification() {
        assertNull(content(emptyList()))
    }

    // ---- content: no raw identifiers -----------------------------------------------------------------------------------------

    @Test
    fun aLabelThatIsARawIdentifierIsReplacedByTheAppName() {
        listOf(peerHex, peerHex.take(12), "node-" + peerHex.take(16), "", "   ", null).forEach { label ->
            val c = content(listOf(item()), label = label, names = true)!!
            assertEquals("label '$label'", app, c.title)
            assertEquals(app, c.publicTitle)
            // Without a name there is no sender to attach the text to.
            assertEquals("New message", c.text)
            assertFalse(c.markReadAllowed)
        }
    }

    @Test
    fun theNamingPrecedenceNeverYieldsAnIdentifierOnAnySurface() {
        val unnamed = ThreadNaming.title(peerHex, null)
        val named = ThreadNaming.title(peerHex, "Asha")
        assertEquals("Asha", named)
        assertNotNull(rules.safeLabel(unnamed))
        listOf(unnamed, named).forEach { label ->
            val c = content(listOf(item()), label = label, names = true)!!
            listOf(c.title, c.text, c.publicTitle, c.publicText).forEach {
                assertFalse(it, it.contains(peerHex))
                assertFalse(it, it.contains(peerHex.take(12)))
                assertFalse(it, Regex("[0-9a-f]{8,}").containsMatchIn(it))
            }
        }
    }

    @Test
    fun aLongMessageIsShortenedAndWhitespaceIsFlattened() {
        val c = content(listOf(item("a".repeat(500) + "\n\nend")))!!
        assertEquals(ConversationNotificationRules.PREVIEW_CHARS, c.text.length)
        assertFalse(content(listOf(item("one\n\n   two")))!!.text.contains("\n"))
        assertEquals("one two", content(listOf(item("one\n\n   two")))!!.text)
    }

    @Test
    fun unreadSummariesComeFromTheStoredMessage() {
        fun message(kind: V2Kind, attachment: AttachmentInfo?, body: String = "", timer: Int = 0) = ThreadMessage(
            MessageDirection.IN, peerHex, "m1".padEnd(32, '0'), kind, 1, 1, 0, body.toByteArray(), attachment, timer, null, mutableListOf("b"),
        )
        fun file(presentation: Presentation) = AttachmentInfo(
            "f1".padEnd(32, '0'), presentation, 10, ByteArray(32), "secret-plans.pdf", "application/pdf", 0, 0, 0, ByteArray(0), 1,
            FileState.STORED, "v1",
        )
        assertEquals(UnreadSummary(UnreadKind.TEXT, "hi", false), rules.unreadOf(message(V2Kind.TEXT, null, "hi")))
        assertEquals(UnreadKind.PICTURE, rules.unreadOf(message(V2Kind.ATTACHMENT, file(Presentation.PICTURE))).kind)
        assertEquals(UnreadKind.VOICE, rules.unreadOf(message(V2Kind.ATTACHMENT, file(Presentation.VOICE))).kind)
        assertEquals(UnreadKind.VIDEO, rules.unreadOf(message(V2Kind.ATTACHMENT, file(Presentation.VIDEO))).kind)
        assertEquals(UnreadKind.FILE, rules.unreadOf(message(V2Kind.ATTACHMENT, file(Presentation.FILE))).kind)
        assertEquals("", rules.unreadOf(message(V2Kind.ATTACHMENT, file(Presentation.FILE))).text)
        assertEquals(UnreadKind.OTHER, rules.unreadOf(message(V2Kind.LOCATION, null)).kind)
        assertTrue(rules.unreadOf(message(V2Kind.TEXT, null, "x", timer = 60)).timed)
    }

    // ---- Mark as read --------------------------------------------------------------------------------------------------------

    @Test
    fun markAsReadIsOfferedOnlyWhenTheNotificationShowedTheWholeMessage() {
        val c = content(listOf(item()))!!
        assertTrue(c.showsText)
        assertTrue(c.markReadAllowed)
        // A hidden preview (timer, no name, several, file, older app) never offers it, so a read receipt cannot claim unseen text.
        assertFalse(content(listOf(item(timed = true)))!!.markReadAllowed)
        assertFalse(content(listOf(item()), label = null)!!.markReadAllowed)
        assertFalse(content(listOf(item(), item()))!!.markReadAllowed)
        assertFalse(content(listOf(item(kind = UnreadKind.FILE)))!!.markReadAllowed)
        assertFalse(content(listOf(item()), legacy = 1)!!.markReadAllowed)
    }

    // ---- when a notification is shown ----------------------------------------------------------------------------------------

    @Test
    fun noNotificationWhileThatThreadIsOpenAndTheAppIsInFront() {
        assertTrue(rules.suppressed(appForeground = true, threadOpen = true))
        // On another screen of the app, or with the thread open but the app in the background, the message is not on screen.
        assertFalse(rules.suppressed(appForeground = true, threadOpen = false))
        assertFalse(rules.suppressed(appForeground = false, threadOpen = true))
        assertFalse(rules.suppressed(appForeground = false, threadOpen = false))
    }

    @Test
    fun theNotificationPlanFollowsTheRules() {
        val c = content(listOf(item()))
        // A new message posts with an alert.
        assertEquals(NotifyPlan.Post(c!!, true), rules.plan(c, suppressed = false, enabled = true, alert = true, active = false))
        // A suppressed thread (open and in front) cancels what is there instead of posting.
        assertEquals(NotifyPlan.Cancel, rules.plan(c, suppressed = true, enabled = true, alert = true, active = true))
        // Everything read or deleted: cancel.
        assertEquals(NotifyPlan.Cancel, rules.plan(null, suppressed = false, enabled = true, alert = false, active = true))
        // The user swiped it away: a read or delete elsewhere does not bring it back, a new message does.
        assertEquals(NotifyPlan.Skip, rules.plan(c, suppressed = false, enabled = true, alert = false, active = false))
        assertEquals(NotifyPlan.Post(c, false), rules.plan(c, suppressed = false, enabled = true, alert = false, active = true))
        // No permission (or notifications switched off): nothing is posted.
        assertEquals(NotifyPlan.Skip, rules.plan(c, suppressed = false, enabled = false, alert = true, active = false))
    }

    // ---- intent routing ------------------------------------------------------------------------------------------------------

    @Test
    fun theTapCarriesTheConversationIdNotTheNodeId() {
        val id = ConversationRoute.conversationHex(self, peer)
        assertEquals(32, id.length)
        assertFalse(id.contains(peerHex.take(12)))
        assertFalse(id.contains(selfHex.take(12)))
        // The same conversation from both ends.
        assertEquals(id, ConversationRoute.conversationHex(peer, self))
        assertEquals("mv2:$id", ConversationRoute.tag(id))
    }

    @Test
    fun aValidConversationIdOpensTheAcceptedContact() {
        val other = NodeId.fromBytes(hexToBytes("ef".repeat(16)))
        val id = ConversationRoute.conversationHex(self, peer)
        assertEquals(peer, ConversationRoute.resolve(id, self, listOf(other, peer)))
        assertNotEquals(id, ConversationRoute.conversationHex(self, other))
        assertEquals(other, ConversationRoute.resolve(ConversationRoute.conversationHex(self, other), self, listOf(other, peer)))
    }

    @Test
    fun anUnknownMalformedOrForgedIdOpensNothing() {
        val id = ConversationRoute.conversationHex(self, peer)
        val other = NodeId.fromBytes(hexToBytes("ef".repeat(16)))
        // The contact was deleted since the notification was posted.
        assertNull(ConversationRoute.resolve(id, self, listOf(other)))
        assertNull(ConversationRoute.resolve(id, self, emptyList()))
        assertNull(ConversationRoute.resolve(null, self, listOf(peer)))
        assertNull(ConversationRoute.resolve("", self, listOf(peer)))
        assertNull(ConversationRoute.resolve(id.take(31), self, listOf(peer)))
        assertNull(ConversationRoute.resolve(id + "0", self, listOf(peer)))
        assertNull(ConversationRoute.resolve(id.uppercase(), self, listOf(peer)))
        assertNull(ConversationRoute.resolve("zz".repeat(16), self, listOf(peer)))
        // A node ID (a raw handle) is not accepted in place of the conversation ID.
        assertNull(ConversationRoute.resolve(peerHex, self, listOf(peer)))
    }

    @Test
    fun everyConversationHasItsOwnPendingIntentRequestCode() {
        val ids = (1..40).map { i -> ConversationRoute.conversationHex(self, NodeId.fromBytes(hexToBytes("%02x".format(i).repeat(16)))) }
        assertEquals(ids.size, ids.map(ConversationRoute::requestCode).toSet().size)
    }

    // ---- the files the Android layer depends on ------------------------------------------------------------------------------

    private val main: File = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    @Test
    fun theChannelIsDefaultImportanceUserControlledAndTheReceiverIsNotExported() {
        val source = File(main, "java/com/example/bluetoothtest/MessagingV2Notifications.kt").readText()
        assertTrue(source.contains("NotificationManager.IMPORTANCE_DEFAULT"))
        assertTrue(source.contains("VISIBILITY_PRIVATE"))
        assertFalse("never a secret or public visibility for message text", source.contains("VISIBILITY_PUBLIC"))
        assertTrue(source.contains("POST_NOTIFICATIONS"))
        val manifest = File(main, "AndroidManifest.xml").readText().replace("\r\n", "\n")
        assertTrue(
            manifest.contains("android:name=\".MessageNotificationReceiver\"\n            android:exported=\"false\""),
        )
    }

    @Test
    fun theV1CountNotificationIsReplacedOnlyWhileTheSwitchIsOn() {
        val activity = File(main, "java/com/example/bluetoothtest/MainActivity.kt").readText().replace("\r\n", "\n")
        assertTrue(activity.contains("if (notify && !MessagingV2Feature.enabled && MessageNotifications.shouldPost("))
    }
}
