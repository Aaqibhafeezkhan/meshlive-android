package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayColdStartTest {
    private val now = 1_000_000_000L
    private val perLink = MeshBandwidthEstimator.perLinkKbps(300, 15)
    private val freshPrior = MeshBandwidthEstimator.safeAggregateKbps(BluetoothTransportOption.BLE_L2CAP)

    @Test
    fun aFreshInstallMayRelayExactlyOneChildOfA300KbpsStream() {
        // mesh4-c0200edf-loop7-1 0001/0005: link_capacity.xml deleted -> "probe none, prior 700 kbps",
        // relay proven 0, need 807: the M31 never relayed.
        val fresh = LearnedLinkCapacity()
        val measured = LinkCapacityPolicy.budgetKbps(fresh, freshPrior, perLink, now)
        assertFalse(relayFits(measured, perLink))
        assertTrue(LinkCapacityPolicy.relayColdStart(fresh, now))
        val budget = LinkCapacityPolicy.relayBudgetKbps(measured, perLink, coldStart = true)
        assertEquals(2 * perLink, budget, 1e-9)
        assertTrue(relayFits(budget, perLink))
        // Exactly one child: never room for a second forwarded link.
        assertFalse(budget >= 3 * perLink)
    }

    @Test
    fun relayEvidenceEitherWayEndsTheColdStart() {
        // A recorded failure (fresh relay ceiling) keeps refusing: the guard's measurement wins.
        val failed = LearnedLinkCapacity(provenKbps = 0, ceilingKbps = 450, ceilingMeasuredAtMs = now - 60_000)
        assertFalse(LinkCapacityPolicy.relayColdStart(failed, now))
        val measured = LinkCapacityPolicy.budgetKbps(failed, freshPrior, perLink, now)
        assertFalse(relayFits(LinkCapacityPolicy.relayBudgetKbps(measured, perLink, coldStart = false), perLink))
        // A proven relay uses the measured "one link beyond proven" budget.
        val proven = LearnedLinkCapacity(provenKbps = 714)
        assertFalse(LinkCapacityPolicy.relayColdStart(proven, now))
        assertTrue(relayFits(LinkCapacityPolicy.budgetKbps(proven, freshPrior, perLink, now), perLink))
        // An expired ceiling with nothing proven is a cold start again (re-checked like every ceiling).
        val expired = failed.copy(ceilingMeasuredAtMs = now - LinkCapacityPolicy.CEILING_TTL_MS)
        assertTrue(LinkCapacityPolicy.relayColdStart(expired, now))
    }

    @Test
    fun theControlArmKeepsTheMeasuredBudget() {
        assertEquals(700.0, LinkCapacityPolicy.relayBudgetKbps(700.0, perLink, coldStart = false), 0.0)
        // A measured budget above the cold-start floor is never lowered.
        assertEquals(1264.0, LinkCapacityPolicy.relayBudgetKbps(1264.0, perLink, coldStart = true), 0.0)
    }
}
