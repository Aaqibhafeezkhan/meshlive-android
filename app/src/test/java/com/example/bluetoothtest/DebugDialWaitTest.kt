package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lane10 b1b-calls-a15s-s24 audio-a15s-s24-r1 (and lane9 b9-newbuild-calls2 audio-a15s-s24): `voice_call:0096b299a99f`
 * was accepted ("applied") 80 ms after a lifecycle scan restart. The dial then waited the fixed 3 s for a fresh sighting;
 * the first sighting of the new scanner came 4.5 s after its start and the expiry changed only a status text, so no
 * invite and no log line followed for 140 s.
 */
class DebugDialWaitTest {
    private val dialAcceptedAtMs = 55_050L
    private val firstSightingAtMs = 59_543L // adv_address_seen on the new scanner

    @Test
    fun theOldFixedWaitExpiredBeforeTheFirstSightingOfTheRestartedScanner() {
        val waitedMs = firstSightingAtMs - dialAcceptedAtMs
        assertEquals(4_493L, waitedMs)
        assertTrue("the automatic exchange keeps its short 3 s wait", waitedMs > DebugDialWait.AUTO_REFRESH_MS)
        assertEquals(DebugDialWait.AUTO_REFRESH_MS, DebugDialWait.refreshTimeoutMs(auto = true))
    }

    @Test
    fun aUserTapWaitsAsLongAsADebugDial() {
        // lane 11 b2x-ab-a15s-asus-audio calls 03/04/08: first sighting after the onResume restart took 3.7-5.8 s.
        assertEquals(DebugDialWait.WAIT_MS, DebugDialWait.refreshTimeoutMs(auto = false))
        for (firstSightingMs in listOf(3_700L, 5_800L)) {
            val dial = DebugDialWait.Pending("680f5915a1b2", CallType.VOICE, false, startedAtMs = 0L, displayName = "Asus")
            assertFalse("a sighting after $firstSightingMs ms still starts the call", dial.expired(firstSightingMs))
        }
    }

    @Test
    fun theUserSeesLookingThenAReasonedNotFoundMessage() {
        assertEquals("Looking for Asus\u2026", DebugDialWait.lookingStatus("Asus"))
        val notSeen = DebugDialWait.notFoundStatus("Asus", scannerFailureCode = null)
        assertTrue(notSeen.startsWith("Asus is no longer reachable"))
        assertTrue(notSeen.contains("not heard in 10 s"))
        val scanner = DebugDialWait.notFoundStatus("Asus", scannerFailureCode = 2)
        assertTrue(scanner.startsWith("Asus was not found nearby"))
        assertTrue(scanner.contains("scanner is unavailable"))
        assertTrue(scanner.contains(ScanFailurePolicy.name(2)))
        assertEquals("scanner_unavailable", DebugDialWait.expiryReason(2))
        assertEquals("peer_not_seen", DebugDialWait.expiryReason(null))
    }

    @Test
    fun theNotFoundNoticeStaysVisibleForABoundedTime() {
        assertTrue(DebugDialWait.noticeVisible(shownAtMs = 1_000L, nowMs = 1_000L))
        assertTrue(DebugDialWait.noticeVisible(1_000L, 1_000L + DebugDialWait.NOTICE_MS - 1))
        assertFalse(DebugDialWait.noticeVisible(1_000L, 1_000L + DebugDialWait.NOTICE_MS))
        assertFalse("a clock that went backwards hides it", DebugDialWait.noticeVisible(2_000L, 1_000L))
    }

    @Test
    fun aDebugDialWaitsLongEnoughForThatSighting() {
        assertEquals(10_000L, DebugDialWait.refreshTimeoutMs(auto = false))
        val dial = DebugDialWait.Pending("0096b299a99f", CallType.VOICE, messageSync = false, startedAtMs = dialAcceptedAtMs)
        assertFalse(dial.expired(firstSightingAtMs))
        assertEquals(dialAcceptedAtMs + 10_000L - firstSightingAtMs, dial.remainingMs(firstSightingAtMs))
        assertEquals(4_493L, dial.waitedMs(firstSightingAtMs))
        assertTrue(dial.matches("0096B299A99F"))
        assertFalse(dial.matches("1e3f0761977b"))
    }

    @Test
    fun theWaitIsBoundedAndNeverNegative() {
        val dial = DebugDialWait.Pending("0096b299a99f", CallType.VIDEO, messageSync = false, startedAtMs = 1_000L)
        assertFalse(dial.expired(10_999L))
        assertTrue(dial.expired(11_000L))
        assertEquals(0L, dial.remainingMs(20_000L))
        assertEquals(0L, dial.waitedMs(500L))
    }

    @Test
    fun everyOutcomeHasAGreppableLine() {
        assertEquals(
            "BENCHMARK call_invite: failed | node=0096b299a99f | reason=refresh_timeout | waitedMs=10000",
            DebugDialWait.line("failed", "0096b299a99f", "reason" to "refresh_timeout", "waitedMs" to 10_000L),
        )
        assertEquals("BENCHMARK call_invite: peer_seen | node=ab", DebugDialWait.line("peer_seen", "ab"))
    }

    // ---- source guards: the paths that used to drop a dial silently stay closed ----

