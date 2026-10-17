package com.example.bluetoothtest

import java.io.File
import java.time.ZoneId
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads the real strings file, so the words in these tests are the words the app shows. */
internal object XmlThreadText : ThreadText {
    private val strings: Map<String, String> by lazy {
        val file = listOf("src/main/res/values", "app/src/main/res/values").map { File(it, "strings_messaging_v2_ui.xml") }.first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    val keys: Set<String> get() = strings.keys

    override fun get(key: String, vararg args: Any): String =
        String.format(Locale.US, requireNotNull(strings[key]) { "Missing string $key" }, *args)
}

/** Phase 1c: row text, ticks, banners, the composer, file cards and the message list (DECISIONS Design M, UX; Q-M1, Q-M3, Q-M12). */
class MessagingV2ThreadModelTest {
    private val text = XmlThreadText
    private val zone = ZoneId.of("UTC")
    private val day = 86_400_000L
    private val now = (1_790_000_000_000L / 86_400_000L) * 86_400_000L + 12 * 3_600_000L // 12:00 UTC
    private val time = ThreadTime(
        { now }, zone, { ms -> "%02d:%02d".format((ms / 3_600_000) % 24, (ms / 60_000) % 60) },
        { ms, withYear -> "d" + (ms / day) + if (withYear) "y" else "" },
    )
    private val peerHex = "ab".repeat(16)
    private val peer = NodeId.fromBytes(hexToBytes(peerHex))

    private fun summary(
        preview: String = "",
        lastMs: Long = 0,
        unread: Int = 0,
        own: OwnMessageState? = null,
        link: ConversationLinkState = ConversationLinkState.READY,
        presentation: Presentation? = null,
    ) = ConversationSummary(peer, "Asha", true, preview, if (presentation != null) "file" else "text", lastMs, own == null, unread, 0, own, link, presentation)

    private fun message(
        direction: MessageDirection = MessageDirection.OUT,
        id: String = "m1",
        body: String = "hello",
        attachment: AttachmentInfo? = null,
        at: Long = now - 60_000,
        delivered: Long = 0,
        read: Long = 0,
        downloaded: Long = 0,
        notSent: Boolean = false,
    ) = ThreadMessage(
        direction, peerHex, id.padEnd(32, '0'), if (attachment == null) V2Kind.TEXT else V2Kind.ATTACHMENT, at, at, 0,
        if (attachment == null) body.toByteArray() else ByteArray(0), attachment, 0, null, mutableListOf("bundle-$id"),
        deliveredAtMs = delivered, downloadedAtMs = downloaded, readAtMs = read, notSent = notSent,
    )

    private fun file(
        name: String = "plan.pdf",
        size: Long = 24_000_000,
        state: FileState = FileState.STORED,
        presentation: Presentation = Presentation.FILE,
    ) = AttachmentInfo("f1".padEnd(32, '0'), presentation, size, ByteArray(32), name, "application/pdf", 0, 0, 0, ByteArray(0), 24, state, if (state == FileState.STORED || state == FileState.COMPLETE) "vault1" else null)

    // ---- Contacts rows ---------------------------------------------------------------------------------------------------

    @Test
    fun aRowShowsThePreviewTheTimeTheUnreadCountAndNoRawId() {
        val row = ConversationRows.row(ContactRef(peerHex, "Asha"), summary("see you at the bridge", now - 3_600_000, unread = 2), true, text, time)
        assertEquals("Asha", row.title)
        assertEquals("see you at the bridge", row.subtitle)
        assertEquals("11:00", row.timeText)
        assertEquals(2, row.unread)
        assertTrue(row.nearby)
        assertTrue(row.description.contains("2 unread"))
        assertFalse(row.description.contains(peerHex.take(12)))
    }

