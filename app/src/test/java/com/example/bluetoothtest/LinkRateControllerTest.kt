package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkRateControllerTest {
    private val second = 1_000_000_000L

    private fun sample(
        offered: Long,
        sent: Long,
        ageMs: Int,
        grace: Boolean = false,
        measuredKbps: Int = 0,
        childLate: Boolean = false,
    ) = LinkRateSample(second, offered, sent, ageMs, grace, measuredKbps, childLate)

    @Test
    fun aShortDipWithAnEmptyQueueNeverDownshifts() {
        // The retired controller cut 1100 -> 902 kbps on a short FPS sample with empty queues.
        val controller = LinkRateController(1_100_000)
        repeat(10) { assertNull(controller.onSample("a", sample(110_000, 90_000, ageMs = 0), it * second)) }
        assertEquals(1_100_000, controller.effectiveVideoBps)
    }

    @Test
    fun aGrowingBacklogLowersOnceToWhatTheLinkCarriedThenHoldsWhileItDrains() {
        // M31 VBR offered 906 kbps; the GATT link carried ~650 kbps with a growing backlog.
        val controller = LinkRateController(600_000)
        // 650 kbps of media carried * 0.85 - 32 kbps audio = 520.5 kbps of video.
        assertEquals(520_500, controller.onSample("a", sample(113_000, 81_250, ageMs = 600), 0))
        // Draining (backlog shrinking, still old): hold, no cascade (300 -> 120 kbps in trend-3641df77).
        assertNull(controller.onSample("a", sample(60_000, 80_000, ageMs = 900), second))
        assertNull(controller.onSample("a", sample(60_000, 80_000, ageMs = 500), 2 * second))
        // Clean windows step back up by 10% of the request.
        assertNull(controller.onSample("a", sample(60_000, 60_000, ageMs = 0), 3 * second))
        assertNull(controller.onSample("a", sample(60_000, 60_000, ageMs = 0), 4 * second))
        assertEquals(580_500, controller.onSample("a", sample(60_000, 60_000, ageMs = 0), 5 * second))
    }

    @Test
    fun aDecreaseWaitsForItsEffectBeforeAnother() {
        val controller = LinkRateController(600_000)
        assertEquals(520_500, controller.onSample("a", sample(113_000, 81_250, ageMs = 600), 0))
        assertNull(controller.onSample("a", sample(113_000, 60_000, ageMs = 900), second))
        // 3 s later, still growing: a second decrease to what the link now carries.
        assertEquals(376_000, controller.onSample("a", sample(113_000, 60_000, ageMs = 1_300), 3 * second))
    }

    @Test
    fun aLargeFrameDrainIsNotCongestion() {
        val controller = LinkRateController(600_000)
        repeat(4) { assertNull(controller.onSample("a", sample(120_000, 80_000, ageMs = 900 + it * 200, grace = true), it * second)) }
        assertEquals(600_000, controller.effectiveVideoBps)
    }

    @Test
    fun lateArrivalsAtTheChildLowerTheRateEvenWhenTheStackHidesTheBacklog() {
        // M31 GATT: the sender queue read 0 ms while frames reached the ASUS up to 2.2 s late.
        val controller = LinkRateController(600_000)
        // min(carried 640, measured 500) * 0.85 - 32 = 393 kbps.
        assertEquals(393_000, controller.onSample("a", sample(80_000, 80_000, ageMs = 0, measuredKbps = 500, childLate = true), 0))
    }

    @Test
    fun everyViewerReceivesEveryFrameSoTheWeakestLinkSetsTheRate() {
        val controller = LinkRateController(600_000)
        controller.onSample("strong", sample(80_000, 80_000, ageMs = 0), 0)
        // 320 kbps carried * 0.85 - 32 = 240 kbps, the 40% floor.
        assertEquals(240_000, controller.onSample("weak", sample(80_000, 40_000, ageMs = 800), 0))
    }

    private class MapMemory : SustainedRateMemory {
        val values = mutableMapOf<String, Int>()
        override fun startBps(peer: String): Int? = values[peer]
        override fun record(peer: String, bps: Int) {
            values[peer] = bps
        }
    }

    @Test
    fun aLinkStartsAtWhatThisPeerSustainedLastTimeAndClimbsFromThere() {
        // callrate-454cf485: CoC 1200 kbps on a ~600 kbps ASUS->M31 link overloaded before the first cut.
        val memory = MapMemory().apply { values["m31"] = 480_000 }
        val controller = LinkRateController(1_200_000, memory = memory)
        assertEquals(480_000, controller.onSample("a", sample(60_000, 60_000, ageMs = 0).copy(peer = "m31"), 0))
        assertNull(controller.onSample("a", sample(60_000, 60_000, ageMs = 0).copy(peer = "m31"), second))
        // Three clean windows: remembered, then 10% of the request higher.
        assertEquals(600_000, controller.onSample("a", sample(60_000, 60_000, ageMs = 0).copy(peer = "m31"), 2 * second))
        assertEquals(480_000, memory.values["m31"])
    }

    @Test
    fun aCongestionCutIsNotRememberedAndAnUnknownPeerStartsAtTheRequest() {
        // A cut target was never sustained; remembering it let one false cut start the next session
        // low (regress-ff0e24e1 S24->A15s 600 -> 494). Only clean windows teach the memory.
        val memory = MapMemory()
        val controller = LinkRateController(600_000, memory = memory)
        assertEquals(520_500, controller.onSample("a", sample(113_000, 81_250, ageMs = 600).copy(peer = "asus"), 0))
        assertNull(memory.values["asus"])
        repeat(3) { controller.onSample("a", sample(60_000, 60_000, ageMs = 0).copy(peer = "asus"), (4 + it) * second) }
        assertEquals(520_500, memory.values["asus"])
        // A remembered rate below the floor still starts at the floor (40% of the request).
        memory.values["s24"] = 50_000
        assertEquals(240_000, LinkRateController(600_000, memory = memory)
            .onSample("b", sample(36_000, 36_000, ageMs = 0).copy(peer = "s24"), 0))
    }

    @Test
    fun theControlArmRemembersEveryCutAsBefore() {
        // test_delivery_reports=false restores the pre-2026-10-09 behaviour exactly, streams included.
        val memory = MapMemory()
        val controller = LinkRateController(600_000, memory = memory, rememberCuts = true)
        assertEquals(520_500, controller.onSample("a", sample(113_000, 81_250, ageMs = 600).copy(peer = "asus"), 0))
        assertEquals(520_500, memory.values["asus"])
    }

    @Test
    fun aCallBelowItsBitrateFloorLowersItsFrameRateNotItsPerFrameBits() {
        // 600 kbps call at 15 FPS: floor 240 kbps = 16 kbit per frame. A ~150 kbps link carries 7 FPS
        // at those bits instead of 15 FPS starved of bits; never below 5 FPS.
        val controller = LinkRateController(600_000, frameRateLadder = FrameRateLadder(15, 5))
        // 220 kbps carried * 0.85 - 32 = 155 kbps.
        val bps = controller.onSample("call", sample(60_000, 27_500, ageMs = 900), 0)
        assertEquals(155_000, bps)
        assertEquals(9, controller.frameRateFor(155_000))
        assertEquals(15, controller.frameRateFor(240_000))
        assertEquals(5, controller.frameRateFor(10_000))
        // Without a ladder (streams) the frame rate is never limited and the floor holds.
        val stream = LinkRateController(600_000)
        assertEquals(240_000, stream.onSample("s", sample(60_000, 27_500, ageMs = 900), 0))
        assertEquals(Int.MAX_VALUE, stream.frameRateFor(155_000))
    }

    @Test
    fun aCallKeepsItsFrameRateDownToWhatItsEncodedGeometryNeeds() {
        // 1000 kbps request on a 426x240/15 call: geometry floor 153 kbps, not 0.4 x request.
        val floor = FrameRateLadder.floorBpsFor(426, 240, 15)
        assertEquals(153_360, floor)
        val controller = LinkRateController(1_000_000, frameRateLadder = FrameRateLadder(15, 5, floor))
        assertEquals(15, controller.frameRateFor(200_000))
        assertEquals(15, controller.frameRateFor(153_360))
        assertEquals(7, controller.frameRateFor(76_680))
        // A geometry needing more than 0.4 x request keeps the request-scaled floor.
        val big = LinkRateController(300_000, frameRateLadder = FrameRateLadder(15, 5, FrameRateLadder.floorBpsFor(1280, 720, 15)))
        assertEquals(7, big.frameRateFor(60_000))
    }

    @Test
    fun aRateHeldOnlyByTheRequestNeverLowersTheRememberedRate() {
        val memory = MapMemory().apply { values["m31"] = 900_000 }
        val controller = LinkRateController(300_000, memory = memory)
        repeat(3) { controller.onSample("a", sample(36_000, 36_000, ageMs = 0).copy(peer = "m31"), it * second) }
        assertEquals(900_000, memory.values["m31"])
        // An unknown peer does learn the request it carried cleanly.
        repeat(3) { controller.onSample("b", sample(36_000, 36_000, ageMs = 0).copy(peer = "asus"), it * second) }
        assertEquals(300_000, memory.values["asus"])
    }
}

