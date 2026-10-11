package com.example.bluetoothtest

import java.io.File
import java.util.Properties
import kotlin.math.pow

/**
 * Automatic store-and-forward sync (needs Messages). Settings > Features "Sync messages automatically";
 * the shipped default is [CallFeatureDefaults.AUTO_SYNC] (off until Messages passes its phone test). A
 * DEBUG benchmark launch uses only `test_sf_auto_sync`.
 *
 * Without it a user taps a person on the Call screen and chooses Exchange messages. With it, the
 * Call screen's existing discovery starts the same BSF1 exchange by itself when an accepted contact
 * comes into range and this phone has something for them (queued own messages or carried copies),
 * or, far less often, to pull what they may hold for this phone. Purely local: same wire messages,
 * same responder admission (60 s cooldown, 10 per hour), no extra scanning (Home still only
 * advertises; a Home phone is reached as a responder). Live calls and streams always win: no
 * automatic start while any media runs, a user tap cancels a pending automatic start, and the
 * existing suspend path stops a running exchange within one chunk.
 *
 * Re-implemented from the described behaviour of Briar's backoff poller and Bitchat's outbox
 * states (design-pattern notes: briar-berty item 1, bitchat items 8-9); no code was copied.
 */
object StoreForwardAutoSyncFeature {
    const val EXTRA = "test_sf_auto_sync"
    const val PREF = "feature_sf_auto_sync"

    @Volatile var enabled = false

    /** Only while store-and-forward itself is on; a benchmark launch follows only its extra. */
    internal fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
        storeForward: Boolean,
    ): Boolean = storeForward && CallFeatureResolver.enabled(CallFeature.AUTO_SYNC, benchmarkLaunch, extra, stored)
}

/** What the sender sees for an own message. Local knowledge only. */
enum class OutboxStatus {
    /** Waiting for the recipient (or a courier). */
    QUEUED,
    /** Handed to a courier, best effort. Needs the carrier role (DECISIONS Design A v2, hard-off in v1). */
    CARRIED,
    /** The recipient's phone holds it (DELIVERED_LOCAL) or a receipt arrived. */
    DELIVERED,
    /** The stored copy is damaged, or the recipient was reached [AutoSyncPolicy.maxAttempts] times without taking it. */
    FAILED,
}

enum class AutoSyncReason { OUTBOX, CARRIED, PULL }

data class AutoSyncPolicy(
    /** Just above the responder's 60 s per-neighbour cooldown, so a retry is never rate limited by it. */
    val outboxMinMs: Long = 65_000,
    /** Briar's Android Bluetooth cap. */
    val outboxMaxMs: Long = 600_000,
    /** A pull costs both phones a link with nothing known to move: much rarer than an outbox retry. */
    val pullMinMs: Long = 600_000,
    val pullMaxMs: Long = 1_800_000,
    val base: Double = 1.5,
    /** Each interval is multiplied by a factor drawn from [1 - jitter, 1 + jitter]. */
    val jitter: Double = 0.15,
    /** The phone with the higher node prefix waits this long, so two scanning phones do not dial each other at once. */
    val tieBreakDelayMs: Long = 10_000,
    /** An attempt with no result after this long (120 s exchange cap + connect) is treated as lost. */
    val inFlightTimeoutMs: Long = 180_000,
    /** Bitchat's attempt limit before an own message shows as failed. */
    val maxAttempts: Int = 8,
    val pullEnabled: Boolean = true,
)

object OutboxStatusRules {
    /** Own-message status: delivered beats carried beats failed beats queued. Null for non-own states. */
    fun status(state: BundleState, attempts: Int, carried: Boolean, maxAttempts: Int): OutboxStatus? = when (state) {
        BundleState.HANDED, BundleState.DELIVERED -> OutboxStatus.DELIVERED
        BundleState.FAILED -> OutboxStatus.FAILED
        BundleState.QUEUED -> when {
            carried -> OutboxStatus.CARRIED
            attempts >= maxAttempts -> OutboxStatus.FAILED
            else -> OutboxStatus.QUEUED
        }
        else -> null
    }

