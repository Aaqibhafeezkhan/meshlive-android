"""Convert finalized accepted PCM evidence to a bracketed AudioTrack clock proxy.

benchmark-audio-track-observation-v1 requires run_id/device/session/pid, the
explicit clock 'run:device:pid:System.nanoTime', hashed wav/chunks/summary and
controls maximum_anchor_age_ns, maximum_anchor_gap_ns, maximum_rate_error_ppm,
timestamp_uncertainty_ns. Output is benchmark-pcm-sample-clock-v1. Clock spans
include both anchor endpoints; unknown sample ranges are half-open. Missing or
unstable anchors are never interpolated across, and there is no extrapolation.
"""

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import wave

from benchmark_layout import original, output_path, resolve as resolve_input

FIELDS = ("sample_start", "sample_count", "source_pts_us", "stage", "at_ns", "audio_frame_position", "audio_timestamp_ns")
UNDERRUN_FIELD = "audio_underrun_count"


def integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"{name} must be an integer >= {minimum}")
    return value


def number(value, name, minimum=0):
    if type(value) not in (int, float) or not math.isfinite(value) or value < minimum:
        raise ValueError(f"{name} must be a finite number >= {minimum}")
    return float(value)


def evidence(entry, base):
    if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
        raise ValueError("Evidence needs path and sha256")
    expected = entry.get("sha256", "")
    if not isinstance(expected, str) or len(expected) != 64 or any(c not in "0123456789abcdefABCDEF" for c in expected):
        raise ValueError("Evidence sha256 must be 64 hexadecimal characters")
    path = resolve_input(entry["path"], base=base)
    with path.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected.lower():
        raise ValueError(f"Evidence hash mismatch: {path}")
    return path, {"path": str(path), "sha256": actual}


def unknown_ranges(spans, count):
    covered = []
    for span in spans:
        lo, hi = span["sample_start"], min(count, span["sample_end"] + 1)
        if covered and lo <= covered[-1][1]:
            covered[-1][1] = max(covered[-1][1], hi)
        else:
            covered.append([lo, hi])
    missing, cursor = [], 0
    for lo, hi in covered:
        if lo > cursor:
            missing.append({"start_sample": cursor, "end_sample": lo})
        cursor = max(cursor, hi)
    if cursor < count:
        missing.append({"start_sample": cursor, "end_sample": count})
    return missing


