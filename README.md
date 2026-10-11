# MeshLive

**Decentralized, encrypted video calls and live streams over a Bluetooth mesh. No internet needed.**

> **Privacy (0.2.0-beta):** MeshLive now broadcasts a random ID that changes every 15 minutes instead of a fixed
> node-ID prefix, hides both phones' identity keys during calls, and shows a stranger a throwaway identity instead of
> yours. In our first test (four phones, three ID changes) a passive scanner could not link a phone across any of them.
> That is an early test, not a guarantee: a live stream still shows its source's identity to viewers, older app
> versions cannot see updated phones, Bluetooth Classic and the Bluetooth device name on Android 13 and older still
> leak, and anyone whose call you accept learns who you are. [What nearby phones can see](#what-nearby-phones-can-see)
> lists what is hidden, what was measured and what was not.

<p align="center">
  <a href="https://github.com/MeshLive/meshlive-android/releases/latest/download/MeshLive.apk"><img alt="Download MeshLive for Android" src="https://img.shields.io/badge/Download-MeshLive%20for%20Android-000000?style=for-the-badge&logo=android&logoColor=white" height="56"></a>
  &nbsp;
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/MeshLive/meshlive-android"><img alt="Get it on Obtainium" src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="56"></a>
</p>

<p align="center">Android 10 or newer · free · no account · <a href="#install">How to install</a></p>

<p align="center">
  <a href="https://scorecard.dev/viewer/?uri=github.com/MeshLive/meshlive-android"><img alt="OpenSSF Scorecard" src="https://api.scorecard.dev/projects/github.com/MeshLive/meshlive-android/badge"></a>
  <a href="https://github.com/MeshLive/meshlive-android/actions/workflows/codeql.yml"><img alt="CodeQL" src="https://github.com/MeshLive/meshlive-android/actions/workflows/codeql.yml/badge.svg?branch=main"></a>
</p>

MeshLive is an Android app that sends encrypted live camera video and microphone audio directly from one phone to
another over Bluetooth. A viewer can pass the stream on to one more phone, so a broadcast can reach people who are
out of range of the source. Two people can also make a private voice or video call, and (experimentally) leave
each other short encrypted messages. There is no account, no server, no ads and no analytics, and the media never
touches Wi-Fi or mobile data. It is meant for places where the network is down, blocked or not trusted: blackouts,
disasters, censorship, remote areas, crowded events.

MeshLive is free software under the GNU GPL, version 3 or later. It is built by volunteers and has not had an
independent security review. See [Status](#status) before you rely on it.

- [Status](#status) | [Device compatibility](#device-compatibility) | [Features](#features) | [Screenshots](#screenshots) | [Install](#install) |
  [Use the app](#use-the-app) | [Verifying a release](#verifying-a-release) | [Distribution](#distribution)
- [Build from source](#build-from-source) | [How it works](#how-it-works) |
  [What nearby phones can see](#what-nearby-phones-can-see) |
  [Security model and limitations](#security-model-and-limitations) | [FAQ](#faq) |
  [How this was built](#how-this-was-built) | [Contributing](#contributing) | [License](#license)

## Status

MeshLive is **pre-release and experimental**.

- It has been measured on a handful of phones (the reference set below), not on a wide range of devices. Bluetooth
  stacks differ a lot between makers and Android versions, and some phone combinations still fail or are slow. The
  project tracks these openly; see [docs/DECISIONS.md](docs/DECISIONS.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
- Video over Bluetooth is a narrow pipe (a few hundred kbps). Expect 240p-480p video at 15-30 FPS, noticeable delay,
  and pauses when the link is busy. Each relay hop spends airtime twice, so long relay chains of video are weak.
- Many newer features (voice calls, messages, names, call relaying) are **off by default and labelled
  Experimental**. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) lists every one with its switch, default and
  reason, and says which are still untested on phones. The privacy features (rotating discovery IDs, hidden
  identities, throwaway identities for strangers) are **on by default since 0.2.0-beta**, each with its own switch.
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

## Device compatibility

**Please add your phone.** Fill in the [device test report](https://github.com/MeshLive/meshlive-android/issues/new?template=device-report.yml)
(about two minutes) and we will add it to this table. Reports where everything worked are as useful as failures.

How to read it: **pass** = met all of our quality and reliability checks; **partial** = it ran and carried audio or
video in our tests, but missed at least one of our own checks or worked only with some phone combinations; **fail** =
did not work in our tests; **not tested** = we have no recorded test.

So far **no phone has met all of our checks, so there is no "pass" yet.** The results below come from the maintainers'
tests on development builds shortly before 0.1.0-beta (2 to 10 October 2026). Fixes made since then, for example to
stream relaying, have not yet been re-measured on phones. We have not recorded the Bluetooth version or chipset of
these phones; reports from users will fill that in.

**What "partial" and the benchmark FAIL verdicts mean.** In the public
[benchmark data](https://github.com/MeshLive/meshlive-benchmarks), about three in four failed executions (930 of 1,261)
delivered media to every phone that was meant to receive it and then missed at least one deliberately strict gate, for
example first picture within 3 seconds, at least 95% of the requested frame rate in every report interval, zero
freezes, or VMAF 90 against the reference. About 9% reached only some of the receiving phones (mostly relayed
streams) and about 15% never connected, mostly in calls and three quarters of them in runs that include the A15s.
So "partial" means "works, with measurable shortfalls" for most rows; it does not mean the feature is broken.

| Phone | Android | Voice call | Video call | Livestream | Relay through one phone | What we saw |
| --- | --- | --- | --- | --- | --- | --- |
| Samsung SM-S921B (Galaxy S24) | 16 | partial | partial | partial | partial | Voice connected with the M31 and ASUS; some voice calls to the A15s failed to set up reliably |
| Samsung SM-S901E (Galaxy S22) | 16 | not tested | not tested | partial | partial | Middle phone in the S24, S22, A55 relay tests |
| Samsung SM-A556E (Galaxy A55) | 16 | not tested | not tested | partial | partial | Relay chain with S24 and S22 formed, but timing and overload checks failed |
| Xiaomi Redmi 23053RN02I | 15 | not tested | not tested | partial | fail | Relay runs failed after the parent phone had been identified |
| Samsung SM-M315F (Galaxy M31) | 12 | partial | partial | partial | partial | Video call with the ASUS at 500 kbps worked both ways but missed picture and timing checks; its hardware VP9 encoder sends large bursts that can freeze GATT video |
| ASUS ROG Phone 2 (ASUS_I01WD) | 11 | partial | partial | partial | partial | Some calls started from this phone did not finish setting up |
| OPPO CPH2179 (A15s) | 10 | partial | partial | partial | partial | Slow when sending video (0.4 to 6 frames per second in call tests); LE L2CAP CoC stops working after roughly 15 uses in one Bluetooth session (an Android stack limit) |

"Relay through one phone" means a livestream passed on by a viewer phone to a second viewer. In our multi-phone relay
test on 8 October, viewers reached through a relay or a fan-out played at 0.9 to 7.6 frames per second. Relaying a
private call is still a debug feature and is not available in normal use. All three transports (GATT, LE CoC and
Classic) were exercised between these phones; a pass or fail for one phone pair does not carry over to other pairs.
Detailed measurements are in [docs/DECISIONS.md](docs/DECISIONS.md) and the benchmark data linked from this repository.

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
- **Privacy by default (0.2.0-beta).** Rotating discovery IDs, an identity-hiding call handshake and throwaway
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

**Updates inside the app (release-page builds).** MeshLive can check for a newer version itself, but only after you say yes: the first time you open it, it asks "Check for updates when online?". Until you answer yes it makes no internet request at all. With yes it asks GitHub's release API (`api.github.com`) at most once a day while you are online; GitHub sees your phone's internet address and that you asked about MeshLive, and nothing else is sent (no account, no cookie, no device or version information, no tracker). It downloads nothing until you tap, and it installs a file only if all of these hold: it is signed with the MeshLive release key that is built into the app (the fingerprint under "Verifying a release"), it is newer than your copy, it is for your phone type, and its SHA-256 equals the one the release publishes. Android then asks you to confirm; nothing is ever installed silently or downgraded. Settings > Features > Check for updates changes your answer (a "yes" restored from a backup onto another phone is dropped and asked again: the answer has a marker in the no-backup folder). While it is off, nothing on the Updates screen can contact GitHub. F-Droid builds contain none of this (F-Droid updates them) and do not ask for the install permission.

**A friend has a newer version.** When two phones connect, each tells the other which MeshLive release it runs (in the signed greeting phones already exchange; never in what a phone broadcasts). If a phone this phone connected to (a call, a stream or a relay you started; a stranger who connects to you is not counted) says it runs a newer release you see a quiet note on the home screen. It is only that phone's claim: nothing is downloaded or checked until you pick a file yourself. It explains how to get the file from that phone (Help > Share app nearby on its screen, or any other way you like), and **Choose the file** checks it the same way before offering to install it. Settings > Features > Tell me about newer versions nearby turns this off, in both directions.

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

Quick check with the [GitHub CLI](https://cli.github.com/) (replace the file name with the one you downloaded):

```bash
gh attestation verify MeshLive-0.2.0-beta-universal.apk --repo MeshLive/meshlive-android
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
   `git show <commit>:<path> | sha256sum`. The manifest binds the source set; for the APK bytes see the reproducibility
   check under Distribution (verified for the unsigned APKs on one OS only, so it is not yet a published claim).

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
- **Flavours.** `github` is the release page's build (`tools/make_release.py` runs `:app:assembleGithubRelease`; the in-app updater and its install permission live only there); `fdroid` is the F-Droid recipe's (`gradle: [fdroid]`, outputs `app-fdroid-<abi>-release-unsigned.apk`). Both carry the "newer version nearby" note. The APK of one source cannot update the other: F-Droid signs its own build, and the in-app updater is offered only to a copy that carries the release key. Neither flavour embeds a build time, so reproducibility is unchanged (`tools/check_reproducible_build.py` defaults to `:app:assembleFdroidRelease`).
- **Deterministic version.** `versionName`/`versionCode` are literals (no date or git hash). The APK carries no Google dependency-info block (`dependenciesInfo` off). **Reproducible builds.** `python -B tools/check_reproducible_build.py build --tree-a DIR_A --tree-b DIR_B -- -PsplitAbis=true -PenableArmeabiV7a=true -PuniversalApk=false` builds the unsigned release (release key never read; build cache off; sequential; refuses to start when memory use is high) in two clean checkouts at different paths and compares every APK entry; `compare A.apk B.apk` compares two existing APKs ignoring the META-INF signature files, as F-Droid does. Result 2026-10-11 (AGP 8.13.2, Gradle 8.13, JDK 21, Windows): both ABIs bit-identical in two checkouts at different paths. The first run differed only in `libbluetooth_video_native.so`, because libopus `__FILE__`/assert strings embedded the checkout path; `app/src/main/cpp/CMakeLists.txt` now maps the libopus, libaom, app-source and build directories with `-ffile-prefix-map`. Not yet verified: a Linux build (the F-Droid buildserver, JDK 17, Linux NDK) against a Windows build, and the signed APK; so F-Droid still signs its own build and `Binaries` stays commented out in the recipe. A release signed with our key must be re-checked with `compare` against the F-Droid-built APK before enabling it.
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
./gradlew :app:testDebugUnitTest :app:assembleGithubDebug

# 3. Host tool tests (optional).
python -B -m pytest -q tools
```

The debug APK is `app/build/outputs/apk/github/debug/app-github-debug.apk`. Install it on a phone you own with
`adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk`. CMake refuses a libopus other than 1.5.2. `-PperlExecutable=<perl>`
overrides the Perl used for libaom and `-PaomSourceDir=<dir>` points at a libaom checkout somewhere else.

Notes:

- **Flavours.** Two distribution flavours (`app/build.gradle.kts` `productFlavors`): `github` (what the release page ships, with the opt-in updater and `REQUEST_INSTALL_PACKAGES`) and `fdroid` (what the F-Droid recipe builds; no updater, no install permission). Name the flavour in build tasks: `:app:assembleGithubDebug` (APK `app/build/outputs/apk/github/debug/app-github-debug.apk`, the one `tools/deploy_build.py` installs), `:app:assembleGithubRelease`, `:app:assembleFdroidRelease`. `:app:testDebugUnitTest` is an alias (AGP defines none once flavours exist): the github flavour's unit tests run once, and the fdroid flavour's main and test code must compile; one class: `:app:testGithubDebugUnitTest --tests <class>`. The plain `assembleDebug`/`assembleRelease` aggregates would compile the native libraries twice, so avoid them. `:app:processGithubReleaseManifest :app:processFdroidReleaseManifest` followed by `python -B -m pytest tools/tests/test_flavour_manifests.py` checks the merged manifests.
- **Application ID.** `io.github.meshlive.app`. The Kotlin package and `namespace` stay `com.example.bluetoothtest`,
  so launch components are `io.github.meshlive.app/com.example.bluetoothtest.MainActivity`. See
  [Distribution](#distribution).
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

## What nearby phones can see

Anyone with a Bluetooth scanner in range can read what MeshLive broadcasts. They do not need to connect or pair. The
**content** of calls, streams and messages is encrypted. What the announcements reveal depends on the settings and the
app versions below.

**What a phone broadcasts.** When a phone offers a call or a stream it sends a 22-byte Bluetooth LE announcement (or an
equivalent service UUID when the experimental "find iPhones" switch is on) containing:

- a **six-byte ID**. With rotating discovery IDs on (the default since 0.2.0-beta) it is a random-looking 48-bit ID
  that changes every 15 minutes. It is derived from a key that never leaves the phone, so it says nothing about who
  the phone is until someone connects (or holds a contact lease, below). Only the cases under "Still stable or
  exposed" use the fixed node-ID prefix instead (48 bits, the first 6 bytes of the SHA-256 of the phone's identity
  key; it does not change until the identity is wiped or reset);
- the Bluetooth transport it offers, which video codecs it supports, and flags such as "accepting connections",
  "can relay", "call available", "stream available" and "paused";
- the route cost (how many relay hops away the source is);
- for a stream: a 4-byte route token that follows the ID, a sequence number, and the resolution, frame rate and
  bitrate. A phone searching for a lost stream advertises a request that carries its own current ID and a hint that
  only the stream's source can match.

**What rotation changes.** Android changes a phone's own Bluetooth LE address from time to time so that scanners
cannot follow it. Before 0.2.0-beta the prefix was in the payload and stayed the same, so a scanner that recorded
prefixes could link all of those changing addresses to the same phone, across places and days. Now every advertising
set of the app (call-ready presence, stream offer, waiting viewer request, forwarder offer, title pages) stops at each
UTC quarter hour, stays silent for a random 1 to 8 seconds and restarts on a new address with a new ID. A call or
stream already connected keeps its link. In normal use the setting **Reachable for calls** is on, which means the
phone advertises that it can take a call while the app is open and, through its session service, in the background;
turning it off stops that, at the cost that others cannot call you unless your Call screen is open. Rotating IDs are
one switch (Settings > Advanced > Rotating discovery IDs); the identity-hiding handshake and the throwaway identities
for strangers are two more (Settings > Features). All three are on by default and each is its own off-switch.

**Who learns who you are.** With the defaults, a call link carries both phones' identity keys encrypted. A phone that
is not one of your verified contacts meets a throwaway identity instead of your real one. Your real identity goes only
to verified contacts and, after you press Accept, to the person whose call you accept; a declined call reveals nothing.
In our test, Bluetooth logs of two phones showed both phones' identities in the clear in a control call with the
switches off, and none of them in three calls with a stranger (accepted, declined, accepted after an app restart).
Still true: accepting a call from a stranger tells them who you are, and calling a stranger tells whoever answers who
you are; a live stream source still answers the old handshake, so a viewer, or anyone who connects to the source,
learns its identity; and a phone with pseudonyms on refuses the old handshake, so it cannot call, or be called by, an
older version of the app or a phone that has the switches off. Message exchanges and calls relayed through other
phones (experimental) work only with verified contacts while pseudonyms are on.

**What the scanner test measured.** In the combined privacy session of 2026-10-11 (build 3996bfb2, four phones), four phones (Galaxy S24, ASUS, Galaxy M31 and Galaxy A15s) ran
rotating IDs with the identity-hiding handshake and pseudonyms. A passive-tracker tool read full Bluetooth logs of
three of them and the phones' own logs: 66 advertising sets decoded across three quarter-hour boundaries (23:15, 23:30
and 23:45 IST) inside a 40-minute window in which only rotating announcements were on air. Result: **no field
carried over any of the three boundaries**: no ID, route token, capability hint or page field repeated, and no phone
reused its advertising address for a new ID. The only allowed repeat was a stream's own logical token, which follows the
stream. The phones switched IDs within 80 ms of the boundary and stayed silent 1.3 to 7.7 seconds. With the tool's
default parameters the same data raised six findings, each explained and none a failure of rotation (two old-style
announcements from the previous test scene still on air, two address resolutions of a bonded phone pair, and two
restarts of a stream source 11 seconds before a boundary that kept the same ID). The same session ran a 4 minute 51
second call across a boundary, a stream found and viewed by its rotating ID (a viewer waiting across a boundary joined
7 seconds after the source returned), a lease exchange between verified contacts, and calls relayed through one phone.
Calls relayed through two phones formed no route in that session.

**What the test did not measure.** Battery cost; reading the Bluetooth name on the A15s; Bluetooth logs of the A15s and
the whole time of the M31; an active attacker holding a contact's key, fake announcements, relays with the new
handshake, message exchange and abuse limits; and the residuals the tool reports but cannot remove: one phone alone in
an area can be followed by the timing of its announcements, and a few capability bits stay the same across IDs. This was
one session on four phones. Treat it as evidence that the design works, not as proof that nobody can track you.

**Still stable or exposed.**

- **Older app versions** cannot see a phone with rotating IDs, and a rotating phone sees them by their fixed prefix. A
  rotating viewer that joins an older source shows its own fixed prefix while its request is on air (the app says so),
  and anyone can advertise a decoy old-style offer to make a viewer do the same.
- **Visible by handle.** The Settings button advertises the fixed prefix for 10 to 25 minutes so older builds and
  people who know your handle can find your streams. Every such window can be linked to every other window and to the
  minutes just before it. A phone with pseudonyms on still refuses calls from older versions.
- **Live streams** show their source's identity to every viewer, stay linkable by their route token while they run,
  and a stream title, if you set one, is advertised openly.
- **Classic Bluetooth** uses the phone's permanent hardware address. A first Classic contact with an unpaired phone
  shows its name and address while it is discoverable.
- **Paired phones.** A phone that is paired with yours through Android can work out your rotating LE addresses.
- **The Bluetooth device name** can be read by any phone that connects, before the handshake, and is shown by Classic
  lookups. MeshLive never renames the phone on its own. With rotating IDs on, connectable announcements are
  non-discoverable on Android 14 and newer, which makes the phone refuse the name to an unpaired phone that connects
  through them. In the test the S24 (Android 16) sent them that way and the ASUS (Android 11) and M31 (Android 12) did
  not, so on Android 13 and older the name stays readable. Links your phone opens itself still show the name to the
  other side. Settings > Advanced has an option, off by default, to use the neutral name "Phone" instead; it renames the
  phone for everything (headphones, car, watch, PC), shows your original name so you can restore it, and if you
  uninstall the app while it is on the name stays "Phone" until you rename the phone in Android settings.
- **Capability bits.** The codec set, transport and relay hint bits stay the same across IDs: a few bits that can
  single out a phone when few others are nearby.
- **Verified contacts.** After a call you both answered, mutual verified contacts exchange keys that let each recognise
  the other's IDs nearby ("seen nearby, unconfirmed", never a check mark) for up to four weeks or until you forget the
  contact or reset the presence key. A phone read on day D reveals its IDs up to 27 days ahead, so use **Reset presence
  key** after a phone was out of your hands.
- **Legacy chips.** A phone whose Bluetooth chip only supports legacy advertising may keep its address when the ID
  changes and shows a note saying so.
- **Other Bluetooth radios.** Android's own beacons (Find My Device, offline finding, Fast Pair), the phone's
  connection-request addresses, and the size and timing of encrypted data on a link are outside the app's control; they
  show that a call or stream is happening, and roughly of what kind.
- Optional names and stream titles (experimental) are public while they are enabled.

To reduce exposure further: switch Bluetooth off when you do not need it, switch **Reachable for calls** off when you are
not expecting calls, leave names and titles off, use the neutral Bluetooth name option on Android 13 and older, and
verify the people you call often (QR code or safety code).

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
- **Rotating IDs, with limits.** Announcements carry a random ID that changes every 15 minutes (on by default since
  0.2.0-beta), so a passive scanner could not follow a phone across the three ID changes we measured. A live stream
  source's identity, Classic Bluetooth addresses, paired phones, a lone phone's announcement timing and a few capability
  bits remain linkable; see [What nearby phones can see](#what-nearby-phones-can-see).
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
bugs. Please send results with the [device test report](https://github.com/MeshLive/meshlive-android/issues/new?template=device-report.yml) form and, if you can, an Export diagnostics block.

**Why is GATT the default? Why does Classic ask me to pair?** LE GATT works on the most phones without pairing, but it
is the slowest. Classic has the highest throughput but needs a one-time mutual pairing (an experimental option removes
that). LE CoC is experimental because some phone stacks have serious bugs with it.

**Can I give the app to a friend with no internet?** Yes: use Share in the app to send the installed APK through
Quick Share/Nearby Share or a file manager. The share screen shows the APK hash and the signing-certificate hash, so
your friend can compare them with the release page.

## How this was built

MeshLive is built with heavy AI assistance. Most of the code was written by Anthropic's Claude (through Claude Code),
directed by the maintainer, who decides what the product should do, runs the tests on real phones and reviews the
changes. A second AI model reviews critical changes read-only. The details, including the rules the agents follow, are
in [docs/AI_ASSISTED_DEVELOPMENT.md](docs/AI_ASSISTED_DEVELOPMENT.md).

What that does and does not mean:

- **Tested on real hardware.** The reference phones listed under [Status](#status) were used for the calls, streams
  and relays described here. The numbers in [MeshLive/meshlive-benchmarks](https://github.com/MeshLive/meshlive-benchmarks)
  come from real device runs, failures included; they are not simulated.
- **Not independently security-audited.** The authentication, encryption and Bluetooth announcement design have had no
  third-party review. Read [What nearby phones can see](#what-nearby-phones-can-see) and
  [Security model and limitations](#security-model-and-limitations), and do not rely on it where your safety depends
  on it.
- **Open to inspection.** The source is GPL-3.0-or-later, the protocol is written down in
  [docs/PROTOCOL.md](docs/PROTOCOL.md), and releases carry a source manifest and attestation
  ([Verifying a release](#verifying-a-release)).

Found a problem? Open an [issue](https://github.com/MeshLive/meshlive-android/issues/new/choose) or send a
[device test report](https://github.com/MeshLive/meshlive-android/issues/new?template=device-report.yml). For anything
security-related use the private route in [SECURITY.md](SECURITY.md), not a public issue.

## Contributing

Measured results on our reference phones, with the recipes to regenerate the exact test inputs, are published in
[MeshLive/meshlive-benchmarks](https://github.com/MeshLive/meshlive-benchmarks): run the same cases on your build and
compare per case.

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
