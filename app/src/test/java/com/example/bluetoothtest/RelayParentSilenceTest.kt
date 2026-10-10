package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayParentSilenceTest {
    private val second = 1_000_000_000L

    @Test
    fun aRelayedLinkThatDeliveredNothingForFiveSecondsIsDead() {
        // mesh4-659efc38 0001: the M31 heard nothing after the ASUS re-anchored and waited 30 s.
        assertFalse(relayParentSilent(nowNs = 4 * second, lastReadNs = 0L, startedNs = 0L, paused = false))
        assertTrue(relayParentSilent(nowNs = 5 * second, lastReadNs = 0L, startedNs = 0L, paused = false))
        assertTrue(relayParentSilent(nowNs = 20 * second, lastReadNs = 14 * second, startedNs = 0L, paused = false))
    }

    @Test
    fun aSlowLinkThatStillDeliversOrAPausedStreamIsNotDead() {
        assertFalse(relayParentSilent(nowNs = 20 * second, lastReadNs = 19 * second, startedNs = 0L, paused = false))
        assertFalse(relayParentSilent(nowNs = 60 * second, lastReadNs = 0L, startedNs = 0L, paused = true))
        // The link start counts as activity: a fresh link gets its full grace.
        assertFalse(relayParentSilent(nowNs = 13 * second, lastReadNs = 2 * second, startedNs = 10 * second, paused = false))
    }

    @Test
    fun aGattSourceFeedingTwoGattChildrenSharesItsAirtime() {
        val gattFanOut = { t: BluetoothTransportOption -> if (t == BluetoothTransportOption.BLE_GATT) 2 else 0 }
        assertTrue(adaptivePhySharedAirtime("gatt", gattFanOut))
        assertTrue(adaptivePhySharedAirtime("coc", gattFanOut))
        val single = { t: BluetoothTransportOption -> if (t == BluetoothTransportOption.BLE_GATT) 1 else 0 }
        assertFalse(adaptivePhySharedAirtime("gatt", single))
    }

    @Test
    fun aRelayedLinkThatTricklesBelowAnEighthOfTheStreamForAWholeWindowIsStarved() {
        // mesh4-8211dc8f 0001: M31 -> A15s carried ~12 kbps of a 300 kbps stream, read gaps under 0.5 s.
        val trickle = RelayParentTrickle(streamBitrateKbps = 300, startedNs = 0L)
        val bytesAt12Kbps = { ns: Long -> ns * 12_000L / 8 / second }
        assertNull(trickle.onTick(4 * second, bytesAt12Kbps(4 * second), paused = false))
        assertEquals(12, trickle.onTick(5 * second, bytesAt12Kbps(5 * second), paused = false))
    }

    @Test
    fun theOnePduPairBandAHealthyLinkAPauseOrAnUnknownBitrateAreNotStarved() {
        // ~120 kbps is the 2M one-PDU-per-event pair (adaptive PHY's case), not a dead link.
        val slowPair = RelayParentTrickle(300, 0L)
        assertNull(slowPair.onTick(5 * second, 5L * 120_000 / 8, paused = false))
        assertNull(RelayParentTrickle(300, 0L).onTick(5 * second, 5L * 300_000 / 8, paused = false))
        // A pause anywhere in the window disqualifies it; the next whole window is judged again.
        val paused = RelayParentTrickle(300, 0L)
        assertNull(paused.onTick(2 * second, 0L, paused = true))
        assertNull(paused.onTick(5 * second, 0L, paused = false))
        assertEquals(0, paused.onTick(10 * second, 0L, paused = false))
        assertNull(RelayParentTrickle(0, 0L).onTick(5 * second, 0L, paused = false))
    }

    @Test
    fun theWrappedSocketStreamCountsEveryByteAndStampsTheRead() {
        // mesh4-f40e9f42: the wrapper called InputStream.mark(readlimit) instead of its own counter, so
        // lastReadAtNs stayed 0 and every relayed child closed a healthy parent 5 s after start.
        val stats = SocketReadGapStats()
        val input = stats.wrap(java.io.ByteArrayInputStream(ByteArray(100) { it.toByte() }))
        assertEquals(0L, stats.lastReadAtNs)
        val before = System.nanoTime()
        assertEquals(0, input.read())
        assertEquals(40, input.read(ByteArray(40), 0, 40))
        val buffered = java.io.BufferedInputStream(input, 16)
        assertEquals(59, buffered.read(ByteArray(80), 0, 80))
        assertEquals(100L, stats.bytesRead)
        assertTrue(stats.lastReadAtNs >= before)
        assertFalse(relayParentSilent(stats.lastReadAtNs + 4 * second, stats.lastReadAtNs, before, paused = false))
    }

    @Test
    fun connectionSetupBeforeTheStreamHeaderIsNeverJudgedAsATrickle() {
        // mesh4ab-52681dbf-loop14b-1 0011: authenticated at t=4.5 s, closed at t=7.2 s with 8 kbps
        // because the window began at socket start and held the whole setup.
        val trickle = RelayParentTrickle(streamBitrateKbps = 300, startedNs = 0L)
        assertNull(trickle.onTick(5 * second, 2_000, paused = false, armed = false))
        assertNull(trickle.onTick(7 * second, 5_000, paused = false, armed = false))
        // Armed at the header (t=7 s): the first judgement comes a whole window later, on media only.
        assertNull(trickle.onTick(11 * second, 5_000 + 4L * 300_000 / 8, paused = false, armed = true))
        assertNull(trickle.onTick(12 * second, 5_000 + 5L * 300_000 / 8, paused = false, armed = true))
        // A genuinely starved armed link still closes.
        assertEquals(1, trickle.onTick(17 * second, 5_000 + 5L * 300_000 / 8 + 5L * 1_000 / 8, paused = false, armed = true))
    }
}
