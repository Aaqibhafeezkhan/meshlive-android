# Plan

For contributors: what is unfinished. Current behaviour lives in the owning document (see the [README](../../README.md#documentation)); when an item is finished its description moves there and the item is removed here. Measured reasons for existing policies, the full design texts and the longer plan notes are in [decisions](decisions/README.md).

Two labels are used:

- **Design review required**: the item changes the protocol, the security model or what a phone reveals over the air. It needs a written design in PROTOCOL.md and an independent review before code ships, and it ships behind a default-off flag (see the register in [switches.md](switches.md)).
- **Experimental**: coded behind a default-off switch and not yet validated on phones.

Nothing here is a promise or a schedule. Contributions are welcome on any item; open an issue first (see [CONTRIBUTING.md](../../CONTRIBUTING.md)).

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

Open across every phone combination that uses the affected behaviour. The benchmark case state ([testing.md](testing.md)) owns pass and fail history; the investigation notes behind each item are in [decisions](decisions/README.md), "Open-defect investigation notes".

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

Fixed numbers (kbps ceilings, 20% margins, 5 s keyframes) were wrong for some phone or link every time. Four workstreams replace them with measurements: no frame loss (latency is the elastic resource; the default, under A/B against bounded media age), self-measured link capacity from packet trains, burst-free encoding (frames capped to the measured link, intra-refresh where supported) and no fixed limits left (constants only as fallbacks before the first measurement). Open: calls beyond a link's capacity, forced CBR on the M31 and the encoder trim, relay child joins, and the 20% planning margin. Workstream tables: [decisions](decisions/README.md), "Plan detail moved from the plan". Next in this workstream: the phone A/B of the Experimental `test_join_probe_seed` (a fresh link's pacer seeded from a short measured burst instead of the cautious prior) and `test_seed_raise_drain_guard` against the default on-time seed raise (SWITCHES; DECISIONS "Starting pace of an unmeasured stream link").

## Intercom core and product direction

Intercom and live video are equal parts of the product. Open: the bounded media-age A/B against no-loss (Experimental; no-loss stays the default meanwhile), a decided media-age target and task-success adapters for the user-centred gates, reliable 1:1 voice, push-to-talk and screen-off operation proven on phones, and a signing mode validated with fluent signers.

## Discovery privacy

**Design review required.** Rotating discovery IDs, BMH3 and stranger pseudonyms are on by default since 0.2.0-beta; formats are in [protocol reference](protocol/README.md) and measurements in [privacy.md](../privacy.md). Not measured: battery cost, the Bluetooth name read on the A15s, an active attacker holding a contact's key, fake adverts, relays with the new handshake and abuse limits. Open design question: the monotonic epoch never returns after a backward clock step, so a lead of 3 to 96 epochs leaves a phone unable to receive a version 5 call until it recovers. The phone-test plan (phase 0 measurements to contacts-only mode) is in [decisions](decisions/README.md), "Plan detail moved from the plan".

## Larger designs

The full design texts are in [decisions](decisions/README.md), "Design records".

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
- **Distribution and field reports.** Real phone screenshots in `fastlane/`, the F-Droid and IzzyOnDroid inclusion requests, and a fuller Export diagnostics block built from bounded in-memory records ([architecture.md](architecture.md#diagnostics-block-contract-for-export-diagnostics)). Google Play is not started.
- **iPhone interoperability (design review required).** Compact discovery is coded behind `test_ios_compact_discovery`; the iOS client is in MeshLive/meshlive-ios. Left: every reference phone finding an iPhone's offers with the switch on, with `scan_stats` evidence of controller versus host filtering. Optional H.264 (`test_h264_codec`) is not coded and needs a PROTOCOL design first. Before the iOS port of in-call link renewal (Design SR): check that the iOS end-to-end hello never sets transport-mask bit 4 (`0x10`, the renewal capability), which is not verifiable from this repository.
- **Wi-Fi Direct / Aware bearer (design review required).** Not started. Optional, explicit, never a silent fallback; Bluetooth stays for discovery.
- **iOS/macOS parity of the 2026-10-21 UX work (user: "fix everywhere"):** your own generated name shown (Calls
  top line, Your name subtitle, incoming call/Meet), settings rows with the (i) inline after wrapped text and a divider
  between every row, value rows wrapping under the title, the plain-language copy audit (no political framing), Chats
  tab structure, missed-call notification, "Couldn't reach <name>" with Call again, and the crash report prompt.
- **Windows Watch and Go live (Design QV rev 4, gates W1/W2 passed 2026-10-21):** implement after the weekly usage
  reset (design decision 2026-10-21: Play Store release first). Android serves PC viewers and sees PC streams behind
  Find PCs; Windows uses one announcing provider switched by mode (F1).
- **Cross-platform parity of Android improvements (user request 2026-10-21; only wire/security rules must match, each
  platform gets its best native version).** Windows (in progress): copied-ID interleave rule, crash report on next
  launch (panic hook + native minidump), plain Bluetooth-restart guidance, leading blank camera frames, ring timeout /
  dead-call hang-up / busy rule checks; GR1 and the lost-chunk fail-fast are done. iOS/macOS (needs the Mac): the same
  list plus GR1 + fail-fast, MetricKit crash and exit diagnostics, CoreBluetooth state guidance, AVCapture first-frame
  check, stream join retry and source warm start when streams land.
- **Reach and features beyond Bluetooth range (roadmap, user request 2026-10-20; research survey the same day; every
  item default-off behind a SWITCHES entry, protocol/security items need a DECISIONS design record + PROTOCOL rules
  first).** Today live video reaches only phones inside the Bluetooth mesh; no app does live video beyond local range
  without the internet (long-range radios are kilobits).
  - Near term (current architecture): N1 signed, rate-limited SOS presets relayed hop by hop with a TTL (coarse
    position, opt-in); N2 push-to-talk (Opus 12-16 kbps over existing links); N3 messages with delivery receipts and
    bounded store-carry-forward (spray-and-wait style: few copies, expiry, per-peer quota, sealed blobs, per-epoch
    recipient tags); N4 small photos with fountain-code repair (RaptorQ/RS) over N3, EXIF stripped; N5 BLE 2M / Coded
    PHY capability profiles via DeviceFamilyPolicy (measured, not assumed); N6 "relay mode" for an old phone on a
    charger; N7 contact-only, coarse-by-default, time-limited location sharing.
  - Mid term: M1 Wi-Fi Aware bearer (Android, and iOS 26+; 20-100 Mbps per hop, 720p over 1-3 hops; BLE stays for
    discovery and control; service IDs rotate with the epoch); M2 Wi-Fi Direct fallback (Android, Windows); M3 cheap
    store-and-forward nodes (ESP32/nRF52) with an encrypted flash ring log and sealed blobs only; M4 group chat with
    sender keys (MLS evaluated later); M5 LoRa companion (Meshtastic/Reticulum class) for SOS, text and position only;
    M6 optional content-blind internet relay for sealed messages (TTL, delete on fetch, no logs) and/or Nostr
    gift-wrapped transport, reachable over Tor/Snowflake.
  - Long term: L1 gateway live streaming between mesh islands: a gateway phone uplinks one stream to a self-hostable
    WebRTC SFU with SFrame end-to-end encryption keyed by MeshLive, Nostr gift-wrapped announcements for discovery and
    signalling, TURN over TLS, Snowflake as a last resort; the remote gateway re-injects it as a hop-local child
    keeping the original-source signature (rough cost: 50 viewers at 480p about 40 Mbps from one small server);
    L2 gateway-to-gateway IP overlays (Yggdrasil/WireGuard) for self-hosted communities; L3 community Wi-Fi mesh
    (OpenWrt/LibreMesh) relay service; L4 satellite direct-to-device SOS/text when carriers expose app data; L5
    vehicle/drone relay kits.
  - Bluetooth platform features to adopt per phone where present (capability-gated through DeviceFamilyPolicy, measured
    not assumed, even when only a small share of phones has them): LE Coded PHY (S2/S8, about 2-4x range at
    125-500 kbps; Android `isLeCodedPhySupported`, chip-dependent; iOS chooses PHY itself) for SOS, text and
    push-to-talk range; LE 2M PHY for video headroom; extended and periodic advertising (larger beacons, SOS);
    LE Audio / LC3 and Auracast broadcast (Bluetooth 5.2+, Android 13+ on some phones; app access to broadcast
    sources is limited; a possible one-to-many "radio" path); isochronous channels (5.2) for low-latency audio;
    PAwR, periodic advertising with responses, and Encrypted Advertising Data (5.4; mostly not exposed to apps
    yet); Channel Sounding (6.0, distance measurement; useful for "who is near me" and anti-relay checks, very few
    phones); LE Data Length Extension and connection subrating (5.3) for power and throughput. Each needs a capability
    probe, a fallback, and a phone measurement before any default.
  - Never for live video: LoRa, satellite today, DTN carrying (recorded clips and photos only), I2P/mixnets.
  - Lessons kept as rules: authenticate every relayed packet and keep no stable identifiers in relayed metadata
    (Bridgefy was broken twice on exactly this); bound every flood; strip location metadata; independent audit before
    promoting to high-risk users; internet paths always opt-in, never required, relays content-blind, with the metadata
    each relay sees stated to the user.
  - Open measurements: Wi-Fi Aware throughput and Android-iPhone interop on our phones; Coded PHY support per phone;
    media latency over Tor/Snowflake.
- **Measurement adapters.** Missing: native stress and calibration evidence, actual output capture with cross-phone clock anchors, reference colour through each encoder and decoder, acoustic experience, lifecycle with Camera2 and process death, and radio and endurance runs. Each needs focused failure-case tests before it can close a benchmark request.
- **Platform and product work.** Dynamic group-key revocation and an independent protocol audit; multi-child and alternate-parent graphs; call recovery and route discovery for ordinary users; session reconstruction after process death; a Windows receiver speaking current `BTV3`; matched endurance, range and SurfaceView measurements; API 37 temporal layers or HDT only with a public API and real output evidence.

When a requirement changes, update the benchmark scope and inventory together with its reason (see [testing.md](testing.md)).
