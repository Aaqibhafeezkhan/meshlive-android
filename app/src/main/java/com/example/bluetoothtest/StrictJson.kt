package com.example.bluetoothtest

/**
 * A small strict JSON reader for the GitHub release response (updater). It exists because
 * `org.json` is a stub in JVM unit tests and the response is untrusted input: depth, text length and
 * string length are bounded, duplicate keys and trailing text are rejected, and numbers keep their
 * exact `Long`/`Double` form. Values are `Map<String, Any?>`, `List<Any?>`, `String`, `Long`,
 * `Double`, `Boolean` and `null`.
 */
internal object StrictJson {
    const val MAX_TEXT_CHARS = 1 shl 20
    const val MAX_DEPTH = 24
    const val MAX_STRING_CHARS = 256 * 1024

    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        if (text.length > MAX_TEXT_CHARS) throw JsonException("JSON text is too long")
        val parser = Parser(text)
        parser.skipSpace()
        val value = parser.value(0)
        parser.skipSpace()
        if (!parser.atEnd()) throw JsonException("Trailing text after the JSON value")
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun objectOf(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arrayOf(value: Any?): List<Any?>? = value as? List<Any?>

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipSpace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("JSON is nested too deeply")
            if (atEnd()) throw JsonException("Unexpected end of JSON")
            return when (val c = text[pos]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw JsonException("Unexpected character at $pos")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!text.startsWith(word, pos)) throw JsonException("Invalid literal at $pos")
            pos += word.length
            return result
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val result = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipSpace()
                if (peek() != '"') throw JsonException("Expected a key at $pos")
                val key = string()
                skipSpace()
                if (peek() != ':') throw JsonException("Expected ':' at $pos")
                pos++
                skipSpace()
                if (result.containsKey(key)) throw JsonException("Duplicate key")
                result[key] = value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            pos++
            val result = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                skipSpace()
                result += value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else throw JsonException("Unexpected end of JSON")

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) throw JsonException("Unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (pos >= text.length) throw JsonException("Unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw JsonException("Short unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    throw JsonException("Invalid unicode escape")
                                }
                                out.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Invalid escape at $pos")
                        }
                    }
                    c < ' ' -> throw JsonException("Control character in string")
                    else -> out.append(c)
                }
                if (out.length > MAX_STRING_CHARS) throw JsonException("String is too long")
            }
        }

        private fun number(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            val digitsStart = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos == digitsStart) throw JsonException("Invalid number at $start")
            if (text[digitsStart] == '0' && pos - digitsStart > 1) throw JsonException("Leading zero at $start")
            var floating = false
            if (pos < text.length && text[pos] == '.') {
                floating = true
                pos++
                val fractionStart = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                if (pos == fractionStart) throw JsonException("Invalid fraction at $start")
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                floating = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                val exponentStart = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                if (pos == exponentStart) throw JsonException("Invalid exponent at $start")
            }
            val token = text.substring(start, pos)
            return if (floating) {
                token.toDouble()
            } else {
                token.toLongOrNull() ?: throw JsonException("Integer out of range at $start")
            }
        }
    }
}
