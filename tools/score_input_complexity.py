"""Compare unmarked native I420 complexity offline; never creates phone passes.

The benchmark-input-complexity-v1 spec contains reference/candidate native fixture
records (the binder's fields), content='unmarked', cut_frames, loop_seam_frames,
external_loop (bool), and provenance={kind,description,timing_basis,derivation,
evidence:[{path,sha256}]}. Either role can declare recorded_camera,
natural_footage or synthetic provenance. Evidence is retained and hashed;
its natural-content claim is a declaration, not inferred from SI/TI or a name.
All frames and adjacent pairs are measured. No conversion or sampling is offered.
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path

import numpy as np

from bind_benchmark_inputs import COLOR_FIELDS, validate_fixture
from score_reference_regions import _frames
from score_visual_markers import file_record, integer
from benchmark_layout import original, output_path, resolve as resolve_input


SCHEMA = "benchmark-input-complexity-v1"
ALGORITHM = {
    "version": "native-unmarked-siti-v1",
    "si": "Population std of unscaled 3x3 Sobel gradient magnitude on native 8-bit Y",
    "ti": "Population std of signed current-minus-previous native 8-bit Y",
    "pixels": "Full unmarked luma except one-pixel border, for both SI and TI",
    "units": "SI: unscaled Sobel Y-code units; TI: 8-bit Y-code difference units",
    "colour": "No range/EOTF normalization or guessed tags; matching declarations required",
    "quantile": "NumPy linear percentile; median, p95 and maximum",
    "sampling": "Every file frame and adjacent pair; no motion filtering or prefix selection",
    "transitions": "Cuts/seams reported included and excluded; external final-to-first seam is separate",
    "scope": "Native luma-complexity diagnostic, not P.910 certification or encoder load",
}


def distribution(values):
    data = np.asarray([value for value in values if value is not None], dtype=np.float64)
    return {"count": len(data), "median": float(np.median(data)) if len(data) else None,
            "p95": float(np.percentile(data, 95)) if len(data) else None,
            "max": float(data.max()) if len(data) else None}


def frame_complexity(luma, previous=None):
    luma = luma.astype(np.float64)
    gx = (luma[:-2, 2:] + 2 * luma[1:-1, 2:] + luma[2:, 2:]
          - luma[:-2, :-2] - 2 * luma[1:-1, :-2] - luma[2:, :-2])
    gy = (luma[2:, :-2] + 2 * luma[2:, 1:-1] + luma[2:, 2:]
          - luma[:-2, :-2] - 2 * luma[:-2, 1:-1] - luma[:-2, 2:])
    return float(np.hypot(gx, gy).std(ddof=0)), (None if previous is None else
           float((luma - previous.astype(np.float64))[1:-1, 1:-1].std(ddof=0)))


def _transitions(value, name, count):
    if not isinstance(value, list):
        raise ValueError(f"{name} must explicitly list native transition frame indices")
    indices = [integer(index, name, 1) for index in value]
    if len(indices) != len(set(indices)) or any(index >= count for index in indices):
        raise ValueError(f"{name} must contain unique in-range transitions after the first frame")
    return sorted(indices)


def _fixture(spec, base, role):
    if not isinstance(spec, dict):
        raise ValueError(f"{role} must declare a native fixture")
    for key in ("width", "height", "fps"):
        integer(spec.get(key), f"{role}.{key}", 4 if key != "fps" else 1)
    if spec.get("content") != "unmarked":
        raise ValueError("Complexity requires declared unmarked native bytes; markers cannot inflate stress")
    record = validate_fixture(spec, base)
    record["cut_frames"] = _transitions(spec.get("cut_frames"), "cut_frames", record["frame_count"])
    record["loop_seam_frames"] = _transitions(spec.get("loop_seam_frames"), "loop_seam_frames", record["frame_count"])
    if set(record["cut_frames"]) & set(record["loop_seam_frames"]):
        raise ValueError("A transition cannot ambiguously be both a cut and a loop seam")
    if type(spec.get("external_loop")) is not bool:
        raise ValueError("external_loop must explicitly declare whether final-to-first replay is planned")
    provenance = spec.get("provenance")
    if not isinstance(provenance, dict) or provenance.get("kind") not in {
            "recorded_camera", "natural_footage", "synthetic"}:
        raise ValueError("Native provenance must distinguish recorded_camera, natural_footage and synthetic")
    for key in ("description", "timing_basis", "derivation"):
        if not isinstance(provenance.get(key), str) or not provenance[key].strip():
            raise ValueError(f"Native provenance requires explicit {key}")
    evidence = provenance.get("evidence")
    if not isinstance(evidence, list) or not evidence:
        raise ValueError("Natural/source provenance requires retained hash-bound evidence; a clip name is insufficient")
    record["provenance"] = {**provenance, "evidence": [file_record(item, base) for item in evidence],
                            "verification": "Evidence bytes verified; source/timing/naturalness remain declared"}
    return record


def _measure(record):
    width, height, count = record["width"], record["height"], record["frame_count"]
    frame_bytes = width * height * 3 // 2
    frame_rows = None
    if "frames" in record["sidecars"]:
        frame_rows, _ = _frames(record["sidecars"]["frames"], width, height, record["fps"])
        if len(frame_rows) != count:
            raise ValueError("Frame sidecar must cover every declared native frame")
    samples, previous, first = [], None, None
    with Path(record["path"]).open("rb") as source:
        for index in range(count):
            raw = source.read(frame_bytes)
            if len(raw) != frame_bytes:
                raise ValueError("Native file changed or contains a partial frame")
            if frame_rows and hashlib.sha256(raw).hexdigest() != frame_rows[index]["unmarked_i420_sha256"]:
                raise ValueError("Frame bytes differ from declared unmarked frame SHA256")
            luma = np.frombuffer(raw, np.uint8, width * height).reshape(height, width).astype(np.float64)
            si, ti = frame_complexity(luma, previous)
            samples.append({"file_frame": index, "pts_numerator": index, "pts_denominator": record["fps"],
                            "si": si, "ti": ti, "cut": index in record["cut_frames"],
                            "loop_seam": index in record["loop_seam_frames"]})
            if first is None:
                first = luma
            previous = luma
        if source.read(1):
            raise ValueError("Native file changed or contains undeclared frames")
    file_record(record, Path(record["path"]).parent)
    external_seam = frame_complexity(first, previous)[1] if record["external_loop"] else None
    return {"input": record, "coverage": {"frames_measured": count, "frames_declared": count,
            "adjacent_pairs_measured": count - 1, "frame_sampling_stride": 1,
            "duration_seconds": count / record["fps"], "unavailable_first_frame_ti": 1},
            "si": distribution([row["si"] for row in samples]),
            "ti_including_cuts_seams": distribution([row["ti"] for row in samples]),
            "ti_excluding_cuts_seams": distribution([row["ti"] for row in samples if not row["cut"] and not row["loop_seam"]]),
            "cut_ti": distribution([row["ti"] for row in samples if row["cut"]]),
            "loop_seam_ti": distribution([row["ti"] for row in samples if row["loop_seam"]]),
            "external_loop_seam_ti": external_seam,
            "external_loop_seam_in_primary_statistics": False,
            "unannotated_transitions": "Retained; no fast-motion frame is automatically classified or removed",
            "samples": samples}


def analyze(spec, base):
    if not isinstance(spec, dict) or spec.get("schema") != SCHEMA:
        raise ValueError(f"Expected {SCHEMA}")
    reference = _fixture(spec.get("reference"), base, "reference")
    candidate = _fixture(spec.get("candidate"), base, "candidate")
    if any(reference[key] != candidate[key] for key in ("width", "height", "fps")):
        raise ValueError("Native geometry/FPS must match exactly; no resize, resample or speed change")
    if any(reference["color"][key].strip().lower() != candidate["color"][key].strip().lower() for key in COLOR_FIELDS):
        raise ValueError("Native colour declarations must match; no normalization or guessed tags")
    reference, candidate = _measure(reference), _measure(candidate)
    comparison, shortfalls = {}, []
    for metric, key in (("si", "si"), ("ti", "ti_excluding_cuts_seams")):
        comparison[metric] = {}
        for statistic in ("median", "p95", "max"):
            a, b = reference[key][statistic], candidate[key][statistic]
            meets = None if a is None or b is None else b >= a
            comparison[metric][statistic] = {"reference": a, "candidate": b, "candidate_at_least_reference": meets,
                "ratio": b / a if a is not None and a > 0 and b is not None else None,
                "absolute_shortfall": max(0.0, a - b) if a is not None and b is not None else None}
            if meets is False:
                shortfalls.append(f"{metric}.{statistic}")
    unknown = any(row["candidate_at_least_reference"] is None for values in comparison.values() for row in values.values())
    return {"schema": SCHEMA, "algorithm": ALGORITHM, "reference": reference, "candidate": candidate,
            "comparison": comparison, "shortfalls": shortfalls,
            "numeric_complexity_status": "UNMEASURED" if unknown else "SHORTFALL" if shortfalls else "MEETS_REFERENCE",
            "natural_content_status": "UNMEASURED" if candidate["input"]["provenance"]["kind"] == "synthetic" else "DECLARED_WITH_HASH_BOUND_EVIDENCE",
            "phone_acceptance_status": "UNMEASURED", "measured_phone_executions": 0, "phone_passes": 0,
            "comparison_policy": {"scope": "DESCRIPTIVE_DIAGNOSTIC", "rule": "Candidate >= reference for median, p95 and max of SI and TI",
                                  "validation": "No calibration establishes this six-statistic rule as a sufficient encoder-load or experience gate"},
            "limits": ["SI/TI do not prove natural demand, actual selected-encoder load, fidelity, screen cadence or phone acceptance",
                       "Native colour/timing declarations are retained; sensor timestamps and colour application require independent evidence",
                       "Six-statistic dominance is a historical screening heuristic; it cannot retire lower-scoring clips or establish exhaustive stress coverage",
                       "Luma-only SI/TI omit chroma; uniform brightness changes have zero TI because TI is a standard deviation",
                       "Cuts and loop seams are separate workloads as well as separate transition statistics"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="Fresh analysis directory for JSON and per-frame CSVs")
    args = parser.parse_args(argv)
    args.output = output_path(args.output, "runs")
    if args.output.exists():
        raise FileExistsError(args.output)
    spec_path = resolve_input(args.spec)
    data = spec_path.read_bytes()
    result = analyze(json.loads(data.decode("utf-8-sig")), original(spec_path).parent)
    result["spec"] = {"path": str(spec_path), "sha256": hashlib.sha256(data).hexdigest()}
    result["tool"] = file_record({"path": str(Path(__file__).resolve()),
                                  "sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}, Path.cwd())
    args.output.mkdir(parents=True, exist_ok=False)
    for role in ("reference", "candidate"):
        rows = result[role].pop("samples")
        path = args.output / f"{role}-frames.csv"
        with path.open("w", newline="", encoding="utf-8") as destination:
            writer = csv.DictWriter(destination, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        result[role]["samples"] = file_record({"path": str(path.resolve()),
                                              "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}, args.output)
    (args.output / "comparison.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("numeric_complexity_status", "shortfalls", "phone_acceptance_status")}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
