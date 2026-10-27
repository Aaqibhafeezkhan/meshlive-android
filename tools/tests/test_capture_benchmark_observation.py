"""Recorder lifecycle/ownership checks using mocks only; hardware stays closed."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import capture_benchmark_observation as capture
import run_benchmark_suite as suite


class ObservationCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="observation-lifecycle-")
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        tool = self.folder / "mock-tool"
        tool.write_bytes(b"Offline mocked executable identity")
        record = {"path": str(tool), "sha256": capture.digest(tool)}
        self.spec = {"schema": "benchmark-observation-capture-v1", "controlled_nonprivate_target": True,
            "clock": "recording-container", "recorder": "external-instrument", "ffmpeg": record, "ffprobe": record,
            "video": {"device": "Declared camera", "width": 64, "height": 64, "fps": 25, "pixel_format": "yuyv422"},
            "audio": {"device": "Declared microphone", "sample_rate": 48000, "sample_format": "s16", "channels": 2},
            "directions": [{"source": "a", "device": "b", "observes": ["source_target", "receiver_screen", "receiver_speaker"],
                "conditions": {"target": "controlled chart"}, "observation_plan": {
                    "source_roi": [0, 0, 24, 24], "receiver_roi": [40, 0, 24, 24],
                    "delay_search": {"minimum_ms": 0, "maximum_ms": 200},
                    "detector": {"source_threshold": 128, "receiver_threshold": 128, "minimum_contrast": 20},
                    "planned": {"clock": "recording-container", "start_ns": 0, "end_ns": 90_000_000_000,
                        "minimum_events": 4, "expected_source_events": 4, "max_frame_gap_ns": 50_000_000}}}]}
        self.path = self.folder / "spec.json"

    def prepared(self):
        self.path.write_text(json.dumps(self.spec))
        return capture.prepare({"path": str(self.path), "sha256": capture.digest(self.path)}, self.folder)

    def probe(self, width=64):
        return subprocess.CompletedProcess([], 0, json.dumps({"streams": [
            {"codec_type": "video", "codec_name": "ffv1", "pix_fmt": "yuv422p", "width": width, "height": 64, "r_frame_rate": "25/1"},
            {"codec_type": "audio", "sample_fmt": "s16", "sample_rate": "48000", "channels": 2}]}), "")

    def launch(self, args, **kwargs):
        Path(args[-1]).write_bytes(b"Mock acquisition bytes, never a phone pass")
        kwargs["stderr"].write(b"frame=1\nprogress=continue\n")
        kwargs["stderr"].flush()
        process = Mock(pid=51, returncode=0)
        process.poll.return_value = None
        return process

    def test_absent_or_incomplete_observation_controls_block_offline(self):
        valid = copy.deepcopy(self.spec["directions"][0]["observation_plan"])
        invalid = [None, {}, {"planned": valid["planned"]}]
        for key in valid:
            invalid.append({name: value for name, value in valid.items() if name != key})
        for section in ("delay_search", "detector", "planned"):
            for key in valid[section]:
                partial = copy.deepcopy(valid)
                del partial[section][key]
                invalid.append(partial)
        with patch.object(capture.subprocess, "Popen", side_effect=AssertionError("Hardware stays closed")), \
             patch.object(capture.subprocess, "run", side_effect=AssertionError("Hardware stays closed")):
            for plan in invalid:
                with self.subTest(plan=plan), self.assertRaisesRegex(ValueError, "observation_plan"):
                    self.spec["directions"][0]["observation_plan"] = plan
                    self.prepared()

    def test_native_roi_bounds_and_disjoint_observations_are_required_before_capture(self):
        valid = copy.deepcopy(self.spec["directions"][0]["observation_plan"])
        for key, box in (("source_roi", [-1, 0, 24, 24]), ("source_roi", [0, 0, 0, 24]),
                         ("source_roi", [0, 0, 24.0, 24]), ("source_roi", [True, 0, 24, 24]),
                         ("receiver_roi", [40, 0, 25, 24]), ("receiver_roi", [40, 41, 24, 24]),
                         ("receiver_roi", [0, 0, 24, 24])):
            with self.subTest(key=key, box=box), self.assertRaisesRegex(ValueError, "observation_plan"):
                self.spec["directions"][0]["observation_plan"] = copy.deepcopy(valid)
                self.spec["directions"][0]["observation_plan"][key] = box
                self.prepared()

    def test_clock_interval_events_and_finite_detector_search_are_required(self):
        valid = copy.deepcopy(self.spec["directions"][0]["observation_plan"])
        changes = [("planned", "clock", "other-container"), ("planned", "start_ns", 90_000_000_000),
            ("planned", "end_ns", True), ("planned", "minimum_events", 5),
            ("planned", "expected_source_events", 0), ("planned", "max_frame_gap_ns", 0),
            ("delay_search", "minimum_ms", -1), ("delay_search", "maximum_ms", -1),
            ("delay_search", "maximum_ms", float("inf")), ("detector", "source_threshold", 0),
            ("detector", "receiver_threshold", 255), ("detector", "minimum_contrast", 0),
            ("detector", "minimum_contrast", float("nan"))]
        for section, key, value in changes:
            with self.subTest(section=section, key=key, value=value), self.assertRaisesRegex(ValueError, "observation_plan"):
                self.spec["directions"][0]["observation_plan"] = copy.deepcopy(valid)
                self.spec["directions"][0]["observation_plan"][section][key] = value
                self.prepared()

    def test_start_waits_for_actual_video_output_before_returning(self):
        recorder = capture.ObservationRecorder(self.prepared(), self.folder / "delayed-start")
        self.addCleanup(lambda: recorder.log.close() if recorder.log is not None else None)

        def delayed_launch(args, **kwargs):
            process = self.launch(args, **kwargs)
            kwargs["stderr"].seek(0)
            kwargs["stderr"].truncate()
            kwargs["stderr"].write(b"frame=0\nprogress=continue\n")
            kwargs["stderr"].flush()
            return process

        def first_frame(_):
            recorder.log.write(b"frame=1\nprogress=continue\n")
            recorder.log.flush()

        with patch.object(capture.subprocess, "Popen", side_effect=delayed_launch), \
             patch.object(capture.time, "sleep", side_effect=first_frame) as wait, \
             patch.object(capture.subprocess, "run", return_value=self.probe()):
            recorder.start()
            self.assertTrue(wait.called, "Process creation cannot stand in for actual camera acquisition")
            self.assertEqual(1, recorder.result["startup_video_frames"])
            self.assertIn("capture_ready_ns", recorder.result)
            recorder.stop()

    def test_startup_timeout_keeps_interrupted_bytes_and_cannot_register(self):
        recorder = capture.ObservationRecorder(self.prepared(), self.folder / "no-frames")
        self.addCleanup(lambda: recorder.log.close() if recorder.log is not None else None)

        def no_frames(args, **kwargs):
            process = self.launch(args, **kwargs)
            kwargs["stderr"].seek(0)
            kwargs["stderr"].truncate()
            kwargs["stderr"].write(b"frame=0\nprogress=continue\n")
            kwargs["stderr"].flush()
            return process

        # A scripted clock local to the recorder module: patching the global time module with a finite list let any
        # other thread's monotonic() call (pytest, logging, a slow host) consume the values and end the loop early or
        # with StopIteration. After the script the clock stays past the deadline.
        clock_values = iter([0, 0])
        scripted = SimpleNamespace(monotonic=lambda: next(clock_values, 11), monotonic_ns=time.monotonic_ns,
                                   sleep=lambda _seconds: None)
        with patch.object(capture.subprocess, "Popen", side_effect=no_frames), \
             patch.object(capture, "time", scripted), \
             patch.object(capture.subprocess, "run", return_value=self.probe()):
            with self.assertRaisesRegex(ValueError, "video output"):
                recorder.start()
        self.assertEqual("INTERRUPTED", recorder.result["status"])
        self.assertTrue((recorder.output / "recording.nut").is_file())
        manifest = {}
        recorder.attach(manifest)
        self.assertNotIn("measurement_captures", manifest)

    def test_acquired_frames_can_be_buffered_before_the_os_file_size_changes(self):
        recorder = capture.ObservationRecorder(self.prepared(), self.folder / "buffered-start")
        self.addCleanup(lambda: recorder.log.close() if recorder.log is not None else None)

        def buffered_launch(args, **kwargs):
            process = self.launch(args, **kwargs)
            Path(args[-1]).write_bytes(b"")
            return process

        with patch.object(capture.subprocess, "Popen", side_effect=buffered_launch), \
             patch.object(capture.time, "sleep", side_effect=AssertionError("Acquisition is already ready")), \
             patch.object(capture.subprocess, "run", return_value=self.probe()):
            recorder.start()
            self.assertEqual(0, recorder.result["startup_recording_bytes"])
            self.assertEqual(1, recorder.result["startup_video_frames"])
            recorder.stop()

    def test_device_failure_after_process_creation_blocks_start(self):
        recorder = capture.ObservationRecorder(self.prepared(), self.folder / "late-start-failure")
        self.addCleanup(lambda: recorder.log.close() if recorder.log is not None else None)

        def delayed_failure(args, **kwargs):
            process = self.launch(args, **kwargs)
            kwargs["stderr"].seek(0)
            kwargs["stderr"].truncate()
            kwargs["stderr"].flush()
            process.poll.side_effect = [None, 3, 3]
            process.returncode = 3
            return process

        with patch.object(capture.subprocess, "Popen", side_effect=delayed_failure), \
             patch.object(capture.time, "sleep"), \
             patch.object(capture.subprocess, "run", side_effect=AssertionError("Failed acquisition must not qualify")):
            with self.assertRaisesRegex(ValueError, "exited during startup"):
                recorder.start()
        self.assertEqual("INTERRUPTED", recorder.result["status"])
        self.assertEqual(3, recorder.result["returncode"])
        recorder.process.wait.assert_not_called()

    def test_offline_plan_has_no_discovery_and_shared_instruments_serialize_lanes(self):
        with patch.object(capture.subprocess, "run", side_effect=AssertionError("Hardware I/O")), \
             patch.object(capture.subprocess, "Popen", side_effect=AssertionError("Hardware I/O")):
            prepared = self.prepared()
            capture.main(["--spec", str(self.path)])
        jobs = [{"phones": list(pair), "execution": "concurrent", "capture_resources": prepared["resources"],
                 "case": {"source": pair[0], "receivers": [{"phone": pair[1], "parent": pair[0]}]}}
                for pair in (("a", "b"), ("c", "d"))]
        self.assertEqual([1, 1], [len(batch) for batch in suite.batches(jobs, 2)])
        self.assertIsNone(suite.schedule_ready(jobs[1:], jobs[:1], {}, {}, 2))
        devices = {phone: {"serial": phone, "node_prefix": phone} for phone in "abcd"}
        with suite.DeviceLease(self.folder / "leases", devices, "ab", False, "one", prepared["resources"]):
            with self.assertRaisesRegex(suite.PrerequisiteError, "recording resource"):
                with suite.DeviceLease(self.folder / "leases", devices, "cd", False, "two", prepared["resources"]):
                    self.fail("Shared recorder leased concurrently")
        with suite.DeviceLease(self.folder / "leases", devices, "cd", False, "two", prepared["resources"]):
            pass

    def test_cleanup_freezes_original_request_and_only_actual_trace_identities_register(self):
        prepared = self.prepared()
        recorder = capture.ObservationRecorder(prepared, self.folder / "recording")
        with patch.object(capture.subprocess, "Popen", side_effect=self.launch), \
             patch.object(capture.subprocess, "run", return_value=self.probe()):
            recorder.start()
            result = recorder.stop()
        self.assertEqual("CAPTURED", result["status"])
        self.assertEqual("passthrough", result["argv"][result["argv"].index("-fps_mode") + 1])
        self.assertTrue(set(("-r", "-vf", "-ar", "-ac", "-af")).isdisjoint(result["argv"]))
        manifest = {"run_id": "actual-run", "media_trace_session": "actual-session",
            "devices": {"b": {"apk_sha256": "a" * 64}}, "logs": [{"device": "b", "pid": 7}],
            "media_traces": [{"device": "b", "role": "receiver", "state": "MEASURED", "summary": {"pid": 7, "session": "actual-session"}},
                             {"device": "b", "role": "receiver", "state": "MEASURED", "summary": {"pid": 8, "session": "stale-session"}}]}
        recorder.attach(manifest)
        self.assertEqual(1, len(manifest["measurement_captures"]))
        row = manifest["measurement_captures"][0]
        self.assertEqual(7, row["pid"])
        self.assertEqual("actual-run", row["run_id"])
        self.assertEqual(self.spec["directions"][0]["observation_plan"], row["observation_plan"])
        self.assertEqual("independent_camera_microphone", row["capture_method"])
        self.assertEqual("recording-container", row["clock"])

    def test_forced_or_wrong_native_capture_remains_interrupted_and_raw_bytes_survive(self):
        for forced in (False, True):
            recorder = capture.ObservationRecorder(self.prepared(), self.folder / str(forced))
            with patch.object(capture.subprocess, "Popen", side_effect=self.launch), \
                 patch.object(capture.subprocess, "run", return_value=self.probe(width=62)):
                recorder.start()
                if forced:
                    recorder.process.wait.side_effect = [subprocess.TimeoutExpired("mock", 5), 0]
                self.assertEqual("INTERRUPTED", recorder.stop()["status"])
            self.assertTrue((recorder.output / "recording.nut").is_file())
            manifest = {}
            recorder.attach(manifest)
            self.assertNotIn("measurement_captures", manifest)

    def test_changed_spec_blocks_before_opening_hardware_and_bad_directions_do_not_bind(self):
        prepared = self.prepared()
        self.path.write_text("changed after planning")
        with patch.object(capture.subprocess, "Popen", side_effect=AssertionError("Hardware I/O")), self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            capture.ObservationRecorder(prepared, self.folder / "changed").start()
        self.assertFalse((self.folder / "changed").exists())
        with self.assertRaisesRegex(ValueError, "executed incoming"):
            capture.validate_directions(prepared, {"source": "a", "receivers": [{"phone": "c"}]})
        prepared["spec"]["recorder"] = "b"
        with self.assertRaisesRegex(ValueError, "independent recorder"):
            capture.validate_directions(prepared, {"source": "a", "receivers": [{"phone": "b"}]})

    def test_changed_probe_is_not_executed_after_acquisition(self):
        prepared = self.prepared()
        recorder = capture.ObservationRecorder(prepared, self.folder / "changed-probe")
        with patch.object(capture.subprocess, "Popen", side_effect=self.launch), \
             patch.object(capture.subprocess, "run", side_effect=AssertionError("Changed executable must not run")):
            recorder.start()
            Path(prepared["tools"]["ffprobe"]["path"]).write_bytes(b"changed")
            self.assertEqual("INTERRUPTED", recorder.stop()["status"])
        self.assertIn("SHA256 mismatch", recorder.result["reason"])
        self.assertTrue((recorder.output / "recording.nut").is_file())

    def test_changed_stimulus_blocks_before_open_and_changed_chroma_cannot_register(self):
        stimulus = self.folder / "declared-stimulus"
        stimulus.write_bytes(b"controlled source identity")
        record = {"path": str(stimulus), "sha256": capture.digest(stimulus)}
        self.spec["directions"][0]["source_stimulus"] = {"wav": record, "events": record}
        prepared = self.prepared()
        stimulus.write_bytes(b"changed")
        with patch.object(capture.subprocess, "Popen", side_effect=AssertionError("Hardware I/O")), self.assertRaisesRegex(ValueError,"SHA256 mismatch"):
            capture.ObservationRecorder(prepared,self.folder / "changed-stimulus").start()
        self.assertFalse((self.folder / "changed-stimulus").exists())
        self.spec["directions"][0].pop("source_stimulus")
        prepared = self.prepared()
        probe = self.probe()
        data = json.loads(probe.stdout)
        data["streams"][0]["pix_fmt"] = "yuv420p"
        probe.stdout = json.dumps(data)
        recorder = capture.ObservationRecorder(prepared,self.folder / "changed-chroma")
        with patch.object(capture.subprocess,"Popen",side_effect=self.launch), patch.object(capture.subprocess,"run",return_value=probe):
            recorder.start()
            self.assertEqual("INTERRUPTED",recorder.stop()["status"])
        manifest = {}
        recorder.attach(manifest)
        self.assertNotIn("measurement_captures",manifest)

    def test_unconfirmed_termination_keeps_an_explicit_live_receipt(self):
        recorder = capture.ObservationRecorder(self.prepared(), self.folder / "still-running")
        with patch.object(capture.subprocess, "Popen", side_effect=self.launch):
            recorder.start()
            recorder.process.wait.side_effect = subprocess.TimeoutExpired("mock-recorder", 2)
            with self.assertRaises(capture.RecorderStillRunning):
                recorder.stop()
        self.assertEqual("RUNNING_UNCONFIRMED", json.loads((recorder.output / "recording.json").read_text())["status"])
        recorder.process.terminate.assert_called_once()
        recorder.process.kill.assert_called_once()


if __name__ == "__main__":
    unittest.main()
