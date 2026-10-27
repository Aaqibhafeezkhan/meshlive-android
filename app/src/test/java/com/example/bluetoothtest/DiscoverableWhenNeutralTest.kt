package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverableWhenNeutralTest {
    private val neutral = NeutralBluetoothName.NEUTRAL

    @After
    fun resetName() {
        DiscoverableWhenNeutral.onLocalNameChanged(neutral)
    }

    @Test
    fun defaultFollowsTheNeutralNameOption() {
        assertTrue(DiscoverableWhenNeutral.switchOn(debug = true, extra = null, neutralOptionOn = true))
        assertFalse(DiscoverableWhenNeutral.switchOn(debug = true, extra = null, neutralOptionOn = false))
        assertFalse(DiscoverableWhenNeutral.switchOn(debug = false, extra = null, neutralOptionOn = false))
    }

    @Test
    fun debugExtraForcesTheSwitchButReleaseIgnoresIt() {
        assertTrue(DiscoverableWhenNeutral.switchOn(debug = true, extra = true, neutralOptionOn = false))
        assertFalse(DiscoverableWhenNeutral.switchOn(debug = true, extra = false, neutralOptionOn = true))
        assertFalse(DiscoverableWhenNeutral.switchOn(debug = false, extra = true, neutralOptionOn = false))
        assertTrue(DiscoverableWhenNeutral.switchOn(debug = false, extra = false, neutralOptionOn = true))
    }

    @Test
    fun discoverableOnlyWithTheSwitchOnAndTheActualNameNeutral() {
        assertEquals(
            DiscoverableWhenNeutral.Decision(true, DiscoverableWhenNeutral.Reason.NEUTRAL_NAME),
            DiscoverableWhenNeutral.decide(switchOn = true, currentName = neutral),
        )
        assertEquals(DiscoverableWhenNeutral.Reason.SWITCH_OFF, DiscoverableWhenNeutral.decide(false, neutral).reason)
        assertFalse(DiscoverableWhenNeutral.decide(false, neutral).discoverable)
        // The option is on but the adapter still has the user's name (rename failed or pending): stay hidden.
        val ownName = DiscoverableWhenNeutral.decide(true, "Alice's Galaxy")
        assertFalse(ownName.discoverable)
        assertEquals(DiscoverableWhenNeutral.Reason.NAME_NOT_NEUTRAL, ownName.reason)
        assertEquals(DiscoverableWhenNeutral.Reason.NAME_UNREADABLE, DiscoverableWhenNeutral.decide(true, null).reason)
        // Similar is not equal.
        assertFalse(DiscoverableWhenNeutral.decide(true, "phone").discoverable)
        assertFalse(DiscoverableWhenNeutral.decide(true, "Phone ").discoverable)
    }

    @Test
    fun aStackAnnouncedRenameOverridesAStaleNeutralRead() {
        val decision = DiscoverableWhenNeutral.decide(true, neutral, lastBroadcastName = "Alice's Galaxy")
        assertFalse(decision.discoverable)
        assertEquals(DiscoverableWhenNeutral.Reason.NAME_CHANGED, decision.reason)
        assertTrue(DiscoverableWhenNeutral.decide(true, neutral, lastBroadcastName = neutral).discoverable)
    }

    @Test
    fun renameAwayFromNeutralRestartsEveryDiscoverableSetOnce() {
        var restarts = 0
        val listener = DiscoverableWhenNeutral.Listener { restarts++ }
        DiscoverableWhenNeutral.register(listener)

        DiscoverableWhenNeutral.onLocalNameChanged(neutral)
        assertEquals("a rename to Phone keeps the set", 0, restarts)

        DiscoverableWhenNeutral.onLocalNameChanged("Alice's Galaxy")
        assertEquals(1, restarts)
        assertEquals("Alice's Galaxy", DiscoverableWhenNeutral.lastBroadcastName)

        DiscoverableWhenNeutral.onLocalNameChanged("Alice's Galaxy 2")
        assertEquals("a set is restarted once; its next start decides again", 1, restarts)
    }

    @Test
    fun anUnreadableAnnouncedNameAlsoHides() {
        var restarts = 0
        val listener = DiscoverableWhenNeutral.Listener { restarts++ }
        DiscoverableWhenNeutral.register(listener)
        DiscoverableWhenNeutral.onLocalNameChanged(null)
        assertEquals(1, restarts)
    }

    @Test
    fun aStoppedSetIsNotRestarted() {
        var restarts = 0
        val listener = DiscoverableWhenNeutral.Listener { restarts++ }
        DiscoverableWhenNeutral.register(listener)
        DiscoverableWhenNeutral.unregister(listener)
        DiscoverableWhenNeutral.onLocalNameChanged("Alice's Galaxy")
        assertEquals(0, restarts)
    }

    @Test
    fun coversCallOffersAndViewerRequestsOnly() {
        val accepting = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS
        assertEquals(
            DiscoverableWhenNeutral.Role.CALL_OFFER,
            DiscoverableWhenNeutral.roleOf(announcement(accepting or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE)),
        )
        assertEquals(DiscoverableWhenNeutral.Role.VIEWER_REQUEST, DiscoverableWhenNeutral.roleOf(announcement(accepting)))
        assertNull(DiscoverableWhenNeutral.roleOf(announcement(accepting or DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE)))
        assertNull(DiscoverableWhenNeutral.roleOf(announcement(DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE)))
    }

    private fun announcement(flags: Int) = DiscoveryAnnouncement(
        nodeIdPrefix = ByteArray(6) { it.toByte() },
        flags = flags,
        capabilitySummary = 0,
        streamToken = 0,
        routeCost = 0,
        sequence = 0,
    )
}
