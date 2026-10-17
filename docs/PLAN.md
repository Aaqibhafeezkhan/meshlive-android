# Plan

For contributors: what is unfinished. Current behaviour lives in the owning document (see the [README](../README.md#documentation)); when an item is finished its description moves there and the item is removed here. Measured reasons for existing policies, the full design texts and the longer plan notes are in [DECISIONS.md](DECISIONS.md).

Two labels are used:

- **Design review required**: the item changes the protocol, the security model or what a phone reveals over the air. It needs a written design in PROTOCOL.md and an independent review before code ships, and it ships behind a default-off flag (see the register in [SWITCHES.md](SWITCHES.md)).
- **Experimental**: coded behind a default-off switch and not yet validated on phones.

Nothing here is a promise or a schedule. Contributions are welcome on any item; open an issue first (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

## Priorities

| Priority | Area | Status | Design review |
| --- | --- | --- | --- |
| 1 | Open defects in shipped behaviour | Open | No |
| 2 | Policy work: fewer fixed limits | In progress | No |
| 3 | Discovery privacy (rotating IDs) | Coded; on by default since 0.2.0-beta | Yes |
| 4 | Intercom core and product direction | Partly coded, Experimental | Parts |
| 5 | Design A: store-and-forward messages | v1 coded, JVM-tested only | Yes |
| 6 | Design B: group calls | Design only | Yes |
| 7 | Design C: relayed private calls | First slice coded, Experimental | Yes |
| 8 | Design D: dual listening | Coded, Experimental | Yes |
| 9 | Design E: uniform adverts | Design only | Yes |
| 10 | Design F: BMH3 handshake and pseudonyms | Coded; on by default since 0.2.0-beta | Yes |
| 11 | Bluetooth headsets | Deliberately excluded; open question | No |
| 12 | Distribution and field reports | In progress | No |
| 13 | iPhone interoperability | Compact discovery coded, Experimental | Yes |
| 14 | Wi-Fi Direct / Aware bearer | Not started | Yes |
| 15 | Measurement adapters still missing | Open | No |
| 16 | Platform and product work | Open | Parts |

## Open defects in shipped behaviour

Open across every phone combination that uses the affected behaviour. The benchmark case state ([TESTING.md](TESTING.md)) owns pass and fail history; the investigation notes behind each item are in [DECISIONS.md](DECISIONS.md), "Open-defect investigation notes".

- **Uneven playback and picture quality.** Separate source pacing, encoder output, Bluetooth stalls, receive queues, decoder and presentation, correlated by run, session, PID and phone clock. Frame-timing proxies cannot approve screen smoothness.
- **Connecting/Recovering flashes and reconnect loops.** Stable UI state, bounded retries, truthful discovery after End or loss. `test_receiver_ui_stability` (default on) awaits phone tests; a failed join still shows the playback screen for 0.25-0.4 s.
- **Real three-phone chains.** Diagnose decrypt failures, missing completions and guard withdrawals; direct-source fallback counts as a failed chain.
- **Bidirectional calls and reverse setup.** Reverse-caller GATT setup stalls (`test_gatt_mtu_probe`, default on, awaits phone tests); the retry onto a half-closed ACL; one A15s case.
- **Relayed viewers hold seconds of media.** Viewers behind a relay held 2.7-7.5 s (direct 250-345 ms). `test_relay_pace_head` (default on) and Experimental `test_relay_pace_lead_cap` need a one-build phone A/B, and a policy for a relay link slower than the stream.
- **Relay dual-role scheduling.** Parent and child LE links keep a fixed phase and some phases never complete on the M31. `test_relay_child_stall_guard` (default on) and Experimental `test_relay_upstream_dck` need an HCI-snoop A/B on both relay candidates.
- **Call speech behind video writes.** Fragments with speech-led playout are on for direct GATT calls. Open: A/V offset, sender CPU and battery on the M31, a third repeat, the A15s over CoC, an HCI check of audio between fragments.
- **Visible age of the last received frame.** `test_viewer_frame_age` (default off) needs a relay loss/rejoin phone test; a clock-synced end-to-end age needs a protocol design.
- **Stream link role reversal (design review required).** A busy-peripheral relay cannot always connect out to its advertising child; the root fix is the child connecting as central. Wait for the dual-role A/B, then write the PROTOCOL design.
- **Requested versus effective settings.** Expose every difference in encoder, allocation, geometry, rate, FPS, audio and buffer instead of substituting settings silently.

## Policy work: fewer fixed limits

Fixed numbers (kbps ceilings, 20% margins, 5 s keyframes) were wrong for some phone or link every time. Four workstreams replace them with measurements: no frame loss (latency is the elastic resource; the default, under A/B against bounded media age), self-measured link capacity from packet trains, burst-free encoding (frames capped to the measured link, intra-refresh where supported) and no fixed limits left (constants only as fallbacks before the first measurement). Open: calls beyond a link's capacity, forced CBR on the M31 and the encoder trim, relay child joins, and the 20% planning margin. Workstream tables: [DECISIONS.md](DECISIONS.md), "Plan detail moved from the plan". Next in this workstream: the phone A/B of the Experimental `test_join_probe_seed` (a fresh link's pacer seeded from a short measured burst instead of the cautious prior) and `test_seed_raise_drain_guard` against the default on-time seed raise (SWITCHES; DECISIONS "Starting pace of an unmeasured stream link").

## Intercom core and product direction

Intercom and live video are equal parts of the product. Open: the bounded media-age A/B against no-loss (Experimental; no-loss stays the default meanwhile), a decided media-age target and task-success adapters for the user-centred gates, reliable 1:1 voice, push-to-talk and screen-off operation proven on phones, and a signing mode validated with fluent signers.

## Discovery privacy

**Design review required.** Rotating discovery IDs, BMH3 and stranger pseudonyms are on by default since 0.2.0-beta; formats are in [PROTOCOL.md](PROTOCOL.md) and measurements in [PRIVACY.md](PRIVACY.md). Not measured: battery cost, the Bluetooth name read on the A15s, an active attacker holding a contact's key, fake adverts, relays with the new handshake and abuse limits. Open design question: the monotonic epoch never returns after a backward clock step, so a lead of 3 to 96 epochs leaves a phone unable to receive a version 5 call until it recovers. The phone-test plan (phase 0 measurements to contacts-only mode) is in [DECISIONS.md](DECISIONS.md), "Plan detail moved from the plan".

## Larger designs

The full design texts are in [DECISIONS.md](DECISIONS.md), "Design records".

- **Design A: store-and-forward messages (design review required).** v1 (text, voice note, photo between two directly adjacent phones with accepted contacts) is coded and JVM-tested only; the carrier role is hard-off. Left: phone tests, forward secrecy before carriers ship (one-time prekeys), the 1 MB prototype that fixes the chunk and bundle limits, and key-change handling for contact cards.
- **Design B: group calls and group push-to-talk (design review required).** Design only (`test_group_call`). Recommendation: a mixing star built so it is a depth-1 mixing tree. Left: prototype an audio-only star for N = 3 over GATT (hub throughput, media age, CPU, battery), then N of 4-6, push-to-talk and `BGK2` rekeying; video and trees come after.
- **Design C: relayed private calls (design review required).** First slice coded behind `test_relayed_calls` (Experimental); reviewed 2026-10-11. Left: the one-helper phone verification queue (GATT then LE CoC, rotating IDs off and on), a measured relayed call cap, mid-call re-routing, Classic and extended-advertising routes, and an iPhone caller.
- **Design D: dual listening (design review required).** Coded behind `test_dual_listening` (Experimental): a CoC endpoint also answers over GATT so the A15s can still call CoC phones. Left: the mixed-pair phone matrix on the S22, Xiaomi and A55.
- **Design E: uniform adverts (design review required).** Design only. Count the distinct version 5 layouts per kind first; if several are populated, move the capability bits behind the signed hello (a protocol version change). Otherwise disclose them as quasi-identifiers.
- **Design F: BMH3 handshake and pseudonyms (design review required).** Coded, on by default since 0.2.0-beta. Left: streams still answer BMH2 so a source stays visible; pseudonym phones cannot talk to older versions; message exchanges and relayed calls need verified contacts; binding the verification QR to a live handshake.
- **Design AL: audio-only live stream, a nearby radio.** Implemented behind the default-off `test_audio_live` (Settings > Features "Audio-only live"); decisions Q-AL1 to Q-AL8 answered 2026-10-12. Offer marker `0x40`, signed `BTL1` stream (the source signs every record), Opus 24 kbps in 200 ms records (about 35 kbps on the link, a Voice relay needs 83 kbps against 567 kbps for 240p video), source cap 4 listeners, one child per relay, age-bounded queues, background listening under a media-playback service. Left: phone tests T-AL1 to T-AL11 (weak pair, three-relay chain, fan-out ladder, background, older build), then the promotion decision. Later: hold-to-talk announcer mode, a verified-contacts-only audience, an application-scoped listener if activity kills show up, per-stream pseudonyms (Design F), adaptive rate and two-child relays.
- **Design M: messaging v2 (reviewed twice; design review required for phases 2 and 3).** Phases 0, 1a, 1b, 1c and 1d are built behind `test_messaging_v2` (off; JVM tests only). Conversation threads, attachments of any format (inline up to 1 MB, receiver-pulled 1 MiB segments up to 64 MB, never carried by couriers, nothing decoded automatically), receipts, a thread link; then forward secrecy as a prekey bootstrap plus Double Ratchet sessions (phases 2 and 2b) before carriers (phase 3); groups later. Left: the phase 0 phone measurements (v1 goodput and background receive), then the 1c phone pass (ASUS screenshots against the UI spec, TalkBack), notification routing to a thread, and phone tests T-M1 to T-M18. Q-M1 to Q-M12 are answered (recommended options).

## Other open work

- **Bluetooth headsets.** Never selected as call output (protective default, not a measured limit). Left: measure what a headset costs a live call per stack and transport, then maybe an Experimental labelled opt-in scoped by a device-family entry.
- **Distribution and field reports.** Real phone screenshots in `fastlane/`, the F-Droid and IzzyOnDroid inclusion requests, and a fuller Export diagnostics block built from bounded in-memory records ([ARCHITECTURE.md](ARCHITECTURE.md#diagnostics-block-contract-for-export-diagnostics)). Google Play is not started.
- **iPhone interoperability (design review required).** Compact discovery is coded behind `test_ios_compact_discovery`; the iOS client is in MeshLive/meshlive-ios. Left: every reference phone finding an iPhone's offers with the switch on, with `scan_stats` evidence of controller versus host filtering. Optional H.264 (`test_h264_codec`) is not coded and needs a PROTOCOL design first.
- **Wi-Fi Direct / Aware bearer (design review required).** Not started. Optional, explicit, never a silent fallback; Bluetooth stays for discovery.
- **Measurement adapters.** Missing: native stress and calibration evidence, actual output capture with cross-phone clock anchors, reference colour through each encoder and decoder, acoustic experience, lifecycle with Camera2 and process death, and radio and endurance runs. Each needs focused failure-case tests before it can close a benchmark request.
- **Platform and product work.** Dynamic group-key revocation and an independent protocol audit; multi-child and alternate-parent graphs; call recovery and route discovery for ordinary users; session reconstruction after process death; a Windows receiver speaking current `BTV3`; matched endurance, range and SurfaceView measurements; API 37 temporal layers or HDT only with a public API and real output evidence.

When a requirement changes, update the benchmark scope and inventory together with its reason (see [TESTING.md](TESTING.md)).
