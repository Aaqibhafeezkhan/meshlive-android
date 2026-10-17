package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Reserved-form text rules for person names (Design G review F13; PROTOCOL "Naming v2 / Text rules"). */
class LabelTextNamingTest {
    @Before
    fun setUp() {
        StrangerNames.installForTest(NamingTestSupport.lists)
        Confusables.installForTest(NamingTestSupport.bundledTable)
    }

    @After
    fun tearDown() {
        StrangerNames.installForTest(null)
        Confusables.installForTest(ConfusableTable.MINIMAL)
    }

    private fun name(text: String): String? = LabelText.rejection(LabelKind.PERSON_NAME, text)

    @Test
    fun aNameMayNotStartOrEndWithATildeOrItsLookAlikes() {
        listOf("~", "∼", "～", "˜", "⁓", "〜").forEach { mark ->
            assertEquals("start $mark", NameTextRules.REASON, name("${mark}Rahul"))
            assertEquals("end $mark", NameTextRules.REASON, name("Rahul$mark"))
        }
    }

    @Test
    fun aTildeAnywhereIsRefused() {
        assertEquals(NameTextRules.REASON, name("Ra~hul"))
        assertEquals(NameTextRules.REASON, name("Rahul ~ Dev"))
    }

    @Test
    fun shieldAndLockSymbolsAreRefused() {
        listOf("🛡", "🔒", "🔓", "🔐", "🔏").forEach { symbol ->
            assertEquals(NameTextRules.REASON, name("Rahul $symbol"))
            assertEquals(NameTextRules.REASON, name("${symbol}Rahul"))
        }
    }

    @Test
    fun theSeparatorsTheAppUsesToJoinNamePartsAreRefused() {
        assertEquals(NameTextRules.REASON, name("Rahul - Blue Otter"))
        assertEquals(NameTextRules.REASON, name("Rahul, seen nearby"))
        assertEquals(NameTextRules.REASON, name("Rahul,seen"))
    }

    @Test
    fun aNameThatReducesToAGeneratedNameIsRefused() {
        // "Blue Otter" is a valid pair only if both words are in the lists; take the real first pair.
        val a = NamingTestSupport.lists.adjectives[0]
        val n = NamingTestSupport.lists.nouns[0]
        val pair = "${a.replaceFirstChar { it.uppercase() }} ${n.replaceFirstChar { it.uppercase() }}"
        assertEquals(NameTextRules.REASON, name(pair))
        assertEquals(NameTextRules.REASON, name(a + n))
        assertEquals(NameTextRules.REASON, name("$a  $n"))
        assertEquals(NameTextRules.REASON, name(pair.uppercase()))
        assertEquals(NameTextRules.REASON, name("$a-$n"))
        assertEquals(NameTextRules.REASON, name("$a. $n"))
    }

    @Test
    fun lookAlikeLettersOfAGeneratedNameAreRefusedToo() {
        // Find a pair containing an 'o' or 'l' and swap it for the digit that has the same UTS #39 skeleton.
        val pair = NamingTestSupport.lists.adjectives.flatMap { a -> NamingTestSupport.lists.nouns.map { n -> a to n } }
            .first { (a, n) -> (a + n).contains('o') }
        val text = (pair.first + " " + pair.second).replaceFirst('o', '0')
        assertEquals(NameTextRules.REASON, name(text))
        val cyrillic = (pair.first + " " + pair.second).replaceFirst('o', 'о')
        assertTrue(name(cyrillic) != null)
    }

    @Test
    fun ordinaryNamesAreStillAccepted() {
        assertNull(name("Rahul"))
        assertNull(name("Mum"))
        assertNull(name("Anna-Marie"))
        assertNull(name("Dr. Rao"))
        assertNull(name("Asha Rao"))
        assertNull(name("अशोक"))
    }

    @Test
    fun theRulesApplyToPersonNamesOnly() {
        assertNull(LabelText.rejection(LabelKind.STREAM_TITLE, "~Concert - tent 4~"))
        assertNull(LabelText.rejection(LabelKind.CALL_SUBJECT, "need help ~ tent 4, seen"))
    }

    @Test
    fun withoutTheListsOnlyTheMarkRulesApply() {
        StrangerNames.installForTest(null)
        assertEquals(NameTextRules.REASON, name("~Rahul"))
        val a = NamingTestSupport.lists.adjectives[0]
        val n = NamingTestSupport.lists.nouns[0]
        assertNull(name("$a $n"))
        assertFalse(NameTextRules.looksLikeGeneratedName("$a $n"))
    }

    @Test
    fun theReceiverDropsASenderThatBrokeTheRule() {
        assertFalse(LabelText.isCanonical(LabelKind.PERSON_NAME, "~Mum"))
        assertTrue(LabelText.isCanonical(LabelKind.PERSON_NAME, "Mum"))
    }
}
