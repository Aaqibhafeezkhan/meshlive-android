package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio route, sender-gap playout, decoded-PTS mapping, mute flush and voice playout policy. */
class CallAudioQualityTest {
    @Test
    fun aPluggedWiredHeadsetReplacesTheEarpieceAndAnUnchosenSpeaker() {
        // No headset: unchanged speaker/earpiece behaviour.
        assertEquals(CallOutput.SPEAKER, callOutputFor(true, false, wiredHeadsetPresent = false, earpiecePresent = true))
        assertEquals(CallOutput.EARPIECE, callOutputFor(false, false, wiredHeadsetPresent = false, earpiecePresent = true))
        // A tablet without an earpiece stays on the speaker.
        assertEquals(CallOutput.SPEAKER, callOutputFor(false, false, wiredHeadsetPresent = false, earpiecePresent = false))
        // Headset plugged: the quiet route is the headset, and a video call's default speaker yields to it.
        assertEquals(CallOutput.WIRED_HEADSET, callOutputFor(false, false, wiredHeadsetPresent = true, earpiecePresent = true))
        assertEquals(CallOutput.WIRED_HEADSET, callOutputFor(true, false, wiredHeadsetPresent = true, earpiecePresent = true))
        // Only the user's explicit speaker choice overrides a plugged headset.
        assertEquals(CallOutput.SPEAKER, callOutputFor(true, true, wiredHeadsetPresent = true, earpiecePresent = true))
        assertEquals(CallOutput.WIRED_HEADSET, callOutputFor(false, true, wiredHeadsetPresent = true, earpiecePresent = false))
    }

    @Test
    fun aTalkSpurtAfterAMuteStartsOnItsTargetBehindSilence() {
        // PTT released for 3 s; the track ran dry, so the next chunk would play 250 ms early.
        assertEquals(250L, senderGapSilenceMs(lagMs = -250, baselineLagMs = 0, gapMs = 3_000, maxMs = 600))
        // The device's steady lag is the reference, not zero.
        assertEquals(230L, senderGapSilenceMs(lagMs = -250, baselineLagMs = -20, gapMs = 3_000, maxMs = 600))
        // Never more silence than the sender's own gap (a 100 ms hole with a deep buffer).
        assertEquals(100L, senderGapSilenceMs(lagMs = -400, baselineLagMs = 0, gapMs = 100, maxMs = 600))
        // Never more than the profile's maximum delay.
        assertEquals(600L, senderGapSilenceMs(lagMs = -5_000, baselineLagMs = 0, gapMs = 6_000, maxMs = 600))
    }

    @Test
    fun continuousOrLateAudioIsNeverPadded() {
        // PTS steps below three frames are framing/rounding, not a sender gap.
        assertEquals(0L, senderGapSilenceMs(lagMs = -250, baselineLagMs = 0, gapMs = 40, maxMs = 600))
        // Within the speed controller's dead band.
        assertEquals(0L, senderGapSilenceMs(lagMs = -30, baselineLagMs = 0, gapMs = 3_000, maxMs = 600))
        // Late audio: the clock already grew the delay at arrival; silence would only add more.
        assertEquals(0L, senderGapSilenceMs(lagMs = 120, baselineLagMs = 0, gapMs = 3_000, maxMs = 600))
        // Before the baseline is learned the reference is zero lag.
        assertEquals(200L, senderGapSilenceMs(lagMs = -200, baselineLagMs = null, gapMs = 3_000, maxMs = 600))
    }

    @Test
    fun aDecoderThatRestampsOutputStillYieldsTheSenderTimeline() {
        // voice-ptt-s24a15s-51cc8028 case 0003: the S24 decoder's output PTS stayed contiguous across a
        // 6 s mute (anchor + decoded samples, 6.5 ms behind because of the pre-skip).
        val queue = SourcePtsQueue()
        listOf(60_000L, 80_000L, 100_000L).forEach(queue::onInput)
        assertEquals(60_000L, queue.onOutput(60_000L))
        assertEquals(80_000L, queue.onOutput(73_500L))
        assertEquals(100_000L, queue.onOutput(93_500L))
        // Mute: the next packet on the wire is 6 s later, the decoder says 113.5 ms.
        queue.onInput(6_120_000L)
        assertEquals(6_120_000L, queue.onOutput(113_500L))
    }

    @Test
    fun anEchoingDecoderThatSkipsPreSkipOutputsStaysAligned() {
        val queue = SourcePtsQueue()
        listOf(0L, 20_000L, 40_000L, 60_000L).forEach(queue::onInput)
        // The first two inputs only filled an 80 ms pre-roll and produced no output.
        assertEquals(40_000L, queue.onOutput(40_000L))
        assertEquals(60_000L, queue.onOutput(60_000L))
        // Nothing queued: the decoder's own timestamp is the fallback.
        assertEquals(80_000L, queue.onOutput(80_000L))
    }

    @Test
    fun theSourceTimestampQueueIsBounded() {
        val queue = SourcePtsQueue(capacity = 3)
        (0 until 10).forEach { queue.onInput(it * 20_000L) }
        assertEquals(140_000L, queue.onOutput(1L))
    }

    @Test
    fun aPartialBatchFlushedAtMuteKeepsEveryAccessUnit() {
        fun unit(i: Int) = StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, i * 20_000L, byteArrayOf(i.toByte(), 7))
        for (count in 1..5) {
            val units = (0 until count).map(::unit)
            val record = combineCallAudio(units)
            val restored = if (count == 1) listOf(record) else StreamProtocol.splitAudioPair(record)
            assertEquals(count, restored.size)
            restored.zip(units).forEach { (got, want) ->
                assertEquals(want.presentationTimeUs, got.presentationTimeUs)
                assertEquals(StreamProtocol.FLAG_AUDIO, got.flags)
                assertArrayEquals(want.payload, got.payload)
            }
        }
    }

    @Test
    fun lowDelayVoicePlayoutIsExperimentalAndSkipsConstrainedLinks() {
        val call = PlaybackDelayProfile.CALL
        // Off (default): unchanged.
        assertEquals(call, privateCallReceiverPlaybackProfile(call, CallType.VOICE, lowDelayVoice = false))
        assertEquals(PlaybackDelayProfile.CALL_VOICE, privateCallReceiverPlaybackProfile(call, CallType.VOICE, lowDelayVoice = true))
        // Video calls keep lip-synced video-call buffering.
        assertEquals(call, privateCallReceiverPlaybackProfile(call, CallType.VIDEO, lowDelayVoice = true))
        // Constrained links deliver audio in batches of up to 400 ms: keep their buffer.
        assertEquals(
            PlaybackDelayProfile.CALL_STABLE,
            privateCallReceiverPlaybackProfile(PlaybackDelayProfile.CALL_STABLE, CallType.VOICE, lowDelayVoice = true),
        )
        val voice = PlaybackDelayProfile.CALL_VOICE
        assertEquals(80L, voice.delayMs)
        assertTrue(voice.adaptive)
        assertTrue(voice.adaptiveMaxDelayMs <= PlaybackDelayProfile.CALL.adaptiveMaxDelayMs)
        // A call profile: calm shrinking after 10 s, not the stream's 60 s.
        assertEquals("Call", voice.stabilityLabel)
    }
}
