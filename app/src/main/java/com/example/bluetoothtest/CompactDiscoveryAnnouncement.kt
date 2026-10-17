package com.example.bluetoothtest

import java.util.UUID

/**
 * iPhone compact discovery, shown to users as "Find iPhones" (promoted to Settings > Features on
 * 2026-10-11; default [ConnectivityFeatureDefaults.IPHONE_DISCOVERY] until phone test T8 has logged how
 * each phone's masked scan filter behaves). DEBUG `test_ios_compact_discovery` is the benchmark switch.
 * iOS apps cannot send manufacturer data, and a foreground iPhone
 * exposes only one 128-bit service UUID to Android scanners, so an iPhone advertises
 * [CompactDiscoveryAnnouncement] instead of the 22-byte manufacturer-data announcement. On: every
 * [BleL2capDiscovery] adds one masked scan filter and maps a matching UUID into the ordinary
 * [DiscoveryAnnouncement]. Off: scanning is exactly as before. Android never advertises the
 * compact form, and no handshake, record or admission rule changes (PROTOCOL.md).
 */
object CompactDiscoveryFeature {
    const val EXTRA = "test_ios_compact_discovery"
    const val PREF = "experimental_ios_compact_discovery_v2" // new key 2026-10-17: no stored 0.2.0 "off" applies

    @Volatile var enabled = false

    /**
     * Set once a scanner reports that the masked filter cannot be used ([CompactFilterGuard]); every later
     * scanner of this process leaves the filter out. Cleared when the feature is switched on again.
     */
    @Volatile var filterRejected = false

    /** Whether a scan started now carries the masked iPhone filter. */
    fun filterActive(): Boolean = enabled && !filterRejected

    /** The shared feature rule ([FeatureCatalog.resolve]). */
    fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): Boolean = FeatureCatalog.resolve(ConnectivityFeatures.iphoneDiscovery, benchmarkLaunch, extra, stored)
}

/**
 * Which scan failures mean the masked filter itself is the problem. A scan start carries the app's own
 * filter and the masked iPhone filter together, so a phone whose scanner cannot take the second filter
 * (no free hardware filter slot, or no masked-UUID filters at all) would otherwise lose Android discovery
 * too. On these codes the scanner drops only the masked filter, restarts, and logs
 * `compact_filter: event=dropped`; AOSP ScanCallback codes 4 (feature unsupported) and 5 (out of hardware
 * resources). Transient codes (internal error, registration failure, scanning too frequently) keep the
 * ordinary back-off and keep the filter.
 */
internal object CompactFilterGuard {
    fun refusesFilter(errorCode: Int): Boolean =
        errorCode == ScanFailurePolicy.FEATURE_UNSUPPORTED || errorCode == ScanFailurePolicy.OUT_OF_HARDWARE_RESOURCES
}

/**
 * The compact discovery announcement: one 128-bit service UUID, read as a big-endian integer `U`
 * in canonical string order (Java most- then least-significant bits).
 *
 * ```
 * 127..112 magic 0xC8D6   111..110 version 1   109..108 kind (0 call, 1 viewer, 2 stream; 3 rejected)
 * 107..60  node ID prefix (6 bytes)
 * 59 GATT   58 relay capable   57 stream paused (stream offers only)
 * 56..54   codec mask (bit 54 VP9, 55 AV1, 56 H.264 reserved; carried, never rejected)
 * 53..50   route cost 0..15
 * 49..19   route token bits 30..0 (call: 0; viewer/stream: token = 0x80000000 or field)
 * 18..0    kind-specific: call 0; viewer bitrate units in 18..12, 11..0 zero;
 *          stream sequence 18..11, resolution index 10..9, FPS index 8..7, bitrate units 6..0
 * ```
 *
 * Every field is the same unauthenticated hint the 22-byte announcement carries; the BMH2 link
 * and endpoint handshakes still bind the connection to the advertised node prefix.
 */
