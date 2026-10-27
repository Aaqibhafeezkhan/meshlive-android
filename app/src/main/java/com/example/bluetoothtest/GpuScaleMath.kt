package com.example.bluetoothtest

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * GPU scale ladder (EXPERIMENTAL, default off; switch [GpuScaleFeature], DEBUG launch extra
 * `test_gpu_scale_ladder`). Pure math only, so the JVM can test it; the GL and camera code that uses
 * it is [GpuFrameRenderer] and [GpuScaleCapture].
 *
 * Problem: a ladder rung change used to restart the camera at another camera mode. That takes
 * hundreds of milliseconds to over a second, can fail ("Camera could not restart"), and limits the
 * rungs to what each phone's mode list offers (an ASUS 3:2 mode after 16:9 rungs changed the aspect).
 *
 * Pattern (libwebrtc `VideoAdapter` + `HardwareVideoEncoder.encodeTextureBuffer`, Grafika
 * `ContinuousCaptureActivity`; see docs/dev/decisions/README.md "GPU scale ladder"): capture once at one camera
 * mode, draw every frame into the encoder's input surface at the rung size through a crop/scale
 * transform. A rung change is then an encoder reset (new MediaCodec, first frame a keyframe) and the
 * camera never restarts. Each rung is an exact fraction of the capture, centre-cropped to the rung's
 * own aspect, so nothing is stretched and the aspect the viewer sees never changes.
 */
internal object GpuScaleMath {
    /** Rung heights, largest first; widths follow from the session aspect (720/540/480/360/240/180/144p). */
    val RUNG_HEIGHTS = intArrayOf(720, 540, 480, 360, 240, 180, 144)

    /** Capture modes above this are never chosen (a 1080p capture only to encode 480p would waste GPU and power). */
    const val MAX_CAPTURE_PIXELS = 1920L * 1088

    private const val ASPECT_TOLERANCE = 0.02

    /** Aspect ratios a camera or the Auto ladder really means; an off-by-a-pixel size snaps to one. */
    private val COMMON_ASPECTS = listOf(16 to 9, 4 to 3, 3 to 2, 16 to 10, 5 to 4, 1 to 1, 2 to 1, 18 to 9, 21 to 9)

    /** A rational aspect, so the rung widths come from 16/9 and not from 854/480 (427 vs 426.67 at 240p). */
    data class Aspect(val num: Int, val den: Int) {
        val ratio: Double get() = num.toDouble() / den
    }

    /** The aspect of [width]x[height], snapped to a common ratio within 1% (else the exact size). */
    fun aspectOf(width: Int, height: Int): Aspect {
        require(width > 0 && height > 0)
        val ratio = width.toDouble() / height
        COMMON_ASPECTS.firstOrNull { (n, d) -> abs(ratio - n.toDouble() / d) / (n.toDouble() / d) <= 0.01 }
            ?.let { (n, d) -> return Aspect(n, d) }
        return Aspect(width, height)
    }

    /** The nearest multiple of [multiple] (halves round up), at least [multiple]. */
    fun roundToMultiple(value: Double, multiple: Int): Int =
        max(multiple, (floor(value / multiple + 0.5) * multiple).toInt())

    /**
     * The rungs for a session whose largest picture is [session], largest first: [RUNG_HEIGHTS] at the
     * session aspect, each side rounded to the encoder's alignment ([alignWidth], [alignHeight]; at
     * least 2 for 4:2:0), never larger than [session] in either side, and only sizes [supported]
     * accepts (the encoder's `isSizeSupported`). The session's own size is always a rung when supported.
     * A rung whose rounding changed its aspect by a few pixels is cropped by [cropFor], not stretched.
     */
    fun rungSizes(
        session: VideoResolution,
        alignWidth: Int,
        alignHeight: Int,
        supported: (width: Int, height: Int) -> Boolean = { _, _ -> true },
    ): List<VideoResolution> {
        val aw = max(2, alignWidth)
        val ah = max(2, alignHeight)
        val aspect = aspectOf(session.width, session.height)
        val sizes = LinkedHashSet<Pair<Int, Int>>()
        if (supported(session.width, session.height)) sizes += session.width to session.height
        for (nominal in RUNG_HEIGHTS) {
            val height = roundToMultiple(nominal.toDouble(), ah)
            val width = roundToMultiple(height * aspect.ratio, aw)
            if (width > session.width || height > session.height) continue
            if (supported(width, height)) sizes += width to height
        }
        return sizes.sortedWith(compareByDescending<Pair<Int, Int>> { it.first.toLong() * it.second }.thenByDescending { it.first })
            .map { (w, h) -> VideoResolution(w, h, "${h}p") }
    }

