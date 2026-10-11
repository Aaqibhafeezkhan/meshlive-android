# User guide

For people who use MeshLive: how to install, update and verify it, what it can do, how to use it, and common questions. Privacy is covered in [PRIVACY.md](PRIVACY.md).

## Install, update and verify

The three-step install is in the [README](../README.md#install). This section covers other ways to install and update, the file list, and how to check a download.

### Other ways to install and update

#### Get updates automatically
 install [Obtainium](https://github.com/ImranR98/Obtainium), then tap
**[Get it on Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/MeshLive/meshlive-android)** (or add `https://github.com/MeshLive/meshlive-android` in Obtainium by hand).
Obtainium checks the releases page for new versions and updates MeshLive for you.

#### Updates inside the app (release-page builds)
 MeshLive can check for a newer version itself, but only after you say yes: the first time you open it, it asks "Check for updates when online?". Until you answer yes it makes no internet request at all. With yes it asks GitHub's release API (`api.github.com`) at most once a day while you are online; GitHub sees your phone's internet address and that you asked about MeshLive, and nothing else is sent (no account, no cookie, no device or version information, no tracker). It downloads nothing until you tap, and it installs a file only if all of these hold: it is signed with the MeshLive release key that is built into the app (the fingerprint under "Verifying a release" below), it is newer than your copy, it is for your phone type, and its SHA-256 equals the one the release publishes. Android then asks you to confirm; nothing is ever installed silently or downgraded. Settings > Features > Check for updates changes your answer (a "yes" restored from a backup onto another phone is dropped and asked again: the answer has a marker in the no-backup folder). While it is off, nothing on the Updates screen can contact GitHub. F-Droid builds contain none of this (F-Droid updates them) and do not ask for the install permission.

#### A friend has a newer version
 When two phones connect, each tells the other which MeshLive release it runs (in the signed greeting phones already exchange; never in what a phone broadcasts). If a phone this phone connected to (a call, a stream or a relay you started; a stranger who connects to you is not counted) says it runs a newer release you see a quiet note on the home screen. It is only that phone's claim: nothing is downloaded or checked until you pick a file yourself. It explains how to get the file from that phone (Help > Share app nearby on its screen, or any other way you like), and **Choose the file** checks it the same way before offering to install it. Settings > Features > Tell me about newer versions nearby turns this off, in both directions.

#### No internet at all

A friend who already has MeshLive can send it to you: in MeshLive's **Help**, they tap **Share this app** and send it with Quick Share, Nearby Share or Bluetooth. The share screen shows the APK hash and the signing-certificate hash, so you can compare them with the release page. An experimental "Share app nearby" screen serves the file over a local Wi-Fi Direct link instead.

#### Other files

On the [releases page](https://github.com/MeshLive/meshlive-android/releases): MeshLive is an
unofficial open-source build (GPL-3.0-or-later), signed with the project's own key, and not on Google Play. Each
release has one APK per CPU type (`MeshLive-<version>-<abi>.apk`); `MeshLive.apk` is the same file as the
arm64-v8a one.

| File | Use it on |
| --- | --- |
| `MeshLive.apk` or `...-arm64-v8a.apk` | Almost every phone since about 2016. **Pick this if unsure.** |
| `...-armeabi-v7a.apk` | Older or 32-bit-only ARM phones (some Android Go models). |
| `...-universal.apk` | Both in one larger file, for when you do not know. |

Updating works only from an APK signed with the same key: do not mix builds from other sources on one phone.

Before installing, check the download as described in [Verify a release](#verifying-a-release).

### Verifying a release

A release is a set of per-ABI APKs signed with the project's release key, published with `SHA256SUMS` and
`SOURCE-MANIFEST.txt`. The signature is what proves who built the APK; the manifest and its attestation prove which
source it was built from. Signing happens only on the maintainer's machine: CI
(`.github/workflows/release-attest.yml`) holds no signing key and builds no APK.

Quick check with the [GitHub CLI](https://cli.github.com/) (replace the file name with the one you downloaded):

```bash
gh attestation verify MeshLive-<version>-universal.apk --repo MeshLive/meshlive-android
gh attestation verify SOURCE-MANIFEST.txt --repo MeshLive/meshlive-android
```

A passing result says this repository's `Release attestation` workflow recorded that exact file digest for a tagged
commit. It does not replace the signing-certificate check below. The full procedure follows.

Signing certificate SHA-256 (also in each release's notes and `signing-cert.sha256`):

```
2E:EF:C0:5A:44:45:22:52:9D:9F:75:D7:EB:CE:FD:08:86:47:07:F2:1A:79:04:1D:B5:1E:BF:F8:36:49:E2:13
```

1. **Certificate.** `apksigner verify --print-certs app.apk` (Android SDK build-tools) must print
   `Signer #1 certificate SHA-256 digest` equal to the fingerprint above, with no other signer. The app's About screen
   shows the same fingerprint for the installed copy (a fuller in-app release check is planned; see [PLAN.md](PLAN.md)). A different fingerprint means a different publisher: do not install it.
2. **APK digest.** `sha256sum -c SHA256SUMS --ignore-missing` against the `SHA256SUMS` file of the release.
3. **Source manifest attestation.** `gh attestation verify SOURCE-MANIFEST.txt --repo MeshLive/meshlive-android`
   confirms the manifest was produced by this repository's workflow. If the APK was uploaded before publishing,
   `gh attestation verify MeshLive-<version>-<abi>.apk --repo MeshLive/meshlive-android` confirms the same digest was
   recorded. The attestation is a record of the digest; trust in the APK itself still comes from step 1.
4. **Source.** `SOURCE-MANIFEST.txt` lists the git commit, tree hash and the SHA-256 of every tracked file. Clone,
   `git checkout <commit>`, then `python tools/make_release.py --manifest-only --output mine.txt` and
   `diff mine.txt SOURCE-MANIFEST.txt` (must be identical). To check one file,
   `git show <commit>:<path> | sha256sum`. The manifest binds the source set; for the APK bytes see the reproducibility
   check under "Distribution and releases" in [ARCHITECTURE.md](ARCHITECTURE.md#distribution-and-releases) (verified for the unsigned APKs on one OS only, so it is not yet a published claim).

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
- **Offline app sharing.** Hand the installed APK to a nearby phone (see [No internet at all](#no-internet-at-all)).
- **Emergency wipe.** Delete the app's keys and data from inside the app (typed confirmation; an optional
  triple-tap gesture).
- **No tracking.** No account, no ads, no analytics, no internet connection for media.
- **Device-family policy registry.** Known Bluetooth-stack and codec quirks are handled in one registry where each
  entry cites official sources and can be switched off.

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
[PLAN.md](PLAN.md)). An experimental LE Coded PHY option is meant for long-range, low-rate links such
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

**Can I give the app to a friend with no internet?** Yes; see [No internet at all](#no-internet-at-all).
