package com.example.bluetoothtest

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file vault (PROTOCOL "File vault"): 4 KiB AES-GCM blocks, write-once, no plaintext copy. */
class FileVaultTest {
    private val dirs = ArrayList<File>()
    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 9 }, "AES") }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun vaultDir(): File = Files.createTempDirectory("vault-test").toFile().also(dirs::add)

    private fun data(size: Int, seed: Long = 5): ByteArray = ByteArray(size).also { java.util.Random(seed).nextBytes(it) }

    private fun dataFile(vault: FileVault, root: File, id: String) = File(File(root, id), "data.bin")

    @Test
    fun aFileRoundTripsThroughBlocksIncludingTheShortFinalBlock() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val bytes = data(4096 * 3 + 100)
        val id = vault.create("in:peer", bytes.size.toLong())
        vault.writeBlocks(id, 0, bytes)
        val info = vault.info(id)!!
        assertEquals(4, info.blockCount)
        assertTrue(info.complete)
        assertArrayEquals(bytes, vault.read(id, 0, bytes.size))
        assertArrayEquals(bytes.copyOfRange(4090, 4110), vault.read(id, 4090, 20))
        assertArrayEquals(bytes.copyOfRange(bytes.size - 5, bytes.size), vault.read(id, bytes.size - 5L, 50))
        assertEquals(0, vault.read(id, bytes.size.toLong(), 10).size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), vault.sha256(id))
        assertEquals(bytes.size.toLong() + 4 * 16, info.physicalBytes)
    }

    @Test
    fun noPlaintextIsStoredAnywhereInTheVaultDirectory() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val marker = "TOP-SECRET-MARKER-0123456789".toByteArray()
        val bytes = ByteArray(10_000) { marker[it % marker.size] }
        val id = vault.create("in:peer", bytes.size.toLong())
        vault.writeBlocks(id, 0, bytes)
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val content = file.readBytes()
            assertFalse(file.path, String(content, Charsets.ISO_8859_1).contains("TOP-SECRET-MARKER"))
        }
        assertTrue(root.walkTopDown().any { it.isFile })
    }

    @Test
    fun segmentsCanBeWrittenInAnyOrderAtTheirOffsets() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val total = MessagingV2Format.SEGMENT_DATA_BYTES * 2 + 5_000
        val bytes = data(total)
        val id = vault.create("in:peer", total.toLong())
        val seg = MessagingV2Format.SEGMENT_DATA_BYTES
        vault.writeBlocks(id, SegmentMath.firstBlock(2), bytes.copyOfRange(2 * seg, total))
        assertFalse(vault.info(id)!!.complete)
        vault.writeBlocks(id, SegmentMath.firstBlock(0), bytes.copyOfRange(0, seg))
        vault.writeBlocks(id, SegmentMath.firstBlock(1), bytes.copyOfRange(seg, 2 * seg))
        assertTrue(vault.info(id)!!.complete)
        assertArrayEquals(bytes, vault.read(id, 0, total))
    }

    @Test
    fun theFinalBlockLengthIsFixedByTheDeclaredSize() {
        val vault = FileVault(vaultDir(), cipher)
        val id = vault.create("o", 4096L + 10)
        assertThrows(IllegalArgumentException::class.java) { vault.writeBlocks(id, 0, data(4096 + 11)) }
        assertThrows(IllegalArgumentException::class.java) { vault.writeBlocks(id, 1, data(4096)) }
        assertThrows("past the end", IllegalArgumentException::class.java) { vault.writeBlocks(id, 2, data(10)) }
        assertThrows("empty", IllegalArgumentException::class.java) { vault.writeBlocks(id, 0, ByteArray(0)) }
        vault.writeBlocks(id, 1, data(10))
        vault.writeBlocks(id, 0, data(4096))
        assertTrue(vault.info(id)!!.complete)
    }

    @Test
    fun aBlockIsWrittenOnceAndOnlyIdenticalRetriesAreAccepted() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val bytes = data(8192)
        val id = vault.create("in:p", 8192)
        vault.writeBlocks(id, 0, bytes)
        val before = dataFile(vault, root, id).readBytes()
        vault.writeBlocks(id, 0, bytes)
        assertArrayEquals("an identical retry changes nothing on disk", before, dataFile(vault, root, id).readBytes())
        val different = bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertThrows(VaultImmutableException::class.java) { vault.writeBlocks(id, 0, different) }
        assertArrayEquals("the refused write left the sealed bytes alone", before, dataFile(vault, root, id).readBytes())
        assertArrayEquals(bytes, vault.read(id, 0, 8192))
    }

    @Test
    fun aCrashBetweenTheBlockAndTheMetaIsRecoveredWithoutNonceReuse() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val bytes = data(4096)
        val id = vault.create("in:p", 4096)
        val meta = File(File(root, id), "meta.bin")
        val staleMeta = meta.readBytes()
        vault.writeBlocks(id, 0, bytes)
        // The crash: the block is on disk but the meta never recorded it.
        meta.writeBytes(staleMeta)
        val recovered = FileVault(root, cipher)
        assertEquals(0, recovered.info(id)!!.writtenBlocks)
        // Different bytes are refused (a nonce is never reused for other plaintext); the identical bytes are accepted.
        assertThrows(VaultImmutableException::class.java) { recovered.writeBlocks(id, 0, bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }) }
        recovered.writeBlocks(id, 0, bytes)
        assertTrue(recovered.info(id)!!.complete)
        assertArrayEquals(bytes, recovered.read(id, 0, 4096))
    }

    @Test
    fun aTornBlockIsNeverOverwrittenAndTheFileMustBeRecreated() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val bytes = data(8192)
        val id = vault.create("in:p", 8192)
        vault.writeBlocks(id, 0, bytes.copyOf(4096))
        val staleMeta = File(File(root, id), "meta.bin").readBytes()
        vault.writeBlocks(id, 1, bytes.copyOfRange(4096, 8192))
        File(File(root, id), "meta.bin").writeBytes(staleMeta)
        // Tear block 1: only half of its sealed bytes survived.
        RandomAccessFile(dataFile(vault, root, id), "rw").use { it.setLength(4112 + 2000) }
        val recovered = FileVault(root, cipher)
        assertThrows(VaultDamagedException::class.java) { recovered.writeBlocks(id, 1, bytes.copyOfRange(4096, 8192)) }
        assertThrows(VaultDamagedException::class.java) { recovered.writeBlocks(id, 1, data(4096, 77)) }
    }

    @Test
    fun truncationTamperingAndBlockSwapsAreDetectedOnRead() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val bytes = data(4096 * 3)
        val id = vault.create("in:p", bytes.size.toLong())
        vault.writeBlocks(id, 0, bytes)
        val file = dataFile(vault, root, id)
        val original = file.readBytes()
        // Flip one ciphertext bit.
        file.writeBytes(original.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() })
        assertThrows(VaultDamagedException::class.java) { vault.readBlock(id, 0) }
        assertArrayEquals(bytes.copyOfRange(4096, 8192), vault.readBlock(id, 1))
        // Swap blocks 1 and 2: the index is in the AAD.
        val swapped = original.copyOf()
        System.arraycopy(original, 4112, swapped, 2 * 4112, 4112)
        System.arraycopy(original, 2 * 4112, swapped, 4112, 4112)
        file.writeBytes(swapped)
        assertThrows(VaultDamagedException::class.java) { vault.readBlock(id, 1) }
        assertThrows(VaultDamagedException::class.java) { vault.readBlock(id, 2) }
        // Truncate the tail.
        file.writeBytes(original.copyOf(original.size - 1))
        assertThrows(VaultDamagedException::class.java) { vault.readBlock(id, 2) }
        assertThrows(VaultDamagedException::class.java) { vault.sha256(id) }
    }

    @Test
    fun aBlockCutShortIsDamagedEvenWhenTheMissingTailByteWasZero() {
        // One tag in 256 ends in a zero byte; a block cut by that byte and zero-padded back would authenticate. Deterministic
        // keys (SHA1PRNG seeded per attempt) let the test land on such a file instead of waiting for a lucky random key.
        val root = vaultDir()
        val bytes = data(300)
        var found = 0
        for (seed in 0L until 5_000L) {
            val vault = FileVault(root, cipher, java.security.SecureRandom.getInstance("SHA1PRNG").also { it.setSeed(seed) })
            val id = vault.create("o", bytes.size.toLong())
            vault.writeBlocks(id, 0, bytes)
            val file = dataFile(vault, root, id)
            val sealed = file.readBytes()
            if (sealed.last().toInt() == 0) {
                found++
                file.writeBytes(sealed.copyOf(sealed.size - 1))
                assertThrows("seed $seed", VaultDamagedException::class.java) { vault.readBlock(id, 0) }
                assertThrows("seed $seed", VaultDamagedException::class.java) { vault.read(id, 0, bytes.size) }
                assertThrows("seed $seed", VaultDamagedException::class.java) { vault.sha256(id) }
                assertThrows("seed $seed", VaultDamagedException::class.java) { vault.writeBlocks(id, 0, bytes) }
                if (found == 3) break
            }
            vault.delete(id)
        }
        assertEquals("found files whose tag ends in a zero byte", 3, found)
    }

    @Test
    fun aBlockCopiedFromAnotherFileDoesNotAuthenticate() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val full = data(8192)
        val a = vault.create("o", 8192)
        vault.writeBlocks(a, 0, full)
        val b = vault.create("o", 4096)
        File(File(root, b), "data.bin").writeBytes(dataFile(vault, root, a).readBytes().copyOf(4112))
        val reopened = FileVault(root, cipher)
        assertEquals(0, reopened.info(b)!!.writtenBlocks)
        assertThrows(IllegalArgumentException::class.java) { reopened.readBlock(b, 0) }
        assertThrows(VaultDamagedException::class.java) { reopened.writeBlocks(b, 0, full.copyOf(4096)) }
    }

    @Test
    fun metaSurvivesARestartAndDeleteRemovesTheKeyAndTheBytes() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val bytes = data(5000)
        val id = vault.create("in:p", 5000)
        vault.writeBlocks(id, 0, bytes)
        val reopened = FileVault(root, cipher)
        assertTrue(reopened.info(id)!!.complete)
        assertArrayEquals(bytes, reopened.read(id, 0, 5000))
        assertEquals(setOf(id), reopened.ids())
        reopened.delete(id)
        assertNull(reopened.info(id))
        assertFalse(File(root, id).exists())
        assertEquals(0, reopened.usedBytes())
        // A directory whose meta is damaged is dropped rather than served.
        val id2 = reopened.create("in:p", 10)
        File(File(root, id2), "meta.bin").writeBytes(ByteArray(40))
        assertNull(FileVault(root, cipher).info(id2))
    }

    @Test
    fun usageIsCountedPerOwnerFromCreationOnward() {
        val vault = FileVault(vaultDir(), cipher)
        val a = vault.create("in:alice", 10_000)
        vault.create("in:bob", 20_000)
        assertEquals(10_000L + 3 * 16, vault.usedBytes("in:alice"))
        assertEquals(30_000L + 3 * 16 + 5 * 16, vault.usedBytes())
        vault.delete(a)
        assertEquals(0, vault.usedBytes("in:alice"))
        assertNotNull(vault.ids().firstOrNull())
        assertThrows(IllegalArgumentException::class.java) { vault.create("x", 0) }
        assertThrows(IllegalArgumentException::class.java) { vault.create("x", MessagingV2Format.MAX_FILE_BYTES + 1) }
    }

    @Test
    fun everyVaultFileHasItsOwnKeyAndNonceSpace() {
        val root = vaultDir()
        val vault = FileVault(root, cipher)
        val same = data(4096)
        val a = vault.create("o", 4096)
        val b = vault.create("o", 4096)
        vault.writeBlocks(a, 0, same)
        vault.writeBlocks(b, 0, same)
        assertFalse("same plaintext, same block index, different files: different ciphertext",
            dataFile(vault, root, a).readBytes().contentEquals(dataFile(vault, root, b).readBytes()))
    }
}
