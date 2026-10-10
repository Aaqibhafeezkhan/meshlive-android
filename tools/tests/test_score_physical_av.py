"""Known-delay generated observations test math, never physical phone acceptance."""
from copy import deepcopy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import wave

import numpy as np

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import score_physical_av as physical
import extract_measurement_capture as extraction


class PhysicalAvTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
            raise unittest.SkipTest("Real FFmpeg/ffprobe required for generated capture math")
        cls.temp = tempfile.TemporaryDirectory(prefix="physical-av-host-")
        cls.base = Path(cls.temp.name)
        cls.addClassCleanup(cls.temp.cleanup)
        frames = np.zeros((60, 24, 64, 3), dtype=np.uint8)
        for start in (10, 30):
            frames[start:start + 3, :24, :24] = 255
            frames[start + 2:start + 5, :24, 40:64] = 255
        raw = cls.base / "camera.rgb"
        raw.write_bytes(frames.tobytes())
        reference, microphone = np.zeros(96000, dtype="<i2"), np.zeros(115200, dtype="<i2")
        cues = []
        for index, start in enumerate((19200, 57600)):
            template = (np.random.default_rng(index + 17).normal(size=480) * 2500).astype("<i2")
            reference[start:start + 480] = template
            microphone[start + 5760:start + 6240] = template
            cues.append({"id": f"cue-{index}", "kind": "noise", "sample_index": start,
                         "sample_count": 480, "file_frame": start // 1920})
        cls.write_wav("reference.wav", reference)
        cls.write_wav("microphone.wav", microphone)
        video = cls.base / "camera.mkv"
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-f", "rawvideo", "-pixel_format", "rgb24",
            "-video_size", "64x24", "-framerate", "25", "-i", str(raw), "-i", str(cls.base / "microphone.wav"),
            "-c:v", "ffv1", "-c:a", "pcm_s16le", str(video)], check=True, capture_output=True)
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-f", "rawvideo", "-pixel_format", "rgb24",
            "-video_size", "64x24", "-framerate", "25", "-i", str(raw), "-vf",
            "setpts='if(lt(N,20),N*0.04/TB,(0.8+(N-20)*0.06)/TB)'", "-fps_mode", "passthrough",
            "-c:v", "ffv1", str(cls.base / "variable-camera.mkv")], check=True, capture_output=True)
        missing_receiver = frames.copy()
        missing_receiver[:, :, 40:] = 0
        missing_raw = cls.base / "missing-receiver.rgb"
        missing_raw.write_bytes(missing_receiver.tobytes())
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-f", "rawvideo", "-pixel_format", "rgb24",
            "-video_size", "64x24", "-framerate", "25", "-i", str(missing_raw),
            "-c:v", "ffv1", str(cls.base / "missing-receiver.mkv")], check=True, capture_output=True)
        cls.write_json("events.json", {"schema": "vsg-events-v1", "audio_schedule": {"schema": "vsg-audio-schedule-v1",
            "enabled": True, "sample_rate": 48000, "channels": 1, "sample_count": 96000, "events": cues}})
        cls.spec = {"schema": "benchmark-physical-av-observation-v1", "recording": cls.pointer("camera.mkv"),
            "source_roi": [0, 0, 24, 24], "receiver_roi": [40, 0, 24, 24],
            "delay_search": {"minimum_ms": 0, "maximum_ms": 200},
            "detector": {"source_threshold": 128, "receiver_threshold": 128, "minimum_contrast": 20},
            "planned": {"clock": "original-camera-container", "start_ns": 0, "end_ns": 1600000000,
                "minimum_events": 4, "expected_source_events": 4, "max_frame_gap_ns": 50000000},
            "audio": {"schema": "benchmark-audio-observation-v1", "reference": {"wav": cls.pointer("reference.wav"),
                "events": cls.pointer("events.json")}, "capture": {"wav": cls.pointer("microphone.wav"),
                "role": "physical_microphone", "clock": {"id": "original-camera-container", "sample_zero_ns": 0},
                "timeline": {"continuity": "CONTINUOUS", "gaps": [], "overflow_samples": 0}},
                "plans": [{"stream_generation": "raw-1", "loop_iteration": 0, "source_start_sample": 0,
                    "source_end_sample": 96000, "capture_start_sample": 5760, "search_radius_samples": 1000}]},
            "cue_edges": [{"stream_generation": "raw-1", "loop_iteration": 0, "id": f"cue-{index}",
                           "source_event_index": index * 2} for index in range(2)]}
        source_samples = np.zeros(115200, dtype="<i2")
        source_samples[:len(reference)] = reference
        with wave.open(str(cls.base / "stereo.wav"), "wb") as output:
            output.setparams((2, 2, 48000, 0, "NONE", "not compressed"))
            output.writeframes(np.column_stack((source_samples, microphone)).astype("<i2").tobytes())
        stereo = cls.base / "stereo-camera.nut"
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-f", "rawvideo", "-pixel_format", "rgb24",
            "-video_size", "64x24", "-framerate", "25", "-i", str(raw), "-i", str(cls.base / "stereo.wav"),
            "-c:v", "ffv1", "-c:a", "pcm_s16le", "-f", "nut", str(stereo)], check=True, capture_output=True)
        extract_spec = {"schema": "benchmark-capture-extract-v1", "input": cls.pointer("stereo-camera.nut"),
            "clock": "original-camera-container", "video": {"stream_index": 0, "width": 64, "height": 24,
                "rgb_conversion": "ffmpeg_native_rgb24", "allow_unspecified_color": True,
                "source_color": {name: "UNSPECIFIED" for name in ("range", "matrix", "primaries", "transfer")}},
            "audio": {"stream_index": 1, "channels": 2, "channel_index": 0, "sample_rate": 48000, "sample_format": "s16"}}
        cls.extracted_source = extraction.extract(extract_spec, cls.base, cls.base / "source-extraction")
        extract_spec["audio"]["channel_index"] = 1
        cls.extracted_receiver = extraction.extract(extract_spec, cls.base, cls.base / "receiver-extraction")

    @classmethod
    def pointer(cls, name):
        path = cls.base / name
        return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    @classmethod
    def write_json(cls, name, value):
        (cls.base / name).write_text(json.dumps(value), encoding="utf-8")
        return cls.pointer(name)

    @classmethod
    def write_wav(cls, name, values):
        with wave.open(str(cls.base / name), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(48000)
            output.writeframes(values.astype("<i2").tobytes())
        return cls.pointer(name)

    def configured(self):
        spec = deepcopy(self.spec)
        spec["_capture_provenance"] = {"physical_observation_status": "MEASURED",
            "observation_plan": physical.controls(spec), "conditions": {"exposure_ns": 2000000,
                "rolling_shutter_ns": 0, "av_clock_validation": "measured"}}
        limits = {"optical_delay_max_ms": 130, "optical_delay_p95_ms": 130, "optical_missing_events": 0,
            "optical_ambiguous_events": 0, "audio_missing_cues": 0, "audio_ambiguous_cues": 0,
            "audio_clipped_samples": 0, "physical_av_offset_abs_ms": 90}
        value = {"schema": "benchmark-physical-av-validation-v1", "status": "VALIDATED",
            "detector_controls": physical.controls(spec), "instrument_uncertainty_ns": 1000000,
            "av_clock_uncertainty_ns": 1000000, "conditions": spec["_capture_provenance"]["conditions"],
            "validated_measurements": list(limits), "provenance": {"origin": "EXTERNAL_RECORDING",
                "captures": [self.pointer("camera.mkv")]}}
        spec["acceptance"] = {"validation": self.write_json("validation.json", value), "limits": limits}
        return spec, value

    def dual_spec(self):
        spec = deepcopy(self.spec)
        spec["recording"] = self.pointer("stereo-camera.nut")
        spec["source_audio"] = deepcopy(spec["audio"])
        spec["source_audio"]["plans"][0]["capture_start_sample"] = 0
        for field, extracted, role in (("audio", self.extracted_receiver, "physical_microphone"),
                ("source_audio", self.extracted_source, "physical_source_microphone")):
            record = extracted["audio"]
            spec[field]["capture"].update(wav={key: record[key] for key in ("path", "sha256")},
                clock=record["clock"], timeline=record["timeline"], channel_selection=record["channel_selection"], role=role)
        return spec

    def test_real_ffmpeg_known_optical_and_microphone_delay_remain_host_diagnostics(self):
        result = physical.analyze(deepcopy(self.spec), self.base)
        self.assertTrue(result["optical"]["timeline_complete"])
        self.assertEqual({"MATCHED": 4}, result["optical"]["counts"])
        self.assertEqual(80, round(result["optical"]["delay_ms"]["p95"]))
        self.assertEqual([40, 40], [round(row["audio_minus_video_ms"]) for row in result["av_events"]])
        self.assertEqual(2, len(result["audio"]["aligned_cues"]))
        self.assertTrue(all(row["status"] == "UNMEASURED" for row in result["measurements"]))

    def test_sampling_bounds_and_uncertainty_control_gate_not_point_estimate(self):
        spec, _ = self.configured()
        spec["acceptance"]["limits"]["physical_av_offset_abs_ms"] = 60
        result = physical.analyze(spec, self.base)
        gates = {row["name"]: row for row in result["measurements"]}
        self.assertEqual("PASS", gates["optical_delay_max_ms"]["status"])
        self.assertEqual("FAIL", gates["physical_av_offset_abs_ms"]["status"])
        self.assertGreater(gates["physical_av_offset_abs_ms"]["value"], 60)
        self.assertEqual("UNMEASURED", gates["acoustic_quality"]["status"])

    def test_variable_container_pts_are_used_instead_of_nominal_camera_fps(self):
        spec = deepcopy(self.spec)
        spec.pop("audio")
        spec.pop("cue_edges")
        spec["recording"] = self.pointer("variable-camera.mkv")
        spec["planned"].update(end_ns=2100000000, max_frame_gap_ns=100000000)
        result = physical.analyze(spec, self.base)
        delays = [round(row["delay_ms"]) for row in result["optical"]["pairing"]["matched"]]
        self.assertEqual([80, 80, 120, 120], delays)

    def test_actual_av_offset_change_retains_elapsed_recording_clock(self):
        values = np.zeros(115200, dtype="<i2")
        with wave.open(str(self.base / "reference.wav"), "rb") as source:
            reference = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
        values[24960:25440] = reference[19200:19680]
        values[65280:65760] = reference[57600:58080]
        spec = deepcopy(self.spec)
        spec["audio"]["capture"]["wav"] = self.write_wav("drift.wav", values)
        spec["audio"]["plans"][0]["search_radius_samples"] = 3000
        result = physical.analyze(spec, self.base)
        change = result["av_offset_change"]
        self.assertEqual("MEASURED", change["measurement_status"])
        self.assertAlmostEqual(40, change["change_ms"])
        self.assertAlmostEqual(.84, change["elapsed_capture_seconds"])

    def test_real_same_adc_stereo_extraction_measures_actual_source_to_speaker_events(self):
        spec = self.dual_spec()
        result = physical.analyze(spec, self.base)
        self.assertEqual([120, 120], [round(row["delay_ms"]) for row in result["acoustic_path_events"]])
        self.assertEqual("UNMEASURED", result["scope"]["encoder_dispatch_to_speaker_latency"])
        self.assertTrue(all(row["status"] != "PASS" for row in result["measurements"]))

    def test_dual_microphone_preview_does_not_inherit_a_live_source_path_pass(self):
        spec = self.dual_spec()
        conditions = {"exposure_ns": 2000000, "rolling_shutter_ns": 0, "av_clock_validation": "measured",
            "source_microphone_distance_m": .1, "receiver_microphone_distance_m": .1}
        spec["_capture_provenance"] = {"physical_observation_status": "MEASURED", "conditions": conditions,
            "source_emission_role": "source_reference_preview", "observation_plan": physical.controls(spec)}
        limits = {"observed_acoustic_path_max_ms": 150, "observed_acoustic_negative_events": 0}
        document = {"schema": "benchmark-physical-av-validation-v1", "status": "VALIDATED", "conditions": conditions,
            "detector_controls": physical.controls(spec), "instrument_uncertainty_ns": 1000000, "av_clock_uncertainty_ns": 1000000,
            "cross_channel_uncertainty_ns": 1000000, "room_path_uncertainty_ns": 1000000,
            "validated_measurements": list(limits), "provenance": {"origin": "EXTERNAL_RECORDING",
                "captures": [self.pointer("stereo-camera.nut")]}}
        spec["acceptance"] = {"validation": self.write_json("dual-validation.json", document), "limits": limits}
        result = physical.analyze(spec, self.base)
        paths = [row for row in result["measurements"] if row["name"].startswith("observed_acoustic_")]
        self.assertTrue(all(row["status"] == "UNMEASURED" for row in paths))
        spec["_capture_provenance"]["source_emission_role"] = "source_acoustic_stimulus"
        result = physical.analyze(spec, self.base)
        paths = [row for row in result["measurements"] if row["name"].startswith("observed_acoustic_")]
        self.assertTrue(all(row["status"] == "PASS" for row in paths))

    def test_generated_validation_or_changed_controls_never_validate_instrument(self):
        for change in ("generated", "roi"):
            spec, value = self.configured()
            if change == "generated":
                value["provenance"]["origin"] = "GENERATED"
            else:
                value["detector_controls"]["receiver_roi"] = [39, 0, 24, 24]
            spec["acceptance"]["validation"] = self.write_json("validation.json", value)
            with self.subTest(change=change), self.assertRaises(ValueError):
                physical.analyze(spec, self.base)

    def test_recording_prefix_tail_and_clock_gap_cannot_pass_good_matched_subset(self):
        for field, value in (("start_ns", -1000000), ("end_ns", 2400000000), ("max_frame_gap_ns", 10000000)):
            spec, validation = self.configured()
            spec["planned"][field] = value
            spec["_capture_provenance"]["observation_plan"] = physical.controls(spec)
            validation["detector_controls"] = physical.controls(spec)
            spec["acceptance"]["validation"] = self.write_json("validation.json", validation)
            with self.subTest(field=field):
                result = physical.analyze(spec, self.base)
                self.assertFalse(result["optical"]["timeline_complete"])
                self.assertTrue(all(row["status"] != "PASS" for row in result["measurements"]))

    def test_different_or_sparse_audio_clock_keeps_av_unmeasured(self):
        for change in ("clock", "gap"):
            spec = deepcopy(self.spec)
            if change == "clock":
                spec["audio"]["capture"]["clock"]["id"] = "another-camera"
            else:
                spec["audio"]["capture"]["timeline"]["continuity"] = "SPARSE"
            result = physical.analyze(spec, self.base)
            self.assertTrue(all(row["status"] == "UNMEASURED" for row in result["av_events"]))

    def test_repeated_optical_candidates_stay_ambiguous(self):
        spec = deepcopy(self.spec)
        spec["delay_search"]["maximum_ms"] = 1200
        result = physical.analyze(spec, self.base)
        self.assertGreater(result["optical"]["counts"].get("AMBIGUOUS", 0), 0)
        self.assertTrue(any(row["status"] == "UNMEASURED" for row in result["av_events"]))

    def test_no_received_screen_events_is_visible_missing_evidence_without_fabricated_delay(self):
        spec, _ = self.configured()
        spec["recording"] = self.pointer("missing-receiver.mkv")
        result = physical.analyze(spec, self.base)
        self.assertEqual({"MISSING": 4}, result["optical"]["counts"])
        gates = {row["name"]: row for row in result["measurements"]}
        self.assertEqual("FAIL", gates["optical_missing_events"]["status"])
        self.assertEqual("UNMEASURED", gates["optical_delay_max_ms"]["status"])

    def test_missing_and_clipped_microphone_cues_are_visible(self):
        for mode in ("missing", "clipped"):
            values = np.zeros(115200, dtype="<i2")
            if mode == "clipped":
                values[24960:25440] = 32767
            spec = deepcopy(self.spec)
            spec["audio"]["capture"]["wav"] = self.write_wav(mode + ".wav", values)
            result = physical.analyze(spec, self.base)
            self.assertGreater(result["audio"]["metrics"]["cue_counts"].get("MISSING", 0), 0)
            if mode == "clipped":
                self.assertEqual(480, result["audio"]["metrics"]["capture_pcm"]["clipped_samples"])
            self.assertTrue(all(row["status"] == "UNMEASURED" for row in result["av_events"]))

    def test_accepted_pcm_or_overlapping_rois_cannot_become_physical_evidence(self):
        for mode in ("track", "overlap"):
            spec = deepcopy(self.spec)
            if mode == "track":
                spec["audio"]["capture"]["sample_clock"] = self.pointer("events.json")
            else:
                spec["receiver_roi"] = spec["source_roi"]
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                physical.analyze(spec, self.base)

    def test_standalone_cli_drops_forged_execution_binding_and_preserves_existing_output(self):
        spec, _ = self.configured()
        pointer = self.write_json("standalone-spec.json", spec)
        destination = self.base / "standalone-result.json"
        with redirect_stdout(io.StringIO()):
            physical.main(["--spec", pointer["path"], "--output", str(destination)])
        result = json.loads(destination.read_text())
        self.assertTrue(all(row["status"] != "PASS" for row in result["measurements"]))
        saved = destination.read_bytes()
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            physical.main(["--spec", "missing-spec.json", "--output", str(destination)])
        self.assertEqual(saved, destination.read_bytes())

    def test_bad_microphone_container_is_a_scoped_observation_error(self):
        pointer = self.write_json("not-pcm.wav", {"fake": "container"})
        spec = deepcopy(self.spec)
        spec["audio"]["capture"]["wav"] = pointer
        with self.assertRaisesRegex(ValueError, "Invalid microphone PCM"):
            physical.analyze(spec, self.base)


if __name__ == "__main__":
    unittest.main()
