package com.example.bluetoothtest

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/*
 * Messaging v2, phase 1c: what the Contacts rows and the thread screen show, as plain data. No Android classes, so every
 * rule (row text, tick mapping, banners, composer state, file cards, date separators, no raw IDs) has a JVM test and the
 * screens only draw these models. The words come through [ThreadText]; the app reads string resources, the tests read the
 * same strings file (strings_messaging_v2_ui.xml), so copy rules are checked against the real text.
 */

/** Looks up the string resource named [key] (without the `R.string.` prefix) and formats it. */
interface ThreadText {
    fun get(key: String, vararg args: Any): String
}

/** Clock and calendar for the screens: injected so tests are exact. */
class ThreadTime(
    private val nowMs: () -> Long,
    private val zone: ZoneId,
    /** "14:02" in the phone's own format. */
    private val clockText: (Long) -> String,
    /** "12 Oct" (the year is added by this class when it differs from this year's). */
    private val dayText: (Long, Boolean) -> String,
) {
    fun now(): Long = nowMs()

    fun clock(ms: Long): String = clockText(ms)

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun sameDay(a: Long, b: Long): Boolean = day(a) == day(b)

    fun dayKey(ms: Long): String = day(ms).toString()

    /** A day separator: Today, Yesterday, else the date. */
    fun dayLabel(ms: Long, text: ThreadText): String {
        val today = day(nowMs())
        val d = day(ms)
        return when {
            d == today -> text.get("mv2_ui_today")
            d == today.minusDays(1) -> text.get("mv2_ui_yesterday")
            else -> dayText(ms, d.year != today.year)
        }
    }

    /** The row's time: the clock for today, "Yesterday", else the date. */
    fun rowTime(ms: Long, text: ThreadText): String {
        if (ms <= 0) return ""
        val today = day(nowMs())
        val d = day(ms)
        return when {
            d == today -> clockText(ms)
            d == today.minusDays(1) -> text.get("mv2_ui_yesterday")
            else -> dayText(ms, d.year != today.year)
        }
    }
}

// ---- states and ticks (Q-M3, M4) -------------------------------------------------------------------------------------------------

enum class DeliveryGlyph { CLOCK, UPLOAD, CHECK, CHECKS, CHECKS_READ, ERROR }

/** What an own message shows beside its time: a glyph and the word a tap reveals. */
data class StateView(val state: OwnMessageState, val glyph: DeliveryGlyph, val word: String)

object TickRules {
    fun glyph(state: OwnMessageState): DeliveryGlyph = when (state) {
        OwnMessageState.WAITING -> DeliveryGlyph.CLOCK
        OwnMessageState.SENDING -> DeliveryGlyph.UPLOAD
        OwnMessageState.DELIVERED -> DeliveryGlyph.CHECK
        OwnMessageState.DOWNLOADED -> DeliveryGlyph.CHECKS
        OwnMessageState.READ -> DeliveryGlyph.CHECKS_READ
        OwnMessageState.NOT_SENT -> DeliveryGlyph.ERROR
    }

    fun word(state: OwnMessageState, text: ThreadText): String = text.get(
        when (state) {
            OwnMessageState.WAITING -> "mv2_ui_state_waiting"
            OwnMessageState.SENDING -> "mv2_ui_state_sending"
            OwnMessageState.DELIVERED -> "mv2_ui_state_delivered"
            OwnMessageState.DOWNLOADED -> "mv2_ui_state_downloaded"
            OwnMessageState.READ -> "mv2_ui_state_read"
            OwnMessageState.NOT_SENT -> "mv2_ui_state_not_sent"
        },
    )

    fun view(state: OwnMessageState, text: ThreadText) = StateView(state, glyph(state), word(state, text))

