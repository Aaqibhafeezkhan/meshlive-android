package com.example.bluetoothtest

import java.net.URI

/**
 * Where the online update check goes and which URLs a download may touch. [GITHUB] is the only source
 * the app ships; a mirror is the same JSON and file layout under another host (PLAN "Update mirror"),
 * added by one more instance. A source is a *transport*: the APK's own signing certificate (pinned),
 * its versionCode and its SHA-256 are checked whatever the source says, so a mirror can withhold an
 * update but cannot make the app install a file the release key did not sign.
 */
internal data class UpdateSource(
    /** The `releases/latest` answer (GitHub REST shape). */
    val latestUrl: String,
    /** Every asset URL the answer names must start with this (https, a fixed repository path). */
    val assetUrlPrefix: String,
    /** The page shown for the release. */
    val pageUrlPrefix: String,
    val releasesPage: String,
    /** A redirect may leave [assetUrlPrefix]'s host only for `host` or `*.suffix` of these. */
    val redirectHosts: List<String>,
    /** DEBUG test servers only (phone test T13): plain `http` and an explicit port. Never true for [GITHUB]. */
    val allowCleartext: Boolean = false,
) {
    /** The URL the answer names for an asset: this source's release downloads, nothing else. */
    fun isReleaseAssetUrl(url: String): Boolean {
        if (!url.startsWith(assetUrlPrefix)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val expectedHost = runCatching { URI(assetUrlPrefix).host }.getOrNull() ?: return false
        val schemeOk = uri.scheme == "https" || (allowCleartext && uri.scheme == "http")
        val portOk = uri.port == -1 || allowCleartext
        return schemeOk && uri.host == expectedHost && uri.userInfo == null && portOk &&
            uri.query == null && uri.fragment == null && !url.contains("..") && !url.contains("%")
    }

    /** A redirect target: HTTPS on one of [redirectHosts] (or a subdomain of a `.suffix` entry). */
    fun isAllowedRedirect(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val schemeOk = uri.scheme == "https" || (allowCleartext && uri.scheme == "http")
        val portOk = uri.port == -1 || uri.port == 443 || allowCleartext
        return schemeOk && uri.userInfo == null && portOk &&
            redirectHosts.any { allowed -> if (allowed.startsWith(".")) host.endsWith(allowed) else host == allowed }
    }

    companion object {
        const val REPOSITORY = "MeshLive/meshlive-android"
        val GITHUB = UpdateSource(
            latestUrl = "https://api.github.com/repos/$REPOSITORY/releases/latest",
            assetUrlPrefix = "https://github.com/$REPOSITORY/releases/download/",
            pageUrlPrefix = "https://github.com/$REPOSITORY/",
            releasesPage = "https://github.com/$REPOSITORY/releases",
            redirectHosts = listOf("github.com", "api.github.com", ".githubusercontent.com"),
        )
    }
}

/** The default source's helpers, kept short for callers and tests. */
internal object UpdateEndpoints {
    val LATEST_URL: String get() = UpdateSource.GITHUB.latestUrl
    val RELEASES_PAGE: String get() = UpdateSource.GITHUB.releasesPage
    fun isReleaseAssetUrl(url: String): Boolean = UpdateSource.GITHUB.isReleaseAssetUrl(url)
    fun isAllowedRedirect(url: String): Boolean = UpdateSource.GITHUB.isAllowedRedirect(url)
}

internal data class ReleaseAsset(
    val name: String,
    val size: Long,
    val url: String,
    /** Lowercase hex SHA-256 from the API's `digest` field ("sha256:..."), or null when it has none. */
    val sha256: String?,
)

internal data class LatestRelease(
    val tag: String,
    val version: ReleaseVersion,
    /** The tag without a leading `v`; it is part of every APK file name. */
    val versionName: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
)

internal class ReleaseParseException(message: String) : Exception(message)

internal object GitHubReleases {
    /** Largest APK the updater will download (the release APKs are 11-19 MB). */
    const val MAX_APK_BYTES = 120L * 1024 * 1024

    /** The `releases/latest` answer: a release body can be 125,000 characters, so this is generous. */
    const val MAX_LATEST_BYTES = 1024 * 1024
    const val SUMS_NAME = "SHA256SUMS"
    const val MAX_SUMS_BYTES = 16 * 1024

    private val ASSET_NAME = Regex("^[A-Za-z0-9._+-]{1,120}$")

    /**
     * Reads the body of `GET /repos/{owner}/{repo}/releases/latest`. That endpoint returns only a
     * published, non-draft, non-prerelease release; a draft or pre-release in the body is refused
     * anyway, as is an unreadable tag or an asset URL outside this repository's release downloads.
     */
    fun parseLatest(body: String, source: UpdateSource = UpdateSource.GITHUB): LatestRelease {
        val root = try {
            StrictJson.objectOf(StrictJson.parse(body))
        } catch (e: StrictJson.JsonException) {
            throw ReleaseParseException("Release response is not valid JSON: ${e.message}")
        } ?: throw ReleaseParseException("Release response is not a JSON object")
        if (root["draft"] == true) throw ReleaseParseException("The latest release is a draft")
        if (root["prerelease"] == true) throw ReleaseParseException("The latest release is a pre-release")
        val tag = root["tag_name"] as? String ?: throw ReleaseParseException("Release has no tag")
        val version = ReleaseVersion.parse(tag) ?: throw ReleaseParseException("Release tag is not a version: $tag")
        val versionName = tag.trim().removePrefix("v")
        val page = (root["html_url"] as? String)?.takeIf {
            it.startsWith(source.pageUrlPrefix) || it == source.releasesPage
        } ?: source.releasesPage
        val rawAssets = StrictJson.arrayOf(root["assets"]) ?: throw ReleaseParseException("Release has no asset list")
        val assets = rawAssets.mapNotNull { item ->
            val asset = StrictJson.objectOf(item) ?: return@mapNotNull null
            val name = asset["name"] as? String ?: return@mapNotNull null
            val url = asset["browser_download_url"] as? String ?: return@mapNotNull null
            val size = (asset["size"] as? Long) ?: return@mapNotNull null
            if (!ASSET_NAME.matches(name) || size <= 0 || !source.isReleaseAssetUrl(url)) return@mapNotNull null
            if (asset["state"] != null && asset["state"] != "uploaded") return@mapNotNull null
            val digest = (asset["digest"] as? String)?.takeIf { it.startsWith("sha256:") }
                ?.let { ReleaseSigner.normalizeHex(it.removePrefix("sha256:")) }
            ReleaseAsset(name, size, url, digest)
        }
        return LatestRelease(tag, version, versionName, page, assets)
    }

    /** `MeshLive-<versionName>-<abi>.apk` for the first of [abis] the release has; null when none. */
    fun selectApk(release: LatestRelease, abis: List<String>): ReleaseAsset? {
        for (abi in abis) {
            val wanted = "MeshLive-${release.versionName}-$abi.apk"
            release.assets.firstOrNull { it.name == wanted && it.size <= MAX_APK_BYTES }?.let { return it }
        }
        return null
    }

    fun sumsAsset(release: LatestRelease): ReleaseAsset? =
        release.assets.firstOrNull { it.name == SUMS_NAME && it.size <= MAX_SUMS_BYTES }

    /** `sha256sum` output: `<64 hex>  <name>` (a `*` before the name marks binary mode). */
    fun parseSums(text: String): Map<String, String> {
        val sums = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val match = Regex("^([0-9A-Fa-f]{64}) [ *](.+)$").matchEntire(line.trimEnd('\r')) ?: return@forEach
            val (hex, name) = match.destructured
            sums.putIfAbsent(name, hex.lowercase())
        }
        return sums
    }

    /**
     * The SHA-256 the release publishes for [apk]: the API `digest`, else the line of `SHA256SUMS`
     * (null while that file has not been read). Two values that disagree refuse the release; no value
     * at all returns null, and the caller installs nothing.
     */
    fun expectedSha256(apk: ReleaseAsset, sums: Map<String, String>?): String? {
        val fromSums = sums?.get(apk.name)
        if (apk.sha256 != null && fromSums != null && apk.sha256 != fromSums) {
            throw ReleaseParseException("The release lists two different SHA-256 values for ${apk.name}")
        }
        return apk.sha256 ?: fromSums
    }

    /**
     * The ABIs to try, most preferred first. A copy installed as a split release stays on its own
     * ABI (arm64-v8a 2, armeabi-v7a 1, universal 0); a non-split build follows the phone's order.
     */
    fun preferredAbis(installedVersionCode: Long, supportedAbis: List<String>): List<String> {
        ReleaseNumber.fromVersionCode(installedVersionCode)?.let {
            ReleaseNumber.abiName(ReleaseNumber.abiDigit(installedVersionCode))?.let { name -> return listOf(name) }
        }
        val known = supportedAbis.filter { it == "arm64-v8a" || it == "armeabi-v7a" }
        return known + "universal"
    }
}
