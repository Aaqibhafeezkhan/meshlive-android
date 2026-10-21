package com.example.bluetoothtest

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.util.Locale

/**
 * Generated names for strangers (Design G, G2; PROTOCOL "Naming v2" `names-v1`). A display convenience only: a name such
 * as "Blue Otter" is a function of a PUBLIC rotating value, so everyone who sees the same value shows the same words, and
 * the owner can say them aloud. It is never an authenticator and never a block handle.
 *
 * ```text
 * d    = SHA-256("BluetoothVideoMesh/Naming/StrangerName/1" || 0x00 || input)
 * name = adjectives[d[0]] + " " + nouns[d[1]]      chip = d[2] & 7      number = d[3] % 100
 * ```
 *
 * `input` is exactly one of two things, enforced by the type: the 6-byte rotating discovery ID ([Input.rdi]) of a version 5 /
 * compact v2 / window advert, or the 16-byte node ID of the throwaway key of an incoming stranger session
 * ([Input.throwawayNodeId]). The only constructors take raw byte arrays of those two sizes and nothing here accepts a
 * [NodeId], an [IdentityFingerprint], a prefix, a handle or a lease key; a test greps the callers.
 *
 * The app uses `names-v2`: the 256-word lists `assets/names/adjectives-v2.txt` and `nouns-v2.txt` plus the pair blocklist
 * `blocked-pairs-v2.txt` (original, GPL-3.0-or-later with the app, frozen; SHA-256 pinned below so any edit is refused at start
 * and fails the build's test). `names-v1` (the earlier lists) stays in the assets and in the tests as vector history. A changed
 * list is `names-v3`. A pair on the blocklist is never shown: [derive] re-hashes the same input with a counter byte until the
 * pair is allowed, so every phone skips the same pairs the same way.
 * Off-switch: `generated_names` ([NamingFeatures]); off shows the handle as in 0.2.0-beta.
 */
internal class StrangerNameLists private constructor(
    val adjectives: List<String>,
    val nouns: List<String>,
    /** Blocked "adjective noun" pairs as `adjectiveIndex * 256 + nounIndex` (empty for names-v1). */
    private val blocked: Set<Int> = emptySet(),
) {
    fun isBlocked(adjectiveIndex: Int, nounIndex: Int): Boolean = (adjectiveIndex * SIZE + nounIndex) in blocked

    val blockedCount: Int get() = blocked.size

    companion object {
        const val SIZE = 256
        private val WORD = Regex("[a-z]{3,7}")

        /** Strict shape check only (count, alphabet, strictly sorted); the screening rules are verified by the unit test. */
        fun parse(adjectives: List<String>, nouns: List<String>, blockedPairs: List<String> = emptyList()): StrangerNameLists {
            fun check(name: String, words: List<String>) {
                require(words.size == SIZE) { "$name must hold $SIZE words" }
                require(words.all { WORD.matches(it) }) { "$name must be a-z, 3-7 letters" }
                require(words.zipWithNext().all { (a, b) -> a < b }) { "$name must be sorted and unique" }
            }
            check("adjectives", adjectives)
            check("nouns", nouns)
            val blocked = blockedPairs.map { line ->
                val parts = line.split(' ')
                require(parts.size == 2) { "A blocked pair is \"adjective noun\"" }
                val a = adjectives.indexOf(parts[0])
                val n = nouns.indexOf(parts[1])
                require(a >= 0 && n >= 0) { "A blocked pair must use words of the lists: $line" }
                a * SIZE + n
            }.toSet()
            return StrangerNameLists(adjectives.toList(), nouns.toList(), blocked)
        }

        fun parseText(adjectivesText: String, nounsText: String, blockedPairsText: String = ""): StrangerNameLists =
            parse(
                adjectivesText.lines().filter { it.isNotEmpty() },
                nounsText.lines().filter { it.isNotEmpty() },
                blockedPairsText.lines().filter { it.isNotEmpty() },
            )
    }
}

