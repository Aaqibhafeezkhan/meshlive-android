package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBudgetTest {
    @Test
    fun interFrameBudgetIsTheLinkShareOverQuarterSecond() {
        // M31 GATT ~450 kbps to one viewer at 400 kbps / 15 FPS: 421% of an average frame (~14 KB).
        assertEquals(421, maxInterFramePercent(450, 400_000, 15))
        // Two viewers sharing it get half; the floor keeps normal motion encodable.
        assertEquals(210, maxInterFramePercent(225, 400_000, 15))
        assertEquals(150, maxInterFramePercent(50, 400_000, 15))
    }

    @Test
    fun measuredLinkLeavesRoomForAudioAndHeadersInThePicker() {
        val video = MeshBandwidthEstimator.videoKbpsForLink(910.0, 15)
        assertTrue(video in 860..880)
    }
}
