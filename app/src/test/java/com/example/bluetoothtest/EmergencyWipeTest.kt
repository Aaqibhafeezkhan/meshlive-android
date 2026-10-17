package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmergencyWipeTest {
    private lateinit var root: File
    private lateinit var dataDir: File
    private lateinit var externalFiles: File
    private lateinit var externalCache: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("emergency-wipe").toFile()
        dataDir = File(root, "data/com.example.bluetoothtest").apply { mkdirs() }
        externalFiles = File(root, "sdcard/Android/data/com.example.bluetoothtest/files").apply { mkdirs() }
        externalCache = File(root, "sdcard/Android/data/com.example.bluetoothtest/cache").apply { mkdirs() }
        PeerIdentityGate.resetForTest()
        WipeGuard.resetForTest()
    }

    @After
    fun tearDown() {
        PeerIdentityGate.resetForTest()
        WipeGuard.resetForTest()
        root.deleteRecursively()
    }

    /** Context-free stand-in for the app: a data directory tree, a key store and preference files. */
    private open inner class FakeEnvironment : WipeEnvironment {
        val aliases = linkedSetOf<String>()
        val preferences = linkedMapOf<String, MutableMap<String, String>>()
        val events = mutableListOf<String>()
        val marker = File(dataDir, "no_backup/${EmergencyWipe.PENDING_MARKER}")
        var inMemoryReset = 0
        var markerClearFails = false

        override fun writePendingMarker() {
            events += "marker"
            marker.parentFile.mkdirs()
            marker.writeText("1")
        }

        override fun clearPendingMarker() {
            events += "marker-cleared"
            check(!markerClearFails)
            marker.delete()
        }

        override fun preservedFiles(): Set<File> = setOf(marker)

        override fun runTerminators(terminators: List<WipeSessionTerminator>, timeoutMs: Long): TerminatorOutcome {
            events += "terminators"
            return EmergencyWipe.runTerminatorsBounded(terminators, { it.run() }, timeoutMs)
        }

        override fun keystoreAliases(): Collection<String> = aliases.toList()
        override fun deleteKeystoreAlias(alias: String) {
            if (alias in aliases) events += "key"
            aliases.remove(alias)
        }

        override fun preferenceNames(): Collection<String> =
            File(dataDir, "shared_prefs").listFiles()?.map { it.name.removeSuffix(".xml") }.orEmpty()

        override fun clearPreferences(name: String) {
            preferences.remove(name)
            File(dataDir, "shared_prefs/$name.xml").delete()
        }

        override fun storageRoots(): List<File> {
            events += "files"
            return EmergencyWipe.storageRootsFor(dataDir, listOf(externalFiles, externalCache, null))
        }

        override fun resetInMemoryState() {
            inMemoryReset++
        }

        override fun cancelNotifications() {
            events += "notifications"
        }
    }

    private fun write(file: File, text: String = "secret") {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun wipeRemovesEveryKeyStoreAndMemoryAndKeepsRuntimeDirectories() {
        val environment = FakeEnvironment()
        environment.aliases += EmergencyWipe.KNOWN_KEYSTORE_ALIASES
        environment.aliases += "an-alias-added-later"
        val noBackup = File(dataDir, "no_backup")
        // Verified contacts, written through the real store.
        val contacts = VerifiedContactStore(
            File(noBackup, "${IdentityVerification.DIRECTORY}/${IdentityVerification.FILE}"),
            AesGcmAtRestCipher { SecretKeySpec(ByteArray(32), "AES") },
        )
        val peer = IdentityFingerprint.fromPublicKey(KeyPairSigningIdentity(generateEcKeyPair()).publicKeyEncoded)
        assertEquals(MarkVerifiedResult.VERIFIED, contacts.markVerified(peer, peer, "Peer", VerificationMethod.QR_SCAN))
        // Store-and-forward outbox/inbox/courier data, mailbox key, contacts, ledger.
        write(File(noBackup, "store-forward/store/0123abcd/header"))
        write(File(noBackup, "store-forward/store/0123abcd/chunk-0"))
        write(File(noBackup, "store-forward/messages/inbox-1"))
        write(File(noBackup, "store-forward/contacts.properties"))
        write(File(noBackup, "store-forward/neighbors.properties"))
        write(File(noBackup, "store-forward/mailbox.key"))
        write(File(noBackup, "classic-addresses.txt"))
        // Learned link/rate/PHY/encoder memories and settings.
        EmergencyWipe.KNOWN_PREFERENCES.forEach { name ->
            write(File(dataDir, "shared_prefs/$name.xml"))
            environment.preferences[name] = mutableMapOf("k" to "v")
        }
        write(File(dataDir, "shared_prefs/unlisted_future_prefs.xml"))
        write(File(dataDir, "files/log.txt"))
        write(File(dataDir, "files/${EmergencyWipe.DEBUG_TOKEN_FILE}"))
        write(File(dataDir, "cache/sf-recording.m4a"))
        write(File(dataDir, "databases/x.db"))
        write(File(dataDir, "app_webview/state"))
        write(File(externalFiles, "benchmark/trace.csv"))
        write(File(externalFiles, "benchmark/reference.yuv"))
        write(File(externalCache, "tmp.bin"))
        // Not app data: native library link and ART code cache.
        write(File(dataDir, "lib/libnative.so"))
        write(File(dataDir, "code_cache/profile"))

        var terminatorCalls = 0
        val terminator = WipeSessionTerminator { terminatorCalls++ }
        EmergencyWipe.registerSessionTerminator(terminator)
        val report = try {
            EmergencyWipe.run(environment, WipeReason.PANIC)
        } finally {
            EmergencyWipe.unregisterSessionTerminator(terminator)
        }

        assertTrue(report.complete)
        assertFalse(report.alreadyRunning)
        assertEquals(1, terminatorCalls)
        assertEquals(1, report.terminatorsRun)
        assertTrue(environment.aliases.isEmpty())
        assertTrue(environment.preferences.isEmpty())
        assertEquals(1, environment.inMemoryReset)
        val remaining = dataDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dataDir).invariantSeparatorsPath }.toList()
        assertEquals(listOf("code_cache/profile", "lib/libnative.so"), remaining.sorted())
        assertEquals(0, externalFiles.walkTopDown().count { it.isFile })
        assertEquals(0, externalCache.walkTopDown().count { it.isFile })
        // Runtime directories stay so Context getters keep working until the process ends.
        assertTrue(File(dataDir, "no_backup").isDirectory)
        assertTrue(File(dataDir, "shared_prefs").isDirectory)
        contacts.dropCache()
        assertTrue(contacts.all().isEmpty())
        assertEquals(VerificationState.UNVERIFIED, contacts.state(peer.nodeId, peer))
    }

    @Test
    fun keysAreDeletedBeforeSessionsEndAndEverythingIsSweptTwice() {
        val environment = FakeEnvironment()
        environment.aliases += EmergencyWipe.KNOWN_KEYSTORE_ALIASES
        // A session ending during the wipe recreates a key and a learned memory (a late writer).
        val terminator = WipeSessionTerminator {
            environment.aliases += "bluetooth-video-mesh-identity-v1"
            write(File(dataDir, "shared_prefs/link_capacity.xml"))
        }
        EmergencyWipe.registerSessionTerminator(terminator)
        val report = try {
            EmergencyWipe.run(environment, WipeReason.USER_CONFIRMED)
        } finally {
            EmergencyWipe.unregisterSessionTerminator(terminator)
        }
        val firstKey = environment.events.indexOf("key")
        val terminators = environment.events.indexOf("terminators")
        assertEquals("marker", environment.events.first())
        assertTrue(firstKey in 1 until terminators)
        assertTrue(environment.events.indexOf("files") > terminators)
        assertEquals(EmergencyWipe.KNOWN_KEYSTORE_ALIASES.size + 1, report.keystoreAliasesDeleted)
        assertTrue(environment.aliases.isEmpty())
        assertFalse(File(dataDir, "shared_prefs/link_capacity.xml").exists())
        assertEquals("marker-cleared", environment.events.last())
        assertTrue(report.complete)
        assertFalse(environment.marker.exists())
    }

    @Test
    fun notificationsAreCancelledAfterSessionsEndAndAgainBeforeTheWipeFinishes() {
        val environment = FakeEnvironment()
        val terminator = WipeSessionTerminator { environment.events += "session-ended" }
        EmergencyWipe.registerSessionTerminator(terminator)
        try {
            EmergencyWipe.run(environment, WipeReason.PANIC)
        } finally {
            EmergencyWipe.unregisterSessionTerminator(terminator)
        }
        val events = environment.events
        val cancels = events.indices.filter { events[it] == "notifications" }
        assertEquals(2, cancels.size)
        assertTrue(cancels.first() > events.indexOf("session-ended"))
        assertEquals(listOf("notifications", "marker-cleared"), events.takeLast(2))
    }

    @Test
    fun incompleteScreenExplainsWithoutIdentifiers() {
        val report = WipeReport(WipeReason.RESUMED_AFTER_INTERRUPTION, false, 0, 0, false, 0, 2, 0, 0, 3, false, 5)
        val message = EmergencyWipe.incompleteMessage(report)
        assertTrue(message.contains("2 key problem(s)") && message.contains("3 file(s) left"))
        assertTrue(message.contains("Retry") && message.contains("App info"))
        val manifest = File("src/main/AndroidManifest.xml").takeIf { it.isFile } ?: File("app/src/main/AndroidManifest.xml")
        val entry = Regex("<activity\\s+android:name=\".WipeIncompleteActivity\"[^>]*>").find(manifest.readText())!!.value
        assertTrue(entry.contains("android:exported=\"false\""))
    }

    @Test
    fun anIncompleteWipeKeepsThePendingMarkerAndSaysSo() {
        val stubborn = object : FakeEnvironment() {
            override fun deleteKeystoreAlias(alias: String) {
                if (alias != "stuck-alias") super.deleteKeystoreAlias(alias)
            }
        }
        stubborn.aliases += "stuck-alias"
        val report = EmergencyWipe.run(stubborn, WipeReason.PANIC)
        assertFalse(report.complete)
        assertEquals(1, report.keystoreFailures)
        assertTrue(stubborn.marker.exists())
        assertFalse("marker-cleared" in stubborn.events)

        val clearFails = FakeEnvironment().apply { markerClearFails = true }
        assertFalse(EmergencyWipe.run(clearFails, WipeReason.RESUMED_AFTER_INTERRUPTION).complete)
    }

    @Test
    fun wipeIsSingleFlightAndStopsAdmissionForTheRestOfTheProcess() {
        val environment = FakeEnvironment()
        var nested: WipeReport? = null
        val terminator = WipeSessionTerminator {
            assertTrue(WipeGuard.active)
            nested = EmergencyWipe.run(FakeEnvironment(), WipeReason.PANIC)
        }
        EmergencyWipe.registerSessionTerminator(terminator)
        try {
            EmergencyWipe.run(environment, WipeReason.PANIC)
        } finally {
            EmergencyWipe.unregisterSessionTerminator(terminator)
        }
        assertTrue(nested!!.alreadyRunning)
        assertFalse(nested!!.complete)
        // After the wipe, nothing in this process may authenticate a peer or create keys.
        assertTrue(WipeGuard.active)
        val peer = KeyPairSigningIdentity(generateEcKeyPair())
        assertThrows(IllegalStateException::class.java) { PeerIdentityGate.admit(peer.nodeId, peer.publicKeyEncoded) }
        assertThrows(IllegalStateException::class.java) { WipeGuard.check("identity key") }
    }

    @Test
    fun aStuckTerminatorCannotHoldTheWipe() {
        val release = CountDownLatch(1)
        val stuck = WipeSessionTerminator { release.await(30, TimeUnit.SECONDS) }
        val started = System.nanoTime()
        val outcome = EmergencyWipe.runTerminatorsBounded(listOf(stuck), { task -> thread { task.run() } }, 200)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertTrue(outcome.timedOut)
        assertEquals(1, outcome.failures)
        // The terminator is stuck for up to 30 s; returning in less than that shows the 200 ms bound worked.
        assertTrue("took $elapsedMs ms", elapsedMs < 25_000)
        val fine = EmergencyWipe.runTerminatorsBounded(listOf(WipeSessionTerminator { }), { task -> thread { task.run() } }, 2_000)
        assertFalse(fine.timedOut)
        assertEquals(0, fine.failures)
    }

    @Test
    fun wipeContinuesPastFailuresAndReportsThem() {
        val environment = object : FakeEnvironment() {
            val deleted = mutableListOf<String>()
            override fun runTerminators(terminators: List<WipeSessionTerminator>, timeoutMs: Long) = error("stuck session")
            override fun keystoreAliases(): Collection<String> = error("keystore unavailable")
            override fun deleteKeystoreAlias(alias: String) {
                if (alias == EmergencyWipe.KNOWN_KEYSTORE_ALIASES.first()) error("busy")
                deleted += alias
            }
            override fun storageRoots(): List<File> = listOf(dataDir)
            override fun resetInMemoryState() = error("ignored")
        }
        write(File(dataDir, "files/a"))
        val report = EmergencyWipe.run(environment, WipeReason.USER_CONFIRMED)
        assertFalse(report.complete)
        assertEquals(1, report.terminatorFailures)
        // Every other known alias was still deleted by name, in both sweeps.
        assertEquals(EmergencyWipe.KNOWN_KEYSTORE_ALIASES.drop(1) + EmergencyWipe.KNOWN_KEYSTORE_ALIASES.drop(1), environment.deleted)
        assertFalse(File(dataDir, "files/a").exists())
    }

    @Test
    fun debugWipeNeedsTheExactAdbWrittenToken() {
        val token = "0123456789abcdef0123456789abcdef"
        assertTrue(EmergencyWipe.debugTokenMatches("$token\n", token))
        assertFalse(EmergencyWipe.debugTokenMatches(null, token))
        assertFalse(EmergencyWipe.debugTokenMatches(token, null))
        assertFalse(EmergencyWipe.debugTokenMatches(token, ""))
        assertFalse(EmergencyWipe.debugTokenMatches(token, token.dropLast(1) + "0"))
        assertFalse(EmergencyWipe.debugTokenMatches("short", "short"))
        assertFalse(EmergencyWipe.debugTokenMatches("", ""))
        assertFalse(EmergencyWipe.debugTokenMatches(token.uppercase(), token.uppercase()))
    }

    @Test
    fun deleteTreeDoesNotFollowSymbolicLinksOutOfTheTree() {
        val outside = File(root, "outside/keep.txt").also { write(it) }
        val link = File(dataDir, "files/link")
        link.parentFile.mkdirs()
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.parentFile.toPath()) }.isSuccess
        EmergencyWipe.deleteTree(File(dataDir, "files"))
        assertTrue(outside.exists())
        if (linked) assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun knownAliasAndPreferenceListsCoverEverySourceDefinition() {
        val source = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val files = source.walkTopDown().filter { it.extension == "kt" }.toList()
        val text = files.joinToString("\n") { it.readText() }
        val aliases = Regex("\"(bluetooth-video-mesh-[a-z0-9-]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
        val preferences = Regex("const val (?:PREFS|PREFERENCES|APP_PREFERENCES) = \"([a-z_]+)\"")
            .findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue(aliases.isNotEmpty() && preferences.isNotEmpty())
        assertEquals(aliases, EmergencyWipe.KNOWN_KEYSTORE_ALIASES.toSet())
        assertEquals(preferences, EmergencyWipe.KNOWN_PREFERENCES.toSet())
        // Every file that creates a Keystore key refuses to do so during or after a wipe.
        files.filter { "KeyGenParameterSpec.Builder(" in it.readText() }.forEach { file ->
            assertTrue("${file.name} creates keys without WipeGuard", "WipeGuard.check(" in file.readText())
        }
    }
}
