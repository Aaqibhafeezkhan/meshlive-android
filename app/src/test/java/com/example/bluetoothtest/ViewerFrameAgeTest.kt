package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's stale-picture rules (PLAN "Visible age of the last received frame"): the threshold,
 * the two causes, recovery on the first fresh picture, repeated loss and rejoin, pauses, the
 * background, and the lower-bound age built only from sender timestamps.
 */
class ViewerFrameAgeTest {
    private fun ms(value: Long) = value * 1_000_000L

    private fun viewing(fps: Int = 15): ViewerFrameAgeMonitor =
        ViewerFrameAgeMonitor().also {
            it.startViewing(0L)
            it.setFrameRate(fps)
        }

    /** A viewing with one link that already presented a picture at [firstFrameMs]. */
    private fun playing(firstFrameMs: Long = 0L, fps: Int = 15): ViewerFrameAgeMonitor =
        viewing(fps).also {
            it.linkStarted()
            it.onFramePresented(ms(firstFrameMs))
            it.drainEvents()
        }

    @Test
    fun thresholdIsTheLargerOfTheFloorAndThreeFrameIntervals() {
        val config = ViewerFrameAgeConfig()
        assertEquals(1_500L, config.thresholdMs(67))
        assertEquals(1_500L, config.thresholdMs(33))
        assertEquals(1_500L, config.thresholdMs(400))
        assertEquals(1_800L, config.thresholdMs(600))
        assertEquals(3_000L, config.thresholdMs(1_000))
        assertEquals(1_500L, viewing(fps = 15).snapshot(0L).thresholdMs)
        assertEquals(1_500L, viewing(fps = 30).snapshot(0L).thresholdMs)
        assertEquals(3_000L, viewing(fps = 1).snapshot(0L).thresholdMs)
    }

    @Test
    fun nothingShownYetIsWaitingAndNeverStale() {
        val monitor = viewing()
        monitor.linkStarted()
        monitor.linkLost(ms(100))
        val snapshot = monitor.snapshot(ms(60_000))
        assertEquals(ViewerPictureState.WAITING, snapshot.state)
        assertEquals(ViewerStaleCause.NONE, snapshot.cause)
        assertFalse(snapshot.stale)
        assertEquals(-1L, snapshot.sinceFrameMs)
        assertEquals(0, snapshot.staleEpisodes)
    }

    @Test
    fun aPictureStaysLiveUpToTheThresholdAndIsStaleJustAfter() {
        val monitor = playing()
        assertEquals(ViewerPictureState.LIVE, monitor.snapshot(ms(1_500)).state)
        assertTrue(monitor.drainEvents().isEmpty())

        val stale = monitor.snapshot(ms(1_501))
        assertEquals(ViewerPictureState.STALE, stale.state)
        assertEquals(ViewerStaleCause.NO_FRAMES, stale.cause)
        assertEquals(1, stale.staleEpisodes)
        val entered = monitor.drainEvents().single() as ViewerFrameAgeEvent.StaleEntered
        assertEquals(ViewerStaleCause.NO_FRAMES, entered.cause)
        assertEquals(1_501L, entered.sinceFrameMs)
        assertEquals(1_500L, entered.thresholdMs)
        assertEquals(1, entered.episode)
    }

    @Test
    fun theFirstFreshPictureClearsAStallAndReportsTheWholeFreeze() {
        val monitor = playing()
        monitor.snapshot(ms(2_000))
        monitor.drainEvents()

        monitor.onFramePresented(ms(4_200))
        val snapshot = monitor.snapshot(ms(4_200))
        assertEquals(ViewerPictureState.LIVE, snapshot.state)
        assertEquals(1, snapshot.recoveries)
        val cleared = monitor.drainEvents().single() as ViewerFrameAgeEvent.StaleCleared
        assertEquals(ViewerStaleClearReason.FRAME, cleared.reason)
        assertEquals(ViewerStaleCause.NO_FRAMES, cleared.fromCause)
        assertEquals(4_200L, cleared.gapMs)
        assertEquals(2_200L, cleared.staleForMs)
    }

