package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThermalFamilyEntriesTest {
    private val batchIds = setOf(
        "memory-tier-log",
        "memory-tier-sizing",
        "native-simd-safe-mode",
        "userspace32-tier",
    )

    @Test
    fun batchEntriesAreInTheRegistryWithCompleteMetadata() {
        val registry = DevicePolicyRegistry.entries.associateBy { it.id }
        batchIds.forEach { id ->
            val entry = registry[id] ?: error("$id missing from the registry")
            assertTrue(id, entry.sources.isNotEmpty() && entry.sources.all { it.reference.startsWith("https://") && it.note.isNotBlank() })
            assertTrue(id, entry.evidence.isNotEmpty() && entry.confidenceNote.isNotBlank() && entry.fallback.isNotBlank())
            assertTrue(id, entry.reviewed.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertEquals("test_device_policy_off=$id", entry.offSwitch)
            assertTrue(id, entry.area == PolicyArea.MEMORY_THERMAL || entry.area == PolicyArea.NATIVE)
            assertTrue(id, entry.validation != PolicyValidation.LOCALLY_REPRODUCED)
        }
    }

    @Test
    fun onlyTheTierLogIsActiveEverythingElseIsAnInertExperiment() {
        val entries = ThermalFamilyEntries.entries.associateBy { it.id }
        assertEquals(batchIds, entries.keys)
        assertFalse(entries.getValue("memory-tier-log").proposed)
        (batchIds - "memory-tier-log").forEach { id ->
            assertTrue(id, entries.getValue(id).proposed)
            assertEquals(id, PolicyValidation.EXPERIMENTAL, entries.getValue(id).validation)
            assertTrue(id, entries.getValue(id).legacySwitches.any { it.contains("default off") })
        }
        // Proposals never become active, even on a matching phone.
        val resolved = DeviceFamilyPolicy.resolve(DeviceTraits("", "", 37), emptySet())
        assertTrue(resolved.isActive("memory-tier-log"))
        batchIds.minus("memory-tier-log").forEach { assertFalse(it, resolved.isActive(it)) }
        assertTrue(resolved.matchingProposals.map { it.id }.containsAll(batchIds - "memory-tier-log"))
    }

    @Test
    fun thermalSwitchNamesAppearInTheEntriesThatOwnThem() {
        val all = MemoryThermalPolicy.entries.associateBy { it.id }
        assertTrue(all.getValue("memory-tier-sizing").legacySwitches.single().contains("test_memory_tier=true"))
        assertTrue(all.getValue("native-simd-safe-mode").legacySwitches.single().contains("test_native_simd_safe_mode=true"))
        assertTrue(all.getValue("userspace32-tier").legacySwitches.single().contains("test_userspace32_tier=true"))
        assertTrue(all.getValue("thermal-battery-temperature-fallback").legacySwitches.single().contains("test_thermal_battery_fallback=true"))
    }

    /** T-10: Android 16 collapses scheduleAtFixedRate catch-up runs at targetSdk 36; nothing here may rely on them. */
    @Test
    fun noSourceUsesScheduleAtFixedRate() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory } ?: error("project root not found")
        val offenders = File(root, "app/src").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { !it.path.replace('\\', '/').contains("/src/test/") && !it.path.replace('\\', '/').contains("/src/androidTest/") }
            .filter { it.readText().contains("scheduleAtFixedRate") }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("scheduleAtFixedRate catch-up semantics changed on Android 16; review: $offenders", offenders.isEmpty())
    }
}
