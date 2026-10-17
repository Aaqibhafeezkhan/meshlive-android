package com.example.bluetoothtest

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException

/*
 * Messaging v2 phase 1b: "Open" a received file in another app (DECISIONS Design M3 "Formats"). The provider is not
 * exported; an app gets a one-file read grant (FLAG_GRANT_READ_URI_PERMISSION) for a random-token URI, and every byte it
 * reads is decrypted on demand from the file vault through `StorageManager.openProxyFileDescriptor`, so no plaintext file
 * exists in our storage. The grant is also checked by the provider itself on every query, open and read, so revoking it
 * (leaving the thread, five minutes, the emergency wipe) stops serving even to an app that still holds a platform URI
 * permission or an open descriptor. Experimental (master switch `test_messaging_v2`).
 */

/** The vault file as a [SniffSource]: sniffing and the header checks read a few KiB, never the whole file. */
class VaultSniffSource(private val vault: FileVault, private val localId: String) : SniffSource {
    override val size: Long = requireNotNull(vault.info(localId)) { "Unknown vault file" }.totalSize

    override fun read(offset: Long, length: Int): ByteArray = vault.read(localId, offset, length)
}

object MessagingV2Opens {
    /** One table for the process: the provider, the thread screen and the decoder share it. */
    val table = OpenGrantTable()

    private val handlerThread: HandlerThread by lazy { HandlerThread("mv2-open").apply { start() } }
    private val handler: Handler by lazy { Handler(handlerThread.looper) }

    fun authority(context: Context): String = context.packageName + ".v2open"

    private fun vaultOf(context: Context): FileVault? = runCatching { StoreForwardRuntime.node(context).v2?.vault }.getOrNull()

    /**
     * Issues a grant for a complete vault file and returns the URI to hand out, or null when the file does not exist, is not
     * complete, or its type may not be opened (only sniffed pictures, audio, video, PDF and plain text; never a polyglot).
     */
    fun grantFor(context: Context, localId: String, displayName: String, presentation: Presentation, scope: String): GrantedFile? {
        val vault = vaultOf(context) ?: return null
        val info = vault.info(localId)?.takeIf { it.complete } ?: return null
        val sniff = FileSniffer.sniff(VaultSniffSource(vault, localId))
        if (!sniff.openAllowed) return null
        val mime = sniff.mimeForOpen(presentation)
        val safeName = SaveName.suggest(displayName, sniff)
        val grant = table.issue(localId, safeName, mime, info.totalSize, scope)
        val uri = Uri.Builder().scheme("content").authority(authority(context)).appendPath("f").appendPath(grant.token).appendPath(safeName).build()
        Handler(Looper.getMainLooper()).postDelayed({ revokeExpired(context) }, table.ttlMs + 1_000)
        return GrantedFile(uri, mime, grant)
    }

    class GrantedFile(val uri: Uri, val mime: String, val grant: OpenGrant)

    /** The intent that shows the file in a viewer: `ACTION_VIEW`, the sniffed type, and a read grant for the receiving app only. */
    fun viewIntent(file: GrantedFile): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(file.uri, file.mime)
        clipData = ClipData.newRawUri("", file.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Leaving a thread: every grant of that conversation ends now. */
    fun revokeScope(context: Context, scope: String) = revoked(context, table.revokeScope(scope))

    fun revokeAll(context: Context) = revoked(context, table.revokeAll())

    fun revokeExpired(context: Context) = revoked(context, table.prune())

    private fun revoked(context: Context, grants: List<OpenGrant>) {
        grants.forEach { grant ->
            val uri = Uri.Builder().scheme("content").authority(authority(context)).appendPath("f").appendPath(grant.token)
                .appendPath(grant.displayName).build()
            runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** A read-only descriptor for [token]; null when the grant is gone or four descriptors are open. Reads decrypt on demand. */
    internal fun openDescriptor(context: Context, token: String): ParcelFileDescriptor? {
        val vault = vaultOf(context) ?: return null
        val lease = table.acquire(token) ?: return null
        return try {
            val reader = VaultRangeReader(vault, lease.grant.localId, lease)
            val storage = context.getSystemService(StorageManager::class.java)
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, VaultProxyCallback(reader), handler)
        } catch (error: Exception) {
            lease.close()
            null
        }
    }

    /** The same descriptor for the app's own isolated decoder: counted and revocable like an external one. */
    internal fun openInternalDescriptor(context: Context, localId: String, scope: String): ParcelFileDescriptor? {
        val vault = vaultOf(context) ?: return null
        val info = vault.info(localId)?.takeIf { it.complete } ?: return null
        val grant = table.issue(localId, "decode", "application/octet-stream", info.totalSize, scope)
        return openDescriptor(context, grant.token)
    }
}

/** Serves reads from the vault; an expired or revoked grant answers EIO. The reader wipes its block and frees the slot on release. */
internal class VaultProxyCallback(private val reader: VaultRangeReader) : ProxyFileDescriptorCallback() {
    override fun onGetSize(): Long = reader.size

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
        reader.read(offset, minOf(size, data.size), data)
    } catch (error: java.io.IOException) {
        throw ErrnoException("onRead", OsConstants.EIO)
    }

    override fun onRelease() = reader.close()
}

/** The non-exported provider: `content://<id>.v2open/f/<token>/<name>`. */
class MessagingV2OpenProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun tokenOf(uri: Uri): String? = uri.pathSegments.takeIf { it.size >= 2 && it[0] == "f" }?.get(1)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val grant = tokenOf(uri)?.let(MessagingV2Opens.table::lookup) ?: return null
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).filter {
            it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE
        }.toTypedArray()
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) grant.displayName else grant.size }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String? = tokenOf(uri)?.let(MessagingV2Opens.table::lookup)?.mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val token = tokenOf(uri) ?: throw FileNotFoundException("Unknown file")
        val context = context ?: throw FileNotFoundException("No context")
        return MessagingV2Opens.openDescriptor(context, token) ?: throw FileNotFoundException("No longer available")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
