package com.example.bluetoothtest

/** One child link's last ~1 s of delivery, measured by its sender. */
data class LinkRateSample(
    val windowNs: Long,
    /** Bytes the encoder handed this link. */
    val offeredBytes: Long,
    /** Media bytes the link actually carried (excluding framing/encryption overhead). */
    val sentBytes: Long,
    val backlogAgeMs: Int,
    /** A keyframe/scene-cut frame is draining; its backlog is expected, not congestion. */
    val largeFrameGrace: Boolean,
    /** What the child measured this link delivers (0 = not measured yet). */
    val measuredLinkKbps: Int = 0,
    /**
     * The child reports that this link keeps delivering later (its added playout delay rose in
     * consecutive reports, or is pinned at its maximum). Hop-local: it describes only this
     * parent-to-child link.
     */
    val childLate: Boolean = false,
    /** The authenticated peer at the other end of this link (its node label); empty when unknown. */
    val peer: String = "",
    /**
     * Experimental temporal layering only: discardable frames this link's queue skipped because it
     * was behind. Always 0 while the experiment is off.
     */
    val thinnedFrames: Long = 0,
    /**
     * Experimental bounded media age only: video this link's queue shed (stale video skipped to a
     * keyframe, verified non-reference frames skipped, or video paused) because it was older than its
     * age budget. Shedding hides the backlog, so any shed is overuse evidence. Always 0 while the
     * experiment is off.
     */
    val shedFrames: Long = 0,
    /**
     * Milliseconds since the last large frame (keyframe or scene cut, see largeFrameDrainGraceNs) was
     * queued on this link; -1 when none yet. Diagnostic, and the burst attribution of the
     * experimental burst-aware drain.
     */
    val largeFrameAgeMs: Int = -1,
    /**
     * Media goodput the peer reported it actually received on this link over its last ~2 s (version 3
     * delivery report); 0 = the peer sends no delivery reports (an older build, a relay, or not yet).
     */
    val deliveredKbps: Int = 0,
    /**
     * Queueing delay in front of the last media the peer reported receiving: that media's one-way
     * delay from this sender's write above its rolling minimum (stack buffers, air and the peer's
     * input; the app queue is [backlogAgeMs]). -1 = unknown.
     */
    val peerQueueMs: Int = -1,
)

/**
 * Sender side of the version 3 delivery report: the goodput the link delivered and the queue in
 * front of the media, measured from the peer's counters and this sender's own write times.
 *
 * Delivered rate: received bytes over the peer's arrival clock, so report timing does not matter.
 *
 * Queue: the reported byte offset is mapped to the time this sender finished writing that byte.
 * The byte's one-way delay is then the peer's arrival clock minus this write clock plus an unknown
 * constant (the two link clocks start apart); minus its rolling minimum it is the queueing delay in
 * front of that byte (stack buffer, air, the peer's input), the backlog the Bluetooth stack hides.
 * How long the report took to come back is not in it. The first version (review 2026-10-09) used
 * "written at report arrival minus received", which counted the return path as forward queue: with
 * a 400-600 ms return delay it collapsed a 640 kbps link to 80-185 kbps.
 *
 * The last values are held between reports (a slow report path does not switch rate control back
 * to the old rules mid-episode). Counters that go backwards are a peer reset, not a 2^32 wrap:
 * the queue is no longer measurable on this link and reads -1.
 */
internal class PeerDeliveryEstimate {
    private val writes = ArrayDeque<LongArray>()
    private var sentTotal = 0L
    private var writesPruned = false
    private val history = ArrayDeque<LongArray>()
    private var lastRawBytes = -1L
    private var lastRawClock = -1L
    private var received = 0L
    private var clockMs = 0L
    private var spanKbps = 0
    private var lastIntervalKbps = 0
    private var queue = -1
    private val baseBuckets = ArrayDeque<LongArray>()
    private var matched = true

    /** Peer counter resets seen on this link (each one disables the queue measurement). */
    var counterResets = 0
        private set

    /** This sender finished writing [bytes] media payload bytes to the link at [doneNs]. */
    @Synchronized
    fun onSent(bytes: Int, doneNs: Long) {
        if (bytes <= 0) return
        sentTotal += bytes
        writes.addLast(longArrayOf(sentTotal, doneNs))
        while (writes.size > 1 && doneNs - writes.first()[1] > WRITE_HISTORY_NS) {
            writes.removeFirst()
            writesPruned = true
        }
    }

