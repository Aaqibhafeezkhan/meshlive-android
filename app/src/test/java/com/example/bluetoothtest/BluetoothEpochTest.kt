package com.example.bluetoothtest

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothEpochTest {
    private val clock = FakeScanClock()
    private val events = ArrayList<String>()
    private val epoch = BluetoothEpochCore(clock, Random(1), { clock.now })

    private companion object {
        const val OFF = 10
        const val TURNING_ON = 11
        const val ON = 12
        const val TURNING_OFF = 13
        const val BLE_TURNING_ON = 14
        const val BLE_ON = 15
        const val BLE_TURNING_OFF = 16
    }

    private fun consumer(name: String) = epoch.register(name, { events += "off:$name" }, { events += "on:$name" })

    @Test
    fun anOffOnEpisodeRebuildsOnceAfterTheDelayInRegistrationOrder() {
        consumer("scanners")
        consumer("advertiser")
        consumer("gatt-server")
        epoch.seed(ON)
        epoch.onState(TURNING_OFF)
        // Teardown runs in reverse order, once.
        assertEquals(listOf("off:gatt-server", "off:advertiser", "off:scanners"), events)
        epoch.onState(OFF)
        assertEquals(3, events.size)
        epoch.onState(TURNING_ON)
        epoch.onState(ON)
        assertEquals(1, epoch.epoch)
        assertEquals(3, events.size) // not before the delay
        clock.advance(BluetoothEpochCore.BASE_DELAY_MS - 1)
        assertEquals(3, events.size)
        clock.advance(BluetoothEpochCore.JITTER_MS + 1)
        assertEquals(
            listOf(
                "off:gatt-server", "off:advertiser", "off:scanners",
                "on:scanners", "on:advertiser", "on:gatt-server",
            ),
            events,
        )
    }

    @Test
    fun spuriousRepeatsChangeNothing() {
        consumer("a")
        epoch.seed(ON)
        epoch.onState(ON)
        epoch.onState(ON)
        clock.advance(10_000L)
        assertTrue(events.isEmpty())
        assertEquals(0, epoch.epoch)
        epoch.onState(OFF)
        epoch.onState(OFF)
        epoch.onState(TURNING_OFF)
        assertEquals(listOf("off:a"), events) // one teardown per episode
        epoch.onState(ON)
        epoch.onState(ON)
        clock.advance(10_000L)
        assertEquals(listOf("off:a", "on:a"), events)
        assertEquals(1, epoch.epoch)
    }

    @Test
    fun hiddenBleStatesAreToleratedAndIgnored() {
        consumer("a")
        epoch.seed(ON)
        // Classic off but the LE stack stays up (scan-always-available): nothing was lost.
        epoch.onState(TURNING_OFF)
        assertEquals(listOf("off:a"), events)
        epoch.onState(BLE_ON)
        epoch.onState(BLE_TURNING_OFF)
        epoch.onState(BLE_TURNING_ON)
        epoch.onState(OFF)
        epoch.onState(ON)
        clock.advance(10_000L)
        assertEquals(listOf("off:a", "on:a"), events)
        assertEquals(1, epoch.epoch)
        // An unknown state number does nothing.
        epoch.onState(99)
        assertEquals(2, events.size)
    }

    @Test
    fun aQuickFlapCancelsTheRebuildThatWasWaiting() {
        consumer("a")
        epoch.seed(ON)
        epoch.onState(OFF)
        epoch.onState(ON)
        clock.advance(100L)
        epoch.onState(TURNING_OFF) // off again before the delay ran
        clock.advance(10_000L)
        assertEquals(listOf("off:a"), events) // no on: while the adapter is down
        epoch.onState(ON)
        clock.advance(10_000L)
        assertEquals(listOf("off:a", "on:a"), events)
        assertEquals(2, epoch.epoch)
    }

    @Test
    fun attachingToAnAdapterThatIsTurningOnBuildsAnEpochWhenItArrives() {
        consumer("a")
        epoch.seed(TURNING_ON)
        epoch.onState(ON)
        clock.advance(10_000L)
        assertEquals(listOf("on:a"), events)
        assertEquals(1, epoch.epoch)
    }

    @Test
    fun anUnregisteredConsumerGetsNothingAndAThrowingOneDoesNotStopTheRest() {
        epoch.register("bad", { throw IllegalStateException("boom") }, { throw IllegalStateException("boom") })
        consumer("good")
        consumer("gone")
        epoch.unregister("gone")
        epoch.seed(ON)
        epoch.onState(OFF)
        epoch.onState(ON)
        clock.advance(10_000L)
        assertEquals(listOf("off:good", "on:good"), events)
    }

    @Test
    fun rebuildDelayBacksOffWithFlapsInTheLastMinuteAndIsCapped() {
        val delays = (0..7).map { epoch.rebuildDelayMs(it, 0L) }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L, 8_000L), delays)
        assertEquals(8_100L, epoch.rebuildDelayMs(9, 100L))
        // Real flaps: each rebuild inside a minute waits longer.
        consumer("a")
        epoch.seed(ON)
        val seen = ArrayList<Long>()
        epoch.register("t", {}, { seen += clock.now })
        for (i in 0 until 4) {
            epoch.onState(OFF)
            val t = clock.now
            epoch.onState(ON)
            clock.advance(20_000L)
            assertTrue(seen.size == i + 1)
            // Delay actually waited is at least the base for this flap.
            assertTrue(seen[i] - t >= minOf(500L shl i, 8_000L))
        }
    }

    @Test
    fun threeQuickOffEpisodesAreASuspectedBluetoothCrashLoop() {
        consumer("a")
        epoch.seed(ON)
        assertFalse(epoch.crashLoopSuspected)
        repeat(2) {
            epoch.onState(OFF)
            clock.advance(400L)
            epoch.onState(ON)
            clock.advance(5_000L)
        }
        assertFalse(epoch.crashLoopSuspected)
        epoch.onState(OFF)
        clock.advance(800L)
        epoch.onState(ON)
        assertTrue(epoch.crashLoopSuspected)
        assertEquals(3, epoch.recentOffEpisodes)
        // A calm minute later it is forgotten.
        clock.advance(61_000L)
        epoch.onState(OFF)
        assertFalse(epoch.crashLoopSuspected)
        assertEquals(1, epoch.recentOffEpisodes)
    }

    @Test
    fun aControllerResetIsSuspectedWhenEveryLinkDropsTogetherAroundAStateChange() {
        val detector = ControllerResetDetector()
        detector.onLinkDropped(10_000L, liveLinksBefore = 3)
        detector.onLinkDropped(10_040L, liveLinksBefore = 2)
        detector.onLinkDropped(10_150L, liveLinksBefore = 1)
        val withState = detector.verdict(lastStateChangeMs = 11_000L)
        assertTrue(withState.suspected)
        assertEquals(3, withState.dropped)
        assertEquals(150L, withState.spreadMs)
        // No adapter state change nearby: a correlation field, not a verdict.
        assertFalse(detector.verdict(lastStateChangeMs = 100_000L).suspected)
        assertFalse(detector.verdict(lastStateChangeMs = -1L).suspected)
    }

    @Test
    fun linksThatDropOneByOneOrASingleLinkAreNotAControllerReset() {
        val spread = ControllerResetDetector()
        spread.onLinkDropped(10_000L, 2)
        spread.onLinkDropped(10_600L, 1) // outside the 200 ms burst: a new burst of one
        assertFalse(spread.verdict(10_000L).suspected)
        val single = ControllerResetDetector()
        single.onLinkDropped(5_000L, 1)
        assertFalse(single.verdict(5_000L).suspected)
        val partial = ControllerResetDetector()
        partial.onLinkDropped(5_000L, 3)
        partial.onLinkDropped(5_050L, 2)
        assertFalse(partial.verdict(5_000L).suspected) // one link survived
        partial.reset()
        assertEquals(0, partial.verdict(5_000L).dropped)
    }
}
