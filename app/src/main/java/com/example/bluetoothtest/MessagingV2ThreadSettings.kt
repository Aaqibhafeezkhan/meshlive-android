package com.example.bluetoothtest

import java.io.File
import java.io.InputStream

/*
 * Messaging v2, phase 1c: the choices a conversation keeps (DECISIONS Design M, Q-M3 and Q-M4): the disappearing timer that
 * new messages of the thread carry, and the read-receipt choice of that contact. Sealed at rest like the other metadata
 * (the file holds node IDs, so it is never plain). Pure Kotlin on purpose: JVM tests cover it.
 */

/** The timers the thread info offers. Seconds; 0 is off. The engine raises anything below its floor, so these are all above it. */
object DisappearChoices {
    val SECONDS: List<Int> = listOf(0, 3_600, 86_400, 7 * 86_400, 30 * 86_400)

    /** The nearest offered choice at or below [seconds] (a peer's odd value shows as the closest smaller option). */
    fun nearest(seconds: Int): Int = SECONDS.lastOrNull { it <= seconds } ?: 0
}

class ThreadSettings(private val file: File, private val cipher: AtRestCipher) {
    private val properties = runCatching { readSealedProperties(file, cipher) }.getOrDefault(java.util.Properties())

    @Synchronized
    fun disappearSeconds(peer: String): Int = properties.getProperty("timer.$peer")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    @Synchronized
    fun setDisappearSeconds(peer: String, seconds: Int) {
        if (seconds <= 0) properties.remove("timer.$peer") else properties.setProperty("timer.$peer", seconds.toString())
        save()
    }

    /** This contact's own choice, or null when the global switch applies. */
    @Synchronized
    fun readReceipts(peer: String): Boolean? = when (properties.getProperty("receipts.$peer")) {
        "1" -> true
        "0" -> false
        else -> null
    }

    @Synchronized
    fun setReadReceipts(peer: String, value: Boolean?) {
        if (value == null) properties.remove("receipts.$peer") else properties.setProperty("receipts.$peer", if (value) "1" else "0")
        save()
    }

    /** Forgetting a contact forgets its thread choices. */
    @Synchronized
    fun forget(peer: String) {
        properties.remove("timer.$peer")
        properties.remove("receipts.$peer")
        save()
    }

    private fun save() = writeSealedProperties(file, cipher, properties)
}

/** Reads a complete vault file as a stream, one decrypted block at a time (a send again of a file this phone kept). */
class VaultInputStream(private val vault: FileVault, private val localId: String) : InputStream() {
    private val size = requireNotNull(vault.info(localId)) { "Unknown vault file" }.totalSize
    private var position = 0L
    private var block: ByteArray? = null
    private var blockIndex = -1

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        val index = (position / FileVault.BLOCK).toInt()
        if (index != blockIndex) {
            block?.fill(0)
            block = vault.readBlock(localId, index)
            blockIndex = index
        }
        val current = requireNotNull(block)
        val from = (position - index.toLong() * FileVault.BLOCK).toInt()
        val take = minOf(length, current.size - from)
        System.arraycopy(current, from, into, offset, take)
        position += take
        return take
    }

    override fun close() {
        block?.fill(0)
        block = null
        blockIndex = -1
    }
}
