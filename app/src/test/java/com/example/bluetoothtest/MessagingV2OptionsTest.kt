package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The attachment switches, the size limits and the photo arithmetic. */
class MessagingV2OptionsTest {
    private fun extras(ints: Map<String, Int> = emptyMap(), bools: Map<String, Boolean> = emptyMap()) =
        MessagingV2AttachOptions.fromExtras({ ints[it] }, { bools[it] })

    @Test
    fun theDefaultsAreTheDesignedOnes() {
        val d = MessagingV2AttachOptions()
        assertEquals(8, d.autoDownloadMb)
        assertEquals(64, d.maxFileMb)
        assertEquals(1600, d.photoEdge)
        assertEquals(80, d.photoQuality)
        assertFalse(d.autoThumbnails)
        assertEquals(DecodeMode.ISOLATED, d.decodeMode)
        assertEquals(8_000_000L, d.autoDownloadBytes)
        assertEquals(64_000_000L, d.maxFileBytes)
        assertEquals(d, extras())
        assertEquals(d.autoDownloadBytes, d.applyTo(MessagingV2Limits()).autoDownloadMaxBytes)
    }

    @Test
    fun extrasOverrideAndAreClampedToWhatTheDesignAllows() {
        val o = extras(
            mapOf("test_mv2_autodownload_mb" to 64, "test_mv2_max_file_mb" to 10, "test_mv2_photo_edge" to 1280, "test_mv2_photo_quality" to 90),
            mapOf("test_mv2_auto_thumbnails" to true, "test_mv2_decode_isolated" to false),
        )
        assertEquals(64, o.autoDownloadMb)
        assertEquals(10, o.maxFileMb)
        assertEquals(1280, o.photoEdge)
        assertEquals(90, o.photoQuality)
        assertTrue(o.autoThumbnails)
        assertEquals(DecodeMode.IN_PROCESS, o.decodeMode)
        val wild = extras(mapOf("test_mv2_autodownload_mb" to 5000, "test_mv2_max_file_mb" to 5000, "test_mv2_photo_edge" to 1, "test_mv2_photo_quality" to 500))
        assertEquals(64, wild.autoDownloadMb)
        assertEquals(64, wild.maxFileMb)
        assertEquals(320, wild.photoEdge)
        assertEquals(95, wild.photoQuality)
        val negative = extras(mapOf("test_mv2_autodownload_mb" to -4, "test_mv2_max_file_mb" to 0))
        assertEquals(0, negative.autoDownloadMb)
        assertEquals(1, negative.maxFileMb)
    }

    @Test
    fun aNonBenchmarkLaunchIgnoresExtras() {
        MessagingV2Options.configure(false, { 1 }, { true })
        assertEquals(MessagingV2AttachOptions(), MessagingV2Options.current)
        MessagingV2Options.configure(true, { if (it == "test_mv2_photo_edge") 800 else null }, { null })
        assertEquals(800, MessagingV2Options.current.photoEdge)
        MessagingV2Options.configure(false, { null }, { null })
        assertEquals(MessagingV2AttachOptions(), MessagingV2Options.current)
    }

    @Test
    fun sizeLimitsStopAnEmptyOrTooLargeFileBeforeAnythingIsSealed() {
        val options = MessagingV2AttachOptions()
        assertTrue(AttachmentPolicy.check(0, options) is AttachRefusal.Empty)
        assertTrue(AttachmentPolicy.check(-5, options) is AttachRefusal.Empty)
        assertNull(AttachmentPolicy.check(1, options))
        assertNull(AttachmentPolicy.check(1_000_000, options))
        assertNull(AttachmentPolicy.check(64_000_000, options))
        val tooBig = AttachmentPolicy.check(64_000_001, options) as AttachRefusal.TooLarge
        assertEquals(64_000_000L, tooBig.limit)
        // A lower switch lowers the limit; a higher one cannot raise it above the design ceiling.
        assertTrue(AttachmentPolicy.check(10_000_001, options.copy(maxFileMb = 10)) is AttachRefusal.TooLarge)
        assertNull(AttachmentPolicy.check(10_000_000, options.copy(maxFileMb = 10)))
        assertTrue(AttachmentPolicy.check(64_000_001, options.copy(maxFileMb = 1000)) is AttachRefusal.TooLarge)
    }

