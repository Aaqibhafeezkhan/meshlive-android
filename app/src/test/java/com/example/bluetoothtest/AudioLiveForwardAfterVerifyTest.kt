package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A relay of an audio-only live stream hands a record to its children only after its own reader verified it: a
 * forged, unsigned or wrongly certified record never reaches the forwarding queue. Video streams are unchanged.
 */
class AudioLiveForwardAfterVerifyTest {
    private val streamId = UUID.fromString("5f0e8a51-2d4b-4c1e-9a77-0c3f6b1d2e44")
    private val identity = KeyPairSigningIdentity(generateEcKeyPair())
    private val source = identity.nodeId
    private val mediaKey = ByteArray(MeshAead.KEY_BYTES) { (it + 41).toByte() }
    private val linkKey = ByteArray(MeshAead.KEY_BYTES) { (it + 1).toByte() }
    private val opusHead = ByteArray(19).also {
        "OpusHead".forEachIndexed { index, c -> it[index] = c.code.toByte() }
        it[8] = 1
        it[9] = 1
    }

    private fun frame(): ByteArray = ByteArray(60) { 3 }.also { it[0] = 0xf8.toByte() }

    private fun relay(bytes: ByteArray, forwarded: MutableList<MeshEnvelope>) = EndToEndStreamReader(
        SecureRecordReader(ByteArrayInputStream(bytes), linkKey),
        EndToEndContentOpener(mediaKey),
        source,
    ) { envelope -> forwarded += envelope }

    private class Source(val writer: EndToEndStreamWriter, val link: SecureRecordWriter, val sealer: EndToEndContentSealer)

    private fun stream(header: AudioLiveSigner, write: (Source) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val link = SecureRecordWriter(bytes, linkKey)
        val sealer = EndToEndContentSealer(mediaKey)
        val writer = EndToEndStreamWriter(link, sealer, source, true, meshStreamId = streamId)
        writer.writeAudioLiveHeader(header)
        write(Source(writer, link, sealer))
        link.flush()
        return bytes.toByteArray()
    }

    @Test
    fun aForgedOrUnsignedRecordIsNeverForwardedButVerifiedOnesAre() {
        val signer = AudioLiveSigner(identity, streamId, AudioLive.PRESET_VOICE)
        val impostor = AudioLiveSigner(identity, streamId, AudioLive.PRESET_VOICE) // another stream key
        val cases = mapOf<String, (Source) -> Unit>(
            "signed by another stream key" to { s -> s.writer.writeAudioLiveRecord(impostor.sign(StreamProtocol.FLAG_AUDIO, 40_000L, frame())) },
            // A sender that holds the media key but not the stream key (a viewer-forwarder) seals an unsigned record.
            "unsigned" to { s ->
                val packet = EndToEndStreamProtocol.encodePacket(StreamProtocol.FLAG_AUDIO, 40_000L, frame())
                val sealed = s.sealer.seal(EndToEndContentType.MEDIA_PACKET, StreamProtocol.FLAG_AUDIO, packet)
                val envelope = MeshEnvelope(streamId, source, 3, 5, StreamProtocol.FLAG_AUDIO, sealed)
                s.link.write(SecureRecordType.MESH_ENVELOPE, MeshEnvelopeCodec.encode(envelope))
            },
        )
        for ((label, forge) in cases) {
            val bytes = stream(signer) { s ->
                s.writer.writeAudioLiveRecord(signer.sign(StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG, 0, opusHead))
                s.writer.writeAudioLiveRecord(signer.sign(StreamProtocol.FLAG_AUDIO, 20_000L, frame()))
                forge(s)
            }
            val forwarded = mutableListOf<MeshEnvelope>()
            val reader = relay(bytes, forwarded)
            assertTrue(reader.readHeader(allowAudioOnly = true).isAudioOnly)
            reader.readStreamContent()
            reader.readStreamContent()
            assertEquals(label, listOf(0L, 1L, 2L), forwarded.map { it.sequence })
            assertThrows(label, Exception::class.java) { reader.readStreamContent() }
            assertEquals(label, listOf(0L, 1L, 2L), forwarded.map { it.sequence })
        }
    }

    @Test
    fun aHeaderWhoseCertificateNamesAnotherSourceIsNeverForwarded() {
        val stranger = AudioLiveSigner(KeyPairSigningIdentity(generateEcKeyPair()), streamId, AudioLive.PRESET_VOICE)
        val forwarded = mutableListOf<MeshEnvelope>()
        val reader = relay(stream(stranger) {}, forwarded)
        assertThrows(Exception::class.java) { reader.readHeader(allowAudioOnly = true) }
        assertTrue(forwarded.isEmpty())
    }

    @Test
    fun videoStreamsStillForwardEveryOpenedRecordAtOnce() {
        val bytes = ByteArrayOutputStream()
        val link = SecureRecordWriter(bytes, linkKey)
        val writer = EndToEndStreamWriter(link, EndToEndContentSealer(mediaKey), source, true, meshStreamId = streamId)
        writer.writeHeader(StreamProtocol.CODEC_VP9, VideoSettings.DEFAULT, 0, includeAudio = true)
        writer.writePacket(StreamProtocol.FLAG_KEY_FRAME, 0L, ByteArray(500) { 1 })
        link.flush()
        val forwarded = mutableListOf<MeshEnvelope>()
        val reader = relay(bytes.toByteArray(), forwarded)
        reader.readHeader(allowAudioOnly = true)
        assertEquals(1, forwarded.size)
        reader.readStreamContent()
        assertEquals(listOf(0L, 1L), forwarded.map { it.sequence })
    }
}
