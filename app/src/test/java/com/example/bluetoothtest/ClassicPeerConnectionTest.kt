package com.example.bluetoothtest

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicPeerConnectionTest {
    private data class Peer(val address: String, val name: String? = null)
    private class Connection(val address: String) : Closeable {
        val closed = CountDownLatch(1)
        override fun close() { closed.countDown() }
    }
    private class Session(val identity: String) : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test
    fun unnamedPrivateAddressTriesBondedDevicesBeforeUnresolvedBleAddress() {
        val discovered = Peer("5A:00:00:00:00:03")
        val bonded = listOf(Peer("AA:BB:CC:00:00:01"), Peer("AA:BB:CC:00:00:02"))

        assertEquals(bonded + discovered, classicConnectionCandidates(discovered, bonded, Peer::address, Peer::name))
    }

    @Test
    fun bondedFallbackSkipsDevicesThatCannotHostTheAppAndPrefersKnownAppService() {
        val earbuds = Peer("AA:BB:CC:00:00:01", "Buds")
        val car = Peer("AA:BB:CC:00:00:02", "Car")
        val phone = Peer("AA:BB:CC:00:00:03", "Phone")
        val appPhone = Peer("AA:BB:CC:00:00:04", "Other phone")
        val discovered = Peer("5A:00:00:00:00:03")

        assertEquals(
            listOf(appPhone, phone, discovered),
            classicConnectionCandidates(
                discovered,
                listOf(earbuds, car, phone, appPhone),
                Peer::address,
                Peer::name,
                canHostApp = { it == phone || it == appPhone },
                advertisesAppService = { it == appPhone },
            ),
        )
    }

    @Test
    fun audioAndCarClassesCannotHostTheAppButUnknownClassRemainsEligible() {
        assertTrue(classicMajorClassCanHostApp(android.bluetooth.BluetoothClass.Device.Major.PHONE))
        assertTrue(classicMajorClassCanHostApp(android.bluetooth.BluetoothClass.Device.Major.COMPUTER))
        assertTrue(classicMajorClassCanHostApp(null))
        assertFalse(classicMajorClassCanHostApp(android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO))
        assertFalse(classicMajorClassCanHostApp(android.bluetooth.BluetoothClass.Device.Major.WEARABLE))
    }

    @Test
    fun resolvedAddressRetainsFastPathAndDuplicateNameIsNotPreferred() {
        val first = Peer("AA:BB:CC:00:00:01", "Phone")
        val resolved = Peer("AA:BB:CC:00:00:02", "Phone")
        val discovered = Peer("aa:bb:cc:00:00:02", "Phone")

        assertEquals(listOf(resolved, first), classicConnectionCandidates(discovered, listOf(first, resolved), Peer::address, Peer::name))
        val unresolved = Peer("5A:00:00:00:00:03", "Phone")
        assertEquals(listOf(first, resolved, unresolved), classicConnectionCandidates(unresolved, listOf(first, resolved), Peer::address, Peer::name))
    }

    @Test
    fun wrongIdentityIsClosedBeforeAnotherCandidateCanBeAdmitted() {
        val connections = mutableListOf<Connection>()
        val sessions = mutableListOf<Session>()
        val accepted = connectAuthenticatedClassicCandidate(
            listOf("wrong", "expected"), { 1_000L }, { false },
            { identity ->
                if (identity == "expected") {
                    assertEquals(0L, connections.single().closed.count)
                    assertTrue(sessions.single().closed)
                }
                Connection(identity).also(connections::add)
            }, {},
            { connection -> Session(connection.address).also(sessions::add) },
            { session -> require(session.identity == "expected") },
        )

        assertSame(connections.last(), accepted.first)
        assertSame(sessions.last(), accepted.second)
        assertEquals(1L, accepted.first.closed.count)
        assertFalse(accepted.second.closed)
    }

    @Test(timeout = 5_000L)
    fun deadlineClosesBlockedHandshakeAndRejectsItsLateSessionBeforeFallback() {
        val sessions = mutableListOf<Session>()
        val connections = mutableListOf<Connection>()
        val accepted = connectAuthenticatedClassicCandidate(
            listOf("blocked", "ready"), { if (it == "blocked") 30L else 1_000L }, { false },
            { Connection(it).also(connections::add) }, {},
            { connection ->
                if (connection.address == "blocked") {
                    assertTrue(connection.closed.await(120, TimeUnit.SECONDS))
                }
                Session("expected").also(sessions::add)
            },
            { session -> require(session.identity == "expected") },
        )

        assertEquals("ready", accepted.first.address)
        assertEquals(0L, connections.first().closed.count)
        assertTrue(sessions.first().closed)
        assertFalse(accepted.second.closed)
    }

    @Test
    fun debugAndLearnedAddressesGoFirstAndOnlyExactAddressesGetTheResolvedBudget() {
        val discovered = Peer("5A:00:00:00:00:03", "Phone")
        val bondedPhone = Peer("AA:BB:CC:00:00:01", "Phone")
        val otherPhone = Peer("AA:BB:CC:00:00:02", "Tablet")
        val learned = Peer("AA:BB:CC:00:00:09")
        val debug = Peer("AA:BB:CC:00:00:0A")

        val plan = classicCandidatePlan(
            discovered,
            listOf(bondedPhone, otherPhone),
            Peer::address,
            Peer::name,
            learned = learned,
            debugOverride = debug,
        )

        assertEquals(listOf(debug, learned, bondedPhone, otherPhone, discovered), plan.map { it.device })
        assertEquals(
            listOf(
                ClassicCandidateKind.DEBUG,
                ClassicCandidateKind.LEARNED,
                ClassicCandidateKind.BONDED_NAME,
                ClassicCandidateKind.BONDED_FALLBACK,
                ClassicCandidateKind.DISCOVERED,
            ),
            plan.map { it.kind },
        )
        assertEquals(12_000L, classicAttemptTimeoutMs(ClassicCandidateKind.LEARNED))
        assertEquals(12_000L, classicAttemptTimeoutMs(ClassicCandidateKind.BONDED_ADDRESS))
        assertEquals(3_000L, classicAttemptTimeoutMs(ClassicCandidateKind.BONDED_NAME))
        assertEquals(3_000L, classicAttemptTimeoutMs(ClassicCandidateKind.BONDED_FALLBACK))
        // An unresolved BLE address is a guess: one page timeout (5.12 s) plus SDP, not the old 12 s.
        assertEquals(7_000L, classicAttemptTimeoutMs(ClassicCandidateKind.DISCOVERED))
        assertFalse(classicPlanIsUnresolved(plan))
    }

    @Test
    fun learnedAddressEqualToABondedDeviceIsTriedOnceAsLearned() {
        val bonded = Peer("AA:BB:CC:00:00:01")
        val plan = classicCandidatePlan(
            Peer("5A:00:00:00:00:03"),
            listOf(bonded),
            Peer::address,
            Peer::name,
            learned = Peer("aa:bb:cc:00:00:01"),
        )

        assertEquals(listOf(ClassicCandidateKind.LEARNED, ClassicCandidateKind.DISCOVERED), plan.map { it.kind })
    }

    @Test
    fun knownRandomBleAddressIsNeverPagedAndAnUnbondedPeerFailsFastAsUnresolved() {
        val discovered = Peer("5A:00:00:00:00:03")

        val noBonds = classicCandidatePlan(discovered, emptyList(), Peer::address, Peer::name, discoveredIsRandomAddress = true)
        assertTrue(noBonds.isEmpty())
        assertTrue(classicPlanIsUnresolved(noBonds))

        val onlyGuesses = classicCandidatePlan(
            discovered,
            listOf(Peer("AA:BB:CC:00:00:01")),
            Peer::address,
            Peer::name,
        )
        assertEquals(
            listOf(ClassicCandidateKind.BONDED_FALLBACK, ClassicCandidateKind.DISCOVERED),
            onlyGuesses.map { it.kind },
        )
        assertTrue(classicPlanIsUnresolved(onlyGuesses))
        assertTrue(classicUnresolvedPeerMessage(bondFree = false).contains("Pair the phones once"))
        assertTrue(classicUnresolvedPeerMessage(bondFree = true).contains("visible"))
    }

    @Test
    fun resolvedBondedAddressKeepsItsKindWhenTheScanAlreadyResolvedTheIdentity() {
        val resolved = Peer("AA:BB:CC:00:00:02")
        val plan = classicCandidatePlan(
            Peer("aa:bb:cc:00:00:02"),
            listOf(Peer("AA:BB:CC:00:00:01"), resolved),
            Peer::address,
            Peer::name,
        )

        assertEquals(resolved, plan.first().device)
        assertEquals(ClassicCandidateKind.BONDED_ADDRESS, plan.first().kind)
        assertEquals(2, plan.size)
    }

    @Test
    fun totalBudgetStopsBeforeAnotherCandidateWithTheLastFailureAsCause() {
        var nowNs = 0L
        val opened = mutableListOf<String>()

        val error = assertThrows(IOException::class.java) {
            connectAuthenticatedClassicCandidate(
                listOf("first", "second", "third"),
                { 3_000L },
                { false },
                { name -> opened += name; nowNs += 3_000_000_000L; Connection(name) },
                {},
                { throw IOException("page timeout") },
                { _: Session -> },
                totalBudgetMs = 5_000L,
                nanoTime = { nowNs },
            )
        }

        assertEquals(listOf("first", "second"), opened)
        assertTrue(error.message!!.contains("budget"))
        assertEquals("page timeout", error.cause?.message)
    }

    @Test
    fun directRoutesNeedTheAnnouncedIdentityAndRelayedRoutesMustNotSkipTheRelay() {
        val endpoint = ByteArray(16) { (it + 1).toByte() }
        val relay = ByteArray(16) { (it + 40).toByte() }
        val announced = endpoint.copyOf(6)

        assertEquals(null, classicLinkIdentityProblem(endpoint, announced, directRoute = true))
        assertTrue(classicLinkIdentityProblem(relay, announced, directRoute = true)!!.contains("changed"))
        assertEquals(null, classicLinkIdentityProblem(relay, announced, directRoute = false))
        assertTrue(classicLinkIdentityProblem(endpoint, announced, directRoute = false)!!.contains("directly"))
        assertEquals(null, classicLinkIdentityProblem(relay, null, directRoute = true))
    }

    /**
     * Review 2026-10-27 (finding 5): a Classic call to a phone in pseudonym mode met a throwaway at the link, failed the
     * prefix comparison ("identity changed") and made the caller forget the correct learned address. A throwaway has no
     * node ID to compare (the reveal binds the callee later); only a real key that proved to be someone else disproves an
     * address.
     */
    @Test
    fun aThrowawayLinkPeerIsNotComparedAndNeverCostsTheLearnedAddress() {
        val throwaway = ByteArray(16) { (it + 90).toByte() }
        val announced = ByteArray(6) { (it + 1).toByte() }
        assertEquals(null, classicLinkIdentityProblem(throwaway, announced, directRoute = true, peerPseudonymous = true))
        assertEquals(null, classicLinkIdentityProblem(throwaway, announced, directRoute = false, peerPseudonymous = true))
        // Review 2026-10-27 (finding 3): a throwaway from a guessed address (bonded fallback, discovered, inquiry) proves
        // nothing about which phone answered, so another phone could ring: that candidate fails and the next one is tried.
        for (kind in ClassicCandidateKind.entries) {
            val problem = classicLinkIdentityProblem(throwaway, announced, directRoute = true, peerPseudonymous = true, resolvedCandidate = kind.resolved)
            assertEquals(kind.name, !kind.resolved, problem != null)
        }
        val wrongPerson = ClassicLinkIdentityException(requireNotNull(classicLinkIdentityProblem(throwaway, announced, directRoute = true)))
        assertTrue(classicLearnedAddressDisproved(ClassicCandidateKind.LEARNED, wrongPerson))
        assertFalse(classicLearnedAddressDisproved(ClassicCandidateKind.DISCOVERED, wrongPerson))
        assertFalse("a failed check of another kind says nothing about the address",
            classicLearnedAddressDisproved(ClassicCandidateKind.LEARNED, IllegalArgumentException("Peer did not authenticate Classic support")))
        assertFalse(classicLearnedAddressDisproved(ClassicCandidateKind.LEARNED, java.io.IOException("page timeout")))
    }

    @Test
    fun legacyPlanKeepsEachRolesPreviousCandidatesAndDeadlines() {
        val discovered = Peer("5A:00:00:00:00:03", "Phone")
        val earbuds = Peer("AA:BB:CC:00:00:01", "Buds")
        val phone = Peer("AA:BB:CC:00:00:02", "Phone")
        val bonded = listOf(earbuds, phone)
        val canHost = { peer: Peer -> peer != earbuds }

        assertEquals(
            listOf(phone),
            legacyClassicCandidatePlan(ClassicRole.PRIVATE_CALL, discovered, bonded, Peer::address, Peer::name, canHost)
                .map { it.device },
        )
        // The call relay paged every bonded device, earbuds included, as it did before.
        assertEquals(
            listOf(phone, earbuds, discovered),
            legacyClassicCandidatePlan(ClassicRole.CALL_RELAY, discovered, bonded, Peer::address, Peer::name, canHost)
                .map { it.device },
        )
        assertEquals(
            classicConnectionCandidates(discovered, bonded, Peer::address, Peer::name, canHost),
            legacyClassicCandidatePlan(ClassicRole.STREAM_SENDER, discovered, bonded, Peer::address, Peer::name, canHost)
                .map { it.device },
        )
        assertEquals(12_000L, legacyClassicAttemptTimeoutMs(ClassicRole.STREAM_SENDER, ClassicCandidateKind.DISCOVERED))
        assertEquals(3_000L, legacyClassicAttemptTimeoutMs(ClassicRole.STREAM_SENDER, ClassicCandidateKind.BONDED_NAME))
        assertEquals(Long.MAX_VALUE, legacyClassicAttemptTimeoutMs(ClassicRole.STREAM_RELAY, ClassicCandidateKind.DISCOVERED))
    }

    @Test
    fun cancellationDuringAuthenticationClosesAttemptAndDoesNotTryMoreDevices() {
        val cancelled = AtomicBoolean(false)
        val opened = mutableListOf<Connection>()
        val session = Session("expected")

        assertThrows(IOException::class.java) {
            connectAuthenticatedClassicCandidate(
                listOf("first", "second"), { 1_000L }, cancelled::get,
                { Connection(it).also(opened::add) }, {},
                { cancelled.set(true); session },
                { require(it.identity == "expected") },
            )
        }
        assertEquals(listOf("first"), opened.map(Connection::address))
        assertEquals(0L, opened.single().closed.count)
        assertTrue(session.closed)
    }
}
