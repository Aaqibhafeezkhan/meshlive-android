package com.example.bluetoothtest

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundProbesTest {
    private val day = 24L * 60 * 60 * 1000

    // ---- Phone updated / restarted --------------------------------------------------------------------------

    private fun stamp(fp: String = "fp1", patch: String = "2026-09-01", boot: Int? = 10) = PhoneStamp(fp, patch, boot)

    @Test
    fun theFirstStartOnlyStoresTheStamp() {
        val first = PhoneUpdatePolicy.next(PhoneUpdateRecord(null), stamp(), 1_000L)
        assertEquals(stamp(), first.stamp)
        assertFalse(PhoneUpdatePolicy.updatedRecently(first, 2_000L))
        assertFalse(PhoneUpdatePolicy.restartedRecently(first, 2_000L))
    }

    @Test
    fun aChangedBuildOrPatchIsAnUpdateForAWeek() {
        val stored = PhoneUpdateRecord(stamp())
        for (changed in listOf(stamp(fp = "fp2"), stamp(patch = "2026-10-01"))) {
            val next = PhoneUpdatePolicy.next(stored, changed, 5L * day)
            assertTrue(PhoneUpdatePolicy.updatedRecently(next, 5L * day))
            assertTrue(PhoneUpdatePolicy.updatedRecently(next, 5L * day + 6 * day))
            assertFalse(PhoneUpdatePolicy.updatedRecently(next, 5L * day + 8 * day))
            assertEquals(changed, next.stamp)
        }
    }

    @Test
    fun aNewBootCountIsARestartForTwoDaysAndUnknownCountsAreIgnored() {
        val stored = PhoneUpdateRecord(stamp(boot = 10))
        val restarted = PhoneUpdatePolicy.next(stored, stamp(boot = 11), 3L * day)
        assertTrue(PhoneUpdatePolicy.restartedRecently(restarted, 3L * day + day))
        assertFalse(PhoneUpdatePolicy.restartedRecently(restarted, 3L * day + 3 * day))
        assertFalse(PhoneUpdatePolicy.updatedRecently(restarted, 3L * day))
        // Same boot, or a count that cannot be read: no restart claimed.
        assertEquals(0L, PhoneUpdatePolicy.next(stored, stamp(boot = 10), 3L * day).restartedAtMs)
        assertEquals(0L, PhoneUpdatePolicy.next(stored, stamp(boot = null), 3L * day).restartedAtMs)
        assertEquals(0L, PhoneUpdatePolicy.next(PhoneUpdateRecord(stamp(boot = null)), stamp(boot = 4), 3L * day).restartedAtMs)
    }

    @Test
    fun aLaterStartKeepsTheEarlierUpdateTime() {
        val updated = PhoneUpdatePolicy.next(PhoneUpdateRecord(stamp()), stamp(fp = "fp2"), 2L * day)
        val later = PhoneUpdatePolicy.next(updated, stamp(fp = "fp2"), 3L * day)
        assertEquals(2L * day, later.updatedAtMs)
    }

    // ---- Xiaomi pop-up probe (Experimental) -----------------------------------------------------------------

    @Test
    fun theAppOpsModeMapsToGrantedDeniedOrUnknown() {
        assertEquals(true, MiuiAppOpsProbe.interpret(AppOpsManager.MODE_ALLOWED))
        assertEquals(false, MiuiAppOpsProbe.interpret(AppOpsManager.MODE_IGNORED))
        assertEquals(false, MiuiAppOpsProbe.interpret(AppOpsManager.MODE_ERRORED))
        assertNull(MiuiAppOpsProbe.interpret(AppOpsManager.MODE_DEFAULT))
        assertNull(MiuiAppOpsProbe.interpret(null))
        assertNull(MiuiAppOpsProbe.interpret(99))
    }

    @Test
    fun theProbeFailsClosedWhenTheReaderThrowsOrKnowsNothing() {
        var asked: Triple<Int, Int, String>? = null
        val granted = MiuiAppOpsProbe.backgroundPopupGranted("com.example.bluetoothtest", 10123, AppOpsReader { op, uid, pkg ->
            asked = Triple(op, uid, pkg)
            AppOpsManager.MODE_IGNORED
        })
        assertEquals(false, granted)
        assertEquals(Triple(MiuiAppOpsProbe.OP_BACKGROUND_START_ACTIVITY, 10123, "com.example.bluetoothtest"), asked)
        assertNull(MiuiAppOpsProbe.backgroundPopupGranted("com.example.bluetoothtest", 10123, AppOpsReader { _, _, _ -> throw SecurityException("hidden") }))
        assertNull(MiuiAppOpsProbe.backgroundPopupGranted("com.example.bluetoothtest", 10123, AppOpsReader { _, _, _ -> null }))
    }

    @Test
    fun theProbeSwitchIsOffByDefault() {
        assertFalse(BackgroundSwitchDefaults.MIUI_APPOPS_PROBE)
        assertFalse(BackgroundSwitches.miuiAppOpsProbe)
        assertEquals("test_miui_appops_probe", BackgroundSwitches.EXTRA_MIUI_APPOPS_PROBE)
    }

    // ---- Incoming-call screen watch -------------------------------------------------------------------------

    private class Harness(waitMs: Long = 4_000L) {
        var now = 0L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val reports = mutableListOf<Pair<String, Long>>()
        val watch = IncomingUiWatch(waitMs, { now }, { delay, action -> scheduled += delay to action }, { r, w -> reports += r to w })
    }

    @Test
    fun theCallScreenBeatsTheTimeout() {
        val h = Harness()
        h.watch.onFullScreenIntentPosted()
        assertEquals(4_000L, h.scheduled.single().first)
        h.now = 600
        h.watch.onCallScreenShown()
        assertEquals(listOf("fullscreen" to 600L), h.reports)
        h.now = 4_000
        h.scheduled.single().second() // the timeout fires late: nothing more is reported
        assertEquals(1, h.reports.size)
    }

    @Test
    fun noCallScreenMeansHeadsUpOnly() {
        val h = Harness()
        h.watch.onFullScreenIntentPosted()
        h.now = 4_000
        h.scheduled.single().second()
        assertEquals(listOf("heads_up_only" to 4_000L), h.reports)
        h.watch.onCallScreenShown() // a late screen is not double-counted
        assertEquals(1, h.reports.size)
        assertEquals("incoming_ui: result=heads_up_only | waitedMs=4000", IncomingUiWatch.line("heads_up_only", 4_000L))
    }

    @Test
    fun aSecondCallIgnoresTheFirstTimeout() {
        val h = Harness()
        h.watch.onFullScreenIntentPosted()
        h.now = 1_000
        h.watch.onCallScreenShown()
        h.now = 2_000
        h.watch.onFullScreenIntentPosted()
        h.now = 4_000
        h.scheduled[0].second() // stale timeout of the first call
        assertTrue(h.reports.none { it.first == "heads_up_only" })
        h.now = 6_000
        h.scheduled[1].second()
        assertEquals("heads_up_only", h.reports.last().first)
    }

    // ---- Timer lateness -------------------------------------------------------------------------------------

    @Test
    fun lateTimersAreCountedByHowLateTheyWere() {
        val t = TimerLateness()
        assertEquals(0L, t.onTick(10_000L))
        assertEquals(0L, t.onTick(11_000L))
        assertEquals(300L, t.onTick(12_300L))
        assertEquals(1_500L, t.onTick(14_800L))
        assertEquals(6_000L, t.onTick(21_800L))
        assertEquals(6_000L, t.maxLateMs())
        assertEquals(
            "timer_lateness: ticks=4 | max_late_ms=6000 | last_late_ms=6000 | over_1s=2 | over_5s=1 | period_ms=1000 | doze=deep_idle",
            t.line("deep_idle"),
        )
        // An early tick is not negative lateness.
        assertEquals(0L, t.onTick(22_000L))
    }

    @Test
    fun aLongGapMeansTheHeartbeatWasNotRunningAndOnlyReanchors() {
        val t = TimerLateness(resumeAfterMs = 60_000L)
        t.onTick(0L)
        assertEquals(0L, t.onTick(10 * 60_000L))
        assertEquals(0L, t.maxLateMs())
        assertEquals(500L, t.onTick(10 * 60_000L + 1_500L))
    }

    @Test
    fun resetStartsANewMeasurement() {
        val t = TimerLateness()
        t.onTick(0L)
        t.onTick(3_000L)
        t.reset()
        assertEquals(0L, t.maxLateMs())
        assertTrue(t.line("awake").startsWith("timer_lateness: ticks=0 | max_late_ms=0"))
        assertEquals(0L, t.onTick(100_000L))
    }

    // ---- Low Power Standby exemption (B-08) -----------------------------------------------------------------

    private val standby = DozeSnapshot(deviceIdle = false, lightIdle = false, lowPowerStandby = true, powerSave = false)

    @Test
    fun anExemptAppIsToldItKeepsWorkingDuringLowPowerStandby() {
        val warned = DozePolicy.message(standby)
        assertNotNull(warned)
        assertEquals("a non-exempt app gets the same warning", warned, DozePolicy.message(standby.copy(lowPowerStandbyExempt = false)))
        val exempt = DozePolicy.message(standby.copy(lowPowerStandbyExempt = true))
        assertTrue(exempt!!.isNotBlank())
        assertNotEquals("an exempt app is not told its calls may be delayed", warned, exempt)
        // Deep sleep still outranks the exemption text.
        val deep = DozePolicy.message(standby.copy(deviceIdle = true, lowPowerStandbyExempt = true))
        assertEquals(DozePolicy.message(DeviceSleepState.DEEP_IDLE), deep)
        assertNotEquals(exempt, deep)
        assertNull(DozePolicy.message(standby.copy(lowPowerStandby = false, lowPowerStandbyExempt = true)))
    }

    @Test
    fun theExemptionIsLoggedOnlyWhenKnown() {
        assertFalse(DozePolicy.line(standby, "x").contains("lowPowerStandbyExempt"))
        assertEquals(
            "doze_state: state=low_power_standby | deviceIdle=false | lightIdle=false | lowPowerStandby=true | powerSave=false | " +
                "lowPowerStandbyExempt=true | trigger=x",
            DozePolicy.line(standby.copy(lowPowerStandbyExempt = true), "x"),
        )
        // A changed exemption alone is a reportable change.
        assertTrue(DozePolicy.changed(standby, standby.copy(lowPowerStandbyExempt = true)))
    }
}
