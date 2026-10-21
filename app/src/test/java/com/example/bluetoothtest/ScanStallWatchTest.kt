package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A visible scan that the stack left without any result (Redmi 12: raw=0 for 28-90 s) is restarted, bounded and backed off. */
class ScanStallWatchTest {
    @Test
    fun aSilentScanIsDueAfterTenSecondsAndAnyResultKeepsItQuiet() {
        val watch = ScanStallWatch()
        watch.onStarted(1_000L)
        assertFalse(watch.due(10_999L))
        assertTrue(watch.due(11_000L))
        assertEquals(10_000L, watch.silentMs(11_000L))
        watch.onResult(10_500L)
        assertFalse("a result, even one the scanner refuses, proves the scan works", watch.due(20_499L))
    }

    @Test
    fun eachRestartThatChangesNothingDoublesTheWaitUpToEightySeconds() {
        val watch = ScanStallWatch()
        watch.onStarted(0L)
        var t = 0L
        val waits = mutableListOf<Long>()
        repeat(6) {
            val wait = watch.requiredSilenceMs()
            waits += wait
            t += wait
            assertTrue(watch.due(t))
            watch.onRestarted(t)
        }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 80_000L, 80_000L), waits)
        // An empty room costs at most one scan start per 80 s; one result brings the 10 s window back.
        watch.onResult(t + 1L)
        assertEquals(ScanStallWatch.STALL_MS, watch.requiredSilenceMs())
    }

    @Test
    fun aRenewalOrBalancedRestartCountsSilenceFromItsStart() {
        val watch = ScanStallWatch()
        watch.onStarted(0L)
        watch.onStarted(9_000L)
        assertFalse(watch.due(18_999L))
        assertTrue(watch.due(19_000L))
    }

    @Test
    fun theLogLineNamesScannerSilenceRestartAndQuotaUse() {
        assertEquals(
            "scan_stall: scanner=24a13c1 | silentMs=10002 | restart=yes | startsIn30s=2",
            ScanStallWatch.line("24a13c1", 10_002L, "yes", 2),
        )
    }

    private fun source(name: String): String = listOf(
        java.io.File("src/main/java/com/example/bluetoothtest/$name"),
        java.io.File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

}