class DeliveryReferencedRateControlTest {
    private val second = 1_000_000_000L

    /** ASUS->S24 900 kbps call (regress-ff0e24e1): 16 kbps audio, trains ~784 kbps, S24 received ~640. */
    private fun call(probing: Boolean = false) = LinkRateController(900_000, audioBps = 16_000, deliveryProbing = probing)

    private fun sample(
        backlogMs: Int = 0,
        late: Boolean = false,
        deliveredKbps: Int = 640,
        queueMs: Int = 0,
        sentBytes: Long = 98_000,
    ) = LinkRateSample(
        second, offeredBytes = 93_000, sentBytes = sentBytes, backlogAgeMs = backlogMs, largeFrameGrace = false,
        measuredLinkKbps = 784, childLate = late, deliveredKbps = deliveredKbps, peerQueueMs = queueMs,
    )

    @Test
    fun aCutLandsAtWhatThePeerReceivedNotAtTheArrivalTrainsOrWhatTheStackAccepted() {
        // Old reference: 0.85 x min(carried 784, trains 784) - 16 = 650 kbps, still above the link.
        val old = LinkRateController(900_000, audioBps = 16_000)
        assertEquals(650_400, old.onSample("call", sample(late = true, deliveredKbps = 0, queueMs = -1), 0))
        // 0.85 x 640 kbps received - 16 kbps audio.
        assertEquals(528_000, call().onSample("call", sample(late = true, queueMs = 400), 0))
    }

