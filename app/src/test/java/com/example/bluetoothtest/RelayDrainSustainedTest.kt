package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q31 follow-up (experimental `test_relay_drain_sustained`, off by default): a slow GATT hop never forms the
 * arrival trains the child's self-measurement needs (lane 9: drainKbps=0 in 319 of 319 relay_time_queue lines),
 * so the relay measures its own sustained delivery. Injected clocks throughout: nothing here sleeps.
 */
class RelayDrainSustainedTest {
    private val ms = 1_000_000L

    /** [writes] writes of [bytes] each, one every [everyMs], all with media still queued; returns the end time. */
    private fun steadyWrites(
        meter: SustainedDeliveryMeter,
        startNs: Long,
        writes: Int,
        bytes: Int,
        everyMs: Long,
        backlogged: Boolean = true,
        heldNs: Long = 0L,
    ): Long {
        var nowNs = startNs
        repeat(writes) {
            nowNs += everyMs * ms
            meter.onWritten(nowNs, bytes, backlogged, heldNs)
        }
        return nowNs
    }

    @Test
    fun theSwitchIsOffByDefault() {
        assertFalse(DebugTunables.relayDrainSustained)
        assertFalse(RelayMediaAgeBound().sustainedDrain)
    }

    @Test
    fun aSlowHopThatNeverFormsAnArrivalTrainStillYieldsADrainRate() {
        // 1 250 bytes every 100 ms = 100 kbps: far below one 8 000-byte train per 60 ms.
        var trainNs = 0L
        val train = ArrivalTrainMeter(nanoTime = { trainNs })
        train.beginRecord()
        train.onRead(1_250, waited = true, availableAfter = 0)
        trainNs += 100 * ms
        train.onRead(1_250, waited = false, availableAfter = 0)
        trainNs += 100 * ms
        train.onRead(1_250, waited = false, availableAfter = 0)
        assertNull("the control estimator cannot measure a slow hop", train.endRecord())
        assertEquals(0L, train.trains)

        val meter = SustainedDeliveryMeter()
        val endNs = steadyWrites(meter, 0L, writes = 120, bytes = 1_250, everyMs = 100L)
        val kbps = meter.estimateKbps(endNs)
        assertNotNull(kbps)
        assertEquals(100.0, kbps!!.toDouble(), 2.0)
    }

    @Test
    fun noEstimateBeforeTheWarmupAndTheMinimumBusyTimeHavePassed() {
        val meter = SustainedDeliveryMeter()
        // 4 s of backlogged writes: 1.5 s warm-up leaves 2.5 s, below the 3 s minimum.
        val shortNs = steadyWrites(meter, 0L, writes = 40, bytes = 1_250, everyMs = 100L)
        assertNull(meter.estimateKbps(shortNs))
        val longNs = steadyWrites(meter, shortNs, writes = 20, bytes = 1_250, everyMs = 100L)
        assertNotNull(meter.estimateKbps(longNs))
    }

    @Test
    fun anIdleQueueNeverProducesADrainRate() {
        val meter = SustainedDeliveryMeter()
        // The writer keeps up and the queue is empty after every write: bursts of a few kB say nothing about capacity.
        val endNs = steadyWrites(meter, 0L, writes = 400, bytes = 4_000, everyMs = 50L, backlogged = false)
        assertNull(meter.estimateKbps(endNs))
    }

    @Test
    fun aBriefBurstOfBacklogDoesNotMakeAnEstimate() {
        val meter = SustainedDeliveryMeter()
        var nowNs = steadyWrites(meter, 0L, writes = 20, bytes = 4_000, everyMs = 50L, backlogged = false)
        // A keyframe: 1.0 s of backlogged writes (a 20 kB frame at ~200 kbps), then the queue empties again.
        nowNs = steadyWrites(meter, nowNs, writes = 10, bytes = 2_500, everyMs = 100L)
        nowNs = steadyWrites(meter, nowNs, writes = 20, bytes = 4_000, everyMs = 50L, backlogged = false)
        assertNull(meter.estimateKbps(nowNs))
    }

    @Test
    fun aWriterHeldByThePacerMeasuresThePaceNotTheLink() {
        val meter = SustainedDeliveryMeter()
        // The pacer held the writer for 60 of every 100 ms.
        val endNs = steadyWrites(meter, 0L, writes = 150, bytes = 1_250, everyMs = 100L, heldNs = 60 * ms)
        assertNull(meter.estimateKbps(endNs))
        // Held for 20 ms of 100 ms is still link-bound.
        val lightNs = steadyWrites(meter, endNs, writes = 150, bytes = 1_250, everyMs = 100L, heldNs = 20 * ms)
        assertNotNull(meter.estimateKbps(lightNs))
    }

