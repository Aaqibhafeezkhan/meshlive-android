package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experimental join probe seed (`test_join_probe_seed`) and seed-raise drain guard
 * (`test_seed_raise_drain_guard`), 2026-10-17. Both default off; the cases mirror LinkSelfMeasurementTest.
 */
class JoinProbeSeedTest {
    private var nowNs = 0L

    private fun pacer(burstNs: Long = LinkPacer.BURST_NS) = LinkPacer({ nowNs }, burstNs)

    private fun meter(joinWindowNs: Long? = 3_000_000_000L) = ArrivalTrainMeter({ nowNs }).also {
        if (joinWindowNs != null) it.enableJoinBurst(joinWindowNs)
    }

    /** One 2 000-byte record whose bytes arrive over [spanMs] after a header the reader waited for. */
    private fun record(meter: ArrivalTrainMeter, spanMs: Long, waited: Boolean = true, size: Int = 2_000): Int? {
        meter.beginRecord()
        meter.onRead(17, waited = waited, availableAfter = 0)
        nowNs += spanMs * 1_000_000L
        meter.onRead(size - 17, waited = waited, availableAfter = 0)
        return meter.endRecord()
    }

    // ---- receiver: the join burst measures records too small to be a train

    @Test
    fun smallBackToBackRecordsInTheJoinWindowAreMeasuredAsOneBurst() {
        val m = meter()
        // Five 2 000-byte records, 40 ms each: 1 983 + 4 x 2 000 = 9 983 bytes over 200 ms = 399 kbps.
        repeat(4) {
            assertNull(record(m, 40))
            assertNull(m.estimateKbps())
        }
        assertNull(record(m, 40)) // no single record is a train
        assertEquals(399, m.estimateKbps())
        assertEquals(1L, m.trains)
        // One sample per join window.
        repeat(5) { record(m, 40) }
        assertEquals(1L, m.trains)
    }

    @Test
    fun withoutTheJoinBurstTheSameRecordsAreNeverMeasured() {
        val m = meter(joinWindowNs = null)
        repeat(20) { assertNull(record(m, 40)) }
        assertNull(m.estimateKbps())
        assertEquals(0L, m.trains)
    }

    @Test
    fun anIdleGapEndsTheBurstSoASourceLimitedPauseIsNotAveragedIn() {
        val m = meter()
        repeat(3) { record(m, 40) }
        nowNs += 250_000_000L // the sender had nothing for 250 ms: a new burst starts
        repeat(3) { record(m, 40) }
        assertNull(m.estimateKbps())
        repeat(2) { record(m, 40) }
        // 1 983 + 4 x 2 000 bytes over 200 ms after the gap, not over the whole 0.6 s.
        assertEquals(399, m.estimateKbps())
    }

    @Test
    fun aRecordAlreadyBufferedCannotStartABurst() {
        val m = meter()
        repeat(10) { assertNull(record(m, 40, waited = false)) }
        assertNull(m.estimateKbps())
    }

    @Test
    fun theJoinSampleExpiresWithTheWindowAndAGenuineTrainReplacesIt() {
        val m = meter(joinWindowNs = 3_000_000_000L)
        repeat(5) { record(m, 40) }
        assertEquals(399, m.estimateKbps())
        nowNs += 3_000_000_000L
        assertNull(m.estimateKbps())

        val m2 = meter()
        repeat(5) { record(m2, 40) }
        assertEquals(399, m2.estimateKbps())
        // A genuine 10 000-byte train over 100 ms (800 kbps) is the estimate, not the median with the burst.
        m2.beginRecord()
        m2.onRead(17, waited = true, availableAfter = 0)
        nowNs += 100_000_000L
        m2.onRead(10_000, waited = true, availableAfter = 0)
        assertEquals(800, m2.endRecord())
        assertEquals(800, m2.estimateKbps())
    }

    @Test
    fun theJoinSampleIsNotAGenuineTrainAndLeavesInOneReportOnly() {
        val m = meter()
        repeat(5) { record(m, 40) }
        assertEquals(1L, m.trains)
        // The viewer keeps sending on-time reports while no genuine train exists.
        assertEquals(0L, m.genuineTrains)
        assertEquals(399, m.estimateKbps())
        m.joinReported() // the report carrying it has been sent: later reports do not repeat the lower bound
        assertNull(m.estimateKbps())
        assertEquals(1L, m.trains)
        assertEquals(0L, m.genuineTrains)
    }

