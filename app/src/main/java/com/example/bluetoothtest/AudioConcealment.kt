package com.example.bluetoothtest

import kotlin.math.sqrt

/*
 * Call audio starvation concealment (experimental, off by default, 2026-10-10; DEBUG
 * `test_call_audio_conceal`, benchmark tunable `call_audio_conceal`).
 *
 * Algorithm ideas adapted from WebRTC NetEq (BSD-3-Clause, Copyright (c) 2011 The WebRTC project
 * authors; see THIRD_PARTY_NOTICES.md): Expand (modules/audio_coding/neteq/expand.cc) repeats the
 * last pitch period with noise mixing under a decaying mute factor and then hands over to comfort
 * noise; Merge (merge.cc) cross-fades the concealment into the next real audio; Accelerate
 * (accelerate.cc) removes audio preferably where the signal is not active speech. This is a
 * simplified Kotlin re-implementation (time-domain autocorrelation pitch search, linear fades), not
 * a line-by-line port. Before: a call AudioTrack that ran dry underran (silence gap, often a click),
 * and catch-up sped up speech and silence alike.
 */

/** PCM16 little-endian bytes to samples (mono). */
internal fun pcm16ToShorts(bytes: ByteArray, size: Int = bytes.size): ShortArray {
    val out = ShortArray(size / 2)
    for (i in out.indices) {
        out[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
    }
    return out
}

/** Samples to PCM16 little-endian bytes. */
internal fun shortsToPcm16(samples: ShortArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val v = samples[i].toInt()
        out[2 * i] = v.toByte()
        out[2 * i + 1] = (v shr 8).toByte()
    }
    return out
}

internal fun rmsOf(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
    if (to <= from) return 0.0
    var sum = 0.0
    for (i in from until to) {
        val v = samples[i].toDouble()
        sum += v * v
    }
    return sqrt(sum / (to - from))
}

/**
 * Generates concealment for a mono PCM16 stream. Feed every real chunk to [observe] after it is
 * played; call [conceal] when the output is about to run dry, and [merge] on the next real chunk
 * (before it is played) to cross-fade into it and end the event. Not thread-safe: the playout
 * thread owns it.
 */
