# Plan

For contributors: what is unfinished (priorities, open defects, designs awaiting review). Current behaviour lives in the owning document (see the [README](../README.md#documentation)); when an item is finished its description moves there and the item is removed here. Measured reasons for existing policies are in [DECISIONS.md](DECISIONS.md).

Two labels are used:

- **Design review required**: the item changes the protocol, the security model or what a phone reveals over the air.
  It needs a written design in PROTOCOL.md and an independent review before code ships, and it ships behind a
  default-off flag (see the register in [ARCHITECTURE.md](ARCHITECTURE.md)).
- **Experimental**: coded behind a default-off switch and not yet validated on phones.

Nothing here is a promise or a schedule. Contributions are welcome on any item; open an issue first (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

## Priorities at a glance

| Priority | Area | Status | Design review |
| --- | --- | --- | --- |
| 1 | [Open defects in shipped behaviour](#open-defects-in-shipped-behaviour) | Open | No |
| 2 | [Policy work: fewer fixed limits](#policy-work-fewer-fixed-limits) | In progress | No |
| 3 | [Discovery privacy](#discovery-privacy) (rotating IDs) | Phases 1, 1b and 2 coded; on by default since 0.2.0-beta | Yes |
| 4 | [Intercom core and product direction](#intercom-core-and-product-direction) | Partly coded, Experimental | Parts |
| 5 | [Design A: store-and-forward messages](#design-a-store-and-forward-messages-delay-tolerant) | v1 coded, JVM-tested only | Yes |
| 6 | [Design B: group calls](#design-b-group-calls-3-6-people-and-group-push-to-talk) | Design only | Yes |
| 7 | [Design C: relayed private calls](#design-c-relayed-private-calls-and-multi-hop-discovery) | First slice coded, Experimental | Yes |
| 8 | [Design D: dual listening](#design-d-dual-listening-coc-and-gatt-at-once) | Coded, Experimental | Yes |
| 9 | [Design E: uniform adverts](#design-e-uniform-adverts-capability-fields-behind-the-handshake) | Design only | Yes |
| 10 | [Design F: BMH3 handshake and stranger pseudonyms](#design-f-bmh3-identity-hiding-handshake-and-stranger-pseudonyms) | Coded; on by default since 0.2.0-beta | Yes |
| 11 | [Bluetooth headsets](#bluetooth-headsets) | Deliberately excluded; open question | No |
| 12 | [Distribution and field reports](#distribution-and-field-reports) | In progress | No |
| 13 | [iPhone interoperability](#iphone-interoperability) | Compact discovery coded, Experimental | Yes |
| 14 | [Wi-Fi Direct / Aware bearer](#wi-fi-direct--aware-bearer-later) | Not started | Yes |
| 15 | [Measurement adapters still missing](#measurement-adapters-still-missing) | Open | No |
| 16 | [Platform and product work](#platform-and-product-work) | Open | Parts |

## Open defects in shipped behaviour

Open across every phone combination that uses the affected behaviour. The benchmark case state ([TESTING.md](TESTING.md)) owns pass and fail history; the investigation notes behind each row are in [DECISIONS.md](DECISIONS.md) under "Open-defect investigation notes".

| Work | What must be established before closing it |
| --- | --- |
| Uneven playback and picture quality | Separate source pacing, encoder output, Bluetooth stalls, receive queues, decoder processing and presentation, correlated by run, session, PID and each phone's clock. Frame-timing proxies and short clean prefixes cannot approve screen smoothness; the missing stage and clock evidence comes before another display policy |
| Connecting/Recovering flashes and reconnect loops | A stable UI state, bounded retries, truthful discovery and availability after End, loss and cancellation, with planned and unexpected loss distinguished. Fix `test_receiver_ui_stability` (default on) awaits phone tests. Open: a failed join still shows the playback screen for 0.25-0.4 s, and some "Connecting" overlays wait 15 s with no progress text |
| Real three-phone chains | Diagnose decrypt failures, missing completions and guard withdrawals without inferring cause from event order across phone clocks. Keep the authenticated original source, requested parent, ordered encryption and guards; direct-source fallback fails the requested chain |
| Bidirectional calls and reverse setup | Resolve reverse-caller GATT setup stalls (an MTU request with no callback); fix `test_gatt_mtu_probe` (default on) awaits phone tests. Open: the retry onto a half-closed ACL and one A15s case. Native inputs reject a geometry or FPS mismatch, so prepare exact matching requests or report ineligibility |
| Relayed viewers hold seconds of media | Viewers behind a relay held 2.7-7.5 s (direct 250-345 ms) because relay queues and socket buffers absorb the backlog. Fixes `test_relay_pace_head` (default on) and Experimental `test_relay_pace_lead_cap` need a one-frozen-build phone A/B (control, head fix, head fix + lead cap; CoC and GATT; 3 repeats; a direct control), and a policy for a relay link slower than the stream (shed, withdraw and reroute, lower the sender rate, or batch audio) |
| Relay dual-role scheduling | A relay's parent and child LE links (both 15 ms) keep a fixed phase, and on the M31 some phases never complete. Guard `test_relay_child_stall_guard` (default on) and Experimental `test_relay_upstream_dck` need an HCI-snoop A/B on both relay candidates (at least 15 child attempts per arm, 3 repeats). DCK passes when the parent link shows 30 ms, no silent timeouts and no worse leaf freezes; the guard passes when a dead attempt ends within 3.5 s and no healthy join is closed |
| Call speech behind video writes | GATT call audio waited 620-1193 ms behind one video frame. Fragments with speech-led playout are on for direct GATT calls since 0.2.0-beta. Open: audio continuity and A/V offset, sender CPU and battery on the M31, a third repeat, the A15s over CoC, and an HCI check that audio goes on air between fragments |
| Visible age of the last received frame | `test_viewer_frame_age` (default off) needs a phone test in the relay loss/rejoin lane, then its default flipped. A clock-synced end-to-end age needs a protocol design |
| Stream link role reversal (**design review required**) | Today a relay or source is LE central and connects to the advertising child, which a busy-peripheral relay cannot always do. Root fix: the child connects to the relay as central. Do not build it before the dual-role A/B shows how many failures remain with DCK. Record first in [PROTOCOL.md](PROTOCOL.md): the advertisement and accept flow with its admission check, discovery and privacy impact, behaviour on Android 10-14 stacks, how the child keeps the 2M/1M PHY, and an A/B against `relay_establish_fallback` |
| Requested versus effective settings | Use the register of concrete limits and the capability evidence to challenge call budgets and legacy protections. Expose every difference in encoder, allocation, geometry, rate, FPS, audio and buffer rather than silently substituting settings |

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

Open: the policy for calls beyond a link's capacity (lower call frame rate, longer call delay, or the current video skip); forced CBR on the M31 and the 15-20 s encoder trim, judged by same-build VMAF; relay child joins (start the child from the relay's next natural keyframe, or grow the relay's playout delay before it connects a child); replacing the 20% per-link planning margin and the 0.9 ceiling headroom. The measured status behind each workstream is in [DECISIONS.md](DECISIONS.md) under "Open-defect investigation notes".

## Intercom core and product direction

Intercom (voice, push-to-talk) and live video are equal parts of the product; the reasoning is in
[DECISIONS.md](DECISIONS.md) under "Product direction".

| Work | Done when |
| --- | --- |
| Bounded media-age experiment (**Experimental**, default off) | Calls keep speech within about 300-400 ms by lowering bitrate or resolution, dropping codec-safe non-reference frames, then going audio-only; streams buffer up to about 2 s, then shed or resync while keeping authenticated order. A randomized A/B against no-loss (M31 <-> ASUS, S24 <-> A15s, both directions, 150-1200 kbps with bursts) reports media age, stall length, frozen share and speech continuity, and the maintainers choose the default from it. No-loss stays the default meanwhile |
| User-centred gates | The `device-run-v4-user` profile in [TESTING.md](TESTING.md) is built around media age, longest stall, frozen share, audio continuity and task success. Remaining: a decided media-age target, and adapters for task success and speech comprehension |
| Intercom core | Reliable 1:1 voice and push-to-talk in small groups, text, photos and location with time and accuracy; screen-off operation and reconnect proven on phones. Later: store-and-forward voice and video messages (Design A) |
| Signing mode | A legibility-first video profile (at least 20 FPS target for hands and faces), validated with fluent signers before any accessibility claim |

Current recommendation: keep no-loss as the default and bounded age as an off-by-default experiment. The interim evidence is in [DECISIONS.md](DECISIONS.md) under "Open-defect investigation notes".

## Discovery privacy

**Design review required.** Today a phone advertises a stable 48-bit identity prefix, so a passive scanner can follow it
between places. The goal: discovery stops broadcasting a stable prefix, using contact-scoped rotating identifiers and
explicit discoverability windows. In-person contact verification (QR code or safety code; the verification API exists
and its UI binding is still to come) and key-change warnings come before the app is positioned for at-risk users.

Status: rotating discovery IDs, the BMH3 handshake and pseudonyms toward strangers are on by default since 0.2.0-beta (an explicit stored choice wins); formats and vectors are in [PROTOCOL.md](PROTOCOL.md) and measurements in [PRIVACY.md](PRIVACY.md). Not measured: battery cost, the Bluetooth name read on the A15s, an active attacker holding a contact's key, fake adverts, relays with the new handshake and abuse limits. Open design question: the monotonic epoch never returns after a backward clock step, so a lead of 3 to 96 epochs leaves a phone unable to receive a version 5 call until it recovers. Phone-test plan:

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

## Design C: relayed private calls and multi-hop discovery

**Design review required.** Default off (`test_relayed_calls`, Settings > Advanced "Experimental: relayed calls"). The
design was reviewed independently on 2026-10-11 and builds on the earlier draft (R14-R22) and on these design
decisions: hop-local reading accepted, relay helping on by default while the app runs once the feature is enabled,
consent asked once and preselected, a radius of 2 relays. Wire rules: PROTOCOL.md "Experimental relayed calls and
multi-hop discovery". With the switch off, every byte on air and on the link is unchanged.

**Goal.** A caller A reaches a call-ready phone D that is one or two relay phones away, and an ordinary phone relays
calls in the background without being told which call to take. Before this, only DEBUG relays existed (a launch extra
names the callee).

- **Roles and reach table.** Target D (call-ready, consents), relay helper R (opted in, call endpoint idle), caller A.
  Each helper keeps a table of targets fed only by adverts it scanned itself: D's own call offer gives a cost-1 entry, a
  neighbour's reach-page entry gives cost + 1, up to the cap (default 2, DEBUG `test_relayed_calls_max_relays`, at most
  4). Reach pages are a second legacy non-connectable advertising set (one page per second).
- **On-demand setup.** A connects to R1's call listener, authenticates the hop, and sends `SETUP(target, budget,
  remaining time, setup ID)` as the first hop record (hop type 12). R1 checks its table, connects to the next hop
  itself and regenerates the same message downstream; `READY` and `FAIL` return one hop at a time. After READY the
  existing opaque call relay copies the ordered end-to-end records, and A runs the ordinary end-to-end handshake
  through the chain.
- **Hop-local, with stated stretch.** A phone re-advertises only what its direct neighbours told it, and setup is
  regenerated at every hop with a new ID, smaller budget and shorter deadline. Reachability spreads by repeated
  re-advertisement (a distance vector with an age), and the target's identifier travels the chain inside the SETUP, so
  each relay on the path learns its adjacent links, the target and (from the signed end-to-end hello it copies) both
  endpoint identities. A passive observer near a relay learns that D is within N hops of it.
- **Identity and abuse limits.** A relay cannot read or forge content (end-to-end keys). A requires the end-to-end
  peer to match the target it selected (the full fingerprint for a verified contact); each helper link identity must
  equal the helper offer that was selected; the last relay accepts only the target or another relay as its child.
  Absolute deadlines from READY (10 s end-to-end handshake, 20 s Invite) and a 60 s ringing cap stop a caller from
  pinning helpers.
- **Rotating IDs.** With both experiments on, relaying uses role-0 rotating IDs only (reach pages version 6, `SETUP`
  message 4, every hop checked with `BDO1`/`BDX1`); DEBUG `test_relayed_rotating=false` restores the earlier exclusion.
- **Battery and pre-emption.** Helping runs only while the phone is call-ready and none of these hold: battery below
  20% and not charging, Battery Saver, severe thermal status, Bluetooth off, the phone's own exchange. A relayed call
  already running is not cut by a pause. Starting or answering the phone's own call or stream ends any relay in
  progress. The foreground notification reads "Helping a call nearby" with a Stop action. Idle scanning cost per hour is
  a gate to measure on each reference phone before any default is considered.
- **Failure and fallback.** A shows the route ("Nearby", or "Via 2 phones, heard 8 s ago, unconfirmed"), picks the
  fewest relays then the freshest, and skips a failed pair for 30 s. A chain that breaks mid-call ends the call;
  automatic re-routing is future work. Direct fallback is a failed requested chain: `test_relayed_calls_require=<n>`
  accepts only a chain of exactly n relays. One bearer end to end; only LE transports are supported on demand. iPhones
  can neither help nor be relayed targets until the compact amendment.
- **Deferred:** automatic candidate iteration inside one attempt, the embedded single-entry carrier for phones without a free advertising set, Classic on-demand relays, mid-call re-routing, extended-advertising reach lists and an iPhone caller. Phone-probe history: [DECISIONS.md](DECISIONS.md) "Relayed calls: first phone probe".
- **Phone verification queue** (one helper at a time, `test_relayed_calls=true` on every participant): run
  `python -B tools/dex_api_scan.py` on the APK first; then one helper on GATT with `test_relayed_calls_require=1`,
  rotating IDs off and on (S24 -> ASUS -> M31 and the reverse, then each phone as helper; the M31's dual-role limit
  decides which may help), then the same on LE CoC. Record setup time (invite to ringing) and compare quality with the
  DEBUG chain at the same settings (relayed video cap 15 FPS / 110 kbps).

## Design D: dual listening, CoC and GATT at once

**Design review required.** Default off (`test_dual_listening`, Settings > Features "Reachable over both Bluetooth LE
links"). Wire rules: PROTOCOL.md "Experimental dual listening".

**Problem.** A call endpoint advertises one transport. The A15s stack stops accepting LE CoC listens and connects after
about 15 registrations per Bluetooth process. The safety net (automatic GATT when LE CoC fails) moves the failing phone
to GATT, but only phones that also scan GATT see it, and once it is a GATT endpoint it lists only GATT offers, so it can
no longer call a CoC phone.

**Design.** A CoC-primary call offer carries one more bit (`capabilitySummary` bit 3, "also answers over GATT"); the
endpoint hosts the existing GATT server beside the CoC server (one advertising set, one database); a caller tries CoC
first for a bounded 9 s, then GATT to the same device from the same sighting; the endpoint keeps its primary transport
and gets a per-link override. Out of scope: streams, store-and-forward as the dialing side, relay hops, Classic,
GATT-primary dual, and the iPhone compact form. Cost: one more GATT server registration and six ATT handles per dual
endpoint. An independent review (14 findings) was adopted.

**Phone test.** With the switch off, the scan response and behaviour are unchanged; with it on, calls between mixed
pairs set up within 1 s of the off matrix, the A15s forced to GATT lists CoC phones and completes 20 consecutive call
cycles, and the first 30 s of a call over the second transport match a native call of that transport. The S22, Xiaomi
and A55 run it as soon as they are available.

**Open design question:** the monotonic epoch of rotating IDs never returns after a backward clock step (see "Discovery
privacy").

## Design E: uniform adverts, capability fields behind the handshake

**Design review required. Designed 2026-10-11; not coded.** A version 5 advert hides who the phone is, but the rest of
its bytes are the same in every epoch of one phone: the transport bit, the two codec bits, the relay-helper flag, the
relay-consent and dual-listening bits, and for streams the resolution, frame rate and bitrate. Together they are a few
bits of per-phone state that split a crowd into groups (GAEN and DULT require everything that differs between devices to
rotate with the address or be identical for all). PROTOCOL.md "Fields that stay constant across epochs" lists the
inventory and the reductions already made.

What has to stay in the advert: kind, transport, accepting/paused/full, route cost, sequence. What can move behind the
handshake (its hello already carries the codec masks, bitrate and `maxChildren`): the codec masks, the helper flag, the
consent bit, the dual-listening hint and the stream's resolution, FPS and bitrate. The cost: a caller can no longer hide a
phone with no common codec before connecting (an incompatible call fails after the handshake with a clear message, after
one connection of 2-4 s), and a relay helper learns the consent only on connecting.

| Option | What changes | Tradeoff |
| --- | --- | --- |
| A | Measure first: count the distinct version 5 layouts per kind on the reference phones before flipping defaults | No code now; the decision comes while changing version 5 is still free |
| B (recommended if more than one layout is populated) | Redefine version 5: the capability summary of calls and streams becomes a constant, and the codec check, helper flag, consent bit and dual hint move into the signed hello | One more failure path for calls with no common codec; a protocol version change |
| C | Leave the bits and disclose them as a few bits of quasi-identifier | Nothing to build; a crowd is split into a few groups |

## Design F: BMH3 identity-hiding handshake and stranger pseudonyms

**Coded 2026-10-11; on by default since 0.2.0-beta** (Settings > Features "Hidden identities" and its child
"Pseudonymous toward strangers"; DEBUG `test_bmh3`, `test_bmh3_pseudonyms`). Wire rules: PROTOCOL.md "Experimental
BMH3 identity-hiding link handshake" and "Phase 2: pseudonymous stranger identities". The protocol choice and the
rejected alternatives are in DECISIONS "BMH3 identity-hiding handshake: protocol choice" and "Pseudonymous strangers:
design choice".

Rationale, reviews and measurements are in [DECISIONS.md](DECISIONS.md) ("BMH3 identity-hiding handshake: protocol choice", "Pseudonymous strangers: design choice"); the executable specification is `tools/bmh3_reference.py`.

**Still open.** Streams answer BMH2, so a source's identity stays visible to viewers. A phone with pseudonyms on refuses
the old handshake, so it cannot call or be called by older versions or by a phone with the switches off. Message
exchanges and relayed calls work only with verified contacts while pseudonyms are on. A stranger who calls or answers
still learns who you are once you accept. Binding the verification QR to a live handshake is a follow-up.

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
  fingerprint, and Export diagnostics copies the block defined in [ARCHITECTURE.md](ARCHITECTURE.md#diagnostics-block-contract-for-export-diagnostics) (no media,
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
| Branches and alternate routes | Normal viewer-forwarders support one child and one active parent. A multi-child UI, four-phone group and adversarial validation, and larger alternate-parent graphs remain open; a diamond needs loop, duplicate, TTL and hysteresis handling. Simultaneous redundant media and make-before-break recovery need separate architecture work |
| Call recovery and expansion | Screen lock, OS or Bluetooth interruption and process-death reconstruction need evidence and a bounded reconnect design. The opaque relay diagnostic builds chains of up to four relays (PROTOCOL.md "Private-call relay chains"); remaining: physical evidence for two or more relays, route discovery for ordinary users (the relay is DEBUG-only), relay forwarding counters and a measured relayed call cap (experimental `relayed_call_measured_cap`) |
| Background and session ownership | Typed foreground services protect ordinary app switching. Reconstructing camera, audio and authenticated sessions after process death needs an explicit design and lifecycle proof |
| Windows receiver | The retained desktop receiver is video-only (`BTV2`). Add current `BTV3` video and audio and ordinary discovery before claiming interoperability with current Android camera-and-audio streams; capture lifecycle and teardown need physical evidence |
| Power, range and display | Matched unplugged, role-reversed endurance, real heat, and controlled range and interference measurements; charging runs and nominal Low Energy do not establish efficiency. Replacing TextureView with SurfaceView needs measured geometry, lifecycle and power preservation |
| New Bluetooth and API facilities | Temporal-layer dropping proceeds through workstream A with bitstream-verified droppability; API 37 temporal-layer IDs or HDT only with a compatible public API, hardware and actual output evidence. HDT is not an arbitrary CIS or BIS payload socket, and hidden or root-only LE Audio paths are not product features. Classic and LE bandwidth aggregation and relay re-encoding are not implemented product paths |

These rows keep future obligations without turning them into approved implementations. When a requirement changes,
update the benchmark scope and inventory together with its reason (see [TESTING.md](TESTING.md)).
