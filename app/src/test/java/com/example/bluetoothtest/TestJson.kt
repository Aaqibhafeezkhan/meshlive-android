package com.example.bluetoothtest

import java.io.File

/** Minimal JSON reader/writer for JVM tests (android.jar's org.json is a stub there). */
internal object TestJson {
    fun parse(text: String): Any? = Parser(text).run { value().also { skipSpace(); require(pos == text.length) { "Trailing JSON at $pos" } } }

    fun parseFile(file: File): Any? = parse(file.readText(Charsets.UTF_8))

    fun write(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(
            separator = ",\n",
            prefix = "{\n",
            postfix = "\n$indent}",
        ) { (key, item) -> "$indent  ${quote(key.toString())}: ${write(item, "$indent  ")}" }
        is List<*> -> if (value.isEmpty()) "[]" else if (value.all { it !is Map<*, *> && it !is List<*> }) {
            value.joinToString(", ", "[", "]") { write(it, indent) }
        } else {
            value.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${write(it, "$indent  ")}" }
        }
        else -> error("Unsupported JSON value ${value::class}")
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            require(pos < text.length) { "Unexpected end of JSON" }
            return when (text[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            require(text.startsWith(word, pos)) { "Bad literal at $pos" }
            pos += word.length
            return result
        }

        private fun number(): Number {
            val start = pos
            while (pos < text.length && text[pos] in "+-0123456789.eE") pos++
            val token = text.substring(start, pos)
            require(token.isNotEmpty()) { "Bad JSON value at $start" }
            return token.toLongOrNull() ?: token.toDouble()
        }

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                require(pos < text.length) { "Unterminated string" }
                val ch = text[pos++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val esc = text[pos++]
                        when (esc) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> out.append(esc)
                        }
                    }
                    else -> out.append(ch)
                }
            }
        }

        private fun array(): List<Any?> {
            pos++
            val items = mutableListOf<Any?>()
            skipSpace()
            if (text[pos] == ']') { pos++; return items }
            while (true) {
                items += value()
                skipSpace()
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return items
                    else -> error("Expected , or ] at ${pos - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val items = LinkedHashMap<String, Any?>()
            skipSpace()
            if (text[pos] == '}') { pos++; return items }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                require(text[pos++] == ':') { "Expected : at ${pos - 1}" }
                items[key] = value()
                skipSpace()
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return items
                    else -> error("Expected , or } at ${pos - 1}")
                }
            }
        }
    }
}

/** The project root (the directory holding tools/benchmark_devices.example.json), from the test's working directory. */
internal fun projectRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        if (File(dir, "tools/benchmark_devices.example.json").isFile) return dir
        dir = dir.parentFile
    }
    error("Project root with tools/benchmark_devices.example.json not found from ${File("").absolutePath}")
}
