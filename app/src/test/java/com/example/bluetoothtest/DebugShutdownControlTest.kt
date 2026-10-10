package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A runner's `shutdown` control that reaches an already-exited app starts a fresh process; that
 * start must exit instead of bringing the app up (lingering processes quarantined phones).
 */
class DebugShutdownControlTest {
    @Test
    fun onlyAFreshDebugStartCarryingShutdownExits() {
        assertTrue(isColdStartShutdown(debug = true, restoring = false, control = "shutdown"))
        assertFalse("release builds ignore controls", isColdStartShutdown(debug = false, restoring = false, control = "shutdown"))
        assertFalse("a restored activity is not a control delivery", isColdStartShutdown(debug = true, restoring = true, control = "shutdown"))
        assertFalse(isColdStartShutdown(debug = true, restoring = false, control = null))
        assertFalse(isColdStartShutdown(debug = true, restoring = false, control = "end"))
    }
}