/** One derived name: words, the colour chip (0..7) and the two-digit disambiguator. */
internal class GeneratedName(
    val adjective: String,
    val noun: String,
    val chip: Int,
    val number: Int,
    /** The last four hex digits of the input bytes (RDI or throwaway node ID), the second disambiguator. */
    val idTail: String,
) {
    val text: String get() = "$adjective $noun"

    /** Screens show this when the name must be read aloud in a place where another phone could match. */
    val numbered: String get() = "$text ${"%02d".format(Locale.ROOT, number)}"

    override fun toString(): String = text
}

internal object StrangerNames {
    const val DOMAIN = "BluetoothVideoMesh/Naming/StrangerName/1"
    const val ASSET_DIRECTORY = "names"
    const val ADJECTIVES_FILE = "adjectives-v2.txt"
    const val NOUNS_FILE = "nouns-v2.txt"
    const val BLOCKED_PAIRS_FILE = "blocked-pairs-v2.txt"

    /** SHA-256 of the three names-v2 files. Editing one changes every phone's display of every ID: that is `names-v3`. */
    const val ADJECTIVES_SHA256 = "d832789668051db146496979eaf9d362b90f43c55d51febb9c825dc1e0417b46"
    const val NOUNS_SHA256 = "1560dd97fdb19313fcdcfd6335c454d888d298ca2da595fc4c032d051ab4a39c"
    const val BLOCKED_PAIRS_SHA256 = "381d7404fe637a61ace734d6569103f159de2e27d2ea62adc54dae4f9acfa304"

    /** names-v1, kept for vector history only (the files stay in the assets; nothing loads them at run time). */
    const val V1_ADJECTIVES_FILE = "adjectives-v1.txt"
    const val V1_NOUNS_FILE = "nouns-v1.txt"
    const val V1_ADJECTIVES_SHA256 = "7d51bad8236ca9e8a910ceb3d2e749c49cae3f0e909107078893b1d5e9456131"
    const val V1_NOUNS_SHA256 = "d61dc92a1bf3a219ff62a119049b7e6aff8ce6a908dc39edd8b04a619bbcf995"

    /** A blocked pair is re-hashed at most this many times (then the last attempt stands; with 95 of 65,536 pairs it never does). */
    const val MAX_PAIR_ATTEMPTS = 16

    /** The only two things a generated name may be derived from. */
    class Input private constructor(internal val bytes: ByteArray) {
        companion object {
            /** The 6-byte rotating discovery ID of an advert (role 0, 1 or 3), as scanned or as computed by its owner. */
            fun rdi(bytes: ByteArray): Input {
                require(bytes.size == RotatingDiscoveryIds.ID_BYTES) { "An RDI is ${RotatingDiscoveryIds.ID_BYTES} bytes" }
                return Input(bytes.copyOf())
            }

            /** The 16-byte node ID of the throwaway key of a stranger session (never a real identity's node ID). */
            fun throwawayNodeId(bytes: ByteArray): Input {
                require(bytes.size == NodeId.BYTE_COUNT) { "A node ID is ${NodeId.BYTE_COUNT} bytes" }
                return Input(bytes.copyOf())
            }
        }
    }

    @Volatile private var installed: StrangerNameLists? = null

    val isInstalled: Boolean get() = installed != null

    /** Loads the lists once at start. A missing or edited file leaves names unavailable (the handle is shown). */
    fun install(context: Context) {
        if (installed != null) return
        runCatching {
            val adjectives = context.assets.open("$ASSET_DIRECTORY/$ADJECTIVES_FILE").use { it.readBytes() }
            val nouns = context.assets.open("$ASSET_DIRECTORY/$NOUNS_FILE").use { it.readBytes() }
            val blocked = context.assets.open("$ASSET_DIRECTORY/$BLOCKED_PAIRS_FILE").use { it.readBytes() }
            installIfFrozen(adjectives, nouns, blocked)
        }.onFailure { Log.w("BluetoothVideo", "Generated-name lists not loaded; the handle is shown", it) }
    }

