package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImpairmentPlanTest {
    private fun phase(
        id: String = "delay", start: Long = 0, end: Long = 10_000, delay: Long = 20,
        jitter: Long = 0, rate: Long = 0, drop: Long = 0, burst: Long = 0, length: Long = 0,
    ) = MediaImpairmentPhase(id, start, end, delay, jitter, rate, drop, burst, length)

    private fun config(vararg phases: MediaImpairmentPhase, seed: Long = 1, wait: Long = 500) =
        MediaImpairmentConfig(seed, wait, phases.toList())

    @Test
    fun configurationAndControlBypassDoNotStartOrAdvanceMediaPhase() {
        val plan = MediaImpairmentPlan(config(phase(drop = 2)))
        val control = plan.offer(StreamProtocol.FLAG_CODEC_CONFIG, 1, 0)
        assertEquals("BYPASS", control.decision)
        assertNull(plan.originNs)
        assertNull(control.mediaIndex)
        assertEquals("SEND", plan.offer(0, 10, 10_000_000).decision)
        assertEquals("BYPASS", plan.offer(StreamProtocol.FLAG_DISPLAY_ROTATION, 1, 20_000_000).decision)
        assertEquals("DROP_PATTERN", plan.offer(StreamProtocol.FLAG_AUDIO, 10, 30_000_000).decision)
        assertEquals(10_000_000L, plan.originNs)
    }

    @Test
    fun audioKeyframesAndDiscontinuityAreMediaButUnknownFlagsAreNeverImpaired() {
        assertTrue(MediaImpairmentPlan.eligible(StreamProtocol.FLAG_KEY_FRAME))
        assertTrue(MediaImpairmentPlan.eligible(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR))
        assertTrue(MediaImpairmentPlan.eligible(StreamProtocol.FLAG_DISCONTINUITY or StreamProtocol.FLAG_DEBUG_INTEGRITY))
        // Experimental temporal layering marks discardable video with bit 7; it is media like any frame.
        assertTrue(MediaImpairmentPlan.eligible(StreamProtocol.FLAG_DISCARDABLE))
        assertFalse(MediaImpairmentPlan.eligible(256))
        assertFalse(MediaImpairmentPlan.eligible(-1))
    }

    @Test
    fun seedAndIndexJitterAreDeterministicAndBounded() {
        val left = MediaImpairmentPlan(config(phase(delay = 30, jitter = 20), seed = 19))
        val right = MediaImpairmentPlan(config(phase(delay = 30, jitter = 20), seed = 19))
        val jitters = (0L..100).map { index ->
            val a = left.offer(0, 1, index * 33_000_000)
            val b = right.offer(0, 1, index * 33_000_000)
            assertEquals(a, b)
            assertTrue(a.jitterMs in -20..20)
            a.jitterMs
        }
        assertTrue(jitters.toSet().size > 10)
    }

    @Test
    fun seededJitterCanNeverReorderDeliveredMedia() {
        val plan = MediaImpairmentPlan(config(phase(delay = 100, jitter = 100), wait = 500))
        val decisions = (0L..100).map { plan.offer(0, 10, it * 1_000_000) }
        assertTrue(decisions.zipWithNext().all { (a,b) -> a.plannedReleaseNs!! <= b.plannedReleaseNs!! })
    }

    @Test
    fun byteRateChargesEveryDeliveredWholeAccessUnitAndDropsBoundedOverload() {
        val plan = MediaImpairmentPlan(config(phase(delay = 0, rate = 8_000), wait = 250))
        assertEquals(100_000_000L, plan.offer(0,100,0).plannedReleaseNs)
        assertEquals(200_000_000L, plan.offer(0,100,0).plannedReleaseNs)
        assertEquals("DROP_MAX_WAIT", plan.offer(0,100,0).decision)
        val next = plan.offer(0,100,250_000_000)
        assertEquals("SEND", next.decision)
        assertEquals(350_000_000L, next.plannedReleaseNs)
    }

    @Test
    fun periodicAndBurstDropsHaveExactWholeMediaIndicesAndDoNotChargeRate() {
        val plan = MediaImpairmentPlan(config(phase(delay = 0, rate = 8_000, drop = 4, burst = 6, length = 2)))
        val decisions = (1..12).map { plan.offer(0, 100, 0).decision }
        assertEquals(listOf("DROP_PATTERN", "DROP_PATTERN", "SEND", "DROP_PATTERN", "SEND", "SEND",
            "DROP_PATTERN", "DROP_PATTERN", "SEND", "SEND", "DROP_MAX_WAIT", "DROP_PATTERN"), decisions)
    }

    @Test
    fun phaseBoundariesAreHalfOpenWithSeparateIndicesAndUnimpairedGaps() {
        val plan = MediaImpairmentPlan(config(phase("first",0,10,drop = 2), phase("later",20,30,drop = 2)))
        plan.offer(0,1,0)
        assertEquals("DROP_PATTERN", plan.offer(0,1,9_000_000).decision)
        assertNull(plan.offer(0,1,10_000_000).phase)
        assertNull(plan.offer(0,1,19_000_000).phase)
        assertEquals(1L, plan.offer(0,1,20_000_000).phaseIndex)
        assertEquals("DROP_PATTERN", plan.offer(0,1,29_000_000).decision)
        assertNull(plan.offer(0,1,30_000_000).phase)
    }

    @Test
    fun waitUsesInjectedClockAndBoundedSlicesWithoutHoldingPayloads() {
        var now = 0L
        val plan = MediaImpairmentPlan(config(phase(delay = 30))) { now }
        val decision = plan.offer(0,1)
        val waits = mutableListOf<Long>()
        assertEquals("SEND", plan.await(decision, { false }, { waits += it; now += it }, { false }))
        assertEquals(30_000_000L, now)
        assertEquals(6, waits.size)
        assertTrue(waits.all { it <= 5_000_000 })
    }

    @Test
    fun closureInterruptAndOversleepCannotDeliverLateMedia() {
        var now = 0L
        val plan = MediaImpairmentPlan(config(phase(delay = 30), wait = 40)) { now }
        val decision = plan.offer(0,1)
        assertEquals("DROP_CLOSED", plan.await(decision, { true }, {}, { false }))
        assertEquals("DROP_INTERRUPTED", plan.await(decision, { false }, {}, { true }))
        assertEquals("DROP_MAX_WAIT", plan.await(decision, { false }, { now += 100_000_000 }, { false }))
    }

    @Test
    fun actualCompletionPreservesOrderForSubsequentScheduledFrames() {
        val plan = MediaImpairmentPlan(config(phase(delay = 0)))
        plan.offer(0,1,0)
        plan.completedRelease(100_000_000)
        assertEquals(100_000_000L, plan.offer(0,1,50_000_000).plannedReleaseNs)
    }

    @Test
    fun rejectsOverlapsUnknownKeysFractionsAndUnboundedConfig() {
        assertThrows(IllegalArgumentException::class.java) { config(phase(end = 20), phase("next",start = 10)) }
        assertThrows(IllegalArgumentException::class.java) { config(phase(),wait = 5_001) }
        assertThrows(IllegalArgumentException::class.java) { phase(rate = -1) }
        assertThrows(IllegalArgumentException::class.java) { phase(burst = 2,length = 3) }
        assertThrows(IllegalArgumentException::class.java) { phase(burst = 3,length = 0) }
        assertThrows(IllegalArgumentException::class.java) { phase(id = "space invalid") }
        val raw = mapOf("schema" to "benchmark-media-impairment-v1", "seed" to 1, "max_wait_ms" to 50,
            "phases" to listOf(mapOf("id" to "clean", "start_ms" to 0, "end_ms" to 10, "delay_ms" to 0,
                "jitter_ms" to 0, "rate_bps" to 0, "drop_every" to 0, "burst_every" to 0, "burst_length" to 0)))
        assertEquals(1L, MediaImpairmentConfig.fromMap(raw).seed)
        assertThrows(IllegalArgumentException::class.java) { MediaImpairmentConfig.fromMap(raw + ("seed" to 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { MediaImpairmentConfig.fromMap(raw + ("silent" to true)) }
    }
}