    @Test
    fun aContactWithoutMessagesShowsNearbyNowOrNothing() {
        assertEquals("Nearby now", ConversationRows.row(ContactRef(peerHex, "Asha"), summary(), true, text, time).subtitle)
        assertEquals("", ConversationRows.row(ContactRef(peerHex, "Asha"), summary(), false, text, time).subtitle)
        assertEquals("a contact with no accepted mailbox card yet still has a row", "Nearby now",
            ConversationRows.row(ContactRef(peerHex, "Asha"), null, true, text, time).subtitle)
    }

    @Test
    fun anAttachmentPreviewNamesTheKindNotTheFile() {
        fun sub(p: Presentation) = ConversationRows.row(ContactRef(peerHex, "Asha"), summary("", now - 1000, presentation = p), false, text, time).subtitle
        assertEquals("Photo", sub(Presentation.PICTURE))
        assertEquals("Video", sub(Presentation.VIDEO))
        assertEquals("Voice note", sub(Presentation.VOICE))
        assertEquals("File", sub(Presentation.FILE))
        // Previews turned off (or an empty text) never leak the words.
        assertEquals("Message", ConversationRows.row(ContactRef(peerHex, "Asha"), summary("", now - 1000), false, text, time).subtitle)
    }

    @Test
    fun aPreviewIsOneLine() {
        val row = ConversationRows.row(ContactRef(peerHex, "Asha"), summary("line one\n\n  line   two", now - 1000), false, text, time)
        assertEquals("line one line two", row.subtitle)
    }

    @Test
    fun theRowsOwnGlyphFollowsTheNewestOwnMessageAndNeverShowsOnAnIncomingOne() {
        val own = ConversationRows.row(ContactRef(peerHex, "Asha"), summary("hi", now - 1000, own = OwnMessageState.DELIVERED), false, text, time)
        assertEquals(DeliveryGlyph.CHECK, own.glyph?.glyph)
        assertEquals("Delivered", own.glyph?.word)
        val incoming = ConversationRows.row(ContactRef(peerHex, "Asha"), summary("hi", now - 1000), false, text, time)
        assertNull(incoming.glyph)
    }

    @Test
    fun aKeyChangeIsTheRowsSubtitleSoItIsNotMissed() {
        val row = ConversationRows.row(ContactRef(peerHex, "Asha"), summary("hi", now - 1000, link = ConversationLinkState.KEYS_CHANGED), false, text, time)
        assertEquals("Keys changed. Compare again.", row.subtitle)
    }

    @Test
    fun rowTimesAreTheClockTodayYesterdayThenTheDate() {
        assertEquals("11:00", time.rowTime(now - 3_600_000, text))
        assertEquals("Yesterday", time.rowTime(now - day, text))
        assertTrue(time.rowTime(now - 5 * day, text).startsWith("d"))
        assertEquals("", time.rowTime(0, text))
    }

    @Test
    fun rowsWithActivityComeFirstNewestFirstThenTheGivenOrder() {
        fun hex(n: Int) = n.toString(16).padStart(2, '0').repeat(16)
        val contacts = listOf(ContactRef(hex(1), "Anil"), ContactRef(hex(2), "Bela"), ContactRef(hex(3), "Chen"), ContactRef(hex(4), "Dev"))
        val summaries = mapOf(
            hex(3) to summary("old", now - 5 * day).copy(peer = NodeId.fromBytes(hexToBytes(hex(3)))),
            hex(4) to summary("new", now - 1000).copy(peer = NodeId.fromBytes(hexToBytes(hex(4)))),
        )
        val rows = ConversationRows.build(contacts, summaries, emptySet(), text, time)
        assertEquals(listOf("Dev", "Chen", "Anil", "Bela"), rows.map { it.title })
    }

