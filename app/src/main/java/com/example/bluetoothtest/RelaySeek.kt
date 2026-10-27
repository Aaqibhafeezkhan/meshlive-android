package com.example.bluetoothtest

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Experimental on-demand relayed route search (DECISIONS "Design OR", revision 3; PROTOCOL "Draft: on-demand route seek").
 * DEBUG launch extras only, all off by default and effective only with relayed calls on. With them off nothing in this
 * file runs: message 5 is rejected as unknown, `RELAY_SEEK` is never signed and helpers keep their tables and pages.
 */
object RelaySeekFeature {
    /** Arm O: helpers run no table scan and no reach pages; contacts are found by searching on tap. */
    const val EXTRA_ON_DEMAND = "test_relayed_on_demand"

    /** Arm B: tables and pages kept, searches answered and sent too. */
    const val EXTRA_KEEP_TABLES = "test_relayed_on_demand_keep_tables"
    const val EXTRA_SCAN_MS = "test_relayed_seek_scan_ms"
    const val EXTRA_FIRST_HOPS = "test_relayed_seek_first_hops"
    const val EXTRA_FANOUT = "test_relayed_seek_fanout"
    const val EXTRA_ORDER = "test_relayed_seek_order"
    const val EXTRA_CONNECT_MS = "test_relayed_seek_connect_ms"
    const val EXTRA_BUDGET_SCALE = "test_relayed_seek_budget_scale"
    const val EXTRA_NO_BACKOFF = "test_relayed_seek_no_backoff"

    @Volatile var onDemand = false
    @Volatile var keepTables = false
    @Volatile var scanMs = RelaySeek.DEFAULT_SCAN_MS
    @Volatile var firstHops = RelaySeek.DEFAULT_FIRST_HOPS
    @Volatile var fanout = RelaySeek.DEFAULT_FANOUT
    @Volatile var farFirst = false
    @Volatile var connectMs = RelaySeek.DEFAULT_CONNECT_MS
    @Volatile var budgetScale = 1
    @Volatile var noBackoff = false

    /** Either arm: this phone sends and answers searches (relayed calls must be on). */
    fun active(): Boolean = RelayedCallsFeature.enabled && (onDemand || keepTables)

    /** Arm O exactly: no reach-table scan and no reach pages on this phone. */
    fun tablesOff(): Boolean = RelayedCallsFeature.enabled && onDemand && !keepTables

    /**
     * OR-1 scope (revision 3): rotating IDs and BMH3 on; with Amendment CP not built, pseudonyms off on helpers and
     * targets. The caller's own pseudonym setting is never restricted (its link edge is a throwaway).
     */
    fun helperScope(rotatingIds: Boolean, bmh3: Boolean, pseudonyms: Boolean): Boolean = rotatingIds && bmh3 && !pseudonyms

    fun callerScope(rotatingIds: Boolean, bmh3: Boolean): Boolean = rotatingIds && bmh3

    /** Parses the DEBUG extras (benchmark launches only); anything out of range falls back to the default. */
    fun apply(
        benchmarkLaunch: Boolean,
        bool: (String, Boolean) -> Boolean,
        int: (String, Int) -> Int,
        string: (String) -> String?,
    ) {
        onDemand = benchmarkLaunch && bool(EXTRA_ON_DEMAND, false)
        keepTables = benchmarkLaunch && bool(EXTRA_KEEP_TABLES, false)
        scanMs = (if (benchmarkLaunch) int(EXTRA_SCAN_MS, 0) else 0).toLong()
            .takeIf { it in 1_000L..6_000L } ?: RelaySeek.DEFAULT_SCAN_MS
        firstHops = (if (benchmarkLaunch) int(EXTRA_FIRST_HOPS, 0) else 0).takeIf { it in 1..3 } ?: RelaySeek.DEFAULT_FIRST_HOPS
        fanout = (if (benchmarkLaunch) int(EXTRA_FANOUT, 0) else 0).takeIf { it in 1..2 } ?: RelaySeek.DEFAULT_FANOUT
        farFirst = benchmarkLaunch && string(EXTRA_ORDER) == "far"
        connectMs = (if (benchmarkLaunch) int(EXTRA_CONNECT_MS, 0) else 0).toLong()
            .takeIf { it in 1_000L..10_000L } ?: RelaySeek.DEFAULT_CONNECT_MS
        budgetScale = (if (benchmarkLaunch) int(EXTRA_BUDGET_SCALE, 0) else 0).takeIf { it in 1..20 } ?: 1
        noBackoff = benchmarkLaunch && bool(EXTRA_NO_BACKOFF, false)
    }