    @Test
    fun theEnginePutsTheSameCeilingOnTheSendAndOnTheSegmentCount() {
        assertEquals(62, SegmentMath.count(MessagingV2Format.MAX_FILE_BYTES))
        assertEquals(63, SegmentMath.count(MessagingV2Format.MAX_FILE_BYTES + 1_044_480L))
    }

    @Test
    fun theTimeEstimateUsesTheLearnedRate() {
        assertEquals(-1, AttachmentPolicy.estimateSeconds(1000, 0))
        // 10 MB at 1000 kbps: about 83 s with framing.
        assertEquals(83L, AttachmentPolicy.estimateSeconds(10_000_000, 1000))
        assertEquals(1L, AttachmentPolicy.estimateSeconds(10, 1000))
        assertTrue(AttachmentPolicy.estimateSeconds(64_000_000, 65) > 7_000)
    }

    // ---- photo arithmetic --------------------------------------------------------------------------------------------

    @Test
    fun aLargePhotoIsScaledToTheLongestEdgeAndNeverEnlarged() {
        val t = PhotoGeometry.target(4000, 3000, 1, 1600)
        assertEquals(1600, t.width)
        assertEquals(1200, t.height)
        assertTrue(t.scaled)
        val small = PhotoGeometry.target(800, 600, 1, 1600)
        assertEquals(800, small.width)
        assertEquals(600, small.height)
        assertFalse(small.scaled)
        val exact = PhotoGeometry.target(1600, 1000, 1, 1600)
        assertFalse(exact.scaled)
        val thin = PhotoGeometry.target(20_000, 3, 1, 1600)
        assertEquals(1600, thin.width)
        assertEquals(1, thin.height)
    }

    @Test
    fun aRotatedPhotoIsMeasuredUpright() {
        for (orientation in 5..8) {
            val t = PhotoGeometry.target(4000, 3000, orientation, 1600)
            assertEquals("orientation $orientation", 1200, t.width)
            assertEquals(1600, t.height)
        }
        for (orientation in 1..4) {
            val t = PhotoGeometry.target(4000, 3000, orientation, 1600)
            assertEquals(1600, t.width)
            assertEquals(1200, t.height)
        }
    }

    @Test
    fun theSampleSizeKeepsTheDecodeAtOrAboveTheTarget() {
        assertEquals(1, PhotoGeometry.sampleSize(1600, 1200, 1600))
        assertEquals(1, PhotoGeometry.sampleSize(3199, 2000, 1600))
        assertEquals(2, PhotoGeometry.sampleSize(3200, 2000, 1600))
        assertEquals(2, PhotoGeometry.sampleSize(4000, 3000, 1600))
        assertEquals(4, PhotoGeometry.sampleSize(12_000, 9_000, 1600))
        assertEquals(16, PhotoGeometry.sampleSize(25_600, 100, 1600))
        // The decode never falls below the target edge.
        for (w in intArrayOf(1700, 3300, 6500, 13_000, 40_000)) {
            val s = PhotoGeometry.sampleSize(w, w / 2, 1600)
            assertTrue(w / s >= 1600)
            assertTrue(w / (s * 2) < 1600)
        }
    }

    @Test
    fun theDisplayDecodeAllocationDependsOnTheDisplayNotOnTheHeader() {
        // A 40 megapixel picture decoded for a 1280 px display: the result is at most (2 x 1280)^2 pixels before scaling.
        val s = PhotoGeometry.displaySampleSize(8000, 5000, 1280)
        assertTrue((8000 / s).toLong() * (5000 / s) <= 2560L * 2560)
        assertEquals(1, PhotoGeometry.displaySampleSize(800, 600, 1280))
    }

    @Test
    fun theThumbnailTargetIsAtMost320() {
        val t = PhotoGeometry.thumbnailTarget(4032, 3024, 6)
        assertEquals(240, t.width)
        assertEquals(320, t.height)
        assertTrue(maxOf(t.width, t.height) <= MessagingV2Format.THUMBNAIL_MAX_EDGE)
    }
}