    @Test
    fun latenessLeftOverFromADrainedQueueDoesNotCutAgain() {
        val controller = call()
        assertEquals(528_000, controller.onSample("call", sample(late = true, queueMs = 600), 0))
        // The measured queue drains (600 -> 450 -> 250 ms): still late, but no cascade.
        assertNull(controller.onSample("call", sample(late = true, queueMs = 450), 3 * second))
        assertNull(controller.onSample("call", sample(late = true, queueMs = 250), 4 * second))
        // Drained, the peer still holds lateness for ~3.5 s: the old rule cut 461 -> 316 kbps here.
        assertNull(controller.onSample("call", sample(late = true, deliveredKbps = 540, queueMs = 40), 7 * second))
        assertEquals(528_000, controller.effectiveVideoBps)
    }

    @Test
    fun aQueueThatStopsDrainingWhileThePeerIsLateIsOveruseAgain() {
        val controller = call()
        assertEquals(528_000, controller.onSample("call", sample(late = true, queueMs = 600), 0))
        assertNull(controller.onSample("call", sample(late = true, queueMs = 400), second))
        // The link got worse: the queue holds while the peer received only 480 kbps.
        assertEquals(392_000, controller.onSample("call", sample(late = true, deliveredKbps = 480, queueMs = 420), 4 * second))
    }

