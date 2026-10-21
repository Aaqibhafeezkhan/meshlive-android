package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CallAcceptContinuationTest {
    @Test fun permissionGrantedAfterCallerCancellationIsANoop() {
        val ringing = PrivateCallState.incoming(UUID.randomUUID())
        val ended = ringing.transition(PrivateCallEvent.REMOTE_CANCEL)
        assertNull(ended.acceptIfRinging())
        assertEquals(PrivateCallPhase.ENDED, ended.phase)
    }

    @Test fun acceptTransitionsOnlyTheStillRingingAttempt() {
        val ringing = PrivateCallState.incoming(UUID.randomUUID())
        val accepted = requireNotNull(ringing.acceptIfRinging())
        assertEquals(ringing.callId, accepted.callId)
        assertEquals(PrivateCallPhase.NEGOTIATING_MEDIA, accepted.phase)
        assertNull(accepted.acceptIfRinging())
        assertNull(PrivateCallState.outgoing().acceptIfRinging())
    }
}
