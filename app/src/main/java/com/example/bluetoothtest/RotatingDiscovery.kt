package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Experimental rotating discovery IDs (off by default; DEBUG `test_rotating_ids`, Advanced setting
 * "Experimental: rotating discovery IDs" otherwise). PROTOCOL "Experimental rotating discovery IDs"
 * owns the wire rules; this file holds the pure, JVM-tested parts (reviewed design RV1-RV19).
 *
 * Phase 1 (strangers only): call-ready presence (role 0) advertises announcement version 5 whose
 * 6 prefix bytes are a 15-minute rotating ID (RDI) instead of the fixed node prefix. A caller that
 * selected a version 5 offer requires the link opening `BDO1` (hop type 13, link hello bit 3) and
 * checks it against the authenticated node ID (RV15). Streams, viewer requests, forwarders and
 * relay chains keep version 2 in this phase (PLAN "Discovery privacy"). Off: every byte on air and
 * on the link is unchanged, and version 5 adverts are rejected exactly as by old builds.
 */
object RotatingDiscoveryFeature {
    const val EXTRA = "test_rotating_ids"
    const val PREF = "experimental_rotating_ids"

    @Volatile var enabled = false

    fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        advancedSettings: Boolean,
        stored: (String) -> Boolean,
    ): Boolean = when {
        benchmarkLaunch -> extra(EXTRA)
        advancedSettings -> stored(PREF)
        else -> false
    }
}

/** The reviewed RDI construction (design section 3). All integers big-endian. */
object RotatingDiscoveryIds {
    const val EPOCH_SECONDS = 900L
    const val ID_BYTES = DiscoveryAnnouncement.NODE_PREFIX_BYTES
    const val SALT_BYTES = 32
    const val PRESENCE_KEY_BYTES = 32

    const val ROLE_CALL_OFFER = 0
    const val ROLE_STREAM_OFFER = 1
    const val ROLE_VIEWER_REQUEST = 2

    /** Link hello transport-mask bit 3: "sends and accepts `BDO1` as the first hop record". */
    const val LINK_HELLO_BIT = 1 shl 3

    val SALT_DOMAIN: ByteArray = "BluetoothVideoMesh/Discovery/Salt/1".toByteArray(Charsets.US_ASCII) + 0
    val ID_DOMAIN: ByteArray = "BluetoothVideoMesh/Discovery/Id/1".toByteArray(Charsets.US_ASCII) + 0

    fun wallEpoch(unixSeconds: Long): Long = Math.floorDiv(unixSeconds, EPOCH_SECONDS)

    fun salt(presenceKey: ByteArray, epoch: Long, role: Int): ByteArray {
        require(presenceKey.size == PRESENCE_KEY_BYTES) { "Presence key must be 32 bytes" }
        require(epoch in 0..0xffffffffL && role in 0..0xff)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(presenceKey, "HmacSHA256"))
            update(SALT_DOMAIN)
            update(u32(epoch))
            update(role.toByte())
            doFinal()
        }
    }

    fun rdi(nodeId: ByteArray, epoch: Long, role: Int, salt: ByteArray): ByteArray {
        require(nodeId.size == NodeId.BYTE_COUNT) { "Node ID must be 16 bytes" }
        require(salt.size == SALT_BYTES) { "Salt must be 32 bytes" }
        require(epoch in 0..0xffffffffL && role in 0..0xff)
        return MessageDigest.getInstance("SHA-256").run {
            update(ID_DOMAIN)
            update(nodeId)
            update(u32(epoch))
            update(role.toByte())
            update(salt)
            digest()
        }.copyOf(ID_BYTES)
    }

    /** The opening entry for ([epoch], [role]) with [saltKey] as the salt key (a lease key, or a vector's P). */
    fun entry(saltKey: ByteArray, nodeId: ByteArray, epoch: Long, role: Int): OpeningEntry {
        val salt = salt(saltKey, epoch, role)
        return OpeningEntry(rdi(nodeId, epoch, role, salt), epoch, role, salt)
    }

    /**
     * The entry this phone publishes: salts are keyed by the week's lease `L_w(e)` derived from the
     * presence key (RV11), so contacts holding leases compute the same IDs (phase 2).
     */
    fun ownEntry(presenceKey: ByteArray, nodeId: ByteArray, epoch: Long, role: Int): OpeningEntry =
        entry(PresenceLeases.leaseKey(presenceKey, PresenceLeases.weekOf(epoch)), nodeId, epoch, role)

    internal fun u32(value: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        .putInt(value.toInt()).array()
}

