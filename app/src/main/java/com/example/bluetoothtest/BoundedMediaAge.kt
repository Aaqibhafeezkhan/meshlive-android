package com.example.bluetoothtest

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.BlockingQueue

/**
 * Experimental bounded media age (design decision 2026-10-09: A/B before choosing; off by default,
 * DebugTunables.experimentalBoundedMediaAge). No frame loss stays the default.
 *
 * Buffering absorbs bursts but not sustained overload: 600 kbps offered into 300 kbps delivered grows
 * the delay by about 1 s per second until rate control catches up. This policy bounds the age instead:
 *
 * - Calls keep speech near [CALL_MOUTH_TO_EAR_BUDGET_MS]. Audio is never dropped and always goes
 *   first. Video degrades in order: lower bitrate (LinkRateController, which also lowers the frame
 *   rate below the geometry floor), skip verified non-reference frames, skip stale video to the next
 *   keyframe, and finally pause video (audio only) until the link recovers, resuming at a keyframe.
 *   Resolution is not lowered: a call receiver rejects a second stream header and the Camera2 output
 *   size is fixed for the session, so no safe in-call reconfiguration exists yet.
 * - Streams buffer up to about [STREAM_AGE_BUDGET_MS]. Beyond it a link's queue sheds stale video
 *   codec-safely: everything before the newest queued keyframe, or all queued video while waiting for
 *   (and requesting) the next keyframe. Audio, codec configuration, rotation and lifecycle records
 *   are never shed.
 *
 * Every drop happens on a phone's own outgoing queue before that hop seals its records (direct
 * sender and call queues hold plaintext; BME1 and BMS1 sequences are assigned when written), or on a
 * relay's child queue, which copies whole BME1 envelopes: the child accepts increasing mesh/BME1
 * sequence gaps and still rejects replay and reordering (PROTOCOL.md). Hop-local: each queue acts on
 * its own age and its direct child's report about that link only.
 */
internal object BoundedMediaAge {
    /** Top of the 300-400 ms mouth-to-ear range (ITU-T G.114 "acceptable" bound). */
    const val CALL_MOUTH_TO_EAR_BUDGET_MS = 400L
    /** A call's own queue always gets at least this much before video is shed (about 1.5 frames at 15 FPS). */
    const val MIN_CALL_VIDEO_QUEUE_MS = 100L
    const val STREAM_AGE_BUDGET_MS = 2_000L
    /**
     * A stream queue never sheds below the backlog the rate controller already treats as congestion,
     * so a pinned viewer delay cannot make every scene-cut drain a shed.
     */
    const val MIN_STREAM_QUEUE_MS = LinkRateController.CONGESTED_BACKLOG_MS.toLong()
    /**
     * Assumed starting playout delay of a stream viewer (the Auto profile); the viewer reports only
     * the delay it added on top. An assumption, not a measurement: viewers on other profiles differ.
     */
    val ASSUMED_VIEWER_BASE_DELAY_MS: Long = PlaybackDelayProfile.AUTO.delayMs
    /**
     * The peer's call playout starting delay. Call setup does not exchange it: the handshake carries
     * node capabilities only (transports, codecs, maximum bitrate, children), and each phone picks its
     * call profile from its own setting and stack (privateCallPlaybackDelayProfile). Assume the
     * largest call base (CALL_STABLE, 500 ms) so the budget never allows more video queueing than the
     * peer's playout leaves room for; with a smaller real base video is held shorter than necessary
     * (the 100 ms floor), never longer.
     */
    val ASSUMED_PEER_CALL_BASE_DELAY_MS: Long =
        PlaybackDelayProfile.entries.filter { it.stabilityLabel == "Call" }.maxOf { it.delayMs }
    /**
     * Extra call-queue slots past the nominal capacity for records that are never dropped or evicted
     * (audio, codec configuration, recovery keyframes) once no video is left to shed: at least 256
     * (over 5 s of 20 ms audio). Only an arriving record is refused when even that is exhausted.
     */
    fun criticalHeadroom(capacity: Int): Int = maxOf(capacity, 256)
    /** After a stream shed, the recovery keyframe and the rate cut need time to show their effect. */
    const val STREAM_SHED_COOLDOWN_NS = 4_000_000_000L
    const val CALL_SHED_COOLDOWN_NS = 1_500_000_000L
    /**
     * While a shed waits for a keyframe, ask again this often until one arrives. A single request can
     * be refused by the encoder's backoff (1 s doubling to 8 s for unforced requests) or coalesced
     * upstream, which left a child waiting for the 60 s safety keyframe. Requests stay coalesced: the
     * shared encoder honours at most one per backoff interval for all children.
     */
    const val KEY_FRAME_REREQUEST_NS = 1_500_000_000L
    /**
     * A relay child's recovery requests back off from [KEY_FRAME_REREQUEST_NS] by doubling up to this
     * cap while its sheds keep recurring. Each request makes the shared source encoder send a
     * keyframe on every branch, and on the slow child that keyframe's envelope holds later speech for
     * its whole transmission (about 1.5 s for 55 KiB at 300 kbps; the relay keeps the source's
     * envelope order). The normal keyframe interval is 60 s, so requests cannot stop altogether
     * without freezing the child that long. Review finding 2026-10-09.
     */
    const val RELAY_RECOVERY_MAX_INTERVAL_NS = 15_000_000_000L
    /** A relay child that has not needed recovery for this long starts again at the short interval. */
    const val RELAY_RECOVERY_CALM_NS = 60_000_000_000L

