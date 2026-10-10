package com.example.bluetoothtest

import java.security.SecureRandom

fun DiscoveryAnnouncement.isStreamOffer(): Boolean =
    flags and DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE != 0

/** A source offer advertising that a new viewer would be rejected for capacity right now. */
fun DiscoveryAnnouncement.isFullStreamOffer(): Boolean =
    isStreamOffer() && flags and DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY == 0 &&
        sequence and DiscoveryAnnouncement.SEQUENCE_SOURCE_FULL != 0

fun DiscoveryAnnouncement.capacityGeneration(): Int =
    sequence and DiscoveryAnnouncement.SEQUENCE_GENERATION_MASK

fun DiscoveryAnnouncement.isPrivateCallOffer(): Boolean =
    flags and DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE != 0 && !isStreamOffer()

fun DiscoveryAnnouncement.isViewerRequest(streamToken: Int): Boolean =
    flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0 &&
        this.streamToken == streamToken

internal const val STREAM_OFFER_STALE_MS = 12_000L

internal fun streamOfferIsFresh(lastSeenMs: Long, nowMs: Long): Boolean =
    nowMs - lastSeenMs <= STREAM_OFFER_STALE_MS

fun DiscoveryAnnouncement.usesGattTransport(): Boolean =
    flags and DiscoveryAnnouncement.FLAG_GATT_TRANSPORT != 0

fun DiscoveryAnnouncement.usesClassicTransport(): Boolean =
    flags and DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT != 0

fun DiscoveryAnnouncement.transportOption(): BluetoothTransportOption = when {
    usesClassicTransport() -> BluetoothTransportOption.CLASSIC_RFCOMM
    usesGattTransport() -> BluetoothTransportOption.BLE_GATT
    else -> BluetoothTransportOption.BLE_L2CAP
}

/**
 * Discovery requests still need a token that identifies one immediate advertiser. The logical
 * token recovered here is shared by every route advertising the same broadcast. It is only a
 * compact discovery correlation value; the authenticated node handshake remains the trust check.
 */
object StreamRouteToken {
    fun forAdvertiser(logicalToken: Int, nodeIdPrefix: ByteArray): Int {
        require(logicalToken in 1..Int.MAX_VALUE) { "Logical stream tokens must be positive" }
        require(nodeIdPrefix.size == DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        return logicalToken xor advertiserMask(nodeIdPrefix)
    }

    fun logicalFrom(routeToken: Int, nodeIdPrefix: ByteArray): Int {
        require(nodeIdPrefix.size == DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        return routeToken xor advertiserMask(nodeIdPrefix)
    }

    private fun advertiserMask(nodeIdPrefix: ByteArray): Int {
        var hash = 0x811c9dc5.toInt()
        ROUTE_DOMAIN.forEach { byte ->
            hash = (hash xor (byte.toInt() and 0xff)) * 0x01000193
        }
        nodeIdPrefix.forEach { byte ->
            hash = (hash xor (byte.toInt() and 0xff)) * 0x01000193
        }
        return hash or Int.MIN_VALUE
    }

    private val ROUTE_DOMAIN = "bluetooth-video-route-v1".toByteArray(Charsets.UTF_8)
}

fun DiscoveryAnnouncement.logicalStreamToken(): Int =
    StreamRouteToken.logicalFrom(streamToken, nodeIdPrefix)

internal data class LogicalStreamTarget(
    val token: Int,
    val codecCapability: Int,
) {
    fun matches(announcement: DiscoveryAnnouncement): Boolean =
        announcement.isStreamOffer() &&
            token == announcement.logicalStreamToken() &&
            codecCapability == announcement.capabilitySummary

    companion object {
        fun from(announcement: DiscoveryAnnouncement): LogicalStreamTarget = LogicalStreamTarget(
            token = announcement.logicalStreamToken(),
            codecCapability = announcement.capabilitySummary,
        )
    }
}

internal fun streamRouteRank(
    announcement: DiscoveryAnnouncement,
    preferredTransport: BluetoothTransportOption?,
): Triple<Int, Int, String> = Triple(
    announcement.routeCost,
    if (announcement.transportOption() == preferredTransport) 0 else 1,
    announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) },
)

object StreamTokenGenerator {
    private val random = SecureRandom()

    fun next(): Int {
        var token = random.nextInt() and Int.MAX_VALUE
        while (token == 0) token = random.nextInt() and Int.MAX_VALUE
        return token
    }
}