/** `RDI (6) | epoch (4) | role (1) | salt (32)`: one opening entry (design 5.2/6). */
class OpeningEntry(rdi: ByteArray, val epoch: Long, val role: Int, salt: ByteArray) {
    private val rdiBytes = rdi.copyOf()
    private val saltBytes = salt.copyOf()

    init {
        require(rdi.size == RotatingDiscoveryIds.ID_BYTES)
        require(salt.size == RotatingDiscoveryIds.SALT_BYTES)
        require(epoch in 0..0xffffffffL && role in 0..0xff)
    }

    val rdi: ByteArray get() = rdiBytes.copyOf()
    val salt: ByteArray get() = saltBytes.copyOf()

    /** True when this entry opens [selected] for [nodeId] (recomputed, never trusted). */
    fun opens(selected: ByteArray, nodeId: ByteArray, expectedRole: Int): Boolean =
        role == expectedRole && rdiBytes.contentEquals(selected) &&
            RotatingDiscoveryIds.rdi(nodeId, epoch, role, saltBytes).contentEquals(selected)

    override fun equals(other: Any?): Boolean = other is OpeningEntry && epoch == other.epoch &&
        role == other.role && rdiBytes.contentEquals(other.rdiBytes) && saltBytes.contentEquals(other.saltBytes)

    override fun hashCode(): Int = (rdiBytes.contentHashCode() * 31 + epoch.hashCode()) * 31 + role

    companion object {
        const val BYTES = 43
    }
}

/** Link opening `"BDO1" | version 1 | count (0..2) | count x entry (43)` (design section 6). */
object DiscoveryOpeningCodec {
    val MAGIC: ByteArray = "BDO1".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val MAX_ENTRIES = 2

    fun encode(entries: List<OpeningEntry>): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "At most two opening entries" }
        val out = ByteArrayOutputStream(MAGIC.size + 2 + entries.size * OpeningEntry.BYTES)
        out.write(MAGIC)
        out.write(VERSION)
        out.write(entries.size)
        entries.forEach { entry ->
            out.write(entry.rdi)
            out.write(RotatingDiscoveryIds.u32(entry.epoch))
            out.write(entry.role)
            out.write(entry.salt)
        }
        return out.toByteArray()
    }

    /** Strict decode: any other magic, version, count or length throws. */
    fun decode(bytes: ByteArray): List<OpeningEntry> {
        require(bytes.size >= MAGIC.size + 2) { "Opening too short" }
        require(bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "Not a BDO1 opening" }
        require(bytes[4].toInt() and 0xff == VERSION) { "Unsupported opening version" }
        val count = bytes[5].toInt() and 0xff
        require(count <= MAX_ENTRIES) { "Too many opening entries" }
        require(bytes.size == MAGIC.size + 2 + count * OpeningEntry.BYTES) { "Invalid opening length" }
        val data = ByteBuffer.wrap(bytes, MAGIC.size + 2, count * OpeningEntry.BYTES).order(ByteOrder.BIG_ENDIAN)
        return List(count) {
            val rdi = ByteArray(RotatingDiscoveryIds.ID_BYTES).also(data::get)
            val epoch = data.int.toLong() and 0xffffffffL
            val role = data.get().toInt() and 0xff
            val salt = ByteArray(RotatingDiscoveryIds.SALT_BYTES).also(data::get)
            OpeningEntry(rdi, epoch, role, salt)
        }
    }
}

