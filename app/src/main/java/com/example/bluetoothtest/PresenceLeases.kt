package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Rotating discovery IDs, phase 2 building blocks (reviewed design 5.1-5.3, RV3, RV10, RV11; user
 * decision Q4: mutual verified contacts, per-contact switch on by default, 4-week leases). Pure and
 * JVM-tested; **not sent or stored yet** (PLAN "Discovery privacy" phase 2 wires delivery, the sealed
 * store, the per-contact switch and the "seen nearby · unconfirmed" list line).
 *
 * `P` never leaves its owner. Contacts receive weekly lease keys
 * `L_w = HMAC-SHA256(P, "BluetoothVideoMesh/Discovery/Lease/1" || 0x00 || u32 w)`, `w = floor(e / 672)`,
 * for the current and next 3 weeks. Every salt, the owner's own included, is keyed by `L_w(e)`
 * ([RotatingDiscoveryIds.ownEntry]), so the reviewed vectors hold with their `P` read as `L_w`.
 */
object PresenceLeases {
    const val EPOCHS_PER_WEEK = 672L
    const val WEEKS_PER_DELIVERY = 4
    val LEASE_DOMAIN: ByteArray = "BluetoothVideoMesh/Discovery/Lease/1".toByteArray(Charsets.US_ASCII) + 0

    fun weekOf(epoch: Long): Long = Math.floorDiv(epoch, EPOCHS_PER_WEEK)

    fun leaseKey(presenceKey: ByteArray, week: Long): ByteArray {
        require(presenceKey.size == RotatingDiscoveryIds.PRESENCE_KEY_BYTES)
        require(week in 0..0xffffffffL)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(presenceKey, "HmacSHA256"))
            update(LEASE_DOMAIN)
            update(RotatingDiscoveryIds.u32(week))
            doFinal()
        }
    }

    /** The leases a contact receives at [epoch]: the current week and the next three (RV11). */
    fun delivery(presenceKey: ByteArray, generation: Long, epoch: Long): DiscoveryExchange.PresenceLease {
        val first = weekOf(epoch)
        return DiscoveryExchange.PresenceLease(
            generation = generation,
            senderEpoch = epoch,
            firstWeek = first,
            leases = List(WEEKS_PER_DELIVERY) { leaseKey(presenceKey, first + it) },
        )
    }
}

/**
 * Endpoint container `BDX1` (BME1 content type 11, calls only, design 5.2). Defined here with its
 * vectors; phase 1 does not send it (endpoint hellos never carry bit 3 yet).
 *
 * ```
 * "BDX1" (4) | kind (1) | body
 *   1 OPENING           count (1, 0..2) | count x entry (43)
 *   2 RECOGNITION_OFFER reserved (1) = 0
 *   3 PRESENCE_LEASE    generation (4, >= 1) | sender epoch (4) | first week (4) | count (1, 1..4) | count x L_w (32)
 * ```
 */
object DiscoveryExchange {
    val MAGIC: ByteArray = "BDX1".toByteArray(Charsets.US_ASCII)
    const val CONTENT_TYPE = 11
    const val KIND_OPENING = 1
    const val KIND_RECOGNITION_OFFER = 2
    const val KIND_PRESENCE_LEASE = 3
    const val LEASE_BYTES = 32
    const val MAX_LEASES = 4

    sealed class Record
    data class Opening(val entries: List<OpeningEntry>) : Record()
    object RecognitionOffer : Record()
    class PresenceLease(val generation: Long, val senderEpoch: Long, val firstWeek: Long, leases: List<ByteArray>) : Record() {
        val leases: List<ByteArray> = leases.map(ByteArray::copyOf)

        init {
            require(generation in 1..0xffffffffL && senderEpoch in 0..0xffffffffL && firstWeek in 0..0xffffffffL)
            require(leases.size in 1..MAX_LEASES && leases.all { it.size == LEASE_BYTES })
        }
    }

    fun encode(record: Record): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        when (record) {
            is Opening -> {
                out.write(KIND_OPENING)
                // The same count + entries body as BDO1 after its version byte.
                val body = DiscoveryOpeningCodec.encode(record.entries)
                out.write(body, DiscoveryOpeningCodec.MAGIC.size + 1, body.size - DiscoveryOpeningCodec.MAGIC.size - 1)
            }
            RecognitionOffer -> {
                out.write(KIND_RECOGNITION_OFFER)
                out.write(0)
            }
            is PresenceLease -> {
                out.write(KIND_PRESENCE_LEASE)
                out.write(RotatingDiscoveryIds.u32(record.generation))
                out.write(RotatingDiscoveryIds.u32(record.senderEpoch))
                out.write(RotatingDiscoveryIds.u32(record.firstWeek))
                out.write(record.leases.size)
                record.leases.forEach(out::write)
            }
        }
        return out.toByteArray()
    }

    /** Strict decode: any other kind or length throws (the receiver logs and drops it, never failing a call). */
    fun decode(bytes: ByteArray): Record {
        require(bytes.size >= MAGIC.size + 2 && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "Not a BDX1 record" }
        val kind = bytes[4].toInt() and 0xff
        return when (kind) {
            KIND_OPENING -> {
                val asBdo1 = DiscoveryOpeningCodec.MAGIC + byteArrayOf(DiscoveryOpeningCodec.VERSION.toByte()) +
                    bytes.copyOfRange(MAGIC.size + 1, bytes.size)
                Opening(DiscoveryOpeningCodec.decode(asBdo1))
            }
            KIND_RECOGNITION_OFFER -> {
                require(bytes.size == MAGIC.size + 2 && bytes[5].toInt() == 0) { "Invalid recognition offer" }
                RecognitionOffer
            }
            KIND_PRESENCE_LEASE -> {
                require(bytes.size >= MAGIC.size + 1 + 13) { "Presence lease too short" }
                val data = ByteBuffer.wrap(bytes, MAGIC.size + 1, bytes.size - MAGIC.size - 1).order(ByteOrder.BIG_ENDIAN)
                val generation = data.int.toLong() and 0xffffffffL
                val senderEpoch = data.int.toLong() and 0xffffffffL
                val firstWeek = data.int.toLong() and 0xffffffffL
                val count = data.get().toInt() and 0xff
                require(generation >= 1) { "Presence lease generation must be at least 1" }
                require(count in 1..MAX_LEASES) { "Invalid presence lease count" }
                require(data.remaining() == count * LEASE_BYTES) { "Invalid presence lease length" }
                PresenceLease(generation, senderEpoch, firstWeek, List(count) { ByteArray(LEASE_BYTES).also(data::get) })
            }
            else -> throw IllegalArgumentException("Unknown BDX1 kind $kind")
        }
    }
}