internal class AudioConcealer(
    private val sampleRate: Int = 48_000,
    seed: Long = 0x5EED,
) {
    private val historySamples = sampleRate * HISTORY_MS / 1_000
    private val history = ShortArray(historySamples)
    private var historyFill = 0
    private val minLag = sampleRate * MIN_PITCH_US / 1_000_000
    private val maxLag = sampleRate * MAX_PITCH_US / 1_000_000
    private val window = sampleRate * CORRELATION_WINDOW_MS / 1_000
    private val fadeSamples = sampleRate * EXPAND_FADE_MS / 1_000
    private val mergeSamples = sampleRate * MERGE_MS / 1_000
    private val noiseBlock = sampleRate * NOISE_BLOCK_MS / 1_000
    private val random = java.util.Random(seed)

    /** Slowly rising, quickly falling background-level estimate (RMS) for comfort noise. */
    var noiseRms = -1.0
        private set

    var concealing = false
        private set
    /** Pitch period (samples) and voicing (normalized correlation 0..1) of the current event. */
    var period = 0
        private set
    var voicing = 0.0
        private set
    private var periodPos = 0
    private var eventRms = 0.0
    private var eventSamples = 0

    var events = 0L
        private set
    var concealedSamples = 0L
        private set
    var expandSamples = 0L
        private set
    var comfortSamples = 0L
        private set

    /** True once enough real audio was seen to continue it. */
    val primed: Boolean get() = historyFill > 0

    fun observe(samples: ShortArray) {
        if (samples.isEmpty()) return
        if (samples.size >= historySamples) {
            System.arraycopy(samples, samples.size - historySamples, history, 0, historySamples)
            historyFill = historySamples
        } else {
            val keep = minOf(historyFill, historySamples - samples.size)
            System.arraycopy(history, historyFill - keep, history, 0, keep)
            System.arraycopy(samples, 0, history, keep, samples.size)
            historyFill = keep + samples.size
        }
        var start = 0
        while (start + noiseBlock <= samples.size) {
            val rms = rmsOf(samples, start, start + noiseBlock)
            noiseRms = when {
                noiseRms < 0.0 || rms < noiseRms -> rms
                else -> noiseRms + (rms - noiseRms) * NOISE_RISE
            }
            start += noiseBlock
        }
    }

    /** Returns [count] samples of concealment and starts an event when none is running. */
    fun conceal(count: Int): ShortArray {
        if (!concealing) startEvent()
        val out = ShortArray(count)
        for (i in 0 until count) {
            if (eventSamples < fadeSamples) expandSamples++ else comfortSamples++
            out[i] = nextSample()
        }
        concealedSamples += count
        return out
    }

    /**
     * Cross-fades the start of real [samples] (in place) from the running concealment and ends the
     * event. Returns the event's concealed sample count, or 0 when nothing was being concealed.
     */
    fun merge(samples: ShortArray): Int {
        if (!concealing) return 0
        val length = eventSamples
        val m = minOf(mergeSamples, samples.size)
        for (i in 0 until m) {
            val w = (i + 1).toDouble() / (m + 1)
            val c = nextSample().toDouble()
            samples[i] = clamp((1.0 - w) * c + w * samples[i])
        }
        concealing = false
        return length
    }

    private fun startEvent() {
        concealing = true
        events++
        eventSamples = 0
        periodPos = 0
        eventRms = if (historyFill > 0) rmsOf(history, maxOf(0, historyFill - window), historyFill) else 0.0
        val (lag, correlation) = pitch()
        period = lag
        voicing = correlation
    }

    /**
     * Normalized autocorrelation over 2.5-20 ms lags on the newest [window] samples (NetEq Expand
     * searches the same pitch range). Short or silent history: no voicing, a 10 ms period.
     */
    internal fun pitch(): Pair<Int, Double> {
        val fallback = minOf(maxOf(1, historyFill), window)
        if (historyFill < maxLag + window || eventRmsOf() < 1.0) return fallback to 0.0
        val end = historyFill
        val start = end - window
        var energyA = 0.0
        for (n in start until end) energyA += history[n].toDouble() * history[n]
        val correlations = DoubleArray(maxLag - minLag + 1)
        var best = 0.0
        for (lag in minLag..maxLag) {
            var dot = 0.0
            var energyB = 0.0
            for (n in start until end) {
                val b = history[n - lag].toDouble()
                dot += history[n] * b
                energyB += b * b
            }
            val c = if (energyA <= 0.0 || energyB <= 0.0) 0.0 else dot / sqrt(energyA * energyB)
            correlations[lag - minLag] = c
            if (c > best) best = c
        }
        if (best <= 0.0) return fallback to 0.0
        // The shortest local peak within 2% of the best: multiples of the period correlate as well.
        for (i in correlations.indices) {
            val c = correlations[i]
            val left = if (i > 0) correlations[i - 1] else Double.NEGATIVE_INFINITY
            val right = if (i < correlations.lastIndex) correlations[i + 1] else Double.NEGATIVE_INFINITY
            if (c >= best * PEAK_TOLERANCE && c >= left && c >= right) return (i + minLag) to c.coerceIn(0.0, 1.0)
        }
        return fallback to 0.0
    }

    private fun eventRmsOf(): Double = rmsOf(history, maxOf(0, historyFill - window), historyFill)

    private fun nextSample(): Short {
        val mute = if (eventSamples < fadeSamples) 1.0 - eventSamples.toDouble() / fadeSamples else 0.0
        val periodic = if (historyFill >= period && period > 0) {
            history[historyFill - period + periodPos % period].toDouble()
        } else {
            0.0
        }
        periodPos++
        // Uniform noise with the wanted RMS: amplitude = rms * sqrt(3).
        val expandNoise = (random.nextDouble() * 2.0 - 1.0) * eventRms * SQRT3
        val expand = mute * (voicing * periodic + (1.0 - voicing) * expandNoise)
        val comfortRms = minOf(maxOf(noiseRms, 0.0), COMFORT_MAX_RMS)
        val comfort = (1.0 - mute) * (random.nextDouble() * 2.0 - 1.0) * comfortRms * SQRT3
        eventSamples++
        return clamp(expand + comfort)
    }

    private fun clamp(v: Double): Short = v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()

    companion object {
        const val HISTORY_MS = 40
        const val MIN_PITCH_US = 2_500
        const val MAX_PITCH_US = 20_000
        const val CORRELATION_WINDOW_MS = 10
        /** The pitch repeat fades out over this time, then only comfort noise remains. */
        const val EXPAND_FADE_MS = 60
        const val MERGE_MS = 5
        const val NOISE_BLOCK_MS = 10
        const val NOISE_RISE = 0.01
        const val PEAK_TOLERANCE = 0.98
        /** Comfort noise never exceeds about -50 dBFS RMS. */
        const val COMFORT_MAX_RMS = 100.0
        private val SQRT3 = sqrt(3.0)
    }
}

/**
 * Silence-gated catch-up (NetEq Accelerate prefers non-speech): playout may run faster than 1.0x
 * only while the chunk is quiet, unless the backlog beyond the device baseline exceeds
 * [SPEECH_CATCHUP_LAG_MS], so continuous speech still cannot build an unbounded delay. Slowing down
 * is unchanged.
 */
internal object SilenceGatedCatchUp {
    /** About -35 dBFS RMS: below this a 20 ms chunk counts as silence or background. */
    const val SPEECH_RMS = 600.0
    const val SPEECH_CATCHUP_LAG_MS = 400L

    fun speed(requested: Float, chunkRms: Double, excessLagMs: Long): Float = when {
        requested <= 1.0f -> requested
        chunkRms < SPEECH_RMS -> requested
        excessLagMs > SPEECH_CATCHUP_LAG_MS -> requested
        else -> 1.0f
    }
}

/** Concealment writer decision: how much to synthesize while the output is nearly dry. */
internal object ConcealmentSchedule {
    /** Conceal only once the AudioTrack holds less than this (ms). */
    const val LOW_WATER_MS = 30L
    const val CHUNK_MS = 10

    fun shouldConceal(audioStarted: Boolean, primed: Boolean, queuedMs: Long): Boolean =
        audioStarted && primed && queuedMs < LOW_WATER_MS
}
