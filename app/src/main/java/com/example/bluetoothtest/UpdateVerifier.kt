package com.example.bluetoothtest

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** What `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` says about a candidate APK. */
internal data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    /** Colon-separated uppercase SHA-256 of each *current* signer certificate (`SigningInfo.apkContentsSigners`). */
    val signersSha256: List<String>,
    val multipleSigners: Boolean,
    /**
     * The signing lineage of a single-signer APK (`SigningInfo.signingCertificateHistory`, oldest first, ending
     * with the current signer), empty when it has none. A rotated APK proves with it that the old key authorised
     * the new one.
     */
    val lineageSha256: List<String> = emptyList(),
)

/** The installed app. */
internal data class InstalledFacts(
    val packageName: String,
    val versionCode: Long,
    val signersSha256: List<String>,
)

internal enum class UpdateRefusal {
    /** The package could not be read or is not signed. */
    UNREADABLE,
    WRONG_PACKAGE,
    NOT_NEWER,
    WRONG_ABI,
    SIGNER_COUNT,
    SIGNER_NOT_INSTALLED,
    SIGNER_NOT_PINNED,

    /** The installed copy does not carry the release certificate, so a GitHub release cannot update it. */
    ONLINE_UPDATE_NOT_OFFERED,
    HASH_MISMATCH,
    NO_PUBLISHED_HASH,
    SIZE_MISMATCH,
}

internal sealed class UpdateVerdict {
    object Accepted : UpdateVerdict()
    data class Refused(val reason: UpdateRefusal, val detail: String) : UpdateVerdict()
}

/**
 * The rules an APK must pass before the app asks Android to install it (PROTOCOL "Update
 * verification"). Android itself also refuses a different signer and a lower `versionCode`; these
 * checks fail first with a readable reason and add the pin, the ABI rule and the hash binding.
 */
internal object UpdateVerifier {
    /**
     * [requirePinned] is true for a download from the release page, which must also carry the pinned
     * release certificate. An APK from a nearby phone only has to carry the *installed* signer (an
     * F-Droid copy accepts an F-Droid-signed file, a release copy a release-signed one).
     */
    fun verify(
        candidate: ApkFacts?,
        installed: InstalledFacts,
        requirePinned: Boolean,
        pinned: Set<String> = ReleaseSigner.PINNED,
    ): UpdateVerdict {
        if (candidate == null) return UpdateVerdict.Refused(UpdateRefusal.UNREADABLE, "no package information")
        if (candidate.packageName != installed.packageName) {
            return UpdateVerdict.Refused(UpdateRefusal.WRONG_PACKAGE, "package ${candidate.packageName}")
        }
        if (candidate.multipleSigners || candidate.signersSha256.size != 1) {
            return UpdateVerdict.Refused(UpdateRefusal.SIGNER_COUNT, "${candidate.signersSha256.size} signers")
        }
        val signer = ReleaseSigner.normalizeColon(candidate.signersSha256[0])
            ?: return UpdateVerdict.Refused(UpdateRefusal.UNREADABLE, "signer is not a SHA-256")
        val installedSigners = installed.signersSha256.mapNotNull(ReleaseSigner::normalizeColon)
        if (installedSigners.size != 1) {
            return UpdateVerdict.Refused(UpdateRefusal.SIGNER_NOT_INSTALLED, "installed signer unreadable")
        }
        // The same signer, or a key rotation: the candidate's lineage (signed by the old key) lists the installed
        // certificate before its current one. Android enforces the lineage's capabilities when it installs.
        val lineage = candidate.lineageSha256.mapNotNull(ReleaseSigner::normalizeColon)
        val rotated = installedSigners[0] != signer && lineage.lastOrNull() == signer && installedSigners[0] in lineage.dropLast(1)
        if (installedSigners[0] != signer && !rotated) {
            return UpdateVerdict.Refused(UpdateRefusal.SIGNER_NOT_INSTALLED, "different publisher")
        }
        if (requirePinned) {
            if (installedSigners[0] !in pinned) {
                return UpdateVerdict.Refused(UpdateRefusal.ONLINE_UPDATE_NOT_OFFERED, "installed signer is not the release key")
            }
            // After a rotation the new certificate must already be pinned by the installed app (announced in an earlier release).
            if (signer !in pinned) {
                return UpdateVerdict.Refused(UpdateRefusal.SIGNER_NOT_PINNED, "signer is not the release key")
            }
        }
        val installedRelease = ReleaseNumber.fromVersionCode(installed.versionCode)
        val candidateRelease = ReleaseNumber.fromVersionCode(candidate.versionCode)
        val newer = if (installedRelease != null && candidateRelease != null) {
            candidateRelease > installedRelease
        } else {
            candidate.versionCode > installed.versionCode
        }
        if (!newer || candidate.versionCode <= installed.versionCode) {
            return UpdateVerdict.Refused(
                UpdateRefusal.NOT_NEWER,
                "versionCode ${candidate.versionCode} is not above ${installed.versionCode}",
            )
        }
        if (installedRelease != null) {
            val installedAbi = ReleaseNumber.abiDigit(installed.versionCode)
            val candidateAbi = ReleaseNumber.abiDigit(candidate.versionCode)
            if (candidateRelease == null || (candidateAbi != installedAbi && candidateAbi != ReleaseNumber.ABI_UNIVERSAL)) {
                return UpdateVerdict.Refused(UpdateRefusal.WRONG_ABI, "ABI digit $candidateAbi, installed $installedAbi")
            }
        }
        return UpdateVerdict.Accepted
    }

    /** The online check is offered only when the installed copy carries exactly the pinned release certificate. */
    fun onlineCheckOffered(installedSigners: List<String>): Boolean = ReleaseSigner.isPinnedSingleSigner(installedSigners)

    /**
     * Compares the digest computed over the verified private copy with the digest of the bytes just
     * written into the installer session; the session is committed only on a match.
     */
    fun sameBytes(verifiedSha256: String, writtenSha256: String): Boolean =
        MessageDigest.isEqual(
            verifiedSha256.lowercase().toByteArray(Charsets.US_ASCII),
            writtenSha256.lowercase().toByteArray(Charsets.US_ASCII),
        )
}

internal class HashMismatchException(message: String) : Exception(message)

/** Copies a stream while hashing it, so the hash is of exactly the bytes that were read. */
internal object HashedCopy {
    /** SHA-256 (lowercase hex) of everything copied; throws [IllegalStateException] over [maxBytes]. */
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long, onProgress: (Long) -> Unit = {}): HashedResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= maxBytes) { "The file is larger than $maxBytes bytes" }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            onProgress(total)
        }
        return HashedResult(ReleaseSigner.hex(digest.digest()), total)
    }

    /**
     * Writes [source] into [sink] (an installer session) and returns only when the bytes written hash
     * to [verifiedSha256], the digest of the private copy that passed [UpdateVerifier.verify]. A file
     * that changed after verification makes this throw, and the caller abandons the session.
     */
    fun copyBoundTo(source: InputStream, sink: OutputStream, verifiedSha256: String, maxBytes: Long): HashedResult {
        val result = copy(source, sink, maxBytes)
        if (!UpdateVerifier.sameBytes(verifiedSha256, result.sha256)) {
            throw HashMismatchException("The file changed between verification and installation")
        }
        return result
    }
}

internal data class HashedResult(val sha256: String, val bytes: Long)
