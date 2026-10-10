package com.example.bluetoothtest

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicBondFreeTest {
    private val nodeA = "0123456789abcdef0123456789abcdef"
    private val nodeB = "fedcba9876543210fedcba9876543210"

    @Test
    fun benchmarkLaunchUsesOnlyItsExtrasAndTheUiSettingNeedsAdvancedMode() {
        val stored = { _: String -> true }
        assertEquals(
            ClassicFeatureSettings(),
            ClassicFeatureSettings.resolve(benchmarkLaunch = true, extra = { false }, advancedSettings = true, stored = stored),
        )
        assertEquals(
            ClassicFeatureSettings(bondFree = true, requestVisibility = false),
            ClassicFeatureSettings.resolve(
                benchmarkLaunch = true,
                extra = { it == ClassicFeatureSettings.EXTRA_BOND_FREE },
                advancedSettings = false,
                stored = { false },
            ),
        )
        // The visibility prompt is only ever part of bond-free mode.
        assertEquals(
            ClassicFeatureSettings(),
            ClassicFeatureSettings.resolve(
                benchmarkLaunch = true,
                extra = { it == ClassicFeatureSettings.EXTRA_REQUEST_VISIBILITY },
                advancedSettings = false,
                stored = { false },
            ),
        )
        assertEquals(
            ClassicFeatureSettings(),
            ClassicFeatureSettings.resolve(benchmarkLaunch = false, extra = { true }, advancedSettings = false, stored = stored),
        )
        assertEquals(
            ClassicFeatureSettings(bondFree = true, requestVisibility = true),
            ClassicFeatureSettings.resolve(benchmarkLaunch = false, extra = { false }, advancedSettings = true, stored = stored),
        )
    }

    @Test
    fun boundedConnectIsOffByDefaultAndBondFreeImpliesIt() {
        assertFalse(ClassicFeatureSettings().usesBoundedConnect)
        assertTrue(ClassicFeatureSettings(bondFree = true).usesBoundedConnect)
        val bounded = ClassicFeatureSettings.resolve(
            benchmarkLaunch = true,
            extra = { it == ClassicFeatureSettings.EXTRA_BOUNDED_CONNECT },
            advancedSettings = false,
            stored = { false },
        )
        assertTrue(bounded.usesBoundedConnect)
        assertFalse(bounded.bondFree)
    }

    @Test
    fun onlyCallsAndRelaysWereSecureAndBondFreeMakesEveryRoleInsecure() {
        val secureByDefault = ClassicRole.entries.filter { classicRoleUsesSecureRfcomm(it, bondFree = false) }
        assertEquals(
            setOf(ClassicRole.PRIVATE_CALL, ClassicRole.CALL_RELAY, ClassicRole.STREAM_RELAY),
            secureByDefault.toSet(),
        )
        assertTrue(ClassicRole.entries.none { classicRoleUsesSecureRfcomm(it, bondFree = true) })
    }

    @Test
    fun addressBookFindsTheNewestFreshAddressByAnnouncedPrefix() {
        val book = ClassicAddressBookState()
        assertTrue(book.learn(nodeA, "aa:bb:cc:00:00:01", nowMs = 1_000L))
        assertFalse(book.learn(nodeA, "AA:BB:CC:00:00:01", nowMs = 2_000L))
        assertTrue(book.learn(nodeB, "AA:BB:CC:00:00:02", nowMs = 3_000L))

        assertEquals("AA:BB:CC:00:00:01", book.lookup(nodeA.take(12), nowMs = 4_000L))
        assertEquals("AA:BB:CC:00:00:02", book.lookup(nodeB.take(12).uppercase(), nowMs = 4_000L))
        assertNull(book.lookup("000000000000", nowMs = 4_000L))
        // A repeat sighting refreshes the entry in memory (it is rewritten to disk at most daily).
        assertEquals("AA:BB:CC:00:00:01", book.lookup(nodeA.take(12), nowMs = 1_500L + ClassicAddressBookState.TTL_MS))
        assertNull(book.lookup(nodeA.take(12), nowMs = 2_000L + ClassicAddressBookState.TTL_MS))
    }

    @Test
    fun anAddressBelongsToOnePhoneAndAWrongIdentityIsForgotten() {
        val book = ClassicAddressBookState()
        book.learn(nodeA, "AA:BB:CC:00:00:01", nowMs = 1L)
        // The same phone now authenticates with a new identity (app reinstalled).
        book.learn(nodeB, "AA:BB:CC:00:00:01", nowMs = 2L)
        assertNull(book.lookup(nodeA.take(12), nowMs = 3L))
        assertEquals(1, book.size())

        assertFalse(book.forget(nodeA.take(12), "AA:BB:CC:00:00:01"))
        assertTrue(book.forget(nodeB.take(12), "AA:BB:CC:00:00:01"))
        assertNull(book.lookup(nodeB.take(12), nowMs = 3L))
    }

    @Test
    fun addressBookRejectsPlaceholdersBoundsItsSizeAndRoundTrips() {
        val book = ClassicAddressBookState(maxEntries = 2)
        assertFalse(book.learn(nodeA, "02:00:00:00:00:00", nowMs = 1L))
        assertFalse(book.learn("not-a-node", "AA:BB:CC:00:00:01", nowMs = 1L))
        assertFalse(book.learn(nodeA, "AA:BB:CC:00:00", nowMs = 1L))
        val nodes = (1..3).map { index -> "%02d".format(index).repeat(16) }
        nodes.forEachIndexed { index, node -> book.learn(node, "AA:BB:CC:00:00:0${index + 1}", nowMs = index + 10L) }
        assertEquals(2, book.size())
        assertNull(book.lookup(nodes[0].take(12), nowMs = 20L))

        val decoded = ClassicAddressBookState.decode(book.encode() + "\ngarbage line\n", maxEntries = 2)
        assertEquals("AA:BB:CC:00:00:03", decoded.lookup(nodes[2].take(12), nowMs = 20L))
        assertEquals("AA:BB:CC:00:00:02", decoded.lookup(nodes[1].take(12), nowMs = 20L))
    }

    @Test
    fun scanAddressTypeIsOnlyTrustedWhereItIsPublicApi() {
        assertFalse(classicScanAddressTypeReported(BluetoothStackFingerprint("qcom", "msmnile", 34)))
        assertTrue(classicScanAddressTypeReported(BluetoothStackFingerprint("s5e9945", "s5e9945", 35)))
    }

    @Test
    fun debugAddressOverridesParseOnlyWellFormedEntries() {
        assertEquals(
            mapOf("9c8d7e6f5a40" to "AA:BB:CC:00:00:01", "8a1b2c3d4e5f" to "AA:BB:CC:00:00:02"),
            parseClassicAddressOverrides("9C8D7E6F5A40=aa:bb:cc:00:00:01; 8a1b2c3d4e5f=AA:BB:CC:00:00:02,bad,x=1"),
        )
        assertTrue(parseClassicAddressOverrides(null).isEmpty())
    }

    @Test
    fun callLinkPacingIsAnOptInForSocketTransportsOnly() {
        assertFalse(callLinkPaced(BluetoothTransportOption.CLASSIC_RFCOMM, callLinkPacing = false, linkPacing = true))
        assertTrue(callLinkPaced(BluetoothTransportOption.CLASSIC_RFCOMM, callLinkPacing = true, linkPacing = true))
        assertTrue(callLinkPaced(BluetoothTransportOption.BLE_L2CAP, callLinkPacing = true, linkPacing = true))
        assertFalse(callLinkPaced(BluetoothTransportOption.BLE_GATT, callLinkPacing = true, linkPacing = true))
        assertFalse(callLinkPaced(BluetoothTransportOption.CLASSIC_RFCOMM, callLinkPacing = true, linkPacing = false))
    }

    @Test
    fun unresolvedClassicCallExplainsPairingInsteadOfAGenericFailure() {
        val unresolved = ClassicPeerUnresolvedException(classicUnresolvedPeerMessage(bondFree = false))
        assertEquals(
            classicUnresolvedPeerMessage(bondFree = false),
            privateCallConnectionFailureMessage(BluetoothTransportOption.CLASSIC_RFCOMM, unresolved),
        )
        val other = privateCallConnectionFailureMessage(BluetoothTransportOption.CLASSIC_RFCOMM, IOException("read failed"))
        assertTrue(other.contains("read failed"))
        assertTrue(other.contains("Pair the phones"))
    }
}
