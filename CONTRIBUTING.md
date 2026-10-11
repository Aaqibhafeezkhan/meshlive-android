# Contributing to MeshLive

For anyone who wants to report a bug, share a device result, improve the docs or send code. You do not need to write code to help: a clear bug report or a device compatibility result is a real contribution.

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). If you found a security problem, do not open a public issue; use [SECURITY.md](SECURITY.md).

Which document answers what is listed under [Documentation in the README](README.md#documentation).

## Report a bug or a device result

Use the forms behind **New issue** ([templates](.github/ISSUE_TEMPLATE/)):

- **Bug report**: something failed (connection, video, audio, crash, pairing). Paste the in-app diagnostics block (About, then Export diagnostics). It holds no media, messages, contact names or full identity keys.
- **Device compatibility report**: what worked on which phone pair, including when everything worked.
- **Feature request**: describe your situation (who, where, how many phones, what connectivity), not only the feature.

Name both phones (model and Android version), the app version and signing fingerprint from About, the transport, what you expected, what happened and how often. Do not attach photos, videos, recordings or screenshots of other people.

## Build and test

You need JDK 21, the Android SDK with NDK `28.2.13676358` and CMake 3.22.1, Perl, and Python 3.11 or newer. Only the C# desktop receiver in `receiver/` needs the .NET SDK.

```
python tools/fetch_native_sources.py      # pinned libaom and libopus; not stored in Git
./gradlew :app:testDebugUnitTest :app:assembleGithubDebug     # gradlew.bat on Windows
python -B -m pytest -q tools              # host tool tests
```

Gradle verifies every dependency against `gradle/verification-metadata.xml` and the lockfiles; if you change a dependency, update both together ([build notes](docs/ARCHITECTURE.md#build-notes)). The application ID is `io.github.meshlive.app` (the Kotlin package stays `com.example.bluetoothtest`); do not change it in a feature branch, because a new ID is a different app to Android.

Streaming, Bluetooth and codec behaviour cannot be proven on an emulator. To test on your own phones, copy `tools/benchmark_devices.example.json` to `tools/benchmark_devices.json` (Git ignores it) and list your phones. Scope, acceptance gates and workflow are in [docs/TESTING.md](docs/TESTING.md). You do not need every reference phone; say which ones you used.

## Send a pull request

For anything bigger than a typo, open an issue first so the approach is agreed before the code exists.

- **Reproduce first** with a failing test, a log or a measurement. **Fix narrowly**, at the root cause, and add a focused regression that fails without the fix.
- **Measure on real phones** when you touch streaming, Bluetooth, codecs, audio or lifecycle: one frozen APK, the same settings before and after. Never silently change a requested bitrate, frame rate, resolution, encoder, audio setting, transport or buffer to make a test pass.
- **Every phone meets the same gates.** Keep the safety guards: authentication, ordered encryption, hop-local control, admission and overload protection. If you think a limit is too low, show measured evidence.
- **Do not call something passed without evidence.** Unmeasured stays unmeasured; a fallback is not a pass for the requested path.
- **Experiments ship default off**, labelled Experimental, with a row in the [switch register](docs/SWITCHES.md). The losing arm of an A/B stays switchable.
- **Device-family policies** (SoC, vendor skin, Bluetooth stack, codec component) need a matcher, official sources (AOSP, CDD or API docs, vendor docs, Bluetooth SIG), test data from more than your own phone, a confidence level and an off switch, in `DeviceFamilyPolicy.kt` and `tools/device_policy_registry.json`.
- **Protocol or security changes** (wire format, identity, encryption, admission, message storage) need a written design in [docs/PROTOCOL.md](docs/PROTOCOL.md) and a review before merge. Do not post exploit details publicly.
- **Docs**: keep each fact in one document and replace outdated text instead of appending. Explain a changed assumption in plain language: what was wrong, what changed, why, and the evidence.

The full working rules for people and coding agents are in [AGENTS.md](AGENTS.md).

## Using AI tools

AI-assisted work is welcome and goes through the same review as any other change. Most of MeshLive was written with Anthropic's Claude under human direction, and critical changes also get a read-only second review by another model; a finding is a lead to verify, not an instruction.

- You are responsible for every line. Read, understand and run what the tool wrote.
- Say in the pull request when AI did a substantial part (a sentence: which tool, for what).
- An agent's claim that it "tested" something is not evidence until you see the logs or numbers. Re-read its documentation against the evidence; agents overclaim.
- Check licences: do not submit code you cannot license under GPL-3.0-or-later, and keep attribution for adapted code.
- Keep secrets and private data out of prompts (see the privacy rules below). Give the tool only the files and phones it needs, and review any cleanup it proposes before it deletes anything.
- Pull requests with no sign of human review, or whose author cannot explain the change, may be closed.

## Licensing and sign-off

MeshLive is **GPL-3.0-or-later** ([LICENSE](LICENSE)); contributions are accepted under the same licence and you keep your copyright. Copyright lines read "The MeshLive Authors" ([AUTHORS](AUTHORS)). The test-video generator in `tools/video-stress-generator/` is MIT-licensed with its own licence file.

- Sign off every commit with `git commit -s`; this certifies the [Developer Certificate of Origin 1.1](https://developercertificate.org/).
- Do not copy code, assets, test media or protocol text from another project unless its licence is compatible. Keep the original notice and add the component to [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Test media must be generated or licensed for redistribution; never commit footage of real people.
- Tooling with restrictive licences (for example the PESQ scorer) stays opt-in and out of the app.

## Privacy rules

The repository is public and permanent. Never commit, paste into an issue or put in a fixture: phone serial numbers, adb device names, Bluetooth or Wi-Fi addresses, real node IDs or key fingerprints; private photos, recordings or screenshots of other people; personal paths, usernames, emails or phone numbers; signing keys, keystore properties or passwords. Use placeholders such as `AA:BB:CC:00:00:01` and `SERIAL`. Raw benchmark evidence stays out of Git; share summaries (numbers, phone models, short build hashes) instead.

## Pull request checklist

- [ ] The problem is described, with evidence that it is real.
- [ ] The change is narrow and a focused regression covers it.
- [ ] `./gradlew :app:testDebugUnitTest :app:assembleGithubDebug` and `python -B -m pytest -q tools` pass.
- [ ] Streaming changes name the phones, the frozen APK hash and the same-settings comparison.
- [ ] New behaviour is default off, labelled Experimental and registered in [docs/SWITCHES.md](docs/SWITCHES.md).
- [ ] Protocol or security changes link to a reviewed design in docs/PROTOCOL.md.
- [ ] The documents that own the changed behaviour are updated.
- [ ] Commits are signed off, the diff contains no private data, and substantial AI assistance is disclosed.
