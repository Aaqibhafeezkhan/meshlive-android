package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPeerCadenceTest {
    private val key = "c79e03836d6a"

    @Test
    fun aPhoneHeardOftenLeavesTheListAfterThreeMissedSightingsNotTwelveSeconds() {
        val cadence = CallPeerCadence()
        var t = 0L
        repeat(10) { cadence.observe(key, t); t += 600L } // heard every 0.6 s
        val last = t - 600L
        assertEquals(CallPeerCadence.MIN_STALE_MS, cadence.staleAfterMs(key)) // 3 x 0.6 s, floored at 4 s
        assertTrue(cadence.isFresh(key, last, last + 4_000L))
        assertFalse("the A15s tapped a row 4 s after the S24 stopped offering calls", cadence.isFresh(key, last, last + 4_001L))
    }

    @Test
    fun aSlowScannerKeepsItsLongerMeasuredWindowUpToTheFixedOne() {
        val cadence = CallPeerCadence()
        listOf(0L, 2_000L, 4_000L, 7_000L, 9_000L).forEach { cadence.observe(key, it) }
        assertEquals(9_000L, cadence.staleAfterMs(key)) // largest gap 3 s x 3
        listOf(13_000L, 17_000L).forEach { cadence.observe(key, it) }
        assertEquals(PRIVATE_CALL_PEER_STALE_MS, cadence.staleAfterMs(key)) // 4 s x 3 capped at 12 s
    }

    @Test
    fun tooFewSightingsOrAReturnAfterLeavingUseTheFixedWindow() {
        val cadence = CallPeerCadence()
        assertEquals(PRIVATE_CALL_PEER_STALE_MS, cadence.staleAfterMs("unknown"))
        listOf(0L, 500L, 1_000L).forEach { cadence.observe(key, it) }
        assertEquals("two gaps are not a measurement", PRIVATE_CALL_PEER_STALE_MS, cadence.staleAfterMs(key))
        listOf(1_500L, 2_000L).forEach { cadence.observe(key, it) }
        assertEquals(CallPeerCadence.MIN_STALE_MS, cadence.staleAfterMs(key))
        cadence.observe(key, 60_000L) // gone for a minute, back again
        assertEquals(PRIVATE_CALL_PEER_STALE_MS, cadence.staleAfterMs(key))
        cadence.forget(key)
        assertEquals(PRIVATE_CALL_PEER_STALE_MS, cadence.staleAfterMs(key))
    }

    @Test
    fun aGattErrorSaysWhatTheUserCanDoAndOtherDetailsStay() {
        val shown = privateCallConnectionFailureMessage(
            BluetoothTransportOption.BLE_GATT,
            "GATT video receiver disconnected (status 133)",
            bondFree = false,
        )
        assertTrue(shown.startsWith("Call could not connect: their phone did not accept the Bluetooth connection"))
        assertEquals("closed", plainCallConnectDetail("closed"))
        assertEquals("status 1330", plainCallConnectDetail("status 1330"))
    }
}
