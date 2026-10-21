package com.example.bluetoothtest

import android.os.Bundle

/**
 * DEBUG launch echo (2026-10-20): one `launch_extras` line per launch with every `test_` extra that reached the app,
 * sorted, so an A/B run shows whether its arm's switch arrived (blankframes-ab could not tell from its logs whether
 * `test_drop_leading_blank_frames` was delivered). Values longer than [MAX_VALUE_CHARS] are cut.
 */
internal object LaunchExtrasEcho {
    const val MAX_VALUE_CHARS = 64

    fun line(values: Map<String, Any?>): String {
        val tests = values.filterKeys { it.startsWith("test_") }.toSortedMap()
        val body = tests.entries.joinToString(",") { (key, value) -> "$key=${render(value)}" }
        return "BENCHMARK launch_extras: count=${tests.size}" + if (body.isEmpty()) "" else " | $body"
    }

    @Suppress("DEPRECATION")
    fun line(extras: Bundle?): String {
        if (extras == null) return line(emptyMap<String, Any?>())
        return line(extras.keySet().associateWith { key -> runCatching { extras.get(key) }.getOrNull() })
    }

    private fun render(value: Any?): String {
        val text = when (value) {
            null -> "null"
            is IntArray -> value.joinToString("/")
            is LongArray -> value.joinToString("/")
            is Array<*> -> value.joinToString("/")
            else -> value.toString()
        }.replace(",", ";").replace("|", "/")
        return if (text.length > MAX_VALUE_CHARS) text.take(MAX_VALUE_CHARS) + "..." else text
    }
}
