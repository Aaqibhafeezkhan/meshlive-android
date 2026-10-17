package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceFamilyPolicyTest {
    private fun traits(sdk: Int, manufacturer: String = "samsung", policyOff: Set<String> = emptySet()) =
        DeviceTraits("qcom", "msmnile", sdk, manufacturer = manufacturer, socManufacturer = "QTI", policyOff = policyOff)

    @Test
    fun everyEntryCarriesTheRegistryFields() {
        val ids = ServiceFamilyPolicy.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf("android10-11-background-scan-needs-location-fgs", "fgs-session-wake-lock", "boot-resume-after-restart"),
            ids.toSet(),
        )
        ServiceFamilyPolicy.entries.forEach { entry ->
            assertTrue(entry.id, entry.id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)+")))
            assertTrue("${entry.id} sources", entry.sources.size >= 2)
            entry.sources.forEach {
                assertTrue("${entry.id} source ${it.reference}", it.reference.startsWith("https://"))
                assertTrue("${entry.id} source note", it.note.isNotBlank())
            }
            assertTrue("${entry.id} confidenceNote", entry.confidenceNote.isNotBlank())
            assertTrue("${entry.id} evidence", entry.evidence.isNotEmpty())
            assertTrue("${entry.id} fallback", entry.fallback.isNotBlank())
            assertTrue("${entry.id} origin", entry.origin.isNotBlank())
            assertTrue("${entry.id} reviewed", entry.reviewed.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertTrue("${entry.id} effects", entry.effects.isNotEmpty())
            assertFalse("${entry.id} must be implemented, not proposed", entry.proposed)
            assertEquals(PolicyArea.BACKGROUND, entry.area)
            assertEquals("test_device_policy_off=${entry.id}", entry.offSwitch)
            assertNotNull(entry.sourceStatus)
            assertNotNull(entry.confidence)
            assertNotNull(entry.validation)
        }
    }

    @Test
    fun noMatcherUsesAPhoneModel() {
        // Matchers describe a family (API, SoC, skin, stack), never Build.MODEL; these entries only use the API range.
        ServiceFamilyPolicy.entries.forEach { entry ->
            assertTrue(entry.id, entry.matcher.hardware.isEmpty() && entry.matcher.boards.isEmpty() && entry.matcher.manufacturers.isEmpty())
        }
    }

    @Test
    fun theLocationEntryMatchesOnlyAndroid10And11() {
        val entry = ServiceFamilyPolicy.LOCATION_FGS
        assertFalse(entry.matcher.matches(traits(28)))
        assertTrue(entry.matcher.matches(traits(29)))
        assertTrue(entry.matcher.matches(traits(30, manufacturer = "asus")))
        assertFalse(entry.matcher.matches(traits(31)))
        assertFalse(entry.matcher.matches(traits(36)))
        assertTrue(entry.effects.single() === LocationForegroundServiceType)
    }

    @Test
    fun theExperimentalEntriesAreMarkedExperimental() {
        assertEquals(PolicyValidation.EXPERIMENTAL, ServiceFamilyPolicy.SESSION_WAKE_LOCK.validation)
        assertEquals(PolicyValidation.EXPERIMENTAL, ServiceFamilyPolicy.BOOT_RESUME.validation)
        assertEquals(PolicyValidation.UPSTREAM_DOCUMENTED, ServiceFamilyPolicy.LOCATION_FGS.validation)
    }

    @Test
    fun theOffSwitchTurnsAnEntryOffWithoutTheAggregateRegistry() {
        val entry = ServiceFamilyPolicy.LOCATION_FGS
        assertTrue(ServiceFamilyPolicy.isActive(entry, traits(30), emptySet()))
        assertFalse(ServiceFamilyPolicy.isActive(entry, traits(30), setOf(entry.id)))
        assertFalse(ServiceFamilyPolicy.isActive(entry, traits(30), setOf("all")))
        assertFalse(ServiceFamilyPolicy.isActive(entry, traits(30, policyOff = setOf(entry.id)), emptySet()))
        assertFalse(ServiceFamilyPolicy.isActive(entry, traits(33), emptySet()))
        assertTrue(ServiceFamilyPolicy.isActive(entry, traits(30), setOf("some-other-entry")))
    }

    @Test
    fun theWakeLockEntryNeedsBothItsSwitchAndItsEntry() {
        // The entry alone never takes a lock: the Experimental switch is off by default.
        assertTrue(ServiceFamilyPolicy.isActive(ServiceFamilyPolicy.SESSION_WAKE_LOCK, traits(34), emptySet()))
        assertFalse(ServiceSwitches.sessionWakeLock)
    }
}
