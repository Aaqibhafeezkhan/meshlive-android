"""Read native VSG1 markers without resizing, identity guessing or clock inference."""

import argparse
import csv
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import zlib

import numpy as np

from benchmark_layout import original, output_path, resolve as resolve_input


SCHEMA = "benchmark-visual-markers-v1"
FRAME_FIELDS = ("file_frame", "frame_id", "source_frame_id", "source_frame", "segment_id",
                "pattern_id", "repeat_index", "pts_numerator", "pts_denominator", "i420_sha256",
                "unmarked_i420_sha256", "rgb_sha256", "marked_rgb_sha256")
SAMPLE_FIELDS = ("sample_index", "stream_generation", "loop_iteration", "time_ns")


def integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"{name} must be an integer >= {minimum}")
    return value


def csv_integer(value, name, minimum=0):
    if not isinstance(value, str) or not re.fullmatch(r"-?[0-9]+", value):
        raise ValueError(f"{name} must be decimal integer text")
    return integer(int(value), name, minimum)


def fraction(value, name):
    if not isinstance(value, dict):
        raise ValueError(f"{name} must declare numerator and denominator")
    num = integer(value.get("numerator"), name + ".numerator")
    den = integer(value.get("denominator"), name + ".denominator", 1)
    result = Fraction(num, den)
    if (result.numerator, result.denominator) != (num, den):
        raise ValueError(f"{name} must be reduced")
    return result


def file_record(spec, base):
    if not isinstance(spec, dict) or not isinstance(spec.get("path"), str) or not spec["path"]:
        raise ValueError("Every media/sidecar needs a nonempty path")
    if not isinstance(spec.get("sha256"), str) or not re.fullmatch(r"[a-fA-F0-9]{64}", spec["sha256"]):
        raise ValueError("Every media/sidecar needs an exact SHA256")
    path = resolve_input(spec["path"], base=base)
    with path.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != spec["sha256"].lower():
        raise ValueError(f"SHA256 mismatch: {path}")
    return {"path": str(path), "sha256": actual, "bytes": path.stat().st_size}


def read_csv(record, fields):
    with Path(record["path"]).open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(fields):
            raise ValueError(f"Exact CSV fields required: {','.join(fields)}")
        rows = list(reader)
    if any(None in row or any(value is None for value in row.values()) for row in rows):
        raise ValueError("CSV row width differs from its header")
    return rows


def marker_bounds(layout, width, height):
    """Validate a predeclared native marker position; never search for a better region."""
    if layout.get("status") != "AVAILABLE":
        if layout.get("bounds_xywh") is not None:
            raise ValueError("Unavailable marker cannot declare marker bounds")
        return None
    cell = integer(layout.get("cell_size"), "cell_size", 2)
    rect = layout.get("bounds_xywh")
    if cell % 2 or layout.get("cells") != [26, 25] or not isinstance(rect, list) or len(rect) != 4 or any(type(n) is not int for n in rect):
        raise ValueError("Expected declared native marker bounds/cells")
    x, y, mw, mh = rect
    if min(x, y) < 0 or x % 2 or y % 2 or [mw, mh] != [26 * cell, 25 * cell] or x + mw > width or y + mh > height:
        raise ValueError("Declared native marker bounds must be even and fit the exact frame")
    return rect


