package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 2 of Design AL: the source's batching and Mute, per-child shedding, child health, microphone rule. */
class AudioLiveSourceCoreTest {
    private fun speech(tag: Int): ByteArray = ByteArray(60) { tag.toByte() }.also { it[0] = 0xf8.toByte() }

    private class Emitted(val flags: Int, val ptsUs: Long, val body: ByteArray) {
        fun units(): List<StreamProtocol.Packet> {
            val packet = StreamProtocol.Packet(flags, ptsUs, body)
            return if (flags and StreamProtocol.FLAG_AUDIO_PAIR != 0) StreamProtocol.splitAudioPair(packet) else listOf(packet)
        }
    }

    private fun newBatcher(frames: Int, out: MutableList<Emitted>) =
        AudioLiveBatcher(frames) { flags, pts, body -> out += Emitted(flags, pts, body) }

    // --- Batching ------------------------------------------------------------------------------------------

    @Test
    fun batcherEmitsContiguousTwentyMillisecondRecords() {
        val out = mutableListOf<Emitted>()
        val b = newBatcher(10, out)
        b.anchor(5_000_000)
        repeat(25) { b.add(speech(it + 1)) }
        assertEquals(2, out.size)
        assertEquals(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR, out[0].flags)
        assertEquals(5_000_000L, out[0].ptsUs)
        assertEquals(5_000_000L + 10 * 20_000L, out[1].ptsUs)
        out.flatMap { it.units() }.forEachIndexed { index, unit ->
            assertEquals(5_000_000L + index * 20_000L, unit.presentationTimeUs)
            assertEquals((index + 1).toByte(), unit.payload[1])
        }
        b.flush() // the 5 pending units
        assertEquals(3, out.size)
        assertEquals(5, out[2].units().size)
        b.flush()
        assertEquals(3, out.size)
    }

    @Test
    fun partialBatchesOfOneAndTwoUnitsUseTheSingleAndPairForms() {
        val out = mutableListOf<Emitted>()
        val b = newBatcher(10, out)
        b.anchor(0)
        b.add(speech(1))
        b.flush()
        b.add(speech(2))
        b.add(speech(3))
        b.flush()
        assertEquals(StreamProtocol.FLAG_AUDIO, out[0].flags)
        assertEquals(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR, out[1].flags)
        assertEquals(listOf(0L, 20_000L, 40_000L), out.flatMap { it.units() }.map { it.presentationTimeUs })
    }

    @Test
    fun anchorAfterAPauseRestartsTheTimelineAndFlushesFirst() {
        val out = mutableListOf<Emitted>()
        val b = newBatcher(10, out)
        b.anchor(0)
        repeat(4) { b.add(speech(1)) }
        b.anchor(9_000_000)
        b.add(speech(2))
        b.flush()
        assertEquals(listOf(0L, 9_000_000L), out.map { it.ptsUs })
    }

    // --- Review 16: Mute gates capture atomically ---------------------------------------------------------

    @Test
    fun muteReplacesEverythingRecordedBeforeTheTapAndKeepsTheCadence() {
        val out = mutableListOf<Emitted>()
        val b = newBatcher(10, out)
        b.anchor(0)
        repeat(13) { b.add(speech(7)) } // one full record sent, three pending (recorded before the tap)
        assertEquals(1, out.size)
        b.mute()
        // Frames the encoder still delivers after the tap (captured before it) are silence too.
        repeat(3) { b.add(speech(8)) }
        b.flush()
        repeat(10) { b.add(speech(9)) }
        b.unmute()
        repeat(10) { b.add(speech(10)) }
        val units = out.flatMap { it.units() }
        // Cadence and timestamps never change.
        assertEquals(List(36) { it * 20_000L }, units.map { it.presentationTimeUs })
        // Units 0-9 went out before the tap; 10-12 were pending and became silence; 13-15 and 16-25 silence.
        assertTrue(units.take(10).all { it.payload[1] == 7.toByte() })
        units.subList(10, 26).forEach { assertArrayEquals(AudioLiveBatcher.SILENCE_FRAME, it.payload) }
        // Nothing recorded before the tap (tags 7, 8, 9) appears at or after unit 10; real audio resumes after unmute.
        assertTrue(units.drop(10).none { it.payload.size > 3 && it.payload[1] in 7..9 })
        assertTrue(units.drop(26).all { it.payload[1] == 10.toByte() })
        assertEquals(16L, b.silencedFrames)
    }

    @Test
    fun muteWhileNothingIsPendingStillSilencesLaterFramesAndMuteIsIdempotent() {
        val out = mutableListOf<Emitted>()
        val b = newBatcher(2, out)
        b.anchor(0)
        b.mute()
        b.mute()
        assertTrue(b.muted)
        b.add(speech(5))
        b.add(speech(5))
        assertArrayEquals(AudioLiveBatcher.SILENCE_FRAME, out.single().units().first().payload)
        assertEquals(2L, b.silencedFrames)
    }

