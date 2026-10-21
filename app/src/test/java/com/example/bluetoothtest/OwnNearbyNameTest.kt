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
}