/**
 * RV15: the check follows the advert the caller selected, never the peer's hello. A selected
 * version 5 (or compact v2) advert requires link bit 3 and a matching opening; a selected version
 * 2 advert uses the prefix check, whatever the hello says.
 */
object DiscoverySelectionCheck {
    enum class Result { PASS, PREFIX_MISMATCH, OPENING_MISSING, OPENING_MISMATCH }

    fun check(
        selected: DiscoveryAnnouncement,
        expectedRole: Int,
        peerNodeId: ByteArray,
        openingEntries: List<OpeningEntry>?,
    ): Result = if (!selected.rotating) {
        if (peerNodeId.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).contentEquals(selected.nodeIdPrefix)) {
            Result.PASS
        } else {
            Result.PREFIX_MISMATCH
        }
    } else {
        when {
            openingEntries == null -> Result.OPENING_MISSING
            openingEntries.any { it.opens(selected.nodeIdPrefix, peerNodeId, expectedRole) } -> Result.PASS
            else -> Result.OPENING_MISMATCH
        }
    }
}

/**
 * RV4 monotonic epoch. A tick happens only when the wall epoch differs from the one seen at the
 * last tick, or [ELAPSED_TICK_MS] of elapsedRealtime passed since it (a stuck or rewound wall
 * clock still rotates). At a tick E = max(wallEpoch, lastEpoch + 1); any other call reuses E, so
 * restarts after calls, retries and defers never push E ahead. After a reboot (elapsed time not
 * comparable) E is reused only while the wall still shows the epoch of the last tick. A forward
 * offset E - wallEpoch above [MAX_AHEAD_EPOCHS] that lasts [AHEAD_RECOVERY_MS] of consistent wall
 * time starts a new presence generation and resets E to the wall epoch.
 */
data class EpochClockState(
    val lastEpoch: Long = -1L,
    val tickWallEpoch: Long = -1L,
    val tickElapsedMs: Long = 0L,
    val bootId: String = "",
    val generation: Long = 1L,
)

class DiscoveryEpochClock(
    initial: EpochClockState,
    private val bootId: String,
    private val persist: (EpochClockState) -> Unit,
) {
    var state: EpochClockState = initial
        private set
    private var aheadSinceElapsedMs: Long? = null
    private var aheadWallOffsetMs = 0L

    /** Epoch to advertise now. Returns true in [Reading.newGeneration] once per recovery. */
    data class Reading(val epoch: Long, val ticked: Boolean, val newGeneration: Boolean)

    @Synchronized
    fun read(wallMs: Long, elapsedMs: Long): Reading {
        val wall = RotatingDiscoveryIds.wallEpoch(Math.floorDiv(wallMs, 1000L))
        var current = state
        var newGeneration = false
        if (current.lastEpoch >= 0 && current.lastEpoch - wall > MAX_AHEAD_EPOCHS) {
            val offset = wallMs - elapsedMs
            val since = aheadSinceElapsedMs
            if (since == null || Math.abs(offset - aheadWallOffsetMs) > CONSISTENT_WALL_TOLERANCE_MS) {
                aheadSinceElapsedMs = elapsedMs
                aheadWallOffsetMs = offset
            } else if (elapsedMs - since >= AHEAD_RECOVERY_MS) {
                current = EpochClockState(wall, wall, elapsedMs, bootId, current.generation + 1)
                state = current
                persist(current)
                aheadSinceElapsedMs = null
                newGeneration = true
                return Reading(wall, ticked = true, newGeneration = true)
            }
        } else {
            aheadSinceElapsedMs = null
        }
        val tick = when {
            current.lastEpoch < 0 -> true
            current.bootId != bootId -> wall != current.tickWallEpoch
            else -> wall != current.tickWallEpoch || elapsedMs - current.tickElapsedMs >= ELAPSED_TICK_MS
        }
        if (!tick) {
            if (current.bootId != bootId) {
                // Same wall epoch after a reboot: keep E, adopt this boot's elapsed base.
                current = current.copy(tickElapsedMs = elapsedMs, bootId = bootId)
                state = current
                persist(current)
            }
            return Reading(current.lastEpoch, ticked = false, newGeneration = newGeneration)
        }
        val epoch = maxOf(wall, current.lastEpoch + 1)
        current = current.copy(lastEpoch = epoch, tickWallEpoch = wall, tickElapsedMs = elapsedMs, bootId = bootId)
        state = current
        persist(current)
        return Reading(epoch, ticked = true, newGeneration = newGeneration)
    }

    companion object {
        /** 900 s plus a minute, so a normal wall boundary always ticks first (no early double tick). */
        const val ELAPSED_TICK_MS = (RotatingDiscoveryIds.EPOCH_SECONDS + 60L) * 1000L
        const val MAX_AHEAD_EPOCHS = 96L
        const val AHEAD_RECOVERY_MS = 3_600_000L
        const val CONSISTENT_WALL_TOLERANCE_MS = 120_000L
    }
}

