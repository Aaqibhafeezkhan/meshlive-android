package com.example.bluetoothtest

import android.app.ActivityManager.RunningAppProcessInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdpfHintControllerTest {
    private class FakeSession : HintSessionHandle {
        val targets = mutableListOf<Long>()
        val reports = mutableListOf<Long>()
        var closed = false
        override fun updateTargetNanos(targetNs: Long) {
            targets += targetNs
        }

        override fun reportActualNanos(actualNs: Long) {
            reports += actualNs
        }

        override fun close() {
            closed = true
        }
    }

    private class Env {
        var switchOn = true
        var importance: Int? = RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        var supported = true
        val created = mutableListOf<FakeSession>()
        val createdTargets = mutableListOf<Long>()
        val backend = HintSessionBackend { _, target ->
            createdTargets += target
            if (!supported) null else FakeSession().also { created += it }
        }

        fun controller(sdk: Int = 34) = AdpfHintController(backend, { switchOn }, { importance }, sdk)
    }

    private val tids = intArrayOf(1, 2)

    @Test
    fun onlyAVisibleProcessMayHaveASession() {
        assertTrue(AdpfHint.sessionAllowed(true, 31, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(AdpfHint.sessionAllowed(true, 31, RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        // The screen-off relay or call is a foreground service: no session.
        assertFalse(AdpfHint.sessionAllowed(true, 34, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(AdpfHint.sessionAllowed(true, 34, RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertFalse(AdpfHint.sessionAllowed(false, 34, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertFalse(AdpfHint.sessionAllowed(true, 30, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
    }

    @Test
    fun targetIsOneFramePeriod() {
        assertEquals(33_333_333L, AdpfHint.targetNanos(30))
        assertEquals(1_000_000_000L, AdpfHint.targetNanos(0))
    }

    @Test
    fun noSessionWhileTheSwitchIsOff() {
        val env = Env().apply { switchOn = false }
        val controller = env.controller()
        controller.update(tids, 30, 10_000_000)
        assertFalse(controller.hasSession)
        assertEquals(0, env.created.size)
    }

    @Test
    fun createsOnceReportsEachFrameAndFollowsFpsChanges() {
        val env = Env()
        val controller = env.controller()
        controller.update(tids, 30, 12_000_000)
        controller.update(tids, 30, 14_000_000)
        controller.update(tids, 15, 20_000_000)
        assertEquals(1, env.created.size)
        assertEquals(listOf(33_333_333L), env.createdTargets)
        assertEquals(listOf(12_000_000L, 14_000_000L, 20_000_000L), env.created[0].reports)
        assertEquals(listOf(66_666_666L), env.created[0].targets)
        assertEquals(3L, controller.reports)
    }

    @Test
    fun theSessionClosesWhenTheProcessLeavesTheScreenAndReopensOnReturn() {
        val env = Env()
        val controller = env.controller()
        controller.update(tids, 30, 1_000_000)
        env.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        controller.update(tids, 30, 1_000_000)
        assertTrue(env.created[0].closed)
        assertFalse(controller.hasSession)
        env.importance = RunningAppProcessInfo.IMPORTANCE_VISIBLE
        controller.update(tids, 30, 1_000_000)
        assertEquals(2, env.created.size)
    }

    @Test
    fun unsupportedPhonesTryOnceAndStayQuiet() {
        val env = Env().apply { supported = false }
        val controller = env.controller()
        repeat(5) { controller.update(tids, 30, 1_000_000) }
        assertEquals(1, env.createdTargets.size)
        assertTrue(controller.unsupported)
        assertFalse(controller.hasSession)
    }

    @Test
    fun anUnreadableImportanceOrEmptyThreadListCreatesNothing() {
        val env = Env().apply { importance = null }
        val controller = env.controller()
        controller.update(tids, 30, 1_000_000)
        assertEquals(0, env.created.size)
        env.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        controller.update(intArrayOf(), 30, 1_000_000)
        assertEquals(0, env.created.size)
        assertFalse(controller.unsupported)
    }

    @Test
    fun aThrowingBackendIsTreatedAsUnsupported() {
        val controller = AdpfHintController(
            { _, _ -> error("vendor failure") },
            { true },
            { RunningAppProcessInfo.IMPORTANCE_FOREGROUND },
            34,
        )
        controller.update(tids, 30, 1_000_000)
        assertTrue(controller.unsupported)
    }

    @Test
    fun switchDefaultsOff() {
        assertFalse(ThermalSwitches.adpfHint)
    }
}
