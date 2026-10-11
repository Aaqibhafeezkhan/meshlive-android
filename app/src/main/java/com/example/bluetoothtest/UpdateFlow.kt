package com.example.bluetoothtest

/** The result of reading one `releases/latest` answer. */
internal sealed class CheckOutcome {
    /** The newest published release is not newer than the installed version. */
    data class UpToDate(val tag: String) : CheckOutcome()

    /** A newer release with a file for this phone. When the answer has no digest, `SHA256SUMS` must be read too ([needsSums]). */
    data class Available(
        val release: LatestRelease,
        val apk: ReleaseAsset,
        val sums: ReleaseAsset?,
    ) : CheckOutcome() {
        /** True when the API gave no digest for the file, so `SHA256SUMS` must be fetched. */
        val needsSums: Boolean get() = apk.sha256 == null
    }

    /** A newer release exists but has no APK for this phone's CPU type. */
    data class NoFileForThisPhone(val release: LatestRelease) : CheckOutcome()

    /** The answer could not be used. [detail] is for the log, never shown as a claim about the release. */
    data class Unusable(val detail: String) : CheckOutcome()
}

internal object UpdateFlow {
    /**
     * Decides what a `releases/latest` body means for the installed copy. Offers an update only for a
     * strictly newer semantic version; the downloaded APK's own versionCode is checked again by
     * [UpdateVerifier] before anything is installed.
     */
    fun evaluate(
        body: String,
        installedVersionName: String,
        installedVersionCode: Long,
        supportedAbis: List<String>,
        source: UpdateSource = UpdateSource.GITHUB,
    ): CheckOutcome {
        val installed = ReleaseVersion.parse(installedVersionName)
            ?: return CheckOutcome.Unusable("installed version name is not a version: $installedVersionName")
        val release = try {
            GitHubReleases.parseLatest(body, source)
        } catch (e: ReleaseParseException) {
            return CheckOutcome.Unusable(e.message ?: "unreadable release")
        }
        if (release.version <= installed) return CheckOutcome.UpToDate(release.tag)
        val apk = GitHubReleases.selectApk(release, GitHubReleases.preferredAbis(installedVersionCode, supportedAbis))
            ?: return CheckOutcome.NoFileForThisPhone(release)
        return CheckOutcome.Available(release, apk, GitHubReleases.sumsAsset(release))
    }

    /**
     * The SHA-256 the release publishes for [available], given the `SHA256SUMS` text if it was fetched.
     * Null means "no published hash": the file must not be installed.
     */
    fun publishedSha256(available: CheckOutcome.Available, sumsText: String?): String? = try {
        GitHubReleases.expectedSha256(available.apk, sumsText?.let(GitHubReleases::parseSums))
    } catch (e: ReleaseParseException) {
        null
    }
}