    /**
     * Whether an exchange counts as one delivery attempt for own messages to that peer that are
     * still queued after it: the authenticated recipient was reached and the exchange finished, or
     * it broke before any chunk moved. A suspend (live media on either side) or the responder's rate
     * limit is not the message's failure; a transfer that broke after moving chunks made progress.
     */
    fun countsAsAttempt(outcome: StoreForwardSyncOutcome, chunksSent: Int): Boolean = when (outcome) {
        StoreForwardSyncOutcome.COMPLETE -> true
        StoreForwardSyncOutcome.TIMEOUT, StoreForwardSyncOutcome.INTERRUPTED, StoreForwardSyncOutcome.FAILED -> chunksSent == 0
        StoreForwardSyncOutcome.SUSPENDED_LOCAL, StoreForwardSyncOutcome.SUSPENDED_BY_PEER,
        StoreForwardSyncOutcome.RATE_LIMITED -> false
    }
}

/**
 * Per own bundle: delivery attempts and the carried mark. Sealed at rest like the other
 * store-and-forward metadata (bundle IDs are metadata). A null file keeps it in memory (tests).
 */
class OutboxAttemptLedger(private val file: File?, private val cipher: AtRestCipher?) {
    private val properties: Properties = run {
        val f = file
        val c = cipher
        if (f != null && c != null) runCatching { readSealedProperties(f, c) }.getOrElse { Properties() } else Properties()
    }

    @Synchronized fun attempts(idHex: String): Int = properties.getProperty("$idHex.attempts")?.toIntOrNull() ?: 0

    @Synchronized fun carried(idHex: String): Boolean = properties.getProperty("$idHex.carried") == "1"

    /** Adds one attempt to each of [ids]; returns the new counts. */
    @Synchronized fun recordAttempt(ids: Collection<String>): Map<String, Int> {
        if (ids.isEmpty()) return emptyMap()
        val counts = ids.associateWith { id -> (attempts(id) + 1).also { properties.setProperty("$id.attempts", it.toString()) } }
        save()
        return counts
    }

    /** No caller in v1: the carrier role that hands own bundles to couriers is hard-off. */
    @Synchronized fun markCarried(idHex: String) {
        properties.setProperty("$idHex.carried", "1")
        save()
    }

    /** Forgets bundles that no longer exist (expired, withdrawn, wiped). */
    @Synchronized fun retain(liveIds: Set<String>) {
        val stale = properties.stringPropertyNames().filter { it.substringBefore('.') !in liveIds }
        if (stale.isEmpty()) return
        stale.forEach(properties::remove)
        save()
    }

    private fun save() {
        val f = file ?: return
        val c = cipher ?: return
        writeSealedProperties(f, c, properties)
    }
}

/** What this phone has for one adjacent contact. */
data class PeerSyncNeed(val contact: NodeId?, val queued: Int, val failed: Int, val carried: Int) {
    fun reason(pullEnabled: Boolean): AutoSyncReason? = when {
        contact == null -> null
        queued > 0 -> AutoSyncReason.OUTBOX
        carried > 0 -> AutoSyncReason.CARRIED
        pullEnabled -> AutoSyncReason.PULL
        else -> null
    }
}

/** Reads the store; never writes bundles and never changes what an exchange offers. */
object StoreForwardAutoSyncPlanner {
    /** The single accepted contact whose node ID starts with the advertised [prefix]; null if none or ambiguous. */
    fun contactFor(node: StoreForwardNode, prefix: ByteArray): StoreForwardContact? =
        node.contacts.accepted().filter { it.nodeId.toByteArray().copyOf(prefix.size).contentEquals(prefix) }.singleOrNull()

