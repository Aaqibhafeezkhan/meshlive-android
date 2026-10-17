package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOpenPolicyTest {
    @Test
    fun errorCodesMapToTheirClass() {
        assertEquals(CameraOpenErrorKind.IN_USE, CameraOpenPolicy.classify(1))
        assertEquals(CameraOpenErrorKind.MAX_IN_USE, CameraOpenPolicy.classify(2))
        assertEquals(CameraOpenErrorKind.DISABLED, CameraOpenPolicy.classify(3))
        assertEquals(CameraOpenErrorKind.DEVICE, CameraOpenPolicy.classify(4))
        assertEquals(CameraOpenErrorKind.SERVICE, CameraOpenPolicy.classify(5))
        assertEquals(CameraOpenErrorKind.UNKNOWN, CameraOpenPolicy.classify(99))
    }

    @Test
    fun inUseIsRetriedThreeTimesFiveHundredMillisecondsApartThenReported() {
        var now = 0L
        val state = CameraOpenRetryState { now }
        repeat(3) {
            val decision = state.next(1)
            assertEquals(500L, decision.retryDelayMs)
            assertFalse(decision.closeFirst)
            now += 500
        }
        val final = state.next(1)
        assertNull(final.retryDelayMs)
        assertTrue(final.message, final.message.startsWith("Camera error 1"))
    }

    @Test
    fun maxCamerasInUseSharesTheTransientPolicy() {
        val state = CameraOpenRetryState { 0L }
        assertEquals(500L, state.next(2).retryDelayMs)
    }

    @Test
    fun deviceAndServiceErrorsCloseFullyBackOffOneSecondAndReopenOnce() {
        var now = 0L
        val state = CameraOpenRetryState { now }
        val first = state.next(4)
        assertEquals(1_000L, first.retryDelayMs)
        assertTrue(first.closeFirst)
        now += 1_000
        val second = state.next(4)
        assertNull("a wedged HAL is not retried in a loop", second.retryDelayMs)
        // The counters are per class: a service error after a device error still gets its one retry.
        assertEquals(1_000L, state.next(5).retryDelayMs)
    }

    @Test
    fun disabledCameraIsNeverRetriedAndIsExplained() {
        val decision = CameraOpenPolicy.decide(3, attemptsSoFar = 0, elapsedMs = 0)
        assertNull(decision.retryDelayMs)
        assertTrue(decision.message, "privacy toggle" in decision.message && decision.message.startsWith("Camera error 3"))
    }

    @Test
    fun unknownErrorsKeepTheFormerMessageAndAreNotRetried() {
        val decision = CameraOpenPolicy.decide(99, 0, 0)
        assertNull(decision.retryDelayMs)
        assertEquals("Camera error 99", decision.message)
    }

    @Test
    fun theTenSecondBudgetStopsTheRetries() {
        val decision = CameraOpenPolicy.decide(1, attemptsSoFar = 1, elapsedMs = 9_800)
        assertNull(decision.retryDelayMs)
    }

    @Test
    fun disablingTheEntryRestoresTheFormerImmediateFailure() {
        val decision = CameraOpenPolicy.decide(1, 0, 0, enabled = false)
        assertNull(decision.retryDelayMs)
        assertEquals("Camera error 1", decision.message)
    }

    @Test
    fun aSuccessfulOpenResetsTheStreak() {
        var now = 0L
        val state = CameraOpenRetryState { now }
        repeat(3) { state.next(1); now += 500 }
        state.reset()
        assertEquals(500L, state.next(1).retryDelayMs)
        assertEquals(1, state.attemptOf(1))
    }

    @Test
    fun theBenchmarkLineNamesTheActionAndTheAttempt() {
        val decision = CameraOpenPolicy.decide(1, 0, 0)
        val line = CameraOpenPolicy.benchmarkLine(decision, 1, 1, 0)
        assertTrue(line, line.startsWith("BENCHMARK camera_open_retry:") && "action=retry_in_500ms" in line && "attempt=1" in line)
    }
}
