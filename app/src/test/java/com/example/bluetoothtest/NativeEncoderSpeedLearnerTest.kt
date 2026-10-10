package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEncoderSpeedLearnerTest {
    private fun NativeEncoderSpeedGovernor.feed(frames: Int, ms: Double, nowNs: Long = 0L) {
        repeat(frames) { record(ms, nowNs) }
    }

    @Test
    fun aRememberedFasterPresetIsTheStartAndASlowerOneIsIgnored() {
        assertEquals(10, NativeEncoderSpeedLearner(7, false, NativeEncoderSpeedRecord(10, false)).startCpuUsed)
        // ASUS settles at 6-7 against the table's 7: it still starts exactly at 7 with full tools.
        val asus = NativeEncoderSpeedLearner(7, false, NativeEncoderSpeedRecord(6, false))
        assertEquals(7, asus.startCpuUsed)
        assertFalse(asus.fastTools)
        assertEquals(7, NativeEncoderSpeedLearner(7, false, null).startCpuUsed)
        assertEquals(10, NativeEncoderSpeedLearner(7, false, NativeEncoderSpeedRecord(12, false)).startCpuUsed)
    }

    @Test
    fun theFastToolProfileIsAddedOnlyAfterOverloadAtTheFastestPreset() {
        // A15s at 640x480/15: cpu-used 10 still measured p95 52-64 ms of 66.7 ms.
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 10, fps = 15)
        val learner = NativeEncoderSpeedLearner(7, defaultFastTools = false, remembered = null)
        governor.feed(30, 60.0)
        assertEquals(1, governor.overloadedAtFastestWindows)
        assertFalse(learner.shouldEscalateToFastTools(governor))
        governor.feed(30, 60.0)
        assertTrue(learner.shouldEscalateToFastTools(governor))
        assertTrue(learner.fastTools)
        assertFalse("escalates once", learner.shouldEscalateToFastTools(governor))
    }

    @Test
    fun overloadBelowTheFastestPresetSpeedsUpInsteadOfEscalating() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 7, fps = 15)
        val learner = NativeEncoderSpeedLearner(7, false, null)
        repeat(3) {
            governor.feed(30, 60.0)
            assertFalse(learner.shouldEscalateToFastTools(governor))
        }
        assertEquals(10, governor.cpuUsed)
        assertEquals(0, governor.overloadedAtFastestWindows)
    }

    @Test
    fun aPhoneWithinBudgetNeverEscalates() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 7, fps = 15)
        val learner = NativeEncoderSpeedLearner(7, false, null)
        repeat(20) {
            governor.feed(30, 26.0)
            assertFalse(learner.shouldEscalateToFastTools(governor))
        }
        assertFalse(learner.fastTools)
    }

    @Test
    fun theSettledPresetIsRememberedOnceAndOnlyAfterItHolds() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 10, fps = 15)
        val learner = NativeEncoderSpeedLearner(7, false, null)
        governor.feed(30, 50.0)
        governor.feed(30, 50.0)
        assertNull(learner.settledRecord(governor))
        governor.feed(30, 50.0)
        assertEquals(NativeEncoderSpeedRecord(10, false), learner.settledRecord(governor))
        governor.feed(30, 50.0)
        assertNull("unchanged records are not rewritten", learner.settledRecord(governor))
    }

    @Test
    fun aTrialIsNeverRememberedAsTheSettledPreset() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 8, fps = 15)
        val learner = NativeEncoderSpeedLearner(7, false, null)
        governor.feed(60, 20.0)
        assertEquals(7, governor.cpuUsed)
        assertTrue(governor.trialInProgress)
        assertNull(learner.settledRecord(governor))
    }

    @Test
    fun aLearnedFastProfileIsKeptOnlyWhileItStillNeedsTheFastestPresets() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 10, fps = 15)
        val learner = NativeEncoderSpeedLearner(7, false, NativeEncoderSpeedRecord(10, true))
        assertTrue(learner.fastTools)
        governor.feed(90, 50.0)
        assertNull("still the remembered record; nothing to rewrite", learner.settledRecord(governor))

        val escalated = NativeEncoderSpeedGovernor(initialCpuUsed = 10, fps = 15)
        val learned = NativeEncoderSpeedLearner(7, false, null)
        escalated.feed(60, 60.0)
        assertTrue(learned.shouldEscalateToFastTools(escalated))
        escalated.feed(90, 50.0)
        assertEquals(NativeEncoderSpeedRecord(10, true), learned.settledRecord(escalated))

        // Settled well below the fastest preset: the next session tries the full tool set again.
        val calm = NativeEncoderSpeedGovernor(initialCpuUsed = 7, fps = 15)
        val relearn = NativeEncoderSpeedLearner(7, false, NativeEncoderSpeedRecord(10, true))
        calm.feed(90, 40.0)
        assertEquals(NativeEncoderSpeedRecord(7, false), relearn.settledRecord(calm))
    }

    @Test
    fun theModeTablesFastProfileIsNotStoredAsLearned() {
        val governor = NativeEncoderSpeedGovernor(initialCpuUsed = 10, fps = 30)
        val learner = NativeEncoderSpeedLearner(10, defaultFastTools = true, remembered = null)
        assertTrue(learner.fastTools)
        assertFalse(learner.fastToolsLearned)
        governor.feed(90, 25.0)
        assertEquals(NativeEncoderSpeedRecord(10, false), learner.settledRecord(governor))
    }

    @Test
    fun speedKeysSeparateGeometryAndFrameRate() {
        assertEquals("640x480@15", nativeEncoderSpeedKey(640, 480, 15))
        assertTrue(nativeEncoderSpeedKey(640, 480, 15) != nativeEncoderSpeedKey(640, 480, 20))
    }
}
