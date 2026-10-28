package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design HC on the receiver: the decoder's csd is the newest keyframe's in-band sets only, and a keyframe whose sets or
 * size differ rebuilds the decoder (parameter-set swap); a codec-config packet on codec 4 or 5 closes the stream.
 */
class HcDecoderFormatTest {
    private fun idr(codec: AnnexBCodec, width: Int, height: Int) =
        StreamProtocol.Packet(StreamProtocol.FLAG_KEY_FRAME, 0L, AnnexBTestStreams.idrAu(codec, AnnexBTestStreams.setsFor(codec, width, height)))

    private fun p(codec: AnnexBCodec) = StreamProtocol.Packet(0, 1L, AnnexBTestStreams.pAu(codec))

    private fun header(codec: AnnexBCodec) = StreamProtocol.Header(854, 480, 15, codec.protocolId, 0, 0, 0, 0)

    @Test
    fun aParameterSetSwapAtAnIdrRebuildsTheDecoderWithTheNewestSetsOnly() {
        for (codec in AnnexBCodec.entries) {
            val first = idr(codec, 640, 480)
            val csd = requireNotNull(hcFirstKeyFrameCsd(codec, header(codec), first)) { "$codec first keyframe csd" }
            assertEquals(if (codec == AnnexBCodec.H264) 2 else 1, csd.size)
            assertTrue(csd.all { it.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 0, 1)) })
            assertNull(hcFirstKeyFrameCsd(codec, header(codec), p(codec)))
            assertEquals(640 to 480, CodedVideoSize.ofKeyFrame(codec.protocolId, first.payload))

            val tracker = DecoderFormatTracker(codec.protocolId, decoderCodecSpecificData(codec.protocolId, csd), 640, 480)
            // The same sets again: no rebuild, as for VP9/AV1 keyframes without a change.
            assertNull(tracker.plan(idr(codec, 640, 480)).rebuild)
            assertNull(tracker.plan(p(codec)).rebuild)
            // New sets with a new size (a ladder rung): rebuild with exactly the new sets as csd.
            val swapped = idr(codec, 426, 240)
            val rebuild = requireNotNull(tracker.plan(swapped).rebuild) { "$codec rebuild" }
            assertTrue("$codec", rebuild.sizeChanged && rebuild.configChanged)
            assertEquals(426 to 240, rebuild.width to rebuild.height)
            val expected = AnnexBTestStreams.idrAu(codec, AnnexBTestStreams.setsFor(codec, 426, 240)).let {
                requireNotNull(AccessUnitGate.keyFrameSets(codec, it, 854, 480)).csd()
            }
            assertEquals(expected.size, rebuild.codecSpecificData.size)
            expected.indices.forEach { assertTrue(expected[it].contentEquals(rebuild.codecSpecificData[it])) }
            assertEquals(expected.size, tracker.currentCodecSpecificData().size)
        }
    }

    @Test
    fun aCodecConfigPacketOnCodec4Or5ClosesTheStreamButNotOnVp9OrAv1() {
        for (codecId in listOf(StreamProtocol.CODEC_H264, StreamProtocol.CODEC_HEVC)) {
            assertTrue(runCatching { VideoCodecMap.requireNoCodecConfig(codecId, StreamProtocol.FLAG_CODEC_CONFIG) }.isFailure)
            VideoCodecMap.requireNoCodecConfig(codecId, StreamProtocol.FLAG_KEY_FRAME)
        }
        VideoCodecMap.requireNoCodecConfig(StreamProtocol.CODEC_VP9, StreamProtocol.FLAG_CODEC_CONFIG)
        VideoCodecMap.requireNoCodecConfig(StreamProtocol.CODEC_AV1, StreamProtocol.FLAG_CODEC_CONFIG)
    }

    @Test
    fun aRejectedAccessUnitMakesTheQueueWaitForTheNextKeyframe() {
        val queue = EncodedFrameQueue(8, requireInitialKeyFrame = true)
        val codec = AnnexBCodec.HEVC
        assertTrue(queue.offer(idr(codec, 640, 480)))
        assertTrue(queue.offer(p(codec)))
        queue.markLoss()
        assertTrue(queue.stats().needsKeyFrame)
        assertEquals(false, queue.offer(p(codec))) // later P frames wait for an IDR
        assertTrue(queue.offer(idr(codec, 640, 480)))
        assertEquals(false, queue.stats().needsKeyFrame)
        assertEquals(3, queue.stats().depth) // the frames before the loss stay decodable
    }
}
