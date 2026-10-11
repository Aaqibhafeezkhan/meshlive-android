package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay child link pacing (relayfix-0725a213-diagM1, S24 -> M31 -> ASUS over LE CoC): the child
 * writer skipped its pacer whenever any audio was queued anywhere, and a backlog always holds audio
 * (50 records/s), so the Android socket buffer took 57-80 KB (3.2-4.4 s on a 144 kbps link) that the
 * queue policies never saw. The queue is strictly ordered, so only the next record can be exempt.
 */
class RelayChildPacingTest {
    private var nowNs = 1_000_000_000L
    private val stream = UUID.fromString("5f1c4a66-3a5f-4ef5-a5fb-6b0b0d0b6c11")
    private val source = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 7 })

    private fun envelope(sequence: Long, flags: Int = 0) =
        MeshEnvelope(stream, source, sequence, 4, flags, ByteArray(8))

    private fun video(sequence: Long) = envelope(sequence)
    private fun audio(sequence: Long) = envelope(sequence, MeshEnvelope.FLAG_AUDIO)

    /** Measured 800 kbps: paced at 120 000 B/s, one 12 000-byte frame is 100 ms of link time. */
    private fun pacerWithFrameCharged(): LinkPacer {
        val pacer = LinkPacer({ nowNs })
        pacer.setMeasuredLinkKbps(800)
        pacer.onSent(12_000)
        return pacer
    }

    @Test
    fun onlyTheHeadOfTheStrictlyOrderedQueueDecidesWhetherAudioIsNext() {
        val queue = RelayChildQueue()
        assertFalse(queue.headIsAudio())
        queue.offer(video(1))
        queue.offer(audio(2))
        queue.offer(audio(3))
        // The wider test the writer used: audio is queued somewhere.
        assertTrue(queue.hasQueuedAudio())
        // The child accepts only increasing sequences, so the video ahead of it is written first.
        assertFalse(queue.headIsAudio())
        queue.take()
        assertTrue(queue.headIsAudio())
        queue.take()
        queue.take()
        assertFalse(queue.headIsAudio())
    }

    @Test
    fun aVideoRecordAheadOfQueuedAudioWaitsForTheLinkPacer() {
        val queue = RelayChildQueue()
        queue.offer(video(1))
        queue.offer(audio(2))
        val pacer = pacerWithFrameCharged()
        var sleeps = 0
        val credit = awaitLinkCredit(pacer, { queue.headIsAudio() }, { true }) { sliceNs ->
            sleeps++
            nowNs += sliceNs
        }
        // 100 ms charged, 50 ms burst allowed ahead: it waits the other 50 ms.
        assertEquals(50_000_000L, credit.sleptNs)
        assertEquals(0L, credit.exemptedWaitNs)
        assertEquals(10, sleeps)
        assertEquals(0L, pacer.waitNs())
    }

    @Test
    fun theEarlierAnyAudioQueuedTestSkippedThePacerForTheWholeBacklog() {
        val queue = RelayChildQueue()
        queue.offer(video(1))
        queue.offer(audio(2))
        val pacer = pacerWithFrameCharged()
        val credit = awaitLinkCredit(pacer, { queue.hasQueuedAudio() }, { true }) { sliceNs -> nowNs += sliceNs }
        // The control arm (test_relay_pace_head=false): the video frame is written at once.
        assertEquals(0L, credit.sleptNs)
        assertEquals(50_000_000L, credit.exemptedWaitNs)
    }

    @Test
    fun audioAtTheHeadIsNeverHeldForCredit() {
        val queue = RelayChildQueue()
        queue.offer(audio(1))
        queue.offer(video(2))
        val pacer = pacerWithFrameCharged()
        val credit = awaitLinkCredit(pacer, { queue.headIsAudio() }, { true }) { sliceNs -> nowNs += sliceNs }
        assertEquals(0L, credit.sleptNs)
        assertEquals(50_000_000L, credit.exemptedWaitNs)
    }

    @Test
    fun audioArrivingAtTheHeadOfAnEmptyQueueEndsTheWait() {
        val queue = RelayChildQueue()
        val pacer = pacerWithFrameCharged()
        var sleeps = 0
        val credit = awaitLinkCredit(pacer, { queue.headIsAudio() }, { true }) { sliceNs ->
            sleeps++
            nowNs += sliceNs
            if (sleeps == 3) queue.offer(audio(1))
        }
        assertEquals(3, sleeps)
        assertEquals(15_000_000L, credit.sleptNs)
        assertEquals(35_000_000L, credit.exemptedWaitNs)
    }

    @Test
    fun theWaitStopsWhenTheWriterIsStopped() {
        val pacer = pacerWithFrameCharged()
        var runs = 2
        val credit = awaitLinkCredit(pacer, { false }, { runs-- > 0 }) { sliceNs -> nowNs += sliceNs }
        assertEquals(10_000_000L, credit.sleptNs)
    }

    @Test
    fun anUnpacedLinkNeverWaits() {
        val credit = awaitLinkCredit(LinkPacer({ nowNs }), { false }, { true }) { error("must not sleep") }
        assertEquals(0L, credit.sleptNs)
        assertEquals(0L, credit.exemptedWaitNs)
    }

    // Experimental lead cap (test_relay_pace_lead_cap).

    /** Writes [frameBytes] records back to back, each after the pacer's wait; returns the bytes per second over [seconds]. */
    private fun sustainedBytesPerSecond(pacer: LinkPacer, frameBytes: Int, seconds: Int): Double {
        val startNs = nowNs
        var bytes = 0L
        while (nowNs - startNs < seconds * 1_000_000_000L) {
            nowNs += pacer.waitNs()
            pacer.onSent(frameBytes)
            bytes += frameBytes
        }
        return bytes * 1e9 / (nowNs - startNs)
    }

    @Test
    fun withoutACapAStandingBacklogRunsTwentyPercentAheadOfTheMeasuredLink() {
        val pacer = LinkPacer({ nowNs })
        pacer.setMeasuredLinkKbps(800) // measured 100 000 B/s, paced at 120 000 B/s
        val rate = sustainedBytesPerSecond(pacer, 3_000, 20)
        assertEquals(120_000.0, rate, 2_500.0)
        // 20 s at +20000 B/s: 400 KB the link had not drained yet, i.e. 4 s of link time in its buffers.
        assertEquals(4_000.0, pacer.modelLeadMs().toDouble(), 150.0)
    }

    @Test
    fun aLeadCapKeepsTheUndrainedBytesBoundedWhileTheLinkStaysBusy() {
        val pacer = LinkPacer({ nowNs })
        pacer.setMeasuredLinkKbps(800)
        pacer.setLeadCapNs(RELAY_PACE_LEAD_CAP_NS)
        val rate = sustainedBytesPerSecond(pacer, 3_000, 20)
        // After the cap binds (about 1.25 s of surplus) the writes follow the measured rate.
        assertEquals(100_000.0, rate, 8_000.0)
        // Never more than the cap plus the record that crossed it (3 000 B = 30 ms of link time).
        assertTrue("lead ${pacer.modelLeadMs()} ms", pacer.modelLeadMs() <= 250L + 30L)
        // And not idle: some data is always ahead of the link.
        assertTrue("lead ${pacer.modelLeadMs()} ms", pacer.modelLeadMs() >= 100L)
    }

    @Test
    fun aLeadCapOfZeroIsTheUncappedPacer() {
        val pacer = LinkPacer({ nowNs })
        pacer.setMeasuredLinkKbps(800)
        pacer.setLeadCapNs(RELAY_PACE_LEAD_CAP_NS)
        pacer.setLeadCapNs(0L)
        assertEquals(0L, pacer.leadCapMs())
        assertEquals(120_000.0, sustainedBytesPerSecond(pacer, 3_000, 20), 2_500.0)
    }

    @Test
    fun theCapNeverHoldsAnExemptRecordBecauseTheCallerSkipsTheWaitForAudio() {
        val queue = RelayChildQueue()
        queue.offer(audio(1))
        val pacer = LinkPacer({ nowNs })
        pacer.setMeasuredLinkKbps(800)
        pacer.setLeadCapNs(RELAY_PACE_LEAD_CAP_NS)
        pacer.onSent(100_000) // 1 s of link time ahead
        val credit = awaitLinkCredit(pacer, { queue.headIsAudio() }, { true }) { sliceNs -> nowNs += sliceNs }
        assertEquals(0L, credit.sleptNs)
        assertTrue(credit.exemptedWaitNs > 0L)
    }

    @Test
    fun theFirstReportScalesTheModelClockLikeThePacedOne() {
        val pacer = LinkPacer({ nowNs })
        pacer.seedKbps(400) // paced at 60 000 B/s, base 50 000 B/s
        pacer.onSent(6_000)
        assertEquals(120L, pacer.modelLeadMs())
        pacer.setMeasuredLinkKbps(1_200) // base 150 000 B/s: the remaining 120 ms drains in a third
        assertEquals(40.0, pacer.modelLeadMs().toDouble(), 1.0)
    }

    @Test
    fun theCapAppliesOnlyWhileTheExperimentIsOnAndTheChildReportsLateMedia() {
        assertEquals(RELAY_PACE_LEAD_CAP_NS, relayLeadCapNs(enabled = true, childLate = true))
        assertEquals(0L, relayLeadCapNs(enabled = true, childLate = false))
        assertEquals(0L, relayLeadCapNs(enabled = false, childLate = true))
        assertEquals(0L, relayLeadCapNs(enabled = false, childLate = false))
        // 250 ms stays below the 400 ms the relay-child media-age budget sheds at.
        assertTrue(RELAY_PACE_LEAD_CAP_NS / 1_000_000L < BoundedMediaAge.MIN_STREAM_QUEUE_MS)
    }

    @Test
    fun theTwoSwitchesDefaultToTheFixOnAndTheExperimentOff() {
        assertTrue(DebugTunables.relayPaceHeadOnly)
        assertFalse(DebugTunables.relayPaceLeadCap)
    }
}
