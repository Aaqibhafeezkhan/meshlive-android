package com.example.bluetoothtest

import kotlin.math.max

data class MediaImpairmentPhase(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val delayMs: Long,
    val jitterMs: Long,
    val rateBps: Long,
    val dropEvery: Long,
    val burstEvery: Long,
    val burstLength: Long,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid impairment phase id" }
        require(startMs in 0..86_400_000 && endMs in 1..86_400_000 && startMs < endMs)
        require(delayMs in 0..5_000 && jitterMs in 0..5_000)
        require(rateBps in 0..1_000_000_000)
        require(dropEvery in 0..1_000_000 && burstEvery in 0..1_000_000)
        require(burstLength in 0..burstEvery)
        require((burstEvery == 0L) == (burstLength == 0L)) { "Burst period and length must both be enabled" }
    }
}

data class MediaImpairmentConfig(val seed: Long, val maxWaitMs: Long, val phases: List<MediaImpairmentPhase>) {
    init {
        require(seed in 0..2_147_483_646 && maxWaitMs in 1..5_000)
        require(phases.size in 1..100 && phases.map { it.id }.toSet().size == phases.size)
        require(phases.zipWithNext().all { (left, right) -> left.endMs <= right.startMs }) { "Phases overlap or are not ordered" }
    }

    companion object {
        private fun integer(values: Map<*, *>, key: String): Long = when (val value = values[key]) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$key must be an integer")
        }

        fun fromMap(values: Map<*, *>): MediaImpairmentConfig {
            require(values.keys == setOf("schema", "seed", "max_wait_ms", "phases")) { "Missing or unknown impairment config keys" }
            require(values["schema"] == "benchmark-media-impairment-v1")
            val rows = values["phases"] as? List<*> ?: throw IllegalArgumentException("phases must be an array")
            val phases = rows.map { row ->
                val phase = row as? Map<*, *> ?: throw IllegalArgumentException("phase must be an object")
                require(phase.keys == setOf("id", "start_ms", "end_ms", "delay_ms", "jitter_ms", "rate_bps", "drop_every", "burst_every", "burst_length")) {
                    "Missing or unknown impairment phase keys"
                }
                MediaImpairmentPhase(
                    phase["id"] as? String ?: throw IllegalArgumentException("phase id must be a string"),
                    integer(phase, "start_ms"), integer(phase, "end_ms"), integer(phase, "delay_ms"),
                    integer(phase, "jitter_ms"), integer(phase, "rate_bps"), integer(phase, "drop_every"),
                    integer(phase, "burst_every"), integer(phase, "burst_length"),
                )
            }
            return MediaImpairmentConfig(integer(values, "seed"), integer(values, "max_wait_ms"), phases)
        }
    }
}

data class MediaImpairmentDecision(
    val planIndex: Long,
    val mediaIndex: Long?,
    val phase: String?,
    val phaseIndex: Long?,
    val offeredNs: Long,
    val plannedReleaseNs: Long?,
    val jitterMs: Long,
    val decision: String,
)

/** Whole-media simulation before an authenticated record/sequence exists.
 * No payload is retained, no worker or unbounded queue exists. Rate shaping
 * charges the offered bytes and blocks the producer; it changes source cadence.
 */
class MediaImpairmentPlan(
    val config: MediaImpairmentConfig,
    private val clock: () -> Long = System::nanoTime,
) {
    var originNs: Long? = null
        private set
    private var planIndex = 0L
    private var mediaIndex = 0L
    private var lastReleaseNs = Long.MIN_VALUE
    private val phaseIndexes = LongArray(config.phases.size)

    @Synchronized
    fun offeredNow(flags: Int): Long = clock().also { if (eligible(flags)) observeOrigin(it) }

    @Synchronized
    fun observeOrigin(offeredNs: Long) {
        if (originNs == null) originNs = offeredNs
    }

    @Synchronized
    fun offer(flags: Int, bytes: Int, offeredNs: Long = clock()): MediaImpairmentDecision {
        require(bytes in 0..StreamProtocol.MAX_PAYLOAD_BYTES)
        planIndex++
        if (!eligible(flags)) return MediaImpairmentDecision(planIndex, null, null, null, offeredNs, offeredNs, 0, "BYPASS")
        observeOrigin(offeredNs)
        mediaIndex++
        val elapsedNs = offeredNs - originNs!!
        require(elapsedNs >= 0) { "Impairment clock precedes origin" }
        val phaseNumber = config.phases.indexOfFirst { elapsedNs >= it.startMs * MILLION && elapsedNs < it.endMs * MILLION }
        if (phaseNumber < 0) {
            val release = max(offeredNs, lastReleaseNs)
            return MediaImpairmentDecision(planIndex, mediaIndex, null, null, offeredNs, release, 0, "SEND")
        }
        val phase = config.phases[phaseNumber]
        val index = ++phaseIndexes[phaseNumber]
        val random = ((config.seed + (index % MODULUS) * 1_103_515_245L + phaseNumber * 12_345L) % MODULUS)
        val jitter = if (phase.jitterMs == 0L) 0 else random % (2 * phase.jitterMs + 1) - phase.jitterMs
        val drop = phase.dropEvery > 0 && index % phase.dropEvery == 0L ||
            phase.burstEvery > 0 && (index - 1) % phase.burstEvery < phase.burstLength
        if (drop) return MediaImpairmentDecision(planIndex, mediaIndex, phase.id, index, offeredNs, null, jitter, "DROP_PATTERN")
        val delayedNs = offeredNs + max(0, phase.delayMs + jitter) * MILLION
        val serviceNs = if (phase.rateBps == 0L) 0 else (bytes.toLong() * 8 * BILLION + phase.rateBps - 1) / phase.rateBps
        val release = max(delayedNs, lastReleaseNs) + serviceNs
        val decision = if (release - offeredNs > config.maxWaitMs * MILLION) "DROP_MAX_WAIT" else "SEND"
        if (decision == "SEND") lastReleaseNs = release
        return MediaImpairmentDecision(planIndex, mediaIndex, phase.id, index, offeredNs, release, jitter, decision)
    }

    @Synchronized
    fun completedRelease(actualNs: Long) {
        lastReleaseNs = max(lastReleaseNs, actualNs)
    }

    fun await(
        decision: MediaImpairmentDecision,
        closed: () -> Boolean,
        waitNs: (Long) -> Unit,
        interrupted: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): String {
        if (decision.decision != "SEND") return decision.decision
        val target = decision.plannedReleaseNs!!
        val deadline = decision.offeredNs + config.maxWaitMs * MILLION
        while (true) {
            if (closed()) return "DROP_CLOSED"
            if (interrupted()) return "DROP_INTERRUPTED"
            val now = clock()
            if (now > deadline) return "DROP_MAX_WAIT"
            if (now >= target) return "SEND"
            waitNs(minOf(target - now, deadline - now, 5 * MILLION))
        }
    }

    companion object {
        private const val MODULUS = 2_147_483_647L
        private const val MILLION = 1_000_000L
        private const val BILLION = 1_000_000_000L

        // 0..0xff: experimental discardable frames (FLAG_DISCARDABLE) are impaired like other media.
        fun eligible(flags: Int): Boolean = flags in 0..0xff &&
            flags and (StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_DISPLAY_ROTATION) == 0
    }
}
