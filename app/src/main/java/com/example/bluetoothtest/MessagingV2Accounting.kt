package com.example.bluetoothtest

import java.io.File
import java.util.Properties

/**
 * Every Messaging v2 number in one place (DECISIONS Design M8, M12, M12a; PROTOCOL "Limits and vectors"). The values are
 * the specified ones; tests build smaller copies. All limits are hop-local or this phone's own storage.
 */
data class MessagingV2Limits(
    /** Admission tier of an accepted contact (M8); other adjacent phones keep the v1 numbers in [StoreForwardLimits]. */
    val contactSessionBytes: Long = 128L * 1024 * 1024,
    val contactDailyBytes: Long = 512L * 1024 * 1024,
    val contactDailyMessages: Int = 200,
    val contactDailyReceipts: Int = 1000,
    /** File vault (M7): total cap and the share one contact may hold. */
    val vaultCapBytes: Long = 1024L * 1024 * 1024,
    val perContactVaultFraction: Double = 0.25,
    /** Nothing new is accepted when device storage would fall below this (M12a). */
    val freeSpaceFloorBytes: Long = 200L * 1000 * 1000,
    /** Global ingress, so identity churn cannot multiply the per-neighbour allowance (M12a). */
    val nonContactDailyBytes: Long = 64L * 1000 * 1000,
    val nonContactHourlyBytes: Long = 16L * 1000 * 1000,
    val contactsDailyBytes: Long = 1024L * 1024 * 1024,
    val maxInFlightBundles: Int = 8,
    val maxSeenEntries: Int = 20_000,
    val maxBundleRecords: Int = 2_000,
    val maxArchiveRows: Int = 2_000,
    /** Own outgoing queue (M7). */
    val ownQueueBytesPerContact: Long = 512L * 1024 * 1024,
    val ownQueueBytesTotal: Long = 1024L * 1024 * 1024,
    val waitingPerContact: Int = 100,
    /** Control budgets per link (M12a). */
    val controlPerSecond: Int = 20,
    /**
     * Burst allowance of the 20 per second sustained rate: one full SUMMARY (256 entries) is answered by a WANT each, which is
     * legitimate traffic and must not trip the budget. The hard limit is [controlPerTenMinutes].
     */
    val controlBurst: Int = 256,
    val controlPerTenMinutes: Int = 2_000,
    val cancelPerMinute: Int = 60,
    val wantPerBundlePerHour: Int = 30,
    val asymmetricOpsPerExchange: Int = 30,
    /** Chat session timing (M6, M12a). */
    val pingIntervalMs: Long = 20_000,
    val pingMinGapMs: Long = 10_000,
    val chatDeadMs: Long = 60_000,
    val chatMaxMs: Long = 30L * 60_000,
    val cancelGraceMs: Long = 5_000,
    val summaryWindowMs: Long = 30L * 60_000,
    /** Largest segmented file fetched without a tap (M3); 0 never. Inline attachments always arrive. */
    val autoDownloadMaxBytes: Long = 8_000_000,
    /** Read receipts: off by default, symmetric (M4). */
    val readReceipts: Boolean = false,
    /** A disappearing timer below this is raised to it (M7). */
    val timerFloorSeconds: Int = MessagingV2Format.DISAPPEAR_FLOOR_SECONDS,
    /** Receipt batching on the direct path: flushed after this long or when the link is about to close (PROTOCOL). */
    val receiptBatchDelayMs: Long = 30_000,
    /** A peer that answered a v2 HELLO with DONE(error) is not tried as v2 again for this long. */
    val legacyPeerMs: Long = 3_600_000,
    /** Files per contact waiting for approval; more are refused. */
    val maxPendingTransfersPerContact: Int = 100,
    /**
     * Responder cooldown relaxed for an accepted contact whose HELLO carries the CHAT flag (M6): 5 s and 60 per hour instead
     * of the v1 60 s and 10 per hour that stay for every other adjacent phone.
     */
    val contactExchangeCooldownMs: Long = 5_000,
    val contactMaxExchangesPerHour: Int = 60,
    /** At most this many CARD records in one v2 exchange (a card costs a signature check). */
    val maxCardsPerExchange: Int = 4,
) {
    val perContactVaultBytes: Long get() = (vaultCapBytes * perContactVaultFraction).toLong()
}

/** Sliding-window counters for the control budgets of one link (M12a). Exceeding one ends the exchange. */
class ControlBudget(private val limits: MessagingV2Limits, private val nowMs: () -> Long) {
    private val controls = ArrayDeque<Long>()
    private var tokens = limits.controlBurst.toDouble()
    private var tokensAtMs = nowMs()
    private val cancels = ArrayDeque<Long>()
    private val wants = HashMap<String, ArrayDeque<Long>>()
    private var asymmetric = 0

