package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CoC and Classic batch's switches (CocSwitches.kt) and registry entries (CocPolicy.kt). */
class CocPolicyTest {
    @After
    fun restoreDefaults() = CocSwitches.configure()

    @Test
    fun theShippedDefaultsBoundTheConnectAndOnlyLogTheClassicCapButRunNoOtherExperiment() {
        CocSwitches.configure()
        assertTrue("Q49: the MTU prime is default on (registry limits it to fresh-ACL centrals)", CocSwitches.mtuPrime)
        assertFalse("Experimental: default off", CocSwitches.echoVerify)
        assertEquals(12_000L, CocSwitches.connectBoundMs)
        assertEquals("refusal is an Experimental arm, off by default", 0, CocSwitches.classicRfcommCap)
    }

    @Test
    fun aDebugLaunchSetsTheArmsAndTheNextLaunchStartsFromTheDefaults() {
        CocSwitches.configure(mtuPrime = true, echoVerify = true, connectBoundMs = 3_000L, classicRfcommCap = 2, debugBuild = true)
        assertTrue(CocSwitches.mtuPrime)
        assertTrue(CocSwitches.echoVerify)
        assertEquals(3_000L, CocSwitches.connectBoundMs)
        assertEquals(2, CocSwitches.classicRfcommCap)
        CocSwitches.configure(mtuPrime = false, debugBuild = true)
        assertFalse("test_coc_mtu_prime=false is the DEBUG control arm", CocSwitches.mtuPrime)
        CocSwitches.configure(debugBuild = true)
        assertTrue("a stored control arm never leaks into the next launch", CocSwitches.mtuPrime)
        assertFalse("a stored choice never leaks into the next launch", CocSwitches.echoVerify)
        assertEquals(12_000L, CocSwitches.connectBoundMs)
        CocSwitches.configure(connectBoundMs = 0L, classicRfcommCap = 0, debugBuild = true)
        assertEquals("0 turns the bound off", 0L, CocSwitches.connectBoundMs)
        assertEquals("0 turns the cap off", 0, CocSwitches.classicRfcommCap)
    }

    @Test
    fun aReleaseBuildIgnoresEveryExtra() {
        CocSwitches.configure(mtuPrime = false, echoVerify = true, connectBoundMs = 0L, classicRfcommCap = 0, debugBuild = false)
        assertTrue("a release build cannot turn the default-on prime off by extra", CocSwitches.mtuPrime)
        assertFalse(CocSwitches.echoVerify)
        assertEquals(12_000L, CocSwitches.connectBoundMs)
        assertEquals(0, CocSwitches.classicRfcommCap)
    }

    @Test
    fun nonsenseValuesFallBackOrClamp() {
        CocSwitches.configure(connectBoundMs = -5L, classicRfcommCap = -1, debugBuild = true)
        assertEquals(12_000L, CocSwitches.connectBoundMs)
        assertEquals(0, CocSwitches.classicRfcommCap)
        CocSwitches.configure(connectBoundMs = 10_000_000L, classicRfcommCap = 500, debugBuild = true)
        assertEquals(CocSwitches.MAX_CONNECT_BOUND_MS, CocSwitches.connectBoundMs)
        assertEquals(CocSwitches.MAX_CLASSIC_RFCOMM_CAP, CocSwitches.classicRfcommCap)
        assertTrue("mtuPrime=true" in CocSwitches.label())
    }