    /** The sentence a tap on the glyph shows ("Delivered to Rahul, 14:00"). */
    fun caption(message: ThreadMessage, state: OwnMessageState, name: String, time: ThreadTime, text: ThreadText): String = when (state) {
        OwnMessageState.WAITING -> text.get("mv2_ui_caption_waiting", name)
        OwnMessageState.SENDING -> text.get("mv2_ui_caption_sending", name)
        OwnMessageState.DELIVERED -> text.get("mv2_ui_caption_delivered", name, time.clock(message.deliveredAtMs))
        OwnMessageState.DOWNLOADED -> text.get("mv2_ui_caption_downloaded", name, time.clock(message.downloadedAtMs))
        OwnMessageState.READ -> text.get("mv2_ui_caption_read", name, time.clock(message.readAtMs))
        OwnMessageState.NOT_SENT -> text.get("mv2_ui_caption_not_sent")
    }
}

// ---- names (no raw IDs anywhere) ---------------------------------------------------------------------------------------------------

object ThreadNaming {
    /**
     * The one name of a contact on every conversation surface: the user's own label, else the handle the Contacts tab shows
     * (the naming precedence of [PeerLabels]). Never the node ID, never its hex prefix.
     */
    fun title(nodeIdHex: String, contactName: String?): String =
        PeerLabels.forPeer(nodeIdHex.take(12), contactName?.takeIf { it.isNotBlank() }).listName()
}

// ---- Contacts rows (Q-M1, option B) ---------------------------------------------------------------------------------------------

/** One saved person as the Contacts tab knows them (the verified-contact store), before the conversation facts are joined. */
data class ContactRef(val nodeIdHex: String, val title: String, val verified: Boolean = true)

data class ConversationRowModel(
    val nodeIdHex: String,
    val title: String,
    val verified: Boolean,
    /** Preview of the newest message, or "Nearby now" when there is no message, or an attention line. */
    val subtitle: String,
    val timeText: String,
    val unread: Int,
    /** The state glyph of the newest message when it is your own. */
    val glyph: StateView?,
    val nearby: Boolean,
    val link: ConversationLinkState,
    val hasActivity: Boolean,
    val description: String,
)

object ConversationRows {
    /**
     * One row per saved person, joined with the conversation facts of that person ([summaries] keyed by node ID hex). Rows with
     * activity first (newest first), then the order of [contacts] (named people A to Z, as the Contacts tab sorts them).
     * [nearbyPrefixes] are the 12-hex prefixes in range now.
     */
    fun build(
        contacts: List<ContactRef>,
        summaries: Map<String, ConversationSummary>,
        nearbyPrefixes: Set<String>,
        text: ThreadText,
        time: ThreadTime,
    ): List<ConversationRowModel> {
        val rows = contacts.map { row(it, summaries[it.nodeIdHex], it.nodeIdHex.take(12) in nearbyPrefixes, text, time) }
        return rows.withIndex()
            .sortedWith(compareBy<IndexedValue<ConversationRowModel>> { !it.value.hasActivity }.thenByDescending { sortKey(it.value, summaries) }.thenBy { it.index })
            .map { it.value }
    }

    private fun sortKey(row: ConversationRowModel, summaries: Map<String, ConversationSummary>): Long =
        if (row.hasActivity) summaries[row.nodeIdHex]?.lastMs ?: 0L else 0L

    fun row(contact: ContactRef, summary: ConversationSummary?, nearby: Boolean, text: ThreadText, time: ThreadTime): ConversationRowModel {
        val active = summary?.hasActivity == true
        val subtitle = subtitle(summary, nearby, text)
        val glyph = summary?.lastOwnState?.takeIf { active }?.let { TickRules.view(it, text) }
        val unread = summary?.unread ?: 0
        val timeText = if (active) time.rowTime(summary!!.lastMs, text) else ""
        val description = buildString {
            append(contact.title)
            if (contact.verified) append(", ").append(text.get("mv2_ui_verified"))
            if (subtitle.isNotEmpty()) append(", ").append(subtitle)
            if (timeText.isNotEmpty()) append(", ").append(timeText)
            if (unread > 0) append(", ").append(text.get("mv2_ui_unread_count", unread))
            if (glyph != null) append(", ").append(glyph.word)
            if (nearby && active) append(", ").append(text.get("mv2_ui_nearby_now"))
        }
        return ConversationRowModel(
            contact.nodeIdHex, contact.title, contact.verified, subtitle, timeText, unread, glyph, nearby,
            summary?.link ?: ConversationLinkState.NOT_A_CONTACT, active, description,
        )
    }

