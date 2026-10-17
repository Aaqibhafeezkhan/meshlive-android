package com.example.bluetoothtest

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call screen's Speaker button: toggle or output sheet, from the outputs that are actually available. */
class CallOutputChoicesTest {
    private val speaker = AudioSink(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker", 1)
    private val earpiece = AudioSink(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Earpiece", 2)
    private val wired = AudioSink(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired headset", 3)
    private val usb = AudioSink(AudioDeviceInfo.TYPE_USB_HEADSET, "USB-C headset", 4)
    private val bluetooth = AudioSink(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds Pro", 5)

    @Test
    fun aPhoneWithoutHeadsetTogglesSpeakerAndEarpiece() {
        val options = CallOutputChoices.options(listOf(speaker, earpiece), bluetoothAllowed = false)
        assertEquals(listOf(CallOutput.SPEAKER, CallOutput.EARPIECE), options.map { it.output })
        assertFalse("no sheet without a headset", CallOutputChoices.opensSheet(options))
        assertTrue("the toggle has two outputs", CallOutputChoices.buttonEnabled(options))
    }

    @Test
    fun aSpeakerOnlyTabletLeavesTheButtonOff() {
        val options = CallOutputChoices.options(listOf(speaker), bluetoothAllowed = false)
        assertFalse(CallOutputChoices.buttonEnabled(options))
        assertFalse(CallOutputChoices.opensSheet(options))
    }

    @Test
    fun aWiredHeadsetOpensTheSheetWithItsName() {
        val options = CallOutputChoices.options(listOf(speaker, earpiece, wired), bluetoothAllowed = false)
        assertTrue(CallOutputChoices.opensSheet(options))
        assertEquals(listOf("Phone speaker", "Earpiece", "Wired headset"), options.map { it.name })
        // A tablet with a headset and no earpiece still has two outputs to choose from.
        val tablet = CallOutputChoices.options(listOf(speaker, usb), bluetoothAllowed = false)
        assertEquals(listOf("Phone speaker", "USB-C headset"), tablet.map { it.name })
        assertTrue(CallOutputChoices.buttonEnabled(tablet))
    }

    @Test
    fun bluetoothIsOfferedOnlyBehindTheExperimentalSwitch() {
        val off = CallOutputChoices.options(listOf(speaker, earpiece, bluetooth), bluetoothAllowed = false)
        assertFalse(off.any { it.output == CallOutput.BLUETOOTH })
        assertFalse("a Bluetooth device alone does not open a sheet while the switch is off", CallOutputChoices.opensSheet(off))
        val on = CallOutputChoices.options(listOf(speaker, earpiece, bluetooth), bluetoothAllowed = true)
        assertEquals("Buds Pro", on.last().name)
        assertTrue(CallOutputChoices.opensSheet(on))
    }

    @Test
    fun theSheetChecksTheRouteInUseAndNamesAnUnnamedDevice() {
        val options = CallOutputChoices.options(
            listOf(speaker, earpiece, AudioSink(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", 6)),
            bluetoothAllowed = false,
        )
        assertEquals("Headset", options.last().name)
        assertEquals(CallOutput.EARPIECE, CallOutputChoices.selected(options, CallOutput.EARPIECE)?.output)
        assertNull(CallOutputChoices.selected(options, CallOutput.BLUETOOTH))
    }

    @Test
    fun anExplicitSheetPickWinsWhileThatOutputExists() {
        // With a wired headset plugged the earpiece or the speaker can still be picked.
        assertEquals(CallOutput.EARPIECE, callOutputFor(false, false, true, true, explicitOutput = CallOutput.EARPIECE))
        assertEquals(CallOutput.SPEAKER, callOutputFor(false, false, true, true, explicitOutput = CallOutput.SPEAKER))
        assertEquals(CallOutput.WIRED_HEADSET, callOutputFor(true, true, true, true, explicitOutput = CallOutput.WIRED_HEADSET))
        // A pick of an output that is gone falls back to the usual rules.
        assertEquals(CallOutput.EARPIECE, callOutputFor(false, false, false, true, explicitOutput = CallOutput.WIRED_HEADSET))
        assertEquals(CallOutput.SPEAKER, callOutputFor(true, false, false, true, explicitOutput = CallOutput.BLUETOOTH))
        assertEquals(CallOutput.BLUETOOTH, callOutputFor(false, false, false, true, explicitOutput = CallOutput.BLUETOOTH, bluetoothPresent = true))
        // No pick: exactly the long-standing rules.
        assertEquals(CallOutput.WIRED_HEADSET, callOutputFor(true, false, true, true))
        assertEquals(CallOutput.SPEAKER, callOutputFor(true, true, true, true))
        assertEquals(CallOutput.EARPIECE, callOutputFor(false, false, false, true))
    }

    @Test
    fun theDefaultsAreUnchanged() {
        // Voice calls start on the earpiece, video calls on the loudspeaker (the route policy is untouched).
        assertFalse(CallAudioRoutePolicy(CallType.VOICE).initialSpeakerOn)
        assertTrue(CallAudioRoutePolicy(CallType.VIDEO).initialSpeakerOn)
    }
}
