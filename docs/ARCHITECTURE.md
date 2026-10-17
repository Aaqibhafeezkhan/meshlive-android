# MeshLive architecture

For developers and curious readers: how MeshLive is put together (modules, radio links, sender and receiver pipelines, relay chains, calls, identity at a high level, distribution) and a summary of the experimental switches. Wire formats and security rules are in [PROTOCOL.md](PROTOCOL.md); testing is in [TESTING.md](TESTING.md); unfinished work is in [PLAN.md](PLAN.md).

Some words used throughout:Some words used throughout:

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
- Tapping a person to call waits up to 10 s for that person's next Bluetooth sighting (a scanner restart, for example
  after returning to the app, can delay the first sighting by several seconds), shows "Looking for <name>…", and on
  expiry says the person was not found nearby with the reason (not seen, or this phone's scanner is unavailable). It
  adds no advertising and sends nothing extra over the air.

## Where the code is

Everything under `app/src/main/java/com/example/bluetoothtest/` (the Kotlin package keeps its original name; the
product is MeshLive). Native code is in `app/src/main/cpp/`.

| Area | Main files | What they do |
| --- | --- | --- |
| Screens | `MainActivity`, `HomeUi`, `ContactActions`, `PeopleList`, `SettingsUi`, `StatusIndicator`, `CallScreenUi`, `CallScreenController`, `MessagesActivity`, `AboutActivity`, `OnboardingActivity`, `SupportUi`, `SecurityUi`, `VerifyContactActivity` | UI and session ownership. Sessions live with the activity, not a background service. |
| Discovery | `BleDiscoveryAdvertiser`, `BleL2capControl` (scanner and control server), `StreamDiscovery`, `MeshControlMessages`, `CompactDiscoveryAnnouncement`, `RotatingDiscovery`, `TitleAdvertiser`, `StableAdvertisingSet`, `PeerAddressBook`, `PeopleList` | The 22-byte announcement, scanning with a start quota and failure recovery, and the "People nearby" and "Nearby broadcasts" lists. |
| Transports | `ClassicConnector`, `ClassicTransport`, `ClassicPeerConnection`, `BleCocClient`, `CocListenerHub`, `CocSocketOutput`, `GattVideoTransport`, `AdaptivePhy`, `BlePhyPreference`, `LeConnectionPriorityKeeper`, `GattClientTeardown`, `BluetoothTransportPolicy` | The three media transports, PHY and connection-parameter handling, and stack-bug workarounds. |
| Security | `SecureBluetoothLink`, `EndToEndVideoSecurity`, `ForwardedStreamSecurity`, `GroupBroadcastSecurity`, `AndroidNodeIdentityStore`, `IdentityVerification`, `EmergencyWipe`, `NotificationPrivacy` | Handshakes, record sealing, identity, verified contacts and the wipe. |
| Sender | `CameraVideoEncoder`, `NativeAomBridge`, `MicrophoneOpusEncoder`, `NativeOpusCodec`, `VideoCodec`, `EncoderBitrateAdjuster`, `NativeEncoderSpeedGovernor`, `BluetoothVideoBroadcaster`, `BluetoothVideoSender` | Camera and raw input, encoders, rate adjustment, framing, per-child queues. |
| Receiver | `AndroidVideoReceiver`, `VideoStreamDecoder`, `EncodedFrameQueue`, `OpusAudioDecoder`, `MediaPlayoutClock`, `AudioPlayoutSync`, `PlaybackDelayProfile`, `VideoSurfaceTransform` | Read, authenticate, queue, decode and present. |
| Mesh and relay | `ReceiverStreamForwarder`, `BluetoothVideoRelay`, `MeshCore`, `MeshBandwidth`, `RelayChildHealthPolicy`, `ReceiverNodeHealthPolicy`, `FanOutStarvationPolicy`, `SourceCapacityRejectionPolicy`, `RelayChildReanchor`, `LinkCapacity`, `LinkRateController` | Hop-local forwarding, admission, overload withdrawal, learned link capacity and rate control. |
| Calls | `AndroidPrivateVideoCall`, `PrivateCallSession`, `PrivateCallMedia`, `PrivateCallAudioSession`, `PrivateCallRelayChain`, `BluetoothPrivateCallRelay`, `IntercomCall`, `CallPlayoutPolicy`, `CallAudioFocus`, `CallRinger`, `CallResolutionLadder`, `AudioConcealment` | Call signalling and media, relay chains, intercom features and audio behaviour. |
| Messages | `StoreForwardNode`, `StoreForwardLink`, `StoreForwardStore`, `StoreForwardCrypto`, `StoreForwardAutoSync`, `MessagesActivity` | Experimental store-and-forward messages. |
| Messages v2 screens | `ThreadScreen`, `MessagingV2ThreadModel`, `ConversationRowsController`, `MessagingV2PrivacyRows`, `VoiceNoteRecorder`, `ThreadSettings` | Experimental conversation rows on Contacts and the thread view (see "Store-and-forward messages"). |
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

## Screens and navigation

The look is "Plain Signal": black ground, Manrope (four bundled weights), Material Symbols Rounded icons, white
primary actions, red only for live, hang up and decline, green only for answer and the verified check, plain rows
instead of cards. The tokens are in `res/values/colors.xml` and `themes.xml`.

`MainActivity` owns every screen as a sibling view and a bottom navigation (`mainNav`) shown on the four tab
screens only: Live, Calls, Contacts, Settings. Live is the stream browser itself (`AppScreen.RECEIVER_BROWSER`): it is
the landing screen, scans for nearby streams while it is visible, lists them as plain rows (tap joins) and pins the
one Go live button above the tab bar; back from it leaves the app. Calls has a Nearby | Recent switch: Nearby is the
call browser (`AppScreen.CALL_BROWSER`, discovery running): everyone in range with voice and video buttons, contacts
first, strangers as "Someone nearby"; Recent is the history of calls (`RecentCalls`) in the idle Home state, where a
tap calls back and a long press blocks. Contacts lists the saved (verified) people, "Nearby now" for those in range
(the call browser runs there too), a tap opens the contact sheet (`ContactActions`: call, video, rename, block, remove,
full ID; with Messages v2 on it opens the conversation and the sheet is in the thread's menu), and Add contact is the one pinned button. Add contact (`VerifyContactActivity`) has a Scan their code | Show
my code switch and is the only place this phone's code and ID are shown. Reachability follows `CallReachability`: Home (Calls > Recent, Settings from Home) and the Live tab while it only browses (`ReachabilityScreen.LIVE_BROWSING`, scanning needs no advertising set) keep the idle call endpoint, so the phone rings on the landing screen; joining a stream (`ReachabilityScreen.WATCH`) or going live closes the endpoint so the viewer request, stream offer and title set keep their advertising sets, and returning restores it. An incoming call while browsing stops the stream scan, shows the call, and returns to the Live tab afterwards. The switch is `ReachableWhileBrowsingFeature` (SWITCHES). Every tab's title bar ends with a status indicator (`StatusKind`): a green dot when
all is well, otherwise the one highest-priority problem with a short word (Bluetooth off, permissions, Hidden, battery
saver pausing relay, heat lowering quality), computed from the existing readiness, reachability, battery and thermal
logic; a tap fixes Bluetooth/permissions or opens the status sheet (Bluetooth, Allow calls, Relay video for others,
battery and temperature). The broadcasting and watching screens share one top bar (back, title, red-dot LIVE badge,
timer when broadcasting). Control rows are one row of equal columns with an odd count and the red End circle (72 dp, the others 64 dp)
exactly in the middle (`HomeLayoutTest` asserts it): a call, voice or video, is Mute, Camera, End, Flip, Speaker; a video live is Mute,
Camera, End, Flip, Pause (`LiveControlSlots`); an audio-only live is Mute, End, Pause with Switch to video as a plain action under it.
A control with nothing to do keeps its slot (hidden or disabled) so End never moves. Flip is Material Symbols `cameraswitch`.
Speaker toggles the earpiece and loudspeaker (voice calls start on the earpiece, video calls on the loudspeaker); once a wired headset is
present (or a Bluetooth audio device, behind the experimental `call_bluetooth_output` switch) it opens the audio output sheet
(`CallOutputSheet`: Phone speaker, Earpiece, the device by name). The routing rules (`callOutputFor`, `PrivateCallAudioSession`) are unchanged
except that an explicit pick in the sheet is honoured while that output exists. A video live's Mute drops the Opus access units from
the microphone encoder (the codec configuration still passes; the microphone stays open as in calls) and Camera releases the camera and
encoder (a keyframe-first restart when it comes back); both work with or without the `live_media_state` switch, which only decides
whether viewers are told (a state record, PROTOCOL "Experimental live media state"). Flip while live restarts the stream camera on
the other lens when that lens offers the stream's current size and frame rate, otherwise it explains and changes nothing. Switch to video
in an audio-only live ends the radio, then starts a new video live with the same title (a new stream, never converted in place). Settings is the fourth tab: one scrolling top level whose rows open
one-level pages (`SettingsPage`: Video, Privacy, Calls, Share app offline, Experiments); Advanced is a section label followed by exactly five
plain rows (Connection, Manual video, Playback, Experiments, Help and feedback), each opening one page of that group's options
(`SettingsPage.CONNECTION`, `MANUAL`, `PLAYBACK`, `EXPERIMENTS`, `HELP`, all with the top level as parent), so nothing nests two levels. Every setting and experiment still exists; a settings row says what it does and shows at most a
current value, and its explanation opens from an (i) (`SettingsInfo`). Lists show contact names or "Someone nearby",
never an ID.

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

When a live stream sender moves its own link from 2M to 1M PHY (`one_pdu_per_event` or `stalled_one_way`), video queued for
longer than twice the viewer's playout (at least 4 s) is dropped once, codec-safely, and the sender waits for a keyframe
(`PhyMoveSkip`). This is on by default for streams with a fixed (manual) bitrate and off with Auto quality, calls and
audio-only live (SWITCHES, "Skip the backlog at a PHY move"). A separate flood guard drops media older than 8 s at the
same moment.

Bluetooth stacks differ a lot between Android versions and makers. Workarounds for known stack bugs (deferred GATT
close on Android 10/11, a shared LE CoC listener on Android 10, a fresh ACL for CoC on Android 12/13, write-busy
retries on Android 13+, the scan start quota and others) are entries in the device policy registry described
below, each with an upstream source.

## The sender pipeline

```text
Camera2 preview surface ----> MediaCodec hardware encoder (VP9 or AV1), surface input
Camera2 -> SurfaceTexture -> GL ----> MediaCodec hardware encoder, surface input   [experimental GPU scale ladder]
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
- **Measured Auto ladder (Experimental, off by default; `test_auto_ladder`).** With Video quality on Auto, the
  start geometry, frame rate and rate come from the measured link instead of the fixed default, and the same
  rungs are then walked up and down while the session runs. `AutoQualityLadder` is a pure policy: link goodput
  (85% utilisation; a call direction plans half the measured link; unmeasured plans 300 kbps), codec, encoder
  class and the wire model `1.076 x (video + 0.64 x fps) + audio wire` give a rung (below the lowest it is audio
  only). The request becomes a climb bound (a call: the transport maximum; a stream: the learned fan-out budget for
  one link, with admission still planned at the start rate) and the call header the class maximum geometry, so
  nothing in Auto is a fixed ceiling. `LinkRateController` starts at the plan's rate and climbs only on clean evidence;
  `CallResolutionLadder` (calls and live streams) follows its target with the measured limits, one rung up after
  1.25x headroom held 10 s and 15 s between changes, down after 1.5 s; `FrameRateLadder.curve` cuts frame rate
  above 15 FPS first and below 15 FPS only after 240p. A stream restarts its camera encoder at a rung change
  (never while paused; the first frame is a keyframe carrying the new size in-band) and follows the weakest direct
  child, so a source never outruns its tightest child (relays handle their own downstream hop). Manual video
  settings are never touched; the picker only warns when bits per pixel per frame fall below the fitted floor.
  Evidence and phone plan: DECISIONS "Measured Auto ladder".
- **GPU scale ladder (Experimental, off by default; `test_gpu_scale_ladder`, with the Auto ladder).** A rung change
  no longer restarts the camera. `GpuScaleCapture` opens the camera once at one capture mode (`GpuScaleMath.chooseCapture`:
  the smallest mode that covers the session's largest picture in its aspect, cropped on the GPU when the sensor has none)
  into a SurfaceTexture; `GpuFrameRenderer` (one EGL context on its own thread) draws each frame into the encoder's input
  surface at the rung size, the crop and scale folded into the texture matrix, with the camera's sensor timestamp as the
  presentation time (so timestamps stay continuous across encoder resets). Frames stay in sensor orientation exactly as
  when the camera fed the encoder directly, so the display-rotation flag and the viewer's transform are unchanged. Rungs
  are 720/540/480/360/240/180/144p in the session aspect, each side rounded to the encoder's alignment with the few
  pixels of difference cropped, never stretched. A rung change is `old encoder close()` (the capture stays open for
  2.5 s) then `new CameraVideoEncoder.start()`; its first keyframe carries the size change marker as before, so
  viewers rebuild their decoder. Software YUV-input encoders, raw fixtures, benchmark reference frames, Manual video
  settings and camera flip/off keep or release the camera as before.
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
- **Size changes mid-stream.** A ladder restart (Auto stream ladder, call resolution ladder) changes the coded size; the
  viewer reads each keyframe's size from its bitstream and, when the size or the codec configuration differs, releases its
  decoder and creates one for it (held new csd, recovery with the newest csd, adaptive-playback maximum, display follows).
  The sender marks the first keyframe of a size-changing restart as a discontinuity; late joiners get only the current
  encoder's csd. Switch and detail: SWITCHES.md, PROTOCOL.md "Mid-stream size changes".
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
- **Per-child thinning (Experimental, off by default).** With the relay child thinning switch on, a forwarder gives
  its child one of three levels, chosen from that child's own queue age, the rate this phone delivered to it and the
  child's late-media report (nothing from any phone beyond the next hop). *Full* forwards every record. *Base*
  leaves out the frames the source marked discardable, which it does only for frames whose compressed data proves that
  no later frame refers to them, so the child sees half the frame rate and every frame it gets decodes: no re-encode,
  no keyframe, and stepping back up needs nothing. *Audio only* leaves out all video while audio, configuration and
  stream-state records continue, so a link too weak even for the base layer keeps speech instead of stalling at a
  fraction of a frame per second; the child shows "Video paused: weak link", and the way back asks for a keyframe
  with the same back-off the bounded-age shedding uses. A step down is immediate on a severe backlog and otherwise
  needs the backlog to persist; a step up needs a calm period and a hold that doubles after each failed probe
  (8 s to 60 s), and does not start if the child's measured capacity cannot carry the next level. Only whole records
  are dropped, so the end-to-end checks are unchanged: each record is sealed with its own sequence, flags in its
  authenticated header, and the receiver accepts any increasing sequence (a gap), refusing replay and reordering. The
  source encodes the second layer for the whole stream while the switch is on (about 2.5 VMAF at equal bitrate),
  because it cannot see a weak link two hops away.
- **Losing a parent.** An unexpected parent loss keeps the last frame on screen and searches for the **same logical
  stream**. The replacement can be the source or another forwarder, but its authenticated identity must match its
  announcement and the original source must match the one first accepted. Playback resumes at a fresh keyframe. The
  recovery has a gap (break-before-make). An explicit End from the source returns the viewer to discovery.
  Experimentally (Settings > Features "Show when the picture is out of date", default off, see the register) the
  preserved frame is dimmed and labelled "Signal lost - reconnecting" with how long ago the last picture arrived, so
  it is never mistaken for live video.
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
- Android backup is off (`allowBackup="false"`, no backup or data-extraction rules): the keys are Keystore-bound, so a
  restored copy would be unreadable, and nothing is sent to cloud backup.
- Call and stream notifications carry the peer's name in their full version only (shown on an unlocked phone); the
  lock-screen version is name-free (`NotificationPrivacy`, `ActiveVideoSessionService`). The opt-in "Show caller names on
  the lock screen" makes a named notification public.
- An unreadable verified-contact store fails open: calls and streams continue and nobody shows as verified. The home
  screen shows "Verified contacts couldn't be loaded" (`VerifiedStoreBannerController`). A temporary Keystore or I/O
  error is retried with backoff (2 s doubling to 60 s, `VerifiedStoreRecovery`) and the banner clears by itself; a
  corrupt file is kept untouched and never overwritten, and the banner offers "Reset verified contacts".
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
- The phone that dials gets the call dial's 2M protections (`MessageDialPhy`, switch `test_message_adaptive_phy`): a first 2M link whose
  hop handshake stalls is closed after 3 s and retried once on 1M, and a peer that needed that, or whose 2M exchange died early
  with no answer, starts on 1M next time. A plain exchange keeps its 120 s total limit only while no transfer moves: it is extended
  (up to 15 minutes) while chunks keep flowing, and the 10 s liveness and progress deadlines stay strict (`test_mv2_progress_total`).
  A responder gives the 60 s spacing back when an admitted exchange is cut by the link before anything moved (the hourly cap stays).

See [PROTOCOL.md](PROTOCOL.md), "Store-and-forward messages", and [DECISIONS.md](DECISIONS.md), "Design A", for the design.

**Messages v2 attachments (Experimental, switch `test_messaging_v2`, off).** Phase 1b of Design M lets a message carry any file. On send, `AttachmentPreparer` turns a picked photo, video or file into a `PreparedAttachment` for `MessagingV2Engine.sendFile`: pictures are re-encoded (1600 px, JPEG 80, no metadata) or, as "Original", a JPEG is rewritten without any `APPn`/`COM` segment or trailer (`JpegMetadataFilter`); videos are remuxed without their location atoms and scrubbed again (`Mp4Scrubber`); any other file is sent as it is. On receive, nothing is parsed or decoded automatically: `FileSniffer` decides from the bytes what the file is (the sender's MIME type is only a label), `FileActionPolicy` offers Open only for pictures, audio, video, PDF and plain text that are not polyglots, and everything else is Save only behind a warning. Open hands the viewer a revocable one-file grant on a non-exported `MessagingV2OpenProvider` that decrypts from the file vault on demand through a proxy file descriptor (`OpenGrantTable`, `VaultRangeReader`); a picture is decoded only after a tap, in `MessagingV2ImageService`, an isolated-process service. The details and limits are in [PROTOCOL.md](PROTOCOL.md) "Implementation notes (phase 1b)".

**Messages v2 conversations (Experimental, switch `test_messaging_v2`, off).** Phase 1c puts the engine behind two screens, both views of `MainActivity` rather than activities, because the thread link (phase 1d) is dialled from the discovery that runs while the Call screen (and so the Contacts tab) is showing; a second activity would stop that scan. With the switch off the Contacts tab is unchanged and a row still opens the contact sheet.
- *Contacts rows* (`ConversationRowsController`, `item_conversation_row`): each saved person is one row with the name and verified check, a one-line preview (or the kind: Photo, Voice note, File), the time, the unread count, the state glyph of the newest own message and "Nearby now". Rows with activity sort first (newest first), then the Contacts order. They are computed off the main thread from `ConversationIndex` and refreshed every 3 s while the tab shows. A tap opens the thread; the contact actions (call, video, rename, block, remove) live in the thread's top bar and its info sheet (`ContactActions`).
- *Thread* (`ThreadScreen`, `view_thread`): top bar (back, name with verified check, "Nearby" or "Not nearby", voice, video, more), a banner for the link state, the message list, the composer. Yours are raised-surface bubbles on the right, theirs plain-surface on the left, with date separators and an unread marker; a tap on a bubble's glyph shows its sentence ("Delivered to Rahul, 14:00"). The states are the engine's: Waiting, Sending, Delivered, Downloaded, Read, and Not sent with Retry (`MessagingV2Engine.retry` seals the message again from its words or from the copy the vault keeps). A file is a card (type icon, name, size) with Download, Cancel, Open, Save or Show picture as `FileActionPolicy` allows; a picture is decoded only after a tap. The banner covers `ConversationLinkState`: not a contact or card pending (Add contact, Compare), keys changed (Compare, Accept new keys after a confirmation, Dismiss; the composer stays off), and an older peer app. The composer has a text field, an attach sheet (Photo or video with "Send original", File) and a microphone that records a voice note while held (slide left to cancel; AAC in MP4, two minutes). The info sheet holds the conversation's disappearing timer and read-receipt choice (`ThreadSettings`, sealed), the contact actions, the safety-number comparison and Delete conversation. Opening the thread is `ChatPresence.threadOpened`; leaving it, or pausing the app, is `threadClosed` and revokes every open grant of the conversation. The window is `FLAG_SECURE` while the thread shows (a DEBUG launch with `test_allow_screenshots` is exempt).
- *Structure:* everything the screens show is built by pure code in `MessagingV2ThreadModel.kt` (row text, glyph mapping, banners and composer state, file cards, the list with separators), over a `ThreadText` that reads string resources by name (`strings_messaging_v2_ui.xml`), so the JVM tests check the real words. Settings > Privacy gains "Read receipts" (off) and "Download files automatically" (up to 8 MB) when the switch is on (`MessagingV2PrivacyRows`, `MessagingV2Options`).

## Audio-only live (Experimental)

Off by default (`AudioLiveFeatureDefaults.ENABLED`, Settings > Features). A radio is a stream kind of its own, not
video with the camera off. Wire rules: [PROTOCOL.md](PROTOCOL.md) "Experimental audio-only live streams"; reasons and
measurements to take: [DECISIONS.md](DECISIONS.md) "Design AL".

```text
microphone -> AudioLiveEncoder (libopus, MediaCodec fallback) -> AudioLiveBatcher (10 x 20 ms)
  -> AudioLiveSigner (one signature per record) -> BluetoothVideoBroadcaster -> senders (per-child queue)
  -> BMS1/BME1 mesh envelopes -> [relay: copy, shed stale whole records] -> listener
listener: reader verifies the source's signatures -> OpusAudioDecoder (RADIO clock, power-saving AudioTrack)
```

- **Source.** `AudioLiveSource` owns capture and the signer; `BluetoothVideoSender` writes the `BTL1` header and
  signed records and applies the age bound and the withdrawal rules per child; the source caps direct listeners at 4.
- **Listener and relay.** `AndroidVideoReceiver` branches after the header: no video decoder, surface or keyframe;
  a relay admits itself from the preset, joins its child at the next record and sheds stale records with the exact
  mesh-flag predicate. Playout uses the `RADIO` profile (start 600 ms, adaptive to 3 s). Audio focus loss mutes
  playout and keeps the link (a permanent loss leaves after 60 s).
- **Background.** A listener runs under the media-playback foreground service with a media-style notification and
  a framework media session (neutral lock-screen text, no metadata); a broadcaster runs under the microphone type.
  The service stops with the receiver.
- **UI.** `AudioLiveController` and its layouts (Go live choice sheet, broadcast screen, listening screen) are new
  files with small hooks in `MainActivity` marked "AUDIO-LIVE hook".

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
`YuvPolicy`, `BluetoothTransportPolicy`, `BackgroundPolicy`, `MemoryThermalPolicy`, `NativeCompat`, `ScanPolicy`,
`ServiceFamilyPolicy`, `AudioPolicyEntries`, `CocPolicy`, `CameraPolicy`, `AdvertisingPolicy` and `PhyLinkPolicy`). Code that
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

**Matchers, areas and service types added in 2026-10-14.** A matcher can now also name a security-patch window (so a
stack bug fixed in a monthly patch stops matching), one of five Bluetooth-stack eras (Android 10-11 legacy stack, Android 12 legacy stack with
optional new modules, Android 13 new core, Android 14 flag-heavy, Android 15+ aconfig flags) and *runtime traits* read once at start by
`DeviceTraitsProbe` (ROM version properties, supported ABIs, page size, memory, low-RAM flag, Bluetooth package
versions, audio features, camera hardware level on first use). A trait that could not be read is "unknown" and never
satisfies a constraint. Entries belong to a `PolicyArea` (codec, YUV, Bluetooth transport, graphics, background,
memory and thermal, native, camera, audio). Each area file exposes `entries` and is listed once in the aggregate;
three areas fold a sub-list into their own `entries` instead (GATT link entries inside the transport area, decoder entries inside
the codec area, phone-maker entries inside the background area, and the thermal family inside memory and thermal), so
no id is counted twice. The foreground service decides its types in one place (`ServiceTypePolicy`): camera and
microphone only for a session that uses them, `connectedDevice` for the Bluetooth session, `mediaPlayback` for audio-only
live, and the optional `location` type only on Android 10-11 and only while Location is on; a refusal drops the optional
type first and is reported as requested versus held (`fgs_start`). Every area has its own switches object
(`XxxSwitches.applyLaunchExtras`), parsed once in the launch code; all non-default arms are DEBUG-only and listed in
[SWITCHES.md](SWITCHES.md).

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
  restriction, standby bucket, power-save) and phone-maker guidance with settings links, and builds the Settings
  "Connection reliability" card. It adds no keep-alive hacks. The one system action it offers is Android's own battery
  exemption dialog, in onboarding, in Settings and when an always-on role is switched on (design decision Q38); the
  process-exit history, a phone-update stamp and the scan watchdog's suspect feed the card.
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
  or peer names, addresses or full keys) for GitHub issue forms. The exact contract is under "Diagnostics block" below.

## The desktop receiver and file sender

`receiver/` is a Windows C# receiver (Windows Bluetooth RFCOMM) that authenticates a phone, writes the stream to an
`.ivf` capture and can play it. It implements the older video-only `BTV2` path, so current `BTV3` audio and normal
discovery integration are unfinished; it is a development tool, not a finished desktop client. `shared/` holds the
C# implementation of the same handshakes and records, with an offline self-test
(`dotnet run --project receiver/BluetoothVideoReceiver.csproj -- --self-test`). `tools/BluetoothVideoFileSender/`
sends a prepared file to a phone for repeatable input.

## Host tools and the benchmark harness

`tools/` holds the Python harness that plans, runs and scores phone tests (inventory, case state, runner, scorers, release and icon scripts). It never touches a phone unless a run is explicitly started. Scope, acceptance gates, commands and result locations are in [TESTING.md](TESTING.md).

## Distribution and releases

For maintainers and packagers. Users: see [Install, update and verify](USER_GUIDE.md#install-update-and-verify).

- **Channels.** GitHub Releases (primary, signed APKs), F-Droid and IzzyOnDroid. No Play Store for now. Draft F-Droid
  metadata: `distribution/fdroid/<applicationId>.yml`; IzzyOnDroid checklist: `distribution/izzyondroid-notes.txt`;
  store text and changelogs: `fastlane/metadata/android/en-US/` (`changelogs/<versionCode>.txt` is required for each
  published APK code, and `make_release.py` refuses a release without them; `images/phoneScreenshots/` is empty until
  real screenshots exist).
- **ABI split.** Release builds use `-PsplitAbis=true -PenableArmeabiV7a=true` (set by `make_release.py`; default
  off, so debug and benchmark builds stay one arm64 APK). Outputs: arm64-v8a, armeabi-v7a and optionally universal.
  `versionCode` in `app/build.gradle.kts` is the base; each APK's code is `base * 10 + digit` (universal 0,
  armeabi-v7a 1, arm64-v8a 2: the Signal/F-Droid convention, so a device that supports both prefers 64-bit).
  Example: base 1 gives 10, 11, 12. `make_release.py` cross-checks Gradle's `output-metadata.json`, each APK's native
  ABIs, the 16 KB page-size check for 64-bit libraries (`tools/check_apk_16kb.py`) and that every APK has exactly the
  expected single signer.
- **Flavours.** `github` is the release page's build (`tools/make_release.py` runs `:app:assembleGithubRelease`; the in-app updater and its install permission live only there); `fdroid` is the F-Droid recipe's (`gradle: [fdroid]`, outputs `app-fdroid-<abi>-release-unsigned.apk`). Both carry the "newer version nearby" note. The APK of one source cannot update the other: F-Droid signs its own build, and the in-app updater is offered only to a copy that carries the release key. Neither flavour embeds a build time, so reproducibility is unchanged (`tools/check_reproducible_build.py` defaults to `:app:assembleFdroidRelease`).
- **Deterministic version.** `versionName`/`versionCode` are literals (no date or git hash). The APK carries no Google dependency-info block (`dependenciesInfo` off). **Reproducible builds.** `python -B tools/check_reproducible_build.py build --tree-a DIR_A --tree-b DIR_B -- -PsplitAbis=true -PenableArmeabiV7a=true -PuniversalApk=false` builds the unsigned release (release key never read; build cache off; sequential; refuses to start when memory use is high) in two clean checkouts at different paths and compares every APK entry; `compare A.apk B.apk` compares two existing APKs ignoring the META-INF signature files, as F-Droid does. Result 2026-10-11 (AGP 8.13.2, Gradle 8.13, JDK 21, Windows): both ABIs bit-identical in two checkouts at different paths. The first run differed only in `libbluetooth_video_native.so`, because libopus `__FILE__`/assert strings embedded the checkout path; `app/src/main/cpp/CMakeLists.txt` now maps the libopus, libaom, app-source and build directories with `-ffile-prefix-map`. Not yet verified: a Linux build (the F-Droid buildserver, JDK 17, Linux NDK) against a Windows build, and the signed APK; so F-Droid still signs its own build and `Binaries` stays commented out in the recipe. A release signed with our key must be re-checked with `compare` against the F-Droid-built APK before enabling it.
- **Making a release.** `python tools/make_release.py` (clean tree, signing key present, fingerprint must match) writes `release/<versionName>-<versionCode>/`: the signed APKs, `SHA256SUMS`, `signing-cert.sha256`, `apksigner-certs.txt`, `SOURCE-MANIFEST.txt` and a `RELEASE-NOTES.md` template to finish by hand. Nothing is uploaded. The checklist is in [TESTING.md](TESTING.md) under "Release checklist".
- **Application ID.** The app name is MeshLive and the application ID is `io.github.meshlive.app` (the draft
  F-Droid metadata is `distribution/fdroid/io.github.meshlive.app.yml`). The benchmark harness uses the same ID;
  set `MESHLIVE_APP_ID` to drive a build with another ID. The Kotlin package, `namespace`, proguard rules and the
  FileProvider authority (`${applicationId}`) need no change; `-PappId=<id>` builds a trial APK with another ID. A
  new ID is a different app to Android: installs of the old ID are not updated, and the signing key stays the same.
  For a later name change edit `app_name` in `app/src/main/res/values/strings.xml`, `RELEASE_BASENAME` in
  `tools/make_release.py` and `fastlane/metadata/android/en-US/title.txt`.
- **Native sources for a clean checkout.** libaom and libopus are not in Git: `python tools/fetch_native_sources.py`
  downloads the pinned versions (see the README, "Build from source"). Needs NDK 28.2.13676358, CMake
  3.22.1, JDK 21 and Perl.
- **Dependency licences.** [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md), "Runtime dependency licences". All
  Apache-2.0 except libaom (BSD-2-Clause plus the AOM patent licence) and libopus (BSD-3-Clause); nothing
  proprietary.
- **Open items before an F-Droid request.** public repository and tag; a final non-`com.example` application id; real screenshots; run the split-APK recipe through `fdroid build` on a buildserver image (`fdroid readmeta`/`lint` of the draft pass with fdroidserver 2.4.5; the srclib `distribution/fdroid/srclibs/libaom.yml` goes to fdroiddata too) and cut a release built with AGP 8.13.2 or newer (v0.1.0-beta used 8.13.0-rc01), then point the recipe's `commit` at that tag.

### Diagnostics block (contract for "Export diagnostics")

The About screen's Export diagnostics button copies one text block that users paste into the GitHub issue forms;
`python tools/parse_user_diagnostics.py report.txt` turns it into a DeviceFamilyPolicy evidence suggestion (a draft
for a human to check against official sources; it never edits the registry and a user report never raises
confidence by itself). The parser is an allowlist and rejects any other field.

Never exported: media, message content, contact or peer names, Bluetooth or Wi-Fi addresses, full identity or session
keys, account or location data. Identity is only a non-reversible short hash: the first 6 bytes (12 lowercase hex) of
SHA-256 over `"bluetoothvideo-diag-v1:"` + the phone's own public node key; each peer is the same hash of that peer's
public node key.

Layout: a header line `=== BLUETOOTH-VIDEO DIAGNOSTICS v1 ===`, the lines below in any order, then
`=== END (lines=N) ===` where N counts the lines between (detects truncated pastes). At most 16 KB.

| Line | Content |
| --- | --- |
| `app.version`, `app.build`, `app.build_type` | versionName, versionCode, `release` or `debug` |
| `app.signing_sha256` | SHA-256 of the installed APK's signing certificate, colon-separated uppercase (`PackageManager` signing info) |
| `device.manufacturer`, `device.model`, `device.soc` | `Build.MANUFACTURER`, `Build.MODEL`, SoC manufacturer + model (`Build.SOC_*`, API 31+, else `unknown`) |
| `device.android`, `device.sdk`, `device.security_patch` | release string, API level, `Build.VERSION.SECURITY_PATCH` |
| `bluetooth.le_2m`, `le_coded`, `le_extended_advertising`, `le_coc` | `true`/`false` from `BluetoothAdapter` (`isLe2MPhySupported`, `isLeCodedPhySupported`, `isLeExtendedAdvertisingSupported`) and API level 29+ for CoC |
| `bluetooth.le_max_adv_data_length` | optional, `getLeMaximumAdvertisingDataLength` |
| `identity.short_hash` | the phone's own short hash (above) |
| `device_policy` | `matched=id1,id2 \| registry=N`: ids of DeviceFamilyPolicy entries that match this phone (`matched=none` if none) and the registry size |
| `conn` (up to 50, newest last) | ` \| `-separated `key=value`: `t` (UTC ISO-8601), `transport` (`gatt`/`coc`/`classic`), `role` (`central`/`peripheral`), `peer` (short hash), `status` (stack status code or `ok`), `phy_req`, `phy_obs` (`1M`/`2M`/`coded`), `mtu_req`, `mtu_obs`, `interval_req_ms`, `interval_obs_ms`, `outcome` (`connected`/`failed`/`timeout`/`disconnected`), `reason` (short token) |
| `adaptive_phy` (up to 50) | `t`, `link`, `from`, `to`, `reason` (as the `BENCHMARK adaptive_phy` line), `kbps`, `blocked` |
| `codec` (up to 20) | `t`, `role` (`tx`/`rx`), `mime`, `component` (codec name), `hw` (`true`/`false`), `req` and `eff` (requested and effective `WxH@fps/kbps`) |

Example start: `app.version: 1.0`, `app.build: 1`, `device.model: SM-S901B`, `device_policy: matched=none | registry=4`,
`conn: t=2026-10-10T10:00:00Z | transport=coc | role=central | peer=abcdef012345 | status=42 | phy_req=2M | phy_obs=1M | outcome=failed | reason=phy_unavailable`.

### Build notes

Extra notes for the build steps in the [README](../README.md#build-from-source).

- **Flavours.** Two distribution flavours (`app/build.gradle.kts` `productFlavors`): `github` (what the release page ships, with the opt-in updater and `REQUEST_INSTALL_PACKAGES`) and `fdroid` (what the F-Droid recipe builds; no updater, no install permission). Name the flavour in build tasks: `:app:assembleGithubDebug` (APK `app/build/outputs/apk/github/debug/app-github-debug.apk`, the one `tools/deploy_build.py` installs), `:app:assembleGithubRelease`, `:app:assembleFdroidRelease`. `:app:testDebugUnitTest` is an alias (AGP defines none once flavours exist): the github flavour's unit tests run once, and the fdroid flavour's main and test code must compile; one class: `:app:testGithubDebugUnitTest --tests <class>`. The plain `assembleDebug`/`assembleRelease` aggregates would compile the native libraries twice, so avoid them. `:app:processGithubReleaseManifest :app:processFdroidReleaseManifest` followed by `python -B -m pytest tools/tests/test_flavour_manifests.py` checks the merged manifests.
- **Application ID.** `io.github.meshlive.app`. The Kotlin package and `namespace` stay `com.example.bluetoothtest`,
  so launch components are `io.github.meshlive.app/com.example.bluetoothtest.MainActivity`. See
  "Distribution and releases" above.
- **Pinned toolchain.** The Gradle wrapper checks `distributionSha256Sum` against every Gradle download.
  `gradle/verification-metadata.xml` holds the SHA-256 of every plugin and dependency artifact and Gradle fails a build
  whose download differs. `buildscript-gradle.lockfile` (plugins) and `app/gradle.lockfile` (every `:app`
  configuration) lock resolved versions. To change a version, update them together: (1) for Gradle, change
  `distributionUrl` and set `distributionSha256Sum` to the official `.sha256` for that distribution; (2) for
  dependencies, edit `gradle/libs.versions.toml` and run
  `./gradlew --no-configuration-cache --write-locks --write-verification-metadata sha256 help :app:assembleGithubDebug :app:assembleFdroidDebug :app:assembleGithubRelease :app:assembleFdroidRelease :app:testDebugUnitTest`
  (add any other task you run, because tools such as AAPT2 and lint are resolved only when their task runs; a new
  Android Gradle Plugin needs the command on each OS or the published checksums added by hand); (3) review the lockfile
  and verification diff and confirm an `--offline` build.
- **Release logging.** `proguard-release-logs.pro` applies to the minified release build only: R8 removes
  `Log.v/d/i`, so release logcat keeps warnings, errors and the `BENCHMARK device_policy` line. Debug builds keep every
  line, including `BENCHMARK` lines.
- **Desktop receiver.** The Windows C# receiver implements the older video-only `BTV2` path; current `BTV3` audio and
  normal discovery integration are unfinished. Its offline cryptographic self-test is
  `dotnet run --project receiver/BluetoothVideoReceiver.csproj -- --self-test`.
- **Test-video generator.** `tools/video-stress-generator/` (Python package `videostress`; run
  `python -m videostress --help` from that folder, or set `VIDEO_STRESS_GENERATOR` to point elsewhere).
- **Benchmarks.** Copy `tools/benchmark_devices.example.json` to `tools/benchmark_devices.json` (git-ignored) and fill
  in your own phones' adb serials and aliases; `tools/benchmark_scope.json` lists the reference aliases. See
  [TESTING.md](TESTING.md).

## Switches and experiments

Behaviour that is not the plain default falls into four kinds: experiments that stay off until their phone tests pass, the losing arm of an A/B comparison kept for later comparison, optional features the user can turn on under Settings > Features, and DEBUG-only tunables used by the benchmark harness. The rule behind this (design rule, 2026-10-08/09): an experiment, or the losing arm of an A/B comparison, is kept, off by default and labelled Experimental, unless it is impossible by first principles. A "control arm" switch restores the previous behaviour exactly, so an A/B can run in either direction.

How defaults work. Each promoted feature has one default constant (`CallFeatureDefaults`, `ConnectivityFeatureDefaults`, `ViewerFeatures`, `UpdateFeatureDefaults`; privacy features are on by default for user sessions since 0.2.0-beta) and one switch under Settings > Features. `FeatureCatalog` (pure Kotlin) holds each feature's id, preference key, default, benchmark extra and texts, and the Settings card is generated from it. An untouched switch follows the constant and an explicit choice sticks.

How benchmark extras work. A DEBUG benchmark launch (any `test_*` extra) reads only its extras, default off, so a case ID names exactly one behaviour no matter what the phone's Settings say. Release builds ignore the extras unless an entry names a Settings switch. Benchmark tunables are listed in square brackets in the register; [TESTING.md](TESTING.md) says how a case carries them.

The full list, with each switch's default, extra, purpose and measured status, is the register in [SWITCHES.md](SWITCHES.md). Add a row there whenever a switch is added, promoted or retired.
