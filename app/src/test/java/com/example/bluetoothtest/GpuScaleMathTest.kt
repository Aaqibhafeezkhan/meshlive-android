package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GpuScaleMathTest {
    private fun r(w: Int, h: Int) = VideoResolution(w, h, "${h}p")
    private fun sizes(list: List<VideoResolution>) = list.map { it.width to it.height }

    /** Applies a column-major 4x4 to (x, y). */
    private fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])

    @Test
    fun aspectSnapsToTheCommonRatioInsteadOfTheRoundedSize() {
        assertEquals(GpuScaleMath.Aspect(16, 9), GpuScaleMath.aspectOf(854, 480))
        assertEquals(GpuScaleMath.Aspect(16, 9), GpuScaleMath.aspectOf(426, 240))
        assertEquals(GpuScaleMath.Aspect(4, 3), GpuScaleMath.aspectOf(640, 480))
        assertEquals(GpuScaleMath.Aspect(3, 2), GpuScaleMath.aspectOf(720, 480))
        assertEquals(GpuScaleMath.Aspect(1000, 700), GpuScaleMath.aspectOf(1000, 700)) // no common ratio
    }

    @Test
    fun sixteenByNineRungsMatchTheAutoLadderSizes() {
        assertEquals(
            listOf(1280 to 720, 960 to 540, 854 to 480, 640 to 360, 426 to 240, 320 to 180, 256 to 144),
            sizes(GpuScaleMath.rungSizes(r(1280, 720), 2, 2)),
        )
        // The Auto header at 480p: nothing above it, the session size is the first rung.
        assertEquals(
            listOf(854 to 480, 640 to 360, 426 to 240, 320 to 180, 256 to 144),
            sizes(GpuScaleMath.rungSizes(r(854, 480), 2, 2)),
        )
    }

    @Test
    fun fourByThreeAndThreeByTwoSessionsKeepTheirAspect() {
        assertEquals(
            listOf(640 to 480, 480 to 360, 320 to 240, 240 to 180, 192 to 144),
            sizes(GpuScaleMath.rungSizes(r(640, 480), 2, 2)),
        )
        // 720x480 (the ASUS 3:2 mode) is no longer an odd one out: every rung is 3:2.
        GpuScaleMath.rungSizes(r(720, 480), 2, 2).forEach { rung ->
            assertEquals(1.5, rung.width.toDouble() / rung.height, 0.01)
        }
    }

    @Test
    fun rungsRoundToEncoderAlignmentAndStayWithinThreePercentOfTheAspect() {
        val rungs = GpuScaleMath.rungSizes(r(1280, 720), 16, 16)
        assertTrue(rungs.size >= 5)
        rungs.forEach { rung ->
            assertEquals("width ${rung.width}", 0, rung.width % 16)
            assertEquals("height ${rung.height}", 0, rung.height % 16)
            val deviation = abs(rung.width.toDouble() / rung.height - 16.0 / 9) / (16.0 / 9)
            assertTrue("$rung deviates ${deviation * 100}%", deviation < 0.03)
        }
        assertEquals(1280 to 720, rungs.first().let { it.width to it.height })
    }

    @Test
    fun unsupportedEncoderSizesAreSkippedAndAlignmentFloorsAtTwo() {
        val noSmall = GpuScaleMath.rungSizes(r(854, 480), 1, 1) { w, h -> w * h >= 640 * 360 }
        assertEquals(listOf(854 to 480, 640 to 360), sizes(noSmall))
        GpuScaleMath.rungSizes(r(854, 480), 1, 1).forEach { assertEquals(0, it.width % 2); assertEquals(0, it.height % 2) }
    }

    @Test
    fun anUnalignedSessionSizeStillLeadsWhenTheEncoderAcceptsIt() {
        val rungs = GpuScaleMath.rungSizes(r(1000, 562), 2, 2)
        assertEquals(1000 to 562, rungs.first().let { it.width to it.height })
        assertFalse(rungs.any { it.width > 1000 || it.height > 562 })
    }

    @Test
    fun cropIsFullForTheSameAspectAndCentredOtherwise() {
        assertTrue(GpuScaleMath.cropFor(1280, 720, 640, 360).isFull)
        assertTrue(GpuScaleMath.cropFor(1280, 720, 1280, 720).isFull)
        // 4:3 sensor into a 16:9 rung: keep the middle three quarters of the height.
        val wide = GpuScaleMath.cropFor(640, 480, 640, 360)
        assertEquals(0f, wide.left, 1e-6f)
        assertEquals(1f, wide.width, 1e-6f)
        assertEquals(0.75f, wide.height, 1e-5f)
        assertEquals(0.125f, wide.top, 1e-5f)
        // 16:9 capture into a 4:3 rung: keep the middle of the width.
        val tall = GpuScaleMath.cropFor(1280, 720, 640, 480)
        assertEquals(0f, tall.top, 1e-6f)
        assertEquals(1f, tall.height, 1e-6f)
        assertEquals((4f / 3f) / (16f / 9f), tall.width, 1e-5f)
        assertEquals((1f - tall.width) / 2f, tall.left, 1e-5f)
    }

    @Test
    fun alignmentRoundingCropsAFewPixelsInsteadOfStretching() {
        // 960x544 from 1280x720: aspect differs by 0.7%; the crop is below 1% of one side, not a squeeze.
        val crop = GpuScaleMath.cropFor(1280, 720, 960, 544)
        assertTrue(crop.width < 1f || crop.height < 1f)
        assertTrue(1f - minOf(crop.width, crop.height) < 0.01f)
        // The cropped region has exactly the destination aspect.
        val croppedAspect = (crop.width * 1280.0) / (crop.height * 720.0)
        assertEquals(960.0 / 544, croppedAspect, 1e-4)
    }

    @Test
    fun cropMatrixMapsTheUnitSquareOntoTheCropRectangle() {
        val crop = GpuScaleMath.CropRect(0.1f, 0.125f, 0.8f, 0.75f)
        val m = GpuScaleMath.cropMatrix(crop)
        val (x0, y0) = apply(m, 0f, 0f)
        val (x1, y1) = apply(m, 1f, 1f)
        assertEquals(0.1f, x0, 1e-6f)
        assertEquals(0.125f, y0, 1e-6f)
        assertEquals(0.9f, x1, 1e-6f)
        assertEquals(0.875f, y1, 1e-6f)
    }

    @Test
    fun textureMatrixAppliesTheCropBeforeTheSurfaceTextureTransform() {
        // The camera buffer transform: a vertical flip (t' = 1 - t).
        val flip = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        val crop = GpuScaleMath.CropRect(0f, 0.125f, 1f, 0.75f)
        val m = GpuScaleMath.textureMatrix(flip, crop)
        val (_, bottom) = apply(m, 0f, 0f)
        val (_, top) = apply(m, 0f, 1f)
        assertEquals(0.875f, bottom, 1e-6f) // crop first (0.125), then the flip
        assertEquals(0.125f, top, 1e-6f)
        // A full crop leaves the transform untouched and returns a copy.
        val full = GpuScaleMath.textureMatrix(flip, GpuScaleMath.CropRect(0f, 0f, 1f, 1f))
        assertTrue(flip.contentEquals(full))
        assertTrue(flip !== full)
    }

    @Test
    fun multiplyMatchesNaiveProductAndIdentityIsNeutral() {
        val a = FloatArray(16) { (it * 7 % 5 + 1).toFloat() }
        val b = FloatArray(16) { (it * 3 % 7 - 2).toFloat() }
        val product = GpuScaleMath.multiply(a, b)
        for (row in 0 until 4) for (col in 0 until 4) {
            var expected = 0f
            for (k in 0 until 4) expected += a[k * 4 + row] * b[col * 4 + k]
            assertEquals(expected, product[col * 4 + row], 1e-4f)
        }
        assertTrue(a.contentEquals(GpuScaleMath.multiply(a, GpuScaleMath.identity())))
        assertTrue(a.contentEquals(GpuScaleMath.multiply(GpuScaleMath.identity(), a)))
    }

    @Test
    fun downscaleTapsOnlyForARealReduction() {
        val full = GpuScaleMath.CropRect(0f, 0f, 1f, 1f)
        assertEquals(0f to 0f, GpuScaleMath.tapOffset(full, 1280, 1280, 720))
        val (sx, sy) = GpuScaleMath.tapOffset(full, 1280, 256, 144)
        assertEquals(0.25f / 256, sx, 1e-9f)
        assertEquals(0.25f / 144, sy, 1e-9f)
        // A cropped capture counts only the cropped width: 960 source pixels into 960 is still 1:1.
        assertEquals(0f to 0f, GpuScaleMath.tapOffset(GpuScaleMath.CropRect(0.125f, 0f, 0.75f, 1f), 1280, 960, 720))
    }

    private val phone = listOf(
        GpuScaleMath.CaptureMode(4000, 3000, 30),
        GpuScaleMath.CaptureMode(1920, 1080, 30),
        GpuScaleMath.CaptureMode(1280, 720, 30),
        GpuScaleMath.CaptureMode(960, 540, 30),
        GpuScaleMath.CaptureMode(640, 480, 30),
        GpuScaleMath.CaptureMode(320, 240, 30),
    )

    @Test
    fun captureIsTheSmallestSameAspectModeThatCoversTheSession() {
        assertEquals(1280 to 720, GpuScaleMath.chooseCapture(phone, r(1280, 720), 15)!!.let { it.width to it.height })
        assertEquals(960 to 540, GpuScaleMath.chooseCapture(phone, r(854, 480), 15)!!.let { it.width to it.height })
        assertEquals(960 to 540, GpuScaleMath.chooseCapture(phone, r(640, 360), 15)!!.let { it.width to it.height })
        val withSmall = phone + GpuScaleMath.CaptureMode(640, 360, 30)
        assertEquals(640 to 360, GpuScaleMath.chooseCapture(withSmall, r(640, 360), 15)!!.let { it.width to it.height })
    }

    @Test
    fun captureNeverPicksAFourKModeAndRespectsTheFrameRate() {
        val modes = listOf(
            GpuScaleMath.CaptureMode(3840, 2160, 30),
            GpuScaleMath.CaptureMode(1920, 1080, 15),
            GpuScaleMath.CaptureMode(1280, 720, 30),
        )
        // 1920x1080 only reaches 15 FPS: a 24 FPS session captures at 720p.
        assertEquals(1280 to 720, GpuScaleMath.chooseCapture(modes, r(1280, 720), 24)!!.let { it.width to it.height })
        // Nothing reaches 60: the best is still chosen rather than none.
        assertNotNull(GpuScaleMath.chooseCapture(modes, r(1280, 720), 60))
        assertNull(GpuScaleMath.chooseCapture(listOf(GpuScaleMath.CaptureMode(3840, 2160, 30)), r(1280, 720), 15))
        assertNull(GpuScaleMath.chooseCapture(emptyList(), r(1280, 720), 15))
    }

    @Test
    fun aSensorWithoutSixteenByNineCropsOnTheGpuToTheSessionAspect() {
        val only43 = listOf(
            GpuScaleMath.CaptureMode(1280, 960, 30),
            GpuScaleMath.CaptureMode(640, 480, 30),
            GpuScaleMath.CaptureMode(320, 240, 30),
        )
        // 854x480 needs the 1280x960 mode: its 16:9 centre crop (1280x720) covers it; 640x480 (640x360) does not.
        val capture = GpuScaleMath.chooseCapture(only43, r(854, 480), 15)!!
        assertEquals(1280 to 960, capture.width to capture.height)
        val crop = GpuScaleMath.cropFor(capture.width, capture.height, 854, 480)
        assertEquals(0.75f, crop.height, 0.01f)
        // 640x360 fits the 640x480 mode's crop, so the smaller capture wins.
        assertEquals(640 to 480, GpuScaleMath.chooseCapture(only43, r(640, 360), 15)!!.let { it.width to it.height })
    }

    @Test
    fun aCaptureSmallerThanTheSessionIsTheBestEffortChoice() {
        val small = listOf(GpuScaleMath.CaptureMode(640, 360, 30), GpuScaleMath.CaptureMode(320, 180, 30))
        assertEquals(640 to 360, GpuScaleMath.chooseCapture(small, r(1280, 720), 15)!!.let { it.width to it.height })
    }

    @Test
    fun aRungNeedsACaptureThatIsAtLeastAsLargeAfterTheCrop() {
        val capture = GpuScaleMath.CaptureMode(960, 540, 30)
        assertTrue(GpuScaleMath.fitsCapture(capture, 854, 480))
        assertTrue(GpuScaleMath.fitsCapture(capture, 960, 540))
        assertTrue(GpuScaleMath.fitsCapture(capture, 256, 144))
        assertFalse(GpuScaleMath.fitsCapture(capture, 1280, 720)) // would be an upscale
        // A 4:3 capture crops to 16:9: 1280x960 keeps 1280x720, so 1280x720 fits and 1600x900 does not.
        val fourByThree = GpuScaleMath.CaptureMode(1280, 960, 30)
        assertTrue(GpuScaleMath.fitsCapture(fourByThree, 1280, 720))
        assertTrue(GpuScaleMath.fitsCapture(fourByThree, 854, 480))
        assertFalse(GpuScaleMath.fitsCapture(fourByThree, 1600, 900))
        // The rung list a capture can supply: nothing above the capture.
        val rungs = GpuScaleMath.rungSizes(r(1280, 720), 2, 2) { w, h -> GpuScaleMath.fitsCapture(capture, w, h) }
        assertEquals(960 to 540, rungs.first().let { it.width to it.height })
    }

    @Test
    fun gpuModesKeepTheNegotiatedAndFittedSizeInsteadOfTheNearestCameraMode() {
        // A phone whose camera has no 854x480 mode (only 640x480 and 1280x720 here).
        val gpuModes = GpuScaleMath.rungSizes(r(854, 480), 2, 2).map { DeviceVideoMode(it, listOf(15, 24, 30), null) }
        val cameraModes = listOf(
            DeviceVideoMode(r(640, 480), listOf(15, 30), null),
            DeviceVideoMode(r(1280, 720), listOf(15, 30), null),
        )
        val requested = VideoSettings.DEFAULT.copy(resolution = r(854, 480), fps = 15, bitRate = 300_000)
        // The call negotiation keeps the size with GPU rungs and replaces it with camera modes.
        assertEquals(r(854, 480), privateCallCameraVideoSettings(requested, gpuModes).resolution)
        assertEquals(r(640, 480), privateCallCameraVideoSettings(requested, cameraModes).resolution)
        // The Auto fit keeps the header too, and an off-grid request lands on a rung of the same aspect.
        assertEquals(r(854, 480), AutoQualityLadder.fitSettings(requested, gpuModes).resolution)
        val fitted = AutoQualityLadder.fitSettings(requested.copy(resolution = r(900, 506)), gpuModes).resolution
        assertEquals(16.0 / 9, fitted.width.toDouble() / fitted.height, 0.02)
    }

    @Test
    fun offReasonNamesTheFirstBlockingCondition() {
        fun reason(
            on: Boolean = true, auto: Boolean = true, surface: Boolean = true, raw: Boolean = false,
            reference: Boolean = false, gpu: Boolean = true,
        ) = GpuScaleMath.offReason(on, auto, surface, raw, reference, gpu)
        assertNull(reason())
        assertEquals("switch_off", reason(on = false))
        assertEquals("auto_ladder_off", reason(auto = false))
        assertEquals("raw_input", reason(raw = true))
        assertEquals("yuv_input_encoder", reason(surface = false))
        assertEquals("reference_frames", reason(reference = true))
        assertEquals("egl_unsupported", reason(gpu = false))
        assertEquals("switch_off", reason(on = false, auto = false, surface = false))
    }

    @Test
    fun theSwitchIsDebugOnlyAndDefaultOff() {
        assertFalse(GpuScaleFeature.enabled)
        val spec = GpuScaleFeature.spec
        assertFalse(FeatureCatalog.resolve(spec, false, { true }, { true }, debugBuild = false))
        assertFalse(FeatureCatalog.resolve(spec, false, { false }, { null }, debugBuild = true))
        assertTrue(FeatureCatalog.resolve(spec, false, { true }, { null }, debugBuild = true))
    }

    @Test
    fun autoLadderSteppingUsesTheGpuRungsWithoutAnyCameraModeList() {
        val header = r(854, 480)
        val rungs = GpuScaleMath.rungSizes(header, 2, 2)
        val vp9 = StreamProtocol.CODEC_VP9
        val ladder = CallResolutionLadder(
            header, rungs, vp9,
            limits = AutoQualityLadder.callLimits(vp9, AutoEncoderClass.STANDARD, 15),
            startLimit = AutoQualityLadder.callStartLimit(vp9, AutoEncoderClass.STANDARD, 15),
        )
        assertEquals(
            listOf(854 to 480, 640 to 360, 426 to 240, 320 to 180),
            ladder.rungs.map { it.width to it.height },
        )
        // Every rung keeps the 16:9 aspect, so a step never changes the picture shape.
        ladder.rungs.forEach { assertEquals(16.0 / 9, it.width.toDouble() / it.height, 0.02) }
        val start = ladder.start(1_000_000, 0L)
        assertEquals(854 to 480, start.resolution.let { it.width to it.height })
        val down = ladder.onTarget(100_000, 100_000L)
        assertNull(down) // the hold has just started
        val stepped = ladder.onTarget(100_000, 200_000L)
        assertNotNull(stepped)
        assertTrue(stepped!!.resolution.height < 480)
        assertEquals(16.0 / 9, stepped.resolution.width.toDouble() / stepped.resolution.height, 0.02)
    }
}
