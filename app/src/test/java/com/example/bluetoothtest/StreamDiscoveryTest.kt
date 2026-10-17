package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDiscoveryTest {
    @Test
    fun distinguishesStreamOffersFromMatchingViewerRequests() {
        val offer = announcement(DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE, 42)
        val request = announcement(DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS, 42)

        assertTrue(offer.isStreamOffer())
        assertFalse(offer.isViewerRequest(42))
        assertTrue(request.isViewerRequest(42))
        assertFalse(request.isViewerRequest(41))
    }

    @Test
    fun pausedBroadcastRemainsDiscoverable() {
        val paused = announcement(
            DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or
                DiscoveryAnnouncement.FLAG_STREAM_PAUSED,
            42,
        )

        assertTrue(paused.isStreamOffer())
        assertTrue(paused.flags and DiscoveryAnnouncement.FLAG_STREAM_PAUSED != 0)
    }

    @Test
    fun generatedTokensAreNonZero() {
        repeat(100) {
            val token = StreamTokenGenerator.next()
            assertTrue(token > 0)
            assertNotEquals(0, token)
        }
    }

    @Test
    fun routeTokensAreHopSpecificButRecoverOneLogicalStream() {
        val logicalToken = 0x12345678
        val sourcePrefix = byteArrayOf(1, 2, 3, 4, 5, 6)
        val relayPrefix = byteArrayOf(6, 5, 4, 3, 2, 1)
        val sourceRoute = StreamRouteToken.forAdvertiser(logicalToken, sourcePrefix)
        val relayRoute = StreamRouteToken.forAdvertiser(logicalToken, relayPrefix)

        assertNotEquals(0, sourceRoute)
        assertNotEquals(0, relayRoute)
        assertNotEquals(sourceRoute, relayRoute)
        assertEquals(logicalToken, StreamRouteToken.logicalFrom(sourceRoute, sourcePrefix))
        assertEquals(logicalToken, StreamRouteToken.logicalFrom(relayRoute, relayPrefix))
    }

    @Test
    fun logicalTargetMatchesAlternateRoutesButNotAnotherStreamOrCodec() {
        val logicalToken = 42
        val sourcePrefix = byteArrayOf(1, 1, 1, 1, 1, 1)
        val relayPrefix = byteArrayOf(2, 2, 2, 2, 2, 2)
        val source = announcement(
            DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
            StreamRouteToken.forAdvertiser(logicalToken, sourcePrefix),
        ).copy(
            nodeIdPrefix = sourcePrefix,
            capabilitySummary = NodeCapabilities.CODEC_VP9,
        )
        val relay = source.copy(
            nodeIdPrefix = relayPrefix,
            streamToken = StreamRouteToken.forAdvertiser(logicalToken, relayPrefix),
            routeCost = 1,
        )
        val target = LogicalStreamTarget.from(source)

        assertTrue(target.matches(source))
        assertTrue(target.matches(relay))
        assertFalse(target.matches(relay.copy(streamToken = relay.streamToken + 1)))
        assertFalse(target.matches(relay.copy(capabilitySummary = NodeCapabilities.CODEC_AV1)))
    }

    @Test
    fun discoveryAnnouncementSelectsItsVideoTransport() {
        assertEquals(
            BluetoothTransportOption.BLE_L2CAP,
            announcement(DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE, 42).transportOption(),
        )
        assertEquals(
            BluetoothTransportOption.BLE_GATT,
            announcement(
                DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
                42,
            ).transportOption(),
        )
        assertEquals(
            BluetoothTransportOption.CLASSIC_RFCOMM,
            announcement(
                DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT,
                42,
            ).transportOption(),
        )
    }

    @Test
    fun reconnectRequiresTheSameSourceSessionAndTransport() {
        val original = announcement(
            DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or
                DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT,
            42,
        )
        val target = StreamReconnectTarget(
            sourcePrefix = "000000000000",
            streamToken = 42,
            transport = BluetoothTransportOption.CLASSIC_RFCOMM,
        )

        assertTrue(target.matches(original))
        assertFalse(target.matches(original.copy(streamToken = 43)))
        assertFalse(
            target.matches(
                original.copy(flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE),
            ),
        )
        assertFalse(target.matches(original.copy(nodeIdPrefix = ByteArray(6) { 1 })))
    }

    @Test
    fun streamOfferSurvivesBriefOemScanGapsThenExpires() {
        val lastSeenMs = 1_000L

        assertTrue(streamOfferIsFresh(lastSeenMs, lastSeenMs + 6_000L))
        assertTrue(streamOfferIsFresh(lastSeenMs, lastSeenMs + STREAM_OFFER_STALE_MS))
        assertFalse(streamOfferIsFresh(lastSeenMs, lastSeenMs + STREAM_OFFER_STALE_MS + 1L))
    }

    @Test
    fun relayedCopiesOfOneBroadcastShareOneRowLedByTheBestJoinableRoute() {
        fun offer(logical: Int, prefix: Byte, cost: Int, full: Boolean = false): DiscoveryAnnouncement {
            val nodePrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { prefix }
            return announcement(DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE, StreamRouteToken.forAdvertiser(logical, nodePrefix))
                .copy(nodeIdPrefix = nodePrefix, routeCost = cost,
                    sequence = if (full) DiscoveryAnnouncement.SEQUENCE_SOURCE_FULL else 0)
        }
        val relayFar = offer(7, 3, cost = 2)
        val source = offer(7, 1, cost = 0)
        val relayNear = offer(7, 2, cost = 1)
        val other = offer(9, 4, cost = 0)

        val groups = groupStreamRoutes(listOf(relayFar, other, source, relayNear), { it }, { !it.isFullStreamOffer() })
        assertEquals(2, groups.size)
        assertEquals(listOf(source, relayNear, relayFar), groups[0].routes)
        assertEquals(other, groups[1].best)

        // A full source yields to a relay of the same broadcast; it stays in the group.
        val fullSource = offer(7, 1, cost = 0, full = true)
        val fallback = groupStreamRoutes(listOf(fullSource, relayFar, relayNear), { it }, { !it.isFullStreamOffer() })
        assertEquals(relayNear, fallback.single().best)
        assertEquals(fullSource, fallback.single().routes.last())

        // Routes whose establishment keeps failing rank after healthy ones at any cost.
        val avoided = groupStreamRoutes(listOf(source, relayFar), { it }, { true }, { if (it === source) 1 else 0 })
        assertEquals(relayFar, avoided.single().best)
    }

    private fun announcement(flags: Int, token: Int) = DiscoveryAnnouncement(
        nodeIdPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
        flags = flags,
        capabilitySummary = 0,
        streamToken = token,
        routeCost = 0,
        sequence = 0,
    )
}
