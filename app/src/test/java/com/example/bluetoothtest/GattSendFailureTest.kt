package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GattSendFailureTest {
    @Test fun aLocalCloseIsReportedAsClosedNotAsAStackStatus() {
        // close() released the latch; the status field still holds the GATT_FAILURE (257) placeholder.
        assertEquals("GATT output is closed", gattSendFailure("GATT notification", 257, closedLocally = true))
        assertEquals("GATT output is closed", gattSendFailure("GATT video write", 257, closedLocally = true))
    }

    @Test fun aStackFailureKeepsItsStatus() {
        assertEquals("GATT notification failed (status 143)", gattSendFailure("GATT notification", 143, closedLocally = false))
        assertEquals("GATT video write failed (status 257)", gattSendFailure("GATT video write", 257, closedLocally = false))
    }

    @Test fun aSuccessfulSendIsNoFailure() {
        assertNull(gattSendFailure("GATT notification", 0, closedLocally = false))
    }
}
