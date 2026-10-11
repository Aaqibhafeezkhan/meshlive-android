package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Rotating discovery IDs, phase 2 (reviewed design 5.1-5.3, RV3, RV10, RV11; design decision Q4: mutual
 * verified contacts, per-contact switch on by default, 4-week leases; RD5 of the 2026-10-11 review: leases
 * are per **day**, so a lease holder cannot resolve IDs logged before the lease was delivered by more than
 * the current day). Pure and JVM-tested; delivery is in [PresenceSession], storage in [PresenceStore].
 *
 * Hardening (PROTOCOL "Keys and epochs"): a day key `L_d` is an **independent random** 32-byte key, the daily
 * Temporary Exposure Key model of GAEN. There is no master key and no derivation between days, so a phone read
 * at day `d` yields only its window `[dayOf(E - 1), dayOf(E) + 27]`: no earlier day (deleted) and no later day
 * (not drawn yet). Every salt, the owner's own included, is keyed by `L_d(e)` ([RotatingDiscoveryIds.ownEntry]).
 * The HMAC derivation of earlier builds (`L_d = HMAC(P, "…/Lease/2" || 0x00 || u32 d)`) survives only as
 * [legacyLeaseKey], used once to migrate a stored `P` and by the migration vectors.
 */
object PresenceLeases {
    /** Epochs per day at the default 900 s epoch; [dayOf] uses the real-time form so a DEBUG short epoch keeps real days. */
    const val EPOCHS_PER_DAY = 96L
    const val DAYS_PER_DELIVERY = 28
    val LEASE_DOMAIN: ByteArray = "BluetoothVideoMesh/Discovery/Lease/2".toByteArray(Charsets.US_ASCII) + 0

    /**
     * The day of an epoch: its start time's day (`epoch * epochSeconds / 86400`, equal to `epoch / 96` at 900 s). Presence-lease
     * days stay real days when a DEBUG launch shortens the epoch (`test_rotid_epoch_s`): day keys, the 28-day window and the
     * day-roll behave as in a normal launch, only the advert ID changes more often.
     */
    fun dayOf(epoch: Long): Long = Math.floorDiv(epoch * RotatingDiscoveryIds.epochSeconds, 86_400L)

    /**
     * MIGRATION ONLY: the earlier derivation from a long-lived presence key `P`. Never called for a phone that
     * has a version 2 store; it keeps leases already delivered to contacts valid across the update.
     */
    fun legacyLeaseKey(presenceKey: ByteArray, day: Long): ByteArray {
        require(presenceKey.size == RotatingDiscoveryIds.PRESENCE_KEY_BYTES)
        require(day in 0..0xffffffffL)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(presenceKey, "HmacSHA256"))
            update(LEASE_DOMAIN)
            update(RotatingDiscoveryIds.u32(day))
            doFinal()
        }
    }

    /** The leases a contact receives at [epoch]: today and the next 27 days from [dayKeys] (RV11, RD5). Null if a day is missing. */
    fun delivery(dayKeys: Map<Long, ByteArray>, generation: Long, epoch: Long): DiscoveryExchange.PresenceLease? {
        val first = dayOf(epoch)
        val keys = List(DAYS_PER_DELIVERY) { dayKeys[first + it] ?: return null }
        return DiscoveryExchange.PresenceLease(generation = generation, senderEpoch = epoch, firstDay = first, leases = keys)
    }
}

/**
 * The owner's day-key window (PROTOCOL "Keys and epochs"). For the monotonic epoch `E` it is
 * `[dayOf(E - 1), dayOf(E) + [DAYS_AHEAD]]`: today and 27 more days (what a lease delivers) plus yesterday while
 * `E` is the first epoch of a day. Keys are created once, when their day enters the window, from a CSPRNG, and
 * deleted when it leaves; an existing key is never replaced. Pure.
 */
object DayKeyWindow {
    const val DAYS_AHEAD = 27

    /** The oldest day kept at [epoch]: the day of the previous epoch. */
    fun firstDay(epoch: Long): Long = PresenceLeases.dayOf((epoch - 1).coerceAtLeast(0))

    /** The last day of the window at [epoch]. */
    fun lastDay(epoch: Long): Long = PresenceLeases.dayOf(epoch) + DAYS_AHEAD

    fun contains(epochNow: Long, day: Long): Boolean = day in firstDay(epochNow)..lastDay(epochNow)

    /**
     * [keys] moved to [epoch]: days before [firstDay] deleted, every missing day from today to [lastDay] created
     * with [newKey]. Existing keys are kept as they are; a window that already fits is returned as the same map.
     */
    fun roll(keys: Map<Long, ByteArray>, epoch: Long, newKey: () -> ByteArray): Map<Long, ByteArray> {
        val keep = keys.filterKeys { it >= firstDay(epoch) }
        val today = PresenceLeases.dayOf(epoch)
        val missing = (today..lastDay(epoch)).filter { it !in keep }
        if (missing.isEmpty() && keep.size == keys.size) return keys
        val next = java.util.TreeMap<Long, ByteArray>()
        keep.forEach { (day, key) -> next[day] = key }
        missing.forEach { day -> next[day] = newKey() }
        return next
    }

