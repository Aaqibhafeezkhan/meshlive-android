package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** BACKLOG R-01 (security-patch window) and R-02 (stack eras). */
class DeviceMatcherPatchAndLineageTest {
    private fun phone(sdk: Int = 33, patch: String = "") = DeviceTraits("qcom", "taro", sdk, securityPatch = patch)

    @Test
    fun patchWindowIsInclusiveAtBothEnds() {
        val window = DeviceMatcher(patchMin = "2023-06-01", patchMax = "2023-07-05")
        assertTrue(window.matches(phone(patch = "2023-06-01")))
        assertTrue(window.matches(phone(patch = "2023-06-15")))
        assertTrue(window.matches(phone(patch = "2023-07-05")))
        assertFalse(window.matches(phone(patch = "2023-05-31")))
        assertFalse(window.matches(phone(patch = "2023-07-06")))
    }

    @Test
    fun oneSidedPatchBoundsWork() {
        val from = DeviceMatcher(patchMin = "2023-06-01")
        assertTrue(from.matches(phone(patch = "2026-10-05")))
        assertFalse(from.matches(phone(patch = "2023-05-05")))
        val until = DeviceMatcher(patchMax = "2023-06-01")
        assertTrue(until.matches(phone(patch = "2022-01-01")))
        assertFalse(until.matches(phone(patch = "2023-06-02")))
    }

    @Test
    fun anEmptyOrMalformedPatchNeverSatisfiesAPatchConstraint() {
        for (patch in listOf("", "unknown", "2023-6-1", "2023-06", "20230601", " 2023-06-01")) {
            assertFalse(patch, DeviceMatcher(patchMin = "2000-01-01").matches(phone(patch = patch)))
            assertFalse(patch, DeviceMatcher(patchMax = "2099-12-31").matches(phone(patch = patch)))
        }
        // Without a patch constraint the patch is irrelevant.
        assertTrue(DeviceMatcher(sdkMin = 33).matches(phone(patch = "")))
    }

    @Test
    fun patchIsAndedWithTheOtherFields() {
        val matcher = DeviceMatcher(sdkMin = 34, patchMin = "2024-01-01")
        assertTrue(matcher.matches(phone(sdk = 34, patch = "2024-02-05")))
        assertFalse(matcher.matches(phone(sdk = 33, patch = "2024-02-05")))
        assertFalse(matcher.matches(phone(sdk = 34, patch = "2023-12-05")))
    }

    @Test
    fun boundsMustBeFullIsoDates() {
        assertThrows(IllegalArgumentException::class.java) { DeviceMatcher(patchMin = "2023-06") }
        assertThrows(IllegalArgumentException::class.java) { DeviceMatcher(patchMax = "June 2023") }
    }

    @Test
    fun aPatchMatcherIsNotUniversalAndDescribesItsWindow() {
        assertFalse(DeviceMatcher(patchMin = "2023-06-01").universal)
        assertTrue(DeviceMatcher(patchMin = "2023-06-01", patchMax = "2023-07-05").describe().contains("security patch 2023-06-01..2023-07-05"))
        assertTrue(DeviceMatcher(patchMin = "2023-06-01").describe().contains("security patch >= 2023-06-01"))
        assertTrue(DeviceMatcher(patchMax = "2023-06-01").describe().contains("security patch <= 2023-06-01"))
    }

    @Test
    fun theTraitsLineCarriesThePatchAndTheEra() {
        val line = phone(sdk = 34, patch = "2024-02-05").describe()
        assertTrue(line, "patch=2024-02-05" in line.split(';'))
        assertTrue(line, "stack=GD_FLAGGED_34" in line.split(';'))
    }

    @Test
    fun sdkMapsToTheFiveEras() {
        val expected = mapOf(
            28 to BluetoothStackLineage.LEGACY_29_30,
            29 to BluetoothStackLineage.LEGACY_29_30,
            30 to BluetoothStackLineage.LEGACY_29_30,
            31 to BluetoothStackLineage.LEGACY_GD_OPTIONAL_31_32,
            32 to BluetoothStackLineage.LEGACY_GD_OPTIONAL_31_32,
            33 to BluetoothStackLineage.GD_CORE_33,
            34 to BluetoothStackLineage.GD_FLAGGED_34,
            35 to BluetoothStackLineage.ACONFIG_35_PLUS,
            36 to BluetoothStackLineage.ACONFIG_35_PLUS,
            37 to BluetoothStackLineage.ACONFIG_35_PLUS,
        )
        for ((sdk, era) in expected) assertEquals("sdk $sdk", era, deriveStackLineage(sdk))
        val eras = expected.values.toSet()
        assertEquals(5, eras.size)
        assertTrue("a phone is never an umbrella value", eras.none {
            it == BluetoothStackLineage.PLATFORM_SYSTEM_BT || it == BluetoothStackLineage.BLUETOOTH_MODULE
        })
    }

    @Test
    fun theOldTwoValuesKeepTheirMeaningAsUmbrellas() {
        val legacy = DeviceMatcher(stackLineages = setOf(BluetoothStackLineage.PLATFORM_SYSTEM_BT))
        val module = DeviceMatcher(stackLineages = setOf(BluetoothStackLineage.BLUETOOTH_MODULE))
        for (sdk in 29..32) {
            assertTrue("legacy $sdk", legacy.matches(phone(sdk)))
            assertFalse("module $sdk", module.matches(phone(sdk)))
        }
        for (sdk in 33..36) {
            assertFalse("legacy $sdk", legacy.matches(phone(sdk)))
            assertTrue("module $sdk", module.matches(phone(sdk)))
        }
    }

    @Test
    fun aFinerEraMatchesOnlyItself() {
        val gdFlagged = DeviceMatcher(stackLineages = setOf(BluetoothStackLineage.GD_FLAGGED_34))
        assertTrue(gdFlagged.matches(phone(34)))
        for (sdk in listOf(29, 31, 33, 35)) assertFalse("sdk $sdk", gdFlagged.matches(phone(sdk)))
        val twoEras = DeviceMatcher(
            stackLineages = setOf(BluetoothStackLineage.LEGACY_29_30, BluetoothStackLineage.GD_CORE_33),
        )
        assertTrue(twoEras.matches(phone(30)))
        assertTrue(twoEras.matches(phone(33)))
        assertFalse(twoEras.matches(phone(32)))
        assertFalse(twoEras.matches(phone(34)))
    }

    @Test
    fun umbrellaAndStackFamilyAreConsistent() {
        for (sdk in 29..37) {
            val traits = phone(sdk)
            assertEquals(traits.stackLineage.umbrella, traits.stackFamily)
            assertTrue(traits.stackFamily.covers(traits.stackLineage))
            assertTrue(traits.stackLineage.covers(traits.stackLineage))
        }
        assertFalse(BluetoothStackLineage.LEGACY_29_30.covers(BluetoothStackLineage.LEGACY_GD_OPTIONAL_31_32))
    }
}
