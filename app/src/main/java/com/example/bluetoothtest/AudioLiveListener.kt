package com.example.bluetoothtest

import kotlin.math.ceil

/**
 * Pure listener and relay rules of the experimental audio-only live stream (Design AL, slice 3): relay
 * queue settings and admission, the marker/header agreement check, and the audio-focus policy. Nothing here
 * touches a radio or an AudioTrack.
 */

/** What a relay needs to forward an audio-only stream to its one child. */
class AudioLiveRelaySettings(
    /** `test_audio_live_age_bound`: false is the control arm (no shedding; the 30 s withdrawal still applies). */
    val ageBound: Boolean = true,
)

/** One relay child's audio state: shed counter and the health history behind the withdrawal verdict. */
class AudioLiveRelayQueue(val ageBound: Boolean) {
    val health = AudioLiveChildHealth()

    @Volatile
    var shedRecords: Long = 0L
}

/** Relay admission and the parent-trickle floor for an audio-only stream, from the authenticated header's preset. */
object AudioLiveRelayAdmission {
    /** Planned wire load of one link of this stream (Voice 24 kbps, 200 ms records: 41.4). */
    fun perLinkKbps(preset: Int, framesPerRecord: Int = AudioLive.DEFAULT_BATCH_FRAMES): Double =
        MeshBandwidthEstimator.audioLivePerLinkKbps(AudioLive.presetKbps(preset), framesPerRecord)

    /**
     * A relay carries the incoming and the outgoing link on one radio. The advertised bitrate is display
     * only (rounded to 25 kbps units, or floored to 0 by the compact encoder), so the figure comes from the
     * preset; an advertised rate of 0 never skips this check.
     */
    fun fits(budgetKbps: Double, preset: Int, framesPerRecord: Int = AudioLive.DEFAULT_BATCH_FRAMES): Boolean =
        relayFits(budgetKbps, perLinkKbps(preset, framesPerRecord))

    /**
     * The rate the relay-parent trickle test (a window delivering under 1/8 of the stream's rate is starved)
     * uses: the wire rate of the preset, not the advertised display figure.
     */
    fun trickleRateKbps(preset: Int, framesPerRecord: Int = AudioLive.DEFAULT_BATCH_FRAMES): Int =
        ceil(MeshBandwidthEstimator.audioLiveWireKbps(AudioLive.presetKbps(preset), framesPerRecord)).toInt()
}

/**
 * Thrown by a receiver when the offer it selected and the authenticated header disagree about the stream
 * kind (review 17). The owner penalises only the attempted route, never the logical stream.
 */
class AudioLiveMarkerMismatchException(val offerMarkedAudioOnly: Boolean) :
    IllegalArgumentException(
        if (offerMarkedAudioOnly) "The offer was marked audio only but the stream is video"
        else "The offer was not marked audio only but the stream is audio only",
    )

/** Fails closed when [offerMarkedAudioOnly] and [header] disagree. */
fun requireAudioLiveMarkerAgreement(offerMarkedAudioOnly: Boolean, header: StreamProtocol.Header) {
    if (!AudioLive.markerAgreesWithHeader(offerMarkedAudioOnly, header)) {
        throw AudioLiveMarkerMismatchException(offerMarkedAudioOnly)
    }
}

/**
 * Listener audio-focus rules (Design AL "Calls win"). A transient loss (another app, a cellular call, a
 * ducking request) mutes playout and keeps the link; a permanent loss mutes playout, keeps the link for
 * [PERMANENT_LOSS_GRACE_MS] and then leaves. The listener's own Mute is separate and is never undone by a
 * focus gain. The caller feeds [onFocusChange] with AudioManager's AUDIOFOCUS_* values and calls [verdict]
 * about once a second.
 */
class AudioLiveFocusPolicy(private val nowMs: () -> Long) {
    data class Verdict(val outputMuted: Boolean, val leave: Boolean)

    private var userMuted = false
    private var transientLoss = false
    private var permanentLossSinceMs: Long? = null

    @Synchronized
    fun setUserMuted(muted: Boolean) {
        userMuted = muted
    }

    @Synchronized
    fun onFocusChange(change: Int): Verdict {
        when (change) {
            FOCUS_GAIN -> {
                transientLoss = false
                permanentLossSinceMs = null
            }
            FOCUS_LOSS_TRANSIENT, FOCUS_LOSS_TRANSIENT_CAN_DUCK -> transientLoss = true
            FOCUS_LOSS -> if (permanentLossSinceMs == null) permanentLossSinceMs = nowMs()
        }
        return verdict()
    }

    @Synchronized
    fun verdict(): Verdict {
        val since = permanentLossSinceMs
        val leave = since != null && nowMs() - since >= PERMANENT_LOSS_GRACE_MS
        return Verdict(outputMuted = userMuted || transientLoss || since != null, leave = leave)
    }

    companion object {
        const val FOCUS_GAIN = 1
        const val FOCUS_LOSS = -1
        const val FOCUS_LOSS_TRANSIENT = -2
        const val FOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
        const val PERMANENT_LOSS_GRACE_MS = 60_000L
    }
}
