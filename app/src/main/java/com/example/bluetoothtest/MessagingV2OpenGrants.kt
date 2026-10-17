package com.example.bluetoothtest

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom

/*
 * Messaging v2 phase 1b: opening and saving a received file (DECISIONS Design M3 "Formats", findings M-R13). Pure logic;
 * the Android parts (the non-exported provider, the proxy file descriptor, the Storage Access Framework) are in
 * MessagingV2OpenProvider.kt and call into this file.
 *
 * Open hands the bytes to another app through a read grant. A grant is a random token that names one vault file; it
 * lives until it is revoked (the thread is left) or five minutes pass, allows at most four open descriptors for the whole
 * app, and stops serving bytes at once when it is revoked, even to a descriptor that was already handed out.
 */

/** One grant for one file. */
class OpenGrant(
    val token: String,
    val localId: String,
    val displayName: String,
    val mime: String,
    val size: Long,
    val issuedMs: Long,
    val expiresMs: Long,
    /** The conversation it belongs to: leaving the thread revokes every grant with this scope. */
    val scope: String,
) {
    @Volatile var revoked: Boolean = false
}

/** A claim on one of the four descriptor slots. Reads check the grant every time, so revocation reaches open descriptors. */
class DescriptorLease internal constructor(val grant: OpenGrant, private val table: OpenGrantTable) : Closeable {
    @Volatile private var closed = false

    /** Throws when the grant was revoked or has expired, or the lease was released. */
    fun checkLive() {
        if (closed) throw IOException("Descriptor released")
        if (!table.isValid(grant)) throw IOException("Grant revoked or expired")
    }

    override fun close() {
        if (closed) return
        closed = true
        table.release()
    }
}

class OpenGrantTable(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    val ttlMs: Long = 5 * 60_000L,
    val maxDescriptors: Int = 4,
    val maxGrants: Int = 16,
) {
    private val grants = LinkedHashMap<String, OpenGrant>()
    private var descriptors = 0

    @Synchronized
    fun issue(localId: String, displayName: String, mime: String, size: Long, scope: String): OpenGrant {
        prune()
        while (grants.size >= maxGrants) {
            val oldest = grants.keys.first()
            grants.remove(oldest)?.revoked = true
        }
        val token = ByteArray(18).also(random::nextBytes).let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val now = clock()
        return OpenGrant(token, localId, displayName, mime, size, now, now + ttlMs, scope).also { grants[token] = it }
    }

    /** The grant for [token] if it exists, is not revoked and has not expired. */
    @Synchronized
    fun lookup(token: String): OpenGrant? {
        val grant = grants[token] ?: return null
        if (!isValid(grant)) {
            grants.remove(token)
            return null
        }
        return grant
    }

    @Synchronized
    fun isValid(grant: OpenGrant): Boolean = !grant.revoked && clock() < grant.expiresMs && grants[grant.token] === grant

    /** A descriptor slot for [token], or null when the grant is gone or four descriptors are already open. */
    @Synchronized
    fun acquire(token: String): DescriptorLease? {
        val grant = lookup(token) ?: return null
        if (descriptors >= maxDescriptors) return null
        descriptors++
        return DescriptorLease(grant, this)
    }

    @Synchronized
    internal fun release() {
        if (descriptors > 0) descriptors--
    }

    val openDescriptors: Int get() = synchronized(this) { descriptors }

    val grantCount: Int get() = synchronized(this) { grants.size }

    @Synchronized
    fun revoke(token: String): Boolean = grants.remove(token)?.also { it.revoked = true } != null

    /** Leaving a thread: every grant of the conversation. Returns the revoked grants (so URI permissions can be revoked too). */
    @Synchronized
    fun revokeScope(scope: String): List<OpenGrant> =
        grants.values.filter { it.scope == scope }.also { list -> list.forEach { revoke(it.token) } }

    @Synchronized
    fun revokeAll(): List<OpenGrant> = grants.values.toList().also { list -> list.forEach { revoke(it.token) } }

    /** Grants that have expired, removed and returned (their URI permissions are revoked by the caller). */
    @Synchronized
    fun prune(): List<OpenGrant> {
        val now = clock()
        val expired = grants.values.filter { now >= it.expiresMs || it.revoked }
        expired.forEach {
            it.revoked = true
            grants.remove(it.token)
        }
        return expired
    }
}

/**
 * Random-access reads of a vault file for another app. Decrypts block by block on demand, keeps one plaintext block in
 * memory and wipes it on [close], so no plaintext file is created and almost no plaintext lingers.
 */
class VaultRangeReader(private val vault: FileVault, private val localId: String, private val lease: DescriptorLease) : Closeable {
    private var cachedIndex = -1
    private var cached: ByteArray? = null
    val size: Long = requireNotNull(vault.info(localId)) { "Unknown vault file" }.totalSize

