package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The emergency wipe reaches everything Messages writes: the bundle store, the archive, contacts, the
 * outbox ledger, the unread marker and a voice-note recording (EmergencyWipe.storageRootsFor deletes
 * every data directory except the native-library link and ART's code cache), and both Keystore keys
 * are in the alias list.
 */
class StoreForwardWipeCoverageTest {
    private lateinit var base: File
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("sf-wipe").toFile()
        dataDir = File(base, "data").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("secret")
    }

    @Test
    fun everyMessagesFileIsUnderADirectoryTheWipeDeletes() {
        val storeRoot = File(dataDir, "no_backup/store-forward")
        val files = listOf(
            File(storeRoot, "store/0123/header"),
            File(storeRoot, "messages/id.body"),
            File(storeRoot, "messages/id.meta"),
            File(storeRoot, "contacts.properties"),
            File(storeRoot, "neighbors.properties"),
            File(storeRoot, "mailbox.key"),
            File(storeRoot, "mailbox.kind"),
            File(storeRoot, "outbox-attempts.properties"),
            File(storeRoot, InboxSeenStore.FILE_NAME),
            File(dataDir, "cache/${StoreForwardPlaintextFiles.RECORDING}"),
            File(dataDir, "cache/${StoreForwardPlaintextFiles.LEGACY_PLAYBACK}"),
        )
        files.forEach(::write)
        File(dataDir, "lib").mkdirs()
        write(File(dataDir, "lib/libnative.so"))
        EmergencyWipe.storageRootsFor(dataDir, emptyList()).forEach { root ->
            root.listFiles()?.forEach { EmergencyWipe.deleteTree(it) }
        }
        files.forEach { assertFalse(it.path, it.exists()) }
        assertTrue("the native-library link is not app data", File(dataDir, "lib/libnative.so").exists())
    }

    @Test
    fun bothMessagesKeysAreDeletedByTheWipe() {
        assertTrue(AndroidMailboxKeyStore.MAILBOX_ALIAS in EmergencyWipe.KNOWN_KEYSTORE_ALIASES)
        assertTrue("bluetooth-video-mesh-store-forward-v1" in EmergencyWipe.KNOWN_KEYSTORE_ALIASES)
    }

    @Test
    fun theNotificationPreferencesAreWipedToo() {
        // The lock-screen names opt-in returns to off, and the notification privacy file is gone.
        assertTrue(NotificationPrivacy.PREFS in EmergencyWipe.KNOWN_PREFERENCES)
        assertEquals("bluetooth_video_settings", CallFeatureDefaults.APP_SETTINGS_PREFERENCES)
        assertTrue(CallFeatureDefaults.APP_SETTINGS_PREFERENCES in EmergencyWipe.KNOWN_PREFERENCES)
    }
}
