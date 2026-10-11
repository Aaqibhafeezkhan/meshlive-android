package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresenceEligibilityTest {
    private fun reason(
        vault: Boolean = true,
        persistent: Boolean = true,
        state: VerificationState? = VerificationState.VERIFIED,
        visible: Boolean = true,
    ) = PresenceEligibility.reason(vault, persistent, state, visible)

    @Test
    fun aVerifiedContactWithAPersistentVaultAndTheSwitchOnIsEligible() {
        assertNull(reason())
    }

    @Test
    fun everyWithheldOfferNamesOneFixedReason() {
        assertEquals("no_vault", reason(vault = false, persistent = false))
        assertEquals("vault_not_persistent", reason(persistent = false))
        assertEquals("no_contact_store", reason(state = null))
        assertEquals("peer_unverified", reason(state = VerificationState.UNVERIFIED))
        assertEquals("peer_mismatch", reason(state = VerificationState.MISMATCH))
        assertEquals("peer_store_unreadable", reason(state = VerificationState.STORE_UNREADABLE))
        assertEquals("contact_switch_off", reason(visible = false))
    }

    @Test
    fun theReasonsAreCheckedInTheOrderTheVaultTheStoreThePeerThenTheSwitch() {
        assertEquals("no_vault", reason(vault = false, persistent = false, state = null, visible = false))
        assertEquals("vault_not_persistent", reason(persistent = false, state = null, visible = false))
        assertEquals("no_contact_store", reason(state = null, visible = false))
        assertEquals("peer_unverified", reason(state = VerificationState.UNVERIFIED, visible = false))
    }
}
