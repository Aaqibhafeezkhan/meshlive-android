package com.example.bluetoothtest

data class LatencySnapshot(
    val samples: Int,
    val averageMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
)

class RollingLatencyStats(private val capacity: Int = 256) {
    init {
        require(capacity > 0)
    }

    private val values = LongArray(capacity)
    private var count = 0
    private var next = 0

    @Synchronized
    fun recordNanos(value: Long) {
        values[next] = value.coerceAtLeast(0)
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun reset() {
        count = 0
        next = 0
    }

    @Synchronized
    fun takeSnapshot(minimumSamples: Int): LatencySnapshot? {
        require(minimumSamples in 1..capacity)
        if (count < minimumSamples) return null
        return snapshot().also { reset() }
    }

    @Synchronized
    fun snapshot(): LatencySnapshot {
        if (count == 0) return LatencySnapshot(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val sorted = values.copyOf(count).sortedArray()
        fun percentile(percent: Double): Double {
            val index = kotlin.math.ceil(percent * sorted.size).toInt()
                .coerceIn(1, sorted.size) - 1
            return sorted[index] / 1_000_000.0
        }
        return LatencySnapshot(
            samples = sorted.size,
            averageMs = sorted.average() / 1_000_000.0,
            p50Ms = percentile(0.50),
            p95Ms = percentile(0.95),
            p99Ms = percentile(0.99),
            maxMs = sorted.last() / 1_000_000.0,
        )
    }
}
