"""Native marker adapter regressions; temporary data is never phone acceptance."""

import copy
import csv
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib
from unittest.mock import patch

import numpy as np

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import score_visual_markers as visual
import benchmark_layout as layout

# Generated fixtures of the in-repo test-video generator (from that folder: python -m videostress generate
# --config examples/mixed.json --output artifacts/mixed); VIDEO_STRESS_GENERATOR overrides the folder.
GENERATOR = Path(os.environ.get("VIDEO_STRESS_GENERATOR") or ROOT / "tools" / "video-stress-generator")


def marker_rgb(frame, fps, w=64, h=64):
    pts = Fraction(frame, fps)
    payload = struct.pack(">HBIQI", 0x5653, 1, frame, pts.numerator, pts.denominator)
    payload += struct.pack(">I", zlib.crc32(payload))
    grid = np.zeros((25, 26), np.uint8)
    grid[0] = 1
    grid[-1] = np.arange(26) % 2
    grid[1:-1, -1] = np.arange(23) % 2
    grid[1:-1, 1:-1] = np.repeat(np.unpackbits(np.frombuffer(payload, np.uint8)), 3).reshape(23, 24)
    image = np.full((h, w, 3), 90, np.uint8)
    image[:50, :52] = np.repeat(np.repeat(grid, 2, axis=0), 2, axis=1)[..., None] * 255
    return image


