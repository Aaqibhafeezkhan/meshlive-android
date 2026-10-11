package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHelperPolicyTest {
    private val ready = RelayHelperInputs(
        featureOn = true, helpOn = true, rotatingIdsOn = false, leTransport = true, bluetoothReady = true,
        callReady = true, batteryPercent = 80, charging = false, batterySaver = false, thermalSevere = false,
        ownActivity = false, screenOn = true,
    )

    @Test
    fun helpingRunsWhenEverythingIsReady() {
        val plan = RelayHelperPolicy.plan(ready)
        assertTrue(plan.helping)
        assertEquals(HelperPause.NONE, plan.pause)
        assertEquals(HelperScan.BALANCED, plan.scan)
        assertEquals("Helping: on", RelayHelperPolicy.statusLine(plan, 80))
    }

    @Test
    fun eachPauseReasonStopsHelping() {
        fun reason(change: (RelayHelperInputs) -> RelayHelperInputs) = RelayHelperPolicy.plan(change(ready))
        assertEquals(HelperPause.FEATURE_OFF, reason { it.copy(featureOn = false) }.pause)
        assertEquals(HelperPause.HELP_OFF, reason { it.copy(helpOn = false) }.pause)
        assertEquals(HelperPause.ROTATING_IDS, reason { it.copy(rotatingIdsOn = true) }.pause)
        assertEquals(HelperPause.CLASSIC_TRANSPORT, reason { it.copy(leTransport = false) }.pause)
        assertEquals(HelperPause.BLUETOOTH, reason { it.copy(bluetoothReady = false) }.pause)
        assertEquals(HelperPause.OWN_ACTIVITY, reason { it.copy(ownActivity = true) }.pause)
        assertEquals(HelperPause.NOT_CALL_READY, reason { it.copy(callReady = false) }.pause)
        assertEquals(HelperPause.BATTERY_SAVER, reason { it.copy(batterySaver = true) }.pause)
        assertEquals(HelperPause.BATTERY, reason { it.copy(batteryPercent = 19) }.pause)
        assertEquals(HelperPause.THERMAL, reason { it.copy(thermalSevere = true) }.pause)
        assertFalse(reason { it.copy(batteryPercent = 5) }.helping)
    }

    @Test
    fun batteryRuleMatchesTheStreamRelayRule() {
        // User decision 2: below 20 % and not charging, or Battery Saver; an unknown level never pauses.
        assertTrue(RelayHelperPolicy.plan(ready.copy(batteryPercent = 20)).helping)
        assertFalse(RelayHelperPolicy.plan(ready.copy(batteryPercent = 19)).helping)
        assertTrue(RelayHelperPolicy.plan(ready.copy(batteryPercent = 5, charging = true)).helping)
        assertTrue(RelayHelperPolicy.plan(ready.copy(batteryPercent = -1)).helping)
        assertFalse(RelayHelperPolicy.plan(ready.copy(batterySaver = true, charging = true)).helping)
    }

    @Test
    fun scanIsLowPowerOnlyWithScreenOffAndNotCharging() {
        assertEquals(HelperScan.LOW_POWER, RelayHelperPolicy.plan(ready.copy(screenOn = false)).scan)
        assertEquals(HelperScan.BALANCED, RelayHelperPolicy.plan(ready.copy(screenOn = false, charging = true)).scan)
        assertEquals(HelperScan.BALANCED, RelayHelperPolicy.plan(ready.copy(screenOn = true)).scan)
    }

    @Test
    fun ownActivityPreemptsARunningRelayAndNothingElseDoes() {
        assertTrue(RelayHelperPolicy.preempts(relaying = true, ownActivityStarting = true))
        assertFalse(RelayHelperPolicy.preempts(relaying = false, ownActivityStarting = true))
        assertFalse(RelayHelperPolicy.preempts(relaying = true, ownActivityStarting = false))
    }

    @Test
    fun statusLinesNameTheReason() {
        assertEquals("Helping: paused, battery 18 %", RelayHelperPolicy.statusLine(RelayHelperPolicy.plan(ready.copy(batteryPercent = 18)), 18))
        assertEquals("Helping: paused (battery saver)", RelayHelperPolicy.statusLine(RelayHelperPolicy.plan(ready.copy(batterySaver = true)), 80))
        assertEquals("Helping: off", RelayHelperPolicy.statusLine(RelayHelperPolicy.plan(ready.copy(helpOn = false)), 80))
    }

    // ----- setup admission and rate limits -----

    private val ok = RelaySetupInputs(
        featureOn = true, helperAllowed = true, paused = false, busy = false, rateLimited = false,
        parentIsSelf = false, parentIsTarget = false, targetIsSelf = false, budget = 2, maxRelays = 2,
        routeAvailable = true,
    )

    @Test
    fun admissionAcceptsAHealthySetupAndRefusesInTheDocumentedOrder() {
        assertNull(RelaySetupAdmission.refusal(ok))
        assertEquals(RelaySetupFailure.REFUSED, RelaySetupAdmission.refusal(ok.copy(featureOn = false)))
        assertEquals(RelaySetupFailure.REFUSED, RelaySetupAdmission.refusal(ok.copy(helperAllowed = false)))
        assertEquals(RelaySetupFailure.REFUSED, RelaySetupAdmission.refusal(ok.copy(paused = true, busy = true)))
        assertEquals(RelaySetupFailure.BUSY, RelaySetupAdmission.refusal(ok.copy(busy = true, rateLimited = true)))
        assertEquals(RelaySetupFailure.REFUSED, RelaySetupAdmission.refusal(ok.copy(rateLimited = true, parentIsSelf = true)))
        assertEquals(RelaySetupFailure.LOOP, RelaySetupAdmission.refusal(ok.copy(parentIsSelf = true)))
        assertEquals(RelaySetupFailure.LOOP, RelaySetupAdmission.refusal(ok.copy(parentIsTarget = true)))
        assertEquals(RelaySetupFailure.LOOP, RelaySetupAdmission.refusal(ok.copy(targetIsSelf = true)))
        assertEquals(RelaySetupFailure.REFUSED, RelaySetupAdmission.refusal(ok.copy(budget = 3)))
        assertEquals(RelaySetupFailure.REFUSED, RelaySetupAdmission.refusal(ok.copy(budget = 0)))
        assertEquals(RelaySetupFailure.NO_ROUTE, RelaySetupAdmission.refusal(ok.copy(routeAvailable = false)))
    }

    @Test
    fun rateLimitsAreThreePerParentAndTenOverallPerMinute() {
        var now = 0L
        val limiter = RelaySetupRateLimiter({ now })
        assertTrue(limiter.tryAdmit("a"))
        assertTrue(limiter.tryAdmit("a"))
        assertTrue(limiter.tryAdmit("a"))
        assertFalse(limiter.tryAdmit("a"))
        assertTrue(limiter.tryAdmit("b"))
        // Free identities get past the per-parent cap but not the overall cap of ten.
        for (i in 0 until 6) assertTrue(limiter.tryAdmit("id$i"))
        assertFalse(limiter.tryAdmit("another"))
        now += 60_000
        assertTrue(limiter.tryAdmit("a"))
        assertTrue(limiter.tryAdmit("another"))
    }

    @Test
    fun rateLimiterMemoryStaysBoundedUnderManyIdentities() {
        var now = 0L
        val limiter = RelaySetupRateLimiter({ now })
        for (i in 0 until 1_000) {
            limiter.tryAdmit("flood$i")
            now += 61_000
        }
        assertTrue(limiter.tryAdmit("honest"))
        assertTrue("tracked ${limiter.trackedParents}", limiter.trackedParents <= 65)
        // Identities that stay active inside one minute are bounded too (the overall cap admits only ten).
        val busy = RelaySetupRateLimiter({ now })
        for (i in 0 until 500) {
            busy.tryAdmit("burst$i")
            now += 10
        }
        assertTrue("tracked ${busy.trackedParents}", busy.trackedParents <= 65)
    }
}
