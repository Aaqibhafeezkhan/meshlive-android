package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FeatureCatalogTest {
    private fun spec(
        id: String = "sample",
        prefKey: String = "feature_sample",
        default: Boolean = false,
        extra: String? = "test_sample",
        dependsOn: String? = null,
    ) = FeatureSpec(id, prefKey, default, extra, dependsOn = dependsOn)

    @Before
    fun clean() = FeatureCatalog.clearForTest()

    @After
    fun cleanAfter() = FeatureCatalog.clearForTest()

    @Test
    fun aBenchmarkLaunchReadsOnlyItsOwnExtraWhateverTheDefaultOrTheStoredChoiceSay() {
        val on = spec(default = true)
        assertFalse(FeatureCatalog.resolve(on, true, { false }, { true }))
        assertTrue(FeatureCatalog.resolve(spec(default = false), true, { it == "test_sample" }, { false }))
        // No extra declared: a benchmark launch always reads off.
        assertFalse(FeatureCatalog.resolve(spec(default = true, extra = null), true, { true }, { true }))
    }

    @Test
    fun aNormalLaunchUsesTheExplicitChoiceElseTheDefaultAndIgnoresExtrasAndManualSettings() {
        assertTrue(FeatureCatalog.resolve(spec(default = true), false, { false }, { null }))
        assertFalse(FeatureCatalog.resolve(spec(default = false), false, { true }, { null }))
        assertFalse(FeatureCatalog.resolve(spec(default = true), false, { true }, { false }))
        assertTrue(FeatureCatalog.resolve(spec(default = false), false, { false }, { true }))
    }

    @Test
    fun aSubOptionIsOnOnlyWhileItsParentIsOn() {
        FeatureCatalog.register(spec("parent", "p"))
        FeatureCatalog.register(spec("child", "c", dependsOn = "parent"))
        val stored = mapOf("p" to false, "c" to true)
        val off = FeatureCatalog.resolveAll(false, { false }) { stored[it] }
        assertFalse(off.isOn("parent"))
        assertFalse(off.isOn("child"))
        val on = FeatureCatalog.resolveAll(false, { false }) { mapOf("p" to true, "c" to true)[it] }
        assertTrue(on.isOn("child"))
        assertFalse(on.isOn("unregistered"))
    }

    @Test
    fun registrationIsIdempotentAndRejectsConflicts() {
        FeatureCatalog.register(spec())
        FeatureCatalog.register(spec())
        assertEquals(1, FeatureCatalog.all().size)
        expectFailure { FeatureCatalog.register(spec(default = true)) }
        expectFailure { FeatureCatalog.register(spec("other", "feature_sample")) }
        expectFailure { FeatureCatalog.register(spec("child", "c", dependsOn = "missing")) }
        expectFailure { spec("Bad Id") }
        expectFailure { spec("self", dependsOn = "self") }
    }

    @Test
    fun refreshRemembersTheSnapshotAndLabelsIt() {
        FeatureCatalog.register(spec("a", "pa", default = true))
        FeatureCatalog.register(spec("b", "pb"))
        val snapshot = FeatureCatalog.refresh(false, { false }) { null }
        assertEquals(snapshot, FeatureCatalog.current)
        assertTrue(FeatureCatalog.isEnabled("a"))
        assertFalse(FeatureCatalog.isEnabled("b"))
        assertEquals("a=on | b=off", snapshot.label())
        assertNotEquals(snapshot, FeatureCatalog.refresh(false, { false }) { if (it == "pb") true else null })
    }

    @Test
    fun theConnectivityFeaturesKeepTheirOldSwitchesAndAreAllDescribed() {
        val all = ConnectivityFeatures.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.prefKey }.toSet().size)
        all.forEach {
            assertTrue(it.id, it.titleRes != 0 && it.descriptionRes != 0 && it.iconRes != 0)
            assertEquals(ConnectivityFeatures.GROUP, it.group)
            assertTrue(it.id, it.phoneTest != null)
        }
        // The old benchmark extras and preference keys stay: case IDs and earlier choices remain valid.
        assertEquals("test_coc_auto_gatt", ConnectivityFeatures.cocAutoGatt.benchmarkExtra)
        assertEquals("test_classic_bond_free", ConnectivityFeatures.classicWithoutPairing.benchmarkExtra)
        assertEquals("test_ios_compact_discovery", ConnectivityFeatures.iphoneDiscovery.benchmarkExtra)
        assertEquals("test_offline_app_share", ConnectivityFeatures.shareAppNearby.benchmarkExtra)
        assertEquals("test_call_native_opus", ConnectivityFeatures.nativeOpus.benchmarkExtra)
        assertEquals("test_dual_listening", ConnectivityFeatures.dualListening.benchmarkExtra)
        assertEquals("experimental_coc_auto_gatt", ConnectivityFeatures.cocAutoGatt.prefKey)
        assertEquals("experimental_classic_bond_free", ConnectivityFeatures.classicWithoutPairing.prefKey)
        assertEquals("experimental_ios_compact_discovery_v2", ConnectivityFeatures.iphoneDiscovery.prefKey)
        assertEquals("experimental_offline_app_share", ConnectivityFeatures.shareAppNearby.prefKey)
        assertEquals("experimental_dual_listening", ConnectivityFeatures.dualListening.prefKey)
        // One default constant per feature.
        assertEquals(ConnectivityFeatureDefaults.COC_AUTO_GATT, ConnectivityFeatures.cocAutoGatt.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.CLASSIC_WITHOUT_PAIRING, ConnectivityFeatures.classicWithoutPairing.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.IPHONE_DISCOVERY, ConnectivityFeatures.iphoneDiscovery.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.SHARE_APP_NEARBY, ConnectivityFeatures.shareAppNearby.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.NATIVE_OPUS, ConnectivityFeatures.nativeOpus.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.DUAL_LISTENING, ConnectivityFeatures.dualListening.defaultEnabled)
    }

    @Test
    fun theCallFeatureSpecsApplyTheSameRuleAsTheCallFeatureResolver() {
        CallFeatureSpecs.registerAll()
        ConnectivityFeatures.registerAll()
        assertEquals(CallFeature.entries.size, CallFeatureSpecs.all.size)
        assertEquals(CallFeatureSpecs.all.size + ConnectivityFeatures.all.size, FeatureCatalog.all().size)
        val storedChoices = listOf<Boolean?>(null, true, false)
        for (feature in CallFeature.entries) {
            val spec = CallFeatureSpecs.specOf(feature)
            assertEquals(feature.prefKey, spec.prefKey)
            assertEquals(feature.defaultOn, spec.defaultEnabled)
            assertEquals(feature.benchmarkExtra, spec.benchmarkExtra)
            for (benchmark in listOf(false, true)) for (extra in listOf(false, true)) for (stored in storedChoices) {
                assertEquals(
                    "$feature benchmark=$benchmark extra=$extra stored=$stored",
                    CallFeatureResolver.enabled(feature, benchmark, { extra }, { stored }),
                    FeatureCatalog.resolve(spec, benchmark, { extra }, { stored }),
                )
            }
        }
        // The automatic sync is a sub-option of Messages in both.
        val stored = mapOf(CallFeature.AUTO_SYNC.prefKey to true, CallFeature.MESSAGES.prefKey to false)
        val snapshot = FeatureCatalog.resolveAll(false, { false }) { stored[it] }
        assertFalse(snapshot.isOn("messages_auto_sync"))
        assertEquals(
            CallFeatureResolver.resolve(false, { false }) { stored[it] }.autoSync,
            snapshot.isOn("messages_auto_sync"),
        )
        assertNull(FeatureCatalog.spec("messages_auto_sync")?.dependsOn?.takeIf { it != "messages" })
    }

    @Test
    fun theNativeOpusSwitchIsReachableWithoutTheDebugGate() {
        assertTrue(NativeOpusSettings.resolveEnabled(false, { false }) { if (it == NativeOpusSettings.PREF_ENABLED) true else null })
        assertFalse(NativeOpusSettings.resolveEnabled(true, { false }) { true })
        assertTrue(NativeOpusSettings.resolveEnabled(true, { it == NativeOpusSettings.EXTRA_ENABLED }) { false })
        assertEquals(
            ConnectivityFeatureDefaults.NATIVE_OPUS,
            NativeOpusSettings.resolveEnabled(false, { true }) { null },
        )
        // The main switch has no BuildConfig.DEBUG gate; DTX keeps one (it changes the packet cadence).
        DebugTunables.callNativeOpus = true
        assertTrue(DebugTunables.callNativeOpus)
        DebugTunables.callNativeOpus = false
        assertFalse(DebugTunables.nativeOpusSettings().dtx)
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