/**
 * Section 6 pin rule: a phone opens only an ID of the role that is on air, or that left the air less
 * than [PREVIOUS_GRACE_MS] earlier (the connection was accepted under it, or the boundary just
 * passed). An ID that went off air long ago (a long call, a Visible-by-handle window, an absence)
 * is never opened again, and an arbitrary or older epoch is never opened on request.
 */
class OpeningPins {
    private var current: OpeningEntry? = null
    private var currentOffAirMs: Long? = null
    private var previous: OpeningEntry? = null
    private var previousOffAirMs = Long.MIN_VALUE / 2

    @Synchronized
    fun onAdvertised(entry: OpeningEntry, elapsedMs: Long) {
        if (current == entry) {
            currentOffAirMs = null
            return
        }
        current?.let { old ->
            previous = old
            previousOffAirMs = currentOffAirMs ?: elapsedMs
        }
        current = entry
        currentOffAirMs = null
    }

    /** The role's advertising stopped (connection accepted, boundary silence, window, close). */
    @Synchronized
    fun offAir(elapsedMs: Long) {
        if (current != null && currentOffAirMs == null) currentOffAirMs = elapsedMs
    }

    @Synchronized
    fun pinned(elapsedMs: Long): List<OpeningEntry> = listOfNotNull(
        current?.takeIf { currentOffAirMs.let { off -> off == null || elapsedMs - off < PREVIOUS_GRACE_MS } },
        previous?.takeIf { elapsedMs - previousOffAirMs < PREVIOUS_GRACE_MS },
    )

    companion object {
        const val PREVIOUS_GRACE_MS = 60_000L
    }
}

/**
 * "Visible by handle" window (design decisions, design 8.1, RV5): call-ready presence advertises
 * version 2 (the fixed prefix, visible to old builds and to anyone who knows the handle) from the
 * start until the first epoch boundary at least [MIN_SECONDS] later, so a window lasts 10-25 min. It
 * starts and ends on a new advertising set. Every window is linkable to every other window (same
 * prefix) and to the part of the epoch just before it.
 */
object VisibleByHandleWindow {
    const val MIN_SECONDS = 600L

    /** Upper bound by elapsed time (10 min + one epoch), whatever the wall clock does. */
    const val MAX_MS = (MIN_SECONDS + RotatingDiscoveryIds.EPOCH_SECONDS) * 1000L

    /** The epoch whose start ends a window that began at [startUnixSeconds]. */
    fun endEpoch(startUnixSeconds: Long): Long =
        Math.floorDiv(startUnixSeconds + MIN_SECONDS + RotatingDiscoveryIds.EPOCH_SECONDS - 1, RotatingDiscoveryIds.EPOCH_SECONDS)

