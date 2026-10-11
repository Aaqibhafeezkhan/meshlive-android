package com.example.bluetoothtest

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

/**
 * Unicode TR39 (UTS #39) confusable skeletons, for flagging look-alike names (naming design review
 * R7: the script check alone lets an all-Cyrillic "Аӏісе" through, which reads as "Alice").
 *
 * skeleton(X) = NFD(X), minus invisible format characters, each code point replaced by its
 * `confusables.txt` "MA" target, then NFD again (UTS #39 section 4). Two strings that differ but share
 * a skeleton are visually confusable. The table is the unmodified Unicode 18.0.0 data bundled as
 * `assets/uts39/confusables_ma.txt` (Unicode License v3, `assets/third_party_licenses/unicode.txt`);
 * [Confusables.install] loads it at app start. If the asset cannot be read, [ConfusableTable.MINIMAL]
 * (below) still maps the common Latin look-alikes from Cyrillic and Greek, so a name is never
 * compared without any table.
 *
 * Used only to warn: nothing is blocked or rewritten, and no skeleton is stored or sent.
 */
internal class ConfusableTable(private val map: Map<Int, IntArray>) {
    val size: Int get() = map.size

    fun skeleton(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val mapped = StringBuilder(decomposed.length)
        decomposed.codePoints().forEach { codePoint ->
            if (isIgnorable(codePoint)) return@forEach
            val target = map[codePoint]
            if (target == null) mapped.appendCodePoint(codePoint) else target.forEach { mapped.appendCodePoint(it) }
        }
        return Normalizer.normalize(mapped, Normalizer.Form.NFD)
    }

    companion object {
        /** Parses `<hex source> <hex target...>` lines; blank lines and `#` comments are skipped. */
        fun parse(lines: Sequence<String>): ConfusableTable {
            val map = HashMap<Int, IntArray>(8192)
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val fields = line.split(' ').filter { it.isNotEmpty() }
                if (fields.size < 2) return@forEach
                val source = fields[0].toIntOrNull(16) ?: return@forEach
                val targets = fields.drop(1).map { it.toIntOrNull(16) ?: return@forEach }
                map[source] = targets.toIntArray()
            }
            return ConfusableTable(map)
        }

        fun parse(stream: InputStream): ConfusableTable =
            stream.bufferedReader(Charsets.UTF_8).use { parse(it.lineSequence()) }

        /** Default-ignorable format characters and variation selectors never change a skeleton. */
        private fun isIgnorable(codePoint: Int): Boolean =
            Character.getType(codePoint) == Character.FORMAT.toInt() ||
                codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF || codePoint == 0x034F

        /**
         * Documented minimal table: the Cyrillic and Greek letters that look like Latin letters, plus
         * the I / l / 1 / 0 / O family. Every entry is a line of confusables.txt (Unicode 18.0.0, MA).
         * Used only when the bundled asset cannot be read.
         */
        val MINIMAL: ConfusableTable by lazy {
            val pairs = mapOf(
                // Cyrillic lower case -> Latin
                0x0430 to 'a', 0x0435 to 'e', 0x043E to 'o', 0x0440 to 'p', 0x0441 to 'c', 0x0443 to 'y',
                0x0445 to 'x', 0x0456 to 'i', 0x0458 to 'j', 0x0455 to 's', 0x0501 to 'd', 0x051B to 'q',
                0x051D to 'w', 0x04BB to 'h', 0x04CF to 'l',
                // Cyrillic upper case -> Latin
                0x0410 to 'A', 0x0412 to 'B', 0x0415 to 'E', 0x041A to 'K', 0x041C to 'M', 0x041D to 'H',
                0x041E to 'O', 0x0420 to 'P', 0x0421 to 'C', 0x0422 to 'T', 0x0425 to 'X', 0x0406 to 'I',
                0x0408 to 'J', 0x0405 to 'S',
                // Greek -> Latin
                0x03BF to 'o', 0x03BD to 'v', 0x03B9 to 'i', 0x0391 to 'A', 0x0392 to 'B', 0x0395 to 'E',
                0x0396 to 'Z', 0x0397 to 'H', 0x0399 to 'I', 0x039A to 'K', 0x039C to 'M', 0x039D to 'N',
                0x039F to 'O', 0x03A1 to 'P', 0x03A4 to 'T', 0x03A5 to 'Y', 0x03A7 to 'X',
            )
            val map = HashMap<Int, IntArray>()
            pairs.forEach { (source, latin) -> map[source] = intArrayOf(latin.code) }
            // MA maps capital I, digit 1 and the vertical bar to lower-case L, and 0 to capital O.
            map[0x0049] = intArrayOf('l'.code)
            map[0x0031] = intArrayOf('l'.code)
            map[0x007C] = intArrayOf('l'.code)
            map[0x0030] = intArrayOf('O'.code)
            ConfusableTable(map)
        }
    }
}

/** The table in use: the bundled Unicode data once installed, else the minimal fallback. */
internal object Confusables {
    const val ASSET = "uts39/confusables_ma.txt"

    @Volatile var table: ConfusableTable = ConfusableTable.MINIMAL
        private set

    /** Loads the bundled table (about 6,700 entries, 85 KB); on any failure the minimal table stays. */
    fun install(context: Context) {
        if (table !== ConfusableTable.MINIMAL) return
        val loaded = runCatching { context.assets.open(ASSET).use { ConfusableTable.parse(it) } }
            .onFailure { Log.w("BluetoothVideo", "Confusables asset not loaded; using the minimal table", it) }
            .getOrNull()
        if (loaded != null && loaded.size > 1_000) table = loaded
    }

    internal fun installForTest(replacement: ConfusableTable) {
        table = replacement
    }
}

/** Finds names that read like another name without being the same text. */
internal object LookAlike {
    /**
     * The first of [others] that looks like [name] but is not the same text, else null. The skeletons
     * are compared case-insensitively, so "alice" and "Alice" are one reading of the word; folding the
     * case after the skeleton keeps the capital I / lower-case l pair confusable.
     */
    fun find(name: String, others: Collection<String>, table: ConfusableTable = Confusables.table): String? {
        val key = key(name, table)
        if (key.isEmpty()) return null
        return others.firstOrNull { other -> other != name && other.isNotBlank() && key(other, table) == key }
    }

    private fun key(text: String, table: ConfusableTable): String = table.skeleton(text.trim()).lowercase(Locale.ROOT)
}
