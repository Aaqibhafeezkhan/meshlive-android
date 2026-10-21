package com.example.bluetoothtest

/**
 * How long a Nearby call row stays after its call offer was last heard. Rows used to stay a fixed
 * [PRIVATE_CALL_PEER_STALE_MS] (12 s), so a phone that had just stopped offering calls (it started a call or a stream, or
 * its app restarted) stayed tappable, and a tap then waited 10 s for nothing (phone runs userflow-a58be974-20261019: the
 * A15s tapped the S24 four seconds after the S24 went into a call with the ASUS).
 *
 * The window now follows how often this scanner really hears that phone: [MISSED_GAPS] missed sightings, measured as the
 * largest of its last [WINDOW] gaps between sightings, kept between [MIN_STALE_MS] and [PRIVATE_CALL_PEER_STALE_MS]. A
 * phone heard fewer than [MIN_GAPS] times, or with a gap longer than the fixed window (it left and came back), keeps the
 * fixed window until enough gaps are measured. Main thread only.
 */
internal class CallPeerCadence {
    private class Track(var lastMs: Long) {
        val gaps = ArrayDeque<Long>()
    }

    private val tracks = HashMap<String, Track>()

    /** The most recent gaps of every phone, kept when rows expire: this scanner's sighting rate (F11). */
    private val recentGaps = ArrayDeque<Long>()

    /** One sighting of the call offer [key] at [nowMs]. */
    fun observe(key: String, nowMs: Long) {
        val track = tracks[key]
        if (track == null) {
            tracks[key] = Track(nowMs)
            return
        }
        val gap = nowMs - track.lastMs
        track.lastMs = nowMs
        when {
            gap <= 0L -> Unit
            // It left the air and came back: what was measured before says nothing about now.
            gap > PRIVATE_CALL_PEER_STALE_MS -> track.gaps.clear()
            else -> {
                track.gaps.addLast(gap)
                while (track.gaps.size > WINDOW) track.gaps.removeFirst()
                recentGaps.addLast(gap)
                while (recentGaps.size > RATE_WINDOW) recentGaps.removeFirst()
            }
        }
    }

    /** How long [key] may go unheard before its row is removed. */
    fun staleAfterMs(key: String): Long {
        val gaps = tracks[key]?.gaps
        if (gaps == null || gaps.size < MIN_GAPS) return PRIVATE_CALL_PEER_STALE_MS
        return (gaps.max() * MISSED_GAPS).coerceIn(MIN_STALE_MS, PRIVATE_CALL_PEER_STALE_MS)
    }

    fun isFresh(key: String, lastSeenMs: Long, nowMs: Long): Boolean = nowMs - lastSeenMs <= staleAfterMs(key)

    /**
     * The median gap between two sightings of one phone over this scanner's recent sightings of every phone, or null with
     * fewer than [MIN_GAPS] measured. 1000 / it is the sighting rate per phone (F11: about 3/s on most phones, 0.3-0.5/s on
     * the Redmi 12).
     */
    fun typicalGapMs(): Long? {
        if (recentGaps.size < MIN_GAPS) return null
        val sorted = recentGaps.sorted()
        return sorted[sorted.size / 2]
    }

    /** The row was removed: a later sighting starts a new measurement. */
    fun forget(key: String) {
        tracks.remove(key)
    }

    fun clear() = tracks.clear()

    companion object {
        const val MISSED_GAPS = 3
        const val WINDOW = 16
        const val MIN_GAPS = 4
        const val MIN_STALE_MS = 4_000L
        const val RATE_WINDOW = 64
    }
}
