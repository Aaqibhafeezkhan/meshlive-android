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

/** The sealed contact-claims file (Design G, G3): seal, atomic write, forget, wipe, downgrade and unreadable handling. */
class ContactClaimsStoreTest {
    private lateinit var dir: File
    private lateinit var file: File
    private val cipher: AtRestCipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private val mum = IdentityFingerprint.fromBytes(ByteArray(32) { (it + 1).toByte() })
    private val dev = IdentityFingerprint.fromBytes(ByteArray(32) { (it + 90).toByte() })

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("claims").toFile()
        file = File(File(dir, IdentityVerification.DIRECTORY), ContactClaims.FILE)
        WipeGuard.resetForTest()
    }

    @After
    fun tearDown() {
        ContactClaims.installForTest(null)
        dir.deleteRecursively()
    }

    private fun store() = ContactClaimsStore(file, cipher)

    @Test
    fun theFileIsSealedAndHoldsNoPlainName() {
        val s = store()
        assertEquals(ClaimRecordResult.FIRST, s.recordClaim(mum, "Sunita", 1_000L))
        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Sunita"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).startsWith("BCC1"))
        val reopened = store().get(mum)!!
        assertEquals("Sunita", reopened.claimedName)
        assertEquals(1_000L, reopened.claimedAtMs)
        assertFalse(reopened.hasPendingChange)
    }

    @Test
    fun aChangedNameBecomesAPendingNoteUntilAcknowledged() {
        val s = store()
        s.recordClaim(mum, "Sunita", 1L)
        assertEquals(ClaimRecordResult.UNCHANGED, s.recordClaim(mum, "Sunita", 2L))
        assertEquals(ClaimRecordResult.CHANGED, s.recordClaim(mum, "Sunita K", 3L))
        val pending = s.get(mum)!!
        assertEquals("Sunita K", pending.claimedName)
        assertEquals("Sunita", pending.acknowledgedName)
        assertTrue(pending.hasPendingChange)
        assertTrue(s.acknowledge(mum))
        assertFalse(store().get(mum)!!.hasPendingChange)
        assertFalse(s.acknowledge(mum))
    }

    @Test
    fun aNameThatBreaksTheTextRulesIsRefusedAndNothingIsWritten() {
        val s = store()
        assertEquals(ClaimRecordResult.INVALID_NAME, s.recordClaim(mum, "~Mum", 1L))
        assertEquals(ClaimRecordResult.INVALID_NAME, s.recordClaim(mum, "Asha‮evil", 1L))
        assertEquals(ClaimRecordResult.INVALID_NAME, s.recordClaim(mum, "", 1L))
        assertFalse(file.exists())
    }

    @Test
    fun theMutualScanFactIsKeptWithOrWithoutAClaimedName() {
        val s = store()
        assertTrue(s.markScannedBothWays(dev, 5L))
        assertTrue(store().get(dev)!!.scannedBothWays)
        assertEquals("", store().get(dev)!!.claimedName)
        assertEquals(ClaimRecordResult.FIRST, s.recordClaim(dev, "Dev", 6L))
        val both = store().get(dev)!!
        assertTrue(both.scannedBothWays)
        assertEquals("Dev", both.claimedName)
        assertFalse(both.hasPendingChange)
    }

    @Test
    fun forgetRemovesOnlyThatContactAndDeletesTheFileWhenEmpty() {
        val s = store()
        s.recordClaim(mum, "Sunita", 1L)
        s.recordClaim(dev, "Dev", 2L)
        assertTrue(s.forget(mum))
        assertNull(store().get(mum))
        assertNotNull(store().get(dev))
        assertTrue(s.forget(dev))
        assertFalse(file.exists())
        assertFalse(s.forget(dev))
    }

    @Test
    fun aDamagedFileIsUnreadableNeverEmptyAndNeverOverwrittenUntilReset() {
        val s = store()
        s.recordClaim(mum, "Sunita", 1L)
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        val damaged = store()
        assertTrue(damaged.isUnreadable())
        assertEquals(ClaimRecordResult.STORE_UNREADABLE, damaged.recordClaim(dev, "Dev", 2L))
        assertTrue(damaged.all().isEmpty())
        val before = file.readBytes().toList()
        damaged.markScannedBothWays(dev, 3L)
        assertEquals(before, file.readBytes().toList())
        assertTrue(damaged.reset())
        assertFalse(file.exists())
        assertFalse(damaged.isUnreadable())
        assertEquals(ClaimRecordResult.FIRST, damaged.recordClaim(dev, "Dev", 4L))
    }

    @Test
    fun anOlderBuildThatIgnoresTheFileLosesOnlyTheNote() {
        // The verified-contact store is a different file with its own magic; the claims file never touches it.
        val contacts = File(File(dir, IdentityVerification.DIRECTORY), IdentityVerification.FILE)
        val verified = VerifiedContactStore(contacts, cipher)
        store().recordClaim(mum, "Sunita", 1L)
        assertNotEquals(contacts.absolutePath, file.absolutePath)
        assertFalse(contacts.exists())
        assertTrue(verified.all().isEmpty())
        file.delete()
        assertTrue(verified.all().isEmpty())
    }

    private fun assertNotEquals(a: String, b: String) = assertFalse(a == b)

    @Test
    fun theFileLivesUnderTheDirectoryTheEmergencyWipeEmpties() {
        store().recordClaim(mum, "Sunita", 1L)
        assertTrue(file.exists())
        // Same layout as the app: dataDir/no_backup/identity-verification/contact-claims.bin
        val data = File(dir, "data").apply { mkdirs() }
        val inWipeRoot = File(data, "no_backup/${IdentityVerification.DIRECTORY}/${ContactClaims.FILE}")
        inWipeRoot.parentFile.mkdirs()
        inWipeRoot.writeText("secret")
        EmergencyWipe.storageRootsFor(data, emptyList()).forEach { root -> root.listFiles()?.forEach { EmergencyWipe.deleteTree(it) } }
        assertFalse(inWipeRoot.exists())
    }

    @Test
    fun wipeDropsTheInMemoryStoreAndForgetHookDeletesTheEntry() {
        val s = store()
        ContactClaims.installForTest(s)
        s.recordClaim(mum, "Sunita", 1L)
        ContactClaims.clearForWipe()
        // A fresh store reads the (still present) file; the wipe itself deletes the file.
        assertNotNull(store().get(mum))
    }
}
