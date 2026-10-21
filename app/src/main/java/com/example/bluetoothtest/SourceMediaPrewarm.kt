package com.example.bluetoothtest

/**
 * Experimental (default off, `test_source_media_prewarm`): a broadcast source starts its camera and encoder when it
 * begins connecting to the first viewer, not when that viewer has authenticated.
 *
 * 2026-10-20 (vab-20261020b/c, S22 <-> ASUS, 320x240 15 fps VP9, GATT and CoC, both directions, 8 runs): selection to
 * first surface took 2.9-3.8 s (median 3.3 s) against the 3 s gate. Medians per stage: viewer request advert 124 ms,
 * source scan and connect 662 ms, link setup and handshake 1,228 ms (encryption, service discovery, MTU, BMH2), first
 * keyframe after authentication 995 ms, transfer and decoder 200 ms, render 79 ms. That first-keyframe stage is our own
 * order of work: the source opened its camera session and encoder only in `onFirstConnected`, so the whole camera and
 * encoder start-up (session about 390 ms, first frame about 950 ms) ran after the link was ready; the first frame was
 * also black in 8 of 8 runs (exposure not settled). Started at connect time it overlaps the 1.2-2 s of link setup, and
 * the viewer's authentication only asks the running encoder for a key frame (already done by `onPeerReady`).
 *
 * The camera is already open for the idle viewfinder while a stream waits for viewers, so this adds encoder work, not
 * camera exposure. The microphone still opens only after a viewer authenticated. No viewer authenticating ends the
 * media through the existing all-disconnected path (connection timeout 12 s, then the 6 s recovery grace).
 */
internal object SourceMediaPrewarm {
    /** Not for audio-only streams (no camera), the Auto ladder (its start rate needs the connected peers) or replay. */
    fun applies(enabled: Boolean, audioOnly: Boolean, autoLadder: Boolean, replay: Boolean): Boolean =
        enabled && !audioOnly && !autoLadder && !replay

    enum class FirstViewer { START_MEDIA, KEY_FRAME_AND_MICROPHONE }

    /** What the first authenticated viewer does: start the media as before, or use the pre-warmed encoder. */
    fun onFirstViewer(prewarmed: Boolean, encoderRunning: Boolean): FirstViewer =
        if (prewarmed && encoderRunning) FirstViewer.KEY_FRAME_AND_MICROPHONE else FirstViewer.START_MEDIA

    /** The microphone waits while the media is pre-warmed for a viewer that has not authenticated yet. */
    fun holdMicrophone(prewarmed: Boolean, connectedViewers: Int): Boolean = prewarmed && connectedViewers == 0
}
