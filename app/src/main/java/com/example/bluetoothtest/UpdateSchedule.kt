package com.example.bluetoothtest

/**
 * When the online update check may contact GitHub, and the one-time question that turns it on
 * (SWITCHES "Check for updates"). Pure and JVM-tested; [UpdateCheckStore] persists the state.
 *
 * The rules: nothing at all until the user said yes; at most one request a day on its own; after a
 * request that reached GitHub (any HTTP answer) the next one waits 24 hours, after a rate limit until
 * GitHub's reset time (at most 24 hours); a request that never left the phone (offline, DNS) may be
 * retried after an hour; a manual "Check now" waits 10 minutes after the last request so one phone
 * cannot spend a shared address's 60 requests per hour.
 */
data class UpdateCheckState(
    /** Last request attempt of any kind, ms since the epoch (0: never). */
    val lastAttemptMs: Long = 0,
    /** Last request that got an HTTP answer. */
    val lastReachedMs: Long = 0,
    /** Earliest time for the next automatic request, set after a rate limit. */
    val notBeforeMs: Long = 0,
    /** The newest release tag the last answer named (newer than the installed copy or not: the screen compares). */
    val latestTag: String? = null,
    /** When the user was asked "Check for updates when online?" (0: not yet). Asked at most once. */
    val askedAtMs: Long = 0,
)

object UpdateSchedule {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val NETWORK_RETRY_MS = 60L * 60 * 1000
    const val MANUAL_MIN_GAP_MS = 10L * 60 * 1000

    fun automaticDue(enabled: Boolean, state: UpdateCheckState, nowMs: Long): Boolean {
        if (!enabled) return false
        if (nowMs < state.notBeforeMs) return false
        // A clock set far back must not freeze the schedule: an attempt "in the future" counts as due.
        val sinceReached = nowMs - state.lastReachedMs
        val sinceAttempt = nowMs - state.lastAttemptMs
        if (state.lastReachedMs != 0L && sinceReached in 0 until DAY_MS) return false
        if (state.lastAttemptMs != 0L && sinceAttempt in 0 until NETWORK_RETRY_MS) return false
        return true
    }

    fun manualAllowed(state: UpdateCheckState, nowMs: Long): Boolean {
        val since = nowMs - state.lastAttemptMs
        return nowMs >= state.notBeforeMs && (state.lastAttemptMs == 0L || since !in 0 until MANUAL_MIN_GAP_MS)
    }

    /** Milliseconds until a manual check is allowed again (0: now). */
    fun manualWaitMs(state: UpdateCheckState, nowMs: Long): Long {
        val since = nowMs - state.lastAttemptMs
        val gap = if (state.lastAttemptMs == 0L || since !in 0 until MANUAL_MIN_GAP_MS) 0L else MANUAL_MIN_GAP_MS - since
        return maxOf(gap, (state.notBeforeMs - nowMs).coerceAtLeast(0L))
    }

    /** After GitHub answered (200, 304 or an error status). [rateLimitedUntilMs] is the reset time of a 403/429, if any. */
    fun afterAnswer(
        state: UpdateCheckState,
        nowMs: Long,
        latestTag: String?,
        rateLimitedUntilMs: Long? = null,
    ): UpdateCheckState = state.copy(
        lastAttemptMs = nowMs,
        lastReachedMs = nowMs,
        notBeforeMs = rateLimitedUntilMs?.coerceIn(nowMs, nowMs + DAY_MS) ?: 0,
        latestTag = latestTag ?: state.latestTag,
    )

    /** After a request that never got an answer. */
    fun afterNetworkFailure(state: UpdateCheckState, nowMs: Long): UpdateCheckState = state.copy(lastAttemptMs = nowMs)

    /** The reset time of a rate-limited answer from its headers, or null when none says so. */
    fun rateLimitReset(status: Int, retryAfterSeconds: Long?, resetEpochSeconds: Long?, remaining: Long?, nowMs: Long): Long? {
        if (status != 403 && status != 429) return null
        if (retryAfterSeconds != null) return nowMs + retryAfterSeconds.coerceIn(0, DAY_MS / 1000) * 1000
        if (remaining == 0L && resetEpochSeconds != null) {
            return resetEpochSeconds.coerceIn(nowMs / 1000, (nowMs + DAY_MS) / 1000) * 1000
        }
        return if (status == 429 || remaining == null || remaining == 0L) nowMs + NETWORK_RETRY_MS else null
    }
}

/** The one-time question. */
object UpdatePrompt {
    /**
     * Ask when the build can update itself, the installed copy carries the release key (otherwise the
     * answer would change nothing), nobody asked yet, this is not a benchmark launch and nothing else
     * (a call, a stream, onboarding) is on screen.
     */
    fun shouldAsk(
        updaterAvailable: Boolean,
        installedHasReleaseKey: Boolean,
        askedAtMs: Long,
        benchmarkLaunch: Boolean,
        homeIdle: Boolean,
    ): Boolean = updaterAvailable && installedHasReleaseKey && askedAtMs == 0L && !benchmarkLaunch && homeIdle
}

/** What the home screen does when it comes back to the front and is idle. */
enum class UpdateResumeAction { ASK, CHECK_NOW, NOTHING }

object UpdateResume {
    /**
     * The question first (when it is due), else the automatic check (only after a yes), else nothing. A build that cannot
     * update itself, a copy without the release key, and a busy home screen (a call, a stream, onboarding) do nothing.
     */
    fun action(
        updaterAvailable: Boolean,
        onlineOffered: Boolean,
        homeIdle: Boolean,
        askedAtMs: Long,
        benchmarkLaunch: Boolean,
        checkEnabled: Boolean,
    ): UpdateResumeAction = when {
        !updaterAvailable || !onlineOffered || !homeIdle -> UpdateResumeAction.NOTHING
        UpdatePrompt.shouldAsk(updaterAvailable, onlineOffered, askedAtMs, benchmarkLaunch, homeIdle) -> UpdateResumeAction.ASK
        checkEnabled -> UpdateResumeAction.CHECK_NOW
        else -> UpdateResumeAction.NOTHING
    }
}

/** Where [UpdateCheckState] lives (SharedPreferences in the app, a map in tests). */
interface UpdateKeyValue {
    fun getLong(key: String, default: Long): Long
    fun getString(key: String): String?
    fun put(longs: Map<String, Long>, strings: Map<String, String?>)
}

class UpdateCheckStore(private val store: UpdateKeyValue) {
    fun load(): UpdateCheckState = UpdateCheckState(
        lastAttemptMs = store.getLong(ATTEMPT, 0),
        lastReachedMs = store.getLong(REACHED, 0),
        notBeforeMs = store.getLong(NOT_BEFORE, 0),
        latestTag = store.getString(LATEST),
        askedAtMs = store.getLong(ASKED, 0),
    )

    fun save(state: UpdateCheckState) = store.put(
        mapOf(
            ATTEMPT to state.lastAttemptMs,
            REACHED to state.lastReachedMs,
            NOT_BEFORE to state.notBeforeMs,
        ),
        mapOf(LATEST to state.latestTag),
    )

    /** Only the question's answer time, so a concurrent check cannot overwrite it. */
    fun markAsked(nowMs: Long) = store.put(mapOf(ASKED to nowMs), emptyMap())

    companion object {
        const val ATTEMPT = "update_check_attempt_at"
        const val REACHED = "update_check_reached_at"
        const val NOT_BEFORE = "update_check_not_before"
        const val ASKED = "update_check_asked_at"
        const val LATEST = "update_check_latest_tag"
    }
}