    @Test
    fun aLostLinkIsStaleAtOnceAndOnlyAPictureOfANewerLinkClearsIt() {
        val monitor = playing()
        monitor.linkLost(ms(500))
        val lost = monitor.snapshot(ms(500))
        // Well under the threshold: a lost link does not wait for it.
        assertEquals(ViewerPictureState.STALE, lost.state)
        assertEquals(ViewerStaleCause.LINK_LOST, lost.cause)

        // A picture that was already in flight from the lost link does not prove a recovery.
        monitor.onFramePresented(ms(600))
        assertEquals(ViewerPictureState.STALE, monitor.snapshot(ms(600)).state)
        // Starting the replacement link alone does not either.
        monitor.linkStarted()
        assertEquals(ViewerPictureState.STALE, monitor.snapshot(ms(1_000)).state)
        monitor.snapshot(ms(5_000))

        monitor.onFramePresented(ms(6_000))
        val recovered = monitor.snapshot(ms(6_000))
        assertEquals(ViewerPictureState.LIVE, recovered.state)
        assertEquals(1, recovered.staleEpisodes)
        assertEquals(1, recovered.recoveries)

        val events = monitor.drainEvents()
        assertTrue(events.any { it is ViewerFrameAgeEvent.LinkLost })
        assertTrue(events.any { it is ViewerFrameAgeEvent.StaleEntered && it.cause == ViewerStaleCause.LINK_LOST })
        assertTrue(events.any { it is ViewerFrameAgeEvent.LinkRestored })
        val cleared = events.filterIsInstance<ViewerFrameAgeEvent.StaleCleared>().single()
        assertEquals(ViewerStaleCause.LINK_LOST, cleared.fromCause)
        assertEquals(2, cleared.linkEpoch)
        assertEquals(5_400L, cleared.gapMs)
    }

    @Test
    fun aFailedReplacementAttemptKeepsTheLossUntilALaterLinkShowsAPicture() {
        val monitor = playing()
        monitor.linkLost(ms(100))
        monitor.linkStarted()
        monitor.linkLost(ms(900))
        monitor.linkStarted()
        monitor.onFramePresented(ms(1_000))
        assertEquals(ViewerPictureState.LIVE, monitor.snapshot(ms(1_000)).state)
        assertEquals(1, monitor.snapshot(ms(1_000)).staleEpisodes)
    }

    @Test
    fun aStallThatBecomesALinkLossChangesTheCauseWithoutANewEpisode() {
        val monitor = playing()
        monitor.snapshot(ms(2_000))
        monitor.linkLost(ms(3_000))
        val snapshot = monitor.snapshot(ms(3_000))
        assertEquals(ViewerStaleCause.LINK_LOST, snapshot.cause)
        assertEquals(1, snapshot.staleEpisodes)
        val events = monitor.drainEvents()
        assertTrue(events.any { it is ViewerFrameAgeEvent.StaleCauseChanged && it.cause == ViewerStaleCause.LINK_LOST })
        assertEquals(1, events.count { it is ViewerFrameAgeEvent.StaleEntered })
    }

    @Test
    fun aPauseIsNeverStaleAndTheCountRestartsAtTheResume() {
        val monitor = playing()
        monitor.onPaused(ms(100))
        assertEquals(ViewerPictureState.PAUSED, monitor.snapshot(ms(60_000)).state)
        assertEquals(0, monitor.snapshot(ms(60_000)).staleEpisodes)

        monitor.onResumed(ms(60_000))
        assertEquals(ViewerPictureState.LIVE, monitor.snapshot(ms(61_500)).state)
        val stale = monitor.snapshot(ms(61_501))
        assertEquals(ViewerPictureState.STALE, stale.state)
        assertEquals(ViewerStaleCause.NO_FRAMES, stale.cause)
        // The true age of the last picture is still reported from the picture itself.
        assertEquals(61_501L, stale.sinceFrameMs)
    }

    @Test
    fun aPauseDoesNotCountAsAFreezeInTheGapWindow() {
        val monitor = playing()
        monitor.onPaused(ms(100))
        monitor.onResumed(ms(60_000))
        monitor.onFramePresented(ms(60_100))
        assertEquals(100L, monitor.snapshot(ms(60_100)).maxGapMs)
    }

