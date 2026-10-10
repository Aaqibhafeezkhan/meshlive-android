package com.example.bluetoothtest

import kotlin.math.abs

/**
 * Keeps continuous audio on the shared playout clock without dropping or skipping samples
 * (user direction 2026-10-07: latency instead of loss). The video delay adapts (it grows when the
 * link delivers late and shrinks slowly after calm); audio, once playing, only drifts. Instead of
 * discarding late audio, playback speed changes slightly (pitch preserved) until audio is back on
 * the clock: speeding up shortens the delay after calm, slowing down follows a grown delay.
 *
 * [onSample] takes how much later than its clock target the chunk being written will play. The
 * first samples after start set the baseline, which absorbs this device's fixed output latency.
 */
internal class AudioPlayoutSync(
    private val baselineSamples: Int = BASELINE_SAMPLES,
) {
    private var baselineSum = 0L
    private var baselineCount = 0
    private var baselineMs: Long? = null

    var speed = 1.0f
        private set

    /** Returns the new speed when it should change, otherwise null. */
    fun onSample(lagMs: Long): Float? {
        val baseline = baselineMs ?: run {
            baselineSum += lagMs
            baselineCount++
            if (baselineCount >= baselineSamples) baselineMs = baselineSum / baselineCount
            return null
        }
        val errorMs = lagMs - baseline
        val target = if (abs(errorMs) <= DEAD_BAND_MS) {
            1.0f
        } else {
            (1.0f + errorMs * GAIN_PER_MS).coerceIn(MIN_SPEED, MAX_SPEED)
        }
        if (abs(target - speed) < MIN_STEP - STEP_TOLERANCE && !(target == 1.0f && speed != 1.0f)) return null
        speed = target
        return target
    }

    /** The device's steady lag (ms) learned from the first samples, or null while still learning. */
    val baselineLagMs: Long?
        get() = baselineMs

    companion object {
        const val BASELINE_SAMPLES = 25
        const val DEAD_BAND_MS = 40L
        /** 200 ms behind -> 5% faster: a 1 s backlog clears in ~20 s, inaudibly. */
        const val GAIN_PER_MS = 0.00025f
        const val MIN_SPEED = 0.96f
        const val MAX_SPEED = 1.06f
        const val MIN_STEP = 0.01f
        private const val STEP_TOLERANCE = 0.0005f
    }
}

/**
 * Re-times audio after a gap the sender left on purpose (mute, push-to-talk release, a dropped
 * packet): the PTS jumped ahead of the end of the previous chunk. During the gap the AudioTrack runs
 * dry, so without this the next talk spurt played as soon as it was written, ahead of its clock
 * target by up to the whole call delay; [AudioPlayoutSync] then slowed speech to 0.96x for about
 * 6 s (250 ms / 4%) to rebuild the delay, with no jitter protection meanwhile, and video calls lost
 * lip sync for that time. Writing silence first starts the spurt on its target instead.
 *
 * [lagMs] is now + queued - target for the first chunk after the gap (as in followPlayoutClock),
 * [baselineLagMs] the device's steady lag. Returns the silence to write (ms): the early part beyond
 * the dead band, never more than the sender's own [gapMs] (so it adds no delay the sender's timeline
 * does not have) nor [maxMs]. Late spurts (lag above baseline) get none; the clock already grew the
 * delay for them at arrival.
 */
internal fun senderGapSilenceMs(lagMs: Long, baselineLagMs: Long?, gapMs: Long, maxMs: Long): Long {
    if (gapMs < SENDER_GAP_MIN_MS) return 0L
    val earlyMs = (baselineLagMs ?: 0L) - lagMs
    if (earlyMs <= AudioPlayoutSync.DEAD_BAND_MS) return 0L
    return minOf(earlyMs, gapMs, maxMs).coerceAtLeast(0L)
}

/** Three missing 20 ms Opus frames: smaller PTS steps are rounding or codec framing, not a gap. */
internal const val SENDER_GAP_MIN_MS = 60L

/**
 * Gives each decoded PCM buffer the sender PTS of the Opus packet it came from. The S24's decoder
 * path (c2.android.opus.decoder, Android 16) returned contiguous output timestamps across a 6 s
 * sender mute (voice-ptt-s24a15s-51cc8028 case 0003: wire PTS jumped, the decoded PTS did not), so
 * the playout follower saw audio 6 s late, ran at 1.06x on an empty track and underran 164 times
 * until the call ended. One Opus packet decodes to one PCM buffer; a decoder that echoes input
 * timestamps (AOSP C2SoftOpusDec) may skip an output while it discards the codec pre-skip, so inputs
 * older than an echoed timestamp are passed over. A decoder that re-stamps never runs ahead of the
 * sender timeline, so its outputs simply take the queued input timestamps in order.
 */
internal class SourcePtsQueue(private val capacity: Int = MAX_PENDING) {
    private val pending = ArrayDeque<Long>()

    fun onInput(ptsUs: Long) {
        pending.addLast(ptsUs)
        while (pending.size > capacity) pending.removeFirst()
    }

    /** The sender PTS for the next output buffer, whose decoder timestamp is [codecPtsUs]. */
    fun onOutput(codecPtsUs: Long): Long {
        while (pending.size > 1 && pending.first() < codecPtsUs - MATCH_TOLERANCE_US &&
            pending.elementAt(1) <= codecPtsUs + MATCH_TOLERANCE_US
        ) {
            pending.removeFirst()
        }
        return pending.removeFirstOrNull() ?: codecPtsUs
    }

    companion object {
        const val MAX_PENDING = 400
        const val MATCH_TOLERANCE_US = 1_000L
    }
}