    /** A camera output size and the frame rate the camera can sustain at it. */
    data class CaptureMode(val width: Int, val height: Int, val maxFps: Int) {
        val pixels: Long get() = width.toLong() * height
    }

    /**
     * The one camera mode a GPU-scaled session captures at: the smallest mode that covers [session]
     * (so the top rung is a 1:1 copy and every other rung a downscale, never an upscale) at its exact
     * aspect and at [fps]. Without an exact-aspect mode, the smallest mode whose centre crop to the
     * session aspect still covers it; without any covering mode, the largest one that comes closest
     * (the top rung is then capped by the capture). Null when the camera offers nothing usable.
     */
    fun chooseCapture(modes: List<CaptureMode>, session: VideoResolution, fps: Int): CaptureMode? {
        val usable = modes.filter { it.pixels <= MAX_CAPTURE_PIXELS && it.width > 0 && it.height > 0 }
        if (usable.isEmpty()) return null
        val atRate = usable.filter { it.maxFps >= fps }.ifEmpty { usable }
        val aspect = aspectOf(session.width, session.height).ratio
        fun exact(mode: CaptureMode) = abs(mode.width.toDouble() / mode.height - aspect) / aspect <= ASPECT_TOLERANCE
        fun covers(mode: CaptureMode): Boolean {
            // The centre crop of [mode] to the session aspect, in pixels.
            val cropWidth = minOf(mode.width.toDouble(), mode.height * aspect)
            val cropHeight = minOf(mode.height.toDouble(), mode.width / aspect)
            return cropWidth + 0.5 >= session.width && cropHeight + 0.5 >= session.height
        }
        atRate.filter { exact(it) && covers(it) }.minByOrNull { it.pixels }?.let { return it }
        atRate.filter { covers(it) }.minWithOrNull(compareBy<CaptureMode> { cropPixels(it, aspect) }.thenBy { it.pixels })
            ?.let { return it }
        return atRate.filter { exact(it) }.maxByOrNull { it.pixels }
            ?: atRate.maxByOrNull { cropPixels(it, aspect) }
    }

    private fun cropPixels(mode: CaptureMode, aspect: Double): Long {
        val cropWidth = minOf(mode.width.toDouble(), mode.height * aspect)
        val cropHeight = minOf(mode.height.toDouble(), mode.width / aspect)
        return (cropWidth * cropHeight).toLong()
    }

    /** A sub-rectangle of the source picture as fractions of its width and height. */
    data class CropRect(val left: Float, val top: Float, val width: Float, val height: Float) {
        val isFull: Boolean get() = left == 0f && top == 0f && width == 1f && height == 1f
    }

