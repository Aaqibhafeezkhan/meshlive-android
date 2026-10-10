import contextlib
import io
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.score_motion_timing import main, score_motion
from tools.tests.test_score_presentation_timing import event, snapshot




class MotionTimingTest(unittest.TestCase):
    def test_cli_reads_split_migrated_manifest_score_and_original_relative_log(self):
        import benchmark_layout
        import tools.score_motion_timing as motion
        self.write_log(snapshot(500, 1, [event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                                      event(2, "TEXTURE", "MATCHED", reported=200, observed=200)]))
        saved = {"manifest.json": json.dumps(self.manifest).encode(), "score.json": json.dumps(self.score).encode(),
                 "receiver.log": (self.base / "receiver.log").read_bytes()}
        aliases = {}
        for index, (name, data) in enumerate(saved.items()):
            target = self.base / f"benchmarks/runs/unit-{index}" / name
            target.parent.mkdir(parents=True)
            target.write_bytes(data)
            aliases["artifacts/original-run/" + name] = target.relative_to(self.base).as_posix()
        catalog = self.base / "benchmarks/catalog"
        catalog.mkdir()
        (catalog / "ledger.json").write_text(json.dumps({"storage": {"paths": aliases}}))
        output = self.base / "motion.json"
        with patch.object(motion, "resolve", side_effect=lambda path, base=None: benchmark_layout.resolve(path, base, self.base)), \
                patch.object(motion, "original", side_effect=lambda path: benchmark_layout.original(path, self.base)), \
                contextlib.redirect_stdout(io.StringIO()):
            motion.main(["--manifest", str(self.base / "artifacts/original-run/manifest.json"), "--output", str(output)])
        result = json.loads(output.read_text())
        self.assertEqual(self.score, result["existing_score"])
        self.assertEqual(1, len(result["devices"]))
        for name, data in saved.items():
            self.assertEqual(data, (self.base / aliases["artifacts/original-run/" + name]).read_bytes())
        self.assertFalse((self.base / "artifacts/original-run").exists())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.spec = {"device": "receiver", "role": "receiver", "pid": 21,
                     "start": "10-03 18:00:00.000", "end": "10-03 18:01:00.000", "path": "receiver.log"}
        self.manifest = {"run_id": "case", "expected_fps": 30,
                         "scenario": {"source": "source", "profile": {"fps": 30}, "video_presentation_timing": True},
                         "logs": [self.spec]}
        self.score = {"run_id": "case", "thresholds": {"version": "device-run-v2"}, "overall_status": "FAIL",
                      "devices": [{"device": "receiver", "role": "receiver", "scope": {key: self.spec[key] for key in ("pid", "start", "end")},
                                   "gates": {"visible_fps": {"status": "FAIL", "value": 10.0, "limit": 28.5}},
                                   "freezes": {"unexpected": 2}, "actions": [{"action": "rejoin", "first_surface_ms": {"status": "FAIL", "value": 6000}}]}]}

    def write_log(self, text):
        lines = [f"10-03 18:00:30.000 21 22 I BluetoothVideo: {line}" for line in text.splitlines()]
        (self.base / self.spec["path"]).write_text("\n".join(lines), encoding="utf-8")

    def score_rows(self, rows, **kwargs):
        self.write_log(snapshot(500, 1, rows, **kwargs))
        return score_motion(self.manifest, self.score, self.base)

    def test_signed_receiver_lag_actual_intervals_and_source_fps_reference(self):
        self.spec["expected_fps"] = 24
        rows = [event(1), event(2, "TEXTURE", "MATCHED", reported=100_000_000, observed=70_000_000),
                event(3, "TEXTURE", "MATCHED", reported=133_333_333, observed=120_000_000),
                event(4, "TEXTURE", "MATCHED", reported=166_666_666, observed=140_000_000)]
        result = self.score_rows(rows)
        texture = result["devices"][0]["phases"][0]["callbacks"]["TEXTURE"]
        cadence = texture["notification_cadence_proxy"]
        self.assertEqual("COMPLETE", result["capture_state"])
        self.assertEqual(30, result["advertised_source_fps"])
        self.assertAlmostEqual(1e9 / 30, result["frame_period_ns"])
        self.assertEqual(20_000_000, cadence["captured_notification_interval_ns"]["min"])
        self.assertEqual(50_000_000, cadence["captured_notification_interval_ns"]["max"])
        self.assertLess(cadence["signed_period_deviation_ns"]["min"], 0)
        self.assertGreater(cadence["signed_period_deviation_ns"]["max"], 0)
        self.assertAlmostEqual(math.sqrt(((50e6 - 1e9 / 30) ** 2 + (20e6 - 1e9 / 30) ** 2) / 2), cadence["rms_jitter_ns"])
        self.assertEqual(-30_000_000, texture["matched_receiver_clock"]["callback_minus_target_ns"]["min"])
        self.assertEqual(-10_000_000, texture["matched_receiver_clock"]["callback_minus_release_call_ns"]["min"])
        self.assertEqual(self.score, result["existing_score"])
        self.assertEqual("UNMEASURED", result["source_to_screen_latency"]["state"])
        self.assertIsNone(result["glass_to_glass_latency"]["value"])

    def test_scope_generation_and_returning_stale_generation_do_not_bridge_phases(self):
        rows = [event(1, "TEXTURE", "MATCHED", generation=1, reported=100, observed=100),
                event(2, "TEXTURE", "MATCHED", generation=1, reported=120, observed=120),
                event(3, "TEXTURE", "MATCHED", generation=2, reported=200, observed=200),
                event(4, "TEXTURE", "STALE_GENERATION", generation=1, reported=220, observed=220),
                event(5, "TEXTURE", "MATCHED", generation=2, reported=240, observed=240)]
        text = snapshot(500, 1, rows) + "\n" + snapshot(600, 1, [event(1, "TEXTURE", "MATCHED", reported=1000, observed=1000)])
        self.write_log(text)
        result = score_motion(self.manifest, self.score, self.base)
        device = result["devices"][0]
        self.assertEqual([1, 2, 1, 2, 1], [phase["generation"] for phase in device["phases"]])
        self.assertEqual(3, len(device["boundary_spans"]))
        self.assertEqual([80, 20, 20], [span["observed_interval_ns"] for span in device["boundary_spans"]])
        intervals = [phase["callbacks"]["TEXTURE"]["notification_cadence_proxy"]["captured_notification_interval_ns"]["samples"]
                     for phase in device["phases"]]
        self.assertEqual([1, 0, 0, 0, 0], intervals)

    def test_trace_hole_keeps_entire_span_and_never_invents_intermediate_callbacks(self):
        rows = [event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                event(2, "TEXTURE", "MATCHED", reported=200, observed=200),
                event(3, "TEXTURE", "MATCHED", reported=400, observed=400)]
        result = self.score_rows(rows, parts=[(1, 3, rows[:1]), (3, 3, rows[2:])])
        cadence = result["devices"][0]["phases"][0]["callbacks"]["TEXTURE"]["notification_cadence_proxy"]
        self.assertEqual("MEASURED_WITH_GAPS", result["timing_state"])
        self.assertEqual(1, cadence["captured_notification_interval_ns"]["samples"])
        self.assertEqual(300, cadence["captured_notification_interval_ns"]["max"])
        self.assertEqual(1, cadence["intervals_crossing_unusable_trace"])
        self.assertEqual([{"start": 2, "end": 2, "count": 1}], cadence["intervals"][0]["missing_sequence_ranges"])

    def test_unknown_duplicate_notifications_are_counted_but_not_matched_as_frames(self):
        rows = [event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                event(2, "TEXTURE", "DUPLICATE", reported=100, observed=110),
                event(3, "TEXTURE", "UNMATCHED", target=None, release=None, pts=None, reported=99, observed=120)]
        text = snapshot(500, 1, rows, final=False) + "\n" + snapshot(500, 2, rows)
        self.write_log(text)
        result = score_motion(self.manifest, self.score, self.base)
        device = result["devices"][0]
        texture = device["phases"][0]["callbacks"]["TEXTURE"]
        self.assertEqual(3, device["identical_snapshot_repeats"])
        self.assertEqual(3, texture["notification_count"])
        self.assertEqual(1, texture["duplicate_notifications"])
        self.assertEqual(1, texture["unknown_identity_notifications"])
        self.assertEqual(2, texture["notification_cadence_proxy"]["captured_notification_interval_ns"]["samples"])
        self.assertEqual(1, texture["matched_receiver_clock"]["callback_minus_target_ns"]["samples"])
        self.assertEqual(-1, texture["notification_cadence_proxy"]["reported_timestamp_interval_ns"]["min"])

    def test_conflicting_event_identity_is_excluded_and_spanning_interval_is_flagged(self):
        rows = [event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                event(2, "TEXTURE", "MATCHED", reported=200, observed=200),
                event(3, "TEXTURE", "MATCHED", reported=300, observed=300)]
        changed = event(2, "TEXTURE", "MATCHED", reported=200, observed=210)
        self.write_log(snapshot(500, 1, rows, final=False) + "\n" + snapshot(500, 2, [rows[0], changed, rows[2]]))
        result = score_motion(self.manifest, self.score, self.base)
        device = result["devices"][0]
        texture = device["phases"][0]["callbacks"]["TEXTURE"]
        self.assertEqual(1, device["conflicting_event_identities"])
        self.assertEqual(4, len(device["raw_events"]))
        self.assertEqual(2, texture["notification_count"])
        intervals = texture["notification_cadence_proxy"]["intervals"]
        self.assertEqual(200, intervals[0]["observed_interval_ns"])
        self.assertTrue(intervals[0]["crosses_unusable_trace"])
        self.assertEqual([2], intervals[0]["conflicting_sequences"])

    def test_missing_observer_and_missing_source_fps_remain_unmeasured(self):
        self.write_log("decoder stats without presentation observation")
        off = score_motion(self.manifest, self.score, self.base)
        self.assertEqual("UNMEASURED", off["timing_state"])
        self.assertEqual([], off["devices"][0]["phases"])
        self.manifest.pop("expected_fps")
        self.manifest["scenario"]["profile"].pop("fps")
        result = self.score_rows([event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                                  event(2, "TEXTURE", "MATCHED", reported=200, observed=200)])
        self.assertIsNone(result["frame_period_ns"])
        cadence = result["devices"][0]["phases"][0]["callbacks"]["TEXTURE"]["notification_cadence_proxy"]
        self.assertIsNone(cadence["rms_jitter_ns"])
        self.assertEqual("UNMEASURED", cadence["period_deviation_state"])

    def test_partial_logs_missing_score_and_unfinalized_media_do_not_become_complete(self):
        self.write_log(snapshot(500, 1, [event(1)], final=False))
        self.manifest["logs"][0].pop("end")
        self.manifest["scenario"].update(media_trace=True, receivers=[{"phone": "receiver"}])
        self.manifest["media_traces"] = [{"device": "receiver", "state": "MEASURED", "summary": {"finalized": False}}]
        result = score_motion(self.manifest, None, self.base)
        self.assertEqual("PARTIAL", result["capture_state"])
        self.assertGreaterEqual(len(result["incomplete_reasons"]), 3)
        self.assertIsNone(result["existing_score"])

    def test_nonfinal_observer_is_partial_even_with_ended_manifest_and_matching_score(self):
        result = self.score_rows([event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                                  event(2, "TEXTURE", "MATCHED", reported=200, observed=200)], final=False)
        self.assertEqual("PARTIAL", result["capture_state"])
        self.assertTrue(result["existing_score_matches_run"])
        self.assertIn("Presentation observer lacks final META for receiver", result["incomplete_reasons"])

    def test_wall_time_source_pause_and_end_remain_unallocated_without_clock_mapping(self):
        self.manifest["planned_intervals"] = [{"device": "source", "action": "pause", "start": "10-03 18:00:10.000", "end": "10-03 18:00:20.000"}]
        self.manifest["selection_events"] = [{"device": "source", "action": "end", "time": "10-03 18:00:40.000"}]
        self.score["devices"][0]["evaluation_intervals"] = [{"device": "receiver", "action": "received_deliberate_end"}]
        result = self.score_rows([event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                                  event(2, "TEXTURE", "MATCHED", reported=1000, observed=1000)])
        device = result["devices"][0]
        self.assertEqual("UNMEASURED", device["control_attribution"]["state"])
        self.assertEqual(2, len(device["control_attribution"]["controls"]))
        self.assertEqual([], device["control_attribution"]["new_excluded_intervals"])
        self.assertEqual(self.score["devices"][0]["evaluation_intervals"], device["control_attribution"]["existing_score_exclusions"])
        self.assertEqual(900, device["phases"][0]["callbacks"]["TEXTURE"]["notification_cadence_proxy"]["captured_notification_interval_ns"]["max"])
        self.assertEqual(self.score, result["existing_score"])

    def test_receiver_pause_clock_separates_active_cadence_without_erasing_original_spans(self):
        rows = [event(index, "TEXTURE", "MATCHED", reported=now, observed=now)
                for index, now in enumerate((100, 200, 350, 1100, 1200, 1500), 1)]
        self.write_log(snapshot(500, 1, rows) + "\n" + "\n".join(
            f"BENCHMARK received stream control: state={state} nowNs={now}"
            for state, now in (("PAUSED", 300), ("PAUSED", 400), ("LIVE", 1000), ("ENDED", 1400))))
        result = score_motion(self.manifest, self.score, self.base)
        device = result["devices"][0]
        clock = device["received_state_cadence"]
        self.assertEqual("MEASURED", clock["state"])
        self.assertEqual(4, len(clock["anchors"]))
        self.assertEqual(300, clock["inactive_ranges"][0]["start_ns"])
        active = next(item for item in clock["active_summaries"] if item["kind"] == "TEXTURE")
        self.assertEqual(2, active["captured_notification_interval_ns"]["samples"])
        self.assertEqual(100, active["captured_notification_interval_ns"]["max"])
        self.assertEqual(3, len(clock["excluded_spans"]))
        self.assertEqual(750, device["phases"][0]["callbacks"]["TEXTURE"]["notification_cadence_proxy"]["captured_notification_interval_ns"]["max"])
        self.assertEqual(self.score, result["existing_score"])

    def test_another_process_control_clock_cannot_exclude_receiver_jitter(self):
        rows = [event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                event(2, "TEXTURE", "MATCHED", reported=1100, observed=1100)]
        self.write_log(snapshot(500, 1, rows))
        path = self.base / self.spec["path"]
        with path.open("a", encoding="utf-8") as stream:
            stream.write("\n10-03 18:00:30.000 44 45 I BluetoothVideo: BENCHMARK received stream control: state=PAUSED nowNs=300\n")
            stream.write("10-03 18:00:30.000 44 45 I BluetoothVideo: BENCHMARK received stream control: state=LIVE nowNs=1000\n")
        result = score_motion(self.manifest, self.score, self.base)
        clock = result["devices"][0]["received_state_cadence"]
        self.assertEqual("UNMEASURED", clock["state"])
        self.assertEqual([], clock["excluded_spans"])
        self.assertEqual(1000, result["devices"][0]["phases"][0]["callbacks"]["TEXTURE"]["notification_cadence_proxy"]["captured_notification_interval_ns"]["max"])

    def test_cli_retains_original_score_and_refuses_to_overwrite_it(self):
        self.write_log(snapshot(500, 1, [event(1, "TEXTURE", "MATCHED", reported=100, observed=100),
                                      event(2, "TEXTURE", "MATCHED", reported=200, observed=200)]))
        manifest_path, score_path, output = [self.base / name for name in ("manifest.json", "score.json", "motion.json")]
        manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")
        original = json.dumps(self.score)
        score_path.write_text(original, encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(["--manifest", str(manifest_path), "--output", str(output)]))
        self.assertEqual(self.score, json.loads(output.read_text(encoding="utf-8"))["existing_score"])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as stopped:
            main(["--manifest", str(manifest_path), "--output", str(score_path)])
        self.assertEqual(2, stopped.exception.code)
        self.assertEqual(original, score_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
