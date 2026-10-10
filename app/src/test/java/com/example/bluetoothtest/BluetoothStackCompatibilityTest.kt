package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothStackCompatibilityTest {
    @Test
    fun scanPauseAtCapacityPreservesLegacyClassicAndLeTransportPolicies() {
        for (stack in listOf(
            BluetoothStackFingerprint("s5e9945", "erd9945", 36),
            BluetoothStackFingerprint("qcom", "taro", 36),
            BluetoothStackFingerprint("exynos9611", "universal9611", 31),
        )) {
            assertTrue(pauseClassicViewerDiscoveryAtCapacity(BluetoothTransportOption.CLASSIC_RFCOMM, stack))
            assertTrue(pauseClassicViewerDiscoveryAtCapacity(BluetoothTransportOption.BLE_GATT, stack))
            assertTrue(pauseClassicViewerDiscoveryAtCapacity(BluetoothTransportOption.BLE_L2CAP, stack))
        }
        assertTrue(pauseClassicViewerDiscoveryAtCapacity(
            BluetoothTransportOption.CLASSIC_RFCOMM,
            BluetoothStackFingerprint("qcom", "msmnile", 30),
        ))
    }

    @Test
    fun fullClassicSourceUsesBalancedScanningUntilAnAdmittedSlotFrees() {
        for (stack in listOf(
            BluetoothStackFingerprint("s5e9945", "erd9945", 36),
            BluetoothStackFingerprint("qcom", "taro", 36),
            BluetoothStackFingerprint("exynos9611", "universal9611", 31),
        )) {
            assertTrue(useContinuousLowLatencyViewerDiscovery(BluetoothTransportOption.CLASSIC_RFCOMM, stack))
            assertFalse(useContinuousLowLatencyViewerDiscovery(BluetoothTransportOption.CLASSIC_RFCOMM, stack, true))
            assertTrue(useContinuousLowLatencyViewerDiscovery(BluetoothTransportOption.CLASSIC_RFCOMM, stack, false))
            assertTrue(useContinuousLowLatencyViewerDiscovery(BluetoothTransportOption.BLE_GATT, stack, true))
            assertTrue(useContinuousLowLatencyViewerDiscovery(BluetoothTransportOption.BLE_L2CAP, stack, true))
        }
        val legacy = BluetoothStackFingerprint("qcom", "msmnile", 30)
        assertFalse(useContinuousLowLatencyViewerDiscovery(BluetoothTransportOption.CLASSIC_RFCOMM, legacy, true))
        assertFalse(useContinuousLowLatencyViewerDiscovery(BluetoothTransportOption.CLASSIC_RFCOMM, legacy, false))
    }

    @Test
    fun legacyQualcommMsmnileClassicStackMatchesAcrossDeviceBrands() {
        assertTrue(
            LegacyQualcommClassicCompatibility.matches(
                BluetoothTransportOption.CLASSIC_RFCOMM,
                BluetoothStackFingerprint("qcom", "msmnile", 30),
            ),
        )
        assertTrue(
            LegacyQualcommClassicCompatibility.matches(
                BluetoothTransportOption.CLASSIC_RFCOMM,
                BluetoothStackFingerprint("QCOM", "sm8150-ac", 32),
            ),
        )
    }

    @Test
    fun transportAndStackFamilyBoundariesProtectUnaffectedDevices() {
        val affectedFamily = BluetoothStackFingerprint("qcom", "msmnile", 30)
        assertFalse(
            LegacyQualcommClassicCompatibility.matches(
                BluetoothTransportOption.BLE_GATT,
                affectedFamily,
            ),
        )
        assertTrue(
            useContinuousLowLatencyViewerDiscovery(
                BluetoothTransportOption.BLE_GATT,
                affectedFamily,
            ),
        )
        assertTrue(
            useContinuousLowLatencyViewerDiscovery(
                BluetoothTransportOption.BLE_L2CAP,
                affectedFamily,
            ),
        )
        assertFalse(
            useContinuousLowLatencyViewerDiscovery(
                BluetoothTransportOption.CLASSIC_RFCOMM,
                affectedFamily,
            ),
        )
        assertTrue(
            useContinuousLowLatencyViewerDiscovery(
                BluetoothTransportOption.CLASSIC_RFCOMM,
                BluetoothStackFingerprint("s5e9945", "erd9945", 36),
            ),
        )
        assertFalse(
            LegacyQualcommClassicCompatibility.matches(
                BluetoothTransportOption.BLE_L2CAP,
                affectedFamily,
            ),
        )
        assertFalse(
            LegacyQualcommClassicCompatibility.matches(
                BluetoothTransportOption.CLASSIC_RFCOMM,
                BluetoothStackFingerprint("s5e9945", "erd9945", 36),
            ),
        )
        assertFalse(
            LegacyQualcommClassicCompatibility.matches(
                BluetoothTransportOption.CLASSIC_RFCOMM,
                BluetoothStackFingerprint("qcom", "msmnile", 33),
            ),
        )
    }

    @Test
    fun gattCallFlushCoalescingFollowsTheLegacyQualcommStackFamily() {
        assertTrue(legacyQualcommGattCallFlushIntervalMs(BluetoothStackFingerprint("qcom", "msmnile", 30)) > 0L)
        assertTrue(legacyQualcommGattCallFlushIntervalMs(BluetoothStackFingerprint("QCOM", "atoll", 30)) > 0L)
        assertTrue(legacyQualcommGattCallFlushIntervalMs(BluetoothStackFingerprint("exynos9611", "exynos9611", 31)) == 0L)
        assertTrue(legacyQualcommGattCallFlushIntervalMs(BluetoothStackFingerprint("qcom", "kalama", 33)) == 0L)
        assertTrue(legacyQualcommGattCallFlushIntervalMs(BluetoothStackFingerprint("s5e9945", "erd9945", 36)) == 0L)
    }

    @Test
    fun atollCompatibilityMatchesTheChipsetFamilyOnly() {
        assertTrue(isLegacyQualcommAtollStack(BluetoothStackFingerprint("qcom", "atoll", 30)))
        assertTrue(isLegacyQualcommAtollStack(BluetoothStackFingerprint("qcom", "curtana", 30)))
        assertTrue(isLegacyQualcommAtollStack(BluetoothStackFingerprint("QCOM", "sm7125-x", 32)))
        assertFalse(isLegacyQualcommAtollStack(BluetoothStackFingerprint("qcom", "atoll", 33)))
        assertFalse(isLegacyQualcommAtollStack(BluetoothStackFingerprint("qcom", "msmnile", 30)))
        assertFalse(isLegacyQualcommAtollStack(BluetoothStackFingerprint("exynos9611", "atoll", 31)))
    }

    @Test
    fun softwareVp9CallDecoderIsLimitedToLegacyQualcommFamily() {
        assertEquals(
            "c2.android.vp9.decoder",
            privateCallPreferredDecoderName(
                StreamProtocol.CODEC_VP9,
                BluetoothStackFingerprint("qcom", "msmnile", 30),
            ),
        )
        assertNull(
            privateCallPreferredDecoderName(
                StreamProtocol.CODEC_AV1,
                BluetoothStackFingerprint("qcom", "msmnile", 30),
            ),
        )
        assertEquals(
            "c2.android.vp9.decoder",
            privateCallPreferredDecoderName(
                StreamProtocol.CODEC_VP9,
                BluetoothStackFingerprint("qcom", "atoll", 30),
            ),
        )
        assertNull(
            privateCallPreferredDecoderName(
                StreamProtocol.CODEC_VP9,
                BluetoothStackFingerprint("qcom", "taro", 36),
            ),
        )
    }
}
