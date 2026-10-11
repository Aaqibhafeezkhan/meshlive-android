# Contributing to MeshLive

Thank you for wanting to help. MeshLive is a volunteer project that sends live video, voice calls and messages
over Bluetooth with no internet, so the most valuable contributions are usually the ones that make it work on more
phones, behave better on a bad link, or are easier to trust. You do not need to write code to help: a clear bug
report or a device compatibility result is a real contribution.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). If you found a security problem, do
not open a public issue; read [SECURITY.md](SECURITY.md) first.

## Where things are documented

| You want to know | Read |
| --- | --- |
| What the app does, how to install and verify it | [README.md](README.md) |
| How it is built, and the register of experimental switches | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| The wire format and security rules | [docs/PROTOCOL.md](docs/PROTOCOL.md) |
| Test scope, acceptance gates, how to measure | [docs/TESTING.md](docs/TESTING.md) |
| Why a measured decision went the way it did | [docs/DECISIONS.md](docs/DECISIONS.md) |
| What is unfinished | [docs/ROADMAP.md](docs/ROADMAP.md) |
| Rules for people and coding agents working in the repo | [AGENTS.md](AGENTS.md) |

## Development setup

You need:

- JDK 21
- Android SDK with NDK 28.2 (`28.2.13676358`) and CMake 3.22.1 (install both from the SDK Manager)
- Perl on your `PATH` (the libaom build uses it)
- Python 3.11 or newer for the host tools
- Optional: the .NET SDK, only for the C# desktop receiver in `receiver/` and the file sender in `tools/`

Steps:

1. Clone the repository and open it in Android Studio, or use the command line.
2. Fetch the pinned native sources. They are not stored in Git:

   ```
   python tools/fetch_native_sources.py
   ```

   This downloads libaom (the commit pinned in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)) and libopus 1.5.2
   (release tarball, SHA-256 verified) into `reference-repos/`, which is ignored by Git.
3. Build and run the unit tests:

   ```
   ./gradlew :app:testDebugUnitTest :app:assembleGithubDebug
   ```

   On Windows use `gradlew.bat`. Gradle verifies every dependency against `gradle/verification-metadata.xml` and the
   lockfiles. If you change a dependency, update the lockfiles and verification metadata together (the steps are in
   the Build section of the [README](README.md)).
4. Run the host tool tests:

   ```
   python -B -m pytest -q tools
   ```

5. Optional: the desktop receiver self-test, which needs no Bluetooth:

   ```
   dotnet run --project receiver/BluetoothVideoReceiver.csproj -- --self-test
   ```

The Android application ID is `io.github.meshlive.app` (the Kotlin package and Gradle `namespace` remain
`com.example.bluetoothtest`). The host tools read `MESHLIVE_APP_ID` if you need to drive a build with another ID.
Do not change the ID in a feature branch: a new ID is a different app to Android.

### Testing on real phones

Streaming, Bluetooth and codec behaviour cannot be proven on an emulator. If you have phones, copy
`tools/benchmark_devices.example.json` to `tools/benchmark_devices.json` (Git ignores the copy) and fill in your own
phones' adb serials and aliases. `tools/benchmark_scope.json` lists the reference phones the project aims to cover.
The workflow, the acceptance gates and the measurement rules are in [docs/TESTING.md](docs/TESTING.md). You do not
need every reference phone to contribute; say which phones you used.

## Reporting bugs and compatibility results

Use the forms in [.github/ISSUE_TEMPLATE/](.github/ISSUE_TEMPLATE/) (the **New issue** button offers them):

- **Bug report**: something failed (connection, video, audio, crash, pairing). Paste the in-app diagnostics block
  (About, then Export diagnostics). It holds no media, messages, contact names or full identity keys.
- **Device compatibility report**: what worked on which phone pair, including when everything worked. These reports
  feed the device-family registry, so good news is useful too.
- **Feature request**: describe your situation (who, where, how many phones, what connectivity), not only the feature.

A good report names both phones (model and Android version), the app version and signing fingerprint from About, the
transport in use, what you expected and what happened, and how often. Please do not attach photos, videos, recordings
or screenshots of other people.

## Before you open a pull request

For anything bigger than a typo, open an issue first and describe the problem you see. It is cheaper to agree on the
approach before the code exists.

### Coding expectations

- **Reproduce first.** Show the defect (a failing test, a log, a measurement) before changing code.
- **Narrow fixes.** Change the smallest thing that causes the problem. Find the root cause rather than tuning a
  constant until the symptom hides. Trace the request, the attempt or session, the process, and the pipeline stage
  that is responsible before you claim a cause.
- **Focused regressions.** Add a test that fails without the fix and passes with it. Skip tests that only restate
  the implementation.
- **Measure on real phones** when the change touches streaming, Bluetooth, codecs, audio or lifecycle. Freeze one
  APK, then compare before and after at the same settings. Keep picture quality, frame timing, lifecycle, Camera2,
  acoustic quality and latency as separate measurements.
- **The same acceptance gates for every phone.** A phone does not get an easier pass because it is old, slow or
  popular. Eligibility follows the capabilities a phone actually offers.
