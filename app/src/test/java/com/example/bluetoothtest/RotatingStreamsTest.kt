package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Rotating discovery IDs, Phase 1b: stream offers, viewer requests, token grace, hop checks, recovery hints. */
class RotatingStreamsTest {
    private val presenceKey = ByteArray(32) { it.toByte() }
    private val node = NodeId.fromBytes("a1b2c3d4e5f60718293a4b5c6d7e8f90".hexToBytes())
    private val epoch = 1_990_656L
    private val logical = 0x12345678

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Before
    fun fixTheClockOfTheEpochWindow() {
        DiscoverySelectionCheck.unixSeconds = { 1_791_590_400L + 100L }
    }

    @After
    fun restoreTheClock() {
        DiscoverySelectionCheck.unixSeconds = { System.currentTimeMillis() / 1000L }
    }

    private fun entry(role: Int, e: Long = epoch) = RotatingDiscoveryIds.entry(presenceKey, node.toByteArray(), e, role)

    // ----- route-token window -----

    @Test
    fun windowAcceptsTheCurrentTokenAndTheReplacedOneForSixtySeconds() {
        val window = RouteTokenWindow()
        window.reset(logical, 0x80000001.toInt())
        assertTrue(window.accepts(0x80000001.toInt(), 0L))
        window.advance(logical, 0x80000002.toInt(), nowMs = 1_000L)
        assertTrue(window.accepts(0x80000002.toInt(), 1_000L))
        assertTrue(window.accepts(0x80000001.toInt(), 1_000L + 59_999L))
        assertFalse(window.accepts(0x80000001.toInt(), 1_000L + 60_000L))
        assertFalse(window.accepts(0x80000003.toInt(), 1_000L))
        assertFalse(window.accepts(0, 1_000L))
    }

    @Test
    fun windowKeepsAtMostTwoRetiredTokensAndNeverTheOldestBeyondThat() {
        val window = RouteTokenWindow()
        window.reset(logical, 1)
        window.advance(logical, 2, 10L)
        window.advance(logical, 3, 20L)
        window.advance(logical, 4, 30L)
        assertTrue(window.accepts(4, 31L))
        assertTrue(window.accepts(3, 31L))
        assertTrue(window.accepts(2, 31L))
        assertFalse(window.accepts(1, 31L))
    }

    @Test
    fun aNewStreamOrAClearDropsEveryOldToken() {
        val window = RouteTokenWindow()
        window.reset(logical, 7)
        window.advance(logical, 8, 5L)
        window.advance(logical + 1, 9, 6L)
        assertTrue(window.accepts(9, 7L))
        assertFalse(window.accepts(8, 7L))
        assertFalse(window.accepts(7, 7L))
        window.clear()
        assertFalse(window.accepts(9, 7L))
    }

    @Test
    fun theSameTokenAgainChangesNothingAndIsTheOffBehaviour() {
        val window = RouteTokenWindow()
        window.reset(logical, 5)
        window.advance(logical, 5, 100L)
        assertTrue(window.accepts(5, 1_000_000L))
        assertEquals(5, window.current())
    }

    // ----- announcements: reviewed vectors -----

    @Test
    fun streamOfferMatchesTheReviewedVector() {
        val role1 = entry(RotatingDiscoveryIds.ROLE_STREAM_OFFER)
        assertEquals("bb2d061f6480", hex(role1.rdi))
        val base = DiscoveryAnnouncement(
            nodeIdPrefix = ByteArray(6),
            flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
            capabilitySummary = NodeCapabilities.CODEC_VP9,
            streamToken = 0,
            routeCost = 0,
            sequence = 1,
            streamWidth = 640,
            streamHeight = 360,
            streamFps = 15,
            streamBitrateKbps = 300,
        )
        val offer = RotatingStreamAnnouncements.streamOffer(base, role1, logical)
        assertEquals(0x92529fc4.toInt(), offer.streamToken)
        assertEquals("05bb2d061f64800c0192529fc40001028001680f012c", hex(DiscoveryAnnouncementCodec.encode(offer)))
        // The logical token is recoverable by anyone (RV14), and it is the same value in every epoch.
        assertEquals(logical, offer.logicalStreamToken())
    }

