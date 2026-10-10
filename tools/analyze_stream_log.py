#!/usr/bin/env python3
"""Summarize receiver discovery, startup, and visible-frame continuity log lines."""

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from benchmark_layout import output_path, resolve


DISCOVERY = re.compile(r"Discovery startup: browser-to-first-offer ([0-9.]+) ms")
STARTUP = re.compile(r"Receiver startup: selection-to-first-surface ([0-9.]+) ms")
CONTINUITY = re.compile(
    r"Receiver visible continuity(?: FINAL)?: (\d+) frames \| "
    r"(\d+) freeze(?: event)?\(s\), ([0-9.]+) ms, ([0-9.]+)% \| threshold ([0-9.]+) ms"
)


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    weight = index - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def stats(values: list[float]) -> dict:
    return {
        "samples": len(values),
        "mean": statistics.fmean(values) if values else None,
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "max": max(values) if values else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("logs", nargs="*", type=Path, help="Read stdin when omitted")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output:
        args.output = output_path(args.output, "runs")
        if args.output.exists():
            raise FileExistsError(args.output)

    discoveries: list[float] = []
    startups: list[float] = []
    continuity: list[dict] = []
    texts = [resolve(path).read_text(encoding="utf-8", errors="replace") for path in args.logs]
    if not texts:
        texts = [sys.stdin.read()]
    for text in texts:
        discoveries.extend(float(value) for value in DISCOVERY.findall(text))
        startups.extend(float(value) for value in STARTUP.findall(text))
        for frames, events, duration, ratio, threshold in CONTINUITY.findall(text):
            continuity.append(
                {
                    "rendered_frames": int(frames),
                    "freeze_events": int(events),
                    "freeze_duration_ms": float(duration),
                    "freeze_ratio_percent": float(ratio),
                    "freeze_threshold_ms": float(threshold),
                }
            )

    result = {
        "discovery_browser_to_first_offer_ms": stats(discoveries),
        "selection_to_first_visible_frame_ms": stats(startups),
        "visible_continuity_snapshots": continuity,
        "latest_visible_continuity": continuity[-1] if continuity else None,
        "scope": (
            "Startup is app selection to first TextureView update; continuity uses visible "
            "TextureView frame gaps. Neither metric is optical glass-to-glass latency."
        ),
    }
    encoded = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
