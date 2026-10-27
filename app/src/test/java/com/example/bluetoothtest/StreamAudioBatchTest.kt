package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experimental stream audio batches (`test_stream_audio_batch_frames`): a viewer on an unchanged reader gets the same
 * units, timestamps and order from 100-200 ms batches as from today's 40 ms pairs, in direct and mesh streams, for
 * fewer bytes on every hop.
 */
class StreamAudioBatchTest {
    private val identity = generateEcKeyPair()
    private val sourceNode = NodeId.fromPublicKey(identity.public.encoded)
    private val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 3).toByte() }
    private val mediaKey = ByteArray(MeshAead.KEY_BYTES) { (it + 77).toByte() }
    private val units = (0 until 20).map { index ->
        StreamProtocol.Packet(StreamProtocol.FLAG_AUDIO, 40_000_000L + index * 20_000L, ByteArray(60 + index % 7) { (index + it).toByte() })
    }

    /** What the broadcaster sends for [frames] units per record (2 = today's pairs). */
    private fun records(frames: Int): List<StreamProtocol.Packet> {
        val out = ArrayList<StreamProtocol.Packet>()
        val pending = ArrayList<StreamProtocol.Packet>()
        for (unit in units) {
            if (!streamAudioBatchAccepts(pending, unit)) {
                out += combineCallAudio(pending.toList()); pending.clear()
            }
            pending += unit
            if (pending.size >= frames) {
                out += combineCallAudio(pending.toList()); pending.clear()
            }
        }
        if (pending.isNotEmpty()) out += combineCallAudio(pending)
        return out
    }

    private fun stream(mesh: Boolean, frames: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val link = SecureRecordWriter(bytes, linkKey)
        val writer = EndToEndStreamWriter(link, EndToEndContentSealer(mediaKey), sourceNode, mesh)
        writer.writeHeader(StreamProtocol.CODEC_VP9, VideoSettings.DEFAULT, 0, includeAudio = true)
        records(frames).forEach { writer.writePacket(it.flags, it.presentationTimeUs, it.payload) }
        writer.writeState(StreamLifecycleState.ENDED)
        link.flush()
        return bytes.toByteArray()
    }

    private fun play(bytes: ByteArray): List<StreamProtocol.Packet> {
        val reader = EndToEndStreamReader(SecureRecordReader(ByteArrayInputStream(bytes), linkKey), EndToEndContentOpener(mediaKey), sourceNode)
        assertTrue(reader.readHeader().hasAudio)
        val played = ArrayList<StreamProtocol.Packet>()
        while (true) {
            when (val content = reader.readStreamContent()) {
                is ReceivedStreamContent.Media -> played += content.packet
                else -> return played
            }
        }
    }

    @Test
    fun viewersPlayTheSameUnitsFromBatchesAsFromPairsForFewerBytes() {
        for (mesh in listOf(false, true)) {
            val pairs = stream(mesh, frames = 2)
            for (frames in listOf(5, 10)) {
                val batched = stream(mesh, frames)
                val played = play(batched)
                assertEquals(units.size, played.size)
                units.zip(played).forEach { (sent, got) ->
                    assertEquals(sent.flags, got.flags)
                    assertEquals(sent.presentationTimeUs, got.presentationTimeUs)
                    assertArrayEquals(sent.payload, got.payload)
                }
                assertTrue("mesh=$mesh frames=$frames", batched.size < pairs.size - (units.size / 2 - units.size / frames) * 60)
            }
            assertEquals(units.map { it.presentationTimeUs }, play(pairs).map { it.presentationTimeUs })
        }
    }

    @Test
    fun onlyThreeToTenFramesTurnBatchingOn() {
        assertEquals(listOf(2, 2, 2, 3, 5, 10, 2), listOf(-1, 0, 2, 3, 5, 10, 11).map(::streamAudioBatchFrames))
    }

    @Test
    fun aTimestampJumpOrRewindStartsANewBatch() {
        val first = units[0]
        assertTrue(streamAudioBatchAccepts(emptyList(), first))
        assertTrue(streamAudioBatchAccepts(listOf(first), units[1]))
        assertFalse(streamAudioBatchAccepts(listOf(units[1]), first))
        val jump = first.copy(presentationTimeUs = first.presentationTimeUs + StreamProtocol.MAX_AUDIO_PAIR_GAP_US + 1)
        assertFalse(streamAudioBatchAccepts(listOf(first), jump))
    }
}
