import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import tools.score_media_delivery as media
from tools.score_media_delivery import align_delivery, planned_pts, verify_ivf, write_timeline, active_phase_plan, score_phase_delivery


class MediaDeliveryTest(unittest.TestCase):
    def test_split_migrated_phase_samples_are_each_hash_checked_at_their_exact_alias(self):
        import benchmark_layout
        import tools.score_media_delivery as media
        with tempfile.TemporaryDirectory(prefix="relocated-phases-") as directory:
            project = Path(directory)
            source = project / "artifacts/original-run"
            source.mkdir(parents=True)
            manifest = self.phase_run(source)
            aliases = {}
            for index, path in enumerate(list(source.iterdir())):
                target = project / f"benchmarks/runs/phase-{index}" / path.name
                target.parent.mkdir(parents=True)
                target.write_bytes(path.read_bytes())
                aliases[path.relative_to(project).as_posix()] = target.relative_to(project).as_posix()
                path.unlink()
            source.rmdir()
            catalog = project / "benchmarks/catalog"
            catalog.mkdir()
            (catalog / "ledger.json").write_text(json.dumps({"storage": {"paths": aliases}}))
            def decode(command, cwd=None):
                Path(command[-1]).write_bytes(b"\x10" * 6)
            with patch.object(media, "resolve", side_effect=lambda path, base=None: benchmark_layout.resolve(path, base, project)), \
                    patch.object(media, "run", side_effect=decode), \
                    patch.object(media, "score_quality", return_value={"scored_frames": 8}):
                result = media.score_phase_delivery(manifest, "b", project / "output", source)
                self.assertEqual("MEASURED", result["measurement_status"])
                self.assertEqual(8, result["planned_frames"])
                altered = project / aliases["artifacts/original-run/s1.csv"]
                altered.write_bytes(altered.read_bytes() + b"changed")
                with self.assertRaisesRegex(ValueError, "hash/bytes changed"):
                    media.score_phase_delivery(manifest, "b", project / "different-output", source)
            self.assertFalse(source.exists())

    def test_exact_source_pts_maps_hole_and_missing_tail_without_ordinal_shift(self):
        pts = planned_pts(5, 15)
        sender = [(value, str(index)) for index, value in enumerate(pts)]
        receiver = [sender[0], sender[2]]
        indices, result = align_delivery(sender, receiver, pts)
        self.assertEqual([0, 0, 1, 1, 1], indices)
        self.assertEqual(3, result["transport_missing_frames"])
        self.assertEqual(2, result["trailing_missing_frames"])
        self.assertEqual(40.0, result["fixture_received_coverage_percent"])
        self.assertEqual(["received", "hold", "received", "hold", "hold"], [row["kind"] for row in result["timeline"]])

    def test_missing_first_frame_is_black_not_a_shifted_later_frame(self):
        pts = planned_pts(4, 30, origin_us=900_000)
        indices, result = align_delivery([(pts[1], "b"), (pts[3], "d")], [(pts[1], "b")], pts)
        self.assertEqual([None, 0, 0, 0], indices)
        self.assertEqual(2, result["encoder_missing_frames"])
        self.assertEqual(1, result["transport_missing_frames"])
        self.assertEqual(1, result["black_frames"])

    def test_duplicates_wrong_hash_unknown_sender_and_off_grid_pts_are_rejected(self):
        pts = planned_pts(2, 15)
        cases = [
            ([(0, "a"), (0, "a")], []),
            ([(0, "a")], [(0, "a"), (0, "a")]),
            ([(0, "a")], [(0, "b")]),
            ([(0, "a")], [(pts[1], "b")]),
            ([(1, "a")], []),
        ]
        for sender, receiver in cases:
            with self.subTest(sender=sender, receiver=receiver), self.assertRaises(ValueError):
                align_delivery(sender, receiver, pts)

    def test_empty_delivery_preserves_every_planned_slot(self):
        pts = planned_pts(3, 15)
        indices, result = align_delivery([(value, "a") for value in pts], [], pts)
        self.assertEqual([None, None, None], indices)
        self.assertEqual(3, result["black_frames"])
        self.assertEqual(3, result["trailing_missing_frames"])
        self.assertEqual(0.0, result["fixture_received_coverage_percent"])

    def test_reconstructed_bytes_hold_last_frame_through_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            decoded, output = Path(directory) / "decoded.yuv", Path(directory) / "out.yuv"
            first, second = bytes([31]) * 6, bytes([63]) * 6
            decoded.write_bytes(first + second)
            write_timeline(decoded, output, [None, 0, 0, 1, 1], 2, 2, 2)
            self.assertEqual(bytes([16]) * 4 + bytes([128]) * 2 + first * 2 + second * 2, output.read_bytes())
            decoded.write_bytes(first)
            with self.assertRaises(ValueError):
                write_timeline(decoded, output, [0], 2, 2, 2)

    def test_ivf_payload_and_count_must_match_csv_before_scoring(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.ivf"
            payload = b"an access unit"
            path.write_bytes(struct.pack("<4sHH4sHHIIII", b"DKIF", 0, 32, b"VP90", 2, 2, 15, 1, 1, 0)
                             + struct.pack("<IQ", len(payload), 0) + payload)
            self.assertEqual((b"VP90", len(payload)), verify_ivf(path, [(0, hashlib.sha256(payload).hexdigest())], 2, 2))
            with self.assertRaises(ValueError):
                verify_ivf(path, [(0, "0" * 64)], 2, 2)
            with self.assertRaises(ValueError):
                verify_ivf(path, [], 2, 2)

    def test_phase_plan_keeps_clock_bounded_missing_tail_and_resets_cyclic_reference(self):
        phases = [{"phase": "one", "fps": 15, "raw_timeline": {"origin_pts_us": 1_000_000, "end_pts_exclusive_us": 1_300_000}},
                  {"phase": "two", "fps": 15, "raw_timeline": {"origin_pts_us": 2_000_000, "end_pts_exclusive_us": 2_200_000}}]
        pts, reference_indices, counts = active_phase_plan(phases, 3)
        self.assertEqual(planned_pts(5, 15, 1_000_000) + planned_pts(3, 15, 2_000_000), pts)
        self.assertEqual([0, 1, 2, 0, 1, 0, 1, 2], reference_indices)
        self.assertEqual([5, 3], [phase["planned_frames"] for phase in counts])
        overlap = [{**phases[1], "raw_timeline": {"origin_pts_us": 1_200_000, "end_pts_exclusive_us": 1_400_000}}]
        with self.assertRaises(ValueError):
            active_phase_plan([phases[0], *overlap], 3)

    def phase_run(self, directory):
        reference = directory / "fixture.yuv"
        reference.write_bytes(b"\x01" * 6 + b"\x02" * 6 + b"\x03" * 6)
        fixture_hash = hashlib.sha256(reference.read_bytes()).hexdigest()
        fixture = {"path": "fixture.yuv", "sha256": fixture_hash, "format": "I420", "width": 2,
                   "height": 2, "frames": 3, "bytes": 18, "loops": True}
        manifest = {"run_id": "case", "scenario": {"source": "a", "profile": {"width": 2, "height": 2, "fps": 15}},
                    "source_fixture": fixture, "media_traces": []}
        def trace(name, device, role, rows, origin=None, end=None):
            phase = "case-" + role + "-" + name[-1]
            csv_file, ivf_file, summary_file = [directory / (name + suffix) for suffix in (".csv", ".ivf", ".json")]
            csv_file.write_text("pts_us,sha256\n" + "".join(f"{pts},{hashlib.sha256(payload).hexdigest()}\n" for pts, payload in rows))
            ivf_file.write_bytes(struct.pack("<4sHH4sHHIIII", b"DKIF", 0, 32, b"VP90", 2, 2, 15, 1, len(rows), 0) +
                                 b"".join(struct.pack("<IQ", len(payload), index) + payload for index, (_, payload) in enumerate(rows)))
            summary = {"phase": phase, "session": "case", "role": role, "codec": "vp9", "width": 2, "height": 2,
                       "fps": 15, "finalized": True, "frames": len(rows), "encoded_bytes": sum(len(payload) for _, payload in rows),
                       "csv": phase + ".csv", "ivf": phase + ".ivf"}
            if origin is not None:
                summary["raw_timeline"] = {"file": "benchmark-raw-" + fixture_hash + ".yuv", "fixture_frames": 3,
                                           "origin_pts_us": origin, "end_pts_exclusive_us": end}
            summary_file.write_text(json.dumps(summary))
            manifest["media_traces"].append({"device": device, "role": role, "phase": phase, "state": "MEASURED",
                "summary": summary, "files": [{"path": path.name, "remote_name": phase + suffix,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size}
                for path, suffix in ((csv_file, ".csv"), (ivf_file, ".ivf"), (summary_file, "-summary.json"))]})
        trace("s1", "a", "sender", [(1_000_000, b"one"), (1_133_333, b"two")], 1_000_000, 1_300_000)
        trace("s2", "a", "sender", [(2_000_000, b"three"), (2_066_666, b"four"), (2_133_333, b"five")], 2_000_000, 2_200_000)
        trace("r1", "b", "receiver", [(1_000_000, b"one")])
        trace("r2", "b", "receiver", [(2_066_666, b"four")])
        return manifest

    def test_whole_phases_decode_independently_and_score_every_missing_slot(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            manifest = self.phase_run(base)
            sidecars = {"frames": {"path": "frames.csv", "sha256": "a" * 64},
                        "marker_layout": {"path": "marker-layout.json", "sha256": "b" * 64}}
            manifest["source_fixture"]["declared_metadata"] = {"sidecars": sidecars}
            decoded_calls, scored = [], {}
            def decode(command, cwd=None):
                decoded_calls.append(command)
                Path(command[-1]).write_bytes(bytes([10 * len(decoded_calls)]) * 6)
            def quality(delivered, reference, width, height, fps, frames, output):
                scored.update(delivered=delivered.read_bytes(), reference=reference.read_bytes(), frames=frames)
                return {"scored_frames": frames, "vmaf": 0, "ssim": 0, "psnr_db": 0}
            def regions(reference, delivered, width, height, fps, indices, frames, layout, origin, panels_record=None):
                self.assertIsNone(panels_record)
                self.assertEqual(((base / "fixture.yuv").resolve(), 2, 2, 15, base), (reference, width, height, fps, origin))
                self.assertEqual([0, 1, 2, 0, 1, 0, 1, 2], indices)
                self.assertEqual(scored["delivered"], delivered.read_bytes())
                self.assertEqual((sidecars["frames"], sidecars["marker_layout"]), (frames, layout))
                return {"measurement_status": "MEASURED", "evidence": {"delivered": {"path": str(delivered)}}}
            with patch("tools.score_media_delivery.run", side_effect=decode), patch("tools.score_media_delivery.score_quality", side_effect=quality), \
                 patch("score_reference_regions.analyze", side_effect=regions):
                result = score_phase_delivery(manifest, "b", base / "output", base)
            self.assertEqual("MEASURED", result["measurement_status"])
            self.assertEqual(8, result["planned_frames"])
            self.assertEqual(3, result["encoder_missing_frames"])
            self.assertEqual(3, result["transport_missing_frames"])
            self.assertEqual(6, result["total_missing_frames"])
            self.assertEqual(1, result["trailing_missing_frames"])
            self.assertEqual([4, 2], [phase["held_frames"] for phase in result["phases"]])
            self.assertEqual([0, 0], [phase["black_frames"] for phase in result["phases"]])
            self.assertEqual(2, len(decoded_calls))
            self.assertEqual(b"\x0a" * 36 + b"\x14" * 12, scored["delivered"])
            self.assertEqual(b"".join(bytes([value]) * 6 for value in (1, 2, 3, 1, 2, 1, 2, 3)), scored["reference"])
            detail = result["content_regions"]
            self.assertEqual("MEASURED", detail["measurement_status"])
            self.assertEqual("UNMEASURED", detail["acceptance_status"])
            evidence = Path(detail["evidence"])
            self.assertEqual(hashlib.sha256(evidence.read_bytes()).hexdigest(), detail["sha256"])
            self.assertNotIn("path", json.loads(evidence.read_text())["evidence"]["delivered"])

    def quality_commands(self, threads):
        commands = []
        def fake_run(command, cwd=None):
            commands.append((command, cwd))
            joined = command[command.index("-lavfi") + 1]
            if "libvmaf" in joined:
                (cwd / "vmaf.json").write_text(json.dumps({"frames": [{}] * 4, "pooled_metrics": {"vmaf": {"mean": 91.5}}}))
                return subprocess.CompletedProcess(command, 0, "", "")
            if joined.endswith("ssim"):
                return subprocess.CompletedProcess(command, 0, "", "SSIM Y:0.9 All:0.912345 (10.5)")
            return subprocess.CompletedProcess(command, 0, "", "PSNR y:30 average:31.25 min:20 max:inf")
        with tempfile.TemporaryDirectory() as directory, patch.object(media, "run", side_effect=fake_run), \
                patch.dict(os.environ, {"BENCH_SCORE_THREADS": str(threads)}):
            result = media.score_quality(Path("d.yuv"), Path("r.yuv"), 2, 2, 15, 4, Path(directory))
        return commands, result

    def test_quality_passes_use_libvmaf_threads_without_changing_the_metric_commands(self):
        serial, serial_result = self.quality_commands(1)
        threaded, threaded_result = self.quality_commands(4)
        self.assertEqual(serial_result, threaded_result)
        self.assertEqual({"scored_frames": 4, "vmaf": 91.5, "ssim": 0.912345, "psnr_db": 31.25}, {
            key: serial_result[key] for key in ("scored_frames", "vmaf", "ssim", "psnr_db")})
        def graphs(commands):  # Threaded runs finish the three passes in any order
            return sorted(command[command.index("-lavfi") + 1] for command, _ in commands)
        base_vmaf = "libvmaf=log_fmt=json:log_path=vmaf.json"
        serial_graphs, threaded_graphs = graphs(serial), graphs(threaded)
        self.assertEqual([base_vmaf], [graph[graph.index("libvmaf"):] for graph in serial_graphs if "libvmaf" in graph])
        self.assertEqual([base_vmaf + ":n_threads=4"], [graph[graph.index("libvmaf"):] for graph in threaded_graphs if "libvmaf" in graph])
        self.assertEqual([graph for graph in serial_graphs if "libvmaf" not in graph],
                         [graph for graph in threaded_graphs if "libvmaf" not in graph])  # SSIM/PSNR graphs are byte-identical
        self.assertEqual(3, len(serial))
        self.assertEqual(3, len(threaded))

    def test_single_phase_decode_is_the_whole_decoded_stream_without_a_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            manifest = self.phase_run(base)
            manifest["media_traces"] = [trace for trace in manifest["media_traces"] if trace["phase"] != "case-receiver-2"]
            decoded_calls, scored = [], {}
            def decode(command, cwd=None):
                decoded_calls.append(command)
                Path(command[-1]).write_bytes(bytes([10]) * 6)
            def quality(delivered, reference, width, height, fps, frames, output):
                scored["delivered"] = delivered.read_bytes()
                return {"scored_frames": frames}
            with patch("tools.score_media_delivery.run", side_effect=decode), patch("tools.score_media_delivery.score_quality", side_effect=quality):
                result = score_phase_delivery(manifest, "b", base / "output", base)
            self.assertEqual(1, len(decoded_calls))
            self.assertEqual(1, result["received_frames"])
            self.assertEqual(b"\x0a" * 6 * 8, scored["delivered"])

    def test_invalid_region_sidecar_cannot_discard_whole_timeline_quality(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            manifest = self.phase_run(base)
            manifest["source_fixture"]["declared_metadata"] = {"sidecars": {"frames": {}, "marker_layout": {}}}
            def decode(command, cwd=None):
                Path(command[-1]).write_bytes(b"\x10" * 6)
            with patch("tools.score_media_delivery.run", side_effect=decode), \
                 patch("tools.score_media_delivery.score_quality", return_value={"scored_frames": 8, "vmaf": 0}), \
                 patch("score_reference_regions.analyze", side_effect=ValueError("Sidecar hash changed")):
                result = score_phase_delivery(manifest, "b", base / "output", base)
            self.assertEqual({"scored_frames": 8, "vmaf": 0}, result["quality"])
            self.assertEqual("UNMEASURED", result["content_regions"]["measurement_status"])
            self.assertIn("Sidecar hash changed", result["content_regions"]["reason"])
            self.assertFalse((base / "output/reference-regions.json").exists())

    def test_unfinalized_or_legacy_phase_never_scores_only_surviving_phases(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            for kind in ("unfinalized", "close_failed", "legacy", "null_timeline", "private_fixture_absent"):
                manifest = self.phase_run(base)
                if kind == "unfinalized":
                    manifest["media_traces"][-1]["state"] = "UNMEASURED"
                elif kind == "close_failed":
                    manifest["media_traces"][-1]["summary"]["finalized"] = False
                elif kind == "null_timeline":
                    manifest["media_traces"][0]["summary"]["raw_timeline"] = None
                elif kind == "private_fixture_absent":
                    del manifest["scenario"]["source"]
                    manifest["source_fixtures"] = {"b": manifest["source_fixture"]}
                else:
                    del manifest["media_traces"][0]["summary"]["raw_timeline"]
                with patch("tools.score_media_delivery.run") as decode, patch("tools.score_media_delivery.score_quality") as quality:
                    result = score_phase_delivery(manifest, "b", base / kind, base, "a")
                self.assertEqual("UNMEASURED", result["measurement_status"])
                self.assertNotIn("quality", result)
                decode.assert_not_called()
                quality.assert_not_called()

    def test_decoder_omission_cannot_shrink_a_receiver_phase_to_an_easy_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            manifest = self.phase_run(base)
            def decode(command, cwd=None):
                Path(command[-1]).write_bytes(b"")
            with patch("tools.score_media_delivery.run", side_effect=decode), patch("tools.score_media_delivery.score_quality") as quality:
                with self.assertRaisesRegex(ValueError, "every AU"):
                    score_phase_delivery(manifest, "b", base / "output", base)
            quality.assert_not_called()

    def test_phase_hash_geometry_and_reference_identity_cannot_be_substituted(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            for kind in ("trace_hash", "fixture_hash", "geometry"):
                manifest = self.phase_run(base)
                if kind == "trace_hash":
                    manifest["media_traces"][0]["files"][0]["sha256"] = "0" * 64
                elif kind == "fixture_hash":
                    manifest["source_fixture"]["sha256"] = "0" * 64
                else:
                    manifest["source_fixture"]["height"] = 4
                with patch("tools.score_media_delivery.run") as decode, self.assertRaises(ValueError):
                    score_phase_delivery(manifest, "b", base / kind, base)
                decode.assert_not_called()


if __name__ == "__main__":
    unittest.main()
