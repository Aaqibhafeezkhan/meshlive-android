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
}
