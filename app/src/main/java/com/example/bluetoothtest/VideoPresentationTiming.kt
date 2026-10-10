package com.example.bluetoothtest

import java.util.ArrayDeque

internal enum class VideoPresentationEventKind { RELEASE, CODEC, TEXTURE }
internal enum class VideoPresentationMatch { RECORDED, MATCHED, UNMATCHED, AMBIGUOUS, DUPLICATE, STALE_GENERATION }

internal data class VideoPresentationTimingEvent(
    val kind: VideoPresentationEventKind,
    val match: VideoPresentationMatch,
    val generation: Long,
    val mediaPtsUs: Long?,
    val targetNs: Long?,
    val releaseCallNs: Long?,
    val reportedNs: Long?,
    val observedNowNs: Long,
    val unseenEarlierTextureReleases: Int = 0,
    val sequence: Long = 0,
) {
    val observedMinusTargetNs: Long? get() = targetNs?.let { observedNowNs - it }
    val reportedMinusTargetNs: Long? get() = reportedNs?.let { reported -> targetNs?.let { reported - it } }
    val codecListenerDelayNs: Long?
        get() = if (kind == VideoPresentationEventKind.CODEC) reportedNs?.let { observedNowNs - it } else null
}

internal data class VideoPresentationTimingSnapshot(
    val generation: Long,
    val capacity: Int,
    val events: List<VideoPresentationTimingEvent>,
    val cumulativeCounts: Map<Pair<VideoPresentationEventKind, VideoPresentationMatch>, Long>,
    val unobservedCodecReleases: Int,
    val unobservedTextureReleases: Int,
    val releaseOverflows: Long,
    val unobservedCodecOverflows: Long,
    val unobservedTextureOverflows: Long,
    val eventOverflows: Long,
    val generationDiscardedReleases: Long,
)

/** Caller must DEBUG-gate creation. Exact timestamp equality does not prove provenance or phone pixels. */
internal class VideoPresentationTiming(generation: Long, private val capacity: Int = 128) {
    private data class Release(val mediaPtsUs: Long, val targetNs: Long, val callNs: Long,
        var codecObserved: Boolean = false, var textureObserved: Boolean = false)
    private var generation = generation
    private val releases = ArrayDeque<Release>()
    private val events = ArrayDeque<VideoPresentationTimingEvent>()
    private val counts = mutableMapOf<Pair<VideoPresentationEventKind, VideoPresentationMatch>, Long>()
    private var releaseOverflows = 0L
    private var unobservedCodecOverflows = 0L
    private var unobservedTextureOverflows = 0L
    private var eventOverflows = 0L
    private var generationDiscardedReleases = 0L
    private var eventSequence = 0L

    init { require(capacity > 0) }

    @Synchronized
    fun beginGeneration(value: Long) {
        if (value == generation) return
        generationDiscardedReleases += releases.size
        releases.clear()
        generation = value
    }

    @Synchronized
    fun recordRelease(eventGeneration: Long, mediaPtsUs: Long, targetNs: Long, callNs: Long): VideoPresentationTimingEvent {
        val match = if (eventGeneration == generation) VideoPresentationMatch.RECORDED else VideoPresentationMatch.STALE_GENERATION
        if (match == VideoPresentationMatch.RECORDED) {
            if (releases.size == capacity) {
                val evicted = releases.removeFirst()
                releaseOverflows++
                if (!evicted.codecObserved) unobservedCodecOverflows++
                if (!evicted.textureObserved) unobservedTextureOverflows++
            }
            releases.addLast(Release(mediaPtsUs, targetNs, callNs))
        }
        return append(VideoPresentationTimingEvent(VideoPresentationEventKind.RELEASE, match,
            eventGeneration, mediaPtsUs, targetNs, callNs, null, callNs))
    }

    @Synchronized
    fun recordCodec(eventGeneration: Long, renderedPtsUs: Long, reportedRenderNs: Long, listenerNowNs: Long) =
        correlate(VideoPresentationEventKind.CODEC, eventGeneration, renderedPtsUs, reportedRenderNs, listenerNowNs)

    @Synchronized
    fun recordTexture(eventGeneration: Long, timestampNs: Long, callbackNowNs: Long) =
        correlate(VideoPresentationEventKind.TEXTURE, eventGeneration, timestampNs, timestampNs, callbackNowNs)