    /** Installs the lists only when all three files equal the frozen hashes. Returns whether they were installed. */
    internal fun installIfFrozen(adjectives: ByteArray, nouns: ByteArray, blockedPairs: ByteArray): Boolean {
        if (sha256Hex(adjectives) != ADJECTIVES_SHA256 || sha256Hex(nouns) != NOUNS_SHA256 ||
            sha256Hex(blockedPairs) != BLOCKED_PAIRS_SHA256
        ) {
            return false
        }
        installed = StrangerNameLists.parseText(
            adjectives.toString(Charsets.US_ASCII),
            nouns.toString(Charsets.US_ASCII),
            blockedPairs.toString(Charsets.US_ASCII),
        )
        return true
    }

    internal fun installForTest(lists: StrangerNameLists?) {
        installed = lists
    }

    /** The installed lists, for the reserved-form text rule ([NameTextRules]) only. */
    internal fun currentListsForRules(): StrangerNameLists? = installed

    /** The generated name of [input], or null when the lists are not installed. */
    fun derive(input: Input, lists: StrangerNameLists? = installed): GeneratedName? {
        lists ?: return null
        val d = digestFor(input, lists)
        val tail = input.bytes.takeLast(2).joinToString("") { "%02x".format(it) }
        return GeneratedName(
            adjective = lists.adjectives[d[0].toInt() and 0xff],
            noun = lists.nouns[d[1].toInt() and 0xff],
            chip = d[2].toInt() and 7,
            number = (d[3].toInt() and 0xff) % 100,
            idTail = tail,
        )
    }

    /**
     * `d = SHA-256(domain || 0x00 || input)`; when the pair `d[0], d[1]` is on the blocklist, the same input is hashed again
     * with the attempt number (1, 2, ...) appended as one byte until the pair is allowed. The first attempt carries no
     * counter, so with an empty blocklist (names-v1) this is the published function exactly.
     */
    private fun digestFor(input: Input, lists: StrangerNameLists?): ByteArray {
        var attempt = 0
        while (true) {
            val d = MessageDigest.getInstance("SHA-256").run {
                update(DOMAIN.toByteArray(Charsets.US_ASCII))
                update(0)
                update(input.bytes)
                if (attempt > 0) update(attempt.toByte())
                digest()
            }
            if (attempt >= MAX_PAIR_ATTEMPTS || lists?.isBlocked(d[0].toInt() and 0xff, d[1].toInt() and 0xff) != true) return d
            attempt++
        }
    }