object CompactDiscoveryAnnouncement {
    const val MAGIC = 0xC8D6
    const val VERSION = 1

    /** Experimental rotating discovery IDs: bits 107..60 carry the role's RDI (maps to announcement version 5). */
    const val ROTATING_VERSION = 2
    const val KIND_PRIVATE_CALL_OFFER = 0

    /**
     * Naming review R11 (compact v1 amendment, before v1 ships): call-offer bit 18 = "may be relayed",
     * the same consent as call-offer `capabilitySummary` bit 4 (relayed discovery, phase 2). Decoders
     * carry it and never reject it; every other call-offer bit 49..19 and 17..0 still rejects.
     */
    const val CALL_RELAY_CONSENT_BIT = 18
    const val CALL_RELAY_CONSENT_SUMMARY = 0x10
    const val KIND_VIEWER_REQUEST = 1
    const val KIND_STREAM_OFFER = 2
    const val BITRATE_UNIT_KBPS = 25
    private const val MAX_BITRATE_UNITS = 0x7f
    private const val MAX_ROUTE_COST = 0xf
    private const val CODEC_FIELD_MASK = 0x7

    /** Index 3 of both tables means "unspecified" (width, height and FPS 0). */
    val RESOLUTIONS: List<Pair<Int, Int>> = listOf(426 to 240, 640 to 360, 854 to 480)
    val FPS: List<Int> = listOf(15, 24, 30)

    /** Scan filter: top 16 bits equal [MAGIC]; every other bit is matched by [decode]. */
    val FILTER_UUID: UUID = UUID(MAGIC.toLong() shl 48, 0L)
    val FILTER_MASK: UUID = UUID(0xffffL shl 48, 0L)

    private const val PREFIX_HIGH_BITS = 44
    private const val PREFIX_HIGH_MASK = (1L shl PREFIX_HIGH_BITS) - 1
    private const val TOKEN_FIELD_MASK = 0x7fffffffL
    private const val SPECIFIC_MASK = 0x7ffffL
    private const val VIEWER_RESERVED_MASK = 0xfff

    fun hasMagic(uuid: UUID): Boolean = (uuid.mostSignificantBits ushr 48).toInt() == MAGIC

    /** The first advertised service UUID carrying the magic, or null. */
    fun firstCandidate(serviceUuids: List<UUID>?): UUID? = serviceUuids?.firstOrNull(::hasMagic)

