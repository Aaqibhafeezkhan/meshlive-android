package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class ReliabilityTextTest {
    @Test
    fun keepsTheFirstSentenceAndDropsManualSteps() {
        assertEquals(
            "Android may pause the app in the background, which can drop calls and shared video.",
            ReliabilityText.firstSentence(
                "Android may pause the app in the background, which can drop calls and shared video. Allow it to run without battery restrictions.",
            ),
        )
        assertEquals(
            "After a few months without use, permissions are removed.",
            ReliabilityText.firstSentence("After a few months without use, permissions are removed.\n- Settings > Apps > (this app): turn off it."),
        )
        assertEquals("No period here", ReliabilityText.firstSentence("  No period here  "))
    }
}