    @Test
    fun aRowNeverShowsTheNodeIdOrItsPrefix() {
        // The title of a contact without a label is the handle (XXXXX-XXXXX); it is not the hex ID.
        val title = ThreadNaming.title(peerHex, null)
        assertFalse(title.contains(peerHex.take(12)))
        assertTrue(Regex("[0-9A-Z]{5}-[0-9A-Z]{5}").matches(title))
        assertEquals("Asha", ThreadNaming.title(peerHex, "Asha"))
        assertEquals("a blank label falls back to the handle", title, ThreadNaming.title(peerHex, "  "))
    }

    // ---- ticks ----------------------------------------------------------------------------------------------------------

    @Test
    fun everyOwnStateHasOneGlyphAndOneWord() {
        val expected = mapOf(
            OwnMessageState.WAITING to (DeliveryGlyph.CLOCK to "Waiting"),
            OwnMessageState.SENDING to (DeliveryGlyph.UPLOAD to "Sending"),
            OwnMessageState.DELIVERED to (DeliveryGlyph.CHECK to "Delivered"),
            OwnMessageState.DOWNLOADED to (DeliveryGlyph.CHECKS to "Downloaded"),
            OwnMessageState.READ to (DeliveryGlyph.CHECKS_READ to "Read"),
            OwnMessageState.NOT_SENT to (DeliveryGlyph.ERROR to "Not sent"),
        )
        assertEquals(OwnMessageState.entries.toSet(), expected.keys)
        expected.forEach { (state, pair) ->
            val view = TickRules.view(state, text)
            assertEquals(pair.first, view.glyph)
            assertEquals(pair.second, view.word)
        }
    }

    @Test
    fun theStateOfAnOwnMessageFollowsTheEngineRulesIncludingALateReceiptAfterNotSent() {
        val delivered = message(delivered = now - 1000, notSent = true)
        val bubble = ThreadItems.bubble(ConversationEntry.V2(delivered), "Asha", false, text, time) { CardInputs(null) }
        assertEquals(OwnMessageState.DELIVERED, bubble.state?.state)
        assertFalse(bubble.canRetry)
        val failed = ThreadItems.bubble(ConversationEntry.V2(message(notSent = true)), "Asha", false, text, time) { CardInputs(null) }
        assertEquals(OwnMessageState.NOT_SENT, failed.state?.state)
        assertTrue(failed.canRetry)
        assertEquals("Not sent. Tap Retry to send it again.", failed.stateCaption)
        val sending = ThreadItems.bubble(ConversationEntry.V2(message()), "Asha", true, text, time) { CardInputs(null) }
        assertEquals(OwnMessageState.SENDING, sending.state?.state)
    }

    @Test
    fun tappingAGlyphGivesAFullSentenceWithTheName() {
        val read = message(delivered = now - 5 * 60_000, read = now - 3_600_000)
        val bubble = ThreadItems.bubble(ConversationEntry.V2(read), "Asha", false, text, time) { CardInputs(null) }
        assertEquals("Read by Asha, 11:00", bubble.stateCaption)
        val waiting = ThreadItems.bubble(ConversationEntry.V2(message()), "Asha", false, text, time) { CardInputs(null) }
        assertEquals("Waiting for Asha to be nearby", waiting.stateCaption)
    }

    @Test
    fun incomingMessagesHaveNoStateOrRetry() {
        val bubble = ThreadItems.bubble(ConversationEntry.V2(message(MessageDirection.IN)), "Asha", false, text, time) { CardInputs(null) }
        assertNull(bubble.state)
        assertNull(bubble.stateCaption)
        assertTrue(bubble.incoming)
        assertFalse(bubble.canRetry)
    }

    // ---- banners and composer ------------------------------------------------------------------------------------------------

    @Test
    fun theComposerIsEnabledOnlyWhereSendingCanWork() {
        val enabled = ConversationLinkState.entries.associateWith { ThreadBanners.compose(it, "Asha", text).enabled }
        assertEquals(
            mapOf(
                ConversationLinkState.READY to true,
                ConversationLinkState.PEER_APP_OLDER to true,
                ConversationLinkState.NOT_A_CONTACT to false,
                ConversationLinkState.CARD_PENDING to false,
                ConversationLinkState.KEYS_CHANGED to false,
            ),
            enabled,
        )
    }

