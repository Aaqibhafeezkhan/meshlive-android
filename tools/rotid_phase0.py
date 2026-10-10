"""Phase 0 of the rotating discovery IDs (PLAN "Discovery privacy"): offline join of advertiser and
scanner DEBUG logcat lines, answering "does restarting an advertising set give a new address on this
phone?" and "how often does the stack's own privacy timer rotate the address?".

Inputs are saved logcat text files (any `adb logcat` format). The advertiser phone logs
`BENCHMARK adv_set_churn: label=... | event=start|stop|balanced_in_place|... | set=... | wallMs=...`
(StableAdvertisingSet); a second phone's scanner logs
`BENCHMARK adv_address_seen: scanner=... | node=<12 hex> | version=2|5 | address=... | previous=... | wallMs=...`
(BleL2capDiscovery). The join uses each phone's wall clock (`wallMs`); --skew-ms widens the matching
window for unsynchronised clocks. Output is one JSON object; nothing is written unless --output.

A restart counts as NEW_ADDRESS when the first address the scanner sees for the node after the set
start differs from the last one seen before it, SAME_ADDRESS when it is equal, UNSEEN when the scanner
saw nothing within --window-ms. Address changes with no set start within the window are attributed to
the stack's own timer (AOSP: 7 min + rand % 8 min per set). With rotating IDs on, each epoch has its
own node value, so pass the advertiser's fixed prefix with the switch off for this measurement.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

sys.dont_write_bytecode = True

LINE = re.compile(r"BENCHMARK (adv_set_churn|adv_address_seen): (.*)$")


def parse_fields(text: str) -> dict:
    fields = {}
    for part in text.split(" | "):
        key, sep, value = part.partition("=")
        if sep:
            fields[key.strip()] = value.strip()
    return fields


def read_events(path: Path, kind: str) -> list[dict]:
    events = []
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = LINE.search(line.rstrip("\n"))
            if not match or match.group(1) != kind:
                continue
            fields = parse_fields(match.group(2))
            try:
                fields["wallMs"] = int(fields["wallMs"])
            except (KeyError, ValueError):
                continue
            events.append(fields)
    events.sort(key=lambda event: event["wallMs"])
    return events


def analyse(churn: list[dict], seen: list[dict], node: str, label: str | None,
            window_ms: int = 30_000, skew_ms: int = 0) -> dict:
    node = node.lower()
    sightings = [event for event in seen if event.get("node", "").lower() == node]
    starts = [event for event in churn if event.get("event") == "start" and (label is None or event.get("label") == label)]
    restarts = []
    for start in starts:
        begin = start["wallMs"] - skew_ms
        before = [s for s in sightings if s["wallMs"] < begin]
        after = [s for s in sightings if begin <= s["wallMs"] <= start["wallMs"] + window_ms + skew_ms]
        previous = before[-1]["address"] if before else None
        if not after:
            verdict = "UNSEEN"
        elif previous is None:
            verdict = "FIRST_SIGHTING"
        else:
            verdict = "NEW_ADDRESS" if after[0]["address"] != previous else "SAME_ADDRESS"
        restarts.append({
            "set": start.get("set"),
            "label": start.get("label"),
            "wallMs": start["wallMs"],
            "previousAddress": previous,
            "nextAddress": after[0]["address"] if after else None,
            "verdict": verdict,
        })
    timer_changes = []
    for sighting in sightings:
        previous = sighting.get("previous", "none")
        if previous in ("none", "", None):
            continue
        explained = any(abs(sighting["wallMs"] - start["wallMs"]) <= window_ms + skew_ms for start in starts)
        if not explained:
            timer_changes.append(sighting["wallMs"])
    intervals = [b - a for a, b in zip(timer_changes, timer_changes[1:])]
    counts = {verdict: sum(1 for r in restarts if r["verdict"] == verdict)
              for verdict in ("NEW_ADDRESS", "SAME_ADDRESS", "UNSEEN", "FIRST_SIGHTING")}
    judged = counts["NEW_ADDRESS"] + counts["SAME_ADDRESS"]
    return {
        "node": node,
        "label": label,
        "restarts": restarts,
        "counts": counts,
        "restartChangesAddress": None if judged == 0 else counts["SAME_ADDRESS"] == 0,
        "timerRotations": len(timer_changes),
        "timerIntervalMs": {
            "min": min(intervals) if intervals else None,
            "median": statistics.median(intervals) if intervals else None,
            "max": max(intervals) if intervals else None,
        },
        "sightings": len(sightings),
        "status": "UNMEASURED" if judged == 0 else "MEASURED",
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--advertiser", type=Path, required=True, help="advertiser phone logcat (adv_set_churn)")
    parser.add_argument("--scanner", type=Path, required=True, help="scanning phone logcat (adv_address_seen)")
    parser.add_argument("--node", required=True, help="advertiser's announced 12-hex node value")
    parser.add_argument("--label", help="only this StableAdvertisingSet label (e.g. 'BLE control', 'Discovery')")
    parser.add_argument("--window-ms", type=int, default=30_000)
    parser.add_argument("--skew-ms", type=int, default=0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    result = analyse(read_events(args.advertiser, "adv_set_churn"), read_events(args.scanner, "adv_address_seen"),
                     args.node, args.label, args.window_ms, args.skew_ms)
    text = json.dumps(result, indent=2, sort_keys=True)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
