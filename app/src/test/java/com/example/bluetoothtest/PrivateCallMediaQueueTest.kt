package com.example.bluetoothtest

import java.util.concurrent.ArrayBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateCallMediaQueueTest {
    @Test
    fun audioPriorityPreservesVideoDecodeOrder() {
        val queue = ArrayBlockingQueue<String>(5).apply {
            addAll(listOf("video-1", "video-2", "audio-1", "video-3", "audio-2"))
        }

        val sent = List(5) { pollNextPrivateCallMedia(queue, 0L) { it.startsWith("audio") } }

        assertEquals(listOf("audio-1", "audio-2", "video-1", "video-2", "video-3"), sent)
    }

    @Test
    fun codecConfigurationStaysBeforeItsKeyframeAndDependentFrames() {
        val queue = ArrayBlockingQueue<String>(4).apply {
            addAll(listOf("configuration", "keyframe", "delta", "audio"))
        }

        val sent = List(4) { pollNextPrivateCallMedia(queue, 0L) { it == "audio" } }

        assertEquals(listOf("audio", "configuration", "keyframe", "delta"), sent)
    }

    @Test
    fun emptyQueueReturnsToCallControlAndHeartbeatWriter() {
        assertNull(pollNextPrivateCallMedia(ArrayBlockingQueue<String>(1), 0L) { true })
    }
}
