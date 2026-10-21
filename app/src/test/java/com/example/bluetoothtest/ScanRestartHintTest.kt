package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * win-userflow qw-packaged redmi-failfast-3 (Redmi, Android 11): the Nearby scanner was refused a registration
 * (`MAX client reached: 32`, scan error 2) on every retry while the list kept saying "No one found yet".
 */
class ScanRestartHintTest {
    private var now = 1_000L
    private lateinit var policy: PoolExhaustionPolicy
    private var changes = 0

    @Before
    fun setUp() {
        now = 1_000L
        changes = 0
        GattSwitches.resetToDefaults()
        policy = PoolExhaustionPolicy { now }
        GattPoolBackoff.resetForTest(PoolBackoff({ now }), policy)
        GattPoolBackoff.onScannerChanged = { changes++ }
    }

    @After
    fun tearDown() {
        GattPoolBackoff.onScannerChanged = null
        GattSwitches.resetToDefaults()
        GattPoolBackoff.resetForTest()
    }

    private fun scannerRefused(times: Int) = repeat(times) {
        GattPoolBackoff.recordFailure(PoolSource.SCANNER, ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED, "scanner-x")
        now += 30_000
    }

    @Test
    fun threeScannerRefusalsInARowShowTheRestartHint() {
        scannerRefused(2)
        assertFalse(ScanRestartHint.due(policy, on = true))
        scannerRefused(1)
        assertTrue(ScanRestartHint.due(policy, on = true))
    }

    @Test
    fun theScannersFirstResultTakesTheHintAway() {
        scannerRefused(3)
        assertTrue(ScanRestartHint.due(policy, on = true))
        GattPoolBackoff.recordSuccess(PoolSource.SCANNER)
        assertFalse(ScanRestartHint.due(policy, on = true))
    }

    @Test
    fun serverOrClientRefusalsAloneDoNotClaimTheSearchIsBroken() {
        repeat(3) {
            GattPoolBackoff.recordFailure(PoolSource.SERVER, null, "gatt-video-server")
            GattPoolBackoff.recordFailure(PoolSource.CLIENT, 257, "client")
            now += 30_000
        }
        assertTrue("the reliability card still asks for a restart", policy.restartGuidanceDue())
        assertFalse(ScanRestartHint.due(policy, on = true))
    }

    @Test
    fun oldRefusalsFadeAfterTwoMinutes() {
        scannerRefused(3)
        now += PoolExhaustionPolicy.REGISTRATION_STALE_MS
        assertFalse(ScanRestartHint.due(policy, on = true))
    }

    @Test
    fun theControlArmKeepsTheOrdinaryEmptyState() {
        scannerRefused(3)
        assertFalse(ScanRestartHint.due(policy, on = false))
    }

    @Test
    fun theListIsToldAboutEveryScannerStepAndTheRecoveryButNotAboutOtherSources() {
        scannerRefused(2)
        assertEquals(2, changes)
        GattPoolBackoff.recordFailure(PoolSource.SERVER, null, "gatt-video-server")
        assertEquals(2, changes)
        GattPoolBackoff.recordSuccess(PoolSource.SCANNER)
        assertEquals(3, changes)
        // A success with nothing to recover from is not a change.
        GattPoolBackoff.recordSuccess(PoolSource.SCANNER)
        assertEquals(3, changes)
    }

    @Test
    fun aFailingListenerCannotBreakTheScannerPath() {
        GattPoolBackoff.onScannerChanged = { throw IllegalStateException("activity gone") }
        scannerRefused(1)
        GattPoolBackoff.recordSuccess(PoolSource.SCANNER)
        assertFalse(GattPoolBackoff.scannerFailing)
    }
}
