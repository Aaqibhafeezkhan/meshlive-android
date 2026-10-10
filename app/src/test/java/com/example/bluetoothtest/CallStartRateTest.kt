package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStartRateTest {
    private val second = 1_000_000_000L

    private class MapMemory(private val unknown: () -> Int? = { null }) : SustainedRateMemory {
        val values = mutableMapOf<String, Int>()
        override fun startBps(peer: String): Int? = values[peer] ?: unknown()
        override fun record(peer: String, bps: Int) {
            values[peer] = bps
        }
    }

    @Test
    fun aCallWithNothingLearnedStartsConservativelyNotAtTheRequest() {
        // mvp-smoke-m31asus-b9029e48: a fresh install started at 1000 kbps on a ~620 kbps GATT link.
        assertEquals(368_000, CallStartRate.unknownPeerStartBps(null, 32_000))
        // With this phone's fresh link measurement: its share (both directions use the link).
        assertEquals(297_500 - 32_000, CallStartRate.unknownPeerStartBps(700, 32_000))
    }

    @Test
    fun theStartAppliesBeforeTheFirstSampleAndTheCallClimbsFromIt() {
        val memory = MapMemory { CallStartRate.unknownPeerStartBps(null, 32_000) }
        val controller = LinkRateController(
            1_000_000, audioBps = 32_000, memory = memory,
            frameRateLadder = FrameRateLadder(15, 5, FrameRateLadder.floorBpsFor(640, 480, 15)),
        )
        assertEquals(368_000, controller.seed("call", "asus", 0))
        assertEquals(368_000, controller.effectiveVideoBps)
        // The first sample continues the seeded link (no reset to the request); clean windows climb.
        val clean = LinkRateSample(second, 46_000, 46_000, 0, false, peer = "asus")
        assertNull(controller.onSample("call", clean, second))
        assertNull(controller.onSample("call", clean, 2 * second))
        assertEquals(468_000, controller.onSample("call", clean, 3 * second))
    }

    @Test
    fun aLearnedRateIsTheStartAndNeverAboveTheRequest() {
        val memory = MapMemory().apply { values["asus"] = 560_000; values["s24"] = 2_000_000 }
        assertEquals(560_000, LinkRateController(1_000_000, memory = memory).seed("call", "asus", 0))
        assertEquals(1_000_000, LinkRateController(1_000_000, memory = memory).seed("call", "s24", 0))
    }

    @Test
    fun learnedRatesUseABuildIndependentFormatWhileTheControlArmKeepsTheOldKeys() {
        val current = LinkCapacity.sustainedKeyName("gatt", "call", "asus", buildKeyed = false)
        val old = LinkCapacity.sustainedKeyName("gatt", "call", "asus", buildKeyed = true)
        assertEquals("gatt.sustained2.call.asus", current)
        assertEquals("gatt.sustained.call.asus", old)
        assertNotEquals(current, old)
        // The A/B reset and the launch record cover both.
        assertTrue(LinkCapacity.isSustainedKey(current) && LinkCapacity.isSustainedKey(old))
        assertTrue(!LinkCapacity.isSustainedKey("gatt.peerlink.asus"))
    }
}
