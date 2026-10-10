package com.example.bluetoothtest

import android.content.Context
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

/** DEBUG only. Whole-media admission before encryption; never RF impairment. */
class DebugMediaImpairment private constructor(
    private val session: String,
    private val config: MediaImpairmentConfig,
    private val configSha256: String,
) : Closeable {
    private val scope = "${Process.myPid()}-${System.nanoTime()}"
    private val plan = MediaImpairmentPlan(config)
    private val lock = ReentrantLock(true)
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val inFlight = AtomicLong(0)
    private val finalWritten = AtomicBoolean(false)
    private val offered = AtomicLong(0)
    private val bypassed = AtomicLong(0)
    private val sent = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val lifecycle = Any()

    init {
        trace("BEGIN", JSONObject().put("schema", "benchmark-media-impairment-v1")
            .put("config_sha256", configSha256).put("clock", "System.nanoTime")
            .put("position", "outgoing-whole-media-before-encryption")
            .put("producer_blocking", true).put("radio_impairment", false))
    }

    fun deliver(flags: Int, ptsUs: Long, bytes: ByteArray, send: (Int, Long, ByteArray) -> Unit) {
        val eligible = MediaImpairmentPlan.eligible(flags)
        synchronized(lifecycle) {
            inFlight.incrementAndGet()
        }
        val offeredNs = plan.offeredNow(flags)
        val seq = sequence.incrementAndGet()
        offered.incrementAndGet()
        trace("OFFER", packet(seq, flags, ptsUs, bytes.size, offeredNs).put("eligible", eligible))
        var acquired = false
        var planned: MediaImpairmentDecision? = null
        var decision = "DROP_CLOSED"
        var actualReleaseNs: Long? = null
        try {
            if (!closed.get()) {
                if (!eligible) {
                    decision = "BYPASS"
                    actualReleaseNs = System.nanoTime()
                    send(flags, ptsUs, bytes)
                    bypassed.incrementAndGet()
                } else {
                    val remaining = config.maxWaitMs * 1_000_000 - (System.nanoTime() - offeredNs)
                    acquired = remaining > 0 && lock.tryLock(remaining, TimeUnit.NANOSECONDS)
                    decision = if (!acquired) "DROP_ADMISSION" else {
                        planned = plan.offer(flags, bytes.size, offeredNs)
                        plan.await(planned!!, closed::get, LockSupport::parkNanos)
                    }
                    if (decision == "SEND") {
                        actualReleaseNs = System.nanoTime()
                        send(flags, ptsUs, bytes)
                        plan.completedRelease(actualReleaseNs)
                        sent.incrementAndGet()
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            decision = "DROP_INTERRUPTED"
        } catch (error: Throwable) {
            decision = "SEND_FAILED"
            failures.incrementAndGet()
            throw error
        } finally {
            if (decision.startsWith("DROP_")) dropped.incrementAndGet()
            val result = packet(seq, flags, ptsUs, bytes.size, offeredNs)
                .put("decision", decision).put("planned_release_ns", planned?.plannedReleaseNs ?: JSONObject.NULL)
                .put("actual_release_ns", actualReleaseNs ?: JSONObject.NULL).put("completed_ns", System.nanoTime())
                .put("phase", planned?.phase ?: JSONObject.NULL).put("phase_index", planned?.phaseIndex ?: JSONObject.NULL)
                .put("plan_index", planned?.planIndex ?: JSONObject.NULL).put("media_index", planned?.mediaIndex ?: JSONObject.NULL)
                .put("jitter_ms", planned?.jitterMs ?: 0).put("plan_decision", planned?.decision ?: JSONObject.NULL)
            trace("RESULT", result)
            if (acquired) lock.unlock()
            if (inFlight.decrementAndGet() == 0L && finalRequested.get()) writeFinal()
        }
    }

    private fun packet(seq: Long, flags: Int, ptsUs: Long, size: Int, offeredNs: Long) =
        JSONObject().put("sequence", seq).put("pts_us", ptsUs).put("flags", flags).put("bytes", size).put("offered_ns", offeredNs)

    private fun trace(kind: String, row: JSONObject) {
        row.put("kind", kind).put("session", session).put("scope", scope).put("pid", Process.myPid())
            .put("clock", "System.nanoTime")
        Log.i("BluetoothVideo", "BENCHMARK impairment v1 $row")
    }

    private fun writeFinal() {
        if (!finalWritten.compareAndSet(false, true)) return
        trace("FINAL", JSONObject().put("schema", "benchmark-media-impairment-v1").put("config_sha256", configSha256)
            .put("origin_ns", plan.originNs ?: JSONObject.NULL).put("closed_ns", System.nanoTime())
            .put("offered", offered.get()).put("sent", sent.get()).put("bypassed", bypassed.get())
            .put("dropped", dropped.get()).put("send_failed", failures.get()).put("in_flight", inFlight.get()))
    }

    private val finalRequested = AtomicBoolean(false)

    fun cancel() {
        synchronized(lifecycle) { closed.set(true) }
    }

    override fun close() {
        synchronized(lifecycle) {
            closed.set(true)
            finalRequested.set(true)
            if (inFlight.get() == 0L) writeFinal()
        }
    }

    companion object {
        fun startIfRequested(context: Context, session: String?, file: File?): DebugMediaImpairment? {
            if (file == null) return null
            require(!session.isNullOrBlank()) { "Impairment needs an exact benchmark session" }
            require(file.isFile && file.length() in 1..131_072) { "Missing or excessive impairment config" }
            val bytes = file.readBytes()
            val parsed = JSONObject(String(bytes, Charsets.UTF_8))
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return DebugMediaImpairment(session, MediaImpairmentConfig.fromMap(map(parsed)), sha)
        }

        private fun map(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { key ->
            when (val row = value.get(key)) {
                is JSONObject -> map(row)
                is JSONArray -> (0 until row.length()).map { index ->
                    val item = row.get(index)
                    if (item is JSONObject) map(item) else item
                }
                JSONObject.NULL -> null
                else -> row
            }
        }
    }
}
