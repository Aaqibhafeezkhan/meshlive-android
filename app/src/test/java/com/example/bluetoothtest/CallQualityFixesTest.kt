package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue

class CallQualityFixesTest {
    private val msmnile = BluetoothStackFingerprint("qcom", "msmnile", 30)
    private val exynos = BluetoothStackFingerprint("exynos9611", "exynos9611", 31)

    @Test
    fun audioWaitingBehindOneLongWriteNoLongerFillsTheVideoBudget() {
        // calls-noloss-m31asus-51cc8028/0002: 33-record queue, a 700 ms GATT write, 35 audio packets.
        val videoCapacity = 33
        val queue = ArrayBlockingQueue<Boolean>(videoCapacity + privateCallAudioHeadroomPackets()) // true = audio
        repeat(2) { queue.add(false) }
        repeat(35) { assertTrue(queue.offer(true)) }
        // Before the fix the 33-slot queue was full here and the next video frame dropped all video.
        assertFalse(privateCallVideoBudgetFull(queue, videoCapacity) { it })
        assertTrue(queue.offer(false))
        // A real video backlog still hits the budget.
        repeat(videoCapacity - 3) { queue.add(false) }
        assertTrue(privateCallVideoBudgetFull(queue, videoCapacity) { it })
        assertTrue(privateCallAudioHeadroomPackets() >= 150) // 3 s of 20 ms audio
    }

    @Test
    fun legacyDecodeRestrictionBecomesASignedVp9PreferenceEndToEnd() {
        val vp9 = NodeCapabilities.CODEC_VP9
        val av1 = NodeCapabilities.CODEC_AV1
        val prefers = NodeCapabilities.DECODE_PREFERS_VP9
        val restricted = privateCallDecodeCodecMask(msmnile, BluetoothTransportOption.BLE_GATT, vp9 or av1)
        assertEquals(vp9, restricted)
        // End-to-end hello: real decoders plus the VP9 preference (no peer or link input at all).
        assertEquals(vp9 or av1 or prefers, privateCallEndpointDecodeMask(restricted, vp9 or av1, fallback = true))
        // No other local decoder, unrestricted phones and the control arm: unchanged.
        assertEquals(vp9, privateCallEndpointDecodeMask(restricted, vp9, fallback = true))
        assertEquals(vp9 or av1, privateCallEndpointDecodeMask(vp9 or av1, vp9 or av1, fallback = true))
        assertEquals(vp9, privateCallEndpointDecodeMask(restricted, vp9 or av1, fallback = false))
        val libaom = VideoCodec(
            displayName = "AV1", mimeType = "video/av01", protocolId = StreamProtocol.CODEC_AV1,
            encoderName = "libaom-native", hardwareAccelerated = false, usesSurfaceInput = false,
            bitrateMode = null, supportedBitrateModes = emptySet(), complexity = null,
            backend = VideoEncoderBackend.NATIVE_LIBAOM,
        )
        // AV1-only sender: fails against VP9 only (control arm), sends AV1 to the preference mask.
        assertEquals(null, privateCallCodecForPeer(libaom, listOf(libaom), vp9))
        assertEquals(libaom, privateCallCodecForPeer(libaom, listOf(libaom), vp9 or av1 or prefers))
        // Relayed topologies: CallCodecFallbackRelayTest.
    }

