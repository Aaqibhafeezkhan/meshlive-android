package com.example.bluetoothtest

/**
 * Experimental relay child thinning (P2 of the adaptive-scaling review; off by default:
 * DebugTunables.relayChildThinning, launch extra `test_relay_child_thinning`). Nothing here runs while the
 * switch is off.
 *
 * A relay forwards the source's original encrypted records to each direct child. With one slow child
 * the relay could only forward everything (the child's queue ages, then the bounded-age shed asks the
 * source for keyframes in a loop, or the child is withdrawn). This policy gives the relay a cheaper
 * answer for ONE child, with no re-encode, no keyframe and no signal beyond the hop:
 *
 *  - FULL: forward every record.
 *  - BASE: forward every record except verified-discardable video (the source's temporal layer 1:
 *    nothing references it, [BitstreamDroppability]). The child sees half the frame rate and every
 *    frame it gets decodes. Going back to FULL needs nothing: all reference frames were delivered.
 *  - AUDIO: forward audio, configuration and lifecycle records only. Video is paused for this child;
 *    resuming needs a keyframe, which the relay requests (paced) when it leaves this level.
 *
 * Whole records are dropped, never edited, so the end-to-end checks hold: the content sequence the
 * child's opener enforces is "at least the next one" (a gap is accepted, replay and reordering are
 * not), and the AEAD nonce and header are bound to the record's own sequence and flags.
 *
 * Hop-local: the evidence is this relay's own queue for this child, what it handed to that child's
 * link, and what that child reports about its own link (late media). No downstream phone's state is used.
 */
enum class RelayChildLevel(val label: String) {
    FULL("full"),
    BASE("base"),
    AUDIO("audio"),
}

/** One look at a child's queue and link; all rates are over the policy's measuring window. */
data class RelayChildLayerSample(
    /** How long the oldest queued record has waited for this child. */
    val queueAgeMs: Long,
    /** What the writer handed the child's link in the window. */
    val deliveredKbps: Int,
    /** What the queue was offered in the window: everything / the part kept at BASE (reference video + audio). */
    val offeredFullKbps: Int,
    val offeredBaseKbps: Int,
    /** Throughput measured while media was waiting (the link's capacity), or null when never measured. */
    val busyCapacityKbps: Int?,
    /** How old that capacity measurement is. */
    val busyCapacityAgeMs: Long,
    /** Newest keyframe offered to this child's queue (bytes), 0 when unknown. */
    val keyFrameBytes: Int,
    /** A keyframe left the queue recently: the backlog behind it is its airtime, not steady-state evidence. */
    val keyFrameGrace: Boolean,
    /** The child's own report (hop-local): media on this link arrives late. */
    val childLate: Boolean,
    /** Bounded media age sheds done for this child so far (monotonic). */
    val shedEvents: Long,
    /** The stream carries verified-discardable frames right now. */
    val hasBaseLayer: Boolean,
    /** The stream carries audio, so AUDIO is a level worth having. */
    val hasAudio: Boolean,
)

/** The decision of one sample. */
data class RelayChildLayerDecision(
    val level: RelayChildLevel,
    val changed: Boolean,
    val reason: String,
)

class RelayChildLayerPolicy(private val config: Config = Config()) {
    data class Config(
        /** Queue age that counts as "behind" (a 15 fps frame is 67 ms; a keyframe drains in 1.5 s at 300 kbps). */
        val behindMs: Long = 600L,
        /** With the child reporting late media, a smaller backlog already counts. */
        val behindLateMs: Long = 300L,
        /** Immediate step down: beyond any playout buffer's ability to hide. */
        val severeMs: Long = 2_000L,
        /** Behind this long before FULL gives up the enhancement layer. */
        val downHoldMs: Long = 1_000L,
        /** Behind this long, still, before BASE gives up video altogether (or FULL with no layer to give). */
        val audioHoldMs: Long = 1_800L,
        /** After a change the queue needs time to drain; milder evidence is ignored for this long. */
        val settleMs: Long = 2_500L,
        /** Backlog below this is calm. */
        val calmMs: Long = 150L,
        /** Calm this long, and the level held for [minUpHoldMs], before probing one level up. */
        val calmBeforeUpMs: Long = 4_000L,
        val minUpHoldMs: Long = 8_000L,
        val maxUpHoldMs: Long = 60_000L,
        /** A step down this soon after a probe means the probe failed: the next one waits twice as long. */
        val probeFailureWindowMs: Long = 20_000L,
        /** A level held this long after a probe resets the wait. */
        val stableMs: Long = 30_000L,
        /** A probe needs the measured capacity to cover the next level's offered rate by this factor. */
        val capacityMargin: Double = 1.15,
        /** Older capacity measurements no longer veto a probe (the link may have changed). */
        val capacityFreshMs: Long = 30_000L,
        /** FULL skips BASE when the measured capacity is below this fraction of the base layer's offered rate. */
        val baseSkipFraction: Double = 0.75,
        /** A resume keyframe longer than this on the measured link is not worth asking for yet. */
        val maxKeyFrameAirtimeMs: Long = 4_500L,
    )

