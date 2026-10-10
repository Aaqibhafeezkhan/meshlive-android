package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoCodecTest {
    private val softwareAv1 = VideoCodec(
        displayName = "AV1 - Android compatibility",
        mimeType = "video/av01",
        protocolId = StreamProtocol.CODEC_AV1,
        encoderName = "c2.android.av1.encoder",
        hardwareAccelerated = false,
        usesSurfaceInput = false,
        bitrateMode = null,
        supportedBitrateModes = emptySet(),
        complexity = 4,
    )
    private val nativeAv1 = softwareAv1.copy(
        displayName = "AV1 - optimized software",
        encoderName = "libaom-native",
        backend = VideoEncoderBackend.NATIVE_LIBAOM,
    )

    @Test
    fun av1ComplexityPreservesRealtimeMarginAsFpsRises() {
        assertEquals(4, softwareAv1.realtimeComplexity(settings(15)))
        assertEquals(2, softwareAv1.realtimeComplexity(settings(24)))
        assertEquals(1, softwareAv1.realtimeComplexity(settings(30)))
        assertEquals(0, softwareAv1.realtimeComplexity(settings(30, 1280, 720)))
    }

    @Test
    fun nativeAv1UsesMeasuredRealtimePresets() {
        assertEquals(7, nativeAv1.nativeCpuUsed(settings(20, 640, 360)))
        assertEquals(8, nativeAv1.nativeCpuUsed(settings(30, 640, 360)))
        assertEquals(7, nativeAv1.nativeCpuUsed(settings(20)))
        assertEquals(8, nativeAv1.nativeCpuUsed(settings(24)))
        assertEquals(9, nativeAv1.nativeCpuUsed(settings(30)))
    }

    @Test
    fun codecPickerPrioritizesHardwareThenOptimizedSoftwareThenCompatibility() {
        val hardwareAv1 = softwareAv1.copy(
            displayName = "AV1 - hardware",
            encoderName = "vendor.av1.encoder",
            hardwareAccelerated = true,
        )
        val hardwareVp9 = hardwareAv1.copy(
            displayName = "VP9 - hardware",
            mimeType = "video/x-vnd.on2.vp9",
            protocolId = StreamProtocol.CODEC_VP9,
            encoderName = "vendor.vp9.encoder",
        )
        val softwareVp9 = softwareAv1.copy(
            displayName = "VP9 - Android compatibility",
            mimeType = "video/x-vnd.on2.vp9",
            protocolId = StreamProtocol.CODEC_VP9,
            encoderName = "c2.android.vp9.encoder",
        )

        val ordered = listOf(softwareVp9, softwareAv1, nativeAv1, hardwareVp9, hardwareAv1)
            .sortedBy(VideoCodec::selectionPriority)

        assertEquals(
            listOf(hardwareAv1, hardwareVp9, nativeAv1, softwareAv1, softwareVp9),
            ordered,
        )
    }

    @Test
    fun softwareCodecPrefersYuvWhenSurfaceAndYuvAreBothSupported() {
        assertEquals(false, preferSurfaceInput(false, supportsSurface = true, supportsYuv = true))
        assertEquals(true, preferSurfaceInput(true, supportsSurface = true, supportsYuv = true))
        assertEquals(true, preferSurfaceInput(false, supportsSurface = true, supportsYuv = false))
        assertEquals(false, preferSurfaceInput(true, supportsSurface = false, supportsYuv = true))
    }

    @Test
    fun legacyQualcommCodecCompatibilityIsTransportAndFamilySpecific() {
        val softwareVp9 = softwareAv1.copy(
            displayName = "VP9 - Android compatibility",
            mimeType = "video/x-vnd.on2.vp9",
            protocolId = StreamProtocol.CODEC_VP9,
            encoderName = "c2.android.vp9.encoder",
        )
        val available = listOf(nativeAv1, softwareVp9)
        val legacyQualcomm = BluetoothStackFingerprint("qcom", "atoll", 30)
        val msmnile = BluetoothStackFingerprint("qcom", "msmnile", 30)
        val modernQualcomm = BluetoothStackFingerprint("qcom", "taro", 36)

        assertEquals(
            softwareVp9,
            privateCallCodec(nativeAv1, available, legacyQualcomm, BluetoothTransportOption.CLASSIC_RFCOMM),
        )
        assertEquals(
            nativeAv1,
            privateCallCodec(nativeAv1, available, modernQualcomm, BluetoothTransportOption.CLASSIC_RFCOMM),
        )
        assertEquals(
            softwareVp9,
            privateCallCodec(nativeAv1, available, legacyQualcomm, BluetoothTransportOption.BLE_GATT),
        )
        assertEquals(
            nativeAv1,
            privateCallCodec(nativeAv1, available, legacyQualcomm, BluetoothTransportOption.BLE_L2CAP),
        )
        assertEquals(
            softwareVp9,
            privateCallCodec(nativeAv1, available, msmnile, BluetoothTransportOption.BLE_L2CAP),
        )
    }

    @Test
    fun peerDecodeMaskChangesOnlyTheUnsupportedCallDirection() {
        val softwareVp9 = softwareAv1.copy(
            displayName = "VP9 - Android compatibility",
            mimeType = "video/x-vnd.on2.vp9",
            protocolId = StreamProtocol.CODEC_VP9,
            encoderName = "c2.android.vp9.encoder",
        )
        val available = listOf(nativeAv1, softwareVp9)

        assertEquals(
            softwareVp9,
            privateCallCodecForPeer(nativeAv1, available, NodeCapabilities.CODEC_VP9),
        )
        assertEquals(
            nativeAv1,
            privateCallCodecForPeer(
                nativeAv1,
                available,
                NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            ),
        )
        assertEquals(null, privateCallCodecForPeer(nativeAv1, available, 0))
    }

    private fun settings(fps: Int, width: Int = 720, height: Int = 480) = VideoSettings(
        resolution = VideoResolution(width, height, "test"),
        fps = fps,
        bitRate = 1_100_000,
        latencyMs = 500,
    )

    @Test
    fun lowLoadNativeModesUseTheFullAv1ToolSet() {
        assertFalse(nativeUsesFastToolProfile(settings(fps = 15, width = 640, height = 480)))
        assertFalse(nativeUsesFastToolProfile(settings(fps = 15, width = 640, height = 360)))
        assertTrue(nativeUsesFastToolProfile(settings(fps = 30, width = 640, height = 480)))
        assertTrue(nativeUsesFastToolProfile(settings(fps = 15, width = 1280, height = 720)))
    }
}
