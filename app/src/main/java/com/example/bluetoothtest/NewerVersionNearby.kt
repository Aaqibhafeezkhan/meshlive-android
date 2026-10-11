package com.example.bluetoothtest

/**
 * What the app remembers about a nearby phone that says it runs a newer release (PROTOCOL "Build tag
 * in the link hello", notice rules). Pure and JVM-tested; [NewerVersionNearbyStore] persists it.
 * No peer identifier is kept: only the best plausible claim, when it was seen and the dismissal.
 */
data class NearbyNoticeState(
    val bestRelease: Int = 0,
    val bestReleaseKey: Boolean = false,
    val seenAtMs: Long = 0,
    val dismissedRelease: Int = 0,
    val dismissedAtMs: Long = 0,
)

object NewerVersionNearby {
    /** A claim more than this many releases above the installed one is ignored (one forged number must not hide later notices). */
    const val MAX_PLAUSIBLE_JUMP = 20

    /** A dismissal hides claims up to its number for this long. */
    const val DISMISS_MS = 7L * 24 * 60 * 60 * 1000

    /** A claim not repeated for this long is forgotten (the other phone may have left or updated). */
    const val STALE_MS = 14L * 24 * 60 * 60 * 1000

    /**
     * Whether [tag] is worth remembering for a phone with [installedRelease]. A phone that carries the
     * project release key only takes claims from release-key phones, because only their APK can update it.
     */
    fun plausible(tag: BuildTag, installedRelease: Int, installedReleaseKey: Boolean): Boolean =
        installedRelease >= 1 &&
            tag.releaseNumber > installedRelease &&
            tag.releaseNumber <= installedRelease + MAX_PLAUSIBLE_JUMP &&
            (!installedReleaseKey || tag.releaseKey)

    /** The state after a verified link handshake with a peer that sent [tag]. */
    fun observe(
        state: NearbyNoticeState,
        tag: BuildTag,
        installedRelease: Int,
        installedReleaseKey: Boolean,
        nowMs: Long,
    ): NearbyNoticeState {
        if (!plausible(tag, installedRelease, installedReleaseKey)) return state
        val expired = state.seenAtMs != 0L && nowMs - state.seenAtMs > STALE_MS
        val higher = tag.releaseNumber > state.bestRelease
        return when {
            higher || expired || state.bestRelease <= installedRelease -> state.copy(
                bestRelease = tag.releaseNumber,
                bestReleaseKey = tag.releaseKey,
                seenAtMs = nowMs,
            )
            tag.releaseNumber == state.bestRelease -> state.copy(seenAtMs = nowMs)
            else -> state
        }
    }

    /** The release to announce, or null while there is nothing to show. */
    fun notice(
        state: NearbyNoticeState,
        installedRelease: Int,
        installedReleaseKey: Boolean,
        nowMs: Long,
    ): Int? {
        val best = state.bestRelease
        if (best <= installedRelease || state.seenAtMs == 0L) return null
        if (best > installedRelease + MAX_PLAUSIBLE_JUMP) return null
        if (installedReleaseKey && !state.bestReleaseKey) return null
        if (nowMs - state.seenAtMs > STALE_MS) return null
        val dismissed = state.dismissedRelease >= best && nowMs - state.dismissedAtMs in 0..DISMISS_MS
        return if (dismissed) null else best
    }

    fun dismiss(state: NearbyNoticeState, nowMs: Long): NearbyNoticeState =
        state.copy(dismissedRelease = state.bestRelease, dismissedAtMs = nowMs)

    /** Drops state that can no longer matter (installed copy caught up, dismissal over). */
    fun tidy(state: NearbyNoticeState, installedRelease: Int, nowMs: Long): NearbyNoticeState {
        var next = state
        if (next.bestRelease in 1..installedRelease) next = next.copy(bestRelease = 0, bestReleaseKey = false, seenAtMs = 0)
        if (next.dismissedRelease != 0 && nowMs - next.dismissedAtMs !in 0..DISMISS_MS) {
            next = next.copy(dismissedRelease = 0, dismissedAtMs = 0)
        }
        return next
    }
}

/** Where [NearbyNoticeState] lives (SharedPreferences in the app, a map in tests). */
interface NoticeKeyValue {
    fun getLong(key: String, default: Long): Long
    fun putLongs(values: Map<String, Long>)
}

class NewerVersionNearbyStore(private val store: NoticeKeyValue) {
    fun load(): NearbyNoticeState = NearbyNoticeState(
        bestRelease = store.getLong(BEST, 0).toInt(),
        bestReleaseKey = store.getLong(BEST_KEY, 0) != 0L,
        seenAtMs = store.getLong(SEEN, 0),
        dismissedRelease = store.getLong(DISMISSED, 0).toInt(),
        dismissedAtMs = store.getLong(DISMISSED_AT, 0),
    )

    fun save(state: NearbyNoticeState) = store.putLongs(
        mapOf(
            BEST to state.bestRelease.toLong(),
            BEST_KEY to if (state.bestReleaseKey) 1L else 0L,
            SEEN to state.seenAtMs,
            DISMISSED to state.dismissedRelease.toLong(),
            DISMISSED_AT to state.dismissedAtMs,
        ),
    )

    companion object {
        const val BEST = "nearby_update_best"
        const val BEST_KEY = "nearby_update_best_key"
        const val SEEN = "nearby_update_seen_at"
        const val DISMISSED = "nearby_update_dismissed"
        const val DISMISSED_AT = "nearby_update_dismissed_at"
    }
}