    var level: RelayChildLevel = RelayChildLevel.FULL
        private set
    var reason: String = "start"
        private set
    var changes: Long = 0
        private set
    var upHoldMs: Long = config.minUpHoldMs
        private set

    private var levelSinceMs = Long.MIN_VALUE
    private var behindSinceMs = Long.MIN_VALUE
    private var calmSinceMs = Long.MIN_VALUE
    private var lastShedEvents = 0L
    private var lastProbeMs = Long.MIN_VALUE

    fun onSample(nowMs: Long, s: RelayChildLayerSample): RelayChildLayerDecision {
        if (levelSinceMs == Long.MIN_VALUE) levelSinceMs = nowMs
        val shedNow = s.shedEvents > lastShedEvents
        lastShedEvents = s.shedEvents
        val settled = nowMs - levelSinceMs >= config.settleMs
        val behindLimit = if (s.childLate) config.behindLateMs else config.behindMs
        val behind = s.queueAgeMs >= behindLimit && !(s.keyFrameGrace && s.queueAgeMs < config.severeMs)
        val severe = s.queueAgeMs >= config.severeMs
        if (behind) {
            if (behindSinceMs == Long.MIN_VALUE) behindSinceMs = nowMs
        } else {
            behindSinceMs = Long.MIN_VALUE
        }
        if (s.queueAgeMs < config.calmMs && !s.childLate) {
            if (calmSinceMs == Long.MIN_VALUE) calmSinceMs = nowMs
        } else {
            calmSinceMs = Long.MIN_VALUE
        }
        val behindForMs = if (behindSinceMs == Long.MIN_VALUE) 0L else nowMs - behindSinceMs

        // Step down: severe or shed evidence acts at once; milder evidence needs the level to have settled.
        val downReason = when {
            severe -> "queue_severe"
            shedNow -> "shed"
            settled && behind && behindForMs >= holdFor(level, s) -> if (s.childLate) "child_late" else "queue_behind"
            else -> null
        }
        if (downReason != null) {
            val target = lowerLevel(level, s)
            if (target != level) {
                // The measured link cannot carry even the base layer: skip it rather than spend a settle on it.
                val skipsBase = level == RelayChildLevel.FULL && target == RelayChildLevel.BASE && s.hasAudio &&
                    baseDoesNotFit(s)
                return if (skipsBase) change(nowMs, RelayChildLevel.AUDIO, "base_does_not_fit") else change(nowMs, target, downReason)
            }
        }

        // Step up: one level, only from a calm, long-held level, and only if the measured link can carry it.
        if (level != RelayChildLevel.FULL && settled) {
            val calmForMs = if (calmSinceMs == Long.MIN_VALUE) 0L else nowMs - calmSinceMs
            if (calmForMs >= config.calmBeforeUpMs && nowMs - levelSinceMs >= upHoldMs) {
                val target = upperLevel(level, s)
                val veto = upVeto(target, s)
                if (veto == null && target != level) {
                    lastProbeMs = nowMs
                    return change(nowMs, target, "calm_probe")
                }
                if (veto != null && reason != veto) reason = veto
            }
        }
        // A level that came from a probe and has held for a while: the next probe may come sooner again.
        if (upHoldMs > config.minUpHoldMs && lastProbeMs != Long.MIN_VALUE && levelSinceMs == lastProbeMs &&
            nowMs - levelSinceMs >= config.stableMs
        ) {
            upHoldMs = config.minUpHoldMs
        }
        return RelayChildLayerDecision(level, false, reason)
    }

    private fun holdFor(from: RelayChildLevel, s: RelayChildLayerSample): Long = when {
        from == RelayChildLevel.FULL && s.hasBaseLayer -> config.downHoldMs
        else -> config.audioHoldMs
    }

    /** The next level down, skipping BASE when the stream has no layer and AUDIO when there is no audio. */
    private fun lowerLevel(from: RelayChildLevel, s: RelayChildLayerSample): RelayChildLevel = when (from) {
        RelayChildLevel.FULL -> when {
            s.hasBaseLayer -> RelayChildLevel.BASE
            s.hasAudio -> RelayChildLevel.AUDIO
            else -> from
        }
        RelayChildLevel.BASE -> if (s.hasAudio) RelayChildLevel.AUDIO else from
        RelayChildLevel.AUDIO -> from
    }

    private fun upperLevel(from: RelayChildLevel, s: RelayChildLayerSample): RelayChildLevel = when (from) {
        RelayChildLevel.AUDIO -> if (s.hasBaseLayer) RelayChildLevel.BASE else RelayChildLevel.FULL
        RelayChildLevel.BASE -> RelayChildLevel.FULL
        RelayChildLevel.FULL -> from
    }

