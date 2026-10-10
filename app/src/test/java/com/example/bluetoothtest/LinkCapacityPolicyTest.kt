package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCapacityPolicyTest {
    private val day = 24L * 60 * 60 * 1_000
    /** Within one session: ceilings are fresh for 30 minutes. */
    private val soon = 10L * 60 * 1_000

    @Test
    fun unmeasuredPhoneUsesThePriorButCanGrowOneLinkBeyondWhatItProved() {
        assertEquals(700.0, LinkCapacityPolicy.budgetKbps(LearnedLinkCapacity(), 700.0, 343.0, 0L), 0.0)
        val proven = LearnedLinkCapacity(provenKbps = 900)
        assertEquals(1_243.0, LinkCapacityPolicy.budgetKbps(proven, 700.0, 343.0, 0L), 0.0)
    }

    @Test
    fun saturationBoundsTheBudgetUntilItExpires() {
        // The S24 middle saturated at ~620 kbps aggregate while relaying 250 kbps over GATT.
        val learned = LinkCapacityPolicy.withCeiling(LearnedLinkCapacity(provenKbps = 650), 620, 0L)
        assertEquals(558, learned.provenKbps)
        val budget = LinkCapacityPolicy.budgetKbps(learned, 700.0, 343.0, soon)
        assertEquals(558.0, budget, 0.0)
        assertFalse(relayFits(budget, MeshBandwidthEstimator.perLinkKbps(250, 15)))
        assertTrue(relayFits(budget, MeshBandwidthEstimator.perLinkKbps(150, 15)))
        // After the ceiling expires the phone may try again.
        assertEquals(
            901.0,
            LinkCapacityPolicy.budgetKbps(learned, 700.0, 343.0, LinkCapacityPolicy.CEILING_TTL_MS),
            0.0,
        )
    }

    @Test
    fun cleanTrafficBelowTheCeilingRaisesTheBudgetAndAboveItClearsTheCeiling() {
        val saturated = LinkCapacityPolicy.withCeiling(LearnedLinkCapacity(), 620, 0L)
        val below = LinkCapacityPolicy.withHealthy(saturated, 600, soon)
        assertEquals(620, below.ceilingKbps)
        assertEquals(600.0, LinkCapacityPolicy.budgetKbps(below, 700.0, 343.0, soon), 0.0)
        val above = LinkCapacityPolicy.withHealthy(below, 700, soon)
        assertNull(above.ceilingKbps)
        assertEquals(700, above.provenKbps)
    }

    @Test
    fun perLinkLoadIncludesAudioHeadersAndMargin() {
        assertEquals(343.4, MeshBandwidthEstimator.perLinkKbps(250, 15), 0.1)
    }

    @Test
    fun probeReplacesTheAssumedPriorAndAWeakPeerCannotLowerIt() {
        val strong = LinkCapacityPolicy.withProbe(LearnedLinkCapacity(), 896, 0L)
        val afterWeakPeer = LinkCapacityPolicy.withProbe(strong, 430, day)
        assertEquals(896, afterWeakPeer.probeKbps)
        assertEquals(896.0, LinkCapacityPolicy.priorKbps(afterWeakPeer, 700.0, day), 0.01)
        assertNull(afterWeakPeer.ceilingKbps)
    }

    @Test
    fun oneSlowChildAmongSeveralDoesNotBoundThePhonesFanOut() {
        assertEquals(LinkLoadKind.FAN_OUT to LinkWindowState.PEER_LIMITED, classifyLinkWindow(2, 0, 1, 600))
        assertEquals(LinkLoadKind.FAN_OUT to LinkWindowState.SATURATED, classifyLinkWindow(2, 0, 2, 600))
        assertEquals(LinkLoadKind.FAN_OUT to LinkWindowState.HEALTHY, classifyLinkWindow(3, 0, 0, 900))
    }

    @Test
    fun oneSlowRelayChildIsThatChildsLinkUnlessThisPhonesReceivingAlsoSuffered() {
        // mesh3-smoke-c85a177c-1857: the ASUS received at full rate while its only child (A15s,
        // 60-84 kbps pair-specific link) fell behind; 346 kbps aggregate is not the ASUS radio.
        assertEquals(LinkLoadKind.RELAY to LinkWindowState.PEER_LIMITED, classifyLinkWindow(1, 1, 1, 346))
        // Its own playback degraded and it withdrew the child: shared airtime, a real relay ceiling.
        assertEquals(
            LinkLoadKind.RELAY to LinkWindowState.SATURATED,
            classifyLinkWindow(1, 1, 1, 620, radioLimited = true),
        )
        // Two children of a relay saturated together: the radio.
        assertEquals(LinkLoadKind.RELAY to LinkWindowState.SATURATED, classifyLinkWindow(2, 1, 2, 700))
    }

    @Test
    fun relayCeilingsStoredBeforeAttributionAreIgnoredButFanOutCeilingsStay() {
        assertFalse(LinkCapacity.usableCeiling(LinkLoadKind.RELAY, version = 1))
        assertFalse(LinkCapacity.usableCeiling(LinkLoadKind.RELAY, version = 2))
        assertTrue(LinkCapacity.usableCeiling(LinkLoadKind.RELAY, version = 3))
        // Fan-out ceilings from before adaptive PHY ignored fan-out windows may be its misfire.
        assertFalse(LinkCapacity.usableCeiling(LinkLoadKind.FAN_OUT, version = 2))
        assertTrue(LinkCapacity.usableCeiling(LinkLoadKind.FAN_OUT, version = 3))
    }

    @Test
    fun withoutTheMisattributedCeilingTheAsusCanRelayAThreeHundredKbpsStreamAgain() {
        // mesh4-915d7390 0001: proven 311 (lowered by the 346 ceiling), CoC probe 1105 kbps.
        val perLink = MeshBandwidthEstimator.perLinkKbps(300, 15)
        assertEquals(807.0, 2 * perLink, 1.0)
        val withBadCeiling = LearnedLinkCapacity(provenKbps = 311, ceilingKbps = 346, ceilingMeasuredAtMs = 0L)
        assertFalse(relayFits(LinkCapacityPolicy.budgetKbps(withBadCeiling, 1105.0, perLink, 60_000L), perLink))
        val ignored = withBadCeiling.copy(ceilingKbps = null, ceilingMeasuredAtMs = 0L)
        assertTrue(relayFits(LinkCapacityPolicy.budgetKbps(ignored, 1105.0, perLink, 60_000L), perLink))
    }

    @Test
    fun receivingWhileForwardingIsLearnedAsRelayAndSingleLinksTeachNothing() {
        assertEquals(LinkLoadKind.RELAY to LinkWindowState.SATURATED, classifyLinkWindow(1, 1, 1, 620, radioLimited = true))
        assertEquals(LinkLoadKind.RELAY to LinkWindowState.HEALTHY, classifyLinkWindow(1, 1, 0, 560))
        assertEquals(null to LinkWindowState.IDLE, classifyLinkWindow(1, 0, 1, 330))
        assertEquals(null to LinkWindowState.IDLE, classifyLinkWindow(0, 1, 0, 330))
    }

    @Test
    fun onlyTwoPointFourGigahertzWifiCountsAsSharingTheBluetoothBand() {
        assertTrue(wifiSharesBluetoothBand(2_472))
        assertFalse(wifiSharesBluetoothBand(5_180))
        assertFalse(wifiSharesBluetoothBand(null))
    }
}
