package com.example.bluetoothtest

import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `names-v2` (Design G, G2) and the frozen `names-v1` history: vectors, hashes, list rules, the pair blocklist, collisions. */
class StrangerNameTest {
    @After
    fun tearDown() {
        StrangerNames.installForTest(null)
    }

    // --- vectors ---

    @Test
    fun theRdiVectorsMatchTheProtocolForNamesV2() {
        // The indices are the function's output and do not depend on the lists; the words do (names-v2).
        assertEquals(listOf(143, 33, 182, 219), StrangerNames.indices(NamingTestSupport.rdi("44e69c0a7a7d"), NamingTestSupport.lists).toList())
        assertEquals(listOf(6, 168, 149, 135), StrangerNames.indices(NamingTestSupport.rdi("67aba670e65a"), NamingTestSupport.lists).toList())
        val first = StrangerNames.derive(NamingTestSupport.rdi("44e69c0a7a7d"), NamingTestSupport.lists)!!
        assertEquals("mild bream", first.text)
        assertEquals(6, first.chip)
        assertEquals(19, first.number)
        assertEquals("7a7d", first.idTail)
        val next = StrangerNames.derive(NamingTestSupport.rdi("67aba670e65a"), NamingTestSupport.lists)!!
        assertEquals("amiable otter", next.text)
        assertEquals(5, next.chip)
        assertEquals(35, next.number)
    }

    @Test
    fun theNamesV1VectorsStayReproducibleForHistory() {
        val first = StrangerNames.derive(NamingTestSupport.rdi("44e69c0a7a7d"), NamingTestSupport.listsV1)!!
        assertEquals("mauve cape", first.text)
        assertEquals("alpine parrot", StrangerNames.derive(NamingTestSupport.rdi("67aba670e65a"), NamingTestSupport.listsV1)!!.text)
    }

    @Test
    fun aBlockedFirstPairIsRehashedWithACounterByte() {
        // RDI 1020000006b8: attempt 0 gives indices 77,118 = "firm jelly" (blocked); attempt 1 hashes the same input with the
        // byte 0x01 appended and gives 170,168,194,183 = "plum otter". Every phone skips the same pair the same way.
        val rdi = NamingTestSupport.rdi("1020000006b8")
        assertEquals(listOf(170, 168, 194, 183), StrangerNames.indices(rdi, NamingTestSupport.lists).toList())
        val name = StrangerNames.derive(rdi, NamingTestSupport.lists)!!
        assertEquals("plum otter", name.text)
        assertEquals(2, name.chip)
        assertEquals(83, name.number)
        // The same input without the blocklist (names-v1 has none) takes the first attempt unchanged.
        assertEquals(listOf(77, 118, 152, 4), StrangerNames.indices(rdi, NamingTestSupport.listsV1).toList())
        // An independent re-computation of attempt 1.
        val d = MessageDigest.getInstance("SHA-256").run {
            update("BluetoothVideoMesh/Naming/StrangerName/1".toByteArray(Charsets.US_ASCII))
            update(0)
            update(byteArrayOf(0x10, 0x20, 0, 0, 0x06, 0xb8.toByte()))
            update(1)
            digest()
        }
        assertEquals(170, d[0].toInt() and 0xff)
        assertEquals(168, d[1].toInt() and 0xff)
    }

    @Test
    fun noInputEverYieldsABlockedPair() {
        val lists = NamingTestSupport.lists
        val blocked = String(NamingTestSupport.blockedPairBytes, Charsets.US_ASCII).lines().filter { it.isNotEmpty() }.toSet()
        var rehashed = 0
        for (i in 0 until 60_000) {
            val input = StrangerNames.Input.rdi(NamingTestSupport.rdiCounter(i))
            val name = StrangerNames.derive(input, lists)!!.text
            assertFalse(name, name in blocked)
            if (StrangerNames.indices(input, lists)[0] != StrangerNames.indices(input, NamingTestSupport.listsV1)[0]) rehashed++
        }
        assertTrue("some inputs must have hit the blocklist ($rehashed)", rehashed > 0)
    }

