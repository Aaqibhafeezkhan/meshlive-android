package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicTargetMatcherTest {
    @Test
    fun deviceNameIgnoresSurroundingWhitespaceAndCase() {
        assertTrue(classicTargetMatches("Pradyun ", "AA:BB", " pradyun"))
    }

    @Test
    fun addressMatchRemainsCaseInsensitive() {
        assertTrue(classicTargetMatches(null, "AA:BB", "aa:bb"))
        assertFalse(classicTargetMatches("Another phone", "AA:BB", "Pradyun"))
    }

    @Test
    fun partialNameDoesNotSelectAnUnrelatedPairedDevice() {
        assertFalse(classicTargetMatches("Galaxy A55 5G", "AA:BB", "Galaxy"))
    }
}
