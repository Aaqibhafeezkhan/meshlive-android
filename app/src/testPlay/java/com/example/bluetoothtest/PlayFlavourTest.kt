package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Google Play build (docs/dev/architecture.md "Distribution and releases"): Play updates it, it can share the app nearby like the other builds (outbound sharing is not self-updating) and never asks for the location type. */
class PlayFlavourTest {
    @Test
    fun playBuildHasNoUpdaterAndNoLocationServiceTypeButCanShareTheApp() {
        assertEquals("play", BuildConfig.DISTRIBUTION)
        assertFalse(FlavourUpdater.available)
        assertFalse(UpdateFeatures.all(BuildConfig.UPDATER_AVAILABLE).any { it.id == "check_for_updates" })
        // "Share app nearby" follows the user's choice, as in the GitHub build.
        assertTrue(OfflineAppShareFeature.resolve(true, { true }, { true }))
        assertFalse(OfflineAppShareFeature.resolve(false, { false }, { false }))
        // Android 10 and 11 with every other condition met: still no location type (the manifest declares none).
        assertFalse(ServiceTypePolicy.locationTypeWanted(29, true, true, true, true, true))
        assertFalse(ServiceTypePolicy.locationTypeWanted(30, true, true, true, true, true))
    }

    @Test
    fun playBuildKeepsTheSharedApplicationId() {
        // The Play app id is permanent once the first bundle is uploaded.
        assertEquals("io.github.meshlive.app", BuildConfig.APPLICATION_ID)
    }
}
