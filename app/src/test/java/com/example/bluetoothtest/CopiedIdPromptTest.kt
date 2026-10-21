package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * win-userflow compact-ab a15s-compact-3/-4: a PC announcing one compact call ID from two random addresses at once
 * (162 alternations) was flagged as a copied ID, so the row's video button opened the "Call anyway" prompt and no call
 * phase followed; nothing was logged.
 */
class CopiedIdPromptTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun oneIdAlternatingBetweenTwoAddressesIsFlaggedAsCopied() {
        val tracker = CopiedIdTracker()
        val addresses = listOf("69:7D:F9:07:34:AF", "7C:E0:51:8B:11:A8")
        var flagged = false
        for (i in 0 until 20) {
            flagged = tracker.observe("473a9acfda82", addresses[i % 2], routeCost = 0, nowMs = i * 600L) || flagged
        }
        assertTrue(flagged)
        assertTrue(tracker.warned("473a9acfda82"))

        // One address only (the M31's view of the same PC: one address change, no overlap) is not a copy.
        val single = CopiedIdTracker()
        single.observe("84e215c78630", "48:5E:86:79:A3:BE", 0, 0L)
        single.observe("84e215c78630", "7F:A0:BE:BD:FA:39", 0, 60_000L)
        assertFalse(single.warned("84e215c78630"))
    }

}
