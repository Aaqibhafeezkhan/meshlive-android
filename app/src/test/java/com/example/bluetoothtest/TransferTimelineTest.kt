package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The measurement lines of a chat session (b1-mv2): a file inside a long session of texts must not read as a slow link. */
class TransferTimelineTest {
    private var nowNs = 0L

    private fun sessionField(line: String, key: String) = Regex("$key=(\\S+)").find(line)!!.groupValues[1]

    @Test
    fun aFileInsideALongChatSessionReportsItsActiveRateNotTheIdleSpan() {
        val timeline = TransferTimeline { nowNs }
        // One 1040-byte text every 3 s for 260 s, then 125 chunks of 8,142 bytes 190 ms apart (about 343 kbps).
        var t = 56_000_000_000L
        repeat(40) {
            nowNs = t
            timeline.onChunk(false, "text%012d".format(it), 0, 1, 1040)
            t += 3_000_000_000L
        }
        repeat(125) {
            nowNs = t
            timeline.onChunk(false, "file", 2, 125, 8142)
            t += 190_000_000L
        }
        nowNs = t
        val line = timeline.sessionLine("peer=x", 1200, "GATT", "state")
        val goodput = sessionField(line, "goodputOutKbps").toInt()
        val active = sessionField(line, "activeOutKbps").toInt()
        assertTrue("the first-to-last rate is diluted: $goodput", goodput < 100)
        assertTrue("the active rate is the link rate: $active", active in 300..400)
        assertEquals("0", sessionField(line, "activeInKbps"))
        assertEquals("41", sessionField(line, "bundles"))
    }

    @Test
    fun theFilesLineSurvivesTheBundleLimitInASessionOfManyTexts() {
        val timeline = TransferTimeline { nowNs }
        repeat(40) {
            nowNs += 3_000_000_000L
            timeline.onChunk(false, "text%012d".format(it), 0, 1, 1040)
        }
        repeat(125) {
            nowNs += 190_000_000L
            timeline.onChunk(false, "filefilefilefile", 2, 125, 8142)
        }
        timeline.onBundleComplete(false, "filefilefilefile")
        val lines = timeline.bundleLines("GATT")
        assertEquals(32, lines.size)
        assertTrue("the file comes first: ${lines.first()}", lines.first().contains("class=2 chunks=125/125"))
        assertTrue(lines.first().contains("complete=true"))
    }
}
