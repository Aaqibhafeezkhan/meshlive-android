#!/usr/bin/env python3
"""Summarise `BENCHMARK message_transfer` lines from saved logcat files (DECISIONS Design M, phase 0).

Offline only: it reads text files and never talks to a phone. A lane saves each phone's logcat to
a run-owned file, then runs

    python -B tools/message_transfer_report.py --log s24=s24.log --log m31=m31.log --output report.json

The report lists every exchange (per phone and direction, with bytes, chunks, setup time and the
goodput of the chunk interval), the per-transport goodput summary, and the app state at each exchange
and each lifecycle marker so background reception (T-M8) can be read from the same file.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

LINE = re.compile(r"BENCHMARK message_transfer (session|bundle|app) (.*)$")
PAIR = re.compile(r"(\w+)=(\S+)")
NUMERIC = {
    "setupMs", "helloMs", "durationMs", "chunksOut", "bytesOut", "chunksIn", "bytesIn", "wireOut", "wireIn",
    "resumedChunks", "firstOutMs", "lastOutMs", "firstInMs", "lastInMs", "goodputOutKbps", "goodputInKbps",
    "sessionOutKbps", "sessionInKbps", "opened", "completed", "dup", "poisoned", "class", "goodputKbps",
    "firstMs", "lastMs", "chunkBytes", "atNs", "activeOutKbps", "activeInKbps", "bundles",
}


def parse_fields(text: str) -> dict:
    fields: dict = {}
    for key, value in PAIR.findall(text):
        fields[key] = int(value) if key in NUMERIC and re.fullmatch(r"-?\d+", value) else value
    return fields


def parse_log(path: Path, phone: str) -> list[dict]:
    rows = []
    for number, raw in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        match = LINE.search(raw)
        if not match:
            continue
        kind, rest = match.groups()
        row = {"phone": phone, "kind": kind, "line": number, **parse_fields(rest)}
        stamp = re.match(r"^\s*(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", raw)
        if stamp:
            row["logTime"] = stamp.group(1)
        rows.append(row)
    return rows


def transfer_direction(session: dict) -> list[tuple[str, int, int, int, int]]:
    """(direction, chunks, bytes, goodput kbps, session kbps) for each direction that moved chunks."""
    result = []
    if session.get("chunksOut", 0) > 0:
        result.append(("out", session["chunksOut"], session["bytesOut"], session["goodputOutKbps"], session["sessionOutKbps"]))
    if session.get("chunksIn", 0) > 0:
        result.append(("in", session["chunksIn"], session["bytesIn"], session["goodputInKbps"], session["sessionInKbps"]))
    return result


def summarise(rows: list[dict]) -> dict:
    sessions = [row for row in rows if row["kind"] == "session"]
    by_transport: dict[str, dict] = {}
    for session in sessions:
        for direction, chunks, size, kbps, session_kbps in transfer_direction(session):
            # Only complete exchanges of useful size say anything about the link rate.
            if session.get("outcome") != "COMPLETE" or size < 20_000:
                continue
            entry = by_transport.setdefault(
                session.get("transport", "unknown"), {"goodputKbps": [], "sessionKbps": [], "activeKbps": [], "setupMs": []})
            entry["goodputKbps"].append(kbps)
            entry["sessionKbps"].append(session_kbps)
            # Rate while chunks moved (newer builds). The first-to-last goodput of a chat session spans its idle minutes.
            active = session.get("activeOutKbps" if direction == "out" else "activeInKbps")
            if isinstance(active, int):
                entry["activeKbps"].append(active)
            if session.get("setupMs", -1) >= 0 and direction == "out":
                entry["setupMs"].append(session["setupMs"])
    transports = {}
    for name, entry in by_transport.items():
        transports[name] = {
            "exchanges": len(entry["goodputKbps"]),
            "goodputKbpsMedian": statistics.median(entry["goodputKbps"]),
            "goodputKbpsMin": min(entry["goodputKbps"]),
            "goodputKbpsMax": max(entry["goodputKbps"]),
            "sessionKbpsMedian": statistics.median(entry["sessionKbps"]),
            "activeKbpsMedian": statistics.median(entry["activeKbps"]) if entry["activeKbps"] else None,
            "setupMsMedian": statistics.median(entry["setupMs"]) if entry["setupMs"] else None,
        }
    background = [
        {"phone": s["phone"], "line": s["line"], "appState": s.get("appState"), "chunksIn": s.get("chunksIn", 0), "opened": s.get("opened", 0)}
        for s in sessions
        if "importance=foreground(" not in s.get("appState", "") or "doze=1" in s.get("appState", "")
    ]
    return {
        "sessions": len(sessions),
        "transports": transports,
        "exchangesWithAppStateNotForeground": background,
        "lifecycleEvents": [row for row in rows if row["kind"] == "app"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--log", action="append", required=True, metavar="PHONE=FILE", help="saved logcat of one phone")
    parser.add_argument("--output", type=Path, help="write the JSON report here (a fresh file)")
    args = parser.parse_args(argv)
    rows: list[dict] = []
    for item in args.log:
        phone, _, name = item.partition("=")
        if not name:
            parser.error("--log needs PHONE=FILE")
        rows.extend(parse_log(Path(name), phone))
    report = {"rows": rows, "summary": summarise(rows)}
    text = json.dumps(report, indent=2, sort_keys=True)
    if args.output:
        if args.output.exists():
            parser.error(f"{args.output} exists; use a fresh run-owned output")
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