    /** Applies the peer's counters; false when they went backwards (a peer reset). */
    @Synchronized
    fun onReport(delivery: LinkDelivery): Boolean {
        val rawBytes = delivery.receivedBytes and UINT32_MASK
        val rawClock = delivery.clockMs and UINT32_MASK
        if (lastRawClock < 0) {
            // The unique 64-bit value at most this link's written total and within 2^31 of it.
            val behind = (sentTotal - rawBytes) and UINT32_MASK
            received = if (behind >= HALF_RANGE) sentTotal + ((1L shl 32) - behind) else sentTotal - behind
            clockMs = rawClock
        } else {
            val addedBytes = (rawBytes - lastRawBytes) and UINT32_MASK
            val addedMs = (rawClock - lastRawClock) and UINT32_MASK
            if (addedBytes >= HALF_RANGE || addedMs >= HALF_RANGE) {
                // One link's counters only grow; backwards is a reset, never a 2^32 wrap.
                counterResets++
                matched = false
                queue = -1
                history.clear()
                baseBuckets.clear()
                spanKbps = 0
                lastIntervalKbps = 0
                lastRawBytes = rawBytes
                lastRawClock = rawClock
                received = rawBytes
                clockMs = rawClock
                return false
            }
            received += addedBytes
            clockMs += addedMs
        }
        lastRawBytes = rawBytes
        lastRawClock = rawClock
        // The clock is the arrival of the last counted byte: unchanged means nothing new arrived.
        if (history.isNotEmpty() && clockMs <= history.last()[0]) return true
        history.lastOrNull()?.let { previous ->
            if (clockMs - previous[0] >= MIN_INTERVAL_MS) lastIntervalKbps = rateKbps(received - previous[1], clockMs - previous[0])
        }
        history.addLast(longArrayOf(clockMs, received))
        while (history.size > 1 && clockMs - history.first()[0] > RATE_SPAN_MS) history.removeFirst()
        val oldest = history.first()
        // Reports further apart than the span leave no valid span: an old span rate must not outlive
        // it (review 2026-10-09: 800 kbps held for ever while 3 s-apart reports showed 100 kbps).
        spanKbps = if (clockMs - oldest[0] >= MIN_RATE_SPAN_MS) rateKbps(received - oldest[1], clockMs - oldest[0]) else 0
        if (matched) sampleOneWayDelay()
        return true
    }

    private fun sampleOneWayDelay() {
        if (received <= 0L) return
        if (received > sentTotal) {
            // The last record arrived before its write returned here; a large gap is a counting
            // mismatch, and offsets would no longer map to writes.
            if (received - sentTotal > MISMATCH_BYTES) {
                matched = false
                queue = -1
            }
            return
        }
        val index = firstWriteReaching(received)
        if (index < 0 || index == 0 && writesPruned && writes.first()[0] > received) return
        val oneWayMs = clockMs - writes[index][1] / 1_000_000L
        val bucket = clockMs / BASE_BUCKET_MS
        val last = baseBuckets.lastOrNull()
        if (last != null && last[0] == bucket) {
            last[1] = minOf(last[1], oneWayMs)
        } else {
            baseBuckets.addLast(longArrayOf(bucket, oneWayMs))
        }
        while (baseBuckets.size > 1 && bucket - baseBuckets.first()[0] >= BASE_WINDOW_MS / BASE_BUCKET_MS) baseBuckets.removeFirst()
        val base = baseBuckets.minOf { it[1] }
        queue = (oneWayMs - base).coerceIn(0L, MAX_QUEUE_MS.toLong()).toInt()
    }

    private fun firstWriteReaching(offset: Long): Int {
        var low = 0
        var high = writes.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (writes[mid][0] >= offset) {
                found = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return found
    }

    /**
     * Goodput the peer received (kbps), 0 before two reports: the higher of the last ~2 s and the
     * last report interval, so the window in which a queue first formed (partly below the link) does
     * not understate the link at the moment it saturated; only the last interval when reports are
     * further apart than the span. Held between reports. 0 once the peer's counters proved
     * impossible (more received than this link wrote, or going backwards): rate control then uses
     * the older references instead of a delivery rate it cannot trust.
     */
    @Synchronized
    fun deliveredKbps(): Int = when {
        !matched -> 0
        spanKbps > 0 -> maxOf(spanKbps, lastIntervalKbps)
        else -> lastIntervalKbps
    }

    /** Queueing delay in front of the last media the peer received (ms), -1 when not measurable. */
    @Synchronized
    fun queueMs(): Int = if (matched) queue else -1

    private fun rateKbps(bytes: Long, ms: Long): Int = (bytes * 8 / ms).coerceIn(0L, MAX_KBPS).toInt()

    companion object {
        private const val UINT32_MASK = 0xffff_ffffL
        private const val HALF_RANGE = 1L shl 31
        /** About two report intervals: one ~1 s window is noisy on GATT (connection-event batching). */
        const val RATE_SPAN_MS = 2_200L
        const val MIN_RATE_SPAN_MS = 800L
        const val MIN_INTERVAL_MS = 500L
        /** Write times kept to map reported offsets back (longer than any queue worth measuring). */
        const val WRITE_HISTORY_NS = 20_000_000_000L
        /** Rolling minimum of the one-way delay: 2 min in 10 s buckets (clock drift stays < ~10 ms). */
        const val BASE_WINDOW_MS = 120_000L
        const val BASE_BUCKET_MS = 10_000L
        /** Clamp: a queue reading never overflows into a negative (clean-looking) value. */
        const val MAX_QUEUE_MS = 60_000
        const val MAX_KBPS = 1_000_000L
        const val MISMATCH_BYTES = 1L shl 20
    }
}

/**
 * Link report version 3 on a private-call link (2026-10-09). Older endpoints close the call on
 * anything but an exact version 2 report, so this endpoint sends delivery only after the adjacent hop
 * said it accepts it: one version-1 heartbeat (the hello), or a version 3 report. A version 2 report
 * says nothing. Delivery goes only on a direct call (no relay hops; a call relay forwards liveness
 * only and would drop it) once the call is known on this link. [enabled] false is the DEBUG control
 * arm: no hello, no version 3, and received delivery is ignored.
 */
internal class CallDeliveryReports(val enabled: Boolean) {
    @Volatile var peerAccepts = false
        private set