    private fun mainActivity(): String {
        val file = listOf(
            File("src/main/java/com/example/bluetoothtest/MainActivity.kt"),
            File("app/src/main/java/com/example/bluetoothtest/MainActivity.kt"),
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun theDebugDialUsesTheBoundedWaitAndLogsItsRefusals() {
        val source = mainActivity()
        val dial = source.substringAfter("private fun debugInvitePrivateCall(").substringBefore("private fun debugDialBlockReason")
        assertTrue(dial.contains("refreshTimeoutMs = DebugDialWait.refreshTimeoutMs(auto = false)"))
        assertTrue("a briefly absent target waits", dial.contains("DebugDialWait.Pending("))
        assertTrue(dial.contains("DebugDialWait.line(\"refused\""))
        // The selection's expiry logs a failure instead of changing only a status text.
        val timeout = source.substringAfter("private fun startOutgoingPrivateCall(").substringBefore("private fun connectOutgoingPrivateCall")
        assertTrue(timeout.contains("\"refresh_timeout\""))
        // A discovery restart keeps a waiting DEBUG dial instead of clearing it with the pending selection.
        val stop = source.substringAfter("private fun stopPrivateCallDiscovery()").substringBefore("private fun chatGate")
        assertTrue(stop.contains("discovery_restarted"))
    }

    @Test
    fun aUserDialWaitsSurvivesARestartAndExplainsItsExpiry() {
        val source = mainActivity()
        val start = source.substringAfter("private fun startOutgoingPrivateCall(").substringBefore("private fun connectOutgoingPrivateCall")
        // The 3 s constant is gone; only an automatic exchange keeps the short wait.
        assertFalse(source.contains("PRIVATE_CALL_SELECTION_REFRESH_TIMEOUT_MS"))
        assertTrue(start.contains("refreshTimeoutMs: Long = DebugDialWait.refreshTimeoutMs(auto)"))
        // Not gated on DEBUG: user dials track their wait, show the name and log call_invite lines (DEBUG builds only).
        assertFalse("the selection wait is not DEBUG-only", start.contains("if (BuildConfig.DEBUG) {\n            dialSelectionStartedAtMs"))
        assertTrue(start.contains("dialSelectionStartedAtMs = if (auto) 0L else pendingPrivateCallSelectedAtMs"))
        assertTrue(start.contains("showDialNotFound("))
        assertTrue(start.contains("GattPoolBackoff.scannerFailureCode()"))
        assertTrue(start.contains("\"scanner_unavailable\""))
        assertFalse(start.contains("not responding yet"))
        // The restart converts the user dial into a bounded waiting dial that carries its name.
        val stop = source.substringAfter("private fun stopPrivateCallDiscovery()").substringBefore("private fun chatGate")
        assertFalse(stop.contains("if (BuildConfig.DEBUG && dialSelectionStartedAtMs"))
        assertTrue(stop.contains("displayName = pendingPrivateCallPeerName"))
        // Sightings resume a waiting dial in release builds too, and the status line keeps the waiting/failed message.
        assertTrue(source.contains("val waitingDial = debugDialWait\n"))
        // The expiry of a restarted dial logs its reason and tells the user.
        val expire = source.substringAfter("private fun expireDebugDialWait()").substringBefore("private fun resumeDebugDialWait")
        assertTrue(expire.contains("DebugDialWait.expiryReason(scanFailure)"))
        assertTrue(expire.contains("showDialNotFound(dial.prefix, it, scanFailure)"))
        // Release builds log no call_invite line (no node IDs in a user's logcat).
        assertTrue(source.contains("private fun logCallInvite(line: String) {\n        if (BuildConfig.DEBUG) Log.i("))
        // A contact called while not listed waits through the same machinery, matching a recognised rotating ID too.
        val contactCall = source.substringAfter("private fun callContact(").substringBefore("private var dialWaitSnackbar")
        assertTrue(contactCall.contains("if (ContactReach.mayWaitForCallBack(hint))"))
        assertTrue(contactCall.contains("startContactDialWait(contact, video)"))
        assertTrue(source.contains("waitingDial.matchesPerson(key, if (waitingDial.contact) recognisedContactPrefix(peer) else null)"))
    }

    /**
     * Phone runs userflow-b57d0eaf-20261019 (S22 20:35:30, 20:36:03): a Recent or Contacts call right after a call found an
     * empty call list and failed at once. The contact dial now waits for a sighting of its fixed prefix or of a rotating ID
     * recognised as exactly this contact.
     */
    @Test
    fun aContactDialMatchesItsPrefixOrARecognisedRotatingIdAndNothingElse() {
        val contact = DebugDialWait.Pending("1c5fbbcd07e0", CallType.VIDEO, false, startedAtMs = 0L, displayName = "Asha", contact = true)
        assertTrue(contact.matchesPerson("1C5FBBCD07E0", null))
        assertTrue("a rotating ID recognised as this contact", contact.matchesPerson("a1b2c3d4e5f6", "1c5fbbcd07e0"))
        assertFalse("recognised as someone else", contact.matchesPerson("a1b2c3d4e5f6", "9c8d7e6f5a40"))
        assertFalse("not recognised", contact.matchesPerson("a1b2c3d4e5f6", null))
        val row = DebugDialWait.Pending("1c5fbbcd07e0", CallType.VIDEO, false, startedAtMs = 0L, displayName = "Asha")
        assertFalse("a row or DEBUG dial matches its advertised key only", row.matchesPerson("a1b2c3d4e5f6", "1c5fbbcd07e0"))
        assertFalse(contact.expired(DebugDialWait.WAIT_MS - 1))
        assertTrue(contact.expired(DebugDialWait.WAIT_MS))
    }
}