    fun line(): String = "relayed_seek_config: on_demand=$onDemand | keep_tables=$keepTables | scan_ms=$scanMs | " +
        "first_hops=$firstHops | fanout=$fanout | order=${if (farFirst) "far" else "strong"} | connect_ms=$connectMs | " +
        "budget_scale=$budgetScale | no_backoff=$noBackoff"
}

/** Constants and the token of revision 3. */
object RelaySeek {
    const val DEFAULT_SCAN_MS = 3_000L
    const val DEFAULT_FIRST_HOPS = 3
    const val DEFAULT_FANOUT = 2
    const val DEFAULT_CONNECT_MS = 4_000L

    /** Hard cap on the wire: two relays. */
    const val MAX_BUDGET = 2

    /** The caller gives up 45 s after the tap, as the proactive arm does for a contact call. */
    const val ATTEMPT_MS = WeakPairReach.RELAYED_CONTACT_WAIT_MS

    /** Minimum allowance after the expected connection time: budget 1 (1 s scan, 6 s onward, 2 s grace) and budget 2. */
    const val MIN_ALLOWANCE_BUDGET1_MS = 9_000L
    const val MIN_ALLOWANCE_BUDGET2_MS = 14_000L

    /** What a helper keeps for the onward link to the target after its scan. */
    const val ONWARD_TARGET_MS = 6_000L

    /** Below this much time a helper does not start a search. */
    const val MIN_SEARCH_MS = 3_000L

    const val COLLECT_AFTER_MATCH_MS = 1_000L
    const val MAX_TARGET_ADDRESSES = 3
    const val DIALS_PER_REQUEST = 3
    const val OPPORTUNISTIC_CACHE_MS = 5_000L
    const val CHILD_CACHE_MS = 10_000L
    const val OFFER_FRESH_AT_DIAL_MS = 10_000L
    const val REFRESH_WAIT_MS = 1_000L
    const val MAX_SKIP = 3
    const val SKIP_PREFIX_BYTES = 2
    const val NONCE_BYTES = 8
    const val TOKEN_BYTES = 6

    /** `RELAY_SEEK` feature bits (BMH3 extension type 5). */
    const val FEATURE_SEEK_V1 = 1
    const val FEATURE_PROOF_RESERVED = 1 shl 1
    const val FEATURE_PROBE_RESERVED = 1 shl 2

    val TOKEN_DOMAIN: ByteArray = "BluetoothVideoMesh/RelaySeek/Token/1".toByteArray(Charsets.US_ASCII) + 0

    /** `token = SHA-256(domain || nonce || RDI)[0:6]`: a lookup hint, never identity proof. */
    fun token(nonce: ByteArray, rdi: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES && rdi.size == DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(TOKEN_DOMAIN)
        digest.update(nonce)
        digest.update(rdi)
        return digest.digest().copyOf(TOKEN_BYTES)
    }

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray {
        while (true) {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            if (nonce.any { it.toInt() != 0 }) return nonce
        }
    }

    fun minAllowanceMs(budget: Int): Long = if (budget >= 2) MIN_ALLOWANCE_BUDGET2_MS else MIN_ALLOWANCE_BUDGET1_MS

    /** The skip prefix of an RDI: its first two bytes. */
    fun skipPrefix(rdi: ByteArray): ByteArray = rdi.copyOf(SKIP_PREFIX_BYTES)

    fun skipped(rdi: ByteArray, skip: List<ByteArray>): Boolean = skip.any { prefix ->
        rdi.size >= SKIP_PREFIX_BYTES && rdi[0] == prefix[0] && rdi[1] == prefix[1]
    }
}

/** Which of the contact's IDs a caller seeks (revision 3, "Selectors"). */
internal object RelaySeekSelectors {
    enum class Kind { PRIMARY, NEIGHBOUR }

    class Selector(val epoch: Long, val rdi: ByteArray, val kind: Kind)

    /**
     * The contact's role-0 IDs that the recognition policy holds ([ids] by epoch, only days whose key the lease has),
     * its centre `myE + skew`, and whether the lease is older than 14 days (recognition widens; search does not).
     */
    class Window(val centreEpoch: Long, val stale: Boolean, val ids: Map<Long, ByteArray>)

    /** 120 s of a 900 s epoch; a DEBUG short epoch keeps the same fraction (its rules keep their meaning in epochs). */
    fun boundaryWindowMs(epochSeconds: Long): Long = epochSeconds * 1_000L * 120L / 900L

