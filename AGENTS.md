# Working rules for contributors and coding agents

These rules apply to everyone who changes this repository, whether you are a person, an AI coding agent, or a team of
both. They are short on purpose. The detail lives in the documents named below, so this file does not repeat
mandatory reading chains.

## Read first

1. [README.md](README.md): what the app does in a few minutes, with links to the other documents.
2. [testing.md](docs/dev/testing.md): test scope, acceptance gates, workflow and where results live. Read it before
   you run any test on a phone.
3. [architecture.md](docs/dev/architecture.md): how the app is built.
   [switches.md](docs/dev/switches.md) is the register of experimental, alternative and optional behaviour.
4. [protocol reference](docs/dev/protocol/README.md): wire and security rules. Read it before touching anything that is sent,
   encrypted, authenticated or stored.
5. [decisions](docs/dev/decisions/README.md) and [roadmap.md](docs/dev/roadmap.md) when your change touches a measured
   limit or unfinished work.
6. Your local **handoff note**, if one exists (see [Handoffs](#handoffs)). Read it completely at the start of each work period.

## Work and ownership

- **Coordinator.** One person or agent owns integration, shared source, builds and the results ledger. Nobody else
  edits those unless the coordinator explicitly hands them over.
- **Workers.** Everyone else gets an exclusive list of phones and files for the task. Record active assignments in
  the handoff. Only the owner of a phone may launch, install, instrument, force-stop or capture on it. Workers stop
  their own participants when they finish.
- **Offline work** (analysis, scoring, tooling, documentation, review) does not need a phone and can run beside
  device work. Schedule it separately so it never disturbs a measurement.

A solo contributor is both roles at once. The rules still apply.

### Handoffs

Keep one handoff note per work stream **outside Git** (an ignored path such as `local/HANDOFF.md`, or a folder outside the checkout); it holds private device data and is never committed. It owns the current state only, and outdated entries are replaced in place. It must contain:

- the last update time and author; the current build (branch and commit, variant, APK path and SHA-256, whether it is frozen, last green checks, last behaviour change with files, reason and affected scope);
- one row per phone (alias, model, adb serial, owner, task and case IDs, last identity/APK/Bluetooth/permission verification, state), the files each worker owns, and unavailable phones with the reason;
- the radio environment, test state, open issues, interrupted work and next actions;
- questions waiting for a maintainer (recommendation first, two to four options, evidence), switches touched, and cleanup owed.

Contribution rules for everyone are in [CONTRIBUTING.md](CONTRIBUTING.md), including the policy for AI-assisted work.

## Fixes and evidence

- **Reproduce first.** Show the defect with a failing test, a log or a measurement before changing code.
- **Narrow fix, focused regression.** Change the smallest thing that explains the failure, and add a test that fails
  without the fix. Trace the request and completion, the attempt, session and process identity, and the pipeline
  stage before you claim a cause. A plan, a build, a first frame or a generated test suite does not complete a
  streaming fix.
- **Find the root cause.** When fixes circle, stop tuning constants. Prefer measured and architectural fixes over a
  hardcoded value, and challenge limits using measured capability rather than assuming a number is a proven maximum.
- **Same gates for every phone.** The acceptance gates in docs/dev/testing.md are identical for all phones. Eligibility
  follows actual capabilities. Family protections and differences between requested and effective settings stay
  explicit. A historical comparator profile has no special pass standard.
- **Never silently change a requested setting** (bitrate, frame rate, resolution, allocation, encoder, audio,
  transport, buffer) to earn a pass. If the effective value differs, report both.
- **Keep the guards.** Preserve authentication, ordered encryption, hop-local control, admission and overload
  protection, and the vendor-specific optimizations that have evidence behind them. A hop is one Bluetooth link
  between two adjacent phones; each phone acts only on its parent and direct children and sends no multi-hop control
  signals.
- **Direct fallback is a failed requested chain.** Prove the authenticated original source and the requested parent.
- **Measure real load**, both directions of a private call, each hop of a relay chain, and the whole planned
  lifecycle.
- **Keep picture quality, frame timing, lifecycle, Camera2, acoustic quality and optical latency as separate
  measurements.** Decoder timestamps and texture notifications are proxies, not proof of what the screen showed.
- **Record every behaviour change:** changed files, reason, time and affected scope (including other phone
  combinations that share the same stage or stack behaviour, not only the failing pair) before you schedule
  preservation tests.

### Device-family policies

Specializing behaviour for a SoC family, vendor skin, Bluetooth stack or Android version, codec component, or any
combination is encouraged when each entry cites official sources (AOSP source or commits, Android CDD or API
documentation, vendor developer documentation, Bluetooth SIG specifications) and test data beyond your own phones.
Keep entries in the device-family policy registry with a matcher, the behaviour, sources, evidence, confidence and an
off switch. The gates stay the same for every phone. A policy changes how the app reaches a gate, never where the
gate is.

### Testing rules

- **Verify before you test:** current adb identity, the authenticated public node ID, the installed APK hash,
  Bluetooth, permission and wake state, and offered capabilities. A registry entry is not proof that a phone is
  available, and an added phone never replaces a required one.
- **Freeze one APK per comparison.** Record its hash. A pass on a previous build is never labelled a pass on a new
  one: an affected case becomes NEEDS RETEST and keeps its old result and evidence.
- **Exclusive device ownership.** Never touch a phone another person or agent owns. When several run in parallel,
  give each an exclusive list and record it in the handoff.
- **Isolated A/B tests are different from concurrent-radio tests.** Parallel independent runs share the radio
  environment, so record the actual overlapping media intervals. A scenario name or an intended overlap is not
  enough. During an isolated A/B, everyone else does offline work.
- **Check the radio environment.** 2.4 GHz Wi-Fi can halve Bluetooth throughput on some phones; look at the Wi-Fi band
  before blaming hardware. Use controlled, non-private targets for live camera tests, and generated or licensed media
  for source workloads.
- **Signal planned and unexpected events.** Label planned kills; signal required motion and unexpected loss.
- **Stop means stop.** If a maintainer asks you to stop or pause physical testing, stop all device activity, preserve
  the interrupted evidence and continue offline work until told to resume.
- **Keep failures visible.** Failed, interrupted, pending, missing and unmeasured cases stay in the results. All
  inventory starts NOT RUN. Never call fallback-only, partial-prefix, setup-only or unmeasured evidence passed, and
  never repeat an unchanged failure endlessly.
- **Observations need identity.** Bind captured bytes, source, direction, run, build, session and process IDs to the
  actual execution using the hash-bound measurement contract in docs/dev/testing.md. Unregistered or generated captures
  cannot pass a phone gate.
- **Evidence stays out of Git.** Raw captures, traces, run folders and media are local. Git tracks source, documents,
  tools and small benchmark definitions. Report numbers, phone aliases and models, and short build hashes in your
  pull request instead.

## Design decisions and experiments

- New behaviour that is not proven ships behind a flag that is **off by default** and labelled **Experimental**.
- Never delete an experiment or the losing arm of an A/B comparison. Keep it switchable and off by default, unless it
  is impossible by first principles.
- List every switch in the "Experimental, alternative and optional behaviour" register in docs/dev/switches.md with
  its switch and reason. Update the register whenever you add, promote or retire a switch.
- New features may be developed while frozen-build tests run. They ship default-off and are tested after the running
  work finishes.
- Changes to the protocol or security (group calls, store-and-forward messages, identity) need a design in
  docs/dev/protocol/README.md and a review before code.

### Decisions that need maintainers

Do not stop work to wait for an answer. Add the question to the handoff under "Waiting for the maintainer": your
recommendation first, then two to four options with their tradeoffs, and the evidence. Then continue with independent
tasks. Once answered, move the decision into docs/dev/decisions/README.md with its reason. Do not promote an unsupported policy
without discussing the evidence and the tradeoff with a maintainer. A decision already given for a named experiment
remains valid for routine implementation and logging.

## Documentation and cleanup

Keep one owner per topic and replace outdated text in place. Do not add redirect pages, a second status ledger, or
plans for finished features.

| Document | Owns |
| --- | --- |
| [README.md](README.md) | Short overview and entry links |
| [AGENTS.md](AGENTS.md) | These working rules |
| [architecture.md](docs/dev/architecture.md) | Design and build/distribution notes |
| [building.md](docs/dev/building.md) | Building the app from source |
| [switches.md](docs/dev/switches.md) | Register of experimental, alternative and optional behaviour |
| [protocol reference](docs/dev/protocol/README.md) | Wire format and security rules |
| [testing.md](docs/dev/testing.md) | Scope, gates, workflow, result locations, release checklist |
| [decisions](docs/dev/decisions/README.md) | Measured reasons and comparisons |
| [roadmap.md](docs/dev/roadmap.md) | Unfinished work |
| User documents (user guide, privacy, devices) | Install and use, what is exposed, which phones work |
| Local handoff note | Current build, ownership, open issues and next actions (not committed) |

When a plan is implemented, move its content into the owner above and delete the plan. Retire a requirement
explicitly, with its reason, and keep meaningful old benchmark evidence. Explain a disputed assumption or a changed
behaviour in plain language, with the evidence and the tradeoff.

### Privacy and hygiene

- Never commit or paste phone serials, adb device names, Bluetooth or Wi-Fi addresses, node IDs or key fingerprints
  of real phones, private photos, voice or video, other people's data, personal file paths, usernames, email
  addresses, or signing keys. Use placeholders such as `AA:BB:CC:00:00:01` and `SERIAL`.
- Keep your device registry (`tools/benchmark_devices.json`) local. The repository ships an example file only.
- Keep scratch files (debug images, intermediate analysis, host-test output) in your operating system's temporary
  directory or in an exclusively owned run output, and delete them after review. Do not keep private pixels.
- Use exact allowlists for deletion and check resolved paths first. Never remove someone's photos, videos or a phone
  directory on a name guess.
- Preserve a dirty working tree: do not blindly revert changes you did not make, and use branches for parallel work.
- Commit after each verified checkpoint, sign off commits (`git commit -s`), and never skip hooks.

### Checks before you hand work back

- `./gradlew :app:testDebugUnitTest :app:assembleGithubDebug` and `python -B -m pytest -q tools` pass.
- Streaming changes name the phones, the frozen APK hash and the same-settings comparison.
- The register in docs/dev/switches.md and the owning documents are updated.
- The diff contains no private data, and your handoff file is current.
