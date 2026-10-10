package com.example.bluetoothtest

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Reads one luma sample; x/y are in the plane's own pixel coordinates. */
internal fun interface LumaPlane {
    fun luma(x: Int, y: Int): Int
}

/** An I420/NV12 luma plane stored in a byte array (raw fixtures, libaom input). */
internal fun lumaPlaneOf(bytes: ByteArray, offset: Int, rowStride: Int): LumaPlane =
    LumaPlane { x, y -> bytes[offset + y * rowStride + x].toInt() and 0xff }

/** A camera Image or codec plane (absolute reads; the buffer position is not moved). */
internal fun lumaPlaneOf(buffer: ByteBuffer, offset: Int, rowStride: Int, pixelStride: Int): LumaPlane =
    LumaPlane { x, y -> buffer.get(offset + y * rowStride + x * pixelStride).toInt() and 0xff }

/**
 * Scene-cut statistics of one frame against the previous one.
 *
 * [changedFraction]: grid cells whose mean luma or texture changed; [meanDelta]: mean absolute
 * change of the cell means (0-255); [detail]: mean absolute luma gradient (a cheap SI-like cost
 * proxy). [cut] is a hard change of most of the picture; [detailRise] says the new picture has
 * clearly more texture than the old one, the case that makes a stale-quantizer frame huge.
 */
internal data class SceneCutResult(
    val cut: Boolean,
    val detailRise: Boolean,
    val changedFraction: Double,
    val meanDelta: Double,
    val recentMedianDelta: Double,
    val detail: Double,
    val previousDetail: Double,
)

/**
 * Workstream C experiment support: detects a scene cut on the raw frame before the encoder sees
 * it, from a coarse luma grid (about [TARGET_SAMPLES] samples per frame, under a millisecond on the
 * test phones' worker threads).
 *
 * Calibrated on the generated coverage input (640x480 at 15 FPS): it flags frame 75 (easy ->
 * fine_detail_cuts, 93% of cells changed, detail 2.4 -> 113) and 225 (-> entropy_complex, 88%,
 * 25 -> 47), not frame 150 (a cut to a flat fade, detail 119 -> 7, cheap to code) and none of the
 * 39%-area partial cuts inside fine_detail_cuts, whose recent median change is already high.
 * Hardware VP9 turned frame 75 into 54-135 KB at 300-900 kbps; 150 and 225 cost 5-11 KB because
 * their quantizer was already high.
 */