/** What this phone stores for one contact's presence (design 5.2, RV11): sealed at rest when wired. */
class ContactPresence(
    val nodeId: ByteArray,
    val generation: Long,
    leasesByWeek: Map<Long, ByteArray>,
    /** `sender epoch - my epoch` at receipt, clamped to +-30 days. */
    val skewEpochs: Long,
    val receivedAtMs: Long,
) {
    val leasesByWeek: Map<Long, ByteArray> = leasesByWeek.mapValues { it.value.copyOf() }

    init {
        require(nodeId.size == NodeId.BYTE_COUNT)
    }

    companion object {
        const val MAX_SKEW_EPOCHS = 30L * 96L

        /**
         * Receiver rule: a higher generation replaces the stored one; the same generation merges the
         * delivered weeks (leases refresh at every session); a lower one is ignored (returns [stored]).
         * Weeks that ended before the first delivered week are dropped.
         */
        fun receive(
            stored: ContactPresence?,
            nodeId: ByteArray,
            lease: DiscoveryExchange.PresenceLease,
            myEpoch: Long,
            nowMs: Long,
        ): ContactPresence {
            if (stored != null && lease.generation < stored.generation) return stored
            val delivered = lease.leases.mapIndexed { index, key -> lease.firstWeek + index to key }.toMap()
            val merged = if (stored != null && stored.generation == lease.generation) {
                stored.leasesByWeek.filterKeys { it >= lease.firstWeek } + delivered
            } else {
                delivered
            }
            val skew = (lease.senderEpoch - myEpoch).coerceIn(-MAX_SKEW_EPOCHS, MAX_SKEW_EPOCHS)
            return ContactPresence(nodeId, lease.generation, merged, skew, nowMs)
        }
    }
}

/**
 * Recognition table (design 5.3 with RV3/RV10): for every contact, roles 0 and 1, and every epoch in
 * `myEpoch + skew + [-1, +1]` (`[-8, +8]` while the skew is older than 14 days) that a lease covers,
 * `RDI -> (contact, epoch, role)`. Role 2 is intentionally not recognised. A hit is only a hint:
 * the UI shows "seen nearby · unconfirmed" without a check mark, and a hit never starts a connection
 * (no BMH3 yet). Rebuilt in full at each tick (200 contacts x 2 roles x 3 epochs = 1,200 entries).
 */
class RecognitionTable {
    data class Hit(val contact: NodeId, val epoch: Long, val role: Int)

    private var table: Map<String, List<Hit>> = emptyMap()

    val size: Int get() = table.values.sumOf { it.size }

    fun rebuild(contacts: Collection<ContactPresence>, myEpoch: Long, nowMs: Long) {
        val next = HashMap<String, MutableList<Hit>>()
        contacts.forEach { contact ->
            val stale = nowMs - contact.receivedAtMs > STALE_SKEW_MS
            val radius = if (stale) WIDE_RADIUS else NARROW_RADIUS
            val centre = myEpoch + contact.skewEpochs
            for (epoch in (centre - radius)..(centre + radius)) {
                if (epoch < 0) continue
                val lease = contact.leasesByWeek[PresenceLeases.weekOf(epoch)] ?: continue
                for (role in RECOGNISED_ROLES) {
                    val salt = RotatingDiscoveryIds.salt(lease, epoch, role)
                    val rdi = RotatingDiscoveryIds.rdi(contact.nodeId, epoch, role, salt)
                    next.getOrPut(rdi.toKey()) { mutableListOf() } += Hit(NodeId.fromBytes(contact.nodeId), epoch, role)
                }
            }
        }
        table = next
    }

    /** Every contact whose ID equals [sixBytes] (two contacts may collide, about 2^-48 per pair). */
    fun lookup(sixBytes: ByteArray): List<Hit> = table[sixBytes.toKey()].orEmpty()

    private fun ByteArray.toKey(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val NARROW_RADIUS = 1L
        const val WIDE_RADIUS = 8L
        const val STALE_SKEW_MS = 14L * 24 * 3_600_000L
        val RECOGNISED_ROLES = listOf(RotatingDiscoveryIds.ROLE_CALL_OFFER, RotatingDiscoveryIds.ROLE_STREAM_OFFER)
    }
}
