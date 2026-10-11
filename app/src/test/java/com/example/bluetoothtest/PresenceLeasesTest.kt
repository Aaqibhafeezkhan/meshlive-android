package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 2 building blocks: daily lease keys (RV11, RD5), `BDX1`, the receiver rule and the recognition table (RV3).
 * The reviewed vectors were produced with day keys derived from `P = 000102…1f` (the migration derivation of
 * [PresenceLeases.legacyLeaseKey]); the day keys of a running phone are independent random keys (PresenceStoreTest).
 */
class PresenceLeasesTest {
    private val presenceKey = ByteArray(32) { it.toByte() }
    private val nodeId = hex("a1b2c3d4e5f60718293a4b5c6d7e8f90")
    private val epoch = 1_990_656L
    private val day = 20_736L

    /** A vector phone's own entry: the salt key is the migration-derived `L_d` of the epoch's day. */
    private fun own(key: ByteArray, node: ByteArray, e: Long, role: Int) =
        RotatingDiscoveryIds.ownEntry(PresenceLeases.legacyLeaseKey(key, PresenceLeases.dayOf(e)), node, e, role)

    /** The 28-day delivery of a phone whose day keys were migrated from [key] at [e]. */
    private fun delivery(key: ByteArray, generation: Long, e: Long) =
        requireNotNull(PresenceLeases.delivery(DayKeyWindow.migrated(key, e), generation, e))

    @Test
    fun leaseKeysAndOwnIdsMatchTheIndependentVectors() {
        assertEquals(
            "426c7565746f6f7468566964656f4d6573682f446973636f766572792f4c656173652f3200",
            PresenceLeases.LEASE_DOMAIN.toHex(),
        )
        assertEquals(day, PresenceLeases.dayOf(epoch))
        assertEquals(day, PresenceLeases.dayOf(epoch + 95))
        assertEquals(day + 1, PresenceLeases.dayOf(epoch + 96))
        assertEquals("6484923eb4b0b044dfb4d38feda471b389ec3b18e6fa199a41e22642f7f786b5", PresenceLeases.legacyLeaseKey(presenceKey, day).toHex())
        assertEquals("c32a563c90e92cb68e786a09357ba1b499ae20c8f122be7b10863ae635b016c7", PresenceLeases.legacyLeaseKey(presenceKey, day + 1).toHex())
        val own = own(presenceKey, nodeId, epoch, 0)
        assertEquals("81b9be731a7cdddd1954e543fb9b712dd963a24d08dcf1876ed219d00cab8ae9", own.salt.toHex())
        assertEquals("fecba64983fd", own.rdi.toHex())
        // The reviewed vectors hold with their P read as L_d.
        assertEquals(RotatingDiscoveryIds.entry(PresenceLeases.legacyLeaseKey(presenceKey, day), nodeId, epoch, 0), own)
    }

