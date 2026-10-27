package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stream viewer audio counters: per-window aggregation, totals and the final line. */
class StreamAudioStatsTest {
    private var now = 0L

    private fun field(line: String, name: String): String =
        line.split(" | ").map { it.substringAfter("stream_audio_stats: ") }
            .first { it.startsWith("$name=") }.substringAfter("=")

    @Test
    fun aWindowAggregatesRecordsBatchSizesPlaysAgesAndDecoderDeltas() {
        val stats = StreamAudioStats("s1", hops = { 2 }, clockMs = { now })
        // Five 100 ms batches of 5 units and one 40 ms pair; each unit plays 30..70 ms after it arrived.
        var pts = 0L
        repeat(5) {
            stats.onRecord(5)
            repeat(5) { stats.onUnitArrived(pts); pts += 20_000 }
        }
        stats.onRecord(2)
        repeat(2) { stats.onUnitArrived(pts); pts += 20_000 }
        now += 50
        listOf(0L, 20_000L, 40_000L).forEach(stats::onUnitPlayed)
        now += 20
        stats.onUnitPlayed(60_000L)
        // A unit the decoder never saw arrive (no age sample) still counts as played.
        stats.onUnitPlayed(9_999_999L)
        assertNull(stats.tick { StreamAudioDecoderCounters() })

        now = 10_000
        val first = stats.tick {
            StreamAudioDecoderCounters(concealedFrames = 3, senderGaps = 1, gapSilenceMs = 40, underruns = 2)
        }!!
        assertEquals("interval", field(first, "event"))
        assertEquals("2", field(first, "hops"))
        assertEquals("6", field(first, "records"))
        assertEquals("0.6", field(first, "records_per_s"))
        assertEquals("27", field(first, "units_received"))
        assertEquals("2:1,5:5", field(first, "batch_sizes"))
        assertEquals("5", field(first, "units_played"))
        assertEquals("4", field(first, "age_samples"))
        assertEquals("70", field(first, "age_max_ms"))
        assertEquals("50", field(first, "age_median_ms"))
        assertEquals("3", field(first, "concealed_frames"))
        assertEquals("2", field(first, "underruns"))

        // The next window counts only what happened in it; totals keep growing.
        stats.onRecord(5)
        now = 20_000
        val second = stats.tick { StreamAudioDecoderCounters(concealedFrames = 4, senderGaps = 1, gapSilenceMs = 40, underruns = 5) }!!
        assertEquals("1", field(second, "records"))
        assertEquals("5:1", field(second, "batch_sizes"))
        assertEquals("0", field(second, "units_played"))
        assertEquals("none", field(second, "age_median_ms"))
        assertEquals("1", field(second, "concealed_frames"))
        assertEquals("0", field(second, "sender_gaps"))
        assertEquals("3", field(second, "underruns"))
        assertEquals("7", field(second, "total_records"))
        assertEquals("5", field(second, "total_underruns"))
    }

    @Test
    fun theFinalLineIsWrittenOnceAndStopsIntervals() {
        val stats = StreamAudioStats("direct", hops = { null }, clockMs = { now })
        stats.onRecord(2)
        now = 3_000
        val last = stats.finish(StreamAudioDecoderCounters(underruns = null))!!
        assertEquals("final", field(last, "event"))
        assertEquals("unknown", field(last, "hops"))
        assertEquals("unknown", field(last, "underruns"))
        assertTrue(last.startsWith("BENCHMARK stream_audio_stats: "))
        assertNull(stats.finish(StreamAudioDecoderCounters()))
        now = 60_000
        assertNull(stats.tick { StreamAudioDecoderCounters() })
    }
}
