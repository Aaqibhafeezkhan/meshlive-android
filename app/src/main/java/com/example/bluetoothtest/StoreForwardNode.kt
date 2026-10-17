package com.example.bluetoothtest

import java.io.File
import java.security.SecureRandom

/** One SUMMARY line: what the offering phone carries. Carrier-visible fields only. */
class SummaryEntry(
    val bundleId: ByteArray,
    val tag: ByteArray,
    val priorityClass: Int,
    val expiryHour: Int,
    val chunkCount: Int,
    val chunksHeld: Int,
) {
    init {
        require(bundleId.size == StoreForwardFormat.BUNDLE_ID_BYTES)
        require(tag.size == StoreForwardFormat.TAG_BYTES)
    }

    val idHex: String get() = bundleId.sfHex()
    /** Upper bound of the ciphertext this bundle occupies, before its header is known. */
    val maxBytes: Long get() = chunkCount.toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES
}

enum class StoreForwardRefusal(val wireValue: Int) {
    /** Not a refusal: every chunk this phone can provide for the WANT has been sent. */
    SENT(0),
    HAVE(1),
    QUOTA(2),
    EXPIRED(3),
    RATE(4),
    TOO_LARGE(5),
    INVALID(6),
    NOT_FOR_ME(7),
    UNKNOWN(8),
    /** Messaging v2 phase 2: the slot named a prekey this phone no longer holds. Not emitted in phase 1. */
    PREKEY_GONE(9),
    ;

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown store-forward reason $value")
    }
}

sealed interface StoreForwardAdmission {
    /** Request this bundle; [missing] are the chunk indexes still needed. */
    /** [candidates]: the accepted contacts whose tag matched (the HEADER must authenticate one). */
    data class Want(val role: BundleRole, val candidates: Set<NodeId>, val needHeader: Boolean, val missing: java.util.BitSet) :
        StoreForwardAdmission
    /** We already opened it: tell the adjacent phone (DELIVERED_LOCAL, hop-local only). */
    data class AlreadyDelivered(val bundleId: ByteArray) : StoreForwardAdmission
    data class Skip(val reason: StoreForwardRefusal) : StoreForwardAdmission
}

/** A HEADER refused before any chunk is stored, with the reason sent back to the peer. */
class StoreForwardRefused(val reason: StoreForwardRefusal, message: String) : IllegalArgumentException(message)

/** What happened to a bundle that completed in a session. */
sealed interface StoreForwardCompletion {
    data class Opened(val message: ArchivedMessage) : StoreForwardCompletion
    data class ReceiptOpened(val forBundleHex: String) : StoreForwardCompletion
    data object PendingContact : StoreForwardCompletion
    data object Carried : StoreForwardCompletion
    data class Rejected(val reason: String) : StoreForwardCompletion
    /** Authenticated but not stored durably (archive full or write failed): kept, not acknowledged. */
    data class Deferred(val reason: String) : StoreForwardCompletion
    /** Messaging v2: a text or attachment manifest stored as a conversation message. */
    data class V2Message(val message: ThreadMessage) : StoreForwardCompletion
    /** Messaging v2: a re-sealed replacement of a message already opened; dropped, still acknowledged. */
    data object V2Duplicate : StoreForwardCompletion
    /** Messaging v2: a receipt bundle; [applied] flags changed, [ignored] named nothing new. */
    data class V2Receipts(val applied: Int, val ignored: Int) : StoreForwardCompletion
    /** Messaging v2: segment [index] of a download is stored; [fileComplete] when it was the last, [damaged] if the hash failed. */
    data class V2Segment(val transferId: String, val index: Int, val fileComplete: Boolean, val damaged: Boolean = false) :
        StoreForwardCompletion
}

/** What an authenticated adjacent phone may send and receive in one exchange and one UTC day (M8). */
class AdmissionTier(
    val sessionBytes: Long,
    val dailyBytes: Long,
    val dailyBundles: Int,
    val senderDailyBundles: Int,
    val sessionBytesOut: Long,
    val dailyBytesOut: Long,
    /** An accepted contact in a Messaging v2 session. */
    val contact: Boolean,
    val v2: Boolean,
)

/** Where a Messaging v2 engine keeps its files and how it checks free space. */
class MessagingV2Setup(
    val limits: MessagingV2Limits = MessagingV2Limits(),
    val freeSpace: () -> Long = { Long.MAX_VALUE },
    val random: SecureRandom = SecureRandom(),
)

/**
 * Store-and-forward state of this phone: its mailbox key, contacts, bundle store, ledger and
 * archive. Every decision uses only local state and the one authenticated adjacent identity of
 * the current link (hop-local, DECISIONS Design A section 10).
 */
