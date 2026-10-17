package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADV-01 (aosp-bt-versions.md): a connectable legacy advertisement has 28 bytes of primary data on Android 13 and
 * below (the 3-byte Flags field is always charged) and 31 on Android 14 and above when the set is non-discoverable.
 * The same payload that starts on Android 14 throws on Android 12/13. Every MeshLive advertisement is checked here
 * against the smaller budget, with the real encoders at their largest.
 */
class AdvertisingPayloadTest {
    private val oldestSdk = 29
    private val lastFlagsAlwaysSdk = 33
    private val firstDiscoverableSdk = 34

    @Test
    fun theConnectableBudgetIs28BytesOnAndroid13AndBelowAnd31OnlyForANonDiscoverableSetOnAndroid14() {
        for (sdk in oldestSdk..lastFlagsAlwaysSdk) {
            assertEquals("sdk $sdk connectable", 28, AdvertisingPayload.primaryBudget(connectable = true, discoverable = true, sdk = sdk))
            assertEquals("sdk $sdk: setDiscoverable does not exist yet", 28, AdvertisingPayload.primaryBudget(true, false, sdk))
            assertEquals("sdk $sdk non-connectable", 31, AdvertisingPayload.primaryBudget(false, true, sdk))
        }
        assertEquals(28, AdvertisingPayload.primaryBudget(true, true, firstDiscoverableSdk))
        assertEquals(31, AdvertisingPayload.primaryBudget(true, false, firstDiscoverableSdk))
        assertEquals(31, AdvertisingPayload.primaryBudget(true, false, 36))
        assertEquals(31, AdvertisingPayload.primaryBudget(false, true, 36))
        assertEquals(28, AdvertisingPayload.CONNECTABLE_OLD_SDK_PRIMARY)
        assertEquals("scan response is never charged flags", 31, AdvertisingPayload.scanResponseBudget())
    }

    @Test
    fun aPayloadOfTwentyNineBytesStartsOnAndroid14ButNotOnAndroid13() {
        assertFalse(AdvertisingPayload.fits(29, connectable = true, discoverable = true, sdk = 33))
        assertFalse(AdvertisingPayload.fits(29, connectable = true, discoverable = false, sdk = 33))
        assertTrue(AdvertisingPayload.fits(29, connectable = true, discoverable = false, sdk = 34))
        assertFalse(AdvertisingPayload.fits(29, connectable = true, discoverable = true, sdk = 34))
        assertTrue(AdvertisingPayload.fits(28, connectable = true, discoverable = true, sdk = 29))
    }

