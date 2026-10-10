package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanOutProvenUnitsTest {
    private val now = 1_000_000_000L
    private val perLink = MeshBandwidthEstimator.perLinkKbps(300, 15)

    private fun children(budget: Double) = MeshBandwidthEstimator.layeredLinkSlots(budget, perLink, null)

    @Test
    fun aSourceProvenOnTwoChildrenMayTryAThird() {
        // mesh4-c0200edf-loop7-1 0002: ASUS probe 827, fan-out proven 723-737 -> "slots full" at 2.
        val asus = LearnedLinkCapacity(provenKbps = 737, probeKbps = 827, probeMeasuredAtMs = now)
        val control = LinkCapacityPolicy.budgetKbps(asus, 827.0, perLink, now,
            LinkCapacityPolicy.fanOutProvenScale(LinkLoadKind.FAN_OUT, enabled = false))
        assertEquals(2, children(control))
        val fixed = LinkCapacityPolicy.budgetKbps(asus, 827.0, perLink, now,
            LinkCapacityPolicy.fanOutProvenScale(LinkLoadKind.FAN_OUT, enabled = true))
        assertEquals(737 * 1.2 + perLink, fixed, 1e-6)
        assertEquals(3, children(fixed))
    }

    @Test
    fun growthIsStillOneLinkAtATimeAndAFreshCeilingGoverns() {
        // Proven on one child (~360 kbps on the wire) allows two, not three.
        val one = LearnedLinkCapacity(provenKbps = 360)
        val scale = LinkCapacityPolicy.fanOutProvenScale(LinkLoadKind.FAN_OUT, enabled = true)
        assertEquals(2, children(LinkCapacityPolicy.budgetKbps(one, 700.0, perLink, now, scale)))
        // A fresh ceiling (a failed third child) is unaffected by the scale.
        val ceiling = LearnedLinkCapacity(provenKbps = 737, ceilingKbps = 900, ceilingMeasuredAtMs = now - 1_000)
        assertEquals(
            LinkCapacityPolicy.budgetKbps(ceiling, 827.0, perLink, now),
            LinkCapacityPolicy.budgetKbps(ceiling, 827.0, perLink, now, scale),
            0.0,
        )
        assertEquals(2, children(LinkCapacityPolicy.budgetKbps(ceiling, 827.0, perLink, now, scale)))
    }

    @Test
    fun relayBudgetsAndTheControlArmKeepRawProven() {
        assertEquals(1.0, LinkCapacityPolicy.fanOutProvenScale(LinkLoadKind.RELAY, enabled = true), 0.0)
        assertEquals(1.0, LinkCapacityPolicy.fanOutProvenScale(LinkLoadKind.FAN_OUT, enabled = false), 0.0)
        assertTrue(LinkCapacityPolicy.fanOutProvenScale(LinkLoadKind.FAN_OUT, enabled = true) > 1.0)
    }
}
