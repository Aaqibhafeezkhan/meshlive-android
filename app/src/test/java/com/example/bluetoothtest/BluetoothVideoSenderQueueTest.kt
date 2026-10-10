package com.example.bluetoothtest

import java.util.concurrent.ArrayBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothVideoSenderQueueTest {
    @Test
    fun prioritizingAudioNeverReordersVideoFrames() {
        val queue = ArrayBlockingQueue<String>(8).apply {
            add("video-1")
            add("video-2")
            add("audio-1")
            add("video-3")
            add("audio-2")
        }

        val sent = List(5) {
            takeNextPrioritizing(queue) { packet -> packet.startsWith("audio") }
        }

        assertEquals(
            listOf("audio-1", "audio-2", "video-1", "video-2", "video-3"),
            sent,
        )
    }
}
