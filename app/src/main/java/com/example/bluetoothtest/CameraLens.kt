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

fun CameraManager.cameraIdFor(lens: CameraLens): String? = cameraIdList.firstOrNull { id ->
    getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lens.lensFacing
}
