package com.example.bluetoothtest

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rotating IDs and relayed calls: reach pages version 6, the rotating table, SETUP message 4 and the feature gates. */
class ReachRotatingTest {
    private var now = 1_000L
    private val ownNow = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
    private val ownPrevious = byteArrayOf(0x71, 0x72, 0x73, 0x74, 0x75, 0x76)
    private val target = byteArrayOf(0xfe.toByte(), 0xcb.toByte(), 0xa6.toByte(), 0x49, 0x83.toByte(), 0xfd.toByte())
    private val relay = byteArrayOf(0x44, 0xe6.toByte(), 0x9c.toByte(), 0x0a, 0x7a, 0x7d)
    private val other = byteArrayOf(0x97.toByte(), 0x4a, 0xe4.toByte(), 0x10, 0xea.toByte(), 0x91.toByte())

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun hexBytes(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun rotatingTable() = ReachTable<String>(
        ByteArray(6), ReachConfig(), { now }, Random(1),
        rotating = true, ownIds = { listOf(ownNow, ownPrevious) },
    )

    private fun fixedTable() = ReachTable<String>(ownNow, ReachConfig(), { now }, Random(1))

    private fun offer(prefix: ByteArray, rotating: Boolean, consent: Boolean = true, helper: Boolean = false, cost: Int = 0) =
        DiscoveryAnnouncement(
            nodeIdPrefix = prefix,
            flags = DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                (if (helper) DiscoveryAnnouncement.FLAG_RELAY_CAPABLE else 0),
            capabilitySummary = NodeCapabilities.CODEC_VP9 or (if (consent) RelayedCalls.CONSENT_BIT else 0),
            streamToken = 0,
            routeCost = cost,
            sequence = 0,
            version = if (rotating) DiscoveryAnnouncement.ROTATING_VERSION else DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
        )

    private fun seenTwice(block: () -> Unit) {
        block()
        now += 2_000
        block()
    }

    // ----- reach page version 6 -----

    @Test
    fun version6PageMatchesTheVectorAndDecodesAsRotating() {
        val page = ReachPage(
            relay, ReachPageCodec.FLAG_COC or ReachPageCodec.FLAG_IDLE, 0, 1,
            listOf(ReachPageEntry(target, 1, 3, target.copyOf(2))), rotating = true,
        )
        val bytes = ReachPageCodec.encode(page)
        assertEquals("0644e69c0a7a7d0901fecba64983fd23fecb", hex(bytes))
        val decoded = ReachPageCodec.decode(bytes)
        assertTrue(decoded.rotating)
        assertEquals(page, decoded)
        // The same bytes with version 4 are the fixed-prefix page.
        val fixed = ReachPageCodec.decode(bytes.copyOf().also { it[0] = 4 })
        assertFalse(fixed.rotating)
        assertFalse(fixed == decoded)
        assertTrue(ReachPageCodec.isReachPage(bytes))
        assertTrue(ReachPageCodec.isReachPage(bytes.copyOf().also { it[0] = 4 }))
        assertFalse(ReachPageCodec.isReachPage(bytes.copyOf().also { it[0] = 5 }))
        assertFalse(ReachPageCodec.isReachPage(bytes.copyOf().also { it[0] = 3 }))
        assertThrows(IllegalArgumentException::class.java) { ReachPageCodec.decode(bytes.copyOf().also { it[0] = 5 }) }
    }

    @Test
    fun twoEntryVersion6PageMatchesTheVector() {
        val page = ReachPage(
            relay, ReachPageCodec.FLAG_COC or ReachPageCodec.FLAG_IDLE, 1, 2,
            listOf(
                ReachPageEntry(target, 2, 31, hexBytes("a1b2")),
                ReachPageEntry(other, 1, 0, other.copyOf(2)),
            ),
            rotating = true,
        )
        assertEquals("0644e69c0a7a7d0912fecba64983fd5fa1b2974ae410ea9120974a", hex(ReachPageCodec.encode(page)))
        assertEquals(27, ReachPageCodec.encode(page).size)
    }

    @Test
    fun pagesCarryTheRotatingFlagAndStaySmallEnoughForALegacySet() {
        val entries = (0 until 4).map { i ->
            ReachPageEntry(byteArrayOf(i.toByte(), 2, 3, 4, 5, 6), 1, i, byteArrayOf(i.toByte(), 2))
        }
        val pages = ReachPageCodec.pages(relay, ReachPageCodec.FLAG_COC, entries, rotating = true)
        assertEquals(2, pages.size)
        pages.forEach { bytes ->
            assertEquals(6, bytes[0].toInt())
            // 31 bytes of scan response minus the 4-byte manufacturer header.
            assertTrue(bytes.size <= 27)
        }
    }

    // ----- the rotating table -----

    @Test
    fun theTwoPopulationsNeverMix() {
        val rotating = rotatingTable()
        val fixed = fixedTable()
        // A version 5 consenting offer enters only the rotating table; a version 2 one only the fixed table.
        seenTwice { rotating.observeOffer("t5", offer(target, rotating = true), "AA:01") }
        seenTwice { fixed.observeOffer("t5", offer(target, rotating = true), "AA:01") }
        assertEquals(1, rotating.size)
        assertEquals(0, fixed.size)
        val rotating2 = rotatingTable()
        val fixed2 = fixedTable()
        seenTwice { rotating2.observeOffer("t2", offer(target, rotating = false), "AA:02") }
        seenTwice { fixed2.observeOffer("t2", offer(target, rotating = false), "AA:02") }
        assertEquals(0, rotating2.size)
        assertEquals(1, fixed2.size)
        // Pages are matched by their version too.
        val v6 = ReachPage(relay, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(target, 1, 0, target.copyOf(2))), rotating = true)
        val v4 = ReachPage(relay, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(target, 1, 0, target.copyOf(2))))
        val t = rotatingTable()
        seenTwice { t.observePage(v4, "AA:03") }
        assertEquals(0, t.size)
        seenTwice { t.observePage(v6, "AA:03") }
        assertEquals(1, t.size)
        val f = fixedTable()
        seenTwice { f.observePage(v6, "AA:04") }
        assertEquals(0, f.size)
    }

    @Test
    fun ownCurrentAndPreviousIdsAreNeverTargetsRelaysOrReturnRoutes() {
        val t = rotatingTable()
        // Offers of this phone's own IDs.
        seenTwice { t.observeOffer("me", offer(ownNow, rotating = true), "AA:01") }
        seenTwice { t.observeOffer("me", offer(ownPrevious, rotating = true), "AA:02") }
        assertEquals(0, t.size)
        // A page that names this phone as the relay, or as a target.
        val asRelay = ReachPage(ownNow, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(target, 1, 0, target.copyOf(2))), rotating = true)
        seenTwice { t.observePage(asRelay, "AA:03") }
        val asTarget = ReachPage(relay, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(ownPrevious, 1, 0, ownPrevious.copyOf(2))), rotating = true)
        seenTwice { t.observePage(asTarget, "AA:04") }
        assertEquals(0, t.size)
        // Poison reverse: a route whose next hop starts with this phone's current or previous ID is no route.
        listOf(ownNow, ownPrevious).forEachIndexed { index, id ->
            val through = ReachPage(relay, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(target, 2, 0, id.copyOf(2))), rotating = true)
            seenTwice { t.observePage(through, "AA:1$index") }
        }
        assertEquals(0, t.size)
        val clean = ReachPage(relay, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(target, 2, 0, hexBytes("a1b2"))), rotating = true)
        seenTwice { t.observePage(clean, "AA:20") }
        assertEquals(1, t.size)
    }

    @Test
    fun aBoundaryFlushEmptiesTheTableAndItRefillsAfterTwoSightings() {
        val t = rotatingTable()
        seenTwice { t.observeOffer("t", offer(target, rotating = true), "AA:01") }
        assertEquals(1, t.size)
        t.clear()
        assertEquals(0, t.size)
        assertNull(t.directOffer(target))
        t.observeOffer("t", offer(target, rotating = true), "AA:01")
        assertEquals(0, t.size)
        now += 2_000
        t.observeOffer("t", offer(target, rotating = true), "AA:01")
        assertEquals(1, t.size)
    }

    @Test
    fun aRotatingHelperOfferAndSetupRouteUseVersion5Bytes() {
        val t = rotatingTable()
        val helper = offer(relay, rotating = true, consent = false, helper = true)
        val page = ReachPage(relay, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(target, 1, 0, target.copyOf(2))), rotating = true)
        seenTwice { t.observeOffer("relay", helper, "AA:01") }
        seenTwice { t.observePage(page, "AA:01") }
        assertNotNull(t.helperOffer(relay))
        val routes = t.callerRoutes(target)
        assertEquals(1, routes.size)
        assertArrayEquals(relay, routes.single().nextHop)
        // A fixed-prefix helper offer of the same bytes does not count in the rotating table.
        val v2Table = fixedTable()
        seenTwice { v2Table.observeOffer("relay", offer(relay, rotating = false, consent = false, helper = true), "AA:02") }
        assertNull(v2Table.helperOffer(relay)?.takeIf { it.announcement.rotating })
        // The helper's own cost for the target is one more, and the page it would advertise carries RDIs only.
        now += 6_000
        val advertised = t.advertisable()
        assertEquals(1, advertised.size)
        assertEquals(2, advertised.single().entry.cost)
        assertArrayEquals(relay.copyOf(2), advertised.single().entry.via)
    }

    // ----- helpers of the feature -----

    @Test
    fun consentAndHelperOffersAreCountedPerPopulation() {
        val v5 = offer(target, rotating = true)
        val v2 = offer(target, rotating = false)
        assertTrue(RelayedCalls.consents(v5, rotating = true))
        assertFalse(RelayedCalls.consents(v5))
        assertTrue(RelayedCalls.consents(v2))
        assertFalse(RelayedCalls.consents(v2, rotating = true))
        val helper5 = offer(relay, rotating = true, consent = false, helper = true)
        assertTrue(RelayedCalls.isRelayHelperOffer(helper5, rotating = true))
        assertFalse(RelayedCalls.isRelayHelperOffer(helper5))
    }

    @Test
    fun aRotatingOfferCarriesTheBitsOnlyWithTheCompatibility() {
        val base = offer(target, rotating = true, consent = false)
        assertEquals(base, RelayedCalls.offerFor(base, consentOn = true, helperActive = true))
        val with = RelayedCalls.offerFor(base, consentOn = true, helperActive = true, rotatingCompat = true)
        assertTrue(RelayedCalls.consents(with, rotating = true))
        assertTrue(RelayedCalls.isRelayHelperOffer(with, rotating = true))
        assertEquals(DiscoveryAnnouncement.ROTATING_VERSION, with.version)
        // A relayed route keeps the version of its first hop.
        val routed = RelayedCalls.routedAnnouncement(target, 1, with)
        assertTrue(routed.rotating)
        assertEquals(1, routed.routeCost)
        val fixedRouted = RelayedCalls.routedAnnouncement(target, 1, offer(relay, rotating = false, helper = true))
        assertFalse(fixedRouted.rotating)
    }

    @Test
    fun helpingAndConsentFollowTheCompatibilitySwitch() {
        val wasEnabled = RelayedCallsFeature.enabled
        val wasCompat = RelayedCallsFeature.rotatingCompat
        try {
            RelayedCallsFeature.enabled = true
            RelayedCallsFeature.helpEnabled = true
            RelayedCallsFeature.consentEnabled = true
            RelayedCallsFeature.rotatingCompat = true
            assertTrue(RelayedCallsFeature.helperAllowed(rotatingIdsOn = true))
            assertTrue(RelayedCallsFeature.consentAllowed(rotatingIdsOn = true))
            assertTrue(RelayedCallsFeature.rotatingActive(true))
            assertFalse(RelayedCallsFeature.rotatingActive(false))
            // The earlier exclusion returns with `test_relayed_rotating=false`.
            RelayedCallsFeature.rotatingCompat = false
            assertFalse(RelayedCallsFeature.helperAllowed(rotatingIdsOn = true))
            assertFalse(RelayedCallsFeature.consentAllowed(rotatingIdsOn = true))
            assertFalse(RelayedCallsFeature.rotatingActive(true))
            assertTrue(RelayedCallsFeature.helperAllowed(rotatingIdsOn = false))
        } finally {
            RelayedCallsFeature.enabled = wasEnabled
            RelayedCallsFeature.rotatingCompat = wasCompat
        }
    }

    @Test
    fun theHelperPolicyPausesOnRotatingIdsOnlyWithoutTheCompatibility() {
        val base = RelayHelperInputs(
            featureOn = true, helpOn = true, rotatingIdsOn = true, leTransport = true, bluetoothReady = true,
            callReady = true, batteryPercent = 80, charging = false, batterySaver = false, thermalSevere = false,
            ownActivity = false, screenOn = true,
        )
        assertEquals(HelperPause.ROTATING_IDS, RelayHelperPolicy.plan(base).pause)
        assertEquals(HelperPause.NONE, RelayHelperPolicy.plan(base.copy(rotatingCompat = true)).pause)
    }

    // ----- SETUP message 4 -----

    @Test
    fun setupMessage4HasTheSameBodyAndIsUnknownUnlessOptedIn() {
        val id = hexBytes("0102030405060708")
        val rdi = RelaySetupCodec.encode(RelaySetupMessage.Setup(other, 2, 20_000, id, rotating = true))
        assertEquals("4252533104974ae410ea910200004e200102030405060708", hex(rdi))
        val fixed = RelaySetupCodec.encode(RelaySetupMessage.Setup(other, 2, 20_000, id))
        assertEquals("4252533101974ae410ea910200004e200102030405060708", hex(fixed))
        assertEquals(24, rdi.size)
        val decoded = RelaySetupCodec.decode(rdi, acceptRdi = true) as RelaySetupMessage.Setup
        assertTrue(decoded.rotating)
        assertArrayEquals(other, decoded.target)
        assertEquals(2, decoded.budget)
        assertEquals(20_000, decoded.remainingMs)
        // A reader that did not opt in rejects message 4 exactly as any unknown message.
        assertThrows(IllegalArgumentException::class.java) { RelaySetupCodec.decode(rdi) }
        assertFalse((RelaySetupCodec.decode(fixed, acceptRdi = true) as RelaySetupMessage.Setup).rotating)
        // READY and FAIL are not affected.
        val ready = RelaySetupCodec.encode(RelaySetupMessage.Ready(id, 1))
        assertEquals(14, ready.size)
        assertTrue(RelaySetupCodec.decode(ready, acceptRdi = true) is RelaySetupMessage.Ready)
    }

    @Test
    fun passiveTrackerSeesNoFixedByteInReachPagesAcrossEpochs() {
        // Pages of a relay for three epochs: relay ID, target ID and via change every epoch; the flags, the page
        // layout and the cost/age bytes are the declared quasi-identifiers.
        val presenceKey = ByteArray(32) { it.toByte() }
        val relayNode = NodeId.fromBytes(ByteArray(16) { 1 })
        val targetNode = NodeId.fromBytes(ByteArray(16) { 2 })
        val seen = HashSet<String>()
        (0 until 3).forEach { e ->
            val epoch = 1_990_656L + e
            val relayId = RotatingDiscoveryIds.ownEntry(presenceKey, relayNode.toByteArray(), epoch, 0).rdi
            val targetId = RotatingDiscoveryIds.ownEntry(ByteArray(32) { 9 }, targetNode.toByteArray(), epoch, 0).rdi
            val page = ReachPage(
                relayId, ReachPageCodec.FLAG_COC or ReachPageCodec.FLAG_IDLE, 0, 1,
                listOf(ReachPageEntry(targetId, 1, 2, targetId.copyOf(2))), rotating = true,
            )
            val bytes = ReachPageCodec.encode(page)
            listOf(hex(bytes.copyOfRange(1, 7)), hex(bytes.copyOfRange(9, 15)), hex(bytes.copyOfRange(16, 18)))
                .forEach { field -> assertTrue("field repeated across epochs: $field", seen.add(field)) }
        }
        assertEquals(9, seen.size)
    }
}