    /**
     * One inbound control record (SUMMARY, WANT, CANCEL, PING, receipt, REPLY); false = budget exceeded. A token bucket:
     * [MessagingV2Limits.controlPerSecond] sustained with a [MessagingV2Limits.controlBurst] allowance, and at most
     * [MessagingV2Limits.controlPerTenMinutes] in any ten minutes.
     */
    fun control(): Boolean {
        val now = nowMs()
        while (controls.isNotEmpty() && now - controls.first() > 600_000) controls.removeFirst()
        if (controls.size >= limits.controlPerTenMinutes) return false
        tokens = minOf(limits.controlBurst.toDouble(), tokens + (now - tokensAtMs) * limits.controlPerSecond / 1000.0)
        tokensAtMs = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        controls.addLast(now)
        return true
    }

    fun cancel(): Boolean {
        val now = nowMs()
        while (cancels.isNotEmpty() && now - cancels.first() > 60_000) cancels.removeFirst()
        if (cancels.size >= limits.cancelPerMinute) return false
        cancels.addLast(now)
        return true
    }

    /** A WANT for [idHex]; at most 30 per hour for the same bundle. */
    fun want(idHex: String): Boolean {
        val now = nowMs()
        val times = wants.getOrPut(idHex) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > 3_600_000) times.removeFirst()
        if (times.size >= limits.wantPerBundlePerHour) return false
        times.addLast(now)
        if (wants.size > 4 * limits.wantPerBundlePerHour * 8) wants.entries.removeAll { it.value.isEmpty() }
        return true
    }

    /**
     * Slot opens and signature checks cost CPU: at most 30 per exchange from a peer that is not an accepted contact.
     * [cost] is 2 for an operation that failed (a failed open costs double).
     */
    fun asymmetric(contact: Boolean, cost: Int = 1): Boolean {
        asymmetric += cost
        return contact || asymmetric <= limits.asymmetricOpsPerExchange
    }
}

/** Why a link ended early. */
enum class DeadlineReason { LIVENESS, PROGRESS, TOTAL }

/**
 * The two independent deadlines of a v2 link (design finding 19). *Liveness*: in a chat session 60 s without any inbound
 * record (a PING counts); otherwise the v1 rule, 10 s without a record in either direction. *Progress*: while a
 * transfer is running a chunk must move every 10 s whatever else arrives, so PING traffic can never keep a stalled
 * transfer alive. A non-chat exchange also has a total limit ([StoreForwardLimits.sessionTimeoutMs], 120 s), progress-aware
 * since 2026-10-13: while chunks keep *flowing* (at least [StoreForwardLimits.flowingChunks] chunks within the last progress
 * window, in either direction) the total limit is extended up to [StoreForwardLimits.sessionHardCapMs], so a 1 MB or 5 MB
 * file on a slow path (A15s answering at about 89 kbps needs about 7 minutes for 5 MB) finishes in one session instead of
 * being cut at 120 s and resumed in a second one behind a cooldown. The liveness and progress deadlines above stay strict, and
 * a trickle (fewer chunks than that per window) gets no extension.
 */
