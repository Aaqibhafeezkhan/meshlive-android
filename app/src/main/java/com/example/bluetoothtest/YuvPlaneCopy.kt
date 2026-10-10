package com.example.bluetoothtest

import java.nio.ByteBuffer

/** A rectangle of one plane, in that plane's samples. */
data class PlaneRegion(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Pure YUV 4:2:0 plane copies used by the camera encoder's Kotlin paths (the native copies in
 * native_aom_encoder.cpp apply the same bounds rules). Follows libyuv's Android420ToI420 contract
 * (https://chromium.googlesource.com/libyuv/libyuv/+/eeb06a6451ba2e21f8ff272c7b42fc62b9faecc5/include/libyuv/convert.h#915):
 * each plane has its own row stride and pixel stride (1 planar, 2 semi-planar, U and V may share a
 * buffer), chroma is (n + 1) / 2 samples, and the last row of a plane may end at its last sample
 * (Image.Plane buffers are not padded to a full row). Bounds are checked once per plane before any
 * byte is written, so a malformed layout leaves the target untouched instead of failing mid-frame.
 */
internal object YuvPlaneCopy {
    /** Chroma samples of a 4:2:0 plane for [lumaSamples] (libyuv: (n + 1) >> 1). */
    fun chromaSize(lumaSamples: Int): Int = (lumaSamples + 1) / 2

    /** Bytes of one tightly packed I420 frame (Y, then U, then V; chroma (n + 1) / 2). */
    fun i420Bytes(width: Int, height: Int): Int = width * height + 2 * chromaSize(width) * chromaSize(height)

    /**
     * The valid luma region of an image: Image.getCropRect() clipped to the image, with left/top
     * rounded down to even so the 4:2:0 chroma region starts on a whole chroma sample. Null or an
     * empty/out-of-image rectangle means the whole image (the API's default crop).
     */
    fun visibleRegion(imageWidth: Int, imageHeight: Int, crop: IntArray?): PlaneRegion {
        val full = PlaneRegion(0, 0, imageWidth, imageHeight)
        if (crop == null || crop.size != 4) return full
        val left = crop[0].coerceIn(0, imageWidth) and 1.inv()
        val top = crop[1].coerceIn(0, imageHeight) and 1.inv()
        val right = crop[2].coerceIn(0, imageWidth)
        val bottom = crop[3].coerceIn(0, imageHeight)
        if (right - left < 2 || bottom - top < 2) return full
        return PlaneRegion(left, top, right - left, bottom - top)
    }

    /** The region of [visible] (in luma samples) that plane [planeIndex] covers. */
    fun planeRegion(visible: PlaneRegion, planeIndex: Int): PlaneRegion =
        if (planeIndex == 0) {
            visible
        } else {
            PlaneRegion(visible.left / 2, visible.top / 2, chromaSize(visible.width), chromaSize(visible.height))
        }

    /** Largest centred region of a [sourceWidth] x [sourceHeight] plane with the target's aspect ratio. */
    fun centerCrop(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): PlaneRegion =
        if (sourceWidth.toLong() * targetHeight > targetWidth.toLong() * sourceHeight) {
            val width = (sourceHeight.toLong() * targetWidth / targetHeight).toInt()
            PlaneRegion((sourceWidth - width) / 2, 0, width, sourceHeight)
        } else {
            val height = (sourceWidth.toLong() * targetHeight / targetWidth).toInt()
            PlaneRegion(0, (sourceHeight - height) / 2, sourceWidth, height)
        }

    /** [centerCrop] inside [area] (offsets are added). */
    fun centerCropWithin(area: PlaneRegion, targetWidth: Int, targetHeight: Int): PlaneRegion =
        centerCrop(area.width, area.height, targetWidth, targetHeight).let {
            it.copy(left = area.left + it.left, top = area.top + it.top)
        }

    /** Index one past the last byte a plane access touches, or -1 when the layout is invalid. */
    fun planeEnd(offset: Int, rowStride: Int, pixelStride: Int, left: Int, top: Int, width: Int, height: Int): Long {
        if (offset < 0 || rowStride <= 0 || pixelStride <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0) return -1
        val rowBytes = (left + width - 1).toLong() * pixelStride + 1
        if (rowBytes > rowStride && height > 1) return -1
        return offset + (top + height - 1).toLong() * rowStride + rowBytes
    }

    /**
     * Copies [region] of a source plane into a [targetWidth] x [targetHeight] target plane, scaling
     * by nearest sample when the sizes differ. Offsets are absolute buffer indices (callers pass
     * buffer.position()). Returns false, writing nothing, when either plane would be read or written
     * outside its buffer's limit.
     */
    fun copyPlane(
        source: ByteBuffer,
        sourceOffset: Int,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        region: PlaneRegion,
        target: ByteBuffer,
        targetOffset: Int,
        targetRowStride: Int,
        targetPixelStride: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Boolean {
        val sourceEnd = planeEnd(sourceOffset, sourceRowStride, sourcePixelStride, region.left, region.top,
            region.width, region.height)
        val targetEnd = planeEnd(targetOffset, targetRowStride, targetPixelStride, 0, 0, targetWidth, targetHeight)
        if (sourceEnd < 0 || targetEnd < 0 || sourceEnd > source.limit() || targetEnd > target.limit()) return false
        if (target.isReadOnly) return false
        val sameWidth = region.width == targetWidth
        repeat(targetHeight) { row ->
            val sourceRow = region.top + (row.toLong() * region.height / targetHeight).toInt()
            val sourceRowStart = sourceOffset + sourceRow * sourceRowStride
            val targetRowStart = targetOffset + row * targetRowStride
            if (sameWidth && sourcePixelStride == 1 && targetPixelStride == 1) {
                val rowBuffer = source.duplicate().apply {
                    limit(sourceRowStart + region.left + targetWidth)
                    position(sourceRowStart + region.left)
                }
                target.duplicate().apply { position(targetRowStart) }.put(rowBuffer)
                return@repeat
            }
            repeat(targetWidth) { column ->
                val sourceColumn = region.left + (column.toLong() * region.width / targetWidth).toInt()
                target.put(
                    targetRowStart + column * targetPixelStride,
                    source.get(sourceRowStart + sourceColumn * sourcePixelStride),
                )
            }
        }
        return true
    }
}
