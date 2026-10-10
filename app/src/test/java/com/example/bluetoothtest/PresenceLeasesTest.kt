package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Phase 2 building blocks: lease keys (RV11), `BDX1`, the receiver rule and the recognition table (RV3). */
class PresenceLeasesTest {
    private val presenceKey = ByteArray(32) { it.toByte() }
    private val nodeId = hex("a1b2c3d4e5f60718293a4b5c6d7e8f90")
    private val epoch = 1_990_656L
    private val week = 2_962L

    @Test
    fun leaseKeysAndOwnIdsMatchTheIndependentVectors() {
        assertEquals(
            "426c7565746f6f7468566964656f4d6573682f446973636f766572792f4c656173652f3100",
            PresenceLeases.LEASE_DOMAIN.toHex(),
        )
        assertEquals(week, PresenceLeases.weekOf(epoch))
        assertEquals("d4b8f2947b874084885be10af7cae3f5e2f5a3037d65bbd3da0af828245d568a", PresenceLeases.leaseKey(presenceKey, week).toHex())
        assertEquals("b5d948c06d9ce94699b90f2d9feebfc49817a64422d5acadc7c58cfdae8e3fdc", PresenceLeases.leaseKey(presenceKey, week + 1).toHex())
        val own = RotatingDiscoveryIds.ownEntry(presenceKey, nodeId, epoch, 0)
        assertEquals("f82bb42958807ae3f9b64ec0bccff4370d521b5719df26d6286c36e3d573265f", own.salt.toHex())
        assertEquals("ddb0a80c67aa", own.rdi.toHex())
        // The reviewed vectors hold with their P read as L_w.
        assertEquals(RotatingDiscoveryIds.entry(PresenceLeases.leaseKey(presenceKey, week), nodeId, epoch, 0), own)
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
        assertEquals("424458310100", DiscoveryExchange.encode(DiscoveryExchange.Opening(emptyList())).toHex())
        assertEquals("424458310200", DiscoveryExchange.encode(DiscoveryExchange.RecognitionOffer).toHex())
        assertSame(DiscoveryExchange.RecognitionOffer, DiscoveryExchange.decode(hex("424458310200")))
        val lease = DiscoveryExchange.PresenceLease(
            1, epoch, week, listOf(PresenceLeases.leaseKey(presenceKey, week), PresenceLeases.leaseKey(presenceKey, week + 1)),
        )
        val leaseBytes = DiscoveryExchange.encode(lease)
        assertEquals(
            "424458310300000001001e600000000b9202d4b8f2947b874084885be10af7cae3f5e2f5a3037d65bbd3da0af828245d568a" +
                "b5d948c06d9ce94699b90f2d9feebfc49817a64422d5acadc7c58cfdae8e3fdc",
            leaseBytes.toHex(),
        )
        val decoded = DiscoveryExchange.decode(leaseBytes) as DiscoveryExchange.PresenceLease
        assertEquals(1L, decoded.generation)
        assertEquals(week, decoded.firstWeek)
        assertArrayEquals(lease.leases[1], decoded.leases[1])
        assertEquals(4, PresenceLeases.delivery(presenceKey, 3, epoch).leases.size)
    }

    @Test
    fun bdx1RejectsOtherKindsAndLengths() {
        val lease = DiscoveryExchange.encode(DiscoveryExchange.PresenceLease(1, epoch, week, listOf(ByteArray(32))))
        val bad = listOf(
            hex("424458310400"), // unknown kind
            hex("424458310201"), // offer reserved byte
            hex("42445831020000"), // offer length
            hex("4244583101"), // too short
            lease.copyOf(lease.size - 1),
            lease + byteArrayOf(0),
            lease.copyOf().also { it[8] = 0 }, // generation 0
            lease.copyOf().also { it[17] = 5 }, // count 5
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
        val first = ContactPresence.receive(null, nodeId, PresenceLeases.delivery(presenceKey, 2, epoch), epoch - 3, 1_000L)
        assertEquals(3L, first.skewEpochs)
        assertEquals((week..week + 3).toSet(), first.leasesByWeek.keys)
        // A session a week later at the same generation refreshes: weeks before the new first week go.
        val refreshed = ContactPresence.receive(first, nodeId, PresenceLeases.delivery(presenceKey, 2, epoch + 672), epoch + 672, 2_000L)
        assertEquals((week + 1..week + 4).toSet(), refreshed.leasesByWeek.keys)
        // A lower generation is ignored.
        val other = ByteArray(32) { 9 }
        assertSame(refreshed, ContactPresence.receive(refreshed, nodeId, PresenceLeases.delivery(other, 1, epoch), epoch, 3_000L))
        // A higher generation (Forget/Reset) replaces everything.
        val reset = ContactPresence.receive(refreshed, nodeId, PresenceLeases.delivery(other, 3, epoch), epoch, 4_000L)
        assertEquals(3L, reset.generation)
        assertArrayEquals(PresenceLeases.leaseKey(other, week), reset.leasesByWeek[week])
        // Skew is clamped to +-30 days.
        val far = ContactPresence.receive(null, nodeId, PresenceLeases.delivery(presenceKey, 1, epoch + 10_000), epoch, 0L)
        assertEquals(ContactPresence.MAX_SKEW_EPOCHS, far.skewEpochs)
    }

    @Test
    fun recognitionUsesTheLearnedSkewAndAOneEpochWindow() {
        val contact = ContactPresence.receive(null, nodeId, PresenceLeases.delivery(presenceKey, 1, epoch + 2), epoch, 0L)
        val table = RecognitionTable()
        table.rebuild(listOf(contact), myEpoch = epoch, nowMs = 1_000L)
        assertEquals(6, table.size) // 3 epochs x roles 0 and 1
        val centre = epoch + 2
        listOf(centre - 1, centre, centre + 1).forEach { e ->
            val call = RotatingDiscoveryIds.ownEntry(presenceKey, nodeId, e, 0).rdi
            assertEquals(listOf(RecognitionTable.Hit(NodeId.fromBytes(nodeId), e, 0)), table.lookup(call))
        }
        // Outside [-1, +1] (RV3: a replay from two epochs ago no longer matches) and role 2: no hit.
        assertTrue(table.lookup(RotatingDiscoveryIds.ownEntry(presenceKey, nodeId, centre - 2, 0).rdi).isEmpty())
        assertTrue(table.lookup(RotatingDiscoveryIds.ownEntry(presenceKey, nodeId, centre, 2).rdi).isEmpty())
        // A skew older than 14 days widens the window to +-8.
        table.rebuild(listOf(contact), myEpoch = epoch, nowMs = RecognitionTable.STALE_SKEW_MS + 1)
        assertEquals(34, table.size)
        assertEquals(1, table.lookup(RotatingDiscoveryIds.ownEntry(presenceKey, nodeId, centre - 8, 1).rdi).size)
    }

    @Test
    fun aNewGenerationIsNotRecognisedWithOldLeases() {
        val contact = ContactPresence.receive(null, nodeId, PresenceLeases.delivery(presenceKey, 1, epoch), epoch, 0L)
        val table = RecognitionTable()
        table.rebuild(listOf(contact), epoch, 0L)
        val newKey = ByteArray(32) { (it + 100).toByte() }
        assertTrue(table.lookup(RotatingDiscoveryIds.ownEntry(newKey, nodeId, epoch, 0).rdi).isEmpty())
        // Epochs past the delivered weeks are not computed (a seized lease set expires).
        val later = epoch + 4 * PresenceLeases.EPOCHS_PER_WEEK + 10
        table.rebuild(listOf(contact), later, 0L)
        assertEquals(0, table.size)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
