# MeshLive

**Live video, voice calls and messages between nearby phones, over Bluetooth, with no internet.**

<p align="center">
  <a href="https://github.com/MeshLive/meshlive-android/releases/latest/download/MeshLive.apk"><img alt="Download MeshLive for Android" src="https://img.shields.io/badge/Download-MeshLive%20for%20Android-000000?style=for-the-badge&logo=android&logoColor=white" height="56"></a>
  &nbsp;
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/MeshLive/meshlive-android"><img alt="Get it on Obtainium" src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="56"></a>
</p>

<p align="center">Android 10 or newer · free · no account · <a href="#install">How to install</a></p>

MeshLive is an Android app that sends encrypted live camera video and microphone audio directly from one phone to
another over Bluetooth. A viewer can pass the stream on to one more phone, so a broadcast can reach people who are
out of range of the source. Two people can also make a private voice or video call, and (experimentally) leave
each other short encrypted messages. There is no account, no server, no ads and no analytics, and the media never
touches Wi-Fi or mobile data. It is meant for places where the network is down, blocked or not trusted: blackouts,
disasters, censorship, remote areas, crowded events.

MeshLive is free software under the GNU GPL, version 3 or later. It is built by volunteers and has not had an
independent security review. See [Status](#status) before you rely on it.

- [Status](#status) | [Features](#features) | [Screenshots](#screenshots) | [Install](#install) |
  [Use the app](#use-the-app) | [Verifying a release](#verifying-a-release) | [Distribution](#distribution)
- [Build from source](#build-from-source) | [How it works](#how-it-works) |
  [Security model and limitations](#security-model-and-limitations) | [FAQ](#faq) |
  [Contributing](#contributing) | [License](#license)

## Status

MeshLive is **pre-release and experimental**.

- It has been measured on a handful of phones (the reference set below), not on a wide range of devices. Bluetooth
  stacks differ a lot between makers and Android versions, and some phone combinations still fail or are slow. The
  project tracks these openly; see [docs/DECISIONS.md](docs/DECISIONS.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
- Video over Bluetooth is a narrow pipe (a few hundred kbps). Expect 240p-480p video at 15-30 FPS, noticeable delay,
  and pauses when the link is busy. Each relay hop spends airtime twice, so long relay chains of video are weak.
- Many newer features (voice calls, messages, names, rotating discovery IDs, call relaying) are **off by default and
  labelled Experimental**. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) lists every one with its switch, default and
  reason, and says which are still untested on phones.
- Please use it for testing and feedback, **not for situations where your safety depends on it.**

Reference phones used for testing, by alias:

| Alias | Model | Android |
| --- | --- | --- |
| S24 | Samsung SM-S921B | 16 |
| S22 | Samsung SM-S901E | 16 |
| A55 | Samsung SM-A556E | 16 |
| Xiaomi | Redmi 23053RN02I | 15 |
| M31 | Samsung SM-M315F | 12 |
| ASUS | ASUS_I01WD | 11 |
| A15s | OPPO CPH2179 | 10 |

An iPhone client is being developed in a sibling repository, `MeshLive/meshlive-ios`.

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
  notes, photos between accepted contacts), **names and titles**, **rotating discovery IDs**, and **calls through up
  to four relays**.
- **Offline app sharing.** Hand the installed APK to a nearby phone through the share sheet, with the APK and
  signing-certificate hashes shown on screen. An experimental "Share app nearby" screen serves it over a local
  Wi-Fi Direct link.
- **Emergency wipe.** Delete the app's keys and data from inside the app (typed confirmation; an optional
  triple-tap gesture).
- **No tracking.** No account, no ads, no analytics, no internet connection for media.
- **Device-family policy registry.** Known Bluetooth-stack and codec quirks are handled in one registry where each
  entry cites official sources and can be switched off.
- **Developer extras.** A Windows C# receiver and file sender, a benchmark and scoring harness, and a test-video
  generator (see [How it works](#how-it-works)).

## Screenshots

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02-home.png" width="19%" alt="Home">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03-call.png" width="19%" alt="People nearby">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04-watch.png" width="19%" alt="Watch nearby">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05-settings.png" width="19%" alt="Settings">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-welcome.png" width="19%" alt="Welcome">
</p>

## Install

**The easy way (any Android phone):**

1. On your phone, tap **[Download MeshLive](https://github.com/MeshLive/meshlive-android/releases/latest/download/MeshLive.apk)**.
2. Open the downloaded file. If Android asks, allow your browser to **install unknown apps**, then go back and tap
   **Install**.
3. Open MeshLive and allow **Nearby devices**, **Camera** and **Microphone** when asked.

That's it. The phone must run Android 10 or newer. Both people need MeshLive to call or watch each other.

**Get updates automatically:** install [Obtainium](https://github.com/ImranR98/Obtainium), then tap
**[Get it on Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/MeshLive/meshlive-android)** (or add `https://github.com/MeshLive/meshlive-android` in Obtainium by hand).
Obtainium checks this page for new versions and updates MeshLive for you.

**No internet at all?** A friend who already has MeshLive can send it to you: in MeshLive's **Help**, they tap
**Share this app** and send it with Quick Share, Nearby Share or Bluetooth.

**Other files** on the [releases page](https://github.com/MeshLive/meshlive-android/releases): MeshLive is an
unofficial open-source build (GPL-3.0-or-later), signed with the project's own key, and not on Google Play. Each
release has one APK per CPU type (`MeshLive-<version>-<abi>.apk`); `MeshLive.apk` is the same file as the
arm64-v8a one.

| File | Use it on |
| --- | --- |
| `MeshLive.apk` or `...-arm64-v8a.apk` | Almost every phone since about 2016. **Pick this if unsure.** |
| `...-armeabi-v7a.apk` | Older or 32-bit-only ARM phones (some Android Go models). |
| `...-universal.apk` | Both in one larger file, for when you do not know. |

Updating works only from an APK signed with the same key: do not mix builds from other sources on one phone.

Verify before installing (the release page lists the expected values; the full procedure is under
[Verifying a release](#verifying-a-release)):

1. **Hash.** `sha256sum -c SHA256SUMS --ignore-missing` in the download folder (PowerShell:
   `Get-FileHash <file> -Algorithm SHA256`).
2. **Signer.** `apksigner verify --print-certs <file>.apk` (Android SDK build-tools) must print one signer whose
   `certificate SHA-256 digest` equals `2e:ef:c0:5a:...:e2:13`, the fingerprint under "Verifying a release". A
   different fingerprint means a different publisher: do not install it.

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
[docs/DECISIONS.md](docs/DECISIONS.md) under "Legacy-family protections".

## Verifying a release

A release is a set of per-ABI APKs signed with the project's release key, published with `SHA256SUMS` and
`SOURCE-MANIFEST.txt`. The signature is what proves who built the APK; the manifest and its attestation prove which
source it was built from. Signing happens only on the maintainer's machine: CI
(`.github/workflows/release-attest.yml`) holds no signing key and builds no APK.

Signing certificate SHA-256 (also in each release's notes and `signing-cert.sha256`):

```
2E:EF:C0:5A:44:45:22:52:9D:9F:75:D7:EB:CE:FD:08:86:47:07:F2:1A:79:04:1D:B5:1E:BF:F8:36:49:E2:13
```

1. **Certificate.** `apksigner verify --print-certs app.apk` (Android SDK build-tools) must print
   `Signer #1 certificate SHA-256 digest` equal to the fingerprint above, with no other signer. The app's About screen
   shows the same fingerprint for the installed copy (a fuller in-app release check is planned in
   [docs/ROADMAP.md](docs/ROADMAP.md)). A different fingerprint means a different publisher: do not install it.
2. **APK digest.** `sha256sum -c SHA256SUMS --ignore-missing` against the `SHA256SUMS` file of the release.
3. **Source manifest attestation.** `gh attestation verify SOURCE-MANIFEST.txt --repo MeshLive/meshlive-android`
   confirms the manifest was produced by this repository's workflow. If the APK was uploaded before publishing,
   `gh attestation verify MeshLive-<version>-<abi>.apk --repo MeshLive/meshlive-android` confirms the same digest was
   recorded. The attestation is a record of the digest; trust in the APK itself still comes from step 1.
4. **Source.** `SOURCE-MANIFEST.txt` lists the git commit, tree hash and the SHA-256 of every tracked file. Clone,
   `git checkout <commit>`, then `python tools/make_release.py --manifest-only --output mine.txt` and
   `diff mine.txt SOURCE-MANIFEST.txt` (must be identical). To check one file,
   `git show <commit>:<path> | sha256sum`. The build is not claimed to be bit-for-bit reproducible, so the manifest
   binds the source set, not the APK bytes.

Maintainer: `python tools/make_release.py` (clean tree, signing key present, fingerprint must match) writes
`release/<versionName>-<versionCode>/`: the signed APKs, `SHA256SUMS`, `signing-cert.sha256`, `apksigner-certs.txt`,
`SOURCE-MANIFEST.txt` and a `RELEASE-NOTES.md` template to finish by hand. Nothing is uploaded. The checklist is in
[docs/TESTING.md](docs/TESTING.md) under "Release checklist".

## Distribution

This section is for maintainers and packagers.

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
- **Deterministic version.** `versionName`/`versionCode` are literals (no date or git hash). The APK carries no
  Google dependency-info block (`dependenciesInfo` off). Bit-for-bit reproducibility of the APK is not verified (see
  the open item below), so F-Droid signs its own build and the GitHub APK is signed by us.
- **Application ID.** The app name is MeshLive and the application ID is `io.github.meshlive.app` (the draft
  F-Droid metadata is `distribution/fdroid/io.github.meshlive.app.yml`). The benchmark harness uses the same ID;
  set `MESHLIVE_APP_ID` to drive a build with another ID. The Kotlin package, `namespace`, proguard rules and the
  FileProvider authority (`${applicationId}`) need no change; `-PappId=<id>` builds a trial APK with another ID. A
  new ID is a different app to Android: installs of the old ID are not updated, and the signing key stays the same.
  For a later name change edit `app_name` in `app/src/main/res/values/strings.xml`, `RELEASE_BASENAME` in
  `tools/make_release.py` and `fastlane/metadata/android/en-US/title.txt`.
- **Native sources for a clean checkout.** libaom and libopus are not in Git: `python tools/fetch_native_sources.py`
  downloads the pinned versions (see [Build from source](#build-from-source)). Needs NDK 28.2.13676358, CMake
  3.22.1, JDK 21 and Perl.
- **Dependency licences.** [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), "Runtime dependency licences". All
  Apache-2.0 except libaom (BSD-2-Clause plus the AOM patent licence) and libopus (BSD-3-Clause); nothing
  proprietary.
- **Open items before an F-Droid request.** A public repository and tag; a final non-`com.example` application ID;
  real screenshots; verify the split-APK recipe with `fdroid build` and decide whether to chase reproducible builds
  (compare two builds in different directories; native `__FILE__` paths and the NDK's build IDs are the likely
  differences).

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

## Build from source

You need:

- JDK 21
- Android SDK with NDK `28.2.13676358` and CMake `3.22.1` (install both from the SDK Manager)
- Perl on your `PATH` (the libaom build uses it)
- Python 3.11 or newer for the host tools

Steps:

```bash
git clone https://github.com/MeshLive/meshlive-android.git
cd meshlive-android

# 1. Native sources. Downloads the pinned libaom commit (see CMakeLists.txt and THIRD_PARTY_NOTICES.md) and the
#    libopus 1.5.2 release tarball (SHA-256 verified) into the git-ignored reference-repos/ folder.
python tools/fetch_native_sources.py

# 2. Unit tests and a debug APK (use .\gradlew.bat on Windows).
./gradlew :app:testDebugUnitTest :app:assembleDebug

# 3. Host tool tests (optional).
python -B -m pytest -q tools
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it on a phone you own with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`. CMake refuses a libopus other than 1.5.2. `-PperlExecutable=<perl>`
overrides the Perl used for libaom and `-PaomSourceDir=<dir>` points at a libaom checkout somewhere else.

Notes:

- **Application ID.** `io.github.meshlive.app`. The Kotlin package and `namespace` stay `com.example.bluetoothtest`,
  so launch components are `io.github.meshlive.app/com.example.bluetoothtest.MainActivity`. See
  [Distribution](#distribution).
- **Pinned toolchain.** The Gradle wrapper checks `distributionSha256Sum` against every Gradle download.
  `gradle/verification-metadata.xml` holds the SHA-256 of every plugin and dependency artifact and Gradle fails a build
  whose download differs. `buildscript-gradle.lockfile` (plugins) and `app/gradle.lockfile` (every `:app`
  configuration) lock resolved versions. To change a version, update them together: (1) for Gradle, change
  `distributionUrl` and set `distributionSha256Sum` to the official `.sha256` for that distribution; (2) for
  dependencies, edit `gradle/libs.versions.toml` and run
  `./gradlew --no-configuration-cache --write-locks --write-verification-metadata sha256 help :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest`
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
  [docs/TESTING.md](docs/TESTING.md).

## How it works

In short: discovery is Bluetooth LE advertising. A source encodes once (hardware VP9/AV1 through MediaCodec, or an
optimized software AV1 encoder built on libaom), adds Opus audio, seals every record twice (hop-by-hop and end to
end) and writes it to one ordered queue per connected child. A viewer decrypts, decodes and plays it on a clock that
absorbs bursty Bluetooth delivery by growing its delay instead of dropping frames. A viewer that forwards copies the
same ciphertext to one child. Private calls run two such pipelines on one link. Phone-specific workarounds live in a
registry with official sources and off-switches.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): modules, transports, sender and receiver pipelines, relay chains,
  calls, identity, store-and-forward, the device-family policy registry, diagnostics, host tools, and the register of
  every experimental switch.
- [docs/PROTOCOL.md](docs/PROTOCOL.md): wire formats, handshakes, key derivation, failure rules.
- [docs/TESTING.md](docs/TESTING.md): what is measured, the acceptance gates (the same for every phone) and how to run
  the harness.
- [docs/DECISIONS.md](docs/DECISIONS.md): measured reasons for the policies, with the comparisons.

## Security model and limitations

What MeshLive protects, and what it does not. The full rules are in [docs/PROTOCOL.md](docs/PROTOCOL.md); to report a
problem privately, read [SECURITY.md](SECURITY.md).

**Encrypted and authenticated.**

- Every phone has a persistent P-256 signing identity in the Android Keystore; there are no accounts or servers.
- Every Bluetooth connection starts with a signed handshake with fresh ephemeral keys. After it, records are sealed
  with AES-256-GCM, ordered and replay-protected. Tampering, replay, reordering and a wrong key fail closed. There is
  no plaintext mode and no silent downgrade.
- Media and call signals are additionally sealed end to end between the source and each viewer (or the two call
  endpoints). A relay in the middle forwards ciphertext without being able to read, forge or reorder it. A viewer that
  forwards is an admitted viewer and can decrypt its own copy.
- Store-and-forward message content is sealed to the recipient and stored encrypted at rest. The in-app emergency wipe
  deletes the keys first.

**What is not hidden.**

- **Discovery is public.** Anyone with a Bluetooth scanner can see that a MeshLive phone is nearby, whether it offers a
  stream or accepts calls, and the stream's rough size and bitrate.
- **A stable ID by default.** The announcement carries a fixed 6-byte node prefix, so a passive scanner can follow a
  phone between places. **Rotating discovery IDs are experimental and off by default**, cover only call-ready presence
  for strangers (streams, relays and viewers still use the fixed prefix), and are not yet tested on phones.
- **Bluetooth metadata.** Bluetooth names and addresses, the existence of a connection, and the size and timing of
  encrypted records are visible to a radio observer and to every relay on the path. That reveals "a call is
  happening" and often "voice only" versus "video". A first Classic contact with an unpaired phone can expose its name
  and address while it is discoverable.
- **No authenticated radio link on LE.** LE GATT and CoC (and Classic without pairing) have no Android-level
  authenticated link key. MeshLive's own handshakes do not depend on one, so an active attacker between the phones
  cannot read or forge content, but can drop, delay or jam traffic.
- **A prefix is not an owner.** Discovery binds a connection to a 48-bit prefix of the node ID, which a determined
  attacker could try to grind a key for. Compare the QR code or safety code with a contact in person; a verified
  contact whose key changes is refused by default.
- **Relays can disrupt.** A malicious relay or advertiser can drop, delay or refuse a stream or call, attract it to a
  longer route or report a false hop count, but it cannot impersonate an endpoint or open the media.
- **Names and titles** (experimental) are signed and sent inside the encrypted session, but a stream title is also
  advertised publicly while the option is on.

**Not done yet.**

- **No independent security audit.** The primitives are standard (P-256, ECDH, HKDF-SHA-256, AES-256-GCM), but the
  protocol composed from them has not been reviewed by outsiders and is not a certification.
- Human ownership verification beyond comparing codes, live group re-key or revocation, and trust-on-first-use pinning
  of unverified peers are open work ([docs/ROADMAP.md](docs/ROADMAP.md)).
- The emergency wipe cannot undo flash remanence, copies outside the app (backups, screenshots, the system log, other
  people's phones), or malware already on the phone.
- Video over Bluetooth is slow and its recovery after link loss has a gap; do not depend on it for safety.

## FAQ

**Does it need the internet or an account?** No. Nothing is sent to a server. Media travels only over Bluetooth. The
app declares internet and Wi-Fi permissions only for the experimental "Share app nearby" screen, which uses a local
Wi-Fi Direct link and never contacts the internet.

**How far does it reach?** Bluetooth reaches one site, not a city: typically tens of metres in the open and much
less through walls, and it depends on the two phones. We have not yet run a controlled range test (see
[docs/ROADMAP.md](docs/ROADMAP.md)). An experimental LE Coded PHY option is meant for long-range, low-rate links such
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

**Which phones were tested?** The seven reference phones in [Status](#status): Samsung S24, S22, A55 and M31, a
Redmi (Xiaomi), an ASUS and an OPPO A15s, running Android 10 to 16. Other phones may work or may hit Bluetooth-stack
bugs. Please send results with the "device compatibility" issue form and, if you can, an Export diagnostics block.

**Why is GATT the default? Why does Classic ask me to pair?** LE GATT works on the most phones without pairing, but it
is the slowest. Classic has the highest throughput but needs a one-time mutual pairing (an experimental option removes
that). LE CoC is experimental because some phone stacks have serious bugs with it.

**Can I give the app to a friend with no internet?** Yes: use Share in the app to send the installed APK through
Quick Share/Nearby Share or a file manager. The share screen shows the APK hash and the signing-certificate hash, so
your friend can compare them with the release page.

## Contributing

Bug reports, device compatibility results, documentation and code are all welcome.

- [CONTRIBUTING.md](CONTRIBUTING.md): setup, workflow and what makes a good change.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md): how we treat each other.
- [SECURITY.md](SECURITY.md): how to report a vulnerability privately.
- [docs/TESTING.md](docs/TESTING.md): scope, acceptance gates and how to measure.
- [docs/AI_ASSISTED_DEVELOPMENT.md](docs/AI_ASSISTED_DEVELOPMENT.md): how AI coding agents are used in this project
  and the rules they follow.
- [docs/ROADMAP.md](docs/ROADMAP.md): unfinished work and future directions.

## License

Copyright (C) 2026 The MeshLive Authors.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

Third-party components keep their own licences (BSD-3, BSD-2, Apache-2.0 and others); see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The copyright holders are listed in [AUTHORS](AUTHORS). The
test-video generator in `tools/video-stress-generator/` is licensed under the MIT license.