def decode_marker(image, layout, mapped_cells=None):
    """Decode actual pixels; optional independently calibrated raster cell mapping."""
    if layout.get("status") != "AVAILABLE":
        return {"status": "UNMEASURED", "reason": layout.get("reason") or "Marker unavailable"}
    cell = layout["cell_size"]
    a = np.asarray(image)
    if mapped_cells is None:
        try:
            x, y, mw, mh = marker_bounds(layout, a.shape[1], a.shape[0])
        except ValueError as error:
            return {"status": "AMBIGUOUS", "reason": str(error)}
        patch = a[y:y + mh, x:x + mw]
        if patch.ndim == 3:
            patch = patch.mean(axis=2)
        if patch.shape != (25 * cell, 26 * cell):
            return {"status": "AMBIGUOUS", "reason": "Native marker geometry mismatch"}
        means = patch.reshape(25, cell, 26, cell).mean(axis=(1, 3))
    else:
        pixels = a.reshape(-1, 3).mean(axis=1)
        means = np.array([pixels[indices].mean() for indices in mapped_cells]).reshape(25, 26)
    if np.any((means > 80) & (means < 175)):
        return {"status": "AMBIGUOUS", "reason": "Intermediate cell luminance"}
    grid = means >= 175
    if (not grid[0].all() or not np.array_equal(grid[-1], np.arange(26) % 2 == 1)
            or grid[1:-1, 0].any() or not np.array_equal(grid[1:-1, -1], np.arange(23) % 2 == 1)):
        return {"status": "AMBIGUOUS", "reason": "Invalid marker border/orientation"}
    triples = grid[1:-1, 1:-1].reshape(-1, 3)
    payload = np.packbits(triples.sum(axis=1) >= 2).tobytes()
    if zlib.crc32(payload[:-4]) != struct.unpack(">I", payload[-4:])[0]:
        return {"status": "AMBIGUOUS", "reason": "CRC32 mismatch"}
    magic, version, frame, num, den = struct.unpack(">HBIQI", payload[:-4])
    if magic != 0x5653 or version != 1 or den == 0 or math.gcd(num, den) != 1:
        return {"status": "AMBIGUOUS", "reason": "Invalid payload fields or unreduced PTS"}
    return {"status": "DECODED", "file_frame": frame, "pts_numerator": num,
            "pts_denominator": den, "corrected_triplets": int(np.count_nonzero(triples.any(axis=1) != triples.all(axis=1)))}


def mapped_marker_cells(record, ref_record, layout, w, h, capture_w, capture_h, evidence):
    """Select captured pixel centers inside declared cells, without image resampling."""
    path = Path(record["path"])
    mapping = json.loads(path.read_text(encoding="utf-8-sig"))
    if mapping.get("schema") != "benchmark-marker-mapping-v1" or mapping.get("method") != "independent_calibration":
        raise ValueError("Screen mapping requires benchmark-marker-mapping-v1 independent calibration")
    if mapping.get("reference_sha256") != ref_record["sha256"] or mapping.get("reference_geometry") != [w, h] or mapping.get("capture_geometry") != [capture_w, capture_h]:
        raise ValueError("Calibrated marker mapping does not match reference/capture hash and geometry")
    evidence["mapping_source"] = file_record(mapping.get("source_evidence"), original(path).parent)
    matrix = mapping.get("affine")
    if not isinstance(matrix, list) or len(matrix) != 2 or any(not isinstance(row, list) or len(row) != 3 or
            any(type(value) not in (int, float) or not math.isfinite(value) for value in row) for row in matrix):
        raise ValueError("Only explicit finite 2x3 affine mapping is supported; no perspective/search")
    a = np.asarray(matrix, float)
    cell = layout["cell_size"]
    if np.linalg.svd(a[:, :2], compute_uv=False).min() * cell < 2:
        raise ValueError("Mapped marker cells must span at least two capture pixels")
    mx, my, mw, mh = marker_bounds(layout, w, h)
    corners = np.array([[mx, my, 1], [mx + mw, my, 1], [mx, my + mh, 1], [mx + mw, my + mh, 1]]) @ a.T
    if np.any(corners < 0) or np.any(corners[:, 0] > capture_w) or np.any(corners[:, 1] > capture_h):
        raise ValueError("Mapped marker is cropped outside the declared capture")
    x0, y0 = np.floor(corners.min(axis=0)).astype(int)
    x1, y1 = np.ceil(corners.max(axis=0)).astype(int)
    yy, xx = np.mgrid[y0:y1, x0:x1]
    points = np.column_stack((xx.ravel() + .5, yy.ravel() + .5))
    native = (points - a[:, 2]) @ np.linalg.inv(a[:, :2]).T
    cols, rows = np.floor((native[:, 0] - mx) / cell).astype(int), np.floor((native[:, 1] - my) / cell).astype(int)
    flattened = yy.ravel() * capture_w + xx.ravel()
    selections = [flattened[(rows == row) & (cols == col)] for row in range(25) for col in range(26)]
    if any(len(indices) < 4 for indices in selections):
        raise ValueError("Every calibrated marker cell needs at least four actual captured pixels")
    return selections


