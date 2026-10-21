package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A settings row's value moves under its title only when the two do not fit on one line (S22 review 2026-10-21). */
class TitleValueFitTest {
    @Test
    fun aShortValueStaysBesideTheTitle() {
        // "Video quality" + "Auto" on a phone-width row.
        assertFalse(TitleValueFit.stack(titleWidth = 300, valueWidth = 90, gap = 48, available = 800))
    }

    @Test
    fun aLongValueMovesUnderTheTitle() {
        // "Who sees my name" + "Everyone I call or answer", or any row at the largest font size.
        assertTrue(TitleValueFit.stack(titleWidth = 420, valueWidth = 520, gap = 48, available = 800))
    }

    @Test
    fun anExactFitStaysOnOneLine() {
        assertFalse(TitleValueFit.stack(titleWidth = 400, valueWidth = 352, gap = 48, available = 800))
    }
}
