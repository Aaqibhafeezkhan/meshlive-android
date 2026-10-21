package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * DEBUG-only raw BLE advertisement observer (Design QW review R2/R3/R5): proves what a Windows PC (or any
 * advertiser) really puts on air, independent of the app's own parser and filters.
 *
 * Trigger: DEBUG launch extra `test_raw_observer=<hex substring>` (for example `fa21b92f`), optional
 * `test_raw_observer_seconds` (default 1800). It starts ONE extra unfiltered LOW_LATENCY scan; use it with the
 * screen on and the app in front (the scan stops with onStop; Android throttles or stops background unfiltered
 * scans anyway). It never runs in release builds and has no UI. Because any `test_` extra makes a benchmark
 * launch, a run that uses it is a benchmark launch.
 *
 * Logs (tag BluetoothVideo): one `BENCHMARK raw_observer:` line per matching result (at most one per address
 * per second), a `BENCHMARK raw_observer_summary:` line every 60 s and at the end.
 */
internal class DebugRawObserver(
    private val adapter: BluetoothAdapter?,
    match: String,
    private val durationMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val core = RawObserverCore(match)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var callback: ScanCallback? = null
    private var deadlineMs = 0L
    private var finished = false

    private val summaryTick = object : Runnable {
        override fun run() {
            if (callback == null) return
            if (clock() >= deadlineMs) {
                stop(final = true)
            } else {
                Log.i(TAG, core.summaryLine(clock()))
                handler.postDelayed(this, SUMMARY_MS.coerceAtMost(deadlineMs - clock()).coerceAtLeast(1L))
            }
        }
    }

    /** Idempotent: a later onResume restarts the scan after an onStop, within the original deadline. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (finished || callback != null) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val now = clock()
        if (deadlineMs == 0L) deadlineMs = now + durationMs
        if (now >= deadlineMs) {
            finished = true
            return
        }
        val extended = runCatching { adapter?.isLeExtendedAdvertisingSupported }.getOrNull() == true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(!extended)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BENCHMARK raw_observer: scan failed code=$errorCode")
                callback = null
            }
        }
        try {
            scanner.startScan(null, settings, cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "BENCHMARK raw_observer: scan not permitted: ${e.message}")
            return
        }
        callback = cb
        Log.i(
            TAG,
            "BENCHMARK raw_observer: started match=${core.match} extended=$extended " +
                "seconds=${(deadlineMs - now) / 1000}",
        )
        handler.postDelayed(summaryTick, SUMMARY_MS.coerceAtMost(deadlineMs - now))
    }

    /** Stops the scan and logs the closing summary; [final] also prevents a restart. */
    @SuppressLint("MissingPermission")
    fun stop(final: Boolean = false) {
        handler.removeCallbacks(summaryTick)
        val cb = callback
        callback = null
        if (cb != null) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
            Log.i(TAG, core.summaryLine(clock()))
        }
        if (final) finished = true
    }

    private fun handle(result: ScanResult) {
        val bytes = result.scanRecord?.bytes ?: return
        val now = clock()
        val address = result.device.address
        val raw = core.onResult(address, RawObserverCore.hex(bytes), now) ?: return
        val addressType = addressTypeOf(result)
        Log.i(
            TAG,
            "BENCHMARK raw_observer: address=$address | addressType=$addressType | legacy=${result.isLegacy} | " +
                "connectable=${result.isConnectable} | primaryPhy=${result.primaryPhy} | " +
                "secondaryPhy=${result.secondaryPhy} | sid=${result.advertisingSid} | txPower=${result.txPower} | " +
                "rssi=${result.rssi} | raw=$raw",
        )
    }

    /** getAddressType() is not in the public SDK stubs here, so it is read by reflection (ScanResult, then the device) on API 34+. */
    private fun addressTypeOf(result: ScanResult): String {
        if (Build.VERSION.SDK_INT < 34) return "n/a"
        for (target in listOf<Any>(result, result.device)) {
            val v = runCatching { target.javaClass.getMethod("getAddressType").invoke(target) }.getOrNull()
            if (v != null) return v.toString()
        }
        return "n/a"
    }

    companion object {
        private const val TAG = "BluetoothVideo"
        private const val SUMMARY_MS = 60_000L
        const val DEFAULT_SECONDS = 1800L

        /** Null unless this is a DEBUG build and the extra is a non-blank hex string. */
        fun create(debug: Boolean, adapter: BluetoothAdapter?, match: String?, seconds: Long?): DebugRawObserver? {
            if (!debug) return null
            val m = RawObserverCore.normalizeMatch(match) ?: return null
            val s = (seconds ?: DEFAULT_SECONDS).takeIf { it > 0 } ?: DEFAULT_SECONDS
            return DebugRawObserver(adapter, m, s * 1000L)
        }
    }
}

/** Pure core of [DebugRawObserver]: hex match, per-address rate limit, summary aggregation. */
internal class RawObserverCore(val match: String, private val rateLimitMs: Long = 1000L) {
    private class Seen(val firstMs: Long, var lastMs: Long, var count: Int)

    private val lastLoggedMs = HashMap<String, Long>()
    private val seen = LinkedHashMap<String, Seen>()
    private var payload: String? = null

    /**
     * Records a result; returns the raw hex to log when it matches and the per-address rate limit allows,
     * else null. Every matching result updates the summary, logged or not.
     */
    fun onResult(address: String, rawHex: String, nowMs: Long): String? {
        val hex = rawHex.lowercase()
        if (!hex.contains(match)) return null
        extractSection(hex, match)?.let { payload = it }
        val s = seen[address]
        if (s == null) seen[address] = Seen(nowMs, nowMs, 1) else {
            s.lastMs = nowMs
            s.count++
        }
        val last = lastLoggedMs[address]
        if (last != null && nowMs - last < rateLimitMs) return null
        lastLoggedMs[address] = nowMs
        return hex
    }

    fun summaryLine(nowMs: Long): String {
        val addrs = seen.entries.joinToString(", ", "[", "]") { (a, s) ->
            "$a(first=${s.firstMs},last=${s.lastMs},count=${s.count})"
        }
        return "BENCHMARK raw_observer_summary: payload=${payload ?: "none"} | addresses=$addrs | now=$nowMs"
    }

    companion object {
        private val HEX = Regex("^[0-9a-f]+$")
        private const val DIGITS = "0123456789abcdef"

        fun hex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 15])
            }
            return sb.toString()
        }

        /** Lower-cased hex with spaces and colons removed; null when blank or not hex. */
        fun normalizeMatch(raw: String?): String? {
            val m = raw?.lowercase()?.replace(" ", "")?.replace(":", "") ?: return null
            return m.takeIf { it.isNotEmpty() && HEX.matches(it) }
        }

        /**
         * The whole AD structure (length, type, data) that contains the match, walking the advertising data;
         * null when the data is malformed before a containing structure is found or the match spans structures.
         */
        fun extractSection(rawHex: String, match: String): String? {
            var pos = 0
            while (pos + 2 <= rawHex.length) {
                val len = rawHex.substring(pos, pos + 2).toInt(16)
                if (len == 0) return null
                val end = pos + 2 + len * 2
                if (end > rawHex.length) return null
                val section = rawHex.substring(pos, end)
                if (section.contains(match)) return section
                pos = end
            }
            return null
        }
    }
}
