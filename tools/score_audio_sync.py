"""Measure supplied PCM cues without inventing screen, acoustic or clock proof.

Input schema: benchmark-audio-observation-v1. Reference wav/events and captured
wav use {path, sha256}; capture.clock is {id, sample_zero_ns}. Timing also requires
capture.timeline={continuity:CONTINUOUS, gaps:[], overflow_samples:0}; missing or
sparse continuity keeps clock/drift arithmetic unmeasured. Each plan declares
stream_generation, loop_iteration, source_start_sample, source_end_sample,
capture_start_sample (nominal mapped interval start) and search_radius_samples.
The search anchor is an association constraint, not a measured source clock.
Optional visual_observations points to a hash-bound visual adapter result with
scope.clock and observations: stream_generation, loop_iteration, file_frame,
marker_status=MATCHED, time_ns. Only an identical clock ID permits A/V arithmetic.
Alternatively capture.sample_clock hashes a benchmark-pcm-sample-clock-v1 from
prepare_audio_track_observation. Its native WAV and source evidence are checked;
only complete consistent brackets yield estimated AudioTrack cue times. Visual
and capture execution_scope must agree whenever either is supplied.
PCM is signed little-endian 16-bit mono at 48 kHz; never implicitly converted.
"""

import argparse
from collections import Counter, defaultdict
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path

from benchmark_layout import original, output_path, resolve as resolve_input
import wave

import numpy as np
from scipy.signal import correlate, find_peaks

RATE = 48000
CHUNK = 262144
MAX_TEMPLATE = RATE * 2
LIMITATIONS = [
    "Cue correlation measures the declared capture signal; decoded PCM is not audible speaker output.",
    "Nominal search anchors do not prove source-to-capture latency or clock synchronization.",
    "A/V offset requires observed visual events in the identical declared capture clock.",
    "Missing cues describe bounded searches, not packet loss; silence between cues is not continuously identifiable.",
    "Echo, microphone routing, double-talk, speech quality and physical glass-to-glass latency remain UNMEASURED.",
    "Fixed templates can miss heavily time-stretched or filtered cues; no resampling or channel mixing is performed.",
]


def integer(value, name, minimum=None):
    if type(value) is not int or (minimum is not None and value < minimum):
        raise ValueError(f"{name} must be an integer" + (f" >= {minimum}" if minimum is not None else ""))
    return value


def number(value, name, minimum=None, maximum=None):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(f"{name} must be finite")
    if minimum is not None and value < minimum or maximum is not None and value > maximum:
        raise ValueError(f"{name} outside permitted range")
    return float(value)


def generation(value):
    if type(value) is int and value >= 0:
        return str(value)
    if isinstance(value, str) and value.strip():
        return value
    raise ValueError("stream_generation must be a nonnegative integer or nonempty string")


def execution_scope(value):
    if value is not None and not isinstance(value, dict):
        raise ValueError("Execution scope must be an object")
    return value


def evidence(entry, base):
    if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
        raise ValueError("Evidence requires path and sha256")
    expected = entry.get("sha256", "")
    if not isinstance(expected, str) or len(expected) != 64 or any(c not in "0123456789abcdefABCDEF" for c in expected):
        raise ValueError("Evidence sha256 must be 64 hexadecimal characters")
    path = resolve_input(entry["path"], base=base)
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(CHUNK), b""):
            digest.update(block)
    if digest.hexdigest() != expected.lower():
        raise ValueError(f"Evidence hash mismatch: {path}")
    return path, {"path": str(path), "sha256": digest.hexdigest()}


class Pcm:
    def __init__(self, path):
        self.wav = wave.open(str(path), "rb")
        self.rate = self.wav.getframerate()
        self.channels = self.wav.getnchannels()
        self.count = self.wav.getnframes()
        self.supported = (self.wav.getcomptype() == "NONE" and self.wav.getsampwidth() == 2
                          and self.channels == 1 and self.rate == RATE)

    def close(self):
        self.wav.close()

    def samples(self, start, count):
        self.wav.setpos(start)
        raw = self.wav.readframes(count)
        if len(raw) != count * 2:
            raise ValueError("Truncated PCM payload does not match WAV header")
        return np.frombuffer(raw, dtype="<i2").astype(np.float64)

    def stats(self):
        clipped, peak, energy = 0, 0, 0.0
        for start in range(0, self.count, CHUNK):
            values = self.samples(start, min(CHUNK, self.count - start))
            clipped += int(np.count_nonzero((values == -32768) | (values == 32767)))
            peak = max(peak, int(np.max(np.abs(values), initial=0)))
            energy += float(np.dot(values, values))
        return {"sample_count": self.count, "sample_rate": self.rate, "channels": self.channels,
                "clipped_samples": clipped, "peak_pcm": peak,
                "rms_pcm": math.sqrt(energy / self.count) if self.count else 0.0}


