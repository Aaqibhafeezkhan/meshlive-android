package com.example.bluetoothtest

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Corner a call picture-in-picture rests in after a drag. */
internal enum class CallPipCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Free area for the picture-in-picture inside the call screen, in pixels: inside the system bars,
 * below the top status row and above the bottom call controls.
 */
internal data class CallPipArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

internal object CallPipLayout {
    /** Fraction of the short screen side used by the picture-in-picture width. */
    const val WIDTH_FRACTION = 0.28f
    const val MIN_WIDTH_DP = 88f
    const val MAX_WIDTH_DP = 132f
    /** Portrait 3:4, the shape of a portrait camera preview. */
    const val HEIGHT_RATIO = 4f / 3f
    /** A fling continues this long before the nearest corner is chosen. */
    const val FLING_PROJECTION_S = 0.15f

    /** Width and height in pixels for a screen whose short side is [shortSidePx]. */
    fun sizePx(shortSidePx: Int, density: Float): Pair<Int, Int> {
        val width = (shortSidePx * WIDTH_FRACTION)
            .coerceIn(MIN_WIDTH_DP * density, MAX_WIDTH_DP * density)
            .coerceAtMost(shortSidePx.toFloat().coerceAtLeast(1f))
            .roundToInt()
        return width to (width * HEIGHT_RATIO).roundToInt()
    }

    /** Top-left position of a [width] x [height] picture-in-picture resting in [corner]. */
    fun cornerPosition(corner: CallPipCorner, width: Int, height: Int, area: CallPipArea): Pair<Float, Float> {
        val maxX = (area.right - width).coerceAtLeast(area.left)
        val maxY = (area.bottom - height).coerceAtLeast(area.top)
        val x = if (corner == CallPipCorner.TOP_LEFT || corner == CallPipCorner.BOTTOM_LEFT) area.left else maxX
        val y = if (corner == CallPipCorner.TOP_LEFT || corner == CallPipCorner.TOP_RIGHT) area.top else maxY
        return x to y
    }

    /** Keeps a dragged picture-in-picture fully inside [area] (top-left anchored when it cannot fit). */
    fun clampPosition(x: Float, y: Float, width: Int, height: Int, area: CallPipArea): Pair<Float, Float> {
        val maxX = (area.right - width).coerceAtLeast(area.left)
        val maxY = (area.bottom - height).coerceAtLeast(area.top)
        return x.coerceIn(area.left, maxX) to y.coerceIn(area.top, maxY)
    }

    /**
     * The corner nearest to where the picture-in-picture is released, after projecting a fling of
     * [velocityX]/[velocityY] pixels per second for [FLING_PROJECTION_S].
     */
    fun snapCorner(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        area: CallPipArea,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
    ): CallPipCorner {
        val centerX = x + width / 2f + velocityX * FLING_PROJECTION_S
        val centerY = y + height / 2f + velocityY * FLING_PROJECTION_S
        val left = centerX < area.centerX
        val top = centerY < area.centerY
        return when {
            top && left -> CallPipCorner.TOP_LEFT
            top -> CallPipCorner.TOP_RIGHT
            left -> CallPipCorner.BOTTOM_LEFT
            else -> CallPipCorner.BOTTOM_RIGHT
        }
    }
}

/** What the call screen shows over the other person's video. */
internal enum class CallRemoteVideoState { NONE, CONNECTING, PAUSED, FROZEN }

internal const val CALL_REMOTE_VIDEO_FROZEN_AFTER_MS = 1_000L

/**
 * Overlay state for the other person's video. [lastFrameAgeMs] is the time since the last frame
 * reached the screen, or null before any frame has.
 */
internal fun callRemoteVideoState(
    mediaPhase: Boolean,
    remoteCameraEnabled: Boolean,
    awaitingFrame: Boolean,
    lastFrameAgeMs: Long?,
    frozenAfterMs: Long = CALL_REMOTE_VIDEO_FROZEN_AFTER_MS,
): CallRemoteVideoState = when {
    !mediaPhase -> CallRemoteVideoState.NONE
    !remoteCameraEnabled -> CallRemoteVideoState.PAUSED
    awaitingFrame || lastFrameAgeMs == null -> CallRemoteVideoState.CONNECTING
    lastFrameAgeMs > frozenAfterMs -> CallRemoteVideoState.FROZEN
    else -> CallRemoteVideoState.NONE
}

