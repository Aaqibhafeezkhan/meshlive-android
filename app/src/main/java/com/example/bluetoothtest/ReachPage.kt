package com.example.bluetoothtest

import java.nio.ByteBuffer

/** One target a relay-helper can reach: [cost] relays between the connecting phone and the target, the helper included. */
internal class ReachPageEntry(
    val target: ByteArray,
    val cost: Int,
    /** Age in 2-second units (0..31, so at most 62 s). */
    val ageUnits: Int,
    /** First two bytes of the next-hop prefix (the target itself for cost 1): poison reverse. */
    val via: ByteArray,
) {
    init {
        require(target.size == DiscoveryAnnouncement.NODE_PREFIX_BYTES) { "Reach target prefix" }
        require(cost in 1..ReachPageCodec.MAX_COST) { "Reach cost" }
        require(ageUnits in 0..ReachPageCodec.MAX_AGE_UNITS) { "Reach age" }
        require(via.size == ReachPageCodec.VIA_BYTES) { "Reach via" }
    }

    val ageMs: Long get() = ageUnits * ReachPageCodec.AGE_UNIT_MS

    override fun equals(other: Any?): Boolean = other is ReachPageEntry && cost == other.cost &&
        ageUnits == other.ageUnits && target.contentEquals(other.target) && via.contentEquals(other.via)

    override fun hashCode(): Int = (target.contentHashCode() * 31 + cost) * 31 + ageUnits
}

internal class ReachPage(
    val relayPrefix: ByteArray,
    /** Bit 0 LE CoC, bit 1 GATT, bit 3 idle ([ReachPageCodec.FLAG_IDLE]). */
    val relayFlags: Int,
    val index: Int,
    val count: Int,
    val entries: List<ReachPageEntry>,
    /** Version 6 (rotating IDs): relay, targets and via are role-0 RDIs of the current epoch, not fixed prefixes. */
    val rotating: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is ReachPage && relayFlags == other.relayFlags &&
        index == other.index && count == other.count && relayPrefix.contentEquals(other.relayPrefix) &&
        entries == other.entries && rotating == other.rotating

    override fun hashCode(): Int = (relayPrefix.contentHashCode() * 31 + index) * 31 + entries.hashCode()
}

/**
 * Reach pages (PROTOCOL "Experimental relayed calls and multi-hop discovery"): the manufacturer
 * `0xFFFF` scan response of a second, legacy, non-connectable set.
 *
 * `version 4 | relay prefix (6) | relay flags | page (index << 4 | count) | entries (1..2) x
 * (target (6) | cost << 5 | age units | via (2))`, 18 or 27 bytes. Old scanners reject it (not 22 bytes).
 */
internal object ReachPageCodec {
    const val VERSION = 4

    /** Rotating IDs: the version 4 layout with role-0 RDIs (PROTOCOL "Rotating IDs and relayed calls"). */
    const val VERSION_RDI = 6
    const val HEADER_BYTES = 9
    const val ENTRY_BYTES = 9
    const val MAX_ENTRIES_PER_PAGE = 2
    const val MAX_PAGES = 4
    const val MAX_COST = 4
    const val MAX_AGE_UNITS = 31
    const val AGE_UNIT_MS = 2_000L
    const val VIA_BYTES = 2

    const val FLAG_COC = 1
    const val FLAG_GATT = 1 shl 1
    const val FLAG_IDLE = 1 shl 3
    private const val KNOWN_FLAGS = FLAG_COC or FLAG_GATT or FLAG_IDLE

    fun isReachPage(bytes: ByteArray?): Boolean =
        bytes != null && bytes.isNotEmpty() && (bytes[0].toInt() == VERSION || bytes[0].toInt() == VERSION_RDI)

    /** Age in ms to the nearest not-smaller 2-second unit, capped at the field maximum; null when past it. */
    fun ageUnitsFor(ageMs: Long): Int? {
        if (ageMs < 0) return 0
        val units = (ageMs + AGE_UNIT_MS - 1) / AGE_UNIT_MS
        return if (units > MAX_AGE_UNITS) null else units.toInt()
    }