    /** Send the version-1 hello once after the endpoint handshake. */
    val sendsHello: Boolean
        get() = enabled

    /** A heartbeat from the adjacent hop ([report] null = version 1); true the first time it accepts version 3. */
    fun onPeerHeartbeat(report: LinkCapacityReport?): Boolean {
        if (!enabled || peerAccepts) return false
        if (report != null && report.delivery == null) return false
        peerAccepts = true
        return true
    }

    /** The delivery to attach to the next report, or null for version 2. */
    fun deliveryFor(callKnown: Boolean, relayHops: Int, snapshot: () -> LinkDelivery): LinkDelivery? =
        if (enabled && peerAccepts && callKnown && relayHops == 0) snapshot() else null

    /** The peer's delivery to use, or null in the control arm. */
    fun accepted(report: LinkCapacityReport): LinkDelivery? = report.delivery?.takeIf { enabled }
}

/**
 * Where a call's video starts when nothing was learned for this peer (2026-10-09). A fresh install
 * started an M31 -> ASUS GATT call at its full 1000 kbps on a ~620 kbps link: a 1.9 s backlog and 34
 * sender drops before the first cut (mvp-smoke-m31asus-b9029e48). With this phone's fresh link
 * measurement the call takes [MEASURED_LINK_SHARE] of it (both call directions share the link);
 * without one it starts at [UNMEASURED_LINK_KBPS], a conservative share of an unmeasured link. The
 * clean-window climb then finds the link from below. Never above the request.
 */
internal object CallStartRate {
    const val MEASURED_LINK_SHARE = 0.425
    const val UNMEASURED_LINK_KBPS = 400

    fun unknownPeerStartBps(measuredLinkKbps: Int?, audioBps: Int): Int =
        if (measuredLinkKbps != null && measuredLinkKbps > 0) {
            (measuredLinkKbps * 1_000L * MEASURED_LINK_SHARE).toInt() - audioBps
        } else {
            UNMEASURED_LINK_KBPS * 1_000 - audioBps
        }
}

/**
 * What each peer link sustained in earlier sessions. Automatic rate control used to start every
 * stream at the full request and learn the link only by overloading it: CoC 1200 kbps on a ~600 kbps
 * link grew the viewer's delay 250 -> 2000 ms in the first seconds (a 9 FPS window with no freeze,
 * callrate-454cf485). A link now starts at the rate that peer last sustained and climbs from there.
 */
internal interface SustainedRateMemory {
    /** Video bps this peer link last sustained, or null when unknown or stale. */
    fun startBps(peer: String): Int?