    /** Fills [into] from [offset]; returns the byte count (0 at the end). Throws [IOException] when the grant is no longer live. */
    @Throws(IOException::class)
    fun read(offset: Long, length: Int, into: ByteArray): Int {
        lease.checkLive()
        if (offset < 0 || length < 0 || length > into.size) throw IOException("Invalid range")
        if (offset >= size) return 0
        val end = minOf(size, offset + length)
        var position = offset
        var written = 0
        while (position < end) {
            lease.checkLive()
            val index = (position / FileVault.BLOCK).toInt()
            val block = block(index)
            val from = (position - index.toLong() * FileVault.BLOCK).toInt()
            val take = minOf(block.size - from, (end - position).toInt())
            System.arraycopy(block, from, into, written, take)
            written += take
            position += take
        }
        return written
    }

    private fun block(index: Int): ByteArray {
        cached?.let { if (cachedIndex == index) return it }
        cached?.fill(0)
        val fresh = try {
            vault.readBlock(localId, index)
        } catch (error: RuntimeException) {
            throw IOException("Vault block unavailable", error)
        }
        cached = fresh
        cachedIndex = index
        return fresh
    }

    override fun close() {
        cached?.fill(0)
        cached = null
        cachedIndex = -1
        lease.close()
    }
}

/** Save to the user's chosen location (the Storage Access Framework hands over [output]): streamed, never a plaintext cache file. */
object VaultExporter {
    fun copy(vault: FileVault, localId: String, output: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long {
        val info = requireNotNull(vault.info(localId)) { "Unknown vault file" }
        require(info.complete) { "The file is incomplete" }
        var done = 0L
        for (index in 0 until info.blockCount) {
            val block = vault.readBlock(localId, index)
            try {
                output.write(block)
            } finally {
                block.fill(0)
            }
            done += block.size
            onProgress(done, info.totalSize)
        }
        output.flush()
        return done
    }
}

/** What the file card offers for a received file, decided from the sniffed bytes (never from the sender's claim). */
class FileActions(
    val sniff: SniffResult,
    val canOpen: Boolean,
    val openMime: String?,
    val saveWarning: WarnLevel,
    /** A picture the user may ask to see; decoding starts only after a tap (the generic icon is shown until then). */
    val canPreview: Boolean,
    val iconCategory: SniffCategory,
)

object FileActionPolicy {
    /** [complete] is true only for a file whose every byte arrived and whose SHA-256 matched (or an own stored file). */
    fun forFile(sniff: SniffResult, presentation: Presentation, complete: Boolean): FileActions {
        val open = complete && sniff.openAllowed
        return FileActions(
            sniff,
            canOpen = open,
            openMime = if (open) sniff.mimeForOpen(presentation) else null,
            saveWarning = if (open) WarnLevel.NONE else sniff.warn,
            canPreview = complete && sniff.decodable,
            iconCategory = sniff.category,
        )
    }
}

object SaveName {
    /**
     * A file name for the Save dialog: the sanitized label, with the sniffed extension added when the label's own
     * extension does not match what the bytes are, so a program named `photo.jpg` is not saved as a picture and a picture
     * named `setup.apk` is not saved as a program. Unknown types keep the label.
     */
    fun suggest(label: String, sniff: SniffResult): String {
        val clean = V2Text.sanitizeName(label)
        val ext = clean.substringAfterLast('.', "").lowercase()
        val expected = sniff.type.extension
        // A type with no extension of its own (unknown, markup, Office, Mach-O): a label that claims a known type is
        // neutralised with ".bin".
        val suffix = when {
            expected == null -> if (ext in CLAIMED_EXTENSIONS) ".bin" else return clean
            ext in equivalents(expected) -> return clean
            else -> ".$expected"
        }
        var base = clean
        while ((base + suffix).toByteArray(Charsets.UTF_8).size > MessagingV2Format.NAME_MAX_BYTES && base.isNotEmpty()) {
            base = base.substring(0, base.offsetByCodePoints(base.length, -1))
        }
        return base + suffix
    }

    private val CLAIMED_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "mp3", "m4a", "aac", "wav", "ogg", "flac", "mp4", "mov", "mkv", "webm",
        "avi", "3gp", "pdf", "txt", "apk", "exe", "dll", "sh", "jar", "zip", "doc", "docx", "xls", "xlsx", "html", "htm", "svg",
    )

    private fun equivalents(extension: String): Set<String> = when (extension) {
        "jpg" -> setOf("jpg", "jpeg", "jpe")
        "mp4" -> setOf("mp4", "m4v")
        "mid" -> setOf("mid", "midi")
        "tar" -> setOf("tar")
        "so" -> setOf("so", "elf", "bin", "o")
        "sh" -> setOf("sh", "bash", "py", "pl", "rb")
        "txt" -> setOf("txt", "text", "log", "md", "csv", "json", "ini", "cfg", "conf", "yml", "yaml", "srt", "tsv", "c", "h", "kt", "java")
        "m4a" -> setOf("m4a", "m4b", "mp4", "aac")
        "3gp" -> setOf("3gp", "3g2", "3gpp")
        "mov" -> setOf("mov", "qt")
        "heic" -> setOf("heic", "heif")
        "zip" -> setOf("zip", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "aar", "cbz")
        "jar" -> setOf("jar", "war")
        "exe" -> setOf("exe", "dll", "scr", "sys")
        else -> setOf(extension)
    }
}
