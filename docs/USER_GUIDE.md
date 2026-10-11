# User guide

Features, day-to-day use and common questions. For installing see [INSTALL.md](INSTALL.md); for the privacy picture see [PRIVACY.md](PRIVACY.md).

## Features

- **Live video broadcast.** Stream your camera (VP9 or AV1 video, mono Opus audio) to nearby phones. Pause, resume
  and stop with authenticated state. Choose the encoder, rate, resolution, FPS and buffer in Settings; the app shows
  requested and effective values and never changes them silently.
- **Relay chains.** A viewer can pass the stream to one more phone ("Help pass streams and calls to others nearby",
  on by default, pauses itself on low battery or in Battery Saver). Each phone talks only to its parent and its direct
  children, and the receiver checks that the stream really comes from the original source.
- **Private calls.** Voice or video calls between two phones, started from a list of people nearby. Both directions
  are independent; mute, camera off and flip only affect your own side. Call audio focus, ringer and Do Not Disturb
  rules, and a proximity screen-off lock are built in.
- **Three Bluetooth transports.** LE GATT (the default, needs no pairing), Bluetooth Classic (higher throughput,
  one-time pairing) and LE L2CAP CoC (experimental). Discovery is always Bluetooth LE.
- **Authenticated and encrypted.** Every phone has its own signing key. Links use signed handshakes and ordered
  AES-256-GCM records, with a second end-to-end layer so a relay in the middle cannot read or alter your media.
  Compare a QR code or a safety code in person to verify a contact.
- **Experimental: voice-only calls and push-to-talk** (an intercom mode), **store-and-forward messages** (text, voice
  notes, photos between accepted contacts), **names and titles**, and **calls through up to four relays**.
- **Privacy by default .** Rotating discovery IDs, an identity-hiding call handshake and throwaway
  identities for strangers are on; each has a switch in Settings.
- **Offline app sharing.** Hand the installed APK to a nearby phone through the share sheet, with the APK and
  signing-certificate hashes shown on screen. An experimental "Share app nearby" screen serves it over a local
  Wi-Fi Direct link.
- **Emergency wipe.** Delete the app's keys and data from inside the app (typed confirmation; an optional
  triple-tap gesture).
- **No tracking.** No account, no ads, no analytics, no internet connection for media.
- **Device-family policy registry.** Known Bluetooth-stack and codec quirks are handled in one registry where each
  entry cites official sources and can be switched off.
- **Developer extras.** A Windows C# receiver and file sender, a benchmark and scoring harness, and a test-video
  generator (see [ARCHITECTURE.md](ARCHITECTURE.md)).

## Use the app

Install the same APK on the phones. Grant Camera, Microphone and Nearby devices through Android's prompts, and
enable Bluetooth.

- **Broadcast.** Choose a transport and profile in Settings, open Start broadcasting and tap the round camera
  control. Viewers select the stream from Nearby broadcasts. Pause keeps the authenticated link alive; Resume
  continues media; Stop sends an authenticated End.
- **Receive and forward.** Saved sender settings stay independent from reception. Settings "Help pass streams and
  calls to others nearby" decides, at each join, whether a viewer offers one downstream child. It is on by default
  for normal launches and pauses itself at that join below 20% battery (when not charging) or in Battery Saver.
- **Private call.** Open the nearby-people browser on both phones, select the other identity and accept or decline on
  the callee. Capture starts after acceptance. Both endpoints send independently selected video and audio.
