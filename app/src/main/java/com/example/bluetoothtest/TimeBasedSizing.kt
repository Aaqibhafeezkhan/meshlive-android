package com.example.bluetoothtest

import kotlin.math.ceil

/**
 * Experimental time-based sizing (design decision 2026-10-14, Q31; off by default; DEBUG launch extras
 * `test_relay_time_queue` and `test_playout_time_target`). The earlier limits are constants: a relay
 * child queue sheds at "2 s minus the viewer's assumed playout" and the viewer's playout grows by
 * "the lateness of one frame plus 50 ms". Neither knows what the link can drain or how unsteady it
 * is. These two policies size the same buffers in milliseconds of media from what is measured:
 *
 * - [RelayTimeQueueSizer]: how long a relay's child queue may hold media before it sheds to the next
 *   keyframe, from the child-measured drain rate (the time one keyframe needs on the wire) plus a
 *   cushion for the drain's own jitter, inside a latency budget.
 * - [PlayoutJitterTarget]: how much delay a viewer's playout needs to cover the arrival jitter it has
 *   measured, applied before a frame is late instead of after.
 *
 * Both stay hop-local (a relay reads its own queue and its direct child's report; a viewer reads its
 * own arrivals), change no wire byte, and fall back to the earlier behaviour wherever they have no
 * measurement or the latency budget is reached. The earlier path is the control arm.
 */
internal object TimeBasedSizing {
    /** Latency budget default: a relay queue never holds more than this much media in the experiment. */
    const val DEFAULT_LATENCY_BUDGET_MS = 1_500L
    /** Smallest relay queue target: the earlier fixed minimum (MIN_STREAM_QUEUE_MS), so a first stall is not shed. */
    const val DEFAULT_FLOOR_MS = 400L
    /** Both policies look back this long (the viewer's calm-before-shrink period for streams). */
    const val WINDOW_NS = 60_000_000_000L
}

