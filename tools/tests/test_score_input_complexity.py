"""Native complexity comparisons retain full input coverage and cannot award phone passes."""

import contextlib
import copy
import csv
from fractions import Fraction
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import score_input_complexity as complexity
from score_visual_markers import FRAME_FIELDS


class InputComplexityTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="input-complexity-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.step = np.zeros((6, 6), np.uint8)
        self.step[:, 3:] = 255
        self.motion = [self.step, 255 - self.step, self.step, 255 - self.step]
        self.evidence = self.record("source-evidence.json", b'{"scope":"host regression fixture, no camera claim"}')
        self.spec = {"schema": complexity.SCHEMA,
                     "reference": self.fixture("reference.i420", self.motion, "recorded_camera"),
                     "candidate": self.fixture("candidate.i420", self.motion, "natural_footage")}

    def record(self, name, data):
        path = self.folder / name
        path.write_bytes(data)
        return {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}

    def fixture(self, name, frames, kind="natural_footage", fps=30):
        height, width = frames[0].shape
        data = b"".join(image.tobytes() + bytes([128]) * (width * height // 2) for image in frames)
        return {**self.record(name, data), "width": width, "height": height, "fps": fps,
                "format": "I420", "bit_depth": 8, "frame_count": len(frames),
                "color": {key: "UNSPECIFIED" for key in complexity.COLOR_FIELDS},
                "content": "unmarked", "cut_frames": [], "loop_seam_frames": [], "external_loop": True,
                "provenance": {"kind": kind, "description": "Explicit host-only regression input",
                               "timing_basis": "Declared integer frame cadence; no physical timing proof",
                               "derivation": "Created by the test, not a measured phone input",
                               "evidence": [self.evidence]}}

    def analyze(self, spec=None):
        return complexity.analyze(self.spec if spec is None else spec, self.folder)

    def test_numeric_sobel_difference_population_std_and_no_range_normalization(self):
        si, ti = complexity.frame_complexity(self.step, 255 - self.step)
        self.assertEqual(510, si)
        self.assertEqual(255, ti)
        limited = (16 + self.step.astype(np.float64) * 219 / 255).astype(np.uint8)
        si, ti = complexity.frame_complexity(limited, 251 - limited.astype(np.float64))
        self.assertEqual(438, si)
        self.assertEqual(219, ti)
        self.assertEqual((0, 0), complexity.frame_complexity(np.full((6, 6), 255, np.uint8), np.zeros((6, 6), np.uint8)))

    def test_linear_quantiles_missing_values_and_no_infinite_zero_ratio(self):
        self.assertEqual({"count": 3, "median": 1.0, "p95": 9.1, "max": 10.0},
                         complexity.distribution([None, 0, 1, 10]))
        self.assertEqual({"count": 0, "median": None, "p95": None, "max": None}, complexity.distribution([]))
        self.spec["reference"] = self.fixture("zero-ref.i420", [np.zeros((6, 6), np.uint8)] * 4, "recorded_camera")
        result = self.analyze()
        self.assertIsNone(result["comparison"]["ti"]["max"]["ratio"])
        json.dumps(result, allow_nan=False)

    def test_full_native_coverage_and_numeric_match_never_earns_phone_pass(self):
        result = self.analyze()
        self.assertEqual("MEETS_REFERENCE", result["numeric_complexity_status"])
        self.assertEqual("UNMEASURED", result["phone_acceptance_status"])
        self.assertEqual(0, result["phone_passes"])
        self.assertEqual(0, result["measured_phone_executions"])
        self.assertEqual("DESCRIPTIVE_DIAGNOSTIC", result["comparison_policy"]["scope"])
        self.assertEqual([], result["shortfalls"])
        for role in ("reference", "candidate"):
            self.assertEqual(4, result[role]["coverage"]["frames_measured"])
            self.assertEqual(3, result[role]["coverage"]["adjacent_pairs_measured"])
            self.assertEqual(1, result[role]["coverage"]["frame_sampling_stride"])
            self.assertEqual([None, 255, 255, 255], [row["ti"] for row in result[role]["samples"]])
            self.assertEqual(255, result[role]["external_loop_seam_ti"])

    def test_static_high_detail_fails_all_three_temporal_statistics(self):
        self.spec["candidate"] = self.fixture("static.i420", [self.step] * 4)
        result = self.analyze()
        self.assertEqual("SHORTFALL", result["numeric_complexity_status"])
        self.assertEqual(["ti.median", "ti.p95", "ti.max"], result["shortfalls"])
        self.assertEqual(255, result["comparison"]["ti"]["max"]["absolute_shortfall"])

    def test_synthetic_numeric_match_is_not_natural_demand_proof(self):
        self.spec["candidate"]["provenance"]["kind"] = "synthetic"
        result = self.analyze()
        self.assertEqual("MEETS_REFERENCE", result["numeric_complexity_status"])
        self.assertEqual("UNMEASURED", result["natural_content_status"])

    def test_cuts_and_internal_loop_seams_cannot_inflate_compared_ti(self):
        self.spec["candidate"] = self.fixture("transitions.i420", [self.step, self.step, 255 - self.step, self.step, self.step])
        self.spec["candidate"].update(cut_frames=[2], loop_seam_frames=[3])
        result = self.analyze()
        self.assertEqual(255, result["candidate"]["ti_including_cuts_seams"]["max"])
        self.assertEqual(0, result["candidate"]["ti_excluding_cuts_seams"]["max"])
        self.assertEqual(1, result["candidate"]["cut_ti"]["count"])
        self.assertEqual(1, result["candidate"]["loop_seam_ti"]["count"])
        self.assertEqual("SHORTFALL", result["numeric_complexity_status"])

    def test_external_seam_stays_separate_and_single_frame_ti_unknown(self):
        self.spec["candidate"] = self.fixture("single.i420", [self.step])
        result = self.analyze()
        self.assertEqual("UNMEASURED", result["numeric_complexity_status"])
        self.assertEqual(0, result["candidate"]["ti_excluding_cuts_seams"]["count"])
        self.assertEqual(0, result["candidate"]["external_loop_seam_ti"])
        self.spec["candidate"]["external_loop"] = False
        self.assertIsNone(self.analyze()["candidate"]["external_loop_seam_ti"])

    def test_exact_native_geometry_and_fps_mismatch_rejected(self):
        for dimensions, fps in (((6, 8), 30), ((6, 6), 15)):
            with self.subTest(dimensions=dimensions, fps=fps):
                spec = copy.deepcopy(self.spec)
                spec["candidate"] = self.fixture("mismatch.i420", [np.zeros(dimensions, np.uint8)] * 4, fps=fps)
                with self.assertRaisesRegex(ValueError, "Native geometry/FPS must match exactly"):
                    self.analyze(spec)

    def test_changed_raw_hash_and_provenance_hash_rejected(self):
        for record in ("candidate", "evidence"):
            with self.subTest(record=record):
                spec = copy.deepcopy(self.spec)
                target = spec["candidate"] if record == "candidate" else spec["candidate"]["provenance"]["evidence"][0]
                target["sha256"] = "f" * 64
                with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                    self.analyze(spec)

    def test_rehashed_partial_frame_and_wrong_frame_count_rejected(self):
        raw = Path(self.spec["candidate"]["path"]).read_bytes()
        for data, count in ((raw[:-1], 4), (raw + b"x", 4), (raw, 3), (raw[:-54], 4)):
            with self.subTest(length=len(data), count=count):
                spec = copy.deepcopy(self.spec)
                spec["candidate"].update(self.record("partial.i420", data), frame_count=count)
                with self.assertRaisesRegex(ValueError, "whole I420 frame count"):
                    self.analyze(spec)

    def test_wrong_native_declarations_and_missing_colour_rejected(self):
        for key, value in (("format", "NV12"), ("bit_depth", 10), ("width", 5), ("fps", True),
                           ("fps", 29.97), ("frame_count", True), ("content", "marked"),
                           ("color", {}), ("external_loop", "yes")):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                spec = copy.deepcopy(self.spec)
                spec["candidate"][key] = value
                self.analyze(spec)
        spec = copy.deepcopy(self.spec)
        spec["candidate"]["color"]["range"] = "limited"
        with self.assertRaisesRegex(ValueError, "colour declarations must match"):
            self.analyze(spec)

    def test_clean_references_measure_all_native_frames_without_camera_claim(self):
        for kind in ("synthetic", "natural_footage"):
            with self.subTest(kind=kind):
                spec = copy.deepcopy(self.spec)
                spec["reference"]["provenance"]["kind"] = kind
                result = self.analyze(spec)
                self.assertEqual(kind, result["reference"]["input"]["provenance"]["kind"])
                self.assertEqual(4, result["reference"]["coverage"]["frames_measured"])
                self.assertEqual(255, result["reference"]["ti_excluding_cuts_seams"]["max"])
                self.assertEqual("MEETS_REFERENCE", result["numeric_complexity_status"])
                self.assertEqual(0, result["phone_passes"])

    def test_missing_or_invalid_provenance_rejected(self):
        for role, key, value in (("reference", "kind", "unknown"), ("reference", "evidence", []),
                                 ("candidate", "timing_basis", ""), ("candidate", "derivation", None)):
            with self.subTest(role=role, key=key), self.assertRaises(ValueError):
                spec = copy.deepcopy(self.spec)
                spec[role]["provenance"][key] = value
                self.analyze(spec)

    def test_bad_transition_indices_and_ambiguous_classification_rejected(self):
        for cut, seam in ((None, []), ([0], []), ([4], []), ([True], []), ([1, 1], []), ([2], [2])):
            with self.subTest(cut=cut, seam=seam), self.assertRaises(ValueError):
                spec = copy.deepcopy(self.spec)
                spec["candidate"].update(cut_frames=cut, loop_seam_frames=seam)
                self.analyze(spec)

    def frame_sidecar(self, record, count=4, wrong_unmarked_hash=False):
        raw = Path(record["path"]).read_bytes()
        path = self.folder / "frames.csv"
        rows = []
        for index in range(count):
            pts = Fraction(index, record["fps"])
            sha = hashlib.sha256(raw[index * 54:(index + 1) * 54]).hexdigest()
            row = {"file_frame": index, "frame_id": f"{'ab' * 32}:6x6@30:{index}",
                   "source_frame_id": "cd" * 32, "source_frame": index, "segment_id": "motion",
                   "pattern_id": "motion", "repeat_index": 0,
                   "pts_numerator": pts.numerator, "pts_denominator": pts.denominator}
            row.update({key: sha for key in FRAME_FIELDS if key.endswith("sha256")})
            if wrong_unmarked_hash:
                row["unmarked_i420_sha256"] = "ef" * 32
            rows.append(row)
        with path.open("w", encoding="utf-8", newline="") as destination:
            writer = csv.DictWriter(destination, fieldnames=FRAME_FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def test_vsg_unmarked_frame_hashes_and_complete_sidecar_required(self):
        self.spec["candidate"]["sidecars"] = {"frames": self.frame_sidecar(self.spec["candidate"])}
        self.assertEqual("MEETS_REFERENCE", self.analyze()["numeric_complexity_status"])
        self.spec["candidate"]["sidecars"]["frames"] = self.frame_sidecar(self.spec["candidate"], wrong_unmarked_hash=True)
        with self.assertRaisesRegex(ValueError, "unmarked frame SHA256"):
            self.analyze()
        self.spec["candidate"]["sidecars"]["frames"] = self.frame_sidecar(self.spec["candidate"], count=3)
        with self.assertRaisesRegex(ValueError, "cover every declared native frame"):
            self.analyze()

    def test_cli_retains_spec_tool_and_sample_hashes_and_requires_fresh_output(self):
        path = self.folder / "spec.json"
        path.write_text(json.dumps(self.spec), encoding="utf-8")
        output = self.folder / "analysis"
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, complexity.main(["--spec", str(path), "--output", str(output)]))
        result = json.loads((output / "comparison.json").read_text(encoding="utf-8"))
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), result["spec"]["sha256"])
        self.assertEqual(hashlib.sha256(Path(complexity.__file__).read_bytes()).hexdigest(), result["tool"]["sha256"])
        for role in ("reference", "candidate"):
            csv = Path(result[role]["samples"]["path"])
            self.assertEqual(hashlib.sha256(csv.read_bytes()).hexdigest(), result[role]["samples"]["sha256"])
            self.assertEqual(5, len(csv.read_text().splitlines()))
        with self.assertRaises(FileExistsError):
            complexity.main(["--spec", str(path), "--output", str(output)])


if __name__ == "__main__":
    unittest.main()