    @Test
    fun everyEntryMeetsTheStrictRules() {
        val problems = CocPolicy.entries.flatMap { entry ->
            DevicePolicyEntryRules.violations(entry, strict = true).map { "${entry.id}: $it" }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
        assertEquals(CocPolicy.entries.size, CocPolicy.entries.map { it.id }.toSet().size)
        CocPolicy.entries.forEach {
            assertEquals("test_device_policy_off=${it.id}", it.offSwitch)
            assertTrue("${it.id} states its fallback", it.fallback.isNotBlank())
        }
    }

    @Test
    fun eachIdIsListedExactlyOnceAndTheOldProposalStaysInertWithItsPremiseCorrected() {
        val registry = DevicePolicyRegistry.entries.map { it.id }
        CocPolicy.entries.forEach { entry -> assertEquals(entry.id, 1, registry.count { it == entry.id }) }
        val old = DevicePolicyRegistry.entries.first { it.id == "android15-qcom-coc-connect-stall" }
        assertTrue("still an inert proposal", old.proposed)
        assertTrue("superseded by the bounded connect, 30 s premise corrected", "coc-connect-bounded" in old.behaviour && "60 s" in old.behaviour)
    }

    @Test
    fun experimentsAreMarkedExperimentalAndTheProposalHasNoEffects() {
        assertEquals("Q49: promoted from Experimental on measured evidence", PolicyValidation.LOCALLY_REPRODUCED, CocPolicy.COC_MTU_EXCHANGE_BEFORE_TRAFFIC.validation)
        assertEquals(PolicyValidation.EXPERIMENTAL, CocPolicy.COC_ECHO_VERIFICATION.validation)
        assertTrue(CocPolicy.ANDROID16_BLUETOOTH_SOCKET_SETTINGS.proposed)
        assertTrue(CocPolicy.ANDROID16_BLUETOOTH_SOCKET_SETTINGS.effects.isEmpty())
        assertTrue("60 s" in CocPolicy.COC_CONNECT_BOUNDED.sources.joinToString { it.note })
    }

    @Test
    fun matchersFollowTheBacklog() {
        fun active(sdk: Int, off: Set<String> = emptySet()) =
            CocPolicy.resolve(DeviceTraits("qcom", "x", sdk), off).activeIds.toSet()
        assertTrue("coc-connect-bounded" in active(29) && "coc-connect-bounded" in active(36))
        assertFalse("error codes exist from Android 14", "coc-socket-error-codes" in active(33))
        assertTrue("coc-socket-error-codes" in active(34))
        assertTrue("coc-android10-address-type-precondition" in active(29))
        assertFalse("coc-android10-address-type-precondition" in active(30))
        assertTrue("coc-credit-stall-classification" in active(33))
        assertFalse("coc-credit-stall-classification" in active(34))
        assertTrue("classic-rfcomm-peer-cap" in active(31))
        val a36 = CocPolicy.resolve(DeviceTraits("qcom", "x", 36), emptySet())
        assertEquals(listOf("android16-bluetooth-socket-settings-coc"), a36.matchingProposals.map { it.id })
        assertFalse("a proposal is never active", "android16-bluetooth-socket-settings-coc" in a36.activeIds)
    }

    @Test
    fun theOffSwitchesRemoveEachEntryAndAllRemovesEverything() {
        val traits = DeviceTraits("qcom", "x", 34)
        CocPolicy.entries.filterNot { it.proposed }.filter { it.matcher.matches(traits) }.forEach { entry ->
            val resolved = CocPolicy.resolve(traits, setOf(entry.id))
            assertFalse(entry.id, entry.id in resolved.activeIds)
            assertEquals(listOf(entry.id), resolved.switchedOff.map { it.id })
        }
        assertTrue(CocPolicy.resolve(traits, setOf("all")).active.isEmpty())
    }

    @Test
    fun theCreditStallReasonMovesAnL2capSessionToGattWithTheFlagOnAndNeverWithItOff() {
        val ledger = CocRegistrationLedger()
        val session = CocAutoGattSession(ledger)
        val l2cap = BluetoothTransportOption.BLE_L2CAP
        assertNull(session.onCocFailure(l2cap, CocAutoGattSettings(enabled = false), CocFallbackReason.CREDIT_STALL))
        val choice = session.onCocFailure(l2cap, CocAutoGattSettings(enabled = true), CocFallbackReason.CREDIT_STALL)
        assertNotNull(choice)
        assertEquals(BluetoothTransportOption.BLE_GATT, choice!!.effective)
        assertEquals(CocFallbackReason.CREDIT_STALL, choice.reason)
        assertTrue(choice.logLine(), "reason=credit_stall" in choice.logLine())
        assertNotNull(choice.notice)
    }
}
