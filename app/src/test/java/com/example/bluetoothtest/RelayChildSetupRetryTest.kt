package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayChildSetupRetryTest {
    @Test
    fun aTwoMChildSetupThatFailedBeforeHopAuthenticationRetriesOn1M() {
        // mesh4-c0200edf-loop7-1 0004: ASUS relay -> A15s CoC connect failed 6 ms after the 2M request, twice.
        assertTrue(relayChildSetupRetriesOn1M(guardOn = true, attemptPhy = BlePhyPreference.TWO_M, hopAuthenticated = false))
    }

    @Test
    fun anAuthenticatedLinkA1MAttemptOrTheControlArmDoNotRetry() {
        assertFalse(relayChildSetupRetriesOn1M(guardOn = true, attemptPhy = BlePhyPreference.TWO_M, hopAuthenticated = true))
        assertFalse(relayChildSetupRetriesOn1M(guardOn = true, attemptPhy = BlePhyPreference.ONE_M_ADAPTIVE, hopAuthenticated = false))
        assertFalse(relayChildSetupRetriesOn1M(guardOn = true, attemptPhy = null, hopAuthenticated = false))
        assertFalse(relayChildSetupRetriesOn1M(guardOn = false, attemptPhy = BlePhyPreference.TWO_M, hopAuthenticated = false))
    }
}
