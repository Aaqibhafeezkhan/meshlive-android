package com.example.bluetoothtest

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class NodeCapabilities(
    val transportMask: Int,
    val encodeCodecMask: Int,
    val decodeCodecMask: Int,
    val maxBitrateKbps: Int,
    val maxChildren: Int,
) {
    init {
        require(transportMask in 0..0xff)
        require(encodeCodecMask in 0..0xff)
        require(decodeCodecMask in 0..0xff)
        require(maxBitrateKbps in 0..0xffff)
        require(maxChildren in 0..0xff)
    }

    companion object {
        const val TRANSPORT_CLASSIC = 1
        const val TRANSPORT_BLE_L2CAP = 1 shl 1
        const val TRANSPORT_BLE_GATT = 1 shl 2
        const val CODEC_VP9 = 1
        const val CODEC_AV1 = 1 shl 1

        /**
         * Decode-mask bit 7, end-to-end call hellos only: "decode VP9 when you can encode it; the
         * other codecs listed are decodable fallbacks" (privateCallEndpointDecodeMask). It is not a
         * codec, so codec tests (mask and codec bit) ignore it; link hellos never carry it.
         */
        const val DECODE_PREFERS_VP9 = 1 shl 7
    }
}

object NodeCapabilitiesCodec {
    const val ENCODED_BYTES = 7
    private const val VERSION = 1

