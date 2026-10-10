package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPresentationTimingTest {
    @Test fun uniqueIdentityPreservesEarlyLateAndListenerTimesSeparately() {
        val timing = VideoPresentationTiming(generation = 1)
        timing.recordRelease(1, mediaPtsUs = 77, targetNs = 2_000, callNs = 1_200)
        val codec = timing.recordCodec(1, renderedPtsUs = 77, reportedRenderNs = 1_950, listenerNowNs = 2_100)
        assertEquals(VideoPresentationMatch.MATCHED, codec.match)
        assertEquals(1_200L, codec.releaseCallNs)
        assertEquals(-50L, codec.reportedMinusTargetNs)
        assertEquals(100L, codec.observedMinusTargetNs)
        assertEquals(150L, codec.codecListenerDelayNs)

        val early = timing.recordTexture(1, timestampNs = 2_000, callbackNowNs = 1_900)
        assertEquals(VideoPresentationMatch.MATCHED, early.match)
        assertEquals(77L, early.mediaPtsUs)
        assertEquals(-100L, early.observedMinusTargetNs)
        assertNull(early.codecListenerDelayNs)
        timing.recordRelease(1, mediaPtsUs = 78, targetNs = 3_000, callNs = 2_200)
        assertEquals(500L, timing.recordTexture(1, 3_000, 3_500).observedMinusTargetNs)
    }

    @Test fun unknownTimestampDoesNotBorrowSourcePtsOrNearestTarget() {
        val timing = VideoPresentationTiming(generation = 1)
        timing.recordRelease(1, mediaPtsUs = 77, targetNs = 2_000, callNs = 1_200)
        for (timestamp in listOf(77_000L, 1_999L)) {
            val texture = timing.recordTexture(1, timestamp, 2_100)
            assertEquals(VideoPresentationMatch.UNMATCHED, texture.match)
            assertEquals(timestamp, texture.reportedNs)
            assertNull(texture.mediaPtsUs)
            assertNull(texture.observedMinusTargetNs)
        }
        val codec = timing.recordCodec(1, 78, 2_000, 2_100)
        assertEquals(VideoPresentationMatch.UNMATCHED, codec.match)
        assertEquals(78L, codec.mediaPtsUs)
        assertNull(codec.targetNs)
        assertEquals(1, timing.snapshot().unobservedCodecReleases)
        assertEquals(1, timing.snapshot().unobservedTextureReleases)
    }

    @Test fun repeatedPtsOrTargetsRemainAmbiguousInsteadOfChoosingLatest() {
        val timing = VideoPresentationTiming(generation = 1)
        timing.recordRelease(1, mediaPtsUs = 77, targetNs = 2_000, callNs = 1_200)
        timing.recordRelease(1, mediaPtsUs = 77, targetNs = 3_000, callNs = 2_200)
        val codec = timing.recordCodec(1, 77, 2_900, 3_100)
        assertEquals(VideoPresentationMatch.AMBIGUOUS, codec.match)
        assertNull(codec.targetNs)
        assertNull(codec.reportedMinusTargetNs)
        assertEquals(VideoPresentationMatch.MATCHED, timing.recordTexture(1, 2_000, 2_050).match)

        timing.recordRelease(1, mediaPtsUs = 78, targetNs = 3_000, callNs = 2_300)
        val texture = timing.recordTexture(1, 3_000, 3_100)
        assertEquals(VideoPresentationMatch.AMBIGUOUS, texture.match)
        assertNull(texture.mediaPtsUs)
        assertNull(texture.releaseCallNs)
        assertEquals(2, timing.snapshot().unobservedTextureReleases)
    }

    @Test fun repeatedCallbacksCannotCountAsNewObservations() {
        val timing = VideoPresentationTiming(generation = 1)
        timing.recordRelease(1, 77, 2_000, 1_200)
        assertEquals(VideoPresentationMatch.MATCHED, timing.recordCodec(1, 77, 2_000, 2_100).match)
        assertEquals(VideoPresentationMatch.DUPLICATE, timing.recordCodec(1, 77, 2_000, 2_150).match)
        assertEquals(VideoPresentationMatch.MATCHED, timing.recordTexture(1, 2_000, 2_100).match)
        assertEquals(VideoPresentationMatch.DUPLICATE, timing.recordTexture(1, 2_000, 2_150).match)
        val snapshot = timing.snapshot()
        assertEquals(1L, snapshot.cumulativeCounts[VideoPresentationEventKind.CODEC to VideoPresentationMatch.MATCHED])
        assertEquals(1L, snapshot.cumulativeCounts[VideoPresentationEventKind.TEXTURE to VideoPresentationMatch.MATCHED])
        assertEquals(0, snapshot.unobservedCodecReleases)
        assertEquals(0, snapshot.unobservedTextureReleases)
    }

    @Test fun laterTextureIdentityLeavesSkippedReleasesUnobservedAndAllowsDelayedEarlierCallback() {
        val timing = VideoPresentationTiming(generation = 1)
        timing.recordRelease(1, 77, 2_000, 1_200)
        timing.recordRelease(1, 78, 3_000, 2_200)
        timing.recordRelease(1, 79, 4_000, 3_200)
        val later = timing.recordTexture(1, 4_000, 4_100)
        assertEquals(2, later.unseenEarlierTextureReleases)
        assertEquals(2, timing.snapshot().unobservedTextureReleases)
        val delayed = timing.recordTexture(1, 2_000, 4_200)
        assertEquals(VideoPresentationMatch.MATCHED, delayed.match)
        assertEquals(2_200L, delayed.observedMinusTargetNs)
        assertEquals(1, timing.snapshot().unobservedTextureReleases)
    }

    @Test fun staleGenerationCannotMatchReusedIdentityOrAddCurrentReleases() {
        val timing = VideoPresentationTiming(generation = 1)
        timing.recordRelease(1, 77, 2_000, 1_200)
        timing.beginGeneration(2)
        timing.recordRelease(2, 77, 2_000, 1_800)
        assertEquals(VideoPresentationMatch.STALE_GENERATION, timing.recordCodec(1, 77, 2_000, 2_100).match)
        assertEquals(VideoPresentationMatch.STALE_GENERATION, timing.recordTexture(1, 2_000, 2_100).match)
        assertEquals(VideoPresentationMatch.STALE_GENERATION, timing.recordRelease(1, 78, 3_000, 2_200).match)
        val current = timing.recordTexture(2, 2_000, 2_100)
        assertEquals(VideoPresentationMatch.MATCHED, current.match)
        assertEquals(1_800L, current.releaseCallNs)
        assertEquals(2L, timing.snapshot().generation)
        assertEquals(1L, timing.snapshot().generationDiscardedReleases)
        assertEquals(1, timing.snapshot().unobservedCodecReleases)
        assertEquals(0, timing.snapshot().unobservedTextureReleases)
    }

    @Test fun boundedHistoryReportsLostMeasurementRatherThanInventingOldIdentity() {
        val timing = VideoPresentationTiming(generation = 1, capacity = 2)
        timing.recordRelease(1, 77, 2_000, 1_200)
        timing.recordRelease(1, 78, 3_000, 2_200)
        timing.recordRelease(1, 79, 4_000, 3_200)
        assertEquals(VideoPresentationMatch.UNMATCHED, timing.recordCodec(1, 77, 2_000, 4_100).match)
        assertEquals(VideoPresentationMatch.UNMATCHED, timing.recordTexture(1, 2_000, 4_200).match)
        val snapshot = timing.snapshot()
        assertEquals(2, snapshot.events.size)
        assertEquals(1L, snapshot.releaseOverflows)
        assertEquals(1L, snapshot.unobservedCodecOverflows)
        assertEquals(1L, snapshot.unobservedTextureOverflows)
        assertEquals(3L, snapshot.eventOverflows)
        assertEquals(2, snapshot.unobservedCodecReleases)
        assertEquals(2, snapshot.unobservedTextureReleases)
        assertEquals(3L, snapshot.cumulativeCounts[VideoPresentationEventKind.RELEASE to VideoPresentationMatch.RECORDED])
    }

    @Test fun evictingAlreadyObservedHistoryDoesNotInventMissingCallbacks() {
        val timing = VideoPresentationTiming(generation = 1, capacity = 1)
        timing.recordRelease(1, 77, 2_000, 1_200)
        timing.recordCodec(1, 77, 2_000, 2_050)
        timing.recordTexture(1, 2_000, 2_100)
        timing.recordRelease(1, 78, 3_000, 2_200)
        val snapshot = timing.snapshot()
        assertEquals(1L, snapshot.releaseOverflows)
        assertEquals(0L, snapshot.unobservedCodecOverflows)
        assertEquals(0L, snapshot.unobservedTextureOverflows)
        assertEquals(1, snapshot.unobservedCodecReleases)
        assertEquals(1, snapshot.unobservedTextureReleases)
    }

    @Test fun traceKeepsSignedNanosecondsAndDoesNotInventUnmatchedTiming() {
        val timing = VideoPresentationTiming(generation = 1)
        timing.recordRelease(1, 77, 2_000, 1_200)
        timing.recordCodec(1, 77, 1_950, 2_100)
        timing.recordTexture(1, 2_000, 1_900)
        timing.recordTexture(1, 77_000, 2_200)
        val lines = timing.snapshot().traceLines(99, 1, false, "test.decoder")
        assertTrue(lines.first().contains("rollingSamples=4"))
        assertTrue(lines.first().contains("optical=UNMEASURED"))
        val columns = lines.single { it.contains(" COLUMNS ") }.substringAfter(" report=1 ").split(',')
        val rows = lines.filter { it.contains(" DATA ") }.flatMap { it.substringAfter(" rows=").split(';') }
            .map { it.split(',') }
        assertEquals(listOf("1", "2", "3", "4"), rows.map { it.first() })
        assertEquals("-50", rows[1][columns.indexOf("reportedMinusTargetNs")])
        assertEquals("150", rows[1][columns.indexOf("codecListenerDelayNs")])
        assertEquals("-100", rows[2][columns.indexOf("observedMinusTargetNs")])
        assertEquals("NA", rows[3][columns.indexOf("targetNs")])
        assertEquals("NA", rows[3][columns.indexOf("observedMinusTargetNs")])
    }

    @Test fun traceChunksStayBelowLogLineLimitAndRollingReportsKeepStableSequence() {
        val timing = VideoPresentationTiming(generation = 1, capacity = 512)
        repeat(600) { index ->
            val target = 1_000_000_000L + index * 33_333_333L
            timing.recordRelease(1, index.toLong(), target, target - 75_000_000L)
            timing.recordCodec(1, index.toLong(), target, target + 1_000_000L)
            timing.recordTexture(1, target, target - 50_000_000L)
        }
        val snapshot = timing.snapshot()
        val lines = snapshot.traceLines(99, 1, false, "test.decoder")
        assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size < 3_000 })
        assertTrue(lines.count { it.contains(" DATA ") } > 1)
        val sequence = lines.filter { it.contains(" DATA ") }
            .flatMap { it.substringAfter(" rows=").split(';') }.map { it.substringBefore(',').toLong() }
        assertEquals((1_289L..1_800L).toList(), sequence)
        assertEquals(sequence, snapshot.traceLines(99, 2, true, "test.decoder")
            .filter { it.contains(" DATA ") }.flatMap { it.substringAfter(" rows=").split(';') }
            .map { it.substringBefore(',').toLong() })
        assertEquals(1_288L, snapshot.eventOverflows)
        assertEquals(88L, snapshot.releaseOverflows)
        assertEquals(0L, snapshot.unobservedTextureOverflows)
    }
}