    @Test
    fun eachStateHasItsBannerWithTheRightActions() {
        fun banner(link: ConversationLinkState) = ThreadBanners.compose(link, "Asha", text).banner
        assertNull(banner(ConversationLinkState.READY))
        assertEquals(listOf(BannerAction.ADD_CONTACT), banner(ConversationLinkState.NOT_A_CONTACT)?.actions)
        assertEquals(listOf(BannerAction.COMPARE), banner(ConversationLinkState.CARD_PENDING)?.actions)
        assertEquals(
            listOf(BannerAction.COMPARE, BannerAction.ACCEPT_KEYS, BannerAction.DISMISS),
            banner(ConversationLinkState.KEYS_CHANGED)?.actions,
        )
        assertEquals(emptyList<BannerAction>(), banner(ConversationLinkState.PEER_APP_OLDER)?.actions)
        assertEquals("Asha's keys changed. Nothing is sent until you compare again.", banner(ConversationLinkState.KEYS_CHANGED)?.text)
        assertEquals("Asha's app is older. They need to update to receive.", banner(ConversationLinkState.PEER_APP_OLDER)?.text)
    }

    @Test
    fun dismissingTheKeyChangeBannerKeepsTheComposerOffWithItsReason() {
        val model = ThreadBanners.compose(ConversationLinkState.KEYS_CHANGED, "Asha", text, bannerDismissed = true)
        assertNull(model.banner)
        assertFalse(model.enabled)
        assertEquals("Compare keys to send messages", model.hint)
    }

    @Test
    fun theTopBarLineIsNearbyOrNotNearbyAndNeverAnId() {
        assertEquals("Nearby", ThreadBanners.stateLine(ConversationLinkState.READY, true, text))
        assertEquals("Not nearby", ThreadBanners.stateLine(ConversationLinkState.READY, false, text))
        assertEquals("Not verified yet", ThreadBanners.stateLine(ConversationLinkState.CARD_PENDING, true, text))
    }

    // ---- file cards ---------------------------------------------------------------------------------------------------------

    private fun card(
        message: ThreadMessage,
        inputs: CardInputs,
        state: OwnMessageState? = if (message.direction == MessageDirection.OUT) ConversationRules.ownState(message, false) else null,
    ) = AttachmentCards.build(message, state, inputs, "Asha", text)!!

    private fun actionsFor(type: SniffedType, presentation: Presentation = Presentation.FILE) =
        FileActionPolicy.forFile(SniffResult(type), presentation, true)

    @Test
    fun aFileWaitingForApprovalOffersDownloadWithItsSize() {
        val model = card(message(MessageDirection.IN, attachment = file(state = FileState.AWAITING_APPROVAL)), CardInputs(null))
        assertEquals(listOf(CardAction.DOWNLOAD), model.actions)
        assertEquals("Download 24 MB", model.downloadLabel)
        assertEquals("plan.pdf", model.name)
        assertEquals("24 MB", model.sizeText)
    }

    @Test
    fun aDownloadShowsProgressAndCancelOrWaitsForTheSender() {
        val incoming = message(MessageDirection.IN, attachment = file(state = FileState.DOWNLOADING))
        val running = card(incoming, CardInputs(null, 0.4f, peerNearby = true))
        assertEquals(listOf(CardAction.CANCEL), running.actions)
        assertEquals("Downloading 40%", running.status)
        assertEquals(0.4f, running.progress)
        val away = card(incoming, CardInputs(null, 0.4f, peerNearby = false))
        assertEquals("Waiting for Asha to be nearby", away.status)
    }