    private fun liveOwnQueuedTo(node: StoreForwardNode, peer: NodeId): List<StoredBundle> {
        val nowHour = StoreForwardFormat.hourOf(node.clock())
        val peerHex = peer.toString()
        return node.store.all().filter {
            it.role == BundleRole.OWN && it.state == BundleState.QUEUED && it.complete && it.peerNode == peerHex &&
                nowHour < it.storedUntilHour(node.limits.maxStoreHours)
        }
    }

    fun need(node: StoreForwardNode, ledger: OutboxAttemptLedger, contact: StoreForwardContact?, policy: AutoSyncPolicy): PeerSyncNeed {
        if (contact == null) return PeerSyncNeed(null, 0, 0, 0)
        val own = liveOwnQueuedTo(node, contact.nodeId)
        val failed = own.count { ledger.attempts(it.idHex) >= policy.maxAttempts }
        val nowHour = StoreForwardFormat.hourOf(node.clock())
        // Courier copies this phone could offer that peer (carrier role only; hard-off in v1).
        val carried = if (!node.limits.carrierEnabled) 0 else node.store.all().count {
            it.role == BundleRole.FOREIGN && it.state == BundleState.STORED && it.complete &&
                it.fromNeighbor != contact.nodeId.toString() && nowHour < it.storedUntilHour(node.limits.maxStoreHours)
        }
        return PeerSyncNeed(contact.nodeId, own.size - failed, failed, carried)
    }

    /**
     * After any exchange with [StoreForwardSyncResult.peer] (either role, automatic or tapped), adds an
     * attempt to every own message still queued for that peer when the exchange counts as an attempt.
     * Returns the new attempt counts of those messages.
     */
    fun recordExchange(node: StoreForwardNode, ledger: OutboxAttemptLedger, result: StoreForwardSyncResult): Map<String, Int> {
        if (!OutboxStatusRules.countsAsAttempt(result.outcome, result.chunksSent)) return emptyMap()
        return ledger.recordAttempt(liveOwnQueuedTo(node, result.peer).map { it.idHex })
    }

    fun status(ledger: OutboxAttemptLedger, bundle: StoredBundle, policy: AutoSyncPolicy): OutboxStatus? =
        if (bundle.role != BundleRole.OWN) null
        else OutboxStatusRules.status(bundle.state, ledger.attempts(bundle.idHex), ledger.carried(bundle.idHex), policy.maxAttempts)
}

/** Why an automatic exchange may not start now; null when it may. Media always wins. */
data class AutoSyncGate(
    val onCallScreen: Boolean,
    val endpointIdle: Boolean,
    val callActive: Boolean,
    val liveMedia: Boolean,
    val exchangeRunning: Boolean,
    val userSelectionPending: Boolean,
    val thermalBlocked: Boolean,
) {
    fun blockedBy(): String? = when {
        callActive -> "call"
        liveMedia -> "media"
        exchangeRunning -> "exchange_running"
        userSelectionPending -> "user_selection"
        !onCallScreen -> "screen"
        !endpointIdle -> "endpoint_busy"
        thermalBlocked -> "thermal"
        else -> null
    }
}

sealed interface AutoSyncDecision {
    data class Start(val reason: AutoSyncReason) : AutoSyncDecision
    data class Wait(val untilMs: Long, val reason: AutoSyncReason) : AutoSyncDecision
    data class Skip(val why: String) : AutoSyncDecision
}

/**
 * Per-peer exponential backoff with a cap and jitter, one automatic exchange at a time. Keys are
 * the 12-hex-character node prefixes discovery reports. Results from exchanges this phone answered
 * count too, so two phones on the Call screen do not take turns dialling each other. Process memory
 * only, like Briar's poller: a restart simply allows one early attempt.
 */
