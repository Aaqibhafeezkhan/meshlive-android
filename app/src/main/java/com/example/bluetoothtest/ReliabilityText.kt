package com.example.bluetoothtest

/** Short text for the Help page's background-reliability cards (2026-10-17 redesign). */
internal object ReliabilityText {
    /** The first sentence of [guidance] (up to the first ". " or line break), or all of it when there is none. */
    fun firstSentence(guidance: String): String {
        val text = guidance.trim()
        val lineEnd = text.indexOf('\n').takeIf { it >= 0 } ?: text.length
        val firstLine = text.substring(0, lineEnd).trim()
        val dot = firstLine.indexOf(". ")
        return if (dot >= 0) firstLine.substring(0, dot + 1) else firstLine
    }
}
