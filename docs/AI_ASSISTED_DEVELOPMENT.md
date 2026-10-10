# AI-assisted development

Much of MeshLive is written, tested and reviewed with AI coding agents working under human direction. This page
explains how that works, why the project is comfortable with it, and what we ask of contributors who use AI tools
too.

**Tools used so far.** The coordinator and worker agents run in **Anthropic Claude Code**. Critical changes
(protocol, security, redesigns) also get a second opinion from **OpenAI Codex** running as a **read-only reviewer**:
it reads the code and reports findings, but never edits the repository. The process below does not depend on these
particular products; any capable agent or tool can follow it.

The short version: **an AI agent is a fast, tireless colleague with no authority of its own.** Humans decide what the
product should do and which policies are acceptable. Everything an agent produces passes the same gates as a human
change, and nothing is called passed without measured evidence.

## Why we are open about it

A project that moves encrypted audio and video between strangers' phones should show how it is built. Telling you
which parts involve AI assistance, and what stops mistakes from reaching you, lets you judge the risk yourself. The
review gates below exist because a plausible-looking change is not the same as a correct one, whoever or whatever
wrote it.

## Roles

| Role | What it does | What it may not do |
| --- | --- | --- |
| **Maintainers (humans)** | Set product direction, decide policies, approve protocol and security changes, accept releases | Hand a decision to an agent |
| **Coordinator agent** | Owns integration, shared source, builds and the results ledger; assigns work; merges verified results | Edit a worker's exclusive files without a handover |
| **Worker agents** | Take an exclusive list of phones and files; run tests, analyse results, propose or make narrow changes in their own files | Touch a phone or file they do not own |
| **Independent reviewer** | A separate read-only model (currently OpenAI Codex) that reviews critical changes and reports findings | Edit the code; it never grades its own work |

A single contributor using one assistant plays all of these roles in sequence. The separation matters most when
several agents run at once.

## How work is split

- **Exclusive ownership.** Every agent gets an explicit list of phones and files. Only the owner of a phone launches,
  installs, instruments, force-stops or captures on it. Ownership is recorded in a local handoff file created from
  [agents/HANDOFF_TEMPLATE.md](agents/HANDOFF_TEMPLATE.md), kept out of Git because it holds private device data.
- **Device lanes and offline lanes.** Agents with phones run measurements. Other agents do offline work at the same
  time: root-causing a failure from saved logs, writing host tools and tests, scoring finished runs, reviewing,
  documentation. Offline scoring is scheduled separately from device work so it never disturbs a measurement.
- **Isolated and concurrent tests are different.** Parallel independent runs share the radio environment. When a test
  needs a quiet radio to isolate one cause, every other agent stays offline until it finishes. Concurrent-radio tests
  record the actual overlapping media intervals.
- **Frozen builds.** The coordinator freezes one APK per comparison and records its hash. New features can be
  developed meanwhile, but only behind default-off flags, and they are tested after the running work finishes.
- **Handoffs.** The handoff file carries current build, ownership, open issues, interrupted work and next actions
  between work periods. It replaces history rather than accumulating it.

## Review gates

An AI-written change has to clear every gate a human change does:

1. **Reproduce the defect.** A failing test, a log or a measurement. Not "it looks wrong".
2. **Focused regression.** A test that fails without the fix and passes with it.
3. **Narrow fix.** The smallest change that explains the failure, with the cause traced through the request,
   attempt, process and pipeline stage rather than guessed. When fixes circle, find the root cause instead of tuning
   constants.
4. **Unit and host tests.** `./gradlew :app:testDebugUnitTest :app:assembleDebug` and `python -B -m pytest -q tools`.
5. **Frozen APK.** One build, identified by hash, installed and verified on each phone.
6. **Same-settings comparison on real phones.** The same requested bitrate, frame rate, resolution, encoder,
   audio, transport and buffers before and after. An agent must never quietly change a requested setting to earn a
   pass.
7. **Separate measurements.** Picture quality, frame timing, lifecycle, Camera2 behaviour, acoustic quality and
   optical latency are measured and reported separately. Decoder timestamps and texture notifications are proxies,
   not proof of what a screen showed.
8. **Honest status.** Failed, interrupted and unmeasured cases stay visible. Fallback-only, partial or setup-only
   evidence is not a pass, and a pass on an older build is not a pass on a new one.
9. **Independent review for critical changes.** Anything touching the protocol, encryption, identity, admission,
   storage or emergency wipe also gets a read-only review by a different model. A finding is a lead to verify, not an
   instruction: the coordinator checks it against the code and the evidence, then fixes or rejects it with a reason.
   Reviews are a second pair of eyes, not a substitute for the human review of the pull request.

Protocol and security changes additionally need a written design in [PROTOCOL.md](PROTOCOL.md) and a maintainer's
review before code is merged. See [../CONTRIBUTING.md](../CONTRIBUTING.md).

## Keeping experiments switchable

Agents are good at producing variants quickly, so the rule is strict. New behaviour ships default off, labelled
Experimental, and is listed in the "Experimental, alternative and optional behaviour" register in
[ARCHITECTURE.md](ARCHITECTURE.md). The losing arm of an A/B comparison is kept switchable rather than deleted,
unless it is impossible by first principles. This keeps results reproducible and lets a later measurement on a
different phone family overturn today's winner.

## Humans decide

Agents queue questions instead of guessing. A question that needs a maintainer goes into the handoff with a
recommendation first, two to four options with their tradeoffs, and the evidence, and the agent carries on with
independent work. Product direction (for example how much to favour latency over frame loss, or what a discovery
privacy default should be), policy promotion and release decisions are made by people. Measured reasons are then
recorded in [DECISIONS.md](DECISIONS.md).

## If you use AI tools to contribute

You are welcome to. We ask for the same things we ask of ourselves.

- **You are responsible for every line.** Read, understand and run what the tool wrote. "The assistant wrote it" is
  not a defence for a bug, a licence problem or a security hole.
- **Disclose substantial assistance** in the pull request description (a sentence is enough: which tool, and for
  what). Small autocomplete does not need a note.
- **Meet the same gates.** A reproduction, a focused regression, a narrow fix and measurements on real phones for
  anything that touches streaming. An agent's report that it "tested" something is not evidence until you see the
  logs or numbers.
- **Check licences.** Agents can reproduce code from other projects. Do not submit anything you cannot license under
  GPL-3.0-or-later, and keep attribution for anything you adapt. Sign your commits with `git commit -s`.
- **Keep secrets and private data out of prompts.** Do not give a tool phone serials, Bluetooth addresses, node IDs,
  key material, signing keystores or passwords, private photos, voice or video, other people's data or personal
  paths. Anything you paste may leave your machine. Use placeholders such as `AA:BB:CC:00:00:01`.
- **Limit what the agent can do.** Give it only the files and phones it needs, and do not let it run destructive
  commands, push, or sign releases for you.
- **Do not let it delete evidence or experiments.** Review any cleanup it proposes against exact allowlists.
- **Keep documents honest.** Agents tend to overclaim ("works on all phones"). Re-read what they wrote against the
  evidence, and label anything unvalidated as experimental.

Pull requests that show no sign of human review, or whose author cannot explain the change, may be closed.

## Related

- [AGENTS.md](../AGENTS.md): the working rules agents and people follow in this repository
- [CONTRIBUTING.md](../CONTRIBUTING.md): setup, reporting and licensing
- [TESTING.md](TESTING.md): scope, acceptance gates and measurement contract
- [agents/HANDOFF_TEMPLATE.md](agents/HANDOFF_TEMPLATE.md): the local handoff template
