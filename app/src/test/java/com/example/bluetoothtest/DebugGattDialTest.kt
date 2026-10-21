package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Design QV gate W2 dial hook: DEBUG only, exact address, and stages named from the production client's progress. */
class DebugGattDialTest {
    @Test
    fun aReleaseBuildNeverParsesTheExtras() {
        assertNull(DebugGattDial.request("65:80:DC:9E:F1:AF", null, null, debug = false))
    }

    @Test
    fun aDebugBuildDialsTheExactAddressOfTheDataServiceByDefault() {
        val request = DebugGattDial.request("65:80:dc:9e:f1:af", null, null, debug = true)!!
        assertEquals("65:80:DC:9E:F1:AF", request.address)
        assertEquals(DebugGattDial.DATA_SERVICE, request.service)
        assertTrue("a Windows provider advertises from a random address", request.randomAddress)
        assertFalse(DebugGattDial.request("65:80:DC:9E:F1:AF", null, "public", debug = true)!!.randomAddress)
    }

    @Test
    fun aMalformedAddressDialsNothing() {
        assertNull(DebugGattDial.request("65:80:DC:9E:F1", null, null, debug = true))
        assertNull(DebugGattDial.request("not an address", null, null, debug = true))
        assertNull(DebugGattDial.request(null, null, null, debug = true))
    }

    @Test
    fun aFailureIsReportedAtTheStageAfterTheLastOneReached() {
        val progress = DebugGattDial.Progress()
        assertEquals(DebugGattDial.Stage.CONNECT, progress.failedStage())
        progress.onStatus("GATT video MTU 247")
        assertEquals("244", progress.pdu())
        assertEquals(DebugGattDial.Stage.SERVICES, progress.failedStage())
        progress.opened()
        assertEquals(DebugGattDial.Stage.FIRST_WRITE, progress.failedStage())
    }

    @Test
    fun theLogLineCarriesStageResultAndPdu() {
        assertEquals(
            "BENCHMARK debug_gatt_dial: stage=subscribe | result=ok | pdu=244",
            DebugGattDial.line(DebugGattDial.Stage.SUBSCRIBE, "ok", "244"),
        )
    }
}