    @Test
    fun aCompletePictureOffersShowPictureAndSaveAndATapShowsIt() {
        val picture = message(MessageDirection.IN, attachment = file("photo.jpg", 300_000, FileState.COMPLETE, Presentation.PICTURE))
        val model = card(picture, CardInputs(actionsFor(SniffedType.JPEG, Presentation.PICTURE)))
        assertEquals(CardIcon.PICTURE, model.icon)
        assertEquals(listOf(CardAction.SHOW_PICTURE, CardAction.SAVE), model.actions)
        assertEquals(CardAction.SHOW_PICTURE, model.tap)
    }

    @Test
    fun aPdfOpensAndAProgramOnlySavesWithAWarning() {
        val pdf = card(message(MessageDirection.IN, attachment = file()), CardInputs(actionsFor(SniffedType.PDF)))
        assertEquals(listOf(CardAction.OPEN, CardAction.SAVE), pdf.actions)
        assertEquals(CardAction.OPEN, pdf.tap)
        assertEquals(WarnLevel.NONE, pdf.saveWarning)
        val apk = card(message(MessageDirection.IN, attachment = file("setup.apk")), CardInputs(actionsFor(SniffedType.APK)))
        assertEquals(listOf(CardAction.SAVE), apk.actions)
        assertNull(apk.tap)
        assertEquals(WarnLevel.DANGEROUS, apk.saveWarning)
        val zip = card(message(MessageDirection.IN, attachment = file("a.zip")), CardInputs(actionsFor(SniffedType.ZIP)))
        assertEquals(WarnLevel.GENERAL, zip.saveWarning)
    }

    @Test
    fun anIncompleteOrUnreadableFileOffersNothingToOpen() {
        val model = card(message(MessageDirection.IN, attachment = file()), CardInputs(null))
        assertEquals(emptyList<CardAction>(), model.actions)
    }

    @Test
    fun damagedAndUnavailableFilesSayWhy() {
        assertEquals("Damaged. Ask Asha to send it again.", card(message(MessageDirection.IN, attachment = file(state = FileState.DAMAGED)), CardInputs(null)).status)
        assertEquals("No longer available", card(message(MessageDirection.IN, attachment = file(state = FileState.UNAVAILABLE)), CardInputs(null)).status)
    }

    @Test
    fun yourOwnFileCanBeCancelledUntilItArrives() {
        val pending = card(message(attachment = file()), CardInputs(actionsFor(SniffedType.PDF)))
        assertTrue(CardAction.CANCEL in pending.actions)
        val arrived = card(message(attachment = file(), delivered = now - 1), CardInputs(actionsFor(SniffedType.PDF)))
        assertFalse(CardAction.CANCEL in arrived.actions)
    }

    @Test
    fun sizesAreShortAndLocaleSafe() {
        assertEquals("812 B", FileSizeText.of(812, Locale.US))
        assertEquals("24 KB", FileSizeText.of(24_000, Locale.US))
        assertEquals("1.2 MB", FileSizeText.of(1_200_000, Locale.US))
        assertEquals("24 MB", FileSizeText.of(24_000_000, Locale.US))
        assertEquals("1.1 GB", FileSizeText.of(1_100_000_000, Locale.US))
    }

    // ---- the list -----------------------------------------------------------------------------------------------------------

    @Test
    fun theListHasDaySeparatorsAndAnUnreadMarkerAtTheFirstUnreadMessage() {
        val entries = listOf(
            ConversationEntry.V2(message(MessageDirection.IN, "a", "old", at = now - 3 * day)),
            ConversationEntry.V2(message(MessageDirection.OUT, "b", "reply", at = now - 3 * day + 1000)),
            ConversationEntry.V2(message(MessageDirection.IN, "c", "today one", at = now - 2000)),
            ConversationEntry.V2(message(MessageDirection.IN, "d", "today two", at = now - 1000)),
        )
        // The first two are read; the last two arrived while the thread was closed.
        entries[0].message.readAtMs = 1
        val first = ThreadItems.firstUnreadKey(entries.drop(1))
        assertEquals(entries[2].key, first)
        val items = ThreadItems.build(entries, "Asha", first, false, text, time) { CardInputs(null) }
        assertEquals(
            listOf("Day", "Bubble", "Bubble", "Day", "UnreadMarker", "Bubble", "Bubble"),
            items.map { it::class.simpleName },
        )
        assertEquals("Today", (items[3] as ThreadItem.Day).label)
        assertEquals(2, (items[4] as ThreadItem.UnreadMarker).count)
        assertTrue("keys are unique", items.map { it.key }.toSet().size == items.size)
    }

