package com.example.bluetoothtest

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * Messaging v2 phase 1b: tap-to-decode of a received picture (DECISIONS Design M3 "Pictures and thumbnails", finding 10,
 * Q-M12). Nothing is decoded automatically. After a tap the picture is decoded from its sniffed header size (40 megapixel
 * limit), down-sampled to the display, never at full size, in `MessagingV2ImageService`, a service declared
 * `android:isolatedProcess="true"` (its own uid, no permissions, no access to our files, destroyed after the decode and on
 * a 10 second timeout). The bytes reach it as a read-only proxy descriptor that decrypts from the vault on demand; no
 * plaintext file is made. When the isolated service cannot be bound the same bounds apply in the app process (the
 * `test_mv2_decode_isolated` control arm; the user chose to open it). The isolated arm is a spike: its behaviour on each
 * reference phone is part of T-M9.
 */

/** Why a picture was not decoded. */
class DecodeRefused(val reason: String) : Exception(reason)

object BoundedImageDecoder {
    /** Display edge the result is scaled to (one screen width is plenty and bounds the reply to about 6 MB). */
    const val DEFAULT_EDGE = 1280

    /**
     * Decodes the picture behind [fd] (seekable) down-sampled so the longest edge is [maxEdge]. Refuses a picture whose
     * header says more than [ImageHeader.MAX_PIXELS] or that cannot be decoded; the allocation is a function of [maxEdge],
     * never of the header's size.
     */
    fun decode(fd: FileDescriptor, maxEdge: Int, maxPixels: Long = ImageHeader.MAX_PIXELS): Bitmap {
        require(maxEdge in 64..4096)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw DecodeRefused("Not a picture this phone can decode")
        if (w.toLong() * h > maxPixels) throw DecodeRefused("The picture is larger than 40 megapixels")
        val options = BitmapFactory.Options().apply {
            inSampleSize = PhotoGeometry.displaySampleSize(w, h, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFileDescriptor(fd, null, options) ?: throw DecodeRefused("The picture is damaged")
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= maxEdge) return decoded
        val scale = maxEdge.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}

/** The isolated process. Declared in the manifest with `android:isolatedProcess="true"`, not exported, no permissions. */
class MessagingV2ImageService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("mv2-decode").also { it.start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                if (message.what != MSG_DECODE) return
                val reply = Message.obtain(null, MSG_RESULT)
                val data = Bundle()
                try {
                    val request = message.data
                    request.classLoader = ParcelFileDescriptor::class.java.classLoader
                    @Suppress("DEPRECATION")
                    val pfd = request.getParcelable<ParcelFileDescriptor>(KEY_FD) ?: throw DecodeRefused("No descriptor")
                    pfd.use {
                        data.putParcelable(KEY_BITMAP, BoundedImageDecoder.decode(it.fileDescriptor, request.getInt(KEY_EDGE, BoundedImageDecoder.DEFAULT_EDGE)))
                    }
                } catch (error: Throwable) {
                    data.putString(KEY_ERROR, if (error is DecodeRefused) error.reason else "Decoding failed")
                }
                reply.data = data
                runCatching { message.replyTo?.send(reply) }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // Nothing of the decode may outlive the request: end the whole process, a hung decoder thread included.
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val MSG_DECODE = 1
        const val MSG_RESULT = 2
        const val KEY_FD = "fd"
        const val KEY_EDGE = "edge"
        const val KEY_BITMAP = "bitmap"
        const val KEY_ERROR = "error"
    }
}

/** Result of a tap-to-decode. */
sealed class DecodeOutcome {
    class Picture(val bitmap: Bitmap, val isolated: Boolean) : DecodeOutcome()
    class Refused(val reason: String) : DecodeOutcome()
}

object TapToDecode {
    const val TIMEOUT_MS = 10_000L
    private const val HEADER_BYTES = 512 * 1024