    fun subtitle(summary: ConversationSummary?, nearby: Boolean, text: ThreadText): String {
        if (summary != null && summary.link == ConversationLinkState.KEYS_CHANGED) return text.get("mv2_ui_row_keys_changed")
        if (summary == null || !summary.hasActivity) return if (nearby) text.get("mv2_ui_nearby_now") else ""
        val typed = summary.lastPresentation?.let { text.get(presentationKey(it)) }
        val words = summary.previewText.replace(Regex("\\s+"), " ").trim()
        return when {
            typed != null -> typed
            words.isNotEmpty() -> words
            else -> text.get("mv2_ui_preview_message")
        }
    }

    fun presentationKey(presentation: Presentation): String = when (presentation) {
        Presentation.PICTURE -> "mv2_ui_preview_photo"
        Presentation.VIDEO -> "mv2_ui_preview_video"
        Presentation.VOICE -> "mv2_ui_preview_voice"
        Presentation.FILE -> "mv2_ui_preview_file"
    }
}

// ---- banners and the composer (M1) ----------------------------------------------------------------------------------------------

enum class BannerAction { ADD_CONTACT, COMPARE, ACCEPT_KEYS, DISMISS }

data class BannerModel(val text: String, val actions: List<BannerAction>)

data class ComposerModel(val enabled: Boolean, val hint: String, val banner: BannerModel?)

object ThreadBanners {
    /**
     * The banner above the composer and whether the composer can be used. [bannerDismissed] hides the banner of a key change
     * for this visit; the composer stays off and its hint says why, because nothing is sent until the user compares again.
     */
    fun compose(link: ConversationLinkState, name: String, text: ThreadText, bannerDismissed: Boolean = false): ComposerModel = when (link) {
        ConversationLinkState.READY -> ComposerModel(true, text.get("mv2_ui_composer_hint"), null)
        ConversationLinkState.PEER_APP_OLDER ->
            ComposerModel(true, text.get("mv2_ui_composer_hint"), BannerModel(text.get("mv2_ui_banner_peer_older", name), emptyList()))
        ConversationLinkState.NOT_A_CONTACT -> ComposerModel(
            false, text.get("mv2_ui_composer_off_not_contact"),
            BannerModel(text.get("mv2_ui_banner_not_contact", name), listOf(BannerAction.ADD_CONTACT)),
        )
        ConversationLinkState.CARD_PENDING -> ComposerModel(
            false, text.get("mv2_ui_composer_off_card_pending"),
            BannerModel(text.get("mv2_ui_banner_card_pending", name), listOf(BannerAction.COMPARE)),
        )
        ConversationLinkState.KEYS_CHANGED -> ComposerModel(
            false, text.get("mv2_ui_composer_off_keys_changed"),
            if (bannerDismissed) null
            else BannerModel(text.get("mv2_ui_banner_keys_changed", name), listOf(BannerAction.COMPARE, BannerAction.ACCEPT_KEYS, BannerAction.DISMISS)),
        )
    }

    /** The line under the name in the top bar. */
    fun stateLine(link: ConversationLinkState, nearby: Boolean, text: ThreadText): String = when (link) {
        ConversationLinkState.NOT_A_CONTACT, ConversationLinkState.CARD_PENDING -> text.get("mv2_ui_state_not_verified")
        ConversationLinkState.KEYS_CHANGED -> text.get("mv2_ui_row_keys_changed")
        else -> text.get(if (nearby) "mv2_ui_state_nearby" else "mv2_ui_state_not_nearby")
    }
}