    @Test
    fun aMeasuredQueueIsNotCleanSoTheRateDoesNotClimbIntoIt() {
        val controller = call()
        assertEquals(528_000, controller.onSample("call", sample(late = true, queueMs = 600), 0))
        // The app queue is empty and the peer not late, but the stack still holds 200 ms.
        repeat(4) { assertNull(controller.onSample("call", sample(queueMs = 200), (1 + it) * second)) }
        // Once it is gone, three clean windows climb as before (10% of the request).
        assertNull(controller.onSample("call", sample(queueMs = 60), 5 * second))
        assertNull(controller.onSample("call", sample(queueMs = 60), 6 * second))
        assertEquals(618_000, controller.onSample("call", sample(queueMs = 60), 7 * second))
    }

    @Test
    fun aGrowingMeasuredQueueIsOveruseEvenWhenTheAppQueueIsEmpty() {
        // layers-diag-c4db9240: the sender queue read 0 ms while frames arrived 2.2 s late.
        val controller = call()
        assertNull(controller.onSample("call", sample(queueMs = 380), 0))
        assertEquals(528_000, controller.onSample("call", sample(queueMs = 460), second))
    }

    @Test
    fun probingHoldsBelowTheLastOveruseAndUndoesAFailedProbe() {
        val controller = call(probing = true)
        assertEquals(528_000, controller.onSample("call", sample(late = true, queueMs = 600), 0))
        var t = 1L
        fun clean(times: Int): Int? {
            var changed: Int? = null
            repeat(times) { controller.onSample("call", sample(queueMs = 20), t++ * second)?.let { changed = it } }
            return changed
        }
        // Climb to 0.95 x 640 - 16 = 592 kbps, not 10% of the request past the link.
        assertEquals(592_000, clean(3))
        assertNull(clean(9))
        // Ten clean windows at the cap: probe to 0.95 x 704 - 16.
        assertEquals(652_800, clean(1))
        // The probe builds a measured queue that is not draining: back to 592 at once, no 15% cut.
        assertNull(controller.onSample("call", sample(queueMs = 120), t++ * second))
        assertEquals(592_000, controller.onSample("call", sample(queueMs = 190), t++ * second))
        // The next probe waits twice as long (20 clean windows).
        assertNull(clean(19))
        assertEquals(652_800, clean(1))
    }

    @Test
    fun aProbeThatHoldsCleanlyReleasesTheCeilingSoAnImprovedLinkIsFound() {
        val controller = call(probing = true)
        assertEquals(528_000, controller.onSample("call", sample(late = true, queueMs = 600), 0))
        var t = 1L
        fun clean(times: Int): Int? {
            var changed: Int? = null
            repeat(times) { controller.onSample("call", sample(queueMs = 20), t++ * second)?.let { changed = it } }
            return changed
        }
        assertEquals(592_000, clean(3))
        assertEquals(652_800, clean(10))
        // Six clean windows above the last overuse point: the old ceiling is stale; climb normally.
        assertEquals(742_800, clean(6))
        assertEquals(832_800, clean(3))
    }

