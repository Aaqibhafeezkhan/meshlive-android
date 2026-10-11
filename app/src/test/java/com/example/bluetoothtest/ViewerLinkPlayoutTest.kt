package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * External review 2026-10-11: "reconnect must not replay old audio or accumulate growing delay". Every
 * viewer link gets a fresh playout clock at the base delay (newViewerLinkPlayoutClock) and, in the
 * receiver, a fresh audio decoder, AudioTrack and video decoder. These tests keep that property:
 * the clock rule runs on the real MediaPlayoutClock, and the structural test reads the receiver
 * source because the decoders need Android (MediaCodec, AudioTrack) and cannot run on the JVM.
 */
class ViewerLinkPlayoutTest {
    private val second = 1_000_000_000L

    /** Late media: the delay grows to cover it, as on a Bluetooth burst. */
    private fun growWithLateMedia(clock: MediaPlayoutClock, lateBySeconds: Long = 3) {
        clock.timing(0L, 0L)
        clock.timing(100_000L, lateBySeconds * second)
    }

    @Test
    fun everyLinkStartsAtTheProfileBaseDelay() {
        for (profile in listOf(
            PlaybackDelayProfile.LOW,
            PlaybackDelayProfile.BALANCED,
            PlaybackDelayProfile.STABLE,
            PlaybackDelayProfile.AUTO,
        )) {
            val clock = newViewerLinkPlayoutClock(profile, profile.delayMs, relaying = false, relayJoinReserveMs = 1_500L)
            assertEquals(profile.name, profile.delayMs, clock.currentDelayMs)
            assertEquals(0L, clock.addedDelayMs)
        }
    }

    @Test
    fun aLinkThatGrewItsDelayDoesNotHandItToTheNextLink() {
        val profile = PlaybackDelayProfile.AUTO
        val first = newViewerLinkPlayoutClock(profile, profile.delayMs, false, 1_500L)
        growWithLateMedia(first)
        assertEquals("the burst grew the first link to its maximum", profile.adaptiveMaxDelayMs, first.currentDelayMs)

        val next = newViewerLinkPlayoutClock(profile, profile.delayMs, false, 1_500L)
        assertEquals(profile.delayMs, next.currentDelayMs)
    }

    @Test
    fun repeatedLossAndRejoinCyclesNeitherAccumulateNorCarryDelay() {
        val profile = PlaybackDelayProfile.AUTO
        val grownPerLink = mutableSetOf<Long>()
        repeat(12) { cycle ->
            val clock = newViewerLinkPlayoutClock(profile, profile.delayMs, false, 1_500L)
            assertEquals("cycle $cycle starts at the base delay", profile.delayMs, clock.currentDelayMs)
            growWithLateMedia(clock)
            grownPerLink += clock.currentDelayMs
        }
        // Each link grows by the same bounded amount: the delay does not creep with the cycle count.
        assertEquals(setOf(profile.adaptiveMaxDelayMs), grownPerLink)
    }

    @Test
    fun aRelayingViewerReservesRoomWithinTheAdaptiveRangeAndNeverBeyondIt() {
        val profile = PlaybackDelayProfile.AUTO
        val plain = newViewerLinkPlayoutClock(profile, profile.delayMs, relaying = false, relayJoinReserveMs = 1_500L)
        val relay = newViewerLinkPlayoutClock(profile, profile.delayMs, relaying = true, relayJoinReserveMs = 1_500L)
        assertEquals(profile.delayMs, plain.currentDelayMs)
        assertTrue(relay.currentDelayMs > plain.currentDelayMs)
        assertTrue(relay.currentDelayMs < profile.adaptiveMaxDelayMs)
        // The next link of a relaying viewer starts from the same reserve, not a larger one.
        assertEquals(
            relay.currentDelayMs,
            newViewerLinkPlayoutClock(profile, profile.delayMs, relaying = true, relayJoinReserveMs = 1_500L).currentDelayMs,
        )
    }

    @Test
    fun anExplicitDelayOverrideIsAFixedClock() {
        val clock = newViewerLinkPlayoutClock(PlaybackDelayProfile.AUTO, 400L, relaying = false, relayJoinReserveMs = 1_500L)
        assertEquals(400L, clock.currentDelayMs)
        growWithLateMedia(clock)
        assertEquals(400L, clock.currentDelayMs)
    }

    @Test
    fun theReceiverBuildsEveryLinkPipelineFromScratch() {
        val source = receiverSource()
        // One clock per link, created inside the link's receive function, from the shared rule.
        assertEquals(1, Regex("newViewerLinkPlayoutClock\\(").findAll(source).count())
        assertFalse(source.contains("playbackDelayProfile.newPlayoutClock("))
        assertFalse(
            "a playout clock held across links would carry the old delay and anchor",
            Regex("(private|internal|@Volatile)\\s+(var|val)\\s+playoutClock").containsMatchIn(source),
        )
        // The decoders are closed when a link ends, on every path, and forgotten.
        val closeBody = source.substringAfter("private fun closeDecoders() {").substringBefore("\n    }")
        assertTrue(closeBody.contains("decoder?.close()"))
        assertTrue(closeBody.contains("decoder = null"))
        assertTrue(closeBody.contains("audioDecoder?.close()"))
        assertTrue(closeBody.contains("audioDecoder = null"))
        // Both receive loops (direct socket and GATT) close them after each connection.
        assertTrue(Regex("closeDecoders\\(\\)").findAll(source).count() >= 3)
        // The audio decoder is created lazily from the link's own header and config, never reused.
        assertEquals(1, Regex("OpusAudioDecoder\\(").findAll(source).count())
    }

    private fun receiverSource(): String {
        val file = listOf(
            "src/main/java/com/example/bluetoothtest/AndroidVideoReceiver.kt",
            "app/src/main/java/com/example/bluetoothtest/AndroidVideoReceiver.kt",
        ).map(::File).first { it.isFile }
        return file.readText().replace("\r\n", "\n")
    }
}
