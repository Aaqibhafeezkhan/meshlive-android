package com.example.bluetoothtest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/*
 * Messaging v2 phase 1b: the send side of the attachment pipeline (DECISIONS Design M3). The user picks something (the
 * Photo Picker for pictures and videos, `OpenDocument` for any file, the voice recorder of v1); this class turns it into a
 * [PreparedAttachment] the engine's `sendFile` takes. Blocking: call from a worker thread. Experimental (master switch).
 *
 *  - Photo, default: orientation applied, transparency flattened on white, longest edge `photoEdge` (1600), JPEG at
 *    `photoQuality` (80); nothing from the original file's metadata survives re-encoding. A 320 px JPEG thumbnail
 *    (at most 16 KiB) goes into the manifest; it is carried and not decoded by the receiver unless a switch says so.
 *  - Photo, "Original": a JPEG goes through [JpegMetadataFilter] (every APP/COM segment and any trailer removed, a
 *    minimal orientation tag kept); PNG, WebP, HEIC, GIF and the rest are sent unchanged and the sheet says they may carry
 *    hidden data.
 *  - Video: lossless remux of the audio and video tracks with no location call, then [Mp4Scrubber]; if the platform cannot
 *    remux it, the video is sent as it is and the sheet warns.
 *  - Any other file: as it is, except that a JPEG chosen as a file is filtered like an Original.
 */

/** What the send sheet should tell the user about what was done to the file. */
enum class PrepareNote {
    PHOTO_REENCODED,
    JPEG_METADATA_REMOVED,
    ORIGINAL_MAY_CARRY_HIDDEN_DATA,
    ANIMATION_KEPT_UNCHANGED,
    JPEG_UNREADABLE_REENCODED,
    VIDEO_REMUXED_NO_LOCATION,
    VIDEO_SENT_AS_FILE_MAY_CARRY_LOCATION,
    SENT_AS_IS,
}

enum class SendMode {
    /** Photo or video from the Photo Picker. */
    MEDIA,

    /** Any file from the document picker. */
    FILE,
}

class AttachException(val refusal: AttachRefusal) : IOException(refusal.message)

/**
 * A file ready for `MessagingV2Engine.sendFile`. [open] may be called more than once; [close] removes any temporary
 * file (overwritten with zeros first). Send first, then close.
 */
class PreparedAttachment(
    val name: String,
    val mime: String,
    val presentation: Presentation,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val thumbnail: ByteArray,
    val notes: List<PrepareNote>,
    val open: () -> InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}

object MessagingV2Attachments {
    /** Hands a prepared attachment to the engine (inline or segmented, decided by the engine from its size). */
    fun send(
        engine: MessagingV2Engine,
        contact: NodeId,
        prepared: PreparedAttachment,
        replyTo: ByteArray? = null,
        /** The conversation's disappearing timer (thread info); 0 is off. */
        disappearSeconds: Int = 0,
    ): ThreadMessage =
        engine.sendFile(
            contact, prepared.open, prepared.size, prepared.presentation, prepared.name, prepared.mime,
            prepared.width, prepared.height, prepared.durationMs, prepared.thumbnail, disappearSeconds = disappearSeconds, replyTo = replyTo,
        )
}