    @Test
    fun aGenuineTrainCountsAsOne() {
        val m = meter(joinWindowNs = null)
        m.beginRecord()
        m.onRead(17, waited = true, availableAfter = 0)
        nowNs += 100_000_000L
        m.onRead(10_000, waited = true, availableAfter = 0)
        assertEquals(800, m.endRecord())
        assertEquals(1L, m.genuineTrains)
        assertEquals(1L, m.trains)
    }

    // ---- sender: the unpaced probe window and the seed

    @Test
    fun theProbeNeedsASeedAndStartsOnce() {
        val p = pacer()
        assertFalse(p.beginJoinProbe())
        p.seedKbps(350)
        assertTrue(p.beginJoinProbe())
        assertFalse(p.beginJoinProbe())
    }

    @Test
    fun writesInsideTheProbeWindowAreUnpacedUnchargedAndBoundedByBytes() {
        val p = pacer(burstNs = 0L)
        p.seedKbps(350) // 52 500 B/s
        p.beginJoinProbe()
        p.onSent(20_000, nowNs)
        assertEquals(0L, p.waitNs())
        assertTrue(p.joinProbeBypassing)
        p.onSent(20_000, nowNs) // 40 000 bytes: the byte cap is reached
        assertFalse(p.joinProbeBypassing)
        p.onSent(10_500, nowNs) // charged at the seed again: 200 ms of link time
        assertEquals(200_000_000L, p.waitNs())
    }

    @Test
    fun theProbeWindowAlsoEndsAfterTheTimeBound() {
        val p = pacer(burstNs = 0L)
        p.seedKbps(350)
        p.beginJoinProbe()
        nowNs += LinkPacer.JOIN_PROBE_BYPASS_NS - 1
        assertTrue(p.joinProbeBypassing)
        nowNs += 1
        assertFalse(p.joinProbeBypassing)
        p.onSent(5_250, nowNs)
        assertEquals(100_000_000L, p.waitNs())
    }

    @Test
    fun aBackloggedSenderTakesTheBurstAsTheLinkRateAndTheCallerMeasuresIt() {
        val p = pacer()
        p.seedKbps(350)
        p.beginJoinProbe()
        nowNs += 800_000_000L
        val result = p.resolveJoinProbe(900, linkBound = true)!!
        assertEquals(JoinProbeResult(900, 900, 800, fallback = false, linkBound = true), result)
        assertEquals(350, p.seedKbps) // the caller applies setMeasuredLinkKbps
        assertFalse(p.measured)
        p.setMeasuredLinkKbps(900)
        assertEquals((900 * 1_000L / 8 * LinkPacer.PACE_GAIN).toLong(), p.bytesPerSecond)
        assertNull(p.resolveJoinProbe(900, linkBound = true)) // once
        assertNull(p.expireJoinProbe())
    }

    @Test
    fun aSenderThatKeptUpOnlyRaisesTheSeedAndItStaysARaisableSeed() {
        val p = pacer()
        p.seedKbps(350)
        p.beginJoinProbe()
        nowNs += 500_000_000L
        val result = p.resolveJoinProbe(500, linkBound = false)!!
        assertEquals(JoinProbeResult(500, 500, 500, fallback = false, linkBound = false), result)
        assertEquals(500, p.seedKbps)
        assertFalse(p.measured)
        assertEquals((500 * 1_000L / 8 * LinkPacer.PACE_GAIN).toLong(), p.bytesPerSecond)
        // Still a seed: the on-time raise and the hold apply.
        assertTrue(p.holdSeed(LinkPacer.SEED_RAISE_BACKLOG_MS))
        assertEquals(625, p.seedKbps)
    }

    @Test
    fun aBurstBelowThePriorNeverLowersAnUnbackloggedSeedAndAHugeOneIsCapped() {
        val low = pacer()
        low.seedKbps(350)
        low.beginJoinProbe()
        assertEquals(350, low.resolveJoinProbe(200, linkBound = false)!!.seedKbps)
        assertEquals(350, low.seedKbps)
        val high = pacer()
        high.seedKbps(350)
        high.beginJoinProbe()
        assertEquals(LinkPacer.SEED_RAISE_CAP_KBPS, high.resolveJoinProbe(4_000, linkBound = false)!!.seedKbps)
    }

    @Test
    fun withoutAReportTheProbeFallsBackToThePriorOnceAfterTheBound() {
        val p = pacer()
        p.seedKbps(350)
        p.beginJoinProbe()
        nowNs += LinkPacer.JOIN_PROBE_RESOLVE_NS - 1
        assertNull(p.expireJoinProbe())
        nowNs += 101_000_000L
        // A report after the bound is too late for the probe.
        assertNull(p.resolveJoinProbe(900, linkBound = true))
        val fallback = p.expireJoinProbe()!!
        assertEquals(JoinProbeResult(0, 350, 3_100, fallback = true, linkBound = false), fallback)
        assertNull(p.expireJoinProbe())
        assertEquals(350, p.seedKbps)
        assertFalse(p.joinProbeBypassing)
    }

