package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/** Shared Swift/Kotlin vectors of the iPhone compact discovery announcement (PROTOCOL.md). */
class CompactDiscoveryAnnouncementTest {
    private val prefixA = bytes("010203040506")
    private val prefixB = bytes("a1b2c3d4e5f6")

    private val callOffer = DiscoveryAnnouncement(
        nodeIdPrefix = prefixA,
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
        capabilitySummary = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
        streamToken = 0,
        routeCost = 0,
        sequence = 0,
    )
    private val gattCallOffer = DiscoveryAnnouncement(
        nodeIdPrefix = prefixB,
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
            DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
        capabilitySummary = NodeCapabilities.CODEC_VP9,
        streamToken = 0,
        routeCost = 2,
        sequence = 0,
    )
    private val viewerRequest = DiscoveryAnnouncement(
        nodeIdPrefix = prefixA,
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS,
        capabilitySummary = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
        streamToken = 0x9abcdef0.toInt(),
        routeCost = 0,
        sequence = 0,
        streamBitrateKbps = 300,
    )
    private val streamOffer = DiscoveryAnnouncement(
        nodeIdPrefix = prefixB,
        flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT or
            DiscoveryAnnouncement.FLAG_RELAY_CAPABLE or DiscoveryAnnouncement.FLAG_STREAM_PAUSED,
        capabilitySummary = NodeCapabilities.CODEC_VP9,
        streamToken = 0xdeadbeef.toInt(),
        routeCost = 1,
        sequence = 0x85,
        streamWidth = 640,
        streamHeight = 360,
        streamFps = 15,
        streamBitrateKbps = 300,
    )

    private val vectors = listOf(
        "C8D64010-2030-4050-60C0-000000000000" to callOffer,
        "C8D64A1B-2C3D-4E5F-6848-000000000000" to gattCallOffer,
        "C8D65010-2030-4050-60C0-D5E6F780C000" to viewerRequest,
        "C8D66A1B-2C3D-4E5F-6E46-F56DF77C2A0C" to streamOffer,
    )

    @Test
    fun decodesAndEncodesTheSharedVectors() {
        vectors.forEach { (text, expected) ->
            val uuid = UUID.fromString(text)
            assertAnnouncement(expected, CompactDiscoveryAnnouncement.decode(uuid))
            assertEquals(text, CompactDiscoveryAnnouncement.encode(expected).toString().uppercase())
        }
    }

    @Test
    fun decodedAnnouncementsDriveTheExistingConsumers() {
        val call = CompactDiscoveryAnnouncement.decode(UUID.fromString(vectors[0].first))
        assertTrue(call.isPrivateCallOffer())
        assertFalse(call.isStreamOffer())
        assertEquals(BluetoothTransportOption.BLE_L2CAP, call.transportOption())
        assertEquals(0, PrivateCallRelayChain.callerRelayHops(call.routeCost))
        assertEquals(BluetoothTransportOption.BLE_GATT, CompactDiscoveryAnnouncement.decode(UUID.fromString(vectors[1].first)).transportOption())

        val viewer = CompactDiscoveryAnnouncement.decode(UUID.fromString(vectors[2].first))
        assertTrue(viewer.isViewerRequest(0x9abcdef0.toInt()))
        assertFalse(viewer.isPrivateCallOffer())
        assertFalse(viewer.isStreamOffer())

        val offer = CompactDiscoveryAnnouncement.decode(UUID.fromString(vectors[3].first))
        assertTrue(offer.isStreamOffer())
        assertTrue(offer.isFullStreamOffer())
        assertEquals(5, offer.capacityGeneration())
        assertFalse(offer.isPrivateCallOffer())
        assertFalse(offer.isViewerRequest(offer.streamToken))
        assertTrue(LogicalStreamTarget.from(offer).matches(offer))
    }

    @Test
    fun everyRealRouteTokenFitsBecauseBit31IsAlwaysSet() {
        listOf(1, 42, 0x12345678, Int.MAX_VALUE).forEach { logical ->
            listOf(prefixA, prefixB).forEach { prefix ->
                val route = StreamRouteToken.forAdvertiser(logical, prefix)
                val request = viewerRequest.copy(nodeIdPrefix = prefix, streamToken = route)
                val decoded = CompactDiscoveryAnnouncement.decode(CompactDiscoveryAnnouncement.encode(request))
                assertTrue(decoded.isViewerRequest(route))
                val decodedOffer = CompactDiscoveryAnnouncement.decode(
                    CompactDiscoveryAnnouncement.encode(streamOffer.copy(nodeIdPrefix = prefix, streamToken = route)),
                )
                assertEquals(logical, decodedOffer.logicalStreamToken())
            }
        }
    }

