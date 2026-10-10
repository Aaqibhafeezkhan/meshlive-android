# MeshLive architecture

This document explains how MeshLive is put together: the modules, the radio links, the sender and receiver
pipelines, how relay chains and private calls work, how identity and encryption fit in at a high level, and what
the host tools do. Wire formats and exact security rules live in [PROTOCOL.md](PROTOCOL.md); how things are
measured lives in [TESTING.md](TESTING.md); the reasons behind measured policies live in
[DECISIONS.md](DECISIONS.md); unfinished work lives in [ROADMAP.md](ROADMAP.md). The end of this document holds the
register of every experimental, alternative and optional switch.

Some words used throughout:

| Word | Meaning |
| --- | --- |
| Source / broadcaster | The phone that captures the camera and microphone and sends the stream. |
| Viewer | A phone that receives and plays a stream. |
| Forwarder / relay | A viewer that also passes the stream on to one more phone. Together they form a **relay chain**. |
| Hop | One Bluetooth link between two neighbouring phones. A stream that crosses two links has two hops. |
| Hop-local | A phone only ever talks to its **parent** (the phone it receives from) and its **direct children** (the phones it sends to). Nothing about the rest of the chain is known or controlled from here. |
| Node ID | The first 16 bytes of SHA-256 over a phone's public identity key. The first 6 bytes are the **prefix** shown in discovery. |
| RFCOMM | Bluetooth Classic's serial-port style byte stream. |
| LE | Bluetooth Low Energy. |
| L2CAP CoC | LE "connection-oriented channel": a byte stream over LE with its own flow control (a credit-based L2CAP channel). |
| GATT | The LE attribute protocol. MeshLive builds a bounded byte stream out of GATT writes and notifications when CoC is not available or wanted. |
| Bounded media age | An experimental policy that limits how old video may become by degrading it (lower rate, skipped frames, audio-only) instead of letting delay grow without limit. The default is "no frame loss": delay grows and playout absorbs it. |

## The big picture

```text
  Source phone                     Viewer + forwarder                 Viewer
  ------------                     ------------------                 ------
  Camera2 / raw input              BLE scan finds the stream          BLE scan finds the stream
    -> encoder (VP9/AV1)           -> link handshake (BMH2)           -> link handshake with its parent
    -> Opus microphone audio       -> endpoint handshake (BME1)       -> endpoint handshake
    -> BTV3 packets                -> decrypt, decode, play           -> decrypt, decode, play
    -> end-to-end seal (BME1)      -> copy the same ciphertext to
    -> per-hop seal (BMS1)            ONE child (new hop seal)
    -> one ordered queue per child
         |                                 |                                |
         +------ Bluetooth link 1 ---------+------ Bluetooth link 2 --------+
```

- Discovery is always Bluetooth LE advertising and scanning, even when the media will travel over Classic.
- Every link has two cryptographic layers: a **hop** layer between the two adjacent phones and an **endpoint** layer
  between the original source and each viewer (or between the two people in a call).
- A forwarding viewer decrypts for its own screen and passes the **same encrypted media** to its child. It does not
  re-encode, so a relay chain adds no codec delay and no quality loss per hop.
- A private call is a separate session with two independent producers and decoders on one authenticated
  full-duplex link.

## Where the code is

Everything under `app/src/main/java/com/example/bluetoothtest/` (the Kotlin package keeps its original name; the
product is MeshLive). Native code is in `app/src/main/cpp/`.

| Area | Main files | What they do |
| --- | --- | --- |
| Screens | `MainActivity`, `HomeUi`, `CallScreenUi`, `CallScreenController`, `MessagesActivity`, `AboutActivity`, `OnboardingActivity`, `SupportUi`, `SecurityUi`, `VerifyContactActivity` | UI and session ownership. Sessions live with the activity, not a background service. |
| Discovery | `BleDiscoveryAdvertiser`, `BleL2capControl` (scanner and control server), `StreamDiscovery`, `MeshControlMessages`, `CompactDiscoveryAnnouncement`, `RotatingDiscovery`, `TitleAdvertiser`, `StableAdvertisingSet`, `PeerAddressBook`, `PeopleList` | The 22-byte announcement, scanning with a start quota and failure recovery, and the "People nearby" and "Nearby broadcasts" lists. |
| Transports | `ClassicConnector`, `ClassicTransport`, `ClassicPeerConnection`, `BleCocClient`, `CocListenerHub`, `CocSocketOutput`, `GattVideoTransport`, `AdaptivePhy`, `BlePhyPreference`, `LeConnectionPriorityKeeper`, `GattClientTeardown`, `BluetoothTransportPolicy` | The three media transports, PHY and connection-parameter handling, and stack-bug workarounds. |
| Security | `SecureBluetoothLink`, `EndToEndVideoSecurity`, `ForwardedStreamSecurity`, `GroupBroadcastSecurity`, `AndroidNodeIdentityStore`, `IdentityVerification`, `EmergencyWipe`, `NotificationPrivacy` | Handshakes, record sealing, identity, verified contacts and the wipe. |
| Sender | `CameraVideoEncoder`, `NativeAomBridge`, `MicrophoneOpusEncoder`, `NativeOpusCodec`, `VideoCodec`, `EncoderBitrateAdjuster`, `NativeEncoderSpeedGovernor`, `BluetoothVideoBroadcaster`, `BluetoothVideoSender` | Camera and raw input, encoders, rate adjustment, framing, per-child queues. |
| Receiver | `AndroidVideoReceiver`, `VideoStreamDecoder`, `EncodedFrameQueue`, `OpusAudioDecoder`, `MediaPlayoutClock`, `AudioPlayoutSync`, `PlaybackDelayProfile`, `VideoSurfaceTransform` | Read, authenticate, queue, decode and present. |
| Mesh and relay | `ReceiverStreamForwarder`, `BluetoothVideoRelay`, `MeshCore`, `MeshBandwidth`, `RelayChildHealthPolicy`, `ReceiverNodeHealthPolicy`, `FanOutStarvationPolicy`, `SourceCapacityRejectionPolicy`, `RelayChildReanchor`, `LinkCapacity`, `LinkRateController` | Hop-local forwarding, admission, overload withdrawal, learned link capacity and rate control. |
| Calls | `AndroidPrivateVideoCall`, `PrivateCallSession`, `PrivateCallMedia`, `PrivateCallAudioSession`, `PrivateCallRelayChain`, `BluetoothPrivateCallRelay`, `IntercomCall`, `CallPlayoutPolicy`, `CallAudioFocus`, `CallRinger`, `CallResolutionLadder`, `AudioConcealment` | Call signalling and media, relay chains, intercom features and audio behaviour. |
| Messages | `StoreForwardNode`, `StoreForwardLink`, `StoreForwardStore`, `StoreForwardCrypto`, `StoreForwardAutoSync`, `MessagesActivity` | Experimental store-and-forward messages. |
| App sharing | `AppShare`, `OfflineAppShare`, `OfflineAppShareActivity` | Passing the APK to a nearby phone. |
| Device policy | `DeviceFamilyPolicy`, `DevicePolicyRegistry`, `CodecPolicy`, `YuvPolicy`, `BluetoothTransportPolicy`, `BackgroundPolicy`, `MemoryThermalPolicy`, `NativeCompat` | The registry of phone-family specific behaviour. |
| Power and lifecycle | `ActiveVideoSessionService`, `BackgroundReliability`, `DozeWatchdog`, `ThermalGuardPolicy`, `WifiCoexistence` | Foreground services, restriction reporting, thermal limits. |
| Debug and test hooks | `DebugTunables`, `IvfVideoReplay`, `ReferenceRecorder`, `ReferencePcmInput`, `MediaImpairmentPlan` | `test_*` launch extras and benchmark inputs, active only in debug launches. |

Outside the Android app:

| Location | What it is |
| --- | --- |
| `receiver/`, `shared/` | A Windows C# receiver and the C# protocol code it shares with the file sender. |
| `tools/` | Python host tools: benchmark harness, scoring, release tooling, policy report, diagnostics parser. `tools/BluetoothVideoFileSender/` is a separate C# project. |
| `tools/video-stress-generator/` | A standalone test-video generator (MIT licensed). |
| `benchmarks/` | Small reusable inputs and catalog definitions for the harness. |
| `distribution/`, `fastlane/` | F-Droid and IzzyOnDroid metadata, store text and changelogs. |

## Transports

MeshLive carries media over one of three Bluetooth transports. The user picks one in Settings; the app never
silently swaps it for another (the one exception is the experimental automatic GATT fallback in the register).

| Transport | Default? | Pairing | Notes |
| --- | --- | --- | --- |
| **Bluetooth LE GATT** | Yes | None | A bounded byte stream over GATT writes and notifications (about 1100 kbps picker maximum before the phone has measured its own link). Works on the most phones and needs no pairing, but one large frame occupies the link for its whole air time. |
| **Bluetooth Classic RFCOMM** | No | A one-time pairing (mutual bond) on both phones, unless the experimental Classic-without-pairing option is on | Highest throughput (about 1500 kbps picker maximum). Discovery is still BLE, whose rotating private addresses only a bond can resolve. |
| **Bluetooth LE L2CAP CoC** | No (Experimental) | None | An LE byte stream with credit-based flow control (about 1200 kbps picker maximum). The listening phone publishes its dynamic PSM in a small GATT characteristic; the caller reads it and opens the channel. Some phone stacks have serious CoC bugs, which is why it stays experimental. |

How they are used:

- **Discovery** advertises a fixed-size announcement as BLE manufacturer data (company ID `0xFFFF`) plus a service
  UUID: the 6-byte node prefix, flags (stream available, private-call available, accepting connections, route
  recovery), capability summary, a stream token, the route cost (how many hops from the source), a sequence byte and
  small stream hints (size, FPS, bitrate). Scanning is rate limited by Android (a sixth scan start inside 30 seconds
  is refused), so scan starts go through a gate and failures back off. An iPhone-style one-UUID compact form is
  decoded behind an experimental switch.
- **Control** for LE transports runs over a GATT service. For CoC it only carries the PSM; for GATT it carries the
  media chunks as well.
- **Media** always goes through the same authenticated, ordered record layer (BMS1 and BME1 in
  [PROTOCOL.md](PROTOCOL.md)), regardless of transport.
- Wi-Fi and USB are used only for development (adb). The one exception is the experimental "Share app nearby"
  screen, which uses a local Wi-Fi Direct group to hand over the APK, never media.

Bluetooth stacks differ a lot between Android versions and makers. Workarounds for known stack bugs (deferred GATT
close on Android 10/11, a shared LE CoC listener on Android 10, a fresh ACL for CoC on Android 12/13, write-busy
retries on Android 13+, the scan start quota and others) are entries in the device policy registry described
below, each with an upstream source.

## The sender pipeline

```text
Camera2 preview surface ----> MediaCodec hardware encoder (VP9 or AV1), surface input
Camera2 ImageReader (YUV) --> MediaCodec byte-buffer input   (software/compatibility encoders)
                          \-> libaom "optimized software AV1" (JNI, CBR / VBR / CBR with frame dropping)
Microphone -----------------> Opus (MediaCodec, mono 48 kHz, 32 kbps)  [experimental: native libopus for calls]
        |
        v
 BTV3 packets (13-byte header: length, flags, shared presentation timestamp) -> BME1 seal -> BMS1 seal
        |
        v
 one ordered queue per child -> link pacer -> Bluetooth transport
```

