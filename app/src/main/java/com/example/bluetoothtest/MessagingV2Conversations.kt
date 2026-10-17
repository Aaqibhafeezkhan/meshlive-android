package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.File

/*
 * Messaging v2, phase 1d: the data behind "conversations live on Contacts rows" (DECISIONS Design M, Q-M1 answer B) and the
 * one-time move from the v1 Messages data. No screen is built here (phase 1c reads these classes); everything is a plain
 * query over the stores that already exist, so it has JVM tests and the DEBUG status line can print it.
 */

/** What stands between the user and sending to a contact (shown above the composer; the words are phase 1c). */
enum class ConversationLinkState {
    READY,

    /** Nobody with this identity is a contact: no thread. */
    NOT_A_CONTACT,

    /** The contact's card arrived but is not accepted yet (not verified in person): compare the safety number first. */
    CARD_PENDING,

    /** A newer signed card of this contact carries other keys: nothing is sent until the user compares again (M1). */
    KEYS_CHANGED,

    /** The contact's phone answered a v2 HELLO with DONE(error): "Their app is older: update to receive" (M1). */
    PEER_APP_OLDER,
}

/** The state of an own message as the thread shows it (M4): the highest applicable fact wins. */
enum class OwnMessageState { WAITING, SENDING, DELIVERED, DOWNLOADED, READ, NOT_SENT }

object ConversationRules {
    /**
     * Delivered, downloaded and read are independent flags set at most once; a receipt that arrives after "not sent" still sets
     * its flag, so a flag beats [ThreadMessage.notSent]. [sending] is true while an exchange with the contact is running.
     */
    fun ownState(message: ThreadMessage, sending: Boolean): OwnMessageState = when {
        message.read -> OwnMessageState.READ
        message.downloaded -> OwnMessageState.DOWNLOADED
        message.delivered -> OwnMessageState.DELIVERED
        message.notSent -> OwnMessageState.NOT_SENT
        sending -> OwnMessageState.SENDING
        else -> OwnMessageState.WAITING
    }
}

/** One row of a thread: a v2 message, or a v1 message kept readable as it was (M1 "legacy rows"). */
sealed interface ConversationEntry {
    val peerNode: String
    val sortMs: Long
    val key: String

    data class V2(val message: ThreadMessage) : ConversationEntry {
        override val peerNode: String get() = message.peerNode
        override val sortMs: Long get() = message.displayMs
        override val key: String get() = message.id
    }

    /** A message from the v1 Messages archive (kept as it was: no delivery flags, no read state). */
    data class Legacy(val summary: ArchivedSummary) : ConversationEntry {
        override val peerNode: String get() = summary.peerNode
        override val key: String get() = "v1-" + summary.bundleIdHex

        /** The same window as a v2 message: the sender's time clamped to `[arrival - 14 days, arrival]`. */
        override val sortMs: Long get() = summary.createdMs.coerceIn(summary.receivedMs - 14L * 24 * 3_600_000, summary.receivedMs)
    }
}

/** A Contacts row that doubles as a conversation row. */
data class ConversationSummary(
    val peer: NodeId,
    val name: String,
    val verified: Boolean,
    /** The newest message's text for the preview line; "" for a file or when there is no message. */
    val previewText: String,
    val lastKind: String,
    val lastMs: Long,
    val lastIncoming: Boolean,
    val unread: Int,
    val waiting: Int,
    val lastOwnState: OwnMessageState?,
    val link: ConversationLinkState,
    /** What the newest message carries (phase 1c: "Photo", "Voice note", "File"); null for a text or no message. */
    val lastPresentation: Presentation? = null,
) {
    val hasActivity: Boolean get() = lastMs > 0
}

