package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design QV review 1, R3: a source's viewer admission under a flood of fresh request keys. */
class ViewerAdmissionBoundsTest {
    private val s = 1_000_000_000L

    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun rejectionTablesStayBoundedAndExpire() {
        // 100,000 fresh request keys, one per millisecond (100 s of a 1,000 per second flood).
        val policy = SourceCapacityRejectionPolicy()
        repeat(100_000) { i -> policy.reject("r:fake$i", i * 1_000_000L) }
        assertTrue("size ${policy.size}", policy.size <= SourceCapacityRejectionPolicy.MAX_ENTRIES)
        // Every entry has expired 31 s later: the next refusal sweeps them.
        policy.reject("r:last", 100 * s + 31 * s)
        assertEquals(1, policy.size)

        val refusals = BoundedExpiringMap<String>(maxEntries = 512)
        repeat(10_000) { i -> refusals.put("k$i", "refused", deadlineNs = i * 1_000_000L + 30 * s, nowNs = i * 1_000_000L) }
        assertEquals(512, refusals.size)
        assertNotNull("the newest stay", refusals.get("k9999", 10 * s))
        assertNull("the soonest-expiring went first", refusals.peek("k0"))
        refusals.sweep(100 * s)
        assertEquals(0, refusals.size)
    }

    @Test
    fun aCapacityRefusalStillWorksAsBefore() {
        val policy = SourceCapacityRejectionPolicy()
        policy.reject("viewer", 0L, SourceCapacityRejectionPolicy.REASON_RELAY_FULL)
        assertEquals(SourceCapacityRejectionPolicy.REASON_RELAY_FULL, policy.reasonFor("viewer"))
        assertTrue(policy.shouldSuppress("viewer", 10 * s, capacityAvailable = false, previousParentStale = false))
        assertTrue("capacity came back", !policy.shouldSuppress("viewer", 11 * s, capacityAvailable = true, previousParentStale = false))
        assertNull(policy.reasonFor("viewer"))
    }

    private class FloodResult(val legitAdmittedAtS: Int?, val maxAttemptsPerWindow: Int, val maxTracked: Int, val attempts: Int)

    /**
     * One round per second: [fakesPerSecond] fresh request keys appear and stay in the source's candidate list for
     * [listedS] s (the caller's freshness window); the real viewer appears at [legitFromS] and keeps requesting. A fake
     * attempt holds its fan-out slot for the 12 s connection timeout and then fails; the real viewer authenticates.
     * The candidate list is given newest first (an adversarial order).
     */
    private fun flood(gateOn: Boolean, fakesPerSecond: Int = 50, listedS: Int = 8, legitFromS: Int = 3, fanOut: Int = 4): FloodResult {
        val gate = PreAuthAdmissionGate { gateOn }
        val seenAt = LinkedHashMap<String, Int>()
        val connecting = HashMap<String, Int>() // key -> second its attempt ends
        val attemptTimes = mutableListOf<Int>()
        var maxTracked = 0
        for (t in 0 until 120) {
            val nowNs = t * s
            connecting.entries.filter { it.value <= t }.forEach { (key, _) ->
                connecting.remove(key)
                gate.onPreAuthFailure(key, nowNs)
            }
            repeat(fakesPerSecond) { i -> seenAt["r:fake-$t-$i"] = t }
            if (t >= legitFromS) seenAt["r:legit"] = t
            seenAt.entries.removeAll { t - it.value >= listedS }
            val candidates = seenAt.keys.filter { it !in connecting }.reversed()
            val selection = gate.select(candidates, { it }, { false }, fanOut, nowNs)
            var free = fanOut - connecting.size
            for (key in selection.chosen) {
                if (free <= 0) break
                gate.noteAttempt(key, nowNs)
                attemptTimes += t
                if (key == "r:legit") {
                    return FloodResult(t - legitFromS, maxWindow(attemptTimes), maxOf(maxTracked, gate.trackedKeys), attemptTimes.size)
                }
                connecting[key] = t + 12
                free--
            }
            maxTracked = maxOf(maxTracked, gate.trackedKeys)
        }
        return FloodResult(null, maxWindow(attemptTimes), maxTracked, attemptTimes.size)
    }

