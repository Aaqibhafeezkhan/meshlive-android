package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AppSharePolicyTest {
    private val release = "CN=Bluetooth Video Release,O=Example,C=IN"
    private val debugRfc2253 = "C=US,O=Android,CN=Android Debug"

    @Test
    fun debugCertificateIsRecognisedInAnyDnOrder() {
        assertTrue(AppSharePolicy.isAndroidDebugCertificate(debugRfc2253))
        assertTrue(AppSharePolicy.isAndroidDebugCertificate("CN=Android Debug, O=Android, C=US"))
        assertFalse(AppSharePolicy.isAndroidDebugCertificate(release))
        assertFalse(AppSharePolicy.isAndroidDebugCertificate("CN=Android Debugger,O=Android"))
    }

    @Test
    fun releaseBuildSignedWithTheDebugKeyIsRefused() {
        assertEquals(
            AppSharePolicy.REFUSE_DEBUG_SIGNED,
            AppSharePolicy.refusal(apkReadable = true, splitCount = 0, debugBuild = false, signerSubjects = listOf(debugRfc2253)),
        )
        assertNull(AppSharePolicy.refusal(true, 0, debugBuild = false, signerSubjects = listOf(release)))
        // A debug build may share itself (testing); the share screen labels it.
        assertNull(AppSharePolicy.refusal(true, 0, debugBuild = true, signerSubjects = listOf(debugRfc2253)))
    }

    @Test
    fun splitInstallsUnreadablePackagesAndMissingSignersAreRefused() {
        val split = AppSharePolicy.refusal(true, splitCount = 2, debugBuild = true, signerSubjects = listOf(release))
        assertEquals(AppSharePolicy.refuseSplit(2), split)
        assertTrue(split!!.contains("3 separate packages"))
        assertEquals(AppSharePolicy.REFUSE_UNREADABLE, AppSharePolicy.refusal(false, 0, true, listOf(release)))
        assertEquals(AppSharePolicy.REFUSE_NO_SIGNER, AppSharePolicy.refusal(true, 0, false, emptyList()))
    }

    @Test
    fun publishedReleaseFingerprintMatchesReadme() {
        val readme = File("../docs/USER_GUIDE.md").takeIf { it.isFile } ?: File("docs/USER_GUIDE.md")
        if (!readme.isFile) return // Not run from the project tree.
        assertTrue(
            "docs/USER_GUIDE.md 'Verifying a release' must carry AppSharePolicy.PUBLISHED_RELEASE_CERT_SHA256",
            readme.readText().contains(AppSharePolicy.PUBLISHED_RELEASE_CERT_SHA256),
        )
        assertTrue(AppSharePolicy.matchesPublishedReleaseKey(listOf(AppSharePolicy.PUBLISHED_RELEASE_CERT_SHA256.lowercase())))
        assertFalse(AppSharePolicy.matchesPublishedReleaseKey(listOf(AppSharePolicy.PUBLISHED_RELEASE_CERT_SHA256, "AA")))
        assertFalse(AppSharePolicy.matchesPublishedReleaseKey(emptyList()))
    }

    @Test
    fun fingerprintsAndHashesUseTheReleaseToolFormats() {
        assertEquals("00:0A:FF", AppSharePolicy.fingerprint(byteArrayOf(0, 10, -1)))
        // sha256("abc"), as sha256sum prints it.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AppSharePolicy.sha256(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test
    fun shareFileNamesAreFriendlyAndSafe() {
        assertEquals("BluetoothVideo-1.0-1.apk", AppSharePolicy.shareFileName("1.0", 1))
        assertEquals("BluetoothVideo-1.0-1.apk.zip", AppSharePolicy.shareFileName("1.0", 1, AppShareFormat.ZIP_RENAMED))
        assertEquals("BluetoothVideo-1.0_beta_..-7.apk", AppSharePolicy.shareFileName("1.0 beta/..", 7))
        assertEquals("BluetoothVideo-unknown-3.apk", AppSharePolicy.shareFileName("", 3))
    }

    @Test
    fun formatsUseThePackageAndZipTypes() {
        assertEquals("application/vnd.android.package-archive", AppShareFormat.APK.mimeType)
        assertEquals("application/zip", AppShareFormat.ZIP_RENAMED.mimeType)
    }

    @Test
    fun verifiedCopyKeepsMatchingBytesAndDeletesAMismatch() {
        val dir = Files.createTempDirectory("appshare-test").toFile()
        try {
            val bytes = ByteArray(200_000) { (it * 31).toByte() }
            val expected = AppSharePolicy.sha256(ByteArrayInputStream(bytes))
            val good = File(dir, "good.apk")
            assertEquals(expected, AppSharePolicy.copyVerified(ByteArrayInputStream(bytes), good, expected))
            assertTrue(good.readBytes().contentEquals(bytes))

            val bad = File(dir, "bad.apk")
            try {
                AppSharePolicy.copyVerified(ByteArrayInputStream(bytes.copyOf(1000)), bad, expected)
                fail("A short copy must be rejected")
            } catch (_: IOException) {
            }
            assertFalse(bad.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanupDeletesOnlyExpiredCopies() {
        val dir = Files.createTempDirectory("appshare-clean").toFile()
        try {
            val now = 10_000_000L
            val old = File(dir, "old.apk").apply { writeText("x"); setLastModified(now - AppSharePolicy.COPY_TTL_MS - 5_000) }
            val fresh = File(dir, "fresh.apk").apply { writeText("y"); setLastModified(now - 1_000) }
            assertEquals(1, AppSharePolicy.cleanup(dir, now, AppSharePolicy.COPY_TTL_MS))
            assertFalse(old.exists())
            assertTrue(fresh.exists())
            assertEquals(1, AppSharePolicy.cleanup(dir, now, 0L))
            assertFalse(fresh.exists())
            assertEquals(0, AppSharePolicy.cleanup(File(dir, "missing"), now, 0L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun shareTextCarriesBothFingerprints() {
        val info = AppApkInfo(
            versionName = "1.0", versionCode = 1, debugBuild = false, sizeBytes = 10, apkSha256 = "ab",
            signingCertSha256 = listOf("AA:BB"), matchesPublishedReleaseKey = false, splitCount = 0, refusal = null,
        )
        val text = AppShare.shareText(info, AppShareFormat.APK)
        assertTrue(text.contains("APK SHA-256 ab"))
        assertTrue(text.contains("AA:BB"))
        assertTrue(AppShare.shareText(info, AppShareFormat.ZIP_RENAMED).contains("Rename"))
    }
}
