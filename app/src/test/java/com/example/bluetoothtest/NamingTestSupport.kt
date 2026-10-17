package com.example.bluetoothtest

import java.io.File

/** Shared helpers of the Naming v2 tests: the frozen word-list files and the bundled UTS #39 table. */
internal object NamingTestSupport {
    private fun assetFile(relative: String): File =
        listOf("src/main/assets/$relative", "app/src/main/assets/$relative").map(::File).first { it.exists() }

    // names-v2: the lists the app uses, and the pair blocklist.
    val adjectiveBytes: ByteArray by lazy { assetFile("names/adjectives-v2.txt").readBytes() }
    val nounBytes: ByteArray by lazy { assetFile("names/nouns-v2.txt").readBytes() }
    val blockedPairBytes: ByteArray by lazy { assetFile("names/blocked-pairs-v2.txt").readBytes() }

    val lists: StrangerNameLists by lazy {
        StrangerNameLists.parseText(
            adjectiveBytes.toString(Charsets.US_ASCII),
            nounBytes.toString(Charsets.US_ASCII),
            blockedPairBytes.toString(Charsets.US_ASCII),
        )
    }

    // names-v1: frozen vector history (no blocklist).
    val v1AdjectiveBytes: ByteArray by lazy { assetFile("names/adjectives-v1.txt").readBytes() }
    val v1NounBytes: ByteArray by lazy { assetFile("names/nouns-v1.txt").readBytes() }
    val listsV1: StrangerNameLists by lazy {
        StrangerNameLists.parseText(v1AdjectiveBytes.toString(Charsets.US_ASCII), v1NounBytes.toString(Charsets.US_ASCII))
    }

    val bundledTable: ConfusableTable by lazy { assetFile("uts39/confusables_ma.txt").inputStream().use { ConfusableTable.parse(it) } }

    fun rdi(hex: String): StrangerNames.Input = StrangerNames.Input.rdi(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    /** A 6-byte RDI counter, for searching colliding names deterministically. */
    fun rdiCounter(i: Int): ByteArray = byteArrayOf(0x10, 0x20, (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())

    /** Source tree root: the directory holding src/main (works from the app module or the repository root). */
    fun mainSources(): File = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
}
