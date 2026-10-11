# Security policy

MeshLive is used where the internet may be down or watched, so a security flaw can matter more than in an ordinary
app. We take reports seriously, and we would rather hear about a problem privately and fix it than read about it in
a public issue first.

## Supported versions

MeshLive is pre-release software. Security fixes go to:

| Version | Supported |
| --- | --- |
| The latest published release | Yes |
| `main` (development) | Yes |
| Older releases | No. Please update to the latest release |

Until the first release, only `main` exists.

## Reporting a vulnerability

**Please do not open a public issue, pull request or discussion for a security problem.**

Report it privately through GitHub Security Advisories:

1. Open the repository's **Security** tab and choose **Report a vulnerability**, or go straight to
   <https://github.com/MeshLive/meshlive-android/security/advisories/new>.
2. Describe the problem as described below and submit. Only the maintainers can see the report.

If you cannot use GitHub Security Advisories, open a public issue that says only "security report" with no details,
and a maintainer will arrange a private channel. Do not include the technical details, keys or proof of concept in
that issue.

### What to include

- Which part is affected (see "Scope" below) and which version or commit.
- What an attacker needs (physical range, a paired phone, a malicious relay, a rooted device, and so on) and what
  they gain (read content, impersonate someone, make a call or relay misbehave, recover wiped data, trick a user into
  installing a different app).
- Steps to reproduce, or a short proof of concept. Logs help; the in-app diagnostics block (About, then Export
  diagnostics) contains no media, messages, contact names or full identity keys.
- The phones and Android versions you tested on.
- Whether you plan to publish your findings, and any date you would like us to work towards.

Please do not include real private media, other people's data or your own keys. Please do not attack other people's
phones, and test with phones you own.

## What to expect

This is a volunteer project, so response times are best effort and there is no service-level promise and no bounty.
We aim to:

- acknowledge a report within about a week,
- tell you whether we can reproduce it and how we rate it,
- fix confirmed problems as soon as we reasonably can, then publish an advisory and credit you if you wish,
- agree a disclosure date with you. Without a better reason we prefer to publish once a fix is released, and no later
  than 90 days after the report.

If we decide something is not a vulnerability, we will explain why.

## Scope

In scope:

- **Protocol, identity and encryption**: the BMH2 handshakes, BMS1 hop records, BME1 endpoint content, key
  agreement, signatures, replay and ordering protection, and identity verification
  ([docs/PROTOCOL.md](docs/PROTOCOL.md)).
- **Relay chains and admission**: a relay or child that bypasses admission, reads content it should not, injects or
  reorders records, or defeats the check that the stream comes from the authenticated original source.
- **Private calls**, including call relay chains and the audio and video carried inside them.
- **Store-and-forward messages**: sealed content, carrier-visible bundle headers, key handling, courier behaviour
  and storage.
- **Emergency wipe**: cases where a completed wipe leaves keys or app data that the design says it removes
  (see the stated limits in PROTOCOL "Emergency wipe").
- **Offline app sharing and APK verification**: any way to make the app hand out, or a user accept, an APK that is
  not the one signed by the project key.
- **Release signing and distribution**: the signing key handling, `SHA256SUMS`, `SOURCE-MANIFEST.txt`, the
  attestation workflow and the fingerprint shown in the app.
- Build and supply-chain problems in this repository (dependency verification, pinned native sources).

Out of scope, with examples:

- Attacks that need a rooted or already-compromised phone, a debugger, or physical access to an unlocked phone.
- Anything that needs a build flag that only exists in debug builds, unless it can reach release builds.
- Flash remanence and the other limits that PROTOCOL "Emergency wipe" already states: copies outside the app,
  system logs, and a phone seized before the wipe.
- Bluetooth radio facts that the project documents: a nearby listener sees that Bluetooth traffic exists, its timing
  and its size.
- Denial of service by radio jamming, or by a nearby device that connects and refuses to forward.
- Findings in third-party components that have no effect on MeshLive (report those upstream).
- Reports from automated scanners without a demonstrated impact.
- Social engineering of maintainers, and spam or abuse in the issue tracker.

If you are unsure whether something is in scope, report it privately anyway.

## Known limitations

We list these so you can judge the risk for your own situation. They are open work, not secrets.

- **No independent security audit.** The cryptographic design uses standard primitives (P-256 ECDH and ECDSA, HKDF-SHA-256,
  AES-256-GCM), but the protocol has been reviewed only by the project and its reviewers. It is not an
  independently certified protocol.
- **Bluetooth metadata is visible.** Anyone in radio range can see advertisements, connection timing, and the size
  and timing of encrypted records. Encryption protects content, not the fact that you are communicating. A relay
  also learns the identities of its direct neighbours.
- **A stable discovery prefix is broadcast by default.** Discovery advertises a stable identifier prefix, so a passive
  scanner could recognise the same phone over time. Rotating discovery IDs exist behind an **Experimental** switch
  and are not yet validated on all phones; see PROTOCOL "Experimental rotating discovery IDs" for what remains.
  Phones paired at the operating-system level, and the Classic Bluetooth address, remain trackable.
- **Identity is not a person.** A cryptographic identity does not prove who owns a phone. Compare fingerprints in
  person for contacts you care about; unverified peers are trusted on first use at most.
- **No live revocation for groups.** Static group forwarding does not support removing a member and re-keying.
- **Experimental features are experimental.** Anything labelled Experimental in
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) is off by default and has had less review.
- **Emergency wipe has stated limits** (PROTOCOL "Emergency wipe"): it cannot reach copies outside the app and cannot
  promise erasure of flash storage.
- **Builds are not bit-for-bit reproducible.** The source manifest binds the source files that went into a release,
  not the APK bytes. The signature is what proves who built an APK.

## Verifying what you install

Releases are signed APKs published with `SHA256SUMS` and `SOURCE-MANIFEST.txt`. The release signing certificate
SHA-256 fingerprint is:

```
2E:EF:C0:5A:44:45:22:52:9D:9F:75:D7:EB:CE:FD:08:86:47:07:F2:1A:79:04:1D:B5:1E:BF:F8:36:49:E2:13
```

Check `apksigner verify --print-certs app.apk` against it and follow the steps in the "Verifying a release" section
of [docs/USER_GUIDE.md](docs/USER_GUIDE.md#verifying-a-release). An APK with a different fingerprint comes from a different publisher: do not install it,
and please tell us where you found it. The signing key lives only on the maintainer's machine; the CI workflow holds
no signing key and builds no APK.

Thank you for helping keep MeshLive users safe.