    val enabled: Boolean get() = DebugTunables.experimentalBoundedMediaAge

    /**
     * A relay's child queue sheds under the global experiment or the relay-child-only experiment
     * (DebugTunables.experimentalRelayChildMediaAge); everything else follows [enabled] alone.
     */
    fun relayChildEnabled(
        global: Boolean = enabled,
        relayChildOnly: Boolean = DebugTunables.experimentalRelayChildMediaAge,
    ): Boolean = global || relayChildOnly

    /** Policy label for BENCHMARK lines, written in both arms. */
    fun policyLabel(enabled: Boolean = this.enabled): String = if (enabled) "bounded-experimental" else "noloss"

    /**
     * How long this phone's stream queue for one child may hold media: the stream budget minus what
     * the child's playout already holds (its starting delay plus the added delay it reports).
     */
    fun streamQueueBudgetMs(
        viewerAddedDelayMs: Int,
        viewerBaseDelayMs: Long = ASSUMED_VIEWER_BASE_DELAY_MS,
    ): Long = (STREAM_AGE_BUDGET_MS - viewerBaseDelayMs - viewerAddedDelayMs.coerceAtLeast(0))
        .coerceAtLeast(MIN_STREAM_QUEUE_MS)

    /** How long call video may wait in this phone's queue so speech-synchronous video stays near the budget. */
    fun callVideoQueueBudgetMs(peerAddedDelayMs: Int, peerBaseDelayMs: Long): Long =
        (CALL_MOUTH_TO_EAR_BUDGET_MS - peerBaseDelayMs.coerceAtLeast(0L) - peerAddedDelayMs.coerceAtLeast(0))
            .coerceIn(MIN_CALL_VIDEO_QUEUE_MS, CALL_MOUTH_TO_EAR_BUDGET_MS)

    /** Verified non-reference frames are skipped from half the budget, before any reference frame is shed. */
    fun thinThresholdMs(budgetMs: Long): Long = budgetMs / 2

    /**
     * The non-reference step is AV1 only. An AV1 frame with refresh_frame_flags == 0 changes no
     * reference slot, saved CDFs or motion fields. VP9 also carries state from the previous decoded
     * frame (previous-frame motion vectors, segmentation, loop-filter deltas) unless every following
     * frame is error resilient; hardware VP9 output is not (BitstreamDroppability then marks nothing),
     * and a mid-session switch could not be proven safe, so VP9 falls back to skip-to-keyframe.
     */
    fun nonReferenceSkipAllowed(codecId: Int): Boolean = codecId == StreamProtocol.CODEC_AV1
}