    @Test
    fun theEstimateIsHeldForAWhileAfterTheBacklogClearsThenDropped() {
        val meter = SustainedDeliveryMeter()
        val endNs = steadyWrites(meter, 0L, writes = 120, bytes = 1_250, everyMs = 100L)
        assertNotNull(meter.estimateKbps(endNs + 29_000 * ms))
        assertNull(meter.estimateKbps(endNs + 31_000 * ms))
    }

    @Test
    fun theEstimateFollowsALinkThatSlowsDown() {
        val meter = SustainedDeliveryMeter()
        var nowNs = steadyWrites(meter, 0L, writes = 150, bytes = 2_500, everyMs = 100L) // 200 kbps
        assertEquals(200.0, meter.estimateKbps(nowNs)!!.toDouble(), 4.0)
        nowNs = steadyWrites(meter, nowNs, writes = 120, bytes = 1_250, everyMs = 100L) // 100 kbps for 12 s
        assertEquals(100.0, meter.estimateKbps(nowNs)!!.toDouble(), 4.0)
    }

    @Test
    fun pushesOnlyMaterialChanges() {
        assertTrue(sustainedDrainChanged(0, 100))
        assertTrue(sustainedDrainChanged(100, 0))
        assertFalse(sustainedDrainChanged(0, 0))
        assertFalse(sustainedDrainChanged(100, 103))
        assertTrue(sustainedDrainChanged(100, 105))
        assertTrue(sustainedDrainChanged(100, 90))
    }

    // ---- the queue's overload judgement fed by the estimate ----

    private fun sizerAndBound(sustained: Boolean, clock: () -> Long): Pair<RelayTimeQueueSizer, RelayMediaAgeBound> {
        val sizer = RelayTimeQueueSizer(RelayTimeQueueConfig(recomputeNs = 0L), 66L, clock)
        return sizer to RelayMediaAgeBound(sizer, sustainedDrain = sustained)
    }

    /** Offers [bytesPerOffer] every 100 ms for 5 s. */
    private fun offerFor5s(sizer: RelayTimeQueueSizer, setNow: (Long) -> Unit, bytesPerOffer: Int) {
        repeat(50) { i ->
            setNow((i + 1) * 100L * ms)
            sizer.onOffered(bytesPerOffer, keyFrame = false)
        }
    }

    @Test
    fun overloadIsDetectedAt1p2To1p3TimesTheSustainedDrainButNotJustAboveIt() {
        for ((offeredBytes, expected) in listOf(1_562 to true /* 125 kbps */, 1_625 to true /* 130 kbps */, 1_300 to false /* 104 kbps */)) {
            var nowNs = 0L
            val (sizer, bound) = sizerAndBound(sustained = true) { nowNs }
            bound.setSustainedDrainKbps(100)
            offerFor5s(sizer, { nowNs = it }, offeredBytes)
            val snapshot = sizer.snapshot(1_750L)
            assertEquals(100, snapshot.drainKbps)
            assertEquals("offered ${snapshot.offeredKbps} kbps against 100", expected, snapshot.overload)
        }
    }

    @Test
    fun withTheSwitchOffTheSlowHopStaysInertAsBeforeAndTheSustainedEstimateIsOnlyLogged() {
        var nowNs = 0L
        val (sizer, bound) = sizerAndBound(sustained = false) { nowNs }
        bound.setDrainKbps(0) // the child's trains reported nothing
        bound.setSustainedDrainKbps(100)
        offerFor5s(sizer, { nowNs = it }, 1_562)
        val snapshot = sizer.snapshot(1_750L)
        assertEquals(0, snapshot.drainKbps)
        assertFalse(snapshot.overload)
        assertEquals("none", bound.drainSource)
        val line = bound.timeQueueSummary(0L)
        assertTrue(line, "drainSource=none" in line)
        assertTrue(line, "sustainedKbps=100" in line)
        assertTrue(line, "sustainedArm=false" in line)
    }

    @Test
    fun withTheSwitchOnTheSustainedEstimateLeadsAndTheTrainReportIsTheFallback() {
        var nowNs = 0L
        val (sizer, bound) = sizerAndBound(sustained = true) { nowNs }
        bound.setDrainKbps(440) // a heavy stream's train report
        assertEquals("train", bound.drainSource)
        assertEquals(440, sizer.snapshot(1_750L).drainKbps)
        bound.setSustainedDrainKbps(100)
        assertEquals("sustained", bound.drainSource)
        assertEquals(100, bound.drainKbps)
        offerFor5s(sizer, { nowNs = it }, 1_562)
        assertTrue(sizer.snapshot(1_750L).overload)
        assertTrue(bound.timeQueueSummary(0L).contains("drainSource=sustained"))
        // The estimate is dropped (idle past the hold): the train report applies again.
        bound.setSustainedDrainKbps(0)
        assertEquals("train", bound.drainSource)
        assertEquals(440, sizer.snapshot(1_750L).drainKbps)
        assertFalse(sizer.snapshot(1_750L).overload)
    }
}
