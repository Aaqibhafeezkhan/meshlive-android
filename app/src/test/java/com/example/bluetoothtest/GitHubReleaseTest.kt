package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseTest {
    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing test resource $name" }
            .use { String(it.readBytes(), Charsets.UTF_8) }

    private val fixture get() = resource("github-latest-release-v0.1.0-beta.json")

    // ---- StrictJson ----

    @Test
    fun jsonReadsTheShapesTheApiUses() {
        val value = StrictJson.parse("""{"a":[1,-2,3.5,"x\u00e9\n",true,false,null,{}],"b":{"c":"d"}}""")
        val root = StrictJson.objectOf(value)!!
        assertEquals(listOf(1L, -2L, 3.5, "x\u00e9\n", true, false, null, emptyMap<String, Any?>()), root["a"])
        assertEquals("d", StrictJson.objectOf(root["b"])!!["c"])
    }

    @Test
    fun jsonRejectsMalformedAndHostileInput() {
        val bad = listOf(
            "", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "{\"a\":1}x", "[01]", "[1.]", "[.5]", "[\"\\q\"]", "[\"\\u12\"]",
            "{\"a\":1,\"a\":2}", "nul", "[\"unterminated", "[\"tab\there\"]", "99999999999999999999",
            "{'a':1}", "[1 2]",
        )
        for (text in bad) {
            assertThrows("'$text'", StrictJson.JsonException::class.java) { StrictJson.parse(text) }
        }
        assertThrows(StrictJson.JsonException::class.java) { StrictJson.parse("[".repeat(StrictJson.MAX_DEPTH + 2)) }
        assertThrows(StrictJson.JsonException::class.java) {
            StrictJson.parse("\"" + "a".repeat(StrictJson.MAX_STRING_CHARS + 1) + "\"")
        }
        assertThrows(StrictJson.JsonException::class.java) { StrictJson.parse(" ".repeat(StrictJson.MAX_TEXT_CHARS + 1)) }
    }

    // ---- the real v0.1.0-beta answer ----

    @Test
    fun parsesTheRealLatestReleaseAnswer() {
        val release = GitHubReleases.parseLatest(fixture)
        assertEquals("v0.1.0-beta", release.tag)
        assertEquals("0.1.0-beta", release.versionName)
        assertEquals(ReleaseVersion.parse("0.1.0-beta"), release.version)
        assertEquals("https://github.com/MeshLive/meshlive-android/releases/tag/v0.1.0-beta", release.pageUrl)
        assertEquals(
            listOf(
                "MeshLive-0.1.0-beta-arm64-v8a.apk", "MeshLive-0.1.0-beta-armeabi-v7a.apk",
                "MeshLive-0.1.0-beta-universal.apk", "MeshLive.apk", "SHA256SUMS", "signing-cert.sha256", "SOURCE-MANIFEST.txt",
            ),
            release.assets.map { it.name },
        )
        val arm64 = GitHubReleases.selectApk(release, listOf("arm64-v8a"))!!
        assertEquals(11_899_036L, arm64.size)
        assertEquals("4a75640f12669bb8780a5039eb4dc9f4a8c8224d91dc6187da14f5c9b6037d91", arm64.sha256)
        assertTrue(UpdateEndpoints.isReleaseAssetUrl(arm64.url))
    }

    @Test
    fun picksTheFileForThePhoneAndNeverTheStableAlias() {
        val release = GitHubReleases.parseLatest(fixture)
        assertEquals("MeshLive-0.1.0-beta-armeabi-v7a.apk", GitHubReleases.selectApk(release, listOf("armeabi-v7a"))!!.name)
        assertEquals("MeshLive-0.1.0-beta-universal.apk", GitHubReleases.selectApk(release, listOf("universal"))!!.name)
        assertEquals(
            "second choice is used only when the first is missing",
            "MeshLive-0.1.0-beta-armeabi-v7a.apk",
            GitHubReleases.selectApk(release, listOf("x86_64", "armeabi-v7a"))!!.name,
        )
        assertNull(GitHubReleases.selectApk(release, listOf("x86_64")))
        assertNull(GitHubReleases.selectApk(release, emptyList()))
    }

    @Test
    fun aSplitInstallStaysOnItsOwnAbiAndADebugInstallFollowsThePhone() {
        assertEquals(listOf("arm64-v8a"), GitHubReleases.preferredAbis(12, listOf("armeabi-v7a", "arm64-v8a")))
        assertEquals(listOf("armeabi-v7a"), GitHubReleases.preferredAbis(11, listOf("arm64-v8a")))
        assertEquals(listOf("universal"), GitHubReleases.preferredAbis(20, listOf("arm64-v8a")))
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "universal"), GitHubReleases.preferredAbis(1, listOf("arm64-v8a", "armeabi-v7a", "x86")))
    }

    @Test
    fun theApiDigestAndTheSumsFileAgreeOnTheRealRelease() {
        val release = GitHubReleases.parseLatest(fixture)
        val sums = GitHubReleases.parseSums(resource("github-SHA256SUMS-v0.1.0-beta.txt"))
        for (asset in release.assets.filter { it.name.endsWith(".apk") && it.name != "MeshLive.apk" }) {
            assertEquals(asset.name, asset.sha256, sums[asset.name])
            assertEquals(asset.sha256, GitHubReleases.expectedSha256(asset, sums))
        }
    }

    @Test
    fun expectedHashComesFromTheDigestElseTheSumsAndAConflictRefusesTheRelease() {
        val withDigest = ReleaseAsset("a.apk", 1, "u", "a".repeat(64))
        val without = ReleaseAsset("a.apk", 1, "u", null)
        assertEquals("a".repeat(64), GitHubReleases.expectedSha256(withDigest, null))
        assertEquals("b".repeat(64), GitHubReleases.expectedSha256(without, mapOf("a.apk" to "b".repeat(64))))
        assertNull("no published hash at all", GitHubReleases.expectedSha256(without, null))
        assertNull(GitHubReleases.expectedSha256(without, mapOf("other.apk" to "b".repeat(64))))
        assertThrows(ReleaseParseException::class.java) {
            GitHubReleases.expectedSha256(withDigest, mapOf("a.apk" to "b".repeat(64)))
        }
    }

    @Test
    fun sumsParsingTakesSha256sumLinesOnly() {
        val h1 = "1".repeat(64)
        val h2 = "2".repeat(64)
        val sums = GitHubReleases.parseSums(
            "$h1  one.apk\r\n$h2 *two.apk\nnot a line\n${"3".repeat(63)}  short.apk\n${h1.uppercase()}  one.apk\n",
        )
        assertEquals(mapOf("one.apk" to h1, "two.apk" to h2), sums)
    }

    // ---- hostile answers ----

    private fun withEdit(edit: (String) -> String): String = edit(fixture)

    @Test
    fun refusesDraftsPrereleasesAndUnreadableTags() {
        assertThrows(ReleaseParseException::class.java) {
            GitHubReleases.parseLatest(withEdit { it.replace("\"draft\": false", "\"draft\": true") })
        }
        assertThrows(ReleaseParseException::class.java) {
            GitHubReleases.parseLatest(withEdit { it.replace("\"prerelease\": false", "\"prerelease\": true") })
        }
        assertThrows(ReleaseParseException::class.java) {
            GitHubReleases.parseLatest(withEdit { it.replace("\"tag_name\": \"v0.1.0-beta\"", "\"tag_name\": \"nightly\"") })
        }
        assertThrows(ReleaseParseException::class.java) { GitHubReleases.parseLatest("[]") }
        assertThrows(ReleaseParseException::class.java) { GitHubReleases.parseLatest("<html>rate limited</html>") }
        assertThrows(ReleaseParseException::class.java) { GitHubReleases.parseLatest("{\"message\":\"Not Found\"}") }
    }

    @Test
    fun dropsAssetsWhoseUrlLeavesThisRepositoryOrWhoseNameIsUnsafe() {
        val evil = withEdit {
            it.replace(
                "https://github.com/MeshLive/meshlive-android/releases/download/v0.1.0-beta/MeshLive-0.1.0-beta-arm64-v8a.apk",
                "https://github.com/attacker/other/releases/download/v0.1.0-beta/MeshLive-0.1.0-beta-arm64-v8a.apk",
            ).replace(
                "\"name\": \"MeshLive-0.1.0-beta-armeabi-v7a.apk\"",
                "\"name\": \"../MeshLive-0.1.0-beta-armeabi-v7a.apk\"",
            )
        }
        val release = GitHubReleases.parseLatest(evil)
        assertNull(GitHubReleases.selectApk(release, listOf("arm64-v8a")))
        assertNull(GitHubReleases.selectApk(release, listOf("armeabi-v7a")))
        assertNotNull(GitHubReleases.selectApk(release, listOf("universal")))
    }

    @Test
    fun onlyThisRepositorysReleaseDownloadsAreAssetUrls() {
        val base = "https://github.com/MeshLive/meshlive-android/releases/download/v1.0.0/"
        assertTrue(UpdateEndpoints.isReleaseAssetUrl(base + "MeshLive-1.0.0-arm64-v8a.apk"))
        for (bad in listOf(
            "http://github.com/MeshLive/meshlive-android/releases/download/v1/a.apk",
            "https://github.com.evil.example/MeshLive/meshlive-android/releases/download/v1/a.apk",
            "https://evil@github.com/MeshLive/meshlive-android/releases/download/v1/a.apk",
            "https://github.com:8443/MeshLive/meshlive-android/releases/download/v1/a.apk",
            "https://github.com/MeshLive/meshlive-android/releases/download/../../../evil/a.apk",
            "https://github.com/MeshLive/meshlive-android/releases/download/v1/a.apk?x=1",
            "https://github.com/MeshLive/meshlive-android/releases/download/v1/%2e%2e/a.apk",
            "https://github.com/MeshLive/other/releases/download/v1/a.apk",
            "https://api.github.com/repos/MeshLive/meshlive-android/releases/assets/1",
            "",
        )) {
            assertFalse("'$bad'", UpdateEndpoints.isReleaseAssetUrl(bad))
        }
    }

    @Test
    fun redirectsStayOnGitHubOverHttps() {
        assertTrue(UpdateEndpoints.isAllowedRedirect("https://objects.githubusercontent.com/github-production-release-asset/abc?x=1"))
        assertTrue(UpdateEndpoints.isAllowedRedirect("https://release-assets.githubusercontent.com/a"))
        assertTrue(UpdateEndpoints.isAllowedRedirect("https://github.com/x"))
        for (bad in listOf(
            "http://objects.githubusercontent.com/a", "https://githubusercontent.com.evil.example/a",
            "https://evilgithubusercontent.com/a", "https://objects.githubusercontent.com:444/a",
            "https://user@objects.githubusercontent.com/a", "ftp://github.com/a", "https://example.com/a", "not a url",
        )) {
            assertFalse("'$bad'", UpdateEndpoints.isAllowedRedirect(bad))
        }
    }

    // ---- the decision ----

    @Test
    fun offersOnlyAStrictlyNewerVersionWithAFileForThePhone() {
        val body = fixture
        val upToDate = UpdateFlow.evaluate(body, "0.1.0-beta", 12, listOf("arm64-v8a"))
        assertTrue(upToDate is CheckOutcome.UpToDate)
        assertTrue("an installed newer build is not downgraded", UpdateFlow.evaluate(body, "0.2.0", 22, listOf("arm64-v8a")) is CheckOutcome.UpToDate)

        val older = body.replace("v0.1.0-beta", "v0.2.0").replace("MeshLive-0.1.0-beta-", "MeshLive-0.2.0-")
        val offer = UpdateFlow.evaluate(older, "0.1.0-beta", 12, listOf("arm64-v8a")) as CheckOutcome.Available
        assertEquals("MeshLive-0.2.0-arm64-v8a.apk", offer.apk.name)
        assertFalse(offer.needsSums)
        assertEquals("SHA256SUMS", offer.sums!!.name)

        assertTrue(UpdateFlow.evaluate(older, "0.1.0-beta", 11, listOf("arm64-v8a")) is CheckOutcome.Available)
        val tiny = older.replace("MeshLive-0.2.0-arm64-v8a.apk", "MeshLive-0.2.0-other.apk")
        assertTrue(UpdateFlow.evaluate(tiny, "0.1.0-beta", 12, listOf("arm64-v8a")) is CheckOutcome.NoFileForThisPhone)
        assertTrue(UpdateFlow.evaluate("{", "0.1.0-beta", 12, emptyList()) is CheckOutcome.Unusable)
        assertTrue(UpdateFlow.evaluate(body, "garbage", 12, emptyList()) is CheckOutcome.Unusable)
    }

    @Test
    fun aPreReleaseOfTheSameNumberIsOlderThanItsRelease() {
        val newer = fixture.replace("v0.1.0-beta", "v0.1.0").replace("MeshLive-0.1.0-beta-", "MeshLive-0.1.0-")
        assertTrue(UpdateFlow.evaluate(newer, "0.1.0-beta", 12, listOf("arm64-v8a")) is CheckOutcome.Available)
    }

    @Test
    fun publishedHashIsReadFromTheDigestElseFromTheSums() {
        val body = fixture.replace("v0.1.0-beta", "v0.2.0").replace("MeshLive-0.1.0-beta-", "MeshLive-0.2.0-")
        val offer = UpdateFlow.evaluate(body, "0.1.0-beta", 12, listOf("arm64-v8a")) as CheckOutcome.Available
        assertEquals(offer.apk.sha256, UpdateFlow.publishedSha256(offer, null))
        val noDigest = offer.copy(apk = offer.apk.copy(sha256 = null))
        assertTrue(noDigest.needsSums)
        assertNull("without a digest and without the sums file nothing is installable", UpdateFlow.publishedSha256(noDigest, null))
        val sums = "${"c".repeat(64)}  ${offer.apk.name}\n"
        assertEquals("c".repeat(64), UpdateFlow.publishedSha256(noDigest, sums))
        assertNull(
            "a digest that disagrees with the sums is refused",
            UpdateFlow.publishedSha256(offer.copy(apk = offer.apk.copy(sha256 = "d".repeat(64))), sums),
        )
    }

    @Test
    fun aMirrorIsAnotherSourceWithTheSameChecksAndNoSpecialTrust() {
        val mirror = UpdateSource(
            latestUrl = "https://updates.example.org/meshlive/latest.json",
            assetUrlPrefix = "https://updates.example.org/meshlive/download/",
            pageUrlPrefix = "https://updates.example.org/meshlive/",
            releasesPage = "https://updates.example.org/meshlive/",
            redirectHosts = listOf("updates.example.org", ".cdn.example.org"),
        )
        val body = fixture.replace("https://github.com/MeshLive/meshlive-android/releases/download/", "https://updates.example.org/meshlive/download/")
            .replace("v0.1.0-beta", "v0.2.0").replace("MeshLive-0.1.0-beta-", "MeshLive-0.2.0-")
        assertTrue(UpdateFlow.evaluate(body, "0.1.0-beta", 12, listOf("arm64-v8a"), mirror) is CheckOutcome.Available)
        assertTrue("the same body is refused under the GitHub source", UpdateFlow.evaluate(body, "0.1.0-beta", 12, listOf("arm64-v8a")) is CheckOutcome.NoFileForThisPhone)
        assertTrue(mirror.isAllowedRedirect("https://eu.cdn.example.org/a"))
        assertFalse(mirror.isAllowedRedirect("https://cdn.example.org.evil.net/a"))
        assertFalse(mirror.isAllowedRedirect("https://github.com/a"))
        assertFalse(mirror.isReleaseAssetUrl("https://updates.example.org.evil.net/meshlive/download/a.apk"))
        // The default source has no mirror host in it.
        assertFalse(UpdateSource.GITHUB.isAllowedRedirect("https://updates.example.org/a"))
    }

    @Test
    fun aLongReleaseBodyDoesNotBreakTheCheck() {
        // GitHub allows 125,000 characters of release notes (security review F9).
        val body = "x".repeat(125_000)
        val big = fixture.replace("\"body\": \"MeshLive 0.1.0-beta (fixture: the real release notes are not needed by the parser).\"", "\"body\": \"$body\"")
        assertTrue(big.length > 125_000)
        assertEquals("v0.1.0-beta", GitHubReleases.parseLatest(big).tag)
        assertTrue(big.toByteArray().size < GitHubReleases.MAX_LATEST_BYTES)
    }

    @Test
    fun aRenamedRepositoryRedirectOnTheApiHostIsFollowedButItsAssetsStillMustMatch() {
        assertTrue(UpdateSource.GITHUB.isAllowedRedirect("https://api.github.com/repositories/123456/releases/latest"))
        assertFalse(UpdateSource.GITHUB.isReleaseAssetUrl("https://api.github.com/repositories/123456/releases/assets/9"))
    }

    @Test
    fun fixturesAreTheTrimmedRealAnswers() {
        assertTrue(File("src/test/resources/github-latest-release-v0.1.0-beta.json").isFile || File("app/src/test/resources/github-latest-release-v0.1.0-beta.json").isFile)
        assertFalse("no account data in the fixture", fixture.contains("avatar_url"))
    }
}
