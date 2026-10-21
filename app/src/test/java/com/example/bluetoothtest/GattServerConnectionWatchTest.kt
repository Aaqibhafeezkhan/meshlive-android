package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S22 calling the ASUS (Android 11): 16 of 16 first connects to an idle call listener were missing from the stack's
 * server map ('serverReadPhy() - no connection to'), and the client's subscribe hung to its 12 s timeout.
 */
class GattServerConnectionWatchTest {
    private val known = KnownDeviceTraits.load().associateBy { it.alias }
    private var now = 1_000L
    private val watch = ServerConnectionWatch(timeoutMs = 400L, clock = { now })
    private val peer = "53:C5:3C:E1:21:CB"

    @Test
    fun theWatchAppliesToTheLegacyStackOnlyAndHasItsOffSwitch() {
        val entry = GattLinkPolicy.SERVER_CONNECTION_UNTRACKED
        assertEquals(setOf(BluetoothStackLineage.LEGACY_29_30), entry.matcher.stackLineages)
        val asus = DeviceFamilyPolicy.resolve(known.getValue("asus").traits, emptySet())
        assertEquals(400L, GattLinkPolicy.serverConnectionWatch(asus)?.timeoutMs)
        assertNotNull(GattLinkPolicy.serverConnectionWatch(DeviceFamilyPolicy.resolve(known.getValue("a15s").traits, emptySet())))
        for (alias in listOf("s22", "s24", "a55", "m31", "xiaomi")) {
            assertNull(alias, GattLinkPolicy.serverConnectionWatch(DeviceFamilyPolicy.resolve(known.getValue(alias).traits, emptySet())))
        }
        assertNull(GattLinkPolicy.serverConnectionWatch(DeviceFamilyPolicy.resolve(known.getValue("asus").traits, setOf(entry.id))))
    }

    @Test
    fun aConnectionWithoutAPhyAnswerFor400MsIsReregistered() {
        assertTrue(watch.onConnected(peer, advertisingListener = true, linkAdmitted = false))
        now += 399
        assertNull("not yet", watch.expired(linkAdmitted = false))
        now += 1
        assertEquals(peer, watch.expired(linkAdmitted = false))
        assertNull("once", watch.expired(linkAdmitted = false))
    }

    @Test
    fun aPhyAnswerOrADisconnectDisarmsIt() {
        watch.onConnected(peer, advertisingListener = true, linkAdmitted = false)
        now += 8
        watch.onPhyAnswer(peer.lowercase())
        now += 1_000
        assertNull(watch.expired(linkAdmitted = false))

        watch.onConnected(peer, advertisingListener = true, linkAdmitted = false)
        watch.onDisconnected(peer)
        now += 1_000
        assertNull(watch.expired(linkAdmitted = false))
    }

    @Test
    fun onlyTheAdvertisingListenerIsWatchedAndAnAdmittedLinkIsNeverCut() {
        assertFalse("the dual-listening second server hears links it does not own", watch.onConnected(peer, false, false))
        assertFalse("a link is already admitted", watch.onConnected(peer, true, true))
        assertNull(watch.watching())

        watch.onConnected(peer, advertisingListener = true, linkAdmitted = false)
        now += 500
        assertNull("admitted meanwhile: the record was there", watch.expired(linkAdmitted = true))
        assertNull(watch.watching())
    }

    /** asus-a15s-m31-055022 step 2: subscribe acknowledged, the first handshake write reached the stack but never the app. */
    private val twoStage = ServerConnectionWatch(timeoutMs = 400L, clock = { now }, firstWriteTimeoutMs = 1_200L)

    @Test
    fun theFirstWriteMustReachTheAppWithin1200MsOfTheSubscribe() {
        assertEquals(1_200L, GattLinkPolicy.serverFirstWriteWatch(DeviceFamilyPolicy.resolve(known.getValue("asus").traits, emptySet()))?.timeoutMs)
        assertNull(GattLinkPolicy.serverFirstWriteWatch(DeviceFamilyPolicy.resolve(known.getValue("s22").traits, emptySet())))
        assertTrue(twoStage.onSubscribed(peer, advertisingListener = true))
        now += 1_199
        assertNull(twoStage.firstWriteExpired(dataReceived = false))
        now += 1
        assertEquals(peer, twoStage.firstWriteExpired(dataReceived = false))
        assertNull("once", twoStage.firstWriteExpired(dataReceived = false))
    }

    @Test
    fun aHealthyFirstWriteOrADisconnectDisarmsTheSecondStage() {
        twoStage.onSubscribed(peer, advertisingListener = true)
        now += 140
        twoStage.onDataReceived(peer)
        now += 5_000
        assertNull(twoStage.firstWriteExpired(dataReceived = true))

        twoStage.onSubscribed(peer, advertisingListener = true)
        twoStage.onDisconnected(peer)
        now += 5_000
        assertNull(twoStage.firstWriteExpired(dataReceived = false))
    }

    @Test
    fun aLinkThatHasReceivedDataIsNeverCutAndTheStageIsOffWithoutItsTimeout() {
        twoStage.onSubscribed(peer, advertisingListener = true)
        now += 2_000
        assertNull("data arrived by another path: never cut", twoStage.firstWriteExpired(dataReceived = true))

        assertFalse("the dual-listening second server", twoStage.onSubscribed(peer, advertisingListener = false))
        assertFalse("stage 2 not configured", watch.onSubscribed(peer, advertisingListener = true))
        now += 5_000
        assertNull(watch.firstWriteExpired(dataReceived = false))
    }

    @Test
    fun theTwoStagesAreIndependent() {
        twoStage.onConnected(peer, advertisingListener = true, linkAdmitted = false)
        twoStage.onPhyAnswer(peer)
        twoStage.onSubscribed(peer, advertisingListener = true)
        now += 1_200
        assertNull("stage 1 was answered", twoStage.expired(linkAdmitted = true))
        assertEquals(peer, twoStage.firstWriteExpired(dataReceived = false))
    }

    @Test
    fun aNewerConnectReplacesTheOlderOneAndAnAnswerForAnotherPeerChangesNothing() {
        watch.onConnected("AA:00:00:00:00:01", advertisingListener = true, linkAdmitted = false)
        now += 300
        watch.onConnected(peer, advertisingListener = true, linkAdmitted = false)
        watch.onPhyAnswer("AA:00:00:00:00:01")
        now += 200
        assertNull("the newer connect's own 400 ms have not passed", watch.expired(linkAdmitted = false))
        now += 200
        assertEquals(peer, watch.expired(linkAdmitted = false))
    }
}
