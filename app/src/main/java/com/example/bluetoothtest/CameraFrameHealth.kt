package com.example.bluetoothtest

/**
 * Frame-stall watchdog thresholds (V-06): after a session is configured, no encoded frame within
 * [FIRST_FRAME_MS] or fewer than [MIN_FRAMES] within [WINDOW_MS] is a stall. CameraX documents the
 * failure as "the camera produces only the first few frames" (b/395822788) or a configured session
 * that never delivers (b/202080832). The thresholds are ours, so only the log is on by default.
 */
internal object FrameStallWatchdog {
    const val FIRST_FRAME_MS = 1_500L
    const val WINDOW_MS = 2_000L
    const val MIN_FRAMES = 3

    enum class Verdict { OK, NO_FRAMES, TOO_FEW }

    /** [frames]: encoded frames seen [elapsedMs] after the session was configured. */
    fun verdict(elapsedMs: Long, frames: Int): Verdict = when {
        elapsedMs >= FIRST_FRAME_MS && frames == 0 -> Verdict.NO_FRAMES
        elapsedMs >= WINDOW_MS && frames < MIN_FRAMES -> Verdict.TOO_FEW
        else -> Verdict.OK
    }

    fun benchmarkLine(
        verdict: Verdict,
        elapsedMs: Long,
        frames: Int,
        template: String,
        streams: String,
        response: String,
    ): String = "BENCHMARK camera_frame_stall: verdict=$verdict | elapsedMs=$elapsedMs | frames=$frames | " +
        "template=$template | streams=$streams | response=$response"
}

/**
 * The stall response ladder. Only the first step (TEMPLATE_PREVIEW once) is done inside the encoder;
 * the later steps need the caller (a GPU capture path, a smaller rung) and are only named in the log.
 * Every step is a requested != effective difference and is never taken in benchmark launches.
 */
internal class FrameStallLadder(private val enabled: Boolean, private val benchmarkLaunch: Boolean) {
    enum class Step { NONE, TEMPLATE_PREVIEW, GPU_CAPTURE_NEEDS_CALLER, SIZE_STEP_NEEDS_CALLER }

    private var taken = 0

    fun next(): Step {
        if (!enabled || benchmarkLaunch) return Step.NONE
        val step = when (taken) {
            0 -> Step.TEMPLATE_PREVIEW
            1 -> Step.GPU_CAPTURE_NEEDS_CALLER
            2 -> Step.SIZE_STEP_NEEDS_CALLER
            else -> Step.NONE
        }
        if (step != Step.NONE) taken++
        return step
    }

    /** What the log says when no automatic response runs. */
    fun responseLabel(step: Step): String = when {
        !enabled -> "off(test_camera_starvation_fallback)"
        benchmarkLaunch -> "off(benchmark_launch)"
        step == Step.NONE -> "exhausted"
        else -> step.name.lowercase()
    }
}

/**
 * Camera privacy toggle (V-07): with the Android 12 camera toggle off the app gets a blank feed and
 * no error (developer.android.com/training/permissions/explaining-access). A dark room is not a
 * toggle: the detector fires only when every luma sample is black for [BLACK_FOR_MS] and the caller
 * knows the phone has a camera toggle.
 */
internal class BlackFrameDetector(
    private val blackMeanLuma: Double = BLACK_MEAN_LUMA,
    private val blackForMs: Long = BLACK_FOR_MS,
    private val minSamples: Int = MIN_SAMPLES,
) {
    private var blackSinceMs = Long.MIN_VALUE
    private var samples = 0
    private var fired = false

    /** Returns true once, when the feed has been black long enough. */
    fun onSample(nowMs: Long, meanLuma: Double): Boolean {
        if (fired) return false
        if (meanLuma >= blackMeanLuma) {
            blackSinceMs = Long.MIN_VALUE
            samples = 0
            return false
        }
        if (blackSinceMs == Long.MIN_VALUE) blackSinceMs = nowMs
        samples++
        if (samples >= minSamples && nowMs - blackSinceMs >= blackForMs) {
            fired = true
            return true
        }
        return false
    }

    companion object {
        const val BLACK_MEAN_LUMA = 2.0
        const val BLACK_FOR_MS = 2_000L
        const val MIN_SAMPLES = 3

        /** Mean of a sparse grid of luma samples (cheap enough to run twice a second). */
        fun sampledMeanLuma(width: Int, height: Int, plane: LumaPlane, step: Int = 16): Double {
            if (width <= 0 || height <= 0) return 255.0
            var sum = 0L
            var count = 0
            var y = step / 2
            while (y < height) {
                var x = step / 2
                while (x < width) {
                    sum += plane.luma(x, y)
                    count++
                    x += step
                }
                y += step
            }
            return if (count == 0) 255.0 else sum.toDouble() / count
        }
    }
}

