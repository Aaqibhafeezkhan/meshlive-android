package com.example.bluetoothtest

import android.graphics.Matrix
import android.view.TextureView
import android.util.Log
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

enum class VideoScaleMode(val label: String, val description: String) {
    FILL(
        "Fill screen",
        "Fills the screen without stretching; excess edges are cropped.",
    ),
    FIT(
        "Show full frame",
        "Shows every edge without stretching; black bars appear when ratios differ.",
    ),
    ;

    companion object {
        fun fromStoredValue(value: String?): VideoScaleMode = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: FILL

        fun fromUnifiedOrLegacyValues(
            unifiedValue: String?,
            senderValue: String?,
            receiverValue: String?,
        ): VideoScaleMode {
            if (unifiedValue != null) return fromStoredValue(unifiedValue)
            return if (fromStoredValue(senderValue) == FIT || fromStoredValue(receiverValue) == FIT) {
                FIT
            } else {
                FILL
            }
        }
    }
}

object VideoSurfaceTransform {
    fun apply(
        textureView: TextureView,
        encodedWidth: Int,
        encodedHeight: Int,
        rotationDegrees: Int,
        scaleMode: VideoScaleMode,
        debugTraceSession: String? = null,
    ) {
        if (textureView.width == 0 || textureView.height == 0) return
        val matrix = Matrix().apply {
            setValues(
                calculateMatrixValues(
                    textureView.width,
                    textureView.height,
                    encodedWidth,
                    encodedHeight,
                    rotationDegrees,
                    scaleMode,
                ),
            )
        }
        textureView.setTransform(matrix)
        if (BuildConfig.DEBUG && debugTraceSession != null) {
            val location = IntArray(2)
            textureView.getLocationOnScreen(location)
            Log.i("BluetoothVideo", "BENCHMARK viewport v1 " + JSONObject()
                .put("session", debugTraceSession).put("pid", android.os.Process.myPid())
                .put("clock", "System.nanoTime").put("at_ns", System.nanoTime())
                .put("view_id", textureView.id).put("x", location[0]).put("y", location[1])
                .put("width", textureView.width).put("height", textureView.height)
                .put("encoded_width", encodedWidth).put("encoded_height", encodedHeight)
                .put("rotation", rotationDegrees).put("mirror", false).put("scale_mode", scaleMode.name)
                .toString())
        }
    }

    internal fun calculateMatrixValues(
        viewWidth: Int,
        viewHeight: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        rotationDegrees: Int,
        scaleMode: VideoScaleMode,
    ): FloatArray {
        require(viewWidth > 0 && viewHeight > 0)
        require(encodedWidth > 0 && encodedHeight > 0)
        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation % 90 == 0) { "Video rotation must be a multiple of 90 degrees" }
        val rotatedWidth = if (rotation == 90 || rotation == 270) encodedHeight else encodedWidth
        val rotatedHeight = if (rotation == 90 || rotation == 270) encodedWidth else encodedHeight
        val widthScale = viewWidth.toFloat() / rotatedWidth
        val heightScale = viewHeight.toFloat() / rotatedHeight
        val scale = if (scaleMode == VideoScaleMode.FILL) {
            max(widthScale, heightScale)
        } else {
            min(widthScale, heightScale)
        }
        val left = (viewWidth - rotatedWidth * scale) / 2f
        val top = (viewHeight - rotatedHeight * scale) / 2f
        val encodedToViewX = scale * encodedWidth / viewWidth
        val encodedToViewY = scale * encodedHeight / viewHeight

        return when (rotation) {
            0 -> floatArrayOf(
                encodedToViewX, 0f, left,
                0f, encodedToViewY, top,
                0f, 0f, 1f,
            )
            90 -> floatArrayOf(
                0f, -encodedToViewY, left + encodedHeight * scale,
                encodedToViewX, 0f, top,
                0f, 0f, 1f,
            )
            180 -> floatArrayOf(
                -encodedToViewX, 0f, left + encodedWidth * scale,
                0f, -encodedToViewY, top + encodedHeight * scale,
                0f, 0f, 1f,
            )
            else -> floatArrayOf(
                0f, encodedToViewY, left,
                -encodedToViewX, 0f, top + encodedWidth * scale,
                0f, 0f, 1f,
            )
        }
    }
}
