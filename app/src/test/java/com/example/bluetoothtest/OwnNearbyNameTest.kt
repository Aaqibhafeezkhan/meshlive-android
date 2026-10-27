package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "You appear as ..." follows this phone's current rotating ID, and is stable when rotating IDs are off. */
class OwnNearbyNameTest {
    // A stand-in for the generated-name derivation: a different ID gives a different name, as StrangerNames does.
    private val derive: (ByteArray) -> String? = { rdi -> "Name-" + rdi.joinToString("") { "%02x".format(it) } }
    private val epochA = ByteArray(6) { 1 }
    private val epochB = ByteArray(6) { 2 }

    private fun shown(rotating: Boolean, names: Boolean = true, rdi: ByteArray?) = OwnNearbyName.shown(
        rotatingOn = rotating, generatedNamesOn = names, ownRdi = rdi, derive = derive,
        nearbyPhone = "Nearby phone", someoneNearby = "Someone nearby",
    )

    @Test
    fun theNameFollowsTheRotatingIdAcrossABoundary() {
        val before = shown(rotating = true, rdi = epochA)!!
        val after = shown(rotating = true, rdi = epochB)!!
        assertTrue(before.rotates && after.rotates)
        assertEquals(derive(epochA), before.name)
        assertNotEquals("a new ID is a new name", before.name, after.name)
    }

    @Test
    fun withRotatingIdsOffTheNameIsStableAndSaysNothingAboutChanging() {
        val a = shown(rotating = false, rdi = epochA)!!
        val b = shown(rotating = false, rdi = epochB)!!
        assertEquals(a, b)
        assertFalse(a.rotates)
        assertEquals("Someone nearby", a.name)
    }

    @Test
    fun withGeneratedNamesOffNearbyPhonesShowNearbyPhone() {
        val shown = shown(rotating = true, names = false, rdi = epochA)!!
        assertEquals("Nearby phone", shown.name)
        assertFalse(shown.rotates)
    }

    @Test
    fun nothingIsShownBeforeAnIdIsOnAir() {
        assertNull(shown(rotating = true, rdi = null))
    }

    /**
     * Review 2026-10-27 (finding 16): the line named the ID computed from the epoch clock, not the one on air. It now
     * follows what the advertiser actually sent: the newest advertised ID, the old one only while it is still on air, and
     * nothing once the offer has been off the air for its minute of grace.
     */
    @Test
    fun theNameIsTheOneOfTheIdActuallyOnAir() {
        val pins = OpeningPins()
        val a = OpeningEntry(epochA, 100L, RotatingDiscoveryIds.ROLE_CALL_OFFER, ByteArray(32) { 1 })
        val b = OpeningEntry(epochB, 101L, RotatingDiscoveryIds.ROLE_CALL_OFFER, ByteArray(32) { 2 })
        assertNull("nothing advertised yet", shown(rotating = true, rdi = OwnNearbyName.onAir(pins.pinned(0L))))
        pins.onAdvertised(a, 0L)
        assertEquals(derive(epochA), shown(rotating = true, rdi = OwnNearbyName.onAir(pins.pinned(10L)))!!.name)
        // The advertiser restarted on the next ID: the name follows it, not the previous one kept for openings.
        pins.onAdvertised(b, 20L)
        assertEquals(derive(epochB), shown(rotating = true, rdi = OwnNearbyName.onAir(pins.pinned(30L)))!!.name)
        // Off the air for longer than the grace: no name is claimed.
        pins.offAir(40L)
        assertNull(shown(rotating = true, rdi = OwnNearbyName.onAir(pins.pinned(40L + OpeningPins.PREVIOUS_GRACE_MS + 1))))
    }
}