    @Test
    fun aStallThatPausesClearsWithThePausedReasonAndIsNotARecovery() {
        val monitor = playing()
        monitor.snapshot(ms(2_000))
        monitor.drainEvents()
        monitor.onPaused(ms(2_500))
        val snapshot = monitor.snapshot(ms(2_500))
        assertEquals(ViewerPictureState.PAUSED, snapshot.state)
        assertEquals(0, snapshot.recoveries)
        val cleared = monitor.drainEvents().filterIsInstance<ViewerFrameAgeEvent.StaleCleared>().single()
        assertEquals(ViewerStaleClearReason.PAUSED, cleared.reason)
        assertEquals(-1L, cleared.gapMs)
    }

    @Test
    fun aPausedNoticeOnTheReplacementLinkProvesItIsUpWithoutAPicture() {
        val monitor = playing()
        monitor.linkLost(ms(100))
        monitor.linkStarted()
        assertEquals(ViewerPictureState.STALE, monitor.snapshot(ms(500)).state)
        // The broadcaster is paused: no picture will come, but the link is back.
        monitor.onPaused(ms(900))
        assertEquals(ViewerPictureState.PAUSED, monitor.snapshot(ms(900)).state)
        assertTrue(monitor.drainEvents().any { it is ViewerFrameAgeEvent.LinkRestored })
    }

    @Test
    fun aResumedNoticeAloneDoesNotEndALoss() {
        val monitor = playing()
        monitor.linkLost(ms(100))
        monitor.linkStarted()
        // A sender may repeat "live" on every join; only a picture proves the replacement works.
        monitor.onResumed(ms(500))
        assertEquals(ViewerPictureState.STALE, monitor.snapshot(ms(600)).state)
        assertEquals(ViewerStaleCause.LINK_LOST, monitor.snapshot(ms(600)).cause)
        monitor.onFramePresented(ms(900))
        assertEquals(ViewerPictureState.LIVE, monitor.snapshot(ms(900)).state)
    }

    @Test
    fun anEndedStreamIsNeverStale() {
        val monitor = playing()
        monitor.linkLost(ms(100))
        monitor.onEnded(ms(200))
        assertEquals(ViewerPictureState.ENDED, monitor.snapshot(ms(300)).state)
        monitor.linkLost(ms(400))
        assertEquals(ViewerPictureState.ENDED, monitor.snapshot(ms(500)).state)
    }

    @Test
    fun theBackgroundGivesNoVerdictAndTheReturnRestartsTheCount() {
        val monitor = playing()
        monitor.onForeground(false, ms(100))
        // A TextureView that is not drawn cannot report pictures: no stale verdict meanwhile.
        assertEquals(ViewerPictureState.LIVE, monitor.snapshot(ms(10_000)).state)
        monitor.onForeground(true, ms(10_000))
        assertEquals(ViewerPictureState.LIVE, monitor.snapshot(ms(11_500)).state)
        assertEquals(ViewerPictureState.STALE, monitor.snapshot(ms(11_501)).state)
    }

    @Test
    fun aLostLinkStaysStaleInTheBackground() {
        val monitor = playing()
        monitor.onForeground(false, ms(100))
        monitor.linkLost(ms(200))
        assertEquals(ViewerPictureState.STALE, monitor.snapshot(ms(5_000)).state)
    }

    @Test
    fun frameAgeIsALowerBoundBuiltOnlyFromSenderTimestamps() {
        val monitor = playing()
        // Unknown until both a received and a rendered timestamp exist.
        assertEquals(-1L, monitor.snapshot(ms(100)).frameAgeMs)
        monitor.onVideoReceived(2_000_000L, ms(1_000))
        monitor.onFrameRendered(1_600_000L)
        val healthy = monitor.snapshot(ms(1_100))
        assertEquals(400L, healthy.behindReceivedMs)
        assertEquals(500L, healthy.frameAgeMs)
        // Nothing new arrives: the age keeps growing by wall time, the picture trailing by 400 ms.
        assertEquals(2_400L, monitor.snapshot(ms(3_000)).frameAgeMs)
    }

