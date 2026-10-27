package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 2026-10-27 (finding 6): the BMH3 pins, the Classic address book and the bad-2M PHY memory name peers this phone
 * met and were plain text. They are sealed now; a phone updated from an earlier build keeps its memory and loses the
 * plain file.
 */
class SealedTextFileTest {
    private lateinit var dir: File
    private val key = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private val otherKey = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES") }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sealed-text").toFile()
        WipeGuard.resetForTest()
    }

    @After
    fun tearDown() {
        WipeGuard.resetForTest()
        dir.deleteRecursively()
    }

    private val nodeLine = "0123456789abcdef0123456789abcdef AA:BB:CC:DD:EE:FF 1700000000000"

    @Test
    fun anUpdatedPhoneKeepsItsMemorySealedAndThePlainFileIsGone() {
        val plain = File(dir, Bmh3Pins.FILE_NAME).apply { writeText("a1b2c3d4e5f6 1700000000000") }
        val sealed = File(dir, Bmh3Pins.SEALED_FILE_NAME)
        val file = SealedTextFile(sealed, { key }, legacyPlain = plain)
        assertEquals("a1b2c3d4e5f6 1700000000000", file.read())
        assertFalse("the plaintext file is deleted", plain.exists())
        assertTrue(sealed.exists())
        assertFalse("no peer prefix is readable in the file", String(sealed.readBytes(), Charsets.ISO_8859_1).contains("a1b2c3d4e5f6"))
        // The next process reads the sealed copy.
        assertEquals("a1b2c3d4e5f6 1700000000000", SealedTextFile(sealed, { key }, legacyPlain = plain).read())
    }

    @Test
    fun theBmh3PinsAndTheClassicBookRoundTripThroughTheSealedFile() {
        val pinsFile = SealedTextFile(File(dir, Bmh3Pins.SEALED_FILE_NAME), { key })
        val pins = Bmh3Pins(object : Bmh3Pins.Storage {
            override fun read(): String? = pinsFile.read()
            override fun write(value: String) = pinsFile.write(value)
        })
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
        pins.pin(prefix)
        val reopened = Bmh3Pins(object : Bmh3Pins.Storage {
            override fun read(): String? = SealedTextFile(File(dir, Bmh3Pins.SEALED_FILE_NAME), { key }).read()
            override fun write(value: String) = Unit
        })
        assertTrue(reopened.isPinned(prefix))
        val book = ClassicAddressBookState.decode(nodeLine)
        val bookFile = SealedTextFile(File(dir, "classic-addresses.bin"), { key })
        bookFile.write(book.encode())
        assertEquals(1, ClassicAddressBookState.decode(requireNotNull(bookFile.read())).size())
    }

    @Test
    fun theBad2mMemoryMovesFromPlainPreferencesIntoTheSealedFileAndSurvivesARestart() {
        val path = File(dir, "adaptive-phy-memory.bin")
        val node = "a1b2c3d4e5f6"
        val markKey = AdaptivePhyMemory.key(BluetoothTransportOption.BLE_GATT, node)
        val marks = AdaptivePhyMarks(SealedTextFile(path, { key }), writeAsync = false)
        marks.importLegacy(mapOf(markKey to 1_700_000_000_000L, AdaptivePhyMemory.GATT_FANOUT_PURGED to true, "junk" to "x"))
        assertFalse("no peer label is readable", String(path.readBytes(), Charsets.ISO_8859_1).contains(node))
        val restarted = AdaptivePhyMarks(SealedTextFile(path, { key }), writeAsync = false)
        assertEquals(1_700_000_000_000L, restarted.get(markKey))
        assertTrue(restarted.flag(AdaptivePhyMemory.GATT_FANOUT_PURGED))
        assertNull(restarted.get("junk"))
        restarted.remove(listOf(markKey))
        assertNull(AdaptivePhyMarks(SealedTextFile(path, { key }), writeAsync = false).get(markKey))
    }

    @Test
    fun aFileSealedUnderAnotherKeyReadsAsEmptyAndIsReplacedByTheNextWrite() {
        val path = File(dir, "classic-addresses.bin")
        SealedTextFile(path, { otherKey }).write(nodeLine)
        val file = SealedTextFile(path, { key })
        assertNull(file.read())
        file.write(nodeLine)
        assertEquals(nodeLine, file.read())
    }

    /** A Keystore that refuses now (locked, busy) and works later, like the real one after unlock. */
    private class FlakyCipher(private val inner: AtRestCipher) : AtRestCipher {
        var failing = true
        override fun seal(plaintext: ByteArray): ByteArray = if (failing) throw java.security.KeyStoreException("busy") else inner.seal(plaintext)
        override fun open(sealed: ByteArray): ByteArray = if (failing) throw java.security.KeyStoreException("busy") else inner.open(sealed)
    }

    /**
     * Security review 2026-10-27 (item 6): a passing Keystore failure used to read as "empty", so the next pin or address
     * replaced the whole stored memory. Now the memory stays unloaded, nothing is written, and it loads once the Keystore
     * works again; a damaged file (another key) is still replaced.
     */
    @Test
    fun aPassingKeystoreFailureNeverWipesTheStoredMemory() {
        val path = File(dir, Bmh3Pins.SEALED_FILE_NAME)
        val stored = byteArrayOf(9, 8, 7, 6, 5, 4)
        Bmh3Pins(object : Bmh3Pins.Storage {
            val f = SealedTextFile(path, { key })
            override fun read(): String? = f.read()
            override fun write(value: String) = f.write(value)
        }).pin(stored)
        val before = path.readBytes()
        val flaky = FlakyCipher(key)
        val file = SealedTextFile(path, { flaky })
        assertThrows(SealedTextUnavailable::class.java) { file.read() }
        val pins = Bmh3Pins(object : Bmh3Pins.Storage {
            override fun read(): String? = file.read()
            override fun write(value: String) = runCatching { file.write(value) }.let { }
        })
        pins.pin(byteArrayOf(1, 1, 1, 1, 1, 1))
        assertFalse(pins.isPinned(stored))
        assertTrue("the stored pins were not written over", before.contentEquals(path.readBytes()))
        flaky.failing = false
        assertTrue("loaded once the Keystore works", pins.isPinned(stored))
        // The bad-2M memory waits the same way and keeps what it learned meanwhile.
        val marksPath = File(dir, "adaptive-phy-memory.bin")
        AdaptivePhyMarks(SealedTextFile(marksPath, { key }), writeAsync = false).put("bad2m2.BLE_GATT.aaaaaaaaaaaa", 5L)
        val flakyMarks = FlakyCipher(key)
        val marks = AdaptivePhyMarks(SealedTextFile(marksPath, { flakyMarks }), writeAsync = false)
        marks.put("bad2m2.BLE_GATT.bbbbbbbbbbbb", 6L)
        flakyMarks.failing = false
        marks.put("bad2m2.BLE_GATT.cccccccccccc", 7L)
        val reopened = AdaptivePhyMarks(SealedTextFile(marksPath, { key }), writeAsync = false)
        assertEquals(5L, reopened.get("bad2m2.BLE_GATT.aaaaaaaaaaaa"))
        assertEquals(6L, reopened.get("bad2m2.BLE_GATT.bbbbbbbbbbbb"))
        assertEquals(7L, reopened.get("bad2m2.BLE_GATT.cccccccccccc"))
    }

    @Test
    fun nothingIsWrittenOnceAnEmergencyWipeStarted() {
        val path = File(dir, "classic-addresses.bin")
        WipeGuard.begin()
        assertThrows(IllegalStateException::class.java) { SealedTextFile(path, { key }).write(nodeLine) }
        assertFalse(path.exists())
    }
}
