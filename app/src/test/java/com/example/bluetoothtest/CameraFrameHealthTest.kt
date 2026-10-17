package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CameraFrameHealthTest {
    @Test
    fun watchdogThresholds() {
        assertEquals(FrameStallWatchdog.Verdict.OK, FrameStallWatchdog.verdict(1_499, 0))
        assertEquals(FrameStallWatchdog.Verdict.NO_FRAMES, FrameStallWatchdog.verdict(1_500, 0))
        assertEquals(FrameStallWatchdog.Verdict.OK, FrameStallWatchdog.verdict(1_500, 1))
        assertEquals(FrameStallWatchdog.Verdict.TOO_FEW, FrameStallWatchdog.verdict(2_000, 2))
        assertEquals(FrameStallWatchdog.Verdict.OK, FrameStallWatchdog.verdict(2_000, 3))
        assertEquals(FrameStallWatchdog.Verdict.OK, FrameStallWatchdog.verdict(5_000, 150))
    }

    @Test
    fun stallResponseIsOffByDefaultAndNeverInBenchmarkLaunches() {
        val off = FrameStallLadder(enabled = false, benchmarkLaunch = false)
        assertEquals(FrameStallLadder.Step.NONE, off.next())
        assertTrue(off.responseLabel(FrameStallLadder.Step.NONE).startsWith("off"))
        val benchmark = FrameStallLadder(enabled = true, benchmarkLaunch = true)
        assertEquals(FrameStallLadder.Step.NONE, benchmark.next())
        assertEquals("off(benchmark_launch)", benchmark.responseLabel(FrameStallLadder.Step.NONE))
    }

    @Test
    fun stallLadderTakesTemplatePreviewOnceThenNamesTheCallerSteps() {
        val ladder = FrameStallLadder(enabled = true, benchmarkLaunch = false)
        assertEquals(FrameStallLadder.Step.TEMPLATE_PREVIEW, ladder.next())
        assertEquals(FrameStallLadder.Step.GPU_CAPTURE_NEEDS_CALLER, ladder.next())
        assertEquals(FrameStallLadder.Step.SIZE_STEP_NEEDS_CALLER, ladder.next())
        assertEquals(FrameStallLadder.Step.NONE, ladder.next())
        assertEquals("exhausted", ladder.responseLabel(FrameStallLadder.Step.NONE))
    }

    @Test
    fun stallLineCarriesTemplateStreamsAndResponse() {
        val line = FrameStallWatchdog.benchmarkLine(FrameStallWatchdog.Verdict.NO_FRAMES, 1_500, 0, "RECORD", "preview+encoder", "off(test_camera_starvation_fallback)")
        assertTrue(line, line.startsWith("BENCHMARK camera_frame_stall:"))
        assertTrue(line, "template=RECORD" in line && "streams=preview+encoder" in line && "frames=0" in line)
    }

    @Test
    fun blackFeedNeedsTwoSecondsOfEveryBlackSample() {
        val detector = BlackFrameDetector()
        assertFalse(detector.onSample(0, 0.5))
        assertFalse(detector.onSample(1_000, 0.4))
        assertFalse("two seconds not reached", detector.onSample(1_999, 0.0))
        assertTrue(detector.onSample(2_000, 0.0))
        assertFalse("fires once", detector.onSample(3_000, 0.0))
    }

    @Test
    fun aDarkRoomOrOneBrightSampleResetsTheBlackFeed() {
        val detector = BlackFrameDetector()
        assertFalse(detector.onSample(0, 0.5))
        assertFalse(detector.onSample(1_000, 12.0))
        assertFalse(detector.onSample(1_500, 0.5))
        assertFalse(detector.onSample(3_000, 0.5))
        assertTrue(detector.onSample(3_500, 0.5))
    }

    @Test
    fun sampledMeanLumaOfAUniformPlane() {
        assertEquals(0.0, BlackFrameDetector.sampledMeanLuma(64, 48, LumaPlane { _, _ -> 0 }), 0.0)
        assertEquals(128.0, BlackFrameDetector.sampledMeanLuma(64, 48, LumaPlane { _, _ -> 128 }), 0.0)
        assertEquals("an empty plane is not black", 255.0, BlackFrameDetector.sampledMeanLuma(0, 0, LumaPlane { _, _ -> 0 }), 0.0)
    }

    @Test
    fun firstFramesBlackPurpleAndGreenAreSuspect() {
        assertEquals(FirstFrameSanity.Verdict.OK, FirstFrameSanity.assess(110.0, 128.0, 127.0))
        assertEquals(FirstFrameSanity.Verdict.BLACK, FirstFrameSanity.assess(0.0, 128.0, 128.0))
        assertEquals(FirstFrameSanity.Verdict.PURPLE, FirstFrameSanity.assess(90.0, 170.0, 180.0))
        assertEquals(FirstFrameSanity.Verdict.GREEN, FirstFrameSanity.assess(90.0, 40.0, 40.0))
        // One chroma channel off alone is a colour cast, not the reported failure.
        assertEquals(FirstFrameSanity.Verdict.OK, FirstFrameSanity.assess(90.0, 170.0, 128.0))
        val line = FirstFrameSanity.benchmarkLine(2, 1.0, 128.0, 128.0, FirstFrameSanity.Verdict.BLACK, "c2.qti.av1.encoder")
        assertTrue(line, line.startsWith("BENCHMARK encoder_first_frames_suspect:") && "verdict=BLACK" in line)
    }

    @Test
    fun planeSamplerReadsStridedAndInterleavedPlanesInsideTheBuffer() {
        val planar = ByteBuffer.allocate(16 * 16).apply { while (hasRemaining()) put(100.toByte()); rewind() }
        assertEquals(100.0, PlaneSampler.mean(planar, 0, 16, 1, 16, 16, step = 4), 0.0)
        // Interleaved chroma (pixel stride 2): only the U bytes are read.
        val interleaved = ByteBuffer.allocate(8 * 2).apply {
            repeat(8) { put(200.toByte()); put(10.toByte()) }
            rewind()
        }
        assertEquals(200.0, PlaneSampler.mean(interleaved, 0, 16, 2, 8, 1, step = 1), 0.0)
        // Reads past the limit are skipped, not thrown; nothing readable is "not black".
        assertEquals(255.0, PlaneSampler.mean(ByteBuffer.allocate(4), 100, 16, 1, 16, 16), 0.0)
    }

    @Test
    fun captureIntervalMonitorReportsFpsAndTheSlowestInterval() {
        val monitor = CaptureIntervalMonitor(reportEveryNs = 1_000_000_000L)
        var now = 0L
        var window: CaptureIntervalMonitor.Window? = null
        // 30 captures 33 ms apart, one 100 ms gap, until a second has passed.
        while (window == null) {
            window = monitor.onCapture(now)
            now += if (now in 400_000_000L..450_000_000L) 100_000_000L else 33_000_000L
        }
        val report = checkNotNull(window)
        assertEquals(30.0, report.fps, 4.0)
        assertTrue(report.maxIntervalMs >= 99.0)
        assertTrue(monitor.line(report).startsWith("BENCHMARK camera_capture_timing:"))
        assertNull(monitor.onCapture(now + 10_000_000L))
    }

    @Test
    fun dynamicBitrateProbeMeasuresTheRateDeliveredAfterTheSettlingTime() {
        val probe = DynamicBitrateProbe(settleNs = 3_000_000_000L, measureNs = 2_000_000_000L)
        probe.onRequest(0L, 500_000, 1_000_000)
        // Frames during the settling time are not counted.
        assertNull(probe.onFrame(1_000_000_000L, 100_000))
        var result: DynamicBitrateProbe.Result? = null
        var t = 3_000_000_000L
        // 125000 bytes per second = 1 Mbit/s for two seconds, one 4166-byte frame every 33.3 ms.
        while (result == null && t < 6_000_000_000L) {
            result = probe.onFrame(t, 4_166)
            t += 33_333_333L
        }
        assertNotNull(result)
        assertEquals(1_000_000.0, result!!.deliveredBps.toDouble(), 60_000.0)
        assertEquals(1.0, result.ratio, 0.06)
        assertEquals(500_000, result.fromBps)
        assertTrue(probe.line(result, "c2.qti.av1.encoder", "video/av01").startsWith("BENCHMARK dynamic_bitrate_response:"))
        assertNull("one result per change", probe.onFrame(t, 4_166))
    }

    @Test
    fun anUnresponsiveEncoderShowsAsALowRatio() {
        val probe = DynamicBitrateProbe(settleNs = 0L, measureNs = 1_000_000_000L)
        probe.onRequest(0L, 300_000, 1_000_000)
        var result: DynamicBitrateProbe.Result? = null
        var t = 0L
        while (result == null && t < 3_000_000_000L) {
            result = probe.onFrame(t, 1_250) // 300 kbit/s regardless of the request
            t += 33_333_333L
        }
        assertTrue(checkNotNull(result).ratio < 0.4)
    }

    @Test
    fun qpIsTaggedWithItsCodecScale() {
        assertEquals("vp9:libvpx-0-64", QpScale.label("video/x-vnd.on2.vp9"))
        assertEquals("av1:unverified", QpScale.label("video/av01"))
        assertEquals("avc_hevc:0-51", QpScale.label("video/avc"))
        assertEquals("unknown", QpScale.label("video/other"))
        val line = QpScale.benchmarkLine("OMX.Exynos.VP9.Encoder", "video/x-vnd.on2.vp9", 33)
        assertTrue(line, "scale=vp9:libvpx-0-64" in line && "firstQp=33" in line)
    }
}
