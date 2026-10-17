package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanHealthTest {
    private val health = ScanHealth()

    private fun inputs(
        now: Long,
        peerExpected: Boolean = true,
        screenOn: Boolean = true,
        idle: Boolean = false,
        blocked: String? = null,
        sdk: Int = 34,
        scanAge: Long = 60_000L,
        oem: Boolean = false,
        renewalPending: Boolean = false,
        slot: Boolean = true,
        restart: Boolean = true,
        quotaFull: Boolean = false,
    ) = ScanHealthInputs(
        nowMs = now, scanning = true, scanAgeMs = scanAge, screenOn = screenOn, deviceIdle = idle,
        blockedReason = blocked, sdk = sdk, peerExpected = peerExpected, oemAggressive = oem,
        renewalPending = renewalPending, gateHasSlot = slot, restartEnabled = restart,
        quotaWindowFull = quotaFull,
    )

    private fun stale(d: ScanHealthDecision) = d as ScanHealthDecision.Stale

    @Test
    fun silenceBeyondTheThresholdWithAPeerExpectedIsStale() {
        health.onScanStart(0L)
        health.onResult(10_000L)
        assertEquals(ScanHealthDecision.Healthy, health.evaluate(inputs(129_999L)))
        val d = stale(health.evaluate(inputs(130_000L)))
        assertEquals(120_000L, d.silentMs)
        assertEquals(120_000L, d.thresholdMs)
        assertEquals("requested", d.action)
        assertTrue(d.restart)
    }

    @Test
    fun aFreshScanStartCountsAsAReferenceLikeBitchats() {
        health.onResult(0L)
        health.onScanStart(100_000L)
        // 130 s after the last result but only 30 s after the scan began: not stale yet.
        assertEquals(ScanHealthDecision.Healthy, health.evaluate(inputs(130_000L)))
        assertTrue(health.evaluate(inputs(220_000L)) is ScanHealthDecision.Stale)
    }

    @Test
    fun noEventAndNoRestartWhenNoPeerIsExpected() {
        health.onScanStart(0L)
        val d = health.evaluate(inputs(1_000_000L, peerExpected = false))
        assertEquals(ScanHealthDecision.Waiting("no_peer_expected"), d)
        // A scan that is not running is not judged either.
        val off = inputs(1_000_000L).copy(scanning = false)
        assertEquals(ScanHealthDecision.Waiting("not_scanning"), health.evaluate(off))
        // Before any reference there is nothing to measure.
        assertEquals(ScanHealthDecision.Waiting("no_reference"), ScanHealth().evaluate(inputs(1_000_000L)))
    }

    @Test
    fun peerWindowFollowsTheLastResult() {
        assertFalse(health.sawPeerRecently(0L))
        health.onResult(1_000L)
        assertTrue(health.sawPeerRecently(1_000L + ScanHealth.EXPECT_WINDOW_MS))
        assertFalse(health.sawPeerRecently(1_001L + ScanHealth.EXPECT_WINDOW_MS))
    }

    @Test
    fun thresholdDoublesPerUnansweredVerdictToTenMinutesAndAResultResetsIt() {
        health.onScanStart(0L)
        var now = 0L
        val thresholds = ArrayList<Long>()
        repeat(6) {
            now += health.currentThresholdMs
            val d = stale(health.evaluate(inputs(now)))
            thresholds += d.thresholdMs
        }
        assertEquals(listOf(120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L), thresholds)
        health.onResult(now + 1L)
        assertEquals(120_000L, health.currentThresholdMs)
        assertEquals(ScanHealthDecision.Healthy, health.evaluate(inputs(now + 2L)))
    }

    @Test
    fun theVerdictMeasuresFromTheLastVerdictNotFromTheOriginalSilence() {
        health.onScanStart(0L)
        assertTrue(health.evaluate(inputs(120_000L)) is ScanHealthDecision.Stale)
        // The next stale one is 240 s after the first verdict, not at 130 s.
        assertEquals(ScanHealthDecision.Healthy, health.evaluate(inputs(300_000L)))
        assertTrue(health.evaluate(inputs(360_000L)) is ScanHealthDecision.Stale)
    }

    @Test
    fun restartIsRequestedOnlyWhenTheArmIsOnAndTheGateHasASlot() {
        health.onScanStart(0L)
        assertEquals("logged_only", stale(health.evaluate(inputs(120_000L, restart = false))).action)

        val h2 = ScanHealth()
        h2.onScanStart(0L)
        val quota = stale(h2.evaluate(inputs(120_000L, slot = false)))
        assertEquals("deferred_quota", quota.action)
        assertFalse(quota.restart)
        // A deferred verdict does not grow the threshold: it asks again at the next check.
        assertEquals(120_000L, h2.currentThresholdMs)
        assertEquals("requested", stale(h2.evaluate(inputs(150_000L, slot = true))).action)

        val h3 = ScanHealth()
        h3.onScanStart(0L)
        val renewing = stale(h3.evaluate(inputs(120_000L, renewalPending = true)))
        assertEquals("deferred_renewal", renewing.action)
        assertEquals(120_000L, h3.currentThresholdMs)
    }

    @Test
    fun causeClassificationTruthTable() {
        val base = inputs(0L)
        assertEquals(ScanSuspect.LOCATION_OFF, ScanHealth.classify(base.copy(blockedReason = ScanEnvironment.REASON_LOCATION_OFF, deviceIdle = true)))
        assertEquals(ScanSuspect.DOZE, ScanHealth.classify(base.copy(deviceIdle = true, screenOn = false)))
        // The stack drops a start over its quota silently: a silent scan with a full budget says so.
        assertEquals(ScanSuspect.QUOTA_SILENT, ScanHealth.classify(base.copy(quotaWindowFull = true)))
        assertEquals("scan_quota_silent", ScanSuspect.QUOTA_SILENT.label)
        assertEquals(ScanSuspect.DOZE, ScanHealth.classify(base.copy(quotaWindowFull = true, deviceIdle = true)))
        assertEquals(ScanSuspect.TIMEOUT_WINDOW, ScanHealth.classify(base.copy(sdk = 34, scanAgeMs = 10 * 60_000L)))
        assertEquals(ScanSuspect.NONE, ScanHealth.classify(base.copy(sdk = 33, scanAgeMs = 10 * 60_000L)))
        assertEquals(ScanSuspect.TIMEOUT_WINDOW, ScanHealth.classify(base.copy(sdk = 33, scanAgeMs = 30 * 60_000L)))
        assertEquals(ScanSuspect.OEM_PAUSE, ScanHealth.classify(base.copy(screenOn = false, oemAggressive = true)))
        assertEquals(ScanSuspect.BACKGROUND, ScanHealth.classify(base.copy(screenOn = false)))
        assertEquals(ScanSuspect.NONE, ScanHealth.classify(base))
        // A fault with the screen on and a fresh scan is the one S-06 may act on.
        assertEquals("none", ScanSuspect.NONE.label)
    }

    @Test
    fun addressRotationPausesAndPeerAddressChangesDoNotTripTheWatchdog() {
        // Android 11+ rotates the own address every 7-15 min and pauses scanning for milliseconds; peers' addresses
        // change too. The heartbeat is any matching result whatever its address, and the threshold is 120 s.
        health.onScanStart(0L)
        var t = 0L
        repeat(60) {
            t += 20_000L // one sighting every 20 s, each from a new rotated address (the watchdog never sees addresses)
            health.onResult(t)
            assertEquals(ScanHealthDecision.Healthy, health.evaluate(inputs(t + 10_000L)))
        }
        // Even a 100 s gap with no result (rotation + a quiet neighbourhood) stays under the first threshold.
        assertEquals(ScanHealthDecision.Healthy, health.evaluate(inputs(t + 100_000L)))
        assertTrue(ScanHealth.DEFAULT_STALE_MS >= 10 * 1_000L) // far above any rotation pause (tens of ms)
    }

    @Test
    fun oemListIsLowercaseManufacturerNamesAndSamsungIsNotInIt() {
        assertTrue(ScanHealth.oemAggressive("Xiaomi"))
        assertTrue(ScanHealth.oemAggressive(" vivo "))
        assertFalse(ScanHealth.oemAggressive("samsung"))
        assertFalse(ScanHealth.oemAggressive("Google"))
    }

    @Test
    fun logLineCarriesTheSuspectedCauseAndTheAction() {
        health.onScanStart(0L)
        val i = inputs(120_000L, screenOn = false, idle = true, restart = false)
        val line = ScanHealth.line(i, stale(health.evaluate(i)))
        assertTrue(line, line.startsWith("scan_stale: suspected=doze | silentMs=120000 | thresholdMs=120000"))
        assertTrue(line, line.contains("screenOn=false | idle=true | sdk=34 | location=ok | peerExpected=true | action=logged_only"))
    }
}
