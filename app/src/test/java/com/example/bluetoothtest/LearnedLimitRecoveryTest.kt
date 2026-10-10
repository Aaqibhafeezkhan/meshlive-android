package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedLimitRecoveryTest {
    private val minute = 60L * 1_000
    private val t0 = 1_000_000_000L

    // mesh4-b9726176-loop4-1: the M31 relay ceiling 450 (proven 405) refused an 807 kbps relay need.
    private val m31Relay = LearnedLinkCapacity(provenKbps = 405, ceilingKbps = 450, ceilingMeasuredAtMs = t0)

    @Test
    fun aFreshCeilingOpensOneProbeAfterACooldownThatDoublesPerFailure() {
        assertFalse(LinkCapacityPolicy.ceilingProbeOpen(m31Relay, 0, t0 + 2 * minute - 1))
        assertTrue(LinkCapacityPolicy.ceilingProbeOpen(m31Relay, 0, t0 + 2 * minute))
        assertFalse(LinkCapacityPolicy.ceilingProbeOpen(m31Relay, 2, t0 + 7 * minute))
        assertTrue(LinkCapacityPolicy.ceilingProbeOpen(m31Relay, 2, t0 + 8 * minute))
        assertEquals(64 * minute, LinkCapacityPolicy.ceilingProbeCooldownMs(9))
        // No ceiling (or an expired one): nothing to probe.
        assertFalse(LinkCapacityPolicy.ceilingProbeOpen(LearnedLinkCapacity(provenKbps = 405), 0, t0 + 3 * minute))
    }

    @Test
    fun anOpenProbeAddsExactlyOneLinkToTheCeilingBudget() {
        val now = t0 + 3 * minute
        val closed = LinkCapacityPolicy.probingBudgetKbps(m31Relay, 1264.0, 403.0, now, probeOpen = false)
        assertEquals(LinkCapacityPolicy.budgetKbps(m31Relay, 1264.0, 403.0, now), closed, 0.0)
        assertEquals(405.0, closed, 0.0)
        assertEquals(808.0, LinkCapacityPolicy.probingBudgetKbps(m31Relay, 1264.0, 403.0, now, probeOpen = true), 0.0)
        // Without a ceiling the probe changes nothing.
        val free = LearnedLinkCapacity(provenKbps = 405)
        assertEquals(
            LinkCapacityPolicy.budgetKbps(free, 1264.0, 403.0, now),
            LinkCapacityPolicy.probingBudgetKbps(free, 1264.0, 403.0, now, probeOpen = true),
            0.0,
        )
    }

    @Test
    fun failuresCountOnlyReRecordedCeilingsAndResetWhenCleared() {
        val ceiling = m31Relay
        val cleared = m31Relay.copy(ceilingKbps = null, ceilingMeasuredAtMs = 0L)
        assertEquals(3, LinkCapacityPolicy.nextProbeFailures(beforeFresh = true, rerecorded = true, after = ceiling, failures = 2))
        assertEquals(0, LinkCapacityPolicy.nextProbeFailures(beforeFresh = false, rerecorded = true, after = ceiling, failures = 2))
        assertEquals(2, LinkCapacityPolicy.nextProbeFailures(beforeFresh = true, rerecorded = false, after = ceiling, failures = 2))
        assertEquals(0, LinkCapacityPolicy.nextProbeFailures(beforeFresh = true, rerecorded = false, after = cleared, failures = 2))
    }

    @Test
    fun theResetRemovesEveryCeilingKeyButNotProvenOrProbe() {
        assertTrue(LinkCapacity.isCeilingKey("BLE_L2CAP.relay.ceiling"))
        assertTrue(LinkCapacity.isCeilingKey("BLE_GATT.fanout.ceilingAt"))
        assertTrue(LinkCapacity.isCeilingKey("BLE_L2CAP.wifi24.fanout.ceilingVersion"))
        assertTrue(LinkCapacity.isCeilingKey("BLE_L2CAP.relay.ceilingProbes"))
        assertFalse(LinkCapacity.isCeilingKey("BLE_L2CAP.relay.proven"))
        assertFalse(LinkCapacity.isCeilingKey("BLE_L2CAP.probe"))
    }

    @Test
    fun recoveryAgesABad2MMarkAfterADayUnlessReconfirmed() {
        val day = 24L * 60 * 60 * 1_000
        assertEquals(7 * day, AdaptivePhyMemory.ttlMs(recovery = false))
        assertEquals(day, AdaptivePhyMemory.ttlMs(recovery = true))
        assertTrue(AdaptivePhyMemory.fresh(t0, t0 + day - 1, AdaptivePhyMemory.ttlMs(recovery = true)))
        assertFalse(AdaptivePhyMemory.fresh(t0, t0 + day, AdaptivePhyMemory.ttlMs(recovery = true)))
        assertTrue(AdaptivePhyMemory.fresh(t0, t0 + day, AdaptivePhyMemory.ttlMs(recovery = false)))
    }
}