    @Test
    fun bdx1MatchesTheIndependentVectors() {
        val opening = DiscoveryExchange.Opening(listOf(RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch, 0)))
        val openingBytes = DiscoveryExchange.encode(opening)
        assertEquals(
            "42445831010144e69c0a7a7d001e600000d8cbe82b78824c1ba08d0c4eed26502189e19ccccd180e46c4826c5ba47e7167",
            openingBytes.toHex(),
        )
        assertEquals(opening, DiscoveryExchange.decode(openingBytes))
        val ownOpening = DiscoveryExchange.encode(
            DiscoveryExchange.Opening(listOf(own(presenceKey, nodeId, epoch, 0))),
        )
        assertEquals(
            "424458310101fecba64983fd001e60000081b9be731a7cdddd1954e543fb9b712dd963a24d08dcf1876ed219d00cab8ae9",
            ownOpening.toHex(),
        )
        assertEquals("424458310100", DiscoveryExchange.encode(DiscoveryExchange.Opening(emptyList())).toHex())
        assertEquals("424458310200", DiscoveryExchange.encode(DiscoveryExchange.RecognitionOffer).toHex())
        assertSame(DiscoveryExchange.RecognitionOffer, DiscoveryExchange.decode(hex("424458310200")))
        val lease = DiscoveryExchange.PresenceLease(
            1, epoch, day, listOf(PresenceLeases.legacyLeaseKey(presenceKey, day), PresenceLeases.legacyLeaseKey(presenceKey, day + 1)),
        )
        val leaseBytes = DiscoveryExchange.encode(lease)
        assertEquals(
            "424458310300000001001e600000005100026484923eb4b0b044dfb4d38feda471b389ec3b18e6fa199a41e22642f7f786b5" +
                "c32a563c90e92cb68e786a09357ba1b499ae20c8f122be7b10863ae635b016c7",
            leaseBytes.toHex(),
        )
        val decoded = DiscoveryExchange.decode(leaseBytes) as DiscoveryExchange.PresenceLease
        assertEquals(1L, decoded.generation)
        assertEquals(day, decoded.firstDay)
        assertArrayEquals(lease.leases[1], decoded.leases[1])
        // A full delivery: today and the next 27 days, 5 + 13 + 28 x 32 bytes.
        val full = delivery(presenceKey, 3, epoch)
        assertEquals(28, full.leases.size)
        assertEquals(4 + 1 + 13 + 28 * 32, DiscoveryExchange.encode(full).size)
    }

    @Test
    fun bdx1RejectsOtherKindsAndLengths() {
        val lease = DiscoveryExchange.encode(DiscoveryExchange.PresenceLease(1, epoch, day, listOf(ByteArray(32))))
        val bad = listOf(
            hex("424458310400"), // unknown kind
            hex("424458310201"), // offer reserved byte
            hex("42445831020000"), // offer length
            hex("4244583101"), // too short
            lease.copyOf(lease.size - 1),
            lease + byteArrayOf(0),
            lease.copyOf().also { it[8] = 0 }, // generation 0
            lease.copyOf().also { it[17] = 29 }, // count above 28
            lease.copyOf().also { it[17] = 0 }, // count 0
            lease.copyOf().also { it[0] = 'X'.code.toByte() },
        )
        bad.forEachIndexed { index, bytes ->
            try {
                DiscoveryExchange.decode(bytes)
                fail("case $index must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun receiverReplacesOnHigherMergesOnEqualAndIgnoresLowerGenerations() {
        val first = ContactPresence.receive(null, nodeId, delivery(presenceKey, 2, epoch), epoch - 3, 1_000L)
        assertEquals(3L, first.skewEpochs)
        assertEquals((day..day + 27).toSet(), first.leasesByDay.keys)
        // A session five days later at the same generation refreshes: days before the new first day go.
        val refreshed = ContactPresence.receive(first, nodeId, delivery(presenceKey, 2, epoch + 5 * 96), epoch + 5 * 96, 2_000L)
        assertEquals((day + 5..day + 32).toSet(), refreshed.leasesByDay.keys)
        // A lower generation is ignored.
        val other = ByteArray(32) { 9 }
        assertSame(refreshed, ContactPresence.receive(refreshed, nodeId, delivery(other, 1, epoch), epoch, 3_000L))
        // A higher generation (Forget/Reset) replaces everything.
        val reset = ContactPresence.receive(refreshed, nodeId, delivery(other, 3, epoch), epoch, 4_000L)
        assertEquals(3L, reset.generation)
        assertArrayEquals(PresenceLeases.legacyLeaseKey(other, day), reset.leasesByDay[day])
        assertEquals(28, reset.leasesByDay.size)
        // Skew is clamped to +-30 days.
        val far = ContactPresence.receive(null, nodeId, delivery(presenceKey, 1, epoch + 10_000), epoch, 0L)
        assertEquals(ContactPresence.MAX_SKEW_EPOCHS, far.skewEpochs)
    }

    @Test
    fun twoKeysUnderOneGenerationNumberReplaceInsteadOfMixing() {
        // RD1(d): the generation counter was lost and reused for a different key. Overlapping days differ, so the
        // stored record is replaced, never merged with leases of another key.
        val stored = ContactPresence.receive(null, nodeId, delivery(presenceKey, 4, epoch), epoch, 1L)
        val newKey = ByteArray(32) { (it + 50).toByte() }
        val next = ContactPresence.receive(stored, nodeId, delivery(newKey, 4, epoch + 96), epoch, 2L)
        assertNotSame(stored, next)
        assertEquals((day + 1..day + 28).toSet(), next.leasesByDay.keys)
        next.leasesByDay.forEach { (d, key) -> assertArrayEquals(PresenceLeases.legacyLeaseKey(newKey, d), key) }
    }

    @Test
    fun recognitionUsesTheLearnedSkewAndAOneEpochWindow() {
        val contact = ContactPresence.receive(null, nodeId, delivery(presenceKey, 1, epoch + 2), epoch, 0L)
        val table = RecognitionTable()
        table.rebuild(listOf(contact), myEpoch = epoch, nowMs = 1_000L)
        assertEquals(6, table.size) // 3 epochs x roles 0 and 1
        val centre = epoch + 2
        listOf(centre - 1, centre, centre + 1).forEach { e ->
            val call = own(presenceKey, nodeId, e, 0).rdi
            assertEquals(listOf(RecognitionTable.Hit(NodeId.fromBytes(nodeId), e, 0)), table.lookup(call))
        }
        // Outside [-1, +1] (RV3: a replay from two epochs ago no longer matches) and role 2: no hit.
        assertTrue(table.lookup(own(presenceKey, nodeId, centre - 2, 0).rdi).isEmpty())
        assertTrue(table.lookup(own(presenceKey, nodeId, centre, 2).rdi).isEmpty())
        // A skew older than 14 days widens the window to +-8; the lease starts on day 20736 (epoch itself), so
        // the epochs before it (centre - 8 .. epoch - 1) have no key and are not computed (RD5).
        table.rebuild(listOf(contact), myEpoch = epoch, nowMs = RecognitionTable.STALE_SKEW_MS + 1)
        assertEquals(22, table.size) // epoch .. epoch + 10, roles 0 and 1
        assertEquals(1, table.lookup(own(presenceKey, nodeId, centre + 8, 1).rdi).size)
        assertTrue(table.lookup(own(presenceKey, nodeId, centre - 8, 1).rdi).isEmpty())
    }

    @Test
    fun aNewGenerationIsNotRecognisedWithOldLeasesAndOldDaysExpire() {
        val contact = ContactPresence.receive(null, nodeId, delivery(presenceKey, 1, epoch), epoch, 0L)
        val table = RecognitionTable()
        table.rebuild(listOf(contact), epoch, 0L)
        val newKey = ByteArray(32) { (it + 100).toByte() }
        assertTrue(table.lookup(own(newKey, nodeId, epoch, 0).rdi).isEmpty())
        // Epochs past the delivered days are not computed (a seized lease set expires after 28 days).
        val later = epoch + 28 * PresenceLeases.EPOCHS_PER_DAY + 10
        table.rebuild(listOf(contact), later, 0L)
        assertEquals(0, table.size)
    }

    @Test
    fun aLeaseDoesNotResolveIdsBeforeItsFirstDay() {
        // RD5: a holder delivered on day D cannot recognise IDs a passive sensor logged on day D-1 or earlier.
        val delivered = ContactPresence.receive(null, nodeId, delivery(presenceKey, 1, epoch + 96), epoch + 96, 0L)
        val table = RecognitionTable()
        table.rebuild(listOf(delivered), epoch + 96, 0L)
        val yesterday = own(presenceKey, nodeId, epoch + 95, 0).rdi
        val today = own(presenceKey, nodeId, epoch + 96, 0).rdi
        assertTrue(table.lookup(yesterday).isEmpty())
        assertEquals(1, table.lookup(today).size)
    }

    // ----- hardening: independent random day keys (PROTOCOL "Keys and epochs") -----

    private fun counter(seed: Int): () -> ByteArray {
        var n = seed
        return { ByteArray(32) { i -> (n * 31 + i).toByte() }.also { n++ } }
    }

    @Test
    fun theWindowIsTodayPlus27AndYesterdayOnlyAtTheFirstEpochOfADay() {
        val first = epoch // the first epoch of day 20736
        assertEquals(day * PresenceLeases.EPOCHS_PER_DAY, first)
        val created = DayKeyWindow.fresh(first, counter(1))
        assertEquals((day..day + 27).toList(), created.keys.toList())
        // The same epoch and the rest of the day change nothing: the very same map comes back.
        assertSame(created, DayKeyWindow.roll(created, first, counter(100)))
        assertSame(created, DayKeyWindow.roll(created, first + 95, counter(100)))
        // The first epoch of the next day keeps yesterday (the previous epoch's ID needs it) and adds day + 28.
        val nextDay = DayKeyWindow.roll(created, first + 96, counter(100))
        assertEquals((day..day + 28).toList(), nextDay.keys.toList())
        // One epoch later yesterday is deleted.
        val later = DayKeyWindow.roll(nextDay, first + 97, counter(200))
        assertEquals((day + 1..day + 28).toList(), later.keys.toList())
        // A key that stayed in the window is byte for byte the same (never recreated within a generation).
        (day + 1..day + 27).forEach { d -> assertArrayEquals(created[d], later[d]) }
    }

    @Test
    fun aPhoneThatWasOffForMoreThanAMonthGetsAWholeNewWindowAndKeepsNoOldDay() {
        val created = DayKeyWindow.fresh(epoch, counter(1))
        val back = DayKeyWindow.roll(created, epoch + 40 * PresenceLeases.EPOCHS_PER_DAY, counter(100))
        assertEquals((day + 40..day + 67).toList(), back.keys.toList())
        back.values.forEach { key -> assertTrue(created.values.none { it.contentEquals(key) }) }
    }

    @Test
    fun aSeizedWindowNamesNoEarlierAndNoLaterDayAndDaysAreIndependent() {
        // The window of a phone read at epoch E: [dayOf(E - 1), dayOf(E) + 27]. Nothing else exists to be computed.
        val now = epoch + 5 * PresenceLeases.EPOCHS_PER_DAY + 7
        val window = DayKeyWindow.roll(DayKeyWindow.fresh(epoch, counter(1)), now, counter(300))
        assertEquals((day + 5..day + 32).toList(), window.keys.toList())
        assertTrue(window.keys.none { it < day + 5 })
        assertEquals(day + 32, DayKeyWindow.lastDay(now))
        // The key of the first day after the window comes from the random source, not from the stored keys: two
        // different sources give different keys for the same state.
        val tomorrow = now + PresenceLeases.EPOCHS_PER_DAY
        val a = DayKeyWindow.roll(window, tomorrow, counter(700))[day + 33]
        val b = DayKeyWindow.roll(window, tomorrow, counter(900))[day + 33]
        assertTrue(a != null && b != null && !a.contentEquals(b))
        // No ratchet: the next day's key is not a hash or an HMAC of a day the holder has.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        window.keys.toList().dropLast(1).forEach { d ->
            val next = requireNotNull(window[d + 1])
            assertTrue(!next.contentEquals(digest.digest(window.getValue(d))))
            assertTrue(!next.contentEquals(PresenceLeases.legacyLeaseKey(window.getValue(d), d + 1)))
        }
    }

    @Test
    fun theMigrationWindowIsTheEarlierDerivationForTodayAndTheNext27Days() {
        val window = DayKeyWindow.migrated(presenceKey, epoch)
        // The first epoch of a day: yesterday is part of the window (its previous-epoch ID was advertised).
        assertEquals((day - 1..day + 27).toList(), window.keys.toList())
        assertEquals((day..day + 27).toList(), DayKeyWindow.migrated(presenceKey, epoch + 1).keys.toList().filter { it >= day })
        assertEquals("6484923eb4b0b044dfb4d38feda471b389ec3b18e6fa199a41e22642f7f786b5", window.getValue(day).toHex())
        assertEquals("c32a563c90e92cb68e786a09357ba1b499ae20c8f122be7b10863ae635b016c7", window.getValue(day + 1).toHex())
        // A delivery is exactly the bytes of the earlier format: contacts need no change.
        val lease = requireNotNull(PresenceLeases.delivery(window, 3, epoch))
        assertEquals(28, lease.leases.size)
        assertEquals(4 + 1 + 13 + 28 * 32, DiscoveryExchange.encode(lease).size)
        // An incomplete window delivers nothing instead of a short lease.
        assertEquals(null, PresenceLeases.delivery(window.filterKeys { it != day + 10 }, 3, epoch))
    }

    @Test
    fun aContactDeletesLeaseDaysThatAreOver() {
        val received = ContactPresence.receive(null, nodeId, delivery(presenceKey, 1, epoch), epoch, 0L)
        assertEquals((day..day + 27).toSet(), received.leasesByDay.keys)
        // Same day: nothing to delete. Two days later the first day is over (one day of margin stays).
        assertSame(received, received.pruned(epoch))
        val twoDaysLater = requireNotNull(received.pruned(epoch + 2 * PresenceLeases.EPOCHS_PER_DAY))
        assertEquals((day + 1..day + 27).toSet(), twoDaysLater.leasesByDay.keys)
        // The learned skew moves the sender's day: a sender 3 epochs ahead near midnight.
        val skewed = ContactPresence.receive(null, nodeId, delivery(presenceKey, 1, epoch + 95), epoch, 0L)
        assertEquals(95L, skewed.skewEpochs)
        assertEquals(skewed.leasesByDay.keys, requireNotNull(skewed.pruned(epoch)).leasesByDay.keys)
        // A lease that is all over leaves nothing.
        assertEquals(null, received.pruned(epoch + 60 * PresenceLeases.EPOCHS_PER_DAY))
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
