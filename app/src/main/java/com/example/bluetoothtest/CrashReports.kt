package com.example.bluetoothtest

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash reports (default on; "Don't ask again" in the prompt, or the DEBUG launch extra `test_crash_reports=false`, turns
 * them off). 2026-10-21: a public report said the app force-closes on a Redmi Note 13 Pro 5G (Android 16, HyperOS 3)
 * and there was no stack trace to read: Android's own exit record ([ProcessExitReport]) names a crash but carries no
 * Java trace. An uncaught exception now leaves a short, scrubbed report in the app's private files; the next launch asks
 * "MeshLive closed unexpectedly. Share a crash report?" ([CrashReportActivity]) and Export diagnostics includes it for
 * [KEEP_MS]. Nothing is sent anywhere by the app.
 *
 * What a report holds: exception classes, scrubbed messages, stack frames (code locations), the thread name, the app
 * version, Android version and phone model (Export diagnostics already lists those). What it never holds: names,
 * messages, keys, node or stream IDs, addresses, file names or numbers; [scrub] replaces anything that looks like one.
 */
internal object CrashReports {
    private const val DIR = "crash_reports"
    private const val PENDING = "pending.txt"
    private const val SHOWN = "shown.txt"
    private const val PREFS = "crash_reports"
    private const val KEY_ENABLED = "enabled"
    const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_FRAMES = 40
    const val MAX_CAUSES = 5
    const val MAX_MESSAGE = 200
    const val MAX_REPORT_CHARS = 16_000

    /** DEBUG launch override (`test_crash_reports=false`); release builds follow the user's choice only. */
    @Volatile var debugDisabled: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR)

    fun enabled(context: Context): Boolean = !debugDisabled &&
        runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true) }
            .getOrDefault(true)

    /** "Don't ask again": no more reports are written or offered, and the kept ones are deleted. */
    fun disable(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        }
        discard(dir(context))
    }

    /** Installs the handler once per process (the application's onCreate, before any screen or service). */
    fun install(context: Context) {
        if (!enabled(context)) return
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(dir(context), current, ::deviceHeader))
    }

    /** Writes the first uncaught exception of the process, then hands it to the previous handler (Android's crash path). */
    class Handler(
        private val dir: File,
        private val previous: Thread.UncaughtExceptionHandler?,
        private val header: () -> List<Pair<String, String>>,
        private val nowMs: () -> Long = System::currentTimeMillis,
    ) : Thread.UncaughtExceptionHandler {
        private val written = AtomicBoolean(false)

        override fun uncaughtException(thread: Thread, error: Throwable) {
            if (!debugDisabled && written.compareAndSet(false, true)) {
                runCatching { writePending(dir, format(thread.name, error, nowMs(), header())) }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** The report text. Pure, so the scrubbing and the limits are tested on the JVM. */
    fun format(threadName: String, error: Throwable, nowMs: Long, header: List<Pair<String, String>>): String {
        val out = StringBuilder()
        out.append("crash_report: v1\n")
        out.append("time_utc: ").append(utcMinute(nowMs)).append('\n')
        header.forEach { (key, value) -> out.append(key).append(": ").append(scrub(value)).append('\n') }
        out.append("thread: ").append(scrub(threadName)).append('\n')
        var cause: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (cause != null && depth <= MAX_CAUSES && seen.add(cause)) {
            if (depth > 0) out.append("Caused by: ")
            out.append(cause.javaClass.name)
            cause.message?.let { out.append(": ").append(scrub(it).take(MAX_MESSAGE)) }
            out.append('\n')
            val frames = cause.stackTrace
            frames.take(MAX_FRAMES).forEach { out.append("  at ").append(it.toString()).append('\n') }
            if (frames.size > MAX_FRAMES) out.append("  ... ").append(frames.size - MAX_FRAMES).append(" more\n")
            cause = cause.cause
            depth++
        }
        return out.toString().take(MAX_REPORT_CHARS)
    }

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""https?://\S+""") to "<url>",
        Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""") to "<email>",
        Regex("""\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b""") to "<uuid>",
        Regex("""\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b""") to "<address>",
        Regex("""(?:/storage|/sdcard|/data/user|/data/data|content:/)\S*""") to "<path>",
        Regex(""""[^"\n]*"""") to "\"<text>\"",
        Regex("""'[^'\n]*'""") to "'<text>'",
        Regex("""\b\d{7,}\b""") to "<number>",
        Regex("""\b[0-9A-Fa-f]{8,}\b""") to "<hex>",
        Regex("""(?=[A-Za-z0-9+/=_-]*\d)[A-Za-z0-9+/=_-]{20,}""") to "<data>",
    )

    /** Replaces anything that could identify a person, a phone, a peer or a key. */
    fun scrub(text: String): String = rules.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }

    fun writePending(dir: File, report: String) {
        dir.mkdirs()
        File(dir, PENDING).writeText(report)
    }

    /** The report waiting for the next-launch prompt, or null. */
    fun pending(dir: File): String? = runCatching { File(dir, PENDING).takeIf { it.isFile }?.readText() }.getOrNull()

    /** The prompt was answered: the report stays for Export diagnostics until [KEEP_MS]. */
    fun markShown(dir: File) {
        runCatching {
            val pending = File(dir, PENDING)
            if (pending.isFile) {
                val shown = File(dir, SHOWN)
                shown.delete()
                if (!pending.renameTo(shown)) pending.delete()
            }
        }
    }

    /** The newest report younger than [KEEP_MS] (pending or already shown), for Export diagnostics; older ones are deleted. */
    fun latest(dir: File, nowMs: Long = System.currentTimeMillis()): String? = runCatching {
        listOf(File(dir, PENDING), File(dir, SHOWN)).filter { it.isFile }.forEach {
            if (nowMs - it.lastModified() > KEEP_MS) it.delete()
        }
        listOf(File(dir, PENDING), File(dir, SHOWN)).filter { it.isFile }.maxByOrNull { it.lastModified() }?.readText()
    }.getOrNull()

    /**
     * The Export diagnostics text when the diagnostics builder itself failed: the failure and, like the full text, the
     * newest crash report of the last 7 days (it is the reason the user is exporting at all). Shared by Export diagnostics
     * and the crash prompt's Share.
     */
    fun diagnosticsFallback(error: Throwable, dir: File, nowMs: Long = System.currentTimeMillis()): String =
        "Diagnostics failed: ${error.javaClass.simpleName}" + (latest(dir, nowMs)?.let { "\n\nlast_crash:\n$it" } ?: "")

    fun discard(dir: File) {
        runCatching {
            File(dir, PENDING).delete()
            File(dir, SHOWN).delete()
        }
    }

    /**
     * Whether this launch should hand over to the prompt: reports on, one pending, and a plain launch. A benchmark or debug
     * launch (any `test_` or `debug_` extra) keeps the report for Export diagnostics but is not interrupted.
     */
    fun shouldPrompt(enabled: Boolean, pendingExists: Boolean, extraKeys: Set<String>): Boolean =
        enabled && pendingExists && extraKeys.none { it.startsWith("test_") || it.startsWith("debug_") }

    private fun deviceHeader(): List<Pair<String, String>> = listOf(
        "app_version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    private fun utcMinute(nowMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
}