    /**
     * The primary selector (a direct recognition's epoch in this epoch, else the centre), then the neighbour on the
     * side of the nearer boundary, only inside the boundary window and never for a stale lease.
     */
    fun selectors(window: Window, directEpoch: Long?, msUntilBoundary: Long, msSinceBoundary: Long, epochSeconds: Long): List<Selector> {
        val primaryEpoch = directEpoch?.takeIf { window.ids.containsKey(it) } ?: window.centreEpoch
        val primary = window.ids[primaryEpoch] ?: return emptyList()
        val out = mutableListOf(Selector(primaryEpoch, primary, Kind.PRIMARY))
        if (window.stale) return out
        val limit = boundaryWindowMs(epochSeconds)
        val neighbourEpoch = when {
            msUntilBoundary <= msSinceBoundary && msUntilBoundary <= limit -> primaryEpoch + 1
            msSinceBoundary < msUntilBoundary && msSinceBoundary <= limit -> primaryEpoch - 1
            else -> null
        }
        neighbourEpoch?.let { epoch -> window.ids[epoch]?.let { out += Selector(epoch, it, Kind.NEIGHBOUR) } }
        return out
    }
}

/**
 * The phone-wide relay duty (revision 3): one at a time, covering a helper's searching, connecting, waiting, relaying and
 * teardown, and a caller's whole attempt. A phone on duty refuses a second request, which keeps every path loop-free even
 * where node IDs cannot be compared.
 */
internal class RelayDutyReservation {
    private var holder: Any? = null

    @Synchronized
    fun tryAcquire(owner: Any): Boolean {
        if (holder != null && holder !== owner) return false
        holder = owner
        return true
    }

    @Synchronized
    fun release(owner: Any) {
        if (holder === owner) holder = null
    }

    val held: Boolean
        @Synchronized get() = holder != null

    @Synchronized
    fun heldBy(owner: Any): Boolean = holder === owner

    companion object {
        val shared = RelayDutyReservation()
    }
}

/**
 * A helper's phone-wide search budgets over rolling windows (revision 3, "Resource envelope"). Only work done is charged;
 * a search that ends in READY is credited back. All limits scale with `test_relayed_seek_budget_scale`.
 */
