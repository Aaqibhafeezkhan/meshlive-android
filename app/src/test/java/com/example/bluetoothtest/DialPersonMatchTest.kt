package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edgecases-20261020 asus-s22-fb0e step 3: the ASUS dialled d7df8a2451a8 1.2 s after the S22's epoch boundary; the S22
 * announced 4b389aaf9073 from the same address, and the dial failed with refresh_timeout after 10 s.
 */
class DialPersonMatchTest {
    private val dialled = DialPersonMatch.Target(key = "d7df8a2451a8", contactPrefix = "1cc0bc9b1088", address = "40:DE:24:0D:78:80")

    @Test
    fun aDialJustBeforeTheBoundaryProceedsOnTheNextEpochsIdOfTheSameContact() {
        assertEquals(DialPersonMatch.By.CONTACT, DialPersonMatch.matches(dialled, "4b389aaf9073", "1cc0bc9b1088", "5A:11:22:33:44:55", on = true))
    }

    @Test
    fun aStrangersDialProceedsWhenTheStackKeptTheAddressAcrossTheEpoch() {
        val stranger = dialled.copy(contactPrefix = null)
        assertEquals(DialPersonMatch.By.ADDRESS, DialPersonMatch.matches(stranger, "4b389aaf9073", null, "40:de:24:0d:78:80", on = true))
    }

    @Test
    fun theSameKeyAlwaysMatchesAndAnotherPhoneNever() {
        assertEquals(DialPersonMatch.By.KEY, DialPersonMatch.matches(dialled, "d7df8a2451a8", null, null, on = false))
        assertNull(DialPersonMatch.matches(dialled, "9e58a9a07983", "3915eea54ea3", "79:15:3E:CD:82:48", on = true))
        assertNull("an unrecognised rotating ID does not match a contact", DialPersonMatch.matches(dialled, "9e58a9a07983", null, "79:15:3E:CD:82:48", on = true))
        assertNull(DialPersonMatch.matches(null, "d7df8a2451a8", null, null, on = true))
    }

    @Test
    fun theControlArmMatchesOnlyTheExactKeyAsBefore() {
        assertNull(DialPersonMatch.matches(dialled, "4b389aaf9073", "1cc0bc9b1088", "40:DE:24:0D:78:80", on = false))
    }

    private fun source(path: String): String = listOf(File(path), File("app/$path"))
        .first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun theUserDialRemembersItsPersonAndTheSightingFollowsIt() {
        val main = source("src/main/java/com/example/bluetoothtest/MainActivity.kt")
        val start = main.substringAfter("private fun startOutgoingPrivateCall(").substringBefore("logCallInvite(")
        assertTrue(start.contains("pendingPrivateCallTarget = DialPersonMatch.Target("))
        val sighting = main.substringAfter("val dialMatch = ").substringBefore("connectOutgoingPrivateCall(peer)")
        assertTrue(sighting.contains("DialPersonMatch.matches("))
        assertTrue(sighting.contains("dialMatch != null &&"))
        assertTrue(sighting.contains("\"matchedBy\" to dialMatch.name.lowercase()"))
    }
}
