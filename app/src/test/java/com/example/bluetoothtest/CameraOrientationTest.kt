package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun rotatesBackCameraFromSensorCoordinatesIntoCurrentDisplay() {
        assertEquals(90, CameraOrientation.relativeRotationDegrees(90, 0, CameraLens.BACK))
        assertEquals(0, CameraOrientation.relativeRotationDegrees(90, 90, CameraLens.BACK))
        assertEquals(180, CameraOrientation.relativeRotationDegrees(90, 270, CameraLens.BACK))
    }

    @Test
    fun rotatesFrontCameraFromSensorCoordinatesIntoCurrentDisplay() {
        assertEquals(270, CameraOrientation.relativeRotationDegrees(270, 0, CameraLens.FRONT))
        assertEquals(0, CameraOrientation.relativeRotationDegrees(270, 90, CameraLens.FRONT))
        assertEquals(180, CameraOrientation.relativeRotationDegrees(270, 270, CameraLens.FRONT))
    }
}
