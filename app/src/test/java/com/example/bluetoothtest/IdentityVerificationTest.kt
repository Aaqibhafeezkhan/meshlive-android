package com.example.bluetoothtest

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IdentityVerificationTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private lateinit var directory: File
    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("identity-verification").toFile()
        PeerIdentityGate.resetForTest()
    }

    @After
    fun tearDown() {
        PeerIdentityGate.resetForTest()
        WipeGuard.resetForTest()
        directory.deleteRecursively()
    }

    private fun store() = VerifiedContactStore(File(directory, "verified-contacts.bin"), cipher) { 1_700_000_000_000L }

    @Test
    fun fingerprintIsSha256OverTheExactIdentityKeyEncodingAndExtendsTheNodeId() {
        val identity = KeyPairSigningIdentity(generateEcKeyPair())
        val fingerprint = IdentityFingerprint.of(identity)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(identity.publicKeyEncoded), fingerprint.bytes())
        assertEquals(identity.nodeId, fingerprint.nodeId)
        assertEquals(identity.nodeId.toString().take(12), fingerprint.nodePrefixHex)
        // Fixed vector: SHA-256("abc").
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            IdentityFingerprint.fromPublicKey("abc".toByteArray()).hex,
        )
        assertEquals(fingerprint, IdentityFingerprint.fromHex(fingerprint.hex))
        assertThrows(IllegalArgumentException::class.java) { IdentityFingerprint.fromHex(fingerprint.hex.uppercase()) }
        assertThrows(IllegalArgumentException::class.java) { IdentityFingerprint.fromHex(fingerprint.hex.drop(2)) }
    }

    @Test
    fun safetyCodeIsSymmetricStableAndPairSpecific() {
        val a = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        val b = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        val c = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        val code = SafetyCode.derive(a, b)
        assertEquals(code, SafetyCode.derive(b, a))
        assertEquals(code, SafetyCode.derive(IdentityFingerprint.fromHex(a.hex), IdentityFingerprint.fromHex(b.hex)))
        assertNotEquals(code, SafetyCode.derive(a, c))
        assertTrue(code.matches(Regex("\\d{5}( \\d{5}){11}")))
        // Signal-style halves: the pair code is each party's own 30 digits, sorted.
        assertEquals(listOf(SafetyCode.half(a), SafetyCode.half(b)).sorted().joinToString(" "), code)
        assertTrue(SafetyCode.derive(a, c).contains(SafetyCode.half(a)))
        // Stability across releases: a fixed pair always yields this code.
        val x = IdentityFingerprint.fromBytes(ByteArray(32) { it.toByte() })
        val y = IdentityFingerprint.fromBytes(ByteArray(32) { (0xff - it).toByte() })
        assertEquals(SafetyCode.derive(x, y), SafetyCode.derive(y, x))
        assertEquals(FIXED_HALF_X, SafetyCode.half(x))
        assertEquals(FIXED_HALF_Y, SafetyCode.half(y))
        assertEquals(FIXED_PAIR_CODE, SafetyCode.derive(x, y))
    }

    @Test
    fun qrPayloadRoundTripsAndRejectsMalformedText() {
        // A fixed fingerprint, not a random key: the "mixed-case hex" negative below uppercases the first ten hex digits, which
        // changes nothing (so the text is a valid payload) when all ten happen to be 0-9, about one random key in 110.
        val fingerprint = IdentityFingerprint.fromBytes(ByteArray(32) { (0xa0 + it).toByte() })
        assertNotEquals("the mixed-case negative needs letters in the first ten digits", fingerprint.hex.take(10), fingerprint.hex.take(10).uppercase())
        val text = IdentityQrCodec.encode(IdentityQrPayload(fingerprint, "Asha · फ़ोन"))
        assertTrue(text.startsWith("BVMID1.${fingerprint.hex.uppercase()}."))
        assertEquals(IdentityQrPayload(fingerprint, "Asha · फ़ोन"), IdentityQrCodec.parse(text))
        // Lowercase hex is accepted too; the parsed name is NFC.
        val lowerText = "BVMID1.${fingerprint.hex}.${text.substringAfterLast('.')}"
        assertEquals(IdentityQrPayload(fingerprint, "Asha · फ़ोन"), IdentityQrCodec.parse(lowerText))
        val decomposed = "José"
        assertEquals("José", IdentityQrCodec.parse("BVMID1.${fingerprint.hex}.${b64(decomposed.toByteArray())}").displayName)

        val name = text.substringAfterLast('.')
        val bad = listOf(
            "",
            "BVMID2.${fingerprint.hex}.$name",
            "bvmid1.${fingerprint.hex}.$name",
            "BVMID1.${fingerprint.hex}",
            "BVMID1.${fingerprint.hex}.$name.extra",
            "BVMID1.${fingerprint.hex.take(10).uppercase()}${fingerprint.hex.drop(10)}.$name",
            "BVMID1.${"g".repeat(64)}.$name",
            "BVMID1.${fingerprint.hex.drop(1)}.$name",
            "BVMID1.${fingerprint.hex}0.$name",
            "BVMID1.${fingerprint.hex}.$name=",
            "BVMID1.${fingerprint.hex}.${name}+",
            "BVMID1.${fingerprint.hex}.",
            "BVMID1.${fingerprint.hex}.${b64("a\u0000b".toByteArray())}",
            "BVMID1.${fingerprint.hex}.${b64(byteArrayOf(0xc3.toByte(), 0x28))}",
            "BVMID1.${fingerprint.hex}.${b64(ByteArray(65) { 'a'.code.toByte() })}",
            "BVMID1.${fingerprint.hex}.${b64("   ".toByteArray())}",
            "BVMID1.${fingerprint.hex}.${b64("Bob‮evil".toByteArray())}",
            "BVMID1.${fingerprint.hex}.${b64("Bob⁦x⁩".toByteArray())}",
            "BVMID1.${fingerprint.hex}.${b64("Bo​b".toByteArray())}",
            "BVMID1.${fingerprint.hex}.${b64("Bob﻿".toByteArray())}",
            "BVMID1.${fingerprint.hex}.${b64("Bob".toByteArray())}",
            "BVMID1.${fingerprint.hex}.${b64("Bob‎".toByteArray())}",
            " $text",
        )
        bad.forEach { candidate ->
            assertThrows(candidate, IdentityQrFormatException::class.java) { IdentityQrCodec.parse(candidate) }
            assertNull(IdentityQrCodec.tryParse(candidate))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IdentityQrCodec.encode(IdentityQrPayload(fingerprint, "x".repeat(65)))
        }
    }

    @Test
    fun markVerifiedRequiresTheAuthenticatedFingerprint() {
        val store = store()
        val peer = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        val other = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(MarkVerifiedResult.NO_AUTHENTICATED_SESSION, store.markVerified(null, peer, "Peer", VerificationMethod.QR_SCAN))
        assertEquals(MarkVerifiedResult.FINGERPRINT_MISMATCH, store.markVerified(peer, other, "Peer", VerificationMethod.QR_SCAN))
        assertEquals(VerificationState.UNVERIFIED, store.state(peer.nodeId, peer))
        assertEquals(MarkVerifiedResult.VERIFIED, store.markVerified(peer, peer, "Peer", VerificationMethod.SAFETY_CODE_CONFIRMED))
        assertEquals(VerificationState.VERIFIED, store.state(peer.nodeId, peer))
        // Persisted, sealed and reloaded.
        val reloaded = store().all()
        assertEquals(listOf(VerifiedContact(peer, "Peer", 1_700_000_000_000L, VerificationMethod.SAFETY_CODE_CONFIRMED)), reloaded)
        val raw = File(directory, "verified-contacts.bin").readBytes()
        assertTrue(raw.toString(Charsets.ISO_8859_1).indexOf("Peer") < 0)
        assertTrue(store().forget(peer))
        assertEquals(VerificationState.UNVERIFIED, store().state(peer.nodeId, peer))
    }

    @Test
    fun samePrefixWithADifferentKeyIsAMismatchAndCannotBeVerified() {
        val store = store()
        val real = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(MarkVerifiedResult.VERIFIED, store.markVerified(real, real, "Real", VerificationMethod.QR_SCAN))
        val impostor = samePrefixDifferentKey(real)
        assertEquals(VerificationState.MISMATCH, store.state(impostor.nodeId, impostor))
        assertEquals(real, store.contactForPrefix(impostor.nodePrefix())?.fingerprint)
        assertEquals(MarkVerifiedResult.PREFIX_CONFLICT, store.markVerified(impostor, impostor, "Fake", VerificationMethod.QR_SCAN))
        // A fingerprint that does not extend the claimed node ID is never accepted.
        val unrelated = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(VerificationState.MISMATCH, store.state(unrelated.nodeId, real))
    }

    @Test
    fun endToEndHandshakeExposesTheFullFingerprintAndRefusesAVerifiedPrefixWithAnotherKey() {
        val caller = KeyPairSigningIdentity(generateEcKeyPair())
        val callee = KeyPairSigningIdentity(generateEcKeyPair())
        val calleeFingerprint = IdentityFingerprint.of(callee)

        // No verified contacts: unchanged behaviour, fingerprint exposed and recorded.
        val (callerSession, _) = handshake(caller, callee)
        assertEquals(calleeFingerprint, callerSession.getOrThrow().peerFingerprint)
        assertEquals(calleeFingerprint, PeerIdentityGate.authenticatedFingerprint(callee.nodeId))

        val store = store()
        PeerIdentityGate.store = store
        // Seed a verified contact whose prefix equals the callee's but whose key differs.
        File(directory, "verified-contacts.bin").delete()
        store.dropCache()
        val impostorContact = samePrefixDifferentKey(calleeFingerprint)
        assertEquals(
            MarkVerifiedResult.VERIFIED,
            store.markVerified(impostorContact, impostorContact, "Verified friend", VerificationMethod.QR_SCAN),
        )
        val mismatches = AtomicReference<IdentityFingerprint?>()
        PeerIdentityGate.addMismatchListener { _, fingerprint, contact ->
            assertEquals(impostorContact, contact?.fingerprint)
            mismatches.set(fingerprint)
        }
        val (refused, calleeSide) = handshake(caller, callee)
        assertTrue(refused.exceptionOrNull() is IdentityMismatchException)
        assertTrue(calleeSide.isSuccess) // the callee sees an unverified caller
        assertEquals(calleeFingerprint, mismatches.get())

        PeerIdentityGate.policy = MismatchPolicy.WARN
        val (warned, _) = handshake(caller, callee)
        assertEquals(calleeFingerprint, warned.getOrThrow().peerFingerprint)

        PeerIdentityGate.policy = MismatchPolicy.REFUSE
        assertTrue(store.forget(impostorContact))
        assertEquals(MarkVerifiedResult.VERIFIED, store.markVerified(calleeFingerprint, calleeFingerprint, "Friend", VerificationMethod.QR_SCAN))
        val (verified, _) = handshake(caller, callee)
        val session = verified.getOrThrow()
        assertEquals(VerificationState.VERIFIED, store.state(session.peerNodeId, session.peerFingerprint))
    }

    @Test
    fun facadeMarksOnlyTheAuthenticatedSessionsFingerprint() {
        val own = KeyPairSigningIdentity(generateEcKeyPair())
        val peer = KeyPairSigningIdentity(generateEcKeyPair())
        val verification = IdentityVerification({ own }, store())
        val peerFingerprint = IdentityFingerprint.of(peer)
        assertEquals(
            MarkVerifiedResult.NO_AUTHENTICATED_SESSION,
            verification.markVerified(peer.nodeId, peerFingerprint, "Peer", VerificationMethod.QR_SCAN),
        )
        PeerIdentityGate.admit(peer.nodeId, peer.publicKeyEncoded)
        val scanned = verification.parseQr(IdentityQrCodec.encode(IdentityQrPayload(peerFingerprint, "Peer")))
        assertEquals(
            MarkVerifiedResult.VERIFIED,
            verification.markVerified(peer.nodeId, scanned.fingerprint, scanned.displayName, VerificationMethod.QR_SCAN),
        )
        assertEquals(VerificationState.VERIFIED, verification.verificationState(peer.nodeId, peerFingerprint))
        assertEquals(SafetyCode.derive(IdentityFingerprint.of(own), peerFingerprint), verification.safetyCode(peerFingerprint))
        assertNotNull(IdentityQrCodec.parse(verification.qrPayload("Me")))
        assertEquals(IdentityFingerprint.of(own), verification.parseQr(verification.qrPayload("Me")).fingerprint)
    }

    @Test
    fun unreadableStoreIsReportedNeverOverwrittenRefusesNobodyAndCanBeReset() {
        val file = File(directory, "verified-contacts.bin")
        val real = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(MarkVerifiedResult.VERIFIED, store().markVerified(real, real, "Real", VerificationMethod.QR_SCAN))
        file.writeBytes(ByteArray(64) { 7 }) // damaged or sealed under a lost key
        val damaged = byteArrayOf(*file.readBytes())
        val store = store()
        assertTrue(store.isUnreadable())
        assertEquals(VerificationState.STORE_UNREADABLE, store.state(real.nodeId, real))
        assertEquals(VerificationState.STORE_UNREADABLE, store.stateForNodeId(real.nodeId))
        assertEquals(MarkVerifiedResult.STORE_UNREADABLE, store.markVerified(real, real, "Real", VerificationMethod.QR_SCAN))
        assertTrue(store.all().isEmpty())
        assertTrue(file.readBytes().contentEquals(damaged))

        // Sessions continue (as unverified) instead of refusing everyone.
        PeerIdentityGate.store = store
        val caller = KeyPairSigningIdentity(generateEcKeyPair())
        val callee = KeyPairSigningIdentity(generateEcKeyPair())
        val (callerSide, calleeSide) = handshake(caller, callee)
        assertTrue(callerSide.isSuccess && calleeSide.isSuccess)
        assertEquals(VerificationState.STORE_UNREADABLE, PeerIdentityGate.admitForwardedSource(callee.nodeId))

        val verification = IdentityVerification({ caller }, store)
        assertTrue(verification.storeUnreadable())
        assertTrue(verification.resetVerifiedContacts())
        assertFalse(verification.storeUnreadable())
        assertFalse(file.exists())
        val calleeFingerprint = IdentityFingerprint.of(callee)
        assertEquals(
            MarkVerifiedResult.VERIFIED,
            verification.markVerified(callee.nodeId, calleeFingerprint, "Callee", VerificationMethod.SAFETY_CODE_CONFIRMED),
        )
        assertEquals(VerificationState.VERIFIED, verification.verificationState(callee.nodeId, calleeFingerprint))
    }

    @Test
    fun aCorruptStoreIsLatchedAndReportedOnce() {
        val file = File(directory, "verified-contacts.bin")
        val real = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(MarkVerifiedResult.VERIFIED, store().markVerified(real, real, "Real", VerificationMethod.QR_SCAN))
        val sealed = file.readBytes()
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte() // GCM tag fails
        file.writeBytes(sealed)
        var opens = 0
        val counting = object : AtRestCipher {
            override fun seal(plaintext: ByteArray) = cipher.seal(plaintext)
            override fun open(sealed: ByteArray): ByteArray {
                opens++
                return cipher.open(sealed)
            }
        }
        val store = VerifiedContactStore(file, counting)
        PeerIdentityGate.store = store
        val heard = mutableListOf<StoreUnreadableCause>()
        PeerIdentityGate.addStoreUnreadableListener { heard += it }
        assertEquals(VerificationState.STORE_UNREADABLE, store.state(real.nodeId, real))
        assertEquals(StoreUnreadableCause.CORRUPT_FILE, store.unreadableCause())
        assertEquals(VerificationState.STORE_UNREADABLE, store.stateForNodeId(real.nodeId))
        assertEquals(1, opens) // latched: not retried
        assertEquals(listOf(StoreUnreadableCause.CORRUPT_FILE), heard)
        // A file that decrypts but has a bad structure is corrupt too.
        val garbled = VerifiedContactStore(File(directory, "garbled.bin").apply { writeBytes(cipher.seal(byteArrayOf(1, 2, 3))) }, cipher)
        assertEquals(StoreUnreadableCause.CORRUPT_FILE, garbled.unreadableCause())
    }

    /** A cipher that fails [failures] times with a Keystore error, then works; counts every open. */
    private inner class FlakyCipher(var failures: Int) : AtRestCipher {
        var opens = 0
        override fun seal(plaintext: ByteArray) = cipher.seal(plaintext)
        override fun open(sealed: ByteArray): ByteArray {
            opens++
            if (failures-- > 0) throw java.security.ProviderException("keystore daemon restarting")
            return cipher.open(sealed)
        }
    }

    @Test
    fun aKeystoreFailureIsRetriedWithBackoffAndReportedOnce() {
        val real = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(MarkVerifiedResult.VERIFIED, store().markVerified(real, real, "Real", VerificationMethod.QR_SCAN))
        val flaky = FlakyCipher(failures = 2)
        var now = 1_000_000L
        val store = VerifiedContactStore(File(directory, "verified-contacts.bin"), flaky) { now }
        PeerIdentityGate.store = store
        val heard = mutableListOf<StoreUnreadableCause>()
        PeerIdentityGate.addStoreUnreadableListener { heard += it }
        // Fail-open while the Keystore is failing: the gate accepts, nothing is overwritten.
        assertEquals(VerificationState.STORE_UNREADABLE, PeerIdentityGate.admitForwardedSource(real.nodeId))
        assertEquals(StoreUnreadableCause.KEYSTORE_ERROR, store.unreadableCause())
        assertEquals(StoreBanner.RETRYING, store.banner())
        assertEquals(listOf(StoreUnreadableCause.KEYSTORE_ERROR), heard) // reported once per episode
        // Inside the backoff the Keystore is not asked again, however often the store is read.
        assertEquals(1, flaky.opens)
        repeat(5) { store.state(real.nodeId, real) }
        assertEquals(1, flaky.opens)
        assertEquals(VerifiedStoreRecovery.FIRST_RETRY_MS, store.retryDueInMs())
        // The first retry is due after 2 s and fails again (the second failure waits 4 s).
        now += VerifiedStoreRecovery.FIRST_RETRY_MS
        assertEquals(VerificationState.STORE_UNREADABLE, store.state(real.nodeId, real))
        assertEquals(2, flaky.opens)
        assertEquals(2 * VerifiedStoreRecovery.FIRST_RETRY_MS, store.retryDueInMs())
        assertEquals(listOf(StoreUnreadableCause.KEYSTORE_ERROR), heard) // same cause: not reported again
        // The third attempt succeeds: the banner clears by itself, no reset needed.
        now += 2 * VerifiedStoreRecovery.FIRST_RETRY_MS
        assertEquals(VerificationState.VERIFIED, store.state(real.nodeId, real))
        assertNull(store.unreadableCause())
        assertEquals(StoreBanner.NONE, store.banner())
        assertNull(store.retryDueInMs())
        assertFalse(store.isUnreadable())
    }

    @Test
    fun aTemporaryFailureNeverTouchesOrReplacesTheFile() {
        val real = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        val file = File(directory, "verified-contacts.bin")
        assertEquals(MarkVerifiedResult.VERIFIED, store().markVerified(real, real, "Real", VerificationMethod.QR_SCAN))
        val before = file.readBytes()
        val store = VerifiedContactStore(file, FlakyCipher(failures = 100)) { 5L }
        val other = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(MarkVerifiedResult.STORE_UNREADABLE, store.markVerified(other, other, "Other", VerificationMethod.QR_SCAN))
        assertFalse(store.forget(real))
        assertTrue(file.readBytes().contentEquals(before))
    }

    @Test
    fun aCorruptFileIsKeptAndNeverRetriedUntilTheUserResets() {
        val file = File(directory, "verified-contacts.bin")
        val real = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(MarkVerifiedResult.VERIFIED, store().markVerified(real, real, "Real", VerificationMethod.QR_SCAN))
        val sealed = file.readBytes()
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        file.writeBytes(sealed)
        var now = 0L
        val store = VerifiedContactStore(file, cipher) { now }
        assertEquals(StoreBanner.RESET_REQUIRED, store.banner())
        assertNull(store.retryDueInMs()) // nothing is scheduled: a corrupt file does not heal by itself
        now += 24 * 60 * 60 * 1000L
        assertEquals(StoreBanner.RESET_REQUIRED, store.banner())
        assertTrue(file.readBytes().contentEquals(sealed))
        assertTrue(store.reset())
        assertEquals(StoreBanner.NONE, store.banner())
        assertFalse(file.exists())
    }

    @Test
    fun theRetryScheduleDoublesFromTwoSecondsAndCapsAtOneMinute() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (1..7).map { VerifiedStoreRecovery.retryDelayMs(it) },
        )
        assertEquals(2_000L, VerifiedStoreRecovery.retryDelayMs(0))
        assertEquals(60_000L, VerifiedStoreRecovery.retryDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun theBannerFollowsTheCauseAndOnlyATemporaryOneSchedulesACheck() {
        assertEquals(StoreBanner.NONE, VerifiedStoreRecovery.banner(null))
        assertEquals(StoreBanner.RETRYING, VerifiedStoreRecovery.banner(StoreUnreadableCause.KEYSTORE_ERROR))
        assertEquals(StoreBanner.RETRYING, VerifiedStoreRecovery.banner(StoreUnreadableCause.IO_ERROR))
        assertEquals(StoreBanner.RESET_REQUIRED, VerifiedStoreRecovery.banner(StoreUnreadableCause.CORRUPT_FILE))
        assertEquals(StoreBanner.NONE, VerifiedStoreBannerController.bannerFor(null))
        assertEquals(StoreBanner.RETRYING, VerifiedStoreBannerController.bannerFor(StoreProblem.TEMPORARY))
        assertEquals(StoreBanner.RESET_REQUIRED, VerifiedStoreBannerController.bannerFor(StoreProblem.CORRUPT))
        assertEquals(5_250L, VerifiedStoreRecovery.nextCheckInMs(StoreBanner.RETRYING, 5_000L))
        assertEquals(250L, VerifiedStoreRecovery.nextCheckInMs(StoreBanner.RETRYING, null))
        assertNull(VerifiedStoreRecovery.nextCheckInMs(StoreBanner.RESET_REQUIRED, 5_000L))
        assertNull(VerifiedStoreRecovery.nextCheckInMs(StoreBanner.NONE, null))
    }

    @Test
    fun forwardedSourceIsComparedByNodeIdAgainstVerifiedContacts() {
        val store = store()
        PeerIdentityGate.store = store
        val source = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(VerificationState.UNVERIFIED, PeerIdentityGate.admitForwardedSource(source.nodeId))
        assertEquals(MarkVerifiedResult.VERIFIED, store.markVerified(source, source, "Source", VerificationMethod.QR_SCAN))
        assertEquals(VerificationState.VERIFIED, PeerIdentityGate.admitForwardedSource(source.nodeId))
        val ground = samePrefixDifferentKey(source).nodeId
        assertEquals(VerificationState.MISMATCH, store.stateForNodeId(ground))
        val heard = AtomicReference<NodeId?>()
        PeerIdentityGate.addMismatchListener { nodeId, fingerprint, contact ->
            assertNull(fingerprint)
            assertEquals(source, contact?.fingerprint)
            heard.set(nodeId)
        }
        assertThrows(IdentityMismatchException::class.java) { PeerIdentityGate.admitForwardedSource(ground) }
        assertEquals(ground, heard.get())
        PeerIdentityGate.policy = MismatchPolicy.WARN
        assertEquals(VerificationState.MISMATCH, PeerIdentityGate.admitForwardedSource(ground))
    }

    @Test
    fun recentSessionsKeepAtLeastAThousandPeers() {
        val first = KeyPairSigningIdentity(generateEcKeyPair())
        PeerIdentityGate.admit(first.nodeId, first.publicKeyEncoded)
        // 1023 further peers do not evict the first.
        repeat(PeerIdentityGate.MAX_RECENT - 1) {
            val peer = KeyPairSigningIdentity(generateEcKeyPair())
            PeerIdentityGate.admit(peer.nodeId, peer.publicKeyEncoded)
        }
        assertNotNull(PeerIdentityGate.authenticatedFingerprint(first.nodeId))
        assertTrue(PeerIdentityGate.MAX_RECENT >= 1024)
    }

    @Test
    fun noSessionIsAdmittedOnceAWipeStarts() {
        val caller = KeyPairSigningIdentity(generateEcKeyPair())
        val callee = KeyPairSigningIdentity(generateEcKeyPair())
        WipeGuard.begin()
        try {
            val (callerSide, calleeSide) = handshake(caller, callee)
            assertTrue(callerSide.isFailure || calleeSide.isFailure)
            assertTrue(calleeSide.exceptionOrNull() is IllegalStateException)
            assertThrows(IllegalStateException::class.java) { PeerIdentityGate.admitForwardedSource(callee.nodeId) }
        } finally {
            WipeGuard.resetForTest()
        }
    }

    @Test
    fun groupSourceDropsOnlyTheRefusedReceiver() {
        val source = KeyPairSigningIdentity(generateEcKeyPair())
        val relay = KeyPairSigningIdentity(generateEcKeyPair())
        val bad = KeyPairSigningIdentity(generateEcKeyPair())
        val good = KeyPairSigningIdentity(generateEcKeyPair())
        val store = store()
        val impostor = samePrefixDifferentKey(IdentityFingerprint.of(bad))
        assertEquals(MarkVerifiedResult.VERIFIED, store.markVerified(impostor, impostor, "Friend", VerificationMethod.QR_SCAN))
        PeerIdentityGate.store = store

        val toRelay = BlockingPipe()
        val toSource = BlockingPipe()
        val sourceResult = AtomicReference<Result<BroadcastGroupSourceSession>>()
        val worker = thread {
            sourceResult.set(runCatching {
                val link = SecureBluetoothHandshake.initiate(toSource.input, toRelay.output, source, CAPS)
                BroadcastGroupHandshake.initiateWithEpoch(link, source, CAPS, 2, epoch = 1L)
            })
        }
        val relayLink = SecureBluetoothHandshake.respond(toRelay.input, toSource.output, relay, CAPS)
        val wrapped = listOf(bad, good).map { receiver ->
            relayLink.writer.write(
                SecureRecordType.GROUP_CONTROL,
                GroupRelayControlCodec.encode(GroupRelayControlMessage(GroupRelayControlType.JOIN, receiver.nodeId)),
            )
            relayLink.writer.flush()
            val sourceHello = HandshakeHelloCodec.decode(GroupRelayControlCodec.decode(relayLink.reader.read().payload).payload)
            val pending = MeshHandshake.start(receiver, HandshakeRole.RESPONDER, CAPS, purpose = HandshakePurpose.END_TO_END)
            val keys = MeshHandshake.finish(pending, sourceHello)
            relayLink.writer.write(
                SecureRecordType.GROUP_CONTROL,
                GroupRelayControlCodec.encode(
                    GroupRelayControlMessage(GroupRelayControlType.RECEIVER_HELLO, receiver.nodeId, HandshakeHelloCodec.encode(pending.hello)),
                ),
            )
            relayLink.writer.flush()
            val key = GroupRelayControlCodec.decode(relayLink.reader.read().payload)
            assertEquals(GroupRelayControlType.WRAPPED_KEY, key.type)
            key.payload to keys
        }
        worker.join(120_000)
        val session = sourceResult.get().getOrThrow()
        assertEquals(0, wrapped[0].first.size) // the refused receiver gets nothing it can open
        assertEquals(EndToEndContentType.GROUP_KEY, EndToEndContentOpener(wrapped[1].second.receiveKey).open(wrapped[1].first).type)
        assertEquals(1, session.receiverCount)
        assertTrue(session.isRefusedReceiver(bad.nodeId))
        assertTrue(session.markRefusedReceiverLeft(bad.nodeId))
        assertFalse(session.markRefusedReceiverLeft(bad.nodeId))
        assertFalse(session.isRefusedReceiver(good.nodeId))
        session.close()
    }

    private fun samePrefixDifferentKey(real: IdentityFingerprint): IdentityFingerprint {
        val bytes = real.bytes()
        // Same 48-bit prefix; every later byte differs (what a 2^48 grind would produce).
        for (i in DiscoveryAnnouncement.NODE_PREFIX_BYTES until bytes.size) bytes[i] = (bytes[i].toInt() xor 0x5a).toByte()
        return IdentityFingerprint.fromBytes(bytes)
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Runs one direct link + end-to-end handshake; returns (caller's session, callee's session). */
    private fun handshake(
        caller: SigningIdentity,
        callee: SigningIdentity,
    ): Pair<Result<EndToEndVideoSession>, Result<EndToEndVideoSession>> {
        val aToB = BlockingPipe()
        val bToA = BlockingPipe()
        val calleeResult = AtomicReference<Result<EndToEndVideoSession>>()
        val worker = thread {
            calleeResult.set(runCatching {
                val link = SecureBluetoothHandshake.respond(aToB.input, bToA.output, callee, CAPS)
                EndToEndVideoHandshake.respond(link, callee, CAPS)
            })
        }
        val callerResult = runCatching {
            val link = SecureBluetoothHandshake.initiate(bToA.input, aToB.output, caller, CAPS)
            EndToEndVideoHandshake.initiate(link, caller, CAPS)
        }
        worker.join(120_000)
        return callerResult to calleeResult.get()
    }

    private class BlockingPipe {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len > 0) chunks.put(b.copyOfRange(off, off + len))
            }
        }
        val input = object : InputStream() {
            private var current = ByteArray(0)
            private var offset = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (offset == current.size) {
                    current = chunks.poll(120, TimeUnit.SECONDS) ?: return -1
                    offset = 0
                }
                val count = minOf(len, current.size - offset)
                System.arraycopy(current, offset, b, off, count)
                offset += count
                return count
            }
        }
    }

    private companion object {
        // Computed independently (Python hashlib) from the PROTOCOL definition.
        const val FIXED_HALF_X = "91662 04557 05960 21671 25144 22586"
        const val FIXED_HALF_Y = "71883 11788 79268 78261 54534 68020"
        const val FIXED_PAIR_CODE = "$FIXED_HALF_Y $FIXED_HALF_X"
        val CAPS = NodeCapabilities(
            transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
            encodeCodecMask = NodeCapabilities.CODEC_VP9,
            decodeCodecMask = NodeCapabilities.CODEC_VP9,
            maxBitrateKbps = 1_000,
            maxChildren = 0,
        )
    }
}
