package com.example.bluetoothtest

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRoutingPolicyTest {
    private fun device(type: Int, name: String = "", id: Int = type) = CallRoutingPolicy.Candidate(type, name, id)

    @Test
    fun watchesAreIgnoredAsBluetoothCallDevices() {
        assertTrue(CallRoutingPolicy.isWatchName("Galaxy Watch4 (ABCD)"))
        assertTrue(CallRoutingPolicy.isWatchName("Pixel Watch"))
        assertFalse(CallRoutingPolicy.isWatchName("Sony WH-1000XM5"))
        assertFalse(CallRoutingPolicy.isWatchName(null))
        val sinks = listOf(
            AudioSink(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Galaxy Watch4", 1),
            AudioSink(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Headset", 2),
            AudioSink(AudioDeviceInfo.TYPE_WIRED_HEADSET, "My Watch Wired", 3),
        )
        // Only a Bluetooth device is filtered by name; a wired headset keeps its row.
        assertEquals(listOf(2, 3), CallRoutingPolicy.withoutWatches(sinks).map { it.id })
    }

    @Test
    fun candidatesForTheWantedKindExcludeWatchesAndPreferTheChosenBluetoothDevice() {
        val devices = listOf(
            device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "speaker", 10),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Pixel Watch", 20),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds A", 21),
            device(AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds B", 22),
        )
        assertEquals(listOf(10), CallRoutingPolicy.candidatesFor(CallOutput.SPEAKER, devices, CallOutputOption.NO_DEVICE).map { it.id })
        assertEquals(listOf(21, 22), CallRoutingPolicy.candidatesFor(CallOutput.BLUETOOTH, devices, CallOutputOption.NO_DEVICE).map { it.id })
        assertEquals(listOf(22, 21), CallRoutingPolicy.candidatesFor(CallOutput.BLUETOOTH, devices, 22).map { it.id })
        assertTrue(CallRoutingPolicy.candidatesFor(CallOutput.EARPIECE, devices, CallOutputOption.NO_DEVICE).isEmpty())
    }

    @Test
    fun aRejectedDeviceIsFollowedByTheNextCandidate() {
        val tried = mutableListOf<Int>()
        val picked = CallRoutingPolicy.pickFirstAccepted(listOf(1, 2, 3)) { id ->
            tried += id
            id == 2
        }
        assertEquals(2 to 1, picked)
        assertEquals(listOf(1, 2), tried)
        assertNull(CallRoutingPolicy.pickFirstAccepted(listOf(1, 2)) { false })
        assertNull(CallRoutingPolicy.pickFirstAccepted(emptyList<Int>()) { true })
    }

    @Test
    fun reassertIsNeededWhenThePlatformMovedOrClearedTheRoute() {
        assertFalse(CallRoutingPolicy.needsReassert(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, CallOutput.EARPIECE))
        assertTrue(CallRoutingPolicy.needsReassert(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, CallOutput.EARPIECE))
        assertTrue(CallRoutingPolicy.needsReassert(null, CallOutput.SPEAKER))
        assertFalse(CallRoutingPolicy.needsReassert(AudioDeviceInfo.TYPE_USB_HEADSET, CallOutput.WIRED_HEADSET))
        assertFalse(CallRoutingPolicy.needsReassert(AudioDeviceInfo.TYPE_BLE_HEADSET, CallOutput.BLUETOOTH))
    }

    @Test
    fun reassertsAreRateLimitedPerWindow() {
        val limiter = RouteReassertLimiter(maxPerWindow = 3, windowMs = 10_000)
        assertTrue(limiter.allow(0))
        assertTrue(limiter.allow(1_000))
        assertTrue(limiter.allow(2_000))
        assertFalse("a fourth in the window is a fight with the platform", limiter.allow(3_000))
        assertTrue(limiter.allow(10_500))
    }

    @Test
    fun theUsbHeadsetModeRuleNeedsTheExperimentOneUiUsbAndNoSpeaker() {
        val inCommunication = AudioManager.MODE_IN_COMMUNICATION
        assertEquals(AudioManager.MODE_NORMAL, CallRoutingPolicy.callMode(true, OemSkin.ONE_UI, true, false))
        assertEquals(inCommunication, CallRoutingPolicy.callMode(true, OemSkin.ONE_UI, false, false))
        assertEquals(inCommunication, CallRoutingPolicy.callMode(false, OemSkin.ONE_UI, true, false))
        assertEquals(inCommunication, CallRoutingPolicy.callMode(true, OemSkin.PIXEL, true, false))
        assertEquals(inCommunication, CallRoutingPolicy.callMode(true, OemSkin.ONE_UI, true, true))
    }

    @Test
    fun theModeChangeWaitIsHalfASecond() {
        assertEquals(500L, CallRoutingPolicy.MODE_CHANGE_WAIT_MS)
    }
}
