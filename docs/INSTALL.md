# Installing and verifying MeshLive

The short version is in the [README](../README.md#install). This page has the other options and the full check.

## Other ways to install and update

**Get updates automatically:** install [Obtainium](https://github.com/ImranR98/Obtainium), then tap
**[Get it on Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/MeshLive/meshlive-android)** (or add `https://github.com/MeshLive/meshlive-android` in Obtainium by hand).
Obtainium checks this page for new versions and updates MeshLive for you.

**Updates inside the app (release-page builds).** MeshLive can check for a newer version itself, but only after you say yes: the first time you open it, it asks "Check for updates when online?". Until you answer yes it makes no internet request at all. With yes it asks GitHub's release API (`api.github.com`) at most once a day while you are online; GitHub sees your phone's internet address and that you asked about MeshLive, and nothing else is sent (no account, no cookie, no device or version information, no tracker). It downloads nothing until you tap, and it installs a file only if all of these hold: it is signed with the MeshLive release key that is built into the app (the fingerprint under "Verifying a release" below), it is newer than your copy, it is for your phone type, and its SHA-256 equals the one the release publishes. Android then asks you to confirm; nothing is ever installed silently or downgraded. Settings > Features > Check for updates changes your answer (a "yes" restored from a backup onto another phone is dropped and asked again: the answer has a marker in the no-backup folder). While it is off, nothing on the Updates screen can contact GitHub. F-Droid builds contain none of this (F-Droid updates them) and do not ask for the install permission.

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

## Verifying a release

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
   shows the same fingerprint for the installed copy (a fuller in-app release check is planned in
   [ROADMAP.md](ROADMAP.md)). A different fingerprint means a different publisher: do not install it.
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

Maintainer: `python tools/make_release.py` (clean tree, signing key present, fingerprint must match) writes
`release/<versionName>-<versionCode>/`: the signed APKs, `SHA256SUMS`, `signing-cert.sha256`, `apksigner-certs.txt`,
`SOURCE-MANIFEST.txt` and a `RELEASE-NOTES.md` template to finish by hand. Nothing is uploaded. The checklist is in
[TESTING.md](TESTING.md) under "Release checklist".
