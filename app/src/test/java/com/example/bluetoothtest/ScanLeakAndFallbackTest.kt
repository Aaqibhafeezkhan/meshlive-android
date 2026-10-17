package com.example.bluetoothtest

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLeakAndFallbackTest {
    private class MemoryStore : ScanMarkerStore {
        var marker = false
        var count = 0
        override fun markerExists() = marker
        override fun writeMarker() { marker = true }
        override fun clearMarker() { marker = false }
        override fun uncleanExits() = count
        override fun setUncleanExits(count: Int) { this.count = count }
    }

    @Test
    fun markerFoundAtStartIsOneUncleanExitAndACleanStopLeavesNoneBehind() {
        val store = MemoryStore()
        val first = ScanMarkerTracker(store)
        assertEquals(0, first.onProcessStart())
        first.onScannerRegistered()
        assertTrue(store.marker)
        first.onAllScannersGone() // stopAll / every owner closed
        assertFalse(store.marker)
        assertEquals(0, ScanMarkerTracker(store).onProcessStart())

        // The process dies with a scanner registered, three times.
        repeat(3) {
            val tracker = ScanMarkerTracker(store)
            tracker.onProcessStart()
            tracker.onScannerRegistered()
        }
        val next = ScanMarkerTracker(store)
        assertEquals(3, next.onProcessStart())
        assertFalse(store.marker) // consumed, so a restart is not counted twice
        assertEquals(3, ScanMarkerTracker(store).onProcessStart())
        next.onBluetoothRestarted()
        assertEquals(0, ScanMarkerTracker(store).onProcessStart())
    }

    @Test
    fun fileStoreKeepsTheMarkerAndResetsTheCountAcrossABoot() {
        val dir = File(System.getProperty("java.io.tmpdir"), "scan-marker-test-${System.nanoTime()}")
        try {
            val store = FileScanMarkerStore(dir, bootCount = 7)
            assertFalse(store.markerExists())
            store.writeMarker()
            store.writeMarker() // idempotent
            assertTrue(store.markerExists())
            store.setUncleanExits(4)
            assertEquals(4, FileScanMarkerStore(dir, bootCount = 7).uncleanExits())
            assertEquals(0, FileScanMarkerStore(dir, bootCount = 8).uncleanExits())
            assertEquals(0, FileScanMarkerStore(dir, bootCount = null).uncleanExits())
            store.clearMarker()
            store.clearMarker() // idempotent
            assertFalse(store.markerExists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun scanError2AndGatt257OnAndroid10AfterAnUncleanExitAreTheLeak() {
        assertEquals("android10_scanner_leak", ScanLeakClassifier.classify(29, 2, null, 1))
        assertEquals("android10_scanner_leak", ScanLeakClassifier.classify(29, null, 257, 5))
        // Without an unclean exit the same codes are an exhausted pool from someone else.
        assertEquals("pool_exhausted_other", ScanLeakClassifier.classify(29, 2, null, 0))
        // Other codes, other SDKs: not this leak.
        assertNull(ScanLeakClassifier.classify(29, 3, null, 4))
        assertEquals("android11_scanner_leak_suspected", ScanLeakClassifier.classify(30, 2, null, 4))
        assertEquals("pool_exhausted_other", ScanLeakClassifier.classify(30, null, 257, 0))
        assertNull(ScanLeakClassifier.classify(30, 3, null, 4))
        assertNull(ScanLeakClassifier.classify(31, 2, null, 4))
        assertNull(ScanLeakClassifier.classify(34, null, 257, 4))
    }

    // ---- S-06 fallback ----

    private val wanted = UUID.fromString("c8d5a101-9f88-4f65-a03d-7f5a2fb921fa")

    @Test
    fun softwareMatchAcceptsOnlyTheAppsServiceUuid() {
        assertTrue(ScanFilterFallback.matchesService(listOf(UUID.randomUUID(), wanted), wanted))
        assertFalse(ScanFilterFallback.matchesService(listOf(UUID.randomUUID()), wanted))
        assertFalse(ScanFilterFallback.matchesService(emptyList(), wanted))
        assertFalse(ScanFilterFallback.matchesService(null, wanted))
    }

    @Test
    fun nextStepTruthTable() {
        val f = ScanFilterStage.FILTERED
        val s = ScanFilterStage.SOFTWARE
        val step = ScanFilterFallback::nextStep
        assertEquals(ScanFilterFallback.Step.NONE, step(false, f, true, true, null)) // switch off
        assertEquals(ScanFilterFallback.Step.PROBE, step(true, f, true, true, null))
        assertEquals(ScanFilterFallback.Step.SWITCH_TO_SOFTWARE, step(true, f, true, true, true))
        assertEquals(ScanFilterFallback.Step.NONE, step(true, f, true, true, false)) // not a filter problem
        assertEquals(ScanFilterFallback.Step.NONE, step(true, f, false, true, null)) // screen off: never unfilter
        assertEquals(ScanFilterFallback.Step.NONE, step(true, f, true, false, null)) // another cause explains it
        assertEquals(ScanFilterFallback.Step.NONE, step(true, s, true, true, null)) // already on
        assertEquals(ScanFilterFallback.Step.REVERT_TO_FILTERED, step(true, s, false, false, null))
    }

    @Test
    fun theFallbackIsOffByDefaultAndTheStageStartsFiltered() {
        ScanSwitches.reset()
        assertFalse(ScanSwitches.softwareFilterFallback)
        ScanFilterFallback.setStage(ScanFilterStage.FILTERED)
        assertFalse(ScanFilterFallback.softwareFiltering())
        ScanFilterFallback.setStage(ScanFilterStage.SOFTWARE)
        assertTrue(ScanFilterFallback.softwareFiltering())
        ScanFilterFallback.setStage(ScanFilterStage.FILTERED)
    }

    @Test
    fun theProbeReportsAdvertsSilenceOrInconclusive() {
        val clock = FakeScanClock()
        val budget = LeScanStartBudget(5, 30_000L)
        val gate = ScanStartGate(clock = { clock.now })
        var emit: (() -> Unit)? = null
        var started = 0
        var stopped = 0
        val probe = UnfilteredScanProbe(
            gate, { budget }, clock,
            start = { onResult -> started++; emit = onResult; true },
            stop = { stopped++ },
        )
        var verdict: Boolean? = false
        assertTrue(probe.run { verdict = it })
        assertTrue(probe.running)
        assertFalse(probe.run { }) // one at a time
        emit!!.invoke()
        clock.advance(UnfilteredScanProbe.DURATION_MS)
        assertEquals(true, verdict)
        assertEquals(1, stopped)
        assertFalse(probe.running)
        assertEquals(1, gate.used(budget)) // the probe takes a gated slot

        probe.run { verdict = it }
        clock.advance(UnfilteredScanProbe.DURATION_MS)
        assertEquals(false, verdict) // nothing arrived: not a filter problem

        // No free slot: inconclusive, nothing started.
        repeat(3) { assertEquals(0L, gate.reserve(budget)) }
        val before = started
        probe.run { verdict = it }
        assertNull(verdict)
        assertEquals(before, started)

        // A scanner that cannot start is inconclusive too.
        verdict = true
        val failing = UnfilteredScanProbe(ScanStartGate(clock = { clock.now }), { budget }, clock, start = { false }, stop = {})
        failing.run { verdict = it }
        assertNull(verdict)
        assertFalse(failing.running)
    }
}
