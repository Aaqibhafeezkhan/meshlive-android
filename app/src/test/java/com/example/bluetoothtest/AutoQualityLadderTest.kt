package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoQualityLadderTest {
    private val av1 = StreamProtocol.CODEC_AV1
    private val vp9 = StreamProtocol.CODEC_VP9
    private val standard = AutoEncoderClass.STANDARD

    private fun label(link: Int?, codec: Int = av1, cls: AutoEncoderClass = standard, audio: Int = 50) =
        AutoQualityLadder.plan(link, codec, cls, audio).rung?.label ?: "audio_only"

    // ---- wire model -------------------------------------------------------------------------

    @Test
    fun videoBudgetFollowsTheWireModel() {
        assertEquals(180, AutoQualityLadder.videoBudgetKbps(300, 15))
        assertEquals(101, AutoQualityLadder.videoBudgetKbps(200, 15))
        assertEquals(299, AutoQualityLadder.videoBudgetKbps(450, 15))
        assertEquals(496, AutoQualityLadder.videoBudgetKbps(700, 15))
        assertEquals(728, AutoQualityLadder.videoBudgetKbps(1000, 24))
        assertEquals(0, AutoQualityLadder.videoBudgetKbps(40, 15))
        // Opus 16 leaves 16 kbps more than Opus 32.
        assertTrue(
            AutoQualityLadder.videoBudgetKbps(100, 10, AutoQualityLadder.OPUS16_WIRE_KBPS) >
                AutoQualityLadder.videoBudgetKbps(100, 10),
        )
    }

    @Test
    fun wireModelMatchesTheHciSnoopPoint() {
        // S24 -> M31, 640x480/15/300 kbps with audio: modelled 386 kbps, measured 360-392.
        val wire = AutoQualityLadder.wireKbps(300, 15, 50)
        assertTrue("wire=$wire", wire in 360.0..392.0)
    }

    @Test
    fun audioWireInterpolatesBetweenTheOpusRates() {
        assertEquals(34, AutoQualityLadder.audioWireKbps(16_000))
        assertEquals(50, AutoQualityLadder.audioWireKbps(32_000))
        assertEquals(42, AutoQualityLadder.audioWireKbps(24_000))
        assertEquals(34, AutoQualityLadder.audioWireKbps(8_000))
        assertEquals(50, AutoQualityLadder.audioWireKbps(64_000))
    }

    // ---- every rung of the approved ladder (AV1, standard encoder) ---------------------------

    @Test
    fun linkSelectsTheApprovedRungAv1() {
        assertEquals("audio_only", label(60))
        assertEquals("audio_only", label(100)) // Opus 32 leaves 26 kbps
        assertEquals("240p@10", label(100, audio = AutoQualityLadder.OPUS16_WIRE_KBPS))
        assertEquals("240p@12", label(150))
        assertEquals("240p@15", label(200))
        assertEquals("360p@15", label(300))
        assertEquals("360p@15", label(450))
        assertEquals("480p@15", label(700))
        assertEquals("480p@24", label(1000))
        assertEquals("480p@30", label(1_300))
    }

    @Test
    fun linkSelectsTheApprovedRungVp9() {
        assertEquals("audio_only", label(60, vp9))
        assertEquals("240p@12", label(150, vp9))
        assertEquals("240p@15", label(200, vp9))
        assertEquals("360p@15", label(300, vp9))
        assertEquals("360p@15", label(450, vp9))
        assertEquals("480p@15", label(700, vp9))
        // 24 FPS needs 850 kbps of VP9 video: the 1000 kbps link stays at 480p15 (quality).
        assertEquals("480p@15", label(1000, vp9))
        assertEquals("480p@24", label(1_200, vp9))
        assertEquals("480p@30", label(1_600, vp9))
    }

    @Test
    fun rungBoundariesAreExact() {
        // AV1 360p15 needs 150 kbps of video: link 261 (150) enters, link 260 (149) does not.
        assertEquals(150, AutoQualityLadder.videoBudgetKbps(261, 15))
        assertEquals("360p@15", label(261))
        assertEquals(149, AutoQualityLadder.videoBudgetKbps(260, 15))
        assertEquals("240p@15", label(260))
        // VP9 360p15 needs 175: link 290 carries 173, link 295 carries 177.
        assertEquals("240p@15", label(290, vp9))
        assertEquals("360p@15", label(295, vp9))
        // 480p24 AV1 needs 700 at 24 FPS.
        val enters = (800..1100).first { AutoQualityLadder.videoBudgetKbps(it, 24) >= 700 }
        assertEquals("480p@24", label(enters))
        assertEquals("480p@15", label(enters - 1))
    }

    @Test
    fun planReportsVideoWireAndAssumedLink() {
        val plan = AutoQualityLadder.plan(300, av1, standard)
        assertEquals(180, plan.videoKbps)
        assertFalse(plan.linkAssumed)
        assertTrue(plan.wireKbps <= 0.85 * 300 + 1)
        assertFalse(plan.audioOnly)
        assertTrue(plan.log().contains("rung=360p@15"))
    }

    @Test
    fun unmeasuredLinkStartsAtThreeSixtyP15NotTheOldDefault() {
        for (codec in listOf(av1, vp9)) {
            val plan = AutoQualityLadder.plan(null, codec, standard)
            assertEquals("360p@15", plan.rung?.label)
            assertTrue(plan.linkAssumed)
            assertEquals(AutoQualityLadder.ASSUMED_LINK_KBPS, plan.linkKbps)
        }
        assertTrue(AutoQualityLadder.plan(0, av1, standard).linkAssumed)
    }

    @Test
    fun audioOnlyPlanHasNoVideo() {
        val plan = AutoQualityLadder.plan(60, av1, standard)
        assertTrue(plan.audioOnly)
        assertNull(plan.rung)
        assertEquals(0, plan.videoKbps)
    }

    @Test
    fun maximumVideoCapsTheRateAndExcludesRungsAboveIt() {
        val capped = AutoQualityLadder.plan(1_500, av1, standard, maxVideoKbps = 300)
        assertEquals("360p@15", capped.rung?.label) // 480p15 needs 430
        assertEquals(300, capped.videoKbps)
        val uncapped = AutoQualityLadder.plan(1_500, av1, standard)
        assertTrue(uncapped.videoKbps <= VideoSettings.MAX_BIT_RATE_KBPS)
    }

    @Test
    fun videoIsAMultipleOfFiveKbpsAndNeverBelowTheRungMinimum() {
        for (link in 60..2_000 step 7) for (codec in listOf(av1, vp9)) {
            val plan = AutoQualityLadder.plan(link, codec, standard)
            val rung = plan.rung ?: continue
            assertEquals(0, plan.videoKbps % 5)
            assertTrue("link=$link ${rung.label} video=${plan.videoKbps}", plan.videoKbps >= rung.minVideoKbps)
        }
    }

    // ---- codec and encoder-class variants ------------------------------------------------------

    @Test
    fun strongAv1EncoderTakes720pAt15InsteadOf24And30Fps() {
        assertEquals("720p@15", label(700, av1, AutoEncoderClass.STRONG))
        assertEquals("720p@15", label(1_000, av1, AutoEncoderClass.STRONG))
        assertEquals("720p@15", label(1_500, av1, AutoEncoderClass.STRONG))
        // Below the 720p entry the ladder is the standard one.
        assertEquals("360p@15", label(450, av1, AutoEncoderClass.STRONG))
        assertEquals("480p@15", label(640, av1, AutoEncoderClass.STRONG))
        // The standard class never offers 720p.
        for (link in 60..3_000 step 13) assertFalse(label(link).startsWith("720p"))
    }

    @Test
    fun strongClassOnVp9StaysOnTheVp9Table() {
        for (link in 60..2_000 step 11) {
            assertEquals(label(link, vp9, standard), label(link, vp9, AutoEncoderClass.STRONG))
        }
    }

    @Test
    fun weakAv1CpuUsesTheVp9Thresholds() {
        for (link in 60..2_000 step 11) {
            assertEquals(label(link, vp9), label(link, av1, AutoEncoderClass.WEAK))
        }
        assertEquals("240p@15", label(290, av1, AutoEncoderClass.WEAK))
    }

    @Test
    fun encoderClassFromMeasuredEncodeMargin() {
        assertEquals(AutoEncoderClass.STRONG, AutoQualityLadder.encoderClass(1.82))
        assertEquals(AutoEncoderClass.STRONG, AutoQualityLadder.encoderClass(1.5))
        assertEquals(AutoEncoderClass.STANDARD, AutoQualityLadder.encoderClass(1.19)) // S22 warm
        assertEquals(AutoEncoderClass.STANDARD, AutoQualityLadder.encoderClass(null))
        assertEquals(AutoEncoderClass.WEAK, AutoQualityLadder.encoderClass(2.0, slowAv1Cpu = true))
    }

    @Test
    fun callPlanGivesEachDirectionHalfOfTheMeasuredLink() {
        val plan = AutoQualityLadder.callPlan(700, av1, standard, 32_000, 1_200)
        assertEquals(350, plan.linkKbps)
        assertEquals("360p@15", plan.rung?.label)
        assertEquals(220, plan.videoKbps)
        assertTrue(AutoQualityLadder.callPlan(null, av1, standard, 32_000, 1_200).linkAssumed)
        // A weak 120 kbps pair (60 per direction) is audio only.
        assertTrue(AutoQualityLadder.callPlan(120, av1, standard, 32_000, 1_200).audioOnly)
    }

    // ---- order of degradation ---------------------------------------------------------------------

    @Test
    fun rungsAreStrictlyAscendingInVideoNeed() {
        for (codec in listOf(av1, vp9)) for (cls in AutoEncoderClass.entries) {
            val rungs = AutoQualityLadder.rungs(codec, cls)
            assertEquals(rungs.sortedBy { it.minVideoKbps }, rungs)
            assertEquals(rungs.map { it.minVideoKbps }.distinct(), rungs.map { it.minVideoKbps })
        }
    }

    @Test
    fun framesRateIsCutBeforeResolutionAbove15AndResolutionBeforeFrameRateBelow() {
        for (codec in listOf(av1, vp9)) for (cls in AutoEncoderClass.entries) {
            val rungs = AutoQualityLadder.rungs(codec, cls)
            val largest = rungs.maxOf { it.pixels }
            // 24 and 30 FPS exist only at the largest size: dropping from them first lowers the frame rate to 15.
            rungs.filter { it.fps > 15 }.forEach { assertEquals(largest, it.pixels) }
            // At 15 FPS every smaller size is entered before any lower frame rate except at 240p.
            val below15 = rungs.filter { it.fps < 15 }
            below15.forEach { assertEquals(426L * 240, it.pixels) }
            // The rung below 15 FPS is always the smallest picture: shrink to 240p, then lose frames.
            assertEquals(rungs.minOf { it.pixels }, below15.first().pixels)
        }
    }

    @Test
    fun risingLinkNeverLowersTheRung() {
        for (codec in listOf(av1, vp9)) for (cls in AutoEncoderClass.entries) {
            val order = AutoQualityLadder.rungs(codec, cls)
            var previous = -1
            for (link in 5..3_000 step 5) {
                val rung = AutoQualityLadder.plan(link, codec, cls, maxVideoKbps = 5_000).rung
                val index = rung?.let(order::indexOf) ?: -1
                assertTrue("link=$link codec=$codec cls=$cls", index >= previous)
                previous = index
            }
        }
    }

    @Test
    fun noThirtyFpsBelowTheFittedMinimum() {
        // 480p30 needs 930 (AV1) / 1190 (VP9) kbps of video.
        assertEquals("480p@24", label(1_200, av1)) // 24 FPS video budget there is below the 30 FPS need
        assertEquals(930, AutoQualityLadder.rungs(av1, standard).last().minVideoKbps)
        assertEquals(1_190, AutoQualityLadder.rungs(vp9, standard).last().minVideoKbps)
    }

    // ---- adaptation thresholds -------------------------------------------------------------------

    @Test
    fun minimumVideoKbpsUsesTheTableThenScalesWithFpsToThePointFiveFive() {
        assertEquals(150, AutoQualityLadder.minVideoKbps(av1, standard, 640, 360, 15))
        assertEquals(175, AutoQualityLadder.minVideoKbps(vp9, standard, 640, 360, 15))
        assertEquals(700, AutoQualityLadder.minVideoKbps(av1, standard, 854, 480, 24))
        assertEquals(95, AutoQualityLadder.minVideoKbps(av1, standard, 426, 240, 15))
        assertEquals(0, AutoQualityLadder.minVideoKbps(av1, standard, 320, 180, 15))
        val scaled = AutoQualityLadder.minVideoKbps(av1, standard, 640, 360, 24)
        assertTrue("360p24=$scaled", scaled in 185..200)
        // 720p without margin evidence keeps the WebRTC limits.
        assertEquals(576, AutoQualityLadder.minVideoKbps(av1, standard, 1280, 720, 15))
        assertEquals(480, AutoQualityLadder.minVideoKbps(av1, AutoEncoderClass.STRONG, 1280, 720, 15))
        assertEquals(480, AutoQualityLadder.minVideoKbps(vp9, standard, 1280, 720, 15))
    }

    @Test
    fun callLimitsReplaceTheWebRtcThreeSixtyPLimit() {
        val measured = AutoQualityLadder.callLimits(av1, standard, 15)
        assertEquals(listOf(57_600L, 129_600L, 230_400L, 518_400L, 921_600L, 2_073_600L), measured.map { it.first })
        val av1Limits = measured.map { it.second }
        assertEquals(listOf(0, 0, 150_000, 430_000, 576_000, 1_000_000), av1Limits)
        // WebRTC's AV1 640x360 entry was 256 kbps, the VP9 one 190: the measured crossover is 150 / 175.
        assertEquals(256_000, CallResolutionLadder.minStartBps(av1, VideoResolution(640, 360, "360p")))
        assertEquals(150_000, CallResolutionLadder.minStartBps(av1, VideoResolution(640, 360, "360p"), measured))
        assertEquals(
            175_000,
            CallResolutionLadder.minStartBps(vp9, VideoResolution(640, 360, "360p"), AutoQualityLadder.callLimits(vp9, standard, 15)),
        )
    }

    @Test
    fun fpsFollowsTheLadderBelowFifteen() {
        fun fps(kbps: Int, header: Int = 15) =
            AutoQualityLadder.fpsForVideoKbps(av1, standard, 854, 480, header, kbps)
        assertEquals(15, fps(500))
        assertEquals(15, fps(95))
        assertEquals(12, fps(94))
        assertEquals(12, fps(60))
        assertEquals(10, fps(59))
        assertEquals(10, fps(0))
        // A 24 FPS header keeps 24 only while the 480p24 entry is met, then falls to 15 and below.
        assertEquals(24, fps(700, 24))
        assertEquals(15, fps(699, 24))
        assertEquals(15, fps(300, 24))
        assertEquals(12, fps(70, 24))
        // Never above the header.
        assertEquals(12, AutoQualityLadder.fpsForVideoKbps(av1, standard, 426, 240, 12, 500))
    }

    @Test
    fun headerIsTheMaximumTheClassMayUse() {
        fun header(codec: Int, cls: AutoEncoderClass, link: Int?) =
            AutoQualityLadder.callHeader(codec, cls, AutoQualityLadder.plan(link, codec, cls)).let {
                "${it.first.width}x${it.first.height}@${it.second}"
            }
        assertEquals("854x480@15", header(av1, standard, 300))
        assertEquals("854x480@15", header(av1, standard, 100))
        assertEquals("854x480@24", header(av1, standard, 1_000))
        assertEquals("854x480@30", header(av1, standard, 1_300))
        assertEquals("1280x720@15", header(av1, AutoEncoderClass.STRONG, 300))
        assertEquals("854x480@15", header(vp9, standard, 300))
    }

    // ---- picker guard: manual requests are classified, never changed ----------------------------

    @Test
    fun starvedCombinationsAreFlaggedAndHealthyOnesAreNot() {
        // 854x480 at 30 FPS and 200 kbps (VP9): 0.019 against a 0.065 floor.
        val starved = AutoQualityLadder.starvation(vp9, 854, 480, 30, 200)
        assertEquals(StarvationLevel.STARVED, starved.level)
        assertTrue("bppf=${starved.bppfEq}", starved.bppfEq in 0.018..0.021)
        assertTrue("minimum=${starved.minimumKbps}", starved.minimumKbps in 660..690)
        // 480p24 at 300 kbps is below the floor but not half of it.
        assertEquals(StarvationLevel.WARN, AutoQualityLadder.starvation(vp9, 854, 480, 24, 300).level)
        assertEquals(StarvationLevel.WARN, AutoQualityLadder.starvation(vp9, 640, 360, 15, 200).level)
        // The historic 240p/15/200 default and 360p/15/300 are fine.
        assertEquals(StarvationLevel.OK, AutoQualityLadder.starvation(vp9, 426, 240, 15, 200).level)
        assertEquals(StarvationLevel.OK, AutoQualityLadder.starvation(vp9, 640, 360, 15, 300).level)
        assertEquals(StarvationLevel.OK, AutoQualityLadder.starvation(av1, 640, 360, 15, 250).level)
    }

    @Test
    fun theFloorIsEnteredExactlyAtTheMinimum() {
        val check = AutoQualityLadder.starvation(vp9, 640, 360, 15, 100)
        assertEquals(StarvationLevel.STARVED, check.level)
        val at = AutoQualityLadder.starvation(vp9, 640, 360, 15, check.minimumKbps)
        assertEquals(StarvationLevel.OK, at.level)
        val below = AutoQualityLadder.starvation(vp9, 640, 360, 15, check.minimumKbps - 2)
        assertTrue(below.level != StarvationLevel.OK)
    }

    @Test
    fun warningTextNamesTheRequestAndTheMinimumAndOkHasNone() {
        val starved = AutoQualityLadder.starvation(vp9, 854, 480, 30, 200)
        val text = AutoQualityLadder.starvationWarning(starved, 854, 480, 30)
        assertNotNull(text)
        assertTrue(text!!.contains("854x480") && text.contains("30 FPS") && text.contains("${starved.minimumKbps} kbps"))
        val ok = AutoQualityLadder.starvation(vp9, 640, 360, 15, 300)
        assertNull(AutoQualityLadder.starvationWarning(ok, 640, 360, 15))
    }

    @Test
    fun theGuardIsAClassificationOnlyAndHasNoSettingsToChange() {
        // The check takes primitives and returns data: a manual request cannot be rewritten through it.
        val before = VideoSettings(VideoResolution(854, 480, "480p"), 30, 200_000, 500)
        AutoQualityLadder.starvation(vp9, before.resolution.width, before.resolution.height, before.fps, before.bitRate / 1_000)
        assertEquals(VideoSettings(VideoResolution(854, 480, "480p"), 30, 200_000, 500), before)
    }

    // ---- switch --------------------------------------------------------------------------------

    @Test
    fun switchIsOffByDefaultAndDebugOnly() {
        assertFalse(AutoQualityFeature.enabled)
        assertFalse(AutoQualityFeature.resolve(debugBuild = true, extraValue = false))
        assertFalse(AutoQualityFeature.resolve(debugBuild = false, extraValue = true))
        assertTrue(AutoQualityFeature.resolve(debugBuild = true, extraValue = true))
        assertEquals(AutoEncoderClass.STRONG, AutoQualityFeature.parseClass("strong"))
        assertEquals(AutoEncoderClass.WEAK, AutoQualityFeature.parseClass("WEAK"))
        assertNull(AutoQualityFeature.parseClass("other"))
        assertNull(AutoQualityFeature.parseClass(null))
    }

    // ---- the call / stream resolution ladder on the measured limits ---------------------------

    private fun r(w: Int, h: Int) = VideoResolution(w, h, "${h}p")
    private val p480 = r(854, 480)
    private val p360 = r(640, 360)
    private val p240 = r(426, 240)
    private val p180 = r(320, 180)
    private val p720 = r(1280, 720)
    private val offered = listOf(p720, p480, p360, p240, p180, r(256, 144), r(640, 480))

    private fun ladder(codec: Int = av1, header: VideoResolution = p480, cls: AutoEncoderClass = standard) =
        CallResolutionLadder(
            header, offered, codec,
            limits = AutoQualityLadder.callLimits(codec, cls, 15),
            downHoldMs = AutoQualityLadder.AUTO_DOWN_HOLD_MS,
        )

    @Test
    fun ladderRungsInsideTheHeaderAreTheMeasuredSizes() {
        // 4:3 and sub-floor modes are excluded; 480p, 360p and 240p remain (180p shares nothing below the 240p floor).
        assertEquals(listOf(p480, p360, p240, p180), ladder().rungs)
    }

    @Test
    fun startPicksTheLargestRungTheStartRateSupports() {
        assertEquals(p480, ladder().start(450_000, 0).resolution)
        assertEquals(p360, ladder().start(300_000, 0).resolution)
        assertEquals(p360, ladder().start(150_000, 0).resolution)
        assertEquals(p240, ladder().start(149_000, 0).resolution)
        // Below the 240p entry the picture stays 240p (the frame rate falls instead).
        assertEquals(p240, ladder().start(50_000, 0).resolution)
        assertEquals(p240, ladder().start(40_000, 0).resolution)
    }

    @Test
    fun vp9ThreeSixtyPNeeds175NotWebRtc190() {
        assertEquals(p240, ladder(vp9).also { it.start(174_000, 0) }.current)
        assertEquals(p360, ladder(vp9).also { it.start(175_000, 0) }.current)
    }

    @Test
    fun ladderMovesAboveTheStartRungInsideTheHeader() {
        val l = ladder()
        assertEquals(p240, l.start(120_000, 0).resolution) // starts low
        // 360p needs 150 x 1.25 = 187.5 kbps for 10 s and 15 s since the start.
        assertNull(l.onTarget(180_000, 20_000)) // under the headroom
        assertNull(l.onTarget(190_000, 21_000))
        assertNull(l.onTarget(190_000, 30_999)) // hold not over
        assertEquals(p360, l.onTarget(190_000, 31_000)?.resolution)
        // Then one rung at a time: 480p needs 430 x 1.25 = 537.5 kbps.
        assertNull(l.onTarget(500_000, 32_000))
        assertNull(l.onTarget(540_000, 40_000))
        assertNull(l.onTarget(540_000, 49_999))
        assertEquals(p480, l.onTarget(540_000, 50_000)?.resolution)
    }

    @Test
    fun anUpwardStepNeverSkipsARungEvenWithAHugeTarget() {
        val l = ladder()
        l.start(100_000, 0)
        assertNull(l.onTarget(2_000_000, 1_000))
        assertEquals(p360, l.onTarget(2_000_000, 16_000)?.resolution)
        assertNull(l.onTarget(2_000_000, 17_000))
        assertEquals(p480, l.onTarget(2_000_000, 32_000)?.resolution)
    }

    @Test
    fun stepsDownAfterTheAutoHoldAndJumpsToTheRungTheTargetSupports() {
        val l = ladder()
        l.start(450_000, 0)
        assertNull(l.onTarget(120_000, 10_000))
        assertNull(l.onTarget(120_000, 11_499))
        val down = l.onTarget(120_000, 11_500)
        assertEquals(p240, down?.resolution)
        assertTrue(down!!.reason.startsWith("weak_link(targetKbps=120"))
    }

    @Test
    fun aBriefDipDoesNotStepDownAndARecoveryResetsTheHold() {
        val l = ladder()
        l.start(450_000, 0)
        assertNull(l.onTarget(300_000, 10_000))
        assertNull(l.onTarget(450_000, 10_500)) // recovered inside the hold
        assertNull(l.onTarget(300_000, 11_000))
        assertNull(l.onTarget(300_000, 12_400))
        assertEquals(p360, l.onTarget(300_000, 12_500)?.resolution)
    }

    @Test
    fun changesAreSpacedByTheMinimumIntervalSoItCannotOscillate() {
        val l = ladder()
        l.start(450_000, 0)
        assertEquals(p360, l.onTarget(300_000, 6_000).let { l.onTarget(300_000, 7_500) }?.resolution)
        // Recovering right away must wait for the up hold and the 15 s since the last change.
        assertNull(l.onTarget(600_000, 8_000))
        assertNull(l.onTarget(600_000, 18_000))
        assertNull(l.onTarget(600_000, 21_000))
        assertEquals(p480, l.onTarget(600_000, 22_500)?.resolution)
    }

    @Test
    fun strongHeaderReachesSevenTwentyPOnlyWithTheStrongLimits() {
        val l = ladder(av1, p720, AutoEncoderClass.STRONG)
        assertEquals(listOf(p720, p480, p360, p240, p180), l.rungs)
        assertEquals(p480, l.start(479_000, 0).resolution)
        assertEquals(p720, l.start(480_000, 0).resolution)
    }

    @Test
    fun webRtcTableIsUntouchedWhenNoLimitsAreGiven() {
        val plain = CallResolutionLadder(p480, offered, av1)
        assertEquals(256_000, CallResolutionLadder.minStartBps(av1, p360))
        assertEquals(p240, plain.start(255_000, 0).resolution)
        assertEquals(p360, plain.start(256_000, 0).resolution)
        assertEquals(3_000L, CallResolutionLadder.DOWN_HOLD_MS)
    }

    // ---- frame rate curve in the rate controller ---------------------------------------------

    @Test
    fun frameRateCurveReplacesTheProportionalFloorRule() {
        val ladder = FrameRateLadder(
            15, 10, floorBps = 60_000,
            curve = { bps -> AutoQualityLadder.fpsForVideoKbps(av1, standard, 854, 480, 15, bps / 1_000) },
        )
        val controller = LinkRateController(1_100_000, frameRateLadder = ladder)
        assertEquals(15, controller.frameRateFor(500_000))
        assertEquals(15, controller.frameRateFor(95_000))
        assertEquals(12, controller.frameRateFor(80_000))
        assertEquals(10, controller.frameRateFor(45_000))
        assertEquals(10, controller.frameRateFor(1_000)) // clamped to the minimum
        // The old rule (no curve) is unchanged: proportional to the floor.
        val old = LinkRateController(1_100_000, frameRateLadder = FrameRateLadder(15, 5, 100_000))
        assertEquals(15, old.frameRateFor(100_000))
        assertEquals(7, old.frameRateFor(50_000))
    }

    // ---- first phone A/B (build 0d7e07b5, ASUS to S24 GATT stream) -------------------------------

    private fun mode(w: Int, h: Int, vararg fps: Int) = DeviceVideoMode(VideoResolution(w, h, "${h}p"), fps.toList(), null)

    /** The ASUS c2.android.vp9.encoder camera modes (benchmarks/catalog/capabilities/asus.json). */
    private val asusModes = listOf(
        mode(176, 144, 15, 30), mode(320, 240, 15, 30), mode(352, 288, 15, 30),
        mode(640, 480, 15, 30), mode(720, 480, 15, 30), mode(1280, 720, 15, 30),
    )

    private fun request(w: Int, h: Int, fps: Int = 15, kbps: Int = 1_037) =
        VideoSettings(VideoResolution(w, h, "${h}p"), fps, kbps * 1_000, 500)

    @Test
    fun aPhoneWithoutSixteenByNineModesGetsAnAspectGroupThatHasRungsNotALoneThreeByTwoMode() {
        // The A/B mapped the 854x480 header to 720x480 (the largest mode under it): the only 3:2 mode, so the
        // call ladder logged no_smaller_rung and the stream stayed at 720x480 while its target fell to 91 kbps.
        val fitted = AutoQualityLadder.fitSettings(request(854, 480), asusModes)
        assertEquals(VideoResolution(640, 480, "480p"), fitted.resolution)
        assertEquals(15, fitted.fps)
        assertEquals(1_037_000, fitted.bitRate)
        val ladder = CallResolutionLadder(
            fitted.resolution, asusModes.map { it.resolution }, vp9,
            limits = AutoQualityLadder.callLimits(vp9, standard, 15),
            startLimit = AutoQualityLadder.callStartLimit(vp9, standard, 15),
        )
        assertTrue(ladder.active)
        assertEquals(listOf(640 to 480, 320 to 240), ladder.rungs.map { it.width to it.height })
        // The old mapping: a lone 720x480 has nothing to step to.
        assertFalse(CallResolutionLadder(VideoResolution(720, 480, "480p"), asusModes.map { it.resolution }, vp9).active)
    }

    @Test
    fun aSixteenByNineDeviceKeepsItsAspectAndAnExactModeIsKept() {
        val modes = listOf(mode(426, 240, 15), mode(640, 360, 15), mode(854, 480, 15), mode(1280, 720, 15), mode(640, 480, 15), mode(320, 240, 15))
        assertEquals(request(854, 480), AutoQualityLadder.fitSettings(request(854, 480), modes))
        // Without 854x480 the 16:9 pair 640x360 / 426x240 beats the 4:3 pair with a larger size.
        val without = modes.filter { it.resolution.width != 854 }
        assertEquals(VideoResolution(640, 360, "360p"), AutoQualityLadder.fitSettings(request(854, 480), without).resolution)
    }

    @Test
    fun fitUsesTheLargestSizeOfTheChosenGroupAndTheFpsTheModeOffers() {
        val modes = listOf(mode(640, 480, 10, 24), mode(320, 240, 10, 24))
        val fitted = AutoQualityLadder.fitSettings(request(854, 480, fps = 30), modes)
        assertEquals(VideoResolution(640, 480, "480p"), fitted.resolution)
        assertEquals(24, fitted.fps)
        // Nothing under the target: the smallest mode.
        assertEquals(
            VideoResolution(1280, 720, "720p"),
            AutoQualityLadder.fitSettings(request(640, 360), listOf(mode(1920, 1080, 15), mode(1280, 720, 15))).resolution,
        )
    }

    @Test
    fun offGridSizesNeedTheirOwnPixelCountNotTheNextBucket() {
        // 640x480 is 25% under 854x480: VP9 needs about 294 kbps, not the 330 of 480p.
        val vp9Need = AutoQualityLadder.minVideoKbps(vp9, standard, 640, 480, 15)
        assertTrue("vp9 640x480=$vp9Need", vp9Need in 290..298)
        assertEquals(330, AutoQualityLadder.minVideoKbps(vp9, standard, 854, 480, 15))
        val av1Need = AutoQualityLadder.minVideoKbps(av1, standard, 640, 480, 15)
        assertTrue("av1 640x480=$av1Need", av1Need in 380..386)
        assertTrue(AutoQualityLadder.minVideoKbps(vp9, standard, 720, 480, 15) in 305..312)
        // Never decreasing with pixel count.
        val sizes = listOf(176 to 144, 320 to 240, 352 to 288, 426 to 240, 480 to 360, 640 to 360, 640 to 480, 720 to 480, 854 to 480, 1280 to 720, 1920 to 1080)
            .sortedBy { it.first * it.second }
        for (codec in listOf(av1, vp9)) {
            val needs = sizes.map { AutoQualityLadder.minVideoKbps(codec, standard, it.first, it.second, 15) }
            assertEquals(needs.sorted(), needs)
        }
        // Table sizes are unchanged by the scaling.
        assertEquals(150, AutoQualityLadder.minVideoKbps(av1, standard, 640, 360, 15))
        assertEquals(95, AutoQualityLadder.minVideoKbps(av1, standard, 426, 240, 15))
    }

    @Test
    fun theStartLimitPutsA300KbpsVp9StartAtFourByThreeVgaAndWeakerLinksAtQvga() {
        val ladder = CallResolutionLadder(
            VideoResolution(640, 480, "480p"), asusModes.map { it.resolution }, vp9,
            limits = AutoQualityLadder.callLimits(vp9, standard, 15),
            startLimit = AutoQualityLadder.callStartLimit(vp9, standard, 15),
        )
        assertEquals(640, ladder.start(300_000, 0).resolution.width)
        assertEquals(320, ladder.start(250_000, 0).resolution.width)
        // 240p and below have no limit: the frame rate falls instead.
        assertEquals(0, AutoQualityLadder.callStartLimit(vp9, standard, 15)(VideoResolution(320, 240, "240p")))
    }

    // ---- climb step: a request used only as a bound must not make one step a third of the target ------

    private class FixedStart(private val bps: Int) : SustainedRateMemory {
        override fun startBps(peer: String): Int? = bps
        override fun record(peer: String, bps: Int) = Unit
    }

    private fun climbAfterThreeCleanWindows(smallClimbSteps: Boolean): Int? {
        val controller = LinkRateController(
            1_037_000,
            memory = FixedStart(300_000),
            frameRateLadder = FrameRateLadder(15, 10, floorBps = 60_000),
            smallClimbSteps = smallClimbSteps,
        )
        var changed: Int? = null
        for (i in 0 until 3) {
            // 300 kbps video + audio carried: loaded and clean (offered 40 KB per second).
            changed = controller.onSample("p", LinkRateSample(1_000_000_000L, 40_000, 40_000, 0, false, peer = "p"), i * 1_000_000_000L)
        }
        return changed
    }

    @Test
    fun theFirstClimbOfAnAutoStreamIsATenthOfTheTargetNotOfTheBound() {
        // Before: 300 -> 403 kbps (a tenth of the 1037 kbps bound) on a link that carried about 170.
        assertEquals(403_700, climbAfterThreeCleanWindows(smallClimbSteps = false))
        assertEquals(330_000, climbAfterThreeCleanWindows(smallClimbSteps = true))
    }

    @Test
    fun smallClimbStepsNeverExceedTheOldStepAndStillClimb() {
        fun climb(request: Int, start: Int, small: Boolean): Int? {
            val controller = LinkRateController(
                request, memory = FixedStart(start), frameRateLadder = FrameRateLadder(15, 10, floorBps = 60_000),
                smallClimbSteps = small,
            )
            var changed: Int? = null
            for (i in 0 until 3) {
                val bytes = (start + 32_000) / 8L
                changed = controller.onSample("p", LinkRateSample(1_000_000_000L, bytes, bytes, 0, false, peer = "p"), i * 1_000_000_000L)
            }
            return changed
        }
        for ((request, start) in listOf(300_000 to 270_000, 600_000 to 300_000, 1_037_000 to 150_000, 1_037_000 to 600_000)) {
            val old = climb(request, start, small = false)!!
            val small = climb(request, start, small = true)!!
            assertTrue("request=$request start=$start old=$old small=$small", small in (start + 1)..old)
        }
    }

    // ---- evidence start (second phone A/B follow-up): probe is a burst rate, not goodput -----------------

    private fun evidence(probe: Int?, phy: Int?, sustained: Int?, codec: Int = vp9) =
        AutoQualityLadder.evidencePlan(probe, phy, sustained, codec, standard)

    @Test
    fun aProbeAloneIsAColdStartOfThirtyPercentCappedAt140KbpsAt240p() {
        // The A/B pair: probe 1074 plans 480p15 at 790 kbps; the link carried about 170.
        val plain = AutoQualityLadder.plan(1_074, vp9, standard)
        assertEquals("480p@15", plain.rung?.label)
        assertEquals(790, plain.videoKbps)
        val plan = evidence(1_074, null, null)
        assertEquals("240p@15", plan.rung?.label)
        assertEquals(140, plan.videoKbps) // 30% of 790 is 237: the hard ceiling applies
        assertEquals("probe_only", plan.evidence)
        assertTrue(plan.log().contains("evidence=probe_only"))
        // AV1 takes the same shape from its own table.
        assertEquals("240p@15", evidence(700, null, null, av1).rung?.label)
        assertEquals(140, evidence(700, null, null, av1).videoKbps)
    }

    @Test
    fun theOneMegabitPhyCeilingBoundsTheLinkOfAKnownOneMegabitPeer() {
        assertEquals(263, AutoQualityLadder.phyCeilingKbps(15.0))
        assertEquals(526, AutoQualityLadder.phyCeilingKbps(7.5))
        // ASUS to S24 on 1M at 15 ms: the 1074 kbps probe is capped at 263 and no extra haircut is taken.
        val vp9Plan = evidence(1_074, 263, null)
        assertEquals("240p@15", vp9Plan.rung?.label)
        assertEquals(150, vp9Plan.videoKbps)
        assertEquals("phy_bound", vp9Plan.evidence)
        assertEquals(263, vp9Plan.linkKbps)
        val av1Plan = evidence(1_074, 263, null, av1)
        assertEquals("360p@15", av1Plan.rung?.label)
        assertEquals(150, av1Plan.videoKbps)
        // A ceiling above the probe changes nothing.
        assertEquals(1_074, evidence(1_074, 5_000, null).linkKbps)
    }

    @Test
    fun deliveredRateCapsTheVideoAndTheRungDropsUntilItFits() {
        assertEquals(150, evidence(1_074, 263, 300).videoKbps) // remembered 300 > what the ceiling plans
        val capped = evidence(1_074, null, 120)
        assertEquals(120, capped.videoKbps)
        assertEquals("240p@15", capped.rung?.label)
        assertEquals("delivered", capped.evidence)
        // Delivered 280 on a 790 plan: 360p15 (175) fits, 480p15 (330) does not.
        val mid = evidence(1_074, null, 280)
        assertEquals("360p@15", mid.rung?.label)
        assertEquals(280, mid.videoKbps)
        // Never above the plan, and below the lowest entry it is audio only.
        assertEquals(790, evidence(1_074, null, 5_000).videoKbps)
        assertTrue(evidence(1_074, null, 30).audioOnly)
    }

    @Test
    fun anAssumedLinkKeepsTheApprovedStartAndAnAudioOnlyProbeStaysAudioOnly() {
        val assumed = evidence(null, null, null)
        assertEquals("360p@15", assumed.rung?.label)
        assertEquals("assumed", assumed.evidence)
        val weak = evidence(80, null, null)
        assertTrue(weak.audioOnly)
        assertEquals("probe_only", weak.evidence)
        // The lowest rung cannot go lower.
        val lowest = evidence(120, null, null) // 240p10 plan: nothing below it
        assertEquals("240p@10", lowest.rung?.label)
        assertEquals(40, lowest.videoKbps)
    }

    @Test
    fun theEvidenceStartNeverExceedsThePlainPlan() {
        for (link in 60..2_000 step 13) for (codec in listOf(av1, vp9)) for (phy in listOf<Int?>(null, 263, 526)) {
            for (sustained in listOf<Int?>(null, 100, 300, 2_000)) {
                val plain = AutoQualityLadder.plan(link, codec, standard)
                val plan = evidence(link, phy, sustained, codec)
                assertTrue("link=$link codec=$codec phy=$phy sustained=$sustained", plan.videoKbps <= plain.videoKbps)
                plan.rung?.let { assertTrue(it.minVideoKbps <= plan.videoKbps) }
            }
        }
    }

    // ---- controller: delivered evidence --------------------------------------------------------------------

    private class Recording(private val start: Int) : SustainedRateMemory {
        val records = mutableListOf<Int>()
        override fun startBps(peer: String): Int? = start
        override fun record(peer: String, bps: Int) {
            records += bps
        }
    }

    private fun filled(bytesPerSecond: Long) = LinkRateSample(1_000_000_000L, bytesPerSecond, bytesPerSecond, 0, false, peer = "p")

    private fun controller(memory: Recording, evidenceMode: Boolean) = LinkRateController(
        1_037_000, memory = memory, frameRateLadder = FrameRateLadder(15, 10, floorBps = 60_000),
        smallClimbSteps = evidenceMode, deliveredEvidence = evidenceMode,
    )

    @Test
    fun aWindowThatFillsOnlyHalfTheTargetNeitherClimbsNorTeachesTheMemoryUnderAuto() {
        // 60% of target + audio is a "loaded" window by the old rule; under Auto it must fill 80%.
        val sixtyFive = (300_000 + 32_000) * 65 / 100 / 8L
        val old = Recording(300_000)
        val oldController = controller(old, evidenceMode = false)
        val auto = Recording(300_000)
        val autoController = controller(auto, evidenceMode = true)
        var oldChange: Int? = null
        var autoChange: Int? = null
        for (i in 0 until 3) {
            oldChange = oldController.onSample("p", filled(sixtyFive), i * 1_000_000_000L)
            autoChange = autoController.onSample("p", filled(sixtyFive), i * 1_000_000_000L)
        }
        assertEquals(403_700, oldChange)
        assertNull(autoChange)
        assertTrue(auto.records.isEmpty())
    }

    @Test
    fun theRememberedRateIsWhatTheCleanWindowsCarriedNotTheTarget() {
        val auto = Recording(300_000)
        val autoController = controller(auto, evidenceMode = true)
        val bytes = (300_000 + 32_000) * 90 / 100 / 8L // 90% filled: loaded, clean
        var change: Int? = null
        for (i in 0 until 3) change = autoController.onSample("p", filled(bytes), i * 1_000_000_000L)
        assertEquals(330_000, change) // the target still climbs by a tenth
        // 37,350 B/s = 298.8 kbps carried, less 32 kbps audio = 266.8 kbps video, under the 300 kbps target.
        assertEquals(listOf(266_800), auto.records)
        val old = Recording(300_000)
        val oldController = controller(old, evidenceMode = false)
        for (i in 0 until 3) oldController.onSample("p", filled(bytes), i * 1_000_000_000L)
        assertEquals(listOf(300_000), old.records)
    }

    // ---- weak pair (A15s libaom, M31 hardware VP9): encoder load, fps hysteresis, audio first ---------------

    /** The A15s libaom took about 100 ms p95 for a 720x480 frame: 291 ms per megapixel (encode_load 1.5-1.9). */
    private val a15sMsPerMpx = 291.0

    @Test
    fun encodeLoadScalesWithPixelsAndFrameRate() {
        assertEquals(1.51, AutoQualityLadder.encodeLoad(a15sMsPerMpx, 720, 480, 15), 0.01)
        assertEquals(1.00, AutoQualityLadder.encodeLoad(a15sMsPerMpx, 640, 360, 15), 0.01)
        assertEquals(0.45, AutoQualityLadder.encodeLoad(a15sMsPerMpx, 426, 240, 15), 0.01)
        assertEquals(0.44, AutoQualityLadder.encodeLoad(a15sMsPerMpx, 352, 288, 15), 0.01)
        assertEquals(0.30, AutoQualityLadder.encodeLoad(a15sMsPerMpx, 426, 240, 10), 0.01)
    }

    @Test
    fun aCpuBoundSoftwareEncoderIsWeakAndAFastOneIsStrong() {
        assertEquals(AutoEncoderClass.WEAK, AutoQualityLadder.encoderClassFromLoad(a15sMsPerMpx))
        assertEquals(AutoEncoderClass.STANDARD, AutoQualityLadder.encoderClassFromLoad(100.0))
        assertEquals(AutoEncoderClass.STRONG, AutoQualityLadder.encoderClassFromLoad(40.0)) // 1.8x at 720p15
        assertNull(AutoQualityLadder.encoderClassFromLoad(null))
        assertNull(AutoQualityLadder.encoderClassFromLoad(0.0))
    }

    @Test
    fun theEncoderOnlyGetsRungsItCanEncodeInTime() {
        // AV1 on a weak CPU uses the VP9 table; of its rungs only 240p fits (360p15 is already load 1.0).
        val usable = AutoQualityLadder.usableRungs(av1, AutoEncoderClass.WEAK, a15sMsPerMpx)
        assertEquals(listOf("240p@10", "240p@12", "240p@15"), usable.map { it.label })
        assertEquals(AutoQualityLadder.rungs(vp9, standard), AutoQualityLadder.usableRungs(vp9, standard, null))
        // Nothing fits: the lowest sensible rung stays.
        assertEquals(listOf("240p@10"), AutoQualityLadder.usableRungs(vp9, standard, 2_000.0).map { it.label })
        // The plan on a 600 kbps link would be 480p15 or 360p15; the weak CPU gets 240p15.
        assertEquals("360p@15", AutoQualityLadder.plan(300, av1, standard).rung?.label)
        val weak = AutoQualityLadder.plan(300, av1, AutoEncoderClass.WEAK, encodeMsPerMpx = a15sMsPerMpx)
        assertEquals("240p@15", weak.rung?.label)
        assertEquals("240p@15", AutoQualityLadder.plan(1_000, av1, AutoEncoderClass.WEAK, encodeMsPerMpx = a15sMsPerMpx).rung?.label)
        val header = AutoQualityLadder.callHeader(av1, AutoEncoderClass.WEAK, weak, a15sMsPerMpx)
        assertEquals(426 to 240, header.first.width to header.first.height)
        assertEquals(15, header.second)
    }

    @Test
    fun anEvidenceStartOnASlowEncoderStaysOnItsOwnRungs() {
        val plan = AutoQualityLadder.evidencePlan(1_074, null, null, av1, AutoEncoderClass.WEAK, encodeMsPerMpx = a15sMsPerMpx)
        assertEquals("240p@15", plan.rung?.label)
        assertTrue(plan.videoKbps <= 140)
        assertEquals("240p@15", AutoQualityLadder.evidencePlan(1_074, 263, null, av1, AutoEncoderClass.WEAK, encodeMsPerMpx = a15sMsPerMpx).rung?.label)
    }

    @Test
    fun theCameraModeMappingSkipsSizesTheSoftwareEncoderCannotEncode() {
        val modes = listOf(mode(176, 144, 15), mode(320, 240, 15), mode(352, 288, 15), mode(640, 480, 15), mode(720, 480, 15))
        val free = AutoQualityLadder.fitSettings(request(854, 480), modes)
        assertEquals(VideoResolution(640, 480, "480p"), free.resolution)
        // 640x480 is load 1.34 and 720x480 1.51 at 15 FPS: the weak CPU maps to 352x288, as the control did.
        val weak = AutoQualityLadder.fitSettings(request(854, 480), modes, a15sMsPerMpx)
        assertEquals(VideoResolution(352, 288, "288p"), weak.resolution)
        // An exact request that does not fit is not kept.
        val exactModes = modes + mode(854, 480, 15)
        assertTrue(AutoQualityLadder.fitSettings(request(854, 480), exactModes, a15sMsPerMpx).resolution.width <= 352)
    }

    // ---- fps hysteresis ------------------------------------------------------------------------------------

    @Test
    fun theWeakPairTargetFlipOf40And150KbpsNoLongerFlipsTheFrameRate() {
        // call_fps lines of the A15s run: 10 -> 15 -> 10 -> 15 -> 10 ... every 1-20 s.
        val hysteresis = FpsHysteresis()
        val flips = listOf(
            0L to 10, 3_000L to 15, 4_000L to 10, 19_000L to 15, 20_000L to 10, 30_000L to 15, 31_000L to 10,
            42_000L to 15, 43_000L to 10,
        )
        for ((at, raw) in flips) assertEquals("at=$at", 10, hysteresis.update(raw, at))
    }

    @Test
    fun aRateIsRaisedOnlyAfterTenSecondsOfAskingOneLevelAtATime() {
        val h = FpsHysteresis()
        assertEquals(10, h.update(10, 0))
        // Asking for 15 steadily: held until 10 s of asking and 15 s since the last change, then 12, then 15.
        for (t in 1_000L..14_000L step 1_000L) assertEquals("t=$t", 10, h.update(15, t))
        assertEquals(12, h.update(15, 15_000))
        for (t in 16_000L..29_000L step 1_000L) assertEquals("t=$t", 12, h.update(15, t))
        assertEquals(15, h.update(15, 30_000))
    }

    @Test
    fun aRateIsLoweredAfterTwoSecondsOfAskingAndAnInterruptionResetsIt() {
        val h = FpsHysteresis()
        assertEquals(15, h.update(15, 0))
        assertEquals(15, h.update(10, 1_000))
        assertEquals(15, h.update(15, 2_500)) // back: the ask is dropped
        assertEquals(15, h.update(10, 3_000))
        assertEquals(15, h.update(10, 4_999))
        assertEquals(10, h.update(10, 5_000))
        // A drop to 12 then 10 follows the curve directly.
        val g = FpsHysteresis()
        g.update(15, 0)
        assertEquals(15, g.update(12, 100))
        assertEquals(12, g.update(12, 2_100))
    }

    // ---- audio first ----------------------------------------------------------------------------------------

    @Test
    fun theCallVideoPausesAfterSixSecondsAtTheFloorAndResumesAfterThirty() {
        val gate = AudioFirstGate()
        assertEquals(AudioFirstGate.Action.NONE, gate.onSample(true, 0))
        assertEquals(AudioFirstGate.Action.NONE, gate.onSample(true, 5_999))
        assertEquals(AudioFirstGate.Action.PAUSE, gate.onSample(true, 6_000))
        assertTrue(gate.paused)
        assertEquals(AudioFirstGate.Action.NONE, gate.onSample(true, 35_999))
        assertEquals(AudioFirstGate.Action.RESUME, gate.onSample(false, 36_000))
        assertFalse(gate.paused)
    }

    @Test
    fun aCongestionThatClearsResetsTheSixSeconds() {
        val gate = AudioFirstGate()
        gate.onSample(true, 0)
        gate.onSample(true, 4_000)
        assertEquals(AudioFirstGate.Action.NONE, gate.onSample(false, 5_000))
        assertEquals(AudioFirstGate.Action.NONE, gate.onSample(true, 5_500))
        assertEquals(AudioFirstGate.Action.NONE, gate.onSample(true, 11_000))
        assertEquals(AudioFirstGate.Action.PAUSE, gate.onSample(true, 11_500))
    }

    @Test
    fun aResumeThatFailsAgainWaitsLongerUpToTwoMinutes() {
        val gate = AudioFirstGate()
        var now = 0L
        fun pauseNow(): Long { // congested until the pause
            gate.onSample(true, now)
            now += 6_000
            assertEquals(AudioFirstGate.Action.PAUSE, gate.onSample(true, now))
            return now
        }
        pauseNow()
        assertEquals(30_000, gate.waitMs())
        now += 30_000
        assertEquals(AudioFirstGate.Action.RESUME, gate.onSample(true, now))
        // Congested again right after the resume: paused again, the wait doubles.
        now += 100
        gate.onSample(true, now)
        now += 6_000
        assertEquals(AudioFirstGate.Action.PAUSE, gate.onSample(true, now))
        assertEquals(60_000, gate.waitMs())
        now += 60_000
        assertEquals(AudioFirstGate.Action.RESUME, gate.onSample(true, now))
        now += 100
        gate.onSample(true, now)
        now += 6_000
        assertEquals(AudioFirstGate.Action.PAUSE, gate.onSample(true, now))
        assertEquals(120_000, gate.waitMs())
        // A resume that holds for longer than the failure window forgets the failures.
        now += 120_000
        assertEquals(AudioFirstGate.Action.RESUME, gate.onSample(false, now))
        now += 60_000
        gate.onSample(true, now)
        now += 6_000
        assertEquals(AudioFirstGate.Action.PAUSE, gate.onSample(true, now))
        assertEquals(30_000, gate.waitMs())
    }

    @Test
    fun theControllerReportsTheLowestRateOfTheFrameRateLadder() {
        val low = controller(Recording(40_000), evidenceMode = true)
        low.onSample("p", filled(9_000), 0L)
        assertTrue(low.atLowestRate)
        val mid = controller(Recording(300_000), evidenceMode = true)
        mid.onSample("p", filled(40_000), 0L)
        assertFalse(mid.atLowestRate)
    }

    // ---- encoder load steps the picture down ------------------------------------------------------------

    @Test
    fun anOverloadedEncoderStepsTheResolutionDownAfterThreeSecondsAndBlocksTheWayBackUp() {
        val l = ladder()
        assertEquals(p480, l.start(450_000, 0).resolution)
        assertNull(l.onEncoderLoad(1.3, 1_000))
        assertNull(l.onEncoderLoad(1.3, 3_999)) // held less than 3 s
        assertNull(l.onEncoderLoad(1.3, 4_000)) // 3 s held but the start was under 5 s ago
        val down = l.onEncoderLoad(1.3, 5_000)
        assertEquals(p360, down?.resolution)
        assertTrue(down!!.reason.startsWith("encoder_load(load=1.30"))
        // The link would allow 480p again, but the encoder block holds the rung for 60 s.
        assertNull(l.onTarget(2_000_000, 6_000))
        assertNull(l.onTarget(2_000_000, 40_000))
        assertNull(l.onTarget(2_000_000, 64_999))
        assertEquals(p480, l.onTarget(2_000_000, 65_000)?.resolution)
    }

    @Test
    fun aBriefOrAbsentOverloadDoesNothing() {
        val l = ladder()
        l.start(450_000, 0)
        assertNull(l.onEncoderLoad(null, 10_000))
        assertNull(l.onEncoderLoad(1.3, 11_000))
        assertNull(l.onEncoderLoad(0.9, 12_000)) // fits again: the hold restarts
        assertNull(l.onEncoderLoad(1.3, 13_000))
        assertNull(l.onEncoderLoad(1.3, 15_999))
        assertEquals(p360, l.onEncoderLoad(1.3, 16_000)?.resolution)
        // 240p is the link floor, but an encoder that cannot keep up at 240p may still use 180p; nothing is below it.
        val bottom = ladder()
        assertEquals(p240, bottom.start(50_000, 0).resolution)
        assertNull(bottom.onEncoderLoad(2.0, 10_000))
        assertEquals(p180, bottom.onEncoderLoad(2.0, 14_000)?.resolution)
        assertNull(bottom.onEncoderLoad(2.0, 30_000))
        assertNull(bottom.onEncoderLoad(2.0, 90_000))
    }

    @Test
    fun theMeasuredEncodeTimePerMegapixelKeepsTheSlowerSession() {
        assertEquals(291.0, EncoderBurstRanking.mergeMsPerMegapixel(null, 291.0), 0.001)
        assertEquals(400.0, EncoderBurstRanking.mergeMsPerMegapixel(291.0, 400.0), 0.001)
        // A faster session lowers it by a fifth of the difference.
        assertEquals(291.0 * 0.8 + 200.0 * 0.2, EncoderBurstRanking.mergeMsPerMegapixel(291.0, 200.0), 0.001)
    }

    // ---- the ladder never raises latency (lane 2: M31 stream source, 24 FPS, queue 1.86 s, playout 2000 ms) ----

    @Test
    fun theGuardAllowsAClimbOnlyAfterFiveCleanSecondsAndNeverBeforeAnySample() {
        val guard = ClimbGuard()
        assertFalse(guard.climbAllowed(0)) // nothing seen yet
        guard.onSample(20, 10, false, 0)
        assertTrue(guard.climbAllowed(0))
        guard.onSample(120, 40, false, 1_000) // 160 ms in flight: over the 150 ms bound
        assertFalse(guard.climbAllowed(1_000))
        assertFalse(guard.climbAllowed(5_999))
        assertTrue(guard.climbAllowed(6_000))
    }

    @Test
    fun aLateChildOrAQueueOverTheBoundBlocksAndAnUnknownPeerQueueDoesNot() {
        val guard = ClimbGuard()
        guard.onSample(0, -1, false, 0) // delivery reports not available: only the sender queue counts
        assertTrue(guard.climbAllowed(10_000))
        guard.onSample(0, 0, true, 10_000) // child reports its playout delay rising
        assertFalse(guard.climbAllowed(14_999))
        assertTrue(guard.climbAllowed(15_000))
        // 150 ms exactly is within the bound.
        val edge = ClimbGuard()
        edge.onSample(100, 50, false, 0)
        assertTrue(edge.climbAllowed(0))
    }

    @Test
    fun congestionIsAQueueOfFourHundredMillisecondsOrALateChildWithAQueue() {
        val guard = ClimbGuard()
        guard.onSample(10, 0, false, 0)
        assertFalse(guard.congested(0))
        guard.onSample(1_860, 0, false, 1_000) // the lane's sender queue maximum
        assertTrue(guard.congested(1_000))
        assertTrue(guard.congested(2_400))
        assertFalse(guard.congested(2_600)) // 1.5 s window
        val late = ClimbGuard()
        late.onSample(200, 0, true, 0)
        assertTrue(late.congested(0))
        val lateEmpty = ClimbGuard()
        lateEmpty.onSample(0, 0, true, 0)
        assertFalse(lateEmpty.congested(0))
    }

    @Test
    fun theFrameRateDoesNotRiseWhileTheGuardHoldsAndRisesOneLevelOnceItReleases() {
        val h = FpsHysteresis()
        assertEquals(10, h.update(10, 0))
        // The curve asks for 24 FPS (870 kbps target), the queues are not clean: no rise at all.
        for (t in 1_000L..40_000L step 1_000L) assertEquals("t=$t", 10, h.update(24, t, allowUp = false))
        // Released: 10 s of asking from the release, then one level at a time.
        for (t in 41_000L..50_000L step 1_000L) assertEquals("t=$t", 10, h.update(24, t, allowUp = true))
        assertEquals(12, h.update(24, 51_000, allowUp = true))
    }

    @Test
    fun congestionLowersTheFrameRateOneLevelAtOnceAndBlocksARiseForThirtySeconds() {
        val h = FpsHysteresis()
        assertEquals(24, h.update(24, 0))
        assertEquals(24, h.update(24, 1_000, congested = true)) // changed less than 5 s ago
        assertEquals(15, h.update(24, 5_000, congested = true))
        assertEquals(12, h.update(24, 10_000, congested = true))
        // No rise until 30 s after the last congestion, then the usual 10 s of asking and 15 s since a change.
        for (t in 11_000L..39_000L step 1_000L) assertEquals("t=$t", 12, h.update(24, t))
        for (t in 40_000L..49_000L step 1_000L) assertEquals("t=$t", 12, h.update(24, t))
        assertEquals(15, h.update(24, 50_000))
    }

    @Test
    fun aStepUpOfThePictureWaitsForTheGuardToo() {
        val l = ladder()
        l.climbGate = { false }
        assertEquals(p240, l.start(120_000, 0).resolution)
        // The target holds 1.25x the 360p entry for far longer than the hold, but the gate is shut.
        for (t in 1_000L..60_000L step 1_000L) assertNull("t=$t", l.onTarget(300_000, t))
        // The gate opens: the hold restarts (10 s of asking) and the step is one rung.
        l.climbGate = { true }
        assertNull(l.onTarget(300_000, 61_000))
        assertNull(l.onTarget(300_000, 70_999))
        assertEquals(p360, l.onTarget(300_000, 71_000)?.resolution)
    }

    @Test
    fun aGateThatClosesAgainRestartsTheHoldAndDoesNotBlockSteppingDown() {
        val l = ladder()
        var open = true
        l.climbGate = { open }
        l.start(120_000, 0)
        assertNull(l.onTarget(300_000, 1_000)) // asking from 1 s
        open = false
        assertNull(l.onTarget(300_000, 9_000)) // closed: the hold is dropped
        open = true
        assertNull(l.onTarget(300_000, 10_000)) // asking again from 10 s
        assertNull(l.onTarget(300_000, 19_999))
        assertEquals(p360, l.onTarget(300_000, 20_000)?.resolution)
        // Down is never gated.
        open = false
        assertNull(l.onTarget(100_000, 21_000))
        assertEquals(p240, l.onTarget(100_000, 25_000)?.resolution)
    }

    @Test
    fun theRunnerSuiteTunablesAreNamedLikeTheExtras() {
        assertEquals("test_auto_ladder", AutoQualityFeature.EXTRA)
        assertEquals("test_auto_ladder_class", AutoQualityFeature.CLASS_EXTRA)
    }

    // ---- before any delivered evidence: probe-only rule, 15 FPS at most, header included (Camera2 A/B) -------

    @Test
    fun theCamera2ColdPlanIsA240pColdStartAtFifteenFpsAndTheHeaderFollows() {
        // ASUS to S24 GATT, probe 1488: the plain plan was 480p24 at 1100 kbps with a 24 FPS header.
        val plain = AutoQualityLadder.plan(1_488, vp9, standard)
        assertEquals("480p@24", plain.rung?.label)
        assertEquals(1_110, plain.videoKbps) // 1100 with the GATT transport maximum
        val cold = evidence(1_488, null, null)
        assertEquals("240p@15", cold.rung?.label)
        assertEquals(140, cold.videoKbps)
        assertEquals("probe_only", cold.evidence)
        val header = AutoQualityLadder.callHeader(vp9, standard, cold)
        assertEquals(15, header.second)
        assertEquals(854 to 480, header.first.width to header.first.height)
    }

    @Test
    fun noPlanWithoutDeliveredEvidenceUsesMoreThanFifteenFps() {
        for (link in 60..3_000 step 11) for (codec in listOf(av1, vp9)) for (cls in AutoEncoderClass.entries) {
            for (phy in listOf<Int?>(null, 263, 5_000)) {
                val plan = AutoQualityLadder.evidencePlan(link, phy, null, codec, cls)
                val fps = plan.rung?.fps ?: continue
                assertTrue("link=$link codec=$codec cls=$cls phy=$phy ${plan.rung?.label}", fps <= AutoQualityLadder.MAX_UNCONFIRMED_FPS)
                assertTrue(AutoQualityLadder.callHeader(codec, cls, plan).second <= 15)
            }
        }
        val assumed = evidence(null, null, null)
        assertEquals("assumed", assumed.evidence)
        assertTrue(assumed.rung!!.fps <= 15)
    }

    @Test
    fun aStrongLinkWithAProbeOnlyStartsAtFifteenWithTheSameProbeOnlyRate() {
        val av1Plan = evidence(1_300, null, null, av1)
        assertEquals("240p@15", av1Plan.rung?.label) // plain AV1 plan is 480p30
        assertEquals(140, av1Plan.videoKbps)
        val ceilinged = evidence(3_000, 3_000, null)
        assertEquals("phy_bound", ceilinged.evidence)
        assertEquals("480p@15", ceilinged.rung?.label) // the plain plan is 480p30
    }

    @Test
    fun deliveredEvidenceMayStillSupportMoreThanFifteenFps() {
        val delivered = evidence(2_000, null, 900)
        assertEquals("delivered", delivered.evidence)
        assertEquals("480p@24", delivered.rung?.label)
        assertEquals(900, delivered.videoKbps)
        assertEquals(24, AutoQualityLadder.callHeader(vp9, standard, delivered).second)
    }

    // ---- stream stability (second Camera2 A/B, build 47300560): sizes change rarely, viewers keep the last frame ----

    private fun streamLadder(guard: ClimbGuard): CallResolutionLadder = CallResolutionLadder(
        p480, offered, av1,
        limits = AutoQualityLadder.callLimits(av1, standard, 15),
        downHoldMs = AutoQualityLadder.STREAM_DOWN_HOLD_MS,
        upHoldMs = AutoQualityLadder.STREAM_UP_HOLD_MS,
        upMinIntervalMs = AutoQualityLadder.STREAM_UP_MIN_INTERVAL_MS,
        downMinIntervalMs = AutoQualityLadder.STREAM_DOWN_MIN_INTERVAL_MS,
        startLimit = AutoQualityLadder.callStartLimit(av1, standard, 15),
    ).also {
        it.climbGate = { now -> guard.climbAllowed(now, AutoQualityLadder.STREAM_UP_CLEAN_MS) }
        it.downGate = { now -> guard.sustainedCongestion(now, AutoQualityLadder.STREAM_CONGESTION_MS) }
    }

    @Test
    fun theGuardKnowsSustainedAndSevereCongestionAsContinuousStretches() {
        val g = ClimbGuard()
        g.onSample(616, 0, false, 0)
        assertFalse(g.sustainedCongestion(2_999, 3_000))
        g.onSample(1_320, 0, true, 1_000)
        g.onSample(2_015, 0, true, 2_000)
        assertTrue(g.sustainedCongestion(3_000, 3_000))
        assertFalse(g.severeCongestion(3_000, 4_000)) // severe since 1.0 s only
        assertTrue(g.severeCongestion(5_000, 4_000))
        g.onSample(40, 0, false, 5_500) // one clean sample ends both stretches
        assertFalse(g.sustainedCongestion(9_000, 3_000))
        assertFalse(g.severeCongestion(9_000, 4_000))
    }

    @Test
    fun theArmCQueueOf534MillisecondsIsNotSevereAndDoesNotStepTheFrameRate() {
        // C: backlog 534, 553, 580, 434 ms with the child on time, then clean; the old rule cut 15 -> 12 FPS.
        val g = ClimbGuard()
        val h = FpsHysteresis()
        assertEquals(15, h.update(15, 0))
        var now = 0L
        for (queue in listOf(534, 553, 580, 434, 420, 489, 403)) {
            now += 1_000
            g.onSample(queue, 0, false, now)
            assertFalse(g.severeCongestion(now, AutoQualityLadder.FPS_SEVERE_CONGESTION_MS))
            assertEquals(15, h.update(15, now, allowUp = g.climbAllowed(now),
                congested = g.severeCongestion(now, AutoQualityLadder.FPS_SEVERE_CONGESTION_MS)))
        }
    }

    @Test
    fun aSevereSustainedQueueStillStepsTheFrameRateDownOnce() {
        val g = ClimbGuard()
        val h = FpsHysteresis()
        assertEquals(24, h.update(24, 0))
        var fps = 24
        for (t in 1_000L..12_000L step 1_000L) {
            g.onSample(1_800, 0, false, t)
            fps = h.update(24, t, allowUp = g.climbAllowed(t), congested = g.severeCongestion(t, AutoQualityLadder.FPS_SEVERE_CONGESTION_MS))
        }
        assertEquals(12, fps) // 24 -> 15 at 5 s, 15 -> 12 at 10 s (5 s apart)
    }

    @Test
    fun aTargetThatFallsByItselfNeverChangesTheStreamSize() {
        val guard = ClimbGuard()
        val l = streamLadder(guard)
        assertEquals(p480, l.start(495_000, 0).resolution)
        // The target is under the 480p limit for a minute, but the queues are clean: no congestion evidence.
        for (t in 1_000L..60_000L step 1_000L) {
            guard.onSample(20, 0, false, t)
            assertNull("t=$t", l.onTarget(97_000, t))
        }
    }

    @Test
    fun aSizeStepDownNeedsSustainedCongestionAndThenTheTimersOfTheStreamLadder() {
        val guard = ClimbGuard()
        val l = streamLadder(guard)
        l.start(495_000, 0)
        // Queue age growing as in arm B: 616, 1320, 2015, 2754, 3278 ms, the target at 97 kbps.
        val queues = listOf(616, 1_320, 2_015, 2_754, 3_278, 3_816)
        var change: CallResolutionLadder.Decision? = null
        var changedAt = -1L
        for ((i, q) in queues.withIndex()) {
            val t = 16_000L + i * 1_000L // after the 15 s minimum since the start
            guard.onSample(q, 0, true, t)
            change = l.onTarget(97_000, t)
            if (change != null) { changedAt = t; break }
        }
        assertEquals(p240, change?.resolution)
        // Congestion began at 16 s; 3 s of it and 3 s of low target: the step comes at 19 s, not before.
        assertEquals(19_000L, changedAt)
    }

    @Test
    fun theWayBackUpTakesThirtyCleanSecondsAndSixtySecondsSinceTheLastChange() {
        val guard = ClimbGuard()
        val l = streamLadder(guard)
        l.start(495_000, 0)
        var down: CallResolutionLadder.Decision? = null
        for (t in 16_000L..19_000L step 1_000L) {
            guard.onSample(2_000, 0, true, t)
            down = l.onTarget(97_000, t) ?: down
        }
        assertEquals(p240, down?.resolution) // the change, at 19 s
        // Clean from 20 s on; the target recovers to 494 kbps (the A/B's link_recovered).
        for (t in 20_000L..78_999L step 1_000L) {
            guard.onSample(30, 0, false, t)
            assertNull("t=$t", l.onTarget(494_000, t)) // under 60 s since the change
        }
        guard.onSample(30, 0, false, 79_000)
        assertEquals(p360, l.onTarget(494_000, 79_000)?.resolution) // 60 s after, one rung
        // And the next rung is another 60 s away.
        for (t in 80_000L..138_999L step 1_000L) {
            guard.onSample(30, 0, false, t)
            assertNull("t=$t", l.onTarget(600_000, t))
        }
        guard.onSample(30, 0, false, 139_000)
        assertEquals(p480, l.onTarget(600_000, 139_000)?.resolution)
    }

    @Test
    fun aQueueBlipDuringTheCleanHoldDelaysTheStepUpByThirtySeconds() {
        val guard = ClimbGuard()
        val l = streamLadder(guard)
        l.start(100_000, 0) // 240p
        for (t in 1_000L..109_000L step 1_000L) {
            // One 300 ms queue at 50 s: the hold toward the first allowed step (60 s since the start) restarts.
            guard.onSample(if (t == 50_000L) 300 else 20, 0, false, t)
            assertNull("t=$t", l.onTarget(300_000, t))
        }
        guard.onSample(20, 0, false, 110_000)
        assertEquals(p360, l.onTarget(300_000, 110_000)?.resolution) // clean again from 80 s, plus a 30 s hold
    }

    @Test
    fun callsKeepTheirOwnLadderTimers() {
        val l = ladder() // no down gate, calls' 1.5 s hold and 5 s interval
        l.start(450_000, 0)
        assertNull(l.onTarget(120_000, 10_000))
        assertEquals(p240, l.onTarget(120_000, 11_500)?.resolution)
    }

    // ---- viewer: a planned size change keeps the last frame ----------------------------------------------------

    @Test
    fun aDiscontinuityKeyFrameIsAPlannedChangeUnlessARecoveryIsAlreadyShowing() {
        assertTrue(isPlannedSizeChange(discontinuityKeyFrame = true, alreadyRecovering = false))
        assertFalse(isPlannedSizeChange(discontinuityKeyFrame = true, alreadyRecovering = true))
        // A keyframe that needs a rebuild but was not marked by the sender's restart is treated as recovery.
        assertFalse(isPlannedSizeChange(discontinuityKeyFrame = false, alreadyRecovering = false))
    }

    @Test
    fun theSenderMarksOnlyARestartThatChangesTheSize() {
        val key = StreamProtocol.FLAG_KEY_FRAME
        val sized = RestartDiscontinuityMarker(sizeChanged = true)
        assertEquals(key or StreamProtocol.FLAG_DISCONTINUITY, sized.flagsFor(key))
        assertEquals(key, sized.flagsFor(key)) // only the first keyframe
        assertEquals(key, RestartDiscontinuityMarker(sizeChanged = false).flagsFor(key))
    }

    // ---- cold start on a weak link (lane 4: probe 1488 kbps, ASUS to A15s carried 44-90 kbps) ----------------------

    @Test
    fun theColdStartNeverExceeds140KbpsOr240pForAnyProbeAndKeepsAnAudioOnlyPlanAudioOnly() {
        for (probe in 60..4_000 step 7) for (codec in listOf(av1, vp9)) for (cls in AutoEncoderClass.entries) {
            val plan = AutoQualityLadder.evidencePlan(probe, null, null, codec, cls)
            val rung = plan.rung ?: continue
            assertTrue("probe=$probe ${rung.label}", plan.videoKbps <= AutoQualityLadder.PROBE_ONLY_MAX_VIDEO_KBPS)
            assertTrue(rung.pixels <= AutoQualityLadder.PROBE_ONLY_MAX_PIXELS)
            assertTrue(rung.fps <= 15)
            assertTrue(plan.videoKbps <= AutoQualityLadder.plan(probe, codec, cls).videoKbps)
        }
        assertTrue(evidence(80, null, null).audioOnly) // a probe that cannot carry video stays audio only
    }

    @Test
    fun aColdStartOnAProbeTheSizeOfTheLinkTakesAThirdOfThePlan() {
        // Probe 300: the plain plan is 360p15 at 180 kbps; the cold start is 30% of it, at the 40 kbps minimum.
        val weak = evidence(300, null, null)
        assertEquals("240p@10", weak.rung?.label)
        assertEquals(50, weak.videoKbps)
        val strong = evidence(4_000, null, null)
        assertEquals(140, strong.videoKbps)
        assertEquals("240p@15", strong.rung?.label)
    }

    @Test
    fun theColdStartOffersAFractionOfTheLoadThatBuiltA9SecondQueueOnA70KbpsLink() {
        val carriedKbps = 70.0 // lane 4: the link carried 44-90 kbps of a 1488 kbps probe
        fun backlogSecondsPerSecond(video: Int, fps: Int) =
            (AutoQualityLadder.wireKbps(video, fps, AutoQualityLadder.OPUS32_WIRE_KBPS) - carriedKbps) / carriedKbps
        val old = AutoQualityLadder.plan(1_488, vp9, standard).let { backlogSecondsPerSecond(it.videoKbps, it.rung!!.fps) }
        val cold = evidence(1_488, null, null).let { backlogSecondsPerSecond(it.videoKbps, it.rung!!.fps) }
        assertTrue("old=$old", old > 10.0) // 1110 kbps of video on 70: the queue grows 12+ s per second
        assertTrue("cold=$cold", cold < 2.5) // 140 kbps video: about 2 s per second, cut at the first sample
    }

    @Test
    fun theControllerReachesItsFloorInTheFirstSampleOfAColdStartOnAWeakLink() {
        val memory = Recording(140_000)
        val controller = LinkRateController(
            1_100_000, memory = memory, frameRateLadder = FrameRateLadder(15, 10, floorBps = 60_000),
            smallClimbSteps = true, deliveredEvidence = true,
        )
        // One second: 172 kbit offered (140 video + 32 audio), 70 kbit carried, the queue 1.4 s old and the child late.
        val first = controller.onSample(
            "p", LinkRateSample(1_000_000_000L, 21_500, 8_750, 1_400, false, childLate = true, peer = "p"), 0L,
        )
        assertEquals(40_000, first) // 0.85 x 70 - 32 = 27 kbps, held at the 40 kbps floor
        assertTrue(controller.atLowestRate)
    }

    @Test
    fun theWarmPathIsUnchangedByTheColdStartRule() {
        // Delivered evidence and the PHY bound are not probe-only: they plan as before.
        val delivered = evidence(2_000, null, 900)
        assertEquals("480p@24", delivered.rung?.label)
        assertEquals(900, delivered.videoKbps)
        val phy = evidence(1_074, 263, null)
        assertEquals("phy_bound", phy.evidence)
        assertEquals(150, phy.videoKbps)
        assertEquals("240p@15", phy.rung?.label) // VP9: 360p15 needs 175
        // The assumed link keeps the approved 360p15 start.
        assertEquals("360p@15", evidence(null, null, null).rung?.label)
    }

    // ---- PHY transient (lane 4 b8: 2M carried 85 kbps, 1M 130-180; the controller stayed at its floor) ----------

    private fun phyController(deliveredEvidence: Boolean = true) = LinkRateController(
        1_100_000, memory = Recording(140_000), frameRateLadder = FrameRateLadder(15, 10, floorBps = 60_000),
        smallClimbSteps = deliveredEvidence, deliveredEvidence = deliveredEvidence,
    )

    private fun window(offeredKbit: Int, sentKbit: Int, backlogMs: Int, late: Boolean) = LinkRateSample(
        1_000_000_000L, offeredKbit * 125L, sentKbit * 125L, backlogMs, false, childLate = late, peer = "p",
    )

    /** The first nine seconds of b8: the cut at the first window, then the queue growing at the floor. */
    private fun runOnTheTwoMegabitLink(c: LinkRateController) {
        assertEquals(40_000, c.onSample("p", window(185, 84, 881, true), 0L))
        val windows = listOf(1_562 to 96, 2_238 to 71, 3_453 to 73, 3_926 to 66, 4_206 to 64, 4_300 to 70, 4_400 to 70, 4_450 to 70)
        for ((i, w) in windows.withIndex()) c.onSample("p", window(72, w.second, w.first, i < 3), (i + 1) * 1_000_000_000L)
    }

    @Test
    fun theColdStartCutsToTheFloorOnTheTwoMegabitLinkThatCarriesEightyFiveKbps() {
        val c = phyController()
        // b8 asus.log 16:17:13.656: offered 185 kbit, sent 84 kbit, backlog 881 ms, child late.
        assertEquals(40_000, c.onSample("p", window(185, 84, 881, true), 0L))
        assertTrue(c.atLowestRate)
        // The queue keeps growing for 6 s at the floor (72-99 kbit offered against 64-73 carried): nothing left to cut.
        var t = 1L
        for ((backlog, sent) in listOf(1_562 to 96, 2_238 to 71, 3_453 to 73, 3_926 to 66, 4_206 to 64)) {
            assertNull("t=$t", c.onSample("p", window(72, sent, backlog, t < 4), t * 1_000_000_000L))
            t++
        }
    }

    @Test
    fun aPhyChangeRestartsTheTargetFromSixtyPercentOfTheStartAndHoldsAnyCutForAnIntervalWhileTheOldQueueDrains() {
        val c = phyController()
        runOnTheTwoMegabitLink(c)
        assertEquals(40_000, c.effectiveVideoBps)
        // 10 s: the adaptive PHY policy moved the link to 1M (140 kbps start x 0.6 = 84 kbps).
        val restart = AutoQualityLadder.linkChangeRestartKbps(140)
        assertEquals(84, restart)
        assertEquals(84_000, c.restartAfterLinkChange(restart * 1_000, 10_000_000_000L))
        // The queue left by the old link (3.5 s, shrinking) must not cut the restarted target within 3 s.
        assertNull(c.onSample("p", window(74, 104, 3_525, false), 11_000_000_000L))
        assertNull(c.onSample("p", window(68, 138, 3_700, true), 12_000_000_000L))
        assertEquals(84_000, c.effectiveVideoBps)
    }

    @Test
    fun theRestartNeverLowersATargetAndSaturatesAtTheRequest() {
        val c = phyController()
        assertEquals(40_000, c.onSample("p", window(185, 84, 881, true), 0L))
        assertEquals(500_000, c.restartAfterLinkChange(500_000, 1_000_000_000L))
        assertNull(c.restartAfterLinkChange(84_000, 2_000_000_000L)) // already higher: unchanged
        assertEquals(500_000, c.effectiveVideoBps)
        assertEquals(1_100_000, c.restartAfterLinkChange(5_000_000, 3_000_000_000L)) // clamped to the request
    }

    @Test
    fun afterTheRestartTheClimbResumesInTwentyKbpsStepsFromTheNewLevel() {
        val restarted = phyController()
        runOnTheTwoMegabitLink(restarted)
        restarted.restartAfterLinkChange(84_000, 9_000_000_000L)
        val stale = phyController()
        runOnTheTwoMegabitLink(stale)
        // Clean windows that fill the target (video + 32 kbps audio, queue empty), 3 per step.
        fun fill(targetKbps: Int) = targetKbps + 32
        var restartedTarget = 84
        var staleTarget = 40
        for (i in 0 until 12) {
            val now = (10L + i) * 1_000_000_000L
            restarted.onSample("p", window(fill(restartedTarget), fill(restartedTarget), 0, false), now)?.let { restartedTarget = it / 1_000 }
            stale.onSample("p", window(fill(staleTarget), fill(staleTarget), 0, false), now)?.let { staleTarget = it / 1_000 }
        }
        assertEquals(164, restartedTarget) // 84 + 4 steps of 20 kbps in 12 s
        assertEquals(120, staleTarget) // 40 + 4 steps of 20 kbps: 44 kbps behind after the same 12 s
    }

    @Test
    fun theAutoClimbStepFromTheFloorIsTwentyKbpsAndOtherModesKeepTheirs() {
        fun climb(auto: Boolean): Int? {
            val c = LinkRateController(
                1_100_000, memory = Recording(40_000), frameRateLadder = FrameRateLadder(15, 10, floorBps = 60_000),
                smallClimbSteps = auto, deliveredEvidence = auto,
            )
            var changed: Int? = null
            for (i in 0 until 3) changed = c.onSample("p", window(72, 72, 0, false), i * 1_000_000_000L) ?: changed
            return changed
        }
        assertEquals(60_000, climb(auto = true)) // 40 + 20
        assertEquals(150_000, climb(auto = false)) // the old step: a tenth of the 1.1 Mbps request
    }

    @Test
    fun aPhyChangeIsCountedOnlyWhenTheTransmitPhyDiffersFromThePreviousReport() {
        val tracker = PhyChangeTracker()
        assertFalse(tracker.onPhy(1)) // the first report is not a change
        assertFalse(tracker.onPhy(1))
        assertTrue(tracker.onPhy(2)) // 1M to 2M at connect
        assertFalse(tracker.onPhy(2))
        assertTrue(tracker.onPhy(1)) // 2M to 1M: the adaptive move
        val before = LinkChangeEvents.epoch
        LinkChangeEvents.phyChanged()
        assertEquals(before + 1, LinkChangeEvents.epoch)
    }

    @Test
    fun theRestartLevelIsSixtyPercentOfTheStartButNeverBelowTheFloor() {
        assertEquals(84, AutoQualityLadder.linkChangeRestartKbps(140))
        assertEquals(40, AutoQualityLadder.linkChangeRestartKbps(50))
        assertEquals(40, AutoQualityLadder.linkChangeRestartKbps(0))
    }
}