internal class SceneCutDetector(
    val width: Int,
    val height: Int,
    private val columns: Int = GRID_COLUMNS,
    private val rows: Int = GRID_ROWS,
) {
    private val cellWidth = width / columns
    private val cellHeight = height / rows
    private val step = max(2, sqrt(width.toDouble() * height / TARGET_SAMPLES).roundToInt())
    private var previousMeans: DoubleArray? = null
    private var previousDetails: DoubleArray? = null
    private val recentDeltas = ArrayDeque<Double>()

    init {
        require(width >= columns * 4 && height >= rows * 4) { "Frame too small for scene-cut grid" }
    }

    fun analyze(plane: LumaPlane): SceneCutResult? {
        val means = DoubleArray(columns * rows)
        val details = DoubleArray(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                var sum = 0L
                var activity = 0L
                var samples = 0
                val left = column * cellWidth
                val top = row * cellHeight
                var y = top + 1
                while (y < top + cellHeight) {
                    var x = left
                    while (x < left + cellWidth - 1) {
                        val value = plane.luma(x, y)
                        sum += value
                        activity += abs(value - plane.luma(x + 1, y)) + abs(value - plane.luma(x, y - 1))
                        samples++
                        x += step
                    }
                    y += step
                }
                val cell = row * columns + column
                means[cell] = sum.toDouble() / samples.coerceAtLeast(1)
                details[cell] = activity.toDouble() / samples.coerceAtLeast(1)
            }
        }
        val oldMeans = previousMeans
        val oldDetails = previousDetails
        previousMeans = means
        previousDetails = details
        if (oldMeans == null || oldDetails == null) return null
        var changed = 0
        var deltaSum = 0.0
        for (cell in means.indices) {
            val meanChange = abs(means[cell] - oldMeans[cell])
            val detailChange = abs(details[cell] - oldDetails[cell])
            deltaSum += meanChange
            if (meanChange > CELL_MEAN_CHANGE || detailChange > max(CELL_DETAIL_CHANGE, oldDetails[cell] * 0.5)) changed++
        }
        val changedFraction = changed.toDouble() / means.size
        val meanDelta = deltaSum / means.size
        val median = recentDeltas.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        recentDeltas.addLast(meanDelta)
        while (recentDeltas.size > HISTORY_FRAMES) recentDeltas.removeFirst()
        val detail = details.average()
        val previousDetail = oldDetails.average()
        val cut = changedFraction >= CUT_CHANGED_FRACTION && meanDelta >= max(MIN_CUT_DELTA, median * CUT_DELTA_OVER_MEDIAN)
        return SceneCutResult(
            cut = cut,
            detailRise = detail >= max(previousDetail * DETAIL_RISE_RATIO, previousDetail + MIN_DETAIL_RISE),
            changedFraction = changedFraction,
            meanDelta = meanDelta,
            recentMedianDelta = median,
            detail = detail,
            previousDetail = previousDetail,
        )
    }

    companion object {
        const val GRID_COLUMNS = 16
        const val GRID_ROWS = 12
        const val TARGET_SAMPLES = 20_000.0
        /** Luma levels a cell mean must move to count as changed (noise and slow pans stay below). */
        const val CELL_MEAN_CHANGE = 12.0
        const val CELL_DETAIL_CHANGE = 6.0
        const val CUT_CHANGED_FRACTION = 0.6
        const val MIN_CUT_DELTA = 8.0
        /** Continuous heavy motion keeps a high median; a cut stands out against it. */
        const val CUT_DELTA_OVER_MEDIAN = 3.0
        const val HISTORY_FRAMES = 15
        const val DETAIL_RISE_RATIO = 1.25
        const val MIN_DETAIL_RISE = 4.0
    }
}

/** Experimental scene-cut shaping (DebugTunables.sceneCutShaping); OFF changes nothing. */
enum class SceneCutShapingMode(val debugValue: Int, val label: String) {
    OFF(0, "off"),
    /** The cut frame is requested as a sync frame with a temporarily lowered target. */
    SYNC(1, "sync"),
    /** Only the target is lowered for the cut frame. */
    TARGET(2, "target"),
    ;

    companion object {
        fun fromDebugValue(value: Int): SceneCutShapingMode = entries.firstOrNull { it.debugValue == value } ?: OFF
    }
}

/**
 * Experimental (off by default): lowers a MediaCodec encoder's target for one detected scene-cut
 * frame, optionally as a sync frame, so the cut is coded at a quantizer that fits about
 * [CUT_AIRTIME_MS] of the measured link, then restores the target when that frame is encoded.
 *
 * Only usable where the app hands raw frames to the encoder (raw fixtures and ByteBuffer camera
 * input). A request made before queueInputBuffer reaches the component before that frame on both
 * framework paths (android-frameworks-av): ACodec posts kWhatSetParameters (OMX setConfig) and the
 * filled input buffer to the same looper in call order; CCodec applies a vendor component's config
 * synchronously inside setParameters, and attaches it to the next queued frame for c2.android.*
 * components. Whether the hardware rate control honours it on that frame is what the phone A/B
 * measures. Live Camera2 with surface input reaches the hardware encoder without passing the app.
 *
 * The cut cost is learned per session as the cut frame's size in average frames at the target in
 * force ([cutFrames], prior [INITIAL_CUT_FRAMES]: hardware VP9 cuts were 17-29 average frames). An
 * encoder that ignores the target makes the learned cost grow and the fraction sink to
 * [MIN_FRACTION]; the BENCHMARK scene_cut lines show it.
 */
