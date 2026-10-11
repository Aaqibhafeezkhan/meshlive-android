package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactFilterGuardTest {
    @After
    fun reset() {
        CompactDiscoveryFeature.enabled = false
        CompactDiscoveryFeature.filterRejected = false
    }

    @Test
    fun onlyFilterRelatedScanFailuresDropTheMaskedFilter() {
        assertTrue(CompactFilterGuard.refusesFilter(ScanFailurePolicy.FEATURE_UNSUPPORTED))
        assertTrue(CompactFilterGuard.refusesFilter(ScanFailurePolicy.OUT_OF_HARDWARE_RESOURCES))
        // Transient failures keep the filter and use the ordinary back-off.
        assertFalse(CompactFilterGuard.refusesFilter(ScanFailurePolicy.ALREADY_STARTED))
        assertFalse(CompactFilterGuard.refusesFilter(ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED))
        assertFalse(CompactFilterGuard.refusesFilter(ScanFailurePolicy.INTERNAL_ERROR))
        assertFalse(CompactFilterGuard.refusesFilter(ScanFailurePolicy.SCANNING_TOO_FREQUENTLY))
        assertFalse(CompactFilterGuard.refusesFilter(99))
    }

    @Test
    fun theFilterIsActiveOnlyWhileTheFeatureIsOnAndNoScannerRefusedIt() {
        assertFalse(CompactDiscoveryFeature.filterActive())
        CompactDiscoveryFeature.enabled = true
        assertTrue(CompactDiscoveryFeature.filterActive())
        CompactDiscoveryFeature.filterRejected = true
        assertFalse(CompactDiscoveryFeature.filterActive())
        CompactDiscoveryFeature.filterRejected = false
        assertTrue(CompactDiscoveryFeature.filterActive())
    }
}
