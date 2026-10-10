# Roadmap

This file lists unfinished work and open design questions. Current behaviour is described in
[../README.md](../README.md) and [ARCHITECTURE.md](ARCHITECTURE.md); the wire format and security rules are in
[PROTOCOL.md](PROTOCOL.md); acceptance gates and how to measure are in [TESTING.md](TESTING.md); measured reasons behind
existing policies are in [DECISIONS.md](DECISIONS.md). When an item is finished, its description moves to the document
that owns it and the item is removed.

Two labels are used:

- **Design review required**: the item changes the protocol, the security model or what a phone reveals over the air.
  It needs a written design in PROTOCOL.md and an independent review before code ships, and it ships behind a
  default-off flag (see the register of experimental behaviour in ARCHITECTURE.md).
- **Experimental**: coded behind a default-off switch and not yet validated on phones.

Nothing here is a promise or a schedule. Contributions are welcome on any item; open an issue first so the approach can
be agreed (see [../CONTRIBUTING.md](../CONTRIBUTING.md)).

## Priorities at a glance

| Priority | Area | Status | Design review |
| --- | --- | --- | --- |
| 1 | [Open defects in shipped behaviour](#open-defects-in-shipped-behaviour) | Open | No |
| 2 | [Policy work: fewer fixed limits](#policy-work-fewer-fixed-limits) | In progress | No |
| 3 | [Discovery privacy](#discovery-privacy) (rotating IDs) | Phase 1 coded, Experimental | Yes |
| 4 | [Intercom core and product direction](#intercom-core-and-product-direction) | Partly coded, Experimental | Parts |
| 5 | [Design A: store-and-forward messages](#design-a-store-and-forward-messages-delay-tolerant) | v1 coded, JVM-tested only | Yes |
| 6 | [Design B: group calls](#design-b-group-calls-3-6-people-and-group-push-to-talk) | Design only | Yes |
| 7 | [Bluetooth headsets](#bluetooth-headsets) | Deliberately excluded; open question | No |
| 8 | [Distribution and field reports](#distribution-and-field-reports) | In progress | No |
| 9 | [iPhone interoperability](#iphone-interoperability) | Compact discovery coded, Experimental | Yes |
| 10 | [Wi-Fi Direct / Aware bearer](#wi-fi-direct--aware-bearer-later) | Not started | Yes |
| 11 | [Measurement adapters still missing](#measurement-adapters-still-missing) | Open | No |
| 12 | [Platform and product work](#platform-and-product-work) | Open | Parts |

## Open defects in shipped behaviour

These are open across every phone combination that uses the affected behaviour. The benchmark suite's case state (see
[TESTING.md](TESTING.md)) owns pass and fail history and retest requirements.

| Work | What must be established before closing it |
| --- | --- |
| Uneven playback and picture quality | Separate source pacing, encoder output, Bluetooth stalls, receive queues, decoder processing and presentation. Correlate traces with run, session and PID identity and each phone's clock; compare the same input and settings. Frame-timing proxies and short clean prefixes cannot approve screen smoothness. The retained scheduler analysis has initial-only clock snapshots, so obtain the missing stage and clock evidence before promoting another display policy |
| Connecting/Recovering flashes and reconnect-to-scan loops | Trace each setup, scan, cancellation, disconnect and completion against its owning attempt and session. Establish a stable UI state, bounded retries, truthful discovery and reusable availability after End, loss and cancellation; keep planned losses distinct from unexpected ones |
| Real three-phone chains | Diagnose the retained decrypt failures, missing completions and guard withdrawals without assuming that event order across different phone clocks proves the cause. Preserve the authenticated original source, requested immediate parent, ordered encryption, admission and overload protection; direct-source fallback fails the requested chain |
| Bidirectional calls and reverse setup | Resolve the reverse-caller GATT setup that queued MTU work without completing it; trace request, callback, teardown and stale-session ownership. Native inputs reject a negotiated geometry or FPS mismatch before media starts, so prepare exact matching native call requests or report ineligibility. Improve both directions, local controls, framing and recovery independently |
| Requested versus effective settings | Use the register of concrete limits and the capability evidence to challenge call budgets, legacy protections and other limits. DEBUG higher-rate comparisons stay experiments. Expose every difference in encoder, allocation, geometry, rate, FPS, audio and buffer rather than silently substituting settings |

## Policy work: fewer fixed limits

Fixed numbers (1100/800/700/500/400 kbps ceilings, 20% margins, 5 s keyframes) were wrong for some phone or link every
time, and fixes kept circling the same freezes. Each workstream removes a root cause instead of tuning a constant; each
step freezes a build, records what broke, what changed and the evidence in [DECISIONS.md](DECISIONS.md), and is
compared on the same coverage cases.

| Workstream | Root cause removed | Design | Done when |
| --- | --- | --- | --- |
| A. No frame loss (the default; under A/B against bounded media age, below) | Every congestion response dropped frames: a queue overflow dropped all queued video and waited for a recovery keyframe (the largest frame), overloading the same link again; temporal-layer thinning cost about 2.5 VMAF | Latency is the elastic resource: ordered sender and relay queues, receiver queues sized for the adaptive delay with backpressure, late media played, audio kept on the clock by a small pitch-preserving speed change; a relay child that stays far behind is withdrawn so it can reroute (hop-local), never thinned | No queue drops; freezes and audio drops at or below the previous builds at the same settings; latency inside the playback profile |
| B. Self-measured links | Link capacity differs per model, peer, Wi-Fi state and concurrent links; no API exposes it | Every large record is a packet train: the receiver times its arrival and reports the estimate on the control channel; GATT senders also time large writes; estimates feed the learned per-phone, per-peer capacity | Estimates match probe maxima within about 15% and track Wi-Fi and concurrency changes |
| C. Burst-free encoding | Keyframes of 40-136 KB need over a second of airtime on any Bluetooth link | Native AV1 caps frame size relative to the measured link; hardware encoders use intra-refresh where supported, keeping full keyframes only for joins | No scheduled frame above about 250 ms of measured link time |
| D. No fixed limits left | Picker maxima and priors are constants | The bitrate control shows the measured link estimate; priors become first measurements | Remaining constants are bounded fallbacks used only before the first measurement |

Status (build `454cf485`, 2026-10-07, plus later notes):

- **A.** No media is dropped for streams; native AV1 pairs showed 0 freezes and 0 audio drops up to a CoC offer 1200
  kbps above the link (measured 2026-10-07). Open: the policy for calls beyond the link's capacity (lower call frame
  rate, longer call delay, or the current video skip) and visible-FPS dips while an overloaded link's delay grows.
- **B.** Hop-local 6-byte reports (arrival rate, pinned delay, added delay); learned ceilings persist with a 6-hour
  re-check.
- **C.** Native inter frames are capped at 250 ms of the per-viewer link share. The M31's OMX VP9 encoder ignores
  video-QP bounds and offers only VBR, and its large frames are four-frame scene-change bursts (about 210 KB), so
  keyframe shaping (a DEBUG A/B) is not promoted. Next: forced CBR on the M31 (DEBUG `test_bitrate_mode`) and the
  15-20 s encoder trim, judged by same-build VMAF.
- **Relay child joins.** Cause found: the joining child's keyframe request crosses the parent link exactly when it
  loses airtime to the new child link (S24 VP9 keyframe of 55.7 KB, link 791 -> 269 kbps, a 1.7 s gap). Options: start
  the child from the relay's next natural keyframe, or grow the relay's playout delay before it connects a child.
- **D.** The picker uses the measured link (fixed maxima are fallbacks). Still to replace: the 20% per-link planning
  margin and the 0.9 ceiling headroom.

## Intercom core and product direction

Intercom (voice, push-to-talk) and live video are equal parts of the product; the reasoning is in
[DECISIONS.md](DECISIONS.md) under "Product direction".

| Work | Done when |
| --- | --- |
| Bounded media-age experiment (**Experimental**, default off) | Calls keep speech within about 300-400 ms by lowering bitrate or resolution, dropping codec-safe non-reference frames, then going audio-only; streams buffer up to about 2 s, then shed or resync while keeping authenticated order. A randomized A/B against no-loss (M31 <-> ASUS, S24 <-> A15s, both directions, 150-1200 kbps with bursts) reports media age, stall length, frozen share and speech continuity, and the maintainers choose the default from it. No-loss stays the default meanwhile |
| User-centred gates | The `device-run-v4-user` profile in [TESTING.md](TESTING.md) is built around media age, longest stall, frozen share, audio continuity and task success. Remaining: a decided media-age target, and adapters for task success and speech comprehension |
| Intercom core | Reliable 1:1 voice and push-to-talk in small groups, text, photos and location with time and accuracy; screen-off operation and reconnect proven on phones. Later: store-and-forward voice and video messages (Design A) |
| Signing mode | A legibility-first video profile (at least 20 FPS target for hands and faces), validated with fluent signers before any accessibility claim |

Interim bounded-age evidence (not conclusive; repeats were still running): with the A15s sending over a weak
libaom/AV1 link, the bounded arm paused video one to three times per call (longest stall 3.8-9.2 s, 26-58% frozen,
against 0.4-2.7 s and 3-18% for no-loss) and did not improve speech; on M31 <-> ASUS calls at 1000 kbps it was worse
(CoC held-age p95 1479 against 1108 ms) or had about twice the freezes (GATT); S24-sending directions and M31 <-> ASUS
streams were about equal. Likely cause: the call arm assumes a 500 ms peer base delay (a 100 ms queue budget) and
requested keyframes re-create bursts. Current recommendation: keep no-loss as the default and bounded age as an
off-by-default experiment; a fairer variant would exchange the playback profile in call setup, make audio-only the last
resort and recover with intra-refresh or the next natural keyframe.

## Discovery privacy

**Design review required.** Today a phone advertises a stable 48-bit identity prefix, so a passive scanner can follow it
between places. The goal: discovery stops broadcasting a stable prefix, using contact-scoped rotating identifiers and
explicit discoverability windows. In-person contact verification (QR code or safety code; the verification API exists
and its UI binding is still to come) and key-change warnings come before the app is positioned for at-risk users.

Status: the design was reviewed on 2026-10-10 (review findings RV1-RV19). Phase 1 for LE call-ready presence, the
Visible-by-handle window and the phase 2 formats (presence leases, `BDX1`, the recognition table; not sent yet) are
coded behind `test_rotating_ids` ("Experimental: rotating discovery IDs"); formats and vectors are in PROTOCOL.md
under "Experimental rotating discovery IDs". Not yet tested on phones. Remaining, in order:

0. **Phase 0 measurements** on every reference phone (`tools/rotid_phase0.py`): does an advertising-set restart change
   the address; are the GAP Device Name and PSM characteristic readable by an unpaired central; do SCAN_REQ and
   CONNECT_IND addresses equal a set address; which OS beacons rotate when; do established links survive a set stop.
   On iOS: does a stop and start change the iPhone address (RV17).
1. **Phone cases for phase 1:** discovery latency and call setup equal to switch-off; boundaries while idle and during
   a call; a passive-tracker scanner sees zero address or ID carry-over across boundaries (RV6); logged boundary
   offsets between phones (RV12); restarts inside an epoch keep the epoch ID (RV4).
2. **Streams and viewers on version 5 (phase 1b):** role 1 and 2 rotating IDs, stream token grace (60 s),
   admission-key translation, the recovery hint with `Khint` (RV18), forwarders and relay-chain mirroring.
3. **Wire contacts with presence leases (phase 2):** mutual verified contacts only, with a per-contact switch "Can see
   when I'm nearby" on by default (RV11); a store sealed with the verified-contacts Keystore key; an unconfirmed "seen
   nearby" line without a check mark; a new generation on Forget, Reset and wipe; no automatic connection from a match
   until BMH3.
4. **Contacts-only mode** (labelled limited until BMH3, RV13), the Visible-by-handle window for streams, iPhone
   compact v2 advertising and reach pages version 6 with naming phase 2.
5. **Separate designs:** the BMH3 identity-hiding handshake (SIGMA), uniform advertisements and a primary-PDU
   announcement (RV7).

Key-change handling and safety numbers for store-and-forward contact cards (Design A) belong here too.

## Design A: store-and-forward messages (delay tolerant)

**Design review required.** Default off (`test_store_forward`, Experimental setting). The v1 wire format and its
differences from the original draft are in PROTOCOL.md under "Experimental store-and-forward messages". Nothing here
changes BMH2, BMS1 or BME1 or live sessions.

**Goal.** Text, voice (Opus 16-24 kbps), photo (about 1280 px) and short video (about 200 kbps, at most 60 s) reach a
contact or group without a live link, carried by phones that meet. A walk-past contact lasts about 28 s and leaves about
0.7-1.5 MB per pass, so transfers go small-first, are resumable and use a 2 MiB maximum bundle.

**Design in brief.** Each identity has a mailbox key and a contact card exchanged by QR or inside an authenticated
session; there is no directory. A bundle is sealed end to end (the recipient authenticates the sender, carriers learn
nothing about it, no transferable signature), padded, split into 8 KiB chunks with a hash list in an immutable header,
and addressed by a pairwise daily recipient tag. Carriers store bundles under quotas (64 MB foreign storage, at most
25% per neighbour), verify every chunk without decrypting, and hand copies over the existing authenticated, ordered hop
link (BMS1 hop type 11). Live media always has priority, and forwarding stays hop-local.

| Phase | Scope | Done when |
| --- | --- | --- |
| v1 two-phone | Cards, `BSB1`/`BSM1`/`BSF1`, hop type 11, chunk, resume, dedup, expiry, receipts, storage, live preemption; direct link only. Coded, JVM-tested only | JVM vectors pass, and a phone pair completes all classes at 150-1200 kbps over GATT and Classic; a mid-chunk kill resumes; a live call preempts within one chunk |
| v2 one carrier | Opt-in carrier role, quotas, fairness, per-neighbour limits, a single-copy token, carrier battery budget | A -> B -> C with B unable to open the content (asserted in test); delivery latency, B's overnight battery and quota eviction measured |
| v3 gossip | Spray-and-wait (default 6 copies), a version-3 discovery digest, decoy WANTs, optional proof of work and tombstones | 4-6 phones in two groups plus a walking carrier: delivery ratio, latency, duplicate bytes and per-phone airtime measured, with no live-session regression |

Open design questions (recommendation first; each needs review before v2 ships):

| Question | Recommendation | Alternatives |
| --- | --- | --- |
| Who carries others' messages? | Opt-in, 64 MB quota, 50 MB/day cap, paused below 20% battery | On for everyone; contacts' groups only |
| Messages from non-contacts? | Contacts only | A capped request folder; public signed bulletins |
| Delivery receipts? | On for 1:1 with time coarsened to the hour, off for groups, no read receipts | All off; all on |
| Message lifetime? | 3 days default, 14 days maximum | 1 and 7 days; 7 and 30 days |
| Clearing delivered copies? | Expiry only, then tell the adjacent carrier in v3 | Gossip tombstones (bends the hop-local rule) |
| Mailbox link identity? | Persistent now, rotating later | Ephemeral now |
| Sender proof? | Deniable | Third-party provable |
| Spam control? | Quotas plus contacts only | Proof of work on everything |
| Message size? | Voice 2 min, video 60 s at about 200 kbps, 2 MiB bundles | Larger |
| **Forward secrecy** | Add it before carrying (v2) ships: one-time prekeys in the contact card or refreshed during live contact, a fresh key per message, prekeys deleted after use, and user authentication on the key-wrapping key where supported. Cost: more complex cards and prekey refresh | Keep v1 as is (direct only, nothing retained on carriers) and add it only for v2 carriers; or accept and disclose the limitation in the UI |

The limitation itself is described in PROTOCOL.md ("Forward-secrecy limitation"): both slot keys derive from the
recipient's long-term mailbox key, so a later compromise of that key decrypts retained bundles and reveals senders.

Risks: contact windows may be too short on slow stacks; mailbox ECDH splits at API 31; clock skew affects day tags and
expiry; Sybil storage abuse and colluding-carrier traffic analysis (inherent to delay-tolerant networks, so stated in
user-facing text); OEM background-scan limits; seized-phone forensics (see "Emergency wipe" in PROTOCOL.md). Prototype
first: a 1 MB bundle between two phones with a forced disconnect and a live-call preemption decides the 8 KiB chunk and
2 MiB limits.

## Design B: group calls (3-6 people) and group push-to-talk

**Design review required.** Design only; default off (`test_group_call`). Existing 1:1 calls, streams, BMH2, BMS1, BME1
and the static BGK1 group-key harness stay unchanged.

**Airtime.** A 20 ms Opus frame at 32 kbps costs about 64 kbps on the link after packet, BME1 and BMS1 overhead (about
50 kbps with the existing 40 ms audio-pair format). Planning uses a = 50 kbps per audio stream and v = 120 kbps per
low-rate video stream. Opus DTX cuts a silent talker to near zero, so real load scales with how many people speak; the
worst case assumes everyone sends. A hub's total radio airtime is the limit, not one link (an M31 GATT two-link relay
tops out at 625 kbps, and in a fan-out test leaves dropped to 0.9-7.6 FPS).

| Topology | Busiest phone, audio (N = 3 / 4 / 6) | Links per phone | Notes |
| --- | --- | --- | --- |
| **Mixing star** (hub decodes, mixes "everyone but you", re-encodes) | 2(N-1)a = **200 / 300 / 500 kbps** | hub N-1, leaf 1 | Small CPU cost; adds about 40-60 ms and one transcode |
| Forwarding star | N(N-1)a = 300 / 600 / **1500 kbps** | hub N-1, leaf 1 | Above the M31-class ceiling at N of 4 or more |
| Full mesh | 2(N-1)a per phone | N-1 (5 at N = 6) | 15 links to keep alive; weak stacks fail on the second or third link |
| Mixing relay tree | 2(c+1)a per node | parent plus c children | Hop-local, extends range; each hop adds about 60-100 ms and a transcode |
| PTT forwarding star (one talker) | at most (N-1)a = 100 / 150 / 250 kbps | as star | No mixing while one person holds the floor |
| Active-speaker video via star | +Nv = 360 / 480 / 720 kbps | as star | One video at a time; gallery video (3.6 Mbps at N = 6) is impossible |

**Recommendation: a mixing star, built so a star is a depth-1 mixing tree.** Hub load grows linearly, leaves need one
link (legacy stacks fail on extra links), and existing hop-local liveness and admission apply unchanged. A leaf hosting
its own children can reuse the mixer later, after the star is measured. Video is active-speaker only, N of 4 or fewer,
admitted only when the hub's measured capacity covers audio plus Nv, forwarded and never mixed.

- **Audio.** Leaves send Opus with DTX over per-edge endpoint sessions; the hub keeps one jitter buffer per leaf, mixes
  the three loudest active streams and encodes one mix-minus per leaf; with exactly one active stream it forwards that
  packet unchanged (re-sealed per edge). Same-room echo is the main acoustic risk: use the VOICE_COMMUNICATION source
  with platform echo cancellation, earpiece or headset by default and a visible speaker toggle.
- **Floor control (the hub arbitrates).** First request wins; a talker captures into a local buffer of at most 400 ms;
  maximum talk time 60 s; a lost holder frees the floor.
- **Keys.** Per-edge endpoint sessions mean removing a member immediately cuts access to new media. A group epoch key
  `BGK2` (increasing epoch, roster hash, controller and succession list, expiry, controller signature) is wrapped per
  member; a new epoch is issued on every join and leave, the old one accepted for 2 s, and a child that does not
  acknowledge within 2 s is closed. The hub hears all audio and can misattribute who is speaking in a mix.
- **When the host leaves.** The signed roster names a succession list (deputy first, by measured capacity, battery and
  signal). A planned handover or an unplanned loss moves leaves to the deputy with fresh authentication and a new epoch
  (target at most 10 s of silence, to be measured); a partition is not merged automatically in v1.
- **Wire changes (all new, all fail closed):** a discovery version-3 group kind with a keyed 5-minute token; BME1 type
  9 `GROUP_CONTROL` (`BGC1`); BME1 type 7 payload `BGK2`; a `BTV3` header flag for mixed audio plus an origin index for
  passthrough PTT; a group-invite reason on the call invite.

Open design questions (recommendation first): topology (mixing star; or forwarding star, full mesh, relay tree); group
video (active speaker only, N of 4 or fewer; or audio only); PTT floor (first come first served; or a leader who can
interrupt, a next-speaker queue); host leaves (a deputy takes over with a new key; or the call ends); rekey on join as
well as leave; speaker misattribution (accept and disclose in the UI; or signed talk-spurts); relay trees for range
(after the star is measured); who adds members (the hub admits from a QR roster; or any member, a fixed roster).

Risks: hub aggregate airtime over N-1 links is unmeasured; legacy stacks (M31 GATT 625 kbps relay ceiling, A15s CoC
slot exhaustion, S22 second-link timeouts) limit who can host; same-room echo; tandem-transcode quality in trees; hub
battery drain (rotate via succession); a mouth-to-ear target of at most 400 ms, to be measured physically; split-brain
and rekey storms under churn. Prototype order: (1) an audio-only mixing star, N = 3 over GATT, measuring hub
throughput, per-leaf media age, mouth-to-ear latency and hub CPU and battery; (2) N of 4-6 with DTX on and off; (3) PTT
passthrough with the floor state machine; (4) `BGK2` rekey on join and leave with JVM tests. Video and trees come after.

## Bluetooth headsets

Wired and USB headsets are honoured: a plugged one replaces the earpiece, and the loudspeaker unless the person
explicitly chose it. Bluetooth headsets (SCO and LE Audio) are deliberately never selected as the call output, because
they take airtime from the same radio that carries the call's own Bluetooth media link. The rule is the single call
output decision (`callOutputFor` in `PrivateCallAudioSession.kt`), covered by JVM tests.

That is a protective default, not a measured limit: no headset-coexistence measurements exist yet. Remaining work:

1. Measure, per Bluetooth stack and transport, what a connected headset costs a live call (throughput, stalls, media
   age, audio continuity) with the headset idle, in A2DP, in SCO and in LE Audio, against the same call without it,
   recording the radio conditions.
2. If the cost is acceptable on some stacks, offer headset output as an explicit, labelled opt-in (default off,
   Experimental) with a visible warning, scoped by a device-family policy entry with sources and an off-switch. Never
   change the call's media-link settings to make headset output look better.
3. Keep LE Audio broadcast paths out of scope unless a public API, hardware and actual output evidence all exist (see
   "New Bluetooth and API facilities" below).

## Distribution and field reports

- **F-Droid and IzzyOnDroid.** The repository carries the metadata and recipe (`fastlane/`, `distribution/`).
  IzzyOnDroid serves developer-signed APKs from the GitHub release unchanged; F-Droid builds from source. Remaining:
  real phone screenshots in `fastlane/` and the inclusion requests (the application ID is already the final
  `io.github.meshlive.app`).
- **Release verification and field reports.** The About screen shows the app version and the signing-certificate
  fingerprint, and Export diagnostics copies the block defined in the README section "Verifying a release" (no media,
  messages, contact names or full keys). The current builder is a minimal fallback; the full block must come from
  bounded in-memory records the app keeps (connection, adaptive-PHY and codec events, device-policy state), never from
  logcat: release builds strip `Log.v/d/i` (`app/proguard-release-logs.pro`), and other apps' logs are not readable.
- **Google Play** would also need Play App Signing, a privacy policy and Data safety form, and a decision on the
  Bluetooth, camera and microphone permission declarations. None is started.

## iPhone interoperability

**Design review required** for anything on the wire. Compact discovery (an iPhone-sized advertisement) is implemented
behind `test_ios_compact_discovery` (PROTOCOL.md "Experimental iPhone compact discovery"); the iOS client lives in the
sibling repository MeshLive/meshlive-ios. Done when an iPhone's call offer, viewer request and stream offer are found
and connected by every reference phone with the switch on, in both setup roles, with `scan_stats` counters and evidence
of whether each phone filters the masked UUID in its controller or falls back to host filtering (AOSP
`ScanManager.shouldUseAllPassFilter` moves a whole scanner to all-pass when it has more filters than free controller
slots; the switch doubles each scanner's slot use).

Optional H.264 is not coded (it would be default-off `test_h264_codec`). It would use `BTV3` codec 4, encode and decode
mask bit 2, `capabilitySummary` bit 2 or compact codec bit 56, and call-hello decode-mask bit 6 (`0x40`, prefer H.264);
all are free today and receivers already reject unknown codecs. Before coding, a PROTOCOL.md design and review must
settle: bit 7 (prefer VP9) wins when both preference bits are set; SPS/PPS delivery (replay the cached config before
each IDR, or set `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`, with receivers accepting in-band SPS/PPS); no B-frames
(`BTV3` timestamps are monotonic); decoders accepting any supported profile, not only Constrained Baseline; Annex B
access-unit validation; and an H.264-only iPhone failing cleanly against Android builds without it.

## Wi-Fi Direct / Aware bearer (later)

**Design review required.** An optional, faster bearer that needs no infrastructure, with the same authenticated
identities and encryption, chosen explicitly. Bluetooth stays for discovery and as the fallback. Not started. It must
not become a silent transport fallback, and each bearer is measured separately under the same gates. (The experimental
Wi-Fi Direct page for sharing the app itself is a different, offline-sharing feature.)

## Measurement adapters still missing

The harness has host regressions for native input binding, marker decoding, recording import, content-ROI diagnostics,
raster calibration, sound-cue analysis, AudioTrack clock estimates, pipeline, lifecycle, resource and overlap
analysis, and controlled impairment (see [TESTING.md](TESTING.md)); none creates a phone pass by itself. What remains:

| Remaining work | Required result |
| --- | --- |
| Native stress and calibration evidence | Prepare only the native variants selected for the next comparison; characterize spatial, temporal and chroma demand and measure the selected encoder's actual time, bursts and queue growth. Validate the framing detector and tolerances at captured viewports; generated checks do not establish physical colour, optics or sufficient stress |
| Actual output capture and clocks | Verify acquisition modes, sensor and audio clock skew, exposure, rolling shutter, room path and dual-channel isolation on a real camera and microphone instrument, with independently measured cross-phone clock anchors. Decoder or Texture callbacks cannot approve physical cadence, blur or optical latency |
| Reference colour | Verify the requested range, matrix, transfer and chroma through each actual encoder and decoder |
| Acoustic experience | Complete clock coverage and diagnose timestamp and underrun changes against planned pauses. Waveform fidelity, speech quality, routing, echo and double-talk remain open; verify mute and independent audio and video phases over the full lifecycle |
| Lifecycle and trace preservation | Extend the generated-input pause, resume and End path to Camera2, foreground, lock, process and thermal cases and real remote acknowledgements; process-death reconstruction is separate product work |
| Radio and endurance | Run declared controlled stalls and loss at actual offered loads, keeping both call directions and every hop; separately measure real radio interference and range and matched 15-30 minute unplugged endurance |

Write focused tests for each adapter's failure cases before using it to close a benchmark request, and keep results in
the suite's own state rather than a second ledger.

## Platform and product work

| Area | Remaining boundary |
| --- | --- |
| Audience membership and trust (**design review required**) | Static group-key distribution exists. Dynamic revocation, epoch rotation and expiry, and rejection of old keys remain unfinished (Design B's `BGK2` covers part). Full-fingerprint verification exists and is opt-in per contact; trust-on-first-use pinning of unverified peers and an independent protocol audit remain open. Audience modes must change admission without disabling encryption |
| Stream link role reversal (**design review required**, PROTOCOL change) | Today a relay or source is the LE central and connects to the advertising child. A relay that is a busy peripheral on its parent link cannot always establish that second, central connection: an M31 relay snoop (2026-10-10) shows ten LE Connection Complete events at a 45 ms interval, each ending 0x3E after 6 events. Root fix: the child connects to the relay as central, and the relay advertises its offer and accepts the CoC, with the same hop and end-to-end authentication, grant and hop-local control; the busy relay then never initiates, and each phone is central for at most its own parent link. Before coding, record in [PROTOCOL.md](PROTOCOL.md): the advertisement and accept flow and its admission check before accept; discovery and privacy impact; behaviour on Android 10-14 stacks (CoC server on the relay); how the child chooses or keeps the 2M/1M PHY; and A/B evidence against `relay_establish_fallback` on the same topologies. |
| Branches and alternate routes | Normal viewer-forwarders support one child and one active parent. A multi-child UI, four-phone group and adversarial validation, and larger alternate-parent graphs remain open; a diamond needs loop, duplicate, TTL and hysteresis handling. Simultaneous redundant media and make-before-break recovery need separate architecture work |
| Call recovery and expansion | Screen lock, OS or Bluetooth interruption and process-death reconstruction need evidence and a bounded reconnect design. The opaque relay diagnostic builds chains of up to four relays (PROTOCOL.md "Private-call relay chains"); remaining: physical evidence for two or more relays, route discovery for ordinary users (the relay is DEBUG-only), relay forwarding counters and a measured relayed call cap (experimental `relayed_call_measured_cap`) |
| Background and session ownership | Typed foreground services protect ordinary app switching. Reconstructing camera, audio and authenticated sessions after process death needs an explicit design and lifecycle proof |
| Windows receiver | The retained desktop receiver is video-only (`BTV2`). Add current `BTV3` video and audio and ordinary discovery before claiming interoperability with current Android camera-and-audio streams; capture lifecycle and teardown need physical evidence |
| Power, range and display | Matched unplugged, role-reversed endurance, real heat, and controlled range and interference measurements; charging runs and nominal Low Energy do not establish efficiency. Replacing TextureView with SurfaceView needs measured geometry, lifecycle and power preservation |
| New Bluetooth and API facilities | Temporal-layer dropping proceeds through workstream A with bitstream-verified droppability; API 37 temporal-layer IDs or HDT only with a compatible public API, hardware and actual output evidence. HDT is not an arbitrary CIS or BIS payload socket, and hidden or root-only LE Audio paths are not product features. Classic and LE bandwidth aggregation and relay re-encoding are not implemented product paths |

These rows keep future obligations without turning them into approved implementations. When a requirement changes,
update the benchmark scope and inventory together with its reason (see [TESTING.md](TESTING.md)).
