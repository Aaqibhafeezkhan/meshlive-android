package com.example.bluetoothtest

import android.bluetooth.le.AdvertisingSetCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watchdog restart policy and the non-throwing start (AdvertisingWatchdog.kt, backlog A-01 and A-03). */
class AdvertisingWatchdogTest {
    private var now = 10_000L
    private fun watchdog() = AdvertisingWatchdog(clock = { now })

    private fun restart(delayMs: Long, reason: String) = AdvertisingWatchdog.Decision.Restart(delayMs, reason)

    @Test
    fun anUnexpectedStopRestartsWithBackOffAndThenGivesUp() {
        val watchdog = watchdog()
        assertEquals(restart(1_000, "set_stopped"), watchdog.onUnexpectedStop())
        assertEquals(restart(2_000, "restart_failed"), watchdog.onRestartFailed())
        assertEquals(restart(4_000, "restart_failed"), watchdog.onRestartFailed())
        assertEquals(restart(8_000, "restart_failed"), watchdog.onRestartFailed())
        assertEquals(restart(16_000, "restart_failed"), watchdog.onRestartFailed())
        val last = watchdog.onRestartFailed()
        assertTrue(last.toString(), last is AdvertisingWatchdog.Decision.GiveUp)
    }

    @Test
    fun restartsOlderThanTheWindowDoNotCount() {
        val watchdog = watchdog()
        repeat(5) { watchdog.onUnexpectedStop() }
        assertTrue(watchdog.onUnexpectedStop() is AdvertisingWatchdog.Decision.GiveUp)
        now += 10 * 60_000L + 1
        assertEquals("a long-lived set that dies again starts the back-off over", restart(1_000, "set_stopped"), watchdog.onUnexpectedStop())
    }

    @Test
    fun aDisableThatTheStackReversesWithinTheGraceIsAnAddressRotationNotALoss() {
        val watchdog = watchdog()
        assertEquals(AdvertisingWatchdog.Decision.WaitGrace(AdvertisingWatchdog.GRACE_MS), watchdog.onUnexpectedDisable())
        now += 40
        assertEquals("the gap is the only observable trace of the rotation", 40L, watchdog.onEnabled())
        assertEquals(AdvertisingWatchdog.Decision.None, watchdog.onGraceElapsed())
    }

    @Test
    fun aSetStillDisabledWhenTheGraceEndsIsRestarted() {
        val watchdog = watchdog()
        watchdog.onUnexpectedDisable()
        now += AdvertisingWatchdog.GRACE_MS
        assertEquals(restart(1_000, "disabled_for_${AdvertisingWatchdog.GRACE_MS}ms"), watchdog.onGraceElapsed())
        assertEquals("one judgement per disable", AdvertisingWatchdog.Decision.None, watchdog.onGraceElapsed())
    }

    @Test
    fun anEnableWithoutAPriorDisableReportsNoGap() {
        assertNull(watchdog().onEnabled())
    }

    @Test
    fun aSecondDisableInsideTheGraceKeepsTheFirstTimestamp() {
        val watchdog = watchdog()
        watchdog.onUnexpectedDisable()
        now += 1_000
        watchdog.onUnexpectedDisable()
        now += 500
        assertEquals(1_500L, watchdog.onEnabled())
    }

    @Test
    fun theGraceIsFarLongerThanTheStacksOwnRotationWindow() {
        // The stack disables a connectable set, sets the new address and enables it again within tens of ms.
        assertTrue(AdvertisingWatchdog.GRACE_MS >= 2_000L)
    }

    @Test
    fun aFailedOptionalStartBacksOffInsteadOfRetryingEveryPageTick() {
        assertEquals(2_000L, AdvertisingWatchdog.startFailureBackoffMs(1, tooMany = false))
        assertEquals(4_000L, AdvertisingWatchdog.startFailureBackoffMs(2, tooMany = false))
        assertEquals(32_000L, AdvertisingWatchdog.startFailureBackoffMs(5, tooMany = false))
        assertEquals(60_000L, AdvertisingWatchdog.startFailureBackoffMs(6, tooMany = false))
        assertEquals(60_000L, AdvertisingWatchdog.startFailureBackoffMs(40, tooMany = false))
        assertEquals("no free instance does not change in seconds", 60_000L, AdvertisingWatchdog.startFailureBackoffMs(1, tooMany = true))
    }

    // ----- AdvertisingStart: the start never throws (A-01) -----