    /** A fresh capacity measurement well below what the base layer is offered at. */
    private fun baseDoesNotFit(s: RelayChildLayerSample): Boolean {
        val capacity = s.busyCapacityKbps ?: return false
        return s.busyCapacityAgeMs < config.capacityFreshMs && capacity > 0 && s.offeredBaseKbps > 0 &&
            capacity < s.offeredBaseKbps * config.baseSkipFraction
    }

    /** Null when the measured link can carry [target]; otherwise why the probe waits (also kept as [reason]). */
    private fun upVeto(target: RelayChildLevel, s: RelayChildLayerSample): String? {
        val capacity = s.busyCapacityKbps ?: return null
        if (s.busyCapacityAgeMs >= config.capacityFreshMs || capacity <= 0) return null
        val need = if (target == RelayChildLevel.FULL) s.offeredFullKbps else s.offeredBaseKbps
        if (need > 0 && capacity < need * config.capacityMargin) return "capacity_short"
        if (level == RelayChildLevel.AUDIO && s.keyFrameBytes > 0 &&
            s.keyFrameBytes * 8L / capacity > config.maxKeyFrameAirtimeMs
        ) {
            return "keyframe_airtime"
        }
        return null
    }

    private fun change(nowMs: Long, target: RelayChildLevel, why: String): RelayChildLayerDecision {
        val goingDown = target.ordinal > level.ordinal
        if (goingDown && lastProbeMs != Long.MIN_VALUE && nowMs - lastProbeMs <= config.probeFailureWindowMs) {
            upHoldMs = (upHoldMs * 2).coerceAtMost(config.maxUpHoldMs)
        }
        level = target
        reason = why
        changes++
        levelSinceMs = nowMs
        behindSinceMs = Long.MIN_VALUE
        calmSinceMs = Long.MIN_VALUE
        return RelayChildLayerDecision(target, true, why)
    }
}

/**
 * Turns the writer's byte counters into the rates the policy needs: delivered and offered kbps over
 * a short window, and the link's capacity measured only while media was waiting (a drained queue
 * delivers what is offered, which says nothing about what the link could carry).
 */