    private fun correlate(kind: VideoPresentationEventKind, eventGeneration: Long,
        identity: Long, reportedNs: Long, nowNs: Long): VideoPresentationTimingEvent {
        val codec = kind == VideoPresentationEventKind.CODEC
        val candidates = if (eventGeneration != generation) emptyList() else releases.filter {
            if (codec) it.mediaPtsUs == identity else it.targetNs == identity
        }
        val release = candidates.singleOrNull()
        val match = when {
            eventGeneration != generation -> VideoPresentationMatch.STALE_GENERATION
            candidates.isEmpty() -> VideoPresentationMatch.UNMATCHED
            candidates.size > 1 -> VideoPresentationMatch.AMBIGUOUS
            release != null && (if (codec) release.codecObserved else release.textureObserved) -> VideoPresentationMatch.DUPLICATE
            else -> VideoPresentationMatch.MATCHED
        }
        val earlier = if (!codec && match == VideoPresentationMatch.MATCHED) {
            releases.takeWhile { it !== release }.count { !it.textureObserved }
        } else 0
        if (match == VideoPresentationMatch.MATCHED && release != null) {
            if (codec) release.codecObserved = true else release.textureObserved = true
        }
        return append(VideoPresentationTimingEvent(kind, match, eventGeneration,
            if (codec) identity else release?.mediaPtsUs, release?.targetNs, release?.callNs,
            reportedNs, nowNs, earlier))
    }

    private fun append(event: VideoPresentationTimingEvent): VideoPresentationTimingEvent {
        val recorded = event.copy(sequence = ++eventSequence)
        val key = event.kind to event.match
        counts[key] = (counts[key] ?: 0L) + 1L
        if (events.size == capacity) {
            events.removeFirst()
            eventOverflows++
        }
        events.addLast(recorded)
        return recorded
    }

    @Synchronized
    fun snapshot() = VideoPresentationTimingSnapshot(generation, capacity, events.toList(), counts.toMap(),
        releases.count { !it.codecObserved }, releases.count { !it.textureObserved },
        releaseOverflows, unobservedCodecOverflows, unobservedTextureOverflows,
        eventOverflows, generationDiscardedReleases)
}

internal fun VideoPresentationTimingSnapshot.traceLines(scopeNs: Long, report: Long, final: Boolean, decoderName: String): List<String> {
    val prefix = "BENCHMARK video presentation v1"
    val counts = cumulativeCounts.entries.joinToString(",") { "${it.key.first}.${it.key.second}:${it.value}" }.ifEmpty { "NONE" }
    val meta = "$prefix META scope=$scopeNs report=$report generation=$generation final=$final decoder=$decoderName " +
        "rollingSamples=${events.size} capacity=$capacity counts=$counts unobservedCodec=$unobservedCodecReleases " +
        "unobservedTexture=$unobservedTextureReleases releaseOverwrites=$releaseOverflows " +
        "unobservedCodecOverflows=$unobservedCodecOverflows unobservedTextureOverflows=$unobservedTextureOverflows " +
        "eventOverwrites=$eventOverflows generationDiscarded=$generationDiscardedReleases " +
        "generationScope=listener_or_current_decoder_not_buffer_epoch optical=UNMEASURED"
    val columns = "$prefix COLUMNS scope=$scopeNs report=$report " +
        "seq,kind,match,generation,ptsUs,targetNs,releaseCallNs,reportedNs,observedNowNs," +
        "observedMinusTargetNs,reportedMinusTargetNs,codecListenerDelayNs,unseenEarlierTextureReleases"
    val chunks = mutableListOf<String>()
    var chunk = StringBuilder()
    for (event in events) {
        val row = listOf(event.sequence, event.kind, event.match, event.generation, event.mediaPtsUs,
            event.targetNs, event.releaseCallNs, event.reportedNs, event.observedNowNs,
            event.observedMinusTargetNs, event.reportedMinusTargetNs, event.codecListenerDelayNs,
            event.unseenEarlierTextureReleases).joinToString(",") { it?.toString() ?: "NA" }
        if (chunk.length + row.length + 1 > 2_600) {
            chunks.add(chunk.toString())
            chunk = StringBuilder()
        }
        if (chunk.isNotEmpty()) chunk.append(';')
        chunk.append(row)
    }
    if (chunk.isNotEmpty()) chunks.add(chunk.toString())
    return listOf(meta, columns) + chunks.mapIndexed { index, rows ->
        "$prefix DATA scope=$scopeNs report=$report part=${index + 1}/${chunks.size} rows=$rows"
    }
}