class StoreForwardAutoSyncScheduler(
    private val policy: AutoSyncPolicy,
    private val localKey: String,
    private val random: () -> Double = Math::random,
) {
    private class Peer(val firstSeenMs: Long) {
        var failures = 0
        var lastEndMs: Long? = null
        var jitterFactor = 1.0
    }

    private val peers = HashMap<String, Peer>()
    private var inFlightKey: String? = null
    private var inFlightSinceMs = 0L

    fun intervalMs(reason: AutoSyncReason, failures: Int): Long {
        val (min, max) = if (reason == AutoSyncReason.PULL) policy.pullMinMs to policy.pullMaxMs else policy.outboxMinMs to policy.outboxMaxMs
        val grown = min * policy.base.pow(failures.coerceIn(0, 32))
        return grown.coerceAtMost(max.toDouble()).toLong()
    }

    @Synchronized fun failures(key: String): Int = peers[key]?.failures ?: 0

    @Synchronized fun inFlight(): String? = inFlightKey

    @Synchronized
    fun decide(key: String, nowMs: Long, reason: AutoSyncReason?, blockedBy: String?): AutoSyncDecision {
        expireInFlight(nowMs)
        val peer = peers.getOrPut(key) { Peer(nowMs) }
        if (blockedBy != null) return AutoSyncDecision.Skip(blockedBy)
        if (reason == null) return AutoSyncDecision.Skip("nothing_to_sync")
        if (inFlightKey != null) return AutoSyncDecision.Skip("in_flight")
        val tieBreak = if (localKey > key) policy.tieBreakDelayMs else 0L
        val due = peer.lastEndMs?.let { it + (intervalMs(reason, peer.failures) * peer.jitterFactor).toLong() + tieBreak }
            ?: (peer.firstSeenMs + tieBreak)
        return if (nowMs >= due) AutoSyncDecision.Start(reason) else AutoSyncDecision.Wait(due, reason)
    }

    @Synchronized
    fun begin(key: String, nowMs: Long) {
        peers.getOrPut(key) { Peer(nowMs) }
        inFlightKey = key
        inFlightSinceMs = nowMs
    }

    /** A user tap or live media took over before the link opened: no failure, no new wait. */
    @Synchronized
    fun cancel(key: String) {
        if (inFlightKey == key) inFlightKey = null
    }

    /** The link could not be opened (not responding, collision, refused before BSF1). */
    @Synchronized
    fun onConnectFailed(key: String, nowMs: Long) {
        // Only the attempt in flight: a late failure after its result was recorded is ignored.
        if (inFlightKey == key) finish(key, nowMs, failed = true, keepFailures = false)
    }

    /** Any finished exchange with [key], automatic or not, in either role. */
    @Synchronized
    fun onResult(key: String, nowMs: Long, outcome: StoreForwardSyncOutcome, progressed: Boolean) {
        val suspended = outcome == StoreForwardSyncOutcome.SUSPENDED_LOCAL || outcome == StoreForwardSyncOutcome.SUSPENDED_BY_PEER
        when {
            progressed -> finish(key, nowMs, failed = false, keepFailures = false)
            suspended -> finish(key, nowMs, failed = false, keepFailures = true)
            outcome == StoreForwardSyncOutcome.COMPLETE -> finish(key, nowMs, failed = true, keepFailures = false) // nothing moved
            else -> finish(key, nowMs, failed = true, keepFailures = false)
        }
    }

    private fun finish(key: String, nowMs: Long, failed: Boolean, keepFailures: Boolean) {
        val peer = peers.getOrPut(key) { Peer(nowMs) }
        if (inFlightKey == key) inFlightKey = null
        if (!keepFailures) peer.failures = if (failed) peer.failures + 1 else 0
        peer.lastEndMs = nowMs
        peer.jitterFactor = 1.0 + policy.jitter * (2 * random() - 1)
    }

    private fun expireInFlight(nowMs: Long) {
        val key = inFlightKey ?: return
        if (nowMs - inFlightSinceMs >= policy.inFlightTimeoutMs) finish(key, nowMs, failed = true, keepFailures = false)
    }

    companion object {
        fun keyOf(node: NodeId): String = node.toString().take(DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2)

        fun progressed(result: StoreForwardSyncResult): Boolean =
            result.chunksSent > 0 || result.chunksReceived > 0 || result.completed.isNotEmpty()
    }
}
