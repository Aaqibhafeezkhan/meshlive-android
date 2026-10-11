package com.example.bluetoothtest

import java.io.InputStream
import java.io.OutputStream

/**
 * Streams for the parent side of an on-demand relay (PLAN Design C). The parent link was already accepted and
 * authenticated by the call endpoint, so the relay copies records through its [SecureBluetoothSession] and never
 * reads or writes these streams; they only give the relay's `TransportConnection` something to hold.
 *
 * Why not `InputStream.nullInputStream()` / `OutputStream.nullOutputStream()`: both are API 33 and the app's
 * minSdk is 29. D8 does not desugar them, so on Android 10-12 the first on-demand relay died with a
 * `NoSuchMethodError` (an Error, which the relay worker's `catch (Exception)` did not catch) and took the whole
 * app process with it (rotid-958c8a53-p4-relayed-calls-gatt-probe: ASUS, Android 11). `ApiLevelGuardTest` keeps
 * such calls out of the main sources.
 */
internal object RelayParentStreams {
    fun emptyInput(): InputStream = object : InputStream() {
        override fun read(): Int = -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkRange(buffer, offset, length)
            return if (length == 0) 0 else -1
        }

        override fun available(): Int = 0
    }

    fun discardingOutput(): OutputStream = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkRange(buffer, offset, length)
        }
    }

    /** `Objects.checkFromIndexSize` is API 30; the stream contract's range check is written out. */
    private fun checkRange(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || length > buffer.size - offset) {
            throw IndexOutOfBoundsException("offset=$offset length=$length size=${buffer.size}")
        }
    }
}

/**
 * A relay duty serves a stranger's request on a worker thread. An unexpected `Error` there (a [LinkageError] from
 * a platform method that is missing on this Android version, an assertion) must end the duty and report a
 * failure upstream, not kill the host app: an uncaught Error terminates the process. Exceptions pass through
 * unchanged; JVM-level failures ([VirtualMachineError]: out of memory, stack overflow) are not contained.
 */
internal fun Throwable.asRelayDutyException(): Exception? = when (this) {
    is Exception -> this
    is VirtualMachineError -> null
    else -> RelayDutyError(this)
}

/** An [Error] that ended a relay duty, kept as the cause so the status line still names it. */
internal class RelayDutyError(cause: Throwable) : IllegalStateException("${cause.javaClass.simpleName}: ${cause.message}", cause)
