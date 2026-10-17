package com.example.bluetoothtest

import android.content.Context

/**
 * One remembered call: who (12-hex node prefix), voice or video, and when it connected. [fingerprintHex] is the
 * caller's full identity fingerprint (64 hex) when known, so a row can block the person (CallBlocking.kt).
 * [blocked] marks a call that was declined without ringing because "Block unknown callers" is on; nobody is named
 * (the caller's identity was never revealed), so its prefix is empty.
 */
internal data class RecentCall(
    val nodePrefixHex: String,
    val video: Boolean,
    val atMs: Long,
    val fingerprintHex: String = "",
    val blocked: Boolean = false,
    /**
     * Naming v2 (review F20): an unverified stranger's entry holds only the generated name they showed and the time. Their
     * node ID and fingerprint stay in memory for the call and are persisted only if the person is then verified, so a seized
     * phone does not hold a list of stranger keys. Such an entry has an empty [nodePrefixHex] and cannot be called back.
     */
    val strangerName: String = "",
)

/**
 * Home "Recent": the last few people this phone talked to, newest first, one entry per person.
 * Stored only on this phone (app-private preferences, cleared by Emergency wipe). Names are not
 * stored: the row asks PeerLabels at display time (contact label or handle).
 */
internal object RecentCalls {
    /** Listed in EmergencyWipe.KNOWN_PREFERENCES. */
    const val PREFERENCES = "recent_calls"
    private const val KEY = "calls_v1"
    const val MAX_ENTRIES = 5

    /** "gold fox", "gold fox 42", "gold fox-7a7d" and "gold fox 42-7a7d": a generated name, lower case, no commas. */
    private val STRANGER_NAME = Regex("[a-z]{3,7} [a-z]{3,7}( [0-9]{2})?(-[0-9a-f]{4})?")

    fun load(context: Context): List<RecentCall> = decode(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null),
    )

    fun record(context: Context, call: RecentCall) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val updated = add(decode(prefs.getString(KEY, null)), call)
        prefs.edit().putString(KEY, encode(updated)).apply()
    }

    /** A call declined by "Block unknown callers": one row, the latest time (nobody is named). */
    fun recordBlocked(context: Context, atMs: Long) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val updated = add(decode(prefs.getString(KEY, null)), RecentCall("", false, atMs, blocked = true))
        prefs.edit().putString(KEY, encode(updated)).apply()
    }

    /** Newest first, one entry per person (and one blocked-call row), at most [MAX_ENTRIES]. */
    fun add(existing: List<RecentCall>, call: RecentCall): List<RecentCall> =
        (listOf(call) + existing.filterNot {
            when {
                call.blocked -> it.blocked
                call.strangerName.isNotEmpty() -> it.strangerName == call.strangerName
                else -> !it.blocked && it.strangerName.isEmpty() && it.nodePrefixHex == call.nodePrefixHex
            }
        }).take(MAX_ENTRIES)

    /**
     * One entry per line: `prefix,v|a,epochMs[,fingerprint[,b]]` (plain text; no JSON so it unit-tests on the JVM).
     * Older lines have three fields; a blocked call has an empty prefix and the trailing `b`.
     */
    fun encode(calls: List<RecentCall>): String = calls.joinToString("\n") {
        val base = "${it.nodePrefixHex},${if (it.video) "v" else "a"},${it.atMs}"
        when {
            it.blocked -> "$base,,b"
            it.strangerName.isNotEmpty() -> "$base,,s,${it.strangerName}"
            it.fingerprintHex.isNotEmpty() -> "$base,${it.fingerprintHex}"
            else -> base
        }
    }

    fun decode(text: String?): List<RecentCall> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.trim().split(',')
        if (parts.size !in 3..6) return@mapNotNull null
        val prefix = parts[0].lowercase()
        val at = parts[2].toLongOrNull()
        if (at == null || parts[1] !in setOf("v", "a")) return@mapNotNull null
        val fingerprint = parts.getOrNull(3).orEmpty().lowercase()
        // A stranger: `,v|a,epochMs,,s,<generated name>` (no prefix, no fingerprint).
        if (parts.getOrNull(4) == "s") {
            val name = parts.getOrNull(5).orEmpty()
            if (parts.size != 6 || prefix.isNotEmpty() || fingerprint.isNotEmpty() || !STRANGER_NAME.matches(name)) return@mapNotNull null
            return@mapNotNull RecentCall("", parts[1] == "v", at, strangerName = name)
        }
        if (parts.size == 6) return@mapNotNull null
        val blocked = parts.getOrNull(4) == "b"
        if (parts.size == 5 && !blocked) return@mapNotNull null
        if (fingerprint.isNotEmpty() && !fingerprint.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
        if (blocked) {
            if (prefix.isNotEmpty() || fingerprint.isNotEmpty()) return@mapNotNull null
            return@mapNotNull RecentCall("", parts[1] == "v", at, blocked = true)
        }
        if (!prefix.matches(Regex("[0-9a-f]{12}")) || (fingerprint.isNotEmpty() && !fingerprint.startsWith(prefix))) {
            return@mapNotNull null
        }
        RecentCall(prefix, parts[1] == "v", at, fingerprint)
    }.take(MAX_ENTRIES)
}
