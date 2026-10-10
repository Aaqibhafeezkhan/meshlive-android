package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PeerAddressBookTest {
    private var now = 0L
    private val book = PeerAddressBook({ now })

    @Test
    fun theFreshestSightingOfANodeWins() {
        book.record("aa01", "4A:00:00:00:00:01", routeCost = 0)
        now = 1_600L
        book.record("aa01", "4B:00:00:00:00:02", routeCost = 0)
        assertEquals("4B:00:00:00:00:02", book.freshest("aa01")?.address)
        // xpair-asusa15s-adf8236d-l3p-1 0003: the A15s kept dialling the replaced address.
        assertEquals("replaced", book.rescanReason("aa01", "4A:00:00:00:00:01"))
        assertNull(book.rescanReason("aa01", "4B:00:00:00:00:02"))
    }

    @Test
    fun anAddressOlderThanTheLimitIsRescannedFirst() {
        book.record("bb02", "11:22:33:44:55:66", routeCost = 1)
        now = PeerAddressBook.STALE_MS
        assertNull(book.rescanReason("bb02", "11:22:33:44:55:66"))
        now = PeerAddressBook.STALE_MS + 1
        assertEquals("stale", book.rescanReason("bb02", "11:22:33:44:55:66"))
    }

    @Test
    fun aNodeNeverSeenIsUnseen() = assertEquals("unseen", book.rescanReason("cc03", "11:22:33:44:55:66"))

    @Test
    fun theBookStaysBounded() {
        val small = PeerAddressBook({ now }, capacity = 2)
        small.record("01", "A", 0)
        small.record("02", "B", 0)
        small.record("03", "C", 0)
        assertNull(small.freshest("01"))
        assertEquals("C", small.freshest("03")?.address)
    }

    @Test
    fun fastFailuresRetryTwiceThenGiveUp() {
        assertEquals(LeConnectFailureAction.RETRY, LeConnectFailureClassifier.classify(400L, 133, fastRetriesUsed = 0))
        assertEquals(LeConnectFailureAction.RETRY, LeConnectFailureClassifier.classify(19_999L, 62, fastRetriesUsed = 1))
        assertEquals(LeConnectFailureAction.GIVE_UP, LeConnectFailureClassifier.classify(400L, 133, fastRetriesUsed = 2))
    }

    @Test
    fun aThirtySecond133MeansAStaleAddressNotABlindRetry() {
        assertEquals(LeConnectFailureAction.RESCAN, LeConnectFailureClassifier.classify(30_010L, 133, fastRetriesUsed = 0))
        assertEquals(LeConnectFailureAction.RESCAN, LeConnectFailureClassifier.classify(30_010L, null, fastRetriesUsed = 0))
        // A slow failure with another status is neither retried nor treated as a stale address.
        assertEquals(LeConnectFailureAction.GIVE_UP, LeConnectFailureClassifier.classify(26_000L, 8, fastRetriesUsed = 0))
        assertEquals(LeConnectFailureAction.GIVE_UP, LeConnectFailureClassifier.classify(21_000L, 133, fastRetriesUsed = 0))
    }

    @Test
    fun theExperimentIsOffByDefault() {
        assertFalse(PeerAddressBook.enabled())
    }
}