- **Input.** Live Camera2 is the real product path. Debug builds can also encode a supplied raw I420 file or replay
  a finished IVF file, so the same encoder runs on a repeatable, license-clean picture. These are different tests
  and are never confused with each other.
- **Encoders.** The app lists what the phone offers: hardware MediaCodec encoders first, then AV1 before VP9 when
  both exist, surface input before byte-buffer input, then the optimized software AV1 encoder (libaom, always built
  in). The selection is shown to the user and never changed silently. Encoder-specific workarounds (for example
  quantizer bounds that OMX encoders ignore, or an OMX VP9 encoder that overshoots its target rate) are policy
  entries or experimental arms listed below.
- **Rate.** The user picks a requested rate (100-1500 kbps in 25 kbps steps; the picker maximum comes from this
  phone's measured link). In Automatic the source lowers the *effective* rate only on sustained backlog or
  late-arrival reports from its **own** children; requested and effective values are both visible. An adjuster
  ported from WebRTC's design corrects encoders that miss their target.
- **Defaults.** 240p (426x240) at 15 FPS and 200 kbps for calls and streams (see DECISIONS "Default video
  settings"); resolutions 240p/360p/480p, FPS 15/24/30. Keyframes are sent on demand
  (join, loss recovery) and every 60 seconds as a safety net, because a scheduled keyframe on detailed content is
  the biggest single burst on a Bluetooth link.
- **Framing.** Video and Opus audio share one monotonic timestamp inside the encrypted `BTV3` packet, so the
  receiver can lip-sync them. Flags mark codec configuration, keyframes, rotation, audio and discontinuities.
- **Queues.** A source encodes once and gives each child an independent ordered queue with admission and
  withdrawal. By default no queue drops media for congestion: a slow link builds latency that the viewer's
  adaptive playout absorbs.
- **Pacing.** Writes are paced from a learned single-link capacity so that backlog shows up in the app (where it is
  measured) rather than hidden inside the Android Bluetooth stack.

## The receiver pipeline

```text
Bluetooth transport -> BMS1 open -> BME1 open -> BTV3 parse
   video -> EncodedFrameQueue -> MediaCodec decoder -> TextureView   (FILL or FIT, aspect preserved)
   audio -> Opus decoder ------> AudioTrack, kept on the same playout clock
                  both follow one MediaPlayoutClock (source timestamp + adaptive delay)
```

- **Playout clock.** One clock maps source timestamps onto a local presentation timeline. The delay starts at a
  profile minimum and grows only while this link delivers late (by the measured lateness plus a margin), then
  shrinks slowly after a calm period. Profiles: Low 100-600 ms, Balanced 250-1200 ms, High 500-2500 ms, Auto
  250-2000 ms (default). Streams remember a burst for 60 seconds before shrinking, because scene cuts on Bluetooth
  arrive in bursts that take 0.6-1.3 s. Private calls keep their own, shorter budgets.
- **No frame loss by default.** Audio stays continuous: when it drifts from the clock, playback speed changes
  slightly with pitch preserved, instead of dropping samples. The encoded-frame queue is sized for the whole
  adaptive range and applies backpressure to the link instead of discarding queued frames. Only frames that cannot
  be decoded anyway (before the first keyframe, or after a marked discontinuity) are skipped.
- **Bounded media age (Experimental).** When the delay must not grow without limit (calls, or a slow relay branch),
  an experimental policy degrades video in a fixed order: lower rate, skip verified non-reference frames, skip to
  the next keyframe, pause video and keep audio. Audio is never dropped. It is under A/B comparison against the
  no-loss default; the register lists the switches.
- **Link measurement.** A receiver tells its **immediate** sender how it is doing (arrival rate, lateness, bytes
  delivered and link clock) in the same authenticated heartbeat that proves liveness. This report is never
  forwarded, so no queue, bitrate, FPS or thermal metric crosses more than one link.
- **Decoder handling.** The app logs the decoder it requested and the one it got; a silent substitution would
  invalidate a test. Decoder start failures can fall back to the next component (user sessions only), and a
  recoverable codec exception re-creates the call decoder at the next keyframe.

## Relay chains and mesh

MeshLive is a tree of hop-local relationships, not a routed network.

- **One parent, direct children.** A viewer has one parent. A forwarding viewer offers **one** downstream child. A
  source may accept several direct viewers within its measured link budget. Each phone acts only on its parent and
  its direct children. Forwarding is controlled by the Settings switch "Help pass streams and calls to others
  nearby": on by default, it is re-evaluated at each join and pauses itself below 20% battery (not charging) or in
  Battery Saver.
- **What a forwarder does.** It authenticates its child with fresh link and endpoint handshakes, wraps the current
  media-open key in a `BFG1` grant that names the original source and stream, and copies upstream ciphertext
  unchanged. The child checks that the stream really comes from the originally announced source. Child keyframe
  requests and acknowledgements are authenticated locally and regenerated toward the upstream parent, because each
  adjacent session has its own keys.
- **Admission and overload guards.** A source or relay learns the load its links actually carried and admits another
  child only inside that measured ceiling (planned links carry a 20% margin). A child that stays behind (more than
  2.5 seconds, or starved for most of a window while a sibling is healthy) is released: the parent sends an authenticated
  `LOCAL_CAPACITY` withdrawal, waits for the acknowledgement, closes that one link, and the child avoids that route
  for 30 seconds before searching again. The source's encoder is never slowed for one child's sake.
- **Losing a parent.** An unexpected parent loss keeps the last frame on screen and searches for the **same logical
  stream**. The replacement can be the source or another forwarder, but its authenticated identity must match its
  announcement and the original source must match the one first accepted. Playback resumes at a fresh keyframe. The
  recovery has a gap (break-before-make). An explicit End from the source returns the viewer to discovery.
- **Liveness.** Immediate socket errors and authenticated heartbeats every 2 seconds end a dead link within 6-8
  seconds. Heartbeats stop at the immediate parent.
- **Direct fallback is a failure.** If a chain was requested and the viewer ends up connected straight to the
  source, that is reported as a failed chain, not a pass.
- **What measurements show.** Each hop spends airtime twice (a relay receives and sends on one radio), so
  multi-hop and fanned-out video is much weaker than a single link: in a multi-phone relay test on 2026-10-08,
  relayed or fanned-out leaves played at 0.9-7.6 FPS at 300 kbps. Short chains of low-rate video are realistic;
  long chains of high-rate video are not.
- **Blind relay (debug).** A debug-only relay holds only adjacent-link keys and cannot open endpoint content. It
  exists to test the trust model and to carry private calls; it has no multi-child user interface and static group
  keys do not implement live revocation.

## Private calls and intercom

- **Calls.** Both people run an independent producer (camera encoder, Opus microphone) and an independent decoder
  on one authenticated, full-duplex link. Signalling (`BMC2`: Invite, Ringing, Accept, Decline, Cancel, Hang-up,
  camera and microphone state) travels inside the encrypted endpoint session, and capture starts only after
  acceptance. Mute, camera off/on, flip and End affect only the local side.
- **Call budgets.** Calls keep much shorter playout budgets than streams and their own rate control. Call video
  starts at the rate that peer link last sustained (or, for an unknown peer, a conservative share of this phone's
  measured link, because both call directions share it) and climbs from there. It steps its resolution down when the
  target rate cannot support the current size. Call audio has its own queue headroom so one large video frame
  cannot starve it.
- **Call relay chains.** A call can cross up to four opaque relays between the two endpoints. A relay copies both
  encrypted directions without a camera, microphone or codec; only the two endpoints hold content keys. Admission is
  hop-local (route cost, signed relay capacity, loop-free by construction). The relay itself is currently a debug
  feature; the protocol, admission rules and tests are in place (see [PROTOCOL.md](PROTOCOL.md), "Private-call
  relay chains"). Broadcast forwarding is not evidence that call relaying works.
- **Intercom features** (voice calls, push-to-talk, names and titles) are experimental and off by default; see the
  register. A voice call starts no camera, encoder or decoder and uses no new wire value.
- **Behaviour that is on in normal use:** transient voice-communication audio focus (music pauses during a call, a
  cellular call puts the call on hold), a ringer policy that follows silent/vibrate/Do Not Disturb, a proximity
  screen-off lock while audio is on the earpiece, and being reachable for calls while the app is open.
- **Legacy-family protections.** Some phone families (notably older Qualcomm Android 10-12 stacks) carry call
  limits such as a 426x240 cap, 110-160 kbps limits, batched audio and a forced software VP9 decoder. These are
  visible policy limits recorded in the registry and in [DECISIONS.md](DECISIONS.md), not hardware ceilings, and
  an experimental switch lifts them for testing.

## Identity and encryption (high level)

[PROTOCOL.md](PROTOCOL.md) is the reference. In short:

- Each phone has a persistent P-256 signing identity in the Android Keystore. Its node ID is derived from the public
  key. There are no accounts and no server.
- Every Bluetooth connection starts with a signed handshake (`BMH2`) with fresh ephemeral ECDH keys. After it,
  every record between the two adjacent phones is sealed with directional AES-256-GCM, ordered and
  replay-protected (`BMS1`).
- A second, independent handshake runs end to end between the source and each viewer (or the two call endpoints)
  with its own signature domain. Media and call signals are sealed with those keys (`BME1`), so a relay in the
  middle can forward and measure sizes and timing but not read or alter content.
- Discovery gives a phone's 6-byte node prefix. The link and endpoint identities must match it. That is a
  convenience check, not proof of who owns a phone: for that, two people compare a QR code or a 60-digit safety code
  in person. A verified contact whose key later changes (same prefix, different key) is refused by default.
- There is an in-app **emergency wipe** (confirmed, or a triple-tap gesture that is off by default) that deletes
  every Keystore key first and then the app data. Its limits (flash remanence, copies outside the app) are listed
  in [PROTOCOL.md](PROTOCOL.md), "Emergency wipe".
- An independent security review has not happened yet.

## Store-and-forward messages (Experimental)

Off by default. When two phones are in range, they can exchange small encrypted messages (text, a voice note up to
two minutes, a photo up to 1280 px) for contacts the user has accepted, so a message can wait for a phone that is
not nearby right now. Version 1 covers two **directly adjacent** phones only.

- Each identity has a static mailbox key; contacts swap signed contact cards (`BCC1`), compare a short safety
  number, and accept them.
- A message becomes a sealed bundle (`BSB1` header visible to carriers, `BSM1` content readable only by the
  recipient) with a lifetime of 1, 3, 7 or 14 days. The inbox and sent list are encrypted at rest.
- An exchange (`BSF1`, hop type 11) runs only when both phones have the Call screen open and one taps the other, and
  it yields immediately to a call, stream or relay. An automatic sync mode (debug extra) uses the same exchange with
  per-peer back-off.
- A carrier role, where a third phone ferries sealed bundles for others, exists in code but is hard-off.

See [PROTOCOL.md](PROTOCOL.md), "Store-and-forward messages", and [ROADMAP.md](ROADMAP.md) for the design.

## Offline app sharing

MeshLive can hand its own APK to a nearby phone when there is no internet (a pattern also used by Briar and
Bitchat, re-implemented from a description, with no code copied).

- **Share sheet.** `AppShare` copies the installed base APK to a private cache directory and offers it through the
  Android share sheet by a non-exported FileProvider scoped to that one directory. The share screen shows the APK's
  SHA-256 and the signing-certificate SHA-256, so the recipient can compare it with the release page. Split
  installs and builds signed with the Android debug key are refused. Copies are deleted after 30 minutes. Stock
  Android Bluetooth refuses to send or accept `.apk` files, so Quick Share/Nearby Share, a file manager or an
  OEM Bluetooth stack are the usual routes.
- **Share app nearby (Experimental).** A Wi-Fi Direct group with a random name and passphrase serves a read-only HTTP
  page and the APK under a random 20-character path, shown as two QR codes (Wi-Fi join and URL). It stops after ten
  minutes, when the screen closes, or when any stream or call exists.

## Device-family policy registry

Phones differ in SoC, Bluetooth stack version, vendor skin and codec components, and many "it works on my phone"
problems are stack bugs. MeshLive keeps every phone-family specific decision in one registry
(`DevicePolicyRegistry.entries`, built from `DevicePolicyRegistry.kt` plus the per-area files `CodecPolicy`,
`YuvPolicy`, `BluetoothTransportPolicy`, `BackgroundPolicy`, `MemoryThermalPolicy` and `NativeCompat`). Code that
used to test `Build` fields asks the resolved policy for an effect instead.

Each entry has:

- **Matcher.** A declarative rule on API level, `Build.HARDWARE`/board, SoC family, SoC model, manufacturer, OEM skin
  family, Bluetooth stack lineage (platform `system/bt` before Android 13, the Mainline Bluetooth module from 13),
  controller features (2M PHY, Coded PHY, extended advertising) or a codec component name. Matchers never use
  `Build.MODEL`: a family is a platform property, not a product name.
- **Typed effects.** What changes: a call video cap, deferred GATT close, a preferred decoder, a longer queue and
  so on.
- **Behaviour in plain words,** official **sources** (AOSP source and commits, Android CDD and API reference, vendor
  developer documents, Bluetooth SIG specifications), **evidence** from our own test runs, a **confidence**
  (high, medium, low) with a note on what is still unproven, and a review date.
- **Validation status.** `UPSTREAM_DOCUMENTED` (an upstream source documents the behaviour but no local phone has
  reproduced it; never counted as a pass), `LOCALLY_REPRODUCED`, or `EXPERIMENTAL`.
- **A fallback and an off switch.** In debug builds `test_device_policy_off=<id>[,<id>]` or `all` turns entries off
  for an A/B comparison; release builds ignore it.
- **Proposals.** Sourced but unimplemented entries are carried as *inert proposals*: the resolver only reports which
  proposals would match the phone, so test runs collect evidence before anything is promoted. At the 2026-10-10
  revision the registry had 75 entries (55 active, 20 proposals).

Two rules keep this honest. Acceptance gates are identical for every phone and never depend on a policy entry. And
a phone-specific protection is a visible limit, never a silent change to a requested bitrate, FPS, resolution,
encoder, transport or buffer. `python tools/device_policy_report.py` prints the registry with its sources and
gaps, and `python tools/parse_user_diagnostics.py report.txt` turns a user's exported diagnostics block into a
draft evidence suggestion for a human to check (it never edits the registry, and a user report never raises
confidence by itself).

## Background, lifecycle and power

- Typed foreground services (camera, microphone, connected device) protect ordinary app switching and screen-off. They
  do not recreate a session after the process is killed.
- `BackgroundReliability` reports Android's documented restriction states (battery optimisation, background
  restriction, standby bucket, power-save) and phone-maker guidance with settings links. It adds no keep-alive hacks
  and no exemption prompt.
- `DozeWatchdog` observes Doze, light Doze and Low Power Standby and shows "Phone is in deep sleep; calls may be
  delayed." It changes no behaviour.
- Thermal limits reduce FPS and rate at source on Android's thermal status; a critical status stops streaming.
  Requested and effective settings stay visible.
- FIFO audio, immutable GATT buffers, codec-configuration and keyframe recovery, and capture-age-preserving
  timestamp conversion are compatibility invariants covered by focused regression tests.

## Diagnostics

- **Log lines.** The app writes `BENCHMARK <topic>: key=value | ...` lines that the host scorers parse (decoder
  requested versus effective, link PHY requested versus effective, call setup, admission decisions, pacing and so
  on). Release builds strip verbose and info logs (R8) and keep warnings, errors and the `BENCHMARK device_policy`
  line.
- **Debug launch extras.** Benchmark behaviour is selected with `test_*` launch extras and benchmark tunables
  (the bracketed names in the register). They exist only in debug launches; normal sessions never read them.
- **Export diagnostics.** About > Export diagnostics copies one bounded text block (never media, messages, contact
  or peer names, addresses or full keys) for GitHub issue forms. README describes the exact contract.

## The desktop receiver and file sender

`receiver/` is a Windows C# receiver (Windows Bluetooth RFCOMM) that authenticates a phone, writes the stream to an
`.ivf` capture and can play it. It implements the older video-only `BTV2` path, so current `BTV3` audio and normal
discovery integration are unfinished; it is a development tool, not a finished desktop client. `shared/` holds the
C# implementation of the same handshakes and records, with an offline self-test
(`dotnet run --project receiver/BluetoothVideoReceiver.csproj -- --self-test`). `tools/BluetoothVideoFileSender/`
sends a prepared file to a phone for repeatable input.

## Host tools and the benchmark harness

The harness lives in `tools/` and never touches a phone unless a run is explicitly started.

- **Plan.** `generate_benchmark_inventory.py` compiles the current scope (reference phones, transports, encoders,
  rates, topologies, lifecycle cases) into a case inventory. A case ID names one exact request; changing a setting
  makes a new case. Everything starts as NOT RUN, and `benchmark_case_state.py` joins results to cases and marks a
  changed area NEEDS RETEST.
- **Run.** `run_benchmark_suite.py` plans, runs and analyses selected cases on phones named by alias in your own
  `tools/benchmark_devices.json` (copy `tools/benchmark_devices.example.json`; the file is git-ignored).
  `tools/benchmark_scope.json` lists the reference phone aliases.
- **Inputs.** `bind_benchmark_inputs.py` binds supplied raw I420/PCM media by hash; `prepare_native_workload.py`
  and the fixture scripts generate repeatable workloads; `tools/video-stress-generator/` makes stress videos.
- **Score.** The `score_*.py` scripts measure delivery, timing, picture quality (including VMAF and BD-rate
  helpers), audio quality and sync, screen framing and physical camera/microphone observations. Picture quality,
  frame timing, lifecycle, Camera2, acoustic quality and optical latency are separate measurements.
  `hci_snoop.py` and `btsnoop_summary.py` read Bluetooth HCI snoop logs. Optional audio scoring dependencies are in
  `tools/requirements-audio.txt`; PESQ, whose ITU reference code has non-commercial and redistribution
  restrictions, is a separate opt-in file (`tools/requirements-audio-pesq.txt`) and never part of the app.
- **Release.** `make_release.py` builds the per-ABI APK set with checksums and a source manifest;
  `check_apk_16kb.py` checks 16 KB page alignment; `generate_app_icon.py` regenerates the app icon and `docs/brand/`.
- **Tests.** `python -B -m pytest -q tools` runs the host tests.

[TESTING.md](TESTING.md) owns the scope, acceptance gates and commands. Failed, interrupted and unmeasured cases stay
visible: a generated case is not a result.

## Experimental, alternative and optional behaviour

This is the single register of switches that are off by default or exist for comparison. The rule behind it
(design rule, 2026-10-08/09): an experiment, or the losing arm of an A/B comparison, is kept, off by default and
labelled Experimental, unless it is impossible by first principles. [DECISIONS.md](DECISIONS.md) records the evidence
for each.

How to read it:

- DEBUG switches are launch extras named `test_*`; benchmark tunables are in square brackets. Release builds ignore
  them unless an entry names a Settings switch.
- "Control arm" means the default is the new behaviour and the switch restores the old one exactly, so an A/B can
  run in either direction.
- Phones are named by alias: S24, S22, M31, ASUS, Xiaomi, A55 (the reference set) and A15s. "Untested on phones"
  means the entry has unit tests but no measured phone run yet; an entry that cites numbers was measured.
- Entries marked with a Settings name appear under Settings > Advanced.

### Streaming: rate control, queues, playout and bounded age

| Switch | Default | What it does and why it is kept |
| --- | --- | --- |
| Temporal layering (`test_temporal_layers`; Advanced setting) [temporal_layers] | Off | Drops discardable enhancement frames under congestion. Superseded by the no-loss default (2026-10-07); it cost about 2.5 VMAF on a calm link. |
| Bounded media age (`test_bounded_media_age`) [bounded_media_age] | Off | Calls keep speech within about 400 ms by degrading video (rate, AV1 non-reference drops, skip to keyframe, audio-only pause). Streams shed beyond about 2 s. It is under A/B against no-loss; the loser stays here. |
| Relay-child media age (Advanced "Relay: slow viewers skip to keyframes"; `test_relay_child_media_age=false` to disable) [relay_child_media_age] | On (design decision 2026-10-09) | Applies the bounded-age shedding to a forwarding viewer's child queue only. A child slower than the stream gets skip-to-keyframe video (audio kept) on its own branch instead of being withdrawn and rerouting to the source, which had halved the source's other child in a three-phone test. Its recovery keyframe requests back off from 1.5 s to one per 15 s while shedding recurs (reset after a calm minute), because each one adds a keyframe on every branch. Off: no-loss with withdrawal. Calls and direct senders stay no-loss. Needs a relay A/B. |
| Link pacing off (`test_link_pacing=false`) [link_pacing] | Pacing on | Control arm for the write pacer (a GATT double-charge in the pacer was fixed 2026-10-09). |
| Link pacer seed off (`test_link_pacer_seed=false`) [link_pacer_seed] | Seed on | Control arm. A child link is paced from join at the learned single-link capacity (else 200-400 kbps by transport); the seed halves every 2 s after 4 s without an arrival report (floor 150 kbps), so a link whose reports never arrive shows its backlog in the app queue rather than the Android stack. Needs link pacing. |
| Per-peer pacer seed (`test_link_pacer_peer_seed`) [link_pacer_peer_seed] | Off | A child link's pacer starts from that child's own last arrival report for this link (24 h, per transport and radio state) instead of this phone's best single-link probe, which seeded a ~65 kbps ASUS -> A15s link at about 1 Mbps. Needs the pacer seed. Untested on phones. |
| Early fan-out release off (`test_fanout_early_release=false`) [fanout_early_release] | Early release on | Control arm. A fan-out child whose backlog grew by 500 ms over 10 consecutive windows (at least 1.5 s behind, receiving less than offered, no keyframe drain) while the encoder is at its rate floor and a sibling is healthy is released, instead of after 24 of 30 s. |
| Delivery reports off (`test_delivery_reports=false`) [delivery_reports] | Reports on | Control arm restoring the behaviour from before 2026-10-09 exactly: no call hello or version 3 report, cuts use the old references (0.85 x min(bytes the stack accepted, arrival trains)), and every cut is remembered as the peer's start rate again. A/B arms also pass `test_reset_learned_rates` [reset_learned_rates], which clears the 24 h learned start rates at launch; every launch logs them (`BENCHMARK learned_rates`). |
| Delivery-based probing (`test_delivery_rate_control`) [delivery_rate_control] | Off | On links whose peer reports delivery (link report version 3): the climb holds at 95% of what the link delivered at its last overuse and probes 10% past it, undoing a probe as soon as the measured queue grows; failed probes back off (10 to 30 s); a measured queue of 300 ms that is not draining is overuse; decreases may be 1 s apart. Off keeps the default: calls exchange version 3 and cut to 0.85 x the delivered rate without cascading. |
| Stream delivery reports (`test_stream_delivery_reports`) [stream_delivery_reports] | Off | Stream viewers report delivery (version 3) every second and the stream sender uses it under its own switch. Every phone in the stream must run the same build (an older parent closes the link on version 3). Reports sent only for delivery carry the delivery-only flag, so the parent's lateness trend sees full reports exactly as before. |
| Learned-limit recovery (`test_ceiling_probe_recovery=true`) [ceiling_probe_recovery] | Off | Experimental. A fresh relay or fan-out ceiling may admit one link beyond it after a 2 min cool-down (doubling per failed probe, at most 64 min); healthy traffic above it clears it, a new saturation re-records it. Bad-2M marks age out after 24 h unless re-confirmed (default 7 days). Reason: learned limits outlive the runs that taught them; stale M31 relay (450) and GATT fan-out (315) ceilings and a bad-2M mark refused four viewers for up to 6 h. `test_reset_learned_state=true` (DEBUG) clears ceilings, sustained rates and bad-2M marks; every launch logs `BENCHMARK learned_state`. |
| Encoder bitrate adjuster off (`test_encoder_bitrate_adjuster=false`) [encoder_bitrate_adjuster] | Adjuster on | Control arm. On: a port of WebRTC's DynamicBitrateAdjuster (`EncoderBitrateAdjuster`) scales the bitrate requested from a MediaCodec encoder under Automatic by 0.25-1.0x from its measured output (judged every 3 s against one second of target bytes; never above the rate controller's target; no reduction while most frames sit at the VP9 maximum quantizer), because the M31 OMX.Exynos.VP9 produced 720 kbps for a 401 kbps target. No model matching. Logs `BENCHMARK bitrate_adjuster` at most once per second. Replaces the output-rate trim for MediaCodec encoders while on. Untested on phones. |
| Encoder frame-rate adjuster (`test_encoder_framerate_adjuster=true`) [encoder_framerate_adjuster] | Off (Experimental) | A/B arm. Applies WebRTC's FramerateBitrateAdjuster within WebRTC's own scope (OMX.Exynos. VP9/H.264). The codec keeps its configured FPS and is asked for target x configured / delivered FPS, where delivered FPS is the number of encoded frames in the last second of output timestamps; re-applied at most once per second; the measured adjuster is skipped. Kept as an arm, not the default, because it corrects undershoot after FPS drops while the M31 OMX.Exynos.VP9 overshoots at a fixed, capped FPS. Untested on phones. |
| Encoder rate trim off (`test_encoder_rate_trim=false`) [encoder_rate_trim] | Trim on | Control arm. While the bitrate adjuster is on, the trim acts only for native libaom. It cannot act at the maximum quantizer. |
| Keyframe shaping (`test_keyframe_shaping`) [keyframe_shaping] | Off | Lowers a hardware encoder's target for one app-requested keyframe. Not promoted: scene-cut bursts are not keyframes. |
| Forced bitrate mode (`test_bitrate_mode` 0/1/2) [bitrate_mode] | Encoder's resolved mode | An M31 CBR test (-4 to 9% burst). CQ is rejected by the OMX encoder. |
| Hardware QP bounds (`test_hw_qp_min/max`) [hw_qp_min/max] | None | Ignored by the ACodec/OMX path (M31). Kept for Codec2 encoders. |
| Stream buffer/flush, playback delay override, decoder low latency, release at playout, decoder choice (`test_stream_buffer_kib`, `test_stream_flush`, `test_playback_delay_ms`, `test_decoder_low_latency`, `test_release_video_at_playout`, `test_decoder`) | Off / defaults | I/O and playout experiments. Output holding worsened losses and freezes. |
| GATT full-slot source-scan pause, output holding | Off | Unpromoted; kept for comparison (see DECISIONS). |
| Thermal headroom forecast (`test_thermal_forecast=true`) | Off | Treats the ADPF forecast `getThermalHeadroom(10)` above 0.85/0.95/1.0 as LIGHT/MODERATE/SEVERE for the FPS/bitrate ladder only (never a stream stop), and only while automatic cooling is on (benchmark profiles turn it off). Guide thresholds, not locally tested; registry proposal `thermal-headroom-forecast`. |
| Thermal libaom speed floor (`test_thermal_speed_floor=true`) | Off | While automatic cooling is on and the effective status is MODERATE/SEVERE, the software AV1 encoder's cpu-used floor is 8/10 and slower trial presets are blocked (the raised preset is not remembered). Not measured on a hot phone; registry proposal `thermal-native-speed-floor`. |
| Native libopus call audio (Experimental; `test_call_native_opus`) [call_native_opus]; sub-switches `test_call_native_opus_fec` (default on), `test_call_native_opus_loss_pct` (default 10), `test_call_native_opus_dtx` (default off) | Off | Calls encode and decode Opus with the pinned libopus 1.5.2 (statically linked in `libbluetooth_video_native.so`) instead of MediaCodec: VOIP application, in-band FEC with a fixed expected-loss hint, the same rate, 20 ms frames, constrained VBR. The decoder conceals a gap of up to 100 ms with libopus PLC (the frame before the arriving packet comes from its FEC when present); a longer gap keeps its remainder for the sender-gap silence alignment. Concealed frames are counted separately, never as received packets. Independent of `test_call_audio_conceal`: native PLC fills missing timestamps at decode, that switch fills a dry AudioTrack at playout. Why: `c2.android.opus` hard-codes general-audio mode with no FEC, DTX or concealment (AOSP C2SoftOpusEnc/C2SoftOpusDec), while WebRTC and Signal use libopus with VOIP mode, FEC and PLC. The packets are standard Opus with the Codec2 configuration block, so MediaCodec and iOS libopus peers decode them and each side switches independently. DTX changes the packet cadence and needs its own A/B plus a protocol note before promotion. A library or encoder failure falls back to MediaCodec. Lines `BENCHMARK audio_codec`, `audio_plc`, `audio_codec_stats`. The code is always built in: arm64 `libbluetooth_video_native.so` grew 7,368,680 -> 7,785,168 bytes (about +244 KB deflated). Speech quality, CPU, FEC overhead at 16/32 kbps and interop are unmeasured. |
| Optional armeabi-v7a library (Gradle `-PenableArmeabiV7a=true`) | Off | Adds the 32-bit ARM native library so phones without arm64-v8a can install. Release APK 10.7 -> 17.6 MB (+6.9 MB stored, about +2.6 MB compressed); software AV1 speed on 32-bit cores is unmeasured. Registry proposal `native-armeabi-v7a-optional-build`. Release builds set it through `make_release.py`. |
| Automated-run speaker gain (`test_playback_gain`) | Unset (runners pass 0.05) | Quiet speakers in automated runs. It does not affect measured PCM. |
| Raw self-view rate (`test_call_raw_self_view_fps`) | 5 FPS in raw benchmark calls | Self-view picture-in-picture from the raw fixture. 0 turns it off. |

### Relay and fan-out admission

| Switch | Default | What it does and why it is kept |
| --- | --- | --- |
| Relay child re-anchor (`test_relay_child_reanchor=false` to disable) [relay_child_reanchor] | On | A relay whose child link carries under 50 kbps for 2 s with media waiting closes that child's connection so it reconnects on a new anchor (Bluetooth HCI logs showed a child anchor inside the parent's multi-packet window carrying 4-37 kbps for the whole connection, otherwise 349-378). The child retries the same relay first after such a link loss. At most 2 per child per minute, then the withdrawal policy decides. Stream relays only; the private-call relay is unchanged. |
| Relay recovery grace (`test_relay_recovery_grace=false` to disable) [relay_recovery_grace] | On | After a link loss that makes the child prefer its previous relay (the re-anchor above), reconnection waits up to 2 s for that relay's offer instead of taking the first offer heard. Manual test 2026-10-10: the A15s heard the full S24 source 147 ms after the ASUS re-anchor, chose it and waited 15 s without recovering. After 2 s any route is allowed. Stream relays only. |
| Relay cold start (`test_relay_cold_start=false` to disable) [relay_cold_start] | On | A viewer with no relay evidence (relay proven 0, no fresh relay ceiling) counts the incoming link it already carries and may forward to one child. Without it a fresh install (`probe none, prior 700 kbps`) never relays a 300 kbps stream (it would need 807) until it has been a source. The receiver-node health detach and congestion withdrawal still roll it back and record a relay ceiling. Line `BENCHMARK relay_admission`. |
| Fan-out growth in planned-link units (`test_fanout_proven_links=false` to disable) [fanout_proven_links] | On | Without a fresh ceiling a source may add one link beyond its proven load; proven is measured wire kbps while a planned link carries a 20% margin, so proven is scaled by 1.2 before the comparison. Without it a 300 kbps source could never pass 2 children (ASUS proven 737 + 403.5 < 3 x 403.5). Fresh ceilings and the fan-out rollback are unchanged. Line `BENCHMARK fanout_admission`. |
| Relay parent trickle close (`test_relay_parent_trickle=false` to disable) [relay_parent_trickle] | On | A relayed child also closes a parent link that delivers under 1/8 of the stream bitrate for a whole 5 s window, not only a silent one, and reconnects through link-loss recovery. In one run M31 -> A15s trickled about 12 kbps for 17 s; the relay's re-anchor closed its socket but the L2CAP channel stayed up while the stack drained, so the child never reconnected. The 4-37 kbps collision band closes; the ~120 kbps 2M one-PDU pair does not. |
| Relay parent fallback on establishment failure (`test_relay_establish_fallback=false` to disable) [relay_establish_fallback] | On | Two link-establishment failures within 2 min (child: the parent never connected before the CoC accept timeout; relay: status 133/62 before hop authentication) mark that peer for 5 min. The child then reconnects to the next-best route (another relay or the source, through normal admission) and ranks the marked parent last; the relay stops connecting to the marked child. Why: in a four-phone mesh A/B (2026-10-10) the M31 relay's HCI snoop showed ten LE connections to its child, each ending 0x3E after 6 events. Line: `BENCHMARK relay_parent_fallback`. |
| Relay PHY guard off (`test_relay_phy_guard=false`) [relay_phy_guard] | Guard on | Control arm. On: relays, and call endpoints whose hop is a relay, never write bad-2M memory from that hop's one-PDU windows or early losses (`bad2m=not_recorded \| hop_relays=true`). A relay's one radio carries both adjacent links, so those losses are not evidence about the pair's 2M (entries written against healthy pairs made a mesh test refuse viewers). Existing "via" entries are purged once. Off restores the old recording exactly. |
| Relay one-PDU PHY move (`test_relay_one_pdu_move=true` to enable) [relay_one_pdu_move] | Off | Experimental. In one run with the move, the ASUS relay's own receive fell from 283 to 119-210 kbps (ASUS 8.7 FPS, A15s 4.7) versus re-anchor alone (ASUS 15.0, A15s 5.4); one run each, A/B pending. Under the relay PHY guard, a relay's child link measured at 0.5-1.25 PDUs per event on 2M still moves to 1M (no bad-2M memory is recorded). In another run ASUS relay -> A15s carried 121 kbps against 131 kbps for one PDU per 15 ms event and the guard kept it on 2M (A15s 1.0 FPS). The collision band (4-37 kbps) still stays on 2M. |
| Relay chain control arm (`test_call_relay_chain=false`) [call_relay_chain] | Chains on | The DEBUG call relay may take another relay's offer as its child, so a call crosses up to four opaque relays (PROTOCOL "Private-call relay chains": hop-local admission by route cost and signed relay capacity, loop-free, endpoint-authenticated end to end; a malicious relay can drop or delay, never impersonate or read). A relayed child must sign the call-relay link capability, Classic reaches only the advertising device (no bonded fallback or inquiry), a relay waits at most 180 s for its parent, and a caller refuses offers above four relays. Off restores the one-relay rule exactly. Benchmark relays use `test_relay_callee` and `test_relay_route_cost` so each takes only its requested child's offer. Untested on phones. |
| Measured relayed call cap (`test_relayed_call_measured_cap`) [relayed_call_measured_cap] | Off | A relayed call's video bitrate cap becomes this phone's fresh measured link x 0.2125 less call audio (a relay's one radio carries both adjacent links in both directions, half the direct 0.425 share), never below the legacy 110 kbps; FPS stays at most 15. The legacy 15 FPS / 110 kbps cap has no measured basis as a ceiling (it was a starting budget), while a one-way stream relay carried 433-458 kbps in and out. Example: a 900 kbps link gives 159 kbps. Line `BENCHMARK call relayed_cap`. Needs a relayed A/B before promotion. Untested on phones. |

### Calls and intercom

| Switch | Default | What it does and why it is kept |
| --- | --- | --- |
| Voice calls (`test_voice_calls`; Advanced setting) | Off | Intercom pillar. Tapping a person asks Voice call or Video call. A voice call starts no camera, encoder or decoder and sends no video packets; it starts on the earpiece and shows the peer, a timer, mute, speaker/earpiece and End. Either person can add or remove their own video during the call. No new wire value (PROTOCOL "Experimental voice calls"); peers without it see a video call with the caller's video off. DEBUG controls `invite_voice` / `voice_call:PREFIX`; lines `BENCHMARK call_type`, `call_type offer`, `call_mode`. Untested on phones. |
| Push-to-talk button, PTT mode (`test_push_to_talk`, `test_ptt_mode`; launch extras only: in a call, holding Mute while muted talks) | Off | Hold to talk in voice and video calls using the existing mute path (no codec restart), with haptic, tone and colour cues. PTT mode starts calls muted. A screen reader's double tap toggles talking. DEBUG controls `ptt_press` / `ptt_release`; line `BENCHMARK call_ptt`. Group push-to-talk is not built. Untested on phones. |
| Call FPS ladder from 0.4 x request (`test_call_ladder_from_request`) [call_ladder_from_request] | Off | The call frame-rate floor used before 2026-10-09. The default floor is 0.1 bit/pixel of the encoded geometry. |
| Call 2M setup-stall guard off (`test_phy_setup_stall=false`) [phy_setup_stall] | Guard on | Control arm. On: the first 2M LE call attempt that may still retry on 1M closes its link when hop authentication has not finished 3 s after it opened (`BENCHMARK adaptive_phy: ... reason=setup_stall \| action=close`), and the 1M retry of a direct offer rescans up to 8 s for the callee's current advertisement by node prefix and route cost (the end-to-end handshake verifies the identity); a relayed offer keeps its original relay so the requested parent never changes. The authenticated 1M retry logs `BENCHMARK link_phy requested=2M effective=1M reason=setup_stall\|setup_retry source=adaptive` and records bad-2M memory except for a relaying hop. Every LE call attempt logs `BENCHMARK call_setup: ... hopAuthMs=`. Why: A15s central -> ASUS on 2M delivered nothing ASUS -> A15s after PHY Update Complete, and the old retry reconnected to the ASUS's stale random address; 1M went active in 2.5 s. 353 saved outgoing GATT setups authenticated within 1.05 s on healthy pairs. Off restores the retry only after the link dies, to the old address. |
| Private-call close waits for the peer (`test_call_close_wait_peer=false` is the fixed-400 ms control arm) | On (socket transports) | After HANG_UP/DECLINE/CANCEL the writer waits until the peer closes the link (its reader ends), capped at 3 s, instead of closing after 400 ms; the fixed drain lost the hang-up and about 1.3 s of media behind a 542 ms CoC queue. Line `BENCHMARK call close_drain`. Untested on phones. |
| Call audio headroom off (`test_call_audio_headroom=false`) [call_audio_headroom] | Headroom on | Control arm. The call send queue was 33 records for 500 ms of audio plus video. During one long GATT video write, audio alone filled it, so every queued video frame was dropped and a keyframe was forced; the keyframe's own write repeated the cycle (104 recoveries in 46 no-loss calls). Audio now has its own 3 s (150-packet) room and video counts only video. Untested on phones. |
| Call codec fallback off (`test_call_codec_fallback=false`) [call_codec_fallback] | Fallback on | Control arm. A legacy Qualcomm phone (API 32 or older) used to advertise VP9-only call decoding. Its end-to-end hello now lists its real decoders plus a signed "prefers VP9" bit (decode-mask bit 7). Senders keep VP9 whenever they can encode it, so VP9-capable peers are unchanged, and AV1-only phones (A15s, Xiaomi) send AV1 instead of failing with "Peer cannot decode any locally available call codec". The decision uses only the end-to-end hello, which relays copy opaquely, so direct and relayed calls agree. The Android CDD requires VP9 decoding, not VP9 encoding, on handhelds. The ASUS decoded AV1 640x480 with 0 freezes in 12 stream cases. Direct A15s <-> ASUS calls worked with the first version; this end-to-end form is not yet tested on phones. |
| Call video fragments (`test_call_video_fragments`) [call_video_fragments] | Off | Direct calls with the bit in both signed endpoint hellos. Video frames over 3 KB travel as authenticated 2 KB fragments and queued call audio is written between them (GATT audio waited up to 1.19 s behind a frame). PROTOCOL "Experimental call video fragments". Untested on phones. |
| Speech-led call playout (`test_call_speech_led_playout`) [call_speech_led_playout] | Off | Late call video may grow the shared call delay by at most 150 ms; late audio keeps the full 250-600 ms range. Later video is shown late (nothing is dropped) and is still reported to the sender's rate control. Every GATT call pinned the shared delay at 600 ms. Pair it with fragments, or audio itself still arrives late on GATT. Untested on phones. |
| Legacy call low delay (`test_call_legacy_low_delay`) [call_legacy_low_delay] | Off | Direct calls on a legacy-constrained route (a legacy Qualcomm phone, or a peer declaring a constrained CoC/Classic limit) send audio in 40 ms pairs instead of 400 ms batches and play with the normal call delay instead of 500-800 ms. Measured protection cost: about 0.9-1.2 s one way on M31/ASUS CoC, including voice calls. The batch's own evidence was a relayed route, where pairs sufficed. Relayed calls keep the batch. Untested on phones. |
| Legacy call relief (`test_call_legacy_relief`) [call_legacy_relief] | Off | No legacy Qualcomm (API 32 or older) call protection applies on this phone: no 426x240 cap, no 110-160 kbps limits, no VP9-only rules or forced software VP9 decoder, Opus 32, no batching or 40 ms flush, normal playout. The phone also advertises its normal receive limits. Its one switch is `privateCallStackFingerprint()`. Relay and stream rules are unchanged. Untested on phones. |
| Call over the lock screen (`test_call_lock_screen`) [call_lock_screen] | Off | While a call rings or runs, the call screen may show over the keyguard, and an incoming call turns the screen on. An incoming call also posts a CATEGORY_CALL notification on an IMPORTANCE_HIGH channel with a full-screen intent, Android's documented path for incoming calls on a locked device. Before, a locked or backgrounded phone only rang under a low-importance service notice. Asks for POST_NOTIFICATIONS (Android 13+) only while on. Android 14+ needs the full-screen special access, otherwise a heads-up is shown. Untested on phones. |
| First call frame on arrival (`test_call_first_frame_now`) [call_first_frame_now] | Off | The first decoded frame of the peer's call video is shown at once instead of after its 250-500 ms playout target; later frames keep the shared timeline. The aim is a shorter first-picture time (median 2.9-3.2 s from setup). Untested on phones. |
| Call encoder fit (`test_call_encoder_fit`) [call_encoder_fit] | Off | Capability-based, not family-based. If a call's native AV1 encoder stays over budget at its fastest preset with fast tools for 3 windows (the A15s encoded at 101-103 ms p95 against a 66.7 ms budget, giving 6.9-7.2 FPS while also decoding), that geometry is remembered for 7 days and later calls start at the next smaller offered size at the same FPS, shown in the status line. Live camera only (raw inputs fix the geometry). Untested on phones. |
| Sender-gap audio realignment off (`test_audio_gap_align=false`) [audio_gap_align] | Realignment on | Control arm. After a mute, push-to-talk release or dropped packet the receiver writes silence so the next talk spurt starts on its playout target, never more than the sender's gap. Before, the spurt played early with no jitter buffer and speech slowed to 0.96x for about 6 s. Decoded PCM also follows the sender timestamps now: the S24 decoder path kept output timestamps contiguous across a 6 s mute and then underran 164 times. Line `BENCHMARK audio_gap_align`. Untested on phones. |
| Low-delay voice-call playout (`test_voice_call_low_delay`) [voice_call_low_delay] | Off | Voice-only calls on unbatched links start playout at 80 ms (adaptive to 400 ms) instead of 250 ms; voice-only senders queue audio at most 27 ms. Line `BENCHMARK call_playout_profile`. Untested on phones. |
| Separate call audio clock (`test_call_audio_own_clock`) [call_audio_own_clock] | Off | Call audio keeps its own playout delay, grown only by late audio, instead of the video delay (600 ms in video calls after bursts). Speech may then lead the picture by up to the video's added delay: lip sync traded for conversational delay, a design decision. Untested on phones. |
| Call-link pacing (`test_call_link_pacing`) [call_link_pacing] | Off | Classic RFCOMM and LE CoC calls pace writes at the peer-measured arrival rate (seeded like a stream child), and the peer's call reports also teach learned link capacity, which today only stream children do. Each call flush already waits until the stack has read the socket buffer, so only the RFCOMM/L2CAP queues (about 10 KB) and controller buffers could hide below the app. Tradeoff: the bounded call queue can fill sooner and drop video until a keyframe, as on GATT. Needs link pacing. Untested on phones. |
| Call resolution ladder (`test_call_resolution_ladder`) [call_resolution_ladder] | On for normal launches (Automatic rate control); Off for DEBUG benchmark launches unless the extra is `true` | Design decision 2026-10-10. Only bitrate and FPS adapted before; the M31 VP9 encoder at 150 kbps and 640x480 sat at its maximum quantizer for 99.5% of frames. Like WebRTC (default singlecast start limits, BSD-3: VP9 480x270 120, 640x360 190, 960x540 350 kbps; AV1 176/256/384 kbps; 320x180 floor), a call steps its camera geometry down when the video target stays below the current size's limit for 3 s, and back up one rung after the target holds 1.25 x the larger size's limit for 10 s (at least 15 s after a change). Rungs are modes every lens offers at the call's exact FPS and aspect ratio, never above the call's start geometry (so device-policy call caps and the encoder fit still bound it). FPS, bitrate target and the call header are unchanged: the header is the maximum and the restarted encoder's first keyframe carries the new size in-band (Android CDD 5.3 dynamic resolution switching); no wire change. Cost: a camera/encoder restart (as for a lens switch) per change. Exact rate control, raw inputs and benchmark launches keep the requested geometry. Lines `BENCHMARK call_resolution requested=... effective=... reason=...` and receiver `BENCHMARK decoder_output_size`. Untested on phones. |
| Call audio focus (`test_call_audio_focus`) [call_audio_focus] | On for user sessions; benchmark launches off unless the extra | `CallAudioFocus`. A call holds AUDIOFOCUS_GAIN_TRANSIENT for USAGE_VOICE_COMMUNICATION while its audio session is open, so music, podcasts and navigation pause instead of playing over the call. A transient loss (a cellular call ringing or active) holds the call on the existing mute path, so the peer gets Microphone muted (signal 9) and the screen says "On hold"; regaining focus restores the user's own mute choice. Permanent and duckable losses are only logged. Line `BENCHMARK call_audio_focus`. Untested on phones. |
| Call ringer policy (`test_call_ringer_policy`) [call_ringer_policy] | On for user sessions; benchmark launches off unless the extra | `CallRinger`/`CallRingerPolicy`. An incoming call follows the ringer mode (silent: nothing; vibrate: vibration only; normal: ringtone, and vibration when "vibrate when ringing" is on or unreadable) and Do Not Disturb (no ring under None/Alarms; Priority rings only when calls from anyone are allowed). Vibration uses USAGE_RINGTONE attributes. If the default ringtone cannot be read (some Samsung phones throw SecurityException) it falls back to the settings default, then a bundled generated tone (`res/raw/call_ring_fallback.wav`). Before, it always vibrated and rang, and a Samsung exception played nothing. Line `BENCHMARK call_ringer`. Untested on phones. |
| Outgoing ringback (`test_call_ringback`) [call_ringback] | Off | The caller hears ToneGenerator TONE_SUP_RINGTONE on the voice-call stream while the callee rings, instead of silence; it stops on any other phase. Untested on phones. |
| CallStyle call notifications (`test_call_style_notification`) [call_style_notification] | Off | `CallNotificationActions`. The incoming alert becomes NotificationCompat.CallStyle with Decline/Answer and a full-screen intent (Android downgrades it to heads-up without the Android 14+ special access); the call's foreground-service notice becomes an ongoing CallStyle with Hang up. Answer opens the call screen (the camera/microphone service still starts while visible); Decline and Hang up are not-exported broadcasts. Every action carries a per-process random token, because MainActivity is exported. Names follow `NotificationPrivacy`: the incoming person is named only when opted in, otherwise "Encrypted call"; the ongoing notice never names the peer. Showing the call screen over the keyguard still needs `test_call_lock_screen`. Asks for POST_NOTIFICATIONS (13+) while on. Line `BENCHMARK call_notification_action`. Untested on phones. |
| Camera pause at the ear (`test_call_proximity_camera_pause`) [call_proximity_camera_pause] | Off | `CallProximityCameraPause`. In a video call with audio on the earpiece and the proximity sensor near, the outgoing camera stops and the peer gets Camera off; far again, or a route change away from the earpiece, restarts it with Camera on. The user's camera choice is unchanged; the screen-off lock is separate and unchanged. Line `BENCHMARK call_proximity_camera`. Untested on phones. |
| Call audio starvation concealment (`test_call_audio_conceal`) [call_audio_conceal] | Off (A/B later) | `AudioConcealment` (NetEq Expand/Merge/Accelerate ideas, WebRTC BSD-3, see THIRD_PARTY_NOTICES). When the call AudioTrack holds under 30 ms and no decoded audio is waiting, 10 ms chunks repeat the last pitch period under a 60 ms fade into comfort noise (at most about -50 dBFS) instead of underrunning; the next real audio is cross-faded in over 5 ms. Playout faster than 1.0x is then allowed only in quiet chunks unless the backlog exceeds 400 ms beyond the device baseline. Concealed samples are not traced as accepted media and do not move the sender timeline; they can add up to about 40 ms of queued audio after a gap. Line `BENCHMARK audio_conceal`. Untested on phones. |
| Caller names on the lock screen (preferences `notification_privacy`/`lock_screen_peer_names`, Advanced setting to add; DEBUG `test_lock_screen_peer_names`) [lock_screen_peer_names] | Off | `NotificationPrivacy`. Call and stream notifications are VISIBILITY_PRIVATE with a public version that says only "Bluetooth Video / Active" or "Incoming call", and the experimental incoming-call alert leaves the caller's name out of its text ("Open the app to see who is calling."). Before, neither set a visibility or public version, so a lock screen showing all content showed the activity and the caller's name. Opted in, the alert carries the name and is VISIBILITY_PUBLIC. Message notifications (none posted yet) would be VISIBILITY_SECRET. The full-screen call screen itself is unchanged. Untested on phones. |
| Always reachable for calls (`test_always_reachable`) [always_reachable] | On for normal launches; Off for DEBUG benchmark launches unless the extra is `true` | Found by the first iPhone -> Android test: on Home `dumpsys bluetooth_manager` showed `Advertisers: []`, so no caller could find the phone. On: the private-call endpoint (the same availability advertisement and GATT/CoC listener as the Call screen, no scanning) also runs on Home and Settings-from-Home while the app is visible, and in the background while the session service holds the connectedDevice type (started while visible, Android 12+ rule; a refusal stops the endpoint and `BENCHMARK foreground_service` reports it). Going live or watching closes it so the stream offer, title pages and viewer forwarder keep their advertising sets; it never adds a scan start. Low battery and Battery Saver do not turn it off (design decision: relay helping pauses, calls stay core). A call that arrives through Home returns to Home. Home shows "Reachable for calls - Visible to nearby phones" only while the endpoint really advertises. Off: exactly the old Call-screen-only behaviour. Line `BENCHMARK call_reachability`. The Settings switch "Reachable for calls" (default on, preference `reachable_for_calls`) is the user off-switch for normal launches. Untested on phones. |

### Transports, PHY and Bluetooth-stack behaviour

| Switch | Default | What it does and why it is kept |
| --- | --- | --- |
| Classic without pairing (`test_classic_bond_free`, `test_classic_request_visible`; Advanced setting) [classic_bond_free, classic_request_visible] | Off | Both phones need it. Calls, the call relay and the stream relay switch from Android's secure RFCOMM (which demands a numeric-comparison bond on both phones) to the insecure sockets streams already used; BMH2/BMS1/BME1 authentication and ordered encryption are unchanged and never depended on Android link security (LE CoC and GATT have none). Android then pairs Just Works without a dialog and stores no bond. Each phone remembers, in no-backup storage, the Classic address at which an authenticated identity was reached (64 entries, 30 days; a hint that BMH2 re-verifies, never trust), so a phone met once is paged directly. A first call to a never-met phone runs one Classic inquiry (about 13 s) after the normal candidates fail, and the other phone must be visible (its Bluetooth settings screen, or the system visibility prompt it shows once per process). Streams never run an inquiry and reach only phones already met. Untested on phones. |
| Bounded Classic connect (`test_classic_bounded_connect`; implied by Classic without pairing) [classic_bounded_connect] | Off | Every Classic role (stream, forwarder, call, call relay, stream relay) uses one connect path: a remembered or exactly bonded address first, then bonded phones and computers only (the call relay used to page earbuds and cars), each attempt bounded (exact address 12 s, bonded guess 3 s, BLE address 7 s; 11.5 s for streams inside the broadcaster's 12 s, 30 s otherwise), and a peer this phone cannot resolve fails within seconds with a pairing explanation instead of a bare timeout. A relayed call refuses a link that reaches the endpoint directly. Off: each role connects as before. Untested on phones. |
| Classic random-address skip off (`test_classic_page_random_address=true`) | Skip on Android 15+ | Control arm. Where Android reports a scanned BLE address's type (API 35+), Classic never pages an address reported random: BR/EDR paging can only reach a public address, and an unbonded peer's private address cost up to 12 s. Older APIs page it as a 7 s guess. Matcher `classicScanAddressTypeReported`, shaped for the policy registry. |
| Classic address overrides (`test_classic_addresses=prefix=AA:BB:..,...`, or `test_classic_address` + `test_classic_address_node`) [classic_addresses] | None | DEBUG: a run supplies a peer's Classic address read over adb for its 12-hex-digit announcement prefix; tried first, never stored. Samsung masks its own address in `dumpsys`, so this cannot always be filled automatically. |
| LE PHY (`test_ble_phy=1m`, `coded-s2` or `coded-s8`) [ble_phy], GATT request MTU (`test_gatt_request_mtu`, 23-517) [gatt_request_mtu] | 2M, 517 | 1M is a control for the ASUS <-> A15s ~65 kbps link. Coded is a long-range option for low-rate links such as voice calls (Core spec: 500 kb/s S2, 125 kb/s S8; measured GATT 154/47 kbps, CoC 81/40 kbps); both phones must support it (the M31 does not), connections still start on LE 1M, and learned link capacity is stored apart from 2M. Android 14+ clients always negotiate MTU 517 and ignore the MTU request. 2M stays the only selectable PHY. Untested on phones. |
| Aligned GATT values (`test_gatt_aligned_writes`) [gatt_aligned_writes] | Off | A 512-byte GATT value is a 519-byte L2CAP PDU, sent as three LE data PDUs of 251 + 251 + 17 octets; 495-byte values fill exactly two (about 245 instead of 170 payload bytes per PDU: +12% airtime efficiency on 2M, more where a controller limits PDUs per connection event). Writes and notifications. Untested on phones. |
| Connection-priority retry off (`test_le_priority_retry=false`) [le_priority_retry] | Retry on | Control arm. A central repeats its own priority request up to three times (0.5/1.5/4 s) after the stack reports the LE connection update failed (0x23 collision, 0x3B unacceptable parameters: ASUS 16, S24 39 in saved Bluetooth logs), which otherwise leaves the link at its 45-49 ms creation interval. Needs the hidden `onConnectionUpdated` report; no report means no retry. Line `BENCHMARK le_priority_retry`. Untested on phones. |
| CoC control PHY at connection (`test_coc_sequenced_phy=false`) [coc_sequenced_phy] | Sequenced | Control arm. The CoC control client requests the PHY after its priority update and MTU exchange, and the control server only reads it, as the GATT video path does. The old order (both sides at connection, beside the priority update) logged 339 `BLE PHY unavailable (status 42)` on M31, ASUS and A15s. Untested on phones. |
| CoC PHY after connect (`test_coc_phy_after_connect`) [coc_phy_after_connect] | Off | Experimental A/B arm. The CoC central requests no PHY during setup (the control server never does); after the CoC socket is connected and hop authentication finished, it requests the preferred PHY and waits for `onPhyUpdate`, with a `readPhy` fallback after 1 s as Nordic's Android-BLE-Library does (BleManagerHandler L2958-2985/L3838-3860, 46d17813), before the end-to-end handshake and media. Applies to stream sender, forwarder child, call (caller), call relay and video relay CoC links. Line `BENCHMARK link_phy: link=... \| requested=2M \| effective=2M\|1M\|failed(status N)\|unknown\|not_queued \| reason=after_connect \| source=update\|read\|none`. Why: a CoC connect overlapped the 2M PHY update (A15s -> ASUS CoC connect failed 6 ms after the request); Nordic treats a link failing right after an early 2M switch as a controller interop fault (Kotlin-BLE-Library Peripheral.kt L1056-1074). Cost: hop authentication runs on 1M and up to 1.5 s more setup. Off: the sequenced request after the PSM read. Untested on phones. |
| CoC single initiator (`test_coc_single_initiator`) [coc_single_initiator] | Off | Experimental. One deterministic initiator per direct call pair: the phone whose node-ID prefix sorts lower (unsigned) dials at once; the other holds its outgoing direct LE call for 1.5 s and yields (no failure notice) when the peer's call reaches it first. Line `BENCHMARK coc_single_initiator: role=responder \| peer=... \| holdoffMs=1500 \| outcome=dialed\|yielded`. Not a registry entry: AOSP 4dc8b042 (cited for an Android 11/12 crossing-connect rejection) changes only the enhanced/ECFC request that EATT uses, and android11-release `l2c_ble.cc` 558-635 accepts a crossing LE CoC request, so no stack-version rule is sourced; this tests app-level call glare only. Cost: up to 1.5 s more call setup for the higher-ID caller. Untested on phones. |
| Peer address book (`test_peer_address_book`) [peer_address_book] | Off | Experimental. Every accepted scan result refreshes its node-ID prefix's freshest address. A direct LE call to an address older than 10 s, replaced by a newer address of the same node, or never seen rescans for the node first (same prefix and route cost, through the scan-start gate; the handshake verifies identity). The first failed attempt is classified by elapsed time as Nordic does (retry only failures under 20 s, at most twice, 300 ms apart; a 133 after 25 s or more is a stale address: rescan, not a blind retry); the class is logged only. Why: Android rotates its resolvable private address every 7-15 min and at every new advertising set (android11-release `btm_ble_addr.cc` 96-102, `btm_ble_multi_adv.cc` 240-267); a connect to a dead address ends only at the ~30 s direct-connect timeout as status 133 (`connection_manager.cc` 33). Not done: streams and relays still connect to the scanned address. Untested on phones. |
| Android 11 and older CoC SDU coalescing (`test_coc_coalesce_sdus`) [coc_coalesce_sdus] | Off | Experimental, scoped by the inert registry proposal `android-11-coc-small-sdu-coalescing` (API 30 and older). Where it matches, CoC media SDUs are up to 8,000 bytes instead of 245-byte single-PDU SDUs (a 50 KB keyframe: 7 SDUs instead of 205), without pacing or drops; elsewhere the switch logs `reason=stack_not_matched`. Line `BENCHMARK coc_sdu: requested=coalesce\|single_pdu\|socket_limit \| effective=... \| sduBytes=...`. Why: Android 11 btif reads one message per SDU into its own buffer, L2CAP counts congestion in queued SDUs and a congested write is freed (`bta_jv_act.cc` 1182, `l2c_main.cc` 997-1007; upstream 471249f4, 46bd51ed); a candidate for the ASUS Bluetooth crash, which has no backtrace yet. It replaces `test_coc_single_pdu_sdus` on matching stacks, so the 251+4-octet keyframe split returns for MPS-251 peers; the app cannot see or cap the stack's own SDU queue. Untested on phones. |
| Shared LE CoC listener (`test_coc_shared_listener=true` or `false` forces it) | Android 10 only (registry) | Android 10 never stops a closed LE CoC server (`btsock_l2cap_free_l` lacks `BTA_JvL2capStopServer`; AOSP d3c996662453 added it in Android 11), so every closed listener, graceful or killed, kept a BTA JV security id (18 shared with RFCOMM/L2CAP servers) until Bluetooth restarted; the A15s then refused all LE CoC listens and connects. On that stack family one long-lived listener per role (private call, receiver, relay, call relay, raw benchmark) is leased to sessions; a connection accepted for an earlier or no session is closed. Its idle PSM stays connectable (closing it would leak). Bound: one registration per role per app process, plus one per concurrent same-role session or listener failure. Fixed stacks keep one listener per session. A debug receiver (`DebugBluetoothRestartReceiver`, adb shell only, DUMP-protected, refused during any session) cycles Bluetooth on Android 12 and older. Untested on phones. |
| Advertising restart at the balanced step (`test_stable_adv_address=false`) | Same set, parameters changed in place | Control arm. The 10 s low-latency -> balanced step stopped and restarted the advertising set; with LE privacy on (every reference phone) a new set gets a new resolvable private address, and all four A15s -> S24 connect timeouts dialled the old address within about 0.3 s of that restart. This is the app's own lifecycle race, not a stack regression. The default disables the set, changes interval/TX power and enables it again; the address stays until the stack's privacy timer rotates it. A failed or stalled step falls back to a restart whose start is bounded (8 s); if that fails the owner clears its advertising state and starts once more. Untested on phones. |
| Deferred GATT client close (`test_deferred_gatt_close=true` or `false`) | Android 11 and older only (registry) | Android 10/11 `ContextMap.removeConnectionsByAppId` is unsynchronized (AOSP 1a2c1e97d203 locked it in Android 12); `close()` 22 ms after `disconnect()` threw ConcurrentModificationException in the A15s Bluetooth process, which skips the native unregister. Its GATT discovery later failed until Bluetooth restarted, but the exception is not proven to have caused that. Deferred: disconnect, then close on the disconnect callback or after 2 s; an expired control attempt is cancelled first so its late callbacks do nothing. Line `GATT client closed after disconnect`. Untested on phones. |
| LE CoC client on a fresh ACL (`test_coc_fresh_acl=true` or `false` forces it) | Android 12 and 13 only (registry) | As LE central these stacks start EATT on every link; a peer that refuses it (the S24 on an unencrypted link) fails only the first of the five channels (android12/13 `l2c_ble.cc` 654-663; android14 `l2cble_handle_connect_rsp_neg` fixes it). A later CoC response can match a stranded channel whose EATT registration has no connect callback: the M31 Bluetooth process crashed (SIGSEGV pc 0 in `l2c_csm_indicate_connection_open`) on every saved CoC connect after a refused EATT. The client reads the PSM over GATT, closes that client and waits up to 2 s for the ACL to drop, connects the CoC on a fresh ACL (its channel queued before EATT's) and reattaches a GATT client for priority/PHY. Line `BENCHMARK coc_fresh_acl`. Untested on phones. |
| Automatic BLE GATT when LE CoC fails (`test_coc_auto_gatt`, `test_coc_gatt_threshold`; Advanced setting) | Off | Design decision 2026-10-09. The A15s stack refuses LE CoC listens and connects after about 15 registrations per Bluetooth process. With the flag on, a session that requests BLE L2CAP is moved to BLE GATT, with a visible notice ("Using BLE GATT: L2CAP unavailable"), when call availability (the CoC listen) fails, an outgoing CoC connect fails (that call is retried once over GATT), or the CoC registration count of this Bluetooth-adapter session reaches the threshold (default 12; reset by an adapter off/on broadcast). While on, the call list also shows GATT-advertising phones and a GATT peer is called over GATT for that call. A GATT or Classic request is never changed. Lines `LE CoC registration: kind=listen\|connect \| count=N`, `BENCHMARK call transport: event=... \| requested=... \| effective=... \| automatic=... \| reason=... \| cocRegistrations=N \| threshold=T`. A peer that only listens on L2CAP cannot be reached after a caller-side fallback; both phones need GATT reachability. Untested on phones. |
| Source/forwarder GATT priority (`test_source_gatt_priority`, `test_forwarder_gatt_priority`) | Policy by rate | Connection-priority experiments. |
| Scan-failure recovery off (`test_scan_recovery=false`) [scan_recovery] | Recovery on | Control arm. Every production BLE scanner goes through `BleL2capDiscovery` (call list, stream discovery, receiver/relay recovery, forwarder, benchmark); any `onScanFailed` used to end discovery and hand the error to the caller, which showed it and restarted after its own delay (30 s for viewer requests). Now ALREADY_STARTED stops and restarts the scan; registration failure, internal error, out of resources, scanning too frequently and unknown codes back off 3 s x n (capped at 30 s, reset by any result); FEATURE_UNSUPPORTED stops and reports. Each restart takes a slot from the scan-start gate. A continuous scan hands the failure to its caller's visible error path once the back-off reaches 30 s (about 2 minutes of failures); a timed scan that never recovered still reports its failure. Classic inquiry is not a BLE scan and is unchanged. Line `BENCHMARK scan_recovery`. Untested on phones. |
| LE scan start quota (`test_device_policy_off=le-scan-start-quota`) | On (registry, every phone) | AOSP refuses a 6th BLE scan start inside 30 s per app UID ("scanning too frequently", seen on the A15s and S24 in multi-phone runs). Starts wait for a free slot instead of failing, the user sees "searching again in N s", and the low-latency -> balanced switch is skipped when no slot is free. Starts are persisted per boot (elapsedRealtime keyed by Settings.Global.BOOT_COUNT) because the stack's count survives app process restarts but not a reboot. Untested on phones. |
| LE connect retry beyond 133 (`test_device_policy_off=le-connect-retry-link-statuses`) | On (registry, every phone) | GATT video and CoC control setup also retry status 8, 0x22, 0x3E and 147 (Nordic's Android-BLE-Library retries any connect-stage failure), at most once, and never past a private call's early-loss budget so the 2M -> 1M setup retry stays possible. Upstream-documented, not locally tested. |
| GATT write busy retry (`test_device_policy_off=gatt-write-busy-retry`) | On (API 33+) | A write the stack reports busy (one permit per peer address for every client and app) is repeated every 2 ms for at most 500 ms instead of failing the link. Upstream-documented, not locally tested. |
| Android 14 MTU note (`test_device_policy_off=gatt-mtu-fixed-517`) | On (API 34+, diagnostics) | The `LE link:` line marks a `test_gatt_request_mtu` below 517 as ignored: Android 14+ always requests 517. |
| Transport proposals (`android16-bond-loss-dialog`, `samsung-gatt-direct-connect-5s`, `android15-qcom-coc-connect-stall`, `android12-gatt-disconnect-status-zero`) | Inert (reported only) | Sourced in `BluetoothTransportPolicy.kt`; promotion needs a phone A/B. |
| Decoder init fallback (`test_device_policy_off=decoder-init-fallback` to disable) | On for user sessions only | Media3 `maybeInitCodecWithFallback`. A video decoder that fails to create, configure or start is released and the next decoder for the format is tried: hardware first, aliases skipped, at most 3 attempts. A policy-forced software decoder (`qcom-legacy-call-vp9-software-decoder`) falls back only to another software decoder, or the call fails. Never in a DEBUG launch with `test_*` extras, and never for a `test_decoder` request: those keep the former single choice, and `BENCHMARK decoder requested=/effective=` lets the scorer fail a substitution. Upstream-documented, untested on phones. |
| Device policy entry off (`test_device_policy_off=<id>[,<id>]` or `all`) | Every entry on | Turns individual registry entries off for an A/B; release builds ignore it. Codec and YUV entries that apply on every phone: `codec-exception-recoverable-reinit` (a call decoder whose CodecException reports `isRecoverable()` is re-created at the next keyframe, at most twice) and `yuv-image-crop-rect` (camera YUV copies and the reference recorder start from `Image.getCropRect()`). Both are upstream-documented and untested on phones. |

### Background and lifecycle

| Switch | Default | What it does and why it is kept |
| --- | --- | --- |
| Foreground-service refusal fallback (`test_device_policy_off=bg-fgs-start-fallback`) | On (API 31+) | Only Android's two refusals are handled: a background start (ForegroundServiceStartNotAllowedException) or a while-in-use camera/microphone type refusal. The service keeps connectedDevice, shows "Camera and microphone are unavailable while the app is in the background", and logs `BENCHMARK foreground_service` (requested, effective and refused types); the scorer fails a session whose refusal is not `none`. Off: the exception propagates (crash) as before. Untested on phones. |
| Background reliability checks (`test_device_policy_off=bg-battery-optimization`, `bg-background-restricted`, `bg-standby-bucket`, `bg-unused-app-restrictions`, `bg-power-save-mode`, `bg-sony-stamina`) | On (diagnostics) | `BackgroundReliability.status(context)` reports Android's documented restriction states with settings links; line `Background reliability:` at start. No keep-alive hacks, no exemption prompt. |
| Phone-maker background guidance (`test_device_policy_off=bg-oem-<maker>`, 20 makers) | On (by `Build.MANUFACTURER`) | Don't Kill My App (3616bc8b) steps per maker (Samsung, Xiaomi, Huawei, OnePlus, OPPO, vivo, realme, Motorola, Sony, Nokia/HMD, Google, ASUS, Tecno and others) plus documented killer apps (manifest `<queries>`, detection only). Makers without a page get generic advice. Upstream-documented, not locally tested. |

### Discovery privacy, identity and messages

| Switch | Default | What it does and why it is kept |
| --- | --- | --- |
| Rotating discovery IDs, phase 1 (`test_rotating_ids`; Advanced setting "Experimental: rotating discovery IDs") | Off | LE (CoC/GATT) call-ready presence broadcasts a 15-minute rotating ID (announcement version 5; compact UUID v2 decodes too) instead of the fixed node prefix, so a passive scanner cannot follow a phone between places. Each boundary stops the call listener, waits 1-8 s and reopens it on a new advertising set (new address; new LE CoC PSM on Android 11+). A caller that picked a version 5 offer requires the link opening `BDO1` (link hello bit 3, hop type 13) and checks it against the authenticated node ID; version 2 offers keep the prefix check. While on, the Bluetooth name is the neutral `Phone` (restored when turned off) and strangers' repeated silent handshakes wait 2^(n-6) s (max 30 s). The Settings button "Visible by handle for 10-25 minutes" advertises version 2 until the first boundary at least 10 min later, so old builds can find the phone (no permanent dual advertising). Strangers only: contact leases, `BDX1` and the recognition table are defined and tested (`PresenceLeases.kt`) but not sent; streams, viewers, forwarders and relay chains still use the fixed prefix; Classic availability stays version 2; relayed version 5 offers are refused; old builds cannot see the phone outside a window. Kept off until address-change measurements on advertising-set restart and the phase 1 phone cases run. PROTOCOL "Experimental rotating discovery IDs"; measurement helper `tools/rotid_phase0.py`. Untested on phones. |
| iPhone compact discovery (`test_ios_compact_discovery`; Advanced setting "Experimental: find iPhones") | Off | Discovery also accepts the one-UUID announcement a foreground iPhone can advertise (iOS apps cannot send manufacturer data): one more masked scan filter (top 16 bits `0xC8D6`) in the same scan start, decoded into the ordinary announcement, so iPhone call offers, viewer requests and stream offers reach the existing lists, matching and handshakes. The 22-byte announcement wins when both are present; this phone's advertising and all authentication are unchanged. Kept off until an iPhone app and the masked-filter behaviour of the reference phones are tested. PROTOCOL "Experimental iPhone compact discovery". Line `compact_discovery: enabled=`, DEBUG `scan_stats ... compact= \| compactInvalid=`. Untested on phones. |
| Names and titles (Experimental; `test_peer_labels`; Advanced setting "Experimental: names and titles") [peer_labels] | Off | Naming design phase 1. Settings > Your ID gets an optional name (empty = anonymous: handle only); placing a call asks for an optional subject and Go live for an optional title. Names and subjects are identity-signed `BNL1` labels bound to the call ID, sent only inside the encrypted endpoint session when both endpoint hellos carry bit 5 (the callee's name only with Accept, or at Ringing to a verified contact); stream titles are bound to the stream ID, sent to direct viewers before the header and passed on unchanged by forwarders. The title is also advertised publicly in a second non-connectable legacy set (title pages; no free set = signed title only) and shown "unconfirmed" until the signed copy arrives after joining. Self-claimed names are quoted beside the full handle; strangers always show the full handle (also with the switch off). Off: no hello bit, no type-10 record, no title pages (wire unchanged). DEBUG extras `test_peer_label_name`, `test_call_subject`, `test_stream_title`. PROTOCOL "Experimental peer labels". Open: no registry off-switch for extra advertising sets until a stack misbehaves with evidence; UTS #39 confusable skeletons are not bundled (all-Cyrillic look-alikes pass). Untested on phones. |
| Verified-contact mismatch policy (`IdentityVerification.policy`: `MismatchPolicy.WARN`) | REFUSE | An end-to-end handshake from a peer whose node-ID prefix matches a verified contact but whose full key differs is refused by default. WARN is kept for diagnosis only: it logs and continues. An unreadable verified-contact store fails open with a logged `STORE_UNREADABLE` (whether it should fail closed is an open design question). |
| Store-and-forward messages (`test_store_forward`; Advanced setting) | Off | Intercom pillar, design A v1 (direct neighbours only). Home shows Messages: copy your contact card or add one from text, accept cards received over a link after comparing their safety number, write text, a voice note (up to 2 min) or a photo (up to 1280 px) with a 1/3/7/14-day lifetime, and read an encrypted-at-rest inbox and sent list. Messages move when both phones have Call open and one taps the other and chooses Exchange messages. A call tap or an incoming call pauses a running exchange within one chunk. The screen is FLAG_SECURE and the copied card is marked sensitive. Wire format: PROTOCOL "Experimental store-and-forward messages" (hop type 11). DEBUG controls `sf_sync:PREFIX`, `sf_send:PREFIX:BYTES`, `sf_accept_cards`, `sf_suspend`; line `BENCHMARK store_forward session`. Untested on phones. |
| Automatic store-and-forward sync (`test_sf_auto_sync`; needs `test_store_forward`; DEBUG launch extra only) | Off | Starts the same BSF1 exchange without a tap. Local only: no wire change and the same responder limits (60 s cooldown, 10 per hour). Sightings from the Call screen's discovery trigger it; a Home phone takes part as the responder. It runs for an accepted contact when this phone has queued messages for them or carried copies (carrier role, hard-off in v1); otherwise it pulls what they may hold, at most every 10-30 min. Backoff is per peer: 65 s x 1.5^failures, capped at 10 min, with +-15% jitter; the phone with the higher node prefix waits 10 s. Live media wins: no start during a call, stream, relay, user selection or thermal block, a tap or requested call cancels a pending start, and a running exchange suspends within one chunk. Outbox states are queued, carried, delivered and failed (the recipient was reached 8 times without taking the message). A failed message stops triggering automatic sync, but a tapped Exchange still offers it. Re-implemented from Briar's backoff poller and Bitchat's outbox (patterns only, no code). Line `BENCHMARK sf_sync auto=...`. JVM tests only; untested on phones. |
| Debug emergency wipe (`test_emergency_wipe=<token>`, DEBUG builds only) | No trigger | Lets a benchmark exercise the full wipe. It needs a one-shot 32-128 hex token that adb writes into internal storage with `run-as`; release builds have no external trigger. Panic and confirmed wipes are in-app only. |
| Quick wipe gesture (Settings > Safety "Quick wipe: triple-tap the app name"; preference `panic_wipe_gesture`) | Off | Three quick taps on the home-screen app name start an emergency wipe after a 3 s Undo. Off by default so a stray tap cannot erase anything; Settings > Emergency wipe (type WIPE to confirm) is always available. Untested on phones. |

### Sharing the app

| Switch | Default | What it does and why it is kept |
| --- | --- | --- |
| App share as `.apk.zip` (`AppShare.shareApk(activity, AppShareFormat.ZIP_RENAMED)`) | Off (`.apk`) | Experimental. The same verified bytes named `BluetoothVideo-<version>-<code>.apk.zip` as `application/zip`, because stock AOSP OPP offers itself and accepts inbound files only from a MIME allowlist that includes zip but not APK (packages/modules/Bluetooth 745ee92b87ed `AndroidManifest.xml` SEND filter, `opp/Constants.java` `ACCEPTABLE_SHARE_INBOUND_TYPES`). The receiver must rename the file to `.apk` before installing; the share text says so. Untested on phones. |
| Share app nearby (`test_offline_app_share`; Advanced setting "Experimental: share app nearby") | Off | Experimental (Briar/Bitchat pattern re-implemented from a description, no GPL code). Help > "Share app nearby" starts a Wi-Fi Direct group (5 GHz first where supported, then automatic, then legacy `createGroup`, because 2.4 GHz Wi-Fi halved M31 Bluetooth throughput; random `DIRECT-xy-...` name without the app name, random 12-character passphrase) and `OfflineAppShareServer`, a read-only HTTP server bound to the group address that accepts only peers in the group subnet and serves only `/<20-character random token>/` (a page with the APK SHA-256 and signing-certificate SHA-256) and the APK under that token (single Range for resume); everything else is 404, no listing. Two QR codes: the Wi-Fi join and the URL. Stops after 10 minutes, when the screen is left, or when a stream, call, relay or reconnect exists (it refuses to start then). Needs `NEARBY_WIFI_DEVICES` (`neverForLocation`, Android 13+) or fine location with Location on (Android 10-12), requested only by that screen; adds the install-time `INTERNET` and `CHANGE_WIFI_STATE` permissions (an IP socket needs `INTERNET`; the app makes no internet connection). Split installs and debug-key release builds are refused as in `AppShare`. Untested on phones. |
