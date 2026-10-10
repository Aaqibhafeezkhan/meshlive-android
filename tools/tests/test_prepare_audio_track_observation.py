"""Hardware sample-clock adapter regressions using owned temporary PCM evidence."""
from contextlib import redirect_stderr, redirect_stdout
import csv
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import wave

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_audio_track_observation as track


class TrackObservationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="track-clock-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.count = 2400
        with wave.open(str(self.folder / "capture.wav"), "wb") as target:
            target.setnchannels(1)
            target.setsampwidth(2)
            target.setframerate(48000)
            target.writeframes(b"\0\0" * self.count)
        self.rows = [{"sample_start": i * 480, "sample_count": 480, "source_pts_us": i * 10000,
                      "stage": "track_accepted", "at_ns": 1_020_000_000 + i * 10_000_000,
                      "audio_frame_position": i * 480, "audio_timestamp_ns": 1_000_000_000 + i * 10_000_000,
                      "audio_underrun_count": 0} for i in range(5)]
        self.summary = {"schema": "benchmark-pcm-trace-v1", "session": "session-1", "pid": 17,
                        "role": "receiver", "clock": "System.nanoTime", "sample_rate": 48000, "channels": 1,
                        "sample_count": self.count, "attempted_samples": self.count, "trace_overflow_chunks": 0,
                        "finalized": True, "error": None, "audio_track_initial_written_frames": 0,
                        "wav": "capture.wav", "csv": "capture.csv"}

    def pointer(self, name):
        return {"path": name, "sha256": hashlib.sha256((self.folder / name).read_bytes()).hexdigest()}

    def spec(self, rows=None, fields=None, summary=None):
        fields = fields or (*track.FIELDS, track.UNDERRUN_FIELD)
        with (self.folder / "capture.csv").open("w", newline="", encoding="utf-8") as target:
            writer = csv.DictWriter(target, fieldnames=fields, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(self.rows if rows is None else rows)
        (self.folder / "summary.json").write_text(json.dumps(self.summary if summary is None else summary), encoding="utf-8")
        return {"schema": "benchmark-audio-track-observation-v1", "run_id": "run-1", "device": "a", "pid": 17,
                "session": "session-1", "clock": "run-1:a:17:System.nanoTime", "wav": self.pointer("capture.wav"),
                "chunks": self.pointer("capture.csv"), "summary": self.pointer("summary.json"),
                "controls": {"maximum_anchor_age_ns": 100_000_000, "maximum_anchor_gap_ns": 50_000_000,
                             "maximum_rate_error_ppm": 1000, "timestamp_uncertainty_ns": 25000}}

    def analyze(self, spec):
        return track.analyze(spec, self.folder)

    def test_known_clock_brackets_and_no_extrapolation_into_tail(self):
        result = self.analyze(self.spec())
        self.assertEqual("benchmark-pcm-sample-clock-v1", result["schema"])
        self.assertEqual("MEASURED", result["measurement_status"])
        self.assertEqual("estimated_track_output", result["role"])
        self.assertEqual(4, len(result["spans"]))
        first = result["spans"][0]
        self.assertEqual((0, 480), (first["sample_start"], first["sample_end"]))
        self.assertEqual((1_000_000_000, 1_010_000_000), (first["start_ns"], first["end_ns"]))
        self.assertAlmostEqual(1e9 / 48000, first["ns_per_sample"])
        self.assertTrue(first["endpoint_inclusive"])
        self.assertEqual([{"start_sample": 1921, "end_sample": 2400}], result["unknown_ranges"])
        self.assertFalse(result["timeline_complete"])
        self.assertEqual([], result["measurements"])

    def test_unknown_origin_unfinalized_overflow_and_count_mismatch_do_not_map(self):
        for field, value in (("audio_track_initial_written_frames", None), ("finalized", False),
                             ("trace_overflow_chunks", 1), ("attempted_samples", 2500), ("sample_count", 2000),
                             ("audio_track_initial_written_frames", False)):
            with self.subTest(field=field):
                result = self.analyze(self.spec(summary={**self.summary, field: value}))
                self.assertEqual("UNMEASURED", result["measurement_status"])
                self.assertEqual([], result["spans"])
                self.assertEqual([{"start_sample": 0, "end_sample": 2400}], result["unknown_ranges"])

    def test_gap_or_wrong_stage_invalidates_exact_submitted_sample_origin(self):
        for mode in ("gap", "stage", "count"):
            rows = [dict(row) for row in self.rows]
            if mode == "gap":
                rows[1]["sample_start"] += 480
            elif mode == "stage":
                rows[1]["stage"] = "decoded_without_track_write"
            else:
                rows.pop()
            result = self.analyze(self.spec(rows))
            self.assertEqual("UNMEASURED", result["measurement_status"])
            self.assertFalse(result["spans"])

    def test_missing_anchor_breaks_interpolation_bracket(self):
        rows = [dict(row) for row in self.rows]
        rows[2]["audio_frame_position"] = None
        rows[2]["audio_timestamp_ns"] = None
        result = self.analyze(self.spec(rows))
        self.assertEqual([(0, 480), (1440, 1920)], [(row["sample_start"], row["sample_end"]) for row in result["spans"]])
        self.assertIn({"start_sample": 481, "end_sample": 1440}, result["unknown_ranges"])
        self.assertIn("hardware_timestamp_unavailable", result["issues"])

    def test_identical_cached_anchor_is_retained_once(self):
        rows = [dict(row) for row in self.rows]
        rows[2]["audio_frame_position"] = rows[1]["audio_frame_position"]
        rows[2]["audio_timestamp_ns"] = rows[1]["audio_timestamp_ns"]
        result = self.analyze(self.spec(rows))
        self.assertEqual(4, len(result["anchors"]))
        self.assertEqual("REUSED", result["diagnostics"][2]["status"])
        self.assertEqual(3, len(result["spans"]))

    def test_same_frame_position_with_changed_time_is_not_a_new_frame_clock(self):
        rows = [dict(row) for row in self.rows]
        rows[2]["audio_frame_position"] = rows[1]["audio_frame_position"]
        result = self.analyze(self.spec(rows))
        self.assertIn("same_hardware_position_has_conflicting_time", result["issues"])
        self.assertFalse(any(row["sample_start"] == 480 and row["sample_end"] > 480 for row in result["spans"]))

    def test_hardware_position_reset_disables_later_origin_assumption(self):
        rows = [dict(row) for row in self.rows]
        rows[2]["audio_frame_position"] = 0
        result = self.analyze(self.spec(rows))
        self.assertIn("hardware_position_reset", result["issues"])
        self.assertEqual(1, len(result["spans"]))
        self.assertEqual({"start_sample": 481, "end_sample": 2400}, result["unknown_ranges"][0])

    def test_position_reset_in_stale_stamp_still_invalidates_later_origin(self):
        rows = [dict(row) for row in self.rows]
        rows[2]["audio_frame_position"] = 0
        rows[2]["audio_timestamp_ns"] = 100
        result = self.analyze(self.spec(rows))
        self.assertIn("hardware_position_reset", result["issues"])
        self.assertEqual(1, len(result["spans"]))

    def test_underrun_counter_reset_invalidates_later_track_origin(self):
        rows = [dict(row) for row in self.rows]
        for row in rows[:2]:
            row["audio_underrun_count"] = 7
        result = self.analyze(self.spec(rows))
        self.assertIn("hardware_underrun_counter_reset", result["issues"])
        self.assertEqual(1, len(result["spans"]))

    def test_counter_change_on_cached_stamp_never_bridges_pre_failure_anchor(self):
        rows = [dict(row) for row in self.rows]
        rows[2]["audio_frame_position"] = rows[1]["audio_frame_position"]
        rows[2]["audio_timestamp_ns"] = rows[1]["audio_timestamp_ns"]
        for row in rows[2:]:
            row["audio_underrun_count"] = 1
        result = self.analyze(self.spec(rows))
        self.assertIn("changed_or_unknown_underrun_counter_at_cached_anchor", result["issues"])
        self.assertEqual([(0, 480), (1440, 1920)], [(row["sample_start"], row["sample_end"]) for row in result["spans"]])

    def test_changed_underrun_counter_forbids_interpolation_across_change(self):
        rows = [dict(row) for row in self.rows]
        for row in rows[2:]:
            row["audio_underrun_count"] = 1
        result = self.analyze(self.spec(rows))
        self.assertIn("underrun_counter_changed", result["issues"])
        self.assertEqual([(0, 480), (960, 1440), (1440, 1920)], [(row["sample_start"], row["sample_end"]) for row in result["spans"]])
        self.assertIn({"start_sample": 481, "end_sample": 960}, result["unknown_ranges"])

    def test_old_csv_missing_underrun_counter_cannot_establish_continuous_output(self):
        result = self.analyze(self.spec(fields=track.FIELDS))
        self.assertEqual("UNMEASURED", result["measurement_status"])
        self.assertFalse(result["spans"])
        self.assertEqual(5, len(result["anchors"]))
        self.assertIn("underrun_counter_missing", result["issues"])

    def test_stale_future_and_outside_submitted_hardware_anchors_stay_unknown(self):
        for mode in ("stale", "future", "outside"):
            with self.subTest(mode=mode):
                rows = [dict(row) for row in self.rows]
                if mode == "stale":
                    rows[2]["audio_timestamp_ns"] = 100
                elif mode == "future":
                    rows[2]["audio_timestamp_ns"] = rows[2]["at_ns"] + 1
                else:
                    rows[2]["audio_frame_position"] = 24000
                result = self.analyze(self.spec(rows))
                self.assertEqual("UNMEASURED", result["diagnostics"][2]["status"])
                self.assertFalse(any(row["sample_start"] < 960 < row["sample_end"] for row in result["spans"]))

    def test_rate_jump_or_excessively_long_anchor_gap_remains_unknown(self):
        spec = self.spec()
        spec["controls"]["maximum_anchor_gap_ns"] = 5_000_000
        result = self.analyze(spec)
        self.assertEqual("UNMEASURED", result["measurement_status"])
        self.assertIn("unbracketed_gap_or_rate_change", result["issues"])
        rows = [dict(row) for row in self.rows]
        for row in rows[2:]:
            row["audio_timestamp_ns"] += 5_000_000
        result = self.analyze(self.spec(rows))
        self.assertFalse(any(row["sample_start"] == 480 and row["sample_end"] == 960 for row in result["spans"]))

    def test_ambiguous_clock_namespace_or_summary_identity_rejected(self):
        spec = self.spec()
        spec["clock"] = "System.nanoTime"
        with self.assertRaisesRegex(ValueError, "namespace"):
            self.analyze(spec)
        spec = self.spec(summary={**self.summary, "pid": 19})
        with self.assertRaisesRegex(ValueError, "PID differs"):
            self.analyze(spec)

    def test_hash_and_truncated_wav_are_rejected(self):
        spec = self.spec()
        spec["chunks"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.analyze(spec)
        target = self.folder / "capture.wav"
        target.write_bytes(target.read_bytes()[:-10])
        spec = self.spec()
        with self.assertRaisesRegex(ValueError, "Truncated PCM"):
            self.analyze(spec)

    def test_output_is_explicit_fresh_and_inputs_unchanged(self):
        spec = self.spec()
        specification = self.folder / "spec.json"
        specification.write_text(json.dumps(spec), encoding="utf-8")
        originals = {name: (self.folder / name).read_bytes() for name in ("capture.wav", "capture.csv", "summary.json")}
        target = self.folder / "result.json"
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, track.main(["--spec", str(specification), "--output", str(target)]))
        for name, data in originals.items():
            self.assertEqual(data, (self.folder / name).read_bytes())
        saved = target.read_bytes()
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            track.main(["--spec", str(specification), "--output", str(target)])
        self.assertEqual(saved, target.read_bytes())


if __name__ == "__main__":
    unittest.main()
