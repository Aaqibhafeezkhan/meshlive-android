package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrangerCallFollowupTest {
    @Test fun endedStrangerCallOffersAddWithoutNeedingAStoredIdentity() {
        val stranger = RecentCall("", true, 1, strangerName = "gold fox")
        assertTrue(StrangerCallFollowup.offer(stranger, emptySet(), true))
        assertFalse(StrangerCallFollowup.offer(stranger, emptySet(), false))
    }

    @Test fun savedContactsBlockedCallsAndMissingCallsNeverOfferAdd() {
        val known = RecentCall("8a1b2c3d4e5f", false, 1)
        assertTrue(StrangerCallFollowup.offer(known, emptySet(), true))
        assertFalse(StrangerCallFollowup.offer(known, setOf(known.nodePrefixHex), true))
        assertFalse(StrangerCallFollowup.offer(known.copy(blocked = true), emptySet(), true))
        assertFalse(StrangerCallFollowup.offer(null, emptySet(), true))
    }
}
