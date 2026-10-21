"""Offline summary of the in-call link renewal A/B (Design SR; catalog sr-renewal-ab-20261021).

Reads finalized private-call executions (each folder's manifest.json, its endpoint logs and score.json) and reports, per
arm (renewal on or off, with or without the stall hook):

- survived: the call reached its planned "end" action with terminal evidence on both endpoints, no runner error and no
  link-loss end message on either phone;
- the renewal phases each endpoint logged (`BENCHMARK call_renewal: role= | phase=`), and whether one bound;
- `call_renewal_silence` (ms) per role;
- unexpected freezes from the existing call scorer (score.json devices[].incoming.freezes.unexpected);
- false renewals: any OFFER logged in a case without the stall hook.

No device I/O; it never changes the evidence it reads. Repeats are separate executions in their own run folders
(tools/run_case_repeats.py), so it takes several --runs folders, or one parent with --match on its direct children:

    python -B tools/analyze_call_renewal.py --runs benchmarks/runs --match "sr-renewal-ab-1a2b3c4d-*" [--output summary.json]
    python -B tools/analyze_call_renewal.py --runs benchmarks/runs/RUN_A --runs benchmarks/runs/RUN_B
"""

import argparse
import fnmatch
import json
import re
import statistics
from pathlib import Path

RENEWAL = re.compile(r"call_renewal: role=(\w+) \| phase=(\w+)")
SILENCE = re.compile(r"call_renewal_silence: role=(\w+) \| ms=(\d+)")
ENDED_UI = re.compile(r"BENCHMARK call ended_ui: message=(.*)")
TX_STALL = re.compile(r"BENCHMARK call_debug_tx_stall:")
LINK_LOSS = re.compile(r"lost|stopped responding|failed|did not complete", re.IGNORECASE)


def arm_of(scenario):
    tunables = scenario.get("tunables") or {}
    per_phone = scenario.get("phone_tunables") or {}
    stalled = sorted(phone for phone, values in per_phone.items() if (values or {}).get("call_tx_stall_after_s", 0) > 0)
    renewal = tunables.get("call_link_renewal") is True
    if not stalled:
        return ("control" if renewal else "no-stall-off"), stalled
    return ("renewal-on" if renewal else "renewal-off"), stalled


def endpoint_facts(text):
    phases = [{"role": role, "phase": phase} for role, phase in RENEWAL.findall(text)]
    silences = [{"role": role, "ms": int(ms)} for role, ms in SILENCE.findall(text)]
    ended = [message.strip() for message in ENDED_UI.findall(text)]
    return {
        "phases": phases,
        "silences": silences,
        "ended_messages": ended,
        "link_loss_end": any(LINK_LOSS.search(message) for message in ended),
        "tx_stall_started": bool(TX_STALL.search(text)),
    }


def unexpected_freezes(score):
    total = 0
    for device in (score or {}).get("devices", []):
        freezes = ((device.get("incoming") or {}).get("freezes") or {})
        total += int(freezes.get("unexpected", 0) or 0)
    return total


def planned_end_reached(manifest):
    for event in manifest.get("control_events", []):
        if event.get("action") == "end" and event.get("terminal_evidence"):
            return True
    return False


def execution(folder):
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    scenario = manifest.get("scenario") or {}
    arm, stalled = arm_of(scenario)
    endpoints = {}
    for spec in manifest.get("logs", []):
        path = folder / spec["path"]
        if path.is_file():
            facts = endpoint_facts(path.read_text(encoding="utf-8", errors="replace"))
            previous = endpoints.get(spec["device"])
            if previous:  # a restarted process: keep every line of the endpoint
                for key in ("phases", "silences", "ended_messages"):
                    previous[key] += facts[key]
                previous["link_loss_end"] |= facts["link_loss_end"]
                previous["tx_stall_started"] |= facts["tx_stall_started"]
            else:
                endpoints[spec["device"]] = facts
    score_path = folder / "score.json"
    score = json.loads(score_path.read_text(encoding="utf-8")) if score_path.is_file() else None
    survived = (planned_end_reached(manifest) and not manifest.get("runner_error")
                and not any(item["link_loss_end"] for item in endpoints.values()))
    offers = sum(1 for item in endpoints.values() for row in item["phases"] if row["role"] == "offerer" and row["phase"] == "offer")
    bound = any(row["phase"] == "bound" for item in endpoints.values() for row in item["phases"])
    return {
        "case": scenario.get("id"),
        "folder": folder.as_posix(),
        "arm": arm,
        "stalled_phone": stalled[0] if stalled else None,
        "survived": survived,
        "renewal_offers": offers,
        "renewal_bound": bound,
        "false_renewal": arm == "control" and offers > 0,
        "silence_ms": [row["ms"] for item in endpoints.values() for row in item["silences"] if row["role"] == "offerer"],
        "unexpected_freezes": unexpected_freezes(score) if score else None,
        "stall_hook_fired": {phone: item["tx_stall_started"] for phone, item in endpoints.items()},
        "endpoints": endpoints,
    }


def summarize(executions):
    arms = {}
    for row in executions:
        arms.setdefault((row["arm"], row["stalled_phone"]), []).append(row)
    summary = []
    for (arm, stalled), rows in sorted(arms.items(), key=lambda item: (item[0][0], item[0][1] or "")):
        silences = [ms for row in rows for ms in row["silence_ms"]]
        freezes = [row["unexpected_freezes"] for row in rows if row["unexpected_freezes"] is not None]
        summary.append({
            "arm": arm,
            "stalled_phone": stalled,
            "calls": len(rows),
            "survived": sum(1 for row in rows if row["survived"]),
            "renewals_bound": sum(1 for row in rows if row["renewal_bound"]),
            "false_renewals": sum(1 for row in rows if row["false_renewal"]),
            "silence_ms_median": statistics.median(silences) if silences else None,
            "silence_ms_max": max(silences) if silences else None,
            "unexpected_freezes_total": sum(freezes) if freezes else None,
            "freeze_measured_calls": len(freezes),
        })
    return summary


def run_roots(runs, match):
    """The folders to search: each --runs folder, or with [match] its direct children whose names match."""
    roots = []
    for folder in runs:
        roots += sorted(child for child in folder.iterdir() if child.is_dir() and fnmatch.fnmatch(child.name, match)) if match else [folder]
    return roots


def call_folders(roots):
    found = set()
    for root in roots:
        for path in root.rglob("manifest.json"):
            if json.loads(path.read_text(encoding="utf-8")).get("scenario", {}).get("caller"):
                found.add(path.parent)
    return sorted(found)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=Path, action="append", required=True,
                        help="Folder holding finalized private-call executions; repeat for several run folders")
    parser.add_argument("--match", help="Use the direct children of each --runs folder whose names match this glob")
    parser.add_argument("--output", type=Path, help="Write the summary JSON here (default: print it)")
    args = parser.parse_args(argv)
    roots = run_roots(args.runs, args.match)
    executions = [execution(folder) for folder in call_folders(roots)]
    result = {"schema_version": 1, "runs": [root.as_posix() for root in roots], "arms": summarize(executions),
              "executions": executions}
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
