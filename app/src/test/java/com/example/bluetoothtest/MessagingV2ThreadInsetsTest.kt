package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The thread overlay keeps clear of the status bar, the navigation buttons and the keyboard (Android 10 phone finding). */
class MessagingV2ThreadInsetsTest {
    private val main: File = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    @Test
    fun theTitleClearsTheStatusBarAndTheComposerClearsTheNavigationButtons() {
        // 3-button navigation, status bar 72 px, nav bar 144 px, no keyboard.
        val pad = ThreadInsets.padding(0, 72, 0, 144, 0, 144)
        assertEquals(ThreadInsets.Pad(0, 72, 0, 144), pad)
    }

    @Test
    fun theComposerMovesAboveTheKeyboardOnBothGenerationsOfAndroid() {
        // Android 11 and later report the keyboard on its own.
        assertEquals(900, ThreadInsets.padding(0, 72, 0, 144, 900, 0).bottom)
        // Android 10 folds the keyboard into the system window inset.
        assertEquals(900, ThreadInsets.padding(0, 72, 0, 144, 0, 900).bottom)
        // Keyboard hidden again: back to the navigation bar.
        assertEquals(144, ThreadInsets.padding(0, 72, 0, 144, 0, 144).bottom)
    }

    @Test
    fun aCutoutOrGestureBarKeepsItsSides() {
        assertEquals(ThreadInsets.Pad(96, 110, 96, 0), ThreadInsets.padding(96, 110, 96, 0, 0, 0))
    }

    @Test
    fun theOverlayAsksForItsInsetsWhenItOpensBecauseItsListenerIsSetAfterTheFirstDispatch() {
        val source = File(main, "java/com/example/bluetoothtest/MessagingV2ThreadScreen.kt").readText()
        val open = source.substringAfter("fun open(nodeIdHex: String)").substringBefore("fun close()")
        assertTrue(open.contains("ViewCompat.requestApplyInsets(root)"))
        assertTrue(open.contains("getRootWindowInsets(root)"))
        // The overlay pads itself; the layout must not also fit the system windows (that would pad twice).
        assertFalse(File(main, "res/layout/view_thread.xml").readText().contains("fitsSystemWindows"))
    }
}
