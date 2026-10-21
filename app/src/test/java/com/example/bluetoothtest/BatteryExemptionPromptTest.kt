package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryExemptionPromptTest {
    private val day = BatteryExemptionPolicy.ROLE_REOFFER_MS
    private val never = BatteryOfferRecord(0, 0L)

    private fun offer(trigger: BatteryOfferTrigger, exempt: Boolean = false, record: BatteryOfferRecord = never, now: Long = 10 * day) =
        BatteryExemptionPolicy.shouldOffer(trigger, exempt, record, now)

    @Test
    fun anExemptAppIsNeverOffered() {
        BatteryOfferTrigger.entries.forEach { assertFalse(it.name, offer(it, exempt = true)) }
    }

    @Test
    fun onboardingOffersOnce() {
        assertTrue(offer(BatteryOfferTrigger.ONBOARDING))
        assertFalse(offer(BatteryOfferTrigger.ONBOARDING, record = BatteryOfferRecord(1, 5 * day)))
    }

    @Test
    fun settingsAlwaysOffersWhenNotExempt() {
        assertTrue(offer(BatteryOfferTrigger.SETTINGS))
        assertTrue(offer(BatteryOfferTrigger.SETTINGS, record = BatteryOfferRecord(5, 10 * day - 1)))
    }

    @Test
    fun turningARoleOnReoffersAfterADayButDoesNotNag() {
        // Skipped in onboarding without an offer being recorded: the first role toggle offers at once.
        assertTrue(offer(BatteryOfferTrigger.ALWAYS_ON_ROLE))
        val justOffered = BatteryOfferRecord(1, 10 * day - 1_000)
        assertFalse(offer(BatteryOfferTrigger.ALWAYS_ON_ROLE, record = justOffered))
        assertFalse(offer(BatteryOfferTrigger.ALWAYS_ON_ROLE, record = BatteryOfferRecord(1, 9 * day + 1)))
        assertTrue(offer(BatteryOfferTrigger.ALWAYS_ON_ROLE, record = BatteryOfferRecord(1, 9 * day)))
    }

    @Test
    fun recordingAnOfferCountsAndStamps() {
        val next = BatteryExemptionPolicy.afterOffer(BatteryOfferRecord(2, 5), 99)
        assertEquals(BatteryOfferRecord(3, 99), next)
    }

    @Test
    fun theRequestIntentIsTheSystemOneTapPromptForThisPackage() {
        val spec = BatteryExemptionPrompt.directSpec("com.example.bluetoothtest")
        assertEquals("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", spec.action)
        assertEquals("package:com.example.bluetoothtest", spec.dataUri)
    }

    @Test
    fun theFallbackIsTheBatteryOptimisationListScreen() {
        assertEquals("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", BatteryExemptionPrompt.listSpec.action)
        assertNull(BatteryExemptionPrompt.listSpec.dataUri)
    }
}
