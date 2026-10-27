package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHeadOfLineTest {
    private fun resolve(
        benchmark: Boolean,
        gatt: Boolean = true,
        mode: CallHeadOfLine.Mode? = null,
        fragments: Boolean = false,
        speechLed: Boolean = false,
    ) = CallHeadOfLine.resolve(benchmark, gatt, mode, fragments, speechLed)

    @Test
    fun aUserGattCallGetsFragmentsAndSpeechLedPlayoutOnlyWhenFragmentsWereNegotiated() {
        val policy = resolve(benchmark = false)
        assertTrue(policy.videoFragments)
        assertTrue(policy.speechLed(fragmentsNegotiated = true))
        // A relayed call or a peer without the fragment bit: speech-led alone was never measured (B5).
        assertFalse(policy.speechLed(fragmentsNegotiated = false))
        assertEquals("user_default", policy.source)
    }

    @Test
    fun aUserCocOrClassicCallKeepsTheWholeFramePathAndTheSharedDelay() {
        val policy = resolve(benchmark = false, gatt = false)
        assertFalse(policy.videoFragments)
        assertFalse(policy.speechLed(fragmentsNegotiated = true))
    }

    @Test
    fun theGattDefaultModeMeasuresExactlyTheUserPolicy() {
        listOf(true, false).forEach { gatt ->
            listOf(true, false).forEach { negotiated ->
                val user = resolve(benchmark = false, gatt = gatt)
                val measured = resolve(benchmark = true, gatt = gatt, mode = CallHeadOfLine.Mode.GATT_DEFAULT)
                assertEquals(user.videoFragments, measured.videoFragments)
                assertEquals(user.speechLed(negotiated), measured.speechLed(negotiated))
            }
        }
    }

    @Test
    fun benchmarkArmsStaySeparatelySwitchable() {
        // Control: no extras, no mode.
        val control = resolve(benchmark = true)
        assertFalse(control.videoFragments)
        assertFalse(control.speechLed(fragmentsNegotiated = true))
        // The Experimental speech-led-alone arm keeps working without fragments.
        assertTrue(resolve(benchmark = true, speechLed = true).speechLed(fragmentsNegotiated = false))
        assertTrue(resolve(benchmark = true, fragments = true).videoFragments)
        // The mode wins over the separate extras.
        val off = resolve(benchmark = true, mode = CallHeadOfLine.Mode.OFF, fragments = true, speechLed = true)
        assertFalse(off.videoFragments)
        assertFalse(off.speechLed(fragmentsNegotiated = true))
        val on = resolve(benchmark = true, gatt = false, mode = CallHeadOfLine.Mode.ON)
        assertTrue(on.videoFragments)
        assertFalse(on.speechLed(fragmentsNegotiated = false))
    }

    @Test
    fun theModeExtraParsesItsThreeValues() {
        assertEquals(CallHeadOfLine.Mode.GATT_DEFAULT, CallHeadOfLine.Mode.fromValue(" GATT_default "))
        assertEquals(CallHeadOfLine.Mode.OFF, CallHeadOfLine.Mode.fromValue("off"))
        assertEquals(null, CallHeadOfLine.Mode.fromValue("maybe"))
    }
}
