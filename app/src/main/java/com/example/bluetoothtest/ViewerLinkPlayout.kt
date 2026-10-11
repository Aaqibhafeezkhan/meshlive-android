package com.example.bluetoothtest

/**
 * The playout clock of one viewer link. Every link of a stream viewer starts a fresh clock at the
 * profile's base delay, together with a fresh audio decoder, AudioTrack and video decoder (the
 * receiver closes all of them when its link ends and builds new ones in `receiveSecure`), so nothing
 * learned on a lost link is carried to the replacement: not the delay the adaptive clock grew to
 * absorb that link's bursts, not the audio still queued, not an anchor.
 *
 * That is the property the external review of 2026-10-11 asked for ("reconnect must not replay old
 * audio or accumulate growing delay") and it holds structurally, not by a tuned constant. It is a
 * function so the property has one tested definition: a regression that cached the clock, or the
 * decoders, across links would show up as a non-base starting delay in `ViewerLinkPlayoutTest` and in
 * the `viewer_link: event=playout_base` log line of a phone run.
 *
 * Within one link the adaptive delay still grows by design when media arrives late (no frame is
 * dropped for latency, DECISIONS 2026-10-07/09; the bounded-age experiment is the separate A/B) and
 * shrinks after 60 s without late media. A stall that keeps the link up therefore replays its backlog
 * late; the frame-age log (`playoutDelayMs`, `behindReceivedMs`) measures how much.
 *
 * [relaying] reserves room for the stall when a child joins this viewer (a relay's playout starts
 * later than a plain viewer's, `RELAY_JOIN_RESERVE_MS`).
 */
internal fun newViewerLinkPlayoutClock(
    profile: PlaybackDelayProfile,
    delayOverrideMs: Long,
    relaying: Boolean,
    relayJoinReserveMs: Long,
): MediaPlayoutClock = profile.newPlayoutClock(delayOverrideMs).also { clock ->
    if (relaying) clock.reserve(relayJoinReserveMs)
}
