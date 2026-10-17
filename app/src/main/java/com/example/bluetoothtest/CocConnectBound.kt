package com.example.bluetoothtest

import android.bluetooth.BluetoothSocketException
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * One-shot deadline on a daemon thread: [onExpire] runs once after [timeoutMs] unless [cancel] wins
 * first. [cancel] returns true when it prevented the expiry (the guarded operation finished in time).
 */
internal class CocDeadline(name: String, timeoutMs: Long, private val onExpire: () -> Unit) {
    private val state = AtomicInteger(PENDING)
    private val done = CountDownLatch(1)

    init {
        thread(name = name, isDaemon = true) {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS) && state.compareAndSet(PENDING, EXPIRED)) {
                runCatching(onExpire)
            }
        }
    }

    /** True when the operation finished before the deadline; false when the deadline already fired. */
    fun cancel(): Boolean {
        val won = state.compareAndSet(PENDING, CANCELLED)
        done.countDown()
        return won || state.get() == CANCELLED
    }

    val expired: Boolean get() = state.get() == EXPIRED

    private companion object {
        const val PENDING = 0
        const val CANCELLED = 1
        const val EXPIRED = 2
    }
}

/** The app-side bound of an LE CoC connect expired and the socket was closed. */
internal class CocConnectTimeoutException(val boundMs: Long, cause: Throwable? = null) :
    IOException("LE CoC connect timed out after $boundMs ms (app-side bound)", cause)

/**
 * C-01: every LE CoC client connect is bounded on every Android version. `BluetoothSocket.connect()`
 * has no timeout parameter and the stack's own channel-connect timer is 60 s (l2c_int.h
 * L2CAP_CHNL_CONNECT_TIMEOUT_MS; there is no 30 s CoC timer, so the older "Android 15 Qualcomm 30 s
 * stall" premise is not an AOSP timer). `close()` from another thread aborts a blocking connect, so a
 * watchdog closes the socket at the bound and the attempt fails like any CoC connect failure; the
 * callers' retry and the automatic GATT fallback do the rest.
 */
internal object CocConnectBound {
    /** The bound for this phone: the registry entry decides whether it applies, [CocSwitches] its length. */
    fun boundMsFor(policy: ResolvedDevicePolicy, configuredMs: Long): Long {
        val effect = policy.effects<CocConnectBoundEffect>().firstOrNull() ?: return 0L
        return if (configuredMs == CocSwitches.DEFAULT_CONNECT_BOUND_MS) effect.defaultBoundMs else configuredMs
    }

    fun currentBoundMs(): Long = boundMsFor(CocPolicy.current(), CocSwitches.connectBoundMs)

    /**
     * Runs [connect] on the caller's thread with [boundMs] as its limit (0 or less: no limit). On
     * expiry [socket] is closed from the watchdog and [CocConnectTimeoutException] is thrown, even
     * when the aborted connect raised its own exception. Any other failure is rethrown unchanged
     * after one log line that carries the BluetoothSocketException code on Android 14+.
     */
    fun <S : Closeable> run(
        socket: S,
        boundMs: Long,
        owner: String,
        log: (String) -> Unit,
        nowMs: () -> Long = System::currentTimeMillis,
        connect: (S) -> Unit,
    ) {
        val startedMs = nowMs()
        val deadline = if (boundMs > 0L) CocDeadline("CocConnectBound", boundMs) { runCatching { socket.close() } } else null
        try {
            connect(socket)
        } catch (error: Throwable) {
            val timedOut = deadline?.cancel() == false
            val elapsedMs = nowMs() - startedMs
            log(CocSocketErrors.failureLine(owner, error, elapsedMs, boundMs, timedOut))
            if (timedOut) throw CocConnectTimeoutException(boundMs, error)
            throw error
        }
        if (deadline != null && !deadline.cancel()) {
            // The watchdog closed the socket while connect() was returning: the socket is unusable.
            val elapsedMs = nowMs() - startedMs
            val timeout = CocConnectTimeoutException(boundMs)
            log(CocSocketErrors.failureLine(owner, timeout, elapsedMs, boundMs, true))
            throw timeout
        }
        log("BENCHMARK coc_connect: owner=$owner | outcome=connected | elapsedMs=${nowMs() - startedMs} | boundMs=$boundMs")
    }
}

/** What a failed socket connect means for the next step. */
internal enum class CocFailureKind(val label: String, val retrySamePsm: Boolean) {
    /** The peer has no listener on that PSM any more: read the PSM again, never repeat this one. */
    STALE_PSM("stale_psm", false),

    /** The peer ran out of L2CAP resources: back off, do not hammer. */
    CAPACITY("peer_capacity", true),

    /** The stack gave up waiting: the connect-stall family. */
    TIMEOUT("timeout", true),

    /** An unexpected secure listener or a link-key mismatch; retrying the same way cannot succeed. */
    SECURITY_MISMATCH("security_mismatch", false),
    ACL_FAILURE("acl_failure", true),

    /** Bluetooth off, socket manager or local socket failure: not about the peer. */
    LOCAL_STACK("local_stack", true),
    OTHER("other", true),
}

