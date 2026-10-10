package com.example.bluetoothtest

/** Debug-build overrides for tunable sweeps; production code ignores them. */
object DebugTunables {
    /** Connection priority a streaming source requests on its GATT media links. */
    @Volatile var sourceGattPriority: BleConnectionPriority? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * MediaCodec bitrate mode forced past the encoder's advertised capabilities (0 CQ, 1 VBR, 2 CBR).
     * The M31 OMX VP9 advertises VBR only and emits ~210 KB four-frame scene-change bursts (2.8 s of a
     * 600 kbps stream); this tests whether it honours CBR anyway. Null keeps the resolved mode.
     */
    @Volatile var bitrateModeOverride: Int? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Hardware encoder QP bounds (MediaFormat video-qp-*-min/max, API 31+) for the scene-cut burst
     * experiment: the M31 OMX VP9 encoder emitted 130 KB frames at scene cuts (VBR at low QP).
     * Null leaves the encoder's own range.
     */
    @Volatile var hardwareQpMin: Int? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }
    @Volatile var hardwareQpMax: Int? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Experimental (off): the call frame-rate ladder starts at 0.4 x the request, as before
     * 2026-10-09, instead of at the encoded geometry's floor (see FrameRateLadder.floorBps).
     */
    @Volatile var callLadderFromRequest: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental delivery-based probing (off by default, 2026-10-09; see LinkRateController
     * `deliveryProbing`). On links whose peer sends delivery reports: the climb holds just below what
     * the link delivered at its last overuse and probes 10% past it rarely (a probe that builds a
     * measured queue is undone; failed probes back off), a measured queue of 300 ms that is not
     * draining is overuse, and decreases may be 1 s apart. Off: the default delivery-referenced cuts
     * in calls still apply. DEBUG launch extra `test_delivery_rate_control` (benchmark tunable
     * `delivery_rate_control`); read when a stream or call starts.
     */
    @Volatile var deliveryRateControl: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental stream delivery reports (off by default, 2026-10-09). Viewers report delivery
     * (link report version 3) every second, and a stream sender uses a child's delivery only with its
     * own switch on. A stream parent cannot advertise that it accepts version 3 (older viewers reject
     * any heartbeat from a parent), so every phone in the stream must run this build. Reports sent
     * only for delivery carry the delivery-only flag, which keeps the parent's lateness trend as it
     * was. DEBUG launch extra `test_stream_delivery_reports` (benchmark tunable
     * `stream_delivery_reports`); read when a stream link starts.
     */
    @Volatile var streamDeliveryReports: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental bounded media age (off by default; design decision 2026-10-09: A/B against the
     * no-loss default before choosing). On: calls keep speech near 400 ms mouth-to-ear by degrading
     * video (bitrate, verified non-reference frames, skip to a keyframe, then audio-only pause), and
     * stream queues shed stale video codec-safely beyond about 2 s of media age. Audio is never shed.
     * Off: no queue sheds and behaviour is unchanged; the BENCHMARK media-age lines are written in
     * both arms. See BoundedMediaAge. DEBUG launch extra `test_bounded_media_age` (benchmark tunable
     * `bounded_media_age`); read when a stream or call starts.
     */
    @Volatile var experimentalBoundedMediaAge: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Relay-child media age (on by default since 2026-10-09, design decision). Applies the bounded media-age shedding of
     * [experimentalBoundedMediaAge] to a viewer-forwarder's child queue only: a child whose link is
     * slower than the stream gets codec-safe skip-to-keyframe on that branch instead of being
     * withdrawn (which made it reroute to the source and halve the source's other children, mesh3
     * c85a177c). Calls, direct sender queues and other branches stay no-loss. DEBUG launch extra
     * `test_relay_child_media_age` (benchmark tunable `relay_child_media_age`); otherwise the Advanced
     * setting "Relay: slow viewers skip to keyframes" (on by default, any build). Read when forwarding starts.
     */
    @Volatile var experimentalRelayChildMediaAge: Boolean = true

    /**
     * Speaker gain (0-1) for automated runs: generated reference audio played at full volume on four
     * phones at once (2026-10-09). Measurements read the PCM accepted by AudioTrack, before this gain.
     * Null keeps normal playback.
     */
    @Volatile var playbackGain: Float? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 0f..1f }
        }

    /** Pacing writes at the child-measured link rate; DEBUG A/B runs can turn it off. */
    @Volatile var linkPacing: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * BLE scan-failure recovery (on by default, 2026-10-10; BleL2capDiscovery.onScanFailure). Each
     * ScanCallback failure code maps to restart / back off (3 s x n, capped at 30 s, reset by any result;
     * each restart takes a ScanStartGate slot, le-scan-start-quota) / report. A continuous scan hands the
     * failure to its caller's visible error path when the back-off reaches the cap. DEBUG
     * `test_scan_recovery=false` (benchmark tunable `scan_recovery`) is the control arm: any failure
     * stops discovery and reports, as before.
     */
    @Volatile var scanRecovery: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): private calls on socket transports (Classic RFCOMM, LE CoC) pace their
     * writes with [LinkPacer] at the peer-measured arrival rate, seeded like a stream child, and the
     * peer's call reports also teach this phone's learned single-link capacity (today only stream
     * children do, so a phone that only calls never learns a start rate for its next call).
     * A GATT call write blocks for its own transmission. An RFCOMM/CoC write returns at once, but
     * the call flushes every loop and LocalSocket.flush polls TIOCOUTQ every 10 ms until the stack
     * has read the socket buffer, so only the RFCOMM port queue (10 frames of up to 990 B, about
     * 10 KB, PORT_TX_HIGH_WM) plus L2CAP/controller buffers can hide below the app. Pacing keeps
     * that part in the app queue too. The tradeoff: the call's bounded queue can fill sooner, which
     * drops video until a keyframe, as it can on GATT. DEBUG launch extra `test_call_link_pacing`
     * (benchmark tunable `call_link_pacing`). Needs [linkPacing].
     */
    @Volatile var callLinkPacing: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Classic pages a scanned BLE address even when Android 15+ reports it random (the control arm
     * of classicScanAddressIsRandom). DEBUG launch extra `test_classic_page_random_address`.
     */
    @Volatile var classicPageRandomAddress: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Seeds a child link's pacer at join (learned capacity or a conservative prior) so it is paced
     * before the child's first arrival report; DEBUG A/B runs can turn it off. Needs [linkPacing].
     */
    @Volatile var linkPacerSeed: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): a child link's pacer seed is the child's own last arrival report for this
     * link (LinkCapacity.peerLinkKbps, 24 h) instead of this phone's best single-link probe. DEBUG
     * launch extra `test_link_pacer_peer_seed`. Needs [linkPacerSeed].
     */
    @Volatile var experimentalPeerLinkSeed: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Releases a starved fan-out child early when the encoder is at its rate floor and the child's
     * backlog keeps growing (see FanOutStarvationPolicy); DEBUG A/B runs can turn it off.
     */
    @Volatile var fanOutEarlyRelease: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Delivery reports in calls (link report version 3, on by default since 2026-10-09). DEBUG A/B
     * runs can turn them off (`test_delivery_reports=false`, benchmark tunable `delivery_reports`) to
     * restore the pre-2026-10-09 behaviour exactly: no version-3 hello or report is sent, received
     * delivery is ignored, rate control uses the old references (arrival trains, bytes the stack
     * accepted), and every cut is remembered again (calls and streams). Read when a call or stream
     * starts. A/B arms should also pass `test_reset_learned_rates` so both start from the same memory.
     */
    @Volatile var deliveryReports: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Learned call start (on by default since 2026-10-09; see CallStartRate). Learned sustained rates
     * survive app updates (format 2, 24 h), a call with no learned rate starts at a share of the
     * measured link or 400 kbps instead of the request, and the start applies before the first frame.
     * DEBUG A/B runs can turn it off (`test_start_rate_guard=false`, benchmark tunable
     * `start_rate_guard`): build-keyed rates, unmeasured calls start at the request. The control arm
     * `test_delivery_reports=false` also turns it off. Read when a call or stream starts.
     */
    @Volatile var startRateGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Fraction of the encoder target a hardware encoder gets for one app-requested keyframe (startup,
     * child join, recovery), restored after that keyframe. Hardware VP9 keyframes were 55-135 KB at
     * 300-600 kbps (1.5-2.2 s of the stream) and ignored QP bounds; null leaves keyframes unshaped.
     */
    @Volatile var keyFrameShaping: Double? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Shared LE CoC listeners (CocListeners): null = DeviceFamilyPolicy.leCocServerLeaks, so only
     * Android 10 stacks, which never stop a closed LE CoC server, keep one long-lived listener per
     * role; fixed stacks open and close one per session. DEBUG launch extra
     * `test_coc_shared_listener=true|false` forces either behaviour on any phone.
     */
    @Volatile var sharedCocListener: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * The low-latency -> balanced advertising step changes the parameters of the running advertising
     * set (StableAdvertisingSet), so its random address stays the one peers just discovered. DEBUG
     * launch extra `test_stable_adv_address=false` restores stop + restart (a new address).
     */
    @Volatile var stableAdvertisingAddress: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Deferred GATT client close (GattClientTeardown): null = the stack-family default (deferred on
     * Android 11 and older, whose GattService ContextMap races an immediate close). DEBUG launch extra
     * `test_deferred_gatt_close=true|false` forces it on or off on any phone.
     */
    /**
     * Call/stream audio after a sender gap (mute, push-to-talk release) starts on its playout target
     * behind written silence (OpusAudioDecoder.alignAfterSenderGap). DEBUG launch extra
     * `test_audio_gap_align=false` is the control arm: the spurt plays as soon as it is written.
     */
    @Volatile var audioGapAlignment: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): voice-only calls use the short CALL_VOICE playout delay (80 ms, growing to
     * 400 ms on late audio) instead of the video-call 250 ms start. Constrained (batched) links keep
     * their longer delay. DEBUG launch extra `test_voice_call_low_delay`.
     */
    @Volatile var voiceCallLowDelay: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off): call audio keeps its own playout clock, grown only by late audio, instead of
     * sharing the video delay. Video calls held audio at 600 ms (calls-noloss-*, bmaraw-ab-call-*:
     * the shared delay grew by 350 ms for video bursts while audio itself arrived on time); with this
     * on, speech can lead the picture by up to that growth (lip sync traded for conversational delay).
     * DEBUG launch extra `test_call_audio_own_clock`.
     */
    @Volatile var callAudioOwnClock: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * LE CoC client on a fresh ACL (BleCocClient, DeviceFamilyPolicy.LE_COC_EATT_STRANDED_CHANNELS):
     * null = the stack-family default (Android 12/13). DEBUG launch extra
     * `test_coc_fresh_acl=true|false` forces it on or off on any phone.
     */
    /**
     * Private-call hang-up close waits for the peer to close the link, capped at 3 s
     * (privateCallCloseWait). DEBUG launch extra `test_call_close_wait_peer=false` is the control arm:
     * the previous fixed 400 ms drain.
     */
    /**
     * Relay child re-anchoring (default on): a relay whose child link is starved (under 50 kbps for 2 s
     * with media waiting) reconnects that child on a fresh connection, at most twice a minute, before
     * the withdrawal policy decides. See RelayChildReanchor.kt. DEBUG launch extra
     * `test_relay_child_reanchor=false` is the control arm.
     */
    @Volatile var relayChildReanchor: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * After a re-anchor-eligible link loss the child waits up to PREFERRED_PARENT_GRACE_MS (2 s) for
     * its previous relay's offer before reconnecting elsewhere (RelayChildReanchor.kt). Default on;
     * DEBUG launch extra `test_relay_recovery_grace=false` is the control arm (first offer heard wins).
     */
    @Volatile var relayRecoveryGrace: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * A relayed child also closes a parent link that trickles under 1/8 of the stream bitrate for a
     * whole 5 s window ([RelayParentTrickle]; mesh4-8211dc8f 0001: ~12 kbps for 17 s, never silent).
     * Default on; DEBUG launch extra `test_relay_parent_trickle=false` is the control arm.
     */
    @Volatile var relayParentTrickle: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    @Volatile var callCloseWaitsForPeer: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    @Volatile var cocFreshAcl: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    @Volatile var deferredGattClose: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Re-issue a central's connection-priority request after the stack reports the LE connection
     * update failed (LeConnectionPriorityKeeper). DEBUG launch extra `test_le_priority_retry=false`
     * is the control arm.
     */
    @Volatile var lePriorityRetry: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * LE CoC control links request the preferred PHY from the client only, after the priority update
     * and MTU exchange (BleL2capPsmReader), and the control server only reads it. DEBUG launch extra
     * `test_coc_sequenced_phy=false` restores the old order: both sides request the PHY at connection,
     * beside the priority update (status 42 collisions on M31/ASUS/A15s).
     */
    @Volatile var cocSequencedPhy: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default off; A/B arm): an LE CoC central requests the preferred PHY only after the
     * CoC socket is connected and hop authentication has finished, and waits for onPhyUpdate (readPhy
     * fallback after 1 s) before the end-to-end handshake and media ([CocPhyAfterConnect]); the control
     * server never requests it. DEBUG launch extra `test_coc_phy_after_connect=true` turns it on.
     */
    @Volatile var cocPhyAfterConnect: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): one deterministic initiator per direct call pair ([CocSingleInitiator]).
     * The phone with the higher node-ID prefix holds an outgoing direct call for 1.5 s and yields when
     * the peer's call reaches it first. DEBUG launch extra `test_coc_single_initiator=true` turns it on.
     */
    @Volatile var cocSingleInitiator: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): peers tracked by node-ID prefix ([PeerAddressBook]). Every accepted
     * scan result updates its node's freshest address; a direct call to an address older than 10 s or
     * replaced by a newer one rescans for the node first, and connect failures are classified by elapsed
     * time ([LeConnectFailureClassifier]). DEBUG launch extra `test_peer_address_book=true` turns it on.
     */
    @Volatile var peerAddressBook: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): on stacks the `android-11-coc-small-sdu-coalescing` proposal matches
     * (API <= 30), CoC media SDUs are coalesced up to [COC_COALESCED_SDU_BYTES] instead of 245-byte
     * single-PDU SDUs ([cocWriteChunkBytes]). DEBUG launch extra `test_coc_coalesce_sdus=true` turns it on.
     */
    @Volatile var cocCoalesceSdus: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Adaptive LE PHY (default on): a central moves a backlogged 2M link that carries at most about one
     * data PDU per connection event to 1M ([AdaptivePhyPolicy]). DEBUG launch extra
     * `test_adaptive_phy=false` is the control arm (2M kept on every link, as before 2026-10-09).
     */
    /**
     * DEBUG experiment (off = null = HIGH): connection priority a relay requests on its CoC child link
     * (`test_forwarder_coc_priority` = balanced|low|dck|high), to test interleaving of a relay's
     * peripheral (parent) and central (child) connections.
     */
    /** DEBUG experiment (off = null): relay child-link interval in 1.25 ms units ([LeChildInterval]). */
    @Volatile var forwarderChildIntervalUnits: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 6..40 }
        }

    @Volatile var forwarderCocPriority: BleConnectionPriority? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /** Adaptive PHY ignores relays' child links ([RelayUpstream], default on); `test_relay_phy_guard=false`. */
    /**
     * Experimental (off): relay child-link re-anchor ([RelayReanchor]); DEBUG `test_relay_reanchor=true`.
     * Not yet proven: in relaylink-arms3 it never fired before the relay's stall release reconnected.
     */
    @Volatile var relayReanchor: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    @Volatile var relayPhyGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Under the relay PHY guard, a relay's child link that measures in the one-PDU-per-event band
     * (at least half of one PDU per event, [relayOnePduBand]) still moves to 1M, without recording
     * bad-2M memory; the starved scheduling band (4-37 kbps at 15 ms) stays untouched. Experimental,
     * default off (mesh4-b9726176-loop4-1 0004: the relay's own receive fell 283 -> ~200 kbps);
     * DEBUG launch extra `test_relay_one_pdu_move=true` turns it on.
     */
    @Volatile var relayOnePduMove: Boolean = false
        set(value) {
            // Default off: release must stay off too (the default-on setter `value || !DEBUG`
            // turned it on in release when MainActivity assigned the false default).
            field = value && BuildConfig.DEBUG
        }

    /**
     * Relay cold start (default on): a viewer with no relay evidence (relay proven 0, no fresh relay
     * ceiling) counts the incoming link it is already carrying and may forward to ONE child, the same
     * "one link beyond what is proven" rule fan-out uses. Without it a fresh install (no probe: prior
     * 700 kbps) never relays a 300 kbps stream (need 807) until it has been a source
     * (mesh4-c0200edf-loop7-1 0001/0005). Rollback stays with the receiver-node health detach and the
     * congestion withdrawal, which record a relay ceiling. DEBUG `test_relay_cold_start=false` is the
     * control arm. Line: `BENCHMARK relay_admission`.
     */
    /**
     * Relay parent fallback on link-establishment failure ([EstablishmentFailureTracker], default on):
     * two establishment failures within 2 min mark the peer for 5 min. A child ranks the marked parent
     * last and a relay stops connecting to a marked child. DEBUG `test_relay_establish_fallback=false`
     * is the control arm. Line: `BENCHMARK relay_parent_fallback`.
     */
    @Volatile var relayEstablishFallback: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    @Volatile var relayColdStart: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Fan-out growth compares proven load and planned links in one unit ([LinkCapacityPolicy.fanOutProvenScale]):
     * proven wire kbps x 1.2 + one planned link. Default on; DEBUG `test_fanout_proven_links=false`
     * is the control arm (raw proven + planned link, which capped a 300 kbps source at 2 children).
     * Line: `BENCHMARK fanout_admission`.
     */
    @Volatile var fanoutProvenLinks: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): learned-limit recovery. A fresh relay/fan-out ceiling may admit one link
     * beyond it after a cool-down ([LinkCapacityPolicy.ceilingProbeOpen], 2 min doubling per failed
     * probe, at most 64 min); healthy traffic above the ceiling clears it (existing rule), a new
     * saturation re-records it and doubles the cool-down. Bad-2M marks age out after 24 h instead of
     * 7 days unless re-confirmed. DEBUG `test_ceiling_probe_recovery=true`. Line: `BENCHMARK ceiling_probe`.
     */
    @Volatile var ceilingProbeRecovery: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    @Volatile var adaptivePhy: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Per-peer bad-2M memory and the one bounded 1M call-setup retry ([AdaptivePhyMemory], default on).
     * DEBUG launch extra `test_adaptive_phy_memory=false` is the control arm.
     */
    @Volatile var adaptivePhyMemory: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * 2M call-setup stall guard (default on, part of the bounded 1M setup retry): the first 2M LE call
     * link is closed when its hop authentication has not finished [AdaptivePhyMemory.SETUP_STALL_MS]
     * after it opened, and the 1M retry of a direct offer scans for the peer's current advertisement (it
     * re-advertises under a new random address) instead of reconnecting to the old one; a relayed offer
     * keeps its original relay (requested parent). DEBUG launch extra
     * `test_phy_setup_stall=false` is the control arm (retry only after the link dies, to the old address).
     */
    @Volatile var phySetupStallGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * LE CoC SDUs of at most 245 bytes, one 251-octet data PDU each ([cocWriteChunkBytes], default on).
     * DEBUG launch extra `test_coc_single_pdu_sdus=false` is the control arm (SDUs up to the socket limit).
     */
    @Volatile var cocSinglePduSdus: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): LE links request another PHY instead of 2M (DEBUG `test_ble_phy=1m`,
     * `coded-s2` or `coded-s8`; see [ExperimentalBlePhy]). 1M is a control for the ASUS <-> A15s
     * ~65 kbps link; Coded is a long-range option for low-rate (voice) links. 2M stays the only
     * user-selectable PHY.
     */
    @Volatile var experimentalBlePhy: ExperimentalBlePhy? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /** The 1M control arm of [experimentalBlePhy] (kept for its existing callers and tests). */
    var experimentalBle1mPhy: Boolean
        get() = experimentalBlePhy == ExperimentalBlePhy.LE_1M
        set(value) {
            experimentalBlePhy = if (value) ExperimentalBlePhy.LE_1M else null
        }

    /**
     * Experimental (off): ATT MTU the GATT video client requests (`test_gatt_request_mtu`, 23-517);
     * null keeps 517. A value of 247 keeps each write within one 251-byte data-length PDU.
     */
    @Volatile var gattRequestMtu: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 23..517 }
        }

    /**
     * Experimental scene-cut shaping (workstream C; off by default). A raw-frame luma detector finds
     * a hard cut before the frame reaches a MediaCodec encoder; SYNC requests that frame as a sync
     * frame at a lowered target, TARGET only lowers the target, and the target is restored once the
     * frame is encoded (SceneCutShaper). Only raw-fixture and ByteBuffer camera input pass the app;
     * live Camera2 surface input does not. Off: nothing is requested; benchmark traces still log the
     * detector's decisions (BENCHMARK scene_cut). DEBUG launch extra `test_scene_cut_shaping`
     * (0 off, 1 sync, 2 target; benchmark tunable `scene_cut_shaping`).
     */
    @Volatile var sceneCutShaping: SceneCutShapingMode = SceneCutShapingMode.OFF
        set(value) {
            field = if (BuildConfig.DEBUG) value else SceneCutShapingMode.OFF
        }

    /**
     * Experimental VP9 quantizer headroom (off by default): while the parsed base_q_idx stays below
     * this floor, the encoder target is lowered so a scene cut is not coded at a stale low
     * quantizer (QuantizerHeadroomGovernor). Null leaves the target alone. DEBUG launch extra
     * `test_vp9_q_floor` (1-254; benchmark tunable `vp9_q_floor`).
     */
    @Volatile var vp9QuantizerFloor: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 1..254 }
        }

    /**
     * Experimental encoder ranking by measured behaviour (a design decision; off by
     * default). On: an encoder whose recorded sessions kept scene-cut frames within about 0.5 s of
     * its own stream (and, for software, encode time within budget) ranks above one measured to
     * burst, e.g. the M31's libaom AV1 (26 KB cuts) above its OMX VP9 (135 KB). Off: the static
     * hardware-first order. DEBUG launch extra `test_encoder_ranking` (benchmark tunable
     * `encoder_ranking`); measurements are recorded in both arms.
     */
    @Volatile var experimentalEncoderRanking: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off): GATT writes and notifications carry values sized to fill whole 251-octet
     * LE data PDUs (495 bytes instead of 514 at MTU 517; see gattValueBytes). DEBUG launch extra
     * `test_gatt_aligned_writes`. Read when a GATT link opens.
     */
    @Volatile var experimentalAlignedGattWrites: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental burst-aware drain (off by default): a child's lateness that follows a large frame
     * while this link now carries everything offered is attributed to that one burst instead of
     * congestion, and large-frame grace lasts while the backlog keeps shrinking. A single 72 KB
     * scene cut otherwise cut M31->ASUS GATT 300 to 206 kbps for 12 s with an empty sender queue
     * (regress-57484598 case 0006). DEBUG launch extra `test_burst_drain` (benchmark tunable
     * `burst_drain`).
     */
    @Volatile var experimentalBurstDrain: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental one-step playout growth (lane-b P2, off by default): media later than
     * MediaPlayoutClock.BURST_LATENESS_MS grows the adaptive delay by this multiple of its lateness
     * at once, instead of lateness + 50 ms per late frame (a staircase of separate holds during one
     * burst). Costs a longer first hold and more held latency. Null keeps the staircase. DEBUG
     * launch extra `test_playout_burst_growth` (1.0-4.0; benchmark tunable `playout_burst_growth`).
     */
    @Volatile var playoutBurstGrowth: Double? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 1.0..4.0 }
        }

    /**
     * Experimental hardware intra refresh (PLAN workstream C, off by default): MediaFormat
     * intra-refresh-period in frames at configure. Whether the encoder applied it is logged from its
     * output format (BENCHMARK encoder_capabilities). Null leaves periodic keyframes. DEBUG launch
     * extra `test_intra_refresh_frames` (benchmark tunable `intra_refresh_frames`).
     */
    @Volatile var intraRefreshFrames: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 2..600 }
        }

    /**
     * Default-on measured bitrate adjuster for MediaCodec encoders (EncoderBitrateAdjuster, a port of
     * WebRTC's DynamicBitrateAdjuster); DEBUG extra `test_encoder_bitrate_adjuster=false` (benchmark
     * tunable `encoder_bitrate_adjuster`) is the control arm, which restores the output-rate trim.
     */
    @Volatile var encoderBitrateAdjuster: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Device-family policy entries turned off for this run (DevicePolicyRegistry IDs, or "all").
     * DEBUG launch extra `test_device_policy_off=id1,id2`; release builds always keep every entry.
     */
    @Volatile var devicePolicyOff: Set<String> = emptySet()
        set(value) {
            field = if (BuildConfig.DEBUG) value else emptySet()
        }

    /**
     * Experimental (off): WebRTC FramerateBitrateAdjuster for OMX.Exynos. VP9/H.264 encoders
     * (CodecPolicy.EXYNOS_FRAMERATE_ADJUSTER_ARM) instead of the measured bitrate adjuster. DEBUG extra
     * `test_encoder_framerate_adjuster=true` (benchmark tunable `encoder_framerate_adjuster`).
     */
    @Volatile var encoderFramerateAdjuster: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * True for a DEBUG launch carrying any `test_*` extra (benchmark runner or manual test launch).
     * Policies meant for user sessions only (decoder-init-fallback) keep the former behaviour then,
     * so a case measures what it requested.
     */
    @Volatile var benchmarkLaunch: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Automatic encoder output-rate trim; DEBUG A/B runs can turn it off. */
    @Volatile var encoderRateTrim: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental temporal layering (workstream A; off by default). Removed on 2026-10-07 because
     * it drops frames, restored on 2026-10-08 because experiments stay in the code, off by default.
     * On: the encoder makes every other frame refresh nothing while LayeringPolicy sees a tight link
     * (native libaom AV1; MediaCodec only with [hardwareTemporalLayers]), frames are marked
     * discardable only when their bitstream proves it (BitstreamDroppability), sender, relay, call
     * and decode queues may skip those frames when behind (TemporalLayerThinning), and admission may
     * plan extra links at the measured base layer. Off: no layer control reaches the encoder, no
     * frame carries the discardable flag, no queue thins and admission is unchanged.
     *
     * Set from the DEBUG launch extra `test_temporal_layers` (benchmark tunable `temporal_layers`) or
     * the persisted Advanced setting "Experimental: temporal layering"; read when a stream starts.
     */
    @Volatile var experimentalTemporalLayers: Boolean = false

    /**
     * With [experimentalTemporalLayers] on, also ask MediaCodec encoders for two temporal layers
     * (DEBUG only, launch extra `test_hardware_temporal_layers`). Off: on the M31 OMX VP9 encoder the
     * error-resilient layered stream lost ~11 VMAF at the same rate (pacing-ab-c777b381) and burst
     * harder. Frames an encoder makes droppable on its own are still verified and used.
     */
    @Volatile var hardwareTemporalLayers: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental call video fragments (off by default; see CallVideoFragments). On at both
     * endpoints of a direct call: video frames above 3 KB travel as authenticated 2 KB fragments and
     * queued call audio is written between them instead of waiting behind the whole frame (GATT
     * audio waited 620-1193 ms behind frames, calls-noloss-m31asus-51cc8028). Off: the hello carries
     * no fragment bit and the wire is unchanged. Launch extra `test_call_video_fragments`
     * (benchmark tunable `call_video_fragments`); read when the call endpoint is created.
     */
    @Volatile var callVideoFragments: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental speech-led call playout (off by default; see CallPlayoutPolicy). On: late call
     * video may grow the shared call playout delay by at most 150 ms; late audio keeps the full call
     * range. Video beyond that plays late instead of delaying speech (every GATT call pinned the
     * shared delay at its 600 ms maximum, calls-noloss-*-51cc8028). Off: unchanged. Launch extra
     * `test_call_speech_led_playout` (benchmark tunable `call_speech_led_playout`).
     */
    @Volatile var callSpeechLedPlayout: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Default-on fix (2026-10-09): call audio has its own room in the call send queue, so audio
     * waiting behind one long video write no longer fills the video budget and drops every queued
     * frame (privateCallAudioHeadroomPackets). Control arm: launch extra
     * `test_call_audio_headroom=false` (benchmark tunable `call_audio_headroom`). Release builds
     * always keep the fix.
     */
    @Volatile var callAudioHeadroom: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default-on fix (2026-10-09): a legacy Qualcomm phone's end-to-end call hello lists its real
     * decoders plus a signed VP9 preference instead of VP9 only (privateCallEndpointDecodeMask);
     * before, such calls failed ("Peer cannot decode any locally available call codec"). Control
     * arm: launch extra `test_call_codec_fallback=false` (benchmark tunable `call_codec_fallback`).
     */
    @Volatile var callCodecFallback: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental legacy low delay (off by default; see privateCallLegacyLowDelay): direct calls on
     * a legacy-constrained route send audio in 40 ms pairs instead of 400 ms batches and play with
     * the normal 250-600 ms call delay instead of 500-800 ms. Launch extra
     * `test_call_legacy_low_delay` (benchmark tunable `call_legacy_low_delay`).
     */
    @Volatile var callLegacyLowDelay: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental legacy relief (off by default; see privateCallStackFingerprint): no legacy
     * Qualcomm call protection applies on this phone (geometry, bitrate and audio caps, VP9-only
     * codec rules, software VP9 decoder, audio batching, flush interval, constrained playout), and
     * it advertises its normal receive limits. Launch extra `test_call_legacy_relief` (benchmark
     * tunable `call_legacy_relief`).
     */
    @Volatile var callLegacyRelief: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Private-call relay chains (on by default, 2026-10-09; see PrivateCallRelayChain): a call relay
     * may take another relay's offer as its child (caller -> R1 -> ... -> callee, at most
     * PrivateCallRelayChain.MAX_RELAYS relays). Control arm: launch extra
     * `test_call_relay_chain=false` (benchmark tunable `call_relay_chain`) keeps the one-relay rule
     * exactly (a relay's child must authenticate as the announced callee). The call relay itself is
     * DEBUG-only.
     */
    @Volatile var callRelayChain: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental measured relayed-call cap (off by default; see privateCallVideoSettingsForRoute):
     * a relayed call's video bitrate cap is this phone's fresh measured link x 0.2125 less audio
     * (a relay's one radio carries both adjacent links, each in both directions), never below the
     * legacy 110 kbps; FPS stays at most 15. Off: the legacy 15 FPS / 110 kbps cap. Launch extra
     * `test_relayed_call_measured_cap` (benchmark tunable `relayed_call_measured_cap`); read when the
     * call endpoint is created.
     */
    @Volatile var relayedCallMeasuredCap: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental lock-screen call presentation (off by default; see CallLockScreen). Launch extra
     * `test_call_lock_screen` (benchmark tunable `call_lock_screen`).
     */
    @Volatile var callLockScreen: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental first call frame shown on arrival (off by default): the first decoded frame of
     * the peer's call video is fed and shown at once instead of waiting for its 250-500 ms playout
     * target; later frames keep the shared timeline. Launch extra `test_call_first_frame_now`
     * (benchmark tunable `call_first_frame_now`).
     */
    @Volatile var callFirstFrameNow: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental call encoder fit (off by default; see CallEncoderFit): a call geometry at which
     * this phone's native encoder stayed over budget at its fastest settings is remembered, and later
     * calls start one supported size smaller at the same frame rate. Launch extra
     * `test_call_encoder_fit` (benchmark tunable `call_encoder_fit`); live camera only.
     */
    @Volatile var callEncoderFit: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental call audio starvation concealment (off by default, 2026-10-10; see
     * AudioConcealment): when a call's jitter buffer runs dry the playout writes a short
     * pitch-repeat fade into comfort noise instead of letting the AudioTrack underrun, cross-fades
     * into the next real audio, and speeds playout up only during silence. Launch extra
     * `test_call_audio_conceal` (benchmark tunable `call_audio_conceal`); read when a call's audio
     * decoder starts. Line: `BENCHMARK audio_conceal`.
     */
    @Volatile var callAudioConceal: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental native libopus call audio (off; README register "Native libopus call audio"):
     * calls encode with OPUS_APPLICATION_VOIP and in-band FEC, and decode with libopus PLC on a gap
     * plus FEC recovery from the next packet, instead of MediaCodec (c2.android.opus has neither).
     * Same Opus bitstream; no wire change. DEBUG launch extra `test_call_native_opus` (benchmark
     * tunable `call_native_opus`); read when a call's encoder or decoder starts. Streams unchanged.
     */
    @Volatile var callNativeOpus: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Native call audio sub-switch (`test_call_native_opus_fec`, default on): in-band FEC. */
    @Volatile var callNativeOpusFec: Boolean = true

    /** Native call audio expected-loss hint in percent (`test_call_native_opus_loss_pct`, default 10). */
    @Volatile var callNativeOpusLossPercent: Int = NativeOpusSettings.DEFAULT_LOSS_PERCENT
        set(value) {
            field = value.coerceIn(0, 100)
        }

    /**
     * Native call audio DTX (`test_call_native_opus_dtx`, default off): silent frames are not sent.
     * Changes the packet cadence (receivers see gaps), so it needs its own A/B before any promotion.
     */
    @Volatile var callNativeOpusDtx: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    internal fun nativeOpusSettings(): NativeOpusSettings =
        NativeOpusSettings(fec = callNativeOpusFec, lossPercent = callNativeOpusLossPercent, dtx = callNativeOpusDtx)
}
