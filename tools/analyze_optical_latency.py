#!/usr/bin/env python3
"""Measure source-event to receiver-event delay from a single external video."""

import argparse
from collections import Counter
from fractions import Fraction
import hashlib
import json
import math
import statistics
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from benchmark_layout import output_path, resolve

import cv2
import numpy as np


def roi(value: str) -> tuple[int, int, int, int]:
    parts = tuple(int(part) for part in value.split(","))
    if len(parts) != 4 or parts[2] <= 0 or parts[3] <= 0:
        raise argparse.ArgumentTypeError("ROI must be x,y,width,height with positive size")
    return parts


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    return float(np.percentile(np.asarray(values), fraction * 100.0))


def normalize(values: list[float]) -> np.ndarray:
    data = np.asarray(values, dtype=np.float64)
    low, high = np.percentile(data, (10.0, 90.0))
    if high - low < 5.0:
        raise RuntimeError("ROI brightness did not change enough to detect events")
    return np.clip((data - low) / (high - low), 0.0, 1.0)


def edges(signal: np.ndarray) -> list[tuple[int, int]]:
    state = signal >= 0.5
    changes = np.flatnonzero(state[1:] != state[:-1]) + 1
    return [(int(index), 1 if state[index] else -1) for index in changes]


def pair_edges(
    source: list[tuple[int, int]],
    receiver: list[tuple[int, int]],
    times: list[float],
    minimum_ms: float,
    maximum_ms: float,
) -> dict:
    if (not all(math.isfinite(value) for value in (minimum_ms, maximum_ms)) or
            minimum_ms < 0 or maximum_ms < minimum_ms):
        raise ValueError("Delay bounds must be finite and ordered, with a nonnegative minimum")
    if len(times) < 2 or any(not math.isfinite(value) for value in times) or any(
            b <= a for a, b in zip(times, times[1:])):
        raise ValueError("Original frame timestamps must increase strictly")
    if any(type(frame) is not int or not 1 <= frame < len(times) or sign not in {-1, 1}
           for frame, sign in source + receiver):
        raise ValueError("Brightness edges require an observed preceding frame and valid polarity")
    events = []
    for source_frame, polarity in source:
        candidates = [frame for frame, sign in receiver if sign == polarity and
                      minimum_ms <= (times[frame] - times[source_frame]) * 1000 <= maximum_ms]
        events.append({"source_frame": source_frame, "polarity": polarity, "receiver_candidates": candidates})
    users = Counter(frame for row in events for frame in row["receiver_candidates"])
    matched = []
    for row in events:
        candidates = row["receiver_candidates"]
        row["status"] = "MISSING" if not candidates else "AMBIGUOUS"
        if len(candidates) == 1 and users[candidates[0]] == 1:
            source_frame, receiver_frame = row["source_frame"], candidates[0]
            row.update(status="MATCHED", receiver_frame=receiver_frame,
                       delay_ms=(times[receiver_frame] - times[source_frame]) * 1000,
                       delay_bounds_ms=[(times[receiver_frame - 1] - times[source_frame]) * 1000,
                                        (times[receiver_frame] - times[source_frame - 1]) * 1000])
            matched.append(row)
    return {"events": events, "matched": matched}


def read_frame_times(path: Path) -> list[float]:
    path = resolve(path)
    result = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames",
                             "-show_entries", "stream=time_base:frame=best_effort_timestamp", "-of", "json", str(path)],
                            capture_output=True, text=True)
    if result.returncode != 0 or result.stderr.strip():
        raise ValueError(f"Original recording timestamps could not be decoded: {result.stderr.strip() or result.returncode}")
    data = json.loads(result.stdout)
    time_base = Fraction(data["streams"][0]["time_base"])
    if time_base <= 0 or any("best_effort_timestamp" not in row for row in data["frames"]):
        raise ValueError("Recording must retain each original frame timestamp and positive timebase")
    times = [float(int(row["best_effort_timestamp"]) * time_base) for row in data["frames"]]
    if len(times) < 2 or any(b <= a for a, b in zip(times, times[1:])):
        raise ValueError("Original frame timestamps must increase strictly")
    return times


