package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattResourceRegistryTest {
    private var now = 1_000L
    private val registry = GattResourceRegistry { now }

    @Test
    fun registrationsBalanceAcrossConnectCancelCycles() {
        repeat(100) {
            val client = Any()
            registry.registerObject(client, "gatt-video", GattResourceRegistry.Kind.CLIENT, "AA:BB")
            assertEquals(1, registry.count(GattResourceRegistry.Kind.CLIENT))
            assertNotNull(registry.releaseObject(client))
        }
        val snapshot = registry.snapshot()
        assertEquals(0, snapshot.registrations)
        assertEquals(100L, snapshot.opened)
        assertEquals(100L, snapshot.closed)
        assertEquals(0L, snapshot.outstanding)
    }

    @Test
    fun aLeakedClientShowsInTheCountsAndAgeList() {
        val leaked = Any()
        registry.registerObject(leaked, "gatt-video", GattResourceRegistry.Kind.CLIENT, "AA:BB")
        val server = Any()
        registry.registerObject(server, "gatt-video-server", GattResourceRegistry.Kind.SERVER)
        now += 60_000L
        assertEquals(2, registry.snapshot().registrations)
        assertEquals(1, registry.countByOwner("gatt-video"))
        assertEquals(2, registry.older(30_000L).size)
        assertTrue(registry.line("test"), "clients=1 | servers=1 | scanners=0 | registrations=2/30" in registry.line("test"))
    }

    @Test
    fun releasingTwiceOrAnUnknownObjectIsHarmless() {
        val client = Any()
        registry.registerObject(client, "x", GattResourceRegistry.Kind.CLIENT)
        assertNotNull(registry.releaseObject(client))
        assertNull(registry.releaseObject(client))
        assertNull(registry.releaseObject(Any()))
        // Registering the same object again returns the same handle, not a second slot.
        val first = registry.registerObject(client, "x", GattResourceRegistry.Kind.CLIENT)
        val second = registry.registerObject(client, "x", GattResourceRegistry.Kind.CLIENT)
        assertEquals(first, second)
        assertEquals(1, registry.count())
    }

    @Test
    fun oneClientPerRemoteIsCountedAndCrossedLinksAreWarned() {
        assertTrue(registry.dialWarnings("AA:BB").isEmpty())
        registry.registerObject(Any(), "gatt-video", GattResourceRegistry.Kind.CLIENT, "AA:BB")
        assertEquals(listOf("second_client_to_same_remote"), registry.dialWarnings("AA:BB"))
        assertEquals(1, registry.liveClientsFor("AA:BB"))
        assertEquals(0, registry.liveClientsFor("CC:DD"))
        registry.noteServerLink("CC:DD")
        assertEquals(listOf("crossed_connection_server_link_exists"), registry.dialWarnings("CC:DD"))
        registry.dropServerLink("CC:DD")
        assertTrue(registry.dialWarnings("CC:DD").isEmpty())
        assertTrue(registry.dialWarnings(null).isEmpty())
    }

    @Test
    fun staleCleanupFiresOnlyForALiveClientNobodyIsClosing() {
        val client = Any()
        assertFalse(registry.needsStaleCleanup(client))
        registry.registerObject(client, "gatt-video", GattResourceRegistry.Kind.CLIENT, "AA:BB")
        assertTrue(registry.needsStaleCleanup(client))
        registry.markClosing(client)
        assertFalse(registry.needsStaleCleanup(client))
        registry.releaseObject(client)
        assertFalse(registry.needsStaleCleanup(client))
    }

    @Test
    fun closeIdleClosesOnlyOldInactiveClientsWithACloser() {
        val closed = mutableListOf<String>()
        val active = Any()
        val idle = Any()
        val young = Any()
        val noCloser = Any()
        registry.registerObject(active, "a", GattResourceRegistry.Kind.CLIENT, "1", closer = { closed += "active" })
        registry.registerObject(idle, "b", GattResourceRegistry.Kind.CLIENT, "2", closer = { closed += "idle" })
        registry.registerObject(noCloser, "c", GattResourceRegistry.Kind.CLIENT, "4")
        registry.setActive(active, true)
        now += 20_000L
        registry.registerObject(young, "d", GattResourceRegistry.Kind.CLIENT, "3", closer = { closed += "young" })
        assertEquals(1, registry.idleClosable(15_000L))
        assertEquals(1, registry.closeIdle(15_000L))
        assertEquals(listOf("idle"), closed)
        assertEquals(3, registry.count(GattResourceRegistry.Kind.CLIENT))
        assertFalse(registry.isLive(idle))
        assertTrue(registry.isLive(active))
    }

    @Test
    fun exhaustionStatusesAreClassified() {
        assertEquals(PoolVerdict.REGISTRATION_EXHAUSTED, GattResourceRegistry.classifyStatus(257))
        assertEquals(PoolVerdict.CLIENT_POOL_EXHAUSTED, GattResourceRegistry.classifyStatus(128))
        assertEquals(PoolVerdict.NONE, GattResourceRegistry.classifyStatus(133))
        assertEquals(PoolVerdict.NONE, GattResourceRegistry.classifyStatus(0))
    }

    @Test
    fun aPoolFailureRetriesOnceAfterClosingIdleClientsThenSurfaces() {
        val policy = PoolExhaustionPolicy { now }
        assertEquals(PoolExhaustionPolicy.Action.RETRY_AFTER_CLOSING_IDLE, policy.onConnectFailure(257, "A", true, ownIdleClients = 2))
        assertEquals(PoolExhaustionPolicy.Action.SURFACE, policy.onConnectFailure(257, "A", true, ownIdleClients = 2))
        policy.onConnectSuccess()
        // Nothing of ours to close: nothing to retry after.
        assertEquals(PoolExhaustionPolicy.Action.SURFACE, policy.onConnectFailure(128, "A", true, ownIdleClients = 0))
        assertEquals(PoolExhaustionPolicy.Action.NOT_A_POOL_FAILURE, policy.onConnectFailure(133, "A", true, ownIdleClients = 3))
    }

    @Test
    fun sixFreshConnectFailuresAcrossTwoPeersAskForARestart() {
        val policy = PoolExhaustionPolicy { now }
        repeat(5) { policy.onConnectFailure(133, "A", true, 0) }
        assertFalse(policy.restartGuidanceDue())
        policy.onConnectFailure(133, "B", true, 0)
        assertTrue(policy.restartGuidanceDue())
        policy.onConnectSuccess()
        assertFalse(policy.restartGuidanceDue())
        // One peer alone never trips it: that peer is the problem, not the stack.
        repeat(10) { policy.onConnectFailure(133, "A", true, 0) }
        assertFalse(policy.restartGuidanceDue())
        // Peers out of range do not count.
        val other = PoolExhaustionPolicy { now }
        repeat(10) { other.onConnectFailure(133, if (it % 2 == 0) "A" else "B", false, 0) }
        assertFalse(other.restartGuidanceDue())
    }
}