internal class RelaySeekBudgets(
    private val clock: () -> Long,
    private val scale: () -> Int = { RelaySeekFeature.budgetScale },
) {
    private class Charge(val atMs: Long, val amount: Long, val search: Any?)

    private val duty = ArrayDeque<Charge>()
    private val scans = ArrayDeque<Charge>()
    private val dials = ArrayDeque<Charge>()
    private val handshakes = ArrayDeque<Charge>()
    private var lastSearchEndMs: Long? = null
    private var exhaustedSinceMs: Long? = null

    sealed class Admission {
        class Accept(val allowanceMs: Long) : Admission()
        class Refuse(val reason: String) : Admission()
    }

    private fun trim(now: Long) {
        listOf(duty, scans, dials, handshakes).forEach { queue ->
            while (queue.isNotEmpty() && now - queue.first().atMs >= WINDOW_MS) queue.removeFirst()
        }
    }

    private fun limit(base: Long): Long = base * scale().coerceIn(1, 20)

    @Synchronized
    fun dutyLeftMs(now: Long = clock()): Long {
        trim(now)
        return (limit(DUTY_MS) - duty.sumOf { it.amount }).coerceAtLeast(0L)
    }

    @Synchronized
    fun scanLeftMs(now: Long = clock()): Long {
        trim(now)
        return (limit(SCAN_MS) - scans.sumOf { it.amount }).coerceAtLeast(0L)
    }

    @Synchronized
    fun dialsLeft(now: Long = clock()): Int {
        trim(now)
        return (limit(DIALS.toLong()) - dials.size).toInt().coerceAtLeast(0)
    }

    @Synchronized
    fun handshakesLeft(now: Long = clock()): Int {
        trim(now)
        return (limit(HANDSHAKES.toLong()) - handshakes.size).toInt().coerceAtLeast(0)
    }

    /**
     * Whether a request of [budget] asking [requestedMs] fits now; the allowance is shortened to the duty left when the
     * minimum for the budget still fits.
     */
    @Synchronized
    fun admit(budget: Int, requestedMs: Long, now: Long = clock()): Admission {
        trim(now)
        lastSearchEndMs?.let { end -> if (now - end < GAP_MS) return Admission.Refuse("pacing") }
        if (dialsLeft(now) < 1) return Admission.Refuse("dials")
        if (handshakesLeft(now) < 1) return Admission.Refuse("handshakes")
        val left = dutyLeftMs(now)
        val minimum = RelaySeek.minAllowanceMs(budget)
        if (left < minimum) return Admission.Refuse("duty")
        return Admission.Accept(minOf(requestedMs, left))
    }

    @Synchronized
    fun chargeScan(ms: Long, search: Any?, now: Long = clock()) {
        if (ms > 0) scans.addLast(Charge(now, ms, search))
        noteExhaustion(now)
    }

    @Synchronized
    fun chargeDial(search: Any?, now: Long = clock()) {
        dials.addLast(Charge(now, 1, search))
        noteExhaustion(now)
    }

    @Synchronized
    fun chargeHandshake(search: Any?, now: Long = clock()) {
        handshakes.addLast(Charge(now, 1, search))
        noteExhaustion(now)
    }

    /** The search ended: its duty is charged, or (READY) its duty and scanning are credited back. */
    @Synchronized
    fun finish(search: Any, startedMs: Long, ready: Boolean, now: Long = clock()) {
        lastSearchEndMs = now
        if (ready) {
            scans.removeAll { it.search === search }
        } else {
            duty.addLast(Charge(now, (now - startedMs).coerceAtLeast(0L), search))
        }
        noteExhaustion(now)
    }

    /** Any budget below what the smallest search needs. */
    @Synchronized
    fun exhausted(now: Long = clock()): Boolean =
        dutyLeftMs(now) < RelaySeek.MIN_ALLOWANCE_BUDGET1_MS || dialsLeft(now) < 1 || handshakesLeft(now) < 1

    /** Exhausted continuously for at least [ms] (then the helper flag is withdrawn at the next offer restart). */
    @Synchronized
    fun exhaustedFor(ms: Long, now: Long = clock()): Boolean {
        noteExhaustion(now)
        return exhaustedSinceMs?.let { now - it >= ms } ?: false
    }

    private fun noteExhaustion(now: Long) {
        val isExhausted = dutyLeftMs(now) < RelaySeek.MIN_ALLOWANCE_BUDGET1_MS || dialsLeft(now) < 1 || handshakesLeft(now) < 1
        exhaustedSinceMs = if (isExhausted) exhaustedSinceMs ?: now else null
    }

    @Synchronized
    fun line(now: Long = clock()): String =
        "relayed_seek_budget: duty_ms=${limit(DUTY_MS) - dutyLeftMs(now)} | scan_ms=${limit(SCAN_MS) - scanLeftMs(now)} | " +
            "dials=${limit(DIALS.toLong()) - dialsLeft(now)} | handshakes=${limit(HANDSHAKES.toLong()) - handshakesLeft(now)} | " +
            "exhausted=${exhausted(now)}"

    companion object {
        const val WINDOW_MS = 600_000L
        const val DUTY_MS = 60_000L
        const val SCAN_MS = 30_000L
        const val DIALS = 12
        const val HANDSHAKES = 12
        const val GAP_MS = 10_000L
        const val FLAG_WITHDRAW_AFTER_MS = 60_000L
    }
}

/**
 * Design OR ("Who carries the risk"): an advertiser address that failed the opening of a sought ID is skipped for the rest of
 * that epoch, so a copied target ID costs a helper one dial per epoch, not one per search. Bounded to 64 addresses.
 */
internal class RelaySeekBadAddresses(private val capacity: Int = 64) {
    private val byAddress = LinkedHashMap<String, Long>()

    @Synchronized
    fun record(address: String, epoch: Long) {
        byAddress.remove(address)
        byAddress[address] = epoch
        while (byAddress.size > capacity) byAddress.remove(byAddress.keys.first())
    }

    @Synchronized
    fun bad(address: String, epoch: Long): Boolean = byAddress[address] == epoch
}

/** Only `FAIL` answers are cached (64 entries, 60 s); a cached answer is re-sent with the new request's setup ID. */
internal class RelaySeekFailCache(private val clock: () -> Long, private val capacity: Int = 64, private val ttlMs: Long = 60_000L) {
    private val entries = LinkedHashMap<String, Pair<Long, RelaySetupFailure>>()

    private fun key(seek: RelaySetupMessage.Seek): String =
        seek.nonce.prefixHex() + "/" + seek.token.prefixHex() + "/" + seek.budget + "/" + seek.skip.joinToString(",") { it.prefixHex() }