    @Test
    fun anOversizeLegacyPayloadIsReportedAsDataTooLargeNotThrown() {
        val thrown = IllegalArgumentException("Legacy advertising data too big")
        assertEquals(AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE, AdvertisingStart.classify(thrown))
        assertEquals(
            AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE,
            AdvertisingStart.classify(IllegalArgumentException("Advertising data too large")),
        )
    }

    @Test
    fun anAdapterThatIsOffIsNotAParameterRefusal() {
        val status = AdvertisingStart.classify(IllegalStateException("BT Adapter is not turned ON"))
        assertEquals(AdvertisingStart.STATUS_ADAPTER_OFF, status)
        assertTrue(AdvertisingStart.isLocal(status))
        assertEquals("Bluetooth is off", AdvertisingStart.describe(status))
    }

    @Test
    fun otherRejectionsAndRuntimeFailuresGetTheirOwnLocalStatus() {
        assertEquals(
            AdvertisingStart.STATUS_BAD_ARGUMENT,
            AdvertisingStart.classify(IllegalArgumentException("Unsupported secondary PHY selected")),
        )
        assertEquals(AdvertisingStart.STATUS_BAD_ARGUMENT, AdvertisingStart.classify(IllegalArgumentException()))
        assertEquals(AdvertisingStart.STATUS_THREW, AdvertisingStart.classify(SecurityException("BLUETOOTH_ADVERTISE")))
        assertEquals(AdvertisingStart.STATUS_THREW, AdvertisingStart.classify(RuntimeException("dead binder")))
    }

    @Test
    fun theStackStatusesAreNeverLocalAndTheLocalOnesNeverCollideWithThem() {
        val stack = listOf(
            AdvertisingSetCallback.ADVERTISE_SUCCESS,
            AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE,
            AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS,
            AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED,
            AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR,
            AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
        )
        stack.forEach { assertTrue("$it", !AdvertisingStart.isLocal(it)) }
        val local = listOf(AdvertisingStart.STATUS_THREW, AdvertisingStart.STATUS_ADAPTER_OFF, AdvertisingStart.STATUS_BAD_ARGUMENT)
        assertEquals(local.size, local.toSet().size)
        local.forEach { assertTrue("$it", AdvertisingStart.isLocal(it) && it !in stack) }
        assertEquals("the existing pseudo status keeps its value", -2, StableAdvertisingSet.START_THREW)
    }

    @Test
    fun everyStatusHasPlainWords() {
        assertEquals("no free advertising slots", AdvertisingStart.describe(AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS))
        assertEquals("the advertisement is too large", AdvertisingStart.describe(AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE))
        assertEquals("timed out", AdvertisingStart.describe(null))
        assertEquals("code 99", AdvertisingStart.describe(99))
    }

    @Test
    fun theStableSetNeverLimitsItselfAndTheTimingRangeIsThePlatformsOwn() {
        assertTrue(AdvertisingStart.isUnlimited(AdvertisingStart.UNLIMITED_DURATION, AdvertisingStart.UNLIMITED_EVENTS))
        assertTrue(!AdvertisingStart.isUnlimited(100, 0))
        assertNull(AdvertisingStart.timingProblem(0, 0))
        assertNull(AdvertisingStart.timingProblem(65535, 255))
        assertTrue(AdvertisingStart.timingProblem(65536, 0)!!.contains("duration"))
        assertTrue(AdvertisingStart.timingProblem(0, 256)!!.contains("maxExtendedAdvertisingEvents"))
        assertTrue(AdvertisingStart.timingProblem(-1, 0) != null)
    }

    @Test
    fun anAdapterOffExceptionDoesNotCountAsTheStackRefusingAHiddenName() {
        // As before: a request that threw is a refusal of the hidden parameters ...
        assertTrue(NonDiscoverableAdvertising.refusedBy(null, threw = true))
        // ... but the start path no longer passes threw for STATUS_ADAPTER_OFF (see StableAdvertisingSet.start), and
        // a plain TOO_MANY or oversize status never was a refusal of the parameters.
        assertTrue(!NonDiscoverableAdvertising.refusedBy(AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS, threw = false))
        assertTrue(!NonDiscoverableAdvertising.refusedBy(AdvertisingStart.STATUS_ADAPTER_OFF, threw = false))
        assertTrue(NonDiscoverableAdvertising.refusedBy(AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED, threw = false))
    }
}