/** A call queue item by its media flags: audio and codec configuration (csd) are never shed. */
internal fun callMediaShedClass(flags: Int): ShedClass = when {
    flags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION) != 0 ->
        ShedClass.KEEP
    flags and StreamProtocol.FLAG_KEY_FRAME != 0 -> ShedClass.KEY_FRAME
    else -> ShedClass.VIDEO
}

/** Result of [offerCodecSafely]. */
internal class BoundedOffer<T>(
    val queued: Boolean,
    /** Queued video removed to make room (never audio, codec configuration or kept keyframes). */
    val shed: List<T>,
    /** All queued video was shed (or the arriving inter frame dropped): drop video until a keyframe and request one. */
    val awaitKeyFrame: Boolean,
    /** A critical record was refused because even the headroom is full; nothing queued was evicted. */
    val criticalOverflow: Boolean,
)

/**
 * Bounded media age: queues [item] within the nominal [capacity] of [queue] (whose physical capacity
 * adds headroom), making room from video only and codec-safely: first everything before the newest
 * queued keyframe, then all queued video. Audio, codec configuration and keyframes ([critical]) are
 * never dropped and never evict each other; when no video is left they use the headroom. A
 * non-critical inter frame is dropped when the shed left nothing it could reference.
 */
internal fun <T> offerCodecSafely(
    lock: Any,
    queue: BlockingQueue<T>,
    item: T,
    capacity: Int,
    critical: Boolean,
    classify: (T) -> ShedClass,
): BoundedOffer<T> = synchronized(lock) {
    if (queue.size < capacity && queue.offer(item)) return@synchronized BoundedOffer(true, emptyList(), false, false)
    val keyFrame = classify(item) == ShedClass.KEY_FRAME
    val shed = ArrayList<T>()
    var await = false
    for (keepNewestKeyFrame in booleanArrayOf(true, false)) {
        val (plan, removed) = shedAtomically(lock, queue, classify) { keepNewestKeyFrame }
        shed += removed
        if (removed.isNotEmpty() && plan.awaitKeyFrame) await = true
        if (queue.size < capacity) break
    }
    if (keyFrame) await = false
    if (!critical) {
        if (!await && queue.size < capacity && queue.offer(item)) return@synchronized BoundedOffer(true, shed, false, false)
        // Its reference was shed, or only audio/configuration fills the queue: wait for a keyframe.
        return@synchronized BoundedOffer(false, shed, true, false)
    }
    if (queue.offer(item)) return@synchronized BoundedOffer(true, shed, await, false)
    BoundedOffer(false, shed, await, true)
}

/**
 * Plans and removes a codec-safe shed from [queue] while holding [lock], the monitor the writer takes
 * under ([takeNextUnderLock]). Without it the writer could take an item between two removals and
 * write a frame whose reference was just removed (a partial GOP). Returns the plan and the removed
 * items.
 */
internal fun <T> shedAtomically(
    lock: Any,
    queue: BlockingQueue<T>,
    classify: (T) -> ShedClass,
    usableKeyFrame: (T) -> Boolean,
): Pair<ShedPlan, List<T>> = synchronized(lock) {
    val queued = queue.toList()
    val plan = planCodecSafeShed(queued, classify, usableKeyFrame)
    if (plan.drop.isEmpty()) return@synchronized plan to emptyList()
    val doomed = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    plan.drop.forEach { doomed.add(queued[it]) }
    val removed = ArrayList<T>(doomed.size)
    val iterator = queue.iterator()
    while (iterator.hasNext()) {
        val item = iterator.next()
        if (item in doomed) {
            iterator.remove()
            removed += item
        }
    }
    plan to removed
}