    @Test
    fun cameraOffOrAudioOnlyWindowsNeitherClimbNorTeachTheLearnedRate() {
        // Review 2026-10-09: audio-only windows climbed to and remembered the full request for 24 h.
        val memory = mutableMapOf<String, Int>()
        val controller = LinkRateController(
            900_000, audioBps = 16_000,
            memory = object : SustainedRateMemory {
                override fun startBps(peer: String): Int? = memory[peer] ?: 400_000
                override fun record(peer: String, bps: Int) {
                    memory[peer] = bps
                }
            },
        )
        fun window(offeredBytes: Long) = LinkRateSample(second, offeredBytes, offeredBytes, 0, false, peer = "asus")
        assertEquals(400_000, controller.seed("call", "asus", 0))
        // Camera off: 16 kbps of audio per window, clean, for ten windows.
        repeat(10) { assertNull(controller.onSample("call", window(2_000), (it + 1) * second)) }
        assertEquals(400_000, controller.effectiveVideoBps)
        assertNull(memory["asus"])
        // Video back near its target: three loaded clean windows learn it and climb.
        assertNull(controller.onSample("call", window(50_000), 11 * second))
        assertNull(controller.onSample("call", window(50_000), 12 * second))
        assertEquals(490_000, controller.onSample("call", window(50_000), 13 * second))
        assertEquals(400_000, memory["asus"])
    }

    @Test
    fun anOverflowingQueueReadingIsTreatedAsAQueueNeverAsClean() {
        val controller = call()
        assertEquals(528_000, controller.onSample("call", sample(backlogMs = 1_000, queueMs = Int.MAX_VALUE), 0))
        repeat(3) { assertNull(controller.onSample("call", sample(queueMs = Int.MAX_VALUE), (1 + it) * second)) }
        assertEquals(528_000, controller.effectiveVideoBps)
    }

    @Test
    fun withoutDeliveryReportsTheRulesAreUnchanged() {
        // An older peer (no version 3): lateness alone cuts, to the old reference.
        val controller = call(probing = true)
        assertEquals(650_400, controller.onSample("call", sample(late = true, deliveredKbps = 0, queueMs = -1), 0))
        assertEquals(494_000, controller.onSample("call", sample(late = true, deliveredKbps = 0, queueMs = -1, sentBytes = 75_000), 3 * second))
    }
}

class PeerDeliveryEstimateTest {
    private val ms = 1_000_000L

