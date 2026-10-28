package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edgecases-20261020 asus-s22-fb0e step 3: the ASUS dialled 70e1a0000001 1.2 s after the S22's epoch boundary; the S22
 * announced 70e2b0000002 from the same address, and the dial failed with refresh_timeout after 10 s.
 */
class DialPersonMatchTest {
    private val dialled = DialPersonMatch.Target(key = "70e1a0000001", contactPrefix = "b24b24b24b24", address = "4D:00:00:00:00:01")

    @Test
    fun aDialJustBeforeTheBoundaryProceedsOnTheNextEpochsIdOfTheSameContact() {
        assertEquals(DialPersonMatch.By.CONTACT, DialPersonMatch.matches(dialled, "70e2b0000002", "b24b24b24b24", "5A:11:22:33:44:55", on = true))
    }

    @Test
    fun aStrangersDialProceedsWhenTheStackKeptTheAddressAcrossTheEpoch() {
        val stranger = dialled.copy(contactPrefix = null)
        assertEquals(DialPersonMatch.By.ADDRESS, DialPersonMatch.matches(stranger, "70e2b0000002", null, "4d:00:00:00:00:01", on = true))
    }

    @Test
    fun theSameKeyAlwaysMatchesAndAnotherPhoneNever() {
        assertEquals(DialPersonMatch.By.KEY, DialPersonMatch.matches(dialled, "70e1a0000001", null, null, on = false))
        assertNull(DialPersonMatch.matches(dialled, "70e3c0000003", "c0a1c0a1c0a1", "7D:00:00:00:00:02", on = true))
        assertNull("an unrecognised rotating ID does not match a contact", DialPersonMatch.matches(dialled, "70e3c0000003", null, "7D:00:00:00:00:02", on = true))
        assertNull(DialPersonMatch.matches(null, "70e1a0000001", null, null, on = true))
    }

    @Test
    fun theControlArmMatchesOnlyTheExactKeyAsBefore() {
        assertNull(DialPersonMatch.matches(dialled, "70e2b0000002", "b24b24b24b24", "4D:00:00:00:00:01", on = false))
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