def search(pcm, template, lo, hi, minimum, margin):
    """Search cue starts inclusively in bounded chunks; keep competing peaks."""
    length = len(template)
    centered = template - np.mean(template)
    energy = float(np.dot(centered, centered))
    if energy <= 0:
        return {"status": "UNMEASURED", "reason": "Reference cue has no AC energy", "candidates": []}
    lo, hi = max(0, lo), min(pcm.count - length, hi)
    if hi < lo:
        return {"status": "UNCOVERED", "reason": "Capture cannot contain a complete cue in search window", "candidates": []}
    peaks, overflow = [], False
    separation = max(1, length // 2)
    for start in range(lo, hi + 1, CHUNK):
        end = min(hi + 1, start + CHUNK)
        values = pcm.samples(start, end - start + length - 1)
        sums = np.r_[0.0, np.cumsum(values)]
        squares = np.r_[0.0, np.cumsum(values * values)]
        variance = squares[length:] - squares[:-length] - (sums[length:] - sums[:-length]) ** 2 / length
        denominator = np.sqrt(np.maximum(variance, 0) * energy)
        numerator = correlate(values, centered, mode="valid", method="fft")
        coefficients = np.divide(numerator, denominator, out=np.zeros_like(numerator), where=denominator > 0)
        # A polarity inversion changes correlation sign, not cue identity/time.
        strengths = np.abs(coefficients)
        # Padding includes peaks exactly at the first/last permitted cue start.
        indices, _ = find_peaks(np.r_[-np.inf, strengths, -np.inf], height=minimum, distance=separation)
        indices -= 1
        peaks.extend((int(start + index), min(1.0, float(strengths[index])), 1 if coefficients[index] >= 0 else -1) for index in indices)
        if len(peaks) > 256:
            overflow = True
            peaks = sorted(peaks, key=lambda row: -row[1])[:128]
    selected = []
    for sample, confidence, polarity in sorted(peaks, key=lambda row: (-row[1], row[0])):
        if all(abs(sample - old[0]) >= separation for old in selected):
            selected.append((sample, confidence, polarity))
            if len(selected) == 3:
                break
    candidates = [{"capture_sample": sample, "correlation": confidence, "polarity": polarity} for sample, confidence, polarity in selected]
    if not selected:
        return {"status": "MISSING", "reason": "No cue above minimum correlation in declared search", "candidates": []}
    if overflow or len(selected) > 1 and selected[0][1] - selected[1][1] < margin:
        return {"status": "AMBIGUOUS", "reason": "Competing cue matches" + ("; candidate bound exceeded" if overflow else ""),
                "candidates": candidates}
    return {"status": "MATCHED", "reason": "Unique correlation peak within declared search constraints",
            "capture_sample": selected[0][0], "correlation": selected[0][1], "polarity": selected[0][2],
            "peak_margin": selected[0][1] - selected[1][1] if len(selected) > 1 else None,
            "candidates": candidates}


def summary(values):
    return {"samples": len(values), "min": min(values) if values else None,
            "p50": float(np.percentile(values, 50)) if values else None,
            "p95": float(np.percentile(values, 95)) if values else None,
            "max": max(values) if values else None}


def sample_clock(spec, base, capture, retained):
    """Return a range mapper; a supplied piecewise clock takes precedence."""
    declared = spec["capture"].get("clock")
    if spec["capture"].get("sample_clock") is not None:
        path, retained["sample_clock"] = evidence(spec["capture"]["sample_clock"], base)
        clock = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(clock, dict) or clock.get("schema") != "benchmark-pcm-sample-clock-v1" or clock.get("role") != "estimated_track_output":
            raise ValueError("Sample clock requires benchmark-pcm-sample-clock-v1 estimated_track_output")
        record = clock.get("evidence", {})
        if not isinstance(record, dict) or not isinstance(record.get("wav"), dict) or not isinstance(record["wav"].get("sha256"), str):
            raise ValueError("Sample clock requires hashed capture WAV evidence")
        if (record["wav"]["sha256"].lower() != retained["capture_wav"]["sha256"]
                or clock.get("sample_count") != capture.count or clock.get("sample_rate") != capture.rate
                or clock.get("channels") != capture.channels):
            raise ValueError("Sample clock WAV/hash/native format differs from actual capture")
        import prepare_audio_track_observation as track
        scope = clock.get("scope", {})
        producer = {"schema": "benchmark-audio-track-observation-v1", **scope, "clock": clock.get("clock"),
                    "wav": clock["evidence"]["wav"], "chunks": clock["evidence"]["chunks"],
                    "summary": clock["evidence"]["summary"], "controls": clock["controls"]}
        verified = track.analyze(producer, original(path).parent)
        for field in ("clock", "scope", "role", "sample_count", "sample_rate", "channels", "spans", "anchors", "unknown_ranges", "measurement_status"):
            if clock.get(field) != verified.get(field):
                raise ValueError(f"Sample clock {field} differs from recomputed capture evidence")
        declared_id = declared.get("id") if isinstance(declared, dict) else declared
        if declared_id is not None and declared_id != clock["clock"]:
            raise ValueError("Declared capture clock differs from hash-bound sample clock")
        capture_scope = execution_scope(spec["capture"].get("execution_scope"))
        clock_scope = execution_scope(clock.get("execution_scope"))
        if capture_scope is not None:
            for field in ("run_id", "device", "pid", "session"):
                if field in capture_scope and str(capture_scope[field]) != str(scope.get(field)):
                    raise ValueError("Capture execution scope differs from sample clock origin")
        if clock_scope is not None and capture_scope != clock_scope:
            raise ValueError("Capture/sample-clock execution scopes differ")
        spans = clock["spans"]
        def mapped(start, end):
            if start < 0 or end < start or end >= capture.count:
                return None
            cursor, used = start, []
            for span in spans:
                if span["sample_end"] < cursor:
                    continue
                if span["sample_start"] > cursor:
                    return None
                used.append(span)
                cursor = max(cursor, span["sample_end"])
                if cursor >= end:
                    break
            if not used or cursor < end:
                return None
            def at(sample):
                span = next(row for row in used if row["sample_start"] <= sample <= row["sample_end"])
                return round(Fraction(span["start_ns"]) + Fraction(sample - span["sample_start"], span["sample_end"] - span["sample_start"]) * (span["end_ns"] - span["start_ns"]))
            return {"start_ns": at(start), "end_ns": at(end), "uncertainty_ns": max(row["uncertainty_ns"] for row in used),
                    "basis": "bracketed_linear_hardware_estimate"}
        return clock["clock"], "estimated_track_output", mapped
    timeline = spec["capture"].get("timeline", {"continuity": "UNKNOWN"})
    continuous = (timeline.get("continuity") == "CONTINUOUS" and timeline.get("gaps") == []
                  and type(timeline.get("overflow_samples")) is int and timeline["overflow_samples"] == 0)
    valid = (continuous and isinstance(declared, dict) and isinstance(declared.get("id"), str)
             and bool(declared["id"]) and type(declared.get("sample_zero_ns")) is int)
    uncertainty = number(declared.get("uncertainty_ns", 0), "capture clock uncertainty_ns", 0) if valid else 0
    def mapped(start, end):
        if not valid or start < 0 or end < start or end >= capture.count:
            return None
        return {"start_ns": declared["sample_zero_ns"] + start * 1_000_000_000 // RATE,
                "end_ns": declared["sample_zero_ns"] + end * 1_000_000_000 // RATE,
                "uncertainty_ns": uncertainty, "basis": "declared_continuous_capture_clock"}
    return declared["id"] if valid else None, spec["capture"].get("role", "UNSPECIFIED"), mapped


def fitted_drift(rows, hardware=False):
    available = [row for row in rows if not hardware or "capture_time_ns" in row]
    if len(available) < 2 or len({row["source_sample"] for row in available}) < 2:
        return {"status": "UNMEASURED", "reason": "At least two distinct timed/matched source cues required"}
    x = np.array([row["source_sample"] for row in available], dtype=np.float64)
    origin = available[0]["capture_time_ns"] if hardware else 0
    y = np.array([row["capture_time_ns"] - origin if hardware else row["capture_sample"] for row in available], dtype=np.float64)
    slope = float(np.dot(x - np.mean(x), y - np.mean(y)) / np.dot(x - np.mean(x), x - np.mean(x)))
    residual = y - (np.mean(y) + slope * (x - np.mean(x)))
    factor = RATE / 1e9 if hardware else 1
    uncertainty = (sum(abs(value - np.mean(x)) * row["capture_time_uncertainty_ns"] for value, row in zip(x, available))
                   / np.dot(x - np.mean(x), x - np.mean(x)) * factor * 1_000_000) if hardware else 0
    return {"status": "MEASURED", "samples": len(available), "source_span_seconds": float(np.ptp(x) / RATE),
            "relative_drift_ppm": (slope * factor - 1) * 1_000_000,
            "uncertainty_ppm": float(uncertainty),
            "fit_residual_rms_ms": float(np.sqrt(np.mean(residual ** 2)) / 1e6 if hardware else np.sqrt(np.mean(residual ** 2)) * 1000 / RATE)}


def analyze(spec, base):
    base = Path(base)
    if spec.get("schema") != "benchmark-audio-observation-v1":
        raise ValueError("Expected benchmark-audio-observation-v1")
    retained = {}
    reference_path, retained["reference_wav"] = evidence(spec["reference"]["wav"], base)
    event_path, retained["events"] = evidence(spec["reference"]["events"], base)
    capture_path, retained["capture_wav"] = evidence(spec["capture"]["wav"], base)
    document = json.loads(event_path.read_text(encoding="utf-8-sig"))
    schedule = document.get("audio_schedule", {})
    if document.get("schema") != "vsg-events-v1" or schedule.get("schema") != "vsg-audio-schedule-v1":
        raise ValueError("Reference events must declare VSG event/audio schemas")
    result = {"schema": "benchmark-audio-sync-v1", "measurement_status": "UNMEASURED",
              "scope": {"capture_role": spec["capture"].get("role", "UNSPECIFIED"),
                        "clock": spec["capture"].get("clock"), "source_to_capture_latency": "UNMEASURED"},
              "evidence": retained, "events": [], "metrics": {}, "measurements": [], "limitations": list(LIMITATIONS)}
    reference, capture = Pcm(reference_path), None
    try:
        capture = Pcm(capture_path)
        if not reference.supported or not capture.supported:
            result["limitations"].append("Unsupported PCM format: require mono signed PCM16 48000 Hz; explicit conversion needed")
            return result
        if schedule.get("enabled") is not True or schedule.get("sample_rate") != RATE or schedule.get("channels") != 1:
            raise ValueError("Reference schedule must enable mono 48000 Hz audio")
        if schedule.get("sample_count") != reference.count:
            raise ValueError("Reference schedule sample_count differs from actual WAV")
        cues, workloads, ids = [], [], set()
        for entry in schedule.get("events", []):
            cue = dict(entry)
            cue_id = cue.get("id")
            if not isinstance(cue_id, str) or not cue_id or cue_id in ids:
                raise ValueError("Reference cue IDs must be nonempty and unique")
            ids.add(cue_id)
            start = integer(cue.get("sample_index"), "cue sample_index", 0)
            count = integer(cue.get("sample_count"), "cue sample_count", 1)
            integer(cue.get("file_frame"), "cue file_frame", 0)
            if start + count > reference.count:
                raise ValueError("Reference event is outside WAV")
            if cue.get("kind") not in {"beep", "chirp", "sweep", "noise"}:
                raise ValueError("Unsupported reference cue kind")
            if cue.get("role", "cue") == "workload":
                workloads.append(cue)
                continue
            if cue.get("role", "cue") != "cue":
                raise ValueError("Reference event role must be cue or workload")
            if count > MAX_TEMPLATE:
                raise ValueError("Reference cue is outside WAV or exceeds bounded 2 second template")
            cues.append(cue)
        result["reference_workloads"] = workloads
        if workloads:
            result["limitations"].append("Declared sustained workload drives audio activity; this cue scorer does not measure continuous waveform fidelity or every dropout.")
        plans = spec.get("plans")
        if not isinstance(plans, list) or not plans:
            raise ValueError("Explicit planned source intervals/loop/generation association required")
        config = spec.get("correlation", {})
        minimum = number(config.get("minimum", 0.80), "correlation minimum", 0.0, 1.0)
        margin = number(config.get("peak_margin", 0.05), "correlation peak_margin", 0.0, 1.0)
        if minimum == 0 or margin == 0:
            raise ValueError("Correlation minimum and ambiguity margin must be positive")
        result["metrics"]["capture_pcm"] = capture.stats()
        reference.stats()  # Also verify the complete reference payload, including unused tails.
        uncovered, identities = 0, set()
        for plan_index, plan in enumerate(plans):
            current_generation = generation(plan.get("stream_generation"))
            loop = integer(plan.get("loop_iteration"), "loop_iteration", 0)
            a = integer(plan.get("source_start_sample"), "source_start_sample", 0)
            b = integer(plan.get("source_end_sample"), "source_end_sample", 1)
            anchor = integer(plan.get("capture_start_sample"), "capture_start_sample")
            radius = integer(plan.get("search_radius_samples"), "search_radius_samples", 0)
            if b <= a or b > reference.count:
                raise ValueError("Planned source interval outside exact reference")
            mapped_end = anchor + b - a
            missing = b - a - max(0, min(capture.count, mapped_end) - max(0, anchor))
            uncovered += missing
            interval = {**plan, "stream_generation": current_generation, "nominal_capture_end_sample": mapped_end, "uncovered_samples": missing,
                        "coverage_basis": "declared nominal anchor, not measured latency"}
            result.setdefault("plans", []).append(interval)
            for cue in cues:
                start, count = cue["sample_index"], cue["sample_count"]
                if start >= b or start + count <= a:
                    continue
                identity = (current_generation, loop, cue["id"])
                if identity in identities:
                    raise ValueError("Repeated planned cue identity; split intervals must not duplicate cues")
                identities.add(identity)
                nominal = anchor + start - a
                row = {"id": cue["id"], "stream_generation": current_generation, "loop_iteration": loop, "plan_index": plan_index,
                       "file_frame": cue["file_frame"], "source_sample": start, "source_sample_count": count,
                       "nominal_capture_sample": nominal, "search_start_sample": nominal - radius,
                       "search_end_sample": nominal + radius}
                if start < a or start + count > b:
                    matched = {"status": "UNMEASURED", "reason": "Planned source interval clips a reference cue", "candidates": []}
                else:
                    matched = search(capture, reference.samples(start, count), nominal - radius, nominal + radius, minimum, margin)
                row.update(matched)
                if row["status"] == "MATCHED":
                    row["nominal_offset_ms"] = (row["capture_sample"] - nominal) * 1000 / RATE
                result["events"].append(row)
        associations = defaultdict(list)
        for row in result["events"]:
            if row["status"] == "MATCHED":
                associations[row["capture_sample"]].append(row)
        for duplicate in associations.values():
            if len(duplicate) > 1:
                for row in duplicate:
                    row["status"] = "AMBIGUOUS"
                    row["reason"] = "Same captured cue assigned to multiple planned identities"
                    row.pop("nominal_offset_ms", None)
        timeline = spec["capture"].get("timeline", {"continuity": "UNKNOWN"})
        if not isinstance(timeline, dict) or timeline.get("continuity") not in {"CONTINUOUS", "SPARSE", "UNKNOWN"}:
            raise ValueError("Capture timeline continuity must be CONTINUOUS, SPARSE or UNKNOWN")
        result["scope"]["capture_timeline"] = timeline
        clock_id, timing_role, mapped = sample_clock(spec, base, capture, retained)
        result["scope"].update({"clock": clock_id, "timing_role": timing_role,
                                "capture_sample_clock_status": "MEASURED" if clock_id is not None else "UNMEASURED"})
        visual = None
        if spec.get("visual_observations") is not None:
            visual_path, retained["visual_observations"] = evidence(spec["visual_observations"], base)
            visual = json.loads(visual_path.read_text(encoding="utf-8-sig"))
        visual_clock = visual.get("scope", {}).get("clock") if isinstance(visual, dict) else None
        if isinstance(visual_clock, dict):
            visual_clock = visual_clock.get("id")
        capture_execution = execution_scope(spec["capture"].get("execution_scope"))
        visual_execution = execution_scope(visual.get("execution_scope")) if isinstance(visual, dict) else None
        same_execution = (capture_execution is None and visual_execution is None) or capture_execution == visual_execution
        same_clock = (clock_id is not None and visual_clock == clock_id and same_execution and isinstance(visual, dict)
                      and visual.get("schema") == "benchmark-visual-markers-v1"
                      and visual.get("scope", {}).get("timing_status") == "MEASURED")
        observed = defaultdict(list)
        if same_clock:
            for row in visual.get("observations", []):
                if row.get("marker_status") == "MATCHED" and type(row.get("time_ns")) is int:
                    observed[(generation(row.get("stream_generation")), row.get("loop_iteration"), row.get("file_frame"))].append(row["time_ns"])
        offsets = []
        for row in result["events"]:
            row["av_status"] = "UNMEASURED"
            if row["status"] != "MATCHED":
                row["av_reason"] = "Audio cue missing or ambiguous"
                continue
            cue_clock = mapped(row["capture_sample"], row["capture_sample"] + row["source_sample_count"] - 1)
            if cue_clock is not None:
                row.update({"capture_time_ns": cue_clock["start_ns"], "capture_end_time_ns": cue_clock["end_ns"],
                            "capture_time_uncertainty_ns": cue_clock["uncertainty_ns"], "timing_basis": cue_clock["basis"]})
            visual_times = observed.get((row["stream_generation"], row["loop_iteration"], row["file_frame"]), [])
            if cue_clock is None:
                row["av_reason"] = "Cue is not wholly covered by a valid capture clock bracket"
            elif not same_execution:
                row["av_reason"] = "Visual and PCM execution scopes differ or one is missing"
            elif not same_clock:
                row["av_reason"] = "Missing visual observations or identical declared capture clock"
            elif len(visual_times) != 1:
                row["av_reason"] = "Missing or ambiguous observed visual event"
            else:
                row["av_status"] = "MEASURED"
                row["visual_time_ns"] = visual_times[0]
                row["audio_minus_video_ms"] = (row["capture_time_ns"] - visual_times[0]) / 1_000_000
                row["av_timing_role"] = timing_role
                offsets.append(row["audio_minus_video_ms"])
        result["scope"]["capture_sample_clock_status"] = "MEASURED" if any("capture_time_ns" in row for row in result["events"]) else "UNMEASURED"
        drift, content_drift = [], []
        grouped = defaultdict(list)
        for row in result["events"]:
            if row["status"] == "MATCHED":
                grouped[(row["stream_generation"], row["loop_iteration"], row["plan_index"])].append(row)
        for identity, rows in sorted(grouped.items()):
            group_scope = {"stream_generation": identity[0], "loop_iteration": identity[1], "plan_index": identity[2]}
            content_drift.append({**group_scope, **fitted_drift(rows), "basis": "captured_file_sample_spacing; no elapsed-clock claim"})
            span_start = min(row["capture_sample"] for row in rows)
            span_end = max(row["capture_sample"] + row["source_sample_count"] - 1 for row in rows)
            qualified = len(rows) >= 2 and all("capture_time_ns" in row for row in rows) and mapped(span_start, span_end) is not None
            drift.append({**group_scope, **(fitted_drift(rows, hardware=True) if qualified else {
                "status": "UNMEASURED", "reason": "Whole cue interval requires valid continuous or piecewise clock coverage"}),
                          "basis": "source declared sample schedule versus capture-clock elapsed", "timing_role": timing_role})
        counts = dict(Counter(row["status"] for row in result["events"]))
        result["metrics"].update({"cue_counts": counts, "planned_cues": len(result["events"]),
                                 "nominal_uncovered_samples": uncovered, "audio_minus_video_ms": summary(offsets),
                                 "wall_clock_drift": drift, "sample_content_drift": content_drift,
                                 "correlation": {"minimum": minimum, "peak_margin": margin},
                                 "planned_timeline_complete": bool(result["events"]) and counts.get("MATCHED", 0) == len(result["events"]) and uncovered == 0})
        result["metrics"]["planned_clock_interval_complete"] = all(mapped(plan["capture_start_sample"], plan["nominal_capture_end_sample"] - 1) is not None for plan in result["plans"])
        matched = counts.get("MATCHED", 0)
        result["measurement_status"] = "MEASURED" if matched else "UNMEASURED"
        values = {"audio_missing_cues": counts.get("MISSING", 0) + counts.get("UNCOVERED", 0),
                  "audio_ambiguous_cues": counts.get("AMBIGUOUS", 0), "audio_uncovered_samples": uncovered,
                  "audio_clipped_samples": result["metrics"]["capture_pcm"]["clipped_samples"],
                  "audio_av_offset_abs_ms": max((abs(row["audio_minus_video_ms"]) + row["capture_time_uncertainty_ns"] / 1e6 for row in result["events"] if row["av_status"] == "MEASURED"), default=None),
                  "audio_drift_abs_ppm": max((abs(x["relative_drift_ppm"]) + x["uncertainty_ppm"] for x in drift if x["status"] == "MEASURED"), default=None)}
        validation = None
        if spec.get("acceptance") is not None:
            validation_path, retained["detector_validation"] = evidence(spec["acceptance"]["validation"], base)
            validation = json.loads(validation_path.read_text(encoding="utf-8-sig"))
            if validation.get("schema") != "benchmark-audio-detector-validation-v1" or validation.get("status") != "VALIDATED":
                raise ValueError("Acceptance requires retained benchmark-audio-detector-validation-v1 VALIDATED evidence")
            if validation.get("correlation") != result["metrics"]["correlation"]:
                raise ValueError("Detector validation correlation controls differ from observation")
        for name, value in values.items():
            limit = spec.get("acceptance", {}).get("limits", {}).get(name)
            validated = validation is not None and name in validation.get("validated_measurements", [])
            usable = value is not None and bool(result["events"]) and (matched > 0 or name in {"audio_missing_cues", "audio_clipped_samples", "audio_uncovered_samples"})
            if name in {"audio_missing_cues", "audio_ambiguous_cues"}:
                usable = usable and counts.get("UNMEASURED", 0) == 0
            if name in {"audio_av_offset_abs_ms", "audio_drift_abs_ppm"}:
                usable = usable and result["metrics"]["planned_timeline_complete"] and result["metrics"]["planned_clock_interval_complete"]
                if name == "audio_av_offset_abs_ms":
                    usable = usable and len(offsets) == len(result["events"])
                else:
                    usable = usable and all(x["status"] == "MEASURED" for x in drift)
            gate = "UNMEASURED"
            reason = "Measured value; acceptance tolerance has not been validated" if value is not None else "Required measurement missing"
            if validated and limit is not None and usable:
                limit = number(limit, name + " limit", 0)
                gate = "PASS" if value <= limit else "FAIL"
                reason = "Compared with predeclared validated maximum"
            result["measurements"].append({"name": name, "family": "acoustic", "status": gate,
                                           "measurement_status": "MEASURED" if value is not None else "UNMEASURED",
                                           "value": value, "limit": limit, "reason": reason})
        result["measurements"].append({"name": "acoustic_quality", "family": "acoustic", "status": "UNMEASURED",
                                       "value": None, "reason": "Cue sync does not measure routing, echo, double-talk or speech fidelity"})
        return result
    finally:
        reference.close()
        if capture is not None:
            capture.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    args.output = output_path(args.output, "runs")
    try:
        if args.output.exists():
            raise ValueError("Output already exists; use a fresh explicit path")
        spec_path = resolve_input(args.spec)
        spec = json.loads(spec_path.read_text(encoding="utf-8-sig"))
        result = analyze(spec, original(spec_path).parent)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    except (ValueError, OSError, KeyError, TypeError, wave.Error) as error:
        parser.error(str(error))
    print(f"{result['measurement_status']}: {result['metrics'].get('cue_counts', {})}; acoustic quality UNMEASURED. {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
