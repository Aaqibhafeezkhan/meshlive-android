package com.example.bluetoothtest

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-04: Classic RFCOMM runtime guard, pairing-request log lines, in-progress registry and the 6-peer cap. */
class ClassicGuardsTest {
    private val lines = ArrayList<String>()

    @Test
    fun aFrameworkNullPointerExceptionBecomesAFailedAttempt() {
        val npe = NullPointerException("Attempt to invoke virtual method on a null object reference")
        val thrown = assertThrows(IOException::class.java) {
            ClassicConnectGuard.connect("call", { lines += it }) { throw npe }
        }
        assertSame("the cause stays attached for the log", npe, thrown.cause)
        assertTrue(thrown.message, "NullPointerException" in thrown.message.orEmpty())
        assertTrue(lines.single(), "owner=call" in lines.single())
    }

    @Test
    fun anIoExceptionPassesThroughUnchangedAndASuccessfulConnectIsSilent() {
        val failure = IOException("Service discovery failed")
        assertSame(failure, assertThrows(IOException::class.java) { ClassicConnectGuard.connect("call", { lines += it }) { throw failure } })
        var ran = false
        ClassicConnectGuard.connect("call", { lines += it }) { ran = true }
        assertTrue(ran)
        assertTrue("no log for the normal paths", lines.isEmpty())
    }

    @Test
    fun theGuardedFailureIsSeenByTheCandidateLoopAsAnOrdinaryFailure() {
        // connectAuthenticatedClassicCandidate catches Exception; the guard keeps the failure an IOException.
        class Conn : java.io.Closeable { override fun close() {} }
        val tried = ArrayList<String>()
        val result = runCatching {
            connectAuthenticatedClassicCandidate<String, Conn, Conn>(
                candidates = listOf("a", "b"),
                attemptTimeoutMs = { 1_000L },
                isCancelled = { false },
                open = { Conn() },
                onOpened = {},
                authenticate = { _ ->
                    ClassicConnectGuard.connect("call") { if (tried.add("x") && tried.size == 1) throw NullPointerException("npe") }
                    Conn()
                },
                verify = {},
            )
        }
        assertTrue("the second candidate connected after the first threw", result.isSuccess)
        assertEquals(2, tried.size)
    }

    @Test
    fun pairingLinesNameTheEventAndNeverTheFullAddress() {
        val request = ClassicPairingLog.requestLine("call", "AA:BB", 2)
        assertTrue(request, "event=pairing_request" in request && "variant=2" in request && "address=...AA:BB" in request)
        val bond = ClassicPairingLog.bondLine("call", "AA:BB", 11, 10)
        assertTrue(bond, "state=bonding" in bond && "previous=none" in bond)
        assertEquals("bonded", ClassicPairingLog.bondName(12))
        assertEquals("unknown(99)", ClassicPairingLog.bondName(99))
    }

    @Test
    fun aBondFreeConnectInProgressBlocksBondingForItsAddresses() {
        val address = "AA:BB:CC:DD:EE:01"
        assertTrue(ClassicConnectProgress.mayCreateBond(address))
        val inside = ClassicConnectProgress.track(listOf(address, "AA:BB:CC:DD:EE:02")) {
            ClassicConnectProgress.mayCreateBond(address.lowercase()) to ClassicConnectProgress.inProgress("AA:BB:CC:DD:EE:02")
        }
        assertEquals(false to true, inside)
        assertTrue("released afterwards", ClassicConnectProgress.mayCreateBond(address))
        assertThrows(IllegalStateException::class.java) {
            ClassicConnectProgress.track(listOf(address)) { throw IllegalStateException("boom") }
        }
        assertTrue("released even when the connect fails", ClassicConnectProgress.mayCreateBond(address))
    }

    @Test
    fun overlappingConnectsToTheSamePeerKeepItInProgressUntilTheLastEnds() {
        val address = "AA:BB:CC:DD:EE:03"
        ClassicConnectProgress.begin(listOf(address))
        ClassicConnectProgress.begin(listOf(address))
        ClassicConnectProgress.end(listOf(address))
        assertTrue(ClassicConnectProgress.inProgress(address))
        ClassicConnectProgress.end(listOf(address))
        assertFalse(ClassicConnectProgress.inProgress(address))
    }