    /** Strict decode; throws [IllegalArgumentException] for anything an encoder never emits. */
    fun decode(uuid: UUID, acceptRotating: Boolean = false): DiscoveryAnnouncement {
        require(hasMagic(uuid)) { "Not a compact discovery announcement" }
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val compactVersion = ((msb ushr 46) and 0x3L).toInt()
        require(compactVersion == VERSION || (acceptRotating && compactVersion == ROTATING_VERSION)) {
            "Unsupported compact announcement version"
        }
        val announcementVersion = if (compactVersion == ROTATING_VERSION) {
            DiscoveryAnnouncement.ROTATING_VERSION
        } else {
            DiscoveryAnnouncement.FIXED_PREFIX_VERSION
        }
        val kind = ((msb ushr 44) and 0x3L).toInt()
        require(kind != 3) { "Reserved compact announcement kind" }
        val prefixBits = ((msb and PREFIX_HIGH_MASK) shl 4) or (lsb ushr 60)
        val prefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { index ->
            (prefixBits ushr (8 * (DiscoveryAnnouncement.NODE_PREFIX_BYTES - 1 - index))).toByte()
        }
        val paused = (lsb ushr 57) and 1L != 0L
        require(!paused || kind == KIND_STREAM_OFFER) { "Only stream offers can be paused" }
        val transportFlags = (if ((lsb ushr 59) and 1L != 0L) DiscoveryAnnouncement.FLAG_GATT_TRANSPORT else 0) or
            (if ((lsb ushr 58) and 1L != 0L) DiscoveryAnnouncement.FLAG_RELAY_CAPABLE else 0) or
            (if (paused) DiscoveryAnnouncement.FLAG_STREAM_PAUSED else 0)
        val codecs = ((lsb ushr 54) and CODEC_FIELD_MASK.toLong()).toInt()
        val routeCost = ((lsb ushr 50) and MAX_ROUTE_COST.toLong()).toInt()
        val tokenField = ((lsb ushr 19) and TOKEN_FIELD_MASK).toInt()
        val specific = (lsb and SPECIFIC_MASK).toInt()
        return when (kind) {
            KIND_PRIVATE_CALL_OFFER -> {
                require(tokenField == 0 && specific and (1 shl CALL_RELAY_CONSENT_BIT).inv() == 0) {
                    "Private-call offers carry no token or stream fields"
                }
                val consent = if (specific != 0) CALL_RELAY_CONSENT_SUMMARY else 0
                DiscoveryAnnouncement(
                    nodeIdPrefix = prefix,
                    flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                        DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or transportFlags,
                    capabilitySummary = codecs or consent,
                    streamToken = 0,
                    routeCost = routeCost,
                    sequence = 0,
                    version = announcementVersion,
                )
            }
            KIND_VIEWER_REQUEST -> {
                require(specific and VIEWER_RESERVED_MASK == 0) { "Reserved viewer-request bits are set" }
                DiscoveryAnnouncement(
                    nodeIdPrefix = prefix,
                    flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or transportFlags,
                    capabilitySummary = codecs,
                    streamToken = tokenField or Int.MIN_VALUE,
                    routeCost = routeCost,
                    sequence = 0,
                    streamBitrateKbps = (specific ushr 12) * BITRATE_UNIT_KBPS,
                    version = announcementVersion,
                )
            }
            else -> {
                val resolution = RESOLUTIONS.getOrNull((specific ushr 9) and 0x3)
                // Experimental audio-only live (PROTOCOL): codec mask 0 with resolution index 3 and FPS index 3
                // is the audio-only marker; mask 0 with any other geometry decodes as before (summary 0).
                val audioOnly = codecs == 0 && ((specific ushr 9) and 0x3) == 3 && ((specific ushr 7) and 0x3) == 3
                DiscoveryAnnouncement(
                    nodeIdPrefix = prefix,
                    flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or transportFlags,
                    capabilitySummary = if (audioOnly) DiscoveryAnnouncement.SUMMARY_AUDIO_ONLY else codecs,
                    streamToken = tokenField or Int.MIN_VALUE,
                    routeCost = routeCost,
                    sequence = (specific ushr 11) and 0xff,
                    streamWidth = resolution?.first ?: 0,
                    streamHeight = resolution?.second ?: 0,
                    streamFps = FPS.getOrNull((specific ushr 7) and 0x3) ?: 0,
                    streamBitrateKbps = (specific and MAX_BITRATE_UNITS) * BITRATE_UNIT_KBPS,
                    version = announcementVersion,
                )
            }
        }
    }

