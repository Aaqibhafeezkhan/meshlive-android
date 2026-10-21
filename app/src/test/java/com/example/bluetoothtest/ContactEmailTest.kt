package com.example.bluetoothtest

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class ContactEmailTest {
    private val email = "meshlive.dev@gmail.com"

    @Test fun settingsAndReportBuildOnlyTheDeclaredEmailDraft() {
        for (concern in listOf(false, true)) {
            var launches = 0
            ContactEmail.open(email, concern, launch = { action, data ->
                launches++
                assertEquals("android.intent.action.SENDTO", action)
                val uri = URI(data)
                assertEquals("mailto", uri.scheme)
                assertEquals(email, uri.rawSchemeSpecificPart.substringBefore('?'))
                assertNull(uri.fragment)
                val fields = uri.rawSchemeSpecificPart.substringAfter('?').split('&').associate {
                    val parts = it.split('=', limit = 2)
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
                assertEquals(mapOf(
                    "subject" to if (concern) "MeshLive: report a concern" else "MeshLive",
                    "body" to if (concern) "What happened:" else "",
                ), fields)
                true
            }, fallback = { fail("An available email app must not show the fallback") })
            assertEquals(1, launches)
        }
    }

    @Test fun missingEmailAppOffersTheSameAddressForBothEntryPoints() {
        for (concern in listOf(false, true)) {
            var launches = 0
            val shown = mutableListOf<String>()
            ContactEmail.open(email, concern, launch = { _, _ -> launches++; false }, fallback = { shown += it })
            assertEquals(1, launches)
            assertEquals(listOf(email), shown)
        }
    }
}