def raw_frames(record, width, height, count, fmt):
    frame_bytes = width * height * 3 if fmt == "RGB24" else width * height * 3 // 2
    if record["bytes"] != count * frame_bytes:
        raise ValueError("Raw size must equal the declared whole frame count; no padding/truncation")
    with Path(record["path"]).open("rb") as source:
        for _ in range(count):
            data = source.read(frame_bytes)
            a = np.frombuffer(data, np.uint8)
            image = a.reshape(height, width, 3) if fmt == "RGB24" else a[:width * height].reshape(height, width)
            yield data, image


def validate_reference(ref, base):
    if not isinstance(ref, dict) or ref.get("format") != "I420" or type(ref.get("bit_depth")) is not int or ref["bit_depth"] != 8:
        raise ValueError("Reference must explicitly declare 8-bit I420")
    w, h, fps, count = (integer(ref.get(k), k, 1) for k in ("width", "height", "fps", "frame_count"))
    if w % 2 or h % 2:
        raise ValueError("Native I420 geometry must be even")
    reference = file_record(ref, base)
    sidecars = ref.get("sidecars", {})
    records = {name: file_record(sidecars.get(name), base) for name in ("frames", "events", "marker_layout")}
    frames = read_csv(records["frames"], FRAME_FIELDS)
    if len(frames) != count:
        raise ValueError("Frame sidecar count differs from native reference")
    ids = set()
    for index, row in enumerate(frames):
        for name in ("file_frame", "source_frame", "repeat_index", "pts_numerator", "pts_denominator"):
            row[name] = csv_integer(row[name], name, 1 if name == "pts_denominator" else 0)
        pts = Fraction(row["pts_numerator"], row["pts_denominator"])
        if row["file_frame"] != index or pts != Fraction(index, fps) or (pts.numerator, pts.denominator) != (row["pts_numerator"], row["pts_denominator"]):
            raise ValueError("Frame sidecar must preserve sequential native indices and reduced exact PTS")
        if any(not row[k] for k in ("frame_id", "source_frame_id", "segment_id", "pattern_id")) or row["frame_id"] in ids:
            raise ValueError("Frame IDs must be nonempty and unique; source identity must be declared")
        ids.add(row["frame_id"])
        if any(not re.fullmatch(r"[0-9a-fA-F]{64}", row[k]) for k in FRAME_FIELDS if k.endswith("sha256")):
            raise ValueError("Frame sidecar needs exact pixel hashes")
    layout = json.loads(Path(records["marker_layout"]["path"]).read_text(encoding="utf-8-sig"))
    if layout.get("schema") != "vsg-marker-v1" or layout.get("status") not in {"AVAILABLE", "DISABLED", "UNMEASURED"}:
        raise ValueError("Expected vsg-marker-v1 availability declaration")
    cell = integer(layout.get("cell_size"), "cell_size", 2)
    if cell % 2 or layout.get("cells") != [26, 25] or layout.get("content_roi", {}).get("geometry") != [w, h]:
        raise ValueError("Marker native geometry/layout conflicts with reference")
    marker_bounds(layout, w, h)
    for row, (data, image) in zip(frames, raw_frames(reference, w, h, count, "I420")):
        if hashlib.sha256(data).hexdigest() != row["i420_sha256"].lower():
            raise ValueError("Reference bytes disagree with frame pixel hash")
        if layout["status"] == "AVAILABLE":
            decoded = decode_marker(image, layout)
            if decoded.get("status") != "DECODED" or any(decoded[k] != row[k] for k in ("file_frame", "pts_numerator", "pts_denominator")):
                raise ValueError("Reference marker disagrees with native frame identity/PTS")
    document = json.loads(Path(records["events"]["path"]).read_text(encoding="utf-8-sig"))
    if document.get("schema") != "vsg-events-v1" or not isinstance(document.get("events"), list):
        raise ValueError("Expected vsg-events-v1 events array")
    panels = None
    if "panels" in sidecars:
        records["panels"] = file_record(sidecars["panels"], base)
        panels = json.loads(Path(records["panels"]["path"]).read_text(encoding="utf-8-sig"))
        if panels.get("schema") != "vsg-panels-v1" or panels.get("geometry") != {"width": w, "height": h, "fps": fps}:
            raise ValueError("Panel event declarations differ from the native reference profile")
    for event in document["events"]:
        if not isinstance(event, dict) or not isinstance(event.get("kind"), str) or not event["kind"]:
            raise ValueError("Each expected event needs its kind")
        if event["kind"] == "file_loop_seam":
            if event.get("boundary_after_file_frame") != count - 1 or event.get("next_file_frame") != 0 or event.get("conditional_on_consumer_loop") is not True or fraction(event.get("at_file_time"), "at_file_time") != Fraction(count, fps):
                raise ValueError("Conditional loop seam must match the exact native duration")
            sample_frame = count
        else:
            index = integer(event.get("file_frame"), "event.file_frame")
            if index >= count or fraction(event.get("pts"), "event.pts") != Fraction(index, fps):
                raise ValueError("Event identity/PTS differs from reference frames")
            if any(event.get(k) != frames[index][k] for k in ("segment_id", "source_frame", "repeat_index")):
                raise ValueError("Event source identity differs from its exact frame")
            if event.get("panel_id") is not None:
                declared = [panel for segment in (panels or {}).get("segments", [])
                    if segment.get("segment_id") == event["segment_id"] and segment.get("file_frame_start", -1) <= index < segment.get("file_frame_end", -1)
                    for panel in segment.get("panels", []) if panel.get("id") == event["panel_id"]]
                if len(declared) != 1 or frames[index]["pattern_id"] != "mosaic" or any(
                        event.get(key) != declared[0].get(key) for key in ("pattern_id", "bounds_xywh")):
                    raise ValueError("Panel event differs from its exact hash-bound native panel/frame")
            elif event.get("pattern_id") != frames[index]["pattern_id"]:
                raise ValueError("Event source identity differs from its exact frame")
            sample_frame = index
        if integer(event.get("audio_sample_index_48k"), "audio_sample_index_48k") != sample_frame * 48000 // fps:
            raise ValueError("Event audio index differs from native PTS quantization")
    return w, h, fps, count, frames, layout, document, {"reference": reference, **records}