    @Test
    fun legacyLowDelayUsesAudioPairsAndNormalCallPlayoutOnDirectCallsOnly() {
        val coc = BluetoothTransportOption.BLE_L2CAP
        // Unchanged default: 20-packet (400 ms) batches and the constrained 500-800 ms profile.
        assertEquals(20, privateCallAudioBatchSizeForRoute(coc, 0, msmnile, null))
        assertEquals(20, privateCallAudioBatchSizeForRoute(coc, 0, exynos, 110))
        assertEquals(
            PlaybackDelayProfile.CALL_STABLE,
            privateCallPlaybackDelayProfile(PlaybackDelayProfile.AUTO, coc, msmnile, null),
        )
        // Experiment on, direct call: 40 ms pairs and the normal call profile.
        val direct = privateCallLegacyLowDelay(enabled = true, relayHops = 0)
        assertEquals(2, privateCallAudioBatchSizeForRoute(coc, 0, msmnile, null, direct))
        assertEquals(2, privateCallAudioBatchSizeForRoute(coc, 0, exynos, 110, direct))
        assertEquals(
            PlaybackDelayProfile.CALL,
            privateCallPlaybackDelayProfile(PlaybackDelayProfile.AUTO, coc, msmnile, null, direct),
        )
        // Relayed calls keep the batch whose measured reason was the relay's record rate.
        assertFalse(privateCallLegacyLowDelay(enabled = true, relayHops = 1))
        assertEquals(20, privateCallAudioBatchSizeForRoute(coc, 1, msmnile, null, legacyLowDelay = true))
        // Unconstrained routes are untouched.
        assertEquals(1, privateCallAudioBatchSizeForRoute(coc, 0, exynos, null, direct))
    }

    @Test
    fun legacyReliefNeutralisesEveryLegacyCallProtectionOnlyWhenOn() {
        assertEquals(msmnile, privateCallStackFingerprint(msmnile, relief = false))
        val relieved = privateCallStackFingerprint(msmnile, relief = true)
        assertEquals(0L, legacyQualcommGattCallFlushIntervalMs(relieved))
        assertFalse(isLegacyQualcommMsmnileStack(relieved))
        val selected = VideoSettings(VideoResolution(640, 480, "480p"), fps = 15, bitRate = 600_000, latencyMs = 500)
        // Default: 426x240-class geometry, 110 kbps CoC limit, Opus 16, software VP9 decoder.
        val protectedSettings = privateCallVideoSettings(selected, BluetoothTransportOption.BLE_L2CAP, msmnile)
        assertEquals(426, protectedSettings.resolution.width)
        assertEquals(110, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.BLE_L2CAP, msmnile, 1_200))
        assertEquals(16_000, privateCallAudioBitRate(BluetoothTransportOption.BLE_L2CAP, msmnile))
        // Relief: the selected geometry, the normal receive limit, Opus 32 and the platform decoder.
        val reliefSettings = privateCallVideoSettings(selected, BluetoothTransportOption.BLE_L2CAP, relieved, preserveSelectedBitrate = true)
        assertEquals(640, reliefSettings.resolution.width)
        assertEquals(1_200, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.BLE_L2CAP, relieved, 1_200))
        assertEquals(StreamProtocol.AUDIO_BIT_RATE, privateCallAudioBitRate(BluetoothTransportOption.BLE_L2CAP, relieved))
        assertEquals(null, privateCallPreferredDecoderName(StreamProtocol.CODEC_VP9, relieved))
    }

    @Test
    fun lockScreenPresentationOnlyWhileACallRingsOrRuns() {
        assertFalse(CallLockScreenPolicy.showOverLockScreen(enabled = false, PrivateCallPhase.INCOMING_RINGING))
        assertTrue(CallLockScreenPolicy.showOverLockScreen(enabled = true, PrivateCallPhase.INCOMING_RINGING))
        assertTrue(CallLockScreenPolicy.showOverLockScreen(enabled = true, PrivateCallPhase.ACTIVE))
        assertFalse(CallLockScreenPolicy.showOverLockScreen(enabled = true, PrivateCallPhase.ENDED))
        assertFalse(CallLockScreenPolicy.showOverLockScreen(enabled = true, null))
        assertTrue(CallLockScreenPolicy.turnScreenOn(enabled = true, PrivateCallPhase.INCOMING_RINGING))
        assertFalse(CallLockScreenPolicy.turnScreenOn(enabled = true, PrivateCallPhase.ACTIVE))
        assertTrue(callLockScreenNeedsNotificationPermission(enabled = true, sdkInt = 33, granted = false))
        assertFalse(callLockScreenNeedsNotificationPermission(enabled = false, sdkInt = 34, granted = false))
        assertFalse(callLockScreenNeedsNotificationPermission(enabled = true, sdkInt = 32, granted = false))
    }
}
