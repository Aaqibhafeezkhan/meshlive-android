package com.example.bluetoothtest

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

enum class CameraLens(
    val lensFacing: Int,
    val displayName: String,
) {
    BACK(CameraCharacteristics.LENS_FACING_BACK, "Back"),
    FRONT(CameraCharacteristics.LENS_FACING_FRONT, "Front"),
}

/**
 * The first camera id facing [lens] (logical ids only: a physical camera id is never targeted, V-17, because
 * OutputConfiguration.setPhysicalCameraId makes some Samsung/Xiaomi/Sony HALs fail with ERROR_CAMERA_DEVICE).
 * An id whose characteristics cannot be read is skipped instead of crashing the lookup (V-04).
 */
fun CameraManager.cameraIdFor(lens: CameraLens): String? = pickCameraId(cameraIdList, lens.lensFacing) { id ->
    getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
}