    /** Header checks that need no decoder: a sniffed, decodable picture whose header size is within 40 megapixels. */
    fun precheck(vault: FileVault, localId: String): DecodeOutcome.Refused? {
        val info = vault.info(localId)
        if (info == null || !info.complete) return DecodeOutcome.Refused("The file is not complete")
        val source = VaultSniffSource(vault, localId)
        val sniff = FileSniffer.sniff(source)
        if (!sniff.decodable) return DecodeOutcome.Refused("This file is not a picture that can be shown")
        val head = source.read(0, HEADER_BYTES)
        if (ImageHeader.dimensions(sniff.type, head) == null) return DecodeOutcome.Refused("The picture header cannot be read")
        if (ImageHeader.checked(sniff.type, head) == null) return DecodeOutcome.Refused("The picture is larger than 40 megapixels")
        return null
    }

    /** Blocking; call from a worker thread, after the user tapped the picture. */
    fun decode(context: Context, localId: String, scope: String, options: MessagingV2AttachOptions = MessagingV2Options.current, maxEdge: Int = BoundedImageDecoder.DEFAULT_EDGE): DecodeOutcome {
        val vault = runCatching { StoreForwardRuntime.node(context).v2?.vault }.getOrNull() ?: return DecodeOutcome.Refused("Messages are not available")
        precheck(vault, localId)?.let { return it }
        val pfd = MessagingV2Opens.openInternalDescriptor(context, localId, scope) ?: return DecodeOutcome.Refused("Too many files are open; try again")
        return try {
            if (options.decodeMode == DecodeMode.ISOLATED) {
                decodeIsolated(context, pfd, maxEdge) ?: decodeHere(pfd, maxEdge)
            } else {
                decodeHere(pfd, maxEdge)
            }
        } finally {
            runCatching { pfd.close() }
        }
    }

    private fun decodeHere(pfd: ParcelFileDescriptor, maxEdge: Int): DecodeOutcome = try {
        DecodeOutcome.Picture(BoundedImageDecoder.decode(pfd.fileDescriptor, maxEdge), isolated = false)
    } catch (error: DecodeRefused) {
        DecodeOutcome.Refused(error.reason)
    } catch (error: Throwable) {
        DecodeOutcome.Refused("Decoding failed")
    }

    /** Null when the isolated service cannot be bound or answers nothing in time (the caller falls back to the control arm). */
    private fun decodeIsolated(context: Context, pfd: ParcelFileDescriptor, maxEdge: Int): DecodeOutcome? {
        val app = context.applicationContext
        val connected = CountDownLatch(1)
        val answered = CountDownLatch(1)
        var service: Messenger? = null
        var outcome: DecodeOutcome? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = Messenger(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                answered.countDown()
            }
        }
        val replyThread = HandlerThread("mv2-decode-reply").also { it.start() }
        try {
            val reply = Messenger(object : Handler(replyThread.looper) {
                override fun handleMessage(message: Message) {
                    val data = message.data
                    data.classLoader = Bitmap::class.java.classLoader
                    @Suppress("DEPRECATION")
                    val bitmap = data.getParcelable<Bitmap>(MessagingV2ImageService.KEY_BITMAP)
                    outcome = if (bitmap != null) DecodeOutcome.Picture(bitmap, isolated = true)
                    else DecodeOutcome.Refused(data.getString(MessagingV2ImageService.KEY_ERROR) ?: "Decoding failed")
                    answered.countDown()
                }
            })
            if (!app.bindService(Intent(app, MessagingV2ImageService::class.java), connection, Context.BIND_AUTO_CREATE)) return null
            if (!connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            val request = Message.obtain(null, MessagingV2ImageService.MSG_DECODE).apply {
                replyTo = reply
                data = Bundle().apply {
                    putParcelable(MessagingV2ImageService.KEY_FD, pfd)
                    putInt(MessagingV2ImageService.KEY_EDGE, maxEdge)
                }
            }
            service?.send(request) ?: return null
            if (!answered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) return DecodeOutcome.Refused("Decoding took too long")
            return outcome
        } catch (error: Exception) {
            return null
        } finally {
            runCatching { app.unbindService(connection) }
            replyThread.quitSafely()
        }
    }
}
