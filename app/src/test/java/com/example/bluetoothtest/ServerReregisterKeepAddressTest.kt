package com.example.bluetoothtest

import com.example.bluetoothtest.ReregisterAdvertising.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep-address part of `android11-gatt-server-connection-untracked`: a re-registration of the call listener's GATT server
 * keeps the advertising set (same address), so a caller's retry does not wait for a new set (2026-10-20, the A15s's retry
 * on the ASUS came after a rediscovery). Rotating-ID epochs never inherit a kept address.
 */
class ServerReregisterKeepAddressTest {
    private val known = KnownDeviceTraits.load().associateBy { it.alias }
    private val caller = "53:C5:3C:E1:21:CB"
    private val other = "6A:11:02:9F:30:D4"

    @Test
    fun keepAddressIsPartOfTheLegacyStackEntryAndFollowsItsOffSwitch() {
        val entry = GattLinkPolicy.SERVER_CONNECTION_UNTRACKED
        assertTrue(entry.effects.contains(GattServerReregisterKeepsAddress))
        assertTrue(GattLinkPolicy.serverReregisterKeepsAddress(DeviceFamilyPolicy.resolve(known.getValue("asus").traits, emptySet())))
        assertTrue(GattLinkPolicy.serverReregisterKeepsAddress(DeviceFamilyPolicy.resolve(known.getValue("a15s").traits, emptySet())))
        for (alias in listOf("s22", "s24", "a55", "m31", "xiaomi")) {
            assertFalse(alias, GattLinkPolicy.serverReregisterKeepsAddress(DeviceFamilyPolicy.resolve(known.getValue(alias).traits, emptySet())))
        }
        assertFalse(
            GattLinkPolicy.serverReregisterKeepsAddress(DeviceFamilyPolicy.resolve(known.getValue("asus").traits, setOf(entry.id))),
        )
    }

    @Test
    fun aRunningSetIsKeptThroughAReregistration() {
        assertEquals(Action.KEEP_SET, ReregisterAdvertising.decide(keepAddress = true, setRunning = true, epochSilence = false))
    }

    @Test
    fun theControlArmReplacesTheRunningSetAsBefore() {
        assertEquals(Action.REPLACE_SET, ReregisterAdvertising.decide(keepAddress = false, setRunning = true, epochSilence = false))
    }

    @Test
    fun withNoSetRunningAReregistrationStartsANewOne() {
        assertEquals(Action.START_SET, ReregisterAdvertising.decide(keepAddress = true, setRunning = false, epochSilence = false))
        assertEquals(Action.START_SET, ReregisterAdvertising.decide(keepAddress = false, setRunning = false, epochSilence = false))
    }

    @Test
    fun aReregistrationDuringTheNewIdsSilenceLeavesTheStartToTheSilence() {
        // The epoch change stopped the old set; starting one now would cut the unlinking silence short.
        assertEquals(Action.LEAVE_TO_REANNOUNCE, ReregisterAdvertising.decide(keepAddress = true, setRunning = false, epochSilence = true))
        assertEquals(Action.LEAVE_TO_REANNOUNCE, ReregisterAdvertising.decide(keepAddress = false, setRunning = false, epochSilence = true))
    }

    @Test
    fun anEpochChangeStopsASetHeldThroughAnAdmission() {
        val held = ReannounceStep.plan(advertising = true, linkAdmitted = true)
        assertTrue(held.stopRunningSet)
        assertFalse(held.restartAfterSilence)
    }

    @Test
    fun anEpochChangeWithNothingAdmittedStopsAndRestartsAfterTheSilence() {
        val idle = ReannounceStep.plan(advertising = true, linkAdmitted = false)
        assertTrue(idle.stopRunningSet)
        assertTrue(idle.restartAfterSilence)
        val quiet = ReannounceStep.plan(advertising = false, linkAdmitted = false)
        assertFalse(quiet.stopRunningSet)
        assertFalse(quiet.restartAfterSilence)
    }

    @Test
    fun withoutTheHoldTheSetStopsAtTheSubscribeAsBefore() {
        val hold = AdmissionAdvertisingHold()
        assertTrue(hold.onAdmitted(caller, hold = false))
        assertNull(hold.holding())
        assertFalse(hold.onFirstWrite(caller))
    }

    @Test
    fun aHeldSetStopsOnceAtTheFirstWrite() {
        val hold = AdmissionAdvertisingHold()
        assertFalse(hold.onAdmitted(caller, hold = true))
        assertEquals(caller, hold.holding())
        assertTrue(hold.onFirstWrite(caller.lowercase()))
        assertFalse(hold.onFirstWrite(caller))
        assertFalse(hold.onWatchEnded(caller))
    }

    @Test
    fun anotherDevicesWriteDoesNotReleaseTheHold() {
        val hold = AdmissionAdvertisingHold()
        hold.onAdmitted(caller, hold = true)
        assertFalse(hold.onFirstWrite(other))
        assertEquals(caller, hold.holding())
    }

    @Test
    fun aWatchThatEndsWithoutAReregistrationStopsTheHeldSet() {
        val hold = AdmissionAdvertisingHold()
        hold.onAdmitted(caller, hold = true)
        assertTrue(hold.onWatchEnded(caller))
        assertNull(hold.holding())
    }

    @Test
    fun aHeldLinkThatEndsLeavesTheSetAdvertising() {
        // Disconnect, or the stage-2 re-registration: the set keeps its address for the caller's retry.
        val hold = AdmissionAdvertisingHold()
        hold.onAdmitted(caller, hold = true)
        hold.onLinkEnded(caller)
        assertNull(hold.holding())
        assertFalse(hold.onFirstWrite(caller))
        assertFalse(hold.onWatchEnded(caller))
    }

    @Test
    fun anEpochChangeClearsTheHoldSoALateWriteStopsNothing() {
        val hold = AdmissionAdvertisingHold()
        hold.onAdmitted(caller, hold = true)
        hold.clear()
        assertFalse(hold.onFirstWrite(caller))
    }
}
