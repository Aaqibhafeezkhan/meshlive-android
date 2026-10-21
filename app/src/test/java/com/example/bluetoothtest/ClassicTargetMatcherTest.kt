package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicTargetMatcherTest {
    @Test
    fun deviceNameIgnoresSurroundingWhitespaceAndCase() {
        assertTrue(classicTargetMatches("Robin ", "AA:BB", " robin"))
    }

    @Test
    fun addressMatchRemainsCaseInsensitive() {
        assertTrue(classicTargetMatches(null, "AA:BB", "aa:bb"))
        assertFalse(classicTargetMatches("Another phone", "AA:BB", "Robin"))
    }

    @Test
    fun partialNameDoesNotSelectAnUnrelatedPairedDevice() {
        assertFalse(classicTargetMatches("Galaxy A55 5G", "AA:BB", "Galaxy"))
    }
}
