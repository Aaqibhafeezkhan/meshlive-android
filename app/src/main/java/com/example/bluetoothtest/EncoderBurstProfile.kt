package com.example.bluetoothtest

import android.content.Context

/**
 * What an encoder did on this phone in earlier sessions (measurement for the
 * experimental encoder ranking). [burstSeconds] is the largest frame expressed in seconds of that
 * frame's own stream (bytes x 8 / requested bitrate): M31 OMX VP9 scene cuts were 1.8-1.9 s at
 * 300-600 kbps, S24 c2.exynos.vp9 1.1-1.4 s, M31 libaom AV1 about 0.35 s (lane-b-*-529f36cc,
 * regress-57484598). It is link-independent and conservative: the link is at least the stream rate.
 * [encodeLoad] is the software encoder's p95 encode time as a fraction of the frame interval
 * (null for MediaCodec).
 */
internal data class EncoderBurstRecord(
    val burstSeconds: Double,
    val encodeLoad: Double?,
    val sessions: Int,
    val updatedAtMs: Long,
) {
    /** Scene cuts stay within about two frame-airtime budgets and the encoder keeps up. */
    val fits: Boolean
        get() = burstSeconds <= EncoderBurstRanking.FIT_BURST_SECONDS &&
            (encodeLoad == null || encodeLoad <= EncoderBurstRanking.MAX_ENCODE_LOAD)

    /** Compact stored form: `v1,<burst s>,<encode load or ->,<sessions>,<updated ms>`. */
    fun encode(): String = listOf(
        "v1",
        burstSeconds.toString(),
        encodeLoad?.toString() ?: "-",
        sessions.toString(),
        updatedAtMs.toString(),
    ).joinToString(",")

    companion object {
        fun decode(value: String): EncoderBurstRecord? {
            val fields = value.split(",")
            if (fields.size != 5 || fields[0] != "v1") return null
            return EncoderBurstRecord(
                burstSeconds = fields[1].toDoubleOrNull()?.takeIf { it >= 0 } ?: return null,
                encodeLoad = if (fields[2] == "-") null else fields[2].toDoubleOrNull() ?: return null,
                sessions = fields[3].toIntOrNull() ?: return null,
                updatedAtMs = fields[4].toLongOrNull() ?: return null,
            )
        }
    }
}

/** One session's largest frame relative to its own requested rate (all encoders, every session). */
internal class EncoderBurstAccumulator {
    var frames = 0
        private set
    var maxBurstSeconds = 0.0
        private set
    var largestBytes = 0
        private set

    fun record(bytes: Int, requestedBps: Int) {
        if (bytes <= 0 || requestedBps <= 0) return
        frames++
        val seconds = bytes * 8.0 / requestedBps
        if (seconds > maxBurstSeconds) {
            maxBurstSeconds = seconds
            largestBytes = bytes
        }
    }

    /** Null for sessions too short to have met the content (fewer than [minFrames] frames). */
    fun sessionBurstSeconds(minFrames: Int): Double? = maxBurstSeconds.takeIf { frames >= minFrames }
}

internal object EncoderBurstRanking {
    /** Two 250 ms frame-airtime budgets at the stream's own rate. */
    const val FIT_BURST_SECONDS = 0.5
    /** Software encode p95 must leave this much of the frame interval (M31 libaom: 0.57-0.67). */
    const val MAX_ENCODE_LOAD = 0.8
    /** A session counts after this many seconds of encoded frames. */
    const val MIN_SESSION_SECONDS = 10
    /** A calmer session lowers a recorded burst only slowly; a larger one replaces it at once. */
    const val CALM_SESSION_WEIGHT = 0.2

    /** Same smoothing for the measured encode time per megapixel: a slower session replaces at once. */
    fun mergeMsPerMegapixel(previous: Double?, sessionMsPerMpx: Double): Double =
        if (previous == null) sessionMsPerMpx
        else maxOf(sessionMsPerMpx, previous * (1 - CALM_SESSION_WEIGHT) + sessionMsPerMpx * CALM_SESSION_WEIGHT)

    fun merge(previous: EncoderBurstRecord?, burstSeconds: Double, encodeLoad: Double?, nowMs: Long): EncoderBurstRecord {
        val burst = when {
            previous == null || burstSeconds >= previous.burstSeconds -> burstSeconds
            else -> previous.burstSeconds * (1 - CALM_SESSION_WEIGHT) + burstSeconds * CALM_SESSION_WEIGHT
        }
        val load = when {
            encodeLoad == null -> previous?.encodeLoad
            previous?.encodeLoad == null -> encodeLoad
            else -> maxOf(encodeLoad, previous.encodeLoad * (1 - CALM_SESSION_WEIGHT) + encodeLoad * CALM_SESSION_WEIGHT)
        }
        return EncoderBurstRecord(burst, load, (previous?.sessions ?: 0) + 1, nowMs)
    }

    /**
     * Experimental order: when some encoder is measured to fit, measured-fitting encoders come first,
     * then unmeasured ones, then encoders measured to burst; each group keeps the static
     * [VideoCodec.selectionPriority] order. With nothing measured to fit, the static order stays.
     */
    fun order(codecs: List<VideoCodec>, record: (String) -> EncoderBurstRecord?): List<VideoCodec> {
        val records = codecs.associate { it.encoderName to record(it.encoderName) }
        if (records.values.none { it?.fits == true }) return codecs
        return codecs.withIndex().sortedWith(
            compareBy<IndexedValue<VideoCodec>> { (_, codec) ->
                val measured = records[codec.encoderName]
                when {
                    measured == null -> 1
                    measured.fits -> 0
                    else -> 2
                }
            }.thenBy { it.index },
        ).map { it.value }
    }
}

/** Per-phone encoder records (app-private preferences; one JSON value per encoder name). */
internal class EncoderBurstProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(encoderName: String): EncoderBurstRecord? =
        preferences.getString(encoderName, null)?.let(EncoderBurstRecord::decode)

    fun save(encoderName: String, record: EncoderBurstRecord) {
        preferences.edit().putString(encoderName, record.encode()).apply()
    }

    /**
     * The software encoder's p95 encode time per megapixel of frame (ms), geometry-independent, so the
     * Auto ladder can predict the load of any size ([AutoQualityLadder.encodeLoad]). The record above keeps
     * a load for whatever geometry the last session used; this one scales with the frame.
     */
    fun msPerMegapixel(encoderName: String): Double? =
        preferences.getString("$encoderName.msPerMpx", null)?.toDoubleOrNull()?.takeIf { it > 0.0 }

    fun saveMsPerMegapixel(encoderName: String, value: Double) {
        preferences.edit().putString("$encoderName.msPerMpx", value.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "encoder_burst_profiles"
    }
}