// ---- file cards (Q-M7, Q-M12) ------------------------------------------------------------------------------------------------

enum class CardAction { DOWNLOAD, CANCEL, OPEN, SAVE, SHOW_PICTURE }

enum class CardIcon { PICTURE, VIDEO, AUDIO, DOCUMENT, FILE }

/** Facts the screen collects from the vault and transfer book for one file message (sniffing is a vault read, so it lives there). */
data class CardInputs(
    /** From [FileActionPolicy]; null while the file is incomplete or could not be read. */
    val actions: FileActions?,
    /** Share of the segments received, 0..1, for a download in progress. */
    val progress: Float? = null,
    val peerNearby: Boolean = false,
)

data class AttachmentCardModel(
    val icon: CardIcon,
    val name: String,
    val sizeText: String,
    /** A line under the name: progress, "Waiting for Rahul to be nearby", "Damaged, ask again". */
    val status: String?,
    val progress: Float?,
    val actions: List<CardAction>,
    /** What a tap on the card does: show the picture, else open, else nothing. */
    val tap: CardAction?,
    /** The text of the Download button includes the size. */
    val downloadLabel: String?,
    /** Save asks first (the file is not opened from Messages, or can run code). */
    val saveWarning: WarnLevel,
)

object FileSizeText {
    fun of(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> String.format(locale, "%.0f KB", bytes / 1_000.0)
        bytes < 10_000_000 -> String.format(locale, "%.1f MB", bytes / 1_000_000.0)
        bytes < 1_000_000_000 -> String.format(locale, "%.0f MB", bytes / 1_000_000.0)
        else -> String.format(locale, "%.1f GB", bytes / 1_000_000_000.0)
    }
}

object AttachmentCards {
    fun build(message: ThreadMessage, state: OwnMessageState?, inputs: CardInputs, peerName: String, text: ThreadText): AttachmentCardModel? {
        val attachment = message.attachment ?: return null
        val actions = ArrayList<CardAction>()
        var status: String? = null
        var progress: Float? = null
        var downloadLabel: String? = null
        val size = FileSizeText.of(attachment.totalSize)
        val complete = attachment.state == FileState.STORED || attachment.state == FileState.COMPLETE
        when (attachment.state) {
            FileState.AWAITING_APPROVAL -> {
                actions += CardAction.DOWNLOAD
                downloadLabel = text.get("mv2_ui_file_download", size)
            }
            FileState.DOWNLOADING -> {
                actions += CardAction.CANCEL
                progress = inputs.progress?.coerceIn(0f, 1f)
                status = if (inputs.peerNearby) text.get("mv2_ui_file_downloading", ((progress ?: 0f) * 100).toInt())
                else text.get("mv2_ui_file_waiting_for", peerName)
            }
            FileState.DAMAGED -> status = text.get("mv2_ui_file_damaged", peerName)
            FileState.UNAVAILABLE -> status = text.get("mv2_ui_file_unavailable")
            FileState.STORED, FileState.COMPLETE -> Unit
        }
        var tap: CardAction? = null
        var warn = WarnLevel.NONE
        if (complete) {
            val policy = inputs.actions
            if (policy != null) {
                // A picture is shown in the app after a tap (isolated decode); everything else that may be opened goes to a viewer.
                if (policy.canPreview) actions += CardAction.SHOW_PICTURE else if (policy.canOpen) actions += CardAction.OPEN
                actions += CardAction.SAVE
                warn = policy.saveWarning
                tap = when {
                    policy.canPreview -> CardAction.SHOW_PICTURE
                    policy.canOpen -> CardAction.OPEN
                    else -> null
                }
            }
        }
        // Your own file that has not arrived yet can be withdrawn.
        if (message.direction == MessageDirection.OUT && (state == OwnMessageState.WAITING || state == OwnMessageState.SENDING)) {
            actions += CardAction.CANCEL
        }
        val icon = when (inputs.actions?.iconCategory) {
            SniffCategory.PICTURE -> CardIcon.PICTURE
            SniffCategory.VIDEO -> CardIcon.VIDEO
            SniffCategory.AUDIO -> CardIcon.AUDIO
            SniffCategory.PDF, SniffCategory.TEXT, SniffCategory.OFFICE -> CardIcon.DOCUMENT
            null -> when (attachment.presentation) {
                Presentation.PICTURE -> CardIcon.PICTURE
                Presentation.VIDEO -> CardIcon.VIDEO
                Presentation.VOICE -> CardIcon.AUDIO
                Presentation.FILE -> CardIcon.FILE
            }
            else -> CardIcon.FILE
        }
        return AttachmentCardModel(icon, attachment.name, size, status, progress, actions, tap, downloadLabel, warn)
    }
}

