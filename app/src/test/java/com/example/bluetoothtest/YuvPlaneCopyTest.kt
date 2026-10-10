package com.example.bluetoothtest

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic Image.Plane layouts (libyuv Android420ToI420 contract): row padding, interleaved chroma
 * sharing one buffer (NV12/NV21 views), buffers that end at the last sample, crop and scaling.
 */
class YuvPlaneCopyTest {
    /** A plane whose sample (x, y) is [value]; [rowStride] >= the row's bytes, no padding after the last sample. */
    private fun plane(width: Int, height: Int, rowStride: Int, pixelStride: Int, value: (Int, Int) -> Int): ByteBuffer {
        val size = (height - 1) * rowStride + (width - 1) * pixelStride + 1
        val bytes = ByteArray(size) { 0x7f }
        for (y in 0 until height) for (x in 0 until width) bytes[y * rowStride + x * pixelStride] = value(x, y).toByte()
        return ByteBuffer.allocateDirect(size).put(bytes).apply { flip() }
    }

    private fun i420Row(target: ByteBuffer, offset: Int, width: Int): IntArray =
        IntArray(width) { target.get(offset + it).toInt() and 0xff }

    @Test
    fun paddedPlanarRowsCopyExactly() {
        val source = plane(6, 4, rowStride = 16, pixelStride = 1) { x, y -> y * 10 + x }
        val target = ByteBuffer.allocateDirect(24)
        assertTrue(YuvPlaneCopy.copyPlane(source, 0, 16, 1, PlaneRegion(0, 0, 6, 4), target, 0, 6, 1, 6, 4))
        for (y in 0 until 4) assertArrayEquals(IntArray(6) { y * 10 + it }, i420Row(target, y * 6, 6))
    }

    @Test
    fun interleavedChromaViewsOfOneBufferEndingAtTheLastSample() {
        // NV12 memory: U at even bytes, V at odd bytes; the U view stops one byte before V's last sample.
        val width = 4
        val height = 3
        val rowStride = 8
        val shared = ByteArray((height - 1) * rowStride + (width - 1) * 2 + 2)
        for (y in 0 until height) for (x in 0 until width) {
            shared[y * rowStride + x * 2] = (100 + y * 10 + x).toByte()
            shared[y * rowStride + x * 2 + 1] = (200 + y * 10 + x).toByte()
        }
        val u = ByteBuffer.allocateDirect(shared.size - 1).put(shared, 0, shared.size - 1).apply { flip() }
        val v = ByteBuffer.allocateDirect(shared.size - 1).put(shared, 1, shared.size - 1).apply { flip() }
        val target = ByteBuffer.allocateDirect(width * height * 2)
        assertTrue(YuvPlaneCopy.copyPlane(u, 0, rowStride, 2, PlaneRegion(0, 0, width, height), target, 0, width, 1, width, height))
        assertTrue(YuvPlaneCopy.copyPlane(v, 0, rowStride, 2, PlaneRegion(0, 0, width, height), target, 12, width, 1, width, height))
        assertArrayEquals(intArrayOf(120, 121, 122, 123), i420Row(target, 8, 4))
        assertArrayEquals(intArrayOf(220, 221, 222, 223), i420Row(target, 20, 4))
    }

    @Test
    fun semiPlanarTargetAndSourceOffsetAreHonoured() {
        val source = plane(4, 2, rowStride = 4, pixelStride = 1) { x, y -> 1 + y * 4 + x }
        val withPrefix = ByteBuffer.allocateDirect(source.limit() + 3).apply {
            put(byteArrayOf(9, 9, 9)); put(source.duplicate()); flip()
        }
        val target = ByteBuffer.allocateDirect(2 * 8)
        assertTrue(YuvPlaneCopy.copyPlane(withPrefix, 3, 4, 1, PlaneRegion(0, 0, 4, 2), target, 1, 8, 2, 4, 2))
        assertEquals(1, target.get(1).toInt())
        assertEquals(4, target.get(7).toInt())
        assertEquals(5, target.get(9).toInt())
        assertEquals(0, target.get(0).toInt()) // untouched interleaved neighbour
    }

