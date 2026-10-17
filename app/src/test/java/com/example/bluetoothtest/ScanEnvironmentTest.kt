package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanEnvironmentTest {
    @After
    fun resetSwitches() {
        DebugTunables.devicePolicyOff = emptySet()
    }

    @Test
    fun blockedReasonTruthTable() {
        // Android 10 (29) and 11 (30): the Location switch, then the permission.
        for (sdk in listOf(29, 30)) {
            assertEquals("location_off", ScanEnvironment.blockedReason(sdk, locationOn = false, hasFine = true))
            assertEquals("location_off", ScanEnvironment.blockedReason(sdk, locationOn = false, hasFine = false))
            assertEquals("fine_location_not_granted", ScanEnvironment.blockedReason(sdk, locationOn = true, hasFine = false))
            assertNull(ScanEnvironment.blockedReason(sdk, locationOn = true, hasFine = true))
        }
        // Android 12+ declares neverForLocation: not affected whatever the switch says.
        for (sdk in listOf(31, 32, 33, 34, 35, 36)) {
            assertNull(ScanEnvironment.blockedReason(sdk, locationOn = false, hasFine = false))
        }
    }

    @Test
    fun theLineAnswersLocationOnAndSdk() {
        val blocked = ScanEnvSnapshot(29, locationEnabled = false, fineLocationGranted = true, neverForLocation = false, blockedReason = "location_off")
        assertEquals(
            "scan_env: locationOn=false | sdk=29 | fineLocation=true | neverForLocation=false | blocked=location_off",
            ScanEnvironment.line(blocked),
        )
        val ok = ScanEnvSnapshot(34, locationEnabled = true, fineLocationGranted = false, neverForLocation = true, blockedReason = null)
        assertTrue(ScanEnvironment.line(ok).endsWith("blocked=none"))
        assertTrue(ScanEnvironment.line(null).contains("locationOn=unknown"))
    }

    @Test
    fun guidanceRunsOnAndroid10To11OnlyAndHonoursTheOffSwitch() {
        assertTrue(ScanEnvironment.guidanceActive(29, emptySet()))
        assertTrue(ScanEnvironment.guidanceActive(30, emptySet()))
        assertFalse(ScanEnvironment.guidanceActive(31, emptySet()))
        assertFalse(ScanEnvironment.guidanceActive(30, setOf("android10-11-location-switch-scan-gate")))
        assertFalse(ScanEnvironment.guidanceActive(29, setOf("all")))
        assertTrue(ScanEnvironment.guidanceActive(29, setOf("some-other-entry")))
    }

    private fun snapshot(blocked: String?) = ScanEnvSnapshot(
        sdk = 30, locationEnabled = blocked == null, fineLocationGranted = true, neverForLocation = false, blockedReason = blocked,
    )

    @Test
    fun watcherFiresOnlyWhenScanningWasBlockedAndIsNot() {
        var current: ScanEnvSnapshot = snapshot("location_off")
        val unblocked = ArrayList<ScanEnvSnapshot>()
        val changed = ArrayList<ScanEnvSnapshot>()
        val watcher = ScanEnvWatcher({ current }, { unblocked += it }, { changed += it })
        watcher.seed(current)
        watcher.onModeChanged() // switch still off: nothing changed
        assertTrue(unblocked.isEmpty() && changed.isEmpty())
        current = snapshot(null)
        watcher.onModeChanged()
        assertEquals(1, unblocked.size)
        assertEquals(1, changed.size)
        watcher.onModeChanged() // repeat broadcast: no second restart
        assertEquals(1, unblocked.size)
        current = snapshot("location_off")
        watcher.onModeChanged() // blocked again: reported, but nothing to restart
        assertEquals(1, unblocked.size)
        assertEquals(2, changed.size)
    }

    @Test
    fun theModeChangedRestartGoesThroughTheScanStartGate() {
        val clock = FakeScanClock()
        val budget = LeScanStartBudget(5, 30_000L)
        val gate = ScanStartGate(clock = { clock.now })
        val registry = ScanRegistryImpl(clock = { clock.now })
        val events = ArrayList<String>()
        registry.register(
            "discovery-a",
            stop = { events += "stop" },
            start = {
                assertEquals(0L, gate.reserve(budget))
                events += "start"
            },
        )
        val renewal = ScanRenewal(registry, gate, { budget }, clock, { clock.now }, sdk = 30, enabled = { false })
        var current = snapshot("location_off")
        val watcher = ScanEnvWatcher({ current }, { renewal.restartAfterEnvironmentChange() })
        watcher.seed(current)

        // Quota full: the unblock restart waits, it does not stack a start.
        repeat(5) { assertEquals(0L, gate.reserve(budget)) }
        current = snapshot(null)
        watcher.onModeChanged()
        clock.advance(10_000L)
        assertTrue(events.isEmpty())

        // A free slot: stop, a 1 s gap, then a gated start.
        clock.advance(30_000L)
        current = snapshot("location_off")
        watcher.onModeChanged()
        current = snapshot(null)
        watcher.onModeChanged()
        assertEquals(listOf("stop"), events)
        clock.advance(ScanRenewalPolicy.STOP_GAP_MS)
        assertEquals(listOf("stop", "start"), events)
        assertEquals(1, gate.used(budget))
    }
}