    /**
     * The centre crop of a [srcWidth]x[srcHeight] picture that has exactly the aspect of
     * [dstWidth]x[dstHeight]: a few pixels when only the alignment rounding differs, a whole side strip
     * when the capture aspect differs from the rung (a 4:3-only sensor feeding 16:9 rungs). Never a stretch.
     */
    fun cropFor(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): CropRect {
        require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0)
        val src = srcWidth.toDouble() / srcHeight
        val dst = dstWidth.toDouble() / dstHeight
        return when {
            abs(src - dst) / dst < 1e-6 -> CropRect(0f, 0f, 1f, 1f)
            src > dst -> {
                val width = (dst / src).toFloat()
                CropRect((1f - width) / 2f, 0f, width, 1f)
            }
            else -> {
                val height = (src / dst).toFloat()
                CropRect(0f, (1f - height) / 2f, 1f, height)
            }
        }
    }

    /** Whether a [width]x[height] rung is a real crop of [capture] (the cropped region is at least as large: no upscale). */
    fun fitsCapture(capture: CaptureMode, width: Int, height: Int): Boolean {
        val crop = cropFor(capture.width, capture.height, width, height)
        return crop.width * capture.width + 0.5 >= width && crop.height * capture.height + 0.5 >= height
    }

    /**
     * Column-major 4x4 (OpenGL layout) mapping texture coordinates of the cropped picture onto the whole
     * picture: x' = left + width x, y' = top + height y.
     */
    fun cropMatrix(crop: CropRect): FloatArray = floatArrayOf(
        crop.width, 0f, 0f, 0f,
        0f, crop.height, 0f, 0f,
        0f, 0f, 1f, 0f,
        crop.left, crop.top, 0f, 1f,
    )

    /** Column-major product [a] x [b]: [b] is applied first. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 16 && b.size == 16)
        val result = FloatArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
                result[column * 4 + row] = sum
            }
        }
        return result
    }

    /**
     * The texture matrix the shader applies: the crop first (in the picture's own coordinates), then the
     * SurfaceTexture's transform ([surfaceTextureMatrix], which also holds the camera buffer's vertical
     * flip). Camera2 buffers stay in sensor orientation, exactly as when the camera fed the encoder
     * directly, so rotation and front-camera handling downstream (display rotation flag, viewer matrix)
     * are unchanged and no rotation or mirror is added here.
     */
    fun textureMatrix(surfaceTextureMatrix: FloatArray, crop: CropRect): FloatArray =
        if (crop.isFull) surfaceTextureMatrix.copyOf() else multiply(surfaceTextureMatrix, cropMatrix(crop))

    /** The identity 4x4. */
    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /**
     * The quarter-pixel offsets (in the output's 0..1 coordinates) of the four samples the shader averages
     * when the rung is a real downscale, so a 5x reduction (720p to 144p) is a box-like filter and not an
     * aliased single bilinear tap. Zero for a 1:1 copy, where four taps would only blur.
     */
    fun tapOffset(crop: CropRect, captureWidth: Int, dstWidth: Int, dstHeight: Int): Pair<Float, Float> {
        val ratio = crop.width * captureWidth.toDouble() / dstWidth
        if (ratio < DOWNSCALE_TAP_RATIO) return 0f to 0f
        return 0.25f / dstWidth to 0.25f / dstHeight
    }

    private const val DOWNSCALE_TAP_RATIO = 1.05

    /**
     * Whether a session uses the GPU path, and if not why (the logged reason). Only the Auto ladder
     * sessions on a hardware surface-input encoder qualify: a software YUV encoder (native libaom) takes
     * CPU frames, a raw fixture bypasses the camera, and the benchmark reference recorder needs its own
     * camera stream at the encoder size.
     */
    fun offReason(
        featureOn: Boolean,
        autoLadderOn: Boolean,
        usesSurfaceInput: Boolean,
        rawInput: Boolean,
        referenceFrames: Boolean,
        gpuSupported: Boolean = true,
    ): String? = when {
        !featureOn -> "switch_off"
        !autoLadderOn -> "auto_ladder_off"
        rawInput -> "raw_input"
        !usesSurfaceInput -> "yuv_input_encoder"
        referenceFrames -> "reference_frames"
        !gpuSupported -> "egl_unsupported"
        else -> null
    }
}

/**
 * A GPU-scaled session: [session] is the largest picture (the Auto header) and fixes the aspect and the
 * capture mode, [fps] the session frame rate. The rung actually encoded is the encoder's own settings.
 */
data class GpuScaleRequest(val session: VideoResolution, val fps: Int)

/**
 * Switch for the GPU scale ladder. Default off; only a DEBUG launch with `test_gpu_scale_ladder=true`
 * turns it on (control arm: the extra absent or false). It applies only to sessions that run the
 * Auto ladder on a hardware surface-input encoder ([GpuScaleMath.offReason]).
 */
object GpuScaleFeature {
    const val EXTRA = "test_gpu_scale_ladder"

    /** A constant, default off, with no Settings row; the extra decides in any DEBUG launch ([FeatureCatalog.resolve]). */
    val spec = FeatureSpec(
        id = "gpu_scale_ladder",
        prefKey = null,
        defaultEnabled = false,
        benchmarkExtra = EXTRA,
        group = ViewerFeatures.GROUP,
        extraInAnyDebugLaunch = true,
    )

    @Volatile var enabled = false
}