// ---- the message list --------------------------------------------------------------------------------------------------------------

data class BubbleModel(
    val key: String,
    /** The thread record's ID for engine calls; null for a v1 (legacy) row. */
    val messageId: String?,
    val incoming: Boolean,
    val text: String,
    val timeText: String,
    val state: StateView?,
    /** What a tap on the state glyph reveals. */
    val stateCaption: String?,
    val card: AttachmentCardModel?,
    val legacy: Boolean,
    val canRetry: Boolean,
    /** "Disappears in 3 h" once the timer runs. */
    val disappears: String?,
    /** The time the bubble is placed by. */
    val sentAtMs: Long,
)

sealed interface ThreadItem {
    val key: String

    data class Day(val label: String, val dayKey: String) : ThreadItem {
        override val key: String get() = "day-$dayKey"
    }

    data class UnreadMarker(val count: Int) : ThreadItem {
        override val key: String get() = "unread-marker"
    }

    data class Bubble(val model: BubbleModel) : ThreadItem {
        override val key: String get() = model.key
    }
}

object ThreadItems {
    /**
     * The list the thread shows: day separators, the unread marker before the first message that was unread when the thread
     * opened ([firstUnreadKey]), and a bubble per entry. [cardInputs] gathers the vault facts of a file message, [sending] tells
     * whether an exchange with the contact is running.
     */
    fun build(
        entries: List<ConversationEntry>,
        peerName: String,
        firstUnreadKey: String?,
        sending: Boolean,
        text: ThreadText,
        time: ThreadTime,
        cardInputs: (ThreadMessage) -> CardInputs,
    ): List<ThreadItem> {
        val out = ArrayList<ThreadItem>()
        var lastDayMs = -1L
        val unreadCount = if (firstUnreadKey == null) 0 else entries.dropWhile { it.key != firstUnreadKey }.count { it is ConversationEntry.V2 && it.message.direction == MessageDirection.IN }
        for (entry in entries) {
            if (lastDayMs < 0 || !time.sameDay(lastDayMs, entry.sortMs)) {
                out += ThreadItem.Day(time.dayLabel(entry.sortMs, text), time.dayKey(entry.sortMs))
                lastDayMs = entry.sortMs
            }
            if (entry.key == firstUnreadKey) out += ThreadItem.UnreadMarker(unreadCount)
            out += ThreadItem.Bubble(bubble(entry, peerName, sending, text, time, cardInputs))
        }
        return out
    }

