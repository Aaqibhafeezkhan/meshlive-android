package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshControlMessagesTest {
    @Test
    fun capabilitiesRoundTrip() {
        val value = NodeCapabilities(
            transportMask = NodeCapabilities.TRANSPORT_CLASSIC or NodeCapabilities.TRANSPORT_BLE_L2CAP,
            encodeCodecMask = NodeCapabilities.CODEC_VP9,
            decodeCodecMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            maxBitrateKbps = 1_100,
            maxChildren = 3,
        )

        assertEquals(value, NodeCapabilitiesCodec.decode(NodeCapabilitiesCodec.encode(value)))
    }

    @Test
    fun compactDiscoveryAnnouncementRoundTrip() {
        val value = DiscoveryAnnouncement(
            nodeIdPrefix = byteArrayOf(1, 2, 3, 4, 5, 6),
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                DiscoveryAnnouncement.FLAG_RELAY_CAPABLE,
            capabilitySummary = 0x0f,
            streamToken = 0x12345678,
            routeCost = 2,
            sequence = 99,
            streamWidth = 720,
            streamHeight = 480,
            streamFps = 24,
            streamBitrateKbps = 700,
        )

        val decoded = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(value))

        assertArrayEquals(value.nodeIdPrefix, decoded.nodeIdPrefix)
        assertEquals(value.flags, decoded.flags)
        assertEquals(value.capabilitySummary, decoded.capabilitySummary)
        assertEquals(value.streamToken, decoded.streamToken)
        assertEquals(value.routeCost, decoded.routeCost)
        assertEquals(value.sequence, decoded.sequence)
        assertEquals(value.streamWidth, decoded.streamWidth)
        assertEquals(value.streamHeight, decoded.streamHeight)
        assertEquals(value.streamFps, decoded.streamFps)
        assertEquals(value.streamBitrateKbps, decoded.streamBitrateKbps)
    }

    @Test
    fun routeRecoveryRequestReusesCompactViewerFieldsWithoutGrowingAdvertisement() {
        val previousUpstream = byteArrayOf(9, 8, 7, 6, 5, 4)
        val value = DiscoveryAnnouncement(
            nodeIdPrefix = byteArrayOf(1, 2, 3, 4, 5, 6),
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                DiscoveryAnnouncement.FLAG_GATT_TRANSPORT or
                DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY,
            capabilitySummary = NodeCapabilities.CODEC_VP9,
            streamToken = 0x12345678,
            routeCost = 0,
            sequence = 0,
            streamBitrateKbps = 300,
            recoveryFromNodeIdPrefix = previousUpstream,
        )

        val encoded = DiscoveryAnnouncementCodec.encode(value)
        val decoded = DiscoveryAnnouncementCodec.decode(encoded)

        assertEquals(DiscoveryAnnouncementCodec.ENCODED_BYTES, encoded.size)
        assertArrayEquals(previousUpstream, decoded.recoveryFromNodeIdPrefix)
        assertEquals(0, decoded.streamWidth)
        assertEquals(0, decoded.streamHeight)
        assertEquals(0, decoded.streamFps)
        assertEquals(300, decoded.streamBitrateKbps)
    }

}
