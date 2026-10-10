package com.example.bluetoothtest

import android.content.Context

/** One remembered call: who (12-hex node prefix), voice or video, and when it connected. */
internal data class RecentCall(val nodePrefixHex: String, val video: Boolean, val atMs: Long)

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

    fun load(context: Context): List<RecentCall> = decode(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null),
    )

    fun record(context: Context, call: RecentCall) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val updated = add(decode(prefs.getString(KEY, null)), call)
        prefs.edit().putString(KEY, encode(updated)).apply()
    }

    /** Newest first, one entry per person, at most [MAX_ENTRIES]. */
    fun add(existing: List<RecentCall>, call: RecentCall): List<RecentCall> =
        (listOf(call) + existing.filterNot { it.nodePrefixHex == call.nodePrefixHex }).take(MAX_ENTRIES)

    /** One entry per line: `prefix,v|a,epochMs` (plain text; no JSON so it unit-tests on the JVM). */
    fun encode(calls: List<RecentCall>): String =
        calls.joinToString("\n") { "${it.nodePrefixHex},${if (it.video) "v" else "a"},${it.atMs}" }

    fun decode(text: String?): List<RecentCall> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.trim().split(',')
        if (parts.size != 3) return@mapNotNull null
        val prefix = parts[0].lowercase()
        val at = parts[2].toLongOrNull()
        if (!prefix.matches(Regex("[0-9a-f]{12}")) || at == null || parts[1] !in setOf("v", "a")) return@mapNotNull null
        RecentCall(prefix, parts[1] == "v", at)
    }.take(MAX_ENTRIES)
}
