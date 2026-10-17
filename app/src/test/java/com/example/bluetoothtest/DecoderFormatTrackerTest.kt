package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderFormatTrackerTest {
    private val av1 = StreamProtocol.CODEC_AV1
    private val vp9 = StreamProtocol.CODEC_VP9

    private fun packet(flags: Int, pts: Long, payload: ByteArray) = StreamProtocol.Packet(flags, pts, payload)

    private fun key(payload: ByteArray, pts: Long, extra: Int = 0) =
        packet(StreamProtocol.FLAG_KEY_FRAME or extra, pts, payload)

    private fun config(payload: ByteArray) = packet(StreamProtocol.FLAG_CODEC_CONFIG, 0, payload)

    /**
     * Stands in for [VideoStreamDecoder] without a codec: the same [DecoderFormatTracker.plan] and
     * [EncodedFrameQueue] calls its offer() makes, with the decoders it would have created and the
     * frames each of them would have been fed recorded.
     */
    private class FakeDecoder(codecId: Int, csd: List<ByteArray>, width: Int, height: Int, first: StreamProtocol.Packet? = null) {
        class Format(val width: Int, val height: Int, val codecSpecificData: List<ByteArray>)

        private val size = initialCodedSize(
            StreamProtocol.Header(width, height, 15, codecId, 0, 0, 0, 0), first, csd,
        )
        val tracker = DecoderFormatTracker(codecId, csd, size.first, size.second)
        val queue = EncodedFrameQueue(32, requireInitialKeyFrame = true)
        val decoders = mutableListOf(Format(size.first, size.second, csd))
        val fed = mutableListOf<Pair<Int, Long>>()
        val queuedConfig = mutableListOf<Pair<Int, ByteArray>>()
        val sizeNotices = mutableListOf<Pair<Int, Int>>()

        fun offer(packet: StreamProtocol.Packet) {
            val plan = tracker.plan(packet)
            if (!plan.queue) return
            val discontinuity = packet.flags and StreamProtocol.FLAG_DISCONTINUITY != 0 &&
                packet.flags and StreamProtocol.FLAG_KEY_FRAME != 0
            if (discontinuity || plan.resetQueue) queue.resetForDiscontinuity()
            queue.offer(packet)
            plan.rebuild?.let { rebuild ->
                // The worker releases the running decoder and creates this one before the queue is drained.
                decoders += Format(rebuild.width, rebuild.height, rebuild.codecSpecificData)
                if (rebuild.sizeChanged) sizeNotices += rebuild.width to rebuild.height
            }
        }

        fun drain() {
            while (true) {
                val (packet, _) = queue.poll() ?: return
                if (packet.flags and StreamProtocol.FLAG_CODEC_CONFIG != 0) {
                    queuedConfig += decoders.lastIndex to packet.payload
                } else {
                    fed += decoders.lastIndex to packet.presentationTimeUs
                }
            }
        }
    }

    @Test
    fun aStreamWithoutASizeChangeNeverRebuilds() {
        val csd = SizeChangeFixtures.av1Config(640, 360)
        val decoder = FakeDecoder(av1, listOf(csd), 640, 360)
        decoder.offer(key(SizeChangeFixtures.av1KeyFrame(640, 360), 0))
        decoder.offer(packet(0, 66, SizeChangeFixtures.av1InterFrame()))
        // A repeated identical configuration (a flip, a late-joiner replay) is queued exactly as before.
        decoder.offer(config(csd.copyOf()))
        decoder.offer(key(SizeChangeFixtures.av1KeyFrame(640, 360), 133, StreamProtocol.FLAG_DISCONTINUITY))
        decoder.offer(packet(0, 200, SizeChangeFixtures.av1InterFrame()))
        decoder.drain()

        assertEquals(1, decoder.decoders.size)
        assertEquals(listOf(0 to 133L, 0 to 200L), decoder.fed)
        assertTrue(decoder.sizeNotices.isEmpty())
    }

    @Test
    fun anAv1RungChangeWithNewConfigurationRebuildsAtTheKeyframeWithTheNewCsd() {
        val startCsd = SizeChangeFixtures.av1Config(1280, 720)
        val rungCsd = SizeChangeFixtures.av1Config(640, 360)
        val decoder = FakeDecoder(av1, listOf(startCsd), 1280, 720)
        decoder.offer(key(SizeChangeFixtures.av1KeyFrame(1280, 720), 0))
        decoder.offer(packet(0, 66, SizeChangeFixtures.av1InterFrame()))
        // restartStreamCamera: the new encoder's configuration, then its marked keyframe, then deltas.
        decoder.offer(config(rungCsd))
        decoder.offer(packet(0, 80, SizeChangeFixtures.av1InterFrame())) // old encoder's last frame still queued
        decoder.offer(key(SizeChangeFixtures.av1KeyFrame(640, 360), 133, StreamProtocol.FLAG_DISCONTINUITY))
        decoder.offer(packet(0, 200, SizeChangeFixtures.av1InterFrame()))
        decoder.drain()

        assertEquals(2, decoder.decoders.size)
        val rebuilt = decoder.decoders[1]
        assertEquals(640, rebuilt.width)
        assertEquals(360, rebuilt.height)
        assertEquals(1, rebuilt.codecSpecificData.size)
        assertArrayEquals(rungCsd, rebuilt.codecSpecificData.single())
        // Nothing of the old picture reaches the new decoder, and the new configuration is not queued on its own.
        assertEquals(listOf(1 to 133L, 1 to 200L), decoder.fed)
        assertTrue(decoder.queuedConfig.isEmpty())
        assertEquals(listOf(640 to 360), decoder.sizeNotices)
    }

    @Test
    fun recoveryAfterARungChangeRequeuesTheCurrentCsdNotTheStreamStartOne() {
        val startCsd = SizeChangeFixtures.av1Config(1280, 720)
        val rungCsd = SizeChangeFixtures.av1Config(640, 360)
        val decoder = FakeDecoder(av1, listOf(startCsd), 1280, 720)
        decoder.offer(key(SizeChangeFixtures.av1KeyFrame(1280, 720), 0))
        decoder.offer(config(rungCsd))
        decoder.offer(key(SizeChangeFixtures.av1KeyFrame(640, 360), 133, StreamProtocol.FLAG_DISCONTINUITY))

        // restartAtRecoveryKeyFrame re-queues exactly this list.
        val recovery = decoder.tracker.currentCodecSpecificData()
        assertEquals(1, recovery.size)
        assertArrayEquals(rungCsd, recovery.single())
        assertFalse(recovery.any { it.contentEquals(startCsd) })

        // A recovery keyframe at the same new size needs no rebuild; the CSD stays the current one.
        assertNull(decoder.tracker.onKeyFrame(SizeChangeFixtures.av1KeyFrame(640, 360)))
        assertArrayEquals(rungCsd, decoder.tracker.currentCodecSpecificData().single())
    }

    @Test
    fun aVp9RungChangeWithoutConfigurationRebuildsFromTheKeyframeSizeAndDropsTheOldCsd() {
        val startCsd = byteArrayOf(9, 9, 9)
        val decoder = FakeDecoder(vp9, listOf(startCsd), 640, 360)
        decoder.offer(key(SizeChangeFixtures.vp9KeyFrame(640, 360), 0))
        decoder.offer(packet(0, 66, SizeChangeFixtures.vp9InterFrame()))
        decoder.offer(key(SizeChangeFixtures.vp9KeyFrame(426, 240), 133))
        decoder.drain()

        assertEquals(2, decoder.decoders.size)
        assertEquals(426, decoder.decoders[1].width)
        assertEquals(240, decoder.decoders[1].height)
        assertTrue(decoder.decoders[1].codecSpecificData.isEmpty())
        assertEquals(listOf(1 to 133L), decoder.fed)
        assertTrue(decoder.tracker.currentCodecSpecificData().isEmpty())
    }

    @Test
    fun framesQueuedBeforeAnUnmarkedSizeChangeNeverReachTheNewDecoder() {
        // An older sender sets no discontinuity: the size alone decides, and the old-size delta frames still
        // in the queue are dropped with the old decoder.
        val decoder = FakeDecoder(vp9, emptyList(), 640, 360)
        decoder.offer(key(SizeChangeFixtures.vp9KeyFrame(640, 360), 0))
        decoder.offer(packet(0, 66, SizeChangeFixtures.vp9InterFrame()))
        decoder.offer(packet(0, 100, SizeChangeFixtures.vp9InterFrame()))
        decoder.offer(key(SizeChangeFixtures.vp9KeyFrame(960, 540), 133))
        decoder.offer(packet(0, 200, SizeChangeFixtures.vp9InterFrame()))
        decoder.drain()

        assertEquals(listOf(1 to 133L, 1 to 200L), decoder.fed)
    }

    @Test
    fun aRungChangeUpIsHandledLikeADownChange() {
        val decoder = FakeDecoder(vp9, emptyList(), 426, 240)
        decoder.offer(key(SizeChangeFixtures.vp9KeyFrame(426, 240), 0))
        decoder.offer(key(SizeChangeFixtures.vp9KeyFrame(640, 360), 100))
        decoder.offer(key(SizeChangeFixtures.vp9KeyFrame(960, 540), 200))
        decoder.offer(key(SizeChangeFixtures.vp9KeyFrame(960, 540), 300))
        assertEquals(listOf(426 to 240, 640 to 360, 960 to 540), decoder.decoders.map { it.width to it.height })
        assertEquals(listOf(640 to 360, 960 to 540), decoder.sizeNotices)
    }

    @Test
    fun aChangedConfigurationAtTheSameSizeStillRebuilds() {
        val first = byteArrayOf(1, 1)
        val second = byteArrayOf(2, 2)
        val tracker = DecoderFormatTracker(vp9, listOf(first), 640, 360)
        assertFalse(tracker.onCodecConfig(second))
        val rebuild = tracker.onKeyFrame(SizeChangeFixtures.vp9KeyFrame(640, 360))
        assertNotNull(rebuild)
        assertFalse(rebuild!!.sizeChanged)
        assertTrue(rebuild.configChanged)
        assertEquals("csd", rebuild.reason)
        assertArrayEquals(second, rebuild.codecSpecificData.single())
        assertArrayEquals(second, tracker.currentCodecSpecificData().single())
    }

    @Test
    fun aKeyframeWhoseSizeCannotBeReadChangesNothing() {
        val tracker = DecoderFormatTracker(vp9, emptyList(), 640, 360)
        assertNull(tracker.onKeyFrame(byteArrayOf(1, 2, 3, 4)))
        val av1Tracker = DecoderFormatTracker(av1, emptyList(), 640, 360)
        assertNull(av1Tracker.onKeyFrame(SizeChangeFixtures.av1KeyFrame(640, 360, sequenceHeaderInBand = false)))
        assertEquals(640 to 360, tracker.codedSize())
    }

    @Test
    fun anEncoderThatRepeatsPartOfTheCurrentConfigurationIsNotAChange() {
        val a = byteArrayOf(1)
        val b = byteArrayOf(2)
        val tracker = DecoderFormatTracker(vp9, listOf(a, b), 640, 360)
        assertTrue(tracker.onCodecConfig(a))
        assertNull(tracker.onKeyFrame(SizeChangeFixtures.vp9KeyFrame(640, 360)))
        assertEquals(2, tracker.currentCodecSpecificData().size)
    }

    @Test
    fun aVp9EncoderWithTwoConfigurationPacketsKeepsBothForItsNewDecoder() {
        val tracker = DecoderFormatTracker(vp9, listOf(byteArrayOf(1)), 640, 360)
        assertFalse(tracker.onCodecConfig(byteArrayOf(5)))
        assertFalse(tracker.onCodecConfig(byteArrayOf(6)))
        assertFalse(tracker.onCodecConfig(byteArrayOf(6))) // duplicates collapse
        val rebuild = tracker.onKeyFrame(SizeChangeFixtures.vp9KeyFrame(426, 240))!!
        assertEquals(listOf(5, 6), rebuild.codecSpecificData.map { it.single().toInt() })
        assertEquals("size+csd", rebuild.reason)
    }

    @Test
    fun aLateJoinerWithAStaleHeaderIsConfiguredAtTheSizeItsFirstKeyframeCarries() {
        val header = StreamProtocol.Header(1280, 720, 15, av1, 0, 0, 0, 0)
        val first = key(SizeChangeFixtures.av1KeyFrame(640, 360), 0)
        assertEquals(640 to 360, initialCodedSize(header, first, emptyList()))
        // A delta frame, a config packet, audio and no packet at all leave the header's size.
        assertEquals(1280 to 720, initialCodedSize(header, packet(0, 0, SizeChangeFixtures.av1InterFrame()), emptyList()))
        assertEquals(1280 to 720, initialCodedSize(header, config(byteArrayOf(1)), emptyList()))
        assertEquals(1280 to 720, initialCodedSize(header, null, emptyList()))
        assertEquals(1280 to 720, initialCodedSize(header, key(byteArrayOf(1, 2), 0), emptyList()))

        val decoder = FakeDecoder(av1, emptyList(), 1280, 720, first)
        decoder.offer(first)
        assertEquals(1, decoder.decoders.size) // no immediate rebuild of the decoder just created
        assertEquals(640 to 360, decoder.tracker.codedSize())
    }

    @Test
    fun restartMarkerFlagsOnlyTheFirstVideoKeyframeAfterASizeChange() {
        val marker = RestartDiscontinuityMarker(sizeChanged = true)
        assertEquals(StreamProtocol.FLAG_CODEC_CONFIG, marker.flagsFor(StreamProtocol.FLAG_CODEC_CONFIG))
        assertEquals(StreamProtocol.FLAG_DISPLAY_ROTATION, marker.flagsFor(StreamProtocol.FLAG_DISPLAY_ROTATION))
        assertEquals(StreamProtocol.FLAG_AUDIO, marker.flagsFor(StreamProtocol.FLAG_AUDIO))
        assertEquals(0, marker.flagsFor(0)) // a delta frame cannot come first, but is never marked
        assertEquals(
            StreamProtocol.FLAG_KEY_FRAME or StreamProtocol.FLAG_DISCONTINUITY,
            marker.flagsFor(StreamProtocol.FLAG_KEY_FRAME),
        )
        assertEquals(StreamProtocol.FLAG_KEY_FRAME, marker.flagsFor(StreamProtocol.FLAG_KEY_FRAME))
    }

    @Test
    fun restartMarkerLeavesARestartThatKeepsTheSizeAlone() {
        val sent = mutableListOf<Int>()
        val marker = RestartDiscontinuityMarker(sizeChanged = false)
        val send = marker.wrap { flags, _, _ -> sent += flags }
        send(StreamProtocol.FLAG_KEY_FRAME, 0, byteArrayOf())
        assertEquals(listOf(StreamProtocol.FLAG_KEY_FRAME), sent)

        val marked = mutableListOf<Int>()
        val markedSend = RestartDiscontinuityMarker(sizeChanged = true).wrap { flags, _, _ -> marked += flags }
        markedSend(StreamProtocol.FLAG_CODEC_CONFIG, 0, byteArrayOf())
        markedSend(StreamProtocol.FLAG_KEY_FRAME, 1, byteArrayOf())
        markedSend(0, 2, byteArrayOf())
        assertEquals(
            listOf(
                StreamProtocol.FLAG_CODEC_CONFIG,
                StreamProtocol.FLAG_KEY_FRAME or StreamProtocol.FLAG_DISCONTINUITY,
                0,
            ),
            marked,
        )
    }

    @Test
    fun configBatchStartsOverOnlyWhenConfigurationFollowsVideo() {
        val batch = VideoConfigBatch()
        assertFalse(batch.onConfig()) // csd before the first frame
        assertFalse(batch.onConfig()) // a second packet of the same encoder
        assertFalse(batch.onMedia())
        assertTrue(batch.onConfig()) // a restarted encoder: the cached packets are stale
        assertFalse(batch.onConfig())
    }

    @Test
    fun aRestartedEncoderThatSendsNoConfigurationMakesTheCacheStaleAtItsMarkedKeyframe() {
        val batch = VideoConfigBatch()
        batch.onConfig()
        assertFalse(batch.onMedia()) // first frames
        assertFalse(batch.onMedia(restartKeyFrame = false))
        assertTrue(batch.onMedia(restartKeyFrame = true)) // marked keyframe, no config since the last frame
        // With configuration first, the marked keyframe finds the cache already replaced.
        val withConfig = VideoConfigBatch()
        withConfig.onConfig()
        withConfig.onMedia()
        assertTrue(withConfig.onConfig())
        assertFalse(withConfig.onMedia(restartKeyFrame = true))
    }
}