class RelayChildLinkMeter(
    private val windowMs: Long = 3_000L,
    private val capacityWindowMs: Long = 10_000L,
) {
    private class Tick(
        val atMs: Long,
        val delivered: Long,
        val offeredFull: Long,
        val offeredBase: Long,
        val busy: Boolean,
    )

    private val ticks = ArrayDeque<Tick>()
    private var capacityKbps: Int? = null
    private var capacityAtMs = Long.MIN_VALUE

    /** Rates over the last [windowMs]; null until two ticks span it. */
    data class Rates(
        val deliveredKbps: Int,
        val offeredFullKbps: Int,
        val offeredBaseKbps: Int,
        val busyCapacityKbps: Int?,
        val busyCapacityAgeMs: Long,
    )

    @Synchronized
    fun onTick(nowMs: Long, deliveredBytes: Long, offeredFullBytes: Long, offeredBaseBytes: Long, backlogged: Boolean): Rates {
        ticks.addLast(Tick(nowMs, deliveredBytes, offeredFullBytes, offeredBaseBytes, backlogged))
        while (ticks.size >= 2 && nowMs - ticks[1].atMs >= capacityWindowMs) ticks.removeFirst()
        // Capacity: bytes handed over across consecutive ticks that both had media waiting.
        var busyBytes = 0L
        var busyMs = 0L
        val list = ticks.toList()
        for (i in 1 until list.size) {
            if (list[i - 1].busy && list[i].busy) {
                busyBytes += list[i].delivered - list[i - 1].delivered
                busyMs += list[i].atMs - list[i - 1].atMs
            }
        }
        if (busyMs >= MIN_BUSY_MS) {
            capacityKbps = (busyBytes * 8 / busyMs).toInt()
            capacityAtMs = nowMs
        }
        val start = list.lastOrNull { nowMs - it.atMs >= windowMs } ?: list.first()
        val span = (nowMs - start.atMs).coerceAtLeast(1L)
        fun kbps(now: Long, then: Long) = ((now - then) * 8 / span).toInt().coerceAtLeast(0)
        return Rates(
            kbps(deliveredBytes, start.delivered),
            kbps(offeredFullBytes, start.offeredFull),
            kbps(offeredBaseBytes, start.offeredBase),
            capacityKbps,
            if (capacityAtMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - capacityAtMs,
        )
    }

    private companion object {
        const val MIN_BUSY_MS = 1_500L
    }
}

/** The viewer's cover text while its relay keeps this link at the audio-only level. */
internal const val WEAK_LINK_PAUSE_TEXT = "Video paused: weak link"

/**
 * The viewer's side of the AUDIO level, inferred without any new record: audio keeps arriving, the
 * source has not paused or turned its camera off, and no video record has come for [pauseAfterMs].
 * One video record clears it.
 */
class WeakLinkPauseWatch(
    private val pauseAfterMs: Long = 3_000L,
    private val audioRecentMs: Long = 1_500L,
) {
    private var lastVideoMs = Long.MIN_VALUE
    private var lastAudioMs = Long.MIN_VALUE
    private var sourceVideoOff = false

    var paused: Boolean = false
        private set

    /** The source said it paused or turned its camera off: nothing is expected, nothing is a weak link. */
    @Synchronized
    fun setSourceVideoOff(off: Boolean, nowMs: Long): Boolean? {
        sourceVideoOff = off
        // Either way the clock restarts: a camera that comes back gets the full grace before a notice.
        lastVideoMs = nowMs
        return evaluate(nowMs)
    }

    @Synchronized
    fun onVideo(nowMs: Long): Boolean? {
        lastVideoMs = nowMs
        return evaluate(nowMs)
    }

    @Synchronized
    fun onAudio(nowMs: Long): Boolean? {
        lastAudioMs = nowMs
        return evaluate(nowMs)
    }

    /** The new state when it changed. */
    private fun evaluate(nowMs: Long): Boolean? {
        val next = !sourceVideoOff && lastVideoMs != Long.MIN_VALUE && lastAudioMs != Long.MIN_VALUE &&
            nowMs - lastAudioMs <= audioRecentMs && nowMs - lastVideoMs >= pauseAfterMs
        if (next == paused) return null
        paused = next
        return next
    }
}

/** One controller tick: what was decided and the evidence behind it, for the BENCHMARK line. */
data class RelayChildLayerReport(
    val decision: RelayChildLayerDecision,
    val queueAgeMs: Long,
    val rates: RelayChildLinkMeter.Rates,
    val stats: RelayChildLayerStats,
)

/**
 * Drives one child's [RelayChildLayerPolicy]. Called from one thread (the relay's child watch timer);
 * the queue itself is thread-safe. Hop-local: its inputs are this child's queue, the bytes this relay
 * handed to this child's link, and this child's own lateness report.
 */
class RelayChildLayerController(
    private val readStats: () -> RelayChildLayerStats?,
    private val applyLevel: (RelayChildLevel) -> Unit,
    private val policy: RelayChildLayerPolicy = RelayChildLayerPolicy(),
    private val meter: RelayChildLinkMeter = RelayChildLinkMeter(),
) {
    val level: RelayChildLevel get() = policy.level

    fun onTick(nowMs: Long, deliveredBytes: Long, queueAgeMs: Long, childLate: Boolean): RelayChildLayerReport? {
        val stats = readStats() ?: return null
        val rates = meter.onTick(nowMs, deliveredBytes, stats.offeredFullBytes, stats.offeredBaseBytes, queueAgeMs > 0L)
        val decision = policy.onSample(
            nowMs,
            RelayChildLayerSample(
                queueAgeMs = queueAgeMs,
                deliveredKbps = rates.deliveredKbps,
                offeredFullKbps = rates.offeredFullKbps,
                offeredBaseKbps = rates.offeredBaseKbps,
                busyCapacityKbps = rates.busyCapacityKbps,
                busyCapacityAgeMs = rates.busyCapacityAgeMs,
                keyFrameBytes = stats.lastKeyFrameBytes,
                keyFrameGrace = stats.keyFrameGrace,
                childLate = childLate,
                shedEvents = stats.shedEvents,
                hasBaseLayer = stats.hasBaseLayer,
                hasAudio = stats.hasAudio,
            ),
        )
        if (decision.changed) applyLevel(decision.level)
        return RelayChildLayerReport(decision, queueAgeMs, rates, stats)
    }
}

/** `BENCHMARK relay_child_layer: child | level | reason | queueAgeMs | deliveredKbps` plus the evidence. */
internal fun relayChildLayerLine(child: String, event: String, report: RelayChildLayerReport): String {
    val stats = report.stats
    return "BENCHMARK relay_child_layer: child=$child | level=${report.decision.level.label} | " +
        "reason=${report.decision.reason} | queueAgeMs=${report.queueAgeMs} | " +
        "deliveredKbps=${report.rates.deliveredKbps} | event=$event | offeredFullKbps=${report.rates.offeredFullKbps} | " +
        "offeredBaseKbps=${report.rates.offeredBaseKbps} | capacityKbps=${report.rates.busyCapacityKbps ?: -1} | " +
        "baseLayer=${stats.hasBaseLayer} | thinned=${stats.thinnedFrames} | paused=${stats.pausedFrames} | " +
        "keyFrameRequests=${stats.keyFrameRequests}"
}
