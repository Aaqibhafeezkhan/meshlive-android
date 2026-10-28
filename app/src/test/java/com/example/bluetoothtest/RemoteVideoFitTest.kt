package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteVideoFitTest {
    // A portrait phone's call screen (S24-like) and its landscape counterpart.
    private val portraitW = 1080
    private val portraitH = 2340

    private fun auto(width: Int, height: Int, rotation: Int, viewW: Int = portraitW, viewH: Int = portraitH) =
        RemoteVideoFit.mode(
            enabled = true,
            chosenMode = VideoScaleMode.FILL,
            override = null,
            encodedWidth = width,
            encodedHeight = height,
            rotationDegrees = rotation,
            viewWidth = viewW,
            viewHeight = viewH,
        )

    @Test
    fun landscapeLaptopCameraOnPortraitPhoneShowsWholePicture() {
        assertEquals(VideoScaleMode.FIT, auto(1280, 720, rotation = 0))
        assertEquals(VideoScaleMode.FIT, auto(640, 480, rotation = 0))
    }

    @Test
    fun portraitPhoneToPortraitPhoneStillFills() {
        // A phone camera sends landscape buffers with a quarter turn; the displayed picture is portrait.
        assertEquals(VideoScaleMode.FILL, auto(1280, 720, rotation = 90))
        assertEquals(VideoScaleMode.FILL, auto(1280, 720, rotation = 270))
        assertEquals(VideoScaleMode.FILL, auto(640, 480, rotation = 90))
        assertEquals(VideoScaleMode.FILL, auto(720, 1280, rotation = 0))
    }

    @Test
    fun halfTurnKeepsTheVideoShape() {
        assertEquals(VideoScaleMode.FIT, auto(1280, 720, rotation = 180))
    }

    @Test
    fun portraitVideoOnLandscapeScreenShowsWholePicture() {
        assertEquals(VideoScaleMode.FIT, auto(1280, 720, rotation = 90, viewW = portraitH, viewH = portraitW))
        // Landscape on landscape fills.
        assertEquals(VideoScaleMode.FILL, auto(1280, 720, rotation = 0, viewW = portraitH, viewH = portraitW))
    }

    @Test
    fun squareVideoOnTallPhoneIsAStrongMismatch() {
        assertEquals(VideoScaleMode.FIT, auto(720, 720, rotation = 0))
        // ...but fills a near-square view.
        assertEquals(VideoScaleMode.FILL, auto(720, 720, rotation = 0, viewW = 1000, viewH = 1100))
    }

    @Test
    fun mildlyLandscapeVideoInMildlyPortraitWindowFitsBecauseShapesAreOpposite() {
        // Split screen: a 4:3 landscape video in a 1000x1200 portrait window (ratio 1.6, below the strong limit).
        assertEquals(VideoScaleMode.FIT, auto(640, 480, rotation = 0, viewW = 1000, viewH = 1200))
    }

    @Test
    fun appWideShowFullFrameSettingAlwaysFits() {
        val mode = RemoteVideoFit.mode(true, VideoScaleMode.FIT, null, 1280, 720, 90, portraitW, portraitH)
        assertEquals(VideoScaleMode.FIT, mode)
    }

    @Test
    fun controlArmUsesOnlyTheAppWideSetting() {
        assertEquals(
            VideoScaleMode.FILL,
            RemoteVideoFit.mode(false, VideoScaleMode.FILL, VideoScaleMode.FIT, 1280, 720, 0, portraitW, portraitH),
        )
        assertEquals(
            VideoScaleMode.FIT,
            RemoteVideoFit.mode(false, VideoScaleMode.FIT, null, 1280, 720, 90, portraitW, portraitH),
        )
    }

    @Test
    fun doubleTapTogglesAndTheChoiceLastsUntilTheCallEnds() {
        val state = RemoteVideoFitState()
        assertNull(state.override)
        val shown = auto(1280, 720, rotation = 0)
        assertEquals(VideoScaleMode.FIT, shown)

        assertEquals(VideoScaleMode.FILL, state.toggle(shown))
        // The choice survives a new header or a rotation of the phone (a different view shape).
        assertEquals(
            VideoScaleMode.FILL,
            RemoteVideoFit.mode(true, VideoScaleMode.FILL, state.override, 1280, 720, 0, portraitW, portraitH),
        )
        assertEquals(
            VideoScaleMode.FILL,
            RemoteVideoFit.mode(true, VideoScaleMode.FILL, state.override, 1280, 720, 0, portraitH, portraitW),
        )
        assertEquals(VideoScaleMode.FIT, state.toggle(VideoScaleMode.FILL))

        state.reset() // Call ended: the next call starts automatic again.
        assertNull(state.override)
    }

    @Test
    fun unmeasuredViewFillsWithoutFailing() {
        assertEquals(VideoScaleMode.FILL, RemoteVideoFit.automaticMode(16.0 / 9.0, 0, 0))
    }
}
