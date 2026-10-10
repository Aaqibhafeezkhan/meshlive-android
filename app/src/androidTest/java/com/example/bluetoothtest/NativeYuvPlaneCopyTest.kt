package com.example.bluetoothtest

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeYuvPlaneCopyTest {
    @Test
    fun stridedCopiesPreservePixelsPaddingAndBufferPositions() {
        for (sourcePixelStride in 1..2) for (targetPixelStride in 1..2) {
            val sourceStride = 5 * sourcePixelStride + 3
            val targetStride = 5 * targetPixelStride + 4
            val source = ByteBuffer.allocateDirect(100)
            val target = ByteBuffer.allocateDirect(100)
            for (index in 0 until 100) target.put(index, 99)
            for (row in 0 until 3) for (column in 0 until 5) {
                source.put(2 + row * sourceStride + column * sourcePixelStride, (row * 5 + column).toByte())
            }
            source.position(2)
            target.position(3)
            assertTrue(NativeAomBridge.nativeCopyPlane(
                source, 2, 100, sourceStride, sourcePixelStride,
                target, 3, 100, targetStride, targetPixelStride, 5, 3,
            ))
            for (index in 0 until 100) {
                val relative = index - 3
                val row = relative / targetStride
                val columnBytes = relative % targetStride
                val column = columnBytes / targetPixelStride
                val pixel = relative >= 0 && row < 3 && column < 5 && columnBytes % targetPixelStride == 0
                assertEquals(if (pixel) (row * 5 + column).toByte() else 99.toByte(), target.get(index))
            }
            assertEquals(2, source.position())
            assertEquals(3, target.position())
        }
    }

    @Test
    fun exactLastPixelDoesNotRequireRowPadding() {
        val source = ByteBuffer.allocateDirect(25)
        val target = ByteBuffer.allocateDirect(17)
        assertTrue(NativeAomBridge.nativeCopyPlane(source, 2, 25, 8, 2, target, 1, 17, 6, 1, 4, 3))
    }

    @Test
    fun invalidBoundsRejectBeforeWritingAnyPixels() {
        val source = ByteBuffer.allocateDirect(25)
        val target = ByteBuffer.allocateDirect(17)
        for (index in 0 until 17) target.put(index, 99)
        assertFalse(NativeAomBridge.nativeCopyPlane(source, 2, 24, 8, 2, target, 1, 17, 6, 1, 4, 3))
        assertFalse(NativeAomBridge.nativeCopyPlane(source, 2, 26, 8, 2, target, 1, 17, 6, 1, 4, 3))
        assertFalse(NativeAomBridge.nativeCopyPlane(source, 2, 25, 8, 2, target, 1, 16, 6, 1, 4, 3))
        assertFalse(NativeAomBridge.nativeCopyPlane(source, 2, 25, 6, 2, target, 1, 17, 6, 1, 4, 3))
        assertFalse(NativeAomBridge.nativeCopyPlane(source, 2, 25, 8, 2, target, 1, 17, 6, 0, 4, 3))
        assertFalse(NativeAomBridge.nativeCopyPlane(ByteBuffer.allocate(25), 2, 25, 8, 2, target, 1, 17, 6, 1, 4, 3))
        for (index in 0 until 17) assertEquals(99.toByte(), target.get(index))
    }
}