- **Never silently change a requested bitrate, frame rate, resolution, encoder, audio setting, transport or buffer
  to make a test pass.** If the effective value differs from the requested one, say so in the result.
- **Keep the safety guards.** Authentication, ordered encryption, hop-local control (each phone talks only to its
  parent and direct children), admission control and overload protection stay in place. If you think a limit is
  too low, show measured evidence rather than removing it.
- **Do not call something passed without measured evidence.** Unmeasured stays unmeasured. A fallback path is not a
  pass for the path that was requested.

### Experiments and alternatives

New behaviour that is not yet proven ships **default off**, labelled **Experimental**, and gets a row in the
"Experimental, alternative and optional behaviour" register in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) with its
switch and the reason it exists. When an A/B comparison picks a winner, the losing arm stays switchable (off by
default and labelled) instead of being deleted, unless it is impossible by first principles. Update the register
whenever you add, promote or retire a switch.

### Device-family policies

Specializing behaviour for a family of phones (SoC family, vendor skin, Bluetooth stack or Android version, codec
component, or a combination) is welcome when the entry is honest about its basis. Each entry in the device-family
policy registry (`DeviceFamilyPolicy.kt`, with `tools/device_policy_registry.json` for the host tools) needs:

- a matcher and the behaviour it changes,
- official sources: AOSP source or commits, the Android CDD or API documentation, vendor developer documentation, or
  Bluetooth SIG specifications,
- test data from more than your own phone,
- a confidence level, and an off switch.

The acceptance gates stay identical for every phone. A policy changes how the app reaches the gate, not where the
gate is.

### Protocol and security changes

Anything that changes the wire format, identity, encryption, admission or message storage (for example group calls
or new store-and-forward behaviour) needs a written design in [docs/PROTOCOL.md](docs/PROTOCOL.md) and a review
before code is merged. Open an issue with the proposed design first. Do not post exploit details publicly; use the
private route in [SECURITY.md](SECURITY.md).

### Documentation

Keep documents in their owners (see the table above) and do not add new top-level pages for one change. Replace
outdated text instead of appending to it. Explain a changed assumption in plain language: what was wrong, what
changed, why, and what evidence supports it.

## Licensing and sign-off

MeshLive is licensed under **GPL-3.0-or-later** ([LICENSE](LICENSE)). Contributions are accepted under the same
licence: inbound equals outbound, and you keep your copyright. Copyright lines read "The MeshLive Authors", listed
in [AUTHORS](AUTHORS). The test-video generator in `tools/video-stress-generator/` is MIT-licensed and keeps its own
licence file.

- Sign off every commit with `git commit -s`. That adds a `Signed-off-by:` line and certifies the
  [Developer Certificate of Origin 1.1](https://developercertificate.org/): you wrote the change or have the right to
  submit it under this licence.
- Do not copy code, assets, test media or protocol text from another project unless its licence is compatible with
  GPL-3.0-or-later. Keep the original copyright and licence notice, and add the component to
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Test media must be generated or licensed for redistribution. Do not commit footage of real people.
- Optional tooling with restrictive licences (for example the PESQ scorer in
  `tools/requirements-audio-pesq.txt`) stays opt-in and out of the app.

## AI-assisted contributions

AI-assisted work is welcome. It goes through exactly the same review gates as any other change, and you are
responsible for every line you submit. Say in the pull request when AI tools did a substantial part of the work. Read
[docs/AI_ASSISTED_DEVELOPMENT.md](docs/AI_ASSISTED_DEVELOPMENT.md) for how the project uses coding agents and for
what to keep out of prompts.

## Privacy rules for contributions

The repository is public and permanent. Never commit, paste into an issue, or include in a test fixture:

- phone serial numbers, adb device names, Bluetooth or Wi-Fi addresses, or real node IDs and key fingerprints,
- private photos, voice recordings or video, or screenshots of other people,
- personal file paths, usernames, email addresses or phone numbers,
- signing keys, keystore properties files or passwords.

Use placeholders such as `AA:BB:CC:00:00:01` for addresses and `SERIAL` for adb serials. Benchmark evidence (raw
captures, traces, run folders) stays out of Git: the repository tracks source, documents, tools and small benchmark
definitions only. Share summaries (numbers, phone aliases and models, short build hashes) in the pull request or
issue instead.

## Pull request checklist

- [ ] The problem is described, with evidence that it is real.
- [ ] The change is narrow, and a focused regression covers it.
- [ ] `./gradlew :app:testDebugUnitTest :app:assembleGithubDebug` and `python -B -m pytest -q tools` pass.
- [ ] Streaming-related changes name the phones, the frozen APK hash and the same-settings comparison.
- [ ] New behaviour is default off, labelled Experimental, and registered in docs/ARCHITECTURE.md.
- [ ] Protocol or security changes link to a reviewed design in docs/PROTOCOL.md.
- [ ] Documents that own the changed behaviour are updated.
- [ ] Commits are signed off (`git commit -s`), and the diff contains no private data.
- [ ] Substantial AI assistance is disclosed.

Thank you, and welcome.