    fun record(peer: String, bps: Int)
}

/**
 * Evidence-gated sending rate for the Automatic rate control (adopted 2026-10-07).
 *
 * No frame is ever dropped (user direction 2026-10-07), so a link that carries less than the
 * encoder offers shows up as a growing backlog here or a growing delay at the child. The controller
 * follows the delay gradient, like real-time congestion control: it lowers the rate once when the
 * backlog or the child's delay is still growing (overuse), holds while a backlog drains (lowering
 * again would only punish the drain), and steps back up only after clean windows. The first no-loss
 * build lowered again on every window that still had a backlog and cascaded 300 -> 120 kbps in
 * 10 s (trend-3641df77), so a decrease needs fresh growth after the previous one.
 *
 * It never exceeds the request. With several children it follows the weakest, so every viewer
 * receives every frame; a child that cannot keep up even at the floor is released by
 * FanOutStarvationPolicy.
 */
/**
 * Calls only (design decision 2026-10-08): below the bitrate floor the frame rate drops instead, so each
 * encoded frame keeps the floor's bits and every frame is still delivered; [minimumFps] bounds it.
 *
 * [floorBps] is what the geometry actually encoded needs at the full frame rate (design decision
 * 2026-10-09). The floor used to be 0.4 x the request: a 1000 kbps call on a legacy-forced 426x240
 * ASUS cut 15 -> 7-10 FPS for ~11 s below 400 kbps, although that geometry held 15 FPS at 105-110 kbps
 * (regress-57484598 vs callrate-454cf485). The ladder now starts below the lower of the two; null keeps
 * the request-scaled floor (DEBUG experiment `test_call_ladder_from_request`).
 */
internal data class FrameRateLadder(val requestedFps: Int, val minimumFps: Int, val floorBps: Int? = null) {
    companion object {
        /** Bits per pixel per frame below which a call trades frame rate for per-frame bits. */
        const val FLOOR_BITS_PER_PIXEL = 0.1

        fun floorBpsFor(width: Int, height: Int, fps: Int): Int =
            (width.toLong() * height * fps * FLOOR_BITS_PER_PIXEL).toInt()
    }
}

internal class LinkRateController(
    private val requestedVideoBps: Int,
    private val audioBps: Int = StreamProtocol.AUDIO_BIT_RATE,
    minimumFraction: Double = 0.4,
    private val memory: SustainedRateMemory? = null,
    private val frameRateLadder: FrameRateLadder? = null,
    /**
     * Backlog age that counts as congestion. Experimental bounded media age gives calls their video
     * age budget here (about 150 ms); otherwise [CONGESTED_BACKLOG_MS].
     */
    private val congestedBacklogMs: Int = CONGESTED_BACKLOG_MS,
    /**
     * Experimental burst-aware drain (DebugTunables.experimentalBurstDrain, off by default): child
     * lateness right after a large frame, in a window where this link carried everything offered
     * with no backlog, is that burst arriving late, not congestion ([burstExplainsLateness]).
     */
    private val burstAwareDrain: Boolean = false,
    /**
     * Experimental delivery-based probing (DEBUG `test_delivery_rate_control`, off by default). Only
     * on links whose peer sends delivery reports: the climb stops just below what the link delivered
     * at its last overuse and probes past it rarely (backing off after each failed probe), a measured
     * queue of 300 ms that is not draining is overuse before the peer is late, and a decrease may follow 1 s after the
     * previous one (a delivery-referenced cut does not compound, so the 3 s wait is not needed).
     */
    private val deliveryProbing: Boolean = false,
    /**
     * Pre-2026-10-09 rule, kept for the DEBUG control arm (`test_delivery_reports=false`): every cut
     * is remembered as the peer's start rate. Default false: only clean sustained windows teach it.
     */
    private val rememberCuts: Boolean = false,
) {
    private class LinkState(var targetBps: Int) {
        var clean = 0
        var lastSampleNs = 0L
        var lastBacklogAgeMs = 0
        var lastDecreaseNs = Long.MIN_VALUE
        val thinnedWindows = ArrayDeque<Boolean>()
        /** App backlog plus peer-reported in-flight media at the previous delivery-reported sample; -1 none. */
        var lastQueueMs = -1
        /** Experimental probing: the climb holds at [CEILING_FRACTION] of this (0 = no ceiling, normal climb). */
        var ceilingBps = 0L
        /** Experimental probing: clean windows held at the cap before the next probe. */
        var probeHoldWindows = PROBE_HOLD_WINDOWS
        /** Experimental probing: the clean target a probe in progress started from (0 = not probing). */
        var probeFromBps = 0
        /** Experimental probing: inside one overuse episode (until the next clean window). */
        var overused = false

        /**
         * Experimental temporal layering: thinning is the designed response to bursts and keeps the
         * backlog short, so the delay gradient cannot see a link that is chronically short. Counting
         * every thinned window as congestion cut 600 -> 240 kbps on a VBR burst and VMAF fell to 40.5
         * (pacing-ab-c777b381); only chronic thinning means the link cannot carry the rate. Always
         * false while the experiment is off (no window is ever thinned).
         */
        fun chronicThinning(thinned: Boolean): Boolean {
            thinnedWindows.addLast(thinned)
            while (thinnedWindows.size > CHRONIC_THINNING_HORIZON) thinnedWindows.removeFirst()
            return thinnedWindows.size == CHRONIC_THINNING_HORIZON &&
                thinnedWindows.count { it } >= CHRONIC_THINNING_REQUIRED
        }

        /**
         * Experimental probing: the link delivered [deliveredBps] while overused. A failed probe doubles
         * the hold before the next one; an overuse nobody probed for (the link changed) resets it.
         */
        fun onOveruse(deliveredBps: Long) {
            if (!overused) {
                probeHoldWindows = if (probeFromBps > 0) {
                    (probeHoldWindows * 2).coerceAtMost(MAX_PROBE_HOLD_WINDOWS)
                } else {
                    PROBE_HOLD_WINDOWS
                }
                probeFromBps = 0
                overused = true
                ceilingBps = deliveredBps
            } else {
                // Every overuse sample is link-limited (a queue is present), so the most delivered in
                // the episode is the link; the least is report noise.
                ceilingBps = maxOf(ceilingBps, deliveredBps)
            }
        }
    }

    private val minimumBps = (requestedVideoBps * minimumFraction).toInt()
    /** Rate below which a call lowers its frame rate (see [FrameRateLadder.floorBps]). */
    private val ladderFloorBps = frameRateLadder?.floorBps?.coerceIn(1, minimumBps) ?: minimumBps
    /** The lowest video rate: the floor, or below it at the ladder's lowest frame rate. */
    private val lowestBps = frameRateLadder?.let { ladderFloorBps * it.minimumFps / it.requestedFps } ?: minimumBps
    private val links = mutableMapOf<Any, LinkState>()

    var effectiveVideoBps = requestedVideoBps
        private set

    /** The video rate is already at the lowest the controller sends (no further cut is possible). */
    val atRateFloor: Boolean get() = effectiveVideoBps <= minimumBps

    /** Experimental burst-aware drain: windows whose child lateness was attributed to a burst. */
    var burstAttributedSamples = 0
        private set

    /**
     * Starts [link] at its learned start rate before any media flows, so the encoder is configured at
     * it from the first frame instead of the request for the first sample's second. Returns the
     * effective video bitrate.
     */
    @Synchronized
    fun seed(link: Any, peer: String, nowNs: Long): Int {
        stateFor(link, peer).lastSampleNs = nowNs
        refresh(nowNs)
        return effectiveVideoBps
    }

    private fun stateFor(link: Any, peer: String): LinkState = links.getOrPut(link) {
        val learned = peer.takeIf { it.isNotEmpty() }?.let { memory?.startBps(it) }
        LinkState(learned?.coerceIn(lowestBps, requestedVideoBps) ?: requestedVideoBps)
    }

    /** Records [sample] for [link]; returns the new effective video bitrate when it changes. */
    @Synchronized
    fun onSample(link: Any, sample: LinkRateSample, nowNs: Long): Int? {
        val state = stateFor(link, sample.peer)
        state.lastSampleNs = nowNs
        val chronicThinning = state.chronicThinning(sample.thinnedFrames > 0) &&
            sample.sentBytes < sample.offeredBytes
        // Experimental burst-aware drain only: the child is late because one burst arrived late.
        // Neither overuse nor clean: the window holds the current rate.
        val burstLate = burstAwareDrain && sample.childLate && burstExplainsLateness(sample)
        if (burstLate) burstAttributedSamples++
        val childLate = sample.childLate && !burstLate
        // The peer's delivery report (version 3), when it sends one: what arrived, in bps.
        val deliveredBps = sample.deliveredKbps.toLong() * 1_000L
        val measured = deliveredBps > 0 && sample.peerQueueMs >= 0
        // With delivery reports the whole queue is measured: this app queue plus the media the stack
        // accepted that has not arrived yet. Without them only the app queue is visible.
        val queueMs = if (measured) {
            (sample.backlogAgeMs.toLong() + sample.peerQueueMs).coerceAtMost(PeerDeliveryEstimate.MAX_QUEUE_MS.toLong()).toInt()
        } else {
            sample.backlogAgeMs
        }
        val previousQueueMs = if (measured) state.lastQueueMs else state.lastBacklogAgeMs
        val queueGrowing = queueMs >= congestedBacklogMs && !sample.largeFrameGrace &&
            queueMs >= previousQueueMs + GROWTH_MS &&
            (measured || sample.sentBytes < sample.offeredBytes)
        val lateOveruse: Boolean
        var standingQueue = false
        var queueDraining = false
        if (measured) {
            // Lateness is overuse only while media is still queued and not draining. Lateness
            // outlives its queue by the peer's hold (about 3.5 s), so the old rule cut again after the
            // queue had drained, against an application-limited window (regress-ff0e24e1 ASUS->S24:
            // 461 -> 316 kbps at a 117 ms backlog, r2 440 -> 289 at 0 ms); a single burst whose
            // lateness straddles a report boundary reads as two rises.
            queueDraining = state.lastQueueMs >= 0 && queueMs <= state.lastQueueMs - QUEUE_DRAIN_MS
            lateOveruse = childLate && queueMs >= QUEUE_PRESENT_MS && !queueDraining
            standingQueue = deliveryProbing && !sample.largeFrameGrace && queueMs >= QUEUE_OVERUSE_MS &&
                state.lastQueueMs >= 0 && !queueDraining
        } else {
            lateOveruse = childLate
        }
        val overuse = queueGrowing || lateOveruse || standingQueue || chronicThinning || sample.shedFrames > 0
        // Not clean while a measured queue remains, even when the app queue is empty: on GATT the
        // stack accepted 48+ KB the link had not carried, and climbing on top of it overshot.
        val clean = !sample.childLate && queueMs < minOf(CLEAN_BACKLOG_MS, congestedBacklogMs) &&
            sample.thinnedFrames == 0L && sample.shedFrames == 0L
        // A clean window proves the target only when the encoder actually filled it: audio-only,
        // camera-off or a scene far below the target says nothing about the link (review 2026-10-09:
        // camera-off windows climbed to and remembered the full request for 24 h).
        val loaded = sample.windowNs > 0 &&
            sample.offeredBytes * 8 * 1_000_000_000L / sample.windowNs >= (state.targetBps + audioBps) * LOADED_FRACTION
        state.lastBacklogAgeMs = sample.backlogAgeMs
        val previousMeasuredQueueMs = state.lastQueueMs
        state.lastQueueMs = if (measured) queueMs else -1
        // Experimental probing: a probe that builds a measured queue, or meets any overuse, is undone at
        // once (the level before it was clean) instead of cut by 15%. If the link itself got worse,
        // the next overuse, 1 s later and no longer probing, cuts.
        val probeFailed = deliveryProbing && measured && state.probeFromBps > 0 && (
            overuse || queueMs >= QUEUE_PRESENT_MS && previousMeasuredQueueMs >= 0 && !queueDraining
            )
        when {
            probeFailed -> {
                state.clean = 0
                state.targetBps = minOf(state.targetBps, state.probeFromBps)
                state.probeFromBps = 0
                state.probeHoldWindows = (state.probeHoldWindows * 2).coerceAtMost(MAX_PROBE_HOLD_WINDOWS)
                // One episode: lingering overuse until the next clean window keeps the longer hold.
                state.overused = true
                state.lastDecreaseNs = nowNs
            }
            overuse -> {
                state.clean = 0
                if (deliveryProbing && deliveredBps > 0) state.onOveruse(deliveredBps)
                val sinceDecrease = nowNs - state.lastDecreaseNs
                val interval = if (deliveryProbing && deliveredBps > 0) FAST_DECREASE_INTERVAL_NS else MIN_DECREASE_INTERVAL_NS
                if ((state.lastDecreaseNs == Long.MIN_VALUE || sinceDecrease >= interval) && sample.windowNs > 0) {
                    val linkBps = if (deliveredBps > 0) {
                        // What the peer received while the link was overused. The arrival-train
                        // estimate is the rate one record crossed at and "carried" counts bytes the
                        // stack buffered; both overstated a shared GATT link (trains 745-875 against
                        // 588-696 kbps received), so cuts landed above the link and cascaded.
                        deliveredBps
                    } else {
                        val carriedBps = sample.sentBytes * 8 * 1_000_000_000L / sample.windowNs
                        if (sample.measuredLinkKbps > 0) minOf(carriedBps, sample.measuredLinkKbps * 1_000L) else carriedBps
                    }
                    val videoBps = (linkBps * LINK_HEADROOM - audioBps).toInt()
                        .coerceIn(lowestBps, requestedVideoBps)
                    if (videoBps < state.targetBps) {
                        state.targetBps = videoBps
                        state.lastDecreaseNs = nowNs
                        // By default not remembered: a cut target was never sustained, and one false
                        // cut started the next session low (regress-ff0e24e1 S24->A15s 600 -> 494).
                        if (rememberCuts) remember(sample.peer, videoBps)
                    }
                }
            }
            clean -> {
                // Unloaded clean windows hold: they neither count toward a climb nor teach the memory.
                if (loaded) state.clean++
                state.overused = false
                val probing = deliveryProbing && state.ceilingBps > 0
                val cap = if (probing) capBps(state.ceilingBps) else requestedVideoBps
                val holdWindows = when {
                    !probing || state.targetBps < cap && state.probeFromBps == 0 -> CLEAN_WINDOWS
                    state.probeFromBps > 0 -> PROBE_CONFIRM_WINDOWS
                    else -> state.probeHoldWindows
                }
                if (state.clean >= holdWindows) {
                    // Sustained cleanly: remember it, then climb. A target held only by this session's
                    // request is not the link's limit, so it may raise the remembered rate but never
                    // lower it (a clean 300 kbps call must not start the next 1200 kbps one at 300).
                    if (state.targetBps < requestedVideoBps || (memoryStart(sample.peer) ?: 0) < state.targetBps) {
                        remember(sample.peer, state.targetBps)
                    }
                    if (state.targetBps < requestedVideoBps) {
                        state.targetBps = when {
                            !probing -> state.targetBps + requestedVideoBps / 10
                            state.probeFromBps > 0 -> {
                                // A probe past the last overuse point held cleanly: the link improved
                                // and that ceiling is stale. Climb normally until the next overuse.
                                state.ceilingBps = 0L
                                state.probeFromBps = 0
                                state.probeHoldWindows = PROBE_HOLD_WINDOWS
                                state.targetBps + requestedVideoBps / 10
                            }
                            state.targetBps < cap -> minOf(state.targetBps + requestedVideoBps / 10, cap)
                            else -> {
                                // Held cleanly at the cap: probe a step past the last overuse point.
                                state.probeFromBps = state.targetBps
                                maxOf(state.targetBps + 1, capBps((state.ceilingBps * (1 + PROBE_STEP)).toLong()))
                            }
                        }.coerceAtMost(requestedVideoBps)
                    }
                    state.clean = 0
                }
            }
            // A backlog still draining after a decrease: hold.
            else -> state.clean = 0
        }
        return refresh(nowNs)
    }

    /** Frame rate that keeps the floor's bits per frame at [videoBps] (the request without a ladder). */
    fun frameRateFor(videoBps: Int): Int {
        val ladder = frameRateLadder ?: return Int.MAX_VALUE
        if (videoBps >= ladderFloorBps) return ladder.requestedFps
        return (ladder.requestedFps.toLong() * videoBps / ladderFloorBps).toInt().coerceIn(ladder.minimumFps, ladder.requestedFps)
    }

    /** Experimental probing: the video target that holds the link at [CEILING_FRACTION] of [ceilingBps]. */
    private fun capBps(ceilingBps: Long): Int =
        (ceilingBps * CEILING_FRACTION - audioBps).toInt().coerceIn(lowestBps, requestedVideoBps)

    private fun memoryStart(peer: String): Int? = peer.takeIf { it.isNotEmpty() }?.let { memory?.startBps(it) }

    private fun remember(peer: String, bps: Int) {
        if (peer.isNotEmpty()) memory?.record(peer, bps)
    }

    @Synchronized
    private fun refresh(nowNs: Long): Int? {
        links.values.removeAll { nowNs - it.lastSampleNs > STALE_LINK_NS }
        val next = links.values.minOfOrNull { it.targetBps } ?: requestedVideoBps
        if (next == effectiveVideoBps) return null
        effectiveVideoBps = next
        return next
    }

    companion object {
        /** Experimental temporal layering: thinning in 8 of the last 10 windows is rate evidence. */
        const val CHRONIC_THINNING_HORIZON = 10
        const val CHRONIC_THINNING_REQUIRED = 8
        const val CONGESTED_BACKLOG_MS = 400
        const val CLEAN_BACKLOG_MS = 150
        /** The backlog must still be growing (by more than timer noise) to count as overuse. */
        const val GROWTH_MS = 50
        /** A decrease needs this long to show its effect before another one. */
        const val MIN_DECREASE_INTERVAL_NS = 3_000_000_000L
        const val CLEAN_WINDOWS = 3
        // Samples count media bytes, so wire/encryption overhead is already outside these numbers.
        const val LINK_HEADROOM = 0.85
        const val STALE_LINK_NS = 5_000_000_000L
        /**
         * Delivery reports: less queued media than this (one large frame, report transit) is not
         * overuse evidence, so lateness left over from a drained queue does not cut again.
         */
        const val QUEUE_PRESENT_MS = 150
        /**
         * A clean window counts toward a climb (and the learned rate) only when the encoder offered at
         * least this fraction of target + audio: encoders run below target on easy scenes.
         */
        const val LOADED_FRACTION = 0.6
        /** Delivery reports: the measured queue fell by at least this since the last sample (draining). */
        const val QUEUE_DRAIN_MS = 50
        /** Experimental probing: a measured queue this deep that is not draining is overuse. */
        const val QUEUE_OVERUSE_MS = 300
        /** Experimental probing: delivery-referenced decreases may be this close together. */
        const val FAST_DECREASE_INTERVAL_NS = 1_000_000_000L
        /** Experimental probing: the climb holds at this fraction of the last overuse delivery. */
        const val CEILING_FRACTION = 0.95
        /** Experimental probing: clean windows held at the cap before a probe; doubles per failed probe. */
        const val PROBE_HOLD_WINDOWS = 10
        const val MAX_PROBE_HOLD_WINDOWS = 30
        /**
         * Experimental probing: clean windows that confirm a probe. A probe 4-5% past the link grows
         * the queue only ~45 ms per second; it must reach [QUEUE_PRESENT_MS] (plus a report) first.
         */
        const val PROBE_CONFIRM_WINDOWS = 6
        /** Experimental probing: one probe aims this fraction past the last overuse delivery. */
        const val PROBE_STEP = 0.10
        /**
         * A child's lateness report trails the late frame by its arrival (up to ~3.1 s for a 135 KB
         * GATT frame), the report interval (~1 s) and the late hold (1.5 s).
         */
        const val BURST_ATTRIBUTION_MS = 6_000
        /** "Carried everything offered": sent within 10% of the offer (framing excluded on both). */
        const val OFFER_CARRIED_FRACTION = 0.9

        /**
         * True when [sample]'s child lateness is explained by a recent large frame: one arrived within
         * [BURST_ATTRIBUTION_MS], and this window has no backlog and carried at least
         * [OFFER_CARRIED_FRACTION] of what was offered, so the link keeps up now. A link that cannot
         * carry the rate shows a backlog or a short window and still counts. The regress-57484598
         * M31->ASUS GATT 300 cut (to 206 kbps for 12 s) had backlog 0 ms and sent == offered.
         */
        fun burstExplainsLateness(sample: LinkRateSample): Boolean =
            sample.largeFrameAgeMs in 0..BURST_ATTRIBUTION_MS &&
                sample.backlogAgeMs < CLEAN_BACKLOG_MS &&
                sample.offeredBytes > 0 &&
                sample.sentBytes >= sample.offeredBytes * OFFER_CARRIED_FRACTION
    }
}

/**
 * Experimental burst-aware drain (sender side): large-frame grace lasts while the burst is still
 * draining, beyond the fixed grace (1.5x the estimated drain, capped at 3 s). A blocking GATT write
 * of a 54-135 KB scene cut on a slow link outlasts that cap; the first window after the write then
 * shows an aged backlog that looks like growth. Draining means: the first sample after the large
 * frame's write completed, or queued bytes still falling since the previous sample, within
 * [maxBurstNs] of the large frame.
 */
internal class BurstDrainTracker(private val maxBurstNs: Long = MAX_BURST_NS) {
    private var largeQueuedNs = Long.MIN_VALUE
    private var largeWrittenNs = Long.MIN_VALUE
    private var lastSampleNs = Long.MIN_VALUE
    private var lastQueuedBytes = Long.MAX_VALUE