    @Test
    fun theMeshLiveServiceUuidIsAFull128BitEntryNotAShortenedOne() {
        assertEquals(16, AdvertisingPayload.uuidBytes(BleL2capControlProtocol.SERVICE_UUID))
        assertEquals(2, AdvertisingPayload.uuidBytes(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")))
        assertEquals(4, AdvertisingPayload.uuidBytes(UUID.fromString("12345678-0000-1000-8000-00805f9b34fb")))
        assertEquals(18, AdvertisingPayload.SERVICE_UUID_128)
    }

    @Test
    fun theConnectablePrimaryDataOfEverySetFitsTheOldBudget() {
        // StableAdvertisingSet owners (BleL2capControl, GattVideoTransport, NameWindowAdvertiser) and
        // BleDiscoveryAdvertiser: the service UUID only. Title and reach sets are non-connectable and use the same data.
        val primary = AdvertisingPayload.primaryBytes(serviceUuids128 = 1)
        assertEquals(18, primary)
        assertTrue("$primary of ${AdvertisingPayload.CONNECTABLE_OLD_SDK_PRIMARY}", primary <= AdvertisingPayload.CONNECTABLE_OLD_SDK_PRIMARY)
        for (sdk in oldestSdk..36) {
            for (discoverable in listOf(true, false)) {
                assertTrue("sdk $sdk discoverable=$discoverable", AdvertisingPayload.fits(primary, true, discoverable, sdk))
            }
        }
    }

    @Test
    fun theDiscoveryAnnouncementInTheScanResponseFits() {
        val announcement = DiscoveryAnnouncement(
            nodeIdPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { 1 },
            flags = 0xff and DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY.inv(), capabilitySummary = 0xff, streamToken = -1, routeCost = 0xff, sequence = 0xff,
            streamWidth = 0xffff, streamHeight = 0xffff, streamFps = 0xff, streamBitrateKbps = 0xffff,
        )
        val encoded = DiscoveryAnnouncementCodec.encode(announcement)
        assertEquals(22, encoded.size)
        val scan = AdvertisingPayload.manufacturer(encoded.size)
        assertEquals(26, scan)
        assertTrue(scan <= AdvertisingPayload.scanResponseBudget())
        // A route-recovery announcement reuses the same six bytes: no larger.
        val recovery = DiscoveryAnnouncement(
            nodeIdPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { 1 },
            flags = DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY, capabilitySummary = 1, streamToken = 1, routeCost = 1, sequence = 1,
            recoveryFromNodeIdPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { 2 },
        )
        assertEquals(22, DiscoveryAnnouncementCodec.encode(recovery).size)
    }

    @Test
    fun theUuidAndTheAnnouncementCouldNotShareOnePrimaryPayload() {
        // The reason the manufacturer data lives in the scan response: together they exceed even 31 bytes.
        val together = AdvertisingPayload.primaryBytes(
            serviceUuids128 = 1,
            manufacturerPayloads = listOf(DiscoveryAnnouncementCodec.ENCODED_BYTES),
        )
        assertEquals(44, together)
        assertFalse(together <= AdvertisingPayload.LEGACY_MAX)
    }

    @Test
    fun theLargestTitleReachAndNamePagesFitTheScanResponse() {
        val maxTitle = "a".repeat(LabelKind.STREAM_TITLE.maxBytes)
        val titlePages = TitlePageCodec.pages(routeToken = 0x01020304, title = maxTitle)
        assertEquals(3, titlePages.size)
        val largestTitle = titlePages.maxOf { AdvertisingPayload.manufacturer(it.size) }
        assertEquals("a full title page uses the whole 31 bytes", 31, largestTitle)
        assertTrue(largestTitle <= AdvertisingPayload.scanResponseBudget())

        val entry = { seed: Int ->
            ReachPageEntry(
                target = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { (seed + it).toByte() },
                cost = ReachPageCodec.MAX_COST, ageUnits = ReachPageCodec.MAX_AGE_UNITS, via = byteArrayOf(1, 2),
            )
        }
        for (rotating in listOf(false, true)) {
            val reachPages = ReachPageCodec.pages(
                relayPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { 9 },
                relayFlags = ReachPageCodec.FLAG_IDLE,
                entries = (1..8).map { entry(it * 10) },
                maxPages = ReachPageCodec.MAX_PAGES,
                rotating = rotating,
            )
            assertEquals(4, reachPages.size)
            val largestReach = reachPages.maxOf { AdvertisingPayload.manufacturer(it.size) }
            assertEquals(31, largestReach)
            assertTrue(largestReach <= AdvertisingPayload.scanResponseBudget())
        }

        val longestName = "n".repeat(NamePageCodec.MAX_NAME_BYTES)
        val namePages = NamePageCodec.pages(ByteArray(RotatingDiscoveryIds.ID_BYTES) { 3 }, longestName)!!
        val largestName = namePages.maxOf { AdvertisingPayload.manufacturer(it.size) }
        assertTrue("$largestName", largestName <= AdvertisingPayload.scanResponseBudget())
    }

    @Test
    fun describeShowsTheMarginForTheDebugLine() {
        assertEquals("primary=18/28 scan=26/31", AdvertisingPayload.describe(18, 26, connectable = true, discoverable = true, sdk = 33))
        assertEquals("primary=18/31 scan=26/31", AdvertisingPayload.describe(18, 26, connectable = true, discoverable = false, sdk = 34))
    }
}
