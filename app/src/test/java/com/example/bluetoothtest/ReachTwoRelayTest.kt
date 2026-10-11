package com.example.bluetoothtest

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why `require=2` never formed a route in rotid-3996bfb2-combined-privacy (P4 two helpers): four phones in one room
 * all hear the target's own offer, a distance-vector table prefers the shortest path, so no phone ever advertised a
 * two-relay entry and a helper handed a budget of 2 dialled the target directly (one relay). These tests run the
 * page exchange between real tables for the co-located lab (the defect, kept as the control arm) and for the
 * `test_relayed_calls_no_direct` topology mask (caller and first helper blind, second helper normal).
 */
class ReachTwoRelayTest {
    private var now = 1_000L
    private val callerId = byteArrayOf(0x71, 0x72, 0x73, 0x74, 0x75, 0x76)
    private val helper1 = byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f)
    private val helper2 = byteArrayOf(0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f)
    private val target = byteArrayOf(1, 2, 3, 4, 5, 6)

    private class Phone(val name: String, val prefix: ByteArray, val table: ReachTable<String>)

    private fun phone(name: String, prefix: ByteArray, blind: Boolean) = Phone(
        name,
        prefix,
        ReachTable(prefix, ReachConfig(maxRelays = 2), { now }, Random(1), directSightings = { !blind }),
    )

    /** A connectable consenting helper offer, as every phone of the lab advertises. */
    private fun offer(prefix: ByteArray) = DiscoveryAnnouncement(
        nodeIdPrefix = prefix,
        flags = DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
            DiscoveryAnnouncement.FLAG_RELAY_CAPABLE or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT,
        capabilitySummary = NodeCapabilities.CODEC_VP9 or RelayedCalls.CONSENT_BIT,
        streamToken = 0,
        routeCost = 0,
        sequence = 0,
    )

    /** Everyone hears everyone's offer and page for 18 s (every phone is in radio range of every other). */
    private fun coLocated(phones: List<Phone>, targetPhone: Phone) {
        repeat(7) {
            val all = phones + targetPhone
            all.forEach { hearer ->
                all.filter { it !== hearer }.forEach { heard ->
                    hearer.table.observeOffer(heard.name, offer(heard.prefix), "AA:${heard.name}", rssi = -60)
                }
            }
            // Pages: each helper-capable phone advertises what its table holds to all the others.
            phones.forEach { speaker ->
                val entries = speaker.table.advertisable().map { it.entry }
                if (entries.isEmpty()) return@forEach
                val page = ReachPage(speaker.prefix, ReachPageCodec.FLAG_GATT, 0, it + 1, entries)
                (phones + targetPhone).filter { listener -> listener !== speaker }
                    .forEach { listener -> listener.table.observePage(page, "BB:${speaker.name}") }
            }
            now += 3_000
        }
    }

    private fun lab(callerBlind: Boolean, helper1Blind: Boolean): Triple<Phone, Phone, Phone> {
        val caller = phone("caller", callerId, callerBlind)
        val first = phone("h1", helper1, helper1Blind)
        val second = phone("h2", helper2, false)
        coLocated(listOf(caller, first, second), phone("target", target, false))
        return Triple(caller, first, second)
    }

    @Test
    fun aCoLocatedLabNeverBuildsATwoRelayRouteAndHelpersDialTheTargetDirectly() {
        val (caller, first, second) = lab(callerBlind = false, helper1Blind = false)
        // The defect behind P4 "two helpers": every phone hears the target, so the best cost anyone advertises is 1.
        assertTrue(caller.table.callerRoutes(target).isNotEmpty())
        assertEquals(emptyList<Int>(), caller.table.callerRoutes(target, requireRelays = 2).map { it.relays })
        assertEquals(listOf(1), first.table.advertisable().filter { it.entry.target.contentEquals(target) }.map { it.entry.cost })
        // And a first helper with a budget of 2 takes the fresh direct offer: one relay, not two.
        assertNull(first.table.setupRoute(target, 2)!!.nextHop)
        assertNotNull(second.table.setupRoute(target, 1))
    }

    @Test
    fun blindCallerAndFirstHelperMakeATwoRelayChainThroughTheSecondHelper() {
        val (caller, first, second) = lab(callerBlind = true, helper1Blind = true)

        // The caller sees the target only through pages: one relay via H2, two relays via H1.
        assertNull(caller.table.directOffer(target))
        val required = caller.table.callerRoutes(target, requireRelays = 2)
        assertEquals(1, required.size)
        assertEquals(2, required[0].relays)
        assertArrayEquals(helper1, required[0].nextHop)
        assertEquals("h1", required[0].hop.peer)
        assertEquals(listOf(1, 2), caller.table.callerRoutes(target).map { it.relays }.sorted())

        // H1 receives SETUP with budget 2: no direct offer to use, so the next hop is H2 with budget 1 left.
        val atFirst = first.table.setupRoute(target, 2)!!
        assertArrayEquals(helper2, atFirst.nextHop)
        assertEquals(1, atFirst.relays)
        assertEquals("h2", atFirst.hop.peer)
        // H2 receives SETUP with budget 1 and dials the target itself.
        val atSecond = second.table.setupRoute(target, 1)!!
        assertNull(atSecond.nextHop)
        assertEquals(0, atSecond.relays)
        assertEquals("target", atSecond.hop.peer)
        // A budget of 1 at H1 cannot reach the target through H2 (one relay would be left over).
        assertNull(first.table.setupRoute(target, 1))
    }

    @Test
    fun blindPhonesStillDialHelpersAndShowTheCostsInTheDebugLine() {
        val (caller, first, _) = lab(callerBlind = true, helper1Blind = true)
        // Offers are kept: a helper's connectable offer is how a hop is dialled even when targets are masked.
        assertNotNull(caller.table.helperOffer(helper1))
        assertNotNull(caller.table.helperOffer(helper2))
        assertTrue(caller.table.costSummary(), caller.table.costSummary().contains("c2="))
        assertTrue(first.table.costSummary(), first.table.costSummary().contains("c1="))
        assertTrue(first.table.costSummary(), !first.table.costSummary().contains("c0="))
        assertEquals("none", phone("x", callerId, false).table.costSummary())
    }
}