class StoreForwardNode(
    val nodeId: NodeId,
    private val identity: SigningIdentity?,
    val mailbox: MailboxKey,
    val store: BundleStore,
    val contacts: ContactBook,
    val archive: MessageArchive,
    val ledger: NeighborLedger,
    val limits: StoreForwardLimits = StoreForwardLimits(),
    val clock: () -> Long = System::currentTimeMillis,
    /** QA3: 1:1 delivery receipts on, time coarsened to the hour. */
    val receiptsEnabled: Boolean = true,
    private val random: SecureRandom = SecureRandom(),
    /** Messaging v2 (switch `test_messaging_v2`): null keeps every v1 path and every byte on air exactly as before. */
    messagingV2: MessagingV2Setup? = null,
    root: File? = null,
    cipher: AtRestCipher? = null,
) {
    private val pairSecrets = HashMap<String, ByteArray>()
    private val sessions = java.util.concurrent.CopyOnWriteArraySet<StoreForwardLinkSession>()

    /** The Messaging v2 engine, or null when the master switch is off. */
    val v2: MessagingV2Engine? = if (messagingV2 != null) {
        MessagingV2Engine(
            this, requireNotNull(root) { "Messaging v2 needs a storage root" }, requireNotNull(cipher) { "Messaging v2 needs a cipher" },
            messagingV2.limits, clock, messagingV2.random, messagingV2.freeSpace,
        )
    } else null

    /**
     * Messaging v2 (M1): the verified-contact store as the source of truth for trust. Set by the Android runtime when the
     * node is opened; null in v1 and in tests that do not need it. A card from a verified identity is accepted without a
     * second comparison.
     */
    @Volatile var verifiedDirectory: VerifiedIdentityDirectory? = null

    internal fun registerSession(session: StoreForwardLinkSession) {
        sessions += session
    }

    internal fun unregisterSession(session: StoreForwardLinkSession) {
        sessions -= session
    }

    /** Exchanges that are running now and stay open as chat sessions (the chat controller ends them when interest lapses). */
    fun chatSessions(): List<StoreForwardLinkSession> = sessions.filter { it.isChat }

    /** Whether a chat session with [peer] is open now. */
    fun chatSessionActive(peer: NodeId): Boolean = sessions.any { it.isChat && it.peerId == peer }

    /** Whether any exchange with [peer] is running now (the thread shows "Sending" for messages that wait). */
    fun exchangeRunning(peer: NodeId): Boolean = sessions.any { it.peerId == peer }

    private val completionListeners = java.util.concurrent.CopyOnWriteArrayList<(NodeId, StoreForwardCompletion) -> Unit>()

    /**
     * Told, from the exchange's reader thread, about every completion that reached storage while an exchange runs (a chat
     * session lasts up to 30 minutes, so the exchange result alone would show a message at its end). Keep it short.
     */
    fun addCompletionListener(listener: (NodeId, StoreForwardCompletion) -> Unit) {
        completionListeners += listener
    }

    fun removeCompletionListener(listener: (NodeId, StoreForwardCompletion) -> Unit) {
        completionListeners -= listener
    }

    internal fun notifyCompletion(peer: NodeId, completion: StoreForwardCompletion) {
        completionListeners.forEach { runCatching { it(peer, completion) } }
    }

    /** A new bundle was queued: running chat sessions offer it at once. */
    fun notifyNewBundles() = sessions.forEach { it.offerNewBundles() }

    /**
     * A peer we hold a card for is trusted for messaging when it is an accepted contact, or when the user verified its
     * identity in person (its card has not arrived yet, so it is not a contact row).
     */
    fun isTrustedPeer(peer: NodeId): Boolean =
        contacts.get(peer)?.state == ContactState.ACCEPTED || verifiedDirectory?.isVerifiedNode(peer) == true

    /**
     * A card arrived over [peer]'s authenticated link. With Messaging v2 on, the verified-contact store decides trust and a
     * changed mailbox key is held back (see [ContactBook.offerFromLinkV2]); without it, exactly v1's behaviour.
     */
    fun onCardFromLink(card: ContactCard, peer: NodeId): CardOffer =
        if (v2 != null) contacts.offerFromLinkV2(card, peer, verifiedDirectory) else contacts.offerFromLink(card, peer)

    /**
     * Messaging v2 housekeeping: follows the verified-contact store (a verified identity's pending card is accepted, a
     * forgotten verification removes the contact it had auto-accepted), then opens bundles that waited for a promoted card.
     */
    fun reconcileContacts(): List<StoreForwardCompletion> {
        val directory = verifiedDirectory ?: return emptyList()
        if (v2 == null) return emptyList()
        val changed = contacts.reconcileVerified(directory)
        changed.demoted.forEach { removeContact(it) }
        return changed.promoted.flatMap { openPendingFrom(it) }
    }

    /** A download was approved: running sessions ask the sender for the missing segments now. */
    fun pullNow() = sessions.forEach { it.pullNow() }

    /** The user cancelled a download: running sessions tell the sender to stop serving these bundles. */
    fun cancelBundles(ids: List<ByteArray>) = sessions.forEach { session -> ids.forEach(session::cancelBundle) }

    /** The v1 admission numbers, or the contact tier for an accepted contact in a Messaging v2 session. */
    fun tierFor(peer: NodeId, v2Active: Boolean): AdmissionTier {
        val engine = v2
        if (!v2Active || engine == null) {
            return AdmissionTier(limits.sessionBytes, limits.neighborDailyBytes, limits.neighborDailyBundles, limits.senderDailyBundles,
                limits.sessionBytesOut, limits.neighborDailyBytesOut, contact = false, v2 = false)
        }
        return if (engine.isContact(peer)) {
            val l = engine.limits
            AdmissionTier(l.contactSessionBytes, l.contactDailyBytes, l.contactDailyMessages + l.contactDailyReceipts,
                l.contactDailyMessages, l.contactSessionBytes, l.contactDailyBytes, contact = true, v2 = true)
        } else {
            AdmissionTier(limits.sessionBytes, limits.neighborDailyBytes, limits.neighborDailyBundles, limits.senderDailyBundles,
                limits.sessionBytesOut, limits.neighborDailyBytesOut, contact = false, v2 = true)
        }
    }
    @Volatile private var cachedCard: ContactCard? = null

    val ownCard: ContactCard
        get() = cachedCard ?: synchronized(this) {
            cachedCard ?: ContactCard.create(requireNotNull(identity) { "No signing identity" }, mailbox, clock())
                .also { cachedCard = it }
        }

    /** Free foreign space advertised in HELLO; 0 means this phone accepts only its own messages. */
    fun freeForeignBytes(): Long =
        if (limits.carrierEnabled) (limits.foreignQuotaBytes - store.usage(BundleRole.FOREIGN)).coerceAtLeast(0) else 0

    fun dailyRemainingBytes(peer: NodeId): Long =
        (limits.neighborDailyBytes - ledger.today(NeighborLedger.neighborKey(peer)).bytesIn).coerceAtLeast(0)

    @Synchronized
    fun pairSecret(contact: StoreForwardContact): ByteArray {
        val key = contact.nodeId.toString() + contact.card.mailboxPublic.sfHex()
        return pairSecrets.getOrPut(key) { StoreForwardTags.pairSecret(mailbox, nodeId, contact.card.mailboxKey, contact.nodeId) }
    }

    /** Seals [message] to an accepted contact and queues it. Returns the stored own bundle. */
    fun send(contactNode: NodeId, message: BundleMessage, ttlHours: Int = StoreForwardFormat.DEFAULT_TTL_HOURS): StoredBundle {
        val contact = requireNotNull(contacts.get(contactNode)) { "Unknown contact" }
        require(contact.state == ContactState.ACCEPTED) { "Accept this contact before messaging it" }
        if (contact.keysChanged) throw MessagingV2KeysChangedException("The contact's keys changed: compare the new safety number first")
        val sealed = StoreForwardSeal.seal(
            message, mailbox, nodeId, contact.card.mailboxKey, contactNode, pairSecret(contact), clock(), ttlHours, random,
        )
        val stored = store.putOwn(sealed, contactNode, limits)
        if (message.kind != MessageKind.RECEIPT) {
            try {
                archive.save(
                    ArchivedMessage(stored.idHex, MessageDirection.OUT, contactNode.toString(), clock(), sealed.header.expiryHour, message),
                    limits.archiveQuotaBytes,
                )
            } catch (error: Exception) {
                store.remove(stored.idHex, null)
                throw error
            }
        }
        return stored
    }

    /**
     * Daily tags of every ACCEPTED contact for the days a live bundle can have been created in, so a
     * recipient matches carrier-visible tags locally (PLAN section 2). Pending cards are left out:
     * their bundles are not stored until the user accepts them (N1). A multimap, so a tag shared by
     * two contacts can never hide one of them (N2).
     */
    fun tagTable(): Map<String, Set<NodeId>> {
        val today = StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(clock()))
        val days = (today - StoreForwardFormat.MAX_TTL_HOURS / 24 - 1)..(today + 1)
        val table = HashMap<String, MutableSet<NodeId>>()
        contacts.accepted().forEach { contact ->
            val secret = pairSecret(contact)
            days.forEach { day -> table.getOrPut(StoreForwardTags.tag(secret, day).sfHex()) { HashSet() } += contact.nodeId }
        }
        return table
    }

    /**
     * Bundles this phone offers [peer] in a SUMMARY, by class, then smallest, then oldest. Own
     * bundles go only to their recipient (the authenticated link identity). A peer's HELLO quota is
     * self-declared, so it widens the offer only when this phone itself runs the carrier role
     * (StoreForwardLimits.carrierEnabled, hard-off in v1; finding 1).
     */
    fun offers(peer: NodeId, peerIsCarrier: Boolean, v2Mode: Boolean = false): List<StoredBundle> {
        val nowHour = StoreForwardFormat.hourOf(clock())
        val peerHex = peer.toString()
        // Messaging v2 (M1): after a key change nothing is sent to that contact until the user compares again.
        val holdOwn = v2 != null && contacts.get(peer)?.keysChanged == true
        return store.all().filter { bundle ->
            // Bulk (class 4) segments are never listed: the receiver pulls them. A v1 peer is offered version 2 bundles only.
            !bundle.header.isBulk && (v2Mode || bundle.header.version == StoreForwardFormat.BUNDLE_VERSION) &&
                bundle.complete && nowHour < bundle.storedUntilHour(limits.maxStoreHours) && when (bundle.role) {
                BundleRole.OWN -> bundle.state == BundleState.QUEUED && !(holdOwn && bundle.peerNode == peerHex) &&
                    (bundle.peerNode == peerHex || (limits.carrierEnabled && peerIsCarrier))
                BundleRole.FOREIGN -> limits.carrierEnabled && bundle.state == BundleState.STORED &&
                    bundle.fromNeighbor != peerHex
                BundleRole.INBOX -> false
            }
        }.sortedWith(compareBy({ it.header.priorityClass }, { it.header.storedBytes }, { it.header.createdHour }))
    }

    fun summaryEntry(bundle: StoredBundle) = SummaryEntry(
        bundle.header.bundleId,
        bundle.header.tags.first(),
        bundle.header.priorityClass,
        bundle.header.expiryHour,
        bundle.chunkCount,
        bundle.presentCount,
    )

    fun inventoryDigest(offered: List<StoredBundle>): ByteArray =
        sfSha256(offered.sortedBy { it.idHex }.fold(ByteArray(0)) { acc, b -> acc + b.header.bundleId + b.bitmap() }).copyOf(8)

    /**
     * Hop-local admission of one SUMMARY entry from the authenticated adjacent [peer].
     * [sessionReserved] is what this session has already requested from that peer. Admitting a new
     * bundle charges only the neighbour's pre-authentication allowance; the sending contact's daily
     * count is charged after the HEADER's key slot authenticates that contact (finding 3).
     */
    fun decide(
        entry: SummaryEntry,
        peer: NodeId,
        sessionReserved: Long,
        tags: Map<String, Set<NodeId>>,
        tier: AdmissionTier = tierFor(peer, false),
    ): StoreForwardAdmission {
        val nowHour = StoreForwardFormat.hourOf(clock())
        if (entry.chunkCount !in 1..StoreForwardFormat.MAX_CHUNKS) return StoreForwardAdmission.Skip(StoreForwardRefusal.TOO_LARGE)
        if (entry.priorityClass !in 0..3) return StoreForwardAdmission.Skip(StoreForwardRefusal.INVALID)
        if (entry.expiryHour <= nowHour - limits.skewHours) return StoreForwardAdmission.Skip(StoreForwardRefusal.EXPIRED)
        val idHex = entry.idHex
        store.get(idHex)?.let { existing ->
            if (existing.complete) return StoreForwardAdmission.Skip(StoreForwardRefusal.HAVE)
            val missing = ChunkBitmap.full(existing.chunkCount).apply { andNot(existing.present) }
            val resumeBytes = missing.cardinality().toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES
            admitBytes(existing.role, emptySet(), peer, resumeBytes, sessionReserved, entry.tag, newBundle = false, tier)?.let { return it }
            if (tier.v2 && existing.role == BundleRole.INBOX) v2?.reserve(existing.idHex, peer, resumeBytes, bulk = false)
            return StoreForwardAdmission.Want(existing.role, emptySet(), false, missing)
        }
        if (store.isSeen(idHex)) {
            // Answer DELIVERED_LOCAL only to the contact the delivered bundle's own tag belongs to, so
            // the reply is not a recipient oracle for anyone who saw the ID (finding 9).
            val tag = store.deliveredTag(idHex)
            return if (tag != null && tag == entry.tag.sfHex() && tags[tag]?.contains(peer) == true) {
                StoreForwardAdmission.AlreadyDelivered(entry.bundleId)
            } else {
                StoreForwardAdmission.Skip(StoreForwardRefusal.HAVE)
            }
        }
        val candidates = tags[entry.tag.sfHex()].orEmpty()
        val role = when {
            candidates.isNotEmpty() -> BundleRole.INBOX
            limits.carrierEnabled -> BundleRole.FOREIGN
            else -> return StoreForwardAdmission.Skip(StoreForwardRefusal.NOT_FOR_ME)
        }
        admitBytes(role, candidates, peer, entry.maxBytes, sessionReserved, entry.tag, newBundle = true, tier)?.let { return it }
        if (tier.v2 && role == BundleRole.INBOX) {
            // Messaging v2: physical reservations, global ingress and metadata caps (M12a) before anything is accepted.
            v2?.resourceRefusal(peer, entry.maxBytes, isBulk = false, contact = tier.contact)?.let {
                return StoreForwardAdmission.Skip(it)
            }
            v2?.reserve(entry.idHex, peer, entry.maxBytes, bulk = false)
        }
        // The neighbour's pre-authentication allowance is charged now, so one session cannot exceed it.
        ledger.add(NeighborLedger.neighborKey(peer), bundlesIn = 1)
        return StoreForwardAdmission.Want(role, candidates, true, ChunkBitmap.full(entry.chunkCount))
    }

    /**
     * Admission of a segment the receiver itself pulls (Messaging v2): a synthesized entry for a class 4 bundle listed by an
     * accepted manifest of an approved download from [peer]. Bulk is charged by bytes, not as a message.
     */
    fun decidePull(entry: SummaryEntry, peer: NodeId, sessionReserved: Long, tier: AdmissionTier): StoreForwardAdmission {
        val engine = v2 ?: return StoreForwardAdmission.Skip(StoreForwardRefusal.INVALID)
        val nowHour = StoreForwardFormat.hourOf(clock())
        val (record, index) = engine.expectedSegment(entry.idHex, peer) ?: return StoreForwardAdmission.Skip(StoreForwardRefusal.NOT_FOR_ME)
        if (entry.chunkCount != record.segmentChunks[index] || entry.priorityClass != StoreForwardFormat.BULK_CLASS) {
            return StoreForwardAdmission.Skip(StoreForwardRefusal.INVALID)
        }
        if (entry.expiryHour <= nowHour - limits.skewHours) return StoreForwardAdmission.Skip(StoreForwardRefusal.EXPIRED)
        store.get(entry.idHex)?.let { existing ->
            if (existing.complete) return StoreForwardAdmission.Skip(StoreForwardRefusal.HAVE)
            val missing = ChunkBitmap.full(existing.chunkCount).apply { andNot(existing.present) }
            val bytes = missing.cardinality().toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES
            admitBytes(BundleRole.INBOX, setOf(peer), peer, bytes, sessionReserved, entry.tag, newBundle = false, tier)?.let { return it }
            engine.reserve(entry.idHex, peer, bytes, bulk = true)
            return StoreForwardAdmission.Want(BundleRole.INBOX, setOf(peer), false, missing)
        }
        if (store.isSeen(entry.idHex)) return StoreForwardAdmission.Skip(StoreForwardRefusal.HAVE)
        admitBytes(BundleRole.INBOX, setOf(peer), peer, entry.maxBytes, sessionReserved, entry.tag, newBundle = false, tier)?.let { return it }
        engine.resourceRefusal(peer, entry.maxBytes, isBulk = true, contact = tier.contact)?.let { return StoreForwardAdmission.Skip(it) }
        engine.reserve(entry.idHex, peer, entry.maxBytes, bulk = true)
        return StoreForwardAdmission.Want(BundleRole.INBOX, setOf(peer), true, ChunkBitmap.full(entry.chunkCount))
    }

    /** Null when within every applicable quota; otherwise the refusal. */
    private fun admitBytes(
        role: BundleRole,
        candidates: Set<NodeId>,
        peer: NodeId,
        bytes: Long,
        sessionReserved: Long,
        tag: ByteArray,
        newBundle: Boolean,
        tier: AdmissionTier,
    ): StoreForwardAdmission.Skip? {
        val neighbor = ledger.today(NeighborLedger.neighborKey(peer))
        if (sessionReserved + bytes > tier.sessionBytes) return StoreForwardAdmission.Skip(StoreForwardRefusal.QUOTA)
        if (neighbor.bytesIn + sessionReserved + bytes > tier.dailyBytes) {
            return StoreForwardAdmission.Skip(StoreForwardRefusal.RATE)
        }
        if (newBundle && neighbor.bundlesIn >= tier.dailyBundles) return StoreForwardAdmission.Skip(StoreForwardRefusal.RATE)
        when (role) {
            BundleRole.INBOX -> {
                // Read-only pre-check; the allowance is charged after the header authenticates.
                if (newBundle && candidates.isNotEmpty() && candidates.all {
                        ledger.today(NeighborLedger.senderKey(it.toString())).bundlesIn >= tier.senderDailyBundles
                    }
                ) return StoreForwardAdmission.Skip(StoreForwardRefusal.RATE)
                if (newBundle && store.usage(BundleRole.INBOX) + archive.bytes(MessageDirection.IN) + bytes > limits.inboxQuotaBytes) {
                    return StoreForwardAdmission.Skip(StoreForwardRefusal.QUOTA)
                }
            }
            BundleRole.FOREIGN -> if (newBundle) {
                val perNeighbor = (limits.foreignQuotaBytes * limits.foreignPerNeighborFraction).toLong()
                val perTag = (limits.foreignQuotaBytes * limits.foreignPerTagFraction).toLong()
                if (store.foreignUsageFrom(peer.toString()) + bytes > perNeighbor) return StoreForwardAdmission.Skip(StoreForwardRefusal.QUOTA)
                if (store.foreignUsageForTag(tag) + bytes > perTag) return StoreForwardAdmission.Skip(StoreForwardRefusal.QUOTA)
                if (!store.makeRoomForForeign(bytes, limits)) return StoreForwardAdmission.Skip(StoreForwardRefusal.QUOTA)
            }
            BundleRole.OWN -> return StoreForwardAdmission.Skip(StoreForwardRefusal.HAVE)
        }
        return null
    }

    /**
     * Validates a HEADER against the SUMMARY entry it answers, then starts (or resumes) storage. For
     * a new inbox bundle the key slot must authenticate one of [tagContacts] (no chunks needed) before the
     * sending contact's daily count is checked and charged; otherwise [StoreForwardRefused].
     */
    fun beginReceive(
        header: BundleHeader,
        entry: SummaryEntry,
        role: BundleRole,
        peer: NodeId,
        tagContacts: Set<NodeId> = emptySet(),
        tier: AdmissionTier = tierFor(peer, false),
    ): StoredBundle {
        val nowHour = StoreForwardFormat.hourOf(clock())
        require(header.bundleId.contentEquals(entry.bundleId)) { "Header does not match the requested bundle" }
        require(header.priorityClass == entry.priorityClass && header.expiryHour == entry.expiryHour &&
            header.chunkCount == entry.chunkCount) { "Header does not match its summary" }
        require(header.createdHour <= nowHour + limits.skewHours) { "Bundle was created in the future" }
        require(header.expiryHour > nowHour - limits.skewHours) { "Bundle has expired" }
        if (header.isBulk) {
            authorizeBulk(header, peer)
            return store.beginReceive(header, BundleRole.INBOX, peer.toString())
        }
        require(header.tags.any { it.contentEquals(entry.tag) }) { "Header does not carry the summarized tag" }
        if (role == BundleRole.INBOX && store.get(header.bundleId.sfHex()) == null) authorizeInbox(header, tagContacts, tier)
        return store.beginReceive(header, role, peer.toString())
    }

    /**
     * A class 4 header has no key slot to authenticate: it is admitted only as a segment listed by the accepted manifest of
     * an approved download from this very peer, and its tag must be the pair's tag for its day.
     */
    private fun authorizeBulk(header: BundleHeader, peer: NodeId) {
        val engine = v2 ?: throw StoreForwardRefused(StoreForwardRefusal.INVALID, "Messaging v2 is off")
        val idHex = header.bundleId.sfHex()
        if (engine.expectedSegment(idHex, peer) == null) {
            throw StoreForwardRefused(StoreForwardRefusal.NOT_FOR_ME, "Segment is not listed by an approved download")
        }
        val contact = contacts.get(peer)?.takeIf { it.state == ContactState.ACCEPTED }
            ?: throw StoreForwardRefused(StoreForwardRefusal.NOT_FOR_ME, "Segments come from accepted contacts only")
        val expected = StoreForwardTags.tag(pairSecret(contact), header.createdDay)
        if (!expected.contentEquals(header.bulkTag)) throw StoreForwardRefused(StoreForwardRefusal.INVALID, "Segment tag is not the pair's tag")
    }

    private fun authorizeInbox(header: BundleHeader, tagContacts: Set<NodeId>, tier: AdmissionTier) {
        val slot = try {
            StoreForwardSeal.authenticate(header, mailbox, nodeId, contacts::isKnownSender)
        } catch (error: IllegalArgumentException) {
            throw StoreForwardRefused(StoreForwardRefusal.INVALID, "Key slot does not authenticate a contact")
        }
        slot.contentKey.fill(0)
        if (tagContacts.isNotEmpty() && slot.senderNodeId !in tagContacts) {
            throw StoreForwardRefused(StoreForwardRefusal.INVALID, "Sender does not own the bundle's tag")
        }
        val key = NeighborLedger.senderKey(slot.senderNodeId.toString())
        if (ledger.today(key).bundlesIn >= tier.senderDailyBundles) {
            throw StoreForwardRefused(StoreForwardRefusal.RATE, "Sender's daily message allowance is used")
        }
        ledger.add(key, bundlesIn = 1)
    }

    /** A bundle became complete in a session with [peer]. */
    fun onComplete(bundle: StoredBundle, peer: NodeId): StoreForwardCompletion {
        return when (bundle.role) {
            BundleRole.FOREIGN -> {
                store.update(bundle.idHex, BundleState.STORED)
                StoreForwardCompletion.Carried
            }
            BundleRole.INBOX -> openInbox(bundle)
            BundleRole.OWN -> StoreForwardCompletion.Carried
        }
    }

    /**
     * Opens a complete inbox bundle (also called when a pending contact is accepted or an earlier
     * open was deferred). Crash-safe order (finding 5): the opened message is stored durably first;
     * only then is the ID tombstoned and marked delivered and the ciphertext deleted. If storing
     * fails the ciphertext stays (UNOPENED) and delivery is not acknowledged.
     */
    fun openInbox(bundle: StoredBundle): StoreForwardCompletion {
        if (bundle.header.version == StoreForwardFormat.BUNDLE_VERSION_V3) {
            return v2?.openInbox(bundle) ?: StoreForwardCompletion.Deferred("Messaging v2 is off")
        }
        val tombstone = limits.tombstoneUntilHour(bundle.header.expiryHour)
        val opened = try {
            StoreForwardSeal.open(bundle.header, store.chunks(bundle.idHex), mailbox, nodeId, contacts::isKnownSender)
        } catch (error: Exception) {
            store.remove(bundle.idHex, tombstone)
            return StoreForwardCompletion.Rejected(error.message ?: error.javaClass.simpleName)
        }
        val sender = requireNotNull(contacts.get(opened.senderNodeId))
        if (sender.state != ContactState.ACCEPTED) {
            store.update(bundle.idHex, BundleState.PENDING_CONTACT, sender.nodeId.toString())
            return StoreForwardCompletion.PendingContact
        }
        val message = opened.message
        val completion = if (message.kind == MessageKind.RECEIPT) {
            val target = message.replyTo?.sfHex()
            val own = target?.let(store::get)
            if (own != null && own.role == BundleRole.OWN && own.peerNode == sender.nodeId.toString()) {
                store.update(own.idHex, BundleState.DELIVERED)
            }
            StoreForwardCompletion.ReceiptOpened(target.orEmpty())
        } else {
            val firstOpen = !archive.contains(bundle.idHex)
            val archived = ArchivedMessage(
                bundle.idHex, MessageDirection.IN, sender.nodeId.toString(), clock(), bundle.header.expiryHour, message,
            )
            try {
                archive.save(archived, limits.archiveQuotaBytes)
            } catch (error: Exception) {
                store.update(bundle.idHex, BundleState.UNOPENED, sender.nodeId.toString())
                return StoreForwardCompletion.Deferred(error.message ?: error.javaClass.simpleName)
            }
            if (receiptsEnabled && firstOpen) {
                runCatching {
                    send(sender.nodeId, BundleMessage.receipt(bundle.header.bundleId, StoreForwardFormat.hourOf(clock()), clock()))
                }
            }
            StoreForwardCompletion.Opened(archived)
        }
        store.markSeen(bundle.idHex, tombstone)
        store.markDelivered(bundle.idHex, bundle.header.tags.first().sfHex())
        store.remove(bundle.idHex, null)
        return completion
    }

    /** Retries complete inbox bundles whose open was deferred or interrupted by a crash. */
    fun retryUnopened(): List<StoreForwardCompletion> = store.all().filter {
        it.role == BundleRole.INBOX && it.complete && (it.state == BundleState.UNOPENED || it.state == BundleState.RECEIVING)
    }.map { bundle -> runCatching { openInbox(bundle) }.getOrElse { StoreForwardCompletion.Deferred(it.message ?: "error") } }

    /**
     * Deletes an opened message at the user's request. The bundle's tombstone stays until expiry plus
     * the acceptance grace, so a replayed copy is never reopened (finding 10).
     */
    fun deleteMessage(bundleIdHex: String) {
        val entry = archive.load(bundleIdHex)
        entry?.let { store.markSeen(bundleIdHex, limits.tombstoneUntilHour(it.expiryHour)) }
        // Deleting an outgoing message withdraws it: it is no longer stored or offered (N6).
        store.get(bundleIdHex)?.takeIf { it.role == BundleRole.OWN }?.let { store.remove(it.idHex, null) }
        archive.delete(bundleIdHex)
    }

    /**
     * Removes a contact (Ignore on a pending card, or pruning) together with any inbox bundles held
     * for it, so throwaway identities leave nothing behind (N1).
     */
    fun removeContact(node: NodeId): Boolean {
        val removed = contacts.remove(node)
        store.all().filter { it.role == BundleRole.INBOX && it.peerNode == node.toString() && it.state != BundleState.RECEIVING }
            .forEach { store.remove(it.idHex, limits.tombstoneUntilHour(it.header.expiryHour)) }
        return removed
    }

    /** Responder-side exchange admission for [peer]: per-neighbour cooldown and hourly cap (N3). */
    fun admitExchange(peer: NodeId): Boolean =
        ledger.admitExchange(NeighborLedger.neighborKey(peer), limits.exchangeCooldownMs, limits.maxExchangesPerHour)

    /** Gives back the cooldown of an admitted exchange that moved nothing ([NeighborLedger.refundCooldown]); the hourly count stays. */
    fun refundExchangeCooldown(peer: NodeId) = ledger.refundCooldown(NeighborLedger.neighborKey(peer))

    /**
     * Messaging v2 (M6): admission after the peer's HELLO was read. An accepted contact whose HELLO set the chat flag gets the
     * relaxed 5 s and 60 per hour; every other adjacent phone keeps the v1 60 s and 10 per hour.
     */
    fun admitExchangeAfterHello(peer: NodeId, contactChat: Boolean): Boolean {
        val l = v2?.limits
        return if (contactChat && l != null) {
            ledger.admitExchange(NeighborLedger.neighborKey(peer), l.contactExchangeCooldownMs, l.contactMaxExchangesPerHour)
        } else {
            admitExchange(peer)
        }
    }

    /** Opens bundles that waited for [contact] to be accepted. */
    fun openPendingFrom(contact: NodeId): List<StoreForwardCompletion> =
        store.all().filter { it.role == BundleRole.INBOX && it.state == BundleState.PENDING_CONTACT && it.peerNode == contact.toString() }
            .map(::openInbox)

    /** DELIVERED_LOCAL from [peer]: an own bundle reached its recipient's phone. Foreign: ignored (QA5, v3). */
    fun onDeliveredLocal(bundleId: ByteArray, peer: NodeId): Boolean {
        val bundle = store.get(bundleId.sfHex()) ?: return false
        if (bundle.role != BundleRole.OWN || bundle.peerNode != peer.toString()) return false
        if (bundle.state == BundleState.QUEUED) store.update(bundle.idHex, BundleState.HANDED)
        v2?.onDelivered(bundle.idHex, peer)
        return true
    }

    /** Deletes opened messages whose lifetime ended (safe to call from the UI at any time). */
    fun sweepArchive() = archive.sweep(StoreForwardFormat.hourOf(clock()))

    /** Expires bundles, tombstones and opened messages, then retries deferred opens. */
    fun sweep() {
        store.sweep(limits)
        archive.sweep(StoreForwardFormat.hourOf(clock()))
        contacts.prunePending(limits.pendingCardMaxAgeMs).forEach(::removeContact)
        // Inbox bundles held for a card that is no longer pending (ignored, pruned or evicted).
        store.all().filter { bundle ->
            bundle.role == BundleRole.INBOX && bundle.state == BundleState.PENDING_CONTACT &&
                bundle.peerNode?.let { peer -> contacts.all().none { it.nodeId.toString() == peer } } != false
        }.forEach { store.remove(it.idHex, limits.tombstoneUntilHour(it.header.expiryHour)) }
        ledger.prune()
        v2?.sweep()
        reconcileContacts()
        retryUnopened()
    }

    companion object {
        fun open(
            root: File,
            identity: SigningIdentity,
            mailbox: MailboxKey,
            cipher: AtRestCipher,
            limits: StoreForwardLimits = StoreForwardLimits(),
            clock: () -> Long = System::currentTimeMillis,
            messagingV2: MessagingV2Setup? = null,
            verifiedDirectory: VerifiedIdentityDirectory? = null,
        ): StoreForwardNode = StoreForwardNode(
            identity.nodeId,
            identity,
            mailbox,
            BundleStore(File(root, "store"), clock, cipher),
            ContactBook(File(root, "contacts.properties"), clock, limits.maxPendingCards, cipher),
            MessageArchive(File(root, "messages"), cipher),
            NeighborLedger(File(root, "neighbors.properties"), clock, cipher, limits.maxNeighborRecords),
            limits,
            clock,
            messagingV2 = messagingV2,
            root = root,
            cipher = cipher,
        ).also { it.verifiedDirectory = verifiedDirectory }
    }
}