    /** Writes 8 000 bytes every 100 ms (640 kbps) from [fromMs] until [toMs] on the sender's clock. */
    private fun PeerDeliveryEstimate.writeEvery100Ms(fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t < toMs) {
            onSent(8_000, t * ms)
            t += 100
        }
    }

    /** The peer's report: every write up to [lastWriteMs] arrived, the last one [oneWayMs] later (peer clock = sender - 5 s). */
    private fun report(lastWriteMs: Long, oneWayMs: Long) =
        LinkDelivery(((lastWriteMs - 5_000) / 100 + 1) * 8_000, lastWriteMs + oneWayMs - 5_000)

    @Test
    fun thePeersArrivalClockGivesTheDeliveredRateAndASteadyTripReadsNoQueue() {
        val estimate = PeerDeliveryEstimate()
        assertEquals(0, estimate.deliveredKbps())
        assertEquals(-1, estimate.queueMs())
        estimate.writeEvery100Ms(5_000, 8_000)
        for (lastWrite in listOf(5_900L, 6_900L, 7_900L)) estimate.onReport(report(lastWrite, oneWayMs = 60))
        assertEquals(640, estimate.deliveredKbps())
        assertEquals(0, estimate.queueMs())
    }

    @Test
    fun howLongTheReportTakesToComeBackIsNotQueue() {
        // Review 2026-10-09: "written at report arrival minus received" read a 600 ms return delay as
        // 600 ms of forward queue. Here the sender has written 600 ms past the reported byte when the
        // report arrives; the byte's own trip was a steady 60 ms.
        val estimate = PeerDeliveryEstimate()
        estimate.writeEvery100Ms(5_000, 6_600)
        estimate.onReport(report(5_900, oneWayMs = 60))
        estimate.writeEvery100Ms(6_600, 7_600)
        estimate.onReport(report(6_900, oneWayMs = 60))
        assertEquals(0, estimate.queueMs())
    }

    @Test
    fun aGrowingTripAboveItsMinimumIsTheQueueInFrontOfTheMedia() {
        val estimate = PeerDeliveryEstimate()
        estimate.writeEvery100Ms(5_000, 9_000)
        estimate.onReport(report(5_900, oneWayMs = 60))
        estimate.onReport(report(6_900, oneWayMs = 260))
        assertEquals(200, estimate.queueMs())
        estimate.onReport(report(7_900, oneWayMs = 510))
        assertEquals(450, estimate.queueMs())
        // Held between reports: a slow report path does not drop back to the old rules.
        assertEquals(450, estimate.queueMs())
        assertEquals(true, estimate.deliveredKbps() > 0)
    }

    @Test
    fun countersThatGoBackwardsAreAPeerResetNotA32BitWrap() {
        val estimate = PeerDeliveryEstimate()
        estimate.writeEvery100Ms(5_000, 8_000)
        estimate.onReport(report(5_900, oneWayMs = 60))
        estimate.onReport(report(6_900, oneWayMs = 60))
        assertEquals(0, estimate.queueMs())
        // The peer's link started again (counters near zero): not "4 GB arrived".
        assertEquals(false, estimate.onReport(LinkDelivery(2_000, 300)))
        assertEquals(1, estimate.counterResets)
        assertEquals(-1, estimate.queueMs())
        // Counters that cannot be checked against this link's writes are not trusted at all: no
        // delivery rate either (rate control falls back to the older references).
        estimate.onReport(LinkDelivery(82_000, 1_300))
        estimate.onReport(LinkDelivery(162_000, 2_300))
        assertEquals(0, estimate.deliveredKbps())
        assertEquals(-1, estimate.queueMs())
    }

    @Test
    fun sparseReportsUseTheirOwnIntervalNotAnExpiredSpan() {
        // Review 2026-10-09: 800 kbps established, then reports 3 s apart showing 100 kbps kept 800.
        val estimate = PeerDeliveryEstimate()
        estimate.onSent(1_000_000, 1_000 * ms)
        estimate.onReport(LinkDelivery(0, 0))
        estimate.onReport(LinkDelivery(100_000, 1_000))
        estimate.onReport(LinkDelivery(200_000, 2_000))
        assertEquals(800, estimate.deliveredKbps())
        estimate.onReport(LinkDelivery(237_500, 5_000))
        assertEquals(100, estimate.deliveredKbps())
        estimate.onReport(LinkDelivery(275_000, 8_000))
        assertEquals(100, estimate.deliveredKbps())
    }

    @Test
    fun genuine32BitWrapsOfBytesAndClockAreFollowed() {
        val estimate = PeerDeliveryEstimate()
        // 4.3 GB written: the byte counter wraps; the peer's clock wraps too.
        estimate.onSent(2_000_000_000, 1_000 * ms)
        estimate.onSent(2_000_000_000, 2_000 * ms)
        estimate.onSent(294_967_296, 3_000 * ms)
        estimate.onSent(80_000, 4_000 * ms)
        estimate.onSent(80_000, 5_000 * ms)
        val wrap = 1L shl 32
        estimate.onReport(LinkDelivery((4_294_967_296L + 80_000) % wrap, wrap - 940))
        assertEquals(true, estimate.onReport(LinkDelivery((4_294_967_296L + 160_000) % wrap, 60)))
        assertEquals(0, estimate.counterResets)
        assertEquals(640, estimate.deliveredKbps())
        assertEquals(0, estimate.queueMs())
    }

    @Test
    fun queueReadingsAreClampedAndAPeerFarAheadStopsTheMeasurement() {
        val estimate = PeerDeliveryEstimate()
        estimate.writeEvery100Ms(5_000, 6_000)
        estimate.onReport(report(5_900, oneWayMs = 60))
        // A write stamped ten hours early (a broken clock): the reading is the clamp, never a
        // negative, clean-looking value.
        estimate.onSent(8_000, (5_900L - 36_000_000L) * ms)
        estimate.onReport(LinkDelivery(88_000, 1_060))
        assertEquals(PeerDeliveryEstimate.MAX_QUEUE_MS, estimate.queueMs())
        // More than 1 MB received beyond what this link wrote is impossible (a broken or hostile
        // peer): neither its queue nor its claimed goodput steers the rate any more.
        estimate.onReport(LinkDelivery(3_000_000, 2_060))
        assertEquals(-1, estimate.queueMs())
        assertEquals(0, estimate.deliveredKbps())
    }
}

