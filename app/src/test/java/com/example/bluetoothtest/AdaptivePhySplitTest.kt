package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Experimental `test_adaptive_phy_split`: the decision that turns the 2M -> 1M move into a one-direction move first. */
class AdaptivePhySplitTest {
    private val oneEvent15ms = AdaptivePhyPolicy.oneEventKbps(15.0)

    private fun AdaptivePhyPolicy.feed(startMs: Long, seconds: Int, kbps: Int, onTwoM: Boolean): List<AdaptivePhyPolicy.Verdict> =
        (1..seconds * 10).map { i ->
            onWrite(startMs + i * 100L, kbps * 100 / 8, 90L, onTwoM, oneEvent15ms)
        }.filter { it != AdaptivePhyPolicy.Verdict.NONE }

    @Test
    fun switchOffAlwaysTakesTheFullMoveToOneM() {
        for (link in listOf("gatt", "coc")) {
            for (already in listOf(false, true)) {
                assertNull(adaptiveSplitFor(null, link, null, null, already))
            }
        }
    }

    @Test
    fun switchOnSplitsAGattLinkOnceWithTheConfiguredDirection() {
        assertEquals(GattPhySplit.TX_2M_RX_1M, adaptiveSplitFor(GattPhySplit.TX_2M_RX_1M, "gatt", null, null, false))
        assertEquals(GattPhySplit.TX_1M_RX_2M, adaptiveSplitFor(GattPhySplit.TX_1M_RX_2M, "gatt", null, null, false))
        // Tried once per link: the next decision on the same link is the full move.
        assertNull(adaptiveSplitFor(GattPhySplit.TX_2M_RX_1M, "gatt", null, null, true))
    }

    @Test
    fun otherLinksAndArmsKeepTheFullMove() {
        // The CoC control link is untested; a link that already started split and a test_ble_phy arm keep their behaviour.
        assertNull(adaptiveSplitFor(GattPhySplit.TX_2M_RX_1M, "coc", null, null, false))
        assertNull(adaptiveSplitFor(GattPhySplit.TX_2M_RX_1M, "gatt", GattPhySplit.TX_2M_RX_1M, null, false))
        assertNull(adaptiveSplitFor(GattPhySplit.TX_2M_RX_1M, "gatt", null, ExperimentalBlePhy.LE_1M, false))
    }

    @Test
    fun theLogValueNamesTheSplit() {
        assertEquals("split-tx2m-rx1m", adaptiveSplitLabel(GattPhySplit.TX_2M_RX_1M))
        assertEquals("split-tx1m-rx2m", adaptiveSplitLabel(GattPhySplit.TX_1M_RX_2M))
    }

    @Test
    fun aLinkStaysMeasurableAfterTheSplit() {
        // Not split: only a transmit PHY of 2M counts (the old rule).
        assertTrue(adaptiveLinkOnTwoM(LePhy.TWO_M, null))
        assertFalse(adaptiveLinkOnTwoM(LePhy.ONE_M, null))
        // tx2m-rx1m keeps the transmit PHY on 2M; tx1m-rx2m still has a 2M receive direction to give up.
        assertTrue(adaptiveLinkOnTwoM(LePhy.TWO_M, GattPhySplit.TX_2M_RX_1M))
        assertFalse(adaptiveLinkOnTwoM(LePhy.ONE_M, GattPhySplit.TX_2M_RX_1M))
        assertTrue(adaptiveLinkOnTwoM(LePhy.ONE_M, GattPhySplit.TX_1M_RX_2M))
        assertFalse(adaptiveLinkOnTwoM(0, GattPhySplit.TX_1M_RX_2M))
    }

    @Test
    fun aLinkThatStillFailsOnTheSplitDecidesAgainAndMovesToFullOneM() {
        // ASUS -> A15s on 2M: one PDU per event while backlogged (slowlink-asussnoop2-b9029e48) -> first verdict.
        var policy = AdaptivePhyPolicy()
        val first = policy.feed(0, 9, kbps = 60, onTwoM = adaptiveLinkOnTwoM(LePhy.TWO_M, null))
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), first)
        val arm = GattPhySplit.TX_2M_RX_1M
        val split = adaptiveSplitFor(arm, "gatt", null, null, alreadySplit = false)
        assertEquals(arm, split)
        // The caller re-arms the policy; the split leaves the transmit PHY on 2M, so the same slowness decides again.
        policy = AdaptivePhyPolicy()
        val second = policy.feed(10_000, 9, kbps = 60, onTwoM = adaptiveLinkOnTwoM(LePhy.TWO_M, split))
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), second)
        assertNull(adaptiveSplitFor(arm, "gatt", null, null, alreadySplit = true))
    }

    @Test
    fun aLinkThatRecoversOnTheSplitIsNotMovedAgain() {
        var policy = AdaptivePhyPolicy()
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), policy.feed(0, 9, kbps = 60, onTwoM = true))
        policy = AdaptivePhyPolicy()
        // Delivery well above 1.25 PDUs per event: the policy keeps the link as it is.
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.KEEP), policy.feed(10_000, 9, kbps = 400, onTwoM = true))
    }

    @Test
    fun theMirrorSplitLinkIsStillMeasuredButOldBehaviourIsNot() {
        // Without a split, a link reported on a 1M transmit PHY is never evaluated (the old rule): no verdict at all.
        val plain = AdaptivePhyPolicy().feed(0, 9, kbps = 60, onTwoM = adaptiveLinkOnTwoM(LePhy.ONE_M, null))
        assertEquals(emptyList<AdaptivePhyPolicy.Verdict>(), plain)
        val mirror = AdaptivePhyPolicy().feed(0, 9, kbps = 60, onTwoM = adaptiveLinkOnTwoM(LePhy.ONE_M, GattPhySplit.TX_1M_RX_2M))
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), mirror)
    }
}