    @Test
    fun viewerRequestMatchesTheReviewedVectorsWithAndWithoutTheHint() {
        val role2 = entry(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST)
        assertEquals("974ae410ea91", hex(role2.rdi))
        val base = DiscoveryAnnouncement(
            nodeIdPrefix = ByteArray(6),
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
            capabilitySummary = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            streamToken = 0x92529fc4.toInt(),
            routeCost = 0,
            sequence = 0,
            streamBitrateKbps = 300,
        )
        val plain = RotatingStreamAnnouncements.viewerRequest(base, role2, null)
        // Hardening: a version 5 request carries no decode mask (summary byte 0), nothing a source matches on changes.
        assertEquals("05974ae410ea91090092529fc400000000000000012c", hex(DiscoveryAnnouncementCodec.encode(plain)))
        assertEquals(0, plain.capabilitySummary)
        val hint = "4d832e9d4dc7".hexToBytes()
        val recovering = RotatingStreamAnnouncements.viewerRequest(base, role2, hint)
        assertEquals("05974ae410ea91890092529fc4004d832e9d4dc7012c", hex(DiscoveryAnnouncementCodec.encode(recovering)))
        // The decoder reads both back as version 5 with the hint in the recovery bytes.
        val decoded = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(recovering), acceptRotating = true)
        assertTrue(decoded.rotating)
        assertArrayEquals(hint, decoded.recoveryFromNodeIdPrefix)
        assertTrue(decoded.isViewerRequest(0x92529fc4.toInt()))
    }

    @Test
    fun aStreamOfferAdvertisesAnOddBitrateOnTheCompactUnitGrid() {
        val base = DiscoveryAnnouncement(
            nodeIdPrefix = ByteArray(6),
            flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
            capabilitySummary = NodeCapabilities.CODEC_VP9,
            streamToken = 0,
            routeCost = 0,
            sequence = 1,
            streamWidth = 640,
            streamHeight = 360,
            streamFps = 15,
            streamBitrateKbps = 487,
        )
        val offer = RotatingStreamAnnouncements.streamOffer(base, entry(RotatingDiscoveryIds.ROLE_STREAM_OFFER), logical)
        assertEquals(475, offer.streamBitrateKbps)
        // Everything a viewer matches on is exact: codec, route token, cost, sequence and size.
        assertEquals(base.copy(nodeIdPrefix = offer.nodeIdPrefix, streamToken = offer.streamToken, version = 5, streamBitrateKbps = 475), offer)
    }

    @Test
    fun anOffAnnouncerCannotReadVersion5() {
        val offer = RotatingStreamAnnouncements.streamOffer(
            DiscoveryAnnouncement(
                nodeIdPrefix = ByteArray(6),
                flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
                capabilitySummary = 1,
                streamToken = 0,
                routeCost = 0,
                sequence = 0,
            ),
            entry(RotatingDiscoveryIds.ROLE_STREAM_OFFER),
            logical,
        )
        val bytes = DiscoveryAnnouncementCodec.encode(offer)
        val error = runCatching { DiscoveryAnnouncementCodec.decode(bytes, acceptRotating = false) }.exceptionOrNull()
        assertEquals("Unsupported announcement version", error?.message)
    }

    @Test
    fun wrongRoleEntriesAreRejectedWhenBuildingAnnouncements() {
        val base = DiscoveryAnnouncement(ByteArray(6), 1, 1, 0, 0, 0)
        val offerWithViewerEntry = runCatching {
            RotatingStreamAnnouncements.streamOffer(base, entry(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST), logical)
        }
        assertTrue(offerWithViewerEntry.isFailure)
        val requestWithOfferEntry = runCatching {
            RotatingStreamAnnouncements.viewerRequest(base, entry(RotatingDiscoveryIds.ROLE_STREAM_OFFER), null)
        }
        assertTrue(requestWithOfferEntry.isFailure)
    }

    // ----- versions per role and transport -----

    @Test
    fun versionRulesFollowTheExperimentTheTransportAndTheSelectedOffer() {
        val le = BluetoothTransportOption.BLE_L2CAP
        val gatt = BluetoothTransportOption.BLE_GATT
        val classic = BluetoothTransportOption.CLASSIC_RFCOMM
        assertTrue(RotatingStreamAnnouncements.offerRotates(true, le))
        assertTrue(RotatingStreamAnnouncements.offerRotates(true, gatt))
        assertFalse(RotatingStreamAnnouncements.offerRotates(true, classic))
        assertFalse(RotatingStreamAnnouncements.offerRotates(false, le))
        // The request mirrors the offer: version 5 only for a version 5 offer (a version 2 source cannot decode it).
        assertTrue(RotatingStreamAnnouncements.viewerRequestRotates(true, le, selectedOfferRotating = true))
        assertFalse(RotatingStreamAnnouncements.viewerRequestRotates(true, le, selectedOfferRotating = false))
        assertFalse(RotatingStreamAnnouncements.viewerRequestRotates(true, classic, selectedOfferRotating = true))
        assertFalse(RotatingStreamAnnouncements.viewerRequestRotates(false, le, selectedOfferRotating = true))
    }

    // ----- link hello and hop checks -----

    private val base = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = 1,
        decodeCodecMask = 1,
        maxBitrateKbps = 1_000,
        maxChildren = 0,
    )

    @Test
    fun linkBitIsSignedOnlyByAnLeNonGroupHopWithTheExperimentOn() {
        val le = BluetoothTransportOption.BLE_L2CAP
        val signed = StreamHopOpening.linkCapabilities(base, true, le)
        assertEquals(base.transportMask or RotatingDiscoveryIds.LINK_HELLO_BIT, signed.transportMask)
        assertEquals(base, StreamHopOpening.linkCapabilities(base, false, le))
        assertEquals(base, StreamHopOpening.linkCapabilities(base, true, BluetoothTransportOption.CLASSIC_RFCOMM))
        val group = base.copy(maxChildren = 4)
        assertEquals(group, StreamHopOpening.linkCapabilities(group, true, le))
        val forwarder = base.copy(maxChildren = 1)
        assertNotEquals(forwarder, StreamHopOpening.linkCapabilities(forwarder, true, le))
    }

    private fun offerFor(role: Int, rotating: Boolean, entry: OpeningEntry? = null): DiscoveryAnnouncement =
        DiscoveryAnnouncement(
            nodeIdPrefix = if (rotating) requireNotNull(entry).rdi else node.toByteArray().copyOf(6),
            flags = if (role == RotatingDiscoveryIds.ROLE_STREAM_OFFER) {
                DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE
            } else {
                DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS
            },
            capabilitySummary = 1,
            streamToken = 0x80000001.toInt(),
            routeCost = 0,
            sequence = 0,
            version = if (rotating) DiscoveryAnnouncement.ROTATING_VERSION else DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
        )

    @Test
    fun aVersion5SelectionNeedsAnOpeningOfTheRightRoleForTheLinkNode() {
        val offerEntry = entry(RotatingDiscoveryIds.ROLE_STREAM_OFFER)
        val selected = offerFor(RotatingDiscoveryIds.ROLE_STREAM_OFFER, true, offerEntry)
        val pass = StreamHopOpening.check(selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, node, listOf(offerEntry))
        assertEquals(DiscoverySelectionCheck.Result.PASS, pass)
        // No opening at all (a peer without bit 3): fails closed, never falls back to the prefix.
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISSING,
            StreamHopOpening.check(selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, node, null),
        )
        // A role-2 entry cannot open a role-1 offer, and a stranger's node cannot open anyone's ID.
        val viewerEntry = entry(RotatingDiscoveryIds.ROLE_VIEWER_REQUEST)
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            StreamHopOpening.check(selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, node, listOf(viewerEntry)),
        )
        val other = NodeId.fromBytes(ByteArray(16) { 9 })
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            StreamHopOpening.check(selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, other, listOf(offerEntry)),
        )
        // A replayed entry for another epoch does not open this epoch's ID.
        val older = entry(RotatingDiscoveryIds.ROLE_STREAM_OFFER, epoch - 1)
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            StreamHopOpening.check(selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, node, listOf(older)),
        )
        // The endpoint must be the link node.
        assertEquals(
            DiscoverySelectionCheck.Result.PREFIX_MISMATCH,
            StreamHopOpening.check(selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, node, listOf(offerEntry), other),
        )
        assertEquals(
            DiscoverySelectionCheck.Result.PASS,
            StreamHopOpening.check(selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, node, listOf(offerEntry), node),
        )
    }

    @Test
    fun aVersion2SelectionKeepsThePrefixCheckWhateverTheHelloSays() {
        val selected = offerFor(RotatingDiscoveryIds.ROLE_STREAM_OFFER, false)
        assertEquals(
            DiscoverySelectionCheck.Result.PASS,
            StreamHopOpening.check(selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, node, null),
        )
        // An opening, if the peer sent one anyway, changes nothing.
        assertEquals(
            DiscoverySelectionCheck.Result.PASS,
            StreamHopOpening.check(
                selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, node,
                listOf(entry(RotatingDiscoveryIds.ROLE_STREAM_OFFER)),
            ),
        )
        assertEquals(
            DiscoverySelectionCheck.Result.PREFIX_MISMATCH,
            StreamHopOpening.check(
                selected, RotatingDiscoveryIds.ROLE_STREAM_OFFER, NodeId.fromBytes(ByteArray(16) { 3 }), null,
            ),
        )
    }

    // ----- recovery hints -----

    @Test
    fun aSourceMatchesTheViewerOfItsFailedChildAndNobodyElse() {
        val mediaKey = ByteArray(32) { (0x40 + it).toByte() }
        val failedChild = node
        val rdi = "974ae410ea91".hexToBytes()
        val token = 0x92529fc4.toInt()
        val hint = RecoveryMatching.hintFor(mediaKey, failedChild, logical, rdi, token)
        assertEquals("4d832e9d4dc7", hex(hint))
        val otherNode = NodeId.fromBytes(ByteArray(16) { 5 })
        val otherKey = ByteArray(32) { 7 }
        val children = listOf(
            RecoveryMatching.Child("r:other", otherNode, otherKey),
            RecoveryMatching.Child("r:failed", failedChild, mediaKey),
        )
        assertEquals("r:failed", RecoveryMatching.match(hint, logical, rdi, token, children))
        // A different stream's logical token, a wrong key or another failed node never match.
        assertNull(RecoveryMatching.match(hint, logical + 1, rdi, token, children))
        assertNull(
            RecoveryMatching.match(RecoveryMatching.hintFor(otherKey, failedChild, logical, rdi, token), logical, rdi, token, children),
        )
        assertNull(
            RecoveryMatching.match(
                RecoveryMatching.hintFor(mediaKey, otherNode, logical, rdi, token), logical, rdi, token, listOf(children[1]),
            ),
        )
        assertNull(RecoveryMatching.match(ByteArray(6), logical, rdi, token, children))
        assertNull(RecoveryMatching.match(ByteArray(5), logical, rdi, token, children))
        // RD3: the hint is bound to the request that carries it. Copied into an advert with another ID or
        // token (a bystander's, or the viewer's next epoch), it matches nobody.
        assertNull(RecoveryMatching.match(hint, logical, "67aba670e65a".hexToBytes(), token, children))
        assertNull(RecoveryMatching.match(hint, logical, rdi, token + 1, children))
    }

    @Test
    fun theViewerRemembersOnlyAForwarderKeyForItsOwnStreamAndClearsItOnADirectLink() {
        ViewerRecoveryMemory.clear()
        assertNull(ViewerRecoveryMemory.hint(logical, ByteArray(6), 1))
        val key = ByteArray(32) { (0x40 + it).toByte() }
        ViewerRecoveryMemory.remember(node, key, logical)
        val rdi = "974ae410ea91".hexToBytes()
        val token = 0x92529fc4.toInt()
        assertEquals("4d832e9d4dc7", hex(requireNotNull(ViewerRecoveryMemory.hint(logical, rdi, token))))
        assertNull(ViewerRecoveryMemory.hint(logical + 1, rdi, token))
        assertNull(ViewerRecoveryMemory.hint(0, rdi, token))
        ViewerRecoveryMemory.clear()
        assertNull(ViewerRecoveryMemory.hint(logical, rdi, token))
    }

    // ----- passive tracker -----

    @Test
    fun noRdiTokenOrAddressFieldCarriesAcrossBoundariesExceptTheDeclaredConstants() {
        // What a passive scanner logs for one phone that is call-ready, streaming and waiting as a viewer.
        // Declared constants: flags, capability bits, geometry (quasi-identifiers, RV14) and the stream's
        // logical token, recoverable from every epoch's route token by design.
        val epochs = (0 until 5).map { epoch + it }
        val ids = HashSet<String>()
        val routeTokens = HashSet<Int>()
        var constantLogical: Int? = null
        epochs.forEach { e ->
            listOf(
                RotatingDiscoveryIds.ROLE_CALL_OFFER,
                RotatingDiscoveryIds.ROLE_STREAM_OFFER,
                RotatingDiscoveryIds.ROLE_VIEWER_REQUEST,
            ).forEach { role ->
                val rdi = hex(entry(role, e).rdi)
                assertTrue("RDI repeated across epochs or roles: $rdi", ids.add(rdi))
            }
            val offer = RotatingStreamAnnouncements.streamOffer(
                DiscoveryAnnouncement(ByteArray(6), DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE, 1, 0, 0, 0),
                entry(RotatingDiscoveryIds.ROLE_STREAM_OFFER, e),
                logical,
            )
            assertTrue("route token repeated across epochs", routeTokens.add(offer.streamToken))
            val recovered = offer.logicalStreamToken()
            if (constantLogical == null) constantLogical = recovered
            assertEquals("the logical token is the one declared constant", constantLogical, recovered)
        }
        assertEquals(15, ids.size)
        assertEquals(5, routeTokens.size)
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
