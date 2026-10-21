package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A public report (Redmi Note 13 Pro 5G, Android 16, HyperOS 3) of force-closes without any stack trace to read. */
class CrashReportsTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("crash-reports").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val header = listOf("app_version" to "0.2.0 (20, release)", "android" to "16 (API 36)", "device" to "Xiaomi 23090RA98G")

    @Test
    fun scrubbingRemovesEverythingThatCouldIdentifySomeone() {
        val raw = "Calling \"Robin Phone\" at 4A:00:00:12:34:56 node 8c3e73a75fd0 key 3q2+7w8vZ1aB4cD5eF6gH7iJ8kL9 " +
            "id 5e9cf4c5-d7f7-4bb7-a49f-53b23f9835ce mail a.b@example.org see https://example.org/x?y=1 " +
            "file /storage/emulated/0/DCIM/me.jpg phone 4915112345678 user 'Alice'"
        val clean = CrashReports.scrub(raw)
        listOf(
            "Robin", "4A:00:00", "8c3e73a75fd0", "3q2+7w8vZ1aB4cD5eF6gH7iJ8kL9", "5e9cf4c5", "a.b@example.org",
            "example.org", "DCIM", "4915112345678", "Alice",
        ).forEach { assertFalse("$it survived: $clean", clean.contains(it)) }
        assertTrue(clean.contains("<address>") && clean.contains("<hex>") && clean.contains("<uuid>") && clean.contains("<number>"))
    }

    @Test
    fun scrubbingKeepsWhatExplainsTheFailure() {
        val text = "Starting FGS with type camera callerApp=ProcessRecord requires permissions: all of " +
            "[android.permission.FOREGROUND_SERVICE_CAMERA] status 133 code 2"
        assertEquals(text, CrashReports.scrub(text))
        assertEquals("Xiaomi 23090RA98G", CrashReports.scrub("Xiaomi 23090RA98G"))
    }

    @Test
    fun theReportHasTheCauseChainFramesAndScrubbedMessagesOnly() {
        val root = IllegalStateException("peer 8c3e73a75fd0 gone")
        val error = SecurityException("Calling \"Bob\" failed", root)
        val report = CrashReports.format("GattVideoAdvertiseBoost", error, 1_792_520_000_000L, header)

        assertTrue(report.startsWith("crash_report: v1\ntime_utc: 2026-10-20T"))
        assertTrue(report.contains("device: Xiaomi 23090RA98G"))
        assertTrue(report.contains("thread: GattVideoAdvertiseBoost"))
        assertTrue(report.contains("java.lang.SecurityException: Calling \"<text>\" failed"))
        assertTrue(report.contains("Caused by: java.lang.IllegalStateException: peer <hex> gone"))
        assertTrue(report.contains("  at com.example.bluetoothtest.CrashReportsTest."))
        assertFalse(report.contains("Bob"))
        assertFalse(report.contains("8c3e73a75fd0"))
    }

    @Test
    fun longTracesAndCauseLoopsAreBounded() {
        val error = RuntimeException("deep")
        error.stackTrace = Array(500) { StackTraceElement("a.B", "m$it", "B.kt", it) }
        val report = CrashReports.format("main", error, 0L, emptyList())
        assertEquals(CrashReports.MAX_FRAMES, report.lines().count { it.startsWith("  at ") })
        assertTrue(report.contains("  ... ${500 - CrashReports.MAX_FRAMES} more"))

        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val looped = CrashReports.format("main", a, 0L, emptyList())
        assertEquals(1, looped.lines().count { it.startsWith("Caused by: ") })
    }

    @Test
    fun theHandlerWritesTheFirstCrashThenHandsItToAndroid() {
        val handed = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, error -> handed += error }
        val handler = CrashReports.Handler(dir, previous, { header }, nowMs = { 0L })
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")

        handler.uncaughtException(Thread.currentThread(), first)
        handler.uncaughtException(Thread.currentThread(), second)

        assertEquals(listOf<Throwable>(first, second), handed)
        val pending = CrashReports.pending(dir)
        assertNotNull(pending)
        assertTrue(pending!!.contains("IllegalStateException: first"))
        assertFalse(pending.contains("second"))
    }

    @Test
    fun aShownReportStaysForExportDiagnosticsForSevenDaysOnly() {
        CrashReports.writePending(dir, "crash_report: v1\n")
        assertNotNull(CrashReports.pending(dir))
        CrashReports.markShown(dir)
        assertNull(CrashReports.pending(dir))
        val now = System.currentTimeMillis()
        assertEquals("crash_report: v1\n", CrashReports.latest(dir, now))
        File(dir, "shown.txt").setLastModified(now - CrashReports.KEEP_MS - 60_000L)
        assertNull(CrashReports.latest(dir, now))
        assertFalse(File(dir, "shown.txt").exists())
    }

    @Test
    fun discardRemovesEveryKeptReport() {
        CrashReports.writePending(dir, "x")
        CrashReports.markShown(dir)
        CrashReports.writePending(dir, "y")
        CrashReports.discard(dir)
        assertNull(CrashReports.latest(dir))
    }

    @Test
    fun onlyAPlainLaunchWithAPendingReportIsInterrupted() {
        assertTrue(CrashReports.shouldPrompt(enabled = true, pendingExists = true, extraKeys = emptySet()))
        assertFalse(CrashReports.shouldPrompt(enabled = false, pendingExists = true, extraKeys = emptySet()))
        assertFalse(CrashReports.shouldPrompt(enabled = true, pendingExists = false, extraKeys = emptySet()))
        assertFalse(CrashReports.shouldPrompt(enabled = true, pendingExists = true, extraKeys = setOf("test_session_role")))
        assertFalse(CrashReports.shouldPrompt(enabled = true, pendingExists = true, extraKeys = setOf("debug_meet")))
    }

    private fun source(path: String): String = listOf(File(path), File("app/$path"), File("../$path"))
        .first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun theHandlerIsInstalledByTheApplicationAndThePromptComesBeforeAnyRadio() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\".MeshLiveApplication\""))
        assertTrue(manifest.contains("android:name=\".CrashReportActivity\""))
        assertTrue(source("src/main/java/com/example/bluetoothtest/MeshLiveApplication.kt").contains("CrashReports.install(this)"))
        val main = source("src/main/java/com/example/bluetoothtest/MainActivity.kt")
        val onCreate = main.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {")
        val prompt = onCreate.indexOf("CrashReports.shouldPrompt(")
        assertTrue(prompt >= 0)
        assertTrue(prompt < onCreate.indexOf("setContentView(R.layout.activity_main)"))
        assertTrue(prompt < onCreate.indexOf("LinkCapacity.attach("))
    }

    @Test
    fun anInstalledHandlerIsNotStackedTwice() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val ours = CrashReports.Handler(dir, original, { header })
            Thread.setDefaultUncaughtExceptionHandler(ours)
            assertSame(ours, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