    fun isActive(endEpoch: Long, nowUnixSeconds: Long): Boolean =
        endEpoch >= 0 && RotatingDiscoveryIds.wallEpoch(nowUnixSeconds) < endEpoch
}

/**
 * RV8 tarpit (replaces the lockout): the responder delays its hello by 2^(n-6) s, capped at 30 s,
 * for the n-th stranger handshake in the epoch that has not led to a user-visible action. Verified
 * contacts are never delayed. It bounds mass probing and battery, not privacy.
 */
class StrangerTarpit {
    private var epoch = -1L
    private var probes = 0

    /** Delay before this stranger's hello; counts it as a probe until [forgive] is called. */
    @Synchronized
    fun nextDelayMs(currentEpoch: Long): Long {
        if (currentEpoch != epoch) {
            epoch = currentEpoch
            probes = 0
        }
        probes++
        return delayMs(probes)
    }

    /** The handshake led to a user-visible action (an incoming call rang): it was not a probe. */
    @Synchronized
    fun forgive(currentEpoch: Long) {
        if (currentEpoch == epoch && probes > 0) probes--
    }

    companion object {
        const val CAP_MS = 30_000L

        fun delayMs(n: Int): Long {
            if (n <= 0) return 0L
            val exponent = n - 6
            if (exponent >= 15) return CAP_MS
            val ms = if (exponent >= 0) 1000L shl exponent else 1000L shr -exponent
            return minOf(ms, CAP_MS)
        }
    }
}

/**
 * Section 4.3 recovery hint with RV18 key separation: `Khint = HKDF-SHA256(Kmedia, salt = 32 zero
 * bytes, info = domain)`, hint = HMAC-SHA256(Khint, domain || 0 || failedNodeId || u32 token)[0:6].
 * Defined for Phase 1b (viewer requests); not sent by this phase.
 */
object RecoveryHint {
    val DOMAIN: ByteArray = "BluetoothVideoMesh/Discovery/Recovery/1".toByteArray(Charsets.US_ASCII)

    fun hintKey(mediaKey: ByteArray): ByteArray = hkdfSha256(mediaKey, ByteArray(32), DOMAIN, 32)

    fun hint(key: ByteArray, failedNodeId: ByteArray, logicalToken: Int): ByteArray {
        require(failedNodeId.size == NodeId.BYTE_COUNT)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(DOMAIN + 0)
            update(failedNodeId)
            update(RotatingDiscoveryIds.u32(logicalToken.toLong() and 0xffffffffL))
            doFinal()
        }.copyOf(RotatingDiscoveryIds.ID_BYTES)
    }

    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }
        val out = ByteArrayOutputStream(length)
        var previous = byteArrayOf()
        var counter = 1
        while (out.size() < length) {
            previous = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(prk, "HmacSHA256"))
                update(previous)
                update(info)
                update(counter.toByte())
                doFinal()
            }
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}

/**
 * RV1: while the experiment is on, the adapter name (served as the GAP Device Name to any central
 * that connects, before any app handshake) is set to [NEUTRAL]; the user's own name is restored
 * when the experiment is turned off. Pure decisions; the Android side applies them.
 */
object NeutralBluetoothName {
    /** The same for every phone running the experiment, so it identifies nobody. */
    const val NEUTRAL = "Phone"

    data class Action(val setName: String?, val saveOriginal: String?, val clearSaved: Boolean)

    fun onEnabled(currentName: String?, savedOriginal: String?): Action = when {
        currentName == null -> Action(null, null, false)
        currentName == NEUTRAL -> Action(null, null, false)
        // The user renamed the phone while the experiment was on: remember the newer name.
        else -> Action(NEUTRAL, currentName, false)
    }

    fun onDisabled(currentName: String?, savedOriginal: String?): Action = when {
        savedOriginal == null -> Action(null, null, false)
        // Restore only our own neutral name; a name the user chose since then is kept.
        currentName == NEUTRAL -> Action(savedOriginal, null, true)
        else -> Action(null, null, true)
    }
}
