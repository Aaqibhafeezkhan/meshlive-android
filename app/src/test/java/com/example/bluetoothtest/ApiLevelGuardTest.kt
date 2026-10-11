package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM that runs the unit tests has every Java 11+ method; a phone on Android 10-12 does not. A call to a
 * missing platform method is a `NoSuchMethodError`, an Error that no `catch (Exception)` sees, and it ends the
 * app process. rotid-958c8a53-p4-relayed-calls-gatt-probe: the first on-demand relay called
 * `InputStream.nullInputStream()` (API 33; minSdk is 29) and crashed the helper (ASUS, Android 11) 1.2 s after it
 * accepted the SETUP. D8 does not desugar these, so the source must not use them. This is the cheap source
 * guard; `tools/dex_api_scan.py` checks a built APK against the SDK's api-versions.xml.
 */
class ApiLevelGuardTest {
    private val minSdk: Int = run {
        val gradle = File("build.gradle.kts").takeIf { it.isFile } ?: File("app/build.gradle.kts")
        Regex("""minSdk\s*=\s*(\d+)""").find(gradle.readText())!!.groupValues[1].toInt()
    }

    /** Calls the app must not make without an SDK_INT guard: pattern to the API level that introduced the method. */
    private val newerThanMinSdk = listOf(
        Regex("""\bnullInputStream\s*\(""") to 33,
        Regex("""\bnullOutputStream\s*\(""") to 33,
        Regex("""\bnullReader\s*\(""") to 33,
        Regex("""\bnullWriter\s*\(""") to 33,
        // InputStream.readAllBytes() takes no argument; Files.readAllBytes(path) (API 26) does.
        Regex("""\.readAllBytes\s*\(\s*\)""") to 33,
        Regex("""\.readNBytes\s*\(""") to 33,
        Regex("""\.skipNBytes\s*\(""") to 33,
        Regex("""\.transferTo\s*\(""") to 33,
        Regex("""java\.util\.(?:List|Set|Map)\.(?:of|copyOf)\s*\(""") to 30,
        Regex("""\bObjects\.(?:checkFromIndexSize|checkFromToIndex|checkIndex)\s*\(""") to 30,
    )

    private fun mainSources(): List<File> {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun mainSourcesDoNotCallMethodsNewerThanMinSdk() {
        val files = mainSources()
        assertTrue("main sources not found", files.size > 100)
        val offenders = ArrayList<String>()
        for (file in files) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                // Comments and KDoc may name the banned calls (RelayDutySupport explains why they are banned).
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                val code = line.substringBefore("//")
                for ((pattern, level) in newerThanMinSdk) {
                    if (level > minSdk && pattern.containsMatchIn(code) && "Build.VERSION.SDK_INT" !in code) {
                        offenders += "${file.name}:${index + 1} needs API $level (minSdk $minSdk): ${line.trim()}"
                    }
                }
            }
        }
        assertEquals("Calls newer than minSdk $minSdk:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun theGuardRecognisesTheCallThatCrashedTheHelper() {
        val crashed = "upstream = TransportConnection(java.io.InputStream.nullInputStream(), java.io.OutputStream.nullOutputStream())"
        val hits = newerThanMinSdk.count { (pattern, level) -> level > minSdk && pattern.containsMatchIn(crashed) }
        assertEquals(2, hits)
        assertTrue(Regex("""\.readAllBytes\s*\(\s*\)""").containsMatchIn("input.readAllBytes()"))
        assertTrue(!Regex("""\.readAllBytes\s*\(\s*\)""").containsMatchIn("Files.readAllBytes(path)"))
    }
}