    fun flagsFor(transport: BluetoothTransportOption, idle: Boolean): Int = when (transport) {
        BluetoothTransportOption.BLE_L2CAP -> FLAG_COC
        BluetoothTransportOption.BLE_GATT -> FLAG_GATT
        BluetoothTransportOption.CLASSIC_RFCOMM -> error("Classic relays are not advertised in reach pages")
    } or (if (idle) FLAG_IDLE else 0)

    fun encode(page: ReachPage): ByteArray {
        require(page.relayPrefix.size == DiscoveryAnnouncement.NODE_PREFIX_BYTES) { "Relay prefix" }
        require(page.relayFlags and KNOWN_FLAGS.inv() == 0) { "Reserved relay flags" }
        require(page.count in 1..MAX_PAGES && page.index in 0 until page.count) { "Reach page index" }
        require(page.entries.size in 1..MAX_ENTRIES_PER_PAGE) { "Reach entries per page" }
        require(page.entries.none { it.target.contentEquals(page.relayPrefix) }) { "Reach target is the relay" }
        require(page.entries.map { it.target.prefixHex() }.toSet().size == page.entries.size) { "Duplicate reach target" }
        val buffer = ByteBuffer.allocate(HEADER_BYTES + ENTRY_BYTES * page.entries.size)
            .put((if (page.rotating) VERSION_RDI else VERSION).toByte())
            .put(page.relayPrefix)
            .put(page.relayFlags.toByte())
            .put(((page.index shl 4) or page.count).toByte())
        page.entries.forEach { entry ->
            buffer.put(entry.target).put(((entry.cost shl 5) or entry.ageUnits).toByte()).put(entry.via)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): ReachPage {
        require(bytes.size == HEADER_BYTES + ENTRY_BYTES || bytes.size == HEADER_BYTES + 2 * ENTRY_BYTES) { "Reach page length" }
        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.get().toInt() and 0xff
        require(version == VERSION || version == VERSION_RDI) { "Reach page version" }
        val relay = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES).also(buffer::get)
        val flags = buffer.get().toInt() and 0xff
        require(flags and KNOWN_FLAGS.inv() == 0) { "Reserved relay flags" }
        val page = buffer.get().toInt() and 0xff
        val index = page ushr 4
        val count = page and 0x0f
        require(count in 1..MAX_PAGES && index < count) { "Reach page index" }
        val entries = ArrayList<ReachPageEntry>(2)
        while (buffer.hasRemaining()) {
            val target = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES).also(buffer::get)
            val costAge = buffer.get().toInt() and 0xff
            val via = ByteArray(VIA_BYTES).also(buffer::get)
            val cost = costAge ushr 5
            require(cost in 1..MAX_COST) { "Reach cost" }
            require(!target.contentEquals(relay)) { "Reach target is the relay" }
            entries += ReachPageEntry(target, cost, costAge and 0x1f, via)
        }
        require(entries.map { it.target.prefixHex() }.toSet().size == entries.size) { "Duplicate reach target" }
        return ReachPage(relay, flags, index, count, entries, rotating = version == VERSION_RDI)
    }

    /**
     * Splits [entries] (best first) into pages of at most two, at most [maxPages] pages: the pages a relay
     * rotates through, each held for several advertising events.
     */
    fun pages(
        relayPrefix: ByteArray,
        relayFlags: Int,
        entries: List<ReachPageEntry>,
        maxPages: Int = 2,
        rotating: Boolean = false,
    ): List<ByteArray> {
        val chunks = entries.take(maxPages * MAX_ENTRIES_PER_PAGE).chunked(MAX_ENTRIES_PER_PAGE)
        return chunks.mapIndexed { index, chunk ->
            encode(ReachPage(relayPrefix, relayFlags, index, chunks.size, chunk, rotating))
        }
    }
}
