package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The copied-ID warning (Design G review F4): honest phones must not trip it, simultaneous copies must. */
class CopiedIdTest {
    private val rdi = "44e69c0a7a7d"

    @Test
    fun aSecondAddressForOneIdInsideAnEpochIsNotACopy() {
        val tracker = CopiedIdTracker()
        // The stack rotates the advertising address: the old one stops, the new one starts 30 s later.
        var warned = false
        for (t in 0..10) warned = tracker.observe(rdi, "AA:01", 1, t * 1_000L) || warned
        for (t in 40..50) warned = tracker.observe(rdi, "AA:02", 1, t * 1_000L) || warned
        assertFalse(warned)
        assertFalse(tracker.warned(rdi))
    }

    @Test
    fun aRelayMirrorAtRouteCostPlusOneIsNotACopy() {
        val tracker = CopiedIdTracker()
        var warned = false
        for (t in 0..20) {
            warned = tracker.observe(rdi, "AA:01", 0, t * 1_000L) || warned
            warned = tracker.observe(rdi, "BB:09", 1, t * 1_000L + 300L) || warned
        }
        assertFalse(warned)
    }

    @Test
    fun twoAddressesAtTheSameCostAtTheSameTimeRepeatedlyAreACopy() {
        val tracker = CopiedIdTracker()
        var firstWarningAt = -1L
        for (t in 0..20) {
            val now = t * 1_000L
            val a = tracker.observe(rdi, "AA:01", 1, now)
            val b = tracker.observe(rdi, "CC:07", 1, now + 400L)
            if ((a || b) && firstWarningAt < 0) firstWarningAt = now
        }
        assertTrue(firstWarningAt in 1..10_000L)
        assertTrue(tracker.warned(rdi))
    }

    @Test
    fun oneOverlapIsNotEnough() {
        val tracker = CopiedIdTracker()
        tracker.observe(rdi, "AA:01", 1, 0L)
        assertFalse(tracker.observe(rdi, "CC:07", 1, 500L))
        assertFalse(tracker.warned(rdi))
    }

    @Test
    fun aForgottenIdStartsOver() {
        val tracker = CopiedIdTracker()
        for (t in 0..20) {
            tracker.observe(rdi, "AA:01", 1, t * 1_000L)
            tracker.observe(rdi, "CC:07", 1, t * 1_000L + 400L)
        }
        assertTrue(tracker.warned(rdi))
        tracker.forget(rdi)
        assertFalse(tracker.warned(rdi))
    }

    @Test
    fun idsNotHeardForAWhileAreDroppedSoMemoryStaysBounded() {
        val tracker = CopiedIdTracker()
        repeat(2_000) { tracker.observe("%012x".format(it), "AA:01", 0, it * 10L) }
        tracker.observe("ffffffffffff", "AA:01", 0, 2_000L * 10 + CopiedIdTracker.STALE_MS + 1)
        // Only the fresh ID is left (the 512-key bound pruned the rest on the way).
        assertFalse(tracker.warned("000000000000"))
        assertFalse(tracker.warned("ffffffffffff"))
    }
}