private const val GENERIC_PEER_NAME = "Nearby device"
private const val PEER_NAME_MAX_CHARS = 32
private const val PEER_SHORT_ID_CHARS = 8

/**
 * Friendly label for a person in the call list. The discovery advertisement carries no user name,
 * so this is the phone's Bluetooth name when Android reports one; otherwise a short node ID.
 * Neither is authenticated: the call link still verifies the full node identity.
 */
internal fun privateCallPeerDisplayName(advertisedName: String?, nodePrefixHex: String): String {
    val name = advertisedName?.trim()
        ?.filter { !it.isISOControl() }
        ?.takeIf { it.isNotEmpty() && !it.equals(GENERIC_PEER_NAME, ignoreCase = true) }
    return name?.take(PEER_NAME_MAX_CHARS) ?: "Nearby phone · ${nodePrefixHex.lowercase().take(PEER_SHORT_ID_CHARS)}"
}

/** Second list line: transport and, when a name is shown, the short ID that tells two phones apart. */
internal fun privateCallPeerSubtitle(advertisedName: String?, nodePrefixHex: String, transportLabel: String): String {
    val named = privateCallPeerDisplayName(advertisedName, nodePrefixHex) !=
        privateCallPeerDisplayName(null, nodePrefixHex)
    return if (named) "$transportLabel · ${nodePrefixHex.lowercase().take(PEER_SHORT_ID_CHARS)}" else transportLabel
}

/** Incoming-call label from the authenticated peer node label (12 hex characters) or a fallback. */
internal fun privateCallIncomingPeerLabel(peerLabel: String): String =
    if (peerLabel.matches(Regex("[0-9a-fA-F]{8,}"))) privateCallPeerDisplayName(null, peerLabel) else peerLabel

/** Subsampling step that keeps a raw self-view thumbnail near [targetLongSide] pixels. */
internal fun rawSelfViewStep(width: Int, height: Int, targetLongSide: Int = 320): Int =
    maxOf(1, ceil(maxOf(width, height) / targetLongSide.toDouble()).toInt())

/**
 * The looping raw fixture frame the encoder is reading at [nowNs], using the same arithmetic as
 * the raw feed (frame zero at [startedNs], rounded to the nearest frame).
 */
internal fun rawFixtureFrameIndex(nowNs: Long, startedNs: Long, fps: Int, frameCount: Long): Long {
    if (frameCount <= 0L || fps <= 0) return 0L
    val sourceFrame = (maxOf(0L, nowNs - startedNs) * fps / 1_000_000_000.0).roundToLong()
    return sourceFrame % frameCount
}

/**
 * Converts every [step]th pixel of an I420 frame (BT.601 limited range) to opaque ARGB in [out],
 * which holds (width / step) x (height / step) pixels.
 */
internal fun i420ToArgbSubsampled(frame: ByteArray, width: Int, height: Int, step: Int, out: IntArray) {
    val outWidth = width / step
    val outHeight = height / step
    require(out.size >= outWidth * outHeight) { "Output too small" }
    val chromaWidth = width / 2
    val uOffset = width * height
    val vOffset = uOffset + chromaWidth * (height / 2)
    var index = 0
    for (row in 0 until outHeight) {
        val y = row * step
        val lumaRow = y * width
        val chromaRow = (y / 2) * chromaWidth
        for (column in 0 until outWidth) {
            val x = column * step
            val c = (frame[lumaRow + x].toInt() and 0xFF) - 16
            val d = (frame[uOffset + chromaRow + x / 2].toInt() and 0xFF) - 128
            val e = (frame[vOffset + chromaRow + x / 2].toInt() and 0xFF) - 128
            val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
            out[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
