package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.BufferUnderflowException

class CameraFpsRangesTest {
    private val typical = listOf(FpsRange(15, 15), FpsRange(24, 24), FpsRange(15, 30), FpsRange(30, 30), FpsRange(7, 60), FpsRange(60, 60))

    @Test
    fun theFormerRuleIsUnchangedHighestCeilingThenHighestFloor() {
        assertEquals(FpsRange(30, 30), CameraFpsRanges.select(typical, 30))
        assertEquals(FpsRange(24, 24), CameraFpsRanges.select(typical, 29))
        assertEquals(FpsRange(60, 60), CameraFpsRanges.select(typical, 60))
        assertEquals(FpsRange(15, 30), CameraFpsRanges.select(listOf(FpsRange(15, 30), FpsRange(5, 30)), 30))
    }

    @Test
    fun noRangeAtOrUnderTheLimitTakesTheLowestCeilingNeverOneThatRunsFaster() {
        val ranges = listOf(FpsRange(7, 30), FpsRange(15, 30), FpsRange(30, 30))
        assertEquals(FpsRange(30, 30), CameraFpsRanges.select(ranges, 7))
        assertNull(CameraFpsRanges.select(emptyList(), 30))
    }

    @Test
    fun legacyLowestLowerRuleOnlyChangesTheFloorAndIsOffByDefault() {
        val legacy = listOf(FpsRange(15, 30), FpsRange(30, 30), FpsRange(7, 30), FpsRange(24, 24))
        assertEquals(FpsRange(30, 30), CameraFpsRanges.select(legacy, 30))
        assertEquals(FpsRange(7, 30), CameraFpsRanges.select(legacy, 30, legacyLowestLower = true))
        // A lower ceiling is still never exceeded.
        assertEquals(FpsRange(24, 24), CameraFpsRanges.select(legacy, 25, legacyLowestLower = true))
    }

    @Test
    fun effectiveMaxFpsIsCappedByTheSlowestConfiguredStream() {
        val sixty = FpsRange(60, 60)
        // A 33.3 ms minimum frame duration (30 FPS) on one stream caps a 60 FPS range.
        assertEquals(30, CameraFpsRanges.effectiveMaxFps(sixty, listOf(16_666_666L, 33_333_333L)))
        assertEquals(60, CameraFpsRanges.effectiveMaxFps(sixty, listOf(16_666_666L)))
        assertEquals(60, CameraFpsRanges.effectiveMaxFps(sixty, listOf(0L, -1L)))
        assertEquals(30, CameraFpsRanges.effectiveMaxFps(null, listOf(33_333_333L)))
        assertNull(CameraFpsRanges.effectiveMaxFps(null, emptyList()))
    }

    @Test
    fun hardwareLevelsAreNamedNeverComparedNumerically() {
        assertEquals("LIMITED", CameraLevelCodes.name(0))
        assertEquals("FULL", CameraLevelCodes.name(1))
        assertEquals("LEGACY", CameraLevelCodes.name(2))
        assertEquals("LEVEL_3", CameraLevelCodes.name(3))
        assertEquals("EXTERNAL", CameraLevelCodes.name(4))
        assertEquals("unknown", CameraLevelCodes.name(null))
        assertTrue(CameraLevelCodes.isLegacy(2))
        // LEGACY (2) is numerically above FULL (1) but is the LOWEST capability: never use > or <.
        assertFalse(CameraLevelCodes.isLegacy(1))
        assertFalse(CameraLevelCodes.isLegacy(null))
    }

    @Test
    fun throwingCharacteristicReadsBecomeUnknown() {
        assertNull(safeCameraRead<Int>("assertion") { throw AssertionError("CONTROL_ZOOM_RATIO_RANGE") })
        assertNull(safeCameraRead<Int>("underflow") { throw BufferUnderflowException() })
        assertNull(safeCameraRead<Int>("npe") { throw NullPointerException() })
        assertEquals(7, safeCameraRead("ok") { 7 })
        assertNull(safeCameraRead<Int>("absent") { null })
    }

    @Test
    fun cameraIdLookupSkipsIdsItCannotReadAndKeepsTheFirstMatch() {
        val ids = arrayOf("0", "1", "2", "3")
        val facing = mapOf("0" to 1, "1" to 0, "3" to 0)
        assertEquals("1", pickCameraId(ids, 0) { id -> facing[id] ?: throw AssertionError("unreadable $id") })
        assertEquals("0", pickCameraId(ids, 1) { id -> facing[id] ?: throw AssertionError("unreadable $id") })
        assertNull(pickCameraId(ids, 2) { facing[it] })
        // Rear camera stays the first back-facing logical id (physical ids are never targeted).
        assertEquals("2", pickCameraId(arrayOf("2", "3"), 0) { 0 })
    }

    @Test
    fun streamPlansDropTheReferenceThenThePreviewAndKeepTheEncoder() {
        val full = CameraStreamPlanner.plans(hasPreview = true, hasReference = true)
        assertEquals(listOf("preview+encoder+reference", "preview+encoder", "encoder"), full.map { it.streams })
        assertEquals(listOf("full", "without_reference", "without_preview"), full.map { it.label })
        assertEquals(listOf("preview+encoder", "encoder"), CameraStreamPlanner.plans(true, false).map { it.streams })
        assertEquals(listOf("encoder+reference", "encoder"), CameraStreamPlanner.plans(false, true).map { it.streams })
        assertEquals(listOf("encoder"), CameraStreamPlanner.plans(false, false).map { it.streams })
        assertTrue(full.all { "encoder" in it.streams })
    }

    @Test
    fun anUnsupportedSetMovesToTheNextPlanAndIsReportedRequestedVersusEffective() {
        val plans = CameraStreamPlanner.plans(true, true)
        val supportedOnlyWithoutReference = { plan: CameraStreamPlan -> if (plan.reference) false else true }
        val chosen = CameraStreamPlanner.firstSupported(plans, supportedOnlyWithoutReference)
        assertEquals("preview+encoder", chosen?.streams)
        val note = CameraStreamPlanner.effectiveNote(plans.first(), chosen)
        assertTrue(note, "capability failure" in note)
        assertTrue(note, "requested=preview+encoder+reference" in note && "effective=preview+encoder" in note)
        // An unknown answer (null) is not a rejection.
        assertEquals(plans.first(), CameraStreamPlanner.firstSupported(plans) { null })
        assertNull(CameraStreamPlanner.firstSupported(plans) { false })
        assertTrue("effective=none" in CameraStreamPlanner.effectiveNote(plans.first(), null))
        assertEquals(
            "requested=preview+encoder+reference effective=preview+encoder+reference",
            CameraStreamPlanner.effectiveNote(plans.first(), plans.first()),
        )
    }
}
