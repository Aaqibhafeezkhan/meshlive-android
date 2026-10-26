package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A release build ignores every launch extra (switch audit 2026-10-27, A1/A2): MainActivity is an exported launcher
 * activity, so any app can start it with `test_*` extras. Each case resolves the launch as a release build would
 * (`debug = false`) and checks the user-facing behaviour stays at its default.
 */
class ReleaseLaunchExtrasTest {
    private val specs: List<FeatureSpec> = CallFeatureSpecs.all + ConnectivityFeatures.all + ViewerFeatures.all +
        UpdateFeatures.all(updaterAvailable = true) + NamingFeatures.all

    /** The launch extras of the feature resolvers, the PHY arms and the automation markers, plus generic runner extras. */
    private val launchExtras: List<String> = (
        specs.mapNotNull { it.benchmarkExtra } +
            PhySwitches.RADIO_ARM_EXTRAS +
            listOf(
                BenchmarkLaunch.EXTRA_USER_LAUNCH,
                BenchmarkLaunch.EXTRA_OFFLINE_BENCHMARK,
                RotatingDiscoveryFeature.EXTRA,
                RelayedCallsFeature.EXTRA,
                CallReachabilityFeature.EXTRA,
                IntercomFeatureSettings.EXTRA_VOICE_CALLS,
                IntercomFeatureSettings.EXTRA_PUSH_TO_TALK,
                IntercomFeatureSettings.EXTRA_PTT_MODE,
                "test_control",
                "test_transport",
                "test_auto_start",
            )
        ).distinct()

    @Test
    fun releaseResolutionOfEveryLaunchExtraKeepsUserDefaults() {
        assertTrue(launchExtras.size > 20)
        launchExtras.forEach { key ->
            listOf(setOf(key), setOf(key, BenchmarkLaunch.EXTRA_USER_LAUNCH)).forEach { keys ->
                val userLaunch = BenchmarkLaunch.EXTRA_USER_LAUNCH in keys
                val benchmark = BenchmarkLaunch.fromExtras(keys, userLaunch, debug = false)
                assertFalse("$keys made a release benchmark launch", benchmark)
                // Onboarding, the go-live disclosure, End-live confirmation, relay battery pause and Recent calls all
                // follow this one predicate; onboarding has its own automation marker too.
                val skips = BenchmarkLaunch.skipsOnboarding(keys, userLaunch, debug = false)
                assertTrue("$keys skipped onboarding", shouldShowOnboarding(false, launchHasExtras = skips, recreated = false))
                assertTrue("$keys flipped the Help relay default", relayDefaultOn(benchmark))
                listOf(true, false).forEach { extraValue ->
                    specs.forEach { spec ->
                        assertEquals(
                            "$keys changed ${spec.id}",
                            spec.defaultEnabled,
                            FeatureCatalog.resolve(spec, benchmark, { extraValue }, { null }),
                        )
                    }
                    assertEquals(
                        RotatingDiscoveryFeature.DEFAULT_ENABLED,
                        RotatingDiscoveryFeature.resolve(benchmark, { extraValue }, { null }),
                    )
                    assertTrue(CallReachabilityFeature.resolve(benchmark, { true }) { extraValue })
                    assertFalse(RelayedCallsFeature.resolve(benchmark, { extraValue }, advancedSettings = false) { false })
                    assertEquals(
                        IntercomFeatureSettings.resolve(false, { false }, { null }),
                        IntercomFeatureSettings.resolve(benchmark, { extraValue }, { null }),
                    )
                }
            }
        }
    }

    @Test
    fun releaseBuildsIgnoreThePhySplitAndPhyAfterAuthArms() {
        val armed = PhySwitches.radioArms(debug = false, string = { "tx1m-rx2m" }, flag = { true })
        assertEquals(PhySwitches.RadioArms(), armed)
    }

    @Test
    fun debugBuildsStillHonourBenchmarkExtrasAndTheUserLaunchEscape() {
        assertTrue(BenchmarkLaunch.fromExtras(setOf("test_gatt_phy_split"), userLaunch = false, debug = true))
        assertFalse(BenchmarkLaunch.fromExtras(setOf("test_gatt_phy_split"), userLaunch = true, debug = true))
        assertTrue(BenchmarkLaunch.skipsOnboarding(setOf(BenchmarkLaunch.EXTRA_OFFLINE_BENCHMARK), userLaunch = false, debug = true))
        val armed = PhySwitches.radioArms(debug = true, string = { "tx1m-rx2m" }, flag = { true })
        assertNotEquals(PhySwitches.RadioArms(), armed)
        assertEquals(GattPhySplit.TX_1M_RX_2M, armed.gattPhySplit)
        assertEquals(GattPhySplit.TX_1M_RX_2M, armed.adaptivePhySplit)
        assertTrue(armed.phyAfterAuth)
    }
}