/**
 * First-frames sanity (V-19): Snapdragon 8 Gen 3 on Android 16 was reported to encode black, purple
 * or brown first frames (androidx/media #3087, 12 of 12 reporters). Average luma and chroma of the
 * first YUV frames show it; there is no codec switch, only the `encoder_first_frames_suspect` log.
 * Chroma is centred at 128: purple is U and V both well above it, green both well below.
 */
internal object FirstFrameSanity {
    enum class Verdict { OK, BLACK, PURPLE, GREEN }

    fun assess(yMean: Double, uMean: Double, vMean: Double): Verdict = when {
        yMean < 3.0 -> Verdict.BLACK
        uMean > 150.0 && vMean > 150.0 -> Verdict.PURPLE
        uMean < 90.0 && vMean < 90.0 -> Verdict.GREEN
        else -> Verdict.OK
    }

    fun benchmarkLine(frame: Int, yMean: Double, uMean: Double, vMean: Double, verdict: Verdict, encoder: String): String =
        "BENCHMARK encoder_first_frames_suspect: frame=$frame | encoder=$encoder | yMean=${"%.1f".format(java.util.Locale.US, yMean)} | " +
            "uMean=${"%.1f".format(java.util.Locale.US, uMean)} | vMean=${"%.1f".format(java.util.Locale.US, vMean)} | verdict=$verdict"
}

/**
 * Experimental (default off, `test_drop_leading_blank_frames`): drop the camera's leading blank buffers.
 *
 * 2026-10-20 (prewarm-ab-20261020a-d, build 502b65f1): the S22 as a source logged `encoder_first_frames_suspect
 * verdict=BLACK` (yMean 0.0, uMean and vMean 128.0) for frames 1-3 in all 8 runs, both arms; the ASUS never did.
 * The check reads the Camera2 `Image` itself (CameraVideoEncoder `checkFirstFramesImage`), so the camera delivered
 * them. Decoding the S22's encoded stream: frames 1-4 are exactly black (YAVG 0.008; 109, 23, 23, 23 bytes), frame 5
 * jumps to 50.7 and the exposure then settles (50 -> 61 over 10 frames). Exact zero luma with neutral chroma is a
 * blank buffer, not a dark scene (a sensor frame has noise) and not exposure convergence (that starts from 50). The
 * black key frame was the first picture a viewer got, about 0.27 s before the real one.
 *
 * Only before the first real frame, at most [MAX_FRAMES] frames and [MAX_MS] after the first one: a frame whose luma
 * mean is below [LUMA_MAX] and whose chroma means are within [CHROMA_TOLERANCE] of 128 is dropped before the encoder
 * sees it. The first frame that is not blank ends the check for the whole session; nothing is dropped mid-stream.
 * Surface-input encoders get no pixels and are not affected.
 */
internal class LeadingBlankFrames(private val enabled: () -> Boolean = { DebugTunables.dropLeadingBlankFrames }) {
    private var done = false
    private var firstAtMs = -1L
    var dropped = 0
        private set

    /** True when this frame should be dropped. [nowMs] is a monotonic clock. */
    fun drop(yMean: Double, uMean: Double, vMean: Double, nowMs: Long): Boolean {
        if (done || !enabled()) return false
        if (firstAtMs < 0) firstAtMs = nowMs
        val blank = isBlank(yMean, uMean, vMean)
        if (!blank || dropped >= MAX_FRAMES || nowMs - firstAtMs >= MAX_MS) {
            done = true
            return false
        }
        dropped++
        return true
    }