/**
 * Writer side of [shedAtomically]: the next item (priority items such as audio first) taken under
 * [lock]. Waits up to [timeoutMs], or indefinitely when null (interruptible, like BlockingQueue.take).
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal fun <T> takeNextUnderLock(lock: Object, queue: BlockingQueue<T>, isPriority: (T) -> Boolean, timeoutMs: Long?): T? {
    val deadlineNs = timeoutMs?.let { System.nanoTime() + it * 1_000_000L }
    synchronized(lock) {
        while (true) {
            val priority = queue.firstOrNull(isPriority)
            if (priority != null && queue.remove(priority)) return priority
            queue.poll()?.let { return it }
            val waitMs = if (deadlineNs == null) {
                WRITER_WAIT_SLICE_MS
            } else {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return null
                (remainingNs / 1_000_000L).coerceIn(1L, WRITER_WAIT_SLICE_MS)
            }
            lock.wait(waitMs)
        }
    }
}

/** Producer side of [takeNextUnderLock]. */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal fun <T> putUnderLock(lock: Object, queue: BlockingQueue<T>, item: T) {
    synchronized(lock) {
        queue.put(item)
        lock.notifyAll()
    }
}

private const val WRITER_WAIT_SLICE_MS = 100L

/** What a queued item is to the shedder. */
enum class ShedClass {
    /** Audio, codec configuration, rotation, lifecycle, headers and controls: never shed. */
    KEEP,
    /** Ordinary (reference or non-reference) video. */
    VIDEO,
    /** A keyframe: a decodable point. */
    KEY_FRAME,
}

data class ShedPlan(
    /** Indices (in queue order) of the items to remove. */
    val drop: List<Int>,
    /** No keyframe stays queued: later non-key video must be dropped until one arrives (and one is requested). */
    val awaitKeyFrame: Boolean,
)

/**
 * Codec-safe shedding of one ordered queue. With a usable keyframe queued (one still inside the age
 * budget, see [usableKeyFrame]), all video before the newest usable keyframe goes (older keyframes
 * included) and everything after it stays decodable. Without one, all queued video goes and the
 * caller waits for (and requests) the next keyframe: resuming at a keyframe that is itself older than
 * the budget would not bring the age back. Non-video is never touched.
 */
fun <T> planCodecSafeShed(
    items: List<T>,
    classify: (T) -> ShedClass,
    usableKeyFrame: (T) -> Boolean = { true },
): ShedPlan {
    val classes = items.map(classify)
    val newestKey = classes.indices.lastOrNull { classes[it] == ShedClass.KEY_FRAME && usableKeyFrame(items[it]) }
    return if (newestKey != null) {
        ShedPlan(classes.indices.filter { it < newestKey && classes[it] != ShedClass.KEEP }, awaitKeyFrame = false)
    } else {
        ShedPlan(classes.indices.filter { classes[it] != ShedClass.KEEP }, awaitKeyFrame = true)
    }
}

/** Per-queue shedding state: when to shed and the wait for a decodable point afterwards. */
class MediaAgeShedder(private val cooldownNs: Long) {
    private var lastShedNs = Long.MIN_VALUE

    var awaitingKeyFrame: Boolean = false
        private set
    var shedEvents: Long = 0
        private set
    var shedFrames: Long = 0
        private set
    /** Arriving frames dropped while waiting for a keyframe (also counted in [shedFrames]). */
    var keyFrameWaitFrames: Long = 0
        private set

    /**
     * An arriving video item while a shed waits for a decodable point: true means drop it. A keyframe
     * ends the wait and is kept.
     */
    fun dropArrivingVideo(keyFrame: Boolean): Boolean {
        if (!awaitingKeyFrame) return false
        if (keyFrame) {
            awaitingKeyFrame = false
            return false
        }
        keyFrameWaitFrames++
        shedFrames++
        return true
    }

    /** The queue is older than its budget outside a large-frame drain and the last shed has had time to act. */
    fun shouldShed(queueAgeMs: Long, budgetMs: Long, largeFrameGrace: Boolean, nowNs: Long): Boolean =
        !awaitingKeyFrame && !largeFrameGrace && queueAgeMs > budgetMs &&
            (lastShedNs == Long.MIN_VALUE || nowNs - lastShedNs >= cooldownNs)

