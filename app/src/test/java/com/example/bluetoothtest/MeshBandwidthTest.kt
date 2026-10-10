package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixed 360p/300 kbps fixture: these expectations must not follow the user default (VideoSettings.DEFAULT).
private val SETTINGS_360P = VideoSettings(VideoSettings.RESOLUTIONS[1], fps = 15, bitRate = 300_000, latencyMs = 500)

class MeshBandwidthTest {
    @Test
    fun balancedPresetMatchesMeasuredSettings() {
        val balanced = MeshProfiles.options[1]

        assertEquals(VideoResolution(640, 360, "360p"), balanced.resolution)
        assertEquals(24, balanced.fps)
        assertEquals(200, balanced.bitrateKbps)
    }

    @Test
    fun qualityDefaultShowsWhenTwoChildRelayExceedsBudget() {
        val estimate = MeshBandwidthEstimator.estimate(
            SETTINGS_360P,
            BluetoothTransportOption.CLASSIC_RFCOMM,
            MeshNodeRole.RELAY,
            plannedChildren = 2,
        )

        assertEquals(403.392, estimate.perLinkKbps, 0.0001)
        assertEquals(1, estimate.sourceChildren)
        assertEquals(0, estimate.relayChildren)
        assertEquals(1_210.176, estimate.plannedLoadKbps, 0.0001)
        assertFalse(estimate.plannedWithinBudget)
    }

    @Test
    fun warnsWhenHighProfileRelayPlansOneChild() {
        val estimate = MeshBandwidthEstimator.estimate(
            SETTINGS_360P.copy(bitRate = 450_000, fps = 30),
            BluetoothTransportOption.CLASSIC_RFCOMM,
            MeshNodeRole.RELAY,
            plannedChildren = 1,
        )

        assertEquals(1, estimate.sourceChildren)
        assertEquals(0, estimate.relayChildren)
        assertFalse(estimate.plannedWithinBudget)
    }

    @Test
    fun transportBudgetsReflectMeasuredAggregateCapacity() {
        val settings = SETTINGS_360P

        val classic = MeshBandwidthEstimator.estimate(
            settings,
            BluetoothTransportOption.CLASSIC_RFCOMM,
            MeshNodeRole.SOURCE,
            plannedChildren = 1,
        )
        val gatt = MeshBandwidthEstimator.estimate(
            settings,
            BluetoothTransportOption.BLE_GATT,
            MeshNodeRole.SOURCE,
            plannedChildren = 1,
        )
        val coc = MeshBandwidthEstimator.estimate(
            settings,
            BluetoothTransportOption.BLE_L2CAP,
            MeshNodeRole.SOURCE,
            plannedChildren = 1,
        )

        assertEquals(1, classic.sourceChildren)
        assertEquals(1, gatt.sourceChildren)
        assertEquals(1, coc.sourceChildren)
        assertEquals(800.0, classic.safeAggregateKbps, 0.0)
        assertEquals(700.0, gatt.safeAggregateKbps, 0.0)
        assertEquals(700.0, coc.safeAggregateKbps, 0.0)
    }

    @Test
    fun highBitrateStillAllowsOneDirectReceiver() {
        val estimate = MeshBandwidthEstimator.estimate(
            SETTINGS_360P.copy(bitRate = 1_100_000),
            BluetoothTransportOption.BLE_GATT,
            MeshNodeRole.SOURCE,
            plannedChildren = 1,
        )

        assertEquals(1, estimate.sourceChildren)
        assertFalse(estimate.plannedWithinBudget)
    }
}