    @Test
    fun anOrdinaryMeasurementEndsTheProbeWindow() {
        val p = pacer()
        p.seedKbps(350)
        p.beginJoinProbe()
        p.setMeasuredLinkKbps(700)
        assertFalse(p.joinProbeBypassing)
        assertNull(p.resolveJoinProbe(700, linkBound = true))
    }

    @Test
    fun theBenchmarkLineCarriesTheRequestedFields() {
        assertEquals(
            "BENCHMARK join_probe_seed: role=stream_tx | peer=ab12 | measuredKbps=900 | seedKbps=900 | " +
                "probeMs=800 | fallback=false | linkBound=true",
            JoinProbeResult(900, 900, 800, fallback = false, linkBound = true).benchmarkLine("stream_tx", "ab12"),
        )
        assertEquals(
            "BENCHMARK join_probe_seed: role=relay_tx | peer=ab12 | measuredKbps=0 | seedKbps=350 | " +
                "probeMs=3100 | fallback=true | linkBound=false",
            JoinProbeResult(0, 350, 3_100, fallback = true, linkBound = false).benchmarkLine("relay_tx", "ab12"),
        )
    }

    // ---- drain guard

    @Test
    fun withoutTheGuardTheRaiseKeepsClimbingOnABlockedLink() {
        val p = pacer()
        p.seedKbps(350)
        repeat(4) {
            nowNs += 1_000_000_000L
            p.noteWrite(heldNs = 0L, writeNs = 900_000_000L)
            p.holdSeed(5_000L + it * 200L)
        }
        assertTrue(p.seedKbps > 800)
        assertNull(p.lastDrainGuardBlock)
    }

    @Test
    fun theGuardStopsTheRaiseWhileTheQueueIsNotDrainingAndWritesBlock() {
        val p = pacer()
        p.seedKbps(350)
        nowNs += 1_000_000_000L
        p.noteWrite(0L, 900_000_000L)
        assertTrue(p.holdSeed(5_000L, drainGuard = true)) // the first eligible report has no history: raises
        assertEquals(437, p.seedKbps)
        assertNull(p.lastDrainGuardBlock)
        nowNs += 1_000_000_000L
        p.noteWrite(0L, 900_000_000L)
        assertTrue(p.holdSeed(5_200L, drainGuard = true)) // queue grew, writes blocked: no raise, seed held
        assertEquals(437, p.seedKbps)
        assertEquals(DrainGuardBlock(5_000L, 5_200L, 90L, 0L), p.lastDrainGuardBlock)
        nowNs += 3_000_000_000L // the hold restarted the silence clock: no decay inside the grace
        assertEquals((437.5 * 1_000 / 8 * LinkPacer.PACE_GAIN).toLong(), p.bytesPerSecond)
        // The queue starts to fall: the raise resumes.
        p.noteWrite(0L, 900_000_000L)
        assertTrue(p.holdSeed(4_500L, drainGuard = true))
        assertNull(p.lastDrainGuardBlock)
        assertEquals(546, p.seedKbps)
    }

    @Test
    fun aPacerLimitedWriterKeepsRaisingEvenWhileTheQueueGrows() {
        val p = pacer()
        p.seedKbps(350)
        nowNs += 1_000_000_000L
        p.noteWrite(heldNs = 800_000_000L, writeNs = 50_000_000L)
        p.holdSeed(5_000L, drainGuard = true)
        nowNs += 1_000_000_000L
        p.noteWrite(heldNs = 800_000_000L, writeNs = 50_000_000L)
        p.holdSeed(5_400L, drainGuard = true)
        assertNull(p.lastDrainGuardBlock)
        assertEquals(546, p.seedKbps)
    }

    @Test
    fun nonBlockingWritesNeverTriggerTheGuard() {
        // A CoC or Classic write returns at once: the guard cannot see a link limit and leaves the raise as it was.
        val p = pacer()
        p.seedKbps(350)
        repeat(3) {
            nowNs += 1_000_000_000L
            p.noteWrite(heldNs = 0L, writeNs = 2_000_000L)
            p.holdSeed(5_000L, drainGuard = true)
        }
        assertNull(p.lastDrainGuardBlock)
        assertEquals(683, p.seedKbps) // three raises: the guard never saw a link limit
    }
}
