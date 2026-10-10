package com.example.bluetoothtest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Debug-only whole-session resource recorder that keeps working after USB is unplugged. */
class DebugSessionTelemetry private constructor(
    private val context: Context,
    private val sessionName: String,
    private val role: String,
    private val warmupMs: Long,
    private val durationMs: Long,
    private val sampleIntervalMs: Long,
) : Closeable {
    private data class BatteryState(
        val temperatureC: Double? = null,
        val voltageMv: Int? = null,
        val plugged: Int? = null,
    )

    private data class Sample(
        val elapsedMs: Long,
        val appCpuMs: Long,
        val appCpuPercent: Double,
        val capacityPercent: Long?,
        val chargeCounterUah: Long?,
        val energyCounterNwh: Long?,
        val currentNowUa: Long?,
        val temperatureC: Double?,
        val voltageMv: Int?,
        val plugged: Int?,
        val thermalStatus: Int,
    )

    private val running = AtomicBoolean(true)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val outputDirectory = File(context.getExternalFilesDir(null), "session-benchmarks").apply {
        mkdirs()
    }
    private val csvFile = File(outputDirectory, "$sessionName.csv")
    private val summaryFile = File(outputDirectory, "$sessionName-summary.json")
    @Volatile private var batteryState = BatteryState()
    @Volatile private var thermalStatus = powerManager.currentThermalStatus
    private val samples = mutableListOf<Sample>()
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::updateBatteryState)
        }
    }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { thermalStatus = it }
    private val worker: Thread

    init {
        csvFile.delete()
        summaryFile.delete()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialBatteryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(batteryReceiver, filter)
        }
        initialBatteryIntent?.let(::updateBatteryState)
        powerManager.addThermalStatusListener(context.mainExecutor, thermalListener)
        worker = thread(name = "DebugSessionTelemetry", isDaemon = true) { record() }
        Log.i(
            TAG,
            "Session resource telemetry armed: ${csvFile.absolutePath}; warmup ${warmupMs / 1_000}s, " +
                "measure ${durationMs / 1_000}s",
        )
    }

    private fun record() {
        try {
            waitWhileRunning(warmupMs)
            if (!running.get()) return
            val startedAtMs = SystemClock.elapsedRealtime()
            var previousAtMs = startedAtMs
            var previousCpuMs = Process.getElapsedCpuTime()
            csvFile.bufferedWriter().use { writer ->
                writer.appendLine(
                    "elapsed_s,app_process_cpu_ms,app_cpu_core_equivalent_percent," +
                        "battery_capacity_percent,charge_counter_uah,energy_counter_nwh,current_now_ua," +
                        "battery_temperature_c,battery_voltage_mv,plugged,thermal_status",
                )
                while (running.get()) {
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - startedAtMs > durationMs) break
                    val cpuMs = Process.getElapsedCpuTime()
                    val wallDeltaMs = (nowMs - previousAtMs).coerceAtLeast(1L)
                    val cpuPercent = (cpuMs - previousCpuMs).coerceAtLeast(0L) * 100.0 / wallDeltaMs
                    val battery = batteryState
                    val sample = Sample(
                        elapsedMs = nowMs - startedAtMs,
                        appCpuMs = cpuMs,
                        appCpuPercent = cpuPercent,
                        capacityPercent = batteryProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                        chargeCounterUah = batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                        energyCounterNwh = batteryProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
                        currentNowUa = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                        temperatureC = battery.temperatureC,
                        voltageMv = battery.voltageMv,
                        plugged = battery.plugged,
                        thermalStatus = thermalStatus,
                    )
                    synchronized(samples) { samples += sample }
                    writer.appendLine(sample.toCsv())
                    writer.flush()
                    previousAtMs = nowMs
                    previousCpuMs = cpuMs
                    waitWhileRunning(sampleIntervalMs)
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.e(TAG, "Session resource telemetry failed", error)
        } finally {
            running.set(false)
            unregisterObservers()
            writeSummary()
        }
    }

    private fun waitWhileRunning(durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (running.get()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            Thread.sleep(minOf(remaining, 250L))
        }
    }

    private fun batteryProperty(property: Int): Long? = batteryManager.getLongProperty(property)
        .takeUnless { it == Long.MIN_VALUE || it == Int.MIN_VALUE.toLong() }

    private fun updateBatteryState(intent: Intent) {
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeUnless { it == Int.MIN_VALUE }
            ?.div(10.0)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            .takeUnless { it == Int.MIN_VALUE }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, Int.MIN_VALUE)
            .takeUnless { it == Int.MIN_VALUE }
        batteryState = BatteryState(temperature, voltage, plugged)
    }

    private fun Sample.toCsv(): String = listOf(
        "%.3f".format(Locale.US, elapsedMs / 1_000.0),
        appCpuMs,
        "%.2f".format(Locale.US, appCpuPercent),
        capacityPercent.orEmpty(),
        chargeCounterUah.orEmpty(),
        energyCounterNwh.orEmpty(),
        currentNowUa.orEmpty(),
        temperatureC?.let { "%.1f".format(Locale.US, it) }.orEmpty(),
        voltageMv.orEmpty(),
        plugged.orEmpty(),
        thermalStatus,
    ).joinToString(",")

    private fun Any?.orEmpty(): String = this?.toString() ?: ""

    private fun writeSummary() {
        val snapshot = synchronized(samples) { samples.toList() }
        val first = snapshot.firstOrNull()
        val last = snapshot.lastOrNull()
        val result = JSONObject().apply {
            put("session", sessionName)
            put("role", role)
            put("warmup_seconds", warmupMs / 1_000.0)
            put("requested_duration_seconds", durationMs / 1_000.0)
            put("samples", snapshot.size)
            put("measured_duration_seconds", last?.elapsedMs?.div(1_000.0) ?: 0.0)
            putNullable("app_process_cpu_ms", if (first != null && last != null) last.appCpuMs - first.appCpuMs else null)
            putNullable("average_app_cpu_core_equivalent_percent", snapshot.drop(1).map(Sample::appCpuPercent).takeIf { it.isNotEmpty() }?.average())
            putNullable("battery_capacity_start_percent", first?.capacityPercent)
            putNullable("battery_capacity_end_percent", last?.capacityPercent)
            putNullable("battery_capacity_used_percent", difference(first?.capacityPercent, last?.capacityPercent))
            putNullable("charge_used_uah", difference(first?.chargeCounterUah, last?.chargeCounterUah))
            putNullable("energy_used_nwh", difference(first?.energyCounterNwh, last?.energyCounterNwh))
            putNullable("battery_temperature_start_c", first?.temperatureC)
            putNullable("battery_temperature_end_c", last?.temperatureC)
            putNullable("battery_temperature_max_c", snapshot.mapNotNull(Sample::temperatureC).maxOrNull())
            put("maximum_thermal_status", snapshot.maxOfOrNull(Sample::thermalStatus) ?: thermalStatus)
            put("usb_power_caveat", "Battery and energy results are valid only when the phone is unplugged during the measured interval.")
            put("cpu_scope", "app process only; use the connected ADB sampler to include media codec, audio, and Bluetooth processes")
        }
        runCatching { summaryFile.writeText(result.toString(2) + "\n") }
            .onFailure { Log.e(TAG, "Could not write telemetry summary", it) }
        Log.i(TAG, "Session resource telemetry finished: ${summaryFile.absolutePath}\n$result")
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun difference(start: Long?, end: Long?): Long? =
        if (start == null || end == null) null else start - end

    private fun unregisterObservers() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { powerManager.removeThermalStatusListener(thermalListener) }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.interrupt()
        if (Thread.currentThread() !== worker) runCatching { worker.join(2_000) }
    }

    companion object {
        private const val TAG = "BluetoothVideo"

        fun startIfRequested(context: Context, intent: Intent, role: String): DebugSessionTelemetry? {
            if (!intent.getBooleanExtra("test_session_metrics", false)) return null
            val safeName = (intent.getStringExtra("test_session_name") ?: "session-${System.currentTimeMillis()}")
                .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            return DebugSessionTelemetry(
                context.applicationContext,
                safeName,
                role,
                intent.getIntExtra("test_metrics_warmup_seconds", 10).coerceIn(0, 3_600) * 1_000L,
                intent.getIntExtra("test_metrics_duration_seconds", 900).coerceIn(5, 86_400) * 1_000L,
                intent.getIntExtra("test_metrics_interval_seconds", 1).coerceIn(1, 60) * 1_000L,
            )
        }
    }
}
