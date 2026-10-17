package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening a received file in another app: revocable grants, the four-descriptor cap, on-demand decryption, no plaintext file. */
class MessagingV2OpenGrantsTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val dirs = ArrayList<File>()
    private val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 3 }, "AES") }
    private val clock = AtomicLong(1_000_000L)
    private val rig = SfRig()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
        rig.cleanUp()
    }

    private fun table(ttl: Long = 5 * 60_000L, maxGrants: Int = 16) = OpenGrantTable(clock::get, java.security.SecureRandom(), ttl, 4, maxGrants)

    private fun vault(): Pair<FileVault, File> {
        val root = Files.createTempDirectory("open-grants").toFile().also(dirs::add)
        return FileVault(root, cipher) to root
    }

    private fun storeFile(vault: FileVault, bytes: ByteArray, owner: String = "in:peer"): String {
        val id = vault.create(owner, bytes.size.toLong())
        vault.writeBlocks(id, 0, bytes)
        return id
    }

    private fun data(size: Int, seed: Long = 4) = ByteArray(size).also { java.util.Random(seed).nextBytes(it) }

    // ---- grants ------------------------------------------------------------------------------------------------------

    @Test
    fun aGrantIsARandomTokenThatEndsAtItsLifetimeOrWhenRevoked() {
        val grants = table()
        val a = grants.issue("vault-a", "a.jpg", "image/jpeg", 10, "peer1")
        val b = grants.issue("vault-a", "a.jpg", "image/jpeg", 10, "peer1")
        assertNotEquals(a.token, b.token)
        assertTrue(a.token.length >= 24)
        assertTrue(a.token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNull(grants.lookup("guess"))
        assertNotNull(grants.lookup(a.token))
        clock.addAndGet(5 * 60_000L - 1)
        assertNotNull(grants.lookup(a.token))
        clock.addAndGet(1)
        assertNull("five minutes end a grant", grants.lookup(a.token))
        grants.prune()
        assertEquals(0, grants.grantCount)
        // A revoked grant is gone at once.
        val c = grants.issue("vault-a", "a.jpg", "image/jpeg", 10, "peer1")
        assertTrue(grants.revoke(c.token))
        assertNull(grants.lookup(c.token))
        assertTrue(c.revoked)
        assertFalse(grants.revoke(c.token))
    }

    @Test
    fun leavingAThreadRevokesOnlyThatThreadsGrants() {
        val grants = table()
        val one = grants.issue("v1", "a", "text/plain", 1, "peer1")
        val two = grants.issue("v2", "b", "text/plain", 1, "peer1")
        val other = grants.issue("v3", "c", "text/plain", 1, "peer2")
        val revoked = grants.revokeScope("peer1")
        assertEquals(setOf(one.token, two.token), revoked.map { it.token }.toSet())
        assertNull(grants.lookup(one.token))
        assertNull(grants.lookup(two.token))
        assertNotNull(grants.lookup(other.token))
        assertEquals(1, grants.revokeAll().size)
        assertNull(grants.lookup(other.token))
    }

    @Test
    fun theNumberOfGrantsIsBoundedAndTheOldestGivesWay() {
        val grants = table(maxGrants = 3)
        val first = grants.issue("v", "a", "text/plain", 1, "p")
        repeat(3) { grants.issue("v", "a", "text/plain", 1, "p") }
        assertNull(grants.lookup(first.token))
        assertEquals(3, grants.grantCount)
        assertTrue(first.revoked)
    }

    @Test
    fun atMostFourDescriptorsAreOpenAndASlotComesBackOnRelease() {
        val grants = table()
        val grant = grants.issue("v", "a", "text/plain", 1, "p")
        val leases = List(4) { requireNotNull(grants.acquire(grant.token)) }
        assertEquals(4, grants.openDescriptors)
        assertNull("the fifth is refused", grants.acquire(grant.token))
        leases[0].close()
        leases[0].close()
        assertEquals("closing twice frees one slot", 3, grants.openDescriptors)
        val again = grants.acquire(grant.token)
        assertNotNull(again)
        assertEquals(4, grants.openDescriptors)
        assertNull(grants.acquire("unknown-token"))
        (leases.drop(1) + again!!).forEach { it.close() }
        assertEquals(0, grants.openDescriptors)
    }

    @Test
    fun revokingStopsAnAlreadyOpenDescriptor() {
        val grants = table()
        val grant = grants.issue("v", "a", "text/plain", 1, "p")
        val lease = requireNotNull(grants.acquire(grant.token))
        lease.checkLive()
        grants.revoke(grant.token)
        assertThrows(IOException::class.java) { lease.checkLive() }
        // So does expiry.
        val other = grants.issue("v", "a", "text/plain", 1, "p")
        val lease2 = requireNotNull(grants.acquire(other.token))
        clock.addAndGet(5 * 60_000L)
        assertThrows(IOException::class.java) { lease2.checkLive() }
        // And a released lease.
        val third = grants.issue("v", "a", "text/plain", 1, "p")
        val lease3 = requireNotNull(grants.acquire(third.token))
        lease3.close()
        assertThrows(IOException::class.java) { lease3.checkLive() }
    }

    @Test
    fun prunedGrantsAreReturnedSoTheirUriPermissionsCanBeRevoked() {
        val grants = table()
        val a = grants.issue("v", "a", "text/plain", 1, "p")
        clock.addAndGet(5 * 60_000L)
        val b = grants.issue("v", "b", "text/plain", 1, "p")
        assertTrue(grants.prune().isEmpty())
        clock.addAndGet(5 * 60_000L)
        assertEquals(listOf(b.token), grants.prune().map { it.token })
        assertTrue(a.revoked)
    }

    // ---- serving bytes ---------------------------------------------------------------------------------------------

    @Test
    fun bytesAreDecryptedOnDemandAndNeverWrittenAsAPlaintextFile() {
        val (vault, root) = vault()
        val marker = "PLAINTEXT-MARKER-0123456789".toByteArray()
        val bytes = ByteArray(40_000) { marker[it % marker.size] }
        val id = storeFile(vault, bytes)
        val grants = table()
        val grant = grants.issue(id, "doc.txt", "text/plain", bytes.size.toLong(), "p")
        val before = root.walkTopDown().filter { it.isFile }.map { it.path to it.length() }.toSet()
        val reader = VaultRangeReader(vault, id, requireNotNull(grants.acquire(grant.token)))
        // Reads of odd sizes at odd offsets, across block borders, to the end and past it.
        val out = ByteArray(bytes.size)
        var position = 0L
        val rnd = java.util.Random(9)
        while (position < bytes.size) {
            val want = 1 + rnd.nextInt(9000)
            val buffer = ByteArray(want)
            val n = reader.read(position, want, buffer)
            assertTrue(n > 0)
            System.arraycopy(buffer, 0, out, position.toInt(), n)
            position += n
        }
        assertArrayEquals(bytes, out)
        assertEquals(0, reader.read(bytes.size.toLong(), 10, ByteArray(10)))
        assertEquals(bytes.size.toLong(), reader.size)
        reader.close()
        // Nothing new on disk, and no file anywhere under the vault holds the plaintext.
        assertEquals(before, root.walkTopDown().filter { it.isFile }.map { it.path to it.length() }.toSet())
        root.walkTopDown().filter { it.isFile }.forEach {
            assertFalse(it.path, String(it.readBytes(), Charsets.ISO_8859_1).contains("PLAINTEXT-MARKER"))
        }
        assertEquals("the slot was freed", 0, grants.openDescriptors)
    }

    @Test
    fun aRevokedOrReleasedReaderServesNothing() {
        val (vault, _) = vault()
        val bytes = data(20_000)
        val id = storeFile(vault, bytes)
        val grants = table()
        val grant = grants.issue(id, "a.bin", "application/octet-stream", bytes.size.toLong(), "p")
        val reader = VaultRangeReader(vault, id, requireNotNull(grants.acquire(grant.token)))
        val buffer = ByteArray(100)
        assertEquals(100, reader.read(0, 100, buffer))
        grants.revoke(grant.token)
        assertThrows(IOException::class.java) { reader.read(0, 100, buffer) }
        reader.close()
        assertThrows(IOException::class.java) { reader.read(0, 100, buffer) }
        // A bad range is refused, not clamped into someone else's bytes.
        val second = grants.issue(id, "a.bin", "application/octet-stream", bytes.size.toLong(), "p")
        val reader2 = VaultRangeReader(vault, id, requireNotNull(grants.acquire(second.token)))
        assertThrows(IOException::class.java) { reader2.read(-1, 10, ByteArray(10)) }
        assertThrows(IOException::class.java) { reader2.read(0, 20, ByteArray(10)) }
        reader2.close()
    }

    @Test
    fun aDamagedBlockIsAnErrorNotGarbage() {
        val (vault, root) = vault()
        val bytes = data(9_000)
        val id = storeFile(vault, bytes)
        val dataFile = File(File(root, id), "data.bin")
        val raw = dataFile.readBytes()
        raw[100] = (raw[100].toInt() xor 0x55).toByte()
        dataFile.writeBytes(raw)
        val grants = table()
        val grant = grants.issue(id, "a.bin", "application/octet-stream", bytes.size.toLong(), "p")
        val reader = VaultRangeReader(vault, id, requireNotNull(grants.acquire(grant.token)))
        assertThrows(IOException::class.java) { reader.read(0, 50, ByteArray(50)) }
        reader.close()
    }

    @Test
    fun saveStreamsTheWholeFileAndRefusesAnIncompleteOne() {
        val (vault, _) = vault()
        val bytes = data(4096 * 3 + 777)
        val id = storeFile(vault, bytes)
        val out = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        assertEquals(bytes.size.toLong(), VaultExporter.copy(vault, id, out) { done, _ -> progress += done })
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals(bytes.size.toLong(), progress.last())
        val partial = vault.create("in:peer", 10_000)
        vault.writeBlocks(partial, 0, ByteArray(4096))
        assertThrows(IllegalArgumentException::class.java) { VaultExporter.copy(vault, partial, ByteArrayOutputStream()) }
    }

    // ---- what the card offers --------------------------------------------------------------------------------------

    private fun jpegBytes(trailer: ByteArray = ByteArray(0)): ByteArray {
        val b = { v: IntArray -> ByteArray(v.size) { v[it].toByte() } }
        return b(intArrayOf(0xff, 0xd8)) +
            b(intArrayOf(0xff, 0xe1, 0, 20)) + "Exif\u0000\u0000SECRET-GPS-1".toByteArray() +
            b(intArrayOf(0xff, 0xdb, 0, 5, 1, 2, 3)) +
            b(intArrayOf(0xff, 0xc0, 0, 17, 8, 0, 16, 0, 16, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)) +
            b(intArrayOf(0xff, 0xda, 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0)) + ByteArray(2000) { (it % 200 + 1).toByte() } +
            b(intArrayOf(0xff, 0xd9)) + trailer
    }

    private fun zipBytes(entry: String = "AndroidManifest.xml"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun actions(bytes: ByteArray, presentation: Presentation = Presentation.FILE, complete: Boolean = true) =
        FileActionPolicy.forFile(FileSniffer.sniff(bytes), presentation, complete)

    @Test
    fun openIsOfferedOnlyForACompleteSafeFileAndSaveCarriesTheWarningOtherwise() {
        val picture = actions(jpegBytes(), Presentation.PICTURE)
        assertTrue(picture.canOpen)
        assertEquals("image/jpeg", picture.openMime)
        assertTrue(picture.canPreview)
        assertEquals(WarnLevel.NONE, picture.saveWarning)
        // Not complete yet: nothing is offered.
        val partial = actions(jpegBytes(), Presentation.PICTURE, complete = false)
        assertFalse(partial.canOpen)
        assertFalse(partial.canPreview)
        // A program that calls itself a picture.
        val elf = actions(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1) + ByteArray(600), Presentation.PICTURE)
        assertFalse(elf.canOpen)
        assertFalse(elf.canPreview)
        assertNull(elf.openMime)
        assertEquals(WarnLevel.DANGEROUS, elf.saveWarning)
        // A picture with an archive appended (a polyglot) is Save-only with the strong warning, and is never decoded.
        val polyglot = actions(jpegBytes(zipBytes()), Presentation.PICTURE)
        assertFalse(polyglot.canOpen)
        assertFalse(polyglot.canPreview)
        assertEquals(WarnLevel.DANGEROUS, polyglot.saveWarning)
        // A zip: save only, plain warning. An unknown binary the same.
        assertEquals(WarnLevel.GENERAL, actions(zipBytes("notes.txt")).saveWarning)
        assertFalse(actions(zipBytes("notes.txt")).canOpen)
        // An APK is an installable: the strong warning.
        assertEquals(WarnLevel.DANGEROUS, actions(zipBytes()).saveWarning)
        // A voice note is opened as audio.
        val voice = actions(ByteArray(24).also { it[3] = 24; "ftypM4A ".toByteArray().copyInto(it, 4) } + ByteArray(600), Presentation.VOICE)
        assertEquals("audio/mp4", voice.openMime)
    }

    @Test
    fun theEmergencyWipeReachesTheVaultAndTheOutgoingTemporaryFiles() {
        val data = Files.createTempDirectory("mv2-wipe").toFile().also(dirs::add)
        val files = listOf(
            File(data, "no_backup/store-forward/v2/vault/0123/data.bin"),
            File(data, "no_backup/store-forward/v2/vault/0123/meta.bin"),
            File(data, "no_backup/store-forward/v2/transfer-keys.properties"),
            File(data, "cache/${AttachmentPreparer.TEMP_DIR}/abcdef.tmp"),
        )
        files.forEach { it.parentFile.mkdirs(); it.writeText("secret") }
        EmergencyWipe.storageRootsFor(data, emptyList()).forEach { root -> root.listFiles()?.forEach { EmergencyWipe.deleteTree(it) } }
        files.forEach { assertFalse(it.path, it.exists()) }
        // And the grants in memory end with it.
        val grants = table()
        val grant = grants.issue("v", "a", "text/plain", 1, "p")
        grants.revokeAll()
        assertNull(grants.lookup(grant.token))
    }

    // ---- end to end through the engine ------------------------------------------------------------------------------

    private fun pair(bLimits: MessagingV2Limits = MessagingV2Limits()): Pair<SfRig.Phone, SfRig.Phone> {
        val a = rig.Phone()
        val b = rig.Phone(v2Limits = bLimits)
        rig.contacts(a, b)
        return a to b
    }

    @Test
    fun aProgramNamedLikeAPictureArrivesAsAProgram() {
        val (alice, bob) = pair()
        val elf = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1) + ByteArray(2000) { 5 }
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(elf) }, elf.size.toLong(), Presentation.PICTURE, "holiday.jpg", "image/jpeg")
        val received = rig.sync(alice, bob).responder.v2Messages.single()
        val info = received.attachment!!
        // The sender's claim survives only as a label...
        assertEquals("image/jpeg", info.mime)
        assertEquals("holiday.jpg", info.name)
        // ...and the bytes decide what the phone does with it.
        val sniff = FileSniffer.sniff(VaultSniffSource(bob.engine.vault, info.vaultId!!))
        assertEquals(SniffedType.ELF, sniff.type)
        val shown = FileActionPolicy.forFile(sniff, info.presentation, true)
        assertFalse(shown.canOpen)
        assertFalse(shown.canPreview)
        assertEquals(WarnLevel.DANGEROUS, shown.saveWarning)
        assertEquals("holiday.jpg.so", SaveName.suggest(info.name, sniff))
        assertEquals(SniffCategory.EXECUTABLE, shown.iconCategory)
    }

    @Test
    fun anOriginalJpegArrivesWithoutMetadataAndCanBeOpenedThroughAGrant() {
        val (alice, bob) = pair()
        val source = jpegBytes(trailer = "TRAILER-SECRET".toByteArray())
        val filtered = ByteArrayOutputStream().also { JpegMetadataFilter.filter({ ByteArrayInputStream(source) }, it) }.toByteArray()
        assertFalse(String(filtered, Charsets.ISO_8859_1).contains("SECRET"))
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(filtered) }, filtered.size.toLong(), Presentation.PICTURE, "IMG_0001.jpg", "image/jpeg", 16, 16)
        val info = rig.sync(alice, bob).responder.v2Messages.single().attachment!!
        val vaultId = info.vaultId!!
        val stored = bob.engine.vault.read(vaultId, 0, filtered.size)
        assertArrayEquals(filtered, stored)
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("SECRET"))
        val sniff = FileSniffer.sniff(VaultSniffSource(bob.engine.vault, vaultId))
        assertEquals(SniffedType.JPEG, sniff.type)
        assertTrue(sniff.openAllowed)
        assertEquals(16 to 16, ImageHeader.checked(sniff.type, bob.engine.vault.read(vaultId, 0, 4096)))
        assertNull("a complete file with a safe header has no refusal", TapToDecodeCheck.refusal(bob.engine.vault, vaultId))
        // A grant serves exactly these bytes, to a descriptor that stops when the thread is left.
        val grants = table()
        val grant = grants.issue(vaultId, SaveName.suggest(info.name, sniff), sniff.mimeForOpen(info.presentation), filtered.size.toLong(), alice.nodeId.toString())
        val reader = VaultRangeReader(bob.engine.vault, vaultId, requireNotNull(grants.acquire(grant.token)))
        val buffer = ByteArray(filtered.size)
        assertEquals(filtered.size, reader.read(0, filtered.size, buffer))
        assertArrayEquals(filtered, buffer)
        grants.revokeScope(alice.nodeId.toString())
        assertThrows(IOException::class.java) { reader.read(0, 10, ByteArray(10)) }
        reader.close()
    }

    @Test
    fun theAutomaticDownloadSizeFollowsTheSwitch() {
        val bytes = data(1_500_000, 31)
        val never = MessagingV2AttachOptions(autoDownloadMb = 0).applyTo(MessagingV2Limits())
        val (alice, bob) = pair(never)
        alice.engine.sendFile(bob.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.FILE, "big.bin")
        val card = rig.sync(alice, bob).responder.v2Messages.single()
        assertEquals(FileState.AWAITING_APPROVAL, card.attachment!!.state)
        // The default switch value pulls a file of this size without a tap.
        val (carol, dave) = pair(MessagingV2AttachOptions().applyTo(MessagingV2Limits()))
        carol.engine.sendFile(dave.nodeId, { ByteArrayInputStream(bytes) }, bytes.size.toLong(), Presentation.FILE, "big.bin")
        rig.sync(carol, dave)
        val info = dave.engine.threads.forPeer(carol.nodeId.toString()).single().attachment!!
        assertEquals(FileState.COMPLETE, info.state)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), dave.engine.vault.sha256(info.vaultId!!))
    }
}

/** The header checks of [TapToDecode.precheck] without Android classes (the decode itself needs a device). */
private object TapToDecodeCheck {
    fun refusal(vault: FileVault, localId: String): String? = TapToDecode.precheck(vault, localId)?.reason
}
