# Handoff template

> **Do not commit a filled-in handoff.** It contains private device data (adb serials, Bluetooth addresses, node
> IDs, APK hashes of your builds) and personal details. Copy this template to an ignored path such as
> `local/HANDOFF.md` (add `local/` to `.git/info/exclude` if `.gitignore` does not cover it) or to a folder outside
> the repository, and fill it in there. Only this empty template belongs in Git.
>
> The handoff owns the **current** state: build, device ownership, open issues and next actions. Replace
> outdated entries in place instead of appending history. Measured reasons go to
> [docs/DECISIONS.md](../DECISIONS.md), unfinished product work to [docs/ROADMAP.md](../ROADMAP.md), and the rules
> are in [AGENTS.md](../../AGENTS.md).

Last updated: `YYYY-MM-DD HH:MM` by `NAME-OR-AGENT-ROLE`

## Current build

| Item | Value |
| --- | --- |
| Branch and commit | `BRANCH @ SHORT_HASH` (clean tree: yes/no) |
| Build variant | `debug` / `release` |
| APK path and SHA-256 | `PATH` / `SHA256` (frozen for comparisons: yes/no) |
| Application id and version | `ID` / `versionName (versionCode)` |
| Native sources | libaom commit and libopus version match THIRD_PARTY_NOTICES: yes/no |
| Last green checks | `./gradlew :app:testDebugUnitTest :app:assembleDebug` and `python -B -m pytest -q tools` at `TIME` |
| Last behaviour change | files, reason, time, affected scope (include other phone combinations that share the stage) |

## Active device assignments

One row per phone. Only the owner may launch, install, instrument, force-stop or capture on it.

| Alias | Model | adb serial | Owner | Task and case IDs | Verified (time) | State |
| --- | --- | --- | --- | --- | --- | --- |
| `ALIAS` | `MODEL` | `SERIAL` | `NAME-OR-AGENT` | `TASK` | identity, node ID, APK hash, Bluetooth, permissions, wake state | free / running / cleaning up |

Also record: files each worker owns exclusively, the coordinator, who owns the results ledger, and any phones that are
unavailable (and why) without erasing the required scope entry.

## Radio environment

Wi-Fi band and channel near the test area, other Bluetooth traffic, whether runs overlap in time (record the actual
overlapping media intervals, not the intent), and any planned kills or signalling used.

## Test state

- Selected eligible cases and their plan or run identifiers (local paths only; do not commit them).
- Failures and NEEDS RETEST cases first, then untested or unmeasured work.
- Last finalized execution and whether the ledger and case state were updated.
- Missing measurement adapters or blocked prerequisites (keep them explicit).

## Open issues

| ID | Symptom | Evidence so far | Suspected stage | Owner | Next step |
| --- | --- | --- | --- | --- | --- |
| `N` | what fails, on which phones and transports | log lines, measurement IDs | request, attempt, process, pipeline stage | `NAME` | reproduce, narrow fix, focused regression |

## Interrupted work

Anything stopped by a pause, a lost phone, a recorder that did not confirm exit, or a crash. Keep the evidence, name
the phones involved, and say what must be verified before resuming (for example "confirm the recorder process
actually stopped").

| What was running | Stopped at | Evidence kept | Needed before resuming |
| --- | --- | --- | --- |
| `RUN` | `TIME` | `LOCAL PATH` | `CHECK` |

## Next actions

Ordered, each with an owner and whether it needs a phone.

1. `ACTION` (owner, device or offline)
2. `ACTION`

Independent offline work that can proceed while phones are busy:

- `ACTION`

## Waiting for the maintainer

Do not stop work to wait. Put the question here with your recommendation first, then continue with independent tasks.
When answered, move the outcome into [docs/DECISIONS.md](../DECISIONS.md) and delete the entry here.

### Question `N`: `SHORT TITLE`

- **Recommendation:** `WHAT YOU WOULD DO AND WHY`
- **Option A:** `DESCRIPTION` - tradeoff: `COST AND BENEFIT`
- **Option B:** `DESCRIPTION` - tradeoff: `COST AND BENEFIT`
- **Option C (optional):** `DESCRIPTION` - tradeoff: `COST AND BENEFIT`
- **Evidence:** `MEASUREMENTS, SOURCES, DECISIONS ENTRY`
- **Blocks:** `WHAT CANNOT PROCEED UNTIL ANSWERED, IF ANYTHING`

## Experimental switches touched

Switch, default, register row in docs/ARCHITECTURE.md updated (yes/no), and what test is still needed.

## Cleanup owed

Temporary captures, debug images or scratch folders you created and still need to review and delete, with the exact
paths. Do not delete evidence another person owns.
