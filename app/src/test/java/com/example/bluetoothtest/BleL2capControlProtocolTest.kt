package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BleL2capControlProtocolTest {
    @Test
    fun roundTripsDynamicPsmInNetworkByteOrder() {
        val bytes = BleL2capControlProtocol.encodePsm(0x0081)

        assertEquals(0, bytes[0].toInt())
        assertEquals(0x81.toByte(), bytes[1])
        assertEquals(0x0081, BleL2capControlProtocol.decodePsm(bytes))
    }

    @Test
    fun rejectsMalformedPsmResponses() {
        assertThrows(IllegalArgumentException::class.java) {
            BleL2capControlProtocol.decodePsm(byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleL2capControlProtocol.decodePsm(byteArrayOf(0, 0))
        }
    }

    @Test
    fun retriesOnlyTransientGattStatus133ControlFailures() {
        assertEquals(true, isRetryableBleControlFailure("BLE control connection failed (status 133)"))
        assertEquals(true, isRetryableBleControlFailure("BLE channel read failed (status 133)"))
        assertEquals(true, isRetryableBleControlFailure("Timed out reading the receiver's BLE video channel"))
        assertEquals(false, isRetryableBleControlFailure("BLE control connection failed (status 8)"))
        assertEquals(false, isRetryableBleControlFailure("BLE channel read failed (status 8)"))
        assertEquals(false, isRetryableBleControlFailure("Receiver did not expose a BLE video channel"))
        assertEquals(false, isRetryableBleControlFailure(null))
    }
}