def planned_slots(plan, count, fps):
    if not isinstance(plan, dict):
        raise ValueError("Exact planned interval and explicit segments are required")
    start, end = (integer(plan.get(k), k) for k in ("start_ns", "end_ns"))
    if end <= start or not isinstance(plan.get("segments"), list) or not plan["segments"]:
        raise ValueError("Planned interval/segments must be nonempty")
    slots, segments, prior_end = {}, [], Fraction(start)
    for number, segment in enumerate(plan["segments"]):
        generation = segment.get("stream_generation")
        if not isinstance(generation, str) or not generation:
            raise ValueError("Each planned segment needs an explicit stream generation")
        loop = integer(segment.get("loop_iteration"), "loop_iteration")
        first = integer(segment.get("first_file_frame"), "first_file_frame")
        n = integer(segment.get("frame_count"), "segment.frame_count", 1)
        beginning = integer(segment.get("start_ns"), "segment.start_ns")
        ending = Fraction(beginning) + Fraction(n * 1_000_000_000, fps)
        if first + n > count or beginning < prior_end or ending > end:
            raise ValueError("Planned segments must be ordered, native, nonoverlapping and within the full interval")
        segment_row = {**segment, "segment_index": number, "end_ns": {"numerator": ending.numerator, "denominator": ending.denominator}, "keys": []}
        for offset in range(n):
            key = (generation, loop, first + offset)
            if key in slots:
                raise ValueError("A repeated file frame needs a distinct loop iteration or stream generation")
            slots[key] = {"rank": len(slots), "segment_index": number,
                          "target_ns": Fraction(beginning) + Fraction(offset * 1_000_000_000, fps)}
            segment_row["keys"].append(key)
        segments.append(segment_row)
        prior_end = ending
    return slots, segments


def quantile(values, q):
    if not values:
        return None
    values = sorted(values)
    index = (len(values) - 1) * q
    left = int(index)
    return float(values[left] + (values[min(left + 1, len(values) - 1)] - values[left]) * (index - left))


