package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFailurePolicyTest {
    @Test
    fun failureCodesMapToActions() {
        assertEquals(ScanFailureAction.RESTART, ScanFailurePolicy.action(ScanFailurePolicy.ALREADY_STARTED))
        assertEquals(ScanFailureAction.BACK_OFF, ScanFailurePolicy.action(ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED))
        assertEquals(ScanFailureAction.BACK_OFF, ScanFailurePolicy.action(ScanFailurePolicy.INTERNAL_ERROR))
        assertEquals(ScanFailureAction.REPORT, ScanFailurePolicy.action(ScanFailurePolicy.FEATURE_UNSUPPORTED))
        assertEquals(ScanFailureAction.BACK_OFF, ScanFailurePolicy.action(ScanFailurePolicy.OUT_OF_HARDWARE_RESOURCES))
        assertEquals(ScanFailureAction.BACK_OFF, ScanFailurePolicy.action(ScanFailurePolicy.SCANNING_TOO_FREQUENTLY))
        assertEquals(ScanFailureAction.BACK_OFF, ScanFailurePolicy.action(99))
        assertEquals("unknown", ScanFailurePolicy.name(99))
        assertEquals("scanning_too_frequently", ScanFailurePolicy.name(6))
    }

    @Test
    fun codesMatchTheFrameworkConstants() {
        // android.bluetooth.le.ScanCallback; 5 and 6 are public from API 33.
        assertEquals(1, ScanFailurePolicy.ALREADY_STARTED)
        assertEquals(2, ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED)
        assertEquals(3, ScanFailurePolicy.INTERNAL_ERROR)
        assertEquals(4, ScanFailurePolicy.FEATURE_UNSUPPORTED)
        assertEquals(5, ScanFailurePolicy.OUT_OF_HARDWARE_RESOURCES)
        assertEquals(6, ScanFailurePolicy.SCANNING_TOO_FREQUENTLY)
    }

    @Test
    fun backoffGrowsByThreeSecondsCapsAtThirtyAndResets() {
        val backoff = ScanRecoveryBackoff()
        val delays = List(12) { backoff.nextDelayMs() }
        assertEquals(
            listOf(3_000L, 6_000L, 9_000L, 12_000L, 15_000L, 18_000L, 21_000L, 24_000L, 27_000L, 30_000L, 30_000L, 30_000L),
            delays,
        )
        assertEquals(12, backoff.failures)
        backoff.reset()
        assertEquals(0, backoff.failures)
        assertEquals(3_000L, backoff.nextDelayMs())
    }

    @Test
    fun continuousScansHandBackToTheCallerOnceTheBackoffReachesItsCap() {
        val backoff = ScanRecoveryBackoff()
        val steps = List(10) { ScanFailurePolicy.step(ScanFailurePolicy.INTERNAL_ERROR, backoff, continuous = true) }
        // Nine silent retries (3..27 s, about 2 minutes in all), then the caller's visible error path.
        assertTrue(steps.take(9).none { it.handBack })
        assertEquals(135_000L, steps.take(9).sumOf { it.delayMs })
        assertTrue(steps[9].handBack)
        assertEquals(ScanFailureAction.BACK_OFF, steps[9].action)
    }

    @Test
    fun timedScansKeepRetryingUntilTheirDurationEnds() {
        val backoff = ScanRecoveryBackoff()
        val steps = List(12) { ScanFailurePolicy.step(ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED, backoff, continuous = false) }
        assertFalse(steps.any { it.handBack })
        assertEquals(30_000L, steps.last().delayMs)
    }

    @Test
    fun unsupportedFeatureIsReportedAtOnceAndAlreadyStartedRestarts() {
        val backoff = ScanRecoveryBackoff()
        val report = ScanFailurePolicy.step(ScanFailurePolicy.FEATURE_UNSUPPORTED, backoff, continuous = false)
        assertTrue(report.handBack)
        assertEquals(ScanFailureAction.REPORT, report.action)
        assertEquals(0, backoff.failures)
        assertTrue(ScanFailurePolicy.userMessage(4).contains("does not support"))
        val restart = ScanFailurePolicy.step(ScanFailurePolicy.ALREADY_STARTED, backoff, continuous = true)
        assertEquals(ScanFailureAction.RESTART, restart.action)
        assertEquals(3_000L, restart.delayMs)
        assertFalse(restart.handBack)
    }

    @Test
    fun recoveryRestartsUseTheRegistryGateAndNothingWhenItIsOff() {
        var now = 0L
        val gate = ScanStartGate { now }
        val budget = LeScanStartBudget(maxStarts = 5, windowMs = 30_000L)
        repeat(5) { assertEquals(0L, gate.reserve(budget)); now += 1_000L }
        // A sixth start inside 30 s waits for the oldest to leave the window (plus the gate's margin).
        assertEquals(30_000L - 5_000L + ScanStartGate.SCAN_QUOTA_MARGIN_MS, gate.reserve(budget))
        // test_device_policy_off=le-scan-start-quota: no budget, so no quota wait at all.
        assertEquals(0L, gate.reserve(null))
    }
}