def as_i420(rgb):
    h, w, _ = rgb.shape
    y = np.rint(16 + rgb[..., 0].astype(float) * 219 / 255).astype(np.uint8)
    return y.tobytes() + bytes([128]) * (w * h // 2)


class VisualMarkersTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="visual-markers-")
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.rgb = [marker_rgb(frame, 30) for frame in range(6)]
        self.raw = [as_i420(rgb) for rgb in self.rgb]
        self.source = self.record("source.i420", b"".join(self.raw))
        self.frames = []
        for index, raw in enumerate(self.raw):
            pts = Fraction(index, 30)
            row = {"file_frame": index, "frame_id": f"fixture:{index}",
                   "source_frame_id": hashlib.sha256(str(index % 2).encode()).hexdigest(),
                   "source_frame": index % 2, "segment_id": "motion", "pattern_id": "p19", "repeat_index": index // 2,
                   "pts_numerator": pts.numerator, "pts_denominator": pts.denominator}
            row.update({key: hashlib.sha256(raw).hexdigest() for key in visual.FRAME_FIELDS if key.endswith("sha256")})
            self.frames.append(row)
        self.layout = {"schema": "vsg-marker-v1", "status": "AVAILABLE", "cell_size": 2, "cells": [26, 25],
                       "bounds_xywh": [0, 0, 52, 50], "content_roi": {"geometry": [64, 64], "exclude_xywh": [[0, 0, 52, 50]]}}
        self.events = {"schema": "vsg-events-v1", "events": [{"kind": "hard_cut", "file_frame": 2,
                       "segment_id": "motion", "pattern_id": "p19", "source_frame": 0, "repeat_index": 1,
                       "pts": {"numerator": 1, "denominator": 15}, "audio_sample_index_48k": 3200},
                       {"kind": "file_loop_seam", "boundary_after_file_frame": 5, "next_file_frame": 0,
                        "at_file_time": {"numerator": 1, "denominator": 5}, "audio_sample_index_48k": 9600,
                        "conditional_on_consumer_loop": True}]}
        self.reference = {**self.source, "width": 64, "height": 64, "fps": 30, "format": "I420", "bit_depth": 8,
                          "frame_count": 6, "sidecars": {"frames": self.csv_record("frames.csv", visual.FRAME_FIELDS, self.frames),
                          "events": self.record("events.json", json.dumps(self.events).encode()),
                          "marker_layout": self.record("layout.json", json.dumps(self.layout).encode())}}

    def record(self, name, data):
        path = self.folder / name
        path.write_bytes(data)
        return {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}

    def csv_record(self, name, fields, rows):
        path = self.folder / name
        with path.open("w", encoding="utf-8", newline="") as source:
            writer = csv.DictWriter(source, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def spec(self, order=None, times=None, contexts=None, fmt="I420", raw=None, segments=None):
        order = list(range(6)) if order is None else order
        times = [i * 1_000_000_000 // 30 for i in range(len(order))] if times is None else times
        contexts = [("g1", 0)] * len(order) if contexts is None else contexts
        data = b"".join((self.raw[i] if fmt == "I420" else self.rgb[i].tobytes()) for i in order) if raw is None else raw
        rows = [{"sample_index": i, "stream_generation": generation, "loop_iteration": loop, "time_ns": time}
                for i, (time, (generation, loop)) in enumerate(zip(times, contexts))]
        return {"reference": copy.deepcopy(self.reference), "observed": {**self.record("observed.raw", data),
                "width": 64, "height": 64, "format": fmt, "frame_count": len(order), "stage": "decoded_output"},
                "samples": self.csv_record("samples.csv", visual.SAMPLE_FIELDS, rows),
                "planned": {"clock": "test-monotonic", "start_ns": 0, "end_ns": 200_000_000,
                            "segments": segments or [{"stream_generation": "g1", "loop_iteration": 0,
                            "first_file_frame": 0, "frame_count": 6, "start_ns": 0}]},
                "freeze_threshold_ns": 100_000_000, "normalization_offset_ns": 0}

    @staticmethod
    def measurement(result, name):
        return next(row for row in result["measurements"] if row["name"] == name)

    def analyze(self, spec):
        return visual.analyze(spec, self.folder)

    def test_native_i420_and_rgb_payload_and_content_repeat(self):
        for fmt in ("I420", "RGB24"):
            with self.subTest(fmt=fmt):
                result = self.analyze(self.spec(fmt=fmt))
                self.assertEqual("MEASURED", result["measurement_status"])
                self.assertEqual("MEASURED", result["scope"]["timing_status"])
                self.assertTrue(all(row["marker_status"] == "MATCHED" for row in result["observations"]))
                self.assertEqual(0, self.measurement(result, "missing_frames")["value"])
                self.assertEqual(0, self.measurement(result, "repeated_frames")["value"])
                self.assertEqual(result["observations"][0]["source_frame_id"], result["observations"][2]["source_frame_id"])
                self.assertNotEqual(result["observations"][0]["frame_id"], result["observations"][2]["frame_id"])

    def test_crc_majority_and_ambiguous_cells_never_nearest_match(self):
        image = self.rgb[2].copy()
        image[2:4, 2:4] = 255 - image[2:4, 2:4]
        result = visual.decode_marker(image, self.layout)
        self.assertEqual("DECODED", result["status"])
        self.assertEqual(1, result["corrected_triplets"])
        image[2:4, 4:6] = 255 - image[2:4, 4:6]
        self.assertEqual("CRC32 mismatch", visual.decode_marker(image, self.layout)["reason"])
        image = self.rgb[2].copy()
        image[8:10, 10:12] = 128
        result = self.analyze(self.spec(order=[0, 1, 2, 3, 4, 5], fmt="RGB24",
                                         raw=b"".join((image if i == 2 else rgb).tobytes() for i, rgb in enumerate(self.rgb))))
        self.assertEqual("AMBIGUOUS", result["observations"][2]["marker_status"])
        self.assertEqual(1, self.measurement(result, "unknown_frames")["value"])
        self.assertEqual(1, self.measurement(result, "missing_frames")["value"])

    def test_border_and_wrong_payload_pts_are_unknown(self):
        image = self.rgb[2].copy()
        image[:2, :2] = 0
        self.assertEqual("AMBIGUOUS", visual.decode_marker(image, self.layout)["status"])
        wrong = marker_rgb(2, 29)
        result = self.analyze(self.spec(order=[2], fmt="RGB24", raw=wrong.tobytes()))
        self.assertEqual("UNKNOWN", result["observations"][0]["marker_status"])
        result = self.analyze(self.spec(order=[0], fmt="RGB24", raw=marker_rgb(100, 30).tobytes()))
        self.assertEqual("UNKNOWN", result["observations"][0]["marker_status"])

    def test_missing_prefix_tail_repeat_and_order(self):
        result = self.analyze(self.spec(order=[1, 3, 3, 2]))
        self.assertEqual(3, self.measurement(result, "missing_frames")["value"])
        self.assertEqual(1, self.measurement(result, "missing_prefix_frames")["value"])
        self.assertEqual(2, self.measurement(result, "missing_tail_frames")["value"])
        self.assertEqual([2], result["repeated_sample_indices"])
        self.assertEqual([3], result["out_of_order_sample_indices"])
        self.assertEqual(6, result["scope"]["planned_frames"])

    def test_explicit_loops_and_restart_generation_are_distinct(self):
        segments = [{"stream_generation": generation, "loop_iteration": loop, "first_file_frame": 0,
                     "frame_count": 6, "start_ns": index * 200_000_000}
                    for index, (generation, loop) in enumerate((("g1", 0), ("g1", 1), ("g2", 0)))]
        spec = self.spec(order=list(range(6)) * 3, contexts=[("g1", 0)] * 6 + [("g1", 1)] * 6 + [("g2", 0)] * 6,
                         segments=segments)
        spec["planned"]["end_ns"] = 600_000_000
        result = self.analyze(spec)
        self.assertEqual(18, result["scope"]["planned_frames"])
        self.assertEqual([], result["missing"])
        self.assertEqual([], result["out_of_order_sample_indices"])
        self.assertEqual([], result["repeated_sample_indices"])
        self.assertEqual(3, len(result["events"]))

    def test_undeclared_loop_is_not_inferred(self):
        spec = self.spec(order=list(range(6)) * 2, contexts=[("g1", 0)] * 12)
        spec["planned"]["end_ns"] = 400_000_000
        spec["planned"]["segments"].append({"stream_generation": "g1", "loop_iteration": 1,
                                           "first_file_frame": 0, "frame_count": 6, "start_ns": 200_000_000})
        result = self.analyze(spec)
        self.assertEqual(6, self.measurement(result, "missing_frames")["value"])
        self.assertEqual(6, self.measurement(result, "repeated_frames")["value"])
        self.assertEqual(6, self.measurement(result, "outside_planned_segment_samples")["value"])

    def test_planned_active_prefix_and_terminal_hold_are_not_trimmed(self):
        spec = self.spec(order=[2], times=[120_000_000])
        spec["freeze_threshold_ns"] = 60_000_000
        result = self.analyze(spec)
        self.assertEqual([120_000_000, 80_000_000], [row["duration_ns"] for row in result["timing"]["freezes"]])
        self.assertEqual("FAIL", self.measurement(result, "marker_nonadvance_freezes")["status"])
        self.assertEqual(2, self.measurement(result, "missing_prefix_frames")["value"])
        self.assertEqual(3, self.measurement(result, "missing_tail_frames")["value"])

    def test_missing_bad_and_nonincreasing_times_leave_timing_unmeasured(self):
        for times in ([None] * 6, [0, 1, 2, "bad", 4, 5], [0, 1, 1, 2, 3, 4], [0, 1, 2, 3, 4, 200_000_000]):
            with self.subTest(times=times):
                result = self.analyze(self.spec(times=times))
                self.assertEqual("MEASURED", result["measurement_status"])
                self.assertEqual("UNMEASURED", result["scope"]["timing_status"])
                self.assertEqual("UNMEASURED", self.measurement(result, "observed_interval_p95_periods")["status"])
                self.assertEqual([], result["timing"]["normalized_lateness_ns"])
        spec = self.spec()
        del spec["planned"]["clock"]
        self.assertEqual("UNMEASURED", self.analyze(spec)["scope"]["timing_status"])

    def test_offset_is_explicit_and_never_physical_latency(self):
        spec = self.spec(times=[10_000_000 + i * 1_000_000_000 // 30 for i in range(6)])
        spec["normalization_offset_ns"] = 10_000_000
        result = self.analyze(spec)
        self.assertLess(abs(self.measurement(result, "normalized_lateness_p95_ns")["value"]), 1)
        del spec["normalization_offset_ns"]
        result = self.analyze(spec)
        self.assertEqual("UNMEASURED", self.measurement(result, "normalized_lateness_p95_ns")["status"])

    def test_cut_missing_is_visible_and_recovery_uses_declared_fault_clock(self):
        spec = self.spec(order=[0, 1, 3, 4, 5], times=[0, 33_333_333, 120_000_000, 150_000_000, 180_000_000])
        spec["planned"]["recovery_events"] = [{"id": "fault1", "after_ns": 60_000_000,
                                              "stream_generation": "g1", "loop_iteration": 0, "first_file_frame": 2}]
        result = self.analyze(spec)
        self.assertEqual("MISSING", result["events"][0]["status"])
        self.assertEqual(60, self.measurement(result, "marker_recovery_ms")["value"])

    def test_no_output_frames_reports_all_missing_and_keeps_whole_window(self):
        result = self.analyze(self.spec(order=[]))
        self.assertEqual(6, self.measurement(result, "missing_frames")["value"])
        self.assertEqual(6, self.measurement(result, "missing_tail_frames")["value"])
        self.assertEqual(200_000_000, result["timing"]["freezes"][0]["duration_ns"])

    def test_hash_changes_native_mismatch_and_partial_frame_are_rejected(self):
        spec = self.spec()
        Path(spec["observed"]["path"]).write_bytes(b"modified")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            self.analyze(spec)
        spec = self.spec()
        spec["observed"]["width"] = 62
        with self.assertRaisesRegex(ValueError, "native geometry"):
            self.analyze(spec)
        spec = self.spec(raw=b"".join(self.raw)[:-1])
        with self.assertRaisesRegex(ValueError, "whole frame count"):
            self.analyze(spec)

    def test_reference_hash_event_and_layout_contents_are_validated(self):
        spec = self.spec()
        frames = copy.deepcopy(self.frames)
        frames[0]["i420_sha256"] = "0" * 64
        spec["reference"]["sidecars"]["frames"] = self.csv_record("bad-frames.csv", visual.FRAME_FIELDS, frames)
        with self.assertRaisesRegex(ValueError, "pixel hash"):
            self.analyze(spec)
        spec = self.spec()
        events = copy.deepcopy(self.events)
        events["events"][0]["pts"]["denominator"] = 14
        spec["reference"]["sidecars"]["events"] = self.record("bad-events.json", json.dumps(events).encode())
        with self.assertRaisesRegex(ValueError, "Event identity/PTS"):
            self.analyze(spec)
        spec = self.spec()
        layout = {**self.layout, "bounds_xywh": [1, 0, 52, 50]}
        spec["reference"]["sidecars"]["marker_layout"] = self.record("bad-layout.json", json.dumps(layout).encode())
        with self.assertRaisesRegex(ValueError, "native marker bounds"):
            self.analyze(spec)

    def test_disabled_marker_never_gets_a_native_identity_pass(self):
        spec = self.spec()
        layout = {**self.layout, "status": "DISABLED", "reason": "Explicitly disabled", "bounds_xywh": None}
        spec["reference"]["sidecars"]["marker_layout"] = self.record("disabled.json", json.dumps(layout).encode())
        result = self.analyze(spec)
        self.assertEqual("UNMEASURED", result["measurement_status"])
        self.assertEqual("UNMEASURED", self.measurement(result, "missing_frames")["status"])

    def test_mosaic_panel_events_require_their_exact_hashed_panel_declaration(self):
        spec = self.spec()
        frames = copy.deepcopy(self.frames)
        for row in frames:
            row["pattern_id"] = "mosaic"
        events = copy.deepcopy(self.events)
        events["events"][0].update(panel_id="motion-panel", bounds_xywh=[32, 0, 32, 64])
        panels = {"schema": "vsg-panels-v1", "geometry": {"width": 64, "height": 64, "fps": 30},
                  "segments": [{"segment_id": "motion", "file_frame_start": 0, "file_frame_end": 6,
                                "panels": [{"id": "motion-panel", "pattern_id": "p19", "bounds_xywh": [32, 0, 32, 64]}]}]}
        sidecars = spec["reference"]["sidecars"]
        sidecars["frames"] = self.csv_record("mosaic-frames.csv", visual.FRAME_FIELDS, frames)
        sidecars["events"] = self.record("mosaic-events.json", json.dumps(events).encode())
        sidecars["panels"] = self.record("panels.json", json.dumps(panels).encode())
        self.assertEqual(6, len(visual.validate_reference(spec["reference"], self.folder)[4]))
        for kind in ("absent", "pattern", "bounds", "frame_window"):
            bad = copy.deepcopy(spec)
            if kind == "absent":
                bad["reference"]["sidecars"].pop("panels")
            else:
                altered = copy.deepcopy(panels)
                if kind == "pattern":
                    altered["segments"][0]["panels"][0]["pattern_id"] = "p22"
                elif kind == "bounds":
                    altered["segments"][0]["panels"][0]["bounds_xywh"] = [0, 0, 32, 64]
                else:
                    altered["segments"][0]["file_frame_end"] = 2
                bad["reference"]["sidecars"]["panels"] = self.record("bad-panels.json", json.dumps(altered).encode())
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, "Panel event differs"):
                visual.validate_reference(bad["reference"], self.folder)

    def test_shifted_native_marker_uses_the_declared_affine_frame_coordinates(self):
        native = np.zeros((64, 64, 3), np.uint8)
        native[8:58, 8:60] = self.rgb[2][:50, :52]
        declared = copy.deepcopy(self.layout)
        declared["bounds_xywh"] = [8, 8, 52, 50]
        self.assertEqual(2, visual.decode_marker(native, declared)["file_frame"])
        proof = self.record("calibration-proof.json", b"Independent mapping test identity")
        matrix = {"schema": "benchmark-marker-mapping-v1", "method": "independent_calibration",
                  "reference_sha256": self.reference["sha256"], "reference_geometry": [64, 64],
                  "capture_geometry": [64, 64], "source_evidence": proof, "affine": [[1, 0, 0], [0, 1, 0]]}
        mapping = self.record("mapping.json", json.dumps(matrix).encode())
        cells = visual.mapped_marker_cells(mapping, self.reference, declared, 64, 64, 64, 64, {})
        self.assertEqual(2, visual.decode_marker(native, declared, cells)["file_frame"])

    def screen_spec(self, matrix=None, order=None):
        matrix = [[0, -2, 140], [2, 0, 10]] if matrix is None else matrix
        order = list(range(6)) if order is None else order
        capture_w, capture_h = 150, 150
        a = np.asarray(matrix, float)
        yy, xx = np.mgrid[:capture_h, :capture_w]
        native = (np.column_stack((xx.ravel() + .5, yy.ravel() + .5)) - a[:, 2]) @ np.linalg.inv(a[:, :2]).T
        nx, ny = np.floor(native[:, 0]).astype(int), np.floor(native[:, 1]).astype(int)
        inside = (nx >= 0) & (nx < 64) & (ny >= 0) & (ny < 64)
        rendered = []
        for frame in order:
            image = np.zeros((capture_h * capture_w, 3), np.uint8)
            image[inside] = self.rgb[frame][ny[inside], nx[inside]]
            rendered.append(image.tobytes())
        spec = self.spec(order=order, fmt="RGB24", raw=b"".join(rendered))
        spec["observed"].update(width=capture_w, height=capture_h, stage="external_recording", sampling="screen_samples")
        mapping = {"schema": "benchmark-marker-mapping-v1", "method": "independent_calibration",
                   "reference_sha256": self.source["sha256"], "reference_geometry": [64, 64],
                   "capture_geometry": [capture_w, capture_h], "affine": matrix,
                   "source_evidence": self.record("calibration-data.json", b'{"synthetic_detector_validation_only":true}')}
        spec["mapping"] = self.record("mapping.json", json.dumps(mapping).encode())
        return spec

    def test_independently_calibrated_scaled_rotated_and_mirrored_raster_cells(self):
        for matrix in ([[0, -2, 140], [2, 0, 10]], [[-2, 0, 140], [0, 2, 10]], [[2, 0, 10], [0, 2, 10]]):
            with self.subTest(matrix=matrix):
                result = self.analyze(self.screen_spec(matrix))
                self.assertEqual([], result["missing"])
                self.assertTrue(all(row["marker_status"] == "MATCHED" for row in result["observations"]))
                self.assertEqual("screen_samples", result["scope"]["sampling"])
                self.assertEqual([150, 150], result["scope"]["capture_geometry"])
                self.assertIn("mapping_source", result["evidence"])

    def test_relocated_calibration_mapping_keeps_its_original_relative_source(self):
        spec = self.screen_spec()
        mapping = json.loads(Path(spec["mapping"]["path"]).read_text())
        mapping["source_evidence"]["path"] = "calibration-data.json"
        spec["mapping"] = self.record("mapping.json", json.dumps(mapping).encode())
        aliases = {"mapping.json": "benchmarks/runs/id/calibration/mapping.json",
                   "calibration-data.json": "benchmarks/runs/id/proof/calibration-data.json"}
        for source, destination in aliases.items():
            target = self.folder / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            (self.folder / source).replace(target)
        ledger = self.folder / "benchmarks/catalog/ledger.json"
        ledger.parent.mkdir(parents=True)
        ledger.write_text(json.dumps({"storage": {"paths": aliases}}))
        resolve = lambda path, base=None: layout.resolve(path, base=base, root=self.folder)
        original = lambda path: layout.original(path, root=self.folder)
        with patch.object(visual, "resolve_input", resolve), patch.object(visual, "original", original):
            result = self.analyze(spec)
            self.assertEqual([], result["missing"])
            self.assertTrue(all(row["marker_status"] == "MATCHED" for row in result["observations"]))
            self.assertEqual((self.folder / aliases["calibration-data.json"]).resolve(), Path(result["evidence"]["mapping_source"]["path"]))
            (self.folder / aliases["calibration-data.json"]).write_text("changed calibration")
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                self.analyze(spec)

    def test_screen_oversampling_preserves_repeats_without_false_delivery_failure(self):
        spec = self.screen_spec(order=[frame for frame in range(6) for _ in range(2)])
        rows = [{"sample_index": i, "stream_generation": "g1", "loop_iteration": 0,
                 "time_ns": i * 1_000_000_000 // 60} for i in range(12)]
        spec["samples"] = self.csv_record("screen-samples.csv", visual.SAMPLE_FIELDS, rows)
        result = self.analyze(spec)
        self.assertEqual(6, self.measurement(result, "repeated_capture_samples")["value"])
        self.assertEqual("MEASURED", self.measurement(result, "repeated_capture_samples")["status"])
        self.assertEqual("PASS", self.measurement(result, "observed_interval_p95_periods")["status"])
        self.assertAlmostEqual(1, self.measurement(result, "observed_interval_p95_periods")["value"], places=7)

    def test_mapping_crop_perspective_and_undersampling_are_rejected(self):
        for changed in ({"affine": [[2, 0, -1], [0, 2, 10]]},
                        {"affine": [[1, 0, 0], [0, 1, 0], [0.1, 0, 1]]},
                        {"affine": [[.5, 0, 10], [0, .5, 10]]},
                        {"reference_sha256": "0" * 64}):
            spec = self.screen_spec()
            mapping = json.loads(Path(spec["mapping"]["path"]).read_text())
            mapping.update(changed)
            spec["mapping"] = self.record("changed-mapping.json", json.dumps(mapping).encode())
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                self.analyze(spec)

    def test_severely_degraded_screen_marker_never_matches_by_similarity(self):
        spec = self.screen_spec()
        data = Path(spec["observed"]["path"]).read_bytes()
        first = np.frombuffer(data[:150 * 150 * 3], np.uint8).copy()
        first[:] = 128
        spec["observed"].update(self.record("degraded-screen.rgb", first.tobytes() + data[150 * 150 * 3:]))
        result = self.analyze(spec)
        self.assertEqual("AMBIGUOUS", result["observations"][0]["marker_status"])
        self.assertEqual("FAIL", self.measurement(result, "unknown_frames")["status"])
        self.assertEqual(1, self.measurement(result, "missing_frames")["value"])

    def test_ambiguous_plan_identity_samples_count_and_output_protection(self):
        spec = self.spec()
        spec["planned"]["end_ns"] = 400_000_000
        spec["planned"]["segments"].append({**spec["planned"]["segments"][0], "start_ns": 200_000_000})
        with self.assertRaisesRegex(ValueError, "distinct loop"):
            self.analyze(spec)
        spec = self.spec()
        spec["observed"]["frame_count"] = 5
        with self.assertRaisesRegex(ValueError, "exactly one row"):
            self.analyze(spec)
        spec = self.spec()
        path = self.folder / "spec.json"
        path.write_text(json.dumps(spec), encoding="utf-8")
        output = self.folder / "result.json"
        self.assertEqual(0, visual.main(["--spec", str(path), "--output", str(output)]))
        snapshot = path.read_bytes()
        with self.assertRaises(SystemExit):
            visual.main(["--spec", str(path), "--output", str(path)])
        self.assertEqual(snapshot, path.read_bytes())

    @unittest.skipUnless((GENERATOR / "artifacts/mixed/raw-fixtures.json").exists(), "Supplied generator evidence not installed")
    def test_actual_supplied_generator_native_i420_and_rgb_frames(self):
        manifest_path = GENERATOR / "artifacts/mixed/raw-fixtures.json"
        fixture = json.loads(manifest_path.read_text(encoding="utf-8"))["fixtures"][0]
        fixture["path"] = str(manifest_path.parent / fixture["path"])
        fixture["sidecars"] = {name: {**row, "path": str(manifest_path.parent / row["path"])}
                               for name, row in fixture["sidecars"].items()}
        for fmt, media in (("I420", fixture), ("RGB24", fixture["sidecars"]["marked_rgb"])):
            with self.subTest(fmt=fmt):
                spec = self.spec()
                spec["reference"] = fixture
                spec["observed"] = {**media, "width": fixture["width"], "height": fixture["height"],
                                    "frame_count": fixture["frame_count"], "format": fmt, "stage": "reference-adapter-verification"}
                rows = [{"sample_index": i, "stream_generation": "export", "loop_iteration": 0,
                         "time_ns": i * 1_000_000_000 // fixture["fps"]} for i in range(fixture["frame_count"])]
                spec["samples"] = self.csv_record("actual-samples.csv", visual.SAMPLE_FIELDS, rows)
                spec["planned"]["end_ns"] = fixture["frame_count"] * 1_000_000_000 // fixture["fps"]
                spec["planned"]["segments"] = [{"stream_generation": "export", "loop_iteration": 0,
                                               "first_file_frame": 0, "frame_count": fixture["frame_count"], "start_ns": 0}]
                result = self.analyze(spec)
                self.assertEqual("MEASURED", result["measurement_status"])
                self.assertEqual(44, len(result["observations"]))
                self.assertEqual([], result["missing"])
                self.assertTrue(all(row["marker_status"] == "MATCHED" for row in result["observations"]))


if __name__ == "__main__":
    unittest.main()
