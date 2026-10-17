package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWakeLockTest {
    private class FakeLock : WakeLockHandle {
        override var isHeld = false
        var acquires = 0
        var releases = 0
        var lastTimeout = 0L
        override fun acquire(timeoutMs: Long) { isHeld = true; acquires++; lastTimeout = timeoutMs }
        override fun release() { isHeld = false; releases++ }
    }

    private class MemoryStore : WakeLockBudget.Store {
        override var windowStartMs = 0L
        override var heldMs = 0L
    }

    private class Rig(capMinutes: Long = 100) {
        var now = 1_000_000L
        var enabled = true
        var screenOn = false
        var hopLive = true
        val lock = FakeLock()
        val store = MemoryStore()
        val log = mutableListOf<String>()
        val controller = SessionWakeLockController(
            lock = lock,
            budget = WakeLockBudget(store, { capMinutes * 60_000L }),
            enabled = { enabled },
            screenInteractive = { screenOn },
            hopLive = { hopLive },
            clock = { now },
            log = { log += it },
        )

        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun policyHoldsOnlyForAScreenOffLiveHopWithinBudget() {
        assertEquals(WakeLockDecision.HOLD, SessionWakeLockPolicy.decide(true, false, true, 1L))
        assertEquals(WakeLockDecision.RELEASE_DISABLED, SessionWakeLockPolicy.decide(false, false, true, 1L))
        assertEquals(WakeLockDecision.RELEASE_SCREEN_ON, SessionWakeLockPolicy.decide(true, true, true, 1L))
        assertEquals(WakeLockDecision.RELEASE_IDLE, SessionWakeLockPolicy.decide(true, false, false, 1L))
        assertEquals(WakeLockDecision.RELEASE_BUDGET, SessionWakeLockPolicy.decide(true, false, true, 0L))
    }

    @Test
    fun theLockIsAcquiredWithATimeoutAndRefreshedEveryTick() {
        val rig = Rig()
        assertEquals(WakeLockDecision.HOLD, rig.controller.tick())
        assertTrue(rig.lock.isHeld)
        assertEquals(SessionWakeLockController.LOCK_TIMEOUT_MS, rig.lock.lastTimeout)
        rig.advance(SessionWakeLockController.RENEW_MS)
        rig.controller.tick()
        assertEquals(2, rig.lock.acquires) // renewed, never stacked: the real lock is not reference counted
        assertEquals(0, rig.lock.releases)
        assertEquals(1, rig.log.count { it.contains("state=acquired") })
    }

    @Test
    fun theScreenComingOnReleasesAndTheScreenGoingOffAcquiresAgain() {
        val rig = Rig()
        rig.controller.tick()
        rig.advance(60_000)
        rig.screenOn = true
        assertEquals(WakeLockDecision.RELEASE_SCREEN_ON, rig.controller.tick())
        assertFalse(rig.lock.isHeld)
        rig.advance(60_000)
        rig.screenOn = false
        rig.controller.tick()
        assertTrue(rig.lock.isHeld)
        assertEquals(1, rig.lock.releases)
    }

    @Test
    fun idleAvailabilityNeverHoldsTheLock() {
        val rig = Rig()
        rig.hopLive = false
        assertEquals(WakeLockDecision.RELEASE_IDLE, rig.controller.tick())
        assertFalse(rig.lock.isHeld)
        assertEquals(0, rig.lock.acquires)
    }

    @Test
    fun theSwitchOffReleasesAtTheNextTick() {
        val rig = Rig()
        rig.controller.tick()
        rig.enabled = false
        rig.advance(60_000)
        assertEquals(WakeLockDecision.RELEASE_DISABLED, rig.controller.tick())
        assertFalse(rig.lock.isHeld)
    }

    @Test
    fun heldTimeIsCountedAndTheDailyBudgetStopsTheLock() {
        val rig = Rig(capMinutes = 3)
        rig.controller.tick()
        repeat(2) {
            rig.advance(60_000)
            assertEquals(WakeLockDecision.HOLD, rig.controller.tick())
        }
        rig.advance(60_000)
        assertEquals(WakeLockDecision.RELEASE_BUDGET, rig.controller.tick())
        assertFalse(rig.lock.isHeld)
        assertEquals(3 * 60_000L, rig.store.heldMs)
        assertTrue(rig.log.any { it.contains("reason=release_budget") })
        // Still over budget a minute later: stays released.
        rig.advance(60_000)
        assertEquals(WakeLockDecision.RELEASE_BUDGET, rig.controller.tick())
        assertEquals(1, rig.lock.releases)
    }

    @Test
    fun theBudgetWindowResetsAfterADay() {
        val rig = Rig(capMinutes = 1)
        rig.controller.tick()
        rig.advance(60_000)
        assertEquals(WakeLockDecision.RELEASE_BUDGET, rig.controller.tick())
        rig.advance(WakeLockBudget.DAY_MS)
        assertEquals(WakeLockDecision.HOLD, rig.controller.tick())
    }

    @Test
    fun aCapOfZeroMeansNoCap() {
        val rig = Rig(capMinutes = 0)
        rig.controller.tick()
        rig.advance(10L * 60 * 60 * 1000)
        assertEquals(WakeLockDecision.HOLD, rig.controller.tick())
    }

    @Test
    fun stopReleasesAndAccountsTheLastPartialInterval() {
        val rig = Rig()
        rig.controller.tick()
        rig.advance(30_000)
        rig.controller.stop("service_stopped")
        assertFalse(rig.lock.isHeld)
        assertEquals(30_000L, rig.store.heldMs)
        assertNull(rig.controller.lastDecision)
        assertTrue(rig.log.last().contains("reason=service_stopped"))
        // A second stop is harmless.
        rig.controller.stop()
        assertEquals(1, rig.lock.releases)
    }

    @Test
    fun theBudgetSurvivesAnewControllerThroughItsStore() {
        val store = MemoryStore()
        val first = WakeLockBudget(store, { 60_000L * 10 })
        first.add(1_000L, 8 * 60_000L)
        val second = WakeLockBudget(store, { 60_000L * 10 })
        assertEquals(2 * 60_000L, second.remainingMs(2_000L))
    }

    @Test
    fun theSwitchIsOffByDefaultAndTheDefaultCapIsBelowThePlayThreshold() {
        assertFalse(SwitchDefaults.SESSION_WAKE_LOCK)
        assertFalse(ServiceSwitches.sessionWakeLock)
        assertTrue(SwitchDefaults.SESSION_WAKE_LOCK_DAILY_CAP_MIN < 120)
    }
}
