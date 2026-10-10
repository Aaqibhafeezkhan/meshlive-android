package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CocSingleInitiatorTest {
    private val low = byteArrayOf(0x10, 0x7f)
    private val high = byteArrayOf(0x10, 0x80.toByte())

    @Test
    fun exactlyOnePhoneOfAPairInitiatesComparingUnsignedBytes() {
        assertTrue(CocSingleInitiator.initiates(low, high))
        assertFalse(CocSingleInitiator.initiates(high, low))
        // 0x80 is above 0x7f unsigned although negative as a signed byte.
        assertFalse(CocSingleInitiator.initiates(byteArrayOf(0x80.toByte()), byteArrayOf(0x7f)))
    }

    @Test
    fun onlyTheResponderOfADirectCallHoldsOffAndOnlyWhenOn() {
        assertEquals(0L, CocSingleInitiator.holdoffMs(enabled = false, local = high, peer = low, direct = true))
        assertEquals(0L, CocSingleInitiator.holdoffMs(enabled = true, local = low, peer = high, direct = true))
        assertEquals(0L, CocSingleInitiator.holdoffMs(enabled = true, local = high, peer = low, direct = false))
        assertEquals(CocSingleInitiator.HOLDOFF_MS, CocSingleInitiator.holdoffMs(enabled = true, local = high, peer = low, direct = true))
    }

    @Test
    fun theResponderYieldsAsSoonAsThePeerCallArrives() {
        var slept = 0L
        var polls = 0
        val yielded = CocSingleInitiator.awaitPeerCall(1_500L, { ++polls >= 3 }) { slept += it }
        assertTrue(yielded)
        assertEquals(100L, slept)
    }

    @Test
    fun withoutAPeerCallTheResponderDialsAfterTheHoldoff() {
        var slept = 0L
        assertFalse(CocSingleInitiator.awaitPeerCall(120L, { false }) { slept += it })
        assertEquals(120L, slept)
    }

    @Test
    fun theExperimentIsOffByDefault() = assertFalse(CocSingleInitiator.enabled())
}