/** Queries over the v2 thread store and the v1 archive for the Contacts rows and the thread screen. */
class ConversationIndex(
    private val node: StoreForwardNode,
    /** The label shown for a contact (the verified-contact name, else the handle). */
    private val nameOf: (StoreForwardContact) -> String = { it.label },
) {
    private val engine: MessagingV2Engine get() = requireNotNull(node.v2) { "Messaging v2 is off" }

    /** The thread of [peer], oldest first: v2 messages and v1 messages merged by their displayed time, then by key. */
    fun entries(peer: NodeId): List<ConversationEntry> {
        val peerHex = peer.toString()
        val v2 = engine.threads.forPeer(peerHex).map { ConversationEntry.V2(it) }
        val v1 = node.archive.page(0, Int.MAX_VALUE).filter { it.peerNode == peerHex }.map { ConversationEntry.Legacy(it) }
        return (v2 + v1).sortedWith(compareBy({ it.sortMs }, { it.key }))
    }

    /** Unread incoming v2 messages of [peer] (v1 rows have no read state and count as read). */
    fun unread(peer: NodeId): Int = engine.threads.forPeer(peer.toString()).count { it.direction == MessageDirection.IN && it.readAtMs == 0L }

    /**
     * One row per accepted contact. Rows with activity first (newest first), then A to Z by name, as the Contacts tab sorts
     * (Q-M1 option B). [showPreview] false (the `test_mv2_show_previews` switch) leaves the preview text empty.
     */
    fun summaries(showPreview: Boolean = true, sending: (NodeId) -> Boolean = { false }): List<ConversationSummary> {
        val verified = node.verifiedDirectory
        val rows = node.contacts.all().filter { it.state == ContactState.ACCEPTED }.map { contact ->
            val entries = entries(contact.nodeId)
            val last = entries.lastOrNull()
            val text = when (last) {
                is ConversationEntry.V2 -> if (last.message.kind == V2Kind.TEXT && showPreview) last.message.text.take(PREVIEW_CHARS) else ""
                is ConversationEntry.Legacy -> if (showPreview) last.summary.preview.take(PREVIEW_CHARS) else ""
                null -> ""
            }
            val lastV2 = (last as? ConversationEntry.V2)?.message
            ConversationSummary(
                contact.nodeId, nameOf(contact), contact.source == ContactSource.VERIFIED || verified?.isVerifiedNode(contact.nodeId) == true,
                text,
                when (last) {
                    is ConversationEntry.V2 -> if (last.message.attachment != null) "file" else "text"
                    is ConversationEntry.Legacy -> last.summary.kind.label
                    null -> ""
                },
                last?.sortMs ?: 0L,
                lastV2?.direction == MessageDirection.IN || (last as? ConversationEntry.Legacy)?.summary?.direction == MessageDirection.IN,
                unread(contact.nodeId),
                engine.threads.waitingFor(contact.nodeId.toString()),
                lastV2?.takeIf { it.direction == MessageDirection.OUT }?.let { ConversationRules.ownState(it, sending(contact.nodeId)) },
                engine.linkState(contact.nodeId),
                when (last) {
                    is ConversationEntry.V2 -> last.message.attachment?.presentation
                    is ConversationEntry.Legacy -> when (last.summary.kind) {
                        MessageKind.PHOTO -> Presentation.PICTURE
                        MessageKind.VOICE -> Presentation.VOICE
                        MessageKind.VIDEO -> Presentation.VIDEO
                        else -> null
                    }
                    null -> null
                },
            )
        }
        return rows.sortedWith(compareBy<ConversationSummary> { !it.hasActivity }.thenByDescending { it.lastMs }.thenBy { it.name.lowercase() }
            .thenBy { it.peer.toString() })
    }

    private companion object {
        const val PREVIEW_CHARS = 120
    }
}

/**
 * The first start of Messaging v2 on data from v1 Messages (M1 "Migration from v1"):
 *
 * - Accepted cards need no move: a thread is a contact row plus a query ([ConversationIndex]).
 * - v1 messages stay readable as legacy rows.
 * - Messages still queued unsent in the v1 outbox are sealed again once as version 3 bundles in the thread and the v1 bundle and
 *   its Sent row are withdrawn. Receipts, locations and anything that does not fit stay as v1 bundles (a v2 phone still
 *   offers a version 2 bundle to a v2 peer, which reads it as a legacy row).
 *
 * Crash safe and repeatable: the map from a v1 bundle to the new message ID is written before the v1 bundle is removed, so a
 * second run only finishes the removal. A crash between sealing and writing the map can send one message twice (two bubbles
 * for the receiver); that window is a few milliseconds.
 */