    @Synchronized
    fun onLargeFrameQueued(nowNs: Long) {
        largeQueuedNs = nowNs
        largeWrittenNs = Long.MIN_VALUE
    }

    @Synchronized
    fun onLargeFrameWritten(nowNs: Long) {
        if (largeQueuedNs != Long.MIN_VALUE) largeWrittenNs = nowNs
    }

    /** Milliseconds since the last large frame was queued; -1 when none. */
    @Synchronized
    fun largeFrameAgeMs(nowNs: Long): Int =
        if (largeQueuedNs == Long.MIN_VALUE) -1 else ((nowNs - largeQueuedNs) / 1_000_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** Call once per rate sample; true while the burst is still draining. */
    @Synchronized
    fun draining(nowNs: Long, queuedBytes: Long): Boolean {
        val active = largeQueuedNs != Long.MIN_VALUE && nowNs - largeQueuedNs <= maxBurstNs
        val firstAfterWrite = largeWrittenNs != Long.MIN_VALUE &&
            (lastSampleNs == Long.MIN_VALUE || largeWrittenNs > lastSampleNs)
        val stillWriting = largeQueuedNs != Long.MIN_VALUE && largeWrittenNs == Long.MIN_VALUE
        val shrinking = queuedBytes < lastQueuedBytes
        lastSampleNs = nowNs
        lastQueuedBytes = queuedBytes
        return active && (stillWriting || firstAfterWrite || shrinking)
    }

    companion object {
        const val MAX_BURST_NS = 10_000_000_000L
    }
}

/**
 * Turns a peer's hop-local reports into "this link keeps delivering later": its playout delay is
 * pinned, or it kept adding delay in consecutive reports (the link carries less than it is sent).
 * One step is an absorbed burst such as a scene cut; treating it as congestion cut a VBR stream far
 * below its link (trend-3641df77).
 */
internal class PeerLatenessTrend {
    private var lastAddedDelayMs = 0
    private var rises = 0
    private var lastReportNs = Long.MIN_VALUE