    @Test
    fun aNewPeerIsRefusedAtTheCapButAConnectedPeerMayReconnect() {
        val peers = ClassicRfcommPeers()
        val open = BooleanArray(6) { true }
        for (i in 0 until 6) peers.note("AA:BB:CC:DD:EE:0$i") { open[i] }
        assertEquals(6, peers.openPeerCount())
        assertFalse("a seventh distinct peer is refused", peers.admit(listOf("AA:BB:CC:DD:EE:09"), cap = 6))
        assertTrue("an already connected peer may reconnect", peers.admit(listOf("aa:bb:cc:dd:ee:03"), cap = 6))
        assertTrue("one of several candidates being connected is enough", peers.admit(listOf("AA:BB:CC:DD:EE:09", "AA:BB:CC:DD:EE:00"), cap = 6))
        assertTrue("cap 0 means no cap", peers.admit(listOf("AA:BB:CC:DD:EE:09"), cap = 0))
        open[2] = false
        assertEquals("a closed socket frees its slot", 5, peers.openPeerCount())
        assertTrue(peers.admit(listOf("AA:BB:CC:DD:EE:09"), cap = 6))
    }

    @Test
    fun severalSocketsOfOnePeerCountOnce() {
        val peers = ClassicRfcommPeers()
        peers.note("AA:BB:CC:DD:EE:01") { true }
        peers.note("aa:bb:cc:dd:ee:01") { true }
        assertEquals(1, peers.openPeerCount())
    }

    @Test
    fun theCapComesFromTheRegistryEntryAndItsOffSwitch() {
        val traits = DeviceTraits("qcom", "x", 33)
        // Refusal is off by default (root review): the shipped switch value enforces nothing; the entry still supplies the log-only threshold.
        assertEquals(0, ClassicRfcommCap.capFor(CocPolicy.resolve(traits, emptySet()), CocSwitches.classicRfcommCap))
        assertEquals(6, ClassicRfcommCap.capFor(CocPolicy.resolve(traits, emptySet()), CocSwitches.DEFAULT_CLASSIC_RFCOMM_CAP))
        assertEquals(3, ClassicRfcommCap.capFor(CocPolicy.resolve(traits, emptySet()), 3))
        assertEquals(6, ClassicRfcommCap.watchCap(CocPolicy.resolve(traits, emptySet())))
        assertEquals(0, ClassicRfcommCap.watchCap(CocPolicy.resolve(traits, setOf("classic-rfcomm-peer-cap"))))
        assertEquals(0, ClassicRfcommCap.capFor(CocPolicy.resolve(traits, setOf("classic-rfcomm-peer-cap")), 6))
    }

    @Test
    fun theRefusalIsACapacityErrorThatNamesTheCounts() {
        val peers = ClassicRfcommCap.peers
        val addresses = (1..6).map { "AA:BB:CC:DD:EE:%02X".format(0x10 + it) }
        var open = true
        addresses.forEach { peers.note(it) { open } }
        try {
            val error = assertThrows(ClassicCapacityException::class.java) {
                ClassicRfcommCap.admitOrThrow(listOf("AA:BB:CC:DD:EE:99"), cap = 6) { lines += it }
            }
            assertEquals(6, error.cap)
            assertTrue(error.message, "capacity" in error.message.orEmpty().lowercase())
            assertTrue(lines.single(), "refused" in lines.single() && "cap=6" in lines.single())
            ClassicRfcommCap.admitOrThrow(listOf(addresses.first()), cap = 6) // reconnect passes
            // Default arm: nothing is refused, a seventh distinct peer only writes the log-only line.
            lines.clear()
            ClassicRfcommCap.admitOrThrow(listOf("AA:BB:CC:DD:EE:99"), cap = 0, watchCap = 6) { lines += it }
            assertTrue(lines.single(), "log_only" in lines.single() && "enforced=false" in lines.single())
        } finally {
            // The registry is process-wide: close the fake sockets so no slot is left for other tests.
            open = false
        }
        assertEquals(0, peers.openPeerCount())
    }
}
