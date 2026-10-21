package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class LocalReportingTest {
    @Test fun reportingBlocksAndHidesTheSamePersonWithoutHidingOtherPeople() {
        val root = Files.createTempDirectory("local-report").toFile()
        try {
            val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32), "AES") }
            val file = File(root, "blocked.bin")
            val person = IdentityFingerprint.fromPublicKey(byteArrayOf(1, 2, 3))
            val other = IdentityFingerprint.fromPublicKey(byteArrayOf(4, 5, 6))
            var hidden = emptySet<String>()
            fun report() = LocalReportPolicy.report({ BlockedPeopleStore(file, cipher).add(person) }) {
                hidden = hidden + person.hex
                true
            }
            assertTrue(report())
            assertTrue(BlockedPeopleStore(file, cipher).contains(person))
            assertFalse(BlockedPeopleStore(file, cipher).contains(other))
            assertTrue(LocalReportPolicy.hidden(person.hex, hidden))
            assertTrue(LocalReportPolicy.hidden(person.nodeId.toString(), hidden))
            assertTrue(LocalReportPolicy.hidden(person.nodePrefixHex, hidden))
            assertFalse(LocalReportPolicy.hidden(other.hex, hidden))
            assertFalse(LocalReportPolicy.hidden("", hidden))
            assertFalse(LocalReportPolicy.hidden(person.hex.take(4), hidden))
            assertTrue(report()) // already blocked is still a successful local report
            BlockedPeopleStore(file, cipher).remove(person)
            assertTrue(LocalReportPolicy.hidden(person.hex, hidden)) // unblocking is not unreporting
        } finally { root.deleteRecursively() }
    }

    @Test fun aFailedBlockNeverHidesOrClaimsSuccess() {
        for (failure in listOf(BlockResult.FULL, BlockResult.STORE_UNREADABLE, BlockResult.UNAVAILABLE)) {
            var hidden = false
            assertFalse(LocalReportPolicy.report({ failure }) { hidden = true; true })
            assertFalse(hidden)
        }
        assertFalse(LocalReportPolicy.report({ BlockResult.BLOCKED }) { false })
    }
}
