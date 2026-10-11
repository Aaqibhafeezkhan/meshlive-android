package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DEBUG harness `test_rotid_epoch_s=<60..900>` (DiscoveryEpochLength): gating, boundary scheduling at 60 s, mismatched
 * lengths, the RV4 counter across a later normal launch, and which periods scale (the advert epoch) and which do not
 * (presence-lease days stay real days).
 */
class ShortEpochHarnessTest {
    private val nodeId = ByteArray(16) { (it * 7 + 3).toByte() }
    // 2026-10-11 00:00:00 UTC, a multiple of 900 and of 60.
    private val t0 = 1_791_676_800L

    @Before
    fun startFromTheDefault() {
        DiscoveryEpochLength.resetForTest()
    }

    @After
    fun restore() {
        DiscoveryEpochLength.resetForTest()
        DiscoverySelectionCheck.unixSeconds = { System.currentTimeMillis() / 1000L }
    }

    private fun announcement(rdi: ByteArray) = DiscoveryAnnouncement(
        nodeIdPrefix = rdi, flags = 0, capabilitySummary = 0, streamToken = 0, routeCost = 0, sequence = 0,
        version = DiscoveryAnnouncement.ROTATING_VERSION,
    )

    private fun short(seconds: Int) {
        assertTrue(DiscoveryEpochLength.set(seconds, buildDebug = true, debuggable = true, started = false))
    }

    @Test
    fun onlyADebugBuildOfADebuggablePackageBeforeStartAcceptsAValueInRange() {
        assertEquals(900L, RotatingDiscoveryIds.epochSeconds)
        assertFalse(DiscoveryEpochLength.set(60, buildDebug = false, debuggable = true, started = false))
        assertFalse(DiscoveryEpochLength.set(60, buildDebug = true, debuggable = false, started = false))
        assertFalse(DiscoveryEpochLength.set(60, buildDebug = false, debuggable = false, started = false))
        for (bad in listOf(Int.MIN_VALUE, -60, 0, 1, 59, 901, 3600, Int.MAX_VALUE)) {
            assertFalse("value $bad", DiscoveryEpochLength.set(bad, buildDebug = true, debuggable = true, started = false))
        }
        assertEquals(900L, RotatingDiscoveryIds.epochSeconds)
        assertEquals(1_990_656L + 192, RotatingDiscoveryIds.wallEpoch(1_791_590_400L + 192 * 900L))

        assertTrue(DiscoveryEpochLength.set(60, buildDebug = true, debuggable = true, started = false))
        assertEquals(60L, RotatingDiscoveryIds.epochSeconds)
        assertTrue(DiscoveryEpochLength.set(900, buildDebug = true, debuggable = true, started = false))
        assertEquals(900L, RotatingDiscoveryIds.epochSeconds)
    }

    @Test
    fun theLengthIsFixedOnceRotatingDiscoveryStarted() {
        short(120)
        // A later activity start in the live process cannot change it; the same value is harmless.
        assertFalse(DiscoveryEpochLength.set(60, buildDebug = true, debuggable = true, started = true))
        assertEquals(120L, RotatingDiscoveryIds.epochSeconds)
        assertTrue(DiscoveryEpochLength.set(120, buildDebug = true, debuggable = true, started = true))
        // A started process on the default length does not take the extra either.
        DiscoveryEpochLength.resetForTest()
        assertFalse(DiscoveryEpochLength.set(60, buildDebug = true, debuggable = true, started = true))
        assertEquals(900L, RotatingDiscoveryIds.epochSeconds)
    }

