package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LargeFrameGraceTest {
    @Test
    fun aLargeKeyframeGetsGraceForItsMeasuredDrainTime() {
        // 40 KB keyframe at a measured 75 KB/s (600 kbps) drains in ~0.53 s; 1.5x headroom.
        assertEquals(799_999_999L, largeFrameDrainGraceNs(40_000, 3_000, keyFrame = true, 0, 75_000))
    }

    @Test
    fun graceIsCappedSoSustainedOverloadStillRecovers() {
        // The 130 KB coverage keyframe at 600 kbps would need 2.6 s; grace stops at 3 s.
        assertEquals(3_000_000_000L, largeFrameDrainGraceNs(130_000, 3_000, keyFrame = true, 20_000, 75_000))
    }

    @Test
    fun sceneCutInterFramesCountButOrdinaryFramesDoNot() {
        assertEquals(1_200_000_000L, largeFrameDrainGraceNs(60_000, 3_000, keyFrame = false, 0, 75_000))
        assertNull(largeFrameDrainGraceNs(6_000, 3_000, keyFrame = false, 0, 75_000))
    }
}
