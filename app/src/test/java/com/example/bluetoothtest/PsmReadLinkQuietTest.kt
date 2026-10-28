package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsmReadLinkQuietTest {
    @Test
    fun psmReadLinkClosedForAFreshAclAsksForNothing() {
        val quiet = PsmReadLinkQuiet.appliesTo(switchOn = true, freshAcl = true)
        assertTrue(quiet)
        assertEquals(
            PsmReadLinkQuiet.Requests(priority = false, phy = false, mtu = false),
            PsmReadLinkQuiet.requests(quiet, knownPsm = false, primeMtu = true),
        )
    }

    @Test
    fun controlLinkThatCarriesTheCallKeepsItsRequests() {
        // Without a fresh ACL the PSM-read GATT client stays up as the call's control link.
        val quiet = PsmReadLinkQuiet.appliesTo(switchOn = true, freshAcl = false)
        assertFalse(quiet)
        assertEquals(
            PsmReadLinkQuiet.Requests(priority = true, phy = true, mtu = true),
            PsmReadLinkQuiet.requests(quiet, knownPsm = false, primeMtu = true),
        )
    }

    @Test
    fun reattachedClientOnTheFreshAclKeepsPriorityAndPhy() {
        // The attach after the CoC connect has a known PSM; it is the link the call uses.
        assertEquals(
            PsmReadLinkQuiet.Requests(priority = true, phy = true, mtu = false),
            PsmReadLinkQuiet.requests(quiet = true, knownPsm = true, primeMtu = false),
        )
    }

    @Test
    fun switchOffLeavesEveryLinkUnchanged() {
        val quiet = PsmReadLinkQuiet.appliesTo(switchOn = false, freshAcl = true)
        assertFalse(quiet)
        assertEquals(
            PsmReadLinkQuiet.Requests(priority = true, phy = true, mtu = true),
            PsmReadLinkQuiet.requests(quiet, knownPsm = false, primeMtu = true),
        )
    }
}