    @Test
    fun outOfBoundsLayoutsWriteNothing() {
        val source = plane(4, 4, rowStride = 4, pixelStride = 1) { _, _ -> 5 }
        val target = ByteBuffer.allocateDirect(16)
        // Claimed row stride runs past the buffer on the last row.
        assertFalse(YuvPlaneCopy.copyPlane(source, 0, 5, 1, PlaneRegion(0, 0, 4, 4), target, 0, 4, 1, 4, 4))
        // A region past the plane's right edge.
        assertFalse(YuvPlaneCopy.copyPlane(source, 0, 4, 1, PlaneRegion(2, 0, 4, 4), target, 0, 4, 1, 4, 4))
        // A target too small.
        assertFalse(YuvPlaneCopy.copyPlane(source, 0, 4, 1, PlaneRegion(0, 0, 4, 4), ByteBuffer.allocateDirect(15), 0, 4, 1, 4, 4))
        // Read-only target.
        assertFalse(YuvPlaneCopy.copyPlane(source, 0, 4, 1, PlaneRegion(0, 0, 4, 4), target.asReadOnlyBuffer(), 0, 4, 1, 4, 4))
        assertTrue((0 until 16).all { target.get(it).toInt() == 0 })
    }

    @Test
    fun centreCropAndNearestSampleScaling() {
        // 16:9 source 32x18 to a 4:3 target: the centre 24x18 columns.
        assertEquals(PlaneRegion(4, 0, 24, 18), YuvPlaneCopy.centerCrop(32, 18, 640, 480))
        assertEquals(PlaneRegion(0, 3, 24, 12), YuvPlaneCopy.centerCrop(24, 18, 32, 16))
        val source = plane(8, 4, rowStride = 8, pixelStride = 1) { x, y -> y * 8 + x }
        val target = ByteBuffer.allocateDirect(8)
        assertTrue(YuvPlaneCopy.copyPlane(source, 0, 8, 1, PlaneRegion(0, 0, 8, 4), target, 0, 4, 1, 4, 2))
        assertArrayEquals(intArrayOf(0, 2, 4, 6), i420Row(target, 0, 4))
        assertArrayEquals(intArrayOf(16, 18, 20, 22), i420Row(target, 4, 4))
    }

    @Test
    fun cropRectIsClippedEvenAlignedAndMappedToChroma() {
        assertEquals(PlaneRegion(0, 0, 640, 480), YuvPlaneCopy.visibleRegion(640, 480, null))
        assertEquals(PlaneRegion(0, 0, 640, 480), YuvPlaneCopy.visibleRegion(640, 480, intArrayOf(0, 0, 640, 480)))
        // Odd left/top round down to even; the right/bottom edge is clipped to the image.
        val visible = YuvPlaneCopy.visibleRegion(640, 480, intArrayOf(3, 5, 700, 400))
        assertEquals(PlaneRegion(2, 4, 638, 396), visible)
        assertEquals(PlaneRegion(1, 2, 319, 198), YuvPlaneCopy.planeRegion(visible, 1))
        // Degenerate rectangles fall back to the whole image.
        assertEquals(PlaneRegion(0, 0, 640, 480), YuvPlaneCopy.visibleRegion(640, 480, intArrayOf(10, 10, 11, 300)))
        assertEquals(3, YuvPlaneCopy.chromaSize(5))
        assertEquals(426 * 240 + 2 * 213 * 120, YuvPlaneCopy.i420Bytes(426, 240))
        assertEquals(15 + 2 * 3 * 2, YuvPlaneCopy.i420Bytes(5, 3))
        assertEquals(PlaneRegion(6, 4, 24, 18), YuvPlaneCopy.centerCropWithin(PlaneRegion(2, 4, 32, 18), 640, 480))
    }

    @Test
    fun fullImageCropMatchesTheFormerPerPlaneCrop() {
        // The former code cropped each plane from its own dimensions (width / 2 for chroma).
        for ((w, h) in listOf(1920 to 1080, 1280 to 720, 640 to 480, 1440 to 1080)) for ((tw, th) in listOf(640 to 480, 426 to 240, 1280 to 720)) {
            for (planeIndex in 0..2) {
                val divisor = if (planeIndex == 0) 1 else 2
                val former = YuvPlaneCopy.centerCrop(w / divisor, h / divisor, tw / divisor, th / divisor)
                val now = YuvPlaneCopy.centerCropWithin(
                    YuvPlaneCopy.planeRegion(YuvPlaneCopy.visibleRegion(w, h, null), planeIndex),
                    if (planeIndex == 0) tw else YuvPlaneCopy.chromaSize(tw),
                    if (planeIndex == 0) th else YuvPlaneCopy.chromaSize(th),
                )
                assertEquals("$w x $h -> $tw x $th plane $planeIndex", former, now)
            }
        }
    }

    @Test
    fun yuvCropRectEntryIsOnEverywhereAndSwitchable() {
        val traits = DeviceTraits("qcom", "taro", 36)
        assertTrue(YuvPolicy.cropRectHonoured(traits))
        assertFalse(DeviceFamilyPolicy.resolve(traits, setOf("yuv-image-crop-rect")).has<HonourImageCropRect>())
    }
}