- **Transport and pairing.** LE GATT is the pairing-free default. Classic needs the two phones paired once in Android
  Bluetooth settings (a mutual bond: discovery is BLE, whose rotating private addresses only a bond can resolve, and
  Android hides a phone's own Classic address from apps), unless both phones turn on the experimental Classic without
  pairing. LE CoC is experimental. The app does not silently replace the selected transport.

| Setting | Meaning |
| --- | --- |
| Encoder / allocation | Offered encoder and Automatic, CBR, VBR or CBR-FD; encoding and decoding support are separate. |
| Broadcast rate | Requested video rate. The picker maximum is this phone's measured link minus audio and headers (Classic 1500, CoC 1200, GATT 1100 kbps until a first measurement); a choice above the measurement is labelled, never lowered. In Automatic the sender lowers the effective rate only on sustained backlog or late-arrival reports from its own child. |
| Playback buffer | Adaptive local playout ranges: Low 100-600, Balanced 250-1200, High 500-2500 ms, Auto 250-2000 ms (default). The delay grows only while this link delivers late and shrinks after calm; frames and audio are never dropped to stay inside it. Calls keep their own, shorter budgets. |
| Fill / full frame | Aspect-preserving local crop or fit; coded size and rate stay the same. |
| Thermal reduction | Source-local limits; requested versus effective settings stay visible; a critical thermal status stops streaming. |

Normal broadcast audio is mono 48 kHz Opus at 32 kbps. Some phone families carry visible call protections that can
reduce effective video or audio and change buffering (older Qualcomm Android 10-12 stacks are the main case). They
are policy limits that still need measured challenges, not hardware ceilings; the reasons are in
[DECISIONS.md](DECISIONS.md) under "Legacy-family protections".

## FAQ

**Does it need the internet or an account?** No. Nothing is sent to a server. Media travels only over Bluetooth. The
app declares internet and Wi-Fi permissions only for the experimental "Share app nearby" screen, which uses a local
Wi-Fi Direct link and never contacts the internet.

**How far does it reach?** Bluetooth reaches one site, not a city: typically tens of metres in the open and much
less through walls, and it depends on the two phones. We have not yet run a controlled range test (see
[ROADMAP.md](ROADMAP.md)). An experimental LE Coded PHY option is meant for long-range, low-rate links such
as voice, but both phones must support it. Relaying extends range, at a cost in quality.

**How many hops can a stream take?** Each forwarding viewer passes the stream to one more phone, and a source can
serve several direct viewers within its measured link budget. Every hop spends airtime twice, so quality drops
quickly: in a multi-phone relay test (2026-10-08) relayed or fanned-out viewers played at 0.9-7.6 FPS at 300 kbps.
Short chains of low-rate video are realistic; long chains of high-rate video are not. Private calls can cross up to
four relays in the protocol, but the call relay is still a debug feature.

**Does it work on iPhone?** Not yet. An iOS client is in progress in the sibling repository `MeshLive/meshlive-ios`,
and an experimental "find iPhones" discovery switch on Android is ready for it. Until then, both phones must run
Android 10 or newer.

**What about battery?** Bluetooth LE is frugal, but live video keeps the camera, encoder and radio busy, so expect
substantial drain while broadcasting. Forwarding pauses itself below 20% battery (when not charging) or in Battery
Saver. We have not yet run matched unplugged endurance tests, so there is no battery-life figure to quote.

**Which phones were tested?** The seven reference phones in [DEVICES.md](DEVICES.md): Samsung S24, S22, A55 and M31, a
Redmi (Xiaomi), an ASUS and an OPPO A15s, running Android 10 to 16. Other phones may work or may hit Bluetooth-stack
bugs. Please send results with the [device test report](https://github.com/MeshLive/meshlive-android/issues/new?template=device-report.yml) form and, if you can, an Export diagnostics block.

**Why is GATT the default? Why does Classic ask me to pair?** LE GATT works on the most phones without pairing, but it
is the slowest. Classic has the highest throughput but needs a one-time mutual pairing (an experimental option removes
that). LE CoC is experimental because some phone stacks have serious bugs with it.

**Can I give the app to a friend with no internet?** Yes: use Share in the app to send the installed APK through
Quick Share/Nearby Share or a file manager. The share screen shows the APK hash and the signing-certificate hash, so
your friend can compare them with the release page.
