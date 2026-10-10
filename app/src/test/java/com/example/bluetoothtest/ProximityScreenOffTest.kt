package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityScreenOffTest {
    private class FakeLock : ProximityLock {
        override var held = false
        var acquires = 0
        var releases = 0

        override fun acquire() {
            held = true
            acquires++
        }

        override fun release() {
            held = false
            releases++
        }
    }

    @Test
    fun holdsOnlyWhileTheEarpieceIsActive() {
        val lock = FakeLock()
        val proximity = ProximityScreenOff(lock)
        proximity.onSpeakerChanged(speakerOn = true)
        assertFalse(proximity.active)
        assertEquals(0, lock.acquires)

        proximity.onSpeakerChanged(speakerOn = false)
        assertTrue(proximity.active)
        assertEquals(1, lock.acquires)

        proximity.onSpeakerChanged(speakerOn = true)
        assertFalse(proximity.active)
        assertEquals(1, lock.releases)
    }

    @Test
    fun repeatedRouteReportsDoNotStackOrDoubleRelease() {
        val lock = FakeLock()
        val proximity = ProximityScreenOff(lock)
        proximity.onSpeakerChanged(false)
        proximity.onSpeakerChanged(false)
        assertEquals(1, lock.acquires)
        proximity.onSpeakerChanged(true)
        proximity.onSpeakerChanged(true)
        assertEquals(1, lock.releases)
    }

    @Test
    fun toggleBackAndForthFollowsTheRoute() {
        val lock = FakeLock()
        val proximity = ProximityScreenOff(lock)
        repeat(3) {
            proximity.onSpeakerChanged(false)
            assertTrue(lock.held)
            proximity.onSpeakerChanged(true)
            assertFalse(lock.held)
        }
        assertEquals(3, lock.acquires)
        assertEquals(3, lock.releases)
    }

    @Test
    fun closeReleasesAnEarpieceLockAndIsHarmlessOtherwise() {
        val lock = FakeLock()
        val proximity = ProximityScreenOff(lock)
        proximity.onSpeakerChanged(false)
        proximity.close()
        assertFalse(lock.held)
        assertEquals(1, lock.releases)
        proximity.close()
        assertEquals(1, lock.releases)

        val speakerOnly = FakeLock()
        ProximityScreenOff(speakerOnly).apply {
            onSpeakerChanged(true)
            close()
        }
        assertEquals(0, speakerOnly.acquires)
        assertEquals(0, speakerOnly.releases)
    }

    @Test
    fun aPhoneWithoutAProximityLockKeepsTheScreenBehaviourUntouched() {
        val proximity = ProximityScreenOff(null)
        proximity.onSpeakerChanged(false)
        assertFalse(proximity.active)
        proximity.onSpeakerChanged(true)
        proximity.close()
        assertFalse(proximity.active)
    }
}
