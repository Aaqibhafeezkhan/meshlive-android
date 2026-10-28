package com.example.bluetoothtest

import kotlin.math.max

/**
 * How the other person's call video is framed (default on; DEBUG control arm `test_remote_video_fit=false`).
 *
 * Filling a portrait phone with a landscape camera (a laptop, or a phone held sideways) crops away most of the
 * picture. When the video's displayed shape and the view's shape differ strongly, the whole picture is shown
 * centred with plain black bars (FIT); when they are close (portrait to portrait) it still fills the view (FILL).
 * A double-tap on the large video toggles the two for the rest of the call. The activity is never rotated, and
 * the self-view picture-in-picture is unchanged. Presentation only: no decoder, surface or wire change.
 */
internal object RemoteVideoFit {
    /**
     * Shapes this far apart (longer aspect over shorter aspect) are shown whole. A 16:9 landscape video on a
     * 9:19.5 portrait phone is 3.9, a square one 2.2; a 9:16 portrait video on the same phone is only 1.2.
     */
    const val STRONG_MISMATCH = 1.8

    /** Shapes within this ratio of square count as neither portrait nor landscape. */
    const val NEAR_SQUARE = 1.15

    /** The video's displayed shape (width / height) after the sender's quarter-turn rotation. */
    fun displayAspect(encodedWidth: Int, encodedHeight: Int, rotationDegrees: Int): Double {
        require(encodedWidth > 0 && encodedHeight > 0)
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val quarter = rotation == 90 || rotation == 270
        val width = if (quarter) encodedHeight else encodedWidth
        val height = if (quarter) encodedWidth else encodedHeight
        return width.toDouble() / height
    }

    /** The automatic choice for a video of [videoAspect] in a view of [viewWidth] x [viewHeight]. */
    fun automaticMode(videoAspect: Double, viewWidth: Int, viewHeight: Int): VideoScaleMode {
        if (viewWidth <= 0 || viewHeight <= 0 || videoAspect <= 0.0) return VideoScaleMode.FILL
        val viewAspect = viewWidth.toDouble() / viewHeight
        val ratio = max(videoAspect / viewAspect, viewAspect / videoAspect)
        if (ratio >= STRONG_MISMATCH) return VideoScaleMode.FIT
        val oppositeShapes = (videoAspect >= NEAR_SQUARE && viewAspect <= 1 / NEAR_SQUARE) ||
            (videoAspect <= 1 / NEAR_SQUARE && viewAspect >= NEAR_SQUARE)
        return if (oppositeShapes) VideoScaleMode.FIT else VideoScaleMode.FILL
    }

    /**
     * The mode to draw with. [chosenMode] is the app-wide framing setting: "Show full frame" always fits.
     * [override] is this call's double-tap choice, which wins.
     */
    fun mode(
        enabled: Boolean,
        chosenMode: VideoScaleMode,
        override: VideoScaleMode?,
        encodedWidth: Int,
        encodedHeight: Int,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): VideoScaleMode {
        if (!enabled) return chosenMode
        if (override != null) return override
        if (chosenMode == VideoScaleMode.FIT) return VideoScaleMode.FIT
        return automaticMode(displayAspect(encodedWidth, encodedHeight, rotationDegrees), viewWidth, viewHeight)
    }

    fun toggled(current: VideoScaleMode): VideoScaleMode =
        if (current == VideoScaleMode.FIT) VideoScaleMode.FILL else VideoScaleMode.FIT
}

/** One call's double-tap choice; cleared when the call ends. */
internal class RemoteVideoFitState {
    var override: VideoScaleMode? = null
        private set

    /** Flips what is shown now ([current]) and keeps it for the rest of the call. */
    fun toggle(current: VideoScaleMode): VideoScaleMode = RemoteVideoFit.toggled(current).also { override = it }

    fun reset() {
        override = null
    }
}
