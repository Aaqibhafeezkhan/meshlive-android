package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase 1c resources checked against the UI spec without a device (Robolectric is not a dependency of this project): every
 * string a model asks for exists, the new layouts use only string resources, only existing drawables and colours, nothing is
 * coloured red or green outside the spec, no upper-case styling, and no string can print a raw identifier.
 */
class MessagingV2UiResourcesTest {
    private val main: File = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private val res = File(main, "res")

    private val newLayouts = listOf(
        "view_thread.xml", "item_thread_message.xml", "item_thread_separator.xml", "item_conversation_row.xml",
        "sheet_thread_attach.xml", "sheet_thread_info.xml", "view_mv2_privacy_rows.xml",
    )

    private fun layout(name: String) = File(res, "layout/$name").readText()

    private fun allStrings(): Map<String, String> {
        val out = HashMap<String, String>()
        res.resolve("values").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.forEach { file ->
            Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
                .forEach { out[it.groupValues[1]] = it.groupValues[2] }
        }
        return out
    }

    private fun mainSources(): List<File> = File(main, "java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun everyStringKeyTheCodeAsksForExists() {
        val keys = Regex("\"(mv2_ui_[a-z0-9_]+)\"")
        val used = mainSources().flatMap { file -> keys.findAll(file.readText()).map { it.groupValues[1] }.toList() }.toSet()
        val missing = used - XmlThreadText.keys
        assertTrue("strings missing from strings_messaging_v2_ui.xml: $missing", missing.isEmpty())
        assertTrue("the scan found the keys the code asks for", used.isNotEmpty())
    }

    @Test
    fun everyStringKeyOfTheModelsHelpersIsBuiltFromExistingNames() {
        // The names built from a list (timer and auto-download labels) are not literals above: check each one.
        DisappearChoices.SECONDS.forEach { assertTrue(DisappearLabels.key(it), DisappearLabels.key(it) in XmlThreadText.keys) }
        AutoDownloadChoices.MEGABYTES.forEach { assertTrue(AutoDownloadChoices.key(it), AutoDownloadChoices.key(it) in XmlThreadText.keys) }
        Presentation.entries.forEach { assertTrue(ConversationRows.presentationKey(it) in XmlThreadText.keys) }
    }

    @Test
    fun layoutsReferenceOnlyExistingStringsDrawablesAndColours() {
        val strings = allStrings().keys
        val colours = Regex("<color name=\"([a-z0-9_]+)\"").findAll(File(res, "values/colors.xml").readText()).map { it.groupValues[1] }.toSet()
        val drawables = res.resolve("drawable").listFiles()!!.map { it.nameWithoutExtension }.toSet()
        val layouts = res.resolve("layout").listFiles()!!.map { it.nameWithoutExtension }.toSet()
        for (name in newLayouts) {
            val xml = layout(name)
            Regex("@string/([a-z0-9_]+)").findAll(xml).forEach { assertTrue("$name: string ${it.groupValues[1]}", it.groupValues[1] in strings) }
            Regex("@color/([a-z0-9_]+)").findAll(xml).forEach { assertTrue("$name: colour ${it.groupValues[1]}", it.groupValues[1] in colours) }
            Regex("@drawable/([a-z0-9_]+)").findAll(xml).forEach { assertTrue("$name: drawable ${it.groupValues[1]}", it.groupValues[1] in drawables) }
            Regex("@layout/([a-z0-9_]+)").findAll(xml).forEach { assertTrue("$name: layout ${it.groupValues[1]}", it.groupValues[1] in layouts) }
        }
    }

    @Test
    fun layoutsHaveNoHardCodedTextNoCapsAndNoColourOutsideTheSpec() {
        // Red is for live and hang up only, green for the verified check only (UI spec).
        val red = listOf("danger", "live_red", "call_end", "record_red", "mismatch_red", "danger_container")
        for (name in newLayouts) {
            val xml = layout(name)
            Regex("android:(text|hint|contentDescription)=\"([^\"]*)\"").findAll(xml).forEach {
                assertTrue("$name: hard-coded ${it.groupValues[1]} \"${it.groupValues[2]}\"", it.groupValues[2].startsWith("@string/"))
            }
            assertFalse("$name: upper-case styling", xml.contains("textAllCaps=\"true\""))
            red.forEach { assertFalse("$name uses @color/$it", xml.contains("@color/$it")) }
            val green = Regex("@color/(success|call_accept)").findAll(xml).map { it.range.first }.toList()
            green.forEach { at ->
                // Only the verified check may be green.
                val context = xml.substring(maxOf(0, at - 400), at)
                assertTrue("$name: green outside the verified check", context.contains("ic_check_circle"))
            }
        }
    }

    @Test
    fun stringsAreSentenceCaseWithoutRawIdentifiers() {
        val own = File(res, "values/strings_messaging_v2_ui.xml").readText()
        val all = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(own).toList()
        assertTrue(all.isNotEmpty())
        for (match in all) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].replace("\\'", "'")
            val first = value.first { it.isLetterOrDigit() || it == '%' }
            assertTrue("$key starts with a capital: $value", first.isUpperCase() || first.isDigit() || first == '%')
            // No shouting: a run of four or more capital letters is a raw ID or an abbreviation the spec does not use.
            assertFalse("$key shouts: $value", Regex("[A-Z]{4,}").containsMatchIn(value))
            assertFalse("$key has a hex run: $value", Regex("[0-9a-f]{8,}").containsMatchIn(value))
            assertFalse("$key mentions an ID or handle", Regex("\\b(node ?id|handle|fingerprint|prefix)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value))
        }
    }

    @Test
    fun theNewScreensKeepTheThreadScreenInsideTheMainLayoutAndOffByDefault() {
        val main = File(res, "layout/activity_main.xml").readText()
        assertTrue(main.contains("@layout/view_thread"))
        assertTrue(main.contains("@layout/view_mv2_privacy_rows"))
        // Hidden until a Contacts row is tapped, and the privacy rows only with the feature on.
        assertTrue(layout("view_thread.xml").contains("android:visibility=\"gone\""))
        assertTrue(layout("view_mv2_privacy_rows.xml").contains("android:visibility=\"gone\""))
    }
}