    private fun maxWindow(times: List<Int>): Int =
        times.maxOfOrNull { start -> times.count { it in start until start + 10 } } ?: 0

    @Test
    fun aRealViewerGetsInWithinFifteenSecondsOfAFiftyPerSecondFlood() {
        val on = flood(gateOn = true)
        assertNotNull("the real viewer was admitted", on.legitAdmittedAtS)
        assertTrue("admitted after ${on.legitAdmittedAtS} s", on.legitAdmittedAtS!! <= 15)
        assertTrue("attempts per 10 s: ${on.maxAttemptsPerWindow}", on.maxAttemptsPerWindow <= PreAuthAdmissionGate.MIN_ATTEMPTS_PER_WINDOW)
        assertTrue("tracked keys: ${on.maxTracked}", on.maxTracked <= 2 * PreAuthAdmissionGate.MAX_TRACKED)

        // Control arm (the old admission: list order, every free slot): the newest-first list keeps the real viewer out.
        val off = flood(gateOn = false)
        assertNull("old admission under the same flood", off.legitAdmittedAtS)
    }

    @Test
    fun withoutAFloodAWholeGroupIsDialledInOneWindow() {
        val gate = PreAuthAdmissionGate { true }
        val group = (1..6).map { "r:viewer$it" }
        val selection = gate.select(group, { it }, { false }, fanOut = 6, nowNs = 0L)
        assertEquals(group, selection.chosen)
        assertTrue(selection.deferred.isEmpty())
        // A recovery request is never deferred.
        selection.chosen.forEach { gate.noteAttempt(it, 0L) }
        val next = gate.select(listOf("r:late", "r:recovery"), { it }, { it == "r:recovery" }, fanOut = 6, nowNs = s)
        assertEquals(listOf("r:recovery"), next.chosen)
        assertEquals(listOf("r:late"), next.deferred)
    }

    @Test
    fun aViewerWhoseFirstConnectFailedIsTriedAgainAfterUntriedOnes() {
        val gate = PreAuthAdmissionGate { true }
        gate.select(listOf("r:a"), { it }, { false }, 4, 0L)
        gate.noteAttempt("r:a", 0L)
        gate.onPreAuthFailure("r:a", s)
        val order = gate.select(listOf("r:a", "r:b"), { it }, { false }, 4, 2 * s).chosen
        assertEquals("never excluded, only later", listOf("r:b", "r:a"), order)
        val after = gate.select(listOf("r:a", "r:b"), { it }, { false }, 4, 12 * s).chosen
        assertEquals("penalty over: the older key first again", listOf("r:a", "r:b"), after)
    }

    @Test
    fun aMarkerMismatchPenalisesOnlyTheRouteThatAdvertisedIt() {
        // Design QV review 1, R1: route-only, never the node, even over an authenticated link.
        val penalties = StreamRoutePenalties()
        val copier = StreamRoutePenalties.Route("AA:AA:AA:AA:AA:AA", "a1b2c3d4e5f6", 7)
        val honest = StreamRoutePenalties.Route("BB:BB:BB:BB:BB:BB", "a1b2c3d4e5f6", 7)
        penalties.onMismatch(copier, null, 0L)
        assertTrue(penalties.isPenalized(copier, null, 1_000L))
        assertTrue("the honest route with the same ID and token is untouched", !penalties.isPenalized(honest, null, 1_000L))
        assertTrue("2 minutes", !penalties.isPenalized(copier, null, StreamRoutePenalties.ROUTE_TTL_MS))
    }

    @Test
    fun theAdmissionBoundsShipOn() {
        assertTrue(DebugTunables.viewerAdmissionBounds)
    }
}
