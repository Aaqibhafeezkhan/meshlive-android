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
}

/**
 * Store-and-forward state of this phone: its mailbox key, contacts, bundle store, ledger and
 * archive. Every decision uses only local state and the one authenticated adjacent identity of
 * the current link (hop-local, PLAN Design A section 10).
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
) {
    private val pairSecrets = HashMap<String, ByteArray>()
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
    fun offers(peer: NodeId, peerIsCarrier: Boolean): List<StoredBundle> {
        val nowHour = StoreForwardFormat.hourOf(clock())
        val peerHex = peer.toString()
        return store.all().filter { bundle ->
            bundle.complete && nowHour < bundle.storedUntilHour(limits.maxStoreHours) && when (bundle.role) {
                BundleRole.OWN -> bundle.state == BundleState.QUEUED &&
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
    fun decide(entry: SummaryEntry, peer: NodeId, sessionReserved: Long, tags: Map<String, Set<NodeId>>): StoreForwardAdmission {
        val nowHour = StoreForwardFormat.hourOf(clock())
        if (entry.chunkCount !in 1..StoreForwardFormat.MAX_CHUNKS) return StoreForwardAdmission.Skip(StoreForwardRefusal.TOO_LARGE)
        if (entry.priorityClass !in 0..3) return StoreForwardAdmission.Skip(StoreForwardRefusal.INVALID)
        if (entry.expiryHour <= nowHour - limits.skewHours) return StoreForwardAdmission.Skip(StoreForwardRefusal.EXPIRED)
        val idHex = entry.idHex
        store.get(idHex)?.let { existing ->
            if (existing.complete) return StoreForwardAdmission.Skip(StoreForwardRefusal.HAVE)
            val missing = ChunkBitmap.full(existing.chunkCount).apply { andNot(existing.present) }
            return admitBytes(existing.role, emptySet(), peer,
                missing.cardinality().toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES, sessionReserved, entry.tag, newBundle = false)
                ?: StoreForwardAdmission.Want(existing.role, emptySet(), false, missing)
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
        admitBytes(role, candidates, peer, entry.maxBytes, sessionReserved, entry.tag, newBundle = true)?.let { return it }
        // The neighbour's pre-authentication allowance is charged now, so one session cannot exceed it.
        ledger.add(NeighborLedger.neighborKey(peer), bundlesIn = 1)
        return StoreForwardAdmission.Want(role, candidates, true, ChunkBitmap.full(entry.chunkCount))
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
    ): StoreForwardAdmission.Skip? {
        val neighbor = ledger.today(NeighborLedger.neighborKey(peer))
        if (sessionReserved + bytes > limits.sessionBytes) return StoreForwardAdmission.Skip(StoreForwardRefusal.QUOTA)
        if (neighbor.bytesIn + sessionReserved + bytes > limits.neighborDailyBytes) {
            return StoreForwardAdmission.Skip(StoreForwardRefusal.RATE)
        }
        if (newBundle && neighbor.bundlesIn >= limits.neighborDailyBundles) return StoreForwardAdmission.Skip(StoreForwardRefusal.RATE)
        when (role) {
            BundleRole.INBOX -> {
                // Read-only pre-check; the allowance is charged after the header authenticates.
                if (newBundle && candidates.isNotEmpty() && candidates.all {
                        ledger.today(NeighborLedger.senderKey(it.toString())).bundlesIn >= limits.senderDailyBundles
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
    ): StoredBundle {
        val nowHour = StoreForwardFormat.hourOf(clock())
        require(header.bundleId.contentEquals(entry.bundleId)) { "Header does not match the requested bundle" }
        require(header.priorityClass == entry.priorityClass && header.expiryHour == entry.expiryHour &&
            header.chunkCount == entry.chunkCount) { "Header does not match its summary" }
        require(header.tags.any { it.contentEquals(entry.tag) }) { "Header does not carry the summarized tag" }
        require(header.createdHour <= nowHour + limits.skewHours) { "Bundle was created in the future" }
        require(header.expiryHour > nowHour - limits.skewHours) { "Bundle has expired" }
        if (role == BundleRole.INBOX && store.get(header.bundleId.sfHex()) == null) authorizeInbox(header, tagContacts)
        return store.beginReceive(header, role, peer.toString())
    }

    private fun authorizeInbox(header: BundleHeader, tagContacts: Set<NodeId>) {
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
        if (ledger.today(key).bundlesIn >= limits.senderDailyBundles) {
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

    /** Opens bundles that waited for [contact] to be accepted. */
    fun openPendingFrom(contact: NodeId): List<StoreForwardCompletion> =
        store.all().filter { it.role == BundleRole.INBOX && it.state == BundleState.PENDING_CONTACT && it.peerNode == contact.toString() }
            .map(::openInbox)

    /** DELIVERED_LOCAL from [peer]: an own bundle reached its recipient's phone. Foreign: ignored (QA5, v3). */
    fun onDeliveredLocal(bundleId: ByteArray, peer: NodeId): Boolean {
        val bundle = store.get(bundleId.sfHex()) ?: return false
        if (bundle.role != BundleRole.OWN || bundle.peerNode != peer.toString()) return false
        if (bundle.state == BundleState.QUEUED) store.update(bundle.idHex, BundleState.HANDED)
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
        )
    }
}
