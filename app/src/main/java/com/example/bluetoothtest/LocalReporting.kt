package com.example.bluetoothtest

import android.content.Context

/** Local moderation only. The callbacks cannot submit a report or open a URL. */
internal object LocalReportPolicy {
    fun report(block: () -> BlockResult, hide: () -> Boolean): Boolean = when (block()) {
        BlockResult.BLOCKED, BlockResult.ALREADY_BLOCKED -> hide()
        else -> false
    }

    fun hidden(identity: String, fingerprints: Set<String>): Boolean = identity.isNotEmpty() &&
        fingerprints.any { it.startsWith(identity.lowercase()) && identity.length in setOf(12, NodeId.BYTE_COUNT * 2, IdentityFingerprint.BYTE_COUNT * 2) }
}

/** Uses the existing wiped app preferences, like chat mute. Keeps hiding even if a block is later removed. */
internal object LocalReporting {
    private const val PEOPLE = "reported_people"
    private const val STREAMS = "reported_streams"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE,
    )

    fun hidden(context: Context, identity: String): Boolean = DebugTunables.localReporting &&
        LocalReportPolicy.hidden(identity, prefs(context).getStringSet(PEOPLE, emptySet()).orEmpty())

    fun prefixes(context: Context): Set<String> = if (!DebugTunables.localReporting) emptySet() else
        prefs(context).getStringSet(PEOPLE, emptySet()).orEmpty().map { it.take(12) }.toSet()

    fun hiddenStream(context: Context, token: Int): Boolean = DebugTunables.localReporting &&
        token.toString() in prefs(context).getStringSet(STREAMS, emptySet()).orEmpty()

    @Synchronized
    fun hideStream(context: Context, token: Int) {
        val prefs = prefs(context)
        prefs.edit().putStringSet(STREAMS, prefs.getStringSet(STREAMS, emptySet()).orEmpty() + token.toString()).commit()
    }

    @Synchronized
    fun report(context: Context, fingerprint: IdentityFingerprint, streamToken: Int? = null): Boolean =
        LocalReportPolicy.report({ BlockedPeople.block(context, fingerprint) }) {
            val prefs = prefs(context)
            val people = prefs.getStringSet(PEOPLE, emptySet()).orEmpty() + fingerprint.hex
            val streams = prefs.getStringSet(STREAMS, emptySet()).orEmpty() + listOfNotNull(streamToken?.toString())
            prefs.edit().putStringSet(PEOPLE, people).putStringSet(STREAMS, streams).commit()
        }
}