class AttachmentPreparer(
    context: Context,
    private val options: MessagingV2AttachOptions = MessagingV2Options.current,
    private val random: SecureRandom = SecureRandom(),
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val tempDir = File(this.context.cacheDir, TEMP_DIR)

    /**
     * Prepares [uri]. [original] is the sheet's "Original" choice for a picture (ignored for other files). Throws
     * [AttachException] for a file that is empty, too large or unreadable.
     */
    fun prepare(uri: Uri, mode: SendMode, original: Boolean = false): PreparedAttachment {
        sweepTemp()
        val meta = queryMeta(uri)
        val head = readHead(uri, HEAD_BYTES)
        val total = meta.size ?: -1L
        val type = FileSniffer.classifyHead(head, if (total > 0) total else head.size.toLong())
        return when {
            type == SniffedType.JPEG -> prepareJpeg(uri, meta, head, mode, original)
            type == SniffedType.GIF -> asIs(uri, meta, type, listOf(PrepareNote.ANIMATION_KEPT_UNCHANGED), pictureHint(type, head))
            type.category == SniffCategory.PICTURE && mode == SendMode.MEDIA && !original -> reencodePicture(uri, meta, type)
            type.category == SniffCategory.PICTURE -> asIs(uri, meta, type, listOf(PrepareNote.ORIGINAL_MAY_CARRY_HIDDEN_DATA), pictureHint(type, head))
            type.category == SniffCategory.VIDEO && mode == SendMode.MEDIA -> prepareVideo(uri, meta, type)
            else -> asIs(uri, meta, type, listOf(PrepareNote.SENT_AS_IS), null)
        }
    }

    /** A voice note recorded by the v1 recorder (AAC in MP4, at most 2 minutes): an inline attachment with its duration. */
    fun prepareVoice(bytes: ByteArray, durationMs: Long): PreparedAttachment {
        AttachmentPolicy.check(bytes.size.toLong(), options)?.let { throw AttachException(it) }
        return PreparedAttachment(
            "voice-note.m4a", "audio/mp4", Presentation.VOICE, bytes.size.toLong(), 0, 0, durationMs, ByteArray(0),
            listOf(PrepareNote.SENT_AS_IS), { ByteArrayInputStream(bytes) },
        )
    }

    // ---- pictures -----------------------------------------------------------------------------------------------

    private class PictureHint(val width: Int, val height: Int)

    private fun pictureHint(type: SniffedType, head: ByteArray): PictureHint? =
        ImageHeader.dimensions(type, head)?.let { PictureHint(it.first, it.second) }

    private fun prepareJpeg(uri: Uri, meta: Meta, head: ByteArray, mode: SendMode, original: Boolean): PreparedAttachment {
        if (mode == SendMode.MEDIA && !original) return reencodePicture(uri, meta, SniffedType.JPEG)
        // Original (or a JPEG chosen as a file): the segment filter. Unreadable structure falls back to a re-encode.
        val temp = newTemp()
        return try {
            temp.outputStream().buffered().use { out -> JpegMetadataFilter.filter({ open(uri) }, out) }
            val size = temp.length()
            checkSize(size)
            val orientation = open(uri).use { ExifOrientation.readFromJpeg(it) }
            val dims = JpegHeader.dimensions(readHead(uri, DIMENSION_HEAD_BYTES))
            val upright = dims?.let { if (ExifOrientation.swapsAxes(orientation)) it.second to it.first else it }
            PreparedAttachment(
                nameOf(meta, "jpg"), "image/jpeg", Presentation.PICTURE, size, upright?.first ?: 0, upright?.second ?: 0, 0,
                thumbnailOf(uri, SniffedType.JPEG), listOf(PrepareNote.JPEG_METADATA_REMOVED),
                { FileInputStream(temp) }, { wipe(temp) },
            )
        } catch (error: JpegFormatException) {
            wipe(temp)
            reencodePicture(uri, meta, SniffedType.JPEG).let {
                PreparedAttachment(
                    it.name, it.mime, it.presentation, it.size, it.width, it.height, it.durationMs, it.thumbnail,
                    it.notes + PrepareNote.JPEG_UNREADABLE_REENCODED, it.open, { it.close() },
                )
            }
        } catch (error: Throwable) {
            wipe(temp)
            throw error
        }
    }

    private fun reencodePicture(uri: Uri, meta: Meta, type: SniffedType): PreparedAttachment {
        val upright = decodeUpright(uri, type, options.photoEdge)
            ?: throw AttachException(AttachRefusal.NotReadable("the picture cannot be decoded"))
        val flat = flatten(upright)
        try {
            val out = ByteArrayOutputStream(256 * 1024)
            if (!flat.compress(Bitmap.CompressFormat.JPEG, options.photoQuality, out)) throw IOException("JPEG encoder failed")
            val bytes = out.toByteArray()
            checkSize(bytes.size.toLong())
            return PreparedAttachment(
                nameOf(meta, "jpg"), "image/jpeg", Presentation.PICTURE, bytes.size.toLong(), flat.width, flat.height, 0,
                thumbnailFrom(flat), listOf(PrepareNote.PHOTO_REENCODED), { ByteArrayInputStream(bytes) },
            )
        } finally {
            if (flat !== upright) flat.recycle()
            upright.recycle()
        }
    }

    /**
     * Decodes [uri] down-sampled, applies the EXIF orientation and scales so the longest edge is at most [edge]. The
     * sender's own picture: bounded by [PhotoGeometry.SENDER_MAX_PIXELS] and by the sample size.
     */
    private fun decodeUpright(uri: Uri, type: SniffedType, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > PhotoGeometry.SENDER_MAX_PIXELS) return null
        val orientation = orientationOf(uri, type)
        val options = BitmapFactory.Options().apply {
            inSampleSize = PhotoGeometry.sampleSize(w, h, edge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val target = PhotoGeometry.target(w, h, orientation, edge)
        val decodedLongest = maxOf(decoded.width, decoded.height).toFloat()
        val scale = (maxOf(target.width, target.height) / decodedLongest).coerceAtMost(1f)
        val matrix = Matrix()
        if (ExifOrientation.mirrored(orientation)) matrix.postScale(-1f, 1f)
        matrix.postRotate(ExifOrientation.rotationDegrees(orientation).toFloat())
        if (scale < 1f) matrix.postScale(scale, scale)
        if (matrix.isIdentity) return decoded
        val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (turned !== decoded) decoded.recycle()
        return turned
    }

    /** Transparency onto white (a JPEG has no alpha; the encoder would otherwise use black). */
    private fun flatten(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }

    private fun orientationOf(uri: Uri, type: SniffedType): Int = when (type) {
        SniffedType.JPEG -> open(uri).use { ExifOrientation.readFromJpeg(it) }
        SniffedType.HEIF, SniffedType.WEBP, SniffedType.PNG -> runCatching {
            open(uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.takeIf(ExifOrientation::valid) ?: 1
        }.getOrDefault(1)
        else -> 1
    }

    /** 320 px JPEG of at most 16 KiB for the manifest. Carried only; the receiver does not decode it without a tap. */
    private fun thumbnailOf(uri: Uri, type: SniffedType): ByteArray = runCatching {
        decodeUpright(uri, type, MessagingV2Format.THUMBNAIL_MAX_EDGE)?.let { bitmap ->
            try {
                thumbnailFrom(flatten(bitmap))
            } finally {
                bitmap.recycle()
            }
        }
    }.getOrNull() ?: ByteArray(0)

    private fun thumbnailFrom(bitmap: Bitmap): ByteArray {
        for (edge in intArrayOf(MessagingV2Format.THUMBNAIL_MAX_EDGE, 240, 160)) {
            val scale = minOf(1f, edge.toFloat() / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true) else bitmap
            try {
                for (quality in intArrayOf(60, 45, 30)) {
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    val bytes = out.toByteArray()
                    if (JpegHeader.acceptableThumbnail(bytes)) return bytes
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        return ByteArray(0)
    }

    // ---- video --------------------------------------------------------------------------------------------------

    private fun prepareVideo(uri: Uri, meta: Meta, type: SniffedType): PreparedAttachment {
        val declared = meta.size ?: -1L
        if (declared > MessagingV2Format.MAX_FILE_BYTES + MessagingV2Format.MAX_FILE_BYTES / 50) {
            throw AttachException(AttachRefusal.TooLarge(declared, minOf(options.maxFileBytes, MessagingV2Format.MAX_FILE_BYTES)))
        }
        val temp = newTemp()
        try {
            val info = VideoRemux.remux(context, uri, temp, type == SniffedType.WEBM)
            val scrubbed = type == SniffedType.WEBM || run {
                Mp4Scrubber.scrub(temp)
                Mp4Scrubber.scan(temp).clean
            }
            if (info != null && scrubbed) {
                val size = temp.length()
                checkSize(size)
                val thumb = videoThumbnail(uri)
                return PreparedAttachment(
                    nameOf(meta, if (type == SniffedType.WEBM) "webm" else "mp4"),
                    if (type == SniffedType.WEBM) "video/webm" else "video/mp4", Presentation.VIDEO, size,
                    info.width, info.height, info.durationMs, thumb, listOf(PrepareNote.VIDEO_REMUXED_NO_LOCATION),
                    { FileInputStream(temp) }, { wipe(temp) },
                )
            }
        } catch (error: AttachException) {
            wipe(temp)
            throw error
        } catch (error: Throwable) {
            // The platform could not remux this video (codec the MP4 muxer does not take, broken container).
        }
        wipe(temp)
        return asIs(uri, meta, type, listOf(PrepareNote.VIDEO_SENT_AS_FILE_MAY_CARRY_LOCATION), null, Presentation.VIDEO)
    }

    private fun videoThumbnail(uri: Uri): ByteArray = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching ByteArray(0)
            try {
                thumbnailFrom(frame)
            } finally {
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }.getOrDefault(ByteArray(0))

    // ---- files --------------------------------------------------------------------------------------------------

    private fun asIs(
        uri: Uri,
        meta: Meta,
        type: SniffedType,
        notes: List<PrepareNote>,
        hint: PictureHint?,
        presentation: Presentation = if (type.category == SniffCategory.PICTURE) Presentation.PICTURE else Presentation.FILE,
    ): PreparedAttachment {
        var size = meta.size ?: -1L
        var temp: File? = null
        if (size <= 0) {
            // The provider does not know its length: copy once, bounded, to learn it.
            temp = newTemp()
            size = open(uri).use { input -> copyBounded(input, temp, MessagingV2Format.MAX_FILE_BYTES + 1) }
        }
        try {
            checkSize(size)
        } catch (error: AttachException) {
            temp?.let(::wipe)
            throw error
        }
        val thumb = if (type.category == SniffCategory.PICTURE) thumbnailOf(uri, type) else ByteArray(0)
        // The sender's claim is only a label (the receiver sniffs); keep it for types we cannot name ourselves.
        val mime = if (type == SniffedType.UNKNOWN || type == SniffedType.TEXT || type == SniffedType.MARKUP) meta.claimedMime ?: type.mime else type.mime
        val opener: () -> InputStream = temp?.let { file -> { FileInputStream(file) } } ?: { open(uri) }
        return PreparedAttachment(
            nameOf(meta, type.extension), mime, presentation, size, hint?.width ?: 0, hint?.height ?: 0, 0, thumb, notes, opener,
            { temp?.let(::wipe) },
        )
    }

    private fun checkSize(size: Long) {
        AttachmentPolicy.check(size, options)?.let { throw AttachException(it) }
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    private class Meta(val displayName: String?, val size: Long?, val claimedMime: String?)

    private fun queryMeta(uri: Uri): Meta {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        if (size == null || size!! <= 0) {
            size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { n -> n > 0 } } }.getOrNull()
        }
        return Meta(name, size, runCatching { resolver.getType(uri) }.getOrNull())
    }

    private fun nameOf(meta: Meta, extension: String?): String {
        val label = meta.displayName?.let(V2Text::sanitizeName) ?: "file"
        if (extension == null) return label
        val own = label.substringAfterLast('.', "").lowercase()
        return if (own == extension || (extension == "jpg" && own == "jpeg")) label else {
            // A re-encoded or rewritten picture is a JPEG whatever the original was called.
            val base = if (label.contains('.')) label.substringBeforeLast('.') else label
            V2Text.sanitizeName("$base.$extension")
        }
    }

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw AttachException(AttachRefusal.NotReadable("no data"))

    private fun readHead(uri: Uri, max: Int): ByteArray = try {
        open(uri).use { input ->
            val buffer = ByteArray(max)
            var read = 0
            while (read < max) {
                val n = input.read(buffer, read, max - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    } catch (error: SecurityException) {
        throw AttachException(AttachRefusal.NotReadable("permission denied"))
    } catch (error: IOException) {
        throw AttachException(AttachRefusal.NotReadable(error.message ?: "I/O error"))
    }

    private fun copyBounded(input: InputStream, to: File, limit: Long): Long {
        var total = 0L
        FileOutputStream(to).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) break
                out.write(buffer, 0, n)
            }
        }
        return total
    }

    private fun newTemp(): File {
        tempDir.mkdirs()
        return File(tempDir, ByteArray(12).also(random::nextBytes).sfHex() + ".tmp")
    }

    /** Overwrite with zeros, then delete: the plaintext of an outgoing file is not left in the cache. */
    private fun wipe(file: File) = wipeFile(file)

    /** Files left by a crashed or interrupted send (older than one hour). */
    private fun sweepTemp() {
        val cutoff = System.currentTimeMillis() - 3_600_000L
        tempDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(::wipeFile)
    }

    companion object {
        const val TEMP_DIR = "mv2-out"
        private const val HEAD_BYTES = 8192
        private const val DIMENSION_HEAD_BYTES = 512 * 1024

        /** Deletes every leftover outgoing file now (emergency wipe, thread teardown). */
        fun wipeAll(context: Context) {
            File(context.applicationContext.cacheDir, TEMP_DIR).listFiles()?.forEach(::wipeFile)
        }

        fun wipeFile(file: File) {
            runCatching {
                if (file.isFile && file.length() > 0) {
                    java.io.RandomAccessFile(file, "rw").use { raf ->
                        val zeros = ByteArray(64 * 1024)
                        var left = raf.length()
                        raf.seek(0)
                        while (left > 0) {
                            val n = minOf(left, zeros.size.toLong()).toInt()
                            raf.write(zeros, 0, n)
                            left -= n
                        }
                        raf.fd.sync()
                    }
                }
            }
            file.delete()
        }
    }
}

/** Lossless remux of the audio and video tracks into a new container with no location call. */
object VideoRemux {
    class Info(val width: Int, val height: Int, val durationMs: Long)

    private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024

    /** Returns the remuxed video's facts, or null when the platform cannot remux it. Throws only for I/O on [out]. */
    fun remux(context: Context, uri: Uri, out: File, webm: Boolean): Info? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(context, uri, null)
            retriever.setDataSource(context, uri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val format = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = MediaMuxer(out.path, format)
            val mapping = HashMap<Int, Int>()
            var width = 0
            var height = 0
            for (track in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(track)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                // Only audio and video: timed metadata, GPS and other tracks are not copied.
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                mapping[track] = muxer.addTrack(trackFormat)
                extractor.selectTrack(track)
                if (mime.startsWith("video/")) {
                    width = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
                    height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)
                }
            }
            if (mapping.isEmpty()) return null
            if (!webm && rotation in intArrayOf(0, 90, 180, 270)) muxer.setOrientationHint(rotation)
            muxer.start()
            var buffer = ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.sampleSize.toInt()
                if (size < 0) break
                if (size > MAX_SAMPLE_BYTES) return null
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size)
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                info.set(0, read, extractor.sampleTime, extractor.sampleFlags)
                val target = mapping[extractor.sampleTrackIndex]
                if (target != null) muxer.writeSampleData(target, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            muxer = null
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val swap = rotation == 90 || rotation == 270
            return Info(if (swap) height else width, if (swap) width else height, duration)
        } catch (error: RuntimeException) {
            return null
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            retriever.release()
        }
    }
}
