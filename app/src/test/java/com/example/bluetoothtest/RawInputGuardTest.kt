package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane 11 (build ae00937): a raw fixture launched with temporal layers was reported as "ignored, camera used".
 * The raw input was honoured; the encoder never started (no receiver connected). These tests keep the camera
 * off whenever a raw fixture is active, with or without temporal layers, and keep the launch evidence in place.
 */
class RawInputGuardTest {
    private fun source(name: String): String {
        val file = listOf(
            java.io.File("src/main/java/com/example/bluetoothtest/$name"),
            java.io.File("app/src/main/java/com/example/bluetoothtest/$name"),
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun theCameraMayStartOnlyWithoutARawFixture() {
        assertTrue(RawInputGuard.cameraMayStart(rawInputActive = false))
        assertFalse(RawInputGuard.cameraMayStart(rawInputActive = true))
    }

    @Test
    fun theLaunchLineAcceptsRawWithEveryTemporalLayerCombination() {
        for (layers in listOf(false, true)) for (hardware in listOf(false, true)) for (thinning in listOf(false, true)) {
            val line = RawInputGuard.launchLine("benchmark-raw-f3cf.yuv", layers, hardware, thinning)
            assertTrue(line, line.startsWith("BENCHMARK raw_input: status=ACCEPTED | camera=inactive | file=benchmark-raw-f3cf.yuv"))
            assertTrue(line, "temporalLayers=$layers | hardwareTemporalLayers=$hardware | relayChildThinning=$thinning" in line)
            assertFalse(line, "setup_refused" in line)
        }
    }

    @Test
    fun noEncoderLayeringBranchDependsOnTheRawFixture() {
        val encoder = source("CameraVideoEncoder.kt")
        val mediaCodecDecision = encoder.substringAfter("temporalLayeringRequested = EncoderOptionalKey.TEMPORAL_LAYERING !in dropped")
            .substringBefore("if (temporalLayeringRequested)")
        assertTrue(mediaCodecDecision.contains("experimentalTemporalLayers"))
        assertFalse(mediaCodecDecision, mediaCodecDecision.contains("debugRawInputFile"))
        assertTrue(encoder.contains("nativeTemporalLayers = experimentalTemporalLayers\n"))
        assertFalse(encoder.contains("nativeTemporalLayers = experimentalTemporalLayers && "))
    }

    @Test
    fun theEncoderOpensTheCameraOnlyThroughTheGuard() {
        val encoder = source("CameraVideoEncoder.kt")
        val start = encoder.substringAfter("fun start() {").substringBefore("openedCameraId = selectedCameraId")
        assertTrue(start.contains("RawInputGuard.cameraMayStart(debugRawInputFile != null)"))
        assertTrue(start.indexOf("RawInputGuard.cameraMayStart") in 0 until start.indexOf("cameraManager.cameraIdFor"))
        // The raw feed starts before any camera open call of start().
        val afterEncoder = encoder.substringAfter("fun start() {")
        assertTrue(afterEncoder.indexOf("startRawInput(debugRawInputFile)") in 0 until afterEncoder.indexOf("cameraManager.openCamera("))
    }

    @Test
    fun everyActivityEncoderBuildCarriesTheRawFixtureOrIsGuarded() {
        val main = source("MainActivity.kt")
        val starts = Regex("CameraVideoEncoder\\(").findAll(main).map { it.range.first }.toList()
        assertEquals(4, starts.size)
        for (start in starts) {
            val call = main.substring(start, minOf(main.length, start + 2500))
            val callBody = call.substringBefore("\n        )\n")
            if ("debugRawInputFile = debugRawInputFile" in callBody) continue
            val function = main.substring(main.lastIndexOf("    private fun ", start), start)
            assertTrue(function.take(80), function.startsWith("    private fun restartStreamCamera("))
            assertTrue(function.contains("RawInputGuard.cameraMayStart(debugRawInputFile != null)"))
        }
    }

    @Test
    fun theIdleCameraPreviewHonoursTheGuard() {
        val main = source("MainActivity.kt")
        assertEquals(1, Regex("= CameraPreview\\(").findAll(main).count())
        val preview = main.substringAfter("private fun startIdleCameraPreview()").substringBefore("newPreview = CameraPreview(")
        assertTrue(preview.contains("!RawInputGuard.cameraMayStart(debugRawInputFile != null)"))
    }

    @Test
    fun theLaunchLogsRawInputBeforeAnyEarlyReturn() {
        val main = source("MainActivity.kt")
        val launch = main.substringAfter("private fun applyDebugLaunchConfiguration()")
        val line = launch.indexOf("RawInputGuard.launchLine(")
        val earlyReturn = launch.indexOf("requestedTransportExperiment == null && !reachableWhileBrowsing")
        assertTrue(line in 0 until earlyReturn)
        // The temporal-layer switches the line reports are applied before the launch configuration runs.
        assertTrue(main.indexOf("applyExperimentalTemporalLayers()\n") in 0 until main.indexOf("        applyDebugLaunchConfiguration()\n"))
    }
}