    @Synchronized
    fun lookup(seek: RelaySetupMessage.Seek): RelaySetupFailure? {
        val now = clock()
        entries.entries.removeAll { now - it.value.first >= ttlMs }
        return entries[key(seek)]?.second
    }

    @Synchronized
    fun store(seek: RelaySetupMessage.Seek, reason: RelaySetupFailure) {
        val now = clock()
        entries.remove(key(seek))
        entries[key(seek)] = now to reason
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    val size: Int @Synchronized get() = entries.size
}

/** One sighting of a cost-0 call offer, as the helper search and the caller's first hops see it. */
internal class SeenOffer<P>(
    val peer: P,
    val address: String,
    val announcement: DiscoveryAnnouncement,
    val rssi: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val count: Int,
) {
    val rdi: ByteArray get() = announcement.nodeIdPrefix
    val consents: Boolean get() = RelayedCalls.consents(announcement, rotating = true)
    val helper: Boolean get() = RelayedCalls.isRelayHelperOffer(announcement, rotating = true)
}

/**
 * The helper's search (revision 3, helper rules 7-11) with every radio effect injected, so the order, the collection, the
 * caps, the deadlines and the final reason are JVM-tested. [plainId] is a plain `SETUP` handled as a search.
 */
internal class RelaySeekSearch<P>(
    private val budget: Int,
    private val nonce: ByteArray?,
    private val token: ByteArray?,
    private val plainId: ByteArray?,
    private val skip: List<ByteArray>,
    private val deadlineMs: Long,
    private val clock: () -> Long,
    private val config: Config,
    private val effects: Effects<P>,
) {
    class Config(
        val scanMs: Long = RelaySeek.DEFAULT_SCAN_MS,
        val fanout: Int = RelaySeek.DEFAULT_FANOUT,
        val connectMs: Long = RelaySeek.DEFAULT_CONNECT_MS,
    )

    enum class DialResult { LINKED, IDENTITY, FAILED }

    sealed class ChildResult {
        class Ready(val relays: Int) : ChildResult()
        class Fail(val reason: RelaySetupFailure) : ChildResult()
        object Identity : ChildResult()
        object NoSeek : ChildResult()
        object Closed : ChildResult()
    }

    interface Effects<P> {
        /** Cost-0 version 5 call offers heard since [sinceMs] on the link's bearer (any scan). */
        fun offers(sinceMs: Long): List<SeenOffer<P>>

        /** Runs an on-demand scan until [untilMs] or [done]; returns the milliseconds scanned (0 when refused). */
        fun scan(untilMs: Long, done: () -> Boolean): Long
        fun isOwnId(rdi: ByteArray): Boolean
        fun stopped(): Boolean

        /** Dials and authenticates the target; [attempts] receives the physical attempts made (PHY retry included). */
        fun dialTarget(offer: SeenOffer<P>, allowRetry: Boolean, attempts: (Int) -> Unit): DialResult

        /** Dials a helper and sends it a regenerated budget-1 `SEEK` with [remainingMs]. */
        fun dialChild(offer: SeenOffer<P>, remainingMs: Int, allowRetry: Boolean, attempts: (Int) -> Unit): ChildResult
    }

    sealed class Outcome<P> {
        class TargetLinked<P>(val offer: SeenOffer<P>) : Outcome<P>()
        class ChildReady<P>(val offer: SeenOffer<P>, val relays: Int) : Outcome<P>()
        class Failed<P>(val reason: RelaySetupFailure) : Outcome<P>()
    }

    /** Physical dials made by this search (capped at [RelaySeek.DIALS_PER_REQUEST]). */
    var dials = 0
        private set
    var scannedMs = 0L
        private set
    var usedCache = false
        private set

    init {
        require(budget in 1..RelaySeek.MAX_BUDGET)
        require(plainId != null || (nonce != null && token != null))
    }

    fun matches(offer: SeenOffer<P>): Boolean {
        if (!offer.consents || !offer.announcement.rotating || effects.isOwnId(offer.rdi)) return false
        plainId?.let { return offer.rdi.contentEquals(it) }
        return RelaySeek.token(requireNotNull(nonce), offer.rdi).contentEquals(token)
    }

    private fun timeLeft(): Long = deadlineMs - clock()

    private fun targetAddresses(since: Long): List<SeenOffer<P>> = effects.offers(since)
        .filter(::matches)
        .groupBy { it.address }
        .map { (_, sightings) -> sightings.maxByOrNull { it.lastSeenMs }!! }
        .sortedWith(compareByDescending<SeenOffer<P>> { it.count >= 2 }.thenByDescending { it.rssi })
        .take(RelaySeek.MAX_TARGET_ADDRESSES)

    fun run(): Outcome<P> {
        val startMs = clock()
        if (timeLeft() < RelaySeek.MIN_SEARCH_MS) return Outcome.Failed(RelaySetupFailure.TIMEOUT)
        // Opportunistic cache: offers this phone heard in the last 5 s from any scan it ran anyway.
        var targets = targetAddresses(startMs - RelaySeek.OPPORTUNISTIC_CACHE_MS)
        usedCache = targets.isNotEmpty()
        if (targets.isEmpty()) {
            val onward = if (budget >= 2) maxOf(RelaySeek.ONWARD_TARGET_MS, config.connectMs + RelaySeek.MIN_ALLOWANCE_BUDGET1_MS) else RelaySeek.ONWARD_TARGET_MS
            val allowance = minOf(config.scanMs, timeLeft() - onward)
            if (allowance > 0) {
                val scanStart = clock()
                var firstMatchMs: Long? = null
                scannedMs = effects.scan(scanStart + allowance) {
                    val found = targetAddresses(scanStart)
                    if (found.isNotEmpty() && firstMatchMs == null) firstMatchMs = clock()
                    effects.stopped() || found.size >= RelaySeek.MAX_TARGET_ADDRESSES ||
                        (firstMatchMs?.let { clock() - it >= RelaySeek.COLLECT_AFTER_MATCH_MS } ?: false)
                }
                targets = targetAddresses(scanStart)
            }
        }
        var identityFailures = 0
        var targetFailures = 0
        for (offer in targets) {
            if (effects.stopped() || dials >= RelaySeek.DIALS_PER_REQUEST || timeLeft() <= 0) break
            when (effects.dialTarget(offer, allowRetry = RelaySeek.DIALS_PER_REQUEST - dials >= 2) { dials += it }) {
                DialResult.LINKED -> return Outcome.TargetLinked(offer)
                DialResult.IDENTITY -> identityFailures++
                DialResult.FAILED -> targetFailures++
            }
        }
        if (budget < 2) {
            return Outcome.Failed(
                when {
                    timeLeft() <= 0 && targets.isNotEmpty() -> RelaySetupFailure.TIMEOUT
                    identityFailures > 0 && targetFailures == 0 -> RelaySetupFailure.CHILD_AUTHENTICATION
                    else -> RelaySetupFailure.NO_ROUTE
                },
            )
        }
        val matchedAddresses = targets.map { it.address }.toSet()
        val now = clock()
        val children = effects.offers(now - RelaySeek.CHILD_CACHE_MS)
            .filter { it.helper && !effects.isOwnId(it.rdi) && !RelaySeek.skipped(it.rdi, skip) && it.address !in matchedAddresses && !matches(it) }
            .groupBy { it.rdi.prefixHex() }
            .map { (_, sightings) -> sightings.maxByOrNull { it.lastSeenMs }!! }
            .sortedByDescending { it.rssi }
            .take(config.fanout.coerceIn(1, RelaySeek.DEFAULT_FANOUT))
        var reached = false
        var childNoRoute = false
        var ranOut = false
        var lastReason: RelaySetupFailure? = null
        for (child in children) {
            if (effects.stopped()) break
            if (dials >= RelaySeek.DIALS_PER_REQUEST) break
            if (timeLeft() - config.connectMs < RelaySeek.MIN_ALLOWANCE_BUDGET1_MS) {
                ranOut = true
                break
            }
            // Dial-time freshness: a recorded helper not heard for 10 s is skipped (a fresher sighting in the cache counts).
            val fresh = effects.offers(clock() - RelaySeek.OFFER_FRESH_AT_DIAL_MS)
                .filter { it.rdi.contentEquals(child.rdi) && it.helper }
                .maxByOrNull { it.lastSeenMs } ?: continue
            val remaining = (timeLeft() - RelayedCalls.PER_HOP_RESERVE_MS).coerceAtMost(RelayedCalls.SETUP_DEADLINE_MS.toLong())
            if (remaining < RelayedCalls.MIN_REMAINING_MS) {
                ranOut = true
                break
            }
            when (val result = effects.dialChild(fresh, remaining.toInt(), RelaySeek.DIALS_PER_REQUEST - dials >= 2) { dials += it }) {
                is ChildResult.Ready -> return Outcome.ChildReady(fresh, result.relays)
                is ChildResult.Fail -> {
                    reached = true
                    lastReason = result.reason
                    if (result.reason == RelaySetupFailure.NO_ROUTE) childNoRoute = true
                }
                ChildResult.Identity, ChildResult.NoSeek, ChildResult.Closed -> Unit
            }
        }
        if (timeLeft() <= 0) ranOut = true
        // Revision 3, helper rule 10: no route when no target or child was reached or any child answered no route; timeout
        // when the deadline ran out; otherwise the last child's reason.
        return Outcome.Failed(
            when {
                !reached || childNoRoute -> RelaySetupFailure.NO_ROUTE
                ranOut -> RelaySetupFailure.TIMEOUT
                else -> lastReason ?: RelaySetupFailure.NO_ROUTE
            },
        )
    }
}

/**
 * The caller's expanding rings (revision 3, caller rules 3-5): ring 1 (budget 1, primary selector) over the first hops,
 * ring 1b (budget 1, neighbour selector) only inside the boundary window, ring 2 (budget 2, primary, a skip list of the
 * ring 1 misses). Pure: the Android glue dials what [next] returns and reports with [record].
 */
internal class RelaySeekRings(
    private val attemptEndMs: Long,
    private val selectors: List<RelaySeekSelectors.Selector>,
    /** The ordered first hops (RDIs of version 5 helper offers), frozen for the attempt. */
    private val firstHops: List<ByteArray>,
    maxRelays: Int,
    require: Int,
    private val connectMs: Long,
    private val clock: () -> Long,
) {
    enum class Ring { ONE, ONE_B, TWO }

    enum class Outcome { READY, NO_ROUTE, BUSY_OR_REFUSED, BINDING_FAILED, NO_SEEK, AMBIGUOUS, TIMEOUT, STALE, OTHER }

    class Request(
        val hop: ByteArray,
        val ring: Ring,
        val budget: Int,
        val selector: RelaySeekSelectors.Selector,
        val skip: List<ByteArray>,
        val remainingMs: Int,
    )

    private val rings: List<Ring> = buildList {
        val cap = maxRelays.coerceAtMost(RelaySeek.MAX_BUDGET)
        if (require != 2) {
            add(Ring.ONE)
            if (selectors.any { it.kind == RelaySeekSelectors.Kind.NEIGHBOUR }) add(Ring.ONE_B)
        }
        if (cap >= 2 && require != 1) add(Ring.TWO)
    }
    private val excluded = HashSet<String>()
    private val ringOneMisses = LinkedHashSet<String>()
    private var ringIndex = 0
    private var hopIndex = 0
    var requests = 0
        private set
    var dials = 0
        private set
    var done = false
        private set

    /** The hard per-attempt caps: `N(S + 1)` requests and two dials per request. */
    val maxRequests: Int = firstHops.size * rings.size
    val maxDials: Int = 2 * maxRequests

    /** Remaining time a request may carry now: `min(20 s, attempt end - now - 2 s)`. */
    fun remainingNow(now: Long = clock()): Long =
        minOf(RelayedCalls.SETUP_DEADLINE_MS.toLong(), attemptEndMs - now - RelaySetupExchange.SETUP_REPLY_GRACE_MS)

    fun next(now: Long = clock()): Request? {
        if (done || requests >= maxRequests || dials >= maxDials) return null
        while (ringIndex < rings.size) {
            val ring = rings[ringIndex]
            while (hopIndex < firstHops.size) {
                val hop = firstHops[hopIndex++]
                if (hop.prefixHex() in excluded) continue
                val budget = if (ring == Ring.TWO) 2 else 1
                val remaining = remainingNow(now)
                if (remaining - connectMs < RelaySeek.minAllowanceMs(budget)) {
                    // Time only shrinks: a budget-1 request that cannot fit means nothing else can either.
                    if (budget == 1) {
                        done = true
                        return null
                    }
                    break
                }
                val selector = if (ring == Ring.ONE_B) {
                    selectors.first { it.kind == RelaySeekSelectors.Kind.NEIGHBOUR }
                } else {
                    selectors.first()
                }
                val skip = if (ring == Ring.TWO) {
                    ringOneMisses.take(RelaySeek.MAX_SKIP).map { hex -> hexBytes(hex).copyOf(RelaySeek.SKIP_PREFIX_BYTES) }
                } else {
                    emptyList()
                }
                requests++
                return Request(hop, ring, budget, selector, skip, remaining.toInt())
            }
            ringIndex++
            hopIndex = 0
        }
        done = true
        return null
    }

    fun record(request: Request, outcome: Outcome, physicalDials: Int) {
        dials += physicalDials
        val key = request.hop.prefixHex()
        when (outcome) {
            Outcome.READY -> done = true
            Outcome.NO_ROUTE -> if (request.ring == Ring.ONE) ringOneMisses += key
            Outcome.BUSY_OR_REFUSED, Outcome.BINDING_FAILED, Outcome.NO_SEEK, Outcome.AMBIGUOUS -> excluded += key
            Outcome.TIMEOUT, Outcome.STALE, Outcome.OTHER -> Unit
        }
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    companion object {
        fun outcomeFor(reason: RelaySetupFailure?): Outcome = when (reason) {
            null -> Outcome.OTHER
            RelaySetupFailure.NO_ROUTE -> Outcome.NO_ROUTE
            RelaySetupFailure.BUSY, RelaySetupFailure.REFUSED -> Outcome.BUSY_OR_REFUSED
            RelaySetupFailure.CHILD_AUTHENTICATION -> Outcome.OTHER
            RelaySetupFailure.TIMEOUT -> Outcome.TIMEOUT
            RelaySetupFailure.LOOP -> Outcome.OTHER
        }
    }
}

/**
 * The caller's memory (revision 3, item 9): the helper that last reached a contact in this epoch, "helper X found no route
 * for selector S" for 60 s, and the per-contact backoff after "Can't find" (30 s doubling to 120 s).
 */
internal class RelaySeekCallerMemory(private val clock: () -> Long) {
    private val reachedBy = HashMap<String, Pair<Long, String>>()
    private val noRoute = HashMap<String, Long>()
    private val backoff = HashMap<String, Pair<Long, Long>>()

    @Synchronized
    fun rememberReached(contact: String, epoch: Long, hop: ByteArray) {
        reachedBy[contact] = epoch to hop.prefixHex()
    }

    @Synchronized
    fun reachedBy(contact: String, epoch: Long): String? = reachedBy[contact]?.takeIf { it.first == epoch }?.second

    @Synchronized
    fun rememberNoRoute(hop: ByteArray, selector: ByteArray, budget: Int) {
        noRoute[hop.prefixHex() + "/" + selector.prefixHex() + "/" + budget] = clock()
    }

    @Synchronized
    fun recentlyNoRoute(hop: ByteArray, selector: ByteArray, budget: Int): Boolean {
        val now = clock()
        noRoute.entries.removeAll { now - it.value >= NO_ROUTE_MS }
        return noRoute.containsKey(hop.prefixHex() + "/" + selector.prefixHex() + "/" + budget)
    }

    /** "Can't find": the search for [contact] waits 30 s, then 60 s, then 120 s. */
    @Synchronized
    fun failed(contact: String) {
        val now = clock()
        val previous = backoff[contact]?.second ?: 0L
        val next = if (previous == 0L) BACKOFF_FIRST_MS else (previous * 2).coerceAtMost(BACKOFF_MAX_MS)
        backoff[contact] = (now + next) to next
    }

    /** The contact was heard directly or a new helper appeared, or a search succeeded. */
    @Synchronized
    fun reset(contact: String) {
        backoff.remove(contact)
    }

    /** Milliseconds until a relayed search for [contact] may run again (0 = now). */
    @Synchronized
    fun backoffMs(contact: String, disabled: Boolean = RelaySeekFeature.noBackoff): Long {
        if (disabled) return 0L
        val until = backoff[contact]?.first ?: return 0L
        return (until - clock()).coerceAtLeast(0L)
    }

    companion object {
        const val NO_ROUTE_MS = 60_000L
        const val BACKOFF_FIRST_MS = 30_000L
        const val BACKOFF_MAX_MS = 120_000L
    }
}

/** Debug lines of the search (no token, nonce or ID ever; DEBUG builds tag them for the scorer). */
internal object RelaySeekLog {
    fun line(
        role: String,
        result: String,
        ring: RelaySeekRings.Ring? = null,
        budget: Int? = null,
        selector: RelaySeekSelectors.Kind? = null,
        scanMs: Long? = null,
        dials: Int? = null,
        candidate: Int? = null,
        reason: String? = null,
    ): String = buildString {
        append("relayed_seek: role=").append(role).append(" | result=").append(result)
        ring?.let { append(" | ring=").append(it.name.lowercase()) }
        budget?.let { append(" | budget=").append(it) }
        selector?.let { append(" | selector=").append(it.name.lowercase()) }
        scanMs?.let { append(" | scan_ms=").append(it) }
        dials?.let { append(" | dials=").append(it) }
        candidate?.let { append(" | candidate=").append(it) }
        reason?.let { append(" | reason=").append(it) }
    }
}
