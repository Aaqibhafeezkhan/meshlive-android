package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNotificationActionsTest {
    @Test
    fun onlyOurActionsWithThisProcessTokenAreAuthentic() {
        val token = CallNotificationActions.token
        assertEquals(32, token.length)
        assertTrue(CallNotificationActions.isAuthentic(CallNotificationActions.ANSWER, token))
        assertTrue(CallNotificationActions.isAuthentic(CallNotificationActions.HANG_UP, token))
        // MainActivity is exported: another app's intent without the token cannot answer.
        assertFalse(CallNotificationActions.isAuthentic(CallNotificationActions.ANSWER, null))
        assertFalse(CallNotificationActions.isAuthentic(CallNotificationActions.ANSWER, "0".repeat(32)))
        assertFalse(CallNotificationActions.isAuthentic("record", token))
        assertFalse(CallNotificationActions.isAuthentic(null, token))
        assertFalse(CallNotificationActions.isAuthentic(CallNotificationActions.ANSWER, token, expected = "other"))
    }

    @Test
    fun actionsActOnlyInTheirPhase() {
        val c = CallNotificationActions
        assertEquals(CallNotificationCommand.ACCEPT, c.commandFor(c.ANSWER, PrivateCallPhase.INCOMING_RINGING))
        assertEquals(CallNotificationCommand.DECLINE, c.commandFor(c.DECLINE, PrivateCallPhase.INCOMING_RINGING))
        assertEquals(CallNotificationCommand.CANCEL, c.commandFor(c.HANG_UP, PrivateCallPhase.OUTGOING_RINGING))
        assertEquals(CallNotificationCommand.CANCEL, c.commandFor(c.HANG_UP, PrivateCallPhase.OUTGOING_INVITE))
        assertEquals(CallNotificationCommand.HANG_UP, c.commandFor(c.HANG_UP, PrivateCallPhase.ACTIVE))
        assertEquals(CallNotificationCommand.HANG_UP, c.commandFor(c.HANG_UP, PrivateCallPhase.NEGOTIATING_MEDIA))
        // A stale Answer after the call moved on, or with no call, does nothing.
        assertEquals(CallNotificationCommand.IGNORE, c.commandFor(c.ANSWER, PrivateCallPhase.ACTIVE))
        assertEquals(CallNotificationCommand.IGNORE, c.commandFor(c.ANSWER, null))
        assertEquals(CallNotificationCommand.IGNORE, c.commandFor(c.DECLINE, PrivateCallPhase.ACTIVE))
        assertEquals(CallNotificationCommand.IGNORE, c.commandFor(c.HANG_UP, PrivateCallPhase.INCOMING_RINGING))
    }

    @Test
    fun callStylePersonNamesTheCallerInTheFullNotificationOnly() {
        // The person is part of the full notification, which Android shows to an unlocked phone; the
        // lock screen shows the name-free public version (NotificationPrivacy).
        val c = CallNotificationActions
        assertEquals("Alice", c.personName(" Alice "))
        // CallStyle refuses an empty person name: fall back to the neutral label.
        assertEquals(c.NEUTRAL_PERSON, c.personName("  "))
        assertEquals(c.NEUTRAL_PERSON, c.personName(null))
        assertNotEquals("", c.NEUTRAL_PERSON)
    }
}

class ProximityCameraPausePolicyTest {
    @Test
    fun pausesOnlyWithCameraOnEarpieceAndNear() {
        val p = ProximityCameraPausePolicy
        assertTrue(p.shouldPause(enabled = true, userCameraOn = true, earpiece = true, near = true))
        assertFalse(p.shouldPause(enabled = false, userCameraOn = true, earpiece = true, near = true))
        assertFalse(p.shouldPause(enabled = true, userCameraOn = false, earpiece = true, near = true))
        assertFalse(p.shouldPause(enabled = true, userCameraOn = true, earpiece = false, near = true))
        assertFalse(p.shouldPause(enabled = true, userCameraOn = true, earpiece = true, near = false))
    }

    @Test
    fun binaryAndRangedSensorsReadNear() {
        val p = ProximityCameraPausePolicy
        // Binary sensor: 0 near, maximumRange far.
        assertTrue(p.isNear(0f, 1f))
        assertFalse(p.isNear(1f, 1f))
        // Ranged sensor: under 5 cm is near.
        assertTrue(p.isNear(3f, 10f))
        assertFalse(p.isNear(8f, 10f))
        // A sensor reporting no range uses the 5 cm rule.
        assertTrue(p.isNear(2f, 0f))
    }
}
