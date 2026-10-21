package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design QL revision 2: the recognition choice at the scan, the plain result and Call now (DECISIONS tests list). */
class QlConsentTest {
    private val peer = IdentityFingerprint.fromBytes(ByteArray(32) { (it + 7).toByte() })

    @Test
    fun anUncheckedBoxIsWrittenBeforeTheSessionMayOfferAndWithholdsTheLease() {
        val writes = ArrayList<Boolean>()
        val mayOffer = QlConsent.applyAnswer(recognise = false, vaultUsable = true) { writes += it }
        assertEquals("written first", listOf(false), writes)
        assertFalse("no OFFER after a refusal", mayOffer)
    }

    @Test
    fun aFailedWriteWithholdsTheLease() {
        assertFalse(QlConsent.applyAnswer(recognise = true, vaultUsable = true) { error("store unreadable") })
    }

    @Test
    fun aCheckedBoxStoresTheChoiceAndLetsTheExchangeGoOn() {
        val writes = ArrayList<Boolean>()
        assertTrue(QlConsent.applyAnswer(recognise = true, vaultUsable = true) { writes += it })
        assertEquals(listOf(true), writes)
    }

    @Test
    fun withoutAPresenceStoreAYesGoesOnAsBeforeAndANoWithholds() {
        assertTrue(QlConsent.applyAnswer(recognise = true, vaultUsable = false) { error("not called") })
        assertFalse(QlConsent.applyAnswer(recognise = false, vaultUsable = false) { error("not called") })
    }

    private fun bothScanned(session: MeetSession) {
        session.scannedByPeer(peer)
        session.scanReached(peer, "Asha")
        assertTrue(session.confirmScreen(peer, yes = true))
    }

    @Test
    fun theResultNamesEachOutcome() {
        val one = MeetSession()
        assertNull(one.qlResult(peer))
        one.scannedByPeer(peer)
        assertEquals(MeetSession.QlResult.ONE_SCAN, one.qlResult(peer))

        val notFinished = MeetSession().also(::bothScanned)
        assertEquals("no reason guessed", MeetSession.QlResult.NOT_FINISHED, notFinished.qlResult(peer))

        val declined = MeetSession().also(::bothScanned)
        declined.declinedRecognition(peer)
        assertEquals(MeetSession.QlResult.DECLINED, declined.qlResult(peer))

        val recognised = MeetSession().also(::bothScanned)
        recognised.leaseStored(peer, nowMs = 1_000L)
        assertEquals(MeetSession.QlResult.RECOGNISED, recognised.qlResult(peer))
    }

    @Test
    fun callNowShowsOnlyForSixtySecondsAfterALeaseStoredInThisVisit() {
        val session = MeetSession().also(::bothScanned)
        assertFalse("no lease, no Call now", session.callNowVisible(peer, nowMs = 5_000L))
        session.leaseStored(peer, nowMs = 10_000L)
        assertTrue(session.callNowVisible(peer, nowMs = 10_000L))
        assertTrue(session.callNowVisible(peer, nowMs = 69_999L))
        assertFalse(session.callNowVisible(peer, nowMs = 70_000L))
        val declined = MeetSession().also(::bothScanned)
        declined.declinedRecognition(peer)
        assertFalse(declined.callNowVisible(peer, nowMs = 10_000L))
    }
}
