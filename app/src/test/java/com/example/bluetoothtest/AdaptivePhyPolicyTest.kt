package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptivePhyPolicyTest {
    private val oneEvent15ms = AdaptivePhyPolicy.oneEventKbps(15.0)

    /** Feeds [seconds] of 100 ms writes of [kbps] payload, blocked [blockedFraction] of the time. */
    private fun AdaptivePhyPolicy.feed(
        startMs: Long, seconds: Int, kbps: Int, blockedFraction: Double, onTwoM: Boolean = true,
    ): List<AdaptivePhyPolicy.Verdict> = (1..seconds * 10).map { i ->
        onWrite(startMs + i * 100L, kbps * 100 / 8, (100 * blockedFraction).toLong(), onTwoM, oneEvent15ms)
    }.filter { it != AdaptivePhyPolicy.Verdict.NONE }

    @Test
    fun oneEventBoundAt15Ms() = assertEquals(131, oneEvent15ms)

    @Test
    fun backloggedOnePduPerEventLinkMovesTo1MOnceAfterThreeWindows() {
        // ASUS -> A15s on 2M: 60 kbps CoC while the writer is blocked (slowlink-asussnoop2-b9029e48).
        val policy = AdaptivePhyPolicy()
        val verdicts = policy.feed(0, 9, kbps = 60, blockedFraction = 0.9)
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), verdicts)
        assertEquals(emptyList<AdaptivePhyPolicy.Verdict>(), policy.feed(9_000, 20, kbps = 60, blockedFraction = 0.9))
    }

    @Test
    fun aFanOutLinkSharingTheRadioIsNeverMoved() {
        // mesh4-041da09f 0002: an ASUS source with two children measured 134 kbps per link because
        // the two links shared the radio, not because either carried one PDU per event.
        val policy = AdaptivePhyPolicy()
        val verdicts = (1..200).map { i ->
            policy.onWrite(i * 100L, 134 * 100 / 8, 66, onTwoM = true, oneEventKbps = oneEvent15ms, sharedAirtime = true)
        }.filter { it != AdaptivePhyPolicy.Verdict.NONE }
        assertEquals(emptyList<AdaptivePhyPolicy.Verdict>(), verdicts)
        // Once it is the only link again, the same rate is judged normally.
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), policy.feed(20_000, 9, kbps = 60, blockedFraction = 0.9))
    }

    @Test
    fun aStalledLinkFarBelowOnePduPerEventIsNotAOnePduMove() {
        // mesh4-7a6c401c 0001: ASUS -> M31 carried 8 kbps, blocked the whole time. The policy reports it as STALLED and
        // AdaptivePhyLink decides (a relay's downstream link keeps 2M; a link the peer is still heard on moves).
        val policy = AdaptivePhyPolicy()
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.STALLED), policy.feed(0, 9, kbps = 8, blockedFraction = 1.0))
    }

    @Test
    fun theA15sSourceThatWroteSevenToTenKbpsForNinetySecondsIsStalledOnce() {
        // b10-97a95ad4 1-defaults/d2: TX 7 to 13 kbps with the writer blocked 99 percent, intervalMs 15, oneEventKbps 131.
        val policy = AdaptivePhyPolicy()
        val verdicts = policy.feed(0, 9, kbps = 7, blockedFraction = 0.99) + policy.feed(9_000, 80, kbps = 9, blockedFraction = 0.99)
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.STALLED), verdicts)
    }

    @Test
    fun justAboveAQuarterOfOnePduIsStillTheOnePduBand() {
        val policy = AdaptivePhyPolicy()
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), policy.feed(0, 9, kbps = 40, blockedFraction = 0.9))
    }

    @Test
    fun backloggedHealthyLinkKeeps2M() {
        // M31 -> A15s on 2M carried 6-10 PDUs per event (343 kbps average, writer backlogged).
        val policy = AdaptivePhyPolicy()
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.KEEP), policy.feed(0, 10, kbps = 400, blockedFraction = 0.9))
    }

    @Test
    fun aLightlyLoadedLinkIsNeverJudged() {
        // 60 kbps of voice on a fast link: the writer is rarely blocked, so no evidence either way.
        val policy = AdaptivePhyPolicy()
        assertEquals(emptyList<AdaptivePhyPolicy.Verdict>(), policy.feed(0, 30, kbps = 60, blockedFraction = 0.05))
    }

    @Test
    fun aLinkAlreadyOff2MIsNotMoved() {
        val policy = AdaptivePhyPolicy()
        assertEquals(emptyList<AdaptivePhyPolicy.Verdict>(), policy.feed(0, 30, kbps = 60, blockedFraction = 0.9, onTwoM = false))
    }

    @Test
    fun theWarmUpWindowIsIgnoredAndTheMeanDecides() {
        val policy = AdaptivePhyPolicy()
        // Warm-up window at 200 kbps (buffer fill), then 120 kbps: one PDU per event at 15 ms.
        assertEquals(emptyList<AdaptivePhyPolicy.Verdict>(), policy.feed(0, 2, kbps = 200, blockedFraction = 0.9))
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), policy.feed(2_000, 7, kbps = 120, blockedFraction = 0.9))
    }

    @Test
    fun aStalledWriterMovesOnlyWhereTheStallIsTheLinksOwnAndThePeerIsStillHeard() {
        // d2: not a relay, 95 heartbeats arrived: move and remember.
        assertEquals(true, stalledTwoMShouldMove(enabled = true, relayGuarded = false, peerHeard = true))
        // A relay's downstream link (mesh4-7a6c401c 0001), a silent peer (the supervision timeout's business), the control arm: keep.
        assertEquals(false, stalledTwoMShouldMove(enabled = true, relayGuarded = true, peerHeard = true))
        assertEquals(false, stalledTwoMShouldMove(enabled = true, relayGuarded = false, peerHeard = false))
        assertEquals(false, stalledTwoMShouldMove(enabled = false, relayGuarded = false, peerHeard = true))
    }

    @Test
    fun theInboundPipeKnowsWhenThePeerWasLastHeard() {
        val pipe = GattBytePipe { }
        assertEquals(false, pipe.heardWithin(AdaptivePhyLink.PEER_HEARD_MS))
        pipe.offer(byteArrayOf(1, 2, 3))
        assertEquals(true, pipe.heardWithin(AdaptivePhyLink.PEER_HEARD_MS))
        assertEquals(false, pipe.heardWithin(-1))
    }
}
