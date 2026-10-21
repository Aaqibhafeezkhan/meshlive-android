package com.example.bluetoothtest

import org.junit.Assert.*
import org.junit.Test

class ReleaseTagOrderingTest {
    @Test fun largeNumericPrereleasePartsStillSortAsNumbersBeforeText() {
        fun v(tag: String) = ReleaseVersion.parse(tag)!!
        assertTrue(v("1.0.0-beta.9999999999") < v("1.0.0-beta.10000000000"))
        assertTrue(v("1.0.0-beta.99999999999999999999") < v("1.0.0-beta.alpha"))
        assertTrue(v("1.0.0-beta.-1") > v("1.0.0-beta.1"))
    }
    @Test fun nonCanonicalNumericTagsCannotProduceADifferentEqualVersion() {
        for (bad in listOf("01.0.0", "1.00.0", "1.0.00", "1.0.0-beta.01")) assertNull(ReleaseVersion.parse(bad))
        assertNotNull(ReleaseVersion.parse("1.0.0-beta.0"))
    }
}