    @Test
    fun threeBoundariesTakeThreeMinutesAt60Seconds() {
        short(60)
        val clock = DiscoveryEpochClock(EpochClockState(), "boot1") { }
        val base = RotatingDiscoveryIds.wallEpoch(t0)
        assertEquals(base, clock.read(t0 * 1000 + 500, 0).epoch)
        assertEquals(base, clock.read(t0 * 1000 + 59_000, 58_500).epoch)
        // A normal wall boundary never gets an early elapsed double tick (elapsed tick is epoch + 60 s).
        assertEquals(120_000L, DiscoveryEpochClock.ELAPSED_TICK_MS)
        val first = clock.read(t0 * 1000 + 60_100, 59_600)
        assertEquals(base + 1, first.epoch)
        assertTrue(first.ticked)
        assertEquals(base + 1, clock.read(t0 * 1000 + 119_000, 118_500).epoch)
        assertEquals(base + 2, clock.read(t0 * 1000 + 120_100, 119_600).epoch)
        assertEquals(base + 3, clock.read(t0 * 1000 + 180_100, 179_600).epoch)
        // The scheduler wakes at the boundary (+50 ms slack in the runtime), never later than one epoch.
        assertEquals(60_000L, RotatingDiscoveryIds.msUntilBoundary(t0 * 1000))
        assertEquals(1L, RotatingDiscoveryIds.msUntilBoundary(t0 * 1000 + 59_999))
        assertEquals(30_000L, RotatingDiscoveryIds.msUntilBoundary(t0 * 1000 + 30_000))
        // A stuck wall clock still rotates after one epoch plus a minute of elapsed time.
        val stuck = DiscoveryEpochClock(EpochClockState(), "boot1") { }
        assertEquals(base, stuck.read(t0 * 1000, 0).epoch)
        assertEquals(base + 1, stuck.read(t0 * 1000 + 1_000, 120_000).epoch)
    }

    @Test
    fun theVisibleByHandleWindowKeepsItsTenMinutesAndEndsOnABoundary() {
        short(60)
        for (offset in listOf(0L, 1L, 30L, 59L, 61L, 3_599L)) {
            val start = t0 + offset
            val end = VisibleByHandleWindow.endEpoch(start) * 60L
            val seconds = end - start
            assertTrue("offset $offset: $seconds", seconds in 600L..660L)
            assertTrue(VisibleByHandleWindow.isActive(VisibleByHandleWindow.endEpoch(start), end - 1))
            assertFalse(VisibleByHandleWindow.isActive(VisibleByHandleWindow.endEpoch(start), end))
        }
        assertEquals(660_000L, VisibleByHandleWindow.MAX_MS)
        DiscoveryEpochLength.resetForTest()
        assertEquals(1_500_000L, VisibleByHandleWindow.MAX_MS)
    }

