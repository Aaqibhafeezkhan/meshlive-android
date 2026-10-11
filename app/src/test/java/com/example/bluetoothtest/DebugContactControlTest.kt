package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugContactControlTest {
    private lateinit var directory: File
    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private val token = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("debug-contact-control").toFile()
        PeerIdentityGate.resetForTest()
        DiscoveryWallClock.resetForTest()
    }

    @After
    fun tearDown() {
        PeerIdentityGate.resetForTest()
        DiscoveryWallClock.resetForTest()
        directory.deleteRecursively()
    }

    private fun store(name: String) =
        VerifiedContactStore(File(directory, name), cipher) { 1_700_000_000_000L }

    private fun identity() = KeyPairSigningIdentity(generateEcKeyPair())

    private fun write(name: String, text: String) = File(directory, name).apply { writeText(text) }

    @Test
    fun releaseAndNonDebuggableBuildsAreNeverAllowed() {
        assertTrue(DebugContactControl.isAllowed(buildDebug = true, debuggable = true))
        assertFalse(DebugContactControl.isAllowed(buildDebug = false, debuggable = true))
        assertFalse(DebugContactControl.isAllowed(buildDebug = true, debuggable = false))
        assertFalse(DebugContactControl.isAllowed(buildDebug = false, debuggable = false))
    }

    @Test
    fun consumeDoesNothingWhenNotAllowed() {
        val peer = IdentityFingerprint.of(identity())
        val file = write(DebugContactControl.VERIFY_FILE, "$token\n${peer.hex}\n")
        assertNull(DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, token, allowed = false))
        assertTrue("a refused build must not even consume the file", file.exists())
    }

    @Test
    fun tokenMismatchOrMalformedTokenIsIgnoredAndKeepsTheFile() {
        val peer = IdentityFingerprint.of(identity())
        val file = write(DebugContactControl.VERIFY_FILE, "$token\n${peer.hex}\n")
        val other = "fedcba9876543210fedcba9876543210"
        assertNull(DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, other, allowed = true))
        assertNull(DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, null, allowed = true))
        assertNull(DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, "", allowed = true))
        assertTrue(file.exists())
        // A stored token that is not 32-128 lowercase hex never matches, even when presented identically.
        write(DebugContactControl.VERIFY_FILE, "short\n${peer.hex}\n")
        assertNull(DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, "short", allowed = true))
        write(DebugContactControl.VERIFY_FILE, "${token.uppercase()}\n${peer.hex}\n")
        assertNull(DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, token.uppercase(), allowed = true))
        // No file at all.
        assertNull(DebugContactControl.consume(directory, "absent", token, allowed = true))
    }

    @Test
    fun theFileIsConsumedExactlyOnce() {
        val peer = IdentityFingerprint.of(identity())
        val file = write(DebugContactControl.VERIFY_FILE, "$token\n${peer.hex}\n")
        val first = DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, token, allowed = true)
        assertNotNull(first)
        assertEquals(peer, first!!.fingerprint)
        assertFalse(file.exists())
        assertNull(DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, token, allowed = true))
    }

    @Test
    fun malformedFingerprintIsRejectedAndTheFileStillBurnt() {
        val file = write(DebugContactControl.VERIFY_FILE, "$token\n${"A".repeat(64)}\n")
        assertNull(DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, token, allowed = true))
        assertFalse("a matching token spends the file even when the fingerprint is bad", file.exists())
        for (bad in listOf("", "ab".repeat(31), "ab".repeat(33), "zz".repeat(32))) {
            assertNull(DebugContactControl.parse("$token\n$bad\n", token))
        }
        assertNull(DebugContactControl.parse("$token\n", token))
        assertNull(DebugContactControl.parse(null, token))
    }

    @Test
    fun displayNameIsOptionalNormalisedAndFallsBackWhenInvalid() {
        val peer = IdentityFingerprint.of(identity())
        assertEquals(
            "Debug contact ${peer.nodePrefixHex.take(6)}",
            DebugContactControl.parse("$token\n${peer.hex}\n", token)!!.displayName,
        )
        assertEquals("Phone B", DebugContactControl.parse("$token\r\n${peer.hex}\r\nPhone B\r\n", token)!!.displayName)
        assertEquals(
            "Debug contact ${peer.nodePrefixHex.take(6)}",
            DebugContactControl.parse("$token\n${peer.hex}\nbad‮name\n", token)!!.displayName,
        )
    }

    @Test
    fun verifyWritesTheSameStoreStateAsTheQrFlow() {
        val peerIdentity = identity()
        val peer = IdentityFingerprint.of(peerIdentity)

        // QR path: authenticated handshake, then the facade marks the scanned fingerprint.
        val qrStore = store("qr.bin")
        val facade = IdentityVerification({ identity() }, qrStore)
        PeerIdentityGate.admit(peerIdentity.nodeId, peerIdentity.publicKeyEncoded)
        assertEquals(
            MarkVerifiedResult.VERIFIED,
            facade.markVerified(peerIdentity.nodeId, peer, "Debug contact ${peer.nodePrefixHex.take(6)}", VerificationMethod.QR_SCAN),
        )

        // Debug path: no handshake, a token-gated request.
        val debugStore = store("debug.bin")
        write(DebugContactControl.VERIFY_FILE, "$token\n${peer.hex}\n")
        val request = DebugContactControl.consume(directory, DebugContactControl.VERIFY_FILE, token, allowed = true)!!
        assertEquals("verified", DebugContactControl.verify(debugStore, own = IdentityFingerprint.of(identity()), request = request))

        assertEquals(qrStore.all(), debugStore.all())
        assertEquals(VerificationMethod.QR_SCAN, debugStore.all().single().method)
        assertEquals(VerificationState.VERIFIED, debugStore.state(peerIdentity.nodeId, peer))
        // Same sealed format: a fresh store over the debug file decodes it, and the fingerprint is not in the clear.
        val reopened = VerifiedContactStore(File(directory, "debug.bin"), cipher)
        assertEquals(debugStore.all(), reopened.all())
        val sealed = File(directory, "debug.bin").readBytes()
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(String(peer.bytes(), Charsets.ISO_8859_1)))
    }

    @Test
    fun verifyRefusesThisPhonesOwnFingerprintAndReportsAPrefixConflict() {
        val own = IdentityFingerprint.of(identity())
        val debugStore = store("debug.bin")
        val ownRequest = DebugContactControl.Request(own, "Me")
        assertEquals("self", DebugContactControl.verify(debugStore, own, ownRequest))
        assertTrue(debugStore.all().isEmpty())

        val real = IdentityFingerprint.of(identity())
        assertEquals("verified", DebugContactControl.verify(debugStore, own, DebugContactControl.Request(real, "Real")))
        val impostorBytes = ByteArray(32) { 0x55 }.also { System.arraycopy(real.bytes(), 0, it, 0, 6) }
        val impostor = IdentityFingerprint.fromBytes(impostorBytes)
        assertEquals("prefix_conflict", DebugContactControl.verify(debugStore, own, DebugContactControl.Request(impostor, "Fake")))
        assertEquals(listOf(real), debugStore.all().map { it.fingerprint })
    }

    @Test
    fun forgetUsesTheFacadeAndRemovesTheContact() {
        val peer = IdentityFingerprint.of(identity())
        val facadeStore = store("debug.bin")
        val facade = IdentityVerification({ identity() }, facadeStore)
        assertEquals("verified", DebugContactControl.verify(facadeStore, null, DebugContactControl.Request(peer, "Peer")))
        write(DebugContactControl.FORGET_FILE, "$token\n${peer.hex}\n")
        val request = DebugContactControl.consume(directory, DebugContactControl.FORGET_FILE, token, allowed = true)!!
        assertTrue(facade.forget(request.fingerprint))
        assertTrue(facade.verifiedContacts().isEmpty())
        assertFalse(facade.forget(request.fingerprint))
        assertFalse(File(directory, DebugContactControl.FORGET_FILE).exists())
    }

    @Test
    fun dayOffsetIsDebugOnlyBoundedForwardAndShiftsOnlyTheDiscoveryClock() {
        val day = 86_400_000L
        fun shift() = DiscoveryWallClock.nowMs() - System.currentTimeMillis()

        assertFalse(DiscoveryWallClock.setDayOffset(3, buildDebug = false, debuggable = true))
        assertFalse(DiscoveryWallClock.setDayOffset(3, buildDebug = true, debuggable = false))
        assertFalse(DiscoveryWallClock.setDayOffset(-1, buildDebug = true, debuggable = true))
        assertFalse(DiscoveryWallClock.setDayOffset(DiscoveryWallClock.MAX_DAYS + 1, buildDebug = true, debuggable = true))
        assertTrue(shift() in 0..1_000L)

        assertTrue(DiscoveryWallClock.setDayOffset(2, buildDebug = true, debuggable = true))
        assertEquals(2, DiscoveryWallClock.dayOffset)
        assertTrue(shift() - 2 * day in 0..1_000L)
        // The selection check, the recognition clock and the presence vault read the shifted clock.
        assertTrue(DiscoverySelectionCheck.systemUnixSeconds() * 1000L - System.currentTimeMillis() - 2 * day in -1_000L..2_000L)
        // An out-of-range request after a valid one does not change it.
        assertFalse(DiscoveryWallClock.setDayOffset(99, buildDebug = true, debuggable = true))
        assertEquals(2, DiscoveryWallClock.dayOffset)
        assertTrue(DiscoveryWallClock.setDayOffset(0, buildDebug = true, debuggable = true))
        assertTrue(shift() in 0..1_000L)
    }
}
