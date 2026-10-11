package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GapNameProbeTest {
    @Test
    fun theLaunchExtraIsUnderstoodInEveryTypeAdbCanSend() {
        // `--es test_gap_name_probe read|pair`, `--ez test_gap_name_probe true`, `--ei ... 1`: all start the probe.
        assertEquals("read", GapNameProbe.optionFrom("read"))
        assertEquals("pair", GapNameProbe.optionFrom("pair"))
        assertEquals("pair", GapNameProbe.optionFrom(" PAIR "))
        assertEquals("read", GapNameProbe.optionFrom(true))
        assertEquals("read", GapNameProbe.optionFrom("true"))
        assertEquals("read", GapNameProbe.optionFrom(1))
        assertNull(GapNameProbe.optionFrom(null))
        assertNull(GapNameProbe.optionFrom(false))
        assertNull(GapNameProbe.optionFrom("false"))
        assertNull(GapNameProbe.optionFrom(""))
    }

    @Test
    fun everyReasonTheProbeCannotRunIsNamedInAFixedOrder() {
        fun reason(
            debug: Boolean = true,
            running: Boolean = false,
            adapter: Boolean = true,
            on: Boolean = true,
            permission: Boolean = true,
            scanner: Boolean = true,
        ) = GapNameProbe.skipReason(debug, running, adapter, on, permission, scanner)
        assertNull(reason())
        assertEquals("not_a_debug_build", reason(debug = false))
        assertEquals("already_running", reason(running = true))
        assertEquals("no_bluetooth_adapter", reason(adapter = false, on = false))
        assertEquals("bluetooth_off", reason(on = false))
        assertEquals("permission_missing", reason(permission = false))
        assertEquals("no_le_scanner", reason(scanner = false))
    }
}
