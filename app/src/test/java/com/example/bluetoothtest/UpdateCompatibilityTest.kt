package com.example.bluetoothtest

import org.junit.Assert.*
import org.junit.Test

class UpdateCompatibilityTest {
    private val signer = ReleaseSigner.PINNED.first()
    private val installed = InstalledFacts("io.github.meshlive.app", 32, listOf(signer), 29, setOf("arm64-v8a"))
    private val apk = ApkFacts(installed.packageName, 42, listOf(signer), false, minSdk = 29, nativeAbis = setOf("arm64-v8a"))
    private fun reason(candidate: ApkFacts?) = (UpdateVerifier.verify(candidate, installed, true) as UpdateVerdict.Refused).reason

    @Test fun incompatibleArchivesAreRefusedBeforeTheInstaller() {
        assertEquals(UpdateVerdict.Accepted, UpdateVerifier.verify(apk, installed, true))
        assertEquals(UpdateRefusal.SDK_TOO_OLD, reason(apk.copy(minSdk = 30)))
        assertEquals(UpdateRefusal.WRONG_ABI, reason(apk.copy(nativeAbis = setOf("x86_64"))))
        assertEquals(UpdateVerdict.Accepted, UpdateVerifier.verify(apk.copy(nativeAbis = setOf("x86_64", "arm64-v8a")), installed, true))
        assertEquals(UpdateRefusal.WRONG_PACKAGE, reason(apk.copy(packageName = "another.app")))
        assertEquals(UpdateRefusal.SIGNER_NOT_INSTALLED, reason(apk.copy(signersSha256 = listOf("AA".repeat(32)))))
        assertEquals(UpdateRefusal.UNREADABLE, reason(null))
        for (code in listOf(22L, 32L)) assertEquals(UpdateRefusal.NOT_NEWER, reason(apk.copy(versionCode = code)))
    }

    @Test fun releaseSelectionRefusesDuplicateNamesButSelectsThePhoneAmongDifferentAssets() {
        val asset = ReleaseAsset("MeshLive-0.4.0-arm64-v8a.apk", 100, "unused", "a".repeat(64))
        val release = LatestRelease("v0.4.0", ReleaseVersion.parse("0.4.0")!!, "0.4.0", "unused", listOf(asset))
        assertEquals(asset, GitHubReleases.selectApk(release, listOf("arm64-v8a")))
        assertNull(GitHubReleases.selectApk(release.copy(assets = listOf(asset, asset)), listOf("arm64-v8a")))
        assertNull(GitHubReleases.selectApk(release.copy(assets = emptyList()), listOf("arm64-v8a")))
        assertEquals(asset, GitHubReleases.selectApk(release.copy(assets = listOf(asset.copy(name = "MeshLive-0.4.0-armeabi-v7a.apk"), asset)), listOf("arm64-v8a")))
    }

    @Test fun conflictingChecksumLinesCannotChooseTheFirstHash() {
        try {
            GitHubReleases.parseSums("${"a".repeat(64)}  app.apk\n${"b".repeat(64)}  app.apk")
            fail("conflicting checksum")
        } catch (_: ReleaseParseException) {}
    }

    @Test fun badReleaseBodiesFailClosedAndSemanticOrderingIncludesPrereleaseTags() {
        for (body in listOf("", "<html>login</html>", "{}", "{\"tag_name\":\"v0.4.0\"}")) {
            assertTrue(UpdateFlow.evaluate(body, "0.3.0", 32, listOf("arm64-v8a")) is CheckOutcome.Unusable)
        }
        fun body(tag: String, pre: Boolean = false) = """{"tag_name":"$tag","prerelease":$pre,"assets":[]}"""
        assertTrue(UpdateFlow.evaluate(body("v0.4.0"), "0.3.0-beta", 32, emptyList()) is CheckOutcome.NoFileForThisPhone)
        for (tag in listOf("v0.3.0", "v0.2.0", "v0.3.0-beta")) {
            assertTrue(UpdateFlow.evaluate(body(tag), "0.3.0", 32, emptyList()) is CheckOutcome.UpToDate)
        }
        assertTrue(UpdateFlow.evaluate(body("v0.4.0-beta", true), "0.3.0", 32, emptyList()) is CheckOutcome.Unusable)
        // A beta tag published as Latest is supported (the project's release convention).
        assertTrue(UpdateFlow.evaluate(body("v0.4.0-beta"), "0.3.0", 32, emptyList()) is CheckOutcome.NoFileForThisPhone)
    }
}
