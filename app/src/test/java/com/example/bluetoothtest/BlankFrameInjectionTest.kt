package com.example.bluetoothtest

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** blankframes-ab-20261020a-d: the gate's ON runs had no blank buffers to drop, and nothing showed the extras arrived. */
class BlankFrameInjectionTest {
    private fun source(path: String): String = listOf(File(path), File("app/$path"), File("../$path"))
        .first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun injectedFramesAreBlankAndTheGateDropsThem() {
        val y = ByteBuffer.allocate(320 * 240).apply { for (i in 0 until limit()) put(i, 90) }
        val u = ByteBuffer.allocate(160 * 120).apply { for (i in 0 until limit()) put(i, 100) }
        val v = ByteBuffer.allocate(160 * 120).apply { for (i in 0 until limit()) put(i, 160.toByte()) }
        assertTrue(BlankFrameInjection.blank(listOf(y, u, v)))
        val yMean = PlaneSampler.mean(y, 0, 320, 1, 320, 240)
        val uMean = PlaneSampler.mean(u, 0, 160, 1, 160, 120)
        val vMean = PlaneSampler.mean(v, 0, 160, 1, 160, 120)
        assertTrue(LeadingBlankFrames.isBlank(yMean, uMean, vMean))
        assertEquals(FirstFrameSanity.Verdict.BLACK, FirstFrameSanity.assess(yMean, uMean, vMean))

        assertFalse("a read-only camera buffer is left alone", BlankFrameInjection.blank(listOf(y.asReadOnlyBuffer(), u, v)))
        assertTrue(BlankFrameInjection.shouldInject(injectedSoFar = 3, requested = 4))
        assertFalse(BlankFrameInjection.shouldInject(injectedSoFar = 4, requested = 4))
        assertFalse("off by default", BlankFrameInjection.shouldInject(0, DebugTunables.injectBlankFrames))
    }

    @Test
    fun theLaunchEchoListsOnlyTestExtrasSorted() {
        assertEquals("BENCHMARK launch_extras: count=0", LaunchExtrasEcho.line(emptyMap<String, Any?>()))
        val line = LaunchExtrasEcho.line(
            mapOf("test_inject_blank_frames" to 4, "debug_meet" to "open", "test_drop_leading_blank_frames" to true),
        )
        assertEquals("BENCHMARK launch_extras: count=2 | test_drop_leading_blank_frames=true,test_inject_blank_frames=4", line)
        val long = LaunchExtrasEcho.line(mapOf("test_raw" to "x".repeat(100)))
        assertTrue(long.endsWith("x".repeat(LaunchExtrasEcho.MAX_VALUE_CHARS) + "..."))
    }
}