    fun bubble(entry: ConversationEntry, peerName: String, sending: Boolean, text: ThreadText, time: ThreadTime, cardInputs: (ThreadMessage) -> CardInputs): BubbleModel =
        when (entry) {
            is ConversationEntry.Legacy -> {
                val summary = entry.summary
                val body = if (summary.kind == MessageKind.TEXT) summary.preview else text.get("mv2_ui_legacy_kind", summary.kind.label, FileSizeText.of(summary.bodyBytes.toLong()))
                BubbleModel(
                    entry.key, null, summary.direction == MessageDirection.IN, body, time.clock(entry.sortMs), null, null, null,
                    legacy = true, canRetry = false, disappears = null, sentAtMs = entry.sortMs,
                )
            }
            is ConversationEntry.V2 -> {
                val message = entry.message
                val incoming = message.direction == MessageDirection.IN
                val own = if (incoming) null else ConversationRules.ownState(message, sending)
                val card = AttachmentCards.build(message, own, cardInputs(message), peerName, text)
                BubbleModel(
                    entry.key, message.id, incoming, if (message.attachment == null) message.text else "", time.clock(entry.sortMs),
                    own?.let { TickRules.view(it, text) }, own?.let { TickRules.caption(message, it, peerName, time, text) }, card,
                    legacy = false, canRetry = own == OwnMessageState.NOT_SENT, disappears = disappearsText(message, time.now(), text),
                    sentAtMs = entry.sortMs,
                )
            }
        }

    /** The first unread incoming entry's key (the boundary the unread marker is drawn at), or null. */
    fun firstUnreadKey(entries: List<ConversationEntry>): String? =
        entries.firstOrNull { it is ConversationEntry.V2 && it.message.direction == MessageDirection.IN && it.message.readAtMs == 0L }?.key

    fun disappearsText(message: ThreadMessage, nowMs: Long, text: ThreadText): String? {
        if (message.disappearAtMs <= 0) return null
        val left = (message.disappearAtMs - nowMs).coerceAtLeast(0)
        val minutes = (left + 59_999) / 60_000
        return when {
            minutes < 60 -> text.get("mv2_ui_disappears_minutes", maxOf(1L, minutes))
            minutes < 48 * 60 -> text.get("mv2_ui_disappears_hours", (minutes + 59) / 60)
            else -> text.get("mv2_ui_disappears_days", (minutes + 24 * 60 - 1) / (24 * 60))
        }
    }
}

// ---- thread info and Settings > Privacy choices -----------------------------------------------------------------------------------

object DisappearLabels {
    fun key(seconds: Int): String = when (seconds) {
        0 -> "mv2_ui_timer_off"
        3_600 -> "mv2_ui_timer_hour"
        86_400 -> "mv2_ui_timer_day"
        7 * 86_400 -> "mv2_ui_timer_week"
        30 * 86_400 -> "mv2_ui_timer_month"
        else -> "mv2_ui_timer_off"
    }
}

/** Settings > Privacy "Download files automatically": the largest segmented file fetched without a tap, in MB. */
object AutoDownloadChoices {
    val MEGABYTES: List<Int> = listOf(0, 1, 8, 25, 64)

    fun nearest(mb: Int): Int = MEGABYTES.minByOrNull { kotlin.math.abs(it - mb) } ?: 8

    fun key(mb: Int): String = when (mb) {
        0 -> "mv2_ui_autodl_never"
        1 -> "mv2_ui_autodl_1"
        8 -> "mv2_ui_autodl_8"
        25 -> "mv2_ui_autodl_25"
        else -> "mv2_ui_autodl_any"
    }
}

/** The Settings choices (Settings > Privacy), stored by the app; the defaults are the design's (receipts off, 8 MB). */
data class MessagingV2UserChoices(val readReceipts: Boolean = false, val autoDownloadMb: Int = 8) {
    companion object {
        const val PREF_READ_RECEIPTS = "mv2_read_receipts"
        const val PREF_AUTO_DOWNLOAD_MB = "mv2_auto_download_mb"
    }
}

/** The padding that keeps the thread overlay clear of the system bars and the keyboard. */
object ThreadInsets {
    data class Pad(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * [barsBottom] is the navigation bar, [imeBottom] the keyboard (Android 11 and later), [legacyBottom] the system window inset
     * of Android 10 and earlier, which already includes a visible keyboard. The composer sits above whichever is tallest.
     */
    fun padding(left: Int, top: Int, right: Int, barsBottom: Int, imeBottom: Int, legacyBottom: Int): Pad =
        Pad(left, top, right, maxOf(barsBottom, imeBottom, legacyBottom))
}
