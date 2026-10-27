package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry metadata says what a phone actually runs (switch audit 2026-10-27, A5 and A10). */
class DevicePolicyHygieneTest {
    private val android14 = DeviceTraits("qcom", "kalama", 34, manufacturer = "samsung")

    @After
    fun restoreArms() {
        GattSwitches.busyWedgeRecovery = false
    }

    @Test
    fun aSupersededProposalIsNeverReportedAsMatching() {
        val resolved = DeviceFamilyPolicy.resolve(android14, emptySet())
        val proposals = resolved.matchingProposals.map { it.id }
        listOf("ble-scan-start-quota", "android-14-gatt-mtu-517", "background-restriction-visible").forEach { id ->
            assertFalse("$id is a superseded duplicate", id in proposals)
        }
        // Their replacements are the implemented entries.
        assertTrue(resolved.isActive("le-scan-start-quota"))
        assertTrue(resolved.isActive("gatt-mtu-fixed-517"))
    }

    @Test
    fun anEntryWaitingForItsExperimentalArmIsNotReportedActive() {
        val id = "android13-14-gatt-client-wedged-after-busy"
        GattSwitches.busyWedgeRecovery = false
        val off = DeviceFamilyPolicy.resolve(android14, emptySet())
        assertFalse(off.reportedActive.any { it.id == id })
        assertTrue(off.armOff.any { it.id == id })
        val line = DeviceFamilyPolicy.benchmarkLine(off)
        assertFalse(line.substringAfter("active=").substringBefore(" |").split(",").contains(id))
        assertTrue(line.substringAfter("armOff=").substringBefore(" |").split(",").contains(id))

        GattSwitches.busyWedgeRecovery = true // DEBUG unit tests: the arm is settable
        val on = DeviceFamilyPolicy.resolve(android14, emptySet())
        assertTrue(on.reportedActive.any { it.id == id })
        assertFalse(on.armOff.any { it.id == id })
        assertTrue(GattLinkPolicy.wedgeRecovery(on) != null)
    }

    @Test
    fun everyBatchHonoursTheTraitsViewsOwnOffSwitches() {
        val id = "bluetooth-process-restart-recovery"
        assertTrue(ScanPolicy.isOff(id, emptySet(), viewOff = setOf(id)))
        assertTrue(ScanPolicy.isOff(id, emptySet(), viewOff = setOf("all")))
        assertFalse(ScanPolicy.isOff(id, emptySet()))
        val view = android14.copy(policyOff = setOf("coc-echo-verification"))
        assertTrue(CocPolicy.resolve(view, emptySet()).switchedOff.any { it.id == "coc-echo-verification" })
    }

    @Test
    fun bluetoothRestartRecoveryShipsOnAndItsOffSwitchKeepsTheOtherBluetoothOnConsumers() {
        val resolved = DeviceFamilyPolicy.resolve(android14, emptySet())
        assertTrue(resolved.isActive("bluetooth-process-restart-recovery"))
        assertFalse(resolved.matchingProposals.any { it.id == "bluetooth-process-restart-recovery" })

        assertEquals(
            listOf(ScanRuntime.CONSUMER_SCAN_REGISTRY, ScanRuntime.CONSUMER_ADVERTISING_BUDGET, ScanRuntime.CONSUMER_LINK_CHANGE_ROLLBACK),
            ScanRuntime.epochConsumers(emptySet()),
        )
        listOf(setOf("bluetooth-process-restart-recovery"), setOf("all")).forEach { off ->
            assertEquals(
                listOf(ScanRuntime.CONSUMER_ADVERTISING_BUDGET, ScanRuntime.CONSUMER_LINK_CHANGE_ROLLBACK),
                ScanRuntime.epochConsumers(off),
            )
        }
    }
}
