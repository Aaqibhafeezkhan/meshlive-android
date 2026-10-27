package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceFamilyPolicy `mediatek-call-relay-gatt-helper-off` (Q-MTK, 2026-10-27): a MediaTek phone helps with other
 * people's calls only over LE CoC. Over GATT it pauses helping (no helper flag, no relay mark, SETUP refused); its own
 * calls and every other family are unchanged, and the entry has its off switch.
 */
class MediaTekGattRelayHelperTest {
    private val id = GattLinkPolicy.MEDIATEK_GATT_RELAY_HELPER.id

    private val a15s = DeviceTraits("mt6765", "mt6765", 29, manufacturer = "OPPO")
    private val redmi12 = DeviceTraits("mt6768", "mt6768", 33, manufacturer = "Xiaomi", socManufacturer = "Mediatek")
    private val s22 = DeviceTraits("qcom", "taro", 34, manufacturer = "samsung", socManufacturer = "QTI")
    private val m31 = DeviceTraits("exynos9611", "exynos9611", 30, manufacturer = "samsung")

    private fun blocked(traits: DeviceTraits, transport: BluetoothTransportOption, off: Set<String> = emptySet()) =
        RelayedCalls.gattHelperDutyBlocked(transport) { DeviceFamilyPolicy.resolve(traits, off) }

    @Test
    fun mediaTekPhonesHelpOverCocButNotOverGatt() {
        for (phone in listOf(a15s, redmi12)) {
            assertTrue(phone.describe(), blocked(phone, BluetoothTransportOption.BLE_GATT))
            assertFalse(phone.describe(), blocked(phone, BluetoothTransportOption.BLE_L2CAP))
            assertFalse(phone.describe(), blocked(phone, BluetoothTransportOption.CLASSIC_RFCOMM))
        }
    }

    @Test
    fun otherFamiliesStillHelpOverGatt() {
        for (phone in listOf(s22, m31)) assertFalse(phone.describe(), blocked(phone, BluetoothTransportOption.BLE_GATT))
    }

    @Test
    fun theOffSwitchRestoresGattHelping() {
        assertFalse(blocked(a15s, BluetoothTransportOption.BLE_GATT, off = setOf(id)))
        assertFalse(blocked(a15s, BluetoothTransportOption.BLE_GATT, off = setOf("all")))
    }

    @Test
    fun aBlockedGattEndpointPausesHelpingWithItsOwnStatusLine() {
        val inputs = RelayHelperInputs(
            featureOn = true, helpOn = true, rotatingIdsOn = false, leTransport = true, gattHelperBlocked = true,
            bluetoothReady = true, callReady = true, batteryPercent = 80, charging = false, batterySaver = false,
            thermalSevere = false, ownActivity = false, screenOn = true,
        )
        val plan = RelayHelperPolicy.plan(inputs)
        assertFalse(plan.helping)
        assertEquals(HelperPause.GATT_FAMILY, plan.pause)
        assertEquals("Helping: paused (CoC only on this phone)", RelayHelperPolicy.statusLine(plan, 80))
        assertTrue(RelayHelperPolicy.plan(inputs.copy(gattHelperBlocked = false)).helping)
    }
}