    /** A brand-new window (a new generation): every key fresh. */
    fun fresh(epoch: Long, newKey: () -> ByteArray): Map<Long, ByteArray> = roll(emptyMap(), epoch, newKey)

    /**
     * MIGRATION ONLY: the window of a stored presence key `P`, derived the way earlier builds did, for the whole
     * window including yesterday while [epoch] is the first of a day (the previous epoch's ID was advertised
     * under it, and the poison-reverse check still has to recognise it).
     */
    fun migrated(presenceKey: ByteArray, epoch: Long): Map<Long, ByteArray> =
        (firstDay(epoch)..lastDay(epoch)).associateWith { PresenceLeases.legacyLeaseKey(presenceKey, it) }
            .toSortedMap()
}

/**
 * Endpoint container `BDX1` (BME1 content type 11, calls only, design 5.2).
 *
 * ```
 * "BDX1" (4) | kind (1) | body
 *   1 OPENING           count (1, 0..2) | count x entry (43)
 *   2 RECOGNITION_OFFER reserved (1) = 0
 *   3 PRESENCE_LEASE    generation (4, >= 1) | sender epoch (4) | first day (4) | count (1, 1..28) | count x L_d (32)
 * ```
 */
object DiscoveryExchange {
    val MAGIC: ByteArray = "BDX1".toByteArray(Charsets.US_ASCII)
    const val CONTENT_TYPE = 11
    const val KIND_OPENING = 1
    const val KIND_RECOGNITION_OFFER = 2
    const val KIND_PRESENCE_LEASE = 3

    /** BMH3 pseudonyms (PROTOCOL "Phase 2: pseudonymous stranger identities"): the real identity, sent after an accepted call. */
    const val KIND_IDENTITY_REVEAL = 4
    const val LEASE_BYTES = 32
    const val MAX_LEASES = 28

    sealed class Record
    data class Opening(val entries: List<OpeningEntry>) : Record()

    /** `role (1: caller, 2: callee) | SPKI length (2) = 91 | SPKI (91) | opening body (count + entries) | signature (64)`. */
    class IdentityReveal(val role: Int, spki: ByteArray, val entries: List<OpeningEntry>, signature: ByteArray) : Record() {
        val spki: ByteArray = spki.copyOf()
        val signature: ByteArray = signature.copyOf()

        init {
            require(role == Bmh3Reveal.ROLE_CALLER || role == Bmh3Reveal.ROLE_CALLEE) { "Invalid reveal role" }
            require(spki.size == Bmh3Wire.SPKI_BYTES && signature.size == Bmh3Wire.SIGNATURE_BYTES) { "Invalid reveal lengths" }
            require(entries.size <= DiscoveryOpeningCodec.MAX_ENTRIES) { "At most two opening entries" }
            require(role == Bmh3Reveal.ROLE_CALLEE || entries.isEmpty()) { "Only the callee opens a rotating ID" }
        }
    }
    object RecognitionOffer : Record()
    class PresenceLease(val generation: Long, val senderEpoch: Long, val firstDay: Long, leases: List<ByteArray>) : Record() {
        val leases: List<ByteArray> = leases.map(ByteArray::copyOf)

        init {
            require(generation in 1..0xffffffffL && senderEpoch in 0..0xffffffffL && firstDay in 0..0xffffffffL)
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
            is IdentityReveal -> {
                out.write(KIND_IDENTITY_REVEAL)
                out.write(record.role)
                out.write(Bmh3Wire.SPKI_BYTES ushr 8)
                out.write(Bmh3Wire.SPKI_BYTES)
                out.write(record.spki)
                val body = DiscoveryOpeningCodec.encode(record.entries)
                out.write(body, DiscoveryOpeningCodec.MAGIC.size + 1, body.size - DiscoveryOpeningCodec.MAGIC.size - 1)
                out.write(record.signature)
            }
            is PresenceLease -> {
                out.write(KIND_PRESENCE_LEASE)
                out.write(RotatingDiscoveryIds.u32(record.generation))
                out.write(RotatingDiscoveryIds.u32(record.senderEpoch))
                out.write(RotatingDiscoveryIds.u32(record.firstDay))
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
                val firstDay = data.int.toLong() and 0xffffffffL
                val count = data.get().toInt() and 0xff
                require(generation >= 1) { "Presence lease generation must be at least 1" }
                require(count in 1..MAX_LEASES) { "Invalid presence lease count" }
                require(data.remaining() == count * LEASE_BYTES) { "Invalid presence lease length" }
                PresenceLease(generation, senderEpoch, firstDay, List(count) { ByteArray(LEASE_BYTES).also(data::get) })
            }
            KIND_IDENTITY_REVEAL -> decodeReveal(bytes)
            else -> throw IllegalArgumentException("Unknown BDX1 kind $kind")
        }
    }