object MessagingV2Migration {
    const val FILE = "migration-1d.properties"
    private const val DONE = "done"

    class Report(val resealed: Int, val skipped: Int, val finishedRemovals: Int)

    fun run(node: StoreForwardNode): Report {
        val engine = node.v2 ?: return Report(0, 0, 0)
        val file = File(engine.directory, FILE)
        val ledger = readSealedProperties(file, engine.cipher)
        if (ledger.getProperty(DONE) == "1") return Report(0, 0, 0)
        var resealed = 0
        var skipped = 0
        var finished = 0
        val queued = node.store.all().filter {
            it.role == BundleRole.OWN && it.state == BundleState.QUEUED && it.complete && !it.header.isBulk &&
                it.header.version == StoreForwardFormat.BUNDLE_VERSION
        }
        for (bundle in queued) {
            val key = "bundle.${bundle.idHex}"
            if (ledger.getProperty(key) != null) {
                node.deleteMessage(bundle.idHex)
                finished++
                continue
            }
            val archived = node.archive.load(bundle.idHex)
            val peerHex = bundle.peerNode
            val peer = peerHex?.let { runCatching { NodeId.fromBytes(hexToBytes(it)) }.getOrNull() }
            val accepted = peer?.let { node.contacts.get(it) }?.takeIf { it.state == ContactState.ACCEPTED && !it.keysChanged }
            // A bundle without a Sent row is a receipt or leftover (nothing to show in a thread): it stays a v1 bundle.
            if (archived == null) continue
            if (peer == null || accepted == null) {
                skipped++
                continue
            }
            val sent = try {
                resealOne(engine, peer, archived.message)
            } catch (error: Exception) {
                null
            }
            if (sent == null) {
                skipped++
                continue
            }
            ledger.setProperty(key, sent.messageIdHex)
            writeSealedProperties(file, engine.cipher, ledger)
            // The thread shows the message at the time it was written, not at the time of the move.
            engine.threads.save(
                ThreadMessage(
                    sent.direction, sent.peerNode, sent.messageIdHex, sent.kind, archived.message.createdMs, archived.receivedMs, sent.flags,
                    sent.body, sent.attachment, sent.disappearSeconds, sent.replyToHex, sent.bundleIds,
                ),
            )
            node.deleteMessage(bundle.idHex)
            resealed++
        }
        if (skipped == 0) {
            ledger.setProperty(DONE, "1")
            writeSealedProperties(file, engine.cipher, ledger)
        }
        return Report(resealed, skipped, finished)
    }

    private fun resealOne(engine: MessagingV2Engine, peer: NodeId, message: BundleMessage): ThreadMessage? = when (message.kind) {
        MessageKind.TEXT ->
            if (message.body.isEmpty() || message.body.size > MessagingV2Format.TEXT_MAX_BYTES) null
            else engine.sendText(peer, String(message.body, Charsets.UTF_8))
        MessageKind.PHOTO -> file(engine, peer, message, Presentation.PICTURE, "photo.jpg")
        MessageKind.VOICE -> file(engine, peer, message, Presentation.VOICE, "voice-note.m4a")
        MessageKind.VIDEO -> file(engine, peer, message, Presentation.VIDEO, "video.mp4")
        else -> null
    }

    private fun file(engine: MessagingV2Engine, peer: NodeId, message: BundleMessage, presentation: Presentation, name: String): ThreadMessage? {
        if (message.body.isEmpty() || message.body.size > MessagingV2Format.INLINE_MAX_BYTES) return null
        return engine.sendFile(
            peer, { ByteArrayInputStream(message.body) }, message.body.size.toLong(), presentation, name,
            message.mime.ifBlank { MessagingV2Format.DEFAULT_MIME },
        )
    }
}