    @Test
    fun aTimestampDiscontinuityNeverProducesANegativeOrHugeAge() {
        val monitor = playing()
        monitor.onVideoReceived(1_000_000L, ms(10))
        monitor.onFrameRendered(9_000_000L)
        assertEquals(0L, monitor.snapshot(ms(20)).behindReceivedMs)
        monitor.onVideoReceived(900_000_000_000L, ms(30))
        assertEquals(60_000L, monitor.snapshot(ms(40)).behindReceivedMs)
    }

    @Test
    fun aNewLinkForgetsTheTimestampsOfTheLostOne() {
        val monitor = playing()
        monitor.onVideoReceived(5_000_000L, ms(10))
        monitor.onFrameRendered(1_000_000L)
        monitor.linkStarted()
        assertEquals(-1L, monitor.snapshot(ms(20)).behindReceivedMs)
        assertEquals(-1L, monitor.snapshot(ms(20)).playoutDelayMs)
    }

    @Test
    fun theGapWindowKeepsTheLongestFreezeAndResetsWhenAsked() {
        val monitor = playing()
        monitor.onFramePresented(ms(100))
        monitor.onFramePresented(ms(1_100))
        monitor.onFramePresented(ms(1_200))
        assertEquals(1_000L, monitor.snapshot(ms(1_300), resetWindow = true).maxGapMs)
        // The next window sees only its own gaps, the open one included.
        assertEquals(200L, monitor.snapshot(ms(1_400)).maxGapMs)
    }

    @Test
    fun repeatedLossAndRejoinRecoversEveryTimeFromTheBaseDelay() {
        val monitor = playing()
        var now = 1_000L
        repeat(5) { cycle ->
            monitor.linkLost(ms(now))
            assertEquals(ViewerPictureState.STALE, monitor.snapshot(ms(now + 1)).state)
            monitor.linkStarted()
            // Each link starts its own clock at the profile's base delay, never the last link's.
            monitor.playoutClockStarted(250L)
            now += 3_000
            monitor.onFramePresented(ms(now))
            val snapshot = monitor.snapshot(ms(now))
            assertEquals(ViewerPictureState.LIVE, snapshot.state)
            assertEquals(cycle + 1, snapshot.staleEpisodes)
            assertEquals(cycle + 1, snapshot.recoveries)
            // The delay this link grew stays this link's own business.
            monitor.onPlayoutDelay(1_200L)
            now += 1_000
            monitor.onFramePresented(ms(now))
        }
        val events = monitor.drainEvents()
        assertEquals(5, events.count { it is ViewerFrameAgeEvent.StaleCleared })
        val bases = events.filterIsInstance<ViewerFrameAgeEvent.PlayoutBase>()
        assertEquals(5, bases.size)
        assertTrue(bases.all { it.delayMs == 250L })
        assertEquals(listOf(2, 3, 4, 5, 6), bases.map { it.linkEpoch })
    }

    @Test
    fun startViewingForgetsEverything() {
        val monitor = playing()
        monitor.linkLost(ms(100))
        monitor.snapshot(ms(5_000))
        monitor.startViewing(ms(10_000))
        val snapshot = monitor.snapshot(ms(10_000))
        assertEquals(ViewerPictureState.WAITING, snapshot.state)
        assertEquals(0, snapshot.staleEpisodes)
        assertEquals(0, snapshot.recoveries)
        assertEquals(0, snapshot.linkEpoch)
        assertTrue(monitor.drainEvents().isEmpty())
    }

    @Test
    fun theEventListIsBoundedWhenNobodyDrainsIt() {
        val monitor = viewing()
        repeat(2_000) { monitor.linkStarted() }
        assertTrue(monitor.drainEvents().size <= 256)
        assertTrue(monitor.drainEvents().isEmpty())
    }

