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

}