    @Test
    fun noUnreadMarkerWhenEverythingWasRead() {
        val entries = listOf(ConversationEntry.V2(message(MessageDirection.IN, "a", "x").also { it.readAtMs = 5 }))
        assertNull(ThreadItems.firstUnreadKey(entries))
        assertFalse(ThreadItems.build(entries, "Asha", null, false, text, time) { CardInputs(null) }.any { it is ThreadItem.UnreadMarker })
    }

    @Test
    fun aFileBubbleCarriesItsCardAndNoText() {
        val entry = ConversationEntry.V2(message(MessageDirection.IN, attachment = file()))
        val bubble = ThreadItems.bubble(entry, "Asha", false, text, time) { CardInputs(actionsFor(SniffedType.PDF)) }
        assertNotNull(bubble.card)
        assertEquals("", bubble.text)
    }

    @Test
    fun aLegacyRowIsReadableAndHasNoStateNoCardAndNoRetry() {
        val summary = ArchivedSummary("aa".repeat(16), MessageDirection.IN, peerHex, now, now, MessageKind.PHOTO, "image/jpeg", 30_000, 0, "")
        val bubble = ThreadItems.bubble(ConversationEntry.Legacy(summary), "Asha", false, text, time) { CardInputs(null) }
        assertTrue(bubble.legacy)
        assertNull(bubble.messageId)
        assertNull(bubble.state)
        assertNull(bubble.card)
        assertEquals("Older message: photo, 30 KB", bubble.text)
        val v1Text = ArchivedSummary("bb".repeat(16), MessageDirection.OUT, peerHex, now, now, MessageKind.TEXT, "text/plain", 5, 0, "hello")
        assertEquals("hello", ThreadItems.bubble(ConversationEntry.Legacy(v1Text), "Asha", false, text, time) { CardInputs(null) }.text)
    }

    @Test
    fun aRunningDisappearTimerIsShownAsTimeLeft() {
        val m = message(MessageDirection.IN)
        assertNull(ThreadItems.disappearsText(m, now, text))
        m.disappearAtMs = now + 30 * 60_000
        assertEquals("Disappears in 30 min", ThreadItems.disappearsText(m, now, text))
        m.disappearAtMs = now + 5 * 3_600_000
        assertEquals("Disappears in 5 h", ThreadItems.disappearsText(m, now, text))
        m.disappearAtMs = now + 5 * day
        assertEquals("Disappears in 5 days", ThreadItems.disappearsText(m, now, text))
    }

    // ---- choices -----------------------------------------------------------------------------------------------------------

    @Test
    fun timerAndAutoDownloadChoicesHaveWordsAndSnapToAnOffer() {
        DisappearChoices.SECONDS.forEach { assertTrue(text.get(DisappearLabels.key(it)).isNotEmpty()) }
        AutoDownloadChoices.MEGABYTES.forEach { assertTrue(text.get(AutoDownloadChoices.key(it)).isNotEmpty()) }
        assertEquals(8, AutoDownloadChoices.nearest(7))
        assertEquals(64, AutoDownloadChoices.nearest(60))
        assertEquals(0, AutoDownloadChoices.nearest(0))
        assertEquals(86_400, DisappearChoices.nearest(100_000))
        assertEquals(0, DisappearChoices.nearest(10))
        assertEquals(MessagingV2UserChoices(false, 8), MessagingV2UserChoices())
    }
}
