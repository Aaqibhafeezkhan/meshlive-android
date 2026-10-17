package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDutyCycleTest {
    private val lowPower = 0
    private val balanced = 1
    private val lowLatency = 2

    @Test
    fun windowAndIntervalChangedInAndroid9And13() {
        assertEquals("500/5000", ScanDutyCycle.duty(27, lowPower, true).label())
        assertEquals("2000/5000", ScanDutyCycle.duty(27, balanced, true).label())
        assertEquals("5000/5000", ScanDutyCycle.duty(27, lowLatency, true).label())
        for (sdk in listOf(28, 29, 30, 31, 32)) {
            assertEquals("512/5120", ScanDutyCycle.duty(sdk, lowPower, true).label())
            assertEquals("1024/4096", ScanDutyCycle.duty(sdk, balanced, true).label())
            assertEquals("4096/4096", ScanDutyCycle.duty(sdk, lowLatency, true).label())
        }
        for (sdk in listOf(33, 34, 35, 36)) {
            assertEquals("140/1400", ScanDutyCycle.duty(sdk, lowPower, true).label())
            assertEquals("183/730", ScanDutyCycle.duty(sdk, balanced, true).label())
            assertEquals("100/100", ScanDutyCycle.duty(sdk, lowLatency, true).label())
        }
    }

    @Test
    fun duty_percentages_follow() {
        assertEquals(10, ScanDutyCycle.duty(34, lowPower, true).percent)
        assertEquals(25, ScanDutyCycle.duty(34, balanced, true).percent)
        assertEquals(100, ScanDutyCycle.duty(34, lowLatency, true).percent)
        assertEquals(25, ScanDutyCycle.duty(30, balanced, true).percent)
        assertEquals(40, ScanDutyCycle.duty(27, balanced, true).percent)
    }

    @Test
    fun screenOffRemapsFilteredScansOnAndroid13Plus() {
        assertEquals("512/10240", ScanDutyCycle.duty(34, lowPower, false).label())
        assertEquals("183/730", ScanDutyCycle.duty(34, balanced, false).label())
        assertEquals("100/100", ScanDutyCycle.duty(34, lowLatency, false).label()) // LOW_LATENCY is exempt
        // 8-12 keep a filtered scan in its requested mode with the screen off.
        assertEquals("1024/4096", ScanDutyCycle.duty(31, balanced, false).label())
    }

    @Test
    fun filteredScansDropToLowPowerAfterTheTimeoutOn13Plus() {
        assertEquals("140/1400", ScanDutyCycle.afterTimeoutDuty(34, lowLatency, true).label())
        assertEquals("140/1400", ScanDutyCycle.afterTimeoutDuty(33, balanced, true).label())
        assertEquals("1024/4096", ScanDutyCycle.afterTimeoutDuty(31, balanced, true).label())
    }

    @Test
    fun theLogLineCarriesSdkModeAndWindows() {
        val line = ScanDutyCycle.line(34, balanced, true, "ab12")
        assertTrue(line, line.startsWith("scan_duty: scanner=ab12 | sdk=34 | mode=1 | screenOn=true | expectedWindowIntervalMs=183/730 | dutyPercent=25"))
        assertTrue(line, line.contains("timeoutWindowMs=600000 | afterTimeoutWindowIntervalMs=140/1400"))
        assertTrue(ScanDutyCycle.line(30, lowLatency, true, "x").contains("timeoutWindowMs=1800000"))
    }
}
