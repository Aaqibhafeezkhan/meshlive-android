package com.example.bluetoothtest

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** One entry of Android's per-app process exit history (ApplicationExitInfo, Android 11+). */
internal data class ExitRecord(
    val timestampMs: Long,
    val pid: Int,
    val reason: Int,
    val description: String,
    /** RunningAppProcessInfo importance at death (lower is more important). */
    val importance: Int,
    val rssKb: Long,
    /** A session service was running in that process (our own marker; Android does not know). */
    val sessionActive: Boolean,
) {
    val cause: ExitCause get() = ExitCause.of(reason)

    /** `BENCHMARK process_exit` payload: reason|description|importance|rss|sessionActive (BACKLOG B-03). */
    fun benchmarkLine(): String =
        "process_exit: ${ProcessExitReport.reasonName(reason)} | ${description.replace('|', '/').replace('\n', ' ').take(120)} | " +
            "importance=$importance | rss_kb=$rssKb | session_active=$sessionActive | t=$timestampMs"

    /** Plain-language one-liner for the reliability banner (batch B2 may add the vendor guidance). */
    fun userText(): String = ProcessExitReport.userText(this)
}

/** What kind of stop it was; the banner and the diagnostics group by this, not by the raw code. */
internal enum class ExitCause {
    /** The app closed itself (System.exit, finish): nothing to report. */
    SELF,

    /** The user or the system UI stopped it (swipe, Active apps list, Force stop, user switch). */
    USER,

    /** Memory pressure, the Android 17 memory limiter, or excessive resource use. */
    MEMORY_OR_RESOURCES,

    /** The freezer or the phone maker's own killer (REASON_OTHER usually names it). */
    SYSTEM_POLICY,

    /** The app itself crashed or hung. */
    APP_FAULT,

    /** Permission change, package update or state change: Android restarts the process on purpose. */
    PLATFORM_RESTART,

    UNKNOWN;

    companion object {
        fun of(reason: Int): ExitCause = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> SELF
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> USER
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> MEMORY_OR_RESOURCES
            ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> SYSTEM_POLICY
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> APP_FAULT
            ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> PLATFORM_RESTART
            else -> UNKNOWN
        }
    }
}

/**
 * Why did Android stop this app? (Device-family backlog B-03; research cpu-power CP-02/CP-05/CP-24, codec-background B9.)
 * The interface is fixed in the backlog's section 8 because batch B2 (reliability banner) and batch S (S-09, Android 10
 * scanner leak) consume it:
 *
 *   `fun readSince(sinceMs: Long): List<ExitRecord>`  and  `fun lastUnexpected(): ExitRecord?`
 *
 * [recordAtStart] is the one root hook: call it from the main activity's onCreate (any process start). It reads the
 * history newer than the last read, logs one `BENCHMARK process_exit` line per record, remembers the records, and
 * stores the newest timestamp so the next start reports only new exits. API 30+ only; before that everything is empty
 * and [lastUnexpected] is null (S-09 uses its own marker on API 29). Read-only: nothing is changed on the phone.
 */
internal object ProcessExitReport {
    private const val KEY_LAST_READ = "exit_report_last_ms"
    private const val KEY_SESSION_PID = "exit_report_session_pid"
    private const val MAX_RECORDS = 8

    @Volatile private var appContext: Context? = null
    @Volatile private var latest: List<ExitRecord> = emptyList()

    /** Call once per process start (main activity onCreate). Safe to call again; reads only new records. */
    fun recordAtStart(context: Context): List<ExitRecord> {
        appContext = context.applicationContext
        val prefs = prefs(context)
        val since = prefs.getLong(KEY_LAST_READ, 0L)
        val records = read(context, since)
        if (records.isNotEmpty()) {
            prefs.edit().putLong(KEY_LAST_READ, records.maxOf { it.timestampMs }).apply()
            records.forEach { logLine(it.benchmarkLine()) }
        }
        latest = (records + latest).distinctBy { it.pid to it.timestampMs }.sortedByDescending { it.timestampMs }.take(MAX_RECORDS)
        return records
    }

    /** All exits newer than [sinceMs] (newest first). Needs [recordAtStart] to have run once; else empty. */
    fun readSince(sinceMs: Long): List<ExitRecord> {
        val context = appContext ?: return emptyList()
        return read(context, sinceMs)
    }

    /**
     * The newest exit that was not the app closing itself, from this process's reads; null when none or before API 30.
     * User-driven stops are included: the banner shows "stopped by you" differently from "stopped by the phone".
     */
    fun lastUnexpected(): ExitRecord? = firstUnexpected(latest)

    /** [records] newest first; the first one that was not the app closing itself. */
    internal fun firstUnexpected(records: List<ExitRecord>): ExitRecord? = records.firstOrNull { it.cause != ExitCause.SELF }

    /** The session service marks itself so a later exit record can say whether a session was running. */
    fun markSession(context: Context, active: Boolean) {
        runCatching {
            prefs(context).edit().putInt(KEY_SESSION_PID, if (active) android.os.Process.myPid() else 0).apply()
        }
    }

    private fun read(context: Context, sinceMs: Long): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            val markedPid = prefs(context).getInt(KEY_SESSION_PID, 0)
            select(
                manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS).map { info ->
                    ExitRecord(
                        timestampMs = info.timestamp,
                        pid = info.pid,
                        reason = info.reason,
                        description = info.description.orEmpty(),
                        importance = info.importance,
                        rssKb = info.rss,
                        sessionActive = markedPid != 0 && markedPid == info.pid,
                    )
                },
                sinceMs,
            )
        }.getOrDefault(emptyList())
    }

    /** Newest first, only records after [sinceMs]. */
    internal fun select(records: List<ExitRecord>, sinceMs: Long): List<ExitRecord> =
        records.filter { it.timestampMs > sinceMs }.sortedByDescending { it.timestampMs }

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    internal fun userText(record: ExitRecord): String {
        val during = if (record.sessionActive) " while a call or stream was running" else ""
        return when (record.reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "The app closed itself."
            ApplicationExitInfo.REASON_USER_REQUESTED -> "You (or Android's Active apps list) stopped the app$during."
            ApplicationExitInfo.REASON_USER_STOPPED -> "The app was stopped because its user profile was stopped$during."
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android stopped the app to free memory$during."
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android stopped the app for using too much battery or memory$during."
            ApplicationExitInfo.REASON_FREEZER -> "Android froze the app to save battery$during."
            ApplicationExitInfo.REASON_OTHER ->
                "The phone's system stopped the app$during." + record.description.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
                "The system ended the app$during."
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "The app crashed$during."
            ApplicationExitInfo.REASON_ANR -> "The app stopped responding$during."
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "The app failed to start."
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "The app restarted because a permission was changed in Settings."
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE ->
                "The app restarted because it was updated or changed."
            else -> "The app stopped for an unknown reason$during."
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(OnboardingActivity.PREFERENCES, Context.MODE_PRIVATE)

    private fun logLine(line: String) {
        try { android.util.Log.i("BluetoothVideo", "BENCHMARK $line") } catch (_: RuntimeException) {}
    }
}