class CallDeliveryReportsTest {
    private val snapshot = { LinkDelivery(1_000, 2_000) }

    @Test
    fun aPeerThatOnlySendsVersion2NeverReceivesVersion3() {
        val reports = CallDeliveryReports(enabled = true)
        assertEquals(true, reports.sendsHello)
        repeat(3) { assertEquals(false, reports.onPeerHeartbeat(LinkCapacityReport(600, linkLate = false, addedDelayMs = 40))) }
        assertNull(reports.deliveryFor(callKnown = true, relayHops = 0, snapshot))
    }

    @Test
    fun theHelloOrAVersion3ReportOpensVersion3OnADirectCallOnly() {
        val reports = CallDeliveryReports(enabled = true)
        assertEquals(true, reports.onPeerHeartbeat(null))
        assertEquals(false, reports.onPeerHeartbeat(null))
        // Not before the call (and its relay hops) is known, and never through a call relay.
        assertNull(reports.deliveryFor(callKnown = false, relayHops = 0, snapshot))
        assertNull(reports.deliveryFor(callKnown = true, relayHops = 1, snapshot))
        assertEquals(LinkDelivery(1_000, 2_000), reports.deliveryFor(callKnown = true, relayHops = 0, snapshot))
        val viaReport = CallDeliveryReports(enabled = true)
        assertEquals(true, viaReport.onPeerHeartbeat(LinkCapacityReport(600, false, 0, LinkDelivery(5, 6))))
    }

    @Test
    fun theControlArmSendsNoHelloNoVersion3AndIgnoresTheDeliveryItReceives() {
        val reports = CallDeliveryReports(enabled = false)
        assertEquals(false, reports.sendsHello)
        assertEquals(false, reports.onPeerHeartbeat(null))
        assertNull(reports.deliveryFor(callKnown = true, relayHops = 0, snapshot))
        assertNull(reports.accepted(LinkCapacityReport(600, false, 0, LinkDelivery(5, 6))))
    }
}

class PeerLatenessTrendTest {
    @Test
    fun oneAbsorbedBurstIsNotLatenessButAKeptRiseIs() {
        val trend = PeerLatenessTrend()
        // A scene cut: the peer adds 300 ms once, then holds.
        org.junit.Assert.assertFalse(trend.onReport(LinkCapacityReport(600, linkLate = false, addedDelayMs = 300)))
        org.junit.Assert.assertFalse(trend.onReport(LinkCapacityReport(600, linkLate = false, addedDelayMs = 300)))
        // A short link: the delay keeps rising report after report.
        org.junit.Assert.assertFalse(trend.onReport(LinkCapacityReport(600, linkLate = false, addedDelayMs = 450)))
        org.junit.Assert.assertTrue(trend.onReport(LinkCapacityReport(600, linkLate = false, addedDelayMs = 600)))
        // Pinned at the maximum counts at once.
        org.junit.Assert.assertTrue(PeerLatenessTrend().onReport(LinkCapacityReport(0, linkLate = true)))
    }

    @Test
    fun twoBurstsTwentySecondsApartAreNotASustainedRise() {
        val trend = PeerLatenessTrend()
        org.junit.Assert.assertFalse(trend.onReport(LinkCapacityReport(600, linkLate = false, addedDelayMs = 650), 3_400_000_000L))
        // The held delay sends nothing until the next scene cut.
        org.junit.Assert.assertFalse(trend.onReport(LinkCapacityReport(600, linkLate = false, addedDelayMs = 900), 23_600_000_000L))
        // Rising report after report within a second still counts.
        org.junit.Assert.assertTrue(trend.onReport(LinkCapacityReport(600, linkLate = false, addedDelayMs = 1_100), 24_600_000_000L))
    }
}
