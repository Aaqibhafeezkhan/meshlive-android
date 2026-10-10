package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CallScreenUiTest {
    // 1080 x 2340 px phone at density 2.625: status bar 63 px, top row ends at 200 px, controls at 2100 px.
    private val area = CallPipArea(left = 32f, top = 232f, right = 1048f, bottom = 2068f)

    @Test
    fun pipSizeScalesWithTheShortSideWithinBounds() {
        assertEquals(302 to 403, CallPipLayout.sizePx(1080, 2.625f))
        // A 300 dp wide phone uses the 88 dp minimum.
        assertEquals(176 to 235, CallPipLayout.sizePx(600, 2f))
        // A tablet stops at 132 dp.
        assertEquals(264 to 352, CallPipLayout.sizePx(1600, 2f))
    }

    @Test
    fun cornersRestInsideTheFreeArea() {
        assertEquals(32f to 232f, CallPipLayout.cornerPosition(CallPipCorner.TOP_LEFT, 302, 403, area))
        assertEquals(746f to 232f, CallPipLayout.cornerPosition(CallPipCorner.TOP_RIGHT, 302, 403, area))
        assertEquals(32f to 1665f, CallPipLayout.cornerPosition(CallPipCorner.BOTTOM_LEFT, 302, 403, area))
        assertEquals(746f to 1665f, CallPipLayout.cornerPosition(CallPipCorner.BOTTOM_RIGHT, 302, 403, area))
    }

    @Test
    fun releasedPipSnapsToTheNearestCorner() {
        assertEquals(CallPipCorner.TOP_LEFT, CallPipLayout.snapCorner(100f, 300f, 302, 403, area))
        assertEquals(CallPipCorner.TOP_RIGHT, CallPipLayout.snapCorner(500f, 600f, 302, 403, area))
        assertEquals(CallPipCorner.BOTTOM_LEFT, CallPipLayout.snapCorner(200f, 1200f, 302, 403, area))
        assertEquals(CallPipCorner.BOTTOM_RIGHT, CallPipLayout.snapCorner(700f, 1600f, 302, 403, area))
    }

    @Test
    fun aFlingCarriesThePipTowardsItsDirection() {
        // Released just left of centre but flung right and down at 3000 px/s.
        assertEquals(
            CallPipCorner.BOTTOM_RIGHT,
            CallPipLayout.snapCorner(300f, 800f, 302, 403, area, velocityX = 3_000f, velocityY = 3_000f),
        )
        assertEquals(CallPipCorner.TOP_LEFT, CallPipLayout.snapCorner(300f, 800f, 302, 403, area))
    }

    @Test
    fun dragIsClampedToTheFreeArea() {
        assertEquals(32f to 232f, CallPipLayout.clampPosition(-50f, 0f, 302, 403, area))
        assertEquals(746f to 1665f, CallPipLayout.clampPosition(2000f, 3000f, 302, 403, area))
        assertEquals(400f to 900f, CallPipLayout.clampPosition(400f, 900f, 302, 403, area))
    }

    @Test
    fun tooSmallAreaAnchorsTopLeftInsteadOfCoveringTheBars() {
        val tiny = CallPipArea(left = 20f, top = 100f, right = 200f, bottom = 300f)
        assertEquals(20f to 100f, CallPipLayout.cornerPosition(CallPipCorner.BOTTOM_RIGHT, 302, 403, tiny))
        assertEquals(20f to 100f, CallPipLayout.clampPosition(500f, 500f, 302, 403, tiny))
    }

    @Test
    fun remoteVideoStateCoversConnectingPausedAndFrozen() {
        assertEquals(CallRemoteVideoState.NONE, callRemoteVideoState(false, true, true, null))
        assertEquals(CallRemoteVideoState.CONNECTING, callRemoteVideoState(true, true, true, null))
        assertEquals(CallRemoteVideoState.PAUSED, callRemoteVideoState(true, false, true, null))
        assertEquals(CallRemoteVideoState.PAUSED, callRemoteVideoState(true, false, false, 5_000))
        assertEquals(CallRemoteVideoState.NONE, callRemoteVideoState(true, true, false, 400))
        assertEquals(CallRemoteVideoState.NONE, callRemoteVideoState(true, true, false, 1_000))
        assertEquals(CallRemoteVideoState.FROZEN, callRemoteVideoState(true, true, false, 1_001))
        // After the peer turns video back on, the screen waits for a frame instead of calling it frozen.
        assertEquals(CallRemoteVideoState.CONNECTING, callRemoteVideoState(true, true, true, 9_000))
    }

    @Test
    fun peerLabelPrefersANameAndOtherwiseAShortId() {
        assertEquals("Nearby phone · 8a1b2c3d", privateCallPeerDisplayName(null, "8a1b2c3d4e5f"))
        assertEquals("Nearby phone · 8a1b2c3d", privateCallPeerDisplayName("Nearby device", "8A1B2C3D4E5F"))
        assertEquals("Nearby phone · 8a1b2c3d", privateCallPeerDisplayName("  ", "8a1b2c3d4e5f"))
        assertEquals("Galaxy S24", privateCallPeerDisplayName(" Galaxy S24 ", "8a1b2c3d4e5f"))
        assertEquals("A".repeat(32), privateCallPeerDisplayName("A".repeat(50), "8a1b2c3d4e5f"))
        assertEquals("Phone", privateCallPeerDisplayName("Ph\u0000one", "8a1b2c3d4e5f"))
    }

    @Test
    fun peerSubtitleShowsTheShortIdOnlyNextToAName() {
        assertEquals("BLE GATT", privateCallPeerSubtitle(null, "8a1b2c3d4e5f", "BLE GATT"))
        assertEquals("BLE GATT · 8a1b2c3d", privateCallPeerSubtitle("Galaxy S24", "8a1b2c3d4e5f", "BLE GATT"))
    }

    @Test
    fun incomingLabelShortensTheAuthenticatedNodeLabel() {
        assertEquals("Nearby phone · 8a1b2c3d", privateCallIncomingPeerLabel("8a1b2c3d4e5f"))
        assertEquals("Nearby device", privateCallIncomingPeerLabel("Nearby device"))
    }

    @Test
    fun rawSelfViewFollowsTheEncoderFrameClock() {
        assertEquals(2, rawSelfViewStep(640, 480))
        assertEquals(4, rawSelfViewStep(1280, 720))
        assertEquals(1, rawSelfViewStep(320, 240))
        val start = 1_000_000_000L
        assertEquals(0L, rawFixtureFrameIndex(start, start, 15, 450))
        assertEquals(15L, rawFixtureFrameIndex(start + 1_000_000_000L, start, 15, 450))
        // Rounded to the nearest frame like the raw feed, and looping over the fixture.
        assertEquals(1L, rawFixtureFrameIndex(start + 40_000_000L, start, 15, 450))
        assertEquals(0L, rawFixtureFrameIndex(start + 30_000_000_000L, start, 15, 450))
        assertEquals(0L, rawFixtureFrameIndex(start - 5L, start, 15, 450))
    }

    @Test
    fun i420ConversionSubsamplesAndKeepsColours() {
        val width = 4
        val height = 2
        // Left half: white (Y 235, neutral chroma). Right half: pure red in limited range.
        val y = byteArrayOf(235.toByte(), 235.toByte(), 81, 81, 235.toByte(), 235.toByte(), 81, 81)
        val u = byteArrayOf(128.toByte(), 90)
        val v = byteArrayOf(128.toByte(), 240.toByte())
        val out = IntArray(2)
        i420ToArgbSubsampled(y + u + v, width, height, 2, out)
        assertArrayEquals(intArrayOf(0xFFFFFFFF.toInt(), 0xFFFF0000.toInt()), out)
    }
}
