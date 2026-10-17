package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class CertificateTextTest {
    @Test
    fun showsTheFirstFourAndLastTwoBytes() {
        val full = "C5:43:5D:9B:CB:90:A4:7A:A8:6D:7F:E8:66:AA:BE:AD:79:EC:DE:15:D9:8C:B5:96:A5:DB:6E:BF:1E:BB:0D:B3"
        assertEquals("C5:43:5D:9B … 0D:B3", CertificateText.short(full))
        assertEquals("AB:CD", CertificateText.short("AB:CD"))
    }
}