internal class SceneCutShaper(
    val mode: SceneCutShapingMode,
    private val cooldownNs: Long = COOLDOWN_NS,
    initialCutFrames: Double = INITIAL_CUT_FRAMES,
) {
    data class Plan(
        val shapedBps: Int,
        val requestSync: Boolean,
        val budgetBytes: Long,
        val expectedBytes: Long,
    )

    var cutFrames = initialCutFrames
        private set
    var pendingPtsUs = Long.MIN_VALUE
        private set
    private var pendingShapedBps = 0
    private var pendingSinceNs = Long.MIN_VALUE
    private var lastShapeNs = Long.MIN_VALUE
    var shapedCuts = 0
        private set

    val pending: Boolean get() = pendingPtsUs != Long.MIN_VALUE

    /**
     * Decides whether the frame at [ptsUs] is shaped. [lastQIdx] is the previous encoded frame's
     * quantizer when the bitstream exposes it: a cut at an already high quantizer is cheap (5-11 KB
     * at q_idx 206-238), so it is left alone. Returns null (with [skipReason]) when nothing changes.
     */
    fun plan(
        ptsUs: Long,
        nowNs: Long,
        cut: SceneCutResult,
        lastQIdx: Int?,
        linkKbps: Int,
        targetBps: Int,
        fps: Int,
    ): Plan? {
        skipReason = null
        if (mode == SceneCutShapingMode.OFF) return skip("experiment off")
        if (!cut.cut) return skip("no cut")
        if (!cut.detailRise) return skip("cut to a simpler picture")
        if (pending) return skip("previous cut still encoding")
        if (lastShapeNs != Long.MIN_VALUE && nowNs - lastShapeNs < cooldownNs) return skip("cooldown")
        if (lastQIdx != null && lastQIdx >= HIGH_Q_IDX) return skip("quantizer already high ($lastQIdx)")
        if (targetBps <= 0 || fps <= 0) return skip("no target")
        val budgetBytes = cutBudgetBytes(linkKbps, targetBps)
        val averageFrameBytes = targetBps / 8.0 / fps
        val expected = cutFrames * averageFrameBytes
        if (expected <= budgetBytes) return skip("expected cut fits the link")
        val fraction = (budgetBytes / expected).coerceIn(MIN_FRACTION, 1.0)
        val shapedBps = (targetBps * fraction).toInt().coerceAtLeast(1)
        pendingPtsUs = ptsUs
        pendingShapedBps = shapedBps
        pendingSinceNs = nowNs
        lastShapeNs = nowNs
        shapedCuts++
        return Plan(shapedBps, mode == SceneCutShapingMode.SYNC, budgetBytes, expected.toLong())
    }

    /** Why the last [plan] returned null. */
    var skipReason: String? = null
        private set

    private fun skip(reason: String): Plan? {
        skipReason = reason
        return null
    }

    /**
     * An encoded frame came out. Returns true when the shaped frame (or a later one) has been
     * encoded, so the full target must be restored. Learns the cut cost from the shaped frame.
     */
    fun onEncoded(ptsUs: Long, bytes: Int, fps: Int): Boolean {
        if (!pending || ptsUs < pendingPtsUs) return false
        if (ptsUs == pendingPtsUs && pendingShapedBps > 0 && fps > 0) {
            val shapedAverage = pendingShapedBps / 8.0 / fps
            val observed = bytes / shapedAverage
            cutFrames = (cutFrames * (1 - LEARNING_RATE) + observed * LEARNING_RATE)
                .coerceIn(MIN_CUT_FRAMES, MAX_CUT_FRAMES)
        }
        clear()
        return true
    }

    /** The encoder rejected the shaped request: nothing is pending. */
    fun cancel() = clear()

    /** The encoder never returned the shaped frame within [timeoutNs]: restore anyway. */
    fun timedOut(nowNs: Long, timeoutNs: Long = RESTORE_TIMEOUT_NS): Boolean {
        if (!pending || nowNs - pendingSinceNs < timeoutNs) return false
        clear()
        return true
    }

    private fun clear() {
        pendingPtsUs = Long.MIN_VALUE
        pendingShapedBps = 0
        pendingSinceNs = Long.MIN_VALUE
    }

    companion object {
        const val CUT_AIRTIME_MS = 250L
        /** Hardware VP9 cut frames were 17-29 average frames at the request (libaom AV1: about 5). */
        const val INITIAL_CUT_FRAMES = 20.0
        const val MIN_CUT_FRAMES = 2.0
        const val MAX_CUT_FRAMES = 400.0
        const val MIN_FRACTION = 0.1
        const val LEARNING_RATE = 0.5
        /** Cuts at q_idx >= this cost 5-11 KB on M31/S24 hardware VP9 (coverage frames 150/225). */
        const val HIGH_Q_IDX = 200
        const val COOLDOWN_NS = 2_000_000_000L
        const val RESTORE_TIMEOUT_NS = 1_000_000_000L

        /** Bytes that cross the link in [CUT_AIRTIME_MS]; the stream's own rate when unmeasured. */
        fun cutBudgetBytes(linkKbps: Int, targetBps: Int): Long {
            val kbps = if (linkKbps > 0) linkKbps else targetBps / 1_000
            return kbps.toLong() * 1_000 / 8 * CUT_AIRTIME_MS / 1_000
        }
    }
}