class SessionDeadlines(
    private val chat: Boolean,
    private val limits: StoreForwardLimits,
    private val v2: MessagingV2Limits,
    private val nowMs: () -> Long,
) {
    private val startedMs = nowMs()
    @Volatile private var lastInboundMs = startedMs
    @Volatile private var lastOutboundMs = startedMs
    @Volatile private var lastChunkMs = startedMs
    @Volatile private var transferActive = false
    /** Times of the last [StoreForwardLimits.flowingChunks] chunks moved (ring, newest overwrites oldest). */
    private val recentChunkMs = LongArray(maxOf(1, limits.flowingChunks)) { Long.MIN_VALUE }
    private var recentChunkNext = 0
    private val chunkLock = Any()

    fun inbound() {
        lastInboundMs = nowMs()
    }

    fun outbound() {
        lastOutboundMs = nowMs()
    }

    fun chunk() {
        val now = nowMs()
        lastChunkMs = now
        synchronized(chunkLock) {
            recentChunkMs[recentChunkNext] = now
            recentChunkNext = (recentChunkNext + 1) % recentChunkMs.size
        }
    }

    /**
     * True while the last [StoreForwardLimits.flowingChunks] chunks all moved within one progress window and the newest is
     * not older than one (a stall still ends on the strict progress deadline, not earlier on the total).
     */
    private fun chunksFlowing(now: Long): Boolean = synchronized(chunkLock) {
        // The oldest entry of the ring is the one the next chunk overwrites.
        val oldest = recentChunkMs[recentChunkNext]
        val newest = recentChunkMs[(recentChunkNext - 1 + recentChunkMs.size) % recentChunkMs.size]
        oldest != Long.MIN_VALUE && newest - oldest <= limits.progressTimeoutMs && now - newest <= limits.progressTimeoutMs
    }

    /** A transfer started or ended; the progress clock starts when it starts. */
    fun setTransferActive(active: Boolean) {
        if (active && !transferActive) lastChunkMs = nowMs()
        transferActive = active
    }

    /** For the log line when a deadline ends an exchange: how long ago each clock last moved. */
    fun describe(): String {
        val now = nowMs()
        return "chat=$chat sinceInboundMs=${now - lastInboundMs} sinceOutboundMs=${now - lastOutboundMs} " +
            "sinceChunkMs=${now - lastChunkMs} transferActive=$transferActive ageMs=${now - startedMs}"
    }

    fun expired(): DeadlineReason? {
        val now = nowMs()
        val lastRecord = if (chat) lastInboundMs else maxOf(lastInboundMs, lastOutboundMs)
        val liveness = if (chat) v2.chatDeadMs else limits.progressTimeoutMs
        if (now - lastRecord > liveness) return DeadlineReason.LIVENESS
        if (transferActive && now - lastChunkMs > limits.progressTimeoutMs) return DeadlineReason.PROGRESS
        if (!chat) {
            val age = now - startedMs
            // Progress-aware total limit: extended while chunks are flowing, and for one progress window after the last chunk even
            // with nothing left to send (the sender awaits the peer's final REPLY and DONE; a 5 MB run was cut 1.5 s before the
            // receiver completed). Never past the cap, and the liveness deadline above stays strict.
            val allowed = if (chunksFlowing(now)) maxOf(limits.sessionTimeoutMs, limits.sessionHardCapMs) else limits.sessionTimeoutMs
            if (age > allowed) return DeadlineReason.TOTAL
        }
        return null
    }
}

/** Entries counted in a sliding window (the 1,024 SUMMARY entries per 30 minutes of a chat session). */
class WindowCounter(private val windowMs: Long, private val nowMs: () -> Long) {
    private val events = ArrayDeque<Pair<Long, Int>>()
    private var total = 0

    @Synchronized
    fun add(count: Int) {
        evict()
        events.addLast(nowMs() to count)
        total += count
    }

    @Synchronized
    fun total(): Int {
        evict()
        return total
    }

    private fun evict() {
        val now = nowMs()
        while (events.isNotEmpty() && now - events.first().first > windowMs) total -= events.removeFirst().second
    }
}

/**
 * Global ingress counters (M12a): all non-contact neighbours together, and all contacts together, so rotating link
 * identities cannot multiply the per-neighbour allowance. Sealed at rest like the neighbour ledger.
 */
class GlobalIngress(
    private val file: File,
    private val clock: () -> Long,
    private val cipher: AtRestCipher,
    private val limits: MessagingV2Limits,
) {
    private val properties: Properties = readSealedProperties(file, cipher)

    private fun day() = StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(clock()))
    private fun hour() = StoreForwardFormat.hourOf(clock())

    private fun dayValue(key: String): Long =
        if (properties.getProperty("day")?.toIntOrNull() == day()) properties.getProperty(key)?.toLongOrNull() ?: 0 else 0

    private fun hourValue(key: String): Long =
        if (properties.getProperty("hour")?.toIntOrNull() == hour()) properties.getProperty(key)?.toLongOrNull() ?: 0 else 0

    /** Bytes still allowed in from [contact] or non-contact neighbours (the smaller of the day and hour allowances). */
    @Synchronized
    fun remaining(contact: Boolean): Long =
        if (contact) (limits.contactsDailyBytes - dayValue("contacts")).coerceAtLeast(0)
        else minOf(limits.nonContactDailyBytes - dayValue("others"), limits.nonContactHourlyBytes - hourValue("othersHour")).coerceAtLeast(0)

    @Synchronized
    fun add(contact: Boolean, bytes: Long) {
        val d = day()
        val h = hour()
        val contactsDay = dayValue("contacts")
        val othersDay = dayValue("others")
        val othersHour = hourValue("othersHour")
        properties.setProperty("day", d.toString())
        properties.setProperty("hour", h.toString())
        properties.setProperty("contacts", (contactsDay + if (contact) bytes else 0).toString())
        properties.setProperty("others", (othersDay + if (contact) 0 else bytes).toString())
        properties.setProperty("othersHour", (othersHour + if (contact) 0 else bytes).toString())
        writeSealedProperties(file, cipher, properties)
    }
}
