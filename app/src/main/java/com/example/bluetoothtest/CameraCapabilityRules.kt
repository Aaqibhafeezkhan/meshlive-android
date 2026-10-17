package com.example.bluetoothtest

import java.nio.ByteBuffer

/**
 * `VideoCapabilities.isSizeSupported` can be false for a size that configures (V-08): CameraX
 * MediaCodecInfoReportIncorrectInfoQuirk lists encoders that report a 1920x1072 maximum although
 * 1920x1080 works. A size within [NEAR_PX] of the reported maximum that the check rejects is a
 * candidate; it is only allowed after a probe-configure succeeded, and only with the Experimental
 * switch. Requested settings and the acceptance gates are never changed by this.
 */
internal object SizeSupportCheck {
    const val NEAR_PX = 8

    data class Decision(val allowed: Boolean, val candidate: Boolean, val probed: Boolean?)

    /** True when the size is rejected but not more than [NEAR_PX] beyond the reported maximum in each direction. */
    fun isFalseNegativeCandidate(
        reportedSupported: Boolean,
        width: Int,
        height: Int,
        reportedMaxWidth: Int?,
        reportedMaxHeight: Int?,
    ): Boolean {
        if (reportedSupported) return false
        if (reportedMaxWidth == null || reportedMaxHeight == null) return false
        return width <= reportedMaxWidth + NEAR_PX && height <= reportedMaxHeight + NEAR_PX
    }

    fun decide(reportedSupported: Boolean, candidate: Boolean, probeEnabled: Boolean, probe: () -> Boolean): Decision = when {
        reportedSupported -> Decision(true, false, null)
        !candidate -> Decision(false, false, null)
        !probeEnabled -> Decision(false, true, null)
        else -> {
            val ok = runCatching(probe).getOrDefault(false)
            Decision(ok, true, ok)
        }
    }

    fun candidateLine(encoder: String, width: Int, height: Int, maxWidth: Int?, maxHeight: Int?, probeEnabled: Boolean, probed: Boolean?): String =
        "BENCHMARK capability_size_candidate: encoder=$encoder | size=${width}x$height | reportedMax=${maxWidth ?: "?"}x${maxHeight ?: "?"} | " +
            "isSizeSupported=false | probeEnabled=$probeEnabled | probeConfigure=${probed ?: "not_run"} | " +
            "wouldAllow=${probed ?: "unknown"}"
}

/**
 * Encoder width/height alignment against the requested size (V-10). WebRTC and Chromium assume 16
 * (crbug 1084702); FFmpeg documents c2.mtk H.264/HEVC needing 16x16; the rungs 426x240, 640x360 and
 * 854x480 are not all 16-aligned. This is a diagnostic: coded sizes are protocol-visible, so changing
 * them waits for the hardware-ladder design record. ByteBuffer input already honours the input
 * Image's plane strides (getInputImage), so stride and slice height need no handling here.
 */
internal object EncoderAlignment {
    data class Report(
        val widthAlignment: Int,
        val heightAlignment: Int,
        val width: Int,
        val height: Int,
    ) {
        val alignedToEncoder: Boolean
            get() = width % widthAlignment.coerceAtLeast(1) == 0 && height % heightAlignment.coerceAtLeast(1) == 0
        val aligned16: Boolean get() = width % 16 == 0 && height % 16 == 0

        /** The 16-pixel rule matters when the encoder does not advertise 16 but the size is not a multiple. */
        val risk16: Boolean
            get() = !aligned16 && (widthAlignment < 16 || heightAlignment < 16)
    }

    fun line(encoder: String, report: Report): String =
        "BENCHMARK encoder_alignment: encoder=$encoder | requested=${report.width}x${report.height} | " +
            "widthAlignment=${report.widthAlignment} | heightAlignment=${report.heightAlignment} | " +
            "alignedToEncoder=${report.alignedToEncoder} | aligned16=${report.aligned16} | risk16=${report.risk16} | " +
            "action=none(coded sizes are protocol-visible)"
}

/** Android's real display size can report 16x16 (CameraX SmallDisplaySizeQuirk, b/287341266). */
internal object DisplaySizeGuard {
    const val MIN_PX = 100

    /** The real display size, or [fallbackWidth] x [fallbackHeight] (WindowMetrics) when it is implausibly small. */
    fun effective(realWidth: Int, realHeight: Int, fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> =
        if (realWidth < MIN_PX || realHeight < MIN_PX) fallbackWidth to fallbackHeight else realWidth to realHeight
}

/**
 * V-24 (Experimental, `test_yuv_tail_clamp`): a YUV plane whose buffer is shorter than its last
 * sample by a few bytes (a HAL that does not map the final chroma samples) is normally refused
 * (YuvPlaneCopy fails closed and the frame is dropped). With the switch on, the missing tail of
 * the LAST row only is replaced by the last readable sample, up to [maxShortfall] bytes; anything
 * larger, or any problem in the target, still fails closed. No failing phone was observed, so this
 * is off by default.
 */
internal object YuvTailClamp {
    fun maxShortfall(sourcePixelStride: Int): Int = 2 * sourcePixelStride.coerceAtLeast(1)

    /** Bytes the plane access needs beyond the buffer limit (<= 0 means it fits), or null for an invalid layout. */
    fun shortfall(
        sourceLimit: Int,
        sourceOffset: Int,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        region: PlaneRegion,
    ): Long? {
        val end = YuvPlaneCopy.planeEnd(
            sourceOffset, sourceRowStride, sourcePixelStride, region.left, region.top, region.width, region.height,
        )
        if (end < 0) return null
        return end - sourceLimit
    }

    fun copyPlaneClamped(
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
        val missing = shortfall(source.limit(), sourceOffset, sourceRowStride, sourcePixelStride, region) ?: return false
        if (missing !in 1..maxShortfall(sourcePixelStride)) return false
        val targetEnd = YuvPlaneCopy.planeEnd(targetOffset, targetRowStride, targetPixelStride, 0, 0, targetWidth, targetHeight)
        if (targetEnd < 0 || targetEnd > target.limit() || target.isReadOnly) return false
        val lastReadable = source.limit() - 1
        if (lastReadable < sourceOffset) return false
        repeat(targetHeight) { row ->
            val sourceRow = region.top + (row.toLong() * region.height / targetHeight).toInt()
            val sourceRowStart = sourceOffset + sourceRow * sourceRowStride
            val targetRowStart = targetOffset + row * targetRowStride
            repeat(targetWidth) { column ->
                val sourceColumn = region.left + (column.toLong() * region.width / targetWidth).toInt()
                val index = minOf(sourceRowStart + sourceColumn * sourcePixelStride, lastReadable)
                target.put(targetRowStart + column * targetPixelStride, source.get(index))
            }
        }
        return true
    }
}