def analyze_video(video, source_roi, receiver_roi, minimum_ms, maximum_ms, detector=None):
    """Read both actual ROIs once and retain original container sample times."""
    video = resolve(video)
    for box in (source_roi, receiver_roi):
        if len(box) != 4 or any(type(value) is not int for value in box) or min(box[:2]) < 0 or min(box[2:]) <= 0:
            raise ValueError("ROI must be four integer coordinates with nonnegative origin and positive size")
    with video.open("rb") as source:
        original_hash = hashlib.file_digest(source, "sha256").hexdigest()
    times = read_frame_times(video)

    capture = cv2.VideoCapture(str(video))
    capture.set(cv2.CAP_PROP_ORIENTATION_AUTO, 0)
    source_values: list[float] = []
    receiver_values: list[float] = []
    sx, sy, sw, sh = source_roi
    rx, ry, rw, rh = receiver_roi
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            height, width = frame.shape[:2]
            if sx + sw > width or sy + sh > height:
                raise RuntimeError("Source ROI is outside the recording")
            if rx + rw > width or ry + rh > height:
                raise RuntimeError("Receiver ROI is outside the recording")
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            source_values.append(float(gray[sy : sy + sh, sx : sx + sw].mean()))
            receiver_values.append(float(gray[ry : ry + rh, rx : rx + rw].mean()))
    finally:
        capture.release()
    if not source_values:
        raise RuntimeError("Recording contains no readable frames")
    if len(source_values) != len(times):
        raise ValueError("Decoded frames do not match the original timestamp count")
    with video.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != original_hash:
            raise ValueError("Recording changed during analysis")

    if detector is None:
        source_edges = edges(normalize(source_values))
        receiver_edges = edges(normalize(receiver_values))
    else:
        def detected(values, name):
            threshold, contrast = detector[name + "_threshold"], detector["minimum_contrast"]
            if any(type(value) not in (int, float) or not math.isfinite(value) for value in (threshold, contrast)) or not (
                    0 < threshold < 255 and 0 < contrast <= 255):
                raise ValueError("Detector thresholds and minimum contrast must be finite native grayscale levels")
            data = np.asarray(values, dtype=np.float64)
            if np.ptp(data) < contrast or not np.any(data < threshold) or not np.any(data >= threshold):
                if name == "source":
                    raise ValueError("Declared source ROI contrast/threshold cannot identify brightness events")
                return []  # No received transition is missing evidence, not a fabricated source match.
            return edges(data >= threshold)
        source_edges = detected(source_values, "source")
        receiver_edges = detected(receiver_values, "receiver")
    pairing = pair_edges(
        source_edges,
        receiver_edges,
        times,
        minimum_ms,
        maximum_ms,
    )
    latencies = [row["delay_ms"] for row in pairing["matched"]]
    intervals = [(b - a) * 1000 for a, b in zip(times, times[1:])]
    result = {
        "video": str(video),
        "video_sha256": original_hash,
        "recording_fps": (len(times) - 1) / (times[-1] - times[0]),
        "timing_basis": "Original per-frame container timestamps; nominal FPS is never a time anchor",
        "frame_times_seconds": times,
        "recording_frames": len(source_values),
        "source_events": len(source_edges),
        "receiver_events": len(receiver_edges),
        "source_edges": source_edges,
        "receiver_edges": receiver_edges,
        "paired_events": len(latencies),
        "pairing": pairing,
        "measurement_status": "MEASURED" if len(latencies) == len(source_edges) and source_edges else "UNMEASURED",
        "latency_ms": {
            "values": latencies,
            "mean": statistics.fmean(latencies) if latencies else None,
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "min": min(latencies) if latencies else None,
            "max": max(latencies) if latencies else None,
        },
        "frame_resolution_ms": max(intervals),
        "frame_intervals_ms": {"min": min(intervals), "max": max(intervals)},
        "scope": (
            "Visible brightness-edge delay in one recording. Ambiguous/missing event matches remain unmeasured. "
            "Per-event bounds retain frame-sampling uncertainty; exposure, rolling shutter, detector threshold and "
            "run/device provenance need independent evidence before physical latency acceptance."
        ),
    }
    return result


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description="Measure source and receiver brightness edges in one independent recording.")
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--source-roi", required=True, type=roi)
    parser.add_argument("--receiver-roi", required=True, type=roi)
    parser.add_argument("--minimum-ms", type=float, default=0.0)
    parser.add_argument("--maximum-ms", type=float, default=6_000.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    if args.output:
        args.output = output_path(args.output, "runs")
        if args.output.exists():
            raise FileExistsError(args.output)
    result = analyze_video(args.video, args.source_roi, args.receiver_roi, args.minimum_ms, args.maximum_ms)
    encoded = json.dumps(result, indent=2, allow_nan=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as destination:
            destination.write(encoded)
    print(encoded, end="")


if __name__ == "__main__":
    main()