    @Test
    fun phonesWithDifferentLengthsFailTheTwoEpochCheck() {
        val dayKey = ByteArray(32) { it.toByte() }
        short(60)
        val nowShort = RotatingDiscoveryIds.wallEpoch(t0 + 100)
        val offered = RotatingDiscoveryIds.ownEntry(dayKey, nodeId, nowShort, RotatingDiscoveryIds.ROLE_CALL_OFFER)
        val selected = announcement(offered.rdi)
        DiscoverySelectionCheck.unixSeconds = { t0 + 100 }
        // Same length on both phones: the opening is accepted, also a minute or two of clock difference.
        assertEquals(
            DiscoverySelectionCheck.Result.PASS,
            DiscoverySelectionCheck.check(selected, RotatingDiscoveryIds.ROLE_CALL_OFFER, nodeId, listOf(offered)),
        )
        DiscoverySelectionCheck.unixSeconds = { t0 + 100 + 2 * 60 }
        assertEquals(
            DiscoverySelectionCheck.Result.PASS,
            DiscoverySelectionCheck.check(selected, RotatingDiscoveryIds.ROLE_CALL_OFFER, nodeId, listOf(offered)),
        )
        // At 60 s the tolerance is only about two minutes of clock skew (+-2 epochs), unlike 30 min at 900 s.
        DiscoverySelectionCheck.unixSeconds = { t0 + 100 + 4 * 60 }
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_STALE,
            DiscoverySelectionCheck.check(selected, RotatingDiscoveryIds.ROLE_CALL_OFFER, nodeId, listOf(offered)),
        )
        // Verifier on the default 900 s epoch: the offered epoch number is 15 times larger than its own: stale (by design).
        DiscoveryEpochLength.resetForTest()
        DiscoverySelectionCheck.unixSeconds = { t0 + 100 }
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_STALE,
            DiscoverySelectionCheck.check(selected, RotatingDiscoveryIds.ROLE_CALL_OFFER, nodeId, listOf(offered)),
        )
        // And the other way round.
        val normal = RotatingDiscoveryIds.ownEntry(dayKey, nodeId, RotatingDiscoveryIds.wallEpoch(t0 + 100), 0)
        short(60)
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_STALE,
            DiscoverySelectionCheck.check(
                announcement(normal.rdi),
                RotatingDiscoveryIds.ROLE_CALL_OFFER, nodeId, listOf(normal),
            ),
        )
    }

    @Test
    fun theStoredCounterNeverCarriesAcrossLengths() {
        // A 60 s run, then a normal 900 s launch, then 60 s again, all on one store.
        short(60)
        var stored = EpochClockState()
        val shortClock = DiscoveryEpochClock(stored, "boot1") { stored = it }
        val shortEpoch = shortClock.read(t0 * 1000 + 5_000, 5_000).epoch
        assertEquals(t0 / 60, shortEpoch)
        shortClock.read(t0 * 1000 + 65_000, 65_000)
        assertEquals(60L, stored.epochSeconds)
        assertEquals(t0 / 60 + 1, stored.lastEpoch)
        // Fresh generation on the store so far; remember it.
        val generation = stored.generation

        DiscoveryEpochLength.resetForTest()
        var persistedAfterRestart: EpochClockState? = null
        val normalClock = DiscoveryEpochClock(stored, "boot1") { persistedAfterRestart = it }
        // The old counter is dropped when the clock loads, and persisted so a crash cannot bring it back.
        assertEquals(900L, persistedAfterRestart?.epochSeconds)
        assertEquals(-1L, persistedAfterRestart?.lastEpoch)
        val reading = normalClock.read((t0 + 3_600) * 1000 + 5_000, 3_700_000)
        assertEquals((t0 + 3_600) / 900, reading.epoch)
        assertFalse("no forward-jump recovery is needed", reading.newGeneration)
        assertEquals(generation, normalClock.state.generation)
        // Even an hour of consistent wall time later it is still a normal epoch, not a pinned one.
        val later = normalClock.read((t0 + 7_300) * 1000, 3_700_000 + 3_600_000)
        assertEquals((t0 + 7_300) / 900, later.epoch)
        assertFalse(later.newGeneration)

        // Back to 60 s: the 900 s counter is dropped the same way, and a restart at the same length keeps its epoch.
        short(60)
        val again = DiscoveryEpochClock(normalClock.state, "boot1") { stored = it }
        assertEquals(-1L, again.state.lastEpoch)
        assertEquals((t0 + 7_300) / 60, again.read((t0 + 7_300) * 1000, 1).epoch)
        val restart = DiscoveryEpochClock(stored, "boot1") { stored = it }
        assertEquals((t0 + 7_300) / 60, restart.state.lastEpoch)
        assertEquals((t0 + 7_300) / 60, restart.read((t0 + 7_310) * 1000, 11_000).epoch)
    }

    @Test
    fun aStateStoredByAnEarlierBuildIsADefaultLengthState() {
        // Earlier builds never stored a length; the loader reads 900 and the default constructor agrees.
        assertEquals(900L, EpochClockState().epochSeconds)
        val old = EpochClockState(lastEpoch = 5, tickWallEpoch = 5, tickElapsedMs = 1, bootId = "b", generation = 3)
        assertSame(old, DiscoveryEpochClock(old, "b") { throw AssertionError("must not persist") }.state)
    }

    @Test
    fun leaseDaysStayRealDaysAndOnlyTheAdvertEpochScales() {
        // The same wall instants give the same day number at every epoch length.
        val instants = listOf(t0, t0 + 899, t0 + 86_399, t0 + 86_400, t0 + 5 * 86_400 + 123)
        val normalDays = instants.map { PresenceLeases.dayOf(RotatingDiscoveryIds.wallEpoch(it)) }
        assertEquals(instants.map { Math.floorDiv(it, 86_400L) }, normalDays)
        assertEquals(PresenceLeases.EPOCHS_PER_DAY, RotatingDiscoveryIds.epochsPerDay)
        for (seconds in listOf(60, 120, 300, 600)) {
            short(seconds)
            assertEquals(instants.map { Math.floorDiv(it, 86_400L) }, instants.map { PresenceLeases.dayOf(RotatingDiscoveryIds.wallEpoch(it)) })
            assertEquals(86_400L / seconds, RotatingDiscoveryIds.epochsPerDay)
            // Day-derived limits keep their real durations: one day of lead before a forward jump, 30 days of skew.
            assertEquals(86_400L / seconds, DiscoveryEpochClock.MAX_AHEAD_EPOCHS)
            assertEquals(30L * 86_400L / seconds, ContactPresence.MAX_SKEW_EPOCHS)
        }
        short(60)
        // The 28-day window of day keys is the same days at any length, and rolls once per real day.
        var n = 0
        val keys = { ByteArray(32) { (n++).toByte() } }
        val day = Math.floorDiv(t0, 86_400L)
        val window = DayKeyWindow.fresh(t0 / 60, keys)
        assertEquals((day..day + 27).toList(), window.keys.toList())
        assertEquals(day - 1, DayKeyWindow.firstDay(t0 / 60))
        val sameDay = DayKeyWindow.roll(window, t0 / 60 + 1_000, keys)
        assertSame("1,000 short epochs (16 min.. 16.7 h) stay in the same real day", window, sameDay)
        val nextDay = DayKeyWindow.roll(window, (t0 + 86_400) / 60 + 1, keys)
        assertEquals(day + 28, nextDay.keys.max())
        assertEquals(day + 1, nextDay.keys.min())

        // Two phones with the same day key compute the same IDs per short epoch, and every epoch has its own ID.
        val key = window.getValue(day)
        val a = RotatingDiscoveryIds.ownEntry(key, nodeId, t0 / 60 + 1, RotatingDiscoveryIds.ROLE_CALL_OFFER)
        val b = RotatingDiscoveryIds.ownEntry(key, nodeId, t0 / 60 + 1, RotatingDiscoveryIds.ROLE_CALL_OFFER)
        val next = RotatingDiscoveryIds.ownEntry(key, nodeId, t0 / 60 + 2, RotatingDiscoveryIds.ROLE_CALL_OFFER)
        assertEquals(a, b)
        assertFalse(a.rdi.contentEquals(next.rdi))

        // A recognition table of a lease holder finds the contact in each of three consecutive short epochs.
        val presence = ContactPresence(nodeId, 1L, window, 0L, 0L)
        val table = RecognitionTable()
        for (step in 0 until 3) {
            val epoch = t0 / 60 + 10 + step
            table.rebuild(listOf(presence), epoch, 0L)
            val advertised = RotatingDiscoveryIds.ownEntry(window.getValue(PresenceLeases.dayOf(epoch)), nodeId, epoch, 0)
            val hits = table.lookup(advertised.rdi)
            assertEquals(1, hits.size)
            assertEquals(epoch, hits[0].epoch)
        }
    }

    @Test
    fun theBmh3EpochGuardsFollowEpochNumbersNotSeconds() {
        short(60)
        val guards = Bmh3EpochGuards()
        val base = t0 / 60
        val first = guards.forEpoch(base)
        val ephemeral = ByteArray(65) { it.toByte() }
        assertTrue(first.replayCache.firstUse(ephemeral))
        assertFalse(first.replayCache.firstUse(ephemeral))
        assertSame(first, guards.forEpoch(base))
        // The next minute is a new epoch: a msg1 replayed across the boundary meets a fresh cache and budget.
        val second = guards.forEpoch(base + 1)
        assertNotSame(first, second)
        assertTrue(second.replayCache.firstUse(ephemeral))
        // Dropped two epochs later, as at 900 s (two minutes here).
        guards.forEpoch(base + 3)
        assertNotSame(first, guards.forEpoch(base))
    }
}
