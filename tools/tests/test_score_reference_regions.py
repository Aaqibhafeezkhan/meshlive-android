"""Fixed native content masks never hide damaged planned frames or create a pass."""

import copy
import csv
from fractions import Fraction
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zlib

import numpy as np

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import score_reference_regions as regions
from score_parallel import DEFAULT_THREADS, THREADS_ENV, score_threads

# Generated fixtures of the in-repo test-video generator (from that folder: python -m videostress generate
# --config examples/mixed.json --output artifacts/mixed); VIDEO_STRESS_GENERATOR overrides the folder.
GENERATOR = Path(os.environ.get("VIDEO_STRESS_GENERATOR") or ROOT / "tools" / "video-stress-generator")
VARIANT = "8a" * 32


def native_frame(index, width=64, height=64, fps=30):
    yy, xx = np.mgrid[:height, :width]
    image = ((xx * 3 + yy * 2 + index * 29) % 200 + 25).astype(np.uint8)
    pts = Fraction(index, fps)
    payload = struct.pack(">HBIQI", 0x5653, 1, index, pts.numerator, pts.denominator)
    payload += struct.pack(">I", zlib.crc32(payload))
    grid = np.zeros((25, 26), np.uint8)
    grid[0] = 1
    grid[-1] = np.arange(26) % 2
    grid[1:-1, -1] = np.arange(23) % 2
    grid[1:-1, 1:-1] = np.repeat(np.unpackbits(np.frombuffer(payload, np.uint8)), 3).reshape(23, 24)
    image[:50, :52] = 16 + np.repeat(np.repeat(grid, 2, axis=0), 2, axis=1) * 219
    return image.tobytes() + bytes([128]) * (width * height // 2)


class ReferenceRegionsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="reference-regions-")
        self.addCleanup(self.temporary.cleanup)
        regions._VERIFIED.clear()
        self.folder = Path(self.temporary.name)
        self.raw = [native_frame(index) for index in range(6)]
        self.source = self.folder / "reference.i420"
        self.source.write_bytes(b"".join(self.raw))
        self.rows = []
        for index, raw in enumerate(self.raw):
            pts = Fraction(index, 30)
            row = {"file_frame": index, "frame_id": f"{VARIANT}:64x64@30:{index}",
                   "source_frame_id": hashlib.sha256(str(index).encode()).hexdigest(),
                   "source_frame": index % 3, "segment_id": "motion" if index < 3 else "noise",
                   "pattern_id": "p19" if index < 3 else "p22", "repeat_index": 0,
                   "pts_numerator": pts.numerator, "pts_denominator": pts.denominator}
            row.update({name: hashlib.sha256(raw).hexdigest() for name in regions.FRAME_FIELDS if name.endswith("sha256")})
            self.rows.append(row)
        included = 64 * 64 - 52 * 50
        self.layout = {"schema": "vsg-marker-v1", "status": "AVAILABLE", "cell_size": 2,
                       "cells": [26, 25], "bounds_xywh": [0, 0, 52, 50], "intrinsic_tracks": [],
                       "content_roi": {"geometry": [64, 64], "exclude_xywh": [[0, 0, 52, 50]],
                                       "status": "AVAILABLE", "included_pixels": included,
                                       "excluded_fraction": 1 - included / (64 * 64)}}

    def record(self, name, data):
        path = self.folder / name
        path.write_bytes(data)
        return {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}

    def frame_record(self, rows=None):
        path = self.folder / "frames.csv"
        with path.open("w", encoding="utf-8", newline="") as destination:
            writer = csv.DictWriter(destination, fieldnames=regions.FRAME_FIELDS)
            writer.writeheader()
            writer.writerows(self.rows if rows is None else rows)
        return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def analyze(self, indices=None, raw=None, rows=None, layout=None, fps=30, panels=None):
        indices = list(range(6)) if indices is None else indices
        data = b"".join(self.raw[index] for index in indices) if raw is None else raw
        delivered = self.folder / "delivered.i420"
        delivered.write_bytes(data)
        return regions.analyze(self.source, delivered, 64, 64, fps, indices,
                               self.frame_record(rows), self.record("layout.json", json.dumps(self.layout if layout is None else layout).encode()),
                               self.folder, panels_record=self.record("panels.json", json.dumps(panels).encode()) if panels else None)

    def mosaic(self):
        rows = copy.deepcopy(self.rows)
        for index, row in enumerate(rows):
            row.update(segment_id="mixed", pattern_id="mosaic", source_frame=index)
        panels = {"schema": "vsg-panels-v1", "geometry": {"width": 64, "height": 64, "fps": 30},
            "content_roi": copy.deepcopy(self.layout["content_roi"]), "segments": [{"segment_id": "mixed",
            "file_frame_start": 0, "file_frame_end": 6, "source_frame_count": 6, "repeat": 1,
            "panels": [{"id": "left", "bounds_xywh": [0, 0, 32, 64], "exclude_xywh": [[0, 0, 32, 50]]},
                       {"id": "right", "bounds_xywh": [32, 0, 32, 64], "exclude_xywh": [[32, 0, 20, 50]]}]}]}
        for panel in panels["segments"][0]["panels"]:
            panel.update(pattern="crossing_objects", pattern_id="p21", seed=3, params={"speed": 1}, render_mode="native")
        return rows, panels

    def test_native_panel_damage_is_localized_and_every_loop_tail_is_scored(self):
        rows, panels = self.mosaic()
        indices = list(range(6)) * 2 + [0]
        changed = []
        for index in indices:
            frame = np.frombuffer(self.raw[index], np.uint8).copy()
            frame[:4096].reshape(64, 64)[:, 52:] = 16
            changed.append(frame.tobytes())
        result = self.analyze(indices, raw=b"".join(changed), rows=rows, panels=panels)
        left, right = result["panels"]
        self.assertEqual(0, left["pooled_luma_mse"])
        self.assertGreater(right["pooled_luma_mse"], result["summary"]["pooled_luma_mse"])
        self.assertEqual(13, right["scored_frames"])
        self.assertEqual(indices, [frame["reference_file_frame"] for frame in right["frames"]])
        self.assertTrue(all(panel["acceptance_status"] == "UNMEASURED" for panel in result["panels"]))

    def test_panel_overlap_mask_and_short_segment_cannot_hide_failures(self):
        rows, panels = self.mosaic()
        for kind in ("overlap", "mask", "window", "geometry", "scaled"):
            declared = copy.deepcopy(panels)
            panel = declared["segments"][0]["panels"][1]
            if kind == "overlap":
                panel["bounds_xywh"] = [0, 0, 32, 64]
            elif kind == "mask":
                panel["exclude_xywh"].append([52, 0, 12, 64])
            elif kind == "window":
                declared["segments"][0]["file_frame_end"] = 5
            elif kind == "geometry":
                declared["geometry"]["fps"] = 60
            else:
                panel["render_mode"] = "scaled"
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                self.analyze(rows=rows, panels=declared)

    def test_shifted_calibration_marker_is_read_at_only_its_declared_native_position(self):
        moved = []
        for raw in self.raw:
            image = np.frombuffer(raw, np.uint8).copy()
            plane = image[:4096].reshape(64, 64)
            patch = plane[:50, :52].copy()
            plane[8:58, 8:60] = patch
            moved.append(image.tobytes())
        self.raw = moved
        self.source.write_bytes(b"".join(moved))
        for row, raw in zip(self.rows, moved):
            row.update({key: hashlib.sha256(raw).hexdigest() for key in regions.FRAME_FIELDS if key.endswith("sha256")})
        layout = copy.deepcopy(self.layout)
        layout["bounds_xywh"] = [8, 8, 52, 50]
        layout["content_roi"]["exclude_xywh"] = [[8, 8, 52, 50]]
        self.assertEqual("inf", self.analyze(layout=layout)["summary"]["pooled_luma_psnr_db"])
        layout["bounds_xywh"] = [6, 8, 52, 50]
        layout["content_roi"]["exclude_xywh"] = [[6, 8, 52, 50]]
        with self.assertRaisesRegex(ValueError, "marker disagrees"):
            self.analyze(layout=layout)

    def test_native_identical_frames_leave_acceptance_unmeasured(self):
        result = self.analyze()
        self.assertEqual(regions.SCHEMA, result["schema"])
        self.assertEqual("MEASURED", result["measurement_status"])
        self.assertEqual("UNMEASURED", result["acceptance_status"])
        self.assertEqual("UNMEASURED", result["scope"]["encoder_decoder_colour_application"])
        self.assertEqual(6, result["summary"]["scored_frames"])
        self.assertEqual("inf", result["summary"]["pooled_luma_psnr_db"])
        self.assertAlmostEqual(1, result["summary"]["mean_luma_ssim"], places=12)
        self.assertEqual(6, result["summary"]["luma_psnr_db_distribution"]["perfect_count"])
        self.assertEqual(2, len(result["segments"]))
        self.assertTrue(all(len(row["reference_members_sha256"]) == 64 for row in result["segments"]))
        json.dumps(result, allow_nan=False)

    def test_gaussian_population_ssim_for_one_known_impulse(self):
        a = np.full((11, 11), 80, np.uint8)
        b = a.copy()
        b[5, 5] = 100
        mse, ssim = regions._pair(a, b, np.ones((11, 11), bool), np.ones((1, 1), bool))
        weight = 1 / sum(math.exp(-index * index / (2 * 1.5 * 1.5)) for index in range(-5, 6)) ** 2
        mean, variance = 80 + 20 * weight, 400 * weight * (1 - weight)
        c1, c2 = (.01 * 255) ** 2, (.03 * 255) ** 2
        expected = (2 * 80 * mean + c1) * c2 / ((80 * 80 + mean * mean + c1) * (variance + c2))
        self.assertAlmostEqual(400 / 121, mse, places=12)
        self.assertAlmostEqual(expected, ssim, places=12)

    def test_damage_only_inside_marker_has_no_valid_window_influence(self):
        changed = []
        for raw in self.raw:
            image = np.frombuffer(raw, np.uint8).copy()
            image[:64 * 64].reshape(64, 64)[:50, :52] = 111
            changed.append(image.tobytes())
        result = self.analyze(raw=b"".join(changed))
        self.assertEqual("inf", result["summary"]["pooled_luma_psnr_db"])
        self.assertAlmostEqual(1, result["summary"]["mean_luma_ssim"], places=12)
        self.assertEqual("UNMEASURED", result["scope"]["roi_acceptance"])

    def test_black_start_held_tail_and_bad_segment_all_count(self):
        black = bytes([16]) * (64 * 64) + bytes([128]) * (64 * 64 // 2)
        result = self.analyze(raw=black + self.raw[1] + self.raw[2] + self.raw[2] * 3)
        self.assertEqual(6, result["summary"]["scored_frames"])
        self.assertEqual(0, result["frames"][1]["luma_mse"])
        self.assertGreater(result["frames"][0]["luma_mse"], 0)
        noise = next(row for row in result["segments"] if row["segment_id"] == "noise")
        self.assertEqual(3, noise["scored_frames"])
        self.assertGreater(noise["pooled_luma_mse"], 0)
        self.assertLess(noise["mean_luma_ssim"], 1)
        self.assertLess(noise["luma_psnr_db_distribution"]["min"], result["summary"]["pooled_luma_psnr_db"])

    def test_explicit_loops_and_restart_mappings_keep_all_source_slots(self):
        indices = list(range(6)) * 2 + [0, 1, 0, 1]
        result = self.analyze(indices)
        self.assertEqual(16, result["summary"]["scored_frames"])
        self.assertEqual(indices, [row["reference_file_frame"] for row in result["frames"]])
        self.assertEqual({"numerator": 7, "denominator": 15}, result["frames"][14]["timeline_pts"])
        self.assertEqual(result["frames"][0]["frame_id"], result["frames"][6]["frame_id"])
        self.assertNotEqual(result["frames"][0]["timeline_frame"], result["frames"][6]["timeline_frame"])
        self.assertEqual(10, next(row for row in result["patterns"] if row["pattern_id"] == "p19")["scored_frames"])

    def test_groups_absent_from_provided_plan_remain_unmeasured(self):
        result = self.analyze([0, 1, 2])
        noise = next(row for row in result["segments"] if row["segment_id"] == "noise")
        self.assertEqual("UNMEASURED", noise["measurement_status"])
        self.assertEqual([3, 4, 5], result["summary"]["reference_frames_not_in_provided_plan"])
        self.assertIsNone(noise["pooled_luma_psnr_db"])

    def test_short_or_long_raw_timeline_cannot_select_a_good_prefix(self):
        for data, indices in ((b"".join(self.raw[:5]), list(range(6))),
                              (b"".join(self.raw), list(range(5))),
                              (b"".join(self.raw) + b"padding", list(range(6)))):
            with self.subTest(size=len(data), indices=indices), self.assertRaisesRegex(ValueError, "every provided planned slot"):
                self.analyze(indices, raw=data)

    def test_out_of_range_bool_and_missing_mapping_rejected(self):
        for indices in ([], [None], [True], [-1], [6]):
            with self.subTest(indices=indices), self.assertRaises(ValueError):
                self.analyze(indices, raw=self.raw[0])

    def test_changed_sidecar_hash_and_original_frame_bytes_rejected(self):
        delivered = self.folder / "delivered.i420"
        delivered.write_bytes(b"".join(self.raw))
        layout = self.record("layout.json", json.dumps(self.layout).encode())
        frames = self.frame_record()
        bad = {**frames, "sha256": "f" * 64}
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            regions.analyze(self.source, delivered, 64, 64, 30, list(range(6)), bad, layout, self.folder)
        content = bytearray(self.source.read_bytes())
        content[-100] ^= 1
        self.source.write_bytes(content)
        with self.assertRaisesRegex(ValueError, "frame pixel SHA256"):
            regions.analyze(self.source, delivered, 64, 64, 30, [0] * 6, frames, layout, self.folder)

    def test_rehashed_csv_pixel_hash_pts_or_profile_changes_rejected(self):
        for key, value in (("i420_sha256", "a" * 64), ("pts_numerator", 999),
                           ("frame_id", f"{VARIANT}:64x64@31:5")):
            rows = copy.deepcopy(self.rows)
            rows[-1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.analyze(rows=rows)
        with self.assertRaises(ValueError):
            self.analyze(fps=31)

    def test_rehashed_roi_geometry_exclusions_and_counts_rejected(self):
        for key, value in (("geometry", [32, 128]), ("exclude_xywh", [[0, 0, 52, 50], [52, 50, 12, 14]]),
                           ("exclude_xywh", []), ("included_pixels", 1), ("status", "UNMEASURED"),
                           ("excluded_fraction", .9)):
            layout = copy.deepcopy(self.layout)
            layout["content_roi"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.analyze(layout=layout)

    def test_intrinsic_marker_union_validated_when_external_marker_disabled(self):
        track = copy.deepcopy(self.layout)
        track.pop("intrinsic_tracks")
        track["segment_id"] = "motion"
        layout = copy.deepcopy(self.layout)
        layout.update(status="DISABLED", bounds_xywh=None, intrinsic_tracks=[track])
        result = self.analyze(layout=layout)
        self.assertEqual("inf", result["summary"]["pooled_luma_psnr_db"])
        layout["intrinsic_tracks"][0]["segment_id"] = "unknown"
        with self.assertRaisesRegex(ValueError, "known, unique"):
            self.analyze(layout=layout)

    def test_wrong_or_shifted_marker_bounds_rejected(self):
        for rect in ([1, 0, 52, 50], [0, 0, 65, 50]):
            layout = copy.deepcopy(self.layout)
            layout["bounds_xywh"] = rect
            with self.subTest(rect=rect), self.assertRaisesRegex(ValueError, "marker bounds"):
                self.analyze(layout=layout)

    def test_empty_content_mask_rejected(self):
        source = self.folder / "empty-content.i420"
        raw = native_frame(0, 52, 50)
        source.write_bytes(raw)
        row = copy.deepcopy(self.rows[0])
        row["frame_id"] = f"{VARIANT}:52x50@30:0"
        row.update({name: hashlib.sha256(raw).hexdigest() for name in regions.FRAME_FIELDS if name.endswith("sha256")})
        layout = copy.deepcopy(self.layout)
        layout["content_roi"].update(geometry=[52, 50], included_pixels=0, excluded_fraction=1)
        with self.assertRaisesRegex(ValueError, "nonempty native pixel mask"):
            regions.analyze(source, source, 52, 50, 30, [0], self.frame_record([row]),
                            self.record("layout.json", json.dumps(layout).encode()), self.folder)

    def test_cue_exclusion_requires_the_predeclared_track_and_cannot_mask_an_extra_area(self):
        declared = copy.deepcopy(self.layout)
        bounds = [54, 52, 8, 8]
        declared["visual_cues"] = {"schema": "vsg-visual-cues-v1", "bounds_xywh": bounds,
                                   "events": [{"bounds_xywh": bounds, "file_frame": 0, "on_frames": 2}]}
        roi = declared["content_roi"]
        roi["exclude_xywh"].append(bounds)
        roi["included_pixels"] -= 64
        roi["excluded_fraction"] = 1 - roi["included_pixels"] / 4096
        self.assertEqual("inf", self.analyze(layout=declared)["summary"]["pooled_luma_psnr_db"])
        declared.pop("visual_cues")
        with self.assertRaisesRegex(ValueError, "predeclared"):
            self.analyze(layout=declared)

    def test_tiny_known_mask_without_ssim_windows_stays_unmeasured(self):
        raw = bytes([40]) * 64 + bytes([128]) * 32
        source = self.folder / "tiny.i420"
        source.write_bytes(raw)
        row = copy.deepcopy(self.rows[0])
        row["frame_id"] = f"{VARIANT}:8x8@30:0"
        row.update({name: hashlib.sha256(raw).hexdigest() for name in regions.FRAME_FIELDS if name.endswith("sha256")})
        layout = {"schema": "vsg-marker-v1", "status": "DISABLED", "cell_size": 2, "cells": [26, 25],
                  "bounds_xywh": None, "intrinsic_tracks": [], "content_roi": {"geometry": [8, 8], "exclude_xywh": [],
                  "status": "AVAILABLE", "included_pixels": 64, "excluded_fraction": 0}}
        result = regions.analyze(source, source, 8, 8, 30, [0], self.frame_record([row]),
                                 self.record("layout.json", json.dumps(layout).encode()), self.folder)
        self.assertEqual(0, result["ssim_windows"])
        self.assertIsNone(result["summary"]["mean_luma_ssim"])
        self.assertEqual("UNMEASURED", result["measurements"][1]["status"])
        self.assertEqual("inf", result["summary"]["pooled_luma_psnr_db"])

    def test_source_marker_corruption_with_updated_pixel_hash_is_not_a_reference(self):
        altered = [bytearray(raw) for raw in self.raw]
        altered[0][:52] = b"\x10" * 52
        self.source.write_bytes(b"".join(altered))
        rows = copy.deepcopy(self.rows)
        rows[0]["i420_sha256"] = hashlib.sha256(altered[0]).hexdigest()
        with self.assertRaisesRegex(ValueError, "reference marker"):
            self.analyze(rows=rows)

    def test_chroma_is_explicitly_outside_luma_scope(self):
        changed = [raw[:64 * 64] + bytes([10]) * (64 * 64 // 2) for raw in self.raw]
        result = self.analyze(raw=b"".join(changed))
        self.assertEqual("inf", result["summary"]["pooled_luma_psnr_db"])
        self.assertTrue(any("chroma fidelity" in text for text in result["limitations"]))

    def test_supplied_44_frame_native_reference_with_each_pattern(self):
        manifest_path = GENERATOR / "artifacts/mixed/raw-fixtures.json"
        if not manifest_path.is_file():
            self.skipTest("Supplied native generator adapter fixture is unavailable")
        fixture = json.loads(manifest_path.read_text(encoding="utf-8"))["fixtures"][0]
        base = manifest_path.parent
        source = base / fixture["path"]
        self.assertEqual(fixture["sha256"], hashlib.sha256(source.read_bytes()).hexdigest())
        result = regions.analyze(source, source, fixture["width"], fixture["height"], fixture["fps"],
                                 list(range(fixture["frame_count"])), fixture["sidecars"]["frames"],
                                 fixture["sidecars"]["marker_layout"], base)
        self.assertEqual(44, result["summary"]["scored_frames"])
        self.assertEqual({"p01", "p14", "p28", "p30", "p34", "p39", "p40"}, {row["pattern_id"] for row in result["patterns"]})
        self.assertEqual(7, len(result["segments"]))
        self.assertEqual(fixture["sha256"], result["evidence"]["reference"]["sha256"])
        self.assertTrue(all(row["pooled_luma_psnr_db"] == "inf" for row in result["segments"]))
        self.assertAlmostEqual(1, result["summary"]["mean_luma_ssim"], places=12)
        self.assertEqual([], result["summary"]["reference_frames_not_in_provided_plan"])
        json.dumps(result, allow_nan=False)

    def test_thread_count_and_repeated_pixel_cache_never_change_any_score(self):
        rows, panels = self.mosaic()
        # Held delivered frames over repeated source indices: identical (reference, delivered) pixels recur.
        indices = [0, 1, 1, 2, 2, 2, 3, 4, 5, 0, 1, 1]
        data = [self.raw[index] for index in indices]
        data[2] = data[1]
        data[4] = data[3] = data[2] = data[1]
        calls = []
        original = regions._score_slot
        def counting(*args):
            calls.append(1)
            return original(*args)
        results = {}
        for threads in (1, 2, 4):
            del calls[:]
            delivered = self.folder / "delivered.i420"
            delivered.write_bytes(b"".join(data))
            with patch.object(regions, "_score_slot", side_effect=counting):
                results[threads] = regions.analyze(
                    self.source, delivered, 64, 64, 30, indices, self.frame_record(rows),
                    self.record("layout.json", json.dumps(self.layout).encode()), self.folder,
                    panels_record=self.record("panels.json", json.dumps(panels).encode()), threads=threads)
            self.assertLess(len(calls), len(indices))  # The repeated identical pairs were scored once
        self.assertEqual(json.dumps(results[1], sort_keys=True), json.dumps(results[2], sort_keys=True))
        self.assertEqual(json.dumps(results[1], sort_keys=True), json.dumps(results[4], sort_keys=True))
        self.assertEqual(len(indices), results[4]["summary"]["scored_frames"])
        self.assertEqual(indices, [frame["reference_file_frame"] for frame in results[4]["frames"]])
        self.assertEqual([len(indices)] * 2, [panel["scored_frames"] for panel in results[4]["panels"]])

    def test_full_reference_verification_is_reused_only_for_the_same_file_identity(self):
        delivered = self.folder / "delivered.i420"
        delivered.write_bytes(b"".join(self.raw))
        layout = self.record("layout.json", json.dumps(self.layout).encode())
        frames = self.frame_record()
        decoded = []
        original = regions.decode_marker
        def counting(*args, **kwargs):
            decoded.append(1)
            return original(*args, **kwargs)
        arguments = (self.source, delivered, 64, 64, 30, list(range(6)), frames, layout, self.folder)
        with patch.object(regions, "decode_marker", side_effect=counting):
            first = regions.analyze(*arguments)
            self.assertEqual(6, len(decoded))
            second = regions.analyze(*arguments)
            self.assertEqual(6, len(decoded))  # Verified once per identity
            self.assertEqual(first["evidence"]["reference"], second["evidence"]["reference"])
            content = bytearray(self.source.read_bytes())
            content[-100] ^= 1
            self.source.write_bytes(content)
            os.utime(self.source, ns=(1, self.source.stat().st_mtime_ns + 5_000_000_000))
            with self.assertRaisesRegex(ValueError, "frame pixel SHA256"):
                regions.analyze(*arguments)

    def test_scratch_buffer_metric_is_bit_identical_to_the_original_formulation(self):
        rng = np.random.default_rng(7)
        for height, width, hole in ((64, 64, (0, 0, 52, 50)), (37, 41, (5, 7, 3, 4)), (9, 9, None), (24, 16, (0, 0, 24, 8))):
            mask = np.ones((height, width), bool)
            if hole:
                x, y, w, h = hole
                mask[y:y + h, x:x + w] = False
            windows = (regions.minimum_filter(mask.astype(np.uint8), size=11, mode="constant", cval=0)[5:-5, 5:-5] != 0
                       if height >= 11 and width >= 11 else np.zeros((0, 0), bool))
            for kind in ("noise", "near", "identical", "flat", "black"):
                reference = rng.integers(0, 256, (height, width), dtype=np.uint8)
                delivered = {"noise": rng.integers(0, 256, (height, width), dtype=np.uint8),
                             "near": np.clip(reference.astype(int) + rng.integers(-6, 7, (height, width)), 0, 255).astype(np.uint8),
                             "identical": reference.copy(), "flat": np.full((height, width), 77, np.uint8),
                             "black": np.zeros((height, width), np.uint8)}[kind]
                with self.subTest(shape=(height, width), kind=kind):
                    # Non-contiguous panel-like views must also match.
                    view = (reference[:, :], delivered[:, :]) if width < 20 else (reference[:, 1:], delivered[:, 1:])
                    narrow = mask if width < 20 else mask[:, 1:]
                    narrow_windows = (regions.minimum_filter(narrow.astype(np.uint8), size=11, mode="constant", cval=0)[5:-5, 5:-5] != 0
                                      if narrow.shape[0] >= 11 and narrow.shape[1] >= 11 else np.zeros((0, 0), bool))
                    for args in ((reference, delivered, mask, windows), (*view, narrow, narrow_windows)):
                        expected = regions._pair_reference(*args)
                        for _ in range(2):  # The second call reuses the thread's scratch arrays
                            self.assertEqual(expected, regions._pair(*args))
                            self.assertEqual([type(value) for value in expected],
                                             [type(value) for value in regions._pair(*args)])

    def test_panel_scores_read_from_the_full_frame_map_equal_independent_panel_smoothing(self):
        rng = np.random.default_rng(11)
        height, width = 64, 96
        # Two panels tiling the frame, an excluded marker corner crossing the first, a third tiny panel row, and a
        # mask that leaves the whole frame without a valid 11x11 window (fallback path).
        layouts = {"normal": [((0, 0, 48, 64), [(0, 0, 40, 20)]), ((48, 0, 48, 64), [])],
                   "small": [((0, 0, 96, 8), []), ((0, 8, 96, 56), [(0, 0, 30, 30)])]}
        for name, panels_declared in layouts.items():
            for blocked in (False, True):
                mask = np.ones((height, width), bool)
                if blocked:
                    mask[::6, :] = False  # No 11x11 window fits anywhere
                windows = regions.minimum_filter(mask.astype(np.uint8), size=11, mode="constant", cval=0)[5:-5, 5:-5] != 0
                panels = []
                for (x, y, pw, ph), holes in panels_declared:
                    panel_mask = mask[y:y + ph, x:x + pw].copy()
                    for hx, hy, hw, hh in holes:
                        panel_mask[hy:hy + hh, hx:hx + hw] = False
                    panel_windows = (regions.minimum_filter(panel_mask.astype(np.uint8), size=11, mode="constant", cval=0)[5:-5, 5:-5] != 0
                                     if pw >= 11 and ph >= 11 else np.zeros((0, 0), bool))
                    if panel_mask.any():
                        panels.append({"declared": {"bounds_xywh": [x, y, pw, ph]}, "mask": panel_mask, "windows": panel_windows})
                for kind in ("noise", "near", "identical"):
                    reference = rng.integers(0, 256, (height, width), dtype=np.uint8)
                    delivered = {"noise": rng.integers(0, 256, (height, width), dtype=np.uint8),
                                 "near": np.clip(reference.astype(int) + rng.integers(-8, 9, (height, width)), 0, 255).astype(np.uint8),
                                 "identical": reference.copy()}[kind]
                    with self.subTest(layout=name, blocked=blocked, kind=kind):
                        expected = regions._score_slot_reference(reference, delivered, mask, windows, panels)
                        actual = regions._score_slot(reference, delivered, mask, windows, panels)
                        self.assertEqual(expected[0], actual[0])
                        self.assertEqual(expected[1], [tuple(part) for part in actual[1]])
                        self.assertEqual(expected, (actual[0], [tuple(part) for part in actual[1]]))

    def test_score_threads_defaults_and_bounds(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop(THREADS_ENV, None)
            self.assertEqual(min(DEFAULT_THREADS, os.cpu_count() or 1), score_threads())
            os.environ[THREADS_ENV] = "1"
            self.assertEqual(1, score_threads())
            os.environ[THREADS_ENV] = "junk"
            self.assertEqual(min(DEFAULT_THREADS, os.cpu_count() or 1), score_threads())
            os.environ[THREADS_ENV] = "-3"
            self.assertEqual(1, score_threads())
            os.environ[THREADS_ENV] = "100000"
            self.assertEqual(os.cpu_count() or 1, score_threads())
        self.assertEqual(1, score_threads(1))


if __name__ == "__main__":
    unittest.main()