def analyze(spec, base):
    w, h, fps, count, frames, layout, events, evidence = validate_reference(spec.get("reference"), base)
    observed = spec.get("observed", {})
    capture_w, capture_h = (integer(observed.get(k), "observed." + k, 1) for k in ("width", "height"))
    mapped_cells = None
    sampling = observed.get("sampling", "frame_outputs")
    if spec.get("mapping") is not None:
        if observed.get("format") != "RGB24" or observed.get("stage") not in {"screen_capture", "external_recording"} or sampling != "screen_samples":
            raise ValueError("Mapped RGB24 needs declared screen_capture/external_recording stage and screen_samples")
        if layout["status"] != "AVAILABLE":
            raise ValueError("Calibrated mapping requires an available marker")
        evidence["mapping"] = file_record(spec["mapping"], base)
        mapped_cells = mapped_marker_cells(evidence["mapping"], evidence["reference"], layout, w, h, capture_w, capture_h, evidence)
    elif observed.get("format") not in {"I420", "RGB24"} or (capture_w, capture_h) != (w, h) or sampling != "frame_outputs":
        raise ValueError("Observed native geometry/format must match reference; no resize/crop/substitution")
    n = integer(observed.get("frame_count"), "observed.frame_count")
    evidence["observed"] = file_record(observed, base)
    evidence["samples"] = file_record(spec.get("samples"), base)
    samples = read_csv(evidence["samples"], SAMPLE_FIELDS)
    if len(samples) != n:
        raise ValueError("Samples sidecar must contain exactly one row per observed raw frame")
    plan = spec.get("planned")
    slots, segments = planned_slots(plan, count, fps)
    clock = plan.get("clock")
    timing_reasons = []
    if not isinstance(clock, str) or not clock:
        timing_reasons.append("One explicit capture clock is required; no clock synchronization is inferred")
    for index, row in enumerate(samples):
        if csv_integer(row["sample_index"], "sample_index") != index or not row["stream_generation"]:
            raise ValueError("Samples need sequential exact indices and explicit stream generations")
        row["sample_index"] = index
        row["loop_iteration"] = csv_integer(row["loop_iteration"], "loop_iteration")
        try:
            row["time_ns"] = csv_integer(row["time_ns"], "time_ns")
        except ValueError:
            row["time_ns"] = None
            timing_reasons.append(f"Sample {index} lacks a valid timestamp")
    times = [row["time_ns"] for row in samples]
    if not timing_reasons and (any(b <= a for a, b in zip(times, times[1:])) or any(t < plan["start_ns"] or t >= plan["end_ns"] for t in times)):
        timing_reasons.append("Observed timestamps must strictly increase inside the exact planned interval")
    timing_valid = not timing_reasons
    observations, seen, repeated, backwards, high_rank = [], {}, [], [], -1
    for sample, (_data, image) in zip(samples, raw_frames(evidence["observed"], capture_w, capture_h, n, observed["format"])):
        decoded = decode_marker(image, layout, mapped_cells)
        row = {**sample, "marker_status": decoded["status"], **{k: v for k, v in decoded.items() if k != "status"}}
        index = decoded.get("file_frame")
        key = (sample["stream_generation"], sample["loop_iteration"], index)
        if decoded["status"] == "DECODED":
            if index >= count or key not in slots or any(decoded[k] != frames[index][k] for k in ("pts_numerator", "pts_denominator")):
                row.update(marker_status="UNKNOWN", reason="Decoded payload does not match an exact planned reference identity/PTS")
            else:
                row.update(marker_status="MATCHED", frame_id=frames[index]["frame_id"], source_frame_id=frames[index]["source_frame_id"],
                           planned_time_ns={"numerator": slots[key]["target_ns"].numerator, "denominator": slots[key]["target_ns"].denominator},
                           segment_index=slots[key]["segment_index"])
                if key in seen:
                    repeated.append(sample["sample_index"])
                if slots[key]["rank"] < high_rank:
                    backwards.append(sample["sample_index"])
                high_rank = max(high_rank, slots[key]["rank"])
                seen.setdefault(key, []).append(row)
        observations.append(row)
    missing = [key for key in slots if key not in seen]
    available = layout["status"] == "AVAILABLE"
    measurements = []

    def measure(name, value, limit=None, family="smoothness", reason=None):
        state = "UNMEASURED" if value is None else "MEASURED" if limit is None else "PASS" if value <= limit else "FAIL"
        measurements.append({"name": name, "family": family, "status": state, "value": value, "limit": limit, "reason": reason})

    unknown = sum(row["marker_status"] != "MATCHED" for row in observations)
    for name, value in (("missing_frames", len(missing)), ("repeated_frames", len(repeated)),
                        ("out_of_order_frames", len(backwards)), ("unknown_frames", unknown)):
        measure("repeated_capture_samples" if name == "repeated_frames" and sampling == "screen_samples" else name,
                value if available else None, None if name == "repeated_frames" and sampling == "screen_samples" else 0,
                reason="Oversampled screen captures can repeat a source frame normally" if name == "repeated_frames" and sampling == "screen_samples" else None if available else layout.get("reason"))
    period = Fraction(1_000_000_000, fps)
    summaries, intervals, holds = [], [], []
    for segment in segments:
        keys = segment["keys"]
        absent = [key[2] for key in keys if key not in seen]
        prefix = next((i for i, key in enumerate(keys) if key in seen), len(keys))
        tail = next((i for i, key in enumerate(reversed(keys)) if key in seen), len(keys))
        scoped = [row for row in observations if row.get("segment_index") == segment["segment_index"]]
        ending = fraction(segment["end_ns"], "segment.end_ns")
        all_scoped = [row for row in observations if timing_valid and segment["start_ns"] <= row["time_ns"] < ending]
        if timing_valid:
            progress, maximum = [Fraction(segment["start_ns"])], -1
            advances = []
            for row in scoped:
                if segment["start_ns"] <= row["time_ns"] < ending and row["file_frame"] > maximum:
                    progress.append(Fraction(row["time_ns"]))
                    advances.append(row)
                    maximum = row["file_frame"]
            cadence = advances if sampling == "screen_samples" else all_scoped
            intervals.extend(b["time_ns"] - a["time_ns"] for a, b in zip(cadence, cadence[1:]))
            progress.append(ending)
            holds.extend({"segment_index": segment["segment_index"], "start_ns": float(a), "end_ns": float(b), "duration_ns": float(b - a)}
                         for a, b in zip(progress, progress[1:]) if b > a)
        summaries.append({k: v for k, v in segment.items() if k != "keys"} | {"missing_file_frames": absent,
                         "missing_prefix_frames": prefix, "missing_tail_frames": tail, "matched_samples": len(scoped)})
    measure("missing_prefix_frames", sum(s["missing_prefix_frames"] for s in summaries) if available else None, 0)
    measure("missing_tail_frames", sum(s["missing_tail_frames"] for s in summaries) if available else None, 0)
    outside = [row["sample_index"] for row in observations if row["marker_status"] == "MATCHED" and timing_valid
               and not segments[row["segment_index"]]["start_ns"] <= row["time_ns"] < fraction(segments[row["segment_index"]]["end_ns"], "segment.end_ns")]
    measure("outside_planned_segment_samples", len(outside) if timing_valid else None, 0)
    measure("observed_interval_p95_periods", quantile(intervals, .95) / float(period) if timing_valid and intervals else None, 1.25,
            reason="All captured intervals, including uncertain/repeated identities; native raw observation stage only")
    measure("observed_interval_p99_periods", quantile(intervals, .99) / float(period) if timing_valid and intervals else None, 2)
    jitter = math.sqrt(sum(float(Fraction(delta) - period) ** 2 for delta in intervals) / len(intervals)) if timing_valid and intervals else None
    measure("observed_interval_rms_jitter_ns", jitter, reason="Includes cadence bias; no division by estimated missing frames")
    threshold = spec.get("freeze_threshold_ns")
    if threshold is not None:
        integer(threshold, "freeze_threshold_ns", 1)
    freezes = [hold for hold in holds if threshold is not None and hold["duration_ns"] > threshold]
    measure("marker_nonadvance_freezes", len(freezes) if timing_valid and available and threshold is not None else None, 0,
            reason="Includes planned active prefixes/tails; threshold must be explicitly declared; not optical screen freezes")
    offset = spec.get("normalization_offset_ns")
    if offset is not None and type(offset) is not int:
        raise ValueError("normalization_offset_ns must be an explicitly declared signed integer")
    lateness = [float(Fraction(row["time_ns"]) - slots[key]["target_ns"] - offset)
                for key, rows in seen.items() for row in (rows[:1] if sampling == "screen_samples" else rows)] if timing_valid and offset is not None else []
    measure("normalized_lateness_p95_ns", quantile(lateness, .95), reason="Signed observed-minus-planned-minus-explicit-offset; not physical latency")
    event_observations = []
    for segment in segments:
        for event in events["events"]:
            key = (segment["stream_generation"], segment["loop_iteration"], event.get("file_frame"))
            if key in slots and slots[key]["segment_index"] == segment["segment_index"]:
                event_observations.append({"kind": event["kind"], "stream_generation": key[0], "loop_iteration": key[1],
                    "file_frame": key[2], "matched_sample_indices": [row["sample_index"] for row in seen.get(key, [])],
                    "status": "OBSERVED" if key in seen else "MISSING" if available else "UNMEASURED"})
    recoveries = []
    for fault in plan.get("recovery_events", []):
        after = integer(fault.get("after_ns"), "recovery.after_ns")
        first = integer(fault.get("first_file_frame"), "recovery.first_file_frame")
        generation, loop = fault.get("stream_generation"), integer(fault.get("loop_iteration"), "recovery.loop_iteration")
        if (generation, loop, first) not in slots or not plan["start_ns"] <= after < plan["end_ns"]:
            raise ValueError("Recovery requires an exact planned reference identity and fault in the declared capture clock")
        candidates = [row for row in observations if row["marker_status"] == "MATCHED" and row["stream_generation"] == generation
                      and row["loop_iteration"] == loop and row["file_frame"] >= first and timing_valid and row["time_ns"] >= after]
        delta = (candidates[0]["time_ns"] - after) / 1_000_000 if candidates else None
        recoveries.append({**fault, "marker_recovery_ms": delta, "sample_index": candidates[0]["sample_index"] if candidates else None})
        measure("marker_recovery_ms", delta, 5000, "connection", "Declared fault-to-first-valid marker proxy; not authentication or physical pixels")
    return {"schema": SCHEMA, "measurement_status": "MEASURED" if available else "UNMEASURED",
            "scope": {"clock": clock, "timing_status": "MEASURED" if timing_valid else "UNMEASURED", "timing_reasons": timing_reasons,
                      "planned_start_ns": plan["start_ns"], "planned_end_ns": plan["end_ns"], "stage": observed.get("stage", "UNSPECIFIED"),
                      "native_geometry": [w, h], "capture_geometry": [capture_w, capture_h], "sampling": sampling,
                      "fps": fps, "planned_frames": len(slots), "observed_frames": n},
            "evidence": evidence, "measurements": measurements, "observations": observations, "segments": summaries,
            "missing": [{"stream_generation": key[0], "loop_iteration": key[1], "file_frame": key[2]} for key in missing],
            "repeated_sample_indices": repeated, "out_of_order_sample_indices": backwards, "events": event_observations,
            "outside_planned_segment_sample_indices": outside,
            "timing": {"intervals_ns": intervals, "nonadvance_spans": holds, "freezes": freezes,
                       "freeze_threshold_ns": threshold, "normalization_offset_ns": offset, "normalized_lateness_ns": lateness},
            "recoveries": recoveries,
            "limitations": ["Native cells or externally calibrated 2D affine raster cells only; no image resampling, mapping search, perspective or screen-viewport detector.",
                            "Checksum/majority voting detects uncertainty; CRC is not authentication and low-bit-rate robustness is unmeasured.",
                            "Unknown/damaged markers remain unknown; no nearest-frame or PTS rematching.",
                            "Loop iteration and stream generation are externally declared facts; their provenance must be checked by the suite.",
                            "Decoded-output cadence/holds and relative lateness are proxies, not physical smoothness or glass-to-glass delay.",
                            "Source content repeats retain distinct file-frame identity; missing prefixes/tails are never excluded."]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    args.output = output_path(args.output, "runs")
    try:
        spec_path = resolve_input(args.spec)
        data = spec_path.read_bytes()
        result = analyze(json.loads(data.decode("utf-8-sig")), original(spec_path).parent)
        protected = {Path(record["path"]).resolve() for record in result["evidence"].values()} | {spec_path}
        if args.output.resolve() in protected or args.output.exists():
            raise ValueError("Output must be fresh and cannot replace source evidence")
        result["spec"] = {"path": str(spec_path), "sha256": hashlib.sha256(data).hexdigest()}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(f"{result['measurement_status']}; timing {result['scope']['timing_status']}; output {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