    @Test
    fun rejectsEverythingAnEncoderNeverEmits() {
        val call = UUID.fromString(vectors[0].first)
        val viewer = UUID.fromString(vectors[2].first)
        val stream = UUID.fromString(vectors[3].first)
        assertRejected(UUID.fromString("C8D74010-2030-4050-60C0-000000000000")) // magic
        listOf(0, 2, 3).forEach { version -> assertRejected(withVersion(call, version)) }
        assertRejected(withKind(call, 3))
        assertRejected(flipLsb(call, 57)) // paused call offer
        assertRejected(flipLsb(viewer, 57)) // paused viewer request
        assertRejected(flipLsb(call, 19)) // call offer with a token
        assertRejected(flipLsb(call, 0)) // call offer with stream fields
        // R11: every call-offer bit 49..19 and 17..0 still rejects; bit 18 is carried (next test).
        ((0..17) + (19..49)).forEach { bit -> assertRejected(flipLsb(call, bit)) }
        (0..11).forEach { bit -> assertRejected(flipLsb(viewer, bit)) } // viewer reserved bits
        // Every stream-offer value decodes: all 19 kind-specific bits are defined.
        (0..18).forEach { bit -> CompactDiscoveryAnnouncement.decode(flipLsb(stream, bit)) }
    }

    @Test
    fun carriesTheRelayConsentBitOfCallOffers() {
        // Naming review R11 vector: call offer, prefix 010203040506, LE CoC, VP9+AV1, cost 0, bit 18 set.
        val consenting = callOffer.copy(capabilitySummary = callOffer.capabilitySummary or 0x10)
        val uuid = UUID.fromString("C8D64010-2030-4050-60C0-000000040000")
        assertAnnouncement(consenting, CompactDiscoveryAnnouncement.decode(uuid))
        assertEquals(uuid, CompactDiscoveryAnnouncement.encode(consenting))
        // Call readers test only codec bits, so the consenting offer still matches VP9/AV1.
        assertEquals(callOffer.capabilitySummary, CompactDiscoveryAnnouncement.decode(uuid).capabilitySummary and 0x7)
    }

    @Test
    fun carriesTheReservedH264CodecBitInsteadOfRejecting() {
        val withH264 = flipLsb(UUID.fromString(vectors[3].first), 56)
        val decoded = CompactDiscoveryAnnouncement.decode(withH264)
        assertEquals(NodeCapabilities.CODEC_VP9 or (1 shl 2), decoded.capabilitySummary)
        assertEquals(0, decoded.capabilitySummary and NodeCapabilities.CODEC_AV1)
    }

    @Test
    fun unspecifiedGeometryAndFrameRateDecodeAsZero() {
        val unspecified = CompactDiscoveryAnnouncement.encode(
            streamOffer.copy(streamWidth = 1280, streamHeight = 720, streamFps = 60, streamBitrateKbps = 0),
        )
        val decoded = CompactDiscoveryAnnouncement.decode(unspecified)
        assertEquals(0, decoded.streamWidth)
        assertEquals(0, decoded.streamHeight)
        assertEquals(0, decoded.streamFps)
        assertEquals(0, decoded.streamBitrateKbps)
        assertEquals(VideoSettings.RESOLUTIONS.map { it.width to it.height }, CompactDiscoveryAnnouncement.RESOLUTIONS)
        assertEquals(VideoSettings.FPS_OPTIONS, CompactDiscoveryAnnouncement.FPS)
    }

    @Test
    fun referenceEncoderRefusesWhatItCannotRepresent() {
        assertEncodeRefused(callOffer.copy(flags = callOffer.flags or DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT))
        assertEncodeRefused(
            viewerRequest.copy(
                flags = viewerRequest.flags or DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY,
                recoveryFromNodeIdPrefix = prefixB,
            ),
        )
        assertEncodeRefused(callOffer.copy(routeCost = 16))
        assertEncodeRefused(callOffer.copy(streamToken = 7))
        assertEncodeRefused(viewerRequest.copy(streamToken = 0))
        assertEncodeRefused(viewerRequest.copy(streamToken = 0x1234))
        assertEncodeRefused(viewerRequest.copy(flags = viewerRequest.flags or DiscoveryAnnouncement.FLAG_STREAM_PAUSED))
        assertEncodeRefused(streamOffer.copy(capabilitySummary = 1 shl 3))
        val quantized = CompactDiscoveryAnnouncement.decode(
            CompactDiscoveryAnnouncement.encode(viewerRequest.copy(streamBitrateKbps = 310)),
        )
        assertEquals(300, quantized.streamBitrateKbps)
        val saturated = CompactDiscoveryAnnouncement.decode(
            CompactDiscoveryAnnouncement.encode(streamOffer.copy(streamBitrateKbps = 5_000)),
        )
        assertEquals(3_175, saturated.streamBitrateKbps)
    }