    fun recordShed(plan: ShedPlan, removed: Int, nowNs: Long) {
        lastShedNs = nowNs
        shedEvents++
        shedFrames += removed
        if (plan.awaitKeyFrame) awaitingKeyFrame = true
    }

    /** Frames dropped for another bounded-age reason (non-reference thinning, paused video). */
    fun countDropped(frames: Long = 1) {
        shedFrames += frames
    }

    fun reset() {
        awaitingKeyFrame = false
    }
}

/**
 * The last call step: pause outgoing video (audio only) when shedding keeps recurring, and probe
 * again with exponential backoff. Resuming waits for a drained queue and a peer that no longer
 * reports late media; the first video after a resume is a keyframe.
 */
internal class CallVideoPauseGovernor {
    private val sheds = ArrayDeque<Long>()
    private var pausedSinceNs = Long.MIN_VALUE
    private var resumeNotBeforeNs = Long.MIN_VALUE
    private var lastResumeNs = Long.MIN_VALUE
    private var backoffNs = MIN_BACKOFF_NS
    private var pausedTotalNs = 0L

    val paused: Boolean get() = pausedSinceNs != Long.MIN_VALUE
    var pauseEvents: Long = 0
        private set

    /** Records a shed; true when this shed pauses video. */
    fun onShed(nowNs: Long): Boolean {
        if (paused) return false
        sheds.addLast(nowNs)
        while (sheds.isNotEmpty() && nowNs - sheds.first() > SHED_WINDOW_NS) sheds.removeFirst()
        if (sheds.size < PAUSE_AFTER_SHEDS) return false
        sheds.clear()
        // A pause soon after the previous resume means the link has not recovered: wait longer.
        backoffNs = if (lastResumeNs != Long.MIN_VALUE && nowNs - lastResumeNs < STABLE_AFTER_RESUME_NS) {
            (backoffNs * 2).coerceAtMost(MAX_BACKOFF_NS)
        } else {
            MIN_BACKOFF_NS
        }
        pausedSinceNs = nowNs
        resumeNotBeforeNs = nowNs + backoffNs
        pauseEvents++
        return true
    }

    fun shouldResume(nowNs: Long, queueAgeMs: Long, budgetMs: Long, peerLate: Boolean): Boolean =
        paused && nowNs >= resumeNotBeforeNs && queueAgeMs <= budgetMs / 2 && !peerLate

    fun onResumed(nowNs: Long) {
        if (!paused) return
        pausedTotalNs += nowNs - pausedSinceNs
        pausedSinceNs = Long.MIN_VALUE
        lastResumeNs = nowNs
    }

    val currentBackoffMs: Long get() = backoffNs / 1_000_000L

    fun pausedMs(nowNs: Long): Long =
        (pausedTotalNs + if (paused) nowNs - pausedSinceNs else 0L) / 1_000_000L

    companion object {
        const val PAUSE_AFTER_SHEDS = 3
        const val SHED_WINDOW_NS = 10_000_000_000L
        const val MIN_BACKOFF_NS = 2_000_000_000L
        const val MAX_BACKOFF_NS = 16_000_000_000L
        const val STABLE_AFTER_RESUME_NS = 30_000_000_000L
    }
}

/**
 * Experimental bounded media age for a viewer-forwarder's child queue. The relay copies whole BME1
 * envelopes, so it sheds whole envelopes: the child accepts the increasing mesh/BME1 sequence gap and
 * still rejects replay and reordering. Only authenticated-type media packets are video candidates
 * (the BME1 type byte is plaintext associated data); headers, lifecycle, codec configuration,
 * rotation, audio and terminal records always stay.
 */
