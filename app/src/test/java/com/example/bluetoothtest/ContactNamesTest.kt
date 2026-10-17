package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Naming saved contacts: the pure name rules and list order, and renaming in the verified-contact store. */
class ContactNamesTest {
    private lateinit var directory: File
    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("contact-names").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store() = VerifiedContactStore(File(directory, "verified-contacts.bin"), cipher) { now }

    private fun fingerprint() =
        IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)

    private fun verify(store: VerifiedContactStore, fp: IdentityFingerprint, name: String, method: VerificationMethod) =
        store.markVerified(fp, fp, name, method)

    @Test
    fun checkAcceptsPlainNamesAndCleansWhitespace() {
        assertEquals(ContactNameCheck.Valid("Asha"), ContactNames.check("Asha"))
        assertEquals(ContactNameCheck.Valid("Asha Rao"), ContactNames.check("  Asha \t Rao  "))
        assertEquals(ContactNameCheck.Valid("José"), ContactNames.check("José"))
        assertEquals(ContactNameCheck.Valid("अशा"), ContactNames.check("अशा"))
    }

    @Test
    fun checkSeparatesBlankFromInvalid() {
        assertEquals(ContactNameCheck.Blank, ContactNames.check(""))
        assertEquals(ContactNameCheck.Blank, ContactNames.check("   \n"))
        listOf(
            "Bob‮evil", // bidi override
            "Bo​b", // zero width
            "Bob\u0000",
            "x".repeat(33), // over the 32-byte person-name limit
            "Аsha a", // Cyrillic mixed with Latin
            "ABCDE-FGHJK", // looks like a handle
            "Done ✓", // check mark
        ).forEach { candidate ->
            assertTrue(candidate, ContactNames.check(candidate) is ContactNameCheck.Invalid)
        }
    }

    @Test
    fun suggestionPrefersTheSavedNameThenTheSharedOneAndNeverAHandleOrJunk() {
        assertEquals("Asha", ContactNames.suggestion("Asha", "Other"))
        assertEquals("Other", ContactNames.suggestion("", "Other"))
        assertEquals("Other", ContactNames.suggestion(null, " Other "))
        assertEquals("", ContactNames.suggestion(null, null))
        assertEquals("", ContactNames.suggestion("", "Bob‮evil"))
        assertEquals("", ContactNames.suggestion("  ", "ABCDE-FGHJK"))
    }

    @Test
    fun contactsShowTheirNameOrTheHandleAndSortNamedFirst() {
        val a = ContactEntry("a1".repeat(32), "zoe", 1L, true)
        val b = ContactEntry("b2".repeat(32), "", 2L, false)
        val c = ContactEntry("c3".repeat(32), "Adam", 3L, true)
        assertEquals("zoe", a.title)
        assertEquals(IdentityHandle.fromPrefixHex("b2".repeat(6)), b.title)
        assertEquals("b2".repeat(16), b.nodeIdHex)
        assertEquals("b2".repeat(6), b.prefixHex)
        assertEquals(listOf(c, a, b), ContactNames.sorted(listOf(b, a, c)))
    }

    @Test
    fun renameChangesOnlyTheLabelAndPersists() {
        val fp = fingerprint()
        val other = fingerprint()
        val first = store()
        assertEquals(MarkVerifiedResult.VERIFIED, verify(first, fp, "Pixel 8", VerificationMethod.QR_SCAN))
        assertEquals(MarkVerifiedResult.VERIFIED, verify(first, other, "Other", VerificationMethod.SAFETY_CODE_CONFIRMED))
        val before = first.all().first { it.fingerprint == fp }
        now += 60_000

        assertEquals(RenameResult.RENAMED, first.rename(fp, "  Asha   Rao "))

        val renamed = first.all().first { it.fingerprint == fp }
        assertEquals("Asha Rao", renamed.displayName)
        assertEquals(before.verifiedAtMs, renamed.verifiedAtMs)
        assertEquals(before.method, renamed.method)
        assertEquals("Other", first.all().first { it.fingerprint == other }.displayName)
        // A new store object reads the same file: the name is persisted, still verified.
        val reopened = store()
        assertEquals("Asha Rao", reopened.all().first { it.fingerprint == fp }.displayName)
        assertEquals(VerificationState.VERIFIED, reopened.state(fp.nodeId, fp))
    }

    @Test
    fun renameRejectsBlankAndNonCanonicalNamesWithoutChangingAnything() {
        val fp = fingerprint()
        val store = store()
        verify(store, fp, "Asha", VerificationMethod.QR_SCAN)
        listOf("", "   ", "Bob‮evil", "x".repeat(40), "ABCDE-FGHJK").forEach { bad ->
            assertEquals(bad, RenameResult.INVALID_NAME, store.rename(fp, bad))
        }
        assertEquals("Asha", store().all().single().displayName)
    }

    @Test
    fun renameOfAnUnknownContactIsReportedAndDoesNotCreateOne() {
        val store = store()
        assertEquals(RenameResult.NOT_FOUND, store.rename(fingerprint(), "Nobody"))
        assertFalse(File(directory, "verified-contacts.bin").exists())
    }

    @Test
    fun renameRefusesAnUnreadableStoreAndLeavesTheFileAlone() {
        val fp = fingerprint()
        verify(store(), fp, "Asha", VerificationMethod.QR_SCAN)
        val file = File(directory, "verified-contacts.bin")
        file.writeBytes(ByteArray(64) { 7 })
        val damaged = file.readBytes()
        assertEquals(RenameResult.STORE_UNREADABLE, store().rename(fp, "Asha Rao"))
        assertTrue(file.readBytes().contentEquals(damaged))
    }

    @Test
    fun verifyingAgainWithoutANameKeepsTheNameYouGaveAndABlankNewContactStaysUnnamed() {
        val fp = fingerprint()
        val store = store()
        assertEquals(MarkVerifiedResult.VERIFIED, verify(store, fp, "", VerificationMethod.QR_SCAN))
        assertEquals("", store.all().single().displayName)
        store.rename(fp, "Asha")
        now += 1000
        assertEquals(MarkVerifiedResult.VERIFIED, verify(store, fp, "", VerificationMethod.SAFETY_CODE_CONFIRMED))
        val contact = store.all().single()
        assertEquals("Asha", contact.displayName)
        assertEquals(now, contact.verifiedAtMs)
        assertEquals(VerificationMethod.SAFETY_CODE_CONFIRMED, contact.method)
    }

    @Test
    fun forgettingARenamedContactRemovesIt() {
        val fp = fingerprint()
        val store = store()
        verify(store, fp, "", VerificationMethod.QR_SCAN)
        store.rename(fp, "Asha")
        assertTrue(store.forget(fp))
        assertTrue(store().all().isEmpty())
        assertEquals(RenameResult.NOT_FOUND, store.rename(fp, "Asha"))
    }
}
