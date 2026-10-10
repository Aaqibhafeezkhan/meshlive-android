#!/usr/bin/env python3
"""Calculate PCHIP Bjontegaard delta-rate from controlled benchmark results."""

import argparse
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from benchmark_layout import output_path, resolve

import numpy as np
from scipy.interpolate import PchipInterpolator


METRICS = ("vmaf", "ssim", "psnr_db")


def load_curve(paths: list[str]) -> list[dict[str, float]]:
    curve = []
    for value in paths:
        path = resolve(value)
        result = json.loads(path.read_text(encoding="utf-8"))
        point = {"actual_kbps": float(result["actual_kbps"])}
        for metric in METRICS:
            point[metric] = float(result[metric])
        curve.append(point)
    if len(curve) < 4:
        raise ValueError("BD-rate needs at least four rate-distortion points per curve")
    return curve


def prepare_points(curve: list[dict[str, float]], metric: str) -> tuple[np.ndarray, np.ndarray]:
    ordered = sorted((point[metric], point["actual_kbps"]) for point in curve)
    qualities: list[float] = []
    log_rates: list[float] = []
    for quality, rate in ordered:
        if rate <= 0:
            raise ValueError("Bitrates must be positive")
        if qualities and quality == qualities[-1]:
            if rate < math.exp(log_rates[-1]):
                log_rates[-1] = math.log(rate)
            continue
        qualities.append(quality)
        log_rates.append(math.log(rate))
    if any(right <= left for left, right in zip(log_rates, log_rates[1:])):
        raise ValueError(f"{metric} curve is not rate-monotonic")
    return np.asarray(qualities), np.asarray(log_rates)


def bd_rate(anchor: list[dict[str, float]], test: list[dict[str, float]], metric: str) -> dict[str, float]:
    anchor_quality, anchor_log_rate = prepare_points(anchor, metric)
    test_quality, test_log_rate = prepare_points(test, metric)
    overlap_low = max(float(anchor_quality.min()), float(test_quality.min()))
    overlap_high = min(float(anchor_quality.max()), float(test_quality.max()))
    if overlap_high <= overlap_low:
        raise ValueError(f"{metric} curves have no overlapping quality range")
    samples = np.linspace(overlap_low, overlap_high, num=1000)
    anchor_values = PchipInterpolator(anchor_quality, anchor_log_rate)(samples)
    test_values = PchipInterpolator(test_quality, test_log_rate)(samples)
    average_log_difference = float(
        np.trapezoid(test_values - anchor_values, samples) / (overlap_high - overlap_low)
    )
    return {
        "percent": (math.exp(average_log_difference) - 1.0) * 100.0,
        "quality_overlap_low": overlap_low,
        "quality_overlap_high": overlap_high,
    }


def metric_report(
    anchor: list[dict[str, float]],
    test: list[dict[str, float]],
    metric: str,
) -> dict[str, float | str]:
    try:
        return bd_rate(anchor, test, metric)
    except ValueError as error:
        return {"error": str(error)}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compute test-vs-anchor BD-rate. A negative result means bitrate savings."
    )
    parser.add_argument("--anchor-label", required=True)
    parser.add_argument("--anchor", nargs="+", required=True, metavar="RESULT_JSON")
    parser.add_argument("--test-label", required=True)
    parser.add_argument("--test", nargs="+", required=True, metavar="RESULT_JSON")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output:
        args.output = output_path(args.output, "runs")
        if args.output.exists():
            raise FileExistsError(args.output)

    anchor = load_curve(args.anchor)
    test = load_curve(args.test)
    report = {
        "interpretation": "Negative percent means the test needs less bitrate than the anchor at equal measured quality.",
        "anchor": {"label": args.anchor_label, "points": anchor},
        "test": {"label": args.test_label, "points": test},
        "bd_rate_test_vs_anchor": {
            metric: metric_report(anchor, test, metric) for metric in METRICS
        },
    }
    rendered = json.dumps(report, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