    /** Whether the check still runs (the caller then samples the frame's planes). */
    val checking: Boolean get() = !done && enabled()

    companion object {
        const val MAX_FRAMES = 15
        const val MAX_MS = 1_000L
        const val LUMA_MAX = 1.0
        const val CHROMA_TOLERANCE = 1.0

        fun isBlank(yMean: Double, uMean: Double, vMean: Double): Boolean =
            yMean < LUMA_MAX && kotlin.math.abs(uMean - 128.0) <= CHROMA_TOLERANCE &&
                kotlin.math.abs(vMean - 128.0) <= CHROMA_TOLERANCE
    }
}

/**
 * DEBUG only (`test_inject_blank_frames=N`): the first N camera images are made blank (luma 0, chroma 128) in the
 * listener, before [LeadingBlankFrames], so an A/B can exercise the gate on any phone (2026-10-20: the S22's real blank
 * buffers came in only 2 of 8 runs, so the gate's ON runs had nothing to drop).
 */
internal object BlankFrameInjection {
    const val MAX_FRAMES = 60

    fun shouldInject(injectedSoFar: Int, requested: Int): Boolean = injectedSoFar < requested.coerceIn(0, MAX_FRAMES)

    /** Y to 0 and both chroma planes to 128, in place; false (nothing written) when a plane buffer is read-only. */
    fun blank(planes: List<java.nio.ByteBuffer>): Boolean {
        if (planes.size < 3 || planes.any { it.isReadOnly }) return false
        fill(planes[0], 0)
        fill(planes[1], 128.toByte())
        fill(planes[2], 128.toByte())
        return true
    }

    private fun fill(buffer: java.nio.ByteBuffer, value: Byte) {
        for (index in buffer.position() until buffer.limit()) buffer.put(index, value)
    }
}

/**
 * Capture-interval monitor (V-22): counts the intervals of consecutive capture completions so a thermal
 * or HAL frame-rate drop shows in the log. Cheap (one subtraction per frame), but the CaptureCallback
 * itself is only registered in DEBUG or with `test_camera_release_timing`, until its overhead is measured.
 */
internal class CaptureIntervalMonitor(private val reportEveryNs: Long = 5_000_000_000L) {
    private var lastNs = Long.MIN_VALUE
    private var windowStartNs = Long.MIN_VALUE
    private var frames = 0
    private var maxIntervalNs = 0L
    private val intervals = LongArray(MAX_SAMPLES)
    private var sampleCount = 0

    data class Window(val fps: Double, val p95IntervalMs: Double, val maxIntervalMs: Double, val frames: Int)

    /** Records one completion; returns a window summary when [reportEveryNs] has passed. */
    fun onCapture(nowNs: Long): Window? {
        if (windowStartNs == Long.MIN_VALUE) windowStartNs = nowNs
        if (lastNs != Long.MIN_VALUE) {
            val interval = nowNs - lastNs
            if (interval > maxIntervalNs) maxIntervalNs = interval
            if (sampleCount < MAX_SAMPLES) intervals[sampleCount++] = interval
        }
        lastNs = nowNs
        frames++
        val elapsed = nowNs - windowStartNs
        if (elapsed < reportEveryNs) return null
        val sorted = intervals.copyOf(sampleCount).also { it.sort() }
        val p95 = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * 0.95).toInt()]
        val window = Window(frames * 1e9 / elapsed, p95 / 1e6, maxIntervalNs / 1e6, frames)
        windowStartNs = nowNs
        frames = 0
        maxIntervalNs = 0L
        sampleCount = 0
        return window
    }

    fun line(window: Window): String =
        "BENCHMARK camera_capture_timing: fps=${"%.1f".format(java.util.Locale.US, window.fps)} | " +
            "p95IntervalMs=${"%.1f".format(java.util.Locale.US, window.p95IntervalMs)} | " +
            "maxIntervalMs=${"%.1f".format(java.util.Locale.US, window.maxIntervalMs)} | frames=${window.frames}"

    private companion object {
        const val MAX_SAMPLES = 512
    }
}

