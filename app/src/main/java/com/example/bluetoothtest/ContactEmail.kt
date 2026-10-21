package com.example.bluetoothtest

import java.net.URLEncoder

/** Only explicit draft text goes to the email app; no app or peer data is accepted here. */
internal object ContactEmail {
    fun open(email: String, concern: Boolean = false, launch: (String, String) -> Boolean, fallback: (String) -> Unit) {
        fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        val subject = if (concern) "MeshLive: report a concern" else "MeshLive"
        val body = if (concern) "What happened:" else ""
        val uri = "mailto:$email?subject=${encode(subject)}&body=${encode(body)}"
        if (!launch("android.intent.action.SENDTO", uri)) fallback(email)
    }
}
