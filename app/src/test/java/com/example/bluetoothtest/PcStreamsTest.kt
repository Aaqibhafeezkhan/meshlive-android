package com.example.bluetoothtest

import org.junit.Assert.*
import org.junit.Test

class PcStreamsTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Suppress("UNCHECKED_CAST")
    private fun vectors(): List<Map<String, Any?>> {
        val text = javaClass.classLoader!!.getResourceAsStream("pc_announcement/pc_streams_v1.json")!!
            .use { String(it.readBytes(), Charsets.UTF_8) }
        return (TestJson.parse(text) as Map<String, Any?>)["vectors"] as List<Map<String, Any?>>
    }

    @Test
    fun sharedWireVectorsMapAndRejectInProtocolOrder() {
        for (v in vectors()) {
            val bytes = hex(v["hex"] as String)
            val result = ScanRecordForms.resolve(null, bytes, true, pcStreams = true)
            if (v["error"] != null) {
                assertTrue(v["name"].toString(), result is ScanRecordForms.Outcome.PcInvalid)
                assertEquals(v["name"].toString(), v["error"], (result as ScanRecordForms.Outcome.PcInvalid).error.name)
            } else if ((v["kind"] as Number).toInt() == 0) {
                assertArrayEquals(bytes, (result as ScanRecordForms.Outcome.Pc).decoded.encode())
            } else {
                val mapped = result as ScanRecordForms.Outcome.PcStream
                assertEquals((v["kind"] as Number).toInt(), mapped.kind)
                assertArrayEquals(hex(v["inner"] as String), DiscoveryAnnouncementCodec.encode(mapped.announcement))
                assertEquals(5, mapped.announcement.version)
                assertEquals(BluetoothTransportOption.BLE_GATT, mapped.announcement.transportOption())
            }
        }
    }

    @Test
    fun switchOffDropsRequestsAndOffersAndKeepsCallOffers() {
        for (v in vectors().filter { it["error"] == null }) {
            val bytes = hex(v["hex"] as String)
            val result = ScanRecordForms.resolve(null, bytes, true, pcStreams = false)
            if ((v["kind"] as Number).toInt() == 0) assertTrue(result is ScanRecordForms.Outcome.Pc)
            else assertTrue(result === ScanRecordForms.Outcome.PcDisabled)
        }
        assertFalse(ConnectivityFeatures.pcStreams.defaultEnabled)
    }

    @Test
    fun pcSourceAndItsForwardedCopyKeepTheSameLogicalTarget() {
        val v = vectors().first { it["name"] == "video source" }
        val source = PcStreamAnnouncement.decode(hex(v["hex"] as String))
        val target = LogicalStreamTarget.from(source)
        val relay = ByteArray(6) { 8 }
        val forwarded = source.copy(nodeIdPrefix = relay, routeCost = 1,
            streamToken = StreamRouteToken.forAdvertiser(target.token, relay),
            flags = source.flags or DiscoveryAnnouncement.FLAG_RELAY_CAPABLE)
        assertTrue(target.matches(source))
        assertTrue(target.matches(forwarded))
        val request = vectors().first { it["name"] == "viewer request" }
        val mapped = PcStreamAnnouncement.decode(hex(request["hex"] as String))
        assertTrue(mapped.isViewerRequest(mapped.streamToken))
        assertTrue(StreamGattFallback.acceptsViewerRequest(mapped.transportOption(), BluetoothTransportOption.BLE_GATT, false))
        assertFalse(StreamGattFallback.acceptsViewerRequest(mapped.transportOption(), BluetoothTransportOption.BLE_L2CAP, false))
    }

    /** The "video source" vector with its summary byte (outer byte 9) replaced. */
    private fun sourceWithSummary(summary: Int): ByteArray =
        hex(vectors().first { it["name"] == "video source" }["hex"] as String).also { it[9] = summary.toByte() }

    @Test
    fun hevcPcStreamOffersAreListedOnlyWhileTheHevcMasterIsOn() {
        val hevcOffer = sourceWithSummary(0x80 or 0x01)
        assertEquals(0x81, PcStreamAnnouncement.decode(hevcOffer, hevc = true).capabilitySummary)
        val hidden = runCatching { PcStreamAnnouncement.decode(hevcOffer, hevc = false) }.exceptionOrNull()
        assertEquals(PcAnnouncementError.Fields, (hidden as PcAnnouncementException).error)
        // HEVC alone, and other unknown bits still fail closed.
        assertEquals(0x80, PcStreamAnnouncement.decode(sourceWithSummary(0x80), hevc = true).capabilitySummary)
        for (bad in listOf(0x88, 0x90, 0xa0)) {
            assertTrue(runCatching { PcStreamAnnouncement.decode(sourceWithSummary(bad), hevc = true) }.isFailure)
        }
    }

    @Test
    fun aPhoneWithBmh3OffDoesNotDialAPcButStillDialsPhones() {
        PcCallTargets.clear()
        val v = vectors().first { it["name"] == "unchanged call offer" }
        val pc = (ScanRecordForms.resolve(null, hex(v["hex"] as String), true, pcStreams = true) as ScanRecordForms.Outcome.Pc).announcement
        assertTrue(pc.rotating)
        PcCallTargets.record(pc.nodeIdPrefix, 1_000L)
        val isPc = PcCallTargets.isPc(pc.nodeIdPrefix, 2_000L)
        assertTrue(isPc)
        // BMH3 off (benchmark launches): no BMH2 hello to the PC; the attempt ends with the plain message.
        assertTrue(PcCallTargets.refusesBmh2Dial(bmh3 = false, offer = pc, isPc = isPc))
        // User launches (BMH3 on by default) are unaffected: Bmh3Choice speaks BMH3 to the rotating offer.
        assertFalse(PcCallTargets.refusesBmh2Dial(bmh3 = true, offer = pc, isPc = isPc))
        assertTrue(Bmh3Choice.decide(true, false, pc, false).bmh3)
        // A phone's rotating offer with BMH3 off still dials with BMH2 (that phone accepts it).
        val phone = pc.copy(nodeIdPrefix = ByteArray(6) { 0x33 })
        assertFalse(PcCallTargets.isPc(phone.nodeIdPrefix, 2_000L))
        assertFalse(PcCallTargets.refusesBmh2Dial(bmh3 = false, offer = phone, isPc = false))
        // The memory expires after 30 minutes and stays bounded.
        assertFalse(PcCallTargets.isPc(pc.nodeIdPrefix, 1_000L + 30 * 60_000L + 1))
        repeat(100) { PcCallTargets.record(ByteArray(6) { b -> (it + b).toByte() }, 3_000L) }
        assertFalse(PcCallTargets.isPc(pc.nodeIdPrefix, 3_000L))
        PcCallTargets.clear()
    }
}