    private fun decodeReveal(bytes: ByteArray): IdentityReveal {
        val fixed = MAGIC.size + 1 + 1 + 2 + Bmh3Wire.SPKI_BYTES + 1
        require(bytes.size >= fixed + Bmh3Wire.SIGNATURE_BYTES) { "Reveal too short" }
        val role = bytes[5].toInt() and 0xff
        val spkiLength = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        require(spkiLength == Bmh3Wire.SPKI_BYTES) { "Invalid reveal identity length" }
        val spki = bytes.copyOfRange(8, 8 + Bmh3Wire.SPKI_BYTES)
        val count = bytes[8 + Bmh3Wire.SPKI_BYTES].toInt() and 0xff
        require(count <= DiscoveryOpeningCodec.MAX_ENTRIES) { "Too many opening entries" }
        require(bytes.size == fixed + count * OpeningEntry.BYTES + Bmh3Wire.SIGNATURE_BYTES) { "Invalid reveal length" }
        val asBdo1 = DiscoveryOpeningCodec.MAGIC + byteArrayOf(DiscoveryOpeningCodec.VERSION.toByte()) +
            bytes.copyOfRange(8 + Bmh3Wire.SPKI_BYTES, fixed + count * OpeningEntry.BYTES)
        return IdentityReveal(role, spki, DiscoveryOpeningCodec.decode(asBdo1), bytes.copyOfRange(bytes.size - Bmh3Wire.SIGNATURE_BYTES, bytes.size))
    }
}

/** What this phone stores for one contact's presence (design 5.2, RV11): sealed at rest by [PresenceStore]. */
class ContactPresence(
    val nodeId: ByteArray,
    val generation: Long,
    leasesByDay: Map<Long, ByteArray>,
    /** `sender epoch - my epoch` at receipt, clamped to +-30 days. */
    val skewEpochs: Long,
    val receivedAtMs: Long,
) {
    val leasesByDay: Map<Long, ByteArray> = leasesByDay.mapValues { it.value.copyOf() }

    init {
        require(nodeId.size == NodeId.BYTE_COUNT)
    }

    /**
     * The days a recognition table can still use: `dayOf(myEpoch + skew) - 1` (in the sender's day numbering) and
     * later; earlier days are deleted so a seized lease holder does not keep the keys of days that are over
     * (PROTOCOL "Keys and epochs", RH6). Null when nothing is left. The same instance when nothing was deleted.
     */
    fun pruned(myEpoch: Long): ContactPresence? {
        val keepFrom = PresenceLeases.dayOf(myEpoch + skewEpochs) - 1
        val kept = leasesByDay.filterKeys { it >= keepFrom }
        if (kept.size == leasesByDay.size) return this
        if (kept.isEmpty()) return null
        return ContactPresence(nodeId, generation, kept, skewEpochs, receivedAtMs)
    }

    companion object {
        /** Thirty days of epochs (2,880 at 900 s). */
        val MAX_SKEW_EPOCHS: Long get() = 30L * RotatingDiscoveryIds.epochsPerDay

        /** Days kept per contact: today's delivery plus a few days of overlap with the next one. */
        const val MAX_DAYS_KEPT = 32

        /**
         * Receiver rule: a higher generation replaces the stored one; the same generation merges the
         * delivered days (leases refresh at every session) and drops days before the first delivered one;
         * a lower one is ignored (returns [stored]). Two deliveries of one generation must agree on every
         * day they share: if any differs the sender holds another key (a lost counter, a restored copy), so
         * the stored record is replaced instead of mixed.
         */
        fun receive(
            stored: ContactPresence?,
            nodeId: ByteArray,
            lease: DiscoveryExchange.PresenceLease,
            myEpoch: Long,
            nowMs: Long,
        ): ContactPresence {
            if (stored != null && lease.generation < stored.generation) return stored
            val delivered = lease.leases.mapIndexed { index, key -> lease.firstDay + index to key }.toMap()
            val sameKey = stored != null && stored.generation == lease.generation &&
                delivered.all { (day, key) -> stored.leasesByDay[day]?.contentEquals(key) != false }
            val merged = if (stored != null && sameKey) {
                (stored.leasesByDay.filterKeys { it >= lease.firstDay } + delivered)
                    .toSortedMap().entries.toList().takeLast(MAX_DAYS_KEPT).associate { it.key to it.value }
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

    @Volatile private var table: Map<String, List<Hit>> = emptyMap()

    val size: Int get() = table.values.sumOf { it.size }

    fun rebuild(contacts: Collection<ContactPresence>, myEpoch: Long, nowMs: Long) {
        val next = HashMap<String, MutableList<Hit>>()
        contacts.forEach { contact ->
            val stale = nowMs - contact.receivedAtMs > STALE_SKEW_MS
            val radius = if (stale) WIDE_RADIUS else NARROW_RADIUS
            val centre = myEpoch + contact.skewEpochs
            for (epoch in (centre - radius)..(centre + radius)) {
                if (epoch < 0) continue
                val lease = contact.leasesByDay[PresenceLeases.dayOf(epoch)] ?: continue
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
