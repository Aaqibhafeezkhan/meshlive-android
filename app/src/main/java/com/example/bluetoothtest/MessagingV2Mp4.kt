package com.example.bluetoothtest

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/*
 * Messaging v2 phase 1b: video metadata (DECISIONS Design M3 "Photos on send", "Video"). The sent video is a lossless
 * remux: `MediaExtractor` and `MediaMuxer` copy the audio and video tracks into a new MP4 and never call
 * `setLocation`, so no location or device atom is copied. This file is the belt and braces: after the remux, every
 * user-data atom is found and neutralised in place (renamed to `free` with its payload zeroed, so no byte of location or
 * device data stays in the file), and the result is checked again. If anything private remains that cannot be neutralised
 * the caller does not send the remuxed file as scrubbed.
 */

/** Random access to the bytes of an MP4 file for the atom walker (a file in the app, a byte array in tests). */
interface Mp4Access {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    fun write(offset: Long, bytes: ByteArray)
}

class ByteArrayMp4Access(val bytes: ByteArray) : Mp4Access {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray =
        bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())

    override fun write(offset: Long, bytes: ByteArray) {
        System.arraycopy(bytes, 0, this.bytes, offset.toInt(), bytes.size)
    }
}

class FileMp4Access(private val file: RandomAccessFile) : Mp4Access {
    override val size: Long get() = file.length()

    override fun read(offset: Long, length: Int): ByteArray {
        val count = minOf(length.toLong(), file.length() - offset).toInt().coerceAtLeast(0)
        val out = ByteArray(count)
        file.seek(offset)
        file.readFully(out)
        return out
    }

    override fun write(offset: Long, bytes: ByteArray) {
        file.seek(offset)
        file.write(bytes)
    }
}

object Mp4Scrubber {
    class Report(
        /** Atom types that were found, in file order (a type can appear more than once). */
        val found: List<String>,
        val bytesZeroed: Long,
        /** False when the box structure could not be followed to the end: nothing can be promised about the file. */
        val parsed: Boolean,
    ) {
        val clean: Boolean get() = found.isEmpty() && parsed
    }

    /** Atoms that carry location, device, software or free-form user data. A renamed atom is skipped by every parser. */
    private val PRIVATE_TYPES = setOf(
        "udta", "meta", "uuid", "©xyz", "loci", "gps ", "GPS ", "smta", "©mak", "©mod", "©swr", "©day", "©too",
        "©nam", "©cmt", "©ART", "©alb", "©gen", "©wrt", "Xtra", "ilst", "keys", "xml ", "XMP_",
    )
    private val CONTAINERS = setOf("moov", "trak")
    private const val MAX_BOXES = 200_000

    /** Finds the private atoms without changing anything. */
    fun scan(access: Mp4Access): Report = walk(access, write = false)

    /** Finds and neutralises the private atoms in place. A second [scan] of a scrubbed file is clean. */
    fun scrub(access: Mp4Access): Report = walk(access, write = true)

    fun scrub(file: File): Report = RandomAccessFile(file, "rw").use { raf ->
        scrub(FileMp4Access(raf)).also { raf.fd.sync() }
    }

    fun scan(file: File): Report = RandomAccessFile(file, "r").use { scan(FileMp4Access(it)) }

    private fun walk(access: Mp4Access, write: Boolean): Report {
        val found = ArrayList<String>()
        var zeroed = 0L
        var boxes = 0
        var parsed = true

        fun level(start: Long, end: Long, depth: Int) {
            var position = start
            while (position + 8 <= end) {
                if (++boxes > MAX_BOXES) throw IOException("Too many boxes")
                val header = access.read(position, 16)
                if (header.size < 8) throw IOException("Truncated box")
                var size = readU32(header, 0)
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                var headerSize = 8L
                if (size == 1L) {
                    if (header.size < 16) throw IOException("Truncated large box")
                    size = readU64(header, 8)
                    headerSize = 16
                } else if (size == 0L) {
                    size = end - position
                }
                if (size < headerSize || position + size > end) throw IOException("Box $type runs past its container")
                if (type in PRIVATE_TYPES) {
                    found += type
                    if (write) {
                        access.write(position + 4, "free".toByteArray(Charsets.ISO_8859_1))
                        var offset = position + headerSize
                        val payloadEnd = position + size
                        while (offset < payloadEnd) {
                            val take = minOf(65_536L, payloadEnd - offset).toInt()
                            access.write(offset, ByteArray(take))
                            offset += take
                        }
                        zeroed += payloadEnd - position - headerSize
                    }
                } else if (type in CONTAINERS) {
                    // Deeper than any real file: not followed, so nothing can be promised about what is inside.
                    if (depth >= 4) throw IOException("Containers nested too deeply")
                    level(position + headerSize, position + size, depth + 1)
                }
                position += size
            }
        }

        try {
            level(0, access.size, 0)
        } catch (error: IOException) {
            parsed = false
        } catch (error: RuntimeException) {
            parsed = false
        }
        return Report(found, zeroed, parsed)
    }

    private fun readU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    private fun readU64(b: ByteArray, o: Int): Long = (readU32(b, o) shl 32) or readU32(b, o + 4)
}