    @Test
    fun silenceFramesAndMutedStreamsPassTheReceiverRulesEndToEnd() {
        val identity = KeyPairSigningIdentity(generateEcKeyPair())
        val streamId = UUID.randomUUID()
        val signer = AudioLiveSigner(identity, streamId, AudioLive.PRESET_VOICE)
        val guard = AudioLiveStreamGuard(
            AudioLiveVerifier.fromHeader(
                EndToEndStreamProtocol.decodeHeader(signer.headerBytes, allowAudioOnly = true), streamId, identity.nodeId,
            ),
        ) { 0L }
        assertTrue(AudioLiveOpus.isSingle20msMonoFrame(AudioLiveBatcher.SILENCE_FRAME))
        val head = ByteArray(19).also {
            "OpusHead".forEachIndexed { index, c -> it[index] = c.code.toByte() }
            it[8] = 1; it[9] = 1
        }
        guard.open(signer.sign(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0, head).let {
            StreamProtocol.Packet(it.flags, it.presentationTimeUs, it.payload)
        })
        var now = 0L
        val guard2 = guard // same stream; records follow
        val b = AudioLiveBatcher(10) { flags, pts, body ->
            val signed = signer.sign(flags, pts, body)
            guard2.open(StreamProtocol.Packet(signed.flags, signed.presentationTimeUs, signed.payload))
        }
        b.anchor(0)
        repeat(30) { b.add(speech(1)) }
        b.mute()
        repeat(30) { b.add(speech(1)) }
        b.unmute()
        repeat(30) { b.add(speech(1)) }
        b.flush()
        now += 1
    }

    // --- Shedding -------------------------------------------------------------------------------------------

    private fun q(flags: Int, ageMs: Long, nowNs: Long) =
        AudioLiveQueued(AudioLiveShed.isSourceRecordSheddable(flags), nowNs - ageMs * 1_000_000L)

    private val audio = StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_AUDIO_PAIR
    private val config = StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG
    private val nowNs = 100_000_000_000L

    @Test
    fun noSheddingBelowTheTriggerOrWhenTheControlArmIsOn() {
        val queue = listOf(q(audio, 2_900, nowNs), q(audio, 1_000, nowNs), q(audio, 200, nowNs))
        assertTrue(AudioLiveQueuePolicy.planShed(queue, nowNs, enabled = true).isEmpty())
        val old = listOf(q(audio, 9_000, nowNs), q(audio, 1_000, nowNs))
        assertTrue(AudioLiveQueuePolicy.planShed(old, nowNs, enabled = false).isEmpty())
        assertTrue(AudioLiveQueuePolicy.planShed(emptyList(), nowNs, true).isEmpty())
    }

    @Test
    fun sheddingKeepsTheNewestOneAndAHalfSecondsAndOnlyDropsWholeAudioRecords() {
        val queue = listOf(
            q(config, 9_000, nowNs), // codec configuration: never shed, however old
            q(-1, 8_000, nowNs), // lifecycle marker (flags -1 as the sender passes them)
            q(audio, 3_400, nowNs), // 2: shed
            q(audio, 2_800, nowNs), // 3: shed
            q(audio, 1_600, nowNs), // 4: shed (older than 1.5 s)
            q(audio, 1_400, nowNs), // 5: kept
            q(audio, 200, nowNs), // 6: kept
        )
        assertEquals(listOf(2, 3, 4), AudioLiveQueuePolicy.planShed(queue, nowNs, enabled = true))
    }

    @Test
    fun sheddingNeverMatchesVideoOrDebugOrControlFlags() {
        val flagsNeverShed = listOf(
            0, StreamProtocol.FLAG_KEY_FRAME, StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_DEBUG_INTEGRITY,
            StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_DISCARDABLE, config, -1,
        )
        flagsNeverShed.forEach { flags ->
            val queue = listOf(q(flags, 20_000, nowNs), q(audio, 4_000, nowNs), q(audio, 100, nowNs))
            assertEquals("flags $flags", listOf(1), AudioLiveQueuePolicy.planShed(queue, nowNs, enabled = true))
        }
    }

    // --- Child health -----------------------------------------------------------------------------------

    @Test
    fun aChildWhoseRecordsKeepGettingShedIsWithdrawn() {
        val health = AudioLiveChildHealth()
        var now = 0L
        repeat(40) {
            health.onQueued(now)
            now += 200
        }
        assertNull(health.verdict(1_500, now))
        health.onShed(5, now) // 12.5%
        assertNull(health.verdict(1_500, now))
        health.onShed(5, now) // 25%
        assertEquals(AudioLiveChildHealth.Reason.SHED_RATE, health.verdict(1_500, now))
    }

