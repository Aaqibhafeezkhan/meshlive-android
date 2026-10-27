package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallBlockingTest {
    private lateinit var base: File
    private lateinit var file: File
    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private var now = 1_000L

    private fun fingerprint(seed: Int): IdentityFingerprint =
        IdentityFingerprint.fromPublicKey(byteArrayOf(seed.toByte(), 7, 7, 7, (seed * 3).toByte()))

    private fun store() = BlockedPeopleStore(file, cipher) { now++ }

    @Before
    fun setUp() {
        base = Files.createTempDirectory("call-blocking").toFile()
        file = File(File(base, "no_backup/identity-verification"), BlockedPeople.FILE)
    }

    @After
    fun tearDown() {
        BlockedPeople.installForTest(null)
        base.deleteRecursively()
    }

    // ---- policy ----

    private fun decide(
        blockUnknown: Boolean,
        pseudonymous: Boolean = false,
        blocked: Boolean = false,
        standing: ContactStanding = ContactStanding.NOT_VERIFIED,
    ) = CallBlockingPolicy.decide(blockUnknown, pseudonymous, blocked, standing)

    @Test
    fun aContactRingsWhetherOrNotUnknownCallersAreBlocked() {
        assertEquals(CallGateDecision.RING, decide(false, standing = ContactStanding.VERIFIED))
        assertEquals(CallGateDecision.RING, decide(true, standing = ContactStanding.VERIFIED))
    }

    @Test
    fun anUnknownCallerRingsUntilTheSettingIsOn() {
        assertEquals(CallGateDecision.RING, decide(false))
        assertEquals(CallGateDecision.RING, decide(false, pseudonymous = true))
        assertEquals(CallGateDecision.DECLINE_UNKNOWN, decide(true))
        // A throwaway key cannot be a recognised contact, so it is unknown too.
        assertEquals(CallGateDecision.DECLINE_UNKNOWN, decide(true, pseudonymous = true))
    }

    @Test
    fun aBlockedPersonIsDeclinedWhateverTheSettingsAndEvenIfAContact() {
        assertEquals(CallGateDecision.DECLINE_BLOCKED_PERSON, decide(false, blocked = true))
        assertEquals(CallGateDecision.DECLINE_BLOCKED_PERSON, decide(true, blocked = true))
        assertEquals(
            CallGateDecision.DECLINE_BLOCKED_PERSON,
            decide(false, blocked = true, standing = ContactStanding.VERIFIED),
        )
    }

    @Test
    fun aThrowawayKeyIsNeverMatchedAgainstTheBlockList() {
        // The block list cannot know a stranger's one-time key: the flag is ignored and only the setting applies.
        assertEquals(CallGateDecision.RING, decide(false, pseudonymous = true, blocked = true))
        assertEquals(CallGateDecision.DECLINE_UNKNOWN, decide(true, pseudonymous = true, blocked = true))
    }

    @Test
    fun aContactStoreThatCannotJudgeNeverBlocksSomeoneUnknownToIt() {
        assertEquals(CallGateDecision.RING, decide(true, standing = ContactStanding.UNKNOWN))
        assertEquals(ContactStanding.UNKNOWN, CallBlocking.standingOf(null))
        assertEquals(ContactStanding.UNKNOWN, CallBlocking.standingOf(VerificationState.STORE_UNREADABLE))
        assertEquals(ContactStanding.VERIFIED, CallBlocking.standingOf(VerificationState.VERIFIED))
        assertEquals(ContactStanding.NOT_VERIFIED, CallBlocking.standingOf(VerificationState.UNVERIFIED))
    }

    @Test
    fun theDeclineDelayLooksLikeAPersonLookingAtTheScreen() {
        val random = Random(1)
        repeat(200) {
            val delay = CallBlockingPolicy.declineDelayMs(random)
            assertTrue(delay >= CallBlockingPolicy.DECLINE_DELAY_MIN_MS)
            assertTrue(delay < CallBlockingPolicy.DECLINE_DELAY_MIN_MS + CallBlockingPolicy.DECLINE_DELAY_SPREAD_MS)
        }
    }

    // ---- store ----

    @Test
    fun blockThenUnblockPersistsAcrossRestarts() {
        val a = fingerprint(1)
        val b = fingerprint(2)
        assertEquals(BlockResult.BLOCKED, store().add(a))
        assertEquals(BlockResult.ALREADY_BLOCKED, store().add(a))
        assertEquals(BlockResult.BLOCKED, store().add(b))

        val reopened = store()
        assertTrue(reopened.contains(a))
        assertTrue(reopened.contains(b))
        assertFalse(reopened.contains(fingerprint(3)))
        assertEquals(listOf(a, b), reopened.all().map { it.fingerprint })

        assertTrue(reopened.remove(a))
        assertFalse(reopened.remove(a))
        assertFalse(store().contains(a))
        assertTrue(store().contains(b))
    }

    @Test
    fun theFileHoldsNoReadableFingerprint() {
        val a = fingerprint(1)
        store().add(a)
        val raw = file.readBytes()
        val hex = a.hex.toByteArray(Charsets.US_ASCII)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(hex, Charsets.ISO_8859_1)))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(a.bytes(), Charsets.ISO_8859_1)))
    }

    @Test
    fun aListSealedUnderAnotherKeyIsUnreadableAndIsNeverOverwritten() {
        store().add(fingerprint(1))
        val other = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
        val blind = BlockedPeopleStore(file, other)
        assertTrue(blind.isUnreadable())
        assertEquals(emptyList<BlockedPerson>(), blind.all())
        val before = file.readBytes()
        assertEquals(BlockResult.STORE_UNREADABLE, blind.add(fingerprint(2)))
        assertTrue(before.contentEquals(file.readBytes()))
        assertTrue(blind.reset())
        assertFalse(blind.isUnreadable())
        assertEquals(BlockResult.BLOCKED, blind.add(fingerprint(2)))
    }

    @Test
    fun theListIsBounded() {
        val s = store()
        repeat(BlockedPeopleStore.MAX_BLOCKED) {
            val id = IdentityFingerprint.fromBytes(ByteArray(32).also { bytes -> bytes[0] = (it shr 8).toByte(); bytes[1] = it.toByte() })
            assertEquals(BlockResult.BLOCKED, s.add(id))
        }
        assertEquals(BlockResult.FULL, s.add(fingerprint(9)))
    }

    @Test
    fun theEmergencyWipeClearsTheListAndItsMemory() {
        val a = fingerprint(1)
        val shared = store()
        BlockedPeople.installForTest(shared)
        shared.add(a)
        assertTrue(file.exists())

        // The wipe deletes every data directory (no_backup holds the file) and resets in-memory state.
        val dataDir = File(base, "data").apply { mkdirs() }
        val inWipedRoot = File(dataDir, "no_backup/identity-verification/${BlockedPeople.FILE}")
        inWipedRoot.parentFile.mkdirs()
        inWipedRoot.writeText("sealed")
        EmergencyWipe.storageRootsFor(dataDir, emptyList()).forEach { root ->
            root.listFiles()?.forEach { EmergencyWipe.deleteTree(it) }
        }
        assertFalse(inWipedRoot.exists())
        // The same relative path as the production file (noBackupFilesDir/identity-verification/blocked-people.bin).
        assertEquals("identity-verification", IdentityVerification.DIRECTORY)

        file.delete()
        BlockedPeople.clearForWipe()
        assertFalse(shared.contains(a))
        assertEquals(emptyList<BlockedPerson>(), store().all())
    }

    @Test
    fun theUnknownCallersSettingIsInTheWipedPreferencesFile() {
        assertTrue(CallFeatureDefaults.APP_SETTINGS_PREFERENCES in EmergencyWipe.KNOWN_PREFERENCES)
        assertTrue("recent_calls" in EmergencyWipe.KNOWN_PREFERENCES)
    }

    // ---- Watch list ----

    private fun offer(prefix: Int, cost: Int, logical: Int, rotating: Boolean = false): DiscoveryAnnouncement {
        val bytes = ByteArray(6) { (prefix + it).toByte() }
        return DiscoveryAnnouncement(
            nodeIdPrefix = bytes,
            flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
            capabilitySummary = 1,
            streamToken = StreamRouteToken.forAdvertiser(logical, bytes),
            routeCost = cost,
            sequence = 0,
            version = if (rotating) DiscoveryAnnouncement.ROTATING_VERSION else DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
        )
    }

    private fun prefixOf(announcement: DiscoveryAnnouncement) = announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }

    @Test
    fun aBlockedSourcesStreamIsHiddenWithTheRoutesThatRelayIt() {
        val blockedSource = offer(0x10, cost = 0, logical = 1111)
        val relayOfBlocked = offer(0x40, cost = 1, logical = 1111)
        val other = offer(0x20, cost = 0, logical = 2222)
        val peers = listOf(blockedSource, relayOfBlocked, other)
        val visible = CallBlocking.hideBlockedStreams(peers, { it }, setOf(prefixOf(blockedSource)), { null })
        assertEquals(listOf(other), visible)
    }

    @Test
    fun anEmptyBlockListLeavesTheWatchListAlone() {
        val peers = listOf(offer(0x10, 0, 1), offer(0x20, 0, 2))
        assertEquals(peers, CallBlocking.hideBlockedStreams(peers, { it }, emptySet(), { null }))
    }

    @Test
    fun aRotatingOfferIsHiddenOnlyThroughTheContactItIsRecognisedAs() {
        val rotatingBlocked = offer(0x10, cost = 0, logical = 7, rotating = true)
        val rotatingStranger = offer(0x30, cost = 0, logical = 8, rotating = true)
        val blocked = setOf("aabbccddeeff")
        val recognised = { announcement: DiscoveryAnnouncement ->
            if (announcement === rotatingBlocked) "aabbccddeeff" else null
        }
        val visible = CallBlocking.hideBlockedStreams(listOf(rotatingBlocked, rotatingStranger), { it }, blocked, recognised)
        assertEquals(listOf(rotatingStranger), visible)
    }

    /**
     * Review 2026-10-27 (finding 4): a blocked person's stream under a rotating ID nobody recognises stayed listed and kept
     * playing. Once its source authenticated as the blocked person (the viewer left it then), that route stays hidden, even
     * with no blocked prefix to match. reviewer review (finding 1): a genuine broadcaster that advertises the same logical
     * token (the token is public and chosen by the advertiser) stays listed.
     */
    @Test
    fun aRouteWhoseSourceAuthenticatedAsABlockedPersonStaysHiddenButNotAGenuineOneWithTheSameToken() {
        PersonPolicy.resetForTest()
        try {
            val blockedRadio = offer(0x10, cost = 0, logical = 31, rotating = true)
            val genuineSameToken = offer(0x30, cost = 0, logical = 31, rotating = true)
            val other = offer(0x60, cost = 0, logical = 32, rotating = true)
            assertEquals(blockedRadio.logicalStreamToken(), genuineSameToken.logicalStreamToken())
            val peers = listOf(blockedRadio, genuineSameToken, other)
            fun visible() = CallBlocking.hideBlockedStreams(peers, { it }, emptySet(), { null }, PersonPolicy::streamHidden)
            assertEquals(peers, visible())
            PersonPolicy.hideStream(blockedRadio)
            assertEquals(listOf(genuineSameToken, other), visible())
            // Nothing about it outlives the process: the emergency wipe forgets it too.
            PersonPolicy.clearForWipe()
            assertEquals(peers, visible())
        } finally {
            PersonPolicy.resetForTest()
        }
    }

    /** S9: one rule answers "blocked?" for a full identity and for a source known only by its node ID (a BFG1 grant). */
    @Test
    fun theBlockListAnswersForAFingerprintAndForTheNodeIdOfTheSamePerson() {
        val phone = FakePhone(Files.createTempDirectory("person-policy").toFile())
        try {
            val context = phone.start()
            val blocked = fingerprint(1)
            val other = fingerprint(2)
            BlockedPeople.installForTest(store())
            PersonPolicy.install(context)
            assertEquals(PersonPolicy.Verdict.ALLOW, PersonPolicy.check(blocked))
            assertEquals(BlockResult.BLOCKED, BlockedPeople.block(context, blocked))
            assertEquals(PersonPolicy.Verdict.BLOCKED, PersonPolicy.check(blocked))
            assertEquals(PersonPolicy.Verdict.BLOCKED, PersonPolicy.checkNode(blocked.nodeId))
            assertEquals(PersonPolicy.Verdict.ALLOW, PersonPolicy.check(other))
            assertEquals(PersonPolicy.Verdict.ALLOW, PersonPolicy.checkNode(other.nodeId))
            assertEquals(PersonPolicy.Verdict.ALLOW, PersonPolicy.check(null))
            // Unblocking lets the person through again everywhere.
            assertTrue(BlockedPeople.unblock(context, blocked))
            assertEquals(PersonPolicy.Verdict.ALLOW, PersonPolicy.checkNode(blocked.nodeId))
        } finally {
            PersonPolicy.resetForTest()
            phone.root.deleteRecursively()
        }
    }

    /** Finding 3: every block (whichever screen made it) runs the side effects, so a blocked contact loses its lease. */
    @Test
    fun everyBlockRunsThePresenceRevocationAndARepeatedBlockDoesToo() {
        val phone = FakePhone(Files.createTempDirectory("person-policy").toFile())
        try {
            val context = phone.start()
            val person = fingerprint(4)
            val revoked = mutableListOf<IdentityFingerprint>()
            BlockedPeople.installForTest(store())
            PersonPolicy.installForTest(emptySet()) { revoked += it }
            assertEquals(BlockResult.BLOCKED, BlockedPeople.block(context, person))
            assertEquals(BlockResult.ALREADY_BLOCKED, BlockedPeople.block(context, person))
            assertEquals(listOf(person, person), revoked)
            // A block that could not be stored revokes nothing (the person is not blocked).
            val unreadable = BlockedPeopleStore(file, AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 9 }, "AES") })
            BlockedPeople.installForTest(unreadable)
            assertEquals(BlockResult.STORE_UNREADABLE, BlockedPeople.block(context, fingerprint(5)))
            assertEquals(2, revoked.size)
        } finally {
            PersonPolicy.resetForTest()
            phone.root.deleteRecursively()
        }
    }

    @Test
    fun aStreamHeardOnlyThroughARelayCannotBeAttributedAndStaysListed() {
        val viaBlockedRelay = offer(0x10, cost = 1, logical = 5)
        val visible = CallBlocking.hideBlockedStreams(listOf(viaBlockedRelay), { it }, setOf(prefixOf(viaBlockedRelay)), { null })
        assertEquals(listOf(viaBlockedRelay), visible)
    }
}
