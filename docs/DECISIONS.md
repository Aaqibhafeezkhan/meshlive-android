# Decisions and measured comparisons

*Engineering log for contributors: the measured reasons behind the app's behaviour. You do not need it to use or build the app.*

This file records why MeshLive behaves the way it does: which behaviour was kept, changed or left experimental, and the measurements behind each choice, so a later change can challenge them with evidence. The raw benchmark evidence (recordings, logs, traces) is not stored in this repository; only the conclusions and the numbers that support them are kept here. The numbers come from the project's own reference phones and are engineering measurements, not certification: they never approve another build, phone, input or topology. Case state and freshness are tracked by the test harness (see [TESTING.md](TESTING.md)).

Every phone uses the same acceptance standard. A regression seen on one device is evidence for the capability or stack behaviour it implicates, not a special pass standard. Reference phones: S24 (SM-S921B), S22 (SM-S901E), M31 (SM-M315F), A55 (SM-A556E), ASUS (ASUS_I01WD), Xiaomi (Redmi 23053RN02I) and A15s (OPPO CPH2179). A historical Redmi and the later Xiaomi are different devices. Build identifiers such as `2cbdafb1` are short hashes of frozen test builds.

Short glossary for this file:

- **Hop**: one Bluetooth link between two phones. A **relay chain** is source -> relay -> viewer, each link a hop.
- **Classic / CoC / GATT**: the three transports. Classic is Bluetooth BR/EDR RFCOMM; CoC is an LE L2CAP connection-oriented channel; GATT is the LE attribute protocol used as a data pipe.
- **Fan-out**: one source serving several direct viewers.
- **Playout delay / adaptive playout**: the buffer a viewer holds before showing frames, which grows when frames arrive late.
- **Bounded age**: an experimental policy that limits how old media may be before it is shed.

## Product direction (2026-10-09)

Decisions are made from the point of view of the people who would use this app (no Wi-Fi or cell service: disasters, shutdowns and censorship, off-grid sites), and earlier rules are open to challenge. Two independent reviews and a separate analysis informed these answers:

| Question | Decision | Reason and boundary |
| --- | --- | --- |
| What the app is | Two equal pillars: (1) an offline local intercom (reliable voice calls, push-to-talk, small groups, text, photos, location; later store-and-forward voice/video messages), and (2) live video (calls, streams, a signing mode). | Bluetooth reaches one site (tens of metres), not a city. Multi-hop live video spends airtime twice per hop (relayed or fanned-out leaves measured 0.9-7.6 FPS in the 4-phone mesh test of build 57484598). Video is essential for some users (sign language, "show me the problem") and optional for others. |
| Congestion policy | A/B before deciding. "No frame loss" stays the default. A bounded media-age policy is built as an experiment (calls keep speech within about 300-400 ms by degrading video; streams buffer up to about 2 s, then shed or resync codec-safely, with audio protected) and compared on phones. | Buffering absorbs bursts but not sustained overload (600 offered into 300 delivered grows the delay by about 1 s per s). The earlier no-loss rule was a default preference, not a requirement. |
| Faster bearers | Wi-Fi Direct / Wi-Fi Aware become an optional faster bearer later. They are listed in [PLAN.md](PLAN.md) and not started. | It would keep the same offline promise with far more throughput; Bluetooth would remain discovery and fallback. |
| Acceptance gates | Move to user-centred gates: media age, longest stall, share of time frozen, speech and task success. Healthy-link regressions still require zero unexpected freezes. VMAF, SSIM, PSNR and FPS fractions remain diagnostics. | Fidelity thresholds were engineering gates, not end-user requirements (for example, M31 AV1 had 0 freezes in 5/5 runs at VMAF 84-87). Old results keep their original verdicts; requirements are retired explicitly with a recorded reason. |
| Who may relay calls (2026-10-11) | Anyone nearby may use a phone as a call relay, bounded by one relayed call at a time, rate limits, pauses and pre-emption by the phone's own call or stream. | Off-grid users need strangers to extend range; verified-only relaying would collapse reach. |
| Relay pause on low battery (2026-10-11) | A charging phone keeps helping; a phone below 20% that is not charging pauses helping (the shipped stream-relay rule, `relayAutoPaused`). | Charging removes the battery cost; an unplugged low phone keeps its battery for its owner. |
| Privacy before reach (2026-10-11) | Rotating discovery IDs become the top privacy priority and are built and phone-tested in parallel with the relay work (phases 0/1 on idle phones, phases 1b/2 in code); relayed calls stay Experimental until relays stop spreading the stable prefix. | An outside reviewer showed the stable 48-bit prefix defeats BLE address randomisation, and relays re-advertise it. Parallel work was chosen over a strict order. |
| Old builds after rotating IDs go default-on (2026-10-11) | Accepted: phones on older MeshLive versions cannot discover updated phones (version 5 adverts are rejected by old scanners). | Privacy for everyone is preferred over compatibility with old installs; an update path for GitHub-release users is being designed. |
| Identity toward strangers (2026-10-11) | Strangers who connect get a per-epoch pseudonymous identity; the real long-term identity is revealed only to verified contacts or after the callee accepts a call. Designed, reviewed and coded 2026-10-11 behind `test_bmh3_pseudonyms` (default off; see "Pseudonymous strangers: design choice" below); the public tracking warning is narrowed (not removed) until its phone test passes. | Rotating IDs (P5 PASS) and BMH3 stop passive tracking and sniffing, but an Everyone-mode responder still proves its identity to any active prober; the chosen direction keeps calls with strangers possible. |
| Privacy defaults ship ON in 0.2.0-beta (design decision 2026-10-12) | Rotating discovery IDs, the BMH3 identity-hiding handshake and stranger pseudonyms are on for user launches (`RotatingDiscoveryFeature.DEFAULT_ENABLED`, `ConnectivityFeatureDefaults.BMH3`, `ConnectivityFeatureDefaults.BMH3_PSEUDONYMS`); each Settings switch is the off-switch and an explicit choice wins; a DEBUG benchmark launch (any `test_` extra) still reads only its extras. The neutral Bluetooth name stays opt-in (the automatic rename is withdrawn). The public tracking warning is narrowed to what was measured, not removed. | Evidence: the combined privacy session (build 3996bfb2) (P5 passive tracker PASS, zero carry-over at 3 of 3 boundaries with the documented `--early-s 5` handling; BMH3 snoop check: cleartext in the BMH2 control, none of four needles in three stranger calls; pseudonym reveal after Accept, none on decline; P7 non-discoverable sets on Android 14+ only). Accepted costs: older builds and phones with the switches off cannot call or see updated phones; relayed calls and message exchanges need verified contacts while pseudonyms are on; streams still answer BMH2 (the source's identity stays visible to viewers); dual-listening keeps its hint bit. Not measured: battery (P6), the A15s name read, the active lease-key prober, fake adverts, relays with BMH3, abuse limits. |

Within live video, calls prioritize intelligible speech and short conversational delay, so a call's adaptive playout keeps the 10 s calm period, while streams keep a burst for 60 s. Capping scene-cut bursts at the encoder (a workstream in [PLAN.md](PLAN.md)) comes before any other congestion response.

## Retained policies

| Policy | Reason and boundary |
| --- | --- |
| Fixed broadcast profile | A congested child cannot lower the common encoder target. Picker maxima Classic 1500 / CoC 1200 / GATT 1100 kbps and 800/700/700 admission budgets are starting policies, not universal ceilings. Requested/effective settings stay visible. |
| Source-local thermal protection | None/Light preserve settings. Reduction ON caps Moderate at 90% and Severe at 80% of selected FPS/bitrate; OFF keeps warnings. Critical/Emergency/Shutdown stop/block either way. Restoration waits 30 s before relaxing one ceiling. Actual supported Camera2 FPS may differ and must be exposed. |
| One trusted viewer-middle child, forwarding initially OFF | A viewer-middle already decrypts locally, shares the authorized media-open key with an authenticated child and copies original ciphertext. A content-blind opaque relay is a different role. Incoming plus outgoing traffic consumes real local capacity. |
| Authenticated hop-local liveness | Immediate EOF/read/write/GATT failure wins first. Classic/CoC heartbeat 2 s with 8 s stale fallback; GATT heartbeat 2 s with 6 s fallback, including ordinary viewers. This carries liveness, not graph-wide bitrate/queue/thermal feedback. |
| Guarded route withdrawal | Authenticated LOCAL_CAPACITY acknowledges withdrawal and suppresses only that route for 30 s. Fresh cadence/audio/overflow windows protect local playback and siblings. End returns to discovery; unexpected loss searches for the original stream. |
| Call budgets and legacy protection | Normal video starting budgets remain Classic 250 / CoC 200 / GATT 180 kbps per direction, with explicit legacy rates/audio/batching/buffering. They are protections, not hardware maxima. Higher-rate tests challenge them before normal-policy promotion. |
| Unpromoted experiments | DEBUG output holding and GATT full-slot source-scan pause remain OFF. Holding worsened losses/freezes. GATT scan pause improved matched high-load delivery but did not pass timing/fidelity or complete lifecycle preservation. |

A 51-family limit audit separates measured starting policies, unproved caps, dormant code and security/queue invariants. Extra 500 ms buffering and 20-frame CoC audio batching need delay tests; the calculated 380 ms oldest-frame batching wait is not total audible delay.

## 2026-10-06/07 change ledger

Every app and host-tool change since build 7396b46e, taken from the per-build change-impact records (tip at the time of writing: 88001e31). Each row says what was broken (measured), what changed, and what to re-check before touching that area again. Debug-only probe plumbing (4c124713, 38108509, 8c642833, 47e9c5de, e3b65f1f, 549c95dd integrity, 19215020) adds measurement only.

| Build | Area | What was broken (measured) | What changed | Re-check when touching it |
| --- | --- | --- | --- | --- |
| 2cbdafb1 | GATT setup order | The ASUS-initiated call to the M31 queued priority, PHY update, PHY read and MTU within 1 ms; the M31 server's own PHY update failed (status 42) and the client got no callback for 12 s. | MTU -> services -> subscription -> client PHY; no server PHY request; 3 s MTU watchdog is retryable. Both call roles set up in ~1.9 s. | Any new request issued before MTU/subscription can collide again; rerun both call roles. |
| 2cbdafb1 | Classic candidates | S24 paged all seven bonded devices (watches/audio) before its target. | Only phone/computer/uncategorized classes, app-service UUID first. | Classic connection latency with many bonds. |
| 2cbdafb1 | Unexpected loss | A viewer that lost an active stream waited a fixed 8 s suppression before retrying. | Immediate retry with 0/1/2/4/8 s backoff after an active session. | Reconnect storms on a dead source. |
| 2cbdafb1 | Activity recreation | Rotation/theme/font changes recreated the activity and `onDestroy` stopped streaming. | `configChanges` declared; recreate only when no session is live. | Any new configuration-dependent UI. |
| 2cbdafb1 | Release R8 | JNI-constructed `NativeAomPacket` could be stripped. | Keep rule. | `assembleRelease` after native changes. |
| 2d89aea9 | Classic source scan | Low-latency viewer scanning during Classic setup: request-to-RFCOMM 3.7-4.3 s (two repeats). | Balanced scan while a Classic connection is pending: 1.7-1.9 s. | Classic first-surface time. |
| ed1c9a35 | Playout anchor | A late first keyframe anchored playout ~800 ms late; on-time frames overflowed the 11-frame decoder queue and looped on recovery keyframes (419/312 decoder drops in the 2d89aea9 baseline). | Re-anchor when a target is more than delay + max(delay, 150 ms) ahead; 0 decoder drops after. | Adaptive playout moves the same anchor (see interactions). |
| e6a079d7 -> 56edbeb6 | Software VP9 rate mode | VBR for `c2.android.vp9.encoder` (to escape Codec2 CBR cpu-used -8, VMAF 41) produced 810 then 500-600 kbps at a 250 kbps request and made GATT sources drop 73 packets in 5 s. | Reverted to CBR. | Never change a rate mode without measuring encoder output vs request on GATT. |
| e6a079d7 | Full-source signalling | A full source rejected a viewer that kept looping through cooldowns. | Sequence bit 0x80 marks a full offer; generation uses 0x7f; fresh viewers skip full offers. | Generation wraparound (127 -> 0) and reconnect targets. |
| 66f5cedd, 5a709ed5 | Source viewer scanning when full | Full LE sources kept scanning: 342 vs 27 lost frames, fan-out M31 5.2 FPS / 15 freezes; legacy msmnile Classic kept balanced scanning with 300-530 ms gaps. | Full sources stop viewer scanning on every stack. | Scanning on any phone carrying media costs airtime. |
| 61405b96 | Middle child scan | A middle's continuous low-latency scan for children stalled its upstream up to ~1 s (source dropped 278-965 packets). | Middles scan balanced on all transports. | Chain upstream drops. |
| 56edbeb6, e691513a, c39c75b6 | Native AV1 encoding | WebRTC tool restrictions cost 8.5 VMAF offline; Automatic frame dropping left 533 ms gaps (13.8 FPS); a fixed cpu-used table ignored that ASUS used 26 of 66.7 ms. | Full tools to 640x480@15; Automatic never drops frames; a speed governor tries slower presets within budget (VMAF 83.1 -> 84.4). | Governor trials can overrun for up to 15 frames; watch encoder FPS on weaker phones. |
| 6d0dc40a, a0c3b4d1, e691513a, 6068fd48, 5191f416 | Link capacity | Fixed 700/800 kbps per phone was wrong both ways (M31 0.36-0.52, ASUS/S24 0.88-1.0 Mbps); a weak-peer probe became a strong phone's ceiling; a relay ceiling cut fan-out to one viewer; double margins refused 2x300 kbps on a 0.89 Mbps phone. | Per-phone learned capacity: probe maxima (any peer) as prior, separate fan-out and relay proof/ceilings, fan-out ceiling needs two saturated children, separate record under 2.4 GHz Wi-Fi, single 20% per-link margin. | Learned values persist (7-day ceilings): run `tools/link_capacity.py report` before blaming hardware; reset stores when semantics change. |
| 5191f416, 33e8755e | 2.4 GHz Wi-Fi | M31 on 2472 MHz: GATT 0.36-0.46 vs 0.89 Mbps off; S24->M31 GATT 600 kbps 1.9 vs 13.0 FPS (Wi-Fi on vs off); 5 GHz and cellular Band 40 (on/off) unchanged. | Detection, pre-start popup, separate capacity record, preflight `radio_conditions`. | Every comparison must state each phone's Wi-Fi band. |
| 549c95dd | Large frames | Generated content gives 40-136 KB keyframes (camera 4-17 KB; byte-identical across builds). The 33-packet sender queue overflowed while one drained, dropped video and requested an equally large keyframe (M31->ASUS 42 drops, 3 recovery keyframes). | Keyframes and frames 4x typical get drain grace (1.5x measured drain, max 3 s, queue up to 4x) in the sender and relay queues; recovery keyframe requests back off 1-8 s (forced joins exempt). 0 drops, VMAF 77.0 -> 84.2. | Grace lets queues exceed their normal limit: every overflow path must remove until room (7b8c28c7). |
| ad4c3bf3, 6108cebd | Scheduled keyframes | Freezes matched scheduled keyframes exactly (5 s: 122 KB/767 ms; 25 s: 111 KB/635 ms; CoC 15 s: 130 KB/867 ms with every other 10 s interval at 15.0 FPS). | Safety keyframe 5 -> 15 -> 60 s; joins force keyframes, losses request them. | Any change to keyframe request plumbing must be tested with loss, or decoders may wait up to 60 s. |
| bcfe7b44 | CoC data integrity | ASUS-transmitted CoC SDUs over ~11.6 KB arrived corrupted (byte 11,627 at 16,384/12,000 bytes; 8,087/8,088 clean; other directions clean); in the app "Secure link decrypt failed" on 30-53 KB keyframe records. | CoC writes are chunked to min(socket TX, RX) packet size: ASUS->S24 CoC 6.8 FPS/decrypt failures -> VMAF 87.2, 0 errors. | Chunking lowers clean-pair throughput (0.71-0.77 vs 0.82-1.0 Mbps); never raise it without an integrity probe on legacy stacks. |
| 88001e31 | CoC control connect | Every M31-initiated CoC control connection failed in ~300 ms with status 133: `connectGatt` was given only the 2M mask, legacy advertising is on 1M and the M31 stack (no extended advertising) did not add 1M. | Initiate on 1M plus the preferred PHY, request 2M after connecting; the CoC chain S24->M31->ASUS now forms. | Any `connectGatt` PHY mask must include 1M. |
| d3538160 | Playout buffer | Bluetooth delivered frames 104-245 ms late in bursts; a fixed 250 ms buffer drained to zero. | Auto profile: 250 ms growing by lateness + 50 ms up to 500 ms, shrinking after 10 s calm; fixed profiles remain in Advanced. CoC p99 149 -> 84-117 ms. | Growth inserts short holds; repeated growth on one burst costs visible frames. |
| 718752aa | Rate control | VBR-only encoders (M31 OMX VP9: 906 kbps at a 600 kbps request) overshoot the link. The 2026-08-27 controller was retired for false downshifts. | Evidence-gated controller for Automatic only (link carried < offered AND drops or 400 ms backlog outside grace; 2 windows down to 85% of carried media - audio; 5 clean windows +10%; second-weakest child). It had not fired yet: remaining freezes are single huge frames inside grace. | It must stay quiet on short dips with empty queues (the retired failure). |
| dee4cf30 | Decoder output timing | Frames handed to the display with timestamps landed one refresh late (p95 83.7 ms > 83.3 gate; p99 to 284 ms). | Release at playout + low-latency decoding are defaults for viewers and calls: Classic ASUS->S24 15.0 FPS, p99 84.6 ms, first surface 2.2 s; at or better than every baseline in six cases; calls 14.8-15.0 FPS, 0 freezes. | Decoders without the low-latency feature fall back silently; verify per phone. |
| dee4cf30 | Auto/Advanced settings | Technical knobs were mixed with Automatic options. | Auto (default): Automatic rate + Auto playout, manual pickers hidden. Advanced: only manual CBR/VBR/CBR-FD and fixed playout, remembered separately; manual test launches enter Advanced for that session. | New tunables must be placed in exactly one mode. |
| 7b8c28c7 | Sender overflow (regression from 549c95dd) | After grace expired with the queue above its normal limit, `offerLatestAudio` removed one packet, failed its check and stopped the M31 source mid fan-out. | Overflow paths remove until room (sender and relay). | Any queue-limit change must exercise overflow while oversized. |
| e7324324 -> 16da94dc -> 04b8b042 | Fan-out service fairness | The M31's radio served one of two 300 kbps GATT viewers (S24 14.3-14.9 FPS) and starved the other (ASUS 0.9-5.3 FPS) for the whole session; one saturated child counted as peer-limited, so nothing was learned. The first per-second rule (e7324324) wrongly released a viewer that was only waiting for a recovery keyframe after one burst, and learned 219 kbps from one window. | Samples count only queue-overflow drops and flag keyframe waits; a child that gets < 50% of its offer in 8 of the last 10 s while a sibling is healthy is released (capacity message, 30 s rejection) and the 10 s carried aggregate becomes the fan-out ceiling: ASUS released after ~20 s, S24 14.7 FPS, M31 learned 428 kbps. | Keyframe waits and large-frame drains must never count as starvation; if every child is poorly served it is shared overload (rate control), not a victim. |
| Host | Gates and harness | 5 s startup / 3 s discovery gates were loose against measured 1.9 s setup and 0.2 s discovery; a second preflight write failed Classic cases with WinError 5 (scanner lock). | `device-run-v3` (3000/1500 ms); preflight writes once and renames with retry; Wi-Fi band per participant; whitelisted per-case `tunables`; `tools/deploy_build.py`, `tools/link_capacity.py`; 40-pattern `coverage` input; Gradle configuration/build caches. | Old results keep their v2 thresholds; compare like with like. |
| 04b8b042 -> 73f517c5 | Temporal layers (root-cause A, see [PLAN.md](PLAN.md)) | Every congestion response dropped reference frames and needed a recovery keyframe, which overloaded the same link again; a weak fan-out child forced "slow everyone or release it". | Automatic encodes droppable frames: authenticated flag (inner BTV3 bit 7, BMV1 bit 7); source, relay, receiver and call queues skip them when >= 125 ms behind or full, with no keyframe wait; thinned relay children do not request upstream keyframes; thinning counts as rate/capacity evidence. MediaCodec VP9 requests `android.generic.2` and marks a frame droppable only when its bitstream proves `refresh_frame_flags == 0` in an all-error-resilient session. | Measured on 73f517c5: S24 c2.exynos and M31 OMX VP9 produced 50% verified-droppable frames (the S24 encoder reports no schema yet layers); native AV1 SVC decoded on S24 HW and M31 SW AV1. |
| 73f517c5 -> b5ebe110 | Encoder output-rate trim | With layering requested, c2.exynos.vp9 (S24) emitted 415-483 kbps and OMX.Exynos.VP9 (M31) 291-481 kbps for 300 kbps for the whole session; the extra load made viewer slots look full. | Automatic scales the encoder's internal target by request/measured after two 5 s windows > 10% over (never above the request; undershoot returns the trim). DEBUG extras `test_temporal_layers` / `test_encoder_rate_trim` enable same-build A/B. | Same-build A/B on b5ebe110: S24 trimmed 435 -> target 206 -> output 297-347 kbps. |
| b5ebe110 -> c4db9240 | Native droppable flags, keyframe floor | Same-build A/B: libaom SVC rate control chose QP 15-30 for base frames right after forced keyframes (27-72 KB, one 5 s window 1060 kbps for 400): CoC fan-out S24 5.2 / M31 0.2 FPS vs baseline 14.6 / 14.9. Mid-stream forced keyframes were 62-72 KB at QP 15-26 in both variants. The largest bursts overall were scene-cut P frames (50-62 KB vs 3 KB average), not keyframes. | Native layering uses per-frame `AOM_EFLAG_NO_UPD_LAST/GF/ARF/ENTROPY` under single-layer rate control (which took the same scene cut from QP 10 to 51). Forced keyframes are floored at last inter QP - 8 for that frame. | Diagnostic run on c4db9240 pending at the time. |
| b5ebe110 -> c4db9240 | Link self-measurement (root-cause B), layered admission | Fixed priors and learned ceilings refused topologies: M31 GATT relay needed 807 kbps against 0.9 x 774; the M31 fan-out ceiling (428) admits one 300 kbps viewer. | Each secure reader times large records (packet-train dispersion excluding already-buffered bytes); the receiver reports its measured arrival capacity to the parent every 2 s in a version-2 hop-local heartbeat, recorded as the phone's measured probe. Fan-out plans the first child full and others at their measured base-layer share (re-planned live); a relay that does not fit at full rate waits 2 s of frames and relays when receive + base-layer forward fits. | Pending. Mixed old/new builds are incompatible (v2 heartbeat). |
| c4db9240 -> c777b381 | Link pacing, measured picker, frame budget | M31 OMX VP9 600 kbps GATT -> ASUS: the sender queue read 0/33, 0 ms while frames arrived up to 2.2 s late (layered) / 1.0 s (single-layer): the Bluetooth stack absorbed bursts, hiding the backlog from thinning and audio priority. GATT self-measurement read 1.07-1.14 Mbps vs a 1.005 Mbps integrity probe. | Writes paced at 1.2x the child-measured rate with 50 ms burst credit (source and relay); trains need >= 8 KB over >= 60 ms, estimate = window median; bitrate picker maximum = measured link minus audio/headers; native inter frames capped at 250 ms of the per-viewer link share. | Pacing A/B on c777b381 (see next row). |
| c777b381 -> ccaa9c9f | Layering/pacing/rate-control interplay | Same build: VP9 old 12.8 FPS / VMAF 84.3; layered unpaced 10.1 / 73.5; layered paced 13.3 FPS, 1 freeze, but VMAF 40.5 (every thinned window counted as congestion: 600 -> 240 kbps, then the trim judged the lagging VBR encoder and set 120) and 96 audio drops (audio waited for credit); single-layer paced 8.9 FPS (nothing droppable in the held backlog). Native AV1 CoC 600 unchanged (14.7 FPS, VMAF 87.6/87.7). | MediaCodec encoders are not asked for layers (error-resilient layered VP9 cost ~11 VMAF); only chronic thinning (8 of 10 windows) is rate evidence; the trim skips two windows after a request change; pacing engages only after droppable frames are seen; audio never waits for credit. | Limits run on ccaa9c9f (see next row). |
| ccaa9c9f -> 8d460c9a | Native droppable frames were not droppable | CoC ASUS->M31 native AV1 1000 kbps: the sender thinned 3 frames marked droppable and the M31 AV1 decoder failed (CodecException 0x80000000), forcing a reconnect. libaom's single-layer real-time reference structure overwrites per-frame `AOM_EFLAG_NO_UPD_*` flags unless an external reference config is set. Earlier runs never thinned a native frame, so the A/Bs could not catch it. | Explicit `AV1E_SET_SVC_REF_FRAME_CONFIG` every frame under single-layer rate control; every AV1 frame (native or MediaCodec) is marked droppable only when its OBU frame header proves `refresh_frame_flags == 0`; mismatches are logged and sent as reference frames. | Thinning run on 8d460c9a, including a 1200 kbps CoC case that forces thinning. |
| 8d460c9a -> 36fc9abf -> 643926ef -> 1f47f343 | Evidence-gated layering | Verified droppable frames kept the decoder intact but cost ~2.5 VMAF at the same rate. A 0.75 load trigger kept GATT 900 kbps (93% of a 1.0 Mbps link) layered (VMAF 87.7 vs 90.2) although single-layer had no freezes; a startup report (786 kbps from two trains, then 996) and CoC's low train median (600-850 for 632 offered) caused false triggers. Over the link layering consistently helped: CoC 1000 kbps 0-1 freezes and 0-49 audio drops vs 1-4 freezes and 180-358. | Droppable frames only while a link is tight: overflow drops, thinning, a congested backlog outside large-frame grace, offered load above the best known capacity (max of child measurement and stored probe), or the hop-local report's tight flag (a relay's child link, or the receiver's own playout delay pinned at its adaptive maximum, since with layering off the stack hides congestion from the sender); off after 20 s of room. | Adaptive run on 1f47f343. |
| 1f47f343 -> a610e5b1 | Zero-cost layering when calm | CoC 600 kbps never layered on 1f47f343 yet scored VMAF 84.8 vs 87.4-87.7 with libaom's own references: the minimal explicit LAST+GOLDEN structure itself cost ~2.5 VMAF. libaom clears explicit references only when an encoder is created. | Calm streams keep libaom's own one-layer references; the first tight signal switches to an explicit structure mirroring it (LAST rotating slots 0-5, GOLDEN slot 6, ALTREF lagging) from libaom's last refreshed slot; after 20 s of room the encoder is re-created with one keyframe floored at the last inter QP. | Run on a610e5b1. |
| a610e5b1 -> 85531a07 | No frame loss (design direction) | Frame dropping was rejected as a congestion response for an app meant for critical, constrained situations, and the multi-hop tight flag broke the hop-local rule. Every pre-existing overflow path also dropped frames (drop-until-keyframe), which caused the recovery-keyframe loops. A scorer regex had also stopped reading sender drops since 73f517c5 (an added "thinned" log field). | Layering/thinning removed; unbounded ordered sender and relay queues; receiver decode/audio queues sized for the full adaptive delay with backpressure; late audio/video played; audio follows the clock with a 0.96-1.06x pitch-preserving speed; rate control on backlog or child-reported lateness, following the weakest child; relays withdraw a child that stays > 2.5 s behind for 3 s; playback options are adaptive ranges up to 0.6/1.2/2.5 s (Auto 2 s); calls unchanged; ceilings expire after 30 min and on app updates. | Measured on 85531a07: CoC ASUS->M31 1000/1200 kbps (above the link) 0 freezes, 0 audio drops, VMAF 88.0-88.3/87.1 (dropping builds: 1-4 freezes, 108-358 audio drops, VMAF 80.8-86.3); CoC 600 14.6 FPS VMAF 87.4; GATT 900 14.8 FPS VMAF 89.5; M31 VP9 600 12.3 FPS, 3 freezes, VMAF 81.2 (84.3 before: rate cuts during VBR bursts). Overloaded links showed 9.9-12 visible FPS: the child's delay stepped 250 -> 2000 ms because the parent learned only at the maximum. 3-phone cases did not run (a participant's Bluetooth was off). |
| 85531a07 -> 3641df77 | Child delay-trend report | See previous row: late lateness signal. | The hop-local report carries the child's added playout delay (at most every 1 s when it moves 50 ms); a rise of 100 ms between reports is congestion for the parent's rate control. DEBUG QP-bound switches for the hardware VP9 burst experiment. | Measured on 3641df77: pairs better (CoC 1000 14.0 FPS, 0 freezes, VMAF 88.6; GATT 900 VMAF 89.7) but 3-phone cases collapsed: chain ASUS 1.0 FPS, GATT fan-out M31->ASUS+S24 1.4/7.9 FPS, CoC fan-out VMAF 65-79. QP floors 20/32/45 had no effect on the M31 (identical 134,918-byte first frame): its OMX VP9 ignores video-qp bounds. |
| 3641df77 -> 9c76a1cc | Delay-gradient rate control, fan-out rollback, ceilings kept | Clearing learned ceilings admitted a relay and a second GATT viewer the M31 radio cannot carry (they measure airtime, not queueing); shared overload had no rollback; the controller cut on every window with a draining backlog and on single delay steps (S24 300 -> 120 kbps in 10 s). | Decrease only on a growing backlog or a child delay that rose in two consecutive reports, at most every 3 s; hold while draining; step up after 3 clean windows. When every fan-out child is starved, withdraw the newest. Ceilings survive updates with a 6 h re-check; a relay detached for its own instability records a relay ceiling. | Measured on 9c76a1cc: CoC fan-out S24 14.4 FPS VMAF 84.4 (was 12.5/79.0); chain relay M31 14.5 FPS (was 10.5); GATT fan-out ASUS/S24 5.3/13.3 FPS (was 1.4/7.9); pairs 13.4-14.8 FPS, 0 freezes, VMAF 87.4-89.4. Chain end phone 3.2 FPS with 463 audio drops (see next row); CoC 1200 not run (ASUS advertiser timed out at setup). |
| 9c76a1cc -> a99f1ef7 | Relay trend withdrawal; stream startup audio kept; keyframe shaping A/B | Relay join glitch root cause: the joining child's keyframe request made the S24 hardware VP9 emit 55.7 KB (1.5 s of the stream), which crossed the parent link while it fell 791 -> 269 kbps to share airtime with the new child link: 1.7 s arrival gap, 1.3 s stall on the relay. The chain's M31->ASUS link carried 209-278 kbps of a ~330 kbps stream and was withdrawn only after 15 s, 5 s behind; the ASUS dropped all 463 audio packets as late startup. | Withdraw a relay child whose backlog is >= 1.5 s, grew >= 500 ms over 3 s and is not draining. Streams play late startup audio (calls unchanged). DEBUG test_keyframe_shaping lowers a hardware encoder's target for one app-requested keyframe and restores it after. | Measured on a99f1ef7: native AV1 pairs 0 freezes, 0 audio drops, VMAF 86.9-90.1 (CoC 1200 above the link 10.5 FPS); the chain relay was declined from the learned M31 GATT relay ceiling (625 kbps vs 807 needed), so the S24 served both phones directly (a failed requested chain, a real airtime limit). Keyframe shaping is not promoted: hardware keyframes in this run were 7-9 KB unshaped (they are content-dependent, 8-55 KB) and the M31 VP9 600 arms differed within noise (12.4 vs 13.0 FPS, 3 vs 2 freezes). The M31's large frames are four-frame scene-change bursts (~210 KB, 135+40+24+11 KB) at a fixed content point, not keyframes. GATT fan-out M31->ASUS+S24: the ASUS child stayed 2.0-3.7 s behind (7 FPS) because the M31 encoder averaged ~1.7x its 120 kbps floor target and the trim never fired. |
| a99f1ef7 -> 454cf485 | Call rate adaptation; trim on averages; backlog starvation | Calls had no rate adaptation and ignored peer heartbeats; the call relay forwarded heartbeats across the hop; the encoder trim's consecutive-window rule missed bursty overshoot; the delivery-ratio starvation rule cannot see a no-loss backlog. | Calls send hop-local 1 s reports and run the delay-gradient controller (Automatic); the call relay forwards liveness only. The trim judges a 15-20 s average. A fan-out window is poor when the child is >= 2 s behind. DEBUG test_bitrate_mode forces a MediaCodec bitrate mode (M31 CBR test). | Measured on 454cf485: five live-camera calls (GATT ASUS<->M31 both roles, S24<->ASUS GATT/CoC, M31<->S24 GATT) 0 freezes in both directions; the fixed per-direction caps (GATT 180, CoC 200 kbps; legacy ASUS 240p/110-150 kbps) kept every call below the link, so call adaptation never acted. Stream pairs unchanged (native AV1 CoC 600/GATT 900/CoC 1000: 0 freezes, VMAF 83.2-88.4); M31 forced CBR identical to VBR (3 freezes, VMAF 80.5 vs 79.7). CoC 1200's 9.0 FPS is one 5 s window: the link fell behind before the first cut and the viewer's delay grew 250 -> 2000 ms in sub-threshold steps (other windows ~15 FPS). |
| 454cf485 -> 529f36cc | Relay join reserve | Relay join stall (see a99f1ef7 row). | A viewer that may relay starts with a 1.5 s reserve, capped at 75% of its profile's adaptive range (Automatic 1562 ms); calm shrinking keeps it. | Measured on S24/A15s and M31/ASUS pairs and in a multi-phone window (2026-10-08). |
| 529f36cc -> next | Experimental layering restored; learned start rate; call ladder; call caps lifted under Automatic | Design rule (2026-10-08): experiments are never deleted; they stay off by default and labelled Experimental unless impossible by first principles. Streams started every link at the full request (CoC 1200 overloaded before the first cut). Calls were capped at 150-250 kbps per direction with fixed buffers; uncapped GATT calls M31<->ASUS ran 300/500 kbps per direction with 0 freezes and adapted 800 -> 450-700. The backlog starvation release fired before the encoder trim could correct an overshooting M31 and left the ASUS with no route. | Temporal layering restored as an Experimental option (DEBUG `test_temporal_layers`, Advanced setting; off = unchanged behaviour). Links start at the rate that peer sustained (per transport/peer/radio state, 24 h), calls seed an unknown peer at ~half the measured link. Calls: delay adapts within 200/600/800 ms bounds, below the bitrate floor the frame rate drops (to >= 5 FPS) at constant bits per frame, no audio dropped at call start, fixed caps lifted under Automatic (CBR/VBR keep them). Backlog-based starvation needs 24 of 30 s. | Frozen as 96d7c7bd, then 5a2d93a7 (+Opus config normalization; A15s audio/calls now play) and ec093715 (+arrival-only playout growth with adaptive decoder feed-ahead, no reconnect loop on local media failures, learned libaom cpu-used/4 input buffers/fast tools). ec093715 was measured only through its successors (next rows). |
| ec093715 -> 57484598 | GATT pacer double-charge | A blocking GATT write already spent its transmit time, and `LinkPacer.onSent` charged it again from the return (135 KB, 1086 ms write + 1287 ms idle). | The charge starts at the write start in the sender and the relay forwarder. | Regression pairs and the 4-phone mesh run on 57484598: no freeze traced to the pacer; GATT app-queue wait about halved; M31<->ASUS GATT improved, including M31 VP9 300/600. The S24->A15s VP9 freezes are 0.65-1.3 s scene-cut arrival gaps that ec093715's correct (smaller) playout buffer no longer hides. In the mesh run the ASUS<->A15s link carries about 65 kbps in both directions on CoC and GATT (direct pair too); the route was withdrawn and fell back. A 6.95 FPS CoC S24->A15s window was a 4.1 s radio stall during an overlapping M31/ASUS start; quiet repeats measured 14.5-14.7 FPS and VMAF 88.1. |
| 57484598 -> ff0e24e1 | Call FPS floor from geometry; stream burst memory 60 s; event-based saturation; learned rates per build | ASUS->S24 calls at 900/1000 cut to 7-10 FPS for about 11 s (ladder below 0.4 x request, though 426x240 held 15 FPS at 110 kbps). The S24->A15s second scene cut froze after the delay shrank below the gap. A pinned delay read as "late" for 12-17 s and cascaded cuts. A15s->S24 AV1 started at a stale 240 kbps learned on the old build (VMAF -3). | The ladder floor is min(0.4 x request, 0.1 bit/pixel of the effective geometry). Streams keep a grown delay for 60 s (calls 10 s). `saturated` needs late media within 2 s. Sustained rates are keyed to the install. | Measured on ff0e24e1 (three runs): ASUS call senders at 15 FPS. A15s->S24 AV1 600 VMAF 78 -> 84.5 (starts at the request). Several GATT stream pairs gained one 250-300 ms first-scene-cut stall (larger frames at the full start rate; root fix is burst capping). The GATT ASUS->S24 900 call had 6/2 freezes: cuts use 0.85 x min(carried, measured), and on GATT the measured link (~770) overstates goodput (~640); the old false cascade had masked this. The A15s l2cap_listen failures cleared after a Bluetooth restart. |
| ff0e24e1 -> e3351065 | Lateness rise window; bounded-age experiment (off); v4 gates | Under the 60 s hold a rise 20 s after the previous report counted as consecutive, giving a false 600 -> 494 kbps cut that was then remembered. The call harness dropped scenario tunables, so earlier call tunable arms ran at defaults. | Rises count only within 3 s. Default-off `test_bounded_media_age` was reviewed twice by an independent read-only reviewer (10 findings fixed). The tunables pass-through is fixed. The `device-run-v4-user` gate profile (user-centred gates) is activated. | Bounded-age A/B: 48 call + 48 stream requests x 3, isolated. The call arm assumes a 500 ms peer base delay (100 ms queue budget) because setup does not exchange the playback profile. |