    /**
     * True when [report] shows sustained lateness on this link. Rises count as consecutive only when
     * the reports are close together: a stream holding one burst's delay sends no report for up to
     * 60 s, and the next burst's rise 20 s later was counted as the second consecutive rise
     * (regress-ff0e24e1 s24->a15s VP9 600: a false 600 -> 494 kbps cut, then remembered).
     */
    fun onReport(report: LinkCapacityReport, nowNs: Long = System.nanoTime()): Boolean {
        if (lastReportNs != Long.MIN_VALUE && nowNs - lastReportNs > RISE_WINDOW_NS) rises = 0
        lastReportNs = nowNs
        val rising = report.addedDelayMs - lastAddedDelayMs >= DELAY_RISE_MS
        lastAddedDelayMs = report.addedDelayMs
        rises = if (rising) rises + 1 else 0
        return report.linkLate || rises >= SUSTAINED_RISES
    }

    companion object {
        /** One report step: the peer grew its delay again since the previous report. */
        const val DELAY_RISE_MS = 100
        const val SUSTAINED_RISES = 2
        /** Reports further apart than this do not form a sustained trend (reports go out about every 1 s while the delay moves). */
        const val RISE_WINDOW_NS = 3_000_000_000L
        /** Until the next report (sent at most every 1 s, or when the delay moves). */
        const val LATE_HOLD_NS = 1_500_000_000L
    }
}
