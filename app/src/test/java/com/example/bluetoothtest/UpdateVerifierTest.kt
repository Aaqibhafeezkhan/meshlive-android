package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVerifierTest {
    private fun der(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)).use { it.readBytes() }

    // Throwaway certificates made with keytool (secp256r1, "Test only"); the fingerprints are keytool's own output.
    private val certA = der("cert-a.der")
    private val certB = der("cert-b.der")
    private val fingerprintA = "C0:07:A0:73:16:69:3C:35:C9:0B:51:AC:10:6A:BF:5D:7A:D6:DA:89:98:D3:24:3D:9C:06:BB:8B:B2:63:87:04"
    private val fingerprintB = "7E:55:D0:81:7E:7C:76:DD:9D:0D:C7:E1:89:28:CE:59:83:48:4C:9E:BE:A7:00:91:58:07:9E:5C:FB:93:09:7F"
    private val release = AppSharePolicy.PUBLISHED_RELEASE_CERT_SHA256

    private val pkg = "io.github.meshlive.app"

    private fun installed(code: Long = 12, signer: String = release) = InstalledFacts(pkg, code, listOf(signer))

    private fun apk(code: Long = 22, signer: String = release, name: String = pkg) =
        ApkFacts(name, code, listOf(signer), multipleSigners = false)

    private fun apkSigners(code: Long, signers: List<String>, multiple: Boolean = false, lineage: List<String> = emptyList()) =
        ApkFacts(pkg, code, signers, multiple, lineage)

    private fun refusal(verdict: UpdateVerdict): UpdateRefusal? = (verdict as? UpdateVerdict.Refused)?.reason

    // ---- certificates ----

    @Test
    fun certificateFingerprintsMatchKeytoolAndApksignerFormat() {
        assertEquals(fingerprintA, ReleaseSigner.sha256Colon(certA))
        assertEquals(fingerprintB, ReleaseSigner.sha256Colon(certB))
        assertEquals(fingerprintA.replace(":", "").lowercase(), ReleaseSigner.sha256Hex(certA))
    }

    @Test
    fun pinnedSetIsExactlyThePublishedReleaseCertificate() {
        assertEquals(setOf(release.uppercase()), ReleaseSigner.PINNED)
        assertTrue(ReleaseSigner.isPinnedSingleSigner(listOf(release)))
        assertTrue(ReleaseSigner.isPinnedSingleSigner(listOf(release.lowercase().replace(":", ""))))
        assertFalse(ReleaseSigner.isPinnedSingleSigner(listOf(fingerprintA)))
        assertFalse("two signers", ReleaseSigner.isPinnedSingleSigner(listOf(release, fingerprintA)))
        assertFalse(ReleaseSigner.isPinnedSingleSigner(emptyList()))
        assertFalse(ReleaseSigner.isPinnedSingleSigner(listOf("not a fingerprint")))
        assertEquals(null, ReleaseSigner.normalizeColon("AB:CD"))
        assertEquals(null, ReleaseSigner.normalizeColon("g".repeat(64)))
    }

    @Test
    fun pythonAndKotlinPinTheSameCertificate() {
        val script = File("../tools/make_release.py").takeIf { it.isFile } ?: File("tools/make_release.py")
        val text = script.readText()
        val digits = Regex("""EXPECTED_CERT_SHA256 = \(((?:\s*"[0-9A-F:]+"\s*)+)\)""").find(text)!!.groupValues[1]
        val python = Regex("\"([0-9A-F:]+)\"").findAll(digits).joinToString("") { it.groupValues[1] }
        assertEquals(release.uppercase(), python)
    }

    // ---- the install rules ----

    @Test
    fun acceptsANewerReleaseSignedWithTheInstalledReleaseKey() {
        assertEquals(UpdateVerdict.Accepted, UpdateVerifier.verify(apk(22), installed(12), requirePinned = true))
        assertEquals(UpdateVerdict.Accepted, UpdateVerifier.verify(apk(20), installed(12), requirePinned = true))
        assertEquals("a universal file may replace an ABI-specific install", UpdateVerdict.Accepted,
            UpdateVerifier.verify(apk(30), installed(22), requirePinned = true))
    }

    @Test
    fun refusesAnotherPackage() {
        assertEquals(UpdateRefusal.WRONG_PACKAGE, refusal(UpdateVerifier.verify(apk(22, name = "com.evil.app"), installed(), true)))
    }

    @Test
    fun refusesASignerThatIsNotTheInstalledOne() {
        assertEquals(UpdateRefusal.SIGNER_NOT_INSTALLED, refusal(UpdateVerifier.verify(apk(22, fingerprintA), installed(), true)))
        assertEquals(UpdateRefusal.SIGNER_NOT_INSTALLED, refusal(UpdateVerifier.verify(apk(22, fingerprintA), installed(), false)))
    }

    @Test
    fun refusesMultipleOrMissingSigners() {
        assertEquals(UpdateRefusal.SIGNER_COUNT, refusal(UpdateVerifier.verify(apkSigners(22, listOf(release, fingerprintA)), installed(), true)))
        assertEquals(UpdateRefusal.SIGNER_COUNT, refusal(UpdateVerifier.verify(apkSigners(22, listOf(release), multiple = true), installed(), true)))
        assertEquals(UpdateRefusal.SIGNER_COUNT, refusal(UpdateVerifier.verify(apkSigners(22, emptyList()), installed(), true)))
        assertEquals(UpdateRefusal.UNREADABLE, refusal(UpdateVerifier.verify(null, installed(), true)))
        assertEquals(UpdateRefusal.UNREADABLE, refusal(UpdateVerifier.verify(apk(22, "not a hash"), installed(), true)))
    }

    @Test
    fun anOnlineUpdateNeedsTheInstalledCopyToCarryTheReleaseKeyAndTheFileToo() {
        // A debug or F-Droid-signed copy (signer A) matches a file signed A, but not the pinned release key.
        val debugInstall = installed(12, fingerprintA)
        assertEquals(UpdateRefusal.ONLINE_UPDATE_NOT_OFFERED, refusal(UpdateVerifier.verify(apk(22, fingerprintA), debugInstall, true)))
        assertEquals("a nearby phone's file with the same signer is fine", UpdateVerdict.Accepted,
            UpdateVerifier.verify(apk(22, fingerprintA), debugInstall, requirePinned = false))
        // A test-only pin set (DEBUG) accepts its own signer.
        assertEquals(UpdateVerdict.Accepted,
            UpdateVerifier.verify(apk(22, fingerprintA), debugInstall, true, pinned = setOf(fingerprintA)))
        assertFalse(UpdateVerifier.onlineCheckOffered(listOf(fingerprintA)))
        assertTrue(UpdateVerifier.onlineCheckOffered(listOf(release)))
    }

    @Test
    fun neverDowngradesOrReinstallsTheSameRelease() {
        assertEquals(UpdateRefusal.NOT_NEWER, refusal(UpdateVerifier.verify(apk(12), installed(12), true)))
        assertEquals(UpdateRefusal.NOT_NEWER, refusal(UpdateVerifier.verify(apk(2), installed(12), true)))
        assertEquals("same release, other ABI digit", UpdateRefusal.NOT_NEWER, refusal(UpdateVerifier.verify(apk(22), installed(20), true)))
        assertEquals("same release, higher digit is still the same release", UpdateRefusal.NOT_NEWER,
            refusal(UpdateVerifier.verify(apk(12), installed(10), true)))
        assertEquals(UpdateRefusal.NOT_NEWER, refusal(UpdateVerifier.verify(apk(12), installed(Long.MAX_VALUE), true)))
    }

    @Test
    fun aThirtyTwoBitFileNeverReplacesASixtyFourBitInstall() {
        assertEquals(UpdateRefusal.WRONG_ABI, refusal(UpdateVerifier.verify(apk(21), installed(12), true)))
        assertEquals(UpdateRefusal.WRONG_ABI, refusal(UpdateVerifier.verify(apk(22), installed(11), true)))
        assertEquals(UpdateVerdict.Accepted, UpdateVerifier.verify(apk(21), installed(11), true))
    }

    @Test
    fun aNonSplitDebugInstallSkipsTheAbiRuleButStillNeedsAHigherCode() {
        assertEquals(UpdateVerdict.Accepted, UpdateVerifier.verify(apk(12, fingerprintA), installed(1, fingerprintA), false))
        assertEquals(UpdateRefusal.NOT_NEWER, refusal(UpdateVerifier.verify(apk(1, fingerprintA), installed(1, fingerprintA), false)))
        assertEquals("a code too large for a release number cannot be matched to an ABI", UpdateRefusal.WRONG_ABI,
            refusal(UpdateVerifier.verify(apk(400_000), installed(12), true)))
    }

    // ---- key rotation (security review F3) ----

    @Test
    fun aRotatedKeyPassesWhenItsLineageNamesTheInstalledCertificateAndTheNewOneIsPinned() {
        val pins = setOf(fingerprintA, release)
        val rotated = apkSigners(22, listOf(release), lineage = listOf(fingerprintA, release))
        assertEquals(UpdateVerdict.Accepted, UpdateVerifier.verify(rotated, installed(12, fingerprintA), requirePinned = true, pinned = pins))
        assertEquals(
            "a file from a nearby phone needs only the lineage",
            UpdateVerdict.Accepted,
            UpdateVerifier.verify(rotated, installed(12, fingerprintA), requirePinned = false),
        )
    }

    @Test
    fun aRotatedKeyIsRefusedWithoutProofOrPin() {
        val pins = setOf(fingerprintA, release)
        // The lineage does not contain the installed certificate.
        val unrelated = apkSigners(22, listOf(release), lineage = listOf(fingerprintB, release))
        assertEquals(UpdateRefusal.SIGNER_NOT_INSTALLED, refusal(UpdateVerifier.verify(unrelated, installed(12, fingerprintA), true, pins)))
        // A lineage whose last entry is not the current signer proves nothing about it.
        val misordered = apkSigners(22, listOf(release), lineage = listOf(release, fingerprintA))
        assertEquals(UpdateRefusal.SIGNER_NOT_INSTALLED, refusal(UpdateVerifier.verify(misordered, installed(12, fingerprintA), false)))
        // No lineage at all.
        assertEquals(UpdateRefusal.SIGNER_NOT_INSTALLED, refusal(UpdateVerifier.verify(apk(22, release), installed(12, fingerprintA), false)))
        // A valid lineage to a certificate the installed app does not pin is refused for the online check.
        val rotated = apkSigners(22, listOf(release), lineage = listOf(fingerprintA, release))
        assertEquals(
            UpdateRefusal.SIGNER_NOT_PINNED,
            refusal(UpdateVerifier.verify(rotated, installed(12, fingerprintA), true, pinned = setOf(fingerprintA))),
        )
        // The installed copy itself must carry a pinned key for the online check.
        assertEquals(
            UpdateRefusal.ONLINE_UPDATE_NOT_OFFERED,
            refusal(UpdateVerifier.verify(rotated, installed(12, fingerprintA), true, pinned = setOf(release))),
        )
    }

    // ---- hash binding between verification and installation ----

    @Test
    fun hashedCopyHashesExactlyTheBytesItWrites() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        val result = HashedCopy.copy(ByteArrayInputStream(data), out, 1_000_000)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(ReleaseSigner.sha256Hex(data), result.sha256)
        assertEquals(200_000L, result.bytes)
    }

    @Test
    fun hashedCopyEnforcesTheSizeLimit() {
        assertThrows(IllegalStateException::class.java) {
            HashedCopy.copy(ByteArrayInputStream(ByteArray(1_000)), ByteArrayOutputStream(), 999)
        }
    }

    @Test
    fun aFileSwappedAfterVerificationIsNotCommitted() {
        val verified = ByteArray(100_000) { it.toByte() }
        val verifiedHash = ReleaseSigner.sha256Hex(verified)
        val swapped = verified.copyOf().also { it[50_000] = (it[50_000] + 1).toByte() }
        val session = ByteArrayOutputStream()
        assertThrows(HashMismatchException::class.java) {
            HashedCopy.copyBoundTo(ByteArrayInputStream(swapped), session, verifiedHash, 1_000_000)
        }
        // The same bytes pass.
        val ok = HashedCopy.copyBoundTo(ByteArrayInputStream(verified), ByteArrayOutputStream(), verifiedHash, 1_000_000)
        assertEquals(verifiedHash, ok.sha256)
    }

    @Test
    fun sameBytesComparesCaseInsensitivelyAndRejectsDifferentLengths() {
        assertTrue(UpdateVerifier.sameBytes("ABCDEF", "abcdef"))
        assertFalse(UpdateVerifier.sameBytes("abcdef", "abcdee"))
        assertFalse(UpdateVerifier.sameBytes("abcdef", "abcde"))
    }

    @Test
    fun theSessionIsWrittenBeforeTheCheckSoTheCallerMustAbandonItOnMismatch() {
        // Documents the contract: bytes reach the sink before the mismatch is known, so the caller owns abandon().
        val written = object : OutputStream() {
            var count = 0
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        }
        assertThrows(HashMismatchException::class.java) {
            HashedCopy.copyBoundTo(ByteArrayInputStream(ByteArray(10)), written, "0".repeat(64), 100)
        }
        assertEquals(10, written.count)
    }
}