    @Test
    fun theShedRateNeedsEnoughRecordsAndExpiresAfterTheWindow() {
        val health = AudioLiveChildHealth()
        repeat(4) { health.onQueued(0) }
        health.onShed(4, 0)
        assertNull(health.verdict(0, 0)) // 4 queued: too few to judge
        val later = AudioLiveChildHealth()
        repeat(20) { later.onQueued(0) }
        later.onShed(10, 0)
        assertEquals(AudioLiveChildHealth.Reason.SHED_RATE, later.verdict(0, 5_000))
        assertNull(later.verdict(0, 10_001)) // the window moved on
    }

    @Test
    fun aRecordOlderThanThirtySecondsWithdrawsTheChildWithOrWithoutShedding() {
        val health = AudioLiveChildHealth()
        assertNull(health.verdict(AudioLive.WITHDRAW_AGE_MS, 0))
        assertEquals(AudioLiveChildHealth.Reason.QUEUE_AGE, health.verdict(AudioLive.WITHDRAW_AGE_MS + 1, 0))
    }

    @Test
    fun theShedPlusHealthLoopSettlesForAStalledChild() {
        // A child that never drains: every 200 ms a record is queued; shedding keeps the backlog at 1.5-3 s
        // (the video thresholds would never fire) and the shed-rate rule releases it within seconds.
        val health = AudioLiveChildHealth()
        val queue = ArrayList<AudioLiveQueued>()
        var releasedAtMs = -1L
        var nowMs = 0L
        while (nowMs < 60_000 && releasedAtMs < 0) {
            val nowNs = nowMs * 1_000_000L
            queue += AudioLiveQueued(true, nowNs)
            health.onQueued(nowMs)
            val doomed = AudioLiveQueuePolicy.planShed(queue, nowNs, enabled = true)
            if (doomed.isNotEmpty()) {
                doomed.reversed().forEach(queue::removeAt)
                health.onShed(doomed.size, nowMs)
            }
            val oldestAge = queue.firstOrNull()?.let { (nowNs - it.queuedAtNs) / 1_000_000L } ?: 0L
            if (health.verdict(oldestAge, nowMs) != null) releasedAtMs = nowMs
            nowMs += 200
        }
        assertTrue("released at $releasedAtMs", releasedAtMs in 1..30_000)
    }

    // --- Microphone service rule (review AL-3) ------------------------------------------------------------

    @Test
    fun aSilentBackgroundBroadcastIsNeverKept() {
        assertTrue(AudioLiveMicrophonePolicy.mustStop(holdsMicrophoneType = false))
        assertFalse(AudioLiveMicrophonePolicy.mustStop(holdsMicrophoneType = true))
        assertTrue(AudioLiveMicrophonePolicy.ERROR_TEXT.isNotBlank())
    }

    // --- Level meter -------------------------------------------------------------------------------------

    @Test
    fun levelMeterFollowsRmsAndIsBounded() {
        assertEquals(0f, AudioLiveLevel.rms(ByteArray(1920)), 0f)
        val loud = ByteArray(1920).also { pcm ->
            for (i in 0 until 960) {
                pcm[2 * i] = 0xff.toByte()
                pcm[2 * i + 1] = 0x7f
            }
        }
        assertEquals(1f, AudioLiveLevel.rms(loud), 0f)
        val quiet = ByteArray(1920).also { pcm ->
            for (i in 0 until 960) {
                pcm[2 * i] = 0xe8.toByte()
                pcm[2 * i + 1] = 0x03 // 1000
            }
        }
        assertEquals(0.25f, AudioLiveLevel.rms(quiet), 0.01f)
        assertEquals(0f, AudioLiveLevel.rms(ByteArray(0)), 0f)
    }

    // --- Switch defaults -------------------------------------------------------------------------------

    @Test
    fun theFeatureIsOnAndEveryExperimentalArmIsOffByDefault() {
        assertTrue(AudioLiveFeatureDefaults.ENABLED)
        assertFalse(AudioLiveTunables.fec)
        assertFalse(AudioLiveTunables.dtx)
        assertFalse(AudioLiveTunables.adaptive)
        assertFalse(AudioLiveTunables.balancedLinkPriority)
        assertEquals(1, AudioLiveTunables.relayChildren)
        assertEquals(AudioLive.DEFAULT_BATCH_FRAMES, AudioLiveTunables.batchFrames)
        assertEquals(AudioLive.DEFAULT_MAX_CHILDREN, AudioLiveTunables.maxChildren)
        assertTrue(AudioLiveTunables.ageBound)
        assertTrue(AudioLiveTunables.nativeOpus)
        assertEquals(AudioLive.PRESET_VOICE, AudioLiveTunables.effectivePreset(AudioLive.PRESET_VOICE))
    }
}
