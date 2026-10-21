package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F11: a refused announcement says what the acceptance rules saw, once per address, so "heard but refused" is visible. */
class ScanRejectLogTest {
    private fun offer(id: Byte) = DiscoveryAnnouncement(
        nodeIdPrefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { id },
        flags = DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
        capabilitySummary = 0, streamToken = 0, routeCost = 0, sequence = 1,
        version = DiscoveryAnnouncement.ROTATING_VERSION,
    )

    @Test
    fun oneLinePerAddressWithIdVersionCostAndRssiBounded() {
        val log = ScanRejectLog(max = 2)
        val line = requireNotNull(log.line("ab12", "AA:BB:CC:DD:EE:01", offer(0x11), -71))
        assertTrue(line.startsWith("scan_reject: scanner=ab12 | address=AA:BB:CC:DD:EE:01 | version=5 | rotating=true | callOffer=true"))
        assertTrue(line.endsWith("| cost=0 | flags=0x%02x | id=111111111111 | rssi=-71".format(DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE)))
        assertNull("the same address is logged once", log.line("ab12", "AA:BB:CC:DD:EE:01", offer(0x22), -60))
        assertTrue(log.line("ab12", "AA:BB:CC:DD:EE:02", offer(0x22), -60) != null)
        assertNull("bounded per scanner", log.line("ab12", "AA:BB:CC:DD:EE:03", offer(0x33), -60))
    }

    @Test
    fun undecodableResultsAppearInTheStatsLineOnlyWhenPresent() {
        val stats = ScanStats("a1")
        assertTrue(stats.line("running").endsWith("rejected=0 | accepted=0"))
        stats.undecodable = 3
        assertEquals(true, stats.line("running").endsWith("accepted=0 | undecodable=3"))
    }
}
