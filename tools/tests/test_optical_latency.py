import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location("optical", Path(__file__).parents[2] / "tools/analyze_optical_latency.py")
optical = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(optical)


class OpticalLatencyTest(unittest.TestCase):
    def test_delay_uses_original_variable_frame_timestamps(self):
        result = optical.pair_edges([(1, 1), (4, -1)], [(2, 1), (5, -1)],
                                    [0, .01, .05, .08, .10, .12], 0, 60)
        self.assertEqual([40, 20], [round(row["delay_ms"]) for row in result["matched"]])

    def test_repeated_events_and_contested_receiver_match_stay_ambiguous(self):
        for source, receiver in (([(1, 1)], [(2, 1), (3, 1)]), ([(1, 1), (2, 1)], [(3, 1)])):
            with self.subTest(source=source):
                result = optical.pair_edges(source, receiver, [0, .01, .02, .03], 0, 100)
                self.assertEqual([], result["matched"])
                self.assertTrue(all(row["status"] == "AMBIGUOUS" for row in result["events"]))

    def test_missing_event_keeps_its_identity_and_delay_uncertainty(self):
        result = optical.pair_edges([(1, 1), (2, -1)], [(3, 1)], [0, .01, .02, .04], 0, 100)
        self.assertEqual("MISSING", result["events"][1]["status"])
        self.assertEqual([10, 40], [round(value) for value in result["matched"][0]["delay_bounds_ms"]])

    def test_initial_or_invalid_edge_cannot_borrow_last_frame_for_uncertainty(self):
        for edge in ((0, 1), (-1, 1), (3, 1), (1, 0)):
            with self.subTest(edge=edge), self.assertRaises(ValueError):
                optical.pair_edges([edge], [(2, 1)], [0, .01, .02], 0, 100)

    def test_nominal_fps_cannot_replace_missing_duplicate_or_reversed_pts(self):
        for stamps in ((0, 1, 1), (0, 2, 1), (0, 1, None)):
            data = {"streams": [{"time_base": "1/1000", "avg_frame_rate": "90000/1"}],
                    "frames": [{"best_effort_timestamp": stamp} if stamp is not None else {} for stamp in stamps]}
            with self.subTest(stamps=stamps), patch.object(optical.subprocess, "run", return_value=Mock(stdout=json.dumps(data), returncode=0, stderr="")):
                with self.assertRaises(ValueError):
                    optical.read_frame_times(Path("recording.mp4"))

    def test_failed_or_warning_decode_is_an_explicit_observation_error(self):
        for code, error in ((1, "invalid data"), (0, "corrupt decoded frame")):
            with self.subTest(code=code), patch.object(optical.subprocess, "run", return_value=Mock(returncode=code, stderr=error)):
                with self.assertRaisesRegex(ValueError, "could not be decoded"):
                    optical.read_frame_times(Path("recording.mp4"))

    def test_existing_output_is_preserved_before_recording_or_process_access(self):
        with tempfile.TemporaryDirectory(prefix="optical-output-") as directory:
            output = Path(directory) / "result.json"
            output.write_bytes(b"measured original")
            with patch.object(optical.subprocess, "run") as process:
                with self.assertRaises(FileExistsError):
                    optical.main(["--video", "missing.mp4", "--source-roi", "0,0,3,3",
                                  "--receiver-roi", "3,3,3,3", "--output", str(output)])
            process.assert_not_called()
            self.assertEqual(b"measured original", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
