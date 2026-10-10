package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCapacityRejectionPolicyTest {
    @Test
    fun capacityRejectionSuppressesOnlyThatViewerUntilItsCooldownExpires() {
        val policy = SourceCapacityRejectionPolicy()
        policy.reject("viewer-a", 1_000L)

        assertTrue(policy.shouldSuppress("viewer-a", 8_000_001_000L, false, false))
        assertFalse(policy.shouldSuppress("viewer-b", 8_000_001_000L, false, false))
        assertFalse(policy.shouldSuppress("viewer-a", 30_000_001_000L, false, false))
    }

    @Test
    fun newlyAvailableSlotImmediatelyClearsTheCapacityRejection() {
        val policy = SourceCapacityRejectionPolicy()
        policy.reject("viewer", 1_000L)

        assertFalse(policy.shouldSuppress("viewer", 2_000L, true, false))
        assertFalse(policy.shouldSuppress("viewer", 3_000L, false, false))
    }

    @Test
    fun stalePreviousParentImmediatelyAllowsItsRecoveryProbe() {
        val policy = SourceCapacityRejectionPolicy()
        policy.reject("viewer", 1_000L)

        assertFalse(policy.shouldSuppress("viewer", 2_000L, false, true))
        assertFalse(policy.shouldSuppress("viewer", 3_000L, false, false))
    }
}