    @Test
    fun theBlocklistIsWellFormedSortedAndHoldsTheFamousPairs() {
        val lines = String(NamingTestSupport.blockedPairBytes, Charsets.US_ASCII).lines().filter { it.isNotEmpty() }
        assertEquals(lines.sorted(), lines)
        assertEquals(lines.size, lines.toSet().size)
        assertEquals(lines.size, NamingTestSupport.lists.blockedCount)
        assertTrue("blue falcon" in lines)
        assertTrue("silver fox" in lines)
        assertTrue("gold dawn" in lines)
        // A line must use words of the lists, or the whole file is refused.
        assertThrows(IllegalArgumentException::class.java) {
            StrangerNameLists.parse(NamingTestSupport.lists.adjectives, NamingTestSupport.lists.nouns, listOf("blue unicorn"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrangerNameLists.parse(NamingTestSupport.lists.adjectives, NamingTestSupport.lists.nouns, listOf("blue"))
        }
    }

    @Test
    fun theSameInputGivesTheSameNameAndAnotherEpochAnotherName() {
        val a = StrangerNames.derive(NamingTestSupport.rdi("44e69c0a7a7d"), NamingTestSupport.lists)!!
        val b = StrangerNames.derive(NamingTestSupport.rdi("44e69c0a7a7d"), NamingTestSupport.lists)!!
        assertEquals(a.text, b.text)
        assertNotEquals(a.text, StrangerNames.derive(NamingTestSupport.rdi("67aba670e65a"), NamingTestSupport.lists)!!.text)
    }

    @Test
    fun aThrowawayNodeIdIsADifferentInputThanAnRdi() {
        val node = ByteArray(16) { it.toByte() }
        val name = StrangerNames.derive(StrangerNames.Input.throwawayNodeId(node), NamingTestSupport.lists)!!
        assertTrue(name.text.contains(' '))
        assertEquals("0e0f", name.idTail)
    }

    @Test
    fun theInputTypeAcceptsOnlyTheTwoPublicSizes() {
        assertThrows(IllegalArgumentException::class.java) { StrangerNames.Input.rdi(ByteArray(7)) }
        assertThrows(IllegalArgumentException::class.java) { StrangerNames.Input.rdi(ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { StrangerNames.Input.throwawayNodeId(ByteArray(6)) }
        assertThrows(IllegalArgumentException::class.java) { StrangerNames.Input.throwawayNodeId(ByteArray(32)) }
    }

    @Test
    fun noOverloadAcceptsAStableKeyType() {
        val inputMethods = StrangerNames.Input.Companion::class.java.declaredMethods.filter { !it.isSynthetic }
        inputMethods.forEach { method ->
            assertEquals(method.name, listOf(ByteArray::class.java), method.parameterTypes.toList())
        }
        val derive = StrangerNames::class.java.declaredMethods.filter { it.name == "derive" && !it.isSynthetic }
        derive.forEach { method ->
            assertTrue(method.parameterTypes.none { it == NodeId::class.java || it == IdentityFingerprint::class.java })
        }
    }

    @Test
    fun noCallerFeedsAStableValueIntoTheName() {
        // Greps every main source file: any use of the Input factories (outside their definition) may pass only an RDI or a
        // throwaway node ID, never a node ID, prefix, fingerprint, handle, lease key or per-install secret.
        val forbidden = listOf("fingerprint", "prefix", "handle", "leasekey", "presencekey", "daykey", "identity", "nodeid.tobytearray", "secret")
        val factoryCall = Regex("Input\\.(rdi|throwawayNodeId)\\(([^)]*)\\)")
        val callers = mutableListOf<String>()
        NamingTestSupport.mainSources().walkTopDown().filter { it.extension == "kt" && it.name != "StrangerNames.kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                factoryCall.findAll(line).forEach { match ->
                    callers += "${file.name}:${index + 1}"
                    val argument = match.groupValues[2].lowercase()
                    if (match.groupValues[1] == "rdi") {
                        assertTrue("${file.name}:${index + 1} passes ${match.groupValues[2]}", forbidden.none { it in argument })
                    } else {
                        assertTrue("${file.name}:${index + 1} passes ${match.groupValues[2]}", "throwaway" in argument)
                    }
                }
            }
        }
    }

    // --- frozen files ---

    @Test
    fun theWordListsAndTheBlocklistAreFrozen() {
        assertEquals(StrangerNames.ADJECTIVES_SHA256, sha256(NamingTestSupport.adjectiveBytes))
        assertEquals(StrangerNames.NOUNS_SHA256, sha256(NamingTestSupport.nounBytes))
        assertEquals(StrangerNames.BLOCKED_PAIRS_SHA256, sha256(NamingTestSupport.blockedPairBytes))
        // names-v1 stays exactly as published.
        assertEquals(StrangerNames.V1_ADJECTIVES_SHA256, sha256(NamingTestSupport.v1AdjectiveBytes))
        assertEquals(StrangerNames.V1_NOUNS_SHA256, sha256(NamingTestSupport.v1NounBytes))
    }

    @Test
    fun anEditedFileIsRefusedAtInstall() {
        val a = NamingTestSupport.adjectiveBytes
        val n = NamingTestSupport.nounBytes
        val p = NamingTestSupport.blockedPairBytes
        assertFalse(StrangerNames.installIfFrozen(a, n.copyOf().also { it[0] = 'z'.code.toByte() }, p))
        assertFalse(StrangerNames.installIfFrozen(a.copyOf().also { it[0] = 'z'.code.toByte() }, n, p))
        assertFalse("a dropped blocklist line", StrangerNames.installIfFrozen(a, n, p.copyOf(p.size - 2)))
        assertFalse(StrangerNames.isInstalled)
        assertNull(StrangerNames.derive(NamingTestSupport.rdi("44e69c0a7a7d")))
        assertTrue(StrangerNames.installIfFrozen(a, n, p))
        assertTrue(StrangerNames.isInstalled)
    }

    // --- list rules (both versions) ---

    @Test
    fun bothListsFollowTheEntryRules() {
        for (lists in listOf(NamingTestSupport.lists, NamingTestSupport.listsV1)) {
            listOf(lists.adjectives, lists.nouns).forEach { words ->
                assertEquals(256, words.size)
                words.forEach { assertTrue(it, Regex("[a-z]{3,7}").matches(it)) }
                for (i in words.indices) for (j in i + 1 until words.size) {
                    val a = words[i]
                    val b = words[j]
                    assertTrue("$a/$b are within edit distance 1", editDistance(a, b) >= 2)
                    assertFalse("$a is a prefix of $b", b.startsWith(a))
                }
            }
            assertTrue("no word in both lists", lists.adjectives.intersect(lists.nouns.toSet()).isEmpty())
        }
    }

    @Test
    fun theListsHoldNoCueWordsPoliticalColoursOrInsultingAnimals() {
        val banned = setOf(
            "police", "army", "official", "admin", "help", "rescue", "safe", "free", "verified", "trusted", "urgent", "fire", "medic",
            "red", "orange", "saffron", "green", "black", "white",
            "cow", "pig", "dog", "donkey", "monkey", "rat",
        )
        val all = NamingTestSupport.lists.adjectives + NamingTestSupport.lists.nouns
        assertTrue(all.filter { it in banned }.toString(), all.none { it in banned })
    }

    @Test
    fun everyWordFlaggedHighOrMediumByEitherReviewIsGoneFromV2() {
        val flagged = setOf(
            // adjectives
            "shining", "desert", "airy", "sour", "frank", "satin", "dense", "broad", "balmy", "simple", "cream", "cherry", "woody",
            "copper", "salty", "shallow",
            // nouns
            "cumin", "beaver", "pond", "swallow", "whale", "conch", "mound", "tetra", "clam", "deer", "snapper", "bunting", "trout",
            "llama", "sable", "sponge",
        )
        val all = (NamingTestSupport.lists.adjectives + NamingTestSupport.lists.nouns).toSet()
        assertTrue(all.intersect(flagged).toString(), all.intersect(flagged).isEmpty())
        // And the replacements the multilingual reviews suggested that passed the rules are in.
        assertTrue("bobcat" in all && "sesame" in all && "krill" in all && "eland" in all)
    }

    @Test
    fun theNearShadeRuleIsAppliedConsistently() {
        // Near-white, near-black, red, orange and green shades and skin-tone food words: none remains (no exception).
        val shades = setOf(
            "ivory", "chalk", "pearl", "onyx", "slate", "pewter", "cocoa", "coffee", "mocha", "caramel", "wheat", "honey", "almond",
            "beige", "ruby", "russet", "rose", "rosy", "sienna", "coral", "amber", "fawn", "bronze",
            "ashen", "sooty", "scarlet", "crimson", "maroon", "emerald", "lime", "olive", "jade", "mint", "apricot", "peach", "tawny",
        )
        val all = (NamingTestSupport.lists.adjectives + NamingTestSupport.lists.nouns).toSet()
        assertTrue(all.intersect(shades).toString(), all.intersect(shades).isEmpty())
    }

    @Test
    fun noWordStartsOrEndsWithAShortBadRoot() {
        // The v1 screen only matched entries of four or more letters, so "cumin" got through.
        val all = NamingTestSupport.lists.adjectives + NamingTestSupport.lists.nouns
        for (w in all) {
            assertFalse(w, w.startsWith("cum") || w.startsWith("ass") || w.startsWith("tit") || w.endsWith("ass") || w == "cu")
        }
    }

    // --- collisions ---

    @Test
    fun collidingNamesGetDigitsFromTheIdAndNeverMergeRows() {
        val lists = NamingTestSupport.lists
        val byName = HashMap<String, Int>()
        var pair: Pair<Int, Int>? = null
        for (i in 0 until 5000) {
            val name = StrangerNames.derive(StrangerNames.Input.rdi(NamingTestSupport.rdiCounter(i)), lists)!!.text
            val earlier = byName[name]
            if (earlier != null) {
                pair = earlier to i
                break
            }
            byName[name] = i
        }
        val (x, y) = pair!!
        val rows = linkedMapOf(
            "x" to StrangerNames.derive(StrangerNames.Input.rdi(NamingTestSupport.rdiCounter(x)), lists)!!,
            "y" to StrangerNames.derive(StrangerNames.Input.rdi(NamingTestSupport.rdiCounter(y)), lists)!!,
            "z" to StrangerNames.derive(NamingTestSupport.rdi("44e69c0a7a7d"), lists)!!,
        )
        val labels = GeneratedNameRows.labels(rows)
        assertEquals("a row with a unique name keeps it", "mild bream", labels["z"])
        assertNotEquals(labels["x"], labels["y"])
        // Finding 11: EVERY member of a collision group is suffixed, by one rule, never "the later one".
        assertTrue(labels["x"]!!.startsWith(rows["x"]!!.text + " "))
        assertTrue(labels["y"]!!.startsWith(rows["y"]!!.text + " "))
        assertEquals(3, labels.values.toSet().size)
        val reversed = GeneratedNameRows.labels(linkedMapOf("z" to rows["z"]!!, "y" to rows["y"]!!, "x" to rows["x"]!!))
        assertEquals(labels, reversed)
    }

    @Test
    fun whenTheTwoDigitsAlsoCollideTheLastFourHexDigitsAreAdded() {
        val lists = NamingTestSupport.lists
        val seen = HashMap<String, Int>()
        var pair: Pair<Int, Int>? = null
        for (i in 0 until 3_000_000) {
            val n = StrangerNames.derive(StrangerNames.Input.rdi(NamingTestSupport.rdiCounter(i)), lists)!!
            val earlier = seen[n.numbered]
            if (earlier != null) {
                pair = earlier to i
                break
            }
            seen[n.numbered] = i
        }
        val (x, y) = pair!!
        val rows = linkedMapOf(
            "x" to StrangerNames.derive(StrangerNames.Input.rdi(NamingTestSupport.rdiCounter(x)), lists)!!,
            "y" to StrangerNames.derive(StrangerNames.Input.rdi(NamingTestSupport.rdiCounter(y)), lists)!!,
        )
        val labels = GeneratedNameRows.labels(rows)
        assertNotEquals(labels["x"], labels["y"])
        assertTrue(labels["x"]!!.endsWith("-" + rows["x"]!!.idTail))
        assertTrue(labels["y"]!!.endsWith("-" + rows["y"]!!.idTail))
    }

    @Test
    fun aPhoneThatSeesOnlyOneOfTwoCollidingRowsShowsNoSuffix() {
        val rows = linkedMapOf("only" to StrangerNames.derive(NamingTestSupport.rdi("44e69c0a7a7d"), NamingTestSupport.lists)!!)
        assertEquals("mild bream", GeneratedNameRows.labels(rows)["only"])
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
