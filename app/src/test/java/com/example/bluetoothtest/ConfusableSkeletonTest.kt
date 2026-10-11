package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UTS #39 confusable skeletons for look-alike names (ConfusableSkeleton.kt). */
class ConfusableSkeletonTest {
    private val bundled: ConfusableTable by lazy {
        val file = listOf("src/main/assets/uts39/confusables_ma.txt", "app/src/main/assets/uts39/confusables_ma.txt")
            .map(::File).first { it.exists() }
        file.inputStream().use { ConfusableTable.parse(it) }
    }

    @Test
    fun parsesSourceTargetLinesAndSkipsCommentsAndBlanks() {
        val table = ConfusableTable.parse(
            sequenceOf("# comment", "", "0430 0061", "0049 006C", "2025 002E 002E", "zzzz 0041", "0041"),
        )
        assertEquals(3, table.size)
        assertEquals("a", table.skeleton("а"))
        assertEquals("l", table.skeleton("I"))
        assertEquals("..", table.skeleton("‥"))
    }

    @Test
    fun theBundledTableIsTheUnicodeMaDataAndAnEmptyOneIsNeverInstalled() {
        assertTrue("about 6,700 mappings expected, got ${bundled.size}", bundled.size in 6_000..8_000)
        assertEquals("A", bundled.skeleton("А")) // Cyrillic capital A
        assertEquals("o", bundled.skeleton("о")) // Cyrillic small o
        assertEquals("l", bundled.skeleton("ӏ")) // Cyrillic palochka
        // Capital I, lower-case l and the digit-one family share a skeleton, as UTS #39 section 4 requires.
        assertEquals(bundled.skeleton("I"), bundled.skeleton("l"))
    }

    @Test
    fun anAllCyrillicLookAlikeOfALatinNameHasTheSameSkeleton() {
        // R7 gap: every letter is Cyrillic, so the script check passes, yet it reads "Alice".
        val lookAlike = "Аӏісе"
        assertTrue(LabelText.scriptsAllowed(lookAlike))
        assertNotEquals("Alice", lookAlike)
        assertEquals(bundled.skeleton("Alice").lowercase(), bundled.skeleton(lookAlike).lowercase())
        assertEquals("Alice", LookAlike.find(lookAlike, listOf("Bob", "Alice"), bundled))
    }

    @Test
    fun theMinimalFallbackCatchesTheCommonCyrillicAndGreekCases() {
        val minimal = ConfusableTable.MINIMAL
        assertEquals("Alice", LookAlike.find("Аӏісе", listOf("Alice"), minimal))
        assertEquals("Omar", LookAlike.find("Οmar", listOf("Omar"), minimal))
        assertEquals("Hope", LookAlike.find("Норe", listOf("Hope"), minimal))
    }

    @Test
    fun theSameTextOrADifferentNameIsNeverFlagged() {
        assertNull(LookAlike.find("Alice", listOf("Alice"), bundled))
        assertNull(LookAlike.find("Alice", listOf("Alina", "Bob", ""), bundled))
        assertNull(LookAlike.find("", listOf("Alice"), bundled))
        // Real Cyrillic names with no Latin twin stay quiet.
        assertNull(LookAlike.find("Сергей", listOf("Sergey", "Alice"), bundled))
    }

    @Test
    fun caseAloneIsTheSameReadingButCapitalIAndLowerLStayConfusable() {
        assertEquals("alice", LookAlike.find("Alice", listOf("alice"), bundled))
        assertEquals("llya", LookAlike.find("Ilya", listOf("llya"), bundled))
    }

    @Test
    fun invisibleFormatCharactersDoNotChangeASkeleton() {
        assertEquals(bundled.skeleton("Alice"), bundled.skeleton("Al​ice⁠"))
        assertEquals("Alice", LookAlike.find("Al​ice", listOf("Alice"), bundled))
    }

    @Test
    fun accentsAreDecomposedBeforeComparing() {
        assertEquals(bundled.skeleton("é"), bundled.skeleton("é"))
    }
}
