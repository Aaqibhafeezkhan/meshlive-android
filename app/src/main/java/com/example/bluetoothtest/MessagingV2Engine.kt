package com.example.bluetoothtest

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/** A send or approval was refused by a local limit (queue, vault, free space): nothing was stored. */
class MessagingV2QuotaException(message: String) : IllegalStateException(message)

/** A `WANT` the receiver should send for a segment of an approved download (a synthesized admission entry). */
class PullRequest(val transferId: String, val segmentIndex: Int, val entry: SummaryEntry)

/**
 * Messaging v2 on top of the v1 store-and-forward node (DECISIONS Design M, PROTOCOL "Messaging v2"), phase 1a: sending
 * text and files (inline or segmented), opening and validating BSM2 content, the receiver-pull file transfer, the file
 * vault, receipts and the physical-accounting rules. It owns no radio logic; [StoreForwardLinkSession] asks it for
 * admission decisions and completions. Exists only when the master switch is on ([StoreForwardNode.v2] is null otherwise,
 * and the v1 paths are untouched).
 */
class MessagingV2Engine(
    private val node: StoreForwardNode,
    root: File,
    internal val cipher: AtRestCipher,
    val limits: MessagingV2Limits,
    private val clock: () -> Long,
    private val random: SecureRandom,
    /** Free bytes on the device's storage (the floor in [MessagingV2Limits] is checked against it). */
    private val freeSpace: () -> Long,
) {
    private val dir = File(root, "v2").apply { mkdirs() }

    /** The engine's private storage directory (the migration keeps its map here). */
    internal val directory: File get() = dir
    val threads = ThreadStore(File(dir, "threads"), cipher, limits.maxArchiveRows, node.limits.archiveQuotaBytes)
    val vault = FileVault(File(dir, "vault"), cipher, random)
    val transfers = TransferBook(File(dir, "transfers.properties"), cipher)
    val keys = TransferKeys(File(dir, "transfer-keys.properties"), cipher)
    val seenIds = SeenMessageIds(File(dir, "seen-ids.properties"), cipher, limits.maxSeenEntries)
    val receiptOutbox = ReceiptOutbox(File(dir, "receipts-out.properties"), cipher)
    val ingress = GlobalIngress(File(dir, "ingress.properties"), clock, cipher, limits)

    /** False while a call or stream runs: segmented files then wait for a tap or the next quiet moment. */
    @Volatile var autoDownloadAllowed: () -> Boolean = { true }

    /** The user's read-receipt switch (also needs the sender to have asked, M4). A contact's own choice ([threadSettings]) wins. */
    @Volatile var readReceipts: Boolean = limits.readReceipts

    /** Largest segmented file fetched without a tap; starts at the limit and follows the Settings row (phase 1c). */
    @Volatile var autoDownloadMaxBytes: Long = limits.autoDownloadMaxBytes

    /** Per-conversation choices of the thread screen: disappearing timer and read receipts (phase 1c). */
    val threadSettings = ThreadSettings(File(dir, "thread-settings.properties"), cipher)

    /** Read receipts for [peer]: its own choice, else the global switch. */
    fun readReceiptsFor(peer: NodeId): Boolean = threadSettings.readReceipts(peer.toString()) ?: readReceipts

    private val legacyPeers = ConcurrentHashMap<String, Long>()
    /** Outgoing files still being sealed in this process: [sweep] must not treat them as crash leftovers. */
    private val preparing = ConcurrentHashMap.newKeySet<String>()

    // ---- peers --------------------------------------------------------------------------------------------------

    /** A peer answered our v2 HELLO with DONE(error): it is older. Not tried as v2 again for an hour. */
    fun markLegacy(peer: NodeId) {
        legacyPeers[peer.toString()] = clock() + limits.legacyPeerMs
    }

    fun isLegacy(peer: NodeId): Boolean {
        val until = legacyPeers[peer.toString()] ?: return false
        if (clock() >= until) {
            legacyPeers.remove(peer.toString())
            return false
        }
        return true
    }

    private fun requireContact(node: NodeId): StoreForwardContact {
        val contact = requireNotNull(this.node.contacts.get(node)) { "Unknown contact" }
        require(contact.state == ContactState.ACCEPTED) { "Accept this contact before messaging it" }
        // M1: after a key change nothing is sent until the user compares the new safety number.
        if (contact.keysChanged) throw MessagingV2KeysChangedException("The contact's keys changed: compare the new safety number first")
        return contact
    }

    fun isContact(peer: NodeId): Boolean = node.contacts.get(peer)?.state == ContactState.ACCEPTED

    // ---- sending ------------------------------------------------------------------------------------------------

    private fun newId(): ByteArray = ByteArray(16).also(random::nextBytes)

    private fun wantReadFlag(peer: NodeId): Int = if (readReceiptsFor(peer)) MessagingV2Format.WANT_READ_RECEIPT else 0

    /** Seals [inner] to [contact] as a version 3 bundle and queues it, honouring the own-queue limits. */
    private fun queue(contact: StoreForwardContact, inner: ByteArray, priorityClass: Int, ttlHours: Int, nowMs: Long): StoredBundle {
        val sealed = StoreForwardSeal.sealV3(
            inner, priorityClass, node.mailbox, node.nodeId, contact.card.mailboxKey, contact.nodeId,
            node.pairSecret(contact), nowMs, ttlHours, random,
        )
        return putOwnChecked(contact.nodeId, sealed)
    }

    private fun putOwnChecked(contact: NodeId, sealed: SealedBundle): StoredBundle {
        val own = node.store.all().filter { it.role == BundleRole.OWN }
        val forContact = own.filter { it.peerNode == contact.toString() }.sumOf { it.header.storedBytes }
        if (forContact + sealed.header.storedBytes > limits.ownQueueBytesPerContact) {
            throw MessagingV2QuotaException("Too many unsent bytes for this contact")
        }
        if (own.sumOf { it.header.storedBytes } + sealed.header.storedBytes > limits.ownQueueBytesTotal) {
            throw MessagingV2QuotaException("Outgoing message storage is full")
        }
        return node.store.putOwn(sealed, contact, node.limits, limits.ownQueueBytesTotal)
    }

    private fun checkWaiting(contact: NodeId) {
        if (threads.waitingFor(contact.toString()) >= limits.waitingPerContact) {
            throw MessagingV2QuotaException("Too many waiting messages for this contact")
        }
    }

    fun sendText(
        contact: NodeId,
        text: String,
        replyTo: ByteArray? = null,
        disappearSeconds: Int = 0,
        ttlHours: Int = MessagingV2Format.TEXT_TTL_HOURS,
    ): ThreadMessage {
        val body = text.toByteArray(Charsets.UTF_8)
        require(body.size in 1..MessagingV2Format.TEXT_MAX_BYTES) { "A text message is 1 to 8000 bytes" }
        val peer = requireContact(contact)
        checkWaiting(contact)
        val now = clock()
        val message = MessageV2(
            V2Kind.TEXT, wantReadFlag(contact), now, newId(), ConversationIds.of(node.nodeId, contact), replyTo, disappearSeconds, body,
        )
        val bundle = queue(peer, message.encode(), 0, ttlHours, now)
        val record = ThreadMessage(
            MessageDirection.OUT, contact.toString(), message.messageIdHex, V2Kind.TEXT, now, now, message.flags, body, null,
            disappearSeconds, replyTo?.sfHex(), mutableListOf(bundle.idHex),
        )
        try {
            threads.save(record)
        } catch (error: Exception) {
            node.store.remove(bundle.idHex, null)
            throw error
        }
        node.notifyNewBundles()
        return record
    }

    /**
     * Sends a file of [size] bytes read from [source] (called once). Up to 1,000,000 bytes it travels inline in its manifest
     * bundle; a larger file is cut into 1,044,480-byte class 4 segments sealed under keys derived from a random file key,
     * then the manifest (class 0) listing them is sealed last. All pieces share one timestamp, so they share their
     * created and expiry hours. A copy goes to the vault (the sender's own file).
     */
    fun sendFile(
        contact: NodeId,
        source: () -> InputStream,
        size: Long,
        presentation: Presentation = Presentation.FILE,
        name: String = "file",
        mime: String = MessagingV2Format.DEFAULT_MIME,
        width: Int = 0,
        height: Int = 0,
        durationMs: Long = 0,
        thumbnail: ByteArray = ByteArray(0),
        disappearSeconds: Int = 0,
        ttlHours: Int? = null,
        replyTo: ByteArray? = null,
    ): ThreadMessage {
        require(size in 1..MessagingV2Format.MAX_FILE_BYTES) { "A file is 1 byte to 64 MB" }
        val peer = requireContact(contact)
        checkWaiting(contact)
        val cleanName = V2Text.sanitizeName(name)
        val cleanMime = V2Text.sanitizeMime(mime.toByteArray(Charsets.US_ASCII))
        val goodThumbnail = if (JpegHeader.acceptableThumbnail(thumbnail)) thumbnail else ByteArray(0)
        val inline = size <= MessagingV2Format.INLINE_MAX_BYTES
        val ttl = ttlHours ?: if (inline) MessagingV2Format.TEXT_TTL_HOURS else MessagingV2Format.SEGMENTED_TTL_HOURS
        val peerHex = contact.toString()
        if (vault.usedBytes() + size > limits.vaultCapBytes) throw MessagingV2QuotaException("Storage used by messages is full")
        val now = clock()
        val fileId = newId()
        val fileKey = ByteArray(MessagingV2Format.FILE_KEY_BYTES).also(random::nextBytes)
        val vaultId = vault.create("out:$peerHex", size)
        val transferId = newId().sfHex()
        val sealedBundles = ArrayList<String>()
        var record: TransferRecord? = null
        preparing += transferId
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val inlineData: ByteArray?
            val segments = ArrayList<SegmentRef>()
            if (inline) {
                inlineData = readFully(source(), size.toInt())
                digest.update(inlineData)
                vault.writeBlocks(vaultId, 0, inlineData)
            } else {
                inlineData = null
                keys.put(transferId, fileKey)
                val count = SegmentMath.count(size)
                record = TransferRecord(
                    transferId, MessageDirection.OUT, peerHex, "", fileId.sfHex(), "", size, ByteArray(32),
                    MutableList(count) { "" }, MutableList(count) { 0 }, StoreForwardFormat.hourOf(now) + ttl, TransferState.ACTIVE,
                    BitSet(), vaultId,
                )
                // Saved before the first segment so a crash mid-way leaves a record that [sweep] cleans up.
                transfers.save(record)
                val pairTag = StoreForwardTags.tag(node.pairSecret(peer), StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(now)))
                source().use { stream ->
                    for (index in 0 until count) {
                        val length = SegmentMath.dataLength(index, size)
                        val data = readFully(stream, length)
                        digest.update(data)
                        vault.writeBlocks(vaultId, SegmentMath.firstBlock(index), data)
                        val sealed = sealSegment(record, fileKey, fileId, index, data, pairTag, now, ttl)
                        val stored = putOwnChecked(contact, sealed)
                        sealedBundles += stored.idHex
                        segments += SegmentRef(stored.header.bundleId, stored.chunkCount)
                        record.segmentIds[index] = stored.idHex
                        record.segmentChunks[index] = stored.chunkCount
                        transfers.save(record)
                        data.fill(0)
                    }
                    require(stream.read() == -1) { "The file is longer than its declared size" }
                }
            }
            val sha = digest.digest()
            val manifest = AttachmentManifest(
                fileId, presentation, size, sha, fileKey, cleanName, cleanMime, width, height, durationMs, goodThumbnail,
                inlineData, segments,
            )
            val message = MessageV2(
                V2Kind.ATTACHMENT, wantReadFlag(contact), now, newId(), ConversationIds.of(node.nodeId, contact), replyTo,
                disappearSeconds, manifest.encode(),
            )
            val priority = if (inline) presentation.inlineClass else 0
            val bundle = queue(peer, message.encode(), priority, ttl, now)
            sealedBundles += bundle.idHex
            val info = AttachmentInfo(
                fileId.sfHex(), presentation, size, sha, cleanName, cleanMime, width, height, durationMs, goodThumbnail,
                segments.size, FileState.STORED, vaultId,
            )
            val thread = ThreadMessage(
                MessageDirection.OUT, peerHex, message.messageIdHex, V2Kind.ATTACHMENT, now, now, message.flags, ByteArray(0), info,
                disappearSeconds, replyTo?.sfHex(), mutableListOf(bundle.idHex),
            )
            threads.save(thread)
            record?.let {
                it.messageIdHex = message.messageIdHex
                it.manifestBundleHex = bundle.idHex
                it.sha256 = sha
                it.expiryHour = bundle.header.expiryHour
                transfers.save(it)
            }
            node.notifyNewBundles()
            return thread
        } catch (error: Throwable) {
            sealedBundles.forEach { node.store.remove(it, null) }
            keys.erase(transferId)
            transfers.remove(transferId)
            vault.delete(vaultId)
            throw error
        } finally {
            preparing -= transferId
            fileKey.fill(0)
        }
    }

    /** A segment index is sealed once per file key: a second seal would reuse every chunk nonce. */
    internal fun sealSegment(
        record: TransferRecord,
        fileKey: ByteArray,
        fileId: ByteArray,
        index: Int,
        data: ByteArray,
        pairTag: ByteArray,
        nowMs: Long,
        ttlHours: Int,
    ): SealedBundle {
        check(!record.sealedSegments.get(index)) { "Segment $index was already sealed under this file key" }
        val body = SegmentBody(fileId, index, data).encode()
        val message = MessageV2(
            V2Kind.SEGMENT, 0, nowMs, newId(), ByteArray(16), null, 0, body,
        )
        val key = SegmentMath.segmentKey(fileKey, fileId, index)
        try {
            val sealed = StoreForwardSeal.sealBulk(message.encode(), SegmentMath.paddedLength(data.size), key, pairTag, nowMs, ttlHours)
            record.sealedSegments.set(index)
            return sealed
        } finally {
            key.fill(0)
        }
    }

    private fun readFully(stream: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = stream.read(data, offset, length - offset)
            require(n >= 0) { "The file is shorter than its declared size" }
            offset += n
        }
        return data
    }

    // ---- admission (the node's `decide` and `beginReceive` call these) ------------------------------------------

    /** A request this phone made and has not yet seen finish: the staged ciphertext plus the vault bytes it becomes. */
    private class Reservation(val bytes: Long, val owner: String, val bulk: Boolean)

    private val reservations = ConcurrentHashMap<String, Reservation>()

    /**
     * Reserves the physical bytes a `WANT` for [idHex] can occupy (design finding 12): [maxBytes] of staged ciphertext and the
     * same again in the vault. Released when the bundle is stored, refused, cancelled or the exchange ends; a restart forgets
     * all of them (their partial bundles are asked for again).
     */
    fun reserve(idHex: String, peer: NodeId, maxBytes: Long, bulk: Boolean) {
        reservations[idHex] = Reservation(maxBytes, peer.toString(), bulk)
    }

    fun release(idHex: String) {
        reservations.remove(idHex)
    }

    /** Physical bytes the transfers in flight can still occupy: staged ciphertext plus the vault bytes it becomes. */
    fun reservedBytes(): Long = reservations.values.sumOf { it.bytes * 2 }

    fun reservationCount(): Int = reservations.size

    /**
     * Local-resource refusal for a new inbound bundle of at most [bytes] ciphertext from [peer] (M12a): the free-space floor,
     * the vault cap and the contact's 25% share, bundles in flight and the metadata caps. Null = go ahead.
     */
    fun resourceRefusal(peer: NodeId, bytes: Long, isBulk: Boolean, contact: Boolean): StoreForwardRefusal? {
        if (freeSpace() - reservedBytes() - bytes * 2 < limits.freeSpaceFloorBytes) return StoreForwardRefusal.QUOTA
        if (reservations.size >= limits.maxInFlightBundles) return StoreForwardRefusal.RATE
        if (node.store.all().size >= limits.maxBundleRecords) return StoreForwardRefusal.QUOTA
        if (seenIds.size() + reservations.size >= limits.maxSeenEntries) return StoreForwardRefusal.QUOTA
        if (threads.count() + reservations.count { !it.value.bulk } >= limits.maxArchiveRows && !isBulk) return StoreForwardRefusal.QUOTA
        if (!isBulk) {
            // An inline attachment's vault bytes: the contact's share and the vault cap, counting what is in flight.
            val owner = peer.toString()
            val inflightOwn = reservations.values.filter { it.owner == owner && !it.bulk }.sumOf { it.bytes }
            val inflightAll = reservations.values.filter { !it.bulk }.sumOf { it.bytes }
            if (vault.usedBytes("in:$owner") + inflightOwn + bytes > limits.perContactVaultBytes) return StoreForwardRefusal.QUOTA
            if (vault.usedBytes() + inflightAll + bytes > limits.vaultCapBytes) return StoreForwardRefusal.QUOTA
        }
        if (ingress.remaining(contact) < bytes) return StoreForwardRefusal.RATE
        return null
    }

    /** Charges the global ingress counters for bytes that actually arrived. */
    fun onBytesReceived(peer: NodeId, bytes: Long) = ingress.add(isContact(peer), bytes)

    // ---- pulls (receiver side of a segmented file) ----------------------------------------------------------------

    /**
     * The `WANT`s to send [peer] now: for each approved download from that peer up to two missing segments (peak disk is
     * the vault file plus two staged segments), as synthesized admission entries (class 4, the manifest's expiry hour).
     */
    fun pendingPulls(peer: NodeId): List<PullRequest> {
        val nowHour = StoreForwardFormat.hourOf(clock())
        val out = ArrayList<PullRequest>()
        for (record in transfers.all()) {
            if (record.direction != MessageDirection.IN || record.peer != peer.toString() || record.state != TransferState.ACTIVE) continue
            if (nowHour >= record.expiryHour) continue
            var picked = 0
            for (index in 0 until record.segmentCount) {
                if (record.doneSegments.get(index)) continue
                if (picked >= MAX_ACTIVE_SEGMENTS) break
                val id = record.segmentIds[index]
                val held = node.store.get(id)?.presentCount ?: 0
                out += PullRequest(
                    record.id, index,
                    SummaryEntry(hexToBytes(id), ByteArray(StoreForwardFormat.TAG_BYTES), StoreForwardFormat.BULK_CLASS,
                        record.expiryHour, record.segmentChunks[index], held),
                )
                picked++
            }
        }
        return out
    }

    /** Whether [idHex] is a segment listed by an approved download from [peer] (the only way a class 4 header is admitted). */
    fun expectedSegment(idHex: String, peer: NodeId): Pair<TransferRecord, Int>? {
        val found = transfers.forSegment(MessageDirection.IN, idHex) ?: return null
        val (record, index) = found
        return if (record.peer == peer.toString() && record.state == TransferState.ACTIVE && !record.doneSegments.get(index)) found else null
    }

    /**
     * Approves the download of a segmented file: allocates its vault file (declared size reserved at once, refused if the
     * vault, the contact's share or free space cannot hold it) and starts pulling at the next exchange.
     */
    fun approveDownload(threadId: String): ThreadMessage {
        val message = requireNotNull(threads.get(threadId)) { "Unknown message" }
        val attachment = requireNotNull(message.attachment) { "Not a file" }
        val record = requireNotNull(transfers.forMessage(MessageDirection.IN, message.peerNode, message.messageIdHex)) { "No download to approve" }
        if (record.state == TransferState.ACTIVE || record.state == TransferState.DONE) return message
        require(record.state == TransferState.AWAITING_APPROVAL) { "This file cannot be downloaded" }
        if (record.vaultId == null) {
            val owner = "in:${record.peer}"
            if (vault.usedBytes() + record.totalSize > limits.vaultCapBytes ||
                vault.usedBytes(owner) + record.totalSize > limits.perContactVaultBytes
            ) throw MessagingV2QuotaException("Storage used by messages is full")
            if (freeSpace() - reservedBytes() - record.totalSize < limits.freeSpaceFloorBytes) {
                throw MessagingV2QuotaException("The phone is low on storage")
            }
            record.vaultId = vault.create(owner, record.totalSize)
        }
        record.state = TransferState.ACTIVE
        transfers.save(record)
        attachment.state = FileState.DOWNLOADING
        attachment.vaultId = record.vaultId
        threads.save(message)
        node.pullNow()
        return message
    }

    /** Stops a download. Progress stays (the vault file and segment bitmaps), so approving again resumes. */
    fun cancelDownload(threadId: String): List<ByteArray> {
        val message = threads.get(threadId) ?: return emptyList()
        val record = transfers.forMessage(MessageDirection.IN, message.peerNode, message.messageIdHex) ?: return emptyList()
        if (record.state != TransferState.ACTIVE) return emptyList()
        record.state = TransferState.AWAITING_APPROVAL
        transfers.save(record)
        message.attachment?.state = FileState.AWAITING_APPROVAL
        threads.save(message)
        // Every segment not yet complete, including ones already asked for whose header has not arrived: the sender would
        // otherwise keep serving those after the user cancelled. A CANCEL for a segment nobody asked for is ignored by the session.
        val inflight = record.segmentIds.filterIndexed { i, id -> !record.doneSegments.get(i) && id.isNotEmpty() }
        val ids = inflight.map(::hexToBytes)
        node.cancelBundles(ids)
        return ids
    }

    // ---- receiving ----------------------------------------------------------------------------------------------

    private fun tombstone(bundle: StoredBundle) = node.limits.tombstoneUntilHour(bundle.header.expiryHour)

    private fun reject(bundle: StoredBundle, reason: String): StoreForwardCompletion {
        release(bundle.idHex)
        node.store.remove(bundle.idHex, tombstone(bundle))
        return StoreForwardCompletion.Rejected(reason)
    }

    private fun finish(bundle: StoredBundle) {
        release(bundle.idHex)
        node.store.markSeen(bundle.idHex, tombstone(bundle))
        node.store.markDelivered(bundle.idHex, bundle.header.tags.first().sfHex())
        node.store.remove(bundle.idHex, null)
    }

    /** Opens a complete inbound version 3 bundle (slot bundle or segment). Idempotent: a crash-retry finds its own work done. */
    fun openInbox(bundle: StoredBundle): StoreForwardCompletion {
        if (bundle.header.isBulk) return openSegment(bundle)
        val raw = try {
            StoreForwardSeal.openRaw(bundle.header, node.store.chunks(bundle.idHex), node.mailbox, node.nodeId, node.contacts::isKnownSender)
        } catch (error: Exception) {
            return reject(bundle, error.message ?: error.javaClass.simpleName)
        }
        val sender = requireNotNull(node.contacts.get(raw.senderNodeId))
        if (sender.state != ContactState.ACCEPTED) {
            node.store.update(bundle.idHex, BundleState.PENDING_CONTACT, sender.nodeId.toString())
            return StoreForwardCompletion.PendingContact
        }
        val message = try {
            MessageV2.decode(raw.inner)
        } catch (error: IllegalArgumentException) {
            return reject(bundle, error.message ?: "Invalid BSM2")
        }
        if (!message.conversationId.contentEquals(ConversationIds.of(node.nodeId, sender.nodeId))) {
            return reject(bundle, "Conversation ID does not belong to this contact")
        }
        // created ms must lie inside the created hour the (carrier-visible) header states.
        if (StoreForwardFormat.hourOf(message.createdMs) != bundle.header.createdHour) {
            return reject(bundle, "Message time is outside the bundle's created hour")
        }
        val peerHex = sender.nodeId.toString()
        val tombstoneHour = tombstone(bundle)
        if (message.kind != V2Kind.RECEIPT && seenIds.contains(peerHex, message.messageIdHex)) {
            // A re-sealed replacement of a message already opened (finding 3): drop it, acknowledge it. It keeps its charge.
            finish(bundle)
            return StoreForwardCompletion.V2Duplicate
        }
        val completion = when (message.kind) {
            V2Kind.TEXT, V2Kind.LOCATION -> openText(bundle, sender, message)
            V2Kind.ATTACHMENT -> openAttachment(bundle, sender, message)
            V2Kind.RECEIPT -> openReceipt(bundle, sender, message)
            V2Kind.SEGMENT -> reject(bundle, "A segment must travel as a class 4 bundle")
            V2Kind.PREKEYS -> reject(bundle, "Prekeys need a newer app")
        }
        if (completion is StoreForwardCompletion.Rejected || completion is StoreForwardCompletion.Deferred) return completion
        if (message.kind != V2Kind.RECEIPT && !seenIds.add(peerHex, message.messageIdHex, tombstoneHour)) {
            return StoreForwardCompletion.Deferred("Message-ID table is full")
        }
        finish(bundle)
        return completion
    }

    /** A receipt is not a message: refund the pre-charged message allowance and charge the receipt allowance (M8). */
    private fun chargeReceipt(peer: NodeId) {
        node.ledger.add(NeighborLedger.senderKey(peer.toString()), bundlesIn = -1)
        node.ledger.add(receiptKey(peer), bundlesIn = 1)
    }

    private fun receiptKey(peer: NodeId) = "receipts.${peer}"

    private fun clampDisappear(seconds: Int): Pair<Int, Boolean> =
        if (seconds in 1 until limits.timerFloorSeconds) limits.timerFloorSeconds to true else seconds to false

    private fun openText(bundle: StoredBundle, sender: StoreForwardContact, message: MessageV2): StoreForwardCompletion {
        if (message.kind == V2Kind.TEXT && message.body.size !in 1..MessagingV2Format.TEXT_MAX_BYTES) {
            return reject(bundle, "Text length out of range")
        }
        if (message.body.size > MessagingV2Format.TEXT_MAX_BYTES) return reject(bundle, "Body too long")
        val peerHex = sender.nodeId.toString()
        val (timer, raised) = clampDisappear(message.disappearSeconds)
        val replyHex = message.replyTo?.sfHex()?.takeIf { id -> threads.forPeer(peerHex).any { it.messageIdHex == id } }
        val record = ThreadMessage(
            MessageDirection.IN, peerHex, message.messageIdHex, message.kind, message.createdMs, clock(), message.flags,
            message.body, null, timer, replyHex, mutableListOf(bundle.idHex), timerRaised = raised,
        )
        return try {
            threads.save(record)
            StoreForwardCompletion.V2Message(record)
        } catch (error: Exception) {
            node.store.update(bundle.idHex, BundleState.UNOPENED, peerHex)
            StoreForwardCompletion.Deferred(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun openAttachment(bundle: StoredBundle, sender: StoreForwardContact, message: MessageV2): StoreForwardCompletion {
        val manifest = try {
            AttachmentManifest.decode(message.body)
        } catch (error: IllegalArgumentException) {
            return reject(bundle, error.message ?: "Invalid manifest")
        }
        val peerHex = sender.nodeId.toString()
        val (timer, raised) = clampDisappear(message.disappearSeconds)
        val replyHex = message.replyTo?.sfHex()?.takeIf { id -> threads.forPeer(peerHex).any { it.messageIdHex == id } }
        // A repeated file ID from this sender with different segments is refused; an identical one is the same file.
        transfers.forFile(MessageDirection.IN, peerHex, manifest.fileIdHex)?.let { existing ->
            if (existing.segmentIds != manifest.segments.map { it.idHex }) return reject(bundle, "File ID reused with different segments")
        }
        if (manifest.inline) {
            val data = requireNotNull(manifest.inlineData)
            if (!MessageDigest.isEqual(sfSha256(data), manifest.sha256)) return reject(bundle, "File hash does not match")
            val owner = "in:$peerHex"
            if (vault.usedBytes(owner) + data.size > limits.perContactVaultBytes || vault.usedBytes() + data.size > limits.vaultCapBytes) {
                return reject(bundle, "Vault share is full")
            }
            val vaultId = vault.create(owner, data.size.toLong())
            return try {
                vault.writeBlocks(vaultId, 0, data)
                val info = attachmentInfo(manifest, FileState.STORED, vaultId)
                val record = ThreadMessage(
                    MessageDirection.IN, peerHex, message.messageIdHex, V2Kind.ATTACHMENT, message.createdMs, clock(), message.flags,
                    ByteArray(0), info, timer, replyHex, mutableListOf(bundle.idHex), timerRaised = raised,
                )
                threads.save(record)
                StoreForwardCompletion.V2Message(record)
            } catch (error: Exception) {
                vault.delete(vaultId)
                node.store.update(bundle.idHex, BundleState.UNOPENED, peerHex)
                StoreForwardCompletion.Deferred(error.message ?: error.javaClass.simpleName)
            }
        }
        if (transfers.all().count { it.direction == MessageDirection.IN && it.peer == peerHex && it.state == TransferState.AWAITING_APPROVAL } >=
            limits.maxPendingTransfersPerContact
        ) return reject(bundle, "Too many files waiting for approval")
        val transferId = newId().sfHex()
        val transfer = TransferRecord(
            transferId, MessageDirection.IN, peerHex, message.messageIdHex, manifest.fileIdHex, bundle.idHex, manifest.totalSize,
            manifest.sha256, manifest.segments.map { it.idHex }.toMutableList(), manifest.segments.map { it.chunkCount }.toMutableList(),
            bundle.header.expiryHour,
            TransferState.AWAITING_APPROVAL, BitSet(), null,
        )
        return try {
            keys.put(transferId, manifest.fileKey)
            transfers.save(transfer)
            val record = ThreadMessage(
                MessageDirection.IN, peerHex, message.messageIdHex, V2Kind.ATTACHMENT, message.createdMs, clock(), message.flags,
                ByteArray(0), attachmentInfo(manifest, FileState.AWAITING_APPROVAL, null), timer, replyHex,
                mutableListOf(bundle.idHex), timerRaised = raised,
            )
            threads.save(record)
            if (autoDownloadMaxBytes >= manifest.totalSize && autoDownloadAllowed()) {
                runCatching { approveDownload(record.id) }
            }
            StoreForwardCompletion.V2Message(record)
        } catch (error: Exception) {
            keys.erase(transferId)
            transfers.remove(transferId)
            node.store.update(bundle.idHex, BundleState.UNOPENED, peerHex)
            StoreForwardCompletion.Deferred(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun attachmentInfo(m: AttachmentManifest, state: FileState, vaultId: String?) = AttachmentInfo(
        m.fileIdHex, m.presentation, m.totalSize, m.sha256, m.name, m.mime, m.width, m.height, m.durationMs, m.thumbnail,
        m.segments.size, state, vaultId,
    )

    private fun openReceipt(bundle: StoredBundle, sender: StoreForwardContact, message: MessageV2): StoreForwardCompletion {
        val body = try {
            ReceiptBody.decode(message.body)
        } catch (error: IllegalArgumentException) {
            return reject(bundle, error.message ?: "Invalid receipt")
        }
        // Receipts have their own allowance (1,000 per contact per day), not the 200 messages.
        chargeReceipt(sender.nodeId)
        if (node.ledger.today(receiptKey(sender.nodeId)).bundlesIn > limits.contactDailyReceipts) {
            return reject(bundle, "Receipt allowance used")
        }
        var applied = 0
        var ignored = 0
        body.bundleIds.forEach { id -> if (applyReceipt(sender.nodeId, body.status, id.sfHex())) applied++ else ignored++ }
        return StoreForwardCompletion.V2Receipts(applied, ignored)
    }

    // ---- segments -----------------------------------------------------------------------------------------------

    private fun openSegment(bundle: StoredBundle): StoreForwardCompletion {
        val peerHex = bundle.fromNeighbor ?: return reject(bundle, "Segment has no supplying neighbour")
        val (record, index) = transfers.forSegment(MessageDirection.IN, bundle.idHex)
            ?: return reject(bundle, "Segment is not listed by an accepted manifest")
        if (record.peer != peerHex) return reject(bundle, "Segment came from a phone other than the manifest's sender")
        if (record.doneSegments.get(index) && record.state == TransferState.DONE) {
            finish(bundle)
            return StoreForwardCompletion.V2Segment(record.id, index, true)
        }
        val fileKey = keys.get(record.id) ?: return reject(bundle, "The file key was already erased")
        val inner = try {
            val key = SegmentMath.segmentKey(fileKey, hexToBytes(record.fileIdHex), index)
            try {
                StoreForwardSeal.openBulk(bundle.header, node.store.chunks(bundle.idHex), key)
            } finally {
                key.fill(0)
            }
        } catch (error: Exception) {
            return failTransfer(bundle, record, error.message ?: "Segment does not open")
        } finally {
            fileKey.fill(0)
        }
        val body = try {
            val message = MessageV2.decode(inner)
            require(message.kind == V2Kind.SEGMENT) { "Not a segment" }
            require(StoreForwardFormat.hourOf(message.createdMs) == bundle.header.createdHour) { "Segment time is outside the bundle's hour" }
            SegmentBody.decode(message.body)
        } catch (error: IllegalArgumentException) {
            return failTransfer(bundle, record, error.message ?: "Invalid segment")
        }
        if (!body.fileId.contentEquals(hexToBytes(record.fileIdHex)) || body.index != index) {
            return failTransfer(bundle, record, "Segment names a different file or position")
        }
        if (body.data.size != SegmentMath.dataLength(index, record.totalSize)) {
            return failTransfer(bundle, record, "Segment has the wrong length")
        }
        val vaultId = record.vaultId ?: return reject(bundle, "No vault file for this download")
        try {
            vault.writeBlocks(vaultId, SegmentMath.firstBlock(index), body.data)
        } catch (error: VaultImmutableException) {
            return reject(bundle, "Segment contradicts bytes already stored")
        } catch (error: VaultDamagedException) {
            return failTransfer(bundle, record, "Vault block damaged")
        } finally {
            body.data.fill(0)
        }
        record.doneSegments.set(index)
        transfers.save(record)
        finish(bundle)
        if (!record.complete) return StoreForwardCompletion.V2Segment(record.id, index, false)
        return completeFile(record, index)
    }

    private fun completeFile(record: TransferRecord, lastIndex: Int): StoreForwardCompletion {
        val vaultId = requireNotNull(record.vaultId)
        val message = threads.find(MessageDirection.IN, record.peer, record.messageIdHex)
        val matches = MessageDigest.isEqual(vault.sha256(vaultId), record.sha256)
        if (!matches) {
            // A wrong whole-file hash deletes the file and counts a violation against the sender.
            damage(record)
            return StoreForwardCompletion.V2Segment(record.id, lastIndex, true, damaged = true)
        }
        record.state = TransferState.DONE
        transfers.save(record)
        keys.erase(record.id)
        message?.attachment?.let { it.state = FileState.COMPLETE }
        message?.let(threads::save)
        // Downloaded is receipt status 3; the sender deletes its sealed segments when it arrives.
        receiptOutbox.queue(record.peer, ReceiptStatus.DOWNLOADED, record.manifestBundleHex, clock())
        return StoreForwardCompletion.V2Segment(record.id, lastIndex, true)
    }

    /** The file can no longer be completed (a segment failed authentication, or the whole-file hash differs). */
    private fun damage(record: TransferRecord) {
        record.vaultId?.let(vault::delete)
        record.vaultId = null
        record.doneSegments.clear()
        record.state = TransferState.DAMAGED
        record.failures++
        transfers.save(record)
        keys.erase(record.id)
        threads.find(MessageDirection.IN, record.peer, record.messageIdHex)?.let { message ->
            message.attachment?.let { it.state = FileState.DAMAGED; it.vaultId = null }
            threads.save(message)
        }
        node.ledger.add("violations.${record.peer}", bundlesIn = 1)
    }

    private fun failTransfer(bundle: StoredBundle, record: TransferRecord, reason: String): StoreForwardCompletion {
        damage(record)
        return reject(bundle, reason)
    }

    // ---- receipts -----------------------------------------------------------------------------------------------

    /**
     * Sets the flag for [status] on the own message carried by [bundleIdHex], at most once per (bundle, status) and only
     * when [from] is the contact the message was addressed to. A receipt for an unknown bundle is ignored; one that
     * arrives after the message was marked not sent still sets its flag. Returns whether a flag changed.
     */
    fun applyReceipt(from: NodeId, status: ReceiptStatus, bundleIdHex: String): Boolean {
        val message = threads.findByBundle(bundleIdHex) ?: return false
        if (message.direction != MessageDirection.OUT || message.peerNode != from.toString()) return false
        val now = clock()
        val changed = when (status) {
            ReceiptStatus.DELIVERED -> if (message.deliveredAtMs == 0L) { message.deliveredAtMs = now; true } else false
            ReceiptStatus.READ -> if (message.readAtMs == 0L) { message.readAtMs = now; true } else false
            ReceiptStatus.DOWNLOADED -> if (message.downloadedAtMs == 0L) { message.downloadedAtMs = now; true } else false
            ReceiptStatus.KEY_GONE -> false
        }
        if (!changed) return false
        startSenderTimer(message, now)
        threads.save(message)
        if (status == ReceiptStatus.DOWNLOADED) releaseOutgoingFile(message)
        return true
    }

    private fun startSenderTimer(message: ThreadMessage, now: Long) {
        if (message.disappearSeconds > 0 && message.disappearAtMs == 0L) message.disappearAtMs = now + message.disappearSeconds * 1000L
    }

    /** The receiver holds the whole verified file: delete the sealed segments and erase the file key. */
    private fun releaseOutgoingFile(message: ThreadMessage) {
        val record = transfers.forMessage(MessageDirection.OUT, message.peerNode, message.messageIdHex) ?: return
        record.segmentIds.forEach { node.store.remove(it, null) }
        keys.erase(record.id)
        record.state = TransferState.DONE
        transfers.save(record)
    }

    /** `DELIVERED_LOCAL` for an own bundle: the contact's phone holds it. Sets the delivered flag (hop-local, direct link). */
    fun onDelivered(bundleIdHex: String, peer: NodeId) {
        applyReceipt(peer, ReceiptStatus.DELIVERED, bundleIdHex)
    }

    /**
     * The user read the incoming message (the open thread showed it): starts its disappearing timer and, when both phones
     * turned read receipts on, queues the read receipt (never a receipt for a receipt).
     */
    fun markRead(threadId: String) {
        val message = threads.get(threadId) ?: return
        if (message.direction != MessageDirection.IN || message.readAtMs != 0L) return
        val now = clock()
        message.readAtMs = now
        if (message.disappearSeconds > 0 && message.disappearAtMs == 0L) message.disappearAtMs = now + message.disappearSeconds * 1000L
        threads.save(message)
        val peerId = runCatching { NodeId.fromBytes(hexToBytes(message.peerNode)) }.getOrNull()
        if (peerId != null && readReceiptsFor(peerId) && message.flags and MessagingV2Format.WANT_READ_RECEIPT != 0) {
            message.bundleIds.forEach { receiptOutbox.queue(message.peerNode, ReceiptStatus.READ, it, now) }
            flushReceiptsInChat(message.peerNode)
        }
    }

    /** In a chat session receipts go out at once (PROTOCOL "Receipts and states"); otherwise they wait for the batch delay or the next exchange. */
    private fun flushReceiptsInChat(peerHex: String) {
        val peer = runCatching { NodeId.fromBytes(hexToBytes(peerHex)) }.getOrNull() ?: return
        if (!node.chatSessionActive(peer)) return
        if (flushReceipts(peer, force = true).isNotEmpty()) node.notifyNewBundles()
    }

    /** Keeps a message whose disappearing timer was raised to the floor (the Keep action). */
    fun keep(threadId: String) {
        val message = threads.get(threadId) ?: return
        message.disappearAtMs = 0
        threads.save(message)
    }

    /**
     * Seals pending receipts into RECEIPT bundles (class 0, up to 64 IDs of one status each). With [force] everything goes;
     * otherwise only receipts older than the batch delay (30 s). Returns the bundles queued.
     */
    fun flushReceipts(peer: NodeId? = null, force: Boolean = false): List<StoredBundle> {
        val now = clock()
        val queued = ArrayList<StoredBundle>()
        val pending = receiptOutbox.pending(peer?.toString()).filter { force || now - it.queuedMs >= limits.receiptBatchDelayMs }
        pending.groupBy { it.peer to it.status }.forEach { (key, items) ->
            val contact = node.contacts.get(NodeId.fromBytes(hexToBytes(key.first)))?.takeIf { it.state == ContactState.ACCEPTED }
                ?: return@forEach
            items.chunked(MessagingV2Format.RECEIPT_MAX_IDS).forEach { batch ->
                val hour = StoreForwardFormat.hourOf(now)
                val body = ReceiptBody(key.second, hour, batch.map { hexToBytes(it.bundleIdHex) })
                val message = MessageV2(V2Kind.RECEIPT, 0, now, newId(), ConversationIds.of(node.nodeId, contact.nodeId), null, 0, body.encode())
                try {
                    queued += queue(contact, message.encode(), 0, MessagingV2Format.TEXT_TTL_HOURS, now)
                    receiptOutbox.remove(batch)
                } catch (error: MessagingV2QuotaException) {
                    // Receipts wait for room; nothing is lost.
                }
            }
        }
        return queued
    }

    // ---- housekeeping -------------------------------------------------------------------------------------------

    /** Deletes a message at the user's request (or its timer): the record, its files, its sealed segments and its key. */
    fun deleteMessage(threadId: String) {
        val message = threads.delete(threadId) ?: return
        val transfer = transfers.forMessage(message.direction, message.peerNode, message.messageIdHex)
        if (message.direction == MessageDirection.OUT) {
            message.bundleIds.forEach { node.store.remove(it, null) }
            transfer?.segmentIds?.forEach { node.store.remove(it, null) }
        } else {
            transfer?.segmentIds?.forEach { node.store.get(it)?.let { b -> node.store.remove(it, node.limits.tombstoneUntilHour(b.header.expiryHour)) } }
        }
        transfer?.let {
            keys.erase(it.id)
            transfers.remove(it.id)
        }
        message.attachment?.vaultId?.let(vault::delete)
        transfer?.vaultId?.let(vault::delete)
    }

    /**
     * Housekeeping at the start of an exchange and from the UI: expired disappearing timers, expired transfers (key erased),
     * unfinished outgoing preparations from a crash, orphan vault files, old message IDs, and own messages whose
     * bundle lifetime ended undelivered (marked not sent, with Retry).
     */
    fun sweep() {
        val now = clock()
        val nowHour = StoreForwardFormat.hourOf(now)
        seenIds.sweep(nowHour)
        threads.expired(now).forEach { deleteMessage(it.id) }
        for (record in transfers.all()) {
            if (record.id in preparing) continue
            val unfinishedPrepare = record.direction == MessageDirection.OUT && record.manifestBundleHex.isEmpty()
            val live = record.state == TransferState.ACTIVE || record.state == TransferState.AWAITING_APPROVAL
            val expired = nowHour >= record.expiryHour && live
            if (unfinishedPrepare || expired) {
                abandon(record, if (expired) TransferState.UNAVAILABLE else TransferState.ABANDONED)
            } else if (!live && nowHour >= record.expiryHour + node.limits.skewHours + 1) {
                // A finished record is kept as long as the message's bundle could still be offered, then forgotten.
                transfers.remove(record.id)
            }
        }
        for (message in threads.all()) {
            if (message.direction == MessageDirection.OUT && !message.delivered && !message.notSent) {
                val alive = message.bundleIds.any { node.store.get(it) != null }
                if (!alive) {
                    message.notSent = true
                    threads.save(message)
                }
            }
        }
        val referenced = HashSet<String>()
        threads.all().forEach { m -> m.attachment?.vaultId?.let(referenced::add) }
        transfers.all().forEach { t -> t.vaultId?.let(referenced::add) }
        vault.ids().filter { it !in referenced }.forEach { id ->
            val directory = File(File(dir, "vault"), id)
            if (now - directory.lastModified() > ORPHAN_VAULT_MS) vault.delete(id)
        }
        // Receipts older than the batch delay go out with the next exchange.
        flushReceipts(null, force = false)
    }

    private fun abandon(record: TransferRecord, state: TransferState) {
        keys.erase(record.id)
        record.segmentIds.filter { it.isNotEmpty() }.forEach { id ->
            val stored = node.store.get(id)
            if (record.direction == MessageDirection.OUT) node.store.remove(id, null)
            else if (stored != null) node.store.remove(id, node.limits.tombstoneUntilHour(stored.header.expiryHour))
        }
        if (record.direction == MessageDirection.IN) {
            record.vaultId?.let(vault::delete)
            record.vaultId = null
            threads.find(MessageDirection.IN, record.peer, record.messageIdHex)?.let { message ->
                message.attachment?.let { it.state = FileState.UNAVAILABLE; it.vaultId = null }
                threads.save(message)
            }
        }
        record.state = state
        transfers.save(record)
        if (record.direction == MessageDirection.OUT && record.manifestBundleHex.isEmpty()) {
            record.vaultId?.let(vault::delete)
            transfers.remove(record.id)
        }
    }

    /** The sender no longer has the segment (`REFUSE unknown`): "No longer available". */
    fun onSegmentUnavailable(segmentIdHex: String, peer: NodeId) {
        val (record, _) = transfers.forSegment(MessageDirection.IN, segmentIdHex) ?: return
        if (record.peer != peer.toString() || record.state != TransferState.ACTIVE) return
        abandon(record, TransferState.UNAVAILABLE)
    }

    // ---- key changes (phase 1d, M1) -----------------------------------------------------------------------------------

    /** What happened to the messages that waited for a contact whose keys changed. */
    class KeyChangeResult(val adopted: Boolean, val resealedTexts: Int, val notSent: Int)

    /**
     * The user compared the new safety number of [peer] and accepts the new mailbox key. The messages still waiting were
     * sealed to the old key, which the contact no longer holds: waiting texts are sealed again under the same message ID
     * (a copy that already arrived is dropped by the receiver), files and everything else become "Not sent" with Retry, and
     * the old sealed bundles are withdrawn.
     */
    fun acceptKeyChange(peer: NodeId): KeyChangeResult {
        node.contacts.acceptKeyChange(peer) ?: return KeyChangeResult(false, 0, 0)
        val contact = requireContact(peer)
        val peerHex = peer.toString()
        val stale = node.store.all().filter { it.role == BundleRole.OWN && it.peerNode == peerHex && it.state == BundleState.QUEUED }
        val staleIds = stale.map { it.idHex }.toSet()
        var resealed = 0
        var notSent = 0
        for (message in threads.forPeer(peerHex)) {
            if (message.direction != MessageDirection.OUT || message.delivered || message.notSent) continue
            if (message.bundleIds.none { it in staleIds }) continue
            message.bundleIds.filter { it in staleIds }.forEach { node.store.remove(it, null) }
            val fileTransfer = transfers.forMessage(MessageDirection.OUT, peerHex, message.messageIdHex)
            if (message.kind == V2Kind.TEXT && message.attachment == null) {
                val now = clock()
                val wire = MessageV2(
                    V2Kind.TEXT, message.flags, now, hexToBytes(message.messageIdHex), ConversationIds.of(node.nodeId, peer),
                    message.replyToHex?.let(::hexToBytes), message.disappearSeconds, message.body,
                )
                try {
                    val bundle = queue(contact, wire.encode(), 0, MessagingV2Format.TEXT_TTL_HOURS, now)
                    message.bundleIds.clear()
                    message.bundleIds += bundle.idHex
                    threads.save(message)
                    resealed++
                    continue
                } catch (error: MessagingV2QuotaException) {
                    // No room to seal again: shown as not sent, the user can retry later.
                }
            }
            fileTransfer?.let { abandon(it, TransferState.ABANDONED) }
            message.notSent = true
            threads.save(message)
            notSent++
        }
        // Whatever else was sealed to the old key (receipts) is useless now.
        stale.filter { node.store.get(it.idHex) != null }.forEach { node.store.remove(it.idHex, null) }
        node.notifyNewBundles()
        return KeyChangeResult(true, resealed, notSent)
    }

    /**
     * "Not sent: Retry" (phase 1c): the message that could not be delivered is sealed again as a new message (a text from
     * its words, a file from the copy this phone keeps in the vault) and the failed one is removed. Throws
     * [IllegalStateException] when the file is no longer on this phone (the user picks it again).
     */
    fun retry(threadId: String): ThreadMessage {
        val old = requireNotNull(threads.get(threadId)) { "Unknown message" }
        require(old.direction == MessageDirection.OUT && old.notSent) { "Only a message that was not sent can be sent again" }
        val peer = NodeId.fromBytes(hexToBytes(old.peerNode))
        val replyTo = old.replyToHex?.let(::hexToBytes)
        val attachment = old.attachment
        val fresh = if (attachment == null) {
            sendText(peer, old.text, replyTo, old.disappearSeconds)
        } else {
            val vaultId = attachment.vaultId
            val info = vaultId?.let { vault.info(it) }
            check(vaultId != null && info != null && info.complete) { "The file is no longer on this phone" }
            sendFile(
                peer, { VaultInputStream(vault, vaultId) }, attachment.totalSize, attachment.presentation, attachment.name, attachment.mime,
                attachment.width, attachment.height, attachment.durationMs, attachment.thumbnail, old.disappearSeconds, replyTo = replyTo,
            )
        }
        deleteMessage(old.id)
        return fresh
    }

    /** The conversation state of [peer] that the thread shows above the composer (phase 1d facts; the words are phase 1c). */
    fun linkState(peer: NodeId): ConversationLinkState {
        val contact = node.contacts.get(peer)
        return when {
            contact == null -> ConversationLinkState.NOT_A_CONTACT
            contact.state != ContactState.ACCEPTED -> ConversationLinkState.CARD_PENDING
            contact.keysChanged -> ConversationLinkState.KEYS_CHANGED
            isLegacy(peer) -> ConversationLinkState.PEER_APP_OLDER
            else -> ConversationLinkState.READY
        }
    }

    companion object {
        const val MAX_ACTIVE_SEGMENTS = 2
        const val ORPHAN_VAULT_MS = 3_600_000L
    }
}