    @Test
    fun maskedFilterMatchesOnlyTheMagic() {
        vectors.forEach { (text, _) -> assertTrue(aospMaskedMatch(UUID.fromString(text))) }
        assertFalse(aospMaskedMatch(BleL2capControlProtocol.SERVICE_UUID))
        assertFalse(aospMaskedMatch(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")))
        assertTrue(aospMaskedMatch(UUID.fromString("c8d6ffff-ffff-ffff-ffff-ffffffffffff")))
    }

    @Test
    fun firstCandidateSkipsTheAppServiceAndShortUuids() {
        val battery = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val first = UUID.fromString(vectors[2].first)
        val second = UUID.fromString(vectors[0].first)
        assertEquals(first, CompactDiscoveryAnnouncement.firstCandidate(listOf(battery, BleL2capControlProtocol.SERVICE_UUID, first, second)))
        assertNull(CompactDiscoveryAnnouncement.firstCandidate(listOf(battery, BleL2capControlProtocol.SERVICE_UUID)))
        assertNull(CompactDiscoveryAnnouncement.firstCandidate(null))
    }

    @Test
    fun featureFollowsTheSharedRuleAndIsOffByDefault() {
        val stored = { _: String -> true }
        // Normal launch: the user's explicit choice, else the default (off until phone test T8); extras are ignored.
        assertFalse(CompactDiscoveryFeature.resolve(false, { true }, { false }))
        assertTrue(CompactDiscoveryFeature.resolve(false, { false }, stored))
        assertEquals(
            ConnectivityFeatureDefaults.IPHONE_DISCOVERY,
            CompactDiscoveryFeature.resolve(false, { true }, { null }),
        )
        // Benchmark launch: only the extra.
        assertFalse(CompactDiscoveryFeature.resolve(true, { false }, stored))
        assertTrue(CompactDiscoveryFeature.resolve(true, { it == CompactDiscoveryFeature.EXTRA }, { false }))
    }

    @Test
    fun scanStatsLineIsUnchangedWithCompactDiscoveryOff() {
        val off = ScanStats("a1")
        off.accepted = 1
        assertTrue(off.line("running").endsWith("rejected=0 | accepted=1"))
        val on = ScanStats("a1", compactDiscovery = true)
        on.compact = 2
        on.compactInvalid = 1
        assertTrue(on.line("running").endsWith("accepted=0 | compact=2 | compactInvalid=1"))
    }

    /** The comparison AOSP ScanFilter.matchesServiceUuid applies (both 64-bit halves masked). */
    private fun aospMaskedMatch(uuid: UUID): Boolean {
        val filter = CompactDiscoveryAnnouncement.FILTER_UUID
        val mask = CompactDiscoveryAnnouncement.FILTER_MASK
        return (filter.mostSignificantBits and mask.mostSignificantBits) ==
            (uuid.mostSignificantBits and mask.mostSignificantBits) &&
            (filter.leastSignificantBits and mask.leastSignificantBits) ==
            (uuid.leastSignificantBits and mask.leastSignificantBits)
    }

    private fun assertAnnouncement(expected: DiscoveryAnnouncement, actual: DiscoveryAnnouncement) {
        assertArrayEquals(expected.nodeIdPrefix, actual.nodeIdPrefix)
        assertEquals(expected.copy(nodeIdPrefix = actual.nodeIdPrefix), actual)
    }

    private fun assertRejected(uuid: UUID) {
        try {
            CompactDiscoveryAnnouncement.decode(uuid)
            fail("Expected $uuid to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun assertEncodeRefused(value: DiscoveryAnnouncement) {
        try {
            CompactDiscoveryAnnouncement.encode(value)
            fail("Expected $value to be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun flipLsb(uuid: UUID, bit: Int) = UUID(uuid.mostSignificantBits, uuid.leastSignificantBits xor (1L shl bit))

    private fun withVersion(uuid: UUID, version: Int) =
        UUID((uuid.mostSignificantBits and (0x3L shl 46).inv()) or (version.toLong() shl 46), uuid.leastSignificantBits)

    private fun withKind(uuid: UUID, kind: Int) =
        UUID((uuid.mostSignificantBits and (0x3L shl 44).inv()) or (kind.toLong() shl 44), uuid.leastSignificantBits)

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