    /** The raw `d[0..3]` bytes, for the published vectors. */
    internal fun indices(input: Input, lists: StrangerNameLists? = installed): IntArray {
        val d = digestFor(input, lists)
        return IntArray(4) { d[it].toInt() and 0xff }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Row text for a list of nearby phones (G2 "Collisions"): two different IDs can draw the same name (16 bits), so the list
 * grows the label until the rows differ and never merges rows by name. The digits come from the ID, never from arrival
 * order, so every phone that sees the same rows shows the same text. A phone that sees only one of two colliding rows
 * shows no suffix: the name is a convenience for picking a phone in a room, not evidence of who owns an ID.
 */
internal object GeneratedNameRows {
    /** [rows]: a stable row key (the RDI hex) to its derived name. Returns the text per key. */
    fun labels(rows: Map<String, GeneratedName>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        rows.entries.groupBy { it.value.text }.forEach { (base, group) ->
            if (group.size == 1) {
                out[group[0].key] = base
                return@forEach
            }
            // Level 1: two digits from the ID. Level 2 (only those that still collide): the last four hex digits too.
            val numbered = group.groupBy { it.value.numbered }
            numbered.forEach { (text, sameNumber) ->
                if (sameNumber.size == 1) {
                    out[sameNumber[0].key] = text
                } else {
                    sameNumber.forEach { out[it.key] = "$text-${it.value.idTail}" }
                }
            }
        }
        return rows.keys.associateWith { out.getValue(it) }
    }
}

/**
 * The copied-ID warning (Design G, review F4). Rows are keyed by RDI. It fires only when two different advertising
 * addresses carry the same RDI at the same route cost and both were heard within [SIMULTANEOUS_MS] of each other, on more
 * than one occasion. One ID on a second address inside an epoch (the stack's own address timer) and a relay's mirror of a
 * child's ID at route cost + 1 are NOT copies. It disables nothing; the screen asks for a second tap.
 *
 * Overlap rule (default on; DEBUG control arm `test_copied_id_overlap=false`). Two addresses count only when their
 * sightings interleave: the other address was heard again after this one first appeared. 2026-10-20 edge-case suite
 * (m31-a15s-redmi-1, steps 3 to 6): the A15s replaced its call-listener advertising set (a new random address each
 * time) when it showed its Meet code (boost to low latency) and again when the boost ended, the old set stopping 31 ms
 * before the new one started. The M31 heard the old address last moments before the new one first, which the
 * 2-second rule counted as a coincidence, twice, so "More than one phone is using this ID" showed on every call. A
 * handover never interleaves; two senders on the air at once do (a copy, or the PC's legacy and extended twin).
 */
internal class CopiedIdTracker(
    private val simultaneousMs: Long = SIMULTANEOUS_MS,
    private val requireOverlap: () -> Boolean = { CopiedIdOverlapRule.enabled },
    private val pcPairExempt: () -> Boolean = { CopiedIdPcPair.enabled },
) {
    private class Seen(val address: String, val firstMs: Long, var lastMs: Long, var form: AdvertTwinForm?)

    private val perKey = HashMap<String, MutableList<Seen>>()
    private val touched = HashMap<String, Long>()
    private val coincidences = HashMap<String, Int>()
    private val lastCoincidenceMs = HashMap<String, Long>()

    /** Records one advert. Returns true when the warning is now due for [rdiHex]. */
    @Synchronized
    fun observe(rdiHex: String, address: String, routeCost: Int, nowMs: Long, form: AdvertTwinForm? = null): Boolean {
        val key = "$rdiHex/$routeCost"
        touched[key] = nowMs
        if (touched.size > MAX_KEYS) prune(nowMs)
        val list = perKey.getOrPut(key) { mutableListOf() }
        val own = list.firstOrNull { it.address == address }?.also { it.lastMs = nowMs; it.form = form }
            ?: Seen(address, nowMs, nowMs, form).also { list += it }
        val overlap = requireOverlap()
        val recent = list.filter { nowMs - it.lastMs <= simultaneousMs }
        val other = !(pcPairExempt() && pcTwin(recent)) && list.any {
            it.address != address && nowMs - it.lastMs <= simultaneousMs &&
                // The other address was heard after this one appeared: both on the air, not one replacing the other.
                (!overlap || it.lastMs > own.firstMs)
        }
        if (other) {
            val previous = lastCoincidenceMs[key]
            if (previous == null || nowMs - previous > simultaneousMs) {
                coincidences[key] = (coincidences[key] ?: 0) + 1
                lastCoincidenceMs[key] = nowMs
            }
        }
        return (coincidences[key] ?: 0) >= REPEATS
    }

    /**
     * Q-PC-COPIED-ID option A (Design CI): a Windows PC sends its compact call offer as one legacy and one extended set
     * from two addresses. Exactly two recent addresses, one legacy and one extended, with byte-identical compared ADs
     * (Flags and the UUID lists, which carry 0x180A) are one sender. A third address or any difference still counts.
     */
    private fun pcTwin(recent: List<Seen>): Boolean {
        if (recent.size != 2) return false
        val (a, b) = recent
        val fa = a.form ?: return false
        val fb = b.form ?: return false
        return fa.legacy != fb.legacy && (if (fa.legacy) fb else fa).extendedSecondaryPhy && fa.compared == fb.compared
    }

    /** True when the warning is currently due for [rdiHex] at any route cost. */
    @Synchronized
    fun warned(rdiHex: String): Boolean = coincidences.any { (key, count) -> key.startsWith("$rdiHex/") && count >= REPEATS }

    /** Drops every ID not heard for [STALE_MS]: an ID that left the air (epoch change) is never needed again. */
    @Synchronized
    fun prune(nowMs: Long) {
        val stale = touched.filterValues { nowMs - it > STALE_MS }.keys
        stale.forEach { key ->
            touched.remove(key)
            perKey.remove(key)
            coincidences.remove(key)
            lastCoincidenceMs.remove(key)
        }
    }

    /** Forgets everything about an ID that left the air (epoch change). */
    @Synchronized
    fun forget(rdiHex: String) {
        val prefix = "$rdiHex/"
        perKey.keys.removeAll { it.startsWith(prefix) }
        coincidences.keys.removeAll { it.startsWith(prefix) }
        lastCoincidenceMs.keys.removeAll { it.startsWith(prefix) }
    }

    companion object {
        const val SIMULTANEOUS_MS = 2_000L
        const val REPEATS = 2
        const val STALE_MS = 20 * 60_000L
        const val MAX_KEYS = 512
    }
}

/**
 * How one compact call-offer advert was received, for [CopiedIdTracker]'s PC exemption: [legacy] is ScanResult.isLegacy,
 * [extendedSecondaryPhy] an extended advert with a secondary PHY, [compared] the AD bytes both sets must share.
 */
data class AdvertTwinForm(val legacy: Boolean, val extendedSecondaryPhy: Boolean, val compared: String)

/** The ADs a PC's legacy and extended twin must carry byte for byte (Design CI rule 2), from the raw advertising bytes. */
internal object AdvertTwinAds {
    private val COMPARED_TYPES = setOf(0x01, 0x02, 0x03, 0x06, 0x07)

    /**
     * Flags, the 16-bit and the 128-bit UUID lists in their order, as hex; null when the 16-bit list lacks 0x180A (the
     * Windows sets carry it) or the bytes are malformed. Other ADs (the PC's service data) are not compared.
     */
    fun compared(bytes: ByteArray?): String? {
        if (bytes == null) return null
        val out = StringBuilder()
        var has180a = false
        var index = 0
        while (index < bytes.size) {
            val length = bytes[index].toInt() and 0xff
            if (length == 0) break
            if (index + length > bytes.size - 1) return null
            val type = bytes[index + 1].toInt() and 0xff
            if (type in COMPARED_TYPES) {
                for (i in index..index + length) out.append("%02x".format(bytes[i].toInt() and 0xff))
                if (type == 0x02 || type == 0x03) {
                    var u = index + 2
                    while (u + 1 <= index + length) {
                        if ((bytes[u].toInt() and 0xff) == 0x0a && (bytes[u + 1].toInt() and 0xff) == 0x18) has180a = true
                        u += 2
                    }
                }
            }
            index += length + 1
        }
        return out.toString().takeIf { has180a }
    }
}

/** Q-PC-COPIED-ID option A, default on; DEBUG `test_copied_id_pc_pair=false` restores the warning for a PC's twin sets. */
internal object CopiedIdPcPair {
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }
}

/** [CopiedIdTracker]'s overlap rule: default on; a DEBUG launch can restore the 2-second rule (`test_copied_id_overlap=false`). */
internal object CopiedIdOverlapRule {
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }
}

/** The rotating discovery ID of a version 5 / compact v2 advert (its six prefix bytes), or null for a fixed-prefix advert. */
internal fun DiscoveryAnnouncement.rotatingId(): ByteArray? = if (rotating) nodeIdPrefix.copyOf() else null