    @Test
    fun logLinesUseTheBenchmarkGrammar() {
        val monitor = playing()
        monitor.onVideoReceived(2_000_000L, ms(1_000))
        monitor.onFrameRendered(1_600_000L)
        monitor.onPlayoutDelay(250L)
        assertEquals(
            "viewer_frame_age: state=live | cause=none | sinceFrameMs=1100 | frameAgeMs=500 | behindReceivedMs=400 | " +
                "playoutDelayMs=250 | maxGapMs=1100 | thresholdMs=1500 | staleEpisodes=0 | recoveries=0 | linkEpoch=1",
            monitor.snapshot(ms(1_100)).logLine(),
        )
        monitor.linkLost(ms(1_200))
        monitor.onFramePresented(ms(9_000))
        val lines = monitor.drainEvents().map { it.logLine() }
        assertTrue(lines.toString(), lines.contains("viewer_link: event=lost | linkEpoch=1 | sinceFrameMs=1200"))
        assertTrue(
            lines.toString(),
            lines.contains(
                "viewer_stale: event=enter | cause=link_lost | sinceFrameMs=1200 | thresholdMs=1500 | linkEpoch=1 | episode=1",
            ),
        )
        // The picture came on the same link epoch: still lost, so no clear line yet.
        assertNull(lines.firstOrNull { it.startsWith("viewer_stale: event=clear") })
        assertEquals("viewer_link: event=start | linkEpoch=3", ViewerFrameAgeEvent.LinkStarted(3).logLine())
        assertEquals(
            "viewer_stale: event=clear | reason=frame | fromCause=no_frames | staleForMs=2200 | gapMs=4200 | " +
                "linkEpoch=1 | recoveries=1",
            ViewerFrameAgeEvent.StaleCleared(
                ViewerStaleClearReason.FRAME, ViewerStaleCause.NO_FRAMES, 2_200L, 4_200L, 1, 1,
            ).logLine(),
        )
    }

    @Test
    fun aStalePictureNeverCarriesTheFreshLabel() {
        val noFrames = playing().snapshot(ms(2_000))
        assertEquals(ViewerStaleUiPlan(ViewerBadgeKind.PICTURE_STALLED, showLabel = false), ViewerStaleUiPlan.of(noFrames, true))

        val lost = playing().also { it.linkLost(ms(100)) }.snapshot(ms(100))
        assertEquals(ViewerStaleUiPlan(ViewerBadgeKind.SIGNAL_LOST, showLabel = false), ViewerStaleUiPlan.of(lost, true))
        assertTrue(ViewerStaleUiPlan.of(lost, false).stale)
    }

    @Test
    fun aFreshPictureShowsTheLabelOnlyWhileTheAppIsOnScreen() {
        val live = playing().snapshot(ms(300))
        assertEquals(ViewerStaleUiPlan(ViewerBadgeKind.NONE, showLabel = true), ViewerStaleUiPlan.of(live, true))
        assertEquals(ViewerStaleUiPlan(ViewerBadgeKind.NONE, showLabel = false), ViewerStaleUiPlan.of(live, false))
    }

    @Test
    fun waitingPausedAndEndedDrawNothing() {
        val nothing = ViewerStaleUiPlan(ViewerBadgeKind.NONE, showLabel = false)
        assertEquals(nothing, ViewerStaleUiPlan.of(viewing().snapshot(ms(5_000)), true))
        assertEquals(nothing, ViewerStaleUiPlan.of(playing().also { it.onPaused(ms(10)) }.snapshot(ms(5_000)), true))
        assertEquals(nothing, ViewerStaleUiPlan.of(playing().also { it.onEnded(ms(10)) }.snapshot(ms(5_000)), true))
    }

    @Test
    fun ageTextRoundsDownAndNeverReadsAhead() {
        assertEquals("<1 s", ViewerFrameAgeText.age(-5))
        assertEquals("<1 s", ViewerFrameAgeText.age(0))
        assertEquals("<1 s", ViewerFrameAgeText.age(999))
        assertEquals("1.0 s", ViewerFrameAgeText.age(1_000))
        assertEquals("3.2 s", ViewerFrameAgeText.age(3_299))
        assertEquals("59.9 s", ViewerFrameAgeText.age(59_999))
        assertEquals("1 min 05 s", ViewerFrameAgeText.age(65_000))
        assertEquals("12 min 00 s", ViewerFrameAgeText.age(720_000))
    }
}
