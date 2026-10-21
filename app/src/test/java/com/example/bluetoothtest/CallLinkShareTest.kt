package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F16 (longcall-ef96bbf0 m31-a15s-video300 and m31-xiaomi-video300): both fixes are Experimental and off by default. */
class CallLinkShareTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun twoLateWindowsOfTheReceivedVideoCapThisSidesRateAndCalmWindowsLiftIt() {
        val share = CallLinkShare(floorBps = 60_000)
        // The M31: sending 200 kbps while the A15s's video arrived up to 1182 ms late.
        assertFalse(share.onWindow(902, 200_000, 200_000))
        assertTrue(share.onWindow(708, 200_000, 200_000))
        assertEquals(150_000, share.capBps)
        assertEquals(150_000, share.apply(200_000))
        assertEquals("a lower controller target stays lower", 120_000, share.apply(120_000))
        // Still late: further down, never below the floor.
        repeat(20) { share.onWindow(1_182, 200_000, 200_000) }
        assertEquals(60_000, share.capBps)
        // A window with no video (camera off) neither stalls nor counts as calm.
        repeat(10) { assertFalse(share.onWindow(null, 200_000, 200_000)) }
        assertEquals(60_000, share.capBps)
        // Five calm windows raise the cap by 15 %, up to the request, then the cap is lifted.
        repeat(4) { assertFalse(share.onWindow(40, 200_000, 200_000)) }
        assertTrue(share.onWindow(40, 200_000, 200_000))
        assertEquals(69_000, share.capBps)
        repeat(200) { share.onWindow(0, 200_000, 200_000) }
        assertNull(share.capBps)
        assertEquals("recovered", share.lastReason)
        assertTrue(share.line(200_000, 200_000).startsWith("call_link_share: requested=200 | target=200 | effective=200 | cap=none"))
    }

    @Test
    fun aSingleLateWindowOrAnOrdinaryLatenessChangesNothing() {
        val share = CallLinkShare(floorBps = 60_000)
        listOf(300L, 100L, 300L, 0L, 249L, 249L).forEach { assertFalse(share.onWindow(it, 200_000, 200_000)) }
        assertNull(share.capBps)
    }

    @Test
    fun theCameraRateIsFollowedInSustainedLowLightAndLeftWhenThereIsLightAgain() {
        val follow = CallFpsFollow(requestedFps = 15)
        // The A15s: sensor frame 100.05 ms, exposure 70 ms at a requested 15 fps.
        assertFalse(follow.onWindow(100.05, 69.997))
        assertTrue(follow.onWindow(100.05, 69.997))
        assertEquals(10, follow.effectiveFps)
        assertTrue(follow.following)
        // Following: the frame is 100 ms by design; a long exposure keeps it there.
        repeat(5) { assertFalse(follow.onWindow(100.05, 69.997)) }
        // Light again: a short exposure for two windows returns to 15.
        assertFalse(follow.onWindow(100.05, 30.0))
        assertTrue(follow.onWindow(100.05, 30.0))
        assertEquals(15, follow.effectiveFps)
        // At 15 fps the normal 66.7 ms frame never triggers it.
        repeat(5) { assertFalse(follow.onWindow(66.7, 60.0)) }
        assertTrue(follow.line(66.7, 60.0).startsWith("call_fps_follow: requested=15 | effective=15"))
    }

    @Test
    fun bothFixesShipOff() {
        // Experimental (docs/dev/switches.md): off unless a benchmark launch asks for them.
        assertFalse(DebugTunables.callLinkShare)
        assertFalse(DebugTunables.callFpsFollow)
    }
}