## Compatibility audit 2026-10-10 (offline, codec and YUV)

The pipeline was compared with pinned upstream code: Media3 `8c6678b6`, WebRTC `42995c38`, libyuv `eeb06a64` (`convert.h`), and the Image/MediaCodec API docs. Statuses: UD = upstream-documented, not locally tested; EXP = experimental. Every entry is in the device-family policy registry with an off switch (`test_device_policy_off=<id>`). Change impact: every video decode path (streams, relays and calls, both roles, all transports) on all phones needs retest. The same applies to camera-YUV encode input (native libaom and flexible-YUV MediaCodec) and the reference recorder.

| Finding | Our code | Upstream | Conditions | Type | Action | Test |
| --- | --- | --- | --- | --- | --- | --- |
| A decoder that fails to start fails the stream or call | `VideoStreamDecoder` init (one decoder) | Media3 `MediaCodecRenderer.java#L1295-L1360` (`maybeInitCodecWithFallback`) | Any phone where a hardware decoder is exhausted or rejects the format | Lifecycle | `decoder-init-fallback` (UD; normal app sessions only; at most 3 attempts; callbacks are scoped to each attempt). `test_decoder` requests and benchmark launches keep the former single choice. The policy software decoder falls back only to software. `BENCHMARK decoder requested/effective/reason`, scored as failed when they differ | `CodecPolicyTest`, `test_score_device_run`; phone: concurrent decodes on a low-instance SoC |
| Only `c2.android.vp9.decoder` recovers from a runtime CodecException | `PrivateCallMedia` decoder catch | Media3 `#L994-L1003`; `CodecException#isRecoverable` | Recoverable codec errors on any decoder | Recovery | `codec-exception-recoverable-reinit` (UD, on; calls only; at most 2). Stream receivers still end (proposal) | `CodecPolicyTest` |
| Surface loss ends the call or stream | `MainActivity` `onSurfaceTextureDestroyed` | Media3 `MediaCodecVideoRenderer.java#L2622-L2640` (`setOutputSurface`; `detachOutputSurface` on API 35; release and recreate where `codecNeedsSetOutputSurfaceWorkaround`: API 28 and older, Fire TV) | The view is detached while audio could continue | Lifecycle | Not changed. Keeping audio and swapping the decoder surface is a product decision and needs a phone A/B | none |
| Frame-rendered listener deadlock below API 30 | `VideoStreamDecoder.createFrameRenderedListener` | Media3 `MediaCodecVideoRenderer.java#L3248-L3264` (AOSP change 1156807) | API 29, when the listener calls back into MediaCodec | Lifecycle | Not affected: the listener only records counters (a guard comment was added) | review |
| Bitrate adjuster for `OMX.Exynos.VP9` | `EncoderBitrateAdjuster` (DynamicBitrateAdjuster port) | WebRTC `HardwareVideoEncoderFactory.java#254`, `FramerateBitrateAdjuster.java` ("codecs that assume the framerate never changes") | OMX.Exynos. VP9/H.264 after frame-rate drops | Rate control | Keep Dynamic. The M31 overshoots at a fixed FPS that `KEY_MAX_FPS_TO_ENCODER` caps, while FramerateBitrateAdjuster corrects undershoot. Arm `omx-exynos-framerate-adjuster-arm` (EXP, off; uses the delivered FPS) | `CodecPolicyTest`; phone: M31 A/B under link/thermal FPS limits |
| Forced periodic keyframes | `KEY_I_FRAME_INTERVAL` | WebRTC `getForcedKeyFrameIntervalMs` (QCOM VP8, API 23 and older) | VP8 only | n/a | None (no VP8; minSdk 29) | none |
| No single "AV1 supported" flag | `NodeCapabilities` encode/decode masks; layering read back from the output format | libaom/libvpx SVC examples, dav1d | All | Capability | Already conforms | existing |
| YUV copies: unchecked per-sample Kotlin fallbacks; `getCropRect` ignored; chroma `w/2` (Kotlin, native, recorder) | `CameraVideoEncoder.copyYuv420*`, `native_aom_encoder.cpp`, `ReferenceRecorder` | libyuv [`Android420ToI420`](https://chromium.googlesource.com/libyuv/libyuv/+/eeb06a6451ba2e21f8ff272c7b42fc62b9faecc5/include/libyuv/convert.h#915); `Image#getCropRect`; `Image.Plane` | Non-default crop, odd sizes, buffers that end at the last sample | YUV | `YuvPlaneCopy`: checks bounds once and writes nothing on failure; chroma `(n+1)/2` everywhere; I420 buffers sized by `i420Bytes`. Plus `yuv-image-crop-rect` (UD, on): encoder input and reference recording use the crop. The full-image crop gives the former result | `YuvPlaneCopyTest` (padding, NV12/NV21 views, trimmed buffers, crop, scaling, sizes) |
| A `ByteArray` per encoded frame | `CameraVideoEncoder` output | none | All | Memory | Not changed: ownership passes to the send queues (MemoryThermal owner) | none |
| GL/EGL driver workarounds | none (TextureView only) | Chromium `gpu_driver_bug_list.json` | n/a | Graphics | Not applicable | none |

Device families we do not have: Media3 and WebRTC have no codec rule for API 29+ that touches a feature we use (VP9/AV1 MediaCodec, Surface or flexible-YUV input). Their device lists are for API 28 and older, Fire TV/MiTV, VP8, HEVC MV or audio formats. So no family-scoped codec entry was added.

## Known interactions (second-order effects)

- **Queue grace <-> overflow paths:** large-frame grace lets sender/relay queues exceed their normal limit; code that assumes one removal makes room breaks (7b8c28c7). Test overflow while oversized.
- **Queue grace <-> rate control:** grace intentionally hides big-frame backlog from the rate controller, so single huge frames freeze without a downshift; sustained overload still downshifts.
- **Keyframe interval <-> recovery:** with a 60 s safety keyframe, recovery depends on requests (decoder errors, drops, joins). The 1-8 s backoff applies to unforced requests, which include relay-forwarded child joins: a child joining shortly after a recovery may wait.
- **Scanning <-> media:** any BLE scan on a phone carrying media (source when full, middle, legacy msmnile Classic) costs airtime and caused 0.3-1 s stalls.
- **Learned capacity <-> radio state and peers:** stores persist; Wi-Fi band, weak peers and relay vs fan-out produce different numbers. A relay ceiling (M31 GATT 774 kbps) now refuses that relay for 7 days.
- **CoC chunk size <-> throughput:** safety on ASUS-class stacks costs ~15% CoC throughput on clean pairs.
- **Probes <-> phones:** `link_capacity.py` restarts the app per direction and writes probe maxima into each phone's learned store.
- **Playout anchor <-> adaptive delay:** re-anchoring and adaptive growth both move the shared audio/video anchor; keep `MediaPlayoutClockTest` covering both.
- **Decoder timing <-> calls:** release-at-playout/low-latency now apply to private calls too.
- **Keyframe waits <-> service metrics:** after an overflow the sender drops video until a keyframe, so offered >> sent is self-inflicted; samples flag it, and fairness/rate decisions must use overflow drops only.
- **Temporal layers (experimental) <-> encoder rate control:** asking a vendor encoder for layers changed its bitrate accuracy (S24/M31 Exynos VP9 +40-60%), and libaom's SVC layer rate control mis-sized frames after keyframes. Keep this in mind before reviving any layered scheme.
- **No loss <-> latency <-> rate control:** with nothing dropped, a link slower than the stream shows up only as backlog and late arrivals; rate control must react within seconds or viewers drift toward the maximum delay. Audio drift is corrected by speed, never by skipping.
- **Temporal layers <-> hidden stack queues:** the lower visible FPS with layered M31 VP9 was not the decoder (hardware, software, low-latency off and release-at-playout off all gave 9.5-10.5 FPS); bursts sat in the Bluetooth stack where thinning cannot see them. Pacing fixes that only when frames are droppable.
- **Reference structure <-> quality:** how frames reference each other is itself a quality setting (~2.5 VMAF between libaom's own one-layer structure and a minimal one). Any change to references needs a same-build VMAF comparison where nothing is dropped, not only congestion cases.
- **Rate decisions <-> measurement noise:** predictive triggers built on the train estimate fired falsely (startup trains, CoC median 10-25% low); delivery evidence at the receiver (late arrivals) is the trustworthy signal for its own link.
- **Thinning <-> rate control <-> trim:** three adaptive loops on one stream. Thinning answers bursts within 125 ms, the rate controller only chronic shortfall, and the trim only steady encoder bias after a settling period; a faster outer loop collapsed VMAF to 40.5.
- **Hop-local rule <-> relays:** a relay cannot slow the source, so a child it cannot serve is withdrawn to reroute; only the link's own child reports lateness to its parent.
- **Encoder requests <-> actual bitstream:** an encoder flag or schema is a request (libaom ignored per-frame no-update flags; the S24 encoder layered while reporting no schema). Anything that drops frames must check the bitstream, and every A/B for it must include a case where frames are actually dropped.
- **Wi-Fi <-> every measurement:** 2.4 GHz Wi-Fi halves affected phones' Bluetooth; results without `radio_conditions` are not comparable.

## Preserved fixes and causal evidence

A three-phone workflow verification reproduced two instrumentation/input defects without changing normal family budgets. S24's decoder reported 48000 Hz mono `audio/raw` without `KEY_PCM_ENCODING`; [Android's format contract](https://developer.android.com/reference/android/media/MediaFormat#KEY_PCM_ENCODING) defines the absent key as signed PCM16. Restricting that default to the actual supported output restored finalized accepted-PCM evidence on the matched ASUS -> S24 request. Receiver capture on all three exercised paths finalized without writer errors or overflow, but incomplete AudioTrack brackets and unavailable independent recording prevent screen/speaker timing or acoustic acceptance.

Native private-call geometry was reinterpreted after legacy negotiation: ASUS 640x480@15 became 426x240@15, and S24's 640x360 became 352x288. The original crash is now an explicit input rejection before media starts. This proves failure handling, not call performance; exact matching native input requests remain necessary. No resize, microphone fallback or family-budget override was introduced.

The same verification kept first-surface timeouts, the native rejection and one empty-message runner failure visible rather than masking them. The benchmark runners now stop before timed controls on failed setup, scope trace pulls to executed processes and record finalization errors separately; participants are verified stopped, and unconfirmed cleanup is quarantined. In one analysis, caching repeated path lookups cut dependency inspection from 95.454 s to 0.638 s with the same 12 dependencies; this measures host tooling cost, not phone performance.

An offline reanalysis that omitted `host_end_ns` falsely marked three post-change executions as needing retest. The state join now accepts a verified original time only for the same exact request, manifest and digest and keeps the newer measured verdict; missing or conflicting timing stays conservative, and a later analysis cannot make a pre-change execution fresh.

The offline measurement integration was verified separately. The video generator's 216 host tests and native mixed/detail validators passed; its samples are contract smoke inputs rather than phone loads. A 44-frame metric check measured identical-source SSIM 1 / zero pixel error / VMAF 99.94; deliberately holding frames reduced SSIM to 0.825, VMAF to 66.18 and PSNR to 15.01 dB. These establish detector and metric behaviour, with zero phone executions.

The integration preserves five measurement boundaries: audio/video reference pacing shares monotonic time while wire PTS is separate; continuous sound is not silently restarted after video off/on; AudioTrack time is a bracketed estimate invalidated by missing/reset/stale/underrun evidence; source PTS cannot prove screen pacing; whole-media simulation occurs before encryption and leaves controls/order/security intact. Finite recording/container clocks, stage proxies and physical sound/screen capture remain distinct. Invalid or generated capture provenance cannot earn component passes. Full-frame fidelity is still primary; native colour application and real phone/lifecycle preservation remain unmeasured. No normal rate/FPS/allocation/scan/heartbeat policy was promoted by host checks.

An offline workflow audit closed concrete tooling defects (dropped earlier change files, accidental expansion of empty selections, incomplete input freshness, unsupported imported audio-gap metadata, unbound extraction clocks). These are host-tool changes with the same frozen app, not preservation or streaming approval.

A native complexity reanalysis reproduced the retained 720x480/30 camera comparison using full unmarked luma. ReadySetGo meets the numeric SI/TI reference; ParkJoy retains a TI-maximum shortfall and ShakeNDry five shortfalls. Original camera cadence assumptions and unspecified colour tags remain explicit. The six-statistic screening output is diagnostic; it does not establish actual encoder load, colour application, natural demand on other modes or phone acceptance. Native programmes and the framing contract now exist; physical colour/detector validation and selected-encoder load evidence remain open.

A first-principles audit challenged that screening rule without lowering acceptance targets. SI/TI measure luma only, and TI is the standard deviation of pixel differences: a uniform flash changes every pixel but returns zero TI. Six medians/extrema cannot prove chroma demand, encoder bursts, exhaustive content coverage or Camera2 behaviour. ReadySetGo remains a useful race/pan workload; ParkJoy and ShakeNDry remain useful distinct workloads rather than rejected inputs. A media census distinguishes native references from captured app-screen MP4s. Generated source media cannot recreate measured historical receiver output.

That audit reproduced three failures in five focused replay tests before fixing signed/rational timestamp arithmetic and unrepresentable scheduling. It also found a missing associated-data mutation in the named tamper test, non-finite gate inputs, receiver-buffer selection/request-ID defects, lost measurement metadata on blocked retries and output-overwrite/stale-command hazards. A generated package-name scaffold, retired five-phone builder tests and label-word assertions supplied no current behavioural proof and were removed. Small regressions for attempt ownership, FIFO/config ordering, immutable bytes and authentication remain useful. Policy snapshots protect configured values; they do not measure capacity or optimality. No normal transport/rate/security/family policy was promoted.

A native tool check uses generated variable-rate frames with original timestamps and known 80/120/160 ms edge offsets. A broad search reports ambiguity; an independently chosen narrower fixture window pairs those events. The analyzer now reports frame-sampling uncertainty instead of using nominal FPS and greedy repeated-flash pairing. The lossless codec diagnostic verifies eight frames, exact PSNR and hash binding in fresh output. These are host calculations with zero physical executions; real optical capture/provenance and actual encoder load remain open.

A scheduler reanalysis of a retained concurrent S24 -> S22 trace covered 19.912 s of the S22 receiver. Closed doFrame/DrawFrame maxima are 8.493/10.829 ms; the receive thread has a 120.615 ms maximum closed sleep and 10.924 ms runnable wait. It reports 28 ftrace setup errors. Both clock snapshots precede the trace interval, so they cannot bracket application events throughout it. Wakers for those long receive sleeps are absent. These bounded observations do not establish the initiating socket/RF cause, physical screen timing or a sole-variable comparison; output holding remains OFF and the playback defect remains open.

These before/after measurements justify their named changes while preserving authentication, ordered encryption, admission, source settings and family behaviour. A faster copy or repaired clock does not establish whole-flow quality.

| Fix / protection | Evidence and measured scope |
| --- | --- |
| Routing/media flag namespaces | Media discontinuity 32 had looked like mesh terminal 32; debug-integrity 16 like mesh control 16. Reserved outer routing bits are mapped separately while original media flags remain authenticated. Regression failed before/passed after. The mesh flag runs (ASUS, Xiaomi, M31, GATT 500 kbps lifecycle, 2026-10-02) restored downstream authentication/video after a recovery keyframe; the high-rate chain later withdrew. Real End stays terminal; ordered encryption unchanged. |
| Capture-age epoch conversion | MediaCodec translates monotonic/elapsed epoch rather than anchoring first output; 150 ms regression and measured 80-140 ms capture age retained. Native output uses the same helper. Native live clock comparison: before 0 submitted/rendered, 1727 decoder drops, video-minus-audio=-322180.7 ms; after 1329 surfaces, receiver loss/freezes 0, epoch correction 321737002 us, initial delta=-735.8 ms. Actual 485.5 kbps/14.6 FPS; two camera-input drops, minimum encoder 14.2/minimum visible 14.1788/p95=84.2 ms FAIL. Startup 2661.3 ms/launch 4646 ms scoped PASS. |
| Native raw versus live capture | Same-phone raw comparison: 501.5 kbps, min/median 15 FPS, 1357 renders, all input/network/A/V loss 0, freezes 0, p95/p99=83.8/94.2 ms, minimum visible 14.964, startup 2094.3 ms/launch 4003 ms, raw offset 0, initial A/V=-271.1 ms. p95 FAIL; raw capacity is distinct from live camera cadence. |
| Exact-size strided YUV copy | Native bounds-checked same-size copy retains crop/scale fallback. ASUS 640x480/15/1000 rolling copy p95 before 41.2-70.9 ms/after 2.2-4.4 ms. Pixel/padding/stride/final-bound/reject-before-write instrumentation passed on five phones. Samsung surface input does not use this path; Bluetooth/optical defects remain. |
| FIFO audio priority / decoder recovery | Audio is removed in place rather than restoring head video at the tail; config stays before keyframe. Wireless GATT fault runs changed repeated natural decoder failures to one injected restart over 4 m 15 s, 399 ms injected gap, zero audio/decoder loss; 703 ms camera-restart gap remained. Duplicate VP9 CSD removed without reordering distinct records; observed c2.android.vp9.decoder recovery bounded to two restarts per call with config/fresh keyframe. No cryptographic checks removed. |
| Call/camera ownership | Atomic incoming/outgoing admission prevents a losing tap closing a live incoming call; decoder offer/close serialize without recreation after teardown. Flexible-YUV release is camera-handler owned; late callbacks ignored. Surface call geometry intersects actual Camera2 modes before header; M31 426x240 failure resolved to 352x288/15 at unchanged 110 kbps. Camera-control before/after: three ASUS off/on/flip cycles caused four incoming freezes/2712.1 ms before; background lifecycle queue gave 0 over 8643 frames/about 9.6 minutes, close 622-717 ms. Deliberate remote-camera gaps remain separate. |
| Call discovery / CoC control lifetime | Incoming calls stop discovery like outgoing calls; measured scanner starts/stops 16/15 -> 18/18. Private calls retain established CoC GATT resources until teardown, separately from broadcast lifecycle. Delayed advertising switch cancels/serializes on stop. A 400 ms CoC/Classic drain helps terminal delivery where viable; it did not overcome severe reverse-route congestion. |
| GATT peer/byte ownership | Current-peer checks reject stale callbacks. Both async receive/transmit boundaries own callback bytes; rollback 401 versus 425, then 1130 versus 1221 produced mutation regressions. Separate write/notify characteristics protect legacy shared-value behaviour; bounded genuine reordering, one stale-cache refresh and legacy acknowledged serialization remain. Interrupted setup closes its own GATT; old 12 s timeout cannot close replacement attempt. |
| Ordinary GATT liveness | Native 640x480/15/500 no-heartbeat leaf kill/rejoin never reauthenticated; heartbeat repeat 43 receipts/fresh surface. Default authenticated 2 s/6 s liveness closes stale sessions and supports active and paused rejoin. Killed unfinalized phases and remaining jitter/recovery failures stay visible; no unauthenticated eviction/duplicate-filter removal. |
| Full-slot nonlegacy Classic source scanning | Complete-source comparison: normal 1828 slots/1804 rx, 23 interior transport holes + one encoder tail, 96.109023/0.980904/36.124423 (VMAF/SSIM/PSNR); scan-paused 1831/1830, one encoder tail, no transport/audio/decoder loss/freezes, 97.199021/0.983270/43.214704. p95/p99=65.7/102.4, minimum visible 27.5945 FAIL. Live lifecycle (S24 -> S22): paused scan loss 0/two freezes, p95=81.8, start 6389.999/rejoin 7791.999 ms; production 90 source/1482 audio/four freezes, p95=85, start 5442.999/rejoin 6054.999. Normal policy pauses only while admission is full, immediately scans when capacity frees, preserves legacy behaviour; not whole-flow restoration. |
| Capacity recovery / guard | Generation change invalidates suppression when a real slot frees; retry 12.54 -> 0.387 s, surface 18.09 -> 3.669 s. Child already withdrew, so not healthy-chain ancestor-loss proof. Guards consume fresh nonoverlapping cadence windows; sustained audio/overflow windows persist across keyframe clears. Source target fixed; authenticated LOCAL_CAPACITY acknowledges/suppresses exact route. |

## Historical profile

The historical S24 -> S22 comparator is Classic RFCOMM, c2.exynos.vp9.encoder, 720x480/30 FPS/1100 kbps CBR, Opus 32, Balanced 250 ms, thermal reduction OFF, production 32 KiB/event-driven 5 ms. It has no special priority or acceptance gate; new clean-source runs keep their own identities. The two private finite-replay recipes that once accompanied it are retired.

A raw-rotation comparison (2026-10-03) separates recorded raw/replay rotation 0 from Camera2 sensor/display rotation. Allocation is applied after selecting the outgoing encoder; an ordinary receiver needs a compatible decoder rather than the sender's encoder.

| Finalized evidence | Measured result and remaining scope |
| --- | --- |
| ReadySetGo CBR controls, complete fidelity (S24 -> S22, 2026-10-03) | Upright actual 720x480/30 verified by geometry screenshots; median 1093.5 kbps. Source/raw/audio/decoder counters 0, freezes 0, minimum visible 29.8 FPS, p95/p99=49.1/51.4 ms, selection startup 2797.5 ms. All 2230 active-source slots scored: 2228 encoded/received, two terminal input tails held, no transport holes, identical 10,155,835 payload bytes. VMAF 85.212807/SSIM 0.862696/PSNR 30.228005 dB: all picture gates FAIL; timing p95 FAIL. Terminal slots remain despite zero raw-miss counter. Whole-wall, Camera2, optical latency and acoustic quality UNMEASURED. |
| ParkJoy CBR (S24 -> S22) | All 1402 encoded AUs delivered byte-identically; source/raw/audio loss 0. S22 discarded 706 decoder frames, froze 14 times; p95/p99=50.8/1639.1 ms, startup 4585.3 ms. Local decode/playout FAIL despite complete delivery. Queue/input wait grew before recovery; responsible local operation unresolved. The original scorer correctly failed; an incorrect short summary was corrected. |
| ReadySetGo VBR (S24 -> S22) | Explicit VBR 1100, median 1098 kbps; source/raw/audio/decoder loss 0, freezes 0; p95/p99=50.5/51.9 ms, minimum visible 29.988 FPS, startup 3903.6 ms. p95 FAIL; separate allocation experiment. |
| Live Camera2 controls (S24 -> S22) | Historical live profile with microphone/holding OFF and real S22 PAUSED/LIVE/ENDED acknowledgements. Measured network/decoder/audio loss and freezes 0; p95/p99=67.6/115.6 ms, minimum visible 28 FPS: timing FAIL. No requested operator motion. Camera2 input loss, controlled-motion fidelity, optical latency and acoustic quality UNMEASURED. |
| ParkJoy and ReadySetGo CoC chains (A55 -> S22 -> S24) | Actual A55 -> S22 -> S24 at 500 initially renders, then overload withdrawal/replacement attempts. ReadySetGo: S22 p95/p99=102.1/168.2 ms, 265 audio drops; S24=118.7/306.2 ms, 229 audio drops, one freeze and two wrong-parent probes. Requested-chain FAIL. A participant loss interrupted final trace coverage; direct fallback is not chain success. |
| Classic chain controls, ShakeNDry (S24 -> S22 -> A55) | Requested S24 -> S22 -> A55 parents retained; Pause/Resume and planned child rejoin exercised. S22 p95=83.6 ms/startup 5303.2 ms; first A55 phase p95/p99=123.7/348 ms and three freezes; rejoined phase one freeze. Timing/startup/continuity FAIL; incomplete later cadence UNMEASURED. ShakeNDry is an easier control. |

## Output holding and timing interpretation

An isolated OFF/ON/ON/OFF comparison (S24 -> S22, 2026-10-03) changed only DEBUG holding, with the actual media intervals of each run recorded. ON A discarded 46 held outputs across 27 generations; ON B discarded 2 across 3. Different failure stages prevent a general cause claim; holding remains OFF.

| Run / holding | Median kbps | Source / raw / decoder / audio losses | Freezes | Worst rolling surface p95 / p99 ms | Startup ms |
| --- | ---: | --- | ---: | --- | ---: |
| OFF A | 1095 | 0 /2 /0 /0 | 0 | 50.5 /67.9 | 5431.9 |
| ON A | 1105 | 0 /0 /1049 /0 | 17 | 1656.9 /4165.9 | 4600.0 |
| ON B | 1093 | 173 /0 /0 /452 | 4 | 101.6 /250.5 | 3333.3 |
| OFF B | 1093 | 0 /0 /0 /0 | 0 | 50.3 /51.8 | 5062.9 |

| Original evidence | Retained finding |
| --- | --- |
| Same-profile observer ABBA | Four same-profile recorded-camera-pan runs: source/raw/audio/decoder loss 0, freezes 0, complete active-source fidelity about 97/0.983/43 dB PASS; all timing FAIL. Observer ON RMS notification interval error 7.318/7.090 ms; pooled p95=50.143/50.302 ms versus 33.333 ms period. |
| Motion timing findings | Actual callback intervals/RMS jitter, signed callback-target lag, phase/boundary gaps and unknown identities remain separate from reported codec/Texture timestamps. Focused timing regressions protect this distinction. No physical-screen/latency proof. |
| Earlier holding controls | Earlier hold OFF/ON/ON/OFF: source losses 950/1089/847/1025, audio 1329/1421/1637/1274, freezes 8/13/13/6 despite 30 FPS/about 1100 kbps and raw misses 0. Older-source and both-older runs also fail: source 1268/1156, audio 1623/1687, freezes 10/7. Both older runs encoded 1917/received 768/absent 1149. Failures on the old APK prevent attributing this to the newer holding build. |
| Samsung transport forensics and scheduler findings | Allowed Classic packet masks exclude the 3 Mbps family, but do not measure throughput/retransmissions. Sleeping threads alone do not prove RF, lock or socket cause. Request/completion, PID/session identity and exact pipeline stages remain required. |
| Active S22 Perfetto evidence | Real 30 s receive window: main draw max 9.940 ms; Bluetooth-read sleeps 326.443/279.815/272.303 ms woken by bt_main_thread. No demonstrated long UI block; upstream starvation's initiating cause unresolved. A later trace retained 22.902583 s of 30 in 32 MiB, overwrote 10,997,760 bytes; 685 app frame timelines on-time, main draw max 5.848 ms/runnable wait 4.459 ms. Full 30 s trace and video-pixel cadence UNMEASURED. |

## Source scanning and GATT liveness comparisons

An isolated scan ABBA (four runs, 2026-10-03) fixed ASUS native AV1 640x480/15/1000 CBR -> ordinary M31 leaf, pan + microphones, Opus 32, Balanced 250 ms, thermal OFF and default liveness. Only source search while admission was full changed. Heartbeat receipts 46/48/42/46 and maximum gaps 2027/2026/2019/2023 ms show no false 6 s disconnect. Pause B missed the 30 s first-surface wait before pause could act. All four failed; pause remains experimental pending middle/recovery/Camera2 preservation.

| Search / run | Actual kbps | Source loss / freezes / audio loss | Complete slots / received; held / terminal | VMAF / SSIM / PSNR dB |
| --- | ---: | --- | --- | --- |
| Normal A | 1002 | 342 /13 /246 | 1368 /1001; 367 /25 | 75.057100 /0.927047 /22.087648 |
| Pause A | 1003.5 | 27 /1 /16 | 1417 /1383; 34 /7 | 97.856398 /0.986541 /32.381456 |
| Pause B | 1003 | 27 /1 /17 | 1250 /1216; 34 /7 | 97.263075 /0.985365 /33.763580 |
| Normal B | 998 | 343 /12 /198 | 1372 /1018; 354 /10; one input hole | 75.658156 /0.928807 /22.130469 |

A heartbeat OFF/ON comparison at 1000 kbps emitted 1001/1002, lost 341/350 source and 180/212 audio frames, and froze 13/12 times. Full-source slots 1371/1374, received 1002/1016 and held 369/358 scored 75.407718/0.930562/22.812916 versus 75.795273/0.929948/22.366589: both fail. ON received 46 heartbeats with maximum gap 2024 ms and no false disconnect. At 500, ON emitted 501, received 46 heartbeats/max 2029 ms, losses/freezes 0, 1375/1374 slots/received plus one encoder tail scored 98.906669/0.985739/44.286051, a scoped fidelity pass; p95=83.8 ms still failed. Callback-after-API p95 ~49-50 ms, API generally < 2 ms and wakeup < 0.5 ms do not establish receiver delivery or RF/controller/OEM/Binder cause. Writer-monitor/between-operation waits remain unresolved; multiple in-flight writes caused busy rejection.

## Complete-source role and lifecycle controls

These comparisons (build 17732675, 2026-10-03) preserve actual load, missing slots and authenticated parents, including failed requested chains.

| Original evidence | Complete result |
| --- | --- |
| Xiaomi source 500 | Actual 501.5 kbps, 1374 slots/1373 rx, one transport terminal tail, no interior holes, loss/freezes 0; 99.303901/0.985725/44.421919 scoped picture PASS, p95=84.0 ms FAIL. Actual offered AV1 encoding and VP9 decoding are distinct capabilities. |
| Xiaomi middle 500 | After child join: source 136 drops; Xiaomi 8 freezes/40 audio/31 forward drops, p95/p99=177.6/489.7 ms; M31 five freezes/185 audio. Guard withdraws; three authenticated direct-source probes rejected. The stricter parent gate retains probes even without a later Receiving status; FAIL. |
| GATT actual 150 chain | M31 -> ASUS -> Xiaomi hardware VP9 640x360/15/150 VBR: actual 150, parents/source correct 90 s, source/raw/audio/decoder loss/freezes 0, ASUS forwarding drops 2. 1455 slots; ASUS 1454 rx, 75.938763/0.948341/35.169191; Xiaomi 1366 rx, 88 initial black + one tail, 71.132523/0.923284/23.536754. No interior holes; picture FAIL. |
| Classic actual 500 chain | S24 -> S22 -> M31, actual 504, parents/source correct. S22 all 1511 rx, p95=99.5/two forward drops, 98.180147/0.979798/42.392126 narrowly SSIM FAIL. M31 1377/1511 rx, 89.723430/0.940402/21.897214, p95/p99=117.2/201, minimum visible 14.0233, one freeze/audio drop, start 6522.1 ms: FAIL. |
| Live profile default lifecycle, raw full source | Live actual 1108, loss/freezes 0, Pause/Resume/rejoin works, but p95/p99=50.8/68, start/rejoin 5554/5998 ms FAIL. Raw 1838 slots/1837 encoded + rx, one interior input hole, no transport holes, 97.153880/0.983231/42.901578 picture PASS; one raw miss/p95-p99=50.8/67.1 FAIL. |

Earlier high-load failures remain: build 7051 live 133 source/2445 audio/five freezes, p95/p99=118.0/234.6 ms; scan-paused 0 source/audio/one freeze, 84.6/167.2 ms; restored older 362 live 222 source/1483 audio/12 freezes at 1092 kbps; 7359 live 572 source/3352 audio/14 freezes, 116.9/252 ms, start 5468 ms. Faster YUV still left ASUS -> Xiaomi 500 with two freezes totalling 910.3 ms, 11 audio, p95/p99=78.7/122.7 ms, min visible 7.91 FPS. Concurrent GATT 1000 chain emitted 998, lost 599 source and withdrew at middle p95=299.9 ms/18 audio. A 1000 request emitting 97 or VBR 1000 emitting 671.5 kbps is insufficient offered-load proof. Live GATT 1100 emitted 1044 but lost 1127 source/3705 audio.

## Allocation and bidirectional call-rate evidence

M31 allocation proof: build 8EDC native AV1/ParkJoy 640x360/15/500 CoC retained CBR/VBR/CBR-FD/Automatic through start, Pause/Resume and rejoin; median rates 499.5/575/499.5/500 kbps and minimum encoder 15/15/14.9/14.9 FPS establish allocation, not picture/timing/Camera2. Negative startup tests reject an unknown encoder/allocation and unavailable hardware-CBR.

Same-pan call-rate comparison: M31 caller/ASUS callee, ASUS software VP9 CBR/M31 hardware VP9 VBR 320x240/15, Opus 16/32, Balanced 250 ms, thermal OFF, all active slots/tails. Losses/freezes were 0 but timing/discovery failed. A lower effective rate does not measure the selected 250 kbps request; 500 picture proof applies only to that pan.

| Selected / effective kbps | Actual ASUS / M31 median kbps | Full ASUS -> M31 VMAF / SSIM / PSNR dB | Full M31 -> ASUS VMAF / SSIM / PSNR dB | Scoped result |
| --- | --- | --- | --- | --- |
| 250 /150-180 | 152.5 /180 | 93.156496 /0.959582 /38.007029 | 96.178402 /0.967227 /39.029018 | Both SSIM FAIL; requested 250 UNMEASURED |
| 250 /250-250 | 252 /250 | 98.309527 /0.975223 /40.742249 | 98.292415 /0.975506 /40.477562 | Both SSIM FAIL |
| 500 /500-500 | 499.5 /500.5 | 99.597340 /0.987616 /42.877168 | 99.548516 /0.987486 /43.467101 | Complete reference-decoder picture PASS only |

| ReadySetGo private-call evidence | Result |
| --- | --- |
| M31 caller 500 controls (build 8EDC) | Actual ASUS/M31=500/500.5 kbps, zero final source/raw/queue/audio/decoder loss/freezes, all 13 lifecycle gates PASS. ASUS p95=83.9 ms/discovery 4569 ms FAIL. Complete ASUS -> M31: 1296 slots/1293 rx, 99.630487/0.900486/31.416801; reverse 1287/1286, 99.615471/0.901407/31.922182. Both picture scopes FAIL SSIM/PSNR despite high VMAF. |
| ASUS caller (build BE31) | No MTU completion before existing setup deadline, no media. Setup FAIL; both media directions UNMEASURED. Rotation correction cannot approve an unstarted connection. |
| M31 caller repeat (build BE31) | Actual 499/500 kbps, 13 lifecycle gates PASS. ASUS incoming p95/p99=84.0/86.9 ms and one unexpected freeze FAIL; M31=68.4/83.8 ms, zero freezes. Complete picture/optical scoring remains separate; this does not cover ASUS-caller setup. |
| Six setup/Decline attempts and setup-order audit | ASUS normal fails twice; late-priority fails once/succeeds once; M31 caller normal/late succeed. PHY status 42 also appears in successful setup. Missing MTU callbacks before 12 s stay failed. Deferring priority is not a complete setup fix; Decline success has no media scope. |

Received-frame colour audit: source/RX decoded pixels were identical for 1293/1286 received frames on build 8EDC and 1340/1289 on BE31, mismatches 0; missing tails remain scored. BE31 Y/U/V PSNR: ASUS 30.385/36.039/37.087 dB, M31 30.799/35.060/36.067; SSIM: ASUS 0.917/0.858/0.882, M31 0.925/0.833/0.864. Controls reject a simple UV swap or +/-1-3-frame offset; input-copy loss versus encoder compression remains unresolved.

## Input integrity and measurement interpretation

Hard natural-motion inputs and matched SI/TI comparisons retain hashes, source maps, median/p95/peak at 720x480/30, 640x360/15 and 320x240/15. ReadySetGo exceeds the camera pan in both activities at all three. Native 120 FPS gives uniform 4/8-frame sampling; ParkJoy 50 FPS gives nonuniform 20/40 or 60/80 ms steps and lower peak TI; ShakeNDry is easier. Licences, transforms, cuts, loop seams and markers remain with the inputs.

An 854x480 input interpreted as 720x480 was invalid and excluded. Correct S24 offline VP9 720x480/30/1100 CBR, 300 frames scored 96.524126/0.98912/41.544698. A 936-AU live-raw trace matched sender/RX; first 900 slots/30 s scored 97.311021/0.983540/43.557065 without missing/black holds, but prefix fidelity is supplemental. Full active phases include initial holes/terminal tails; killed phases cannot approve complete lifecycle fidelity.

An early framing check: 90-degree metadata made 720x480 occupy 1080x1620 on 1080x2340 with 360-pixel bars; 39 black rows in 300 reference frames became an ~88-pixel right strip. Fixture/transform effects are separate from Camera2; raw/replay rotation 0 supersedes that transform.

Finite encoded replay does not loop: 20 s in a 90 s run is invalid freeze evidence; 4500 frames/150 s included setup. Raw actual-encoder input can loop; encoded replay has no live encoder/microphone. Callback intervals/signed target lag, decoder/Texture timestamps, screen/optical delay, lens blur and acoustic/lip-sync are distinct measurements; missing clocks/pixels cannot be invented.

Earlier private benchmark footage has been retired and is not distributed. The old footage was not required by the current inventory: broad historical-comparison labels and optional pixel rescoring had been mistaken for continuing necessity. Recorded network, queue, timing, authentication and lifecycle numbers remain; old numerical picture scores stand as recorded, while rescoring that needs the retired pixels is unavailable. Future picture measurements use native generated or licensed references; Camera2 uses a controlled non-private target. The legacy matrix no longer defaults to private input. The native complexity tool no longer insists on a camera reference: both generated and natural references retain exact geometry/cadence/colour/hash checks. Its focused regression failed for both kinds before removing that guard; 16 focused checks and a real 180-frame native CLI self-comparison passed. This changes a diagnostic prerequisite, not acceptance targets or encoder-load proof. New inputs need new cases and runs; unrelated clean pixels cannot replace old measured bytes.

Eager validation made a selected observation fail when an unrelated registered capture was retired; metadata-first selection now validates only the chosen capture/extraction and its actual clock files, and focused tests retain missing/changed selected-evidence rejection.

Six scan/buffer replay recipes also depended on historical inputs too short for their own window: 1800 frames at 15 FPS stopped sending at 119.933 s, while measurement plus connection allowance reserved 120 s. The app sends no nominal final-frame interval after EOF, so the guard was preserved. Replacement finite fixtures keep the original payloads and explicitly append 60 packets beginning at a keyframe, ending at 123.933 s. Both fully decode 1860 frames and their appended decoded prefix matches. New fixtures preserve durations, topology, transport, rate, scan and buffer choices. Parent encoder records remain provenance, not a runtime dependency or a new current-encoder pass. Raw-source identity limitations remain explicit.

## Bounded stress and independent observation

The offline stress workflow uses 60-second focused/composite programmes and a 12-second framing chart; these are bounded engineering starting points, not proven sufficient durations. [NTIA scene-selection research](https://its.ntia.gov/publications/download/PinsonVPQM2013.pdf) supports varied detail/motion/content chosen for the measurement purpose. [Parecki's native encoder clips](https://aaronpk.tv/stress-test/) provide competing noise regions, pixel-accurate detail and frame-rate-specific A/V cues. Our inference is to compare focused full-frame and four-panel inputs, retain licensed natural scenes, and measure actual encoder time/bursts/queues before judging difficulty. More panels neither increase pixel count nor prove higher load.

The settings diagnostics cover 2,395 45-second cases for each feasible individual offered geometry/FPS/rate and named boundaries. The original 24,390 request objects keep their IDs; 2,439,902 residual products remain UNMEASURED. Lower FPS keeps its own request. Native input generation is selected on demand: 720x480/30/60s needs 1,872,000,044 estimated bytes before scalar sidecars, so eagerly generating 154 variants would waste storage. One exact input is reused across role/codec/rate comparisons. Acceptance targets and normal app settings were not changed.

End-to-end checks exposed two contract gaps: the short chirps left audio mostly silent, and treating every event as a timing cue rejected a sustained signal. Explicit 40-second native PCM workloads now coexist with bounded cue templates. Full-duration temporary generation/consumer checks verified 924 frames, four native panels and 44 cues; generated chart geometry has zero landmark error but no detector/phone acceptance. The default 3-second white cue repeats remain ambiguous in a 0-6 s optical search; audio uniqueness cannot establish optical identity.

The suite-owned [DirectShow recorder](https://ffmpeg.org/ffmpeg-devices.html#dshow) freezes native acquisition settings and records actual output formats/original timestamps. Physical scoring distinguishes source-target/screen edges, microphone/screen offset, actual source acoustic emission and preview timing, with uncertainty and validation required. Cleanup tests reproduced an instrument release while recorder termination was unconfirmed; its lease is now quarantined and finalization blocked. Generated/proxy/unregistered evidence grants no physical pass. [RFC 2544](https://www.rfc-editor.org/rfc/rfc2544.html) separates load, latency, loss and burst measurements; this separation informs our diagnostics without claiming Bluetooth or RFC certification. Real radio interference, Camera2, colour, acoustic experience and endurance remain separate evidence gaps.

## Historical direct presentation comparator

The 2026-08-25 S24 -> S22 Classic hardware VP9 720x480/30/1100 CBR + Opus source sustained 29.9-30.0 FPS, PTS p50/p95=33.3/33.4 ms, max 33.6, queues 0-1/drops 0. A 20 s replay isolated transport/decode scheduling. These old decoder shown/timestamp values are not current Texture or physical-pixel proof.

| Local playback | FPS | Ideal interval ms | Historical shown p50 / p95 / p99 / worst ms |
| --- | ---: | ---: | --- |
| Low 30 ms | 15 | 66.7 | 66.7 /130.1 /270.9 /367.0 |
| Low 30 ms | 24 | 41.7 | 41.7 /131.5 /200.6 /251.8 |
| Low 30 ms | 30 | 33.3 | 33.3 /42.6 /109.6 /177.9 |
| Balanced 250 ms | 15 | 66.7 | 66.7 /66.7 /151.2 /367.2 |
| Balanced 250 ms | 24 | 41.7 | 41.7 /41.7 /142.7 /247.7 |
| Balanced 250 ms | 30 | 33.3 | 33.3 /33.3 /33.3 /33.3 |
| High 500 ms | 15 | 66.7 | 66.7 /66.7 /66.7 /66.7 |
| High 500 ms | 24 | 41.7 | 41.7 /41.7 /41.7 /41.7 |
| High 500 ms | 30 | 33.3 | 33.3 /33.3 /33.3 /33.3 |

Final High 15/24/30 rendered 283/283, 293/293, 350/350, drops 0. Earlier 400/500 ms trials collided with the 500 ms schedule-ahead ceiling; a bounded 2 s ceiling removed that collision while the target stayed 500 ms. This justifies the scheduling fix, not extra buffering as a motion-quality fix.

## Native AV1 real-time tool tradeoff

These comparisons used libaom 3.14.1 (commit `0006e96cdb595ff88950a2c664115335ca5517bf`) with per-frame CSV, device and score data recorded at the time; the private IVF pixels have since been retired. The 2026-08-26 encoder was S22; receiver S24 used c2.exynos.av1.decoder. Within each offline resolution group, cases used identical I420 frames. VMAF is comparable only within that source-resolution group. Live cases used Classic with both encryption layers, Opus 32 and High 500 playback on already-warm phones, with thermal reduction disabled for isolation. These are bounded historical comparisons, not universal defaults or ceilings.

The custom encoder now has a WebRTC-derived real-time tool profile. It disables expensive AV1 tools
that the current WebRTC libaom encoder also disables for real-time communication: TPL, order hints,
palette, OBMC, warped/global/ref-frame motion vectors, several intra/inter prediction tools, trellis
quantization, restoration, rectangular transforms, TX64 and related search work. This is an encoder
speed/compression trade, not receiver buffering.

All cases: 720x480, 15 FPS, 400 kbps, 75 identical frames.

| cpu-used / threads | Tools | Encode FPS | p95 / p99 | Caller CPU | VMAF | Decision |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 6 / 4 | Off | 27.94 | 59.3 / 103.1 ms | 342% | 97.394 | Rejected: tail latency exceeds the 66.7 ms live deadline. |
| 6 / 4 | On | 35.17 | 45.2 / 54.0 ms | 342% | 97.068 | Tools save the tail for only 0.326 VMAF. |
| 6 / 3 | On | 31.99 | 57.6 / 63.6 ms | 279% | 97.266 | Rejected for live use: too little hot-phone margin. |
| 7 / 3 | On | 60.94 | 24.8 / 35.7 ms | 275% | 96.176 | Retained at 720x480 up to 20 FPS. |
| 7 / 4 | On | 63.21 | 25.4 / 37.0 ms | 322% | 95.884 | Rejected: fourth thread costs CPU and quality. |
| 8 / 4 | On | 71.08 | 23.5 / 37.1 ms | 329% | 94.312 | Rejected at 15 FPS: little tail gain for excess quality loss. |

The important result is not that cpu-used 7 is universally best. It is the measured point where this
phone gains large real-time and CPU headroom for about a one-point VMAF cost, preventing motion from
turning a nominally good average into missed live deadlines.

## Native AV1 keyframe tradeoff

All cases: 720x480, 15 FPS, 400 kbps, cpu-used 7, 3 threads, 150 identical frames.

| GOP | Actual kbps | Keyframes | VMAF | SSIM | Encode p95 / p99 | Decision |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 2 s | 409.09 | 5 | 96.453 | 0.980533 | 20.2 / 33.3 ms | Rejected: frequent keyframes consume useful motion budget. |
| 5 s | 408.19 | 2 | 97.153 | 0.981645 | 23.1 / 29.7 ms | Retained: gains 0.70 VMAF with bounded join/recovery. |
| 10 s | 408.35 | 1 | 97.205 | 0.981703 | 22.7 / 32.7 ms | Rejected: only +0.052 VMAF over 5 s for twice the natural recovery wait. |

The product uses a normal 5-second interval. A joining or recovering receiver can still request
an immediate keyframe, so this does not force it to wait for the next periodic keyframe. (The later safety-keyframe interval for streams is described in the change ledger above.)

## Native AV1 bitrate-quality curve

All cases: cpu-used 7, 3 threads, 5-second GOP, 150 identical frames.

| Requested kbps | Actual kbps | VMAF | SSIM | p95 / p99 | Meaning |
| ---: | ---: | ---: | ---: | ---: | --- |
| 350 | 357.62 | 95.981 | 0.979647 | 20.1 / 28.6 ms | Lower-rate mesh option; visible motion softness expected. |
| 400 | 408.19 | 97.153 | 0.981645 | 23.1 / 29.7 ms | Detail-first mesh baseline. |
| 450 | 458.39 | 98.017 | 0.983403 | 21.6 / 34.6 ms | Aggressive mesh/direct transition. |
| 500 | 508.77 | 98.572 | 0.984665 | 24.3 / 29.3 ms | Stronger direct quality; branching headroom falls. |
| 700 | 707.43 | 99.438 | 0.988152 | 24.9 / 49.8 ms | Direct/detail option, not a multi-child default. |

The curve proves that 400 kbps is a genuine visual-quality constraint. Receiver buffers cannot create
the detail that the encoder was not allowed to send.

## Native AV1 policy

| Profile | Speed / threads | Encode FPS | p95 / p99 | Caller CPU | VMAF | Decision |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 640x360/20/400 | 7 / 2 | 94.18 | 18.1 / 23.4 ms | 222% | 97.598 | Best measured 20 FPS implementation; same bitstream/VMAF as 3 threads with about 11% less CPU. |
| 640x360/20/400 | 7 / 3 | 94.77 | 17.6 / 26.8 ms | 250% | Not reported | Rejected: no quality gain over 2 threads. |
| 640x360/20/400 | 7 / 4 | 96.91 | 17.4 / 24.8 ms | 293% | Not reported | Rejected: extra CPU and -0.169 VMAF. |
| 640x360/24/400 | 7 / 3 | 99.10 | 16.8 / 27.8 ms | 250% class | 96.704 | Valid motion-first mesh profile; physical app uses 2 threads after the thread result. |
| 640x360/30/500 | 7 / 3 | 95.87 | 17.4 / 34.0 ms | 252% | 96.817 | Good quality but p99 slightly crosses 33.3 ms. |
| 640x360/30/500 | 8 / 2 | 92.24 | 18.2 / 23.0 ms | 239% | 95.614 | Retained safe speed/thread policy for this mode. |
| 720x480/20/550 | 7 / 3 | 74.12 | 24.7 / 36.2 ms | 263% | 97.261 | Retained through 20 FPS. |
| 720x480/20/550 | 8 / 3 | 87.14 | 19.9 / 27.8 ms | 258% | 95.737 | Rejected: speed 7 is safely real-time and +1.524 VMAF. |
| 720x480/24/700 | 7 / 3 | 68.86 | 27.5 / 38.2 ms | 259% | 97.479 | Highest offline quality but limited hot-phone margin. |
| 720x480/24/700 | 8 / 3 | 84.00 | 20.5 / 33.6 ms | 259% | 96.285 | Retained direct profile: about -1.19 VMAF for much safer live margin. |
| 720x480/24/700 | 8 / 4 | 83.53 | 21.1 / 30.0 ms | 300% | 95.926 | Rejected: fourth thread raises CPU and lowers quality. |
| 720x480/30/900 | 7 / 3 | 71.52 | 25.2 / 38.3 ms | 262% | 98.037 | Rejected for live: p99 exceeds the 33.3 ms deadline. |
| 720x480/30/900 | 8 / 3 | 80.80 | 18.5 / 26.9 ms | 258% | 97.303 | Valid, but no useful quality advantage over the faster policy. |
| 720x480/30/900 | 9 / 3 | 97.93 | 16.6 / 22.5 ms | 260% | 97.405 | Retained maximum-direct policy; much more hot-phone margin than speed 7. |
| 720x480/30/900 | 9 / 4 | 86.31 | 17.8 / 26.4 ms | 308% | 97.173 | Rejected: fourth thread is slower, hotter and lower quality. |

The retained automatic native policy is therefore:

- through 640x360/24: cpu-used 7, 2 threads;
- 640x360 above 24 FPS: cpu-used 8, 2 threads;
- 720x480 through 20 FPS: cpu-used 7, 3 threads;
- 720x480 at 24 FPS: cpu-used 8, 3 threads;
- 720x480 at 30 FPS: cpu-used 9, 3 threads.

Larger modes stay conservative until they receive their own exact-source and sustained-hot tests. (A later speed governor, described in the change ledger, may try slower presets within the frame budget.)

## Native AV1 encrypted live motion

All four runs used the actual camera, native AV1, 32 kbps Opus, application encryption, Classic
RFCOMM, the asynchronous decoder, and the 500 ms receiver profile. Start/stop beeps marked a common
20-second aggressive hand-pan window. Both phones were already hot; automatic thermal reduction was
disabled for isolation.

| Live profile | Encoder result during motion | Receiver result during motion | Visual/product result |
| --- | --- | --- | --- |
| 720x480/15/400, speed 7/3 | 15.0 FPS; 396-407 kbps; encode p95 47.6 ms; zero capture/transport drops | p50/p95 66.7/66.7 ms; zero decoder/audio drops; empty BT queue | Coherent and stable, but fast pans retain blur/smear. Detail-first mesh option. |
| 640x360/24/400, speed 7/2 | 24.0 FPS; 401-437 kbps; encode p95 35.7 ms; zero capture/transport drops | p50/p95/p99 41.7/41.7/41.7 ms; zero decoder/audio drops | Much smoother cadence, but visibly more spatial blockiness. Motion-first mesh option, not a universal default. |
| 720x480/24/700, speed 8/3 | 24.0 FPS; 680-714 kbps; encode p95 35.0 ms; zero capture/transport drops | p50/p95 41.7/41.7 ms; zero decoder/audio drops; queues bounded | Measured direct balance; subjective observation is not an acceptance gate. |
| 720x480/30/900, speed 9/3 | 29.9-30.0 FPS; 890-903 kbps; encode p95 26.4 ms; one camera-frame miss; zero transport drops | p50/p95/p99 33.3/33.3/33.3 ms; zero decoder/audio drops; queues bounded | Measured high-rate direct mode. Costs more radio/CPU and leaves less burst headroom than 700/24. |

The receiver's cumulative debug continuity counter initially included the intentional multi-second
gaps while the sender was stopped between profile changes. Those historical "freeze events" are not
stream freezes and are excluded above. The tracker now closes and resets on every new stream header.

## MediaCodec AV1 and codec controls

Codec curves and a decoder A/B separate compute from config/dependency defects. MediaCodec AV1 complexity 4 at 720x480/30/1100 gave 10-16 FPS/froze at 205 renders; keyframe recovery without old dependencies repaired it. S22 complexity 1/2/3 measured 41.31/36.72/33.22 FPS, complexity 1 = 1.377 x real time. Retained policy 1 through 720x480/30, 0 for larger 30 FPS; a corrected ~90 s run held 29.9-30.1 FPS, PTS 33.3/33.5/33.6, rx 2867/rendered 2862, decoder drops 0. Thermal policy at 41.6 C selected 15 FPS/66.7 ms; frozen-frame repair is not efficiency proof.

Extreme-motion AU hashes matched; sender IVF/600-AU replay decoded cleanly on hardware/system dav1d. Two distinct 4-byte configs caused earlier-alone black/final-alone normal. Sender strips keyframe flags from config/uses format csd-0; receiver keeps newest AV1 config. A 406-frame capture at 27.38 recorded FPS peaked 0.57% pink/green detector versus 64.6% broken; inspection/source/transport/decoder loss 0. Moderate policy selected 990 kbps/27 FPS. This is scoped CSD/pixel proof.

S22 controlled 720p/15, 150/150 frames, four AV1 complexity 4 / four VP9 complexity 0 points: overlap VMAF/PSNR BD-rate 44.1%/48.7% lower AV1; SSIM had no overlap/no extrapolation. Warm compute margins 1.05-1.19 x AV1 versus 1.24-1.34 x VP9 expose thermal cost. The 600/800/1000 kbps AV1 captures used separate camera motion.

High-rate files (ASUS, M31; 2026-10-02): ASUS software VP9 1000/1500 measured 85.20/85.79 harness FPS, VMAF 97.54/98.50; M31 software 24.92/23.54. M31 hardware VBR 1000/1500 emitted 1001.628/1501.6768 kbps, 300/300, drops 0, harness 26.40/26.52 FPS. Copy/drain/hash/trace overhead prevents interpreting harness FPS as encoder ceiling.

## Primed CoC hardware motion comparison

Historical CoC observations: S24 c2.exynos.vp9.encoder -> S22 c2.qti.vp9.decoder, CBR, Opus 32, High 500, thermal reduction OFF, 12 s cues, thermal state Light/Moderate. Clock preflight had tracks 8.4 hours apart; normalized starting A/V delta 138-274 ms and a bounded 250 ms startup reserve removed the recovery loop without changing the 500 ms target. A 24 FPS preflight held 23.9 FPS, 41.7 ms p50/p95, drops 0.

| Requested geometry/FPS/kbps | Actual FPS / encoded kbps | Historical p50 / p95 ms | Loss/freeze / visual observation |
| --- | --- | --- | --- |
| 640x360/15/600 | 15.0 /591-622 | 66.7 /66.8 | 0; less fluid motion |
| 640x360/24/600 | 24.0 /571-606 | 41.7 /41.8 | 0; 360p balance |
| 640x360/30/300 | 30.0 /288-303 | 33.3 /33.3 | 0; most smear/blocks |
| 640x360/30/450 | 30.0 /448-452 | 33.3 /33.3 | 0; improved over 300 |
| 640x360/30/600 | 30.0 /594-607 | 33.3 /33.4 | 0; cleanest 360p30 in set |
| 720x480/15/600 | 15.0 /564-637 | 66.7 /66.7 | 0; detail/bitrate compromise |
| 1280x720/15/600 | 15.0 /586-615 | 66.7 /66.8 | 0; fast-pan blur remains |
| 720x480/24/700 | 24.0 /603-713 | 41.7 /41.9 | 0; secure-wire briefly 852 kbps, near ceiling |

Queues 0-1 and transport/audio/decoder drops 0 are timing evidence. Different hand paths are not aligned picture scores. This short direct matrix did not promote CoC or establish stable chain capacity.

## Bounded payload and CoC priming

| Initial 2026-08-22 S24 -> S22 | Bounded Mbps samples | Central Mbps |
| --- | --- | ---: |
| Classic BR/EDR | 1.141-1.159 (3) | 1.149 |
| CoC 2M, unprimed | 0.221-0.254 (3) | 0.242 |
| GATT 2M/MTU 517 | 0.426-0.428 (2) | 0.427 |

Unprimed S22 -> S24 reproduced 0.263 Mbps with 16 KiB/four-packet window and 0.266 with 65,535-byte SDU despite 2M/MPS 65,535. Dumps showed set_max_data_length_for_lecoc disabled. A public same-ACL GATT MTU 517 exchange before CoC removed the plateau; the hidden requestMaximumTxDataLength was not used. Controller data length is not exposed, so link priming is a measured effect, not direct DLE observation.

| Primed CoC control | Sender / receiver Mbps |
| --- | --- |
| S22 -> S24, setup GATT retained | 0.801 /0.804 |
| S24 -> S22, setup GATT retained | 0.810 /0.811 |
| S22 -> S24, setup released after connect | 0.848 /0.851 |
| Same direction, DCK instead of HIGH | 0.813 /0.815 |

Constant about 64 KiB ACK horizon, 4/8/16/32/65.5 KiB SDUs yielded 0.819-0.851 Mbps; 16 KiB retained. Two/four primed CoCs aggregate 0.862/0.843 Mbps, without striping benefit. No-window-ACK reported a false 1.838 Mbps interim sender rate, then **33.4 s** to deliver 3.5 MB at 0.841 receiver-timed. Private calls retain CoC control until teardown according to their own lifecycle evidence.

| Bounded transport / observed mode | Payload Mbps |
| --- | ---: |
| Classic BR/EDR | 1.193 |
| CoC 2M, MTU 517 primed | 0.80-0.86 |
| CoC 1M, unprimed | 0.193-0.194 |
| CoC Coded S2 /S8 | 0.081 /0.040 |
| GATT client writes 2M, tuned | 0.74-0.86 |
| GATT notifications 2M | 0.776-0.781 receiver-timed |
| GATT 1M | 0.320 |
| GATT Coded S2 /S8 | 0.154 /0.047 |

Unprimed CoC 2M is about 32% faster than 1M; bounded GATT 2M about 37.5% faster than 1M. Primed 1M/Coded unmeasured. The headline 1.4 Mbps LE 2M application figure is not an Android guarantee.

| Actual concurrent run | Classic Mbps | LE Mbps | Sum Mbps |
| --- | ---: | ---: | ---: |
| Classic+CoC 2M | 0.405 | 0.220 | 0.625 |
| Classic+GATT 2M | 0.407 | 0.373-0.375 | 0.780-0.782 |

Classic alone 1.193 Mbps; same-pair shared-controller contention was not additive. Parallel independent sessions also share radio conditions; their actual media overlap must be recorded.

## CoC windows and GATT completion discipline

| Unprimed CoC variable | Values | Mbps result |
| --- | --- | --- |
| Payload, four-packet ACK | 4/8/16 KiB | 0.248 /0.253 /0.239 |
| ACK window, 8 KiB | 1/2/4/8/16 packets | 0.229 /0.23 /0.253 /0.247 /0.245 |
| Read buffer | 4/8/16/64 KiB | 0.238-0.249, no monotonic effect |
| Write buffer | 4/8/16/64 KiB | 0.247-0.253, no material effect |
| Large payload | 32 KiB x 1 /65,535 B x 1 /65,535 B x 2 | 0.252 /0.241 /0.247 |
| DCK | 8 KiB x 4 | 0.260 short sample |

16-packet and 65,535-byte x 2 cases took 8.5 s for a 6 s request, increasing drain without gain.

| Unprimed CoC channels (8 KiB x 4, DCK, 2M) | Sender / receiver Mbps | Completion sender / receiver s |
| --- | --- | --- |
| 1 | 0.245 /0.246 | 8.6 /8.5 |
| 2 | 0.264 /0.270 | 9.9 /9.7 |
| 4 | 0.258 /0.261 | 9.1 /9.0 |

Four channels were uneven, 0.064-0.086 Mbps each. Earlier unbounded writes queued 1.8 MB while 0.8 MB arrived before close; an ordered-End/final-ACK test took 52 s to deliver 1.7 MB at 0.264 Mbps after 8 s enqueue. This and the primed 33.4 s drain reject enqueue speed as video throughput.

GATT MTU 517, 511 benchmark payload bytes/value, one outstanding Android operation; historical HIGH fence spacing:

| Data writes / acknowledged fence | Mbps |
| ---: | ---: |
| 1 | 0.107 |
| 4 | 0.302 |
| 8 | 0.445 |
| 16 | 0.541 |
| 32 | 0.635 |
| 64 | 0.685 |
| 128 | 0.672 |
| 256 | 0.730 |
| 512 | 0.766 |
| 1024 | 0.734 |
| 2048 | 0.776 |

Two/four/eight in-flight no-response writes caused 1500-1700 busy rejections per 8 s, no gain; the prototype was removed. A 15 s HIGH 0.766/0.740, mean 0.753 versus reversed DCK 0.855/0.815, mean 0.835 (10.9% scoped gain; best 0.855 about 61% of the 1.4 headline). These are controls, not power rankings. Production retains one completion outstanding and scoped HIGH/DCK/legacy pacing rules; callback return is not receiver delivery.

| Reverse GATT (MTU 517/2M, 15 s) | Transmitter / receiver Mbps |
| --- | --- |
| Notifications, HIGH | 0.803 /0.781 |
| Notifications, DCK | 0.823 /0.776 |
| Client writes, DCK, same-session | 0.818 /0.820 |

No notification-direction benefit; roles unchanged. API 36 measurements do not establish API 37 HDT/temporal-layer/LE Audio support.

## Deterministic stream I/O

Same 150-frame 1280x720/15 VP9 replay at about 414 kbps secure-wire; accepted Classic runs about 15 FPS, empty queues, drops 0:

| RFCOMM policy | Trials | Sender frame-call p95 ms | Flush p95 ms | Scheduled playout p95 ms |
| --- | ---: | ---: | ---: | ---: |
| Per-frame, 32 KiB | 3 | 3.8 mean | 1.0 mean | 286 mean |
| 5 ms deadline, 32 KiB | 3 | 2.7 mean | 0.4 mean | 226 mean |
| 10 ms deadline, 32 KiB | 1 | 2.9 | 0.6 | 258 |
| 16 KiB or 50 ms | 1 | 2.8 | 0.9 | 268 |
| 32 KiB or 50 ms | 1 | 3.3 | 0.7 | 292 |

Fixed per-frame, 4/16/32/64 KiB buffers gave 3.7/3.6/3.5/3.7 ms, one trial each, no useful size trend. Event-driven 5 ms replaced a 200 wakeups/s loop; final frame-call/flush p95=1.2/0.3 ms, 142/143 renders at 14.8 FPS, drops 0. Production 32 KiB/event-driven 5 ms retained. CoC 207 kbps replay, three trials each: 5 ms versus per-frame sender p95=3.2/4.3 ms, scheduled playout 308/333 ms, queues/drops 0; two setup attempts never opened the data channel and remain failures. GATT 699 kbps wire: three 5 ms trials complete, two of three per-frame complete, one BAD_DECRYPT; no causal flush claim. Final camera GATT 141/142 at 15.1 FPS, empty queues/drops 0. Two-hop CoC 4 KiB exception has separate evidence below.

## Low-output functional transport controls

Initial hardware VP9 640x360/15/300 request emitted 24-44 kbps; functional controls only:

| Initial video metric | Classic | CoC | GATT |
| --- | --- | --- | --- |
| Launch -> video s | 3.86 | 3.23 | 3.88 |
| Received / rendered | 300 /299 | 305 /304 | 229 /228 |
| Rendered FPS | 15.1 | 15.1 | 15.1 |
| Sender queue / drops | 0/30 /0 | 0/30 /0 | 0/30 /0 |
| Write average / max ms | 0.6 /5.2 | 1.9 /27.0 | 38.1 /69.5 |
| Source / receiver CPU snapshot % | 14.2 /32.1 | 17.8 /29.6 | 25.0 /37.0 |
| Source / receiver PSS MB | 103 /119 | 107 /119 | 107 /119 |

CPU values are single snapshots; run order and scene were not controlled. The full suite reached source thermal status Moderate (40.2 C skin / 38.4 C); this is not a transport thermal ranking.

## Viewer-middle rate and guard evidence

S24 -> S22 (local view + forward) -> M31, hardware VP9 640x360/15, Opus 32, Balanced 250. The numerical observations are recorded below; private video pixels are retired.

| Transport / allocation / selected kbps | Historical scoped result | Measured boundary |
| --- | --- | --- |
| CoC CBR 375, 4 KiB | Short PASS | All 15 FPS, final A/V/freezes 0, relay 433-438 kbps, queue 0-1/32, write p95 ~3 ms |
| CoC VBR 375 | FAIL | Writes 0.7-1.3 s, queue 26/32, 109 drops, final ~11 FPS, >2000 audio |
| CoC CBR 400 /450 | FAIL | 400 source saturation/both ~4 FPS; 450 final ~11 FPS; 4 KiB passed where 8/16/32 degraded |
| GATT CBR 400 | 20 s PASS | Both 15 FPS, loss/freezes 0, source wire 458, relay write p95=25-27 ms, queue 1-2/32 |
| GATT VBR 400 /CBR 450 | FAIL | VBR<8 FPS/source saturation; 450 source drops, middle ~11 FPS/final disconnect |
| Classic CBR 400 | Short PASS | All 15 FPS, source 0 drops, relay 0-1/32, write p95=1.4-3.3 ms, final A/V/freezes 0 |
| Classic CBR 500 | Aggressive retry | One final-hop disconnect/rejoin, eight join relay drops; then 15 FPS/final A/V/freezes 0 |
| Classic CBR 600 | FAIL | Actual 600 -> final 13.7-14 FPS, 328 audio, two freezes/~785 ms |
| Classic VBR 400 | Interrupted | Both 15 FPS/audio 0, one 234 ms gap versus none in CBR |

CBR-FD was not offered or tested by S24 hardware. A short CBR advantage does not remove offered VBR or prove a universal 375/400 limit. Direct 1280x720/15/1000 CBR produced 974-1035 kbps, secure 1050-1059, 15 FPS/66.7 ms p50/p95, queues 0-1, drops 0; screen recording perturbed continuity. Chain 720x480/15/500 produced 510-527, source 0 drops, middle 15 FPS/0 A/V, relay wire 517, queue 0-2/32/one pre-keyframe join drop; M31 ~15 FPS/decoder 0 but 51 late audio and GOP-related visible gaps. Transport capacity only, not final smoothness.

M31 received 1280x720/15 prerecorded VP9 through S22 at 400 with 15 FPS/0 freezes, and live 720x480/15 + Opus directly over CoC 500 with 0 A/V/freezes. S22 direct was smooth; simultaneous live receive + forward raised middle p95 from 66.7 to 117-170 ms and burst-fed the child despite small queues/fast writes. Evidence supports a topology/controller scheduling boundary rather than model/PHY/decoder blame; initiating RF cause not established.

| Classic profile | Middle cadence | Child / policy |
| --- | --- | --- |
| 720x480/15/500 | Post-child p95=170.2; graceful-close repeat 121.7 ms, bounded queues | Bursty before detach; middle returns toward 66.7, source unchanged |
| 640x360/15/400 | 15 FPS, p50/p95=66.7/67.1, 0 A/V/freezes, relay 456 kbps, queue 0-1/32 | Child 14.9-15 FPS, p95 ~84 ms, 0 A/V/freezes; retained |

Guards compare fresh pre/post-child cadence at actual FPS, sustained new Opus loss and repeated overflow across keyframe clears. CoC 700 overload proved LOCAL_CAPACITY/ack/exact-route suppression/no loop; CoC 375/GATT 400 controls retained the child. GATT 600 withdrew at 26/32 after 44 drops; a depth-only reset defect was fixed. Pink/green recovery lasted beyond 2.27 s; pressure detection begins after the fresh-keyframe boundary, local cover clears after keyframe render, source request throttle 2.5 -> 1 s. Later healthy child/middle-loss runs were clean; direction-specific blocked CoC still FAIL.

## Historical conservative lifecycle and recovery

Hardware VP9 640x360/15/150 CBR, Opus 32, Balanced 250, thermal OFF isolates lifecycle, not capacity.

| Transport / chain | Final-child loss | Middle-loss -> source recovery | Historical scope |
| --- | --- | --- | --- |
| GATT S24 -> S22 -> M31 | 2 s heartbeat/6 s stale removes at 6.36 s; middle 15 FPS/0 A/V/decoder/freezes | 2.4-2.9 s, 15 FPS/decoder 0 | This direction |
| CoC S24 -> M31 -> S22 | Control EOF 85 ms; middle 15 FPS/0 A/V/freezes | 2.27 s, 15 FPS/A/V/decoder 0 | This direction |
| Classic S24 -> S22 -> M31 | Balanced advertiser/scanner fixed child-loss stalls; middle 15 FPS, maximum interval < 250 ms, 0 A/V/decoder | 4.65 s, 15 FPS/A/V 0 | This direction |

Opposite CoC S24 -> S22 -> M31 blocked a second-hop write **9.76 s**, filled the 32-record queue and withdrew despite 2M/MPS 65,535/middle 15 FPS. Recovery has no search expiry; End does not reconnect. Discovery 22 bytes; recovery reuses six profile bytes to name the failed node; source offer 1.45 s after loss, remaining gap = CoC/authentication/keyframe/Balanced. Cached inactive-parent request skipped. Signed prefix, not rotating address, keys admitted peers. Free source slots admit authenticated recovery immediately; full slots protected. Per-join timing preserves cumulative continuity.

Later Classic 150 full fault-to-surface 5194 ms versus 4647.7 ms from later selection: strict recovery FAIL. Authenticated M31 parent S22/replacement S24 prove fallback. Revised attribution retains one real pre-transition freeze and a recovery-overlapping gap without erasing counters.

| Normal CoC one-child gate | Historical measured result |
| --- | --- |
| Late join | Middle 14.9-15 FPS/child 15; A/V +119 to +150 ms, queues 0-1/video+transport drops 0 |
| Child isolation | Middle 15 FPS persists; stable hop-specific slot returns; one earlier small teardown gap |
| Rejoin | 3.19 s, A/V +120.1 ms, 15 FPS, 0 audio/decoder/freezes |
| Pause/Resume | Links retained; middle 66.7/child 66.8 ms; middle 6 audio discards around resume/child 0 |
| End | Child ack -> middle -> source; accepted run 0 freeze events |
| Source isolation | After hop-token fix, source never dials final directly; BFG1/BMM1 retain original identity |

Hop tokens prevented duplicate answers; cached header/config + fresh upstream keyframe replaced old-keyframe late join with -3.56 s A/V/decoder error. Queue close wakes the writer to republish one slot. Normal middle has one child; group/opaque diagnostics are separate.

## Fan-out and opaque-relay comparisons

Historical 2026-08-24 VP9 640x360/15 counts are latest samples, not synchronized stop totals. Opaque relay forwards endpoint-sealed records without encoder/decoder; normal middle is a trusted viewer.

| Direct fan-out target | Rendered / received | Isolation |
| --- | --- | --- |
| Classic 300 | S22 14378/14379; S24 14381/14388; ~15 FPS, empty queues, source 0 drops | Killed S24 leaves S22 healthy -> 15363; dynamic rejoin |
| GATT 250, 2M/MTU 517 | S22 14910/14911; A55 15097/15098; ~15 FPS, empty queues, source 0 drops | Killed A55 leaves S22 healthy; dynamic rejoin |
| CoC 100, 2M/MPS 65,535 | S22 13865/13866; S24 13938/13959; ~15 FPS, empty queues/drops 0 | DEBUG 200 ms packet delay -> S24 4.8-4.9 FPS while S22 15; slow child times out/reconnects independently |

CoC used an explicit two-child override under the old 220 budget; the MTU fix replaced that budget with 700. Not current normal branching proof.

| S22 -> A55 opaque relay -> S24 | Rendered / received | Relay |
| --- | --- | --- |
| CoC 100 | 14234/14235, 14.9 FPS, transport 0/1, decoder 1/7, drops 0 | 2M/MPS 65,535, queue 0-1/60, drops 0, TTL 7; setup release removed slowdown |
| GATT 250 | 14136/14137, 14.8-15 FPS, GATT 0/512, decoder 1/7, drops 0 | 2M/MTU 517, queue 0-1/60, drops 0, TTL 7 |
| Classic 300, software VP9 source | 14133/14135 at 15 m 39 s, 15 FPS, transport 0/1, decoder 1/7, drops 0 | Both hops authenticated, queue 0-1/60, drops 0, TTL 7, no encode/decode |

Earlier S22-as-CoC-relay repeatedly failed with controller 0x22/LMP response timeout on the second link. Pre-endpoint S24 -> A55 -> S22 reached 3384/3385 at 15 FPS/~4 m/relay 0 drops; superseded as protocol proof.

### Background delivery and instrumentation

The 2026-08-24 background GATT run used the normal discovery/request UI and then Home on both phones: S24 (SM-S921B) -> S22 (SM-S901E), hardware VP9 surface 640x360/15 FPS/150 kbps, signed identities, endpoint encryption and congestion feedback. MTU 517 and negotiated LE 2M TX/RX remained throughout; no disconnect. The historical report recorded a 900-second interval followed by final cumulative counters of 14626 received/14625 rendered, reported 15.1 FPS, RX pipe 0/512 and TX queue 0/30 with 0 drops. Those totals are not a newly aligned source-clock interval score. Source thermal status moved 0 (36.2 C) -> 2 (39.9 C); observed peak 40.4 C. Receiver stayed at status 0, 31.2 C -> 32.0 C. Foreground service types were source 0x50 (camera|connectedDevice) and receiver 0x10 (connectedDevice). Moderate heat did not reduce FPS/rate or stop the session. This is ordinary background continuity, not energy, standardized scene quality or process-death restoration proof.

The 2026-08-25 instrumentation check used the same phone models with encrypted GATT/LE 2M, VP9 640x360/15 FPS/300 kbps and Balanced 250. Selection to first Texture update was 5563.4 ms: beyond the current 5000 ms startup target. There were 2178 updates, 6 gaps over 250 ms, 1197.9 ms freezes and 0.81% freeze ratio; decoder ~15 FPS, queue 0-1/4, 0 drops and ~66.7 ms cadence did not erase those display gaps.

A 20-second resource collection and a final five-second smoke proved recording and a sticky starting temperature of 37.9 C only; they support no efficiency claim. Five connected CPU samples separated app/codec/audio/Bluetooth CPU after startup, not energy use. An S22 software VP9 30-frame field verification covered p99 output latency, two keyframe sizes, delta sizes, caller CPU and core-equivalent percentage; the short low-resolution case does not rank codec efficiency. The optical analyzer's generated 60 FPS fixture paired nine events with six-frame offsets and reported mean/p50/p95/min/max 100.0 ms at 16.67 ms recording resolution. That validates the calculation on synthetic data; real optical delay still requires filming both phones.

### Relay dual-role scheduling (2026-10-11)

HCI snoops of a relay that is LE peripheral to its parent and LE central to its child, both at 15 ms: the M31 (Exynos 9611) and the ASUS (Qualcomm SM8150) in saved relay runs. Reproduce with `python -B tools/relay_dual_role_snoop.py LOG...` (anchors are inferred from host timestamps, +-0.5-1 ms).

| Child connection attempts | Never established (0x3E) | Silent until 0x08 | Other (carried, low rate, closed by the relay) |
| --- | ---: | ---: | ---: |
| M31 relay (CoC and GATT), 55 | 22 | 9 | 24 |
| ASUS relay, 29 | 5 | 0 | 24 |

| M31 15 ms stage (child anchor after the parent anchor; gap to the next parent anchor) | Outcome |
| --- | --- |
| 3.7 ms  ; 7.9 ; 9.40 and 9.41  ; 10.21  ; 10.28  ; gaps 11.3-4.7 ms | carried 126-252 kbps |
| 11.51 and 11.35  ; 11.63 and 11.38  ; gaps 3.4-3.65 ms | silent: last completed packet 77-92 ms after the update to 15 ms, then nothing for 5.03-5.04 s, `Disconnection_Complete 0x08`; 19 completed-packet events and 55 ACL plus completed-packet records in each of the four |
| 11.98, 12.14, 13.52, 13.73  ; gaps 3.0-1.3 ms | alive but 3-31 kbps (no ATT MTU exchange, so no data-length update: 27-byte PDUs) |
| 6.1 and 6.6  | 17-29 kbps for the same reason (no data-length update), not a phase effect |

What the traces show. (1) The parent link is untouched: during the 5 s of silence it kept receiving 1.2-2.5 KB per 50 ms (busy 11-34% of the time), so the controller keeps the peripheral link and gives the child no event. (2) Every death is in the equal-interval stage: the 45 ms and 7.5 ms stages of the same connections never died (the 7.5 ms child has two events per parent event; the stack's own service-discovery lock sets it, l2c_ble_conn_params.cc), and the dead links all follow the `LE Data Length Change (251 octets, 2120 us)` that the MTU exchange triggers 16-31 ms after the update to 15 ms, with the 2M PHY update still pending (1M-forced arms died alike). (3) The ASUS controller carried a child at the same or worse phase (12.33-13.29 ms, 351-366 kbps), so this is a controller scheduling difference, not an arithmetic limit. (4) The 5 s timeout is not the problem: 333 intervals with zero completed exchanges; a longer timeout only keeps a dead link up longer, and the app cannot set it (`GattService.connectionParameterUpdate` hard-codes 500 units). (5) The phase at the 15 ms stage is the 45 ms stage's phase plus a window offset the controller chooses (mostly forward, +0 to +3.5 ms observed), so it is a fresh draw per connection and per update; an update with unchanged parameters is a no-op (immediate Update Complete, no new anchor).

Hypothesis consistent with all 21 M31 stages, not proven (no sniffer, no Exynos document): the controller admits a central event only if the exchange its negotiated data length allows (maximum PDU time each way, 2 x 2.12 ms + 2 x 150 us = 4.5 ms at 1M and 251 octets; 2.4 ms at 2M) fits before the next committed parent event. The healthy/dead boundary then lies at a 4.5 ms gap (healthy 4.7-11.3, dead 3.4-3.65). Predictions the A/B can break: 2M or a smaller data length moves the boundary to about 2.5 ms; a parent at 30 ms leaves one of two child events free; the child at 7.5 ms never dies.

Options. (a) A child interval that is a multiple of the parent's does not help: both intervals stay phase-locked and the child has one chance per parent event; the direction that helps is a parent interval at least twice the child's. The app cannot choose an interval: HIGH (11.25-15 ms) resolves to 15 ms on the Exynos, Qualcomm and S24 controllers, BALANCED (30-50 ms) to 45-50 ms on the Exynos (the balanced arms carried 36-184 kbps, 24-30% frozen, and re-anchored 2-3 times), and the hidden `requestLeConnectionUpdate` throws NoSuchMethodException . DCK (API 34) is 30 ms in AOSP, so a source on Android 14+ can make its relay-capable child's link 30 ms: built as the Experimental `test_relay_upstream_dck`. (b) Supervision timeout: rejected, see (4). (c) Role reversal (PLAN design): it removes the 22 central-initiation failures (the relay's controller would place the child's first anchor right after its own advertising event, in a gap it knows) but not the later collision: the child central still draws the update's window offset without knowing the relay's parent schedule, and two peripheral links at 15 ms still share one radio; it also needs a PROTOCOL change and a CoC-server on the relay. Not built; decide after the A/B. (d) A DeviceFamilyPolicy entry (`exynos9611-dual-role-equal-interval-child-starvation`) is recorded as an inert proposal, LOW confidence: one Exynos phone, no vendor document; Nordic's controller documentation shows the contrasting design (a peripheral connection about to time out gets the highest priority). What shipped as a generic fix is only the stall guard: it shortens a dead attempt from 5.9 s to about 3 s and lets the existing establishment fallback count it; it does not make the link carry anything.

## Legacy-family protections and measured scope

Qualcomm qcom/msmnile-sm8150-family/API<=32 Classic source continuous low-latency BLE scanning caused 300-700 ms delivery gaps despite 66.6 ms encoder PTS. Credit/packet ordering/VP9/small frames/1000 ms playout did not solve it and were removed. Retained scope changes source BLE scheduling only: 8 s acquisition then balanced; GATT/CoC, newer Qualcomm/Exynos unaffected. Profile: 640x480/15/220 AV1 + Opus 32/High 500:

| Direction | Historical result |
| --- | --- |
| ASUS -> S24, rule active | 15.0 FPS/66.6 ms, 0 freezes/sender/decoder/audio drops, 5-7 buffered frames |
| S24 -> ASUS, rule inactive | 15.0 FPS/~66.7 ms, 0 freezes/sender/decoder/audio drops |

The measured historical legacy private-GATT fallback used flexible-YUV VP9 426x240/up-to 15 FPS/24 kbps plus Opus 16 when selected AV1 was incompatible and VP9 was offered. Each current run must verify its effective rate. This does not cover requested AV1/Opus 32; newer stacks and CoC/Classic retain independent normal choices.

| ASUS/S22 direct historical path | Sustained / controls |
| --- | --- |
| GATT | 109 s full-duplex: ASUS VP9 15 FPS/24 kbps/Opus 16, S22 AV1 ~30 FPS/180/Opus 32, decoder 0; first status 133 returns discovery/retry connects; End availability ~2 s |
| CoC | 67 s reverse setup, normal AV1/Opus 32, 15/30 FPS, decoder 0; caller End/availability |
| Classic | >4 m bonded full-duplex, 15/30 FPS, >8000/4000 received, decoder 0; mute/unmute changes matching 50 audio packets/s; End discovery |

Historical Redmi Note 9 Pro (atoll/API 30) direct to S22 exercised all three; legacy GATT only on Redmi 426x240/15/24/Opus 16 versus S22 AV1 640x360/30/180/Opus 32, CoC normal AV1 15/30, Classic ~3 m 15/30/decoder 0/camera/mic controls. Unpaired Classic failed after ~7 s with a pairing instruction. ASUS -> S22 opaque relay -> Redmi:

| Transport | Historical motion / lifecycle |
| --- | --- |
| Classic | Both 15 FPS/~66 ms, decoder 0, >1 m; adjacent bonds; 400 ms drain changed Broken pipe to Peer End |
| CoC | 110 kbps still record-rate overloaded; pairing two 20 ms Opus only in relayed CoC changed 50 -> 25 records/s. Repeat 641/647 frames/~45 s, 15 FPS, 66.3/66.6 ms, decoder 0; one status 133 retry/one cache refresh reached 2M/MTU 517/MPS 65,535; clean End |
| GATT | Both legacy VP9 426x240/15/24/Opus 16, 747/741 received, 15 FPS/~66 ms, decoder 0; server-close first changed availability ~10 s timeout -> 1.94-2.00 s |

Final ASUS teardown raced ImageReader input against a released MediaCodec; a handler-owned release and late-callback guard retained PID/Peer End/2.38 s re-advertise, without lowering successful Samsung settings.

## Call role and teardown comparisons

Three-phone call runs (2026-10-01) kept profiles, identities, failures and the native crash buffer; normal selections, family limits and security were preserved.

| M31 -> S22 opaque relay -> ASUS unless stated | Measured motion / terminal result |
| --- | --- |
| CoC before camera/scan fix | M31 no video; reverse 5-6 FPS/late audio/freezes: FAIL |
| Classic before incoming-scan fix | Both 15 FPS, but ~19% visible frozen, gaps 1.27 s: FAIL |
| Classic after scan fix | 125 s, ASUS/M31 visible 1845/1856, 0 freezes/decoder/late audio, 66.7/66.8 ms; mute works/clean End/re-advertise < 1 s; short regression |
| CoC after camera/scan, before control retention | ~3 m, 2713/2740 visible, 15 FPS/0 freezes/audio/decoder; End EOF/separate 0x22 setup loss: FAIL |
| GATT after camera/scan | ~3.5 m, 3107/3057 visible, 0 audio/decoder; ASUS 0 freezes/M31 three 751 ms (0.36%), p95 ~134 ms: timing FAIL; clean End |
| M31 CoC retry | OS Bluetooth SIGSEGV libbluetooth.so/l2c_csm_indicate_connection_open before media; no proven workaround |
| Reverse ASUS -> S22 -> M31, before retention | Injected recovery then M31 had 321 freezes/55.42% frozen/late audio/drop cycles; 400 ms drain insufficient, reset: FAIL |
| Same reverse, retained control | ~5 m, M31 4532 visible/0 freezes, ASUS 4495/one 1069 ms injected gap (0.36%), 0 audio/decoder; Peer End, availability 600/772 ms |
| Direct M31/S22 CoC | VP9 640x360/15/150 each, ~3 m 40 s, 3300/3312 visible, 0 freezes/decoder; S22 startup 16 audio losses then unchanged/M31 0; clean End < 1 s |
| Direct S22/M31 GATT | Same VP9, ~13 m 20 s, 11994/11987 visible, 0 freezes/audio/decoder; S22 66.7/M31 p95-max 83.4/99.4 ms; clean End ~2 s; optical unmeasured |

Earlier warm S24/M31 broadcast 640x360/15/150 CBR + Opus:

| Transport | Functional result |
| --- | --- |
| GATT | 2M/MTU 517, both handshakes, source 0 drops, >=450 renders/15 FPS, 0 freezes/A/V/decoder; source loss -> recovery |
| CoC | 2M/MPS 65,535, 291/297 rendered/received, 15 FPS, 0 freezes/audio/decoder, source 0 drops |
| Classic | Bonded identity, >400 renders/~15 FPS, 0 A/V/decoder/source drops; one 334 ms freeze, functional only |

Two-way calls worked on all three; End/Hang-up returns availability. Atomic admission advanced M31 receive 1 -> 65 -> 126 -> >2000/decoder 0. On an Android 15 iQOO phone a BLUETOOTH_SCAN SecurityException led to an actual permission recheck at the discovery boundary; the final Samsung matrix does not prove iQOO media.

### Classic identity boundary

A rotating privacy address with an SDP/page timeout and no stable bond is a Classic identity boundary, not a decoder cause. Classic RFCOMM has to page a BR/EDR address, but discovery is BLE: phones advertise with resolvable private addresses that only a bond's identity-resolving key can resolve, and Android hides a phone's own address from apps. Without a mutual bond a phone therefore cannot learn the address to page, and Classic setup S24 -> M31 paged unresolved candidates until the broadcaster's 12 s timeout. Android's secure RFCOMM sockets also require an authenticated link key on both phones, so a stale one-sided bond fails too. The experimental bond-free Classic mode (default off, Advanced setting; see the register in [ARCHITECTURE.md](ARCHITECTURE.md)) addresses both: it uses insecure RFCOMM with the app's own authentication and ordered encryption, remembers the address at which an authenticated identity was reached, and uses a Classic inquiry for first contact (which finds only a discoverable phone).

Historical 2026-08-27 S22/S24 direct two-way matrix used BMC1; current strict BMC2 adds a validated relay hop. Native AV1/hardware VP9 directions independent:

| Transport | Historical cap / result |
| --- | --- |
| Classic | 250 kbps per direction, both A/V, bounded queues, decoder 0, mute/video off/on/flip/hangup |
| CoC | 200 per direction, 2M/MTU 517/MPS 65,535, 30/15 FPS, 50 audio packets/s, decoder 0 |
| GATT | 180 per direction, 2M/MTU 517, 30/15 FPS, bounded queues, decoder 0 |

GATT admission requires the notification channel, ignores unrelated system probes and rejects a second active channel. Fixed Decline/Cancel returns availability without stale frames; a 15 s Home test advanced both directions under connectedDevice/camera/microphone service. Screen lock/process death/endurance are separate.

## Retired bitrate controller and fit-fill geometry

The old adaptive controller/feedback v2 was removed 2026-08-27; these downshift controls explain the persisted selection, not the current fixed-rate policy.

| Historical hardware VP9 CBR picture control | Offered / actual | Observation |
| --- | --- | --- |
| Classic before, 1280x720/15/1100 | 0.9-1.1 Mbps, empty queues/drops 0; a short FPS sample cut 1100 -> 902 kbps | Unjustified downshift |
| Classic after, same | 1024/1028/1097 kbps, empty queue/drops 0, 14-16.5 FPS, no downshift | No downshift |
| GATT old floor, 1280x720/15/1100 max | 2M, 0.69-0.84 Mbps, backlog target 552, recovery 979, drops 0 | Better than former 345 floor |

Retired 0.04 bits/pixel/frame floor changed 1280x720/15 from 345.6 to 552.96 kbps. Relaunch previously returned 640x360/15/150 (Home wire 182); persisted resolution/FPS/rate and then 300 default fixed lost selection. The old screenshots captured different instants/display crops and did not establish aligned VMAF/SSIM. The numeric observations above remain the historical evidence.

Aspect controls: 1280x720 replay on 1080x2340; FILL uniform crop circle 802x802/square 906x907; FIT four edges, boundary 1.7946 versus 1.7955 reference. Sender FIT surface 1080x1920 centered on 1080x2340. These prove local uniform fit/fill, not all display shapes. Historical split allowed sender FIT/receiver FILL; later one-local-phone preference selects FIT if either former key was FIT. Encoded bytes/load unchanged.

## Audio and asynchronous decoder controls

Live camera 48 kHz mono Opus 32, 20 ms units/about 50 packets/s, same elapsed clock, both encryption layers; replay has no mic. Historical hardware VP9 640x360/15/300 S24 -> S22:

| Transport | Audio / video observation |
| --- | --- |
| Classic | 50 audio packets/s, drops 0; AudioFlinger 48 kHz mono CAMCORDER active/not silenced, speaker started/underruns 0; TX 0/30/drops 0/~15 FPS |
| GATT | 2M/MTU 517, 50 audio packets/s, drops 0; TX 0/30/drops 0/15.5-16 FPS |
| CoC | 2M/MPS 65,535, 50 audio packets/s, drops 0; TX 0/30/drops 0/~15 FPS |

Shared playout anchors before decoder startup; Low 30/Balanced 250/High 500 targets are not physical delay. Historical 15 FPS capacities 9/11/15 include 250 ms reserve; PCM queue 1 s. Dedicated capture/encode/decode/paced AudioTrack threads retained. Forced low-latency track mode produced queue loss; full prebuffer before play could block a stopped track; authenticated 1 KiB video splits did not fix audio loss. These were removed without assuming an API-label or head-of-line cause.

| Older 300-request Low camera | Received / rendered | FPS | Video / audio / sender losses | Scheduled-video p95 ms |
| --- | --- | ---: | --- | ---: |
| Classic | 254 /253 | 15.2 | 0 /0 /0 | 115 |
| GATT | 298 /297 | 15.1 | 0 /0 /0 | 101 |
| CoC | 298 /297 | 15.0 | 0 /0 /0 | 77 |

| Later 150-request Balanced | Received / rendered | FPS | Video / audio / sender losses | Scheduled-video p95 ms |
| --- | --- | ---: | --- | ---: |
| Classic | 205 /204 | 15.1 | 0 /0 /0 | 123.3 |
| GATT | 236 /235 | 15.1 | 0 /0 /0 | 140.3 |
| CoC | 225 /224 | 15.6 | 0 /0 /0 | 354.0 |

Queues empty except pending decoder/chunks; scene, run order and a warm (Severe) source were not controlled. Scheduled-video = decoder output -> requested presentation, not glass/audio delay; not a transport ranking.

| Earlier async 640x360/15/300 (actual 20-24 kbps) | Received / rendered | FPS | Decoder queue / age / drops | Input p95 / scheduled playout p95 ms |
| --- | --- | ---: | --- | --- |
| Classic | 314 /313 | 15.0 | 1/7 /0 /0 | 1.2 /277 |
| CoC | 222 /221 | 15.0 | 1/7 /0 /0 | 1.2 /274 |
| GATT | 157 /156 | 15.2 | 1/7 /0 /0 | 1.2 /290 |

Hardware VP9 VBR mode 1 short 220/221 at 15 FPS, queue 1/7/age 0/drops 0. CBR-FD unavailable; the old automatic CBR mode 2 fallback was functional, not requested CBR-FD coverage. S22 decoder lacked the optional Android low-latency feature, so the flag was disabled.

GATT overflow regression 720x480/30/1100: 117/118 at 23.4 FPS then 64-chunk pipe overflow while retired feedback 871 kbps/23.3 FPS. The 512-MTU chunk pipe/real occupancy/EOF teardown reached 244/245 at 30.3 FPS, RX 0/512/no overflow, then hot Severe stop. Historical thermal-adaptive 660 kbps/native 15 FPS lasted 5 m 13 s, 4692/4693, source 0 drops/RX 0/512, 39.7 -> 41.0 C/Moderate; not 1100 capacity/current thermal policy.

## Authenticated failure and group-key controls

BMH2 signed adjacent/endpoint identities, BMS1/BME1 ordered AES-GCM, authenticated parent/original source remain mandatory. Trusted normal viewer-middle and content-blind opaque relay are different roles. Hop-local keepalive/keyframe/End never becomes graph-wide encoder feedback. Removed plaintext/BTV1/feedback v2 acceptance stays removed. Metadata visibility, human trust, re-key/revocation and independent audit limits remain explicit. See [PROTOCOL.md](PROTOCOL.md) for the wire and security rules.

| 2026-08-23 post-cutover S24 -> S22 | Retained result |
| --- | --- |
| GATT 2M/MTU 517 | 78/79 renders at 15.4 FPS, TX 0/30/drops 0; preceding 155/156 at 15.4; both identities match |
| CoC 2M | 77/78 renders at 15.3 FPS, TX 0/30/drops 0 |
| Classic request-first UI | 228/229 at 15.0 FPS, TX 0/30/drops 0, prefixes match auth; earlier 153/154 at 15.2 |
| Endpoint tamper | Both handshakes complete, first altered video BAD_DECRYPT, 0 accepted frames |

JVM tests reject replay/wrong-recipient/tamper/cross-domain hello; Android/Windows match a fixed AES-GCM vector. Windows Release/CNG identity/self-tests/advertise are historical only; the receiver's video-only BTV2 path still needs BTV3 audio/discovery.

Other unique controls: pre-endpoint CoC 78/79 at 15.5 FPS; same-stream reconnect 154/155 at 15.2; pre-endpoint GATT 153/154 at 15.2; GATT 700 target underproduced 392-413 kbps, 305/306 at 15.1/TX 0/30/drops 0/Moderate. Normal GATT 150 discovery 184/185 at 15 FPS. Fixed-hop Classic 300 pause 18 s exceeded the 8 s heartbeat, 634 received/632 rendered; resume 15 FPS/all percentiles 66.7/TX 0/33/drops 0/clean End. CoC 300: 249/248 at 15.1/TX 0/33/drops 0/>17 s; GATT 150: 15 FPS/all percentiles 66.7/0 freezes/TX 0/33/RX 0-1/512/>20 s. An easy 20-30 kbps scene does not prove 150 load.

Four-phone CoC group S24 -> A55 opaque relay -> (S22, M31) distributed one epoch key separately to authenticated endpoints; relay content-blind. Source 15 FPS/drops 0; S22 12-14 FPS/M31 2-4; planned M31 kill left S22 connected/recovered; S22 End ack. Key distribution/isolation proof only, joint quality FAIL. A later RECEIVER_LEFT member/terminal code passed JVM/build but the physical membership-End rerun was not completed; dynamic revocation/re-key are open. Older blind GATT relay 34-40/40, 1211 drops, 3.6 FPS FAIL; Classic opaque 15 FPS/three freezes/69 audio functional only. Normal CoC preflight 14.8-15 FPS/0 audio-decoder/RX 0-1 was not full chain coverage.

Private CoC S24 -> M31 opaque -> S22 proved endpoint identity through the relay radio and >45 s A/V, then load/framing FAIL; the 110 kbps/15 FPS starting budget is not a ceiling. GATT relay rollback 401 versus 425 / 1130 versus 1221 byte-ownership fixes extended one 86 s interval at 15 FPS/decoder 0; final post-second-fix endurance not run. Unbonded adjacent Classic relay did not run; missing pairing is not PASS.

## Debug connectivity is separate from media

A three-phone run (2026-10-01) recorded an Android BSSID-change shutdown of wireless debugging before the TLS errors: a network-change disable and `adb_wifi_enabled` 1 -> 0 set by Android. The same SSID/band did not prove an unchanged BSSID; the initiating reassociation cause is unproved. The debug transport environment is separate from Bluetooth media, which continued.

## Naming, uniform handle and relayed discovery (2026-10-10)

Design: the naming and relayed-discovery design was reviewed independently (findings R1-R22) and these decisions were taken:

1. Hop-local reading: a phone re-advertises only what its direct neighbours told it (with cost and age), which counts as hop-local.
2. Relay helping is ON by default while the app runs. It pauses automatically below 20% battery or in Battery Saver, and the person can switch it off. Active calls and streams always relay. A reviewer proposed "only while charging"; that was rejected because off-grid users cannot be expected to stay plugged in.
3. Consent to be discoverable through relays: asked once, preselected on.
4. Stream titles are public by default, with an in-app note.
5. The uniform handle derives from the existing 48-bit discovery prefix (Crockford Base32; already on air, so no new tracking surface). Strangers are shown with the full handle.
6. Discovery radius: 2 relays.

## Rotating discovery IDs on phones (2026-10-11, build 0725a213)

Phase 0 and phase 1 measurement sessions (manual adb/logcat with HCI snoop; each run has a hash manifest and results):

- A new advertising set gave a new random address on every phone (S24, M31, ASUS, A15s: 2 of 2 restarts each).
- Without a restart the stack's own address timer differs per phone: none in 31 min on the S24 and A15s, 14.2 min on the M31, 7.7 min (twice) on the ASUS. On the S24 and A15s the set restart at each 15-minute boundary is therefore the only thing that changes the address; the design's break-before-make restart is required, not optional.
- Phase 1 (`test_rotating_ids`): two UTC boundaries on all four phones; boundary callback +52 ms; silence 1.0-6.8 s; no RDI or address shared across epochs; a restart inside an epoch kept the RDI. A S24<->M31 call stayed ACTIVE 274 s across a boundary. Call setup rotating on vs off: 2.9 s vs 3.0 s (n=3 each, suggestive only).
- Not yet measured: GAP Device Name readability by an unpaired generic GATT client, SCAN_REQ/CONNECT_IND address equality, sub-second restart failure.
- P5 passive tracker (build 958c8a53, tools/rotid_tracker.py): **PASS, zero carry-over across 3 of 3 boundaries** (20:30, 20:45, 21:00 IST) with all four phones active (M31 stream source, ASUS forwarder, A15s leaf, S24 call-ready with reach pages); blocking findings 0; allowed logical stream token x1; non-blocking residuals: quasi-identifier x1, continuity x4, timing fingerprints x31. Boundary callbacks +54-77 ms, silences 1.1-7.8 s; in-epoch restarts kept E; rotid_open PASS on every stream/viewer hop. Limits: full-snoop ring buffers kept only ~10 min on M31/ASUS (partial), A15s snoop not retrieved; the 3-boundary verdict rests on the S24 snoop plus all four app logs; per-phone address-reuse attribution missing (0 findings unattributed). Sub-second restart: S24 and ASUS 10/10 sets started; M31 1/10 (process killed before start); A15s unmeasured. GAP name via a host-computer central: not obtained (UNMEASURED; P7 uses GapNameProbe).

## Discovery privacy: what the reference apps do (research 2026-10-11)

- No reference app renames the phone's Bluetooth adapter (bitchat-android has it commented out; bitchat iOS, qaul, Berty omit the name from adverts; Briar uses Classic discoverability only while adding a contact; GAEN uses non-connectable adverts). A rename persists phone-wide in system settings (survives crash, reboot, uninstall). Decision direction: no automatic rename; `setDiscoverable(false)` advertising sets on API 34+ hide the GAP name from connecting strangers (AOSP gap_ble.cc / gatt_db.cc / acl.cc); the neutral name becomes an explicit opt-in.
- Rotating IDs alone do not stop tracking while the link handshake sends the static key in the clear (bitchat PEER-ID-ROTATION.md; Bridgefy attacks), so the identity-hiding handshake (BMH3) follows immediately.
- Legacy-advertising controllers keep one address across set restarts (le_advertising_manager.cc), so a new ID can appear under an old address on those phones; detect and disclose.
- Seizure resistance: independent random daily presence keys deleted after use (GAEN TEK model) instead of keys derived from one long-lived key.
- Constant advert fields (transport, codec bits, flags, route cost, stream settings) are a cross-epoch fingerprint; reduce where safe, move capabilities behind the handshake later.

### Hardening (2026-10-11; PROTOCOL "Experimental rotating discovery IDs", JVM-tested, not on phones)

- Verified in AOSP source (android13 to android16 and main, fetched from android.googlesource.com): the Flags AD is added only for connectable AND discoverable sets (`le_advertising_manager.cc set_data`); the set's `discoverable` value reaches the connection (`OnAdvertisingSetTerminated`, `acl.cc`) for extended and vendor multi-advertising controllers; the GAP Device Name is `GATT_PERM_READ_IF_ENCRYPTED_OR_DISCOVERABLE` from android14-release (absent in android13); `setDiscoverable` is public API 34. The reuse of the initiator address belongs to the gd advertiser (`new_advertiser_address`, Android 13+, Android 12 behind a flag); Android 10/11 `btm_ble_multi_adv.cc` gives each instance its own address, so a legacy-only suspicion is raised on Android 13+ only.
- Independent review of the design: 14 findings RH1-RH14, all adopted in the text except as noted. The ones that changed the design: the hidden name protects only links phones open to us (RH1: a forged viewer request makes a source connect as central and expose its name; the neutral name stays the control there), the GATT database and its hash stay readable (RH2), "seizure resistance" gives a reader the next 27 days (RH3: advise Reset presence key), the bonding sentence of the API is not enforced in the SMP code found (RH8), a refusal of the parameters must fall back to discoverable and be visible (RH9), the set's value is fixed at start (RH9), a rename the owner makes after the option was applied wins and pauses the option (RH12), and an earlier automatic rename is kept with a one-time notice instead of being silently undone (RH11).
- Not changed, an open design question (PLAN, before Design E): the monotonic epoch never returns after a backward clock step (RH4).
- Rejected: per-set boundary jitter (a synchronous boundary maximises the continuity anonymity set; the lone-phone residual is stated) and a one-way ratchet for day keys (a lease holder could compute every later day).

## Call head-of-line A/B (2026-10-11, build 958c8a53)

Question: does writing call video as authenticated fragments (and giving audio the gaps between them) cut the time call audio waits behind a video frame, and does it cost video? Arms: A control; B `test_call_video_fragments` (fixed 2 KB chunks); C = B + `test_call_speech_led_playout` (late video may grow the shared call delay by at most 150 ms); D = B + `test_call_fragment_airtime_ms=40` (chunk = link rate x 40 ms, 675-4096 B). Calls were isolated 45 s bidirectional 300 kbps recorded-motion calls.

Method. Per cell (caller role x transport x direction) every arm has up to three repeats. Same-arm repeat spread (max-min) is the noise band, as in `tools/analyze_ab_arms.py`: an arm differs only when the mean delta exceeds the larger spread and the repeat ranges do not overlap. Counts below are cells (GATT 8, CoC 4). All 65 measured cases FAIL the device-run-v3 gates in every arm (profile mismatch, surface p95/p99, A15s FPS), so this is a comparison, not a pass. Not measured: received-audio PCM continuity/underruns, A/V offset, mouth-to-ear delay, CPU, battery, on-air order (HCI snoop did not run).

Measurement note. The `BENCHMARK call pipeline stage=audio_queue` series samples only audio taken by the normal dequeue path, not audio written between fragments, so in arms B/C/D it reads far too low (M31<->ASUS GATT max 447 ms control vs 35 ms B, while `audioWaitMaxMs` in B averages 147 ms of window maxima). The comparable audio queue age is the `media_age call_tx audioQueueAgeMaxMs` window series (the fragment writer records interleaved audio into it). Use that; the pipeline numbers are kept only as a supplementary column.

Setup: 65 of 86 cases measured, 13 blocked in r1 (S24 not in a verified quiet state / offline; 10 re-run in r1b, 3 S24-calls-A15s CoC never re-run), 8 setup failures (A15s CoC 5; ASUS-calls-M31 CoC control r1 socket read failed; A15s-calls-S24 GATT C r1 ADB pidof failed on A15s; r3 D A15s-calls-S24 A15s never reached the outgoing phase). Per arm, attempted-and-set-up: A 17/18, B 16/16, C 17/18, D 15/16 (excluding A15s CoC and blocked); no failure depends on the arm. Not run: M31<->ASUS GATT B and D in r3 (the four snoop cases), so those arms have 2 repeats on that pair; A15s<->S24 C and D have 2 repeats in some cells.

### GATT results (pooled per pair; mean over both caller roles, both directions, all repeats)

| M31<->ASUS GATT (A/B/C/D samples 12/8/12/8) | A | B | C | D |
| --- | --- | --- | --- | --- |
| audio queue age p50 / p95 / max over windows (ms) | 36 / 457 / 486 | 32 / 140 / 151 | 31 / 141 / 152 | 29 / 136 / 144 |
| audio wait behind video, max / mean (ms) | - | 147 / 50 | 135 / 48 | 109 / 21 |
| shared call playout delay p50 (ms) | 459 | 480 | 364 | 470 |
| audio drops (rx and tx) | 0 | 0 | 0 | 0 |
| visible FPS | 14.1 | 14.0 | 14.0 | 14.0 |
| freeze events per call / frozen % | 1.9 / 1.4 | 1.4 / 1.2 | 1.3 / 1.5 | 1.6 / 1.4 |
| longest stall (ms) | 392 | 471 | 461 | 454 |
| fragment overhead % / chunk mean, min, max (B) / fragments per s | - | 4.15 / 1189, 187, 2048 / 6.5 | 4.15 / 1175, 187, 2048 / 6.4 | 5.23 / 1487, 815, 2068 / 13.7 |

| S24<->A15s GATT (A/B/C/D samples 12/12/10/10) | A | B | C | D |
| --- | --- | --- | --- | --- |
| audio queue age p50 / p95 / max over windows (ms) | 93 / 281 / 314 | 70 / 170 / 195 | 68 / 131 / 151 | 50 / 89 / 95 |
| audio wait behind video, max / mean (ms) | - | 94 / 18 | 116 / 20 | 72 / 9 |
| shared call playout delay p50 (ms) | 582 | 589 | 469 | 595 |
| audio drops | 0 | 0 | 0 | 0 |
| visible FPS (limit 14.25) | 7.5 | 7.9 | 8.8 | 8.7 |
| freeze events per call / frozen % | 12.8 / 15.0 | 11.7 / 12.9 | 14.1 / 13.1 | 12.9 / 11.0 |
| longest stall (ms) | 1728 | 1657 | 1145 | 928 |
| fragment overhead % / chunk mean, min, max / fragments per s | - | 4.32 / 1280, 96, 2048 / 7.5 | 4.34 / 1246, 75, 2048 / 7.4 | 6.22 / 1309, 680, 2195 / 13.8 |

Beyond-noise counts on GATT (better/worse/within noise out of 8 cells, vs control A): audio queue age p95: B 4/0/4, C 4/0/4, D 6/0/2; audio queue age max: B 4/0/4, C 4/0/4, D 5/0/3; shared playout delay p50: B 1/0/7, C 4/0/4, D 2/0/6 (C vs B 5/0/3); audio drops 0/0/8 everywhere; visible FPS B 0/1/7, C 0/1/7, D 1/1/6; freeze events B 1/0/7, C 0/0/8, D 0/0/8; frozen share B 1/0/7, C 0/0/8, D 0/0/8; longest stall B 0/1/7, C 0/1/7, D 1/1/6. D vs B: audio wait mean lower in 8 of 8 cells (about half), audio wait max 3/0/5, audio queue age max 1/1/6. The one consistently worse cell is M31 sending to ASUS (ASUS calls M31): all three fragment arms show 0.2-0.3 FPS less (13.4-13.5 vs 13.7) and a 90-110 ms longer longest stall (642-658 vs 550 ms) than control, with disjoint repeat ranges (control 3 repeats, B and D 2, C 3).

### LE CoC results (M31<->ASUS only, 4 cells)

CoC has no head-of-line problem to fix: control audio queue age is p50 1 ms, p95 median 4 ms, max median 5 ms (one 350 ms outlier). Fragments move almost nothing: B/C fragment about 5 frames per call (D 120) and write 0 to 1 interleaved audio packets. Audio queue age max medians: A 5, B 9.5, C 6.5, D 13 ms (C and D within noise in 4 of 4 cells; B worse in 1, within noise in 3). D doubles the framing cost on CoC (overhead 7.9% vs 4.0% for B/C; chunk mean about 1.0 KB, fragments per s 8.9 vs 5.8) and, in the M31-calls-ASUS direction ASUS->M31, showed a 1.0-1.2 s video queue p95 and 1.1-1.2 s longest stall in 2 of 3 repeats (control once 0.35 s video queue p95, B once 0.16 s). Audio drops 0, visible FPS 10.2-10.3 pooled mean in every arm, shared playout delay p50 564-589 ms in every arm.

### Verdict per arm

- B fragments: fixes the GATT audio queue (p95 and max down 38-69%, from 457/486 to 140/151 ms on M31<->ASUS and 281/314 to 170/195 ms on S24<->A15s, beyond noise in 4 of 8 cells and never worse), at 4.2-4.3% framing overhead and no change to the offered video load (M31<->ASUS 284.6 vs 283.7 kbps). Does not move the shared playout delay (480 vs 459 and 589 vs 582 ms; it stays pinned at 600 ms on S24<->A15s), so the speech path still pays for late video. Cost: a small M31-sender video loss (0.3 FPS, +100 ms stall). No CoC benefit.
- C fragments + speech-led: same audio queue gain as B (151/152 ms on M31<->ASUS, 131/151 on S24<->A15s) plus the only arm that lowers the shared call playout delay: p50 459 to 364 ms (M31<->ASUS) and 582 to 469 ms (S24<->A15s), beyond noise in 4 of 8 cells vs control and 5 of 8 vs B, with no cell worse on playout delay. No audio drop, no extra freeze events or frozen share vs control beyond noise (C vs B: frozen share worse in 1 cell, the M31-sender cell above; visible FPS and longest stall worse in that same cell vs control), longest stall otherwise equal (S24<->A15s 1145 vs 1728 ms, within noise). On CoC it is neutral (the cleanest CoC arm, audio queue max median 6.5 ms).
- D fragments + airtime 40 ms: best audio wait (mean about half of B in 8 of 8 GATT cells; S24<->A15s queue age p95/max 89/95 ms vs control 281/314), no change to the 600 ms playout delay, 1.1-1.5x B's overhead on GATT (5.2-6.2% vs 4.2-4.3%) and about 2x the fragment rate (13.7 vs 6.5-7.5 per s), and clearly worse on CoC (overhead 7.9%, 120 fragmented frames per call, two 1.1 s video stalls). Residual audio wait on M31<->ASUS is about 145 ms max in both B and D, so fragment size is not the only floor there.

### Recommendation

1. Promote arm C's behaviour for GATT direct calls to the default (fragments plus speech-led playout), as one switchable policy so each half keeps its own off-switch: fragments are the only way to remove the 0.3-0.5 s audio queue wait, and speech-led playout is the only thing that lowers the 600 ms shared delay. Keep fragments off on LE CoC (no head-of-line wait to remove, small added queue age, no gain) by gating on transport or on measured audio wait.
2. Keep D (airtime) experimental and off: better mean wait but more overhead, twice the fragment rate and a CoC video-stall risk. Re-evaluate D+C (airtime plus speech-led, an arm not run) and D only on GATT after the snoop.
3. Do not call this a pass. Every case fails the formal gates in every arm, S24<->A15s video is bound by A15s sending (video queue p95 about 3 s, 7.5-8.8 FPS, about 12-14 freeze events per call) and fragments do not change that. The head-of-line fix improves audio and does not make A15s video acceptable.
4. Before promoting, close the gaps: received-audio continuity/underruns and A/V offset with speech-led playout (the 150 ms video growth cap is a lip-sync trade the logs cannot see), CPU and battery cost of 2x records on the M31 sender, a rerun of M31<->ASUS GATT B and D for the third repeat, S24<->A15s on C/D for the missing repeats, A15s CoC once its setup works, and a build with the pipeline `audio_queue` stage fixed to include interleaved audio.

Caveats: 3 repeats (2 in some arms), noise band from 2-3 values, so "within noise" is under-powered and "better" is a weak claim at n=2; two phone pairs, one of them with a weak libaom/AV1 sender; GATT and CoC differ by an order of magnitude in baseline wait, so pooled GATT numbers are not CoC predictions; counts of cells are not independent (each pair/direction shares a radio environment); radio conditions across repeats were not controlled.

What the M31<->ASUS GATT snoop (the snoop bundle, B and D, both caller roles, full HCI snoop on both phones) would have verified, and why it matters: (a) that audio writes really go on air between two video fragments, in the order the app logged (ATT Write Command order and Number-of-Completed-Packets timing), rather than sitting in the Android GATT queue behind several queued fragments; (b) the true head-of-line wait from write call to on-air transmission, which the app's `audioWaitMaxMs` (queue to write call) cannot see and which would explain the 145 ms floor shared by B and D; (c) how many ACL packets and connection events each fragment takes, i.e. whether D's smaller chunks waste events on the 7.5-45 ms connection interval and what the true overhead is with L2CAP and ATT headers on air; (d) receive-side arrival gaps for audio and video, an independent audio delay measurement. It did not run (r3 executed with `--hci-snoop off` and omitted those four cases), so nothing here is verified at HCI level and the app-side wait numbers remain unconfirmed by an independent clock.


## Relayed calls: first phone probe (2026-10-11)

Probe on build 958c8a53: S24 caller, ASUS helper (Android 11), M31 callee, GATT, `test_relayed_calls_require=1`, rotating IDs on and off. Both arms failed identically: the helper accepted SETUP at +0 s, finished the link handshake with the callee, and its app process died at +1.2 s. The S24 gave up at the 20 s setup deadline and the M31 logged `Incoming call failed: EOFException` about 1 s after the helper died.

| Question | Answer and evidence |
| --- | --- |
| Did discovery work? | Yes. The caller listed "relayed [target] via 1" from a reach page and called `relayed_setup budget=1` (S24 log, both arms). The probe note's "entries=0, the invite followed the debug path" was a logging gap: `relayed_reach_table: entries=` printed only the fixed-prefix table, and with rotating IDs the rows live in the role-0 table (now `rotating_entries=`). The off arm shows `entries=4`. |
| What ended the helper? | `NoSuchMethodError` on `InputStream.nullInputStream()` / `OutputStream.nullOutputStream()` (API 33; minSdk is 29). `dexdump` of 958c8a53 shows the direct `invoke-static` in `BluetoothPrivateCallRelay`; D8 does not desugar it. It is an `Error`, so the worker's `catch (Exception)` skipped it: no `relayed_setup: ... failed`, no `Private-call relay stopped`, no FAIL reply; only the `finally` ran (`Private call ended: Stopped helping a call nearby` on the worker thread, 6 ms after `rotid_open PASS` in the rotating arm). The uncaught Error killed the process. |
| Why is that the cause and not a timer, guard or GATT setup? | The ASUS logcat shows `--------- beginning of crash` at that instant in both arms and nowhere else (the S24 and M31 logs have none); the ASUS capture (`--pid`) stops there, and the off arm continues under a new pid 1.9 s later. No deadline line (setup deadline 18 s, pre-setup 20 s, chain guard 10/20 s after READY) was logged; READY was never sent; the GATT link to the callee completed at +0.2 s (MTU, services, CCCD). The DEBUG chain relay (the debug chain relay) accepts its real parent connection and never builds the stand-in streams. |
| Why did no test catch it? | The unit tests run on a JDK that has the method, and lint's NewApi check is not part of the builds. A scan of the whole 958c8a53 dex (`tools/dex_api_scan.py`, SDK api-versions.xml) finds no other unguarded Java-library call above minSdk; the android.* candidates are all behind `SDK_INT` checks. |
| What changed? | Own empty/discarding streams (`RelayParentStreams`); the relay worker contains an unexpected `Error` as a failed duty (FAIL 5 refused upstream, status line; `VirtualMachineError` is not masked); the forwarding stage is its own member with a JVM test over real record sessions; `ApiLevelGuardTest` and `tools/dex_api_scan.py` guard the class of bug; `rotating_entries=` in the table line. The caller's wait for a helper that dies silently stays bounded by the 20 s setup deadline (a dead helper's GATT link did not drop for 20 s). |

## BMH3 identity-hiding handshake: protocol choice (2026-10-11)

Question: which handshake hides both identity keys from a sniffer while keeping the signing-only Keystore identity, the discovery-binding checks and hop-local relays? Wire rules: PROTOCOL "Experimental BMH3 identity-hiding link handshake"; design reviews and the phase plan: PLAN "Design F".

| Protocol | How it hides identities | Who is protected from an active attacker | Fit here |
| --- | --- | --- | --- |
| TLS 1.3 / IKEv2 (SIGMA-I) | ephemeral key share first, the server and then the client certificate encrypted under handshake keys | the client (initiator); the server only against passive attackers (ECH needs a published HPKE key) | the chosen shape for Everyone-mode responders (R_FIRST) |
| JFKr (SIGMA-R) | the initiator reveals first, the responder only to an admitted peer | the responder | the chosen shape for Contacts-only responders (I_FIRST) |
| Noise XX | `e`, then `e, ee, s, es`, then `s, se` | initiator (Noise 7.8 level 8, "encrypted with forward secrecy to an authenticated party"); responder's static to any active initiator (level 1) | same hiding as R_FIRST, but static Diffie-Hellman needs a second static key per identity; the Keystore identity key only signs, and iOS Secure Enclave signing and key-agreement keys are distinct types |
| Noise IK / WireGuard | initiator static encrypted to the responder's known static; WireGuard `mac1` keyed by the responder's public key silences the responder to anyone without it | responder hidden from scanners; initiator identity not forward secret | needs the responder's key in advance (contacts only); its `mac1` idea is the contact tag |
| Signal X3DH, sealed sender | server-hosted prekeys | not a link handshake | no server off grid |
| Briar | connections only between contacts, per-period transport tags, ephemeral plus static keys with a proof of ownership | both, contacts only | the same prior-knowledge idea as the contact tag |
| bitchat (Noise XX) | static keys encrypted in the handshake, but announcements publish them in cleartext | initiator only | the lesson: hiding in the handshake is pointless while the advertisement or an announce carries the key |

Choice: SIGMA in both orders (the responder's global mode picks the order) with a TLS 1.3 shaped schedule and a lease-keyed contact tag bound to the selected advert. Rejected: Noise XX or IK (second static key), a signed "BMH3 capable" bit in BMH2 hellos (readable only after the initiator's hello is sent), an escalating tarpit on the BMH3 path (it delays contacts that cannot prove they are one; a signing budget replaced it), a responder-side refusal of BMH2 in Everyone mode (protects nobody). Not solved: an Everyone-mode responder reveals its identity to any active prober; hiding it needs prior shared knowledge (Contacts-only) or per-epoch pseudonymous identities for strangers (Design F, Phase 2, under "Design records").

## Pseudonymous strangers: design choice (2026-10-11)

Question: what does a stranger who connects meet instead of the long-term identity, and when does the real identity follow? Wire rules: PROTOCOL "Phase 2: pseudonymous stranger identities"; the review (PR1-PR15) and the phase plan: PLAN "Design F".

| Approach | What it gives | Fit here |
| --- | --- | --- |
| bitchat (PEER-ID-ROTATION.md) | rotating peer IDs plus a replacement binding proved inside the session (the proof must name the exact session); explicit non-goal: an active attacker who completes a handshake learns the identity | the in-session binding idea is adopted (the reveal is bound to the end-to-end exporter and to both throwaways); the non-goal is exactly the gap  closes |
| Signal sealed sender | the sender certificate is encrypted to the recipient; needs a server and an access key derived from the profile key | no server off grid; the idea of "reveal only to who may know" maps to the lease tag |
| Briar | connections only between contacts, per-period transport tags | the contact tag already does this; no stranger mode |
| Session / Cwtch (onion-routed ephemeral IDs) | long-term identity never leaves the contact relationship; sessions use ephemeral addressing | needs relays and a network; the principle (stranger sessions use throwaway identities) is adopted |
| Tor v3 key blinding | unlinkable per-period public keys derivable by anyone who knows the base key | needs scalar multiplication with the identity secret; the Keystore key only signs, and a software base key would be a second identity per phone. Rejected |
| Berty / Meshtastic / qaul | stable keys or names | no stranger privacy |

Choice: a software throwaway key per connection (callee) and per attempt (caller), the real key for recognised contacts only (lease tag, now wired to the real presence keys), a signed `REVEAL` after Accept bound to the session, callee first, the caller verifying the binding to the selected offer before it reveals. Rejected: an epoch-wide stranger key (it attributes a whole epoch to one accepted reveal, ties call and stream roles together and breaks forwarders when it changes; the advertised ID already gives the continuity), salt in the handshake (a confirmation oracle for anyone who knows a public key), keeping BMH2 for Everyone mode (it answers a prober with the real key), stranger message exchanges (the contact card and bundle IDs are stable identities), an automatic new presence generation on a suspected lease leak (false positives break every contact), "answer without revealing" (the caller cannot tell the owner from an impostor; it would have to accept an unbound callee). Not solved: accepting a call shows the caller who you are, calling shows whoever answers who you are, ringing nearby phones locates one, a held connection across a boundary links two IDs, the capability bytes identify a phone class, streams (until they run on BMH3), relayed calls to such a phone.

## Release, licence and later direction (2026-10-10)

- Licence: GPL-3.0-or-later. Chosen over Apache/MIT to prevent closed clones of a security app. AGPL adds nothing for a serverless peer-to-peer app. Dual licensing stays possible while the project holds the copyright (a CLA for outside contributors). The test-video generator in `tools/video-stress-generator/` is MIT.
- Distribution: open source; an unofficial signed APK on GitHub Releases, plus F-Droid and IzzyOnDroid; separate arm64-v8a and armeabi-v7a APKs. No Play Store or App Store for now. iOS is published as source only.
- INTERNET permission accepted, for the local-only "Share app nearby" Wi-Fi Direct server. The app never contacts the internet.
- Name: MeshLive. The app name and organisation are the same: GitHub org github.com/MeshLive. HexLive was found taken; NodeLive was rejected because an IoT dashboard project uses it; meshlive.de is a German clothing brand (a different class). The application ID is `io.github.meshlive.app` (no domain is bought for now; the iOS bundle ID will be `io.github.meshlive.ios`); the Kotlin package stays `com.example.bluetoothtest`, and the host tools follow the ID (`MESHLIVE_APP_ID` overrides it). Web checks only; not legal clearance.
- Unreadable secure store: fail open with a banner and retry. Names are shown when the device is unlocked and hidden on the lock screen.
- Started as default-off experiments that do not block launch: rotating discovery IDs (design first), native libopus (FEC/PLC), a call resolution ladder, iPhone relaying, automatic store-and-forward sync. Translations dropped for now. The Wi-Fi bearer comes later (see [PLAN.md](PLAN.md)). Experimental switches are tracked in the "Experimental, alternative and optional behaviour" register in [ARCHITECTURE.md](ARCHITECTURE.md).
- UI: push-to-talk is not a mode (hold Mute to talk inside calls). No relay toggle on the Watch screen (automatic, one Settings switch). No security, heat or battery text unless actionable. iOS uses native HIG while keeping Android's screens, flow, wording and brand colours.

## Default video settings (2026-10-10)

Calls and streams default to 426x240 at 15 FPS and 200 kbps (was 640x360 at 300 kbps). WebRTC's VP9 singlecast start limits put 360p at about 350 kbps, so 300 kbps under-fed 360p, and real Bluetooth links (especially relays) often deliver less. 360p and 480p remain choices, and the call resolution ladder can step up when the link allows. Benchmark launches pass explicit settings, so they are unaffected.

## Scope for the first release (2026-10-10)

- Scope: direct voice/video calls and multi-hop live streams (viewers relay automatically). Private-call relaying is DEBUG/test-only today; user-facing background call relaying (a "Helping a call nearby" notification, the person's own activity pre-empts it, pauses below 20% battery) plus multi-hop discovery (naming phase 2) is the first feature after release, behind an experimental switch.
- Publication: the public repositories are MeshLive/meshlive-android, MeshLive/meshlive-ios and MeshLive/.github, all GPL-3.0-or-later, copyright "The MeshLive Authors", each starting from a fresh single commit. The video stress generator is published inside meshlive-android. Device identifiers are scrubbed from published files, and the test phones' app identities are reset after launch. The [README](../README.md#how-this-was-built) names the AI used and [CONTRIBUTING.md](../CONTRIBUTING.md) sets the policy for AI-assisted work. The speech test audio (LibriSpeech CC BY 4.0, LibriVox public domain, an Open Goldberg Variations CC0 clip) is published with attribution in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Open-defect investigation notes (moved from the plan, 2026-10-12)

These are the detailed findings that used to sit in the plan's open-defect and policy sections. The plan lists only what is still open.

### Open-defect investigation notes (the original table, moved from the plan)

## Open defects in shipped behaviour

These are open across every phone combination that uses the affected behaviour. The benchmark suite's case state (see
[TESTING.md](TESTING.md)) owns pass and fail history and retest requirements.

| Work | What must be established before closing it |
| --- | --- |
| Uneven playback and picture quality | Separate source pacing, encoder output, Bluetooth stalls, receive queues, decoder processing and presentation. Correlate traces with run, session and PID identity and each phone's clock; compare the same input and settings. Frame-timing proxies and short clean prefixes cannot approve screen smoothness. The retained scheduler analysis has initial-only clock snapshots, so obtain the missing stage and clock evidence before promoting another display policy |
| Connecting/Recovering flashes and reconnect-to-scan loops | Trace each setup, scan, cancellation, disconnect and completion against its owning attempt and session. Establish a stable UI state, bounded retries, truthful discovery and reusable availability after End, loss and cancellation; keep planned losses distinct from unexpected ones. Causes found offline from saved viewer logs (2026-10-11): the recovery cover (a 70% black layer) followed the decoder's recovery flag with no hysteresis and flashed on and off about a dozen times in 30 s on older builds; a failed join returns to the browser by design while its route cools down. Fix built (default on, control arm `test_receiver_ui_stability=false`, not yet tested on phones). Still open: a failed join still shows the playback screen for 0.25-0.4 s, some "Connecting" overlays wait the full 15 s with no progress text, and planned versus unexpected loss is not distinguished in the UI |
| Real three-phone chains | Diagnose the retained decrypt failures, missing completions and guard withdrawals without assuming that event order across different phone clocks proves the cause. Preserve the authenticated original source, requested immediate parent, ordered encryption, admission and overload protection; direct-source fallback fails the requested chain |
| Bidirectional calls and reverse setup | Resolve the reverse-caller GATT setup that queued MTU work without completing it; trace request, callback, teardown and stale-session ownership. Native inputs reject a negotiated geometry or FPS mismatch before media starts, so prepare exact matching native call requests or report ineligibility. Improve both directions, local controls, framing and recovery independently. Causes found offline (2026-10-11): the original wedge was MTU, PHY and priority work queued within 1 ms of the connection callback (fixed by the setup order MTU, services, subscription, then the client PHY); the stalls that remain (21 of 1,167 saved setup attempts with an MTU request and no callback) are either a dropped request (the stack logs `configureMTU() - No connection`, all before the deferred GATT close on SDK <= 30) or a request queued behind the stack's own service discovery (A15s median 1.34 s, a 5th relay child 3.2 s). Fix built (default on, control arm `test_gatt_mtu_probe=false`, not yet tested on phones): at 0.75 s without a callback one `readPhy` proves the request is only queued and the wait runs to 8 s; no callback keeps the 3 s verdict. Still open: the retry onto a half-closed ACL, and one A15s case that received the MTU response with no app callback |
| Relayed viewers hold seconds of media | A viewer behind a relay showed 2.7-7.5 s held age with its playout pinned at 2000 ms (direct 250-345 ms). Traced per stage (S24 -> M31 -> ASUS, LE CoC, 200 kbps offered): the source and the first hop add tens of milliseconds, the relay forwards the original frames, the relay's app queue holds 1.3-1.9 s, the Android socket buffer and stack below it 3.2-4.4 s, the air carries a flat 144 kbps (one PDU per 15 ms event), and the viewer's playout then holds 2 s because it cannot shrink while media is late. The relay skipped its child pacer whenever audio was queued anywhere, which is always during a backlog. Built 2026-10-11: `test_relay_pace_head` (default on, control arm false) and Experimental `test_relay_pace_lead_cap` (see the register in ARCHITECTURE.md). Open: a phone A/B on one frozen build (control / head fix / head fix + lead cap, CoC and GATT, 3 repeats, a direct control); a policy for a relay link slower than the stream (shed, withdraw and reroute, lower the sender rate, or batch audio records); a relay holds its own screen 1562 ms (the child-join reserve) |
| Relay dual-role scheduling (M31 relay child link) | A relay is LE peripheral to its parent and LE central to its child, both at 15 ms, so their anchors keep one fixed phase. On the M31 (Exynos 9611) a child anchor 11.3-13.7 ms after the parent anchor got no completed exchange (9 of 55 child attempts silent until disconnect reason 0x08, 22 never established with 0x3E; ASUS: 0 and 5 of 29); the 7.5 ms and 45 ms stages never died. Built: default-on `test_relay_child_stall_guard` (closes a silent LE child link after 2 s, counted by the establishment fallback), Experimental `test_relay_upstream_dck` (an Android 14+ parent requests DCK, 30 ms, for a relay-capable child so the relay's 15 ms child link has two events per parent event), an inert device-policy proposal, and `tools/relay_dual_role_snoop.py`. Open: the phone A/B with HCI snoops on both relay candidates (relay under test and control relay, GATT and CoC, planned viewer restarts labelled, 3 repeats, at least 15 child attempts per arm; DCK passes when the parent link shows 30 ms, no silent timeouts and no worse leaf freezes; the stall guard passes when a dead attempt ends within 3.5 s and no healthy join is closed). Evidence: DECISIONS "Relay dual-role scheduling" |
| Call speech behind video writes (head-of-line) | GATT call audio waited 620-1193 ms behind one video frame; the audio-headroom fix only stopped queue overflow. A four-arm A/B (control, fragments, fragments + speech-led playout, fragments + airtime-sized chunks) on M31 <-> ASUS and S24 <-> A15s is in DECISIONS "Call head-of-line A/B"; fragments with speech-led playout are on for direct GATT calls in user launches since 0.2.0-beta. Open: received-audio continuity and A/V offset with speech-led playout, CPU and battery cost on the M31 sender, a third repeat on two arms, A15s over CoC, and an HCI-level check that audio really goes on air between fragments |
| Visible age of the last received frame | A frozen frame must never be presented as live. Built behind the default-off switch `test_viewer_frame_age` (Settings > Features): stale-picture dim and badge, an honest "Last update" label (this phone's last picture, not camera-to-screen delay) and `viewer_frame_age` log lines. Remaining: a phone test in the relay loss/rejoin lane, then flip the default. Still open: a stall that keeps the link up replays its backlog late by the no-loss design (the bounded-age experiment is the separate answer); a clock-synced end-to-end age would need a protocol design |
| Stream link role reversal (design, needs a PROTOCOL review) | Today a relay or source is the LE central and connects to the advertising child. A relay that is a busy peripheral on its parent link cannot always establish that second, central connection (an M31 snoop shows ten LE Connection Complete events at 45 ms, each ending 0x3E after 6 events). Root fix: the child connects to the relay as central and the relay accepts the CoC, with the same authentication, grant and hop-local control. It would remove the central-initiation failures but not the equal-interval collision above, so do not build it before the dual-role A/B shows how many failures remain with `test_relay_upstream_dck`. Before coding, record in PROTOCOL.md the advertisement and accept flow and its admission check, discovery and privacy impact, and behaviour on Android 10-14 stacks |
| Requested versus effective settings | Use the register of concrete limits and the capability evidence to challenge call budgets, legacy protections and other limits. DEBUG higher-rate comparisons stay experiments. Expose every difference in encoder, allocation, geometry, rate, FPS, audio and buffer rather than silently substituting settings |

### Policy workstream status (build 454cf485, 2026-10-07 and later)

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

### Interim bounded-age evidence

Interim bounded-age evidence (not conclusive; repeats were still running): with the A15s sending over a weak
libaom/AV1 link, the bounded arm paused video one to three times per call (longest stall 3.8-9.2 s, 26-58% frozen,
against 0.4-2.7 s and 3-18% for no-loss) and did not improve speech; on M31 <-> ASUS calls at 1000 kbps it was worse
(CoC held-age p95 1479 against 1108 ms) or had about twice the freezes (GATT); S24-sending directions and M31 <-> ASUS
streams were about equal. Likely cause: the call arm assumes a 500 ms peer base delay (a 100 ms queue budget) and
requested keyframes re-create bursts. Current recommendation: keep no-loss as the default and bounded age as an
off-by-default experiment; a fairer variant would exchange the playback profile in call setup, make audio-only the last
resort and recover with intra-refresh or the next natural keyframe.

### Discovery privacy status (2026-10-12)

Status (2026-10-12): rotating discovery IDs, the BMH3 identity-hiding handshake and pseudonyms toward strangers are on
by default since 0.2.0-beta (one constant each; an explicit stored choice wins). The design was reviewed on 2026-10-10
(findings RV1-RV19). Coded: phase 1 (LE call-ready presence), the Visible-by-handle window, phase 1b (stream offers,
viewer requests, forwarders, call-relay chains), phase 2 (presence leases, `BDX1`, the recognition table), reach pages
version 6 for relayed calls and non-discoverable connectable sets on Android 14+; formats and vectors are in PROTOCOL.md
under "Experimental rotating discovery IDs". Measured once, on four phones: no field carried over any of three ID
changes (PRIVACY.md "Measurements"). Not measured: battery cost, the Bluetooth name read on the A15s, an
active attacker holding a contact's key, fake adverts, relays with the new handshake and abuse limits. Open design
question: the monotonic epoch never returns after a backward clock step, so a lead of 3 to 96 epochs leaves a phone
unable to receive a version 5 call until it recovers. The list below was the plan; items 0 and 1 were exercised in that
session, the rest follow:

### Design C status

- **Status.** The first phone probe (2026-10-11) found a helper crash from an API 33 call (fixed; see DECISIONS
  "Relayed calls: first phone probe"). In the 0.2.0-beta combined privacy session, one-helper calls completed on GATT
  and LE CoC; two helpers never formed a route. Deferred: automatic candidate iteration inside one attempt, the embedded
  single-entry carrier for phones without a free advertising set, Classic on-demand relays, mid-call re-routing,
  extended-advertising reach lists and an iPhone caller.

### Design F rationale, reviews and measurements

**Why.** Rotating discovery IDs hide the advertisement only; the BMH2 link handshake sends both identity keys in the
clear on every hop and answers any connector with its own. BMH3 is a SIGMA-style handshake in both reveal orders using
the existing primitives and the signing-only Keystore key, with a lease-keyed contact tag bound to the selected advert.
Phase 2 gives a stranger who connects a throwaway software identity per connection; the real identity follows only for
verified contacts or after the callee presses Accept, in a signed `REVEAL` bound to the session.

**Reviews.** Two independent security reviews (findings SR1-SR14 and BR1-BR11 for the handshake, PR1-PR15 for the
pseudonyms) were adopted or rejected with reasons in the design record; the executable specification is
`tools/bmh3_reference.py` with byte-exact vectors in the JVM tests.

**Measured** (the combined privacy session of 2026-10-11, build 3996bfb2): a snoop of the BMH2 control call showed both
identities in the clear and none in three stranger calls; the reveal followed Accept and never followed Decline. Not
measured: battery, the A15s name read, an active lease-key prober, fake adverts, relays with BMH3, message exchanges and
abuse limits.

### Duplicate role-reversal row

| Stream link role reversal (**design review required**, PROTOCOL change) | Today a relay or source is the LE central and connects to the advertising child. A relay that is a busy peripheral on its parent link cannot always establish that second, central connection: an M31 relay snoop (2026-10-10) shows ten LE Connection Complete events at a 45 ms interval, each ending 0x3E after 6 events. Root fix: the child connects to the relay as central, and the relay advertises its offer and accepts the CoC, with the same hop and end-to-end authentication, grant and hop-local control; the busy relay then never initiates, and each phone is central for at most its own parent link. Before coding, record in [PROTOCOL.md](PROTOCOL.md): the advertisement and accept flow and its admission check before accept; discovery and privacy impact; behaviour on Android 10-14 stacks (CoC server on the relay); how the child chooses or keeps the 2M/1M PHY; and A/B evidence against `relay_establish_fallback` on the same topologies. |

## Design records

The full texts of the larger designs that used to sit in the plan, kept here as the record of what was proposed, reviewed and decided. [PLAN.md](PLAN.md) carries only each design's status and what is left; wire rules are in [PROTOCOL.md](PROTOCOL.md). Related status and rationale sections are "Design C status" and "Design F rationale, reviews and measurements" above.

### Design A: store-and-forward messages (delay tolerant)

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

### Design B: group calls (3-6 people) and group push-to-talk

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

### Design C: relayed private calls and multi-hop discovery

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

### Design D: dual listening, CoC and GATT at once

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

### Design E: uniform adverts, capability fields behind the handshake

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

### Design F: BMH3 identity-hiding handshake and stranger pseudonyms

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

## Plan detail moved from the plan (2026-10-12)

The longer descriptions of the policy workstreams, intercom work, discovery-privacy phone-test plan, headsets, distribution, iPhone interoperability, Wi-Fi bearer, missing measurement adapters and platform work. The plan keeps one short item for each.

### Policy work: fewer fixed limits

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

### Intercom core and product direction

Intercom (voice, push-to-talk) and live video are equal parts of the product; the reasoning is in
[DECISIONS.md](DECISIONS.md) under "Product direction".

| Work | Done when |
| --- | --- |
| Bounded media-age experiment (**Experimental**, default off) | Calls keep speech within about 300-400 ms by lowering bitrate or resolution, dropping codec-safe non-reference frames, then going audio-only; streams buffer up to about 2 s, then shed or resync while keeping authenticated order. A randomized A/B against no-loss (M31 <-> ASUS, S24 <-> A15s, both directions, 150-1200 kbps with bursts) reports media age, stall length, frozen share and speech continuity, and the maintainers choose the default from it. No-loss stays the default meanwhile |
| User-centred gates | The `device-run-v4-user` profile in [TESTING.md](TESTING.md) is built around media age, longest stall, frozen share, audio continuity and task success. Remaining: a decided media-age target, and adapters for task success and speech comprehension |
| Intercom core | Reliable 1:1 voice and push-to-talk in small groups, text, photos and location with time and accuracy; screen-off operation and reconnect proven on phones. Later: store-and-forward voice and video messages (Design A) |
| Signing mode | A legibility-first video profile (at least 20 FPS target for hands and faces), validated with fluent signers before any accessibility claim |

Current recommendation: keep no-loss as the default and bounded age as an off-by-default experiment. The interim evidence is in [DECISIONS.md](DECISIONS.md) under "Open-defect investigation notes".

### Discovery privacy

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

### Bluetooth headsets

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

### Distribution and field reports

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

### iPhone interoperability

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

### Wi-Fi Direct / Aware bearer (later)

**Design review required.** An optional, faster bearer that needs no infrastructure, with the same authenticated
identities and encryption, chosen explicitly. Bluetooth stays for discovery and as the fallback. Not started. It must
not become a silent transport fallback, and each bearer is measured separately under the same gates. (The experimental
Wi-Fi Direct page for sharing the app itself is a different, offline-sharing feature.)

### Measurement adapters still missing

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

### Platform and product work

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
