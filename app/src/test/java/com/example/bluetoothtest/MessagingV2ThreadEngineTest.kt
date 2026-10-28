package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Phase 1c engine additions: Retry for a message that was not sent, the per-conversation choices, the Settings overrides. */
class MessagingV2ThreadEngineTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private val rig = SfRig()

    @After
    fun cleanUp() = rig.cleanUp()

    private fun failed(phone: SfRig.Phone, message: ThreadMessage) {
        message.notSent = true
        phone.engine.threads.save(message)
    }

    // ---- retry ----------------------------------------------------------------------------------------------------------------

    @Test
    fun retryOfATextSealsItAgainAndRemovesTheFailedOne() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val first = alice.engine.sendText(bob.nodeId, "bring water")
        failed(alice, first)
        val again = alice.engine.retry(first.id)
        assertEquals("bring water", again.text)
        assertNotEquals(first.id, again.id)
        assertNull(alice.engine.threads.get(first.id))
        assertFalse(again.notSent)
        assertEquals(listOf(again.id), alice.engine.threads.forPeer(bob.nodeId.toString()).map { it.id })
        // The new copy is the one that reaches the contact.
        val received = rig.sync(alice, bob).responder
        assertEquals(listOf("bring water"), received.v2Messages.map { it.text })
    }

    @Test
    fun retryOfAnInlineFileUsesTheCopyThisPhoneKept() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val bytes = rig.bytes(30_000, 5)
        val first = alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.PICTURE, "p.jpg", "image/jpeg")
        failed(alice, first)
        val again = alice.engine.retry(first.id)
        val attachment = requireNotNull(again.attachment)
        assertEquals("p.jpg", attachment.name)
        assertArrayEquals(first.attachment!!.sha256, attachment.sha256)
        assertNull(alice.engine.threads.get(first.id))
        val vaultId = requireNotNull(attachment.vaultId)
        assertArrayEquals(bytes, alice.engine.vault.read(vaultId, 0, bytes.size))
        assertEquals("the old vault file is gone", 1, alice.engine.vault.ids().size)
    }

    @Test
    fun onlyAFailedOwnMessageCanBeRetried() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val waiting = alice.engine.sendText(bob.nodeId, "still waiting")
        try {
            alice.engine.retry(waiting.id)
            fail("a waiting message is not retried")
        } catch (expected: IllegalArgumentException) {
        }
        failed(alice, waiting)
        waiting.deliveredAtMs = 5
        alice.engine.threads.save(waiting)
        // A late receipt makes it delivered: it is no longer "not sent" in the thread, but the flag is what retry looks at.
        assertEquals(OwnMessageState.DELIVERED, ConversationRules.ownState(waiting, false))
    }

    @Test
    fun retryOfAFileWhoseCopyIsGoneSaysSo() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val bytes = rig.bytes(5_000, 6)
        val first = alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.FILE, "a.bin")
        alice.engine.vault.delete(first.attachment!!.vaultId!!)
        failed(alice, first)
        try {
            alice.engine.retry(first.id)
            fail("the file is no longer on this phone")
        } catch (expected: IllegalStateException) {
        }
        assertEquals("the failed message stays so the user can see it", 1, alice.engine.threads.count())
    }

    @Test
    fun theRowsSummaryKnowsWhatTheNewestMessageCarries() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        alice.engine.sendText(bob.nodeId, "first")
        rig.clockMs.addAndGet(1_000)
        assertNull(ConversationIndex(alice.node).summaries().single().lastPresentation)
        val bytes = rig.bytes(8_000, 8)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.VOICE, "voice-note.m4a", "audio/mp4")
        val row = ConversationIndex(alice.node).summaries().single()
        assertEquals(Presentation.VOICE, row.lastPresentation)
        assertEquals(OwnMessageState.WAITING, row.lastOwnState)
        // The rows model turns it into the words of the Contacts row.
        val time = ThreadTime({ rig.clock() }, java.time.ZoneId.of("UTC"), { "12:00" }, { _, _ -> "1 Jan" })
        val model = ConversationRows.row(ContactRef(bob.nodeId.toString(), "Bob"), row, false, XmlThreadText, time)
        // Alice's own newest message: the chat list reads "You: Voice note" (2026-10-21).
        assertEquals(XmlThreadText.get("mv2_ui_preview_you", "Voice note"), model.subtitle)
        assertEquals(DeliveryGlyph.CLOCK, model.glyph?.glyph)
    }

    // ---- per-conversation choices -----------------------------------------------------------------------------------------------

    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 9 }, "AES") }

    @Test
    fun threadSettingsPersistSealedAndForgetWhenAsked() {
        // Realistic 12-hex node IDs: a two-letter ID ("aa") also occurs by chance in the sealed random bytes (flaky).
        val dir = Files.createTempDirectory("mv2-settings").toFile()
        try {
            val file = File(dir, "thread-settings.properties")
            val settings = ThreadSettings(file, cipher)
            assertEquals(0, settings.disappearSeconds("c0a1c0a1c0a1"))
            assertNull(settings.readReceipts("c0a1c0a1c0a1"))
            settings.setDisappearSeconds("c0a1c0a1c0a1", 86_400)
            settings.setReadReceipts("c0a1c0a1c0a1", true)
            settings.setReadReceipts("c0a2c0a2c0a2", false)
            val reopened = ThreadSettings(file, cipher)
            assertEquals(86_400, reopened.disappearSeconds("c0a1c0a1c0a1"))
            assertEquals(true, reopened.readReceipts("c0a1c0a1c0a1"))
            assertEquals(false, reopened.readReceipts("c0a2c0a2c0a2"))
            assertEquals(0, reopened.disappearSeconds("c0a2c0a2c0a2"))
            assertFalse("the node IDs are sealed at rest", String(file.readBytes(), Charsets.ISO_8859_1).contains("c0a1c0a1c0a1"))
            reopened.setDisappearSeconds("c0a1c0a1c0a1", 0)
            reopened.setReadReceipts("c0a1c0a1c0a1", null)
            assertEquals(0, ThreadSettings(file, cipher).disappearSeconds("c0a1c0a1c0a1"))
            assertNull(ThreadSettings(file, cipher).readReceipts("c0a1c0a1c0a1"))
            reopened.forget("c0a2c0a2c0a2")
            assertNull(ThreadSettings(file, cipher).readReceipts("c0a2c0a2c0a2"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aContactsOwnReceiptChoiceBeatsTheGlobalSwitchBothWays() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        assertFalse("off by default", alice.engine.readReceiptsFor(bob.nodeId))
        assertEquals(0, alice.engine.sendText(bob.nodeId, "plain").flags and MessagingV2Format.WANT_READ_RECEIPT)
        alice.engine.threadSettings.setReadReceipts(bob.nodeId.toString(), true)
        assertTrue(alice.engine.readReceiptsFor(bob.nodeId))
        assertNotEquals(0, alice.engine.sendText(bob.nodeId, "asks").flags and MessagingV2Format.WANT_READ_RECEIPT)
        alice.engine.readReceipts = true
        alice.engine.threadSettings.setReadReceipts(bob.nodeId.toString(), false)
        assertEquals(0, alice.engine.sendText(bob.nodeId, "private").flags and MessagingV2Format.WANT_READ_RECEIPT)
        alice.engine.threadSettings.setReadReceipts(bob.nodeId.toString(), null)
        assertNotEquals(0, alice.engine.sendText(bob.nodeId, "global").flags and MessagingV2Format.WANT_READ_RECEIPT)
    }

    @Test
    fun theThreadsDisappearTimerIsCarriedByTheMessageItSends() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        alice.engine.threadSettings.setDisappearSeconds(bob.nodeId.toString(), 3_600)
        val sent = alice.engine.sendText(bob.nodeId, "gone in an hour", null, alice.engine.threadSettings.disappearSeconds(bob.nodeId.toString()))
        assertEquals(3_600, sent.disappearSeconds)
        val received = rig.sync(alice, bob).responder.v2Messages.single()
        assertEquals(3_600, received.disappearSeconds)
    }

    @Test
    fun theAutomaticDownloadSizeFollowsTheSettingsRow() {
        val alice = rig.Phone()
        assertEquals(alice.engine.limits.autoDownloadMaxBytes, alice.engine.autoDownloadMaxBytes)
        alice.engine.autoDownloadMaxBytes = 0
        assertEquals(0L, alice.engine.autoDownloadMaxBytes)
    }

    @Test
    fun aVaultFileReadsBackAsAStreamAcrossBlocks() {
        val alice = rig.Phone()
        val bob = rig.Phone()
        rig.contacts(alice, bob)
        val bytes = rig.bytes(FileVault.BLOCK * 2 + 123, 7)
        val sent = alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.FILE, "x.bin")
        val streamed = VaultInputStream(alice.engine.vault, sent.attachment!!.vaultId!!).use { it.readBytes() }
        assertArrayEquals(bytes, streamed)
    }

    // ---- the Settings choices ---------------------------------------------------------------------------------------------------

    @Test
    fun aBenchmarkLaunchIgnoresTheStoredChoicesAndAnOrdinaryLaunchUsesThem() {
        val stored = MessagingV2UserChoices(readReceipts = true, autoDownloadMb = 25)
        MessagingV2Options.configure(false, { null }, { null }, stored)
        assertEquals(25, MessagingV2Options.current.autoDownloadMb)
        assertTrue(MessagingV2Options.readReceipts)
        MessagingV2Options.configure(true, { null }, { null }, stored)
        assertEquals("a benchmark launch keeps the design default", 8, MessagingV2Options.current.autoDownloadMb)
        assertFalse(MessagingV2Options.readReceipts)
        // A change in Settings does not leak into a benchmark launch.
        MessagingV2Options.applyChoices(MessagingV2UserChoices(true, 64))
        assertEquals(8, MessagingV2Options.current.autoDownloadMb)
        MessagingV2Options.configure(true, { null }, { if (it == MessagingV2Options.EXTRA_READ_RECEIPTS) true else null }, stored)
        assertTrue(MessagingV2Options.readReceipts)
        // Leave the process the way other tests expect it.
        MessagingV2Options.configure(false, { null }, { null })
        assertEquals(8, MessagingV2Options.current.autoDownloadMb)
        assertFalse(MessagingV2Options.readReceipts)
    }

    @Test
    fun aSettingsChangeAppliesToAnOrdinaryLaunchAtOnce() {
        MessagingV2Options.configure(false, { null }, { null })
        MessagingV2Options.applyChoices(MessagingV2UserChoices(true, 1))
        assertEquals(1, MessagingV2Options.current.autoDownloadMb)
        assertTrue(MessagingV2Options.readReceipts)
        MessagingV2Options.configure(false, { null }, { null })
    }

    // ---- the voice note gesture ---------------------------------------------------------------------------------------------------

    @Test
    fun aVoiceNotePressSendsCancelsOrIsTooShort() {
        assertEquals(VoiceNoteGesture.Decision.SEND, VoiceNoteGesture.pressDecision(3_000, 0f))
        assertEquals(VoiceNoteGesture.Decision.TOO_SHORT, VoiceNoteGesture.pressDecision(300, 0f))
        assertEquals(VoiceNoteGesture.Decision.CANCELLED, VoiceNoteGesture.pressDecision(3_000, 120f))
        assertEquals("a long slide wins over a short press", VoiceNoteGesture.Decision.CANCELLED, VoiceNoteGesture.pressDecision(100, 200f))
        assertEquals("0:07", VoiceNoteGesture.clock(7_400))
        assertEquals("2:00", VoiceNoteGesture.clock(VoiceNoteGesture.MAX_MS))
    }
}
