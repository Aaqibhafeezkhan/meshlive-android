package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugMeetControlTest {
    private val code = Bvmid2.encode(
        Bvmid2Payload(IdentityFingerprint.fromHex("ab".repeat(32)), ByteArray(32) { 7 }, ByteArray(6) { 1 }),
    )

    @Test
    fun aScanCommandNeedsAValidCode() {
        assertNull(DebugMeetControl.parse("scan\nBVMID2.nonsense\nyes\n"))
        assertNull(DebugMeetControl.parse("scan\n"))
        assertNull(DebugMeetControl.parse(null))
        assertNull(DebugMeetControl.parse("call\n$code\n"))
        assertEquals(code, DebugMeetControl.parse("scan\n$code\nyes\n")!!.code)
        assertEquals("show", DebugMeetControl.parse("show\n")!!.mode)
    }

    @Test
    fun theThirdLineScriptsTheAnswerOrLeavesItToAHuman() {
        assertEquals(true, DebugMeetControl.parse("scan\n$code\nyes\n")!!.confirm)
        assertEquals(false, DebugMeetControl.parse("scan\n$code\nNO\n")!!.confirm)
        assertNull(DebugMeetControl.parse("scan\n$code\n")!!.confirm)
    }
}
