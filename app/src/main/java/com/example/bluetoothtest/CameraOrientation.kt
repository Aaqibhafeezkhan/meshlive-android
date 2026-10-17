package com.example.bluetoothtest

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager

object CameraOrientation {
    fun currentCameraRotationDegrees(context: Context, cameraLens: CameraLens): Int {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val cameraId = cameraManager.cameraIdFor(cameraLens)
            ?: error("${cameraLens.displayName} camera is unavailable")
        val sensorOrientation = safeCameraRead("SENSOR_ORIENTATION") {
            cameraManager.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
        } ?: 0
        return relativeRotationDegrees(
            sensorOrientation,
            currentDisplayRotation(context),
            cameraLens,
        )
    }

    internal fun relativeRotationDegrees(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        cameraLens: CameraLens,
    ): Int {
        val displayDirection = if (cameraLens == CameraLens.FRONT) {
            displayRotationDegrees
        } else {
            -displayRotationDegrees
        }
        return (sensorOrientation + displayDirection + 360) % 360
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(context: Context): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation
                ?: context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        } else {
            context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
