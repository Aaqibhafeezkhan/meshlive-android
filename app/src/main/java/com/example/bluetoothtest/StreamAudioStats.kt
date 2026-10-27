package com.example.bluetoothtest

/** The decoder's own cumulative counters, read when a [StreamAudioStats] line is written. */
data class StreamAudioDecoderCounters(
    /** Native libopus PLC and FEC frames (20 ms each) generated for missing units. */
    val concealedFrames: Long = 0L,
    /** Experimental starvation concealment (calls only), in milliseconds. */
    val concealedMs: Long = 0L,
    /** Sender timeline gaps of at least the gap threshold, and the silence written to realign after them. */
    val senderGaps: Long = 0L,
    val gapSilenceMs: Long = 0L,
    /** Units dropped by the decoder (partial AudioTrack writes). */
    val drops: Long = 0L,
    /** AudioTrack.getUnderrunCount(); null when the track could not be read. */
    val underruns: Long? = null,
)

/**
 * DEBUG viewer counters for stream audio (`BENCHMARK stream_audio_stats:`), so stream audio A/Bs such as
 * `test_stream_audio_batch_frames` can be measured on the viewer: audio records received and their sizes (units per
 * record: 1 single, 2 a `BTA1` pair, 3..20 a `BTA2` batch), units played, concealed and gap units, AudioTrack underruns,
 * and the queue age of each played unit. Queue age is from the unit's arrival at the decoder to its AudioTrack write
 * (matched by presentation time); the AudioTrack's own buffer is not included. One line per [intervalMs] window (window
 * counts, plus totals) and a final line at stop. Thread-safe: the receiver thread records arrivals, the playback thread
 * records plays and writes lines.
 */
class StreamAudioStats(
    private val stream: String,
    /** Relay hops above this viewer (initial mesh TTL minus the TTL received; 0 direct), null while unknown. */
    private val hops: () -> Int?,
    private val clockMs: () -> Long,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private var windowStartMs = clockMs()
    private val startedMs = windowStartMs
    private var records = 0L
    private var unitsReceived = 0L
    private val batchSizes = java.util.TreeMap<Int, Long>()
    private var unitsPlayed = 0L
    private val ages = ArrayList<Long>()
    private var totalRecords = 0L
    private var totalUnitsReceived = 0L
    private var totalUnitsPlayed = 0L
    private var lastCounters = StreamAudioDecoderCounters()
    private var finished = false
    private val arrivals = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > MAX_PENDING_ARRIVALS
    }

    /** One audio record arrived carrying [units] units. */
    @Synchronized
    fun onRecord(units: Int) {
        if (units <= 0) return
        records++
        totalRecords++
        unitsReceived += units
        totalUnitsReceived += units
        batchSizes[units] = (batchSizes[units] ?: 0L) + 1L
    }

    /** A unit with [presentationTimeUs] entered the decoder queue. */
    @Synchronized
    fun onUnitArrived(presentationTimeUs: Long) {
        arrivals[presentationTimeUs] = clockMs()
    }

    /** Decoded audio of the unit at [presentationTimeUs] was written to the AudioTrack. */
    @Synchronized
    fun onUnitPlayed(presentationTimeUs: Long) {
        unitsPlayed++
        totalUnitsPlayed++
        val arrived = arrivals.remove(presentationTimeUs) ?: return
        if (ages.size < MAX_AGE_SAMPLES) ages += (clockMs() - arrived).coerceAtLeast(0L)
    }

    /** The interval line when a window has elapsed, else null. */
    @Synchronized
    fun tick(counters: () -> StreamAudioDecoderCounters): String? {
        if (finished || clockMs() - windowStartMs < intervalMs) return null
        return line("interval", counters())
    }

    /** The final line (once; null afterwards). */
    @Synchronized
    fun finish(counters: StreamAudioDecoderCounters): String? {
        if (finished) return null
        finished = true
        return line("final", counters)
    }

    private fun line(event: String, counters: StreamAudioDecoderCounters): String {
        val now = clockMs()
        val windowMs = (now - windowStartMs).coerceAtLeast(1L)
        val sorted = ages.sorted()
        val median = if (sorted.isEmpty()) "none" else sorted[sorted.size / 2].toString()
        val max = sorted.lastOrNull()?.toString() ?: "none"
        val previous = lastCounters
        val underrunsDelta = if (counters.underruns != null && previous.underruns != null) {
            (counters.underruns - previous.underruns).toString()
        } else {
            counters.underruns?.toString() ?: "unknown"
        }
        val text = "BENCHMARK stream_audio_stats: event=$event | stream=$stream | hops=${hops() ?: "unknown"} | " +
            "window_ms=$windowMs | records=$records | records_per_s=${"%.1f".format(java.util.Locale.ROOT, records * 1_000.0 / windowMs)} | " +
            "units_received=$unitsReceived | batch_sizes=${if (batchSizes.isEmpty()) "none" else batchSizes.entries.joinToString(",") { "${it.key}:${it.value}" }} | " +
            "units_played=$unitsPlayed | age_samples=${sorted.size} | age_median_ms=$median | age_max_ms=$max | " +
            "concealed_frames=${counters.concealedFrames - previous.concealedFrames} | " +
            "concealed_ms=${counters.concealedMs - previous.concealedMs} | " +
            "sender_gaps=${counters.senderGaps - previous.senderGaps} | " +
            "gap_silence_ms=${counters.gapSilenceMs - previous.gapSilenceMs} | " +
            "drops=${counters.drops - previous.drops} | underruns=$underrunsDelta | " +
            "total_ms=${now - startedMs} | total_records=$totalRecords | total_units_received=$totalUnitsReceived | " +
            "total_units_played=$totalUnitsPlayed | total_concealed_frames=${counters.concealedFrames} | " +
            "total_underruns=${counters.underruns ?: "unknown"}"
        windowStartMs = now
        records = 0L
        unitsReceived = 0L
        batchSizes.clear()
        unitsPlayed = 0L
        ages.clear()
        lastCounters = counters
        return text
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        private const val MAX_AGE_SAMPLES = 4_096
        private const val MAX_PENDING_ARRIVALS = 1_024
    }
}
