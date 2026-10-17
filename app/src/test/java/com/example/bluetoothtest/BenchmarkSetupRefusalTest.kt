package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lane10: a requested benchmark encoder or bitrate allocation that the phone lacks (M31 cbr on OMX.Exynos.VP9, A15s
 * vp9) used to crash the activity through require(). It is now one `setup_refused` line and a clean finish, and
 * nothing is substituted. The raw-fixture geometry refusal (640x360 fixture, encoder at 640x480 on the ASUS and A15s)
 * is visible the same way.
 */
class BenchmarkSetupRefusalTest {
    @Test
    fun theLineNamesTheReasonAndEveryDetail() {
        assertEquals(
            "BENCHMARK setup_refused: reason=encoder_unavailable | requested=vp9",
            BenchmarkSetupRefusal.codecRefusalLine("vp9"),
        )
    }

    @Test
    fun theRawFixtureGeometryRefusalCarriesRequestedAndEffective() {
        val line = BenchmarkSetupRefusal.rawFixtureGeometryLine(640, 360, 640, 480, "no reinterpretation or rescaling")
        assertEquals(
            "BENCHMARK setup_refused: reason=raw_fixture_geometry | requested=640x360 | effective=640x480 | " +
                "detail=no reinterpretation or rescaling",
            line,
        )
    }

    @Test
    fun anEncoderThatCannotEncodeTheRawFixtureSizeIsRefusedNamingBoth() {
        assertEquals(
            "BENCHMARK setup_refused: reason=raw_fixture_encoder_size | requested=640x360 | " +
                "encoder=c2.android.vp9.encoder | detail=encoder does not support the declared size; no substitution",
            BenchmarkSetupRefusal.rawEncoderSizeLine("c2.android.vp9.encoder", 640, 360),
        )
    }

    @Test
    fun codecRequestsMatchFamiliesAndExactEncoderNames() {
        assertTrue(BenchmarkSetupRefusal.codecMatches("AV1", isAv1 = true, isVp9 = false, encoderName = "libaom-native"))
        assertTrue(BenchmarkSetupRefusal.codecMatches("vp9", isAv1 = false, isVp9 = true, encoderName = "OMX.Exynos.VP9.Encoder"))
        assertFalse(BenchmarkSetupRefusal.codecMatches("vp9", isAv1 = true, isVp9 = false, encoderName = "libaom-native"))
        assertTrue(BenchmarkSetupRefusal.codecMatches("omx.exynos.vp9.encoder", false, true, "OMX.Exynos.VP9.Encoder"))
        assertFalse(BenchmarkSetupRefusal.codecMatches("omx.other", false, true, "OMX.Exynos.VP9.Encoder"))
    }

    @Test
    fun anUnavailableAllocationIsRefusedWithoutSubstitution() {
        val offered = listOf(EncoderRateControl.AUTOMATIC, EncoderRateControl.VBR)
        val line = BenchmarkSetupRefusal.rateControlRefusalLine("cbr", "OMX.Exynos.VP9.Encoder", offered)
        assertNotNull(line)
        assertEquals(
            "BENCHMARK setup_refused: reason=rate_control_unavailable | requested=CBR | " +
                "encoder=OMX.Exynos.VP9.Encoder | available=AUTOMATIC,VBR",
            line,
        )
        assertNull(BenchmarkSetupRefusal.rateControlRefusalLine("vbr", "OMX.Exynos.VP9.Encoder", offered))
        assertNull(BenchmarkSetupRefusal.rateControlRefusalLine("AUTOMATIC", "x", offered))
    }

    @Test
    fun anUnknownAllocationNameOrMissingEncoderIsRefusedNotThrown() {
        assertEquals(
            "BENCHMARK setup_refused: reason=rate_control_unknown | requested=turbo",
            BenchmarkSetupRefusal.rateControlRefusalLine("turbo", "x", emptyList()),
        )
        assertEquals(
            "BENCHMARK setup_refused: reason=rate_control_no_encoder | requested=cbr",
            BenchmarkSetupRefusal.rateControlRefusalLine("cbr", null, emptyList()),
        )
        assertEquals(EncoderRateControl.CBR_FD, BenchmarkSetupRefusal.rateControlFor("cbr-fd"))
        assertNull(BenchmarkSetupRefusal.rateControlFor("turbo"))
    }