def analyze(spec, base):
    if spec.get("schema") != "benchmark-audio-track-observation-v1":
        raise ValueError("Expected benchmark-audio-track-observation-v1")
    for field in ("run_id", "device", "session"):
        if not isinstance(spec.get(field), str) or not spec[field]:
            raise ValueError(f"Missing {field}")
    pid = integer(spec.get("pid"), "pid", 1)
    clock = f"{spec['run_id']}:{spec['device']}:{pid}:System.nanoTime"
    if spec.get("clock") != clock:
        raise ValueError("Clock must use the exact run:device:pid:System.nanoTime namespace")
    retained = {}
    wav_path, retained["wav"] = evidence(spec["wav"], Path(base))
    chunk_path, retained["chunks"] = evidence(spec["chunks"], Path(base))
    summary_path, retained["summary"] = evidence(spec["summary"], Path(base))
    summary = json.loads(summary_path.read_text(encoding="utf-8-sig"))
    if summary.get("schema") != "benchmark-pcm-trace-v1" or summary.get("pid") != pid or summary.get("session") != spec["session"]:
        raise ValueError("PCM summary schema/session/PID differs from observation")
    if summary.get("clock") != "System.nanoTime" or summary.get("role") != "receiver":
        raise ValueError("Only same-clock receiver accepted PCM has AudioTrack hardware anchors")
    controls = spec.get("controls", {})
    age = integer(controls.get("maximum_anchor_age_ns"), "maximum_anchor_age_ns", 1)
    gap = integer(controls.get("maximum_anchor_gap_ns"), "maximum_anchor_gap_ns", 1)
    ppm = number(controls.get("maximum_rate_error_ppm"), "maximum_rate_error_ppm")
    uncertainty = number(controls.get("timestamp_uncertainty_ns"), "timestamp_uncertainty_ns")
    result = {"schema": "benchmark-pcm-sample-clock-v1", "measurement_status": "UNMEASURED",
              "role": "estimated_track_output", "clock": clock,
              "scope": {field: spec[field] for field in ("run_id", "device", "pid", "session", "clock")},
              "evidence": retained, "controls": controls, "anchors": [], "spans": [],
              "unknown_ranges": [], "diagnostics": [], "issues": [], "measurements": [],
              "limitations": [
                  "AudioTrack timestamps estimate hardware output; they are not acoustic speaker recordings.",
                  "Submitted-sample origin requires trace creation before every write; declared zero origin is checked.",
                  "Interpolation is bounded by two consistent observed anchors; outside brackets remains unknown.",
                  "Changed or unavailable underrun counters prohibit interpolation across a bracket.",
                  "Timestamp uncertainty is supplied, not validated here; no audio-sync acceptance gate is produced.",
                  "No cross-device clock alignment or physical latency is established."]}
    with wave.open(str(wav_path), "rb") as source:
        rate, channels, count = source.getframerate(), source.getnchannels(), source.getnframes()
        result.update({"sample_rate": rate, "channels": channels, "sample_count": count})
        if source.getcomptype() != "NONE" or source.getsampwidth() != 2 or rate != 48000 or channels != 1:
            result["issues"].append("unsupported_format_requires_mono_PCM16_48000Hz")
            result["unknown_ranges"] = [{"start_sample": 0, "end_sample": count}] if count else []
            return result
        for start in range(0, count, 262144):
            expected = min(262144, count - start)
            if len(source.readframes(expected)) != expected * 2:
                raise ValueError("Truncated PCM payload differs from WAV frame count")
    if (any(type(summary.get(field)) is not int for field in ("sample_rate", "channels", "sample_count", "attempted_samples"))
            or summary.get("sample_rate") != rate or summary.get("channels") != channels
            or summary.get("sample_count") != count or summary.get("attempted_samples") != count):
        result["issues"].append("summary_written_attempted_or_format_mismatch")
    if summary.get("finalized") is not True or type(summary.get("trace_overflow_chunks")) is not int or summary.get("trace_overflow_chunks") != 0 or summary.get("error") is not None:
        result["issues"].append("trace_unfinalized_overflow_or_error")
    if type(summary.get("audio_track_initial_written_frames")) is not int or summary.get("audio_track_initial_written_frames") != 0:
        result["issues"].append("submitted_sample_origin_not_proven")
    with chunk_path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        fieldnames = tuple(reader.fieldnames or [])
        if fieldnames not in {FIELDS, (*FIELDS, UNDERRUN_FIELD)}:
            raise ValueError("Unexpected PCM chunk columns")
        rows = list(reader)
    cursor, last_at = 0, None
    for index, row in enumerate(rows):
        row.setdefault(UNDERRUN_FIELD, "")
        for field in (*FIELDS, UNDERRUN_FIELD):
            if field == "stage":
                continue
            if field in {"audio_frame_position", "audio_timestamp_ns", UNDERRUN_FIELD} and row[field] == "":
                row[field] = None
            else:
                row[field] = integer(int(row[field]), field, 1 if field == "sample_count" else 0)
        if row["sample_start"] != cursor or row["stage"] != "track_accepted":
            result["issues"].append(f"noncontiguous_or_wrong_stage_chunk_{index}")
        cursor += row["sample_count"]
        if last_at is not None and row["at_ns"] < last_at:
            result["issues"].append(f"chunk_observation_time_backtrack_{index}")
        last_at = row["at_ns"]
        if (row["audio_frame_position"] is None) != (row["audio_timestamp_ns"] is None):
            result["issues"].append(f"incomplete_anchor_fields_{index}")
    if cursor != count:
        result["issues"].append("CSV_sample_count_differs_from_exact_WAV")
    if result["issues"]:
        result["unknown_ranges"] = [{"start_sample": 0, "end_sample": count}] if count else []
        return result
    previous, segment, origin_lost = None, 0, False
    for index, row in enumerate(rows):
        frame, stamp, at = row["audio_frame_position"], row["audio_timestamp_ns"], row["at_ns"]
        diagnostic = {"chunk_index": index, "sample_start": row["sample_start"], "sample_count": row["sample_count"]}
        reason = None
        if origin_lost:
            reason = "origin_unknown_after_hardware_position_reset"
        elif frame is None:
            reason = "hardware_timestamp_unavailable"
        elif previous is not None and frame < previous["sample_index"]:
            reason = "hardware_position_reset"
            origin_lost = True
        elif (previous is not None and previous["underrun_count"] is not None and row[UNDERRUN_FIELD] is not None
              and row[UNDERRUN_FIELD] < previous["underrun_count"]):
            reason = "hardware_underrun_counter_reset"
            origin_lost = True
        elif frame > row["sample_start"] + row["sample_count"] or frame > count:
            reason = "hardware_position_exceeds_submitted_samples"
        elif stamp > at:
            reason = "hardware_timestamp_after_observation"
        elif at - stamp > age:
            reason = "hardware_timestamp_stale"
        elif previous is not None and frame == previous["sample_index"]:
            if stamp != previous["time_ns"]:
                reason = "same_hardware_position_has_conflicting_time"
            elif row[UNDERRUN_FIELD] != previous["underrun_count"]:
                reason = "changed_or_unknown_underrun_counter_at_cached_anchor"
            else:
                diagnostic.update({"status": "REUSED", "anchor_index": len(result["anchors"]) - 1})
                result["diagnostics"].append(diagnostic)
                continue
        elif previous is not None and stamp <= previous["time_ns"]:
            reason = "hardware_timestamp_backtrack"
        if reason is not None:
            segment += 1
            diagnostic.update({"status": "UNMEASURED", "reason": reason})
            result["diagnostics"].append(diagnostic)
            if reason not in result["issues"]:
                result["issues"].append(reason)
            continue
        anchor = {"sample_index": frame, "time_ns": stamp, "observed_at_ns": at,
                  "uncertainty_ns": uncertainty, "segment": segment, "chunk_index": index,
                  "underrun_count": row[UNDERRUN_FIELD]}
        diagnostic.update({"status": "MEASURED", "anchor_index": len(result["anchors"])})
        result["anchors"].append(anchor)
        result["diagnostics"].append(diagnostic)
        if previous is not None and previous["segment"] == segment:
            duration, samples = stamp - previous["time_ns"], frame - previous["sample_index"]
            rate_error = (duration * rate / (samples * 1e9) - 1) * 1e6
            if anchor["underrun_count"] is None or previous["underrun_count"] is None:
                result["issues"].append("underrun_counter_missing")
            elif anchor["underrun_count"] != previous["underrun_count"]:
                result["issues"].append("underrun_counter_changed")
            elif duration > gap or abs(rate_error) > ppm:
                result["issues"].append("unbracketed_gap_or_rate_change")
            else:
                result["spans"].append({"sample_start": previous["sample_index"], "sample_end": frame,
                    "start_ns": previous["time_ns"], "end_ns": stamp, "endpoint_inclusive": True,
                    "ns_per_sample": duration / samples, "uncertainty_ns": uncertainty,
                    "relative_rate_error_ppm": rate_error, "status": "MEASURED", "basis": "bracketed_linear_hardware_estimate"})
        previous = anchor
    result["unknown_ranges"] = unknown_ranges(result["spans"], count)
    result["measurement_status"] = "MEASURED" if result["spans"] else "UNMEASURED"
    result["timeline_complete"] = bool(count) and not result["unknown_ranges"] and not origin_lost
    result["issues"] = list(dict.fromkeys(result["issues"]))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    args.output = output_path(args.output, "runs")
    try:
        if args.output.exists():
            raise ValueError("Output exists; use a fresh explicit path")
        spec_path = resolve_input(args.spec)
        result = analyze(json.loads(spec_path.read_text(encoding="utf-8-sig")), original(spec_path).parent)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    except (ValueError, OSError, KeyError, TypeError, wave.Error) as error:
        parser.error(str(error))
    print(f"{result['measurement_status']}: {len(result['spans'])} hardware clock brackets; acoustic output UNMEASURED. {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