/** C-02: BluetoothSocketException codes (API 34+) as measurable classes. */
internal object CocSocketErrors {
    /** The constants of BluetoothSocketException (API 34) as literals, so JVM tests and Android 10-13 never load the class. */
    fun classify(code: Int): CocFailureKind = when (code) {
        13 -> CocFailureKind.STALE_PSM
        12 -> CocFailureKind.CAPACITY
        14 -> CocFailureKind.TIMEOUT
        3, 4, 5, 6, 7 -> CocFailureKind.SECURITY_MISMATCH
        2 -> CocFailureKind.ACL_FAILURE
        15, 16, 17, 18, 19, 20, 21 -> CocFailureKind.LOCAL_STACK
        else -> CocFailureKind.OTHER
    }

    /** The error code of [error] or of the first cause that carries one; null before Android 14 or when none does. */
    fun errorCodeOf(
        error: Throwable,
        sdk: Int = Build.VERSION.SDK_INT,
        codeOf: (Throwable) -> Int? = ::platformErrorCode,
    ): Int? {
        if (sdk < 34) return null
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            codeOf(current)?.let { return it }
            current = current.cause
        }
        return null
    }

    private fun platformErrorCode(error: Throwable): Int? =
        if (Build.VERSION.SDK_INT >= 34 && error is BluetoothSocketException) error.errorCode else null

    /** `code=13 kind=stale_psm` style fragment for log lines; "none" when the platform gave no code. */
    fun describe(
        error: Throwable,
        sdk: Int = Build.VERSION.SDK_INT,
        codeOf: (Throwable) -> Int? = ::platformErrorCode,
    ): String {
        val code = errorCodeOf(error, sdk, codeOf) ?: return "errorCode=none"
        return "errorCode=$code | errorKind=${classify(code).label}"
    }

    /** One BENCHMARK line for a failed CoC connect. */
    fun failureLine(owner: String, error: Throwable, elapsedMs: Long, boundMs: Long, timedOut: Boolean): String {
        val detail = describe(error)
        val code = errorCodeOf(error)
        val action = when {
            timedOut -> "close_and_fallback"
            code == null -> "retry"
            classify(code) == CocFailureKind.STALE_PSM -> "reread_psm"
            classify(code) == CocFailureKind.SECURITY_MISMATCH -> "no_retry_same_way"
            classify(code) == CocFailureKind.CAPACITY -> "back_off"
            else -> "retry"
        }
        return "BENCHMARK coc_connect: owner=$owner | outcome=${if (timedOut) "timeout" else "failed"} | " +
            "elapsedMs=$elapsedMs | boundMs=$boundMs | $detail | action=$action | " +
            "error=${error.javaClass.simpleName}: ${error.message}"
    }

    private const val MAX_CAUSE_DEPTH = 6
}

/**
 * C-03 (Android 10): a CoC client connect to an address the stack has not learned in this Bluetooth
 * session never establishes (the address type is not copied into the stack record). MeshLive reads
 * the PSM over GATT first, which creates the record; this check only logs, so a refactor that calls
 * the CoC connect without a GATT connect or fresh scan sighting is visible in the first run.
 */
internal object CocAddressPrecondition {
    /** Returns true when the precondition holds or does not apply. Logs only. */
    fun check(
        policy: ResolvedDevicePolicy,
        precededByGattOrScan: Boolean,
        owner: String,
        log: (String) -> Unit,
    ): Boolean {
        if (!policy.has<CocAddressTypePrecondition>()) return true
        log(
            "BENCHMARK coc_address_precondition: owner=$owner | sdk=${policy.traits.sdkInt} | " +
                "gattOrScanFirst=$precededByGattOrScan | " + if (precededByGattOrScan) "ok" else "VIOLATED",
        )
        return precededByGattOrScan
    }
}

/**
 * B #12 / C-01: a CoC writer that handed bytes to the socket but sees nothing acknowledged by the
 * peer's application from the first second is the signature of a receiver-side credit stall (the
 * peer never grants credits). Pure classification; the caller feeds the counters and applies the
 * result (log, then the existing coc_auto_gatt fallback with [CocFallbackReason.CREDIT_STALL]).
 */
internal object CocCreditStall {
    const val WINDOW_MS = 1_000L
    const val MIN_BYTES_WRITTEN = 1

    /**
     * [sinceFirstWriteMs] is the time since the first byte was written, [bytesWritten] the bytes handed
     * to the socket, [bytesAcknowledged] the bytes the peer's application confirmed (or that returned
     * as a response), [writeBlockedMs] how long the current write has been blocked.
     */
    fun isStall(sinceFirstWriteMs: Long, bytesWritten: Long, bytesAcknowledged: Long, writeBlockedMs: Long): Boolean =
        sinceFirstWriteMs >= WINDOW_MS && bytesWritten >= MIN_BYTES_WRITTEN && bytesAcknowledged == 0L &&
            writeBlockedMs >= WINDOW_MS

    fun logLine(owner: String, sinceFirstWriteMs: Long, bytesWritten: Long, writeBlockedMs: Long): String =
        "BENCHMARK coc_credit_stall: owner=$owner | sinceFirstWriteMs=$sinceFirstWriteMs | bytesWritten=$bytesWritten | " +
            "bytesAcknowledged=0 | writeBlockedMs=$writeBlockedMs | classified=receiver_credit_stall | action=gatt_fallback"
}