    @Test
    fun theLaunchPathNoLongerThrowsForAnUnavailableRequest() {
        val file = listOf(
            File("src/main/java/com/example/bluetoothtest/MainActivity.kt"),
            File("app/src/main/java/com/example/bluetoothtest/MainActivity.kt"),
        ).first { it.exists() }
        val source = file.readText().replace("\r\n", "\n")
        val body = source.substringAfter("private fun applyDebugCodecSelection()").substringBefore("private fun usesBroadcastDiscovery")
        assertFalse(body.contains("require("))
        assertFalse(body.contains("requireNotNull("))
        assertFalse(body.contains("error("))
        assertTrue(body.contains("refuseDebugBenchmarkSetup("))
        assertTrue(source.substringAfter("private fun refuseDebugBenchmarkSetup").substringBefore("private fun applyDebugCodecSelection")
            .contains("finishAndRemoveTask()"))
    }

    @Test
    fun theRawRateRefusalNamesSizeRateAndEncoderWithoutSubstitution() {
        assertEquals(
            "BENCHMARK setup_refused: reason=raw_fixture_encoder_rate | requested=640x360@60 | " +
                "encoder=c2.android.vp9.encoder | detail=encoder does not support the declared size at this frame rate; no substitution",
            BenchmarkSetupRefusal.rawEncoderRateLine("c2.android.vp9.encoder", 640, 360, 60),
        )
    }

    @Test
    fun theWatchdogFiresOnlyForALiveLaunchStillWaitingToStart() {
        assertTrue(BenchmarkSetupRefusal.stallWatchdogShouldFire(autoStartPending = true, alreadyRefused = false, activityGone = false))
        assertFalse(BenchmarkSetupRefusal.stallWatchdogShouldFire(false, false, false)) // the stream started
        assertFalse(BenchmarkSetupRefusal.stallWatchdogShouldFire(true, true, false)) // already refused
        assertFalse(BenchmarkSetupRefusal.stallWatchdogShouldFire(true, false, true)) // activity finishing
        assertEquals(5_000L, BenchmarkSetupRefusal.STALL_WATCHDOG_MS)
    }

    @Test
    fun aLocalGateThatIsFalseRefusesButAMissingPeerOnlyWaits() {
        assertEquals(BenchmarkSetupRefusal.StallVerdict.REFUSE, BenchmarkSetupRefusal.stallVerdict(localGatesOk = false, peersReady = true))
        assertEquals(BenchmarkSetupRefusal.StallVerdict.REFUSE, BenchmarkSetupRefusal.stallVerdict(false, false))
        assertEquals(BenchmarkSetupRefusal.StallVerdict.WAIT, BenchmarkSetupRefusal.stallVerdict(true, false))
        // Everything true yet still pending: a start that never ran is a stall too.
        assertEquals(BenchmarkSetupRefusal.StallVerdict.REFUSE, BenchmarkSetupRefusal.stallVerdict(true, true))
    }

    @Test
    fun theStallLinesNameEveryGateTerm() {
        val gates = listOf<Pair<String, Any?>>(
            "role" to "SEND", "canStart" to false, "destinationReady" to true, "codecs" to 3, "controlsIdle" to true,
            "modeSupported" to false, "bluetooth" to true, "transport" to "BLE_L2CAP:true", "permissions" to true, "thermal" to true,
        )
        val stalled = BenchmarkSetupRefusal.setupStalledLine(BenchmarkSetupRefusal.StallVerdict.REFUSE, gates)
        assertTrue(stalled, stalled.startsWith("BENCHMARK setup_stalled: role=SEND | canStart=false | destinationReady=true | codecs=3 | controlsIdle=true | modeSupported=false | bluetooth=true | transport=BLE_L2CAP:true | permissions=true | thermal=true"))
        assertTrue(BenchmarkSetupRefusal.setupStalledLine(BenchmarkSetupRefusal.StallVerdict.WAIT, gates).startsWith("BENCHMARK setup_waiting: "))
    }

    @Test
    fun theWatchdogIsArmedOnBothDebugAutoStartLaunchScreensAndTheRawRateIsChecked() {
        val file = File("src/main/java/com/example/bluetoothtest/MainActivity.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/bluetoothtest/MainActivity.kt")
        val source = file.readText().replace("\r\n", "\n")
        assertTrue(source.contains("showStreamCamera()\n            armDebugSetupWatchdog()"))
        assertTrue(source.contains("ensurePermissionsAndRefresh()\n            armDebugSetupWatchdog()"))
        val setup = source.substringAfter("private fun applyDebugCodecSelection()").substringBefore("private fun usesBroadcastDiscovery")
        assertTrue(setup.contains("encoderAllowsRate(") && setup.contains("rawEncoderRateLine("))
    }
}