/** A sliding window of timestamped samples with quantiles; callers hold their own lock. */
internal class WindowedSamples(private val windowNs: Long, private val maxSamples: Int = 8_192) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Long>()
    private var cacheNs = Long.MIN_VALUE
    private var cacheSorted = LongArray(0)

    val size: Int get() = values.size

    fun add(nowNs: Long, value: Long) {
        times.addLast(nowNs)
        values.addLast(value)
        prune(nowNs)
        while (values.size > maxSamples) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    fun prune(nowNs: Long) {
        while (times.isNotEmpty() && nowNs - times.first() > windowNs) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    fun clear() {
        times.clear()
        values.clear()
        cacheNs = Long.MIN_VALUE
    }

    /**
     * Sorted values, recomputed at most every [maxAgeNs] (0 = every call): the callers read once per
     * frame and a quantile that is a quarter second old is as good as a fresh one.
     */
    fun sorted(nowNs: Long, maxAgeNs: Long): LongArray {
        prune(nowNs)
        if (cacheNs == Long.MIN_VALUE || maxAgeNs <= 0L || nowNs - cacheNs >= maxAgeNs) {
            cacheSorted = values.toLongArray().also { it.sort() }
            cacheNs = nowNs
        }
        return cacheSorted
    }

    companion object {
        /** The [q] quantile (0-1) of an ascending array: the smallest value with at least q of the samples at or below it. */
        fun quantile(sorted: LongArray, q: Double): Long {
            if (sorted.isEmpty()) return 0L
            val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/** Settings of [RelayTimeQueueSizer]; a launch extra sets the two budgets, the rest are constants under test. */
data class RelayTimeQueueConfig(
    /** Longest the child queue may hold media before it sheds (the latency budget). */
    val latencyBudgetMs: Long = TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS,
    /** Shortest target: keeps a scene-cut drain from shedding every keyframe. */
    val floorMs: Long = TimeBasedSizing.DEFAULT_FLOOR_MS,
    /**
     * Drain stalls at this quantile of the window size the cushion. The default is the worst stall of the
     * window (1.0): a stall is one write gap among thousands of takes, so a lower quantile would never see it,
     * and the cushion exists to absorb exactly that. Like the viewer's playout, the queue remembers a stall for
     * the window (60 s) and then forgets it.
     */
    val jitterQuantile: Double = 1.0,
    /** The cushion is this multiple of the stall quantile, plus two frame intervals. */
    val jitterFactor: Double = 1.0,
    val windowNs: Long = TimeBasedSizing.WINDOW_NS,
    /** Fewer stall samples than this say nothing about jitter. */
    val minJitterSamples: Int = 1,
    /** A write gap counts as a stall only beyond this multiple of the airtime the measured rate predicts. */
    val rateTolerance: Double = 1.10,
    /** Offered above this multiple of the drain over [OFFERED_WINDOW_NS] is a sustained overload. */
    val overloadRatio: Double = 1.10,
    /** The stall quantile is recomputed at most this often (0 = every read; tests). */
    val recomputeNs: Long = 100_000_000L,
)

/** What [RelayTimeQueueSizer] currently believes, for the `relay_time_queue` line and the tests. */
internal data class RelayTimeQueueSnapshot(
    val targetMs: Long,
    val legacyBudgetMs: Long,
    val drainKbps: Int,
    val offeredKbps: Int,
    val jitterMs: Long,
    val keyFrameDrainMs: Long,
    val overload: Boolean,
    val jitterSamples: Int,
)

/**
 * Time-based shed target for one relay child queue (Q31; experimental). The target is the media time
 * the queue may hold, not a frame or byte count:
 *
 *     target = clamp(keyframe drain time + cushion, floor, min(latency budget, earlier budget))
 *
 * - Keyframe drain time is the last keyframe's bytes over the child-measured drain rate. A queue
 *   shorter than that sheds every keyframe it is handed, and the recovery keyframe is the largest
 *   record of the stream (the starved-link loop in DECISIONS "Design RT").
 * - The cushion is two frame intervals plus the worst drain stall of the last minute (how long a record waited for the
 *   link beyond its own arrival and the airtime the measured rate predicts for the record before it).
 *   In a sustained overload (offered above the drain) a jitter cushion only adds delay to a
 *   queue that cannot catch up, so the cushion falls back to the two frames.
 * - The ceiling is the smaller of the latency budget and the earlier budget (the viewer's maximum
 *   playout minus what its playout already holds), so the arm never holds more than the earlier path.
 * - With no drain measurement the target is the earlier budget unchanged.
 *
 * Beyond the target the existing shedder acts as before: video before the newest usable keyframe goes,
 * or all queued video while waiting for the next keyframe; audio, configuration and lifecycle records
 * stay. Thread-safe: the queue calls in under its lock and the child's heartbeat reader calls
 * [setDrainKbps].
 */
internal class RelayTimeQueueSizer(
    private val config: RelayTimeQueueConfig = RelayTimeQueueConfig(),
    frameIntervalMs: Long = 66L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val frameIntervalMs = frameIntervalMs.coerceAtLeast(10L)
    private val stalls = WindowedSamples(config.windowNs)
    private val offered = ArrayDeque<Pair<Long, Int>>()
    private var offeredBytesInWindow = 0L
    private var firstOfferedNs = Long.MIN_VALUE
    private var lastKeyFrameBytes = 0
    private var drainKbps = 0
    private var prevTakeNs = Long.MIN_VALUE
    private var prevTakeBytes = 0

    /** The child's measured delivery rate (its arrival report); 0 or less clears it. */
    @Synchronized
    fun setDrainKbps(kbps: Int) {
        drainKbps = kbps.coerceAtLeast(0)
    }

    /** A record was offered to the queue (before any shedding), so the offered rate sees the stream's real load. */
    @Synchronized
    fun onOffered(bytes: Int, keyFrame: Boolean) {
        val nowNs = nanoTime()
        if (firstOfferedNs == Long.MIN_VALUE) firstOfferedNs = nowNs
        offered.addLast(nowNs to bytes)
        offeredBytesInWindow += bytes
        pruneOffered(nowNs)
        if (keyFrame) lastKeyFrameBytes = bytes
    }

    /**
     * The writer took a record of [bytes] that was queued at [enqueuedAtNs]. The earliest the link could
     * start it is the later of its arrival and the previous take plus the previous record's airtime at
     * the drain rate (with the tolerance); the time beyond that is a drain stall. A record that arrived
     * during a pause counts (it waited for the link), and an empty queue's idle wait does not (the
     * record's own arrival is the start).
     */
    @Synchronized
    fun onTaken(bytes: Int, enqueuedAtNs: Long, nowNs: Long = nanoTime()) {
        val previousNs = prevTakeNs
        val previousBytes = prevTakeBytes
        prevTakeNs = nowNs
        prevTakeBytes = bytes
        val drain = drainKbps
        if (previousNs == Long.MIN_VALUE || drain <= 0) return
        val expectedNs = (previousBytes * 8_000_000L / drain * config.rateTolerance).toLong()
        val readyNs = maxOf(enqueuedAtNs, previousNs + expectedNs)
        stalls.add(nowNs, (nowNs - readyNs).coerceAtLeast(0L) / 1_000_000L)
    }

    /** The shed target in ms of media; [legacyBudgetMs] is what the earlier path would use. */
    @Synchronized
    fun targetMs(legacyBudgetMs: Long): Long = snapshotLocked(legacyBudgetMs, nanoTime()).targetMs

    @Synchronized
    fun snapshot(legacyBudgetMs: Long): RelayTimeQueueSnapshot = snapshotLocked(legacyBudgetMs, nanoTime())

    private fun snapshotLocked(legacyBudgetMs: Long, nowNs: Long): RelayTimeQueueSnapshot {
        pruneOffered(nowNs)
        val offeredKbps = offeredKbpsLocked(nowNs)
        val jitterSamples = stalls.sorted(nowNs, config.recomputeNs).size
        val jitterMs = if (jitterSamples >= config.minJitterSamples) {
            WindowedSamples.quantile(stalls.sorted(nowNs, config.recomputeNs), config.jitterQuantile)
        } else {
            0L
        }
        val drain = drainKbps
        if (drain <= 0) {
            return RelayTimeQueueSnapshot(legacyBudgetMs, legacyBudgetMs, 0, offeredKbps, jitterMs, 0L, false, jitterSamples)
        }
        val keyFrameMs = lastKeyFrameBytes * 8L / drain
        val overload = offeredKbps > 0 && offeredKbps > drain * config.overloadRatio
        val twoFrames = 2 * frameIntervalMs
        val cushionMs = twoFrames + if (overload) 0L else (jitterMs * config.jitterFactor).toLong()
        val ceilingMs = minOf(config.latencyBudgetMs, legacyBudgetMs).coerceAtLeast(config.floorMs)
        val targetMs = (keyFrameMs + cushionMs).coerceIn(config.floorMs, ceilingMs)
        return RelayTimeQueueSnapshot(targetMs, legacyBudgetMs, drain, offeredKbps, jitterMs, keyFrameMs, overload, jitterSamples)
    }

    private fun pruneOffered(nowNs: Long) {
        while (offered.isNotEmpty() && nowNs - offered.first().first > OFFERED_WINDOW_NS) {
            offeredBytesInWindow -= offered.removeFirst().second
        }
    }

    /** Offered rate over the last [OFFERED_WINDOW_NS] (or the stream's age while it is younger, at least 1 s). */
    private fun offeredKbpsLocked(nowNs: Long): Int {
        if (firstOfferedNs == Long.MIN_VALUE) return 0
        val spanNs = minOf(OFFERED_WINDOW_NS, nowNs - firstOfferedNs).coerceAtLeast(MIN_OFFERED_SPAN_NS)
        return (offeredBytesInWindow * 8_000_000L / spanNs).toInt()
    }

    companion object {
        const val OFFERED_WINDOW_NS = 5_000_000_000L
        private const val MIN_OFFERED_SPAN_NS = 1_000_000_000L
    }
}

/**
 * Time-based viewer playout target (Q31; experimental), used by [MediaPlayoutClock] for streams. The
 * clock's earlier rule grows its delay by the lateness of the one frame that was late plus 50 ms, so a
 * burst climbs in steps and each step is a visible hold. This target measures the arrival jitter
 * instead: for each arrival, how much later than the luckiest arrival of the window it came, relative
 * to the media's own timestamps. The delay needed to play the window's 99th-percentile arrival on
 * time, plus a margin, is applied before a frame is late. This follows the shape of WebRTC NetEq's
 * delay manager (a high quantile of the relative arrival delay sets the target level), with the
 * viewer's own profile as the floor and the latency budget as the ceiling.
 *
 * Beyond [budgetMs] the earlier lateness growth still works up to the profile's maximum: the budget
 * limits what is paid in advance, not what a late frame can still claim.
 */
class PlayoutJitterTarget(
    val budgetMs: Long = TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS,
    private val windowNs: Long = TimeBasedSizing.WINDOW_NS,
    private val quantile: Double = 0.99,
    private val marginMs: Long = 30L,
    private val minSamples: Int = 45,
    private val recomputeNs: Long = 250_000_000L,
) {
    private val offsets = WindowedSamples(windowNs)

    /** Times the clock grew its delay to this target. */
    var grows: Int = 0
        private set

    /** The last needed delay, ms, or -1 before enough samples. */
    var lastNeededMs: Long = -1L
        private set

    /** Offset of one arrival: how long after its timestamp it arrived, in microseconds. */
    fun onArrival(nowNs: Long, presentationTimeUs: Long) {
        offsets.add(nowNs, nowNs / 1_000L - presentationTimeUs)
    }

    /** The timestamp timeline jumped; offsets before the jump say nothing about the new one. */
    fun reset() {
        offsets.clear()
        lastNeededMs = -1L
    }

    /** Delay (ms, from arrival to presentation) that plays the window's [quantile] arrival on time, or null with too few samples. */
    fun neededDelayMs(nowNs: Long): Long? {
        val sorted = offsets.sorted(nowNs, recomputeNs)
        if (sorted.size < minSamples) return null
        val spreadUs = WindowedSamples.quantile(sorted, quantile) - sorted.first()
        return (spreadUs / 1_000L + marginMs).also { lastNeededMs = it }
    }

    fun noteGrow() {
        grows++
    }

    fun summary(): String = "neededMs=$lastNeededMs | budgetMs=$budgetMs | grows=$grows"

    companion object {
        /** The target for a stream clock, or null (control arm) unless the DEBUG launch extra is on. */
        fun fromTunables(): PlayoutJitterTarget? =
            if (DebugTunables.playoutTimeTarget) PlayoutJitterTarget(DebugTunables.timeQueueBudgetMs) else null
    }
}
