package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EncoderBenchSpecTest {
    private val base: Map<String, Any?> = mapOf(
        "test_encoder_bench" to true,
        "test_raw_yuv" to "/sdcard/Android/data/app/files/benchmark-raw-abc.yuv",
        "test_raw_width" to 640,
        "test_raw_height" to 360,
    )

    private fun request(vararg extra: Pair<String, Any?>): EncoderBenchRequest {
        val parsed = EncoderBenchSpec.parse(base + extra)
        assertEquals(emptyList<String>(), parsed.issues)
        return checkNotNull(parsed.request)
    }

    private fun caps(
        name: String = "c2.test.av1.encoder",
        mime: String = EncoderBenchSpec.MIME_AV1,
        native: Boolean = false,
        modes: Set<Int> = setOf(1, 2),
        default: Int? = 2,
        size: Boolean? = true,
        intra: Boolean? = true,
    ) = EncoderBenchCaps(name, mime, hardware = !native, nativeLibaom = native, supportedModes = modes,
        defaultMode = default, sizeAndRateSupported = size, intraRefreshFeature = intra)

    @After
    fun reset() {
        EncoderBenchGuard.deactivate()
        EncoderBenchGuard.report = { }
    }

    @Test
    fun theBenchRunsOnlyInDebugBuildsForAnExplicitTrue() {
        assertTrue(EncoderBenchSpec.requested(true, mapOf("test_encoder_bench" to true)))
        assertTrue(EncoderBenchSpec.requested(true, mapOf("test_encoder_bench" to "true")))
        assertFalse(EncoderBenchSpec.requested(false, mapOf("test_encoder_bench" to true)))
        assertFalse(EncoderBenchSpec.requested(true, mapOf("test_encoder_bench" to false)))
        assertFalse(EncoderBenchSpec.requested(true, mapOf("test_encoder_bench" to "maybe")))
        assertFalse(EncoderBenchSpec.requested(true, emptyMap()))
    }

    @Test
    fun defaultsComeFromTheFixtureGeometryAndAreTheAppsOwn() {
        val r = request()
        assertEquals(640, r.width)
        assertEquals(360, r.height)
        assertEquals(30, r.fps)
        assertEquals(400, r.bitrateKbps)
        assertEquals(BenchBitrateMode.AUTO, r.bitrateMode)
        assertNull(r.intraRefreshFrames)
        assertNull(r.keyFrameIntervalSeconds)
        assertEquals(20, r.durationSeconds)
        assertNull(r.codec)
    }

    @Test
    fun adbStringExtrasAreAccepted() {
        val r = request("test_fps" to "24", "test_bitrate_kbps" to "800", "test_bench_duration_s" to "5",
            "test_bench_codec" to "av1", "test_bench_bitrate_mode" to "CBR")
        assertEquals(24, r.fps)
        assertEquals(800, r.bitrateKbps)
        assertEquals(5, r.durationSeconds)
        assertEquals("av1", r.codec)
        assertEquals(BenchBitrateMode.CBR, r.bitrateMode)
    }

    @Test
    fun bitrateModeAcceptsTheBenchTokenOrTheExistingIntegerExtra() {
        assertEquals(BenchBitrateMode.CQ, request("test_bench_bitrate_mode" to "cq").bitrateMode)
        assertEquals(BenchBitrateMode.CBR_FD, request("test_bench_bitrate_mode" to "cbr-fd").bitrateMode)
        assertEquals(BenchBitrateMode.VBR, request("test_bitrate_mode" to 1).bitrateMode)
        assertEquals(BenchBitrateMode.CBR_FD, request("test_bitrate_mode" to 3).bitrateMode)
        // The token wins over the legacy integer.
        assertEquals(BenchBitrateMode.CBR, request("test_bench_bitrate_mode" to "cbr", "test_bitrate_mode" to 1).bitrateMode)
    }

    @Test
    fun invalidOrOutOfRangeValuesAreIssuesNeverSilentDefaults() {
        fun issues(vararg extra: Pair<String, Any?>) = EncoderBenchSpec.parse(base + extra).issues.joinToString(";")
        // DebugTunables.intraRefreshFrames would silently drop 1 and 1000; the bench must say so.
        assertTrue(issues("test_intra_refresh_frames" to 1).contains("test_intra_refresh_frames=1"))
        assertTrue(issues("test_intra_refresh_frames" to 1000).contains("outside 2..600"))
        assertTrue(issues("test_bench_bitrate_mode" to "turbo").contains("turbo"))
        assertTrue(issues("test_bitrate_mode" to 9).contains("test_bitrate_mode=9"))
        assertTrue(issues("test_fps" to 0).contains("test_fps=0"))
        assertTrue(issues("test_bench_duration_s" to 601).contains("test_bench_duration_s=601"))
        assertTrue(issues("test_bitrate_kbps" to "fast").contains("not an integer"))
        assertTrue(issues("test_bench_libaom_cpu_used" to 11).contains("0..10"))
        assertNull(EncoderBenchSpec.parse(base + ("test_fps" to 0)).request)
    }

    @Test
    fun theRawFixtureIsRequiredAndGeometryMustBeEven() {
        val noRaw = EncoderBenchSpec.parse(mapOf("test_width" to 640, "test_height" to 360))
        assertTrue(noRaw.issues.single().contains("test_raw_yuv is required"))
        val odd = EncoderBenchSpec.parse(mapOf("test_raw_yuv" to "/x.yuv", "test_width" to 641, "test_height" to 360))
        assertTrue(odd.issues.single().contains("not an even I420 geometry"))
        val none = EncoderBenchSpec.parse(mapOf("test_raw_yuv" to "/x.yuv"))
        assertTrue(none.issues.single().contains("no geometry"))
    }

    @Test
    fun codecResolutionFollowsTheAppOrderAndNamesWhatIsMissing() {
        val list = listOf(
            BenchCodecCandidate("c2.hw.av1.encoder", EncoderBenchSpec.MIME_AV1, false),
            BenchCodecCandidate("c2.hw.vp9.encoder", EncoderBenchSpec.MIME_VP9, false),
            BenchCodecCandidate("c2.android.av1.encoder", EncoderBenchSpec.MIME_AV1, false),
            BenchCodecCandidate("libaom-native", EncoderBenchSpec.MIME_AV1, true),
        )
        assertEquals("c2.hw.av1.encoder", EncoderBenchSpec.resolveCodec(null, list).first?.encoderName)
        assertEquals("c2.hw.av1.encoder", EncoderBenchSpec.resolveCodec("auto", list).first?.encoderName)
        assertEquals("c2.hw.av1.encoder", EncoderBenchSpec.resolveCodec("AV1", list).first?.encoderName)
        assertEquals("c2.hw.vp9.encoder", EncoderBenchSpec.resolveCodec("vp9", list).first?.encoderName)
        assertEquals("c2.hw.vp9.encoder", EncoderBenchSpec.resolveCodec(EncoderBenchSpec.MIME_VP9, list).first?.encoderName)
        assertEquals("libaom-native", EncoderBenchSpec.resolveCodec("libaom", list).first?.encoderName)
        assertEquals("c2.android.av1.encoder", EncoderBenchSpec.resolveCodec("C2.ANDROID.AV1.ENCODER", list).first?.encoderName)
        val svt = EncoderBenchSpec.resolveCodec("svt-av1", list)
        assertNull(svt.first)
        assertTrue(svt.second!!.startsWith("svt_av1_not_built"))
        val missing = EncoderBenchSpec.resolveCodec("nope", list)
        assertNull(missing.first)
        assertTrue(missing.second!!.startsWith("codec_unavailable"))
        assertNull(EncoderBenchSpec.resolveCodec("av1", emptyList()).first)
    }

    private val ladder = listOf(
        BenchCodecCandidate("c2.hw.av1.encoder", EncoderBenchSpec.MIME_AV1, false),
        BenchCodecCandidate("c2.hw.vp9.encoder", EncoderBenchSpec.MIME_VP9, false),
        BenchCodecCandidate("libaom-native", EncoderBenchSpec.MIME_AV1, true),
        BenchCodecCandidate("c2.qti.hevc.encoder", EncoderBenchSpec.MIME_HEVC, false, benchOnly = true),
        BenchCodecCandidate("c2.android.hevc.encoder", EncoderBenchSpec.MIME_HEVC, false, benchOnly = true),
        BenchCodecCandidate("c2.qti.avc.encoder", EncoderBenchSpec.MIME_AVC, false, benchOnly = true),
    )

    @Test
    fun hevcAndAvcResolveByTokenMimeOrExactNameAndAutoNeverPicksThem() {
        assertEquals("c2.qti.hevc.encoder", EncoderBenchSpec.resolveCodec("hevc", ladder).first?.encoderName)
        assertEquals("c2.qti.hevc.encoder", EncoderBenchSpec.resolveCodec("HEVC", ladder).first?.encoderName)
        assertEquals("c2.qti.hevc.encoder", EncoderBenchSpec.resolveCodec("h265", ladder).first?.encoderName)
        assertEquals("c2.qti.hevc.encoder", EncoderBenchSpec.resolveCodec("video/hevc", ladder).first?.encoderName)
        assertEquals("c2.qti.avc.encoder", EncoderBenchSpec.resolveCodec("avc", ladder).first?.encoderName)
        assertEquals("c2.qti.avc.encoder", EncoderBenchSpec.resolveCodec("h264", ladder).first?.encoderName)
        assertEquals("c2.qti.avc.encoder", EncoderBenchSpec.resolveCodec("video/avc", ladder).first?.encoderName)
        assertEquals("c2.android.hevc.encoder", EncoderBenchSpec.resolveCodec("c2.android.hevc.encoder", ladder).first?.encoderName)
        assertEquals("c2.qti.avc.encoder", EncoderBenchSpec.resolveCodec("C2.QTI.AVC.ENCODER", ladder).first?.encoderName)
        // The product tokens are unchanged, and "auto" skips the bench-only entries even when they come first.
        assertEquals("c2.hw.av1.encoder", EncoderBenchSpec.resolveCodec("auto", ladder).first?.encoderName)
        assertEquals("c2.hw.vp9.encoder", EncoderBenchSpec.resolveCodec("vp9", ladder).first?.encoderName)
        val onlyBench = ladder.filter { it.benchOnly }
        assertNull(EncoderBenchSpec.resolveCodec(null, onlyBench).first)
        // A phone with no HEVC encoder refuses instead of substituting another codec.
        val noHevc = EncoderBenchSpec.resolveCodec("hevc", ladder.filter { it.mime != EncoderBenchSpec.MIME_HEVC })
        assertNull(noHevc.first)
        assertTrue(noHevc.second!!.startsWith("codec_unavailable"))
    }

    @Test
    fun hevcAndAvcDefaultToAnExplicitCbrEvenWhenModeIsAuto() {
        for (mime in listOf(EncoderBenchSpec.MIME_HEVC, EncoderBenchSpec.MIME_AVC)) {
            val plan = EncoderBenchSpec.plan(request(), caps(name = "c2.qti.x.encoder", mime = mime, modes = setOf(1, 2), default = 1))
            assertEquals(emptyList<String>(), plan.refusals)
            assertEquals(2, plan.bitrateModeOverride)
            assertEquals(EncoderRateControl.CBR, plan.rateControl)
            assertEquals("cbr(bench_default)", plan.effective.toMap()["mode"])
            assertEquals("off", plan.effective.toMap()["rate_adjuster"])
            assertEquals("auto", plan.requested.toMap()["mode"])
        }
        // AV1 and VP9 keep the app's Automatic rate control.
        val av1 = EncoderBenchSpec.plan(request(), caps())
        assertNull(av1.bitrateModeOverride)
        assertEquals(EncoderRateControl.AUTOMATIC, av1.rateControl)
    }

    @Test
    fun anEncoderThatDeclaresNoModeCanRunOnItsDefaultLikeTheProduct() {
        // The A15s MediaTek AVC encoder declares no bitrate mode: the explicit CBR default is refused, encoder_default runs.
        val noModes = caps(name = "OMX.MTK.VIDEO.ENCODER.AVC", mime = EncoderBenchSpec.MIME_AVC, modes = emptySet())
        assertTrue(EncoderBenchSpec.plan(request(), noModes).refusals.single().startsWith("mode_unsupported"))
        val plan = EncoderBenchSpec.plan(request("test_bench_bitrate_mode" to "encoder_default"), noModes)
        assertEquals(emptyList<String>(), plan.refusals)
        assertEquals(null, plan.bitrateModeOverride)
        assertEquals("encoder_default", plan.effective.toMap()["mode"])
        // An encoder that declares modes cannot claim the no-mode path.
        val hevc = caps(name = "c2.qti.hevc.encoder", mime = EncoderBenchSpec.MIME_HEVC, modes = setOf(1, 2))
        assertTrue(EncoderBenchSpec.plan(request("test_bench_bitrate_mode" to "encoder_default"), hevc).refusals.single().contains("encoder_default"))
    }

    @Test
    fun hevcAndAvcHonourAnExplicitModeAndRefuseWhatTheyDoNotReport() {
        val hevc = caps(name = "c2.qti.hevc.encoder", mime = EncoderBenchSpec.MIME_HEVC, modes = setOf(1, 2))
        val vbr = EncoderBenchSpec.plan(request("test_bench_bitrate_mode" to "vbr"), hevc)
        assertEquals(emptyList<String>(), vbr.refusals)
        assertEquals(1, vbr.bitrateModeOverride)
        assertEquals("vbr", vbr.effective.toMap()["mode"])
        val cqRefused = EncoderBenchSpec.plan(request("test_bench_bitrate_mode" to "cq"), hevc)
        assertTrue(cqRefused.refusals.single().startsWith("mode_unsupported"))
        // No CBR on the encoder: the explicit default is refused, not swapped for VBR.
        val vbrOnly = caps(name = "c2.vendor.avc.encoder", mime = EncoderBenchSpec.MIME_AVC, modes = setOf(1), default = 1)
        val noCbr = EncoderBenchSpec.plan(request(), vbrOnly)
        assertTrue(noCbr.refusals.single().startsWith("mode_unsupported"))
        assertTrue(noCbr.refusals.single().contains("hevc/avc default to an explicit cbr"))
        assertEquals(2, noCbr.bitrateModeOverride)
    }

    @Test
    fun hevcAndAvcRefuseUnsupportedSizeFpsAndLibaomKnobs() {
        for (mime in listOf(EncoderBenchSpec.MIME_HEVC, EncoderBenchSpec.MIME_AVC)) {
            val c = caps(name = "c2.qti.x.encoder", mime = mime, size = false)
            val plan = EncoderBenchSpec.plan(request("test_fps" to 120, "test_bench_libaom_cpu_used" to 8), c)
            assertTrue(plan.refusals.any { it.startsWith("size_unsupported") && it.contains("640x360 at 120 fps") })
            assertTrue(plan.refusals.contains("libaom_cpu_used_requires_libaom_native"))
            assertEquals("120", plan.requested.toMap()["fps"])
            assertEquals("120", plan.effective.toMap()["fps"])
        }
        val mismatch = EncoderBenchSpec.plan(
            request("test_width" to 1280, "test_height" to 720),
            caps(name = "c2.qti.hevc.encoder", mime = EncoderBenchSpec.MIME_HEVC),
        )
        assertTrue(mismatch.refusals.single().startsWith("geometry_mismatch"))
    }

    @Test
    fun theCodecExtraAcceptsHevcAndAvcTokensMimesAndNames() {
        for (token in listOf("hevc", "avc", "video/hevc", "video/avc", "c2.qti.avc.encoder")) {
            assertEquals(token, request("test_bench_codec" to " $token ").codec)
        }
    }

    @Test
    fun theBenchOnlyMimesAreExactlyHevcAndAvc() {
        assertTrue(EncoderBenchSpec.isBenchOnlyMime("video/hevc"))
        assertTrue(EncoderBenchSpec.isBenchOnlyMime("video/avc"))
        assertFalse(EncoderBenchSpec.isBenchOnlyMime(EncoderBenchSpec.MIME_AV1))
        assertFalse(EncoderBenchSpec.isBenchOnlyMime(EncoderBenchSpec.MIME_VP9))
    }

    @Test
    fun aSupportedRequestMapsToTheEncodersOwnSettings() {
        val r = request("test_bench_bitrate_mode" to "vbr", "test_intra_refresh_frames" to 30, "test_bench_keyframe_s" to 4)
        val plan = EncoderBenchSpec.plan(r, caps())
        assertEquals(emptyList<String>(), plan.refusals)
        assertEquals(1, plan.bitrateModeOverride)
        assertEquals(EncoderRateControl.VBR, plan.rateControl)
        val effective = plan.effective.toMap()
        assertEquals("c2.test.av1.encoder", effective["codec"])
        assertEquals("vbr", effective["mode"])
        assertEquals("off", effective["rate_adjuster"])
        assertEquals("pending_readback", effective["intra_refresh_frames"])
        assertEquals("4", effective["keyframe_s"])
        assertEquals("n/a", effective["libaom_cpu_used"])
        assertEquals("30", plan.requested.toMap()["intra_refresh_frames"])
    }

    @Test
    fun autoKeepsTheAppsRateControlAndReportsTheModeItResolvesTo() {
        val plan = EncoderBenchSpec.plan(request(), caps(default = 2))
        assertNull(plan.bitrateModeOverride)
        assertEquals(EncoderRateControl.AUTOMATIC, plan.rateControl)
        assertEquals("auto(cbr)", plan.effective.toMap()["mode"])
        assertEquals("app_default", plan.effective.toMap()["rate_adjuster"])
        assertEquals("60", plan.effective.toMap()["keyframe_s"])
    }

    @Test
    fun cqIsPassedThroughOnlyWhereTheEncoderReportsIt() {
        val r = request("test_bench_bitrate_mode" to "cq")
        val refused = EncoderBenchSpec.plan(r, caps(modes = setOf(1, 2)))
        assertEquals(1, refused.refusals.size)
        assertTrue(refused.refusals.single().startsWith("mode_unsupported"))
        assertTrue(refused.refusals.single().contains("supports vbr,cbr"))
        val allowed = EncoderBenchSpec.plan(r, caps(modes = setOf(0, 1, 2)))
        assertEquals(emptyList<String>(), allowed.refusals)
        assertEquals(0, allowed.bitrateModeOverride)
    }

    @Test
    fun unsupportedSettingsRefuseWithTheirNamesAndKeepTheRequestVisible() {
        val r = request("test_bench_bitrate_mode" to "cbr_fd", "test_fps" to 60)
        val plan = EncoderBenchSpec.plan(r, caps(modes = setOf(1), size = false))
        assertEquals(2, plan.refusals.size)
        assertTrue(plan.refusals.any { it.startsWith("mode_unsupported") })
        assertTrue(plan.refusals.any { it.startsWith("size_unsupported") && it.contains("640x360 at 60 fps") })
        assertEquals("cbr_fd", plan.requested.toMap()["mode"])
        assertEquals("60", plan.requested.toMap()["fps"])
    }

    @Test
    fun libaomKnobsApplyOnlyToLibaomAndIntraRefreshOnlyToMediaCodec() {
        val knobs = request("test_bench_libaom_cpu_used" to 8, "test_bench_libaom_threads" to 4)
        val onHardware = EncoderBenchSpec.plan(knobs, caps())
        assertEquals(
            setOf("libaom_cpu_used_requires_libaom_native", "libaom_threads_requires_libaom_native"),
            onHardware.refusals.toSet(),
        )
        val libaom = caps(name = "libaom-native", native = true, modes = setOf(1, 2, 3), size = null, intra = null)
        val onLibaom = EncoderBenchSpec.plan(knobs, libaom)
        assertEquals(emptyList<String>(), onLibaom.refusals)
        assertNull(onLibaom.bitrateModeOverride)
        assertEquals("8", onLibaom.effective.toMap()["libaom_cpu_used"])
        assertEquals("4", onLibaom.effective.toMap()["libaom_threads"])
        val intra = EncoderBenchSpec.plan(request("test_intra_refresh_frames" to 30), libaom)
        assertTrue(intra.refusals.single().startsWith("intra_refresh_unsupported"))
        val cq = EncoderBenchSpec.plan(request("test_bench_bitrate_mode" to "cq"), libaom)
        assertTrue(cq.refusals.single().startsWith("mode_unsupported"))
        // Unpinned libaom speed stays with the app's governor and says so.
        assertEquals("app_governor", EncoderBenchSpec.plan(request(), libaom).effective.toMap()["libaom_cpu_used"])
    }

    @Test
    fun aFixtureOfAnotherSizeIsRefusedNotRescaled() {
        val r = request("test_width" to 1280, "test_height" to 720)
        val plan = EncoderBenchSpec.plan(r, caps())
        assertTrue(plan.refusals.single().startsWith("geometry_mismatch"))
        assertTrue(plan.refusals.single().contains("never rescales"))
    }

    @Test
    fun featureUnsupportedIntraRefreshIsStillTriedAndReadBackHonestly() {
        val r = request("test_intra_refresh_frames" to 30)
        val plan = EncoderBenchSpec.plan(r, caps(intra = false))
        assertEquals(emptyList<String>(), plan.refusals)
        assertEquals("requested_feature_unsupported", plan.effective.toMap()["intra_refresh_frames"])
        fun readback(period: Int?) = EncoderBenchReadback(period, 2, 400_000, 30, 60, null, null, 3L)
        val applied = EncoderBenchSpec.effectiveAfterStart(EncoderBenchSpec.plan(r, caps()), r, readback(30)).toMap()
        assertEquals("applied:30", applied["intra_refresh_frames"])
        val ignored = EncoderBenchSpec.effectiveAfterStart(EncoderBenchSpec.plan(r, caps()), r, readback(null)).toMap()
        assertEquals("not_reported(requested 30)", ignored["intra_refresh_frames"])
        // No intra refresh requested: the read-back never invents one.
        val none = request()
        assertEquals("none", EncoderBenchSpec.effectiveAfterStart(EncoderBenchSpec.plan(none, caps()), none, readback(30)).toMap()["intra_refresh_frames"])
    }

    @Test
    fun readbackShowsWhatTheEncoderReportedBesideTheRequest() {
        val r = request("test_bench_bitrate_mode" to "cbr", "test_bitrate_kbps" to 800)
        val readback = EncoderBenchReadback(null, 1, 650_000, 30, 60, null, null, 0L)
        val effective = EncoderBenchSpec.effectiveAfterStart(EncoderBenchSpec.plan(r, caps()), r, readback).toMap()
        assertEquals("cbr|reported:vbr", effective["mode"])
        assertEquals("800|reported:650", effective["bitrate_kbps"])
    }

    @Test
    fun theMeterCountsFramesKeyframesRateAndLatencyPercentiles() {
        val meter = EncoderBenchMeter()
        meter.onPacket(0, 1_000, keyFrame = false, codecConfig = true, latencyMs = null)
        for (i in 1..10) {
            meter.onPacket(i * 100L, 1_250, keyFrame = i == 1, codecConfig = false, latencyMs = i * 10.0)
        }
        val window = meter.sample(1_000)
        assertEquals(10, window.frames)
        assertEquals(10.0, window.fpsOut, 1e-9)
        assertEquals(100.0, window.kbpsOut, 1e-9)
        assertEquals(1, window.keyFrames)
        assertEquals(50.0, window.latencyP50Ms!!, 1e-9)
        assertEquals(100.0, window.latencyP95Ms!!, 1e-9)
        val empty = meter.sample(1_000)
        assertEquals(0, empty.frames)
        assertNull(empty.latencyP50Ms)
        val total = meter.total(2_000)
        assertEquals(10, total.frames)
        assertEquals(5.0, total.fpsOut, 1e-9)
        assertEquals(1, total.keyFrames)
        assertEquals(100L, meter.firstPacketAtMs())
        assertNull(EncoderBenchMeter.percentile(emptyList(), 0.5))
    }

    @Test
    fun linesCarryTheDocumentedFieldsAndBracketedSettings() {
        val r = request("test_bench_bitrate_mode" to "cbr")
        val plan = EncoderBenchSpec.plan(r, caps())
        val window = EncoderBenchMeter.Window(29.97, 412.34, 12.0, 20.55, 1, 30)
        val sample = EncoderBenchLines.sample(3, "c2.test.av1.encoder", plan.requested, plan.effective, window, 2L, "none")
        assertTrue(sample.startsWith("BENCHMARK encoder_bench: event=sample | codec=c2.test.av1.encoder | requested=[codec=auto;"))
        for (field in listOf("effective=[", "fps_out=29.97", "kbps_out=412.3", "enc_latency_ms_p50/p95=12.0/20.6", "keyframes=1", "dropped=2", "thermal=none")) {
            assertTrue("$field in $sample", sample.contains(field))
        }
        val summary = EncoderBenchLines.summary("ok", null, "c2.test.av1.encoder", plan.requested, plan.effective, window, 2L, "none", 20.04, 0)
        assertTrue(summary.contains("event=summary"))
        assertTrue(summary.contains("result=ok | reason=none | frames=30 | run_s=20.0 | ble_violations=0"))
        val refused = EncoderBenchLines.summary("refused", "mode_unsupported: x", "n/a", plan.requested, emptyList(), null, 0L, "none", 0.0, 0)
        assertTrue(refused.contains("fps_out=n/a | kbps_out=n/a | enc_latency_ms_p50/p95=n/a/n/a | keyframes=0"))
        assertTrue(refused.contains("result=refused | reason=mode_unsupported: x"))
        assertEquals("[a=1;b=x,y]", EncoderBenchSpec.bracket(listOf("a" to "1", "b" to "x;y")))
    }

    @Test
    fun noBluetoothStartPathRunsWhileTheBenchIsActive() {
        val reports = mutableListOf<String>()
        EncoderBenchGuard.report = { reports += it }
        // Inactive: free, silent, nothing counted.
        EncoderBenchGuard.check("GattVideoTransport.gattServer")
        assertEquals(0, EncoderBenchGuard.violations)
        assertTrue(reports.isEmpty())

        EncoderBenchGuard.activate()
        assertTrue(EncoderBenchGuard.active)
        try {
            EncoderBenchGuard.check("BleL2capControl.scan")
            fail("a Bluetooth start path must be refused while the bench is active")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("BleL2capControl.scan"))
        }
        // A caller that swallows the exception (runCatching) is still counted and reported.
        runCatching { EncoderBenchGuard.check("StableAdvertisingSet.start") }
        assertEquals(2, EncoderBenchGuard.violations)
        assertEquals(listOf("BleL2capControl.scan", "StableAdvertisingSet.start"), EncoderBenchGuard.violationSites())
        assertEquals(2, reports.size)
        assertTrue(reports.first().contains("reason=bluetooth_start_during_encoder_bench"))

        EncoderBenchGuard.deactivate()
        EncoderBenchGuard.check("BleL2capControl.scan")
        assertEquals(2, EncoderBenchGuard.violations)
        assertNotNull(EncoderBenchGuard.violationSites())
        // A new activation starts clean.
        EncoderBenchGuard.activate()
        assertEquals(0, EncoderBenchGuard.violations)
    }

    private fun reading(
        now: Long? = -450_000L,
        avg: Long? = null,
        mv: Int? = 3_900,
        plugged: Int? = 0,
        status: Int? = BenchPower.STATUS_DISCHARGING,
        tenths: Int? = 312,
    ) = BatteryReading(now, avg, mv, plugged, status, tenths)

    @Test
    fun powerNormalisationReadsMicroampsAndMilliampsAndFollowsTheDetectedSign() {
        // AOSP: microamps, discharge negative, unplugged.
        val aosp = BenchPower.sample(reading(now = -450_000L, avg = -400_000L))
        assertEquals(CurrentSign.AOSP, aosp.sign)
        assertEquals("ua", aosp.currentNowUnits)
        assertEquals(-450.0, aosp.currentNowMa!!, 1e-9)
        assertEquals(-400.0, aosp.currentAverageMa!!, 1e-9)
        assertEquals(450.0 * 3_900 / 1_000.0, aosp.powerMw!!, 1e-9)
        assertTrue(aosp.valid)
        assertEquals("ok", aosp.reason)
        assertEquals(31.2, aosp.temperatureC!!, 1e-9)
        // Inverted OEM: discharge reported positive; normalised back to the AOSP sign, same power.
        val inverted = BenchPower.sample(reading(now = 450_000L, avg = 400_000L))
        assertEquals(CurrentSign.INVERTED, inverted.sign)
        assertEquals(-450.0, inverted.currentNowMa!!, 1e-9)
        assertEquals(-400.0, inverted.currentAverageMa!!, 1e-9)
        assertEquals(aosp.powerMw!!, inverted.powerMw!!, 1e-9)
        // A phone that reports milliamps (small magnitude) is read as mA, not as 0.45 mA.
        val milli = BenchPower.sample(reading(now = -450L))
        assertEquals("ma", milli.currentNowUnits)
        assertEquals(-450.0, milli.currentNowMa!!, 1e-9)
        assertEquals(aosp.powerMw!!, milli.powerMw!!, 1e-9)
        // The boundary: 9 999 is mA, 10 000 is microamps (10 mA).
        assertEquals("ma", BenchPower.magnitudeMa(9_999L)!!.second)
        assertEquals("ua", BenchPower.magnitudeMa(-10_000L)!!.second)
        assertEquals(10.0, BenchPower.magnitudeMa(-10_000L)!!.first, 1e-9)
    }

    @Test
    fun powerSignIsDetectedFromTheChargeStateWhilePluggedToo() {
        assertEquals(CurrentSign.AOSP, BenchPower.detectSign(900_000L, 2, BenchPower.STATUS_CHARGING))
        assertEquals(CurrentSign.INVERTED, BenchPower.detectSign(-900_000L, 2, BenchPower.STATUS_CHARGING))
        assertEquals(CurrentSign.UNKNOWN, BenchPower.detectSign(900_000L, 2, BenchPower.STATUS_FULL))
        assertEquals(CurrentSign.UNKNOWN, BenchPower.detectSign(0L, 0, BenchPower.STATUS_DISCHARGING))
        assertEquals(CurrentSign.UNKNOWN, BenchPower.detectSign(null, 0, BenchPower.STATUS_DISCHARGING))
        // Plug state missing but the platform says discharging.
        assertEquals(CurrentSign.AOSP, BenchPower.detectSign(-5_000L, null, BenchPower.STATUS_DISCHARGING))
        // An unknown sign leaves the raw sign alone (no flip).
        val unknown = BenchPower.sample(reading(now = 300_000L, plugged = null, status = null))
        assertEquals(CurrentSign.UNKNOWN, unknown.sign)
        assertEquals(300.0, unknown.currentNowMa!!, 1e-9)
    }

    @Test
    fun powerIsInvalidWhilePluggedAndUnsupportedPropertiesAreUnknown() {
        val usb = BenchPower.sample(reading(now = 800_000L, plugged = 2, status = BenchPower.STATUS_CHARGING))
        assertFalse(usb.valid)
        assertEquals("plugged", usb.reason)
        assertEquals("usb", usb.plugged)
        assertEquals("charging", usb.status)
        assertNotNull(usb.powerMw) // the figure is still logged, but flagged invalid
        assertEquals("ac+usb", BenchPower.pluggedToken(3))
        assertEquals("none", BenchPower.pluggedToken(0))
        assertEquals("unknown", BenchPower.pluggedToken(null))

        val none = BenchPower.sample(BatteryReading(null, null, null, null, null, null))
        assertFalse(none.valid)
        assertEquals("plugged_unknown", none.reason)
        assertNull(none.powerMw)
        val field = BenchPower.sampleField(none)
        for (token in listOf(
            "current_now_raw=unknown", "current_now_ma=unknown", "current_avg_ma=unknown", "voltage_mv=unknown",
            "power_mw=unknown", "plugged=unknown", "temp_c=unknown", "power_valid=false",
        )) {
            assertTrue("$token in $field", field.contains(token))
        }
        // Zero current (what many phones answer when unsupported) is unknown, never a free 0 mW.
        val zero = BenchPower.sample(reading(now = 0L))
        assertEquals("current_unknown", zero.reason)
        assertNull(zero.powerMw)
        // A voltage outside any battery's range is not used.
        val badVoltage = BenchPower.sample(reading(mv = 12))
        assertEquals("voltage_unknown", badVoltage.reason)
        assertNull(badVoltage.powerMw)
    }

    @Test
    fun powerSummaryMathUsesOnlyValidWindows() {
        val meter = EncoderBenchPowerMeter()
        assertEquals("no_samples", meter.summary().reason)
        assertFalse(meter.summary().powerValid)
        // 3.9 V: 400 mA -> 1560 mW, 500 mA -> 1950 mW, 600 mA -> 2340 mW; windows 1 s, 1 s, 2 s with 30, 30, 60 frames.
        meter.add(BenchPower.sample(reading(now = -400_000L)), 1_000, 30)
        meter.add(BenchPower.sample(reading(now = -500_000L)), 1_000, 30)
        meter.add(BenchPower.sample(reading(now = -600_000L)), 2_000, 60)
        val s = meter.summary()
        assertTrue(s.powerValid)
        assertEquals("ok", s.reason)
        assertEquals(3, s.samples)
        assertEquals(3, s.validSamples)
        val energy = 1560.0 * 1 + 1950.0 * 1 + 2340.0 * 2
        assertEquals(energy, s.energyMj!!, 1e-6)
        assertEquals(energy / 4.0, s.meanMw!!, 1e-6) // time-weighted
        assertEquals(2340.0, s.p95Mw!!, 1e-6) // nearest rank
        assertEquals(energy / 120.0, s.mjPerFrame!!, 1e-9)
        assertEquals("aosp", s.sign)
        assertEquals("ua", s.units)
        assertEquals(3_900.0, s.voltageMeanMv!!, 1e-9)
        assertEquals(31.2, s.tempStartC!!, 1e-9)
        assertEquals(31.2, s.tempMaxC!!, 1e-9)
        val field = BenchPower.summaryField(s)
        assertTrue(field, field.contains("power_valid=true"))
        assertTrue(field, field.contains("power_mw_mean=2048"))
        assertTrue(field, field.contains("power_mw_p95=2340"))
        assertTrue(field, field.contains("mj_per_frame=68.25"))

        // One plugged second: the run is flagged invalid, and the plugged window stays out of the figures.
        val mixed = EncoderBenchPowerMeter()
        mixed.add(BenchPower.sample(reading(now = -400_000L)), 1_000, 30)
        mixed.add(BenchPower.sample(reading(now = 900_000L, plugged = 2, status = BenchPower.STATUS_CHARGING)), 1_000, 30)
        val m = mixed.summary()
        assertFalse(m.powerValid)
        assertEquals("plugged", m.reason)
        assertEquals(1, m.pluggedSamples)
        assertEquals(1, m.validSamples)
        assertEquals(1560.0, m.meanMw!!, 1e-6)
        assertEquals(1560.0 / 30.0, m.mjPerFrame!!, 1e-9)

        // Entirely plugged: no figures at all.
        val plugged = EncoderBenchPowerMeter()
        plugged.add(BenchPower.sample(reading(now = 900_000L, plugged = 2, status = BenchPower.STATUS_CHARGING)), 1_000, 30)
        val p = plugged.summary()
        assertFalse(p.powerValid)
        assertNull(p.meanMw)
        assertNull(p.mjPerFrame)
        assertTrue(BenchPower.summaryField(p).contains("power_mw_mean=unknown"))
    }

    @Test
    fun powerRidesOnTheSampleAndSummaryLinesAndIsOptional() {
        val plan = EncoderBenchSpec.plan(request("test_bench_bitrate_mode" to "cbr"), caps())
        val window = EncoderBenchMeter.Window(30.0, 400.0, 10.0, 20.0, 1, 30)
        val power = BenchPower.sample(reading())
        val sample = EncoderBenchLines.sample(1, "c2.x", plan.requested, plan.effective, window, 0L, "none", power)
        assertTrue(sample, sample.contains(" | thermal=none | power=[current_now_raw=-450000;current_now_ma=-450.0;"))
        assertTrue(sample, sample.endsWith("power_valid=true;power_reason=ok] | t_s=1"))
        val meter = EncoderBenchPowerMeter()
        meter.add(power, 1_000, 30)
        val summary = EncoderBenchLines.summary(
            "ok", null, "c2.x", plan.requested, plan.effective, window, 0L, "none", 1.0, 0, meter.summary(),
        )
        assertTrue(summary, summary.contains(" | power=[power_valid=true;power_reason=ok;samples=1;valid_samples=1;"))
        assertTrue(summary, summary.contains("] | result=ok | reason=none"))
        // Without power the lines are exactly what they were.
        assertFalse(EncoderBenchLines.sample(1, "c2.x", plan.requested, plan.effective, window, 0L, "none").contains("power="))
    }
}