class RelayMediaAgeBound {
    internal val shedder = MediaAgeShedder(BoundedMediaAge.STREAM_SHED_COOLDOWN_NS)
    /** Paces this child's recovery keyframe requests (see [RelayRecoveryBackoff]). */
    internal val recovery = RelayRecoveryBackoff()
    val keyFrameRequests: Long get() = recovery.requests
    /** The child's reported added playout delay on this link (hop-local). */
    @Volatile var childAddedDelayMs: Int = 0

    fun classify(envelope: MeshEnvelope): ShedClass {
        if (envelope.isAudio || envelope.isControl ||
            envelope.flags and (MeshEnvelope.FLAG_CODEC_CONFIG or MeshEnvelope.FLAG_TERMINAL or RELAY_ROTATION_FLAG) != 0
        ) {
            return ShedClass.KEEP
        }
        val type = runCatching { EndToEndContentProtocol.inspect(envelope.payload).type }.getOrNull()
        if (type != EndToEndContentType.MEDIA_PACKET) return ShedClass.KEEP
        return if (envelope.isKeyFrame) ShedClass.KEY_FRAME else ShedClass.VIDEO
    }

    fun budgetMs(): Long = BoundedMediaAge.streamQueueBudgetMs(childAddedDelayMs)

    private companion object {
        /** Outer mesh flags mirror inner media flags except bits 4/5; bit 2 is display rotation. */
        const val RELAY_ROTATION_FLAG = StreamProtocol.FLAG_DISPLAY_ROTATION
    }
}

/** Largest value seen since the last [take]; for per-window BENCHMARK lines. */
internal class WindowMax {
    private var value = 0L

    @Synchronized
    fun record(sample: Long) {
        if (sample > value) value = sample
    }

    @Synchronized
    fun take(): Long = value.also { value = 0L }
}

/**
 * Per-child pacing of a relay's recovery keyframe requests. The first request after a calm period
 * goes at once; while sheds keep recurring the interval doubles from
 * [BoundedMediaAge.KEY_FRAME_REREQUEST_NS] to [BoundedMediaAge.RELAY_RECOVERY_MAX_INTERVAL_NS], so a
 * persistently slow child costs every branch at most one extra keyframe per cap interval and its own
 * speech at most one keyframe stall per cap interval. After [BoundedMediaAge.RELAY_RECOVERY_CALM_NS]
 * without a request the interval resets. The child stays attached: its audio is never shed, so a link
 * that cannot carry even speech still grows the backlog the withdrawal policy reads.
 */
internal class RelayRecoveryBackoff {
    private var lastRequestNs = Long.MIN_VALUE
    var intervalNs: Long = BoundedMediaAge.KEY_FRAME_REREQUEST_NS
        private set
    var requests: Long = 0
        private set
    /** Requests not sent because the backoff interval had not passed. */
    var suppressed: Long = 0
        private set

    /** True when a recovery keyframe request may go now (and records it). */
    fun tryRequest(nowNs: Long): Boolean {
        val last = lastRequestNs
        if (last != Long.MIN_VALUE) {
            val sinceNs = nowNs - last
            if (sinceNs >= BoundedMediaAge.RELAY_RECOVERY_CALM_NS) {
                intervalNs = BoundedMediaAge.KEY_FRAME_REREQUEST_NS
            } else if (sinceNs < intervalNs) {
                suppressed++
                return false
            } else {
                intervalNs = (intervalNs * 2).coerceAtMost(BoundedMediaAge.RELAY_RECOVERY_MAX_INTERVAL_NS)
            }
        }
        lastRequestNs = nowNs
        requests++
        return true
    }
}

/**
 * Relay-child skip-to-keyframe setting: a DEBUG benchmark launch uses its extra; otherwise the stored
 * Advanced choice applies while Advanced settings are on, and the default (on) while they are off.
 */
internal fun relayChildMediaAgeSetting(
    benchmarkLaunch: Boolean,
    benchmarkExtra: Boolean,
    advancedSettings: Boolean,
    storedChoice: Boolean,
): Boolean = when {
    benchmarkLaunch -> benchmarkExtra
    advancedSettings -> storedChoice
    else -> true
}