/**
 * Experimental quantizer headroom (DebugTunables.vp9QuantizerFloor, off by default): while the
 * parsed VP9 base_q_idx of the last second stays below [floorQIdx], the encoder target is lowered
 * step by step (down to [minFactor] of the target), and it is released when the quantizer is well
 * above the floor again. It works on any VP9 encoder whose output the app sees, including live
 * Camera2 surface input, because it acts before the cut instead of at it.
 *
 * Why: the scene-cut burst size follows the quantizer in force at the cut (M31 OMX VP9: q12 ->
 * 135 KB, q112-122 -> 66-73 KB, q130-138 -> 57-62 KB; lane-b F1). Easy content at the full target
 * drives the quantizer to 12-40, where extra bits buy little picture but set up a huge cut. Cost:
 * the encoder sends less than requested on easy content (the offered-load and VMAF effects are what
 * the A/B measures); the requested rate stays visible in the logs.
 */
internal class QuantizerHeadroomGovernor(
    private val floorQIdx: Int,
    private val minFactor: Double = MIN_FACTOR,
    private val windowNs: Long = WINDOW_NS,
) {
    var factor = 1.0
        private set
    private val window = ArrayList<Int>()
    private var windowStartNs = Long.MIN_VALUE
    var lastWindowMedian = -1
        private set

    /** Records one encoded frame's quantizer; returns the new factor when it changes. */
    fun onFrame(qIdx: Int, nowNs: Long): Double? {
        if (qIdx < 0) return null
        if (windowStartNs == Long.MIN_VALUE) windowStartNs = nowNs
        window += qIdx
        if (nowNs - windowStartNs < windowNs) return null
        val median = window.sorted()[window.size / 2]
        lastWindowMedian = median
        window.clear()
        windowStartNs = nowNs
        val next = when {
            median < floorQIdx -> (factor * STEP_DOWN).coerceAtLeast(minFactor)
            median > floorQIdx + RELEASE_MARGIN -> (factor * STEP_UP).coerceAtMost(1.0)
            else -> factor
        }
        if (abs(next - factor) < 1e-9) return null
        factor = next
        return next
    }

    companion object {
        const val MIN_FACTOR = 0.35
        const val WINDOW_NS = 1_000_000_000L
        const val STEP_DOWN = 0.8
        const val STEP_UP = 1.15
        /** Released only well above the floor, so the target does not oscillate at the boundary. */
        const val RELEASE_MARGIN = 40
    }
}
