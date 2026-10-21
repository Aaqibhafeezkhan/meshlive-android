package com.example.bluetoothtest

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitReportTest {
    private fun record(reason: Int, t: Long = 1_000L, description: String = "", session: Boolean = false, pid: Int = 10) =
        ExitRecord(t, pid, reason, description, importance = 125, rssKb = 204_800, sessionActive = session)

    @Test
    fun selectKeepsOnlyNewerRecordsNewestFirst() {
        val records = listOf(
            record(ApplicationExitInfo.REASON_LOW_MEMORY, t = 100),
            record(ApplicationExitInfo.REASON_USER_REQUESTED, t = 300),
            record(ApplicationExitInfo.REASON_EXIT_SELF, t = 200),
        )
        assertEquals(listOf(300L, 200L), ProcessExitReport.select(records, sinceMs = 100).map { it.timestampMs })
        assertEquals(emptyList<ExitRecord>(), ProcessExitReport.select(records, sinceMs = 300))
        assertEquals(listOf(300L, 200L, 100L), ProcessExitReport.select(records, sinceMs = 0).map { it.timestampMs })
    }

    @Test
    fun everyReasonMapsToACause() {
        val expected = mapOf(
            ApplicationExitInfo.REASON_EXIT_SELF to ExitCause.SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED to ExitCause.USER,
            ApplicationExitInfo.REASON_USER_STOPPED to ExitCause.USER,
            ApplicationExitInfo.REASON_LOW_MEMORY to ExitCause.MEMORY_OR_RESOURCES,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to ExitCause.MEMORY_OR_RESOURCES,
            ApplicationExitInfo.REASON_FREEZER to ExitCause.SYSTEM_POLICY,
            ApplicationExitInfo.REASON_OTHER to ExitCause.SYSTEM_POLICY,
            ApplicationExitInfo.REASON_SIGNALED to ExitCause.SYSTEM_POLICY,
            ApplicationExitInfo.REASON_CRASH to ExitCause.APP_FAULT,
            ApplicationExitInfo.REASON_CRASH_NATIVE to ExitCause.APP_FAULT,
            ApplicationExitInfo.REASON_ANR to ExitCause.APP_FAULT,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to ExitCause.PLATFORM_RESTART,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED to ExitCause.PLATFORM_RESTART,
            ApplicationExitInfo.REASON_UNKNOWN to ExitCause.UNKNOWN,
            9999 to ExitCause.UNKNOWN,
        )
        expected.forEach { (reason, cause) -> assertEquals("reason $reason", cause, ExitCause.of(reason)) }
    }

    @Test
    fun theTextNamesWhoStoppedTheAppAndWhetherASessionRan() {
        val texts = listOf(
            record(ApplicationExitInfo.REASON_USER_REQUESTED, session = true).userText(),
            record(ApplicationExitInfo.REASON_LOW_MEMORY).userText(),
            record(ApplicationExitInfo.REASON_FREEZER).userText(),
            record(ApplicationExitInfo.REASON_OTHER).userText(),
            record(12345).userText(),
        )
        texts.forEach { assertTrue(it.isNotBlank()) }
        assertEquals("each cause the user can act on has its own wording", texts.size, texts.toSet().size)
        val described = record(ApplicationExitInfo.REASON_OTHER, description = "MemoryLimiter:AnonSwap").userText()
        assertTrue("the system's own description is shown", described.contains("MemoryLimiter:AnonSwap"))
    }

    @Test
    fun theBenchmarkLineCarriesReasonDescriptionImportanceRssAndSession() {
        val line = record(ApplicationExitInfo.REASON_OTHER, t = 77, description = "killed | by\nvendor", session = true).benchmarkLine()
        assertEquals(
            "process_exit: OTHER | killed / by vendor | importance=125 | rss_kb=204800 | session_active=true | t=77",
            line,
        )
    }

    @Test
    fun theReasonNamesCoverTheTaskManagerAndFreezerCases() {
        assertEquals("USER_REQUESTED", ProcessExitReport.reasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("USER_STOPPED", ProcessExitReport.reasonName(ApplicationExitInfo.REASON_USER_STOPPED))
        assertEquals("FREEZER", ProcessExitReport.reasonName(ApplicationExitInfo.REASON_FREEZER))
        assertEquals("LOW_MEMORY", ProcessExitReport.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("OTHER", ProcessExitReport.reasonName(ApplicationExitInfo.REASON_OTHER))
        assertEquals("REASON_77", ProcessExitReport.reasonName(77))
    }

    @Test
    fun theLastUnexpectedExitSkipsTheAppClosingItself() {
        val newestFirst = listOf(
            record(ApplicationExitInfo.REASON_EXIT_SELF, t = 300),
            record(ApplicationExitInfo.REASON_USER_REQUESTED, t = 200),
            record(ApplicationExitInfo.REASON_LOW_MEMORY, t = 100),
        )
        assertEquals(200L, ProcessExitReport.firstUnexpected(newestFirst)?.timestampMs)
        assertNull(ProcessExitReport.firstUnexpected(listOf(record(ApplicationExitInfo.REASON_EXIT_SELF))))
        assertNull(ProcessExitReport.firstUnexpected(emptyList()))
        // Before recordAtStart ran (and on API 29) the object reports nothing.
        assertTrue(ProcessExitReport.readSince(0).isEmpty())
        assertNull(ProcessExitReport.lastUnexpected())
    }
}