    fun encode(value: NodeCapabilities): ByteArray = ByteBuffer
        .allocate(ENCODED_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(VERSION.toByte())
        .put(value.transportMask.toByte())
        .put(value.encodeCodecMask.toByte())
        .put(value.decodeCodecMask.toByte())
        .putShort(value.maxBitrateKbps.toShort())
        .put(value.maxChildren.toByte())
        .array()

    fun decode(bytes: ByteArray): NodeCapabilities {
        require(bytes.size == ENCODED_BYTES) { "Invalid capability message length" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(data.get().toInt() and 0xff == VERSION) { "Unsupported capability version" }
        return NodeCapabilities(
            transportMask = data.get().toInt() and 0xff,
            encodeCodecMask = data.get().toInt() and 0xff,
            decodeCodecMask = data.get().toInt() and 0xff,
            maxBitrateKbps = data.short.toInt() and 0xffff,
            maxChildren = data.get().toInt() and 0xff,
        )
    }
}

data class DiscoveryAnnouncement(
    val nodeIdPrefix: ByteArray,
    val flags: Int,
    val capabilitySummary: Int,
    val streamToken: Int,
    val routeCost: Int,
    val sequence: Int,
    val streamWidth: Int = 0,
    val streamHeight: Int = 0,
    val streamFps: Int = 0,
    val streamBitrateKbps: Int = 0,
    val recoveryFromNodeIdPrefix: ByteArray? = null,
    /**
     * 2 = [nodeIdPrefix] is the fixed node prefix; 5 = experimental rotating discovery ID (RDI) in
     * the same 6 bytes (RotatingDiscovery). Compact UUID v2 maps to 5.
     */
    val version: Int = FIXED_PREFIX_VERSION,
) {
    /** True when the 6 prefix bytes are a rotating ID that only an opening can bind to an identity. */
    val rotating: Boolean get() = version == ROTATING_VERSION || version == WINDOW_VERSION

    /** Visible by name: the connectable window offer on the role 3 ID, `streamToken` = the name hash (PROTOCOL). */
    val isWindowOffer: Boolean get() = version == WINDOW_VERSION

    init {
        require(version == FIXED_PREFIX_VERSION || version == ROTATING_VERSION || version == WINDOW_VERSION) {
            "Unsupported announcement version"
        }
        require(nodeIdPrefix.size == NODE_PREFIX_BYTES)
        require(flags in 0..0xff)
        require(capabilitySummary in 0..0xff)
        require(routeCost in 0..0xff)
        require(sequence in 0..0xff)
        require(streamWidth in 0..0xffff)
        require(streamHeight in 0..0xffff)
        require(streamFps in 0..0xff)
        require(streamBitrateKbps in 0..0xffff)
        require(recoveryFromNodeIdPrefix == null || recoveryFromNodeIdPrefix.size == NODE_PREFIX_BYTES)
        require(
            (flags and FLAG_ROUTE_RECOVERY != 0) == (recoveryFromNodeIdPrefix != null),
        ) { "Route-recovery discovery metadata and flag must agree" }
    }

    companion object {
        const val FIXED_PREFIX_VERSION = 2
        const val ROTATING_VERSION = 5

        /** Visible by name (Naming v2): the window offer. Layout identical to version 5; rejected by every 0.2.0-beta decoder. */
        const val WINDOW_VERSION = 8
        const val NODE_PREFIX_BYTES = 6
        const val FLAG_ACCEPTING_CONNECTIONS = 1
        const val FLAG_RELAY_CAPABLE = 1 shl 1
        const val FLAG_STREAM_AVAILABLE = 1 shl 2
        const val FLAG_GATT_TRANSPORT = 1 shl 3
        const val FLAG_CLASSIC_TRANSPORT = 1 shl 4
        const val FLAG_STREAM_PAUSED = 1 shl 5
        const val FLAG_PRIVATE_CALL_AVAILABLE = 1 shl 6
        const val FLAG_ROUTE_RECOVERY = 1 shl 7

        /** Stream offers: top bit of [sequence] = no free viewer slot; low bits = capacity generation. */
        const val SEQUENCE_SOURCE_FULL = 0x80
        const val SEQUENCE_GENERATION_MASK = 0x7f

        /**
         * Experimental audio-only live streams (PROTOCOL "Experimental audio-only live streams"): the whole
         * `capabilitySummary` of a stream offer is exactly this value (bit 6, no codec bits). Older readers
         * find no decoder overlap and list the row as unsupported.
         */
        const val SUMMARY_AUDIO_ONLY = 0x40
    }
}

object DiscoveryAnnouncementCodec {
    const val ENCODED_BYTES = 22

    /**
     * Version 2 always; version 5 (rotating discovery ID, same layout) is produced only by an
     * announcement built with it, and decoded only when [decode] is asked to accept it.
     */
    fun encode(value: DiscoveryAnnouncement): ByteArray {
        val data = ByteBuffer
            .allocate(ENCODED_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(value.version.toByte())
            .put(value.nodeIdPrefix)
            .put(value.flags.toByte())
            .put(value.capabilitySummary.toByte())
            .putInt(value.streamToken)
            .put(value.routeCost.toByte())
        val recoveryPrefix = value.recoveryFromNodeIdPrefix
        if (recoveryPrefix == null) {
            data.put(value.sequence.toByte())
                .putShort(value.streamWidth.toShort())
                .putShort(value.streamHeight.toShort())
                .put(value.streamFps.toByte())
        } else {
            // Viewer requests do not carry video dimensions. Reuse those six bytes so route
            // recovery does not enlarge the legacy 31-byte BLE advertisement/scan response.
            data.put(recoveryPrefix)
        }
        return data.putShort(value.streamBitrateKbps.toShort()).array()
    }

    /**
     * [acceptRotating] false (feature off): version 5 is rejected exactly as by old builds. [acceptWindow] false (Visible by
     * name off, the default): the window offer, version 8, is rejected the same way.
     */
    fun decode(bytes: ByteArray, acceptRotating: Boolean = false, acceptWindow: Boolean = false): DiscoveryAnnouncement {
        require(bytes.size == ENCODED_BYTES) { "Invalid discovery announcement length" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = data.get().toInt() and 0xff
        require(
            version == DiscoveryAnnouncement.FIXED_PREFIX_VERSION ||
                (acceptRotating && version == DiscoveryAnnouncement.ROTATING_VERSION) ||
                (acceptRotating && acceptWindow && version == DiscoveryAnnouncement.WINDOW_VERSION),
        ) { "Unsupported announcement version" }
        val prefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES).also(data::get)
        val flags = data.get().toInt() and 0xff
        val capabilitySummary = data.get().toInt() and 0xff
        val streamToken = data.int
        val routeCost = data.get().toInt() and 0xff
        val compactFields = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES).also(data::get)
        val streamBitrateKbps = data.short.toInt() and 0xffff
        val recoveryPrefix = compactFields.takeIf {
            flags and DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY != 0
        }
        val ordinaryFields = ByteBuffer.wrap(compactFields).order(ByteOrder.BIG_ENDIAN)
        return DiscoveryAnnouncement(
            nodeIdPrefix = prefix,
            flags = flags,
            capabilitySummary = capabilitySummary,
            streamToken = streamToken,
            routeCost = routeCost,
            sequence = if (recoveryPrefix == null) ordinaryFields.get().toInt() and 0xff else 0,
            streamWidth = if (recoveryPrefix == null) ordinaryFields.short.toInt() and 0xffff else 0,
            streamHeight = if (recoveryPrefix == null) ordinaryFields.short.toInt() and 0xffff else 0,
            streamFps = if (recoveryPrefix == null) ordinaryFields.get().toInt() and 0xff else 0,
            streamBitrateKbps = streamBitrateKbps,
            recoveryFromNodeIdPrefix = recoveryPrefix,
            version = version,
        )
    }
}
