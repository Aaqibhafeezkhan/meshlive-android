package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedFrameQueueTest {
    @Test
    fun measuresBytesAndOldestAge() {
        val queue = EncodedFrameQueue(3)
        assertTrue(queue.offer(packet(0, 100, 10), 1_000_000_000L))
        assertTrue(queue.offer(packet(0, 200, 20), 1_100_000_000L))

        val stats = queue.stats(1_350_000_000L)
        assertEquals(2, stats.depth)
        assertEquals(300, stats.queuedBytes)
        assertEquals(350, stats.oldestFrameAgeMs)
        assertEquals(10L, queue.peek()?.presentationTimeUs)
        queue.poll()
        assertEquals(20L, queue.peek()?.presentationTimeUs)
    }

    @Test
    fun codecConfigurationStaysAheadOfItsKeyFrame() {
        val queue = EncodedFrameQueue(3, requireInitialKeyFrame = true)
        val config = packet(StreamProtocol.FLAG_CODEC_CONFIG)
        val keyFrame = packet(StreamProtocol.FLAG_KEY_FRAME, presentationTimeUs = 20_000_000L)

        assertTrue(queue.offer(config, 1L))
        assertTrue(queue.offer(keyFrame, 2L))
        assertEquals(config, queue.peek())
        assertEquals(config, queue.poll()?.first)
        assertEquals(keyFrame, queue.peek())
        assertEquals(keyFrame, queue.poll()?.first)
    }

    @Test
    fun aFullQueueWaitsForTheDecoderInsteadOfDroppingFrames() {
        // The previous queue dropped every queued frame on overflow and waited for a keyframe.
        val queue = EncodedFrameQueue(2)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 1L))
        assertTrue(queue.offer(packet(0), 2L))
        val offered = java.util.concurrent.CountDownLatch(1)
        Thread {
            queue.offer(packet(0, presentationTimeUs = 3L), 3L)
            offered.countDown()
        }.start()
        assertFalse(offered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        queue.poll()
        assertTrue(offered.await(1, java.util.concurrent.TimeUnit.SECONDS))
        val stats = queue.stats(4L)
        assertEquals(2, stats.depth)
        assertEquals(0, stats.droppedFrames)
        assertFalse(stats.needsKeyFrame)
    }

    @Test(expected = IllegalStateException::class)
    fun aDecoderThatStopsConsumingFailsLoudlyRatherThanLosingFrames() {
        val queue = EncodedFrameQueue(1, backpressureTimeoutNs = 50_000_000L)
        queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 1L)
        queue.offer(packet(0), 2L)
    }

    @Test
    fun initialDecoderQueueRejectsDependentFramesUntilKeyframe() {
        val queue = EncodedFrameQueue(3, requireInitialKeyFrame = true)

        assertTrue(queue.offer(packet(StreamProtocol.FLAG_CODEC_CONFIG), 1L))
        assertFalse(queue.offer(packet(0), 2L))
        assertTrue(queue.stats(2L).needsKeyFrame)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 3L))
        assertFalse(queue.stats(3L).needsKeyFrame)
    }

    @Test
    fun discontinuityDropsQueuedDependenciesAndRestartsAtKeyframe() {
        val queue = EncodedFrameQueue(3)
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 1L))
        assertTrue(queue.offer(packet(0), 2L))

        queue.resetForDiscontinuity()

        assertFalse(queue.offer(packet(0), 3L))
        assertTrue(queue.offer(packet(StreamProtocol.FLAG_KEY_FRAME), 4L))
        val stats = queue.stats(4L)
        assertEquals(1, stats.depth)
        assertEquals(3, stats.droppedFrames)
        assertFalse(stats.needsKeyFrame)
    }

    private fun packet(flags: Int, bytes: Int = 1, presentationTimeUs: Long = 0) = StreamProtocol.Packet(
        flags = flags,
        presentationTimeUs = presentationTimeUs,
        payload = ByteArray(bytes),
    )
}