    /**
     * Reference encoder for the shared Swift/Kotlin vectors. Android never advertises the compact
     * form. Refuses what cannot be represented (Classic, route recovery, route cost above 15, a
     * call offer with a token, a viewer/stream token without bit 31); quantizes bitrates down to
     * 25 kbps (saturating at 3175) and maps geometry/FPS outside the tables to index 3.
     */
    internal fun encode(value: DiscoveryAnnouncement): UUID {
        val flags = value.flags
        require(flags and (DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT or DiscoveryAnnouncement.FLAG_ROUTE_RECOVERY) == 0) {
            "Classic and route recovery are not representable"
        }
        val callOffer = flags and DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE == 0 &&
            flags and DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE != 0
        val audioOnlyOffer = flags and DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE != 0 &&
            value.capabilitySummary == DiscoveryAnnouncement.SUMMARY_AUDIO_ONLY
        val representable = CODEC_FIELD_MASK or if (callOffer) CALL_RELAY_CONSENT_SUMMARY else 0
        require(audioOnlyOffer || value.capabilitySummary and representable.inv() == 0) {
            "Only codec bits are representable"
        }
        require(!audioOnlyOffer || (value.streamWidth == 0 && value.streamHeight == 0 && value.streamFps == 0)) {
            "An audio-only offer has no video geometry"
        }
        require(value.routeCost in 0..MAX_ROUTE_COST) { "Route cost above 15 is not representable" }
        val kind = when {
            flags and DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE != 0 -> KIND_STREAM_OFFER
            flags and DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE != 0 -> KIND_PRIVATE_CALL_OFFER
            flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0 -> KIND_VIEWER_REQUEST
            else -> throw IllegalArgumentException("Not a call offer, viewer request or stream offer")
        }
        val kindFlags = kindFlags(kind)
        require(
            flags and kindFlags == kindFlags &&
                flags and (kindFlags or HINT_FLAGS).inv() == 0,
        ) { "Flags do not map to one compact kind" }
        val paused = flags and DiscoveryAnnouncement.FLAG_STREAM_PAUSED != 0
        require(!paused || kind == KIND_STREAM_OFFER) { "Only stream offers can be paused" }
        if (kind == KIND_PRIVATE_CALL_OFFER) {
            require(value.streamToken == 0) { "Private-call offers carry token 0" }
        } else {
            require(value.streamToken < 0) { "Route tokens always have bit 31 set" }
        }
        val specific = when (kind) {
            KIND_PRIVATE_CALL_OFFER ->
                if (value.capabilitySummary and CALL_RELAY_CONSENT_SUMMARY != 0) 1 shl CALL_RELAY_CONSENT_BIT else 0
            KIND_VIEWER_REQUEST -> bitrateUnits(value.streamBitrateKbps) shl 12
            else -> {
                val resolutionIndex = RESOLUTIONS.indexOf(value.streamWidth to value.streamHeight)
                    .takeIf { it >= 0 } ?: 3
                val fpsIndex = FPS.indexOf(value.streamFps).takeIf { it >= 0 } ?: 3
                (value.sequence shl 11) or (resolutionIndex shl 9) or (fpsIndex shl 7) or
                    bitrateUnits(value.streamBitrateKbps)
            }
        }
        val prefixBits = value.nodeIdPrefix.fold(0L) { bits, byte -> (bits shl 8) or (byte.toLong() and 0xffL) }
        val compactVersion = if (value.rotating) ROTATING_VERSION else VERSION
        val msb = (MAGIC.toLong() shl 48) or (compactVersion.toLong() shl 46) or (kind.toLong() shl 44) or
            (prefixBits ushr 4)
        val lsb = ((prefixBits and 0xfL) shl 60) or
            (if (flags and DiscoveryAnnouncement.FLAG_GATT_TRANSPORT != 0) 1L shl 59 else 0L) or
            (if (flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE != 0) 1L shl 58 else 0L) or
            (if (paused) 1L shl 57 else 0L) or
            ((if (audioOnlyOffer) 0 else value.capabilitySummary and CODEC_FIELD_MASK).toLong() shl 54) or
            (value.routeCost.toLong() shl 50) or
            ((value.streamToken.toLong() and TOKEN_FIELD_MASK) shl 19) or
            specific.toLong()
        return UUID(msb, lsb)
    }

    private fun bitrateUnits(kbps: Int): Int = (kbps / BITRATE_UNIT_KBPS).coerceIn(0, MAX_BITRATE_UNITS)

    private fun kindFlags(kind: Int): Int = when (kind) {
        KIND_PRIVATE_CALL_OFFER ->
            DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE
        KIND_VIEWER_REQUEST -> DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS
        else -> DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE
    }

    private const val HINT_FLAGS = DiscoveryAnnouncement.FLAG_GATT_TRANSPORT or
        DiscoveryAnnouncement.FLAG_RELAY_CAPABLE or DiscoveryAnnouncement.FLAG_STREAM_PAUSED
}
