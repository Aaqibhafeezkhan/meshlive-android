package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An encoder that rejects KEY_TEMPORAL_LAYERING (or an init-fallback chain that drops the key) used to leave a
 * "layers on" benchmark running single-layer without a layering line. The decision is pure; the encoder source is
 * guarded so both start paths report, and a benchmark launch refuses instead of measuring no layering.
 */
class TemporalLayeringReportTest {
    private val schema = "android.generic.2"

    private fun source(name: String): String {
        val file = listOf(
            java.io.File("src/main/java/com/example/bluetoothtest/$name"),
            java.io.File("app/src/main/java/com/example/bluetoothtest/$name"),
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun theDecisionRefusesOnlyABenchmarkLaunchThatLostRequestedLayers() {
        val a = TemporalLayeringReport.Action.values().associateBy { it.name }
        assertEquals(a["NOT_REQUESTED"], TemporalLayeringReport.action(null, null, benchmarkLaunch = true))
        assertEquals(a["NOT_REQUESTED"], TemporalLayeringReport.action(null, null, benchmarkLaunch = false))
        assertEquals(a["APPLIED"], TemporalLayeringReport.action(schema, schema, benchmarkLaunch = true))
        assertEquals(a["APPLIED"], TemporalLayeringReport.action(schema, schema, benchmarkLaunch = false))
        assertEquals(a["REFUSE"], TemporalLayeringReport.action(schema, null, benchmarkLaunch = true))
        assertEquals(a["SINGLE_LAYER_BEST_EFFORT"], TemporalLayeringReport.action(schema, null, benchmarkLaunch = false))
    }

    @Test
    fun theLineStatesRequestedAndEffectiveInTheAgreedShape() {
        assertEquals(
            "BENCHMARK temporal_layering: requested=android.generic.2 | effective=none | " +
                "encoder=c2.exynos.vp9.encoder | reason=configure_rejected: bad / key",
            TemporalLayeringReport.line(schema, null, "c2.exynos.vp9.encoder", "configure_rejected: bad | key"),
        )
        val none = TemporalLayeringReport.line(null, null, "enc", "not_requested")
        assertEquals("BENCHMARK temporal_layering: requested=none | effective=none | encoder=enc | reason=not_requested", none)
        val applied = TemporalLayeringReport.line(schema, schema, "enc", "configure_accepted")
        assertTrue(applied, "requested=$schema | effective=$schema | encoder=enc | reason=configure_accepted" in applied)
    }

    @Test
    fun reasonTextCannotBreakTheFieldSeparatorOrRunOnForever() {
        val line = TemporalLayeringReport.line(schema, null, "enc", "a | b\nc " + "x".repeat(400))
        assertEquals(3, line.count { it == '|' })
        assertFalse(line.contains('\n'))
        assertTrue(line.length < 400)
    }

    @Test
    fun theRefusalLineIsTheSetupRefusalWithTheAgreedReason() {
        val line = BenchmarkSetupRefusal.temporalLayeringRefusalLine(schema, "c2.exynos.vp9.encoder", "configure_rejected: x")
        assertTrue(line, line.startsWith("BENCHMARK setup_refused: reason=temporal_layering_unsupported"))
        assertTrue(line, "requested=$schema" in line && "effective=none" in line && "encoder=c2.exynos.vp9.encoder" in line)
    }

    @Test
    fun theInitFallbackReasonNamesTheDroppedKey() {
        assertEquals("not_requested", TemporalLayeringReport.initFallbackReason(false, false, null))
        assertEquals("configure_accepted", TemporalLayeringReport.initFallbackReason(true, false, null))
        assertEquals("init_fallback_dropped_key", TemporalLayeringReport.initFallbackReason(true, true, null))
        assertEquals(
            "init_fallback_dropped_key: CodecException",
            TemporalLayeringReport.initFallbackReason(true, true, "CodecException"),
        )
    }

    @Test
    fun theInitFallbackPathReportsADroppedKeyAndTheSingleAttemptPathReportsARejection() {
        val encoder = source("CameraVideoEncoder.kt")
        val fallback = encoder.substringAfter("private fun startWithInitFallback() {").substringBefore("private fun performEncoderInit")
        assertTrue(fallback.contains("EncoderOptionalKey.TEMPORAL_LAYERING in requestedKeys"))
        assertTrue(fallback.contains("EncoderOptionalKey.TEMPORAL_LAYERING in effective.dropped"))
        assertTrue(fallback.contains("reportTemporalLayering("))
        assertTrue(fallback.contains("TemporalLayeringReport.initFallbackReason("))
        // The line is emitted after the chain finishes, before any status text about the fallback.
        assertTrue(fallback.indexOf("reportTemporalLayering(") < fallback.indexOf("onStatus("))

        val single = encoder.substringAfter("private fun createConfigureStartSingle(").substringBefore("private fun startWithInitFallback")
        assertTrue(single.contains("reportTemporalLayering("))
        // A rejection never reaches the single-layer retry without going through the report (and its refusal).
        assertTrue(single.indexOf("reportTemporalLayering(") < single.lastIndexOf("codec = MediaCodec.createByCodecName(activeEncoderName)"))
        assertTrue(encoder.contains("error is TemporalLayeringRefused"))

        val report = encoder.substringAfter("private fun reportTemporalLayering(").substringBefore("private fun releaseFailedCodec")
        assertTrue(report.contains("DebugTunables.benchmarkLaunch"))
        assertTrue(report.contains("TemporalLayeringReport.Action.REFUSE"))
        assertTrue(report.contains("throw TemporalLayeringRefused("))
    }
}
