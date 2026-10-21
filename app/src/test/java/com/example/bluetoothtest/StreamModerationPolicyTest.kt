package com.example.bluetoothtest

import org.junit.Assert.*
import org.junit.Test

class StreamModerationPolicyTest {
    @Test fun aRelayClaimCannotBlockEvenAKnownContact() {
        assertFalse(StreamModerationPolicy.canModerateIdentity(false, true))
        assertFalse(StreamModerationPolicy.canModerateIdentity(false, false))
    }

    @Test fun identityActionsNeedCurrentStreamAuthenticationAndAFullFingerprint() {
        assertTrue(StreamModerationPolicy.canModerateIdentity(true, true))
        assertFalse(StreamModerationPolicy.canModerateIdentity(true, false))
    }
}