/**
 * Dynamic-bitrate response probe (V-13): CDD requires dynamic bitrate change for H.264/VP8/VP9/HEVC
 * but not AV1, and the CTS only checks the long-run mean within 60 %. After a `video-bitrate`
 * change the delivered rate over the 3-5 s that follow is measured and logged as
 * `dynamic_bitrate_response` (relative response, not a pass/fail). One probe at a time; a change
 * that arrives while one is measuring restarts it.
 */
internal class DynamicBitrateProbe(
    private val settleNs: Long = 3_000_000_000L,
    private val measureNs: Long = 2_000_000_000L,
) {
    private var changeNs = Long.MIN_VALUE
    private var fromBps = 0
    private var toBps = 0
    private var bytes = 0L
    private var firstMeasuredNs = Long.MIN_VALUE

    data class Result(val fromBps: Int, val toBps: Int, val deliveredBps: Long, val ratio: Double, val windowMs: Long)

    @Synchronized
    fun onRequest(nowNs: Long, previousBps: Int, newBps: Int) {
        changeNs = nowNs
        fromBps = previousBps
        toBps = newBps
        bytes = 0L
        firstMeasuredNs = Long.MIN_VALUE
    }

    /** Feed every encoded frame; returns the result once the measuring window is complete. */
    @Synchronized
    fun onFrame(nowNs: Long, frameBytes: Int): Result? {
        if (changeNs == Long.MIN_VALUE) return null
        val since = nowNs - changeNs
        if (since < settleNs) return null
        if (firstMeasuredNs == Long.MIN_VALUE) firstMeasuredNs = nowNs
        bytes += frameBytes
        val window = nowNs - firstMeasuredNs
        if (window < measureNs) return null
        val delivered = (bytes * 8_000_000_000L / window.coerceAtLeast(1L))
        val result = Result(fromBps, toBps, delivered, delivered.toDouble() / toBps.coerceAtLeast(1), window / 1_000_000L)
        changeNs = Long.MIN_VALUE
        return result
    }

    fun line(result: Result, encoder: String, mime: String): String =
        "BENCHMARK dynamic_bitrate_response: encoder=$encoder | mime=$mime | fromKbps=${result.fromBps / 1_000} | " +
            "toKbps=${result.toBps / 1_000} | deliveredKbps=${result.deliveredBps / 1_000} | " +
            "ratio=${"%.2f".format(java.util.Locale.US, result.ratio)} | windowMs=${result.windowMs}"
}

/** Sparse plane sampling for the black-feed and first-frames checks (no allocation, absolute reads). */
internal object PlaneSampler {
    /** Mean of every [step]-th sample of a plane; 255.0 (not black) when nothing could be read. */
    fun mean(buffer: java.nio.ByteBuffer, offset: Int, rowStride: Int, pixelStride: Int, width: Int, height: Int, step: Int = 16): Double {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return 255.0
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = offset + y * rowStride + x * pixelStride
                if (index in 0 until buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xff
                    count++
                }
                x += step
            }
            y += step
        }
        return if (count == 0) 255.0 else sum.toDouble() / count
    }
}

/** QP scale tags for logged QP values (V-20): a QP is only comparable inside its codec's scale. */
internal object QpScale {
    fun label(mime: String): String = when (mime.lowercase()) {
        "video/x-vnd.on2.vp9", "video/x-vnd.on2.vp8" -> "vp9:libvpx-0-64"
        "video/avc", "video/hevc" -> "avc_hevc:0-51"
        "video/av01" -> "av1:unverified"
        else -> "unknown"
    }

    fun benchmarkLine(encoder: String, mime: String, firstQp: Int): String =
        "BENCHMARK qp_scale: encoder=$encoder | mime=$mime | scale=${label(mime)} | firstQp=$firstQp | " +
            "note=do not feed into thresholds written for another scale"
}
