"""Supplied observations cannot approve a different execution, input, build or missing adapter."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_measurements as measurements
import benchmark_layout as layout
from run_device_benchmark import reference_audio_metadata, media_impairment_metadata
from run_private_call_benchmark import broadcast_shape


class MeasurementsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.manifest = {"run_id": "run", "scenario": {"id": "case", "source": "a",
            "profile": {"width": 64, "height": 64, "fps": 30}, "receivers": [{"phone": "b", "parent": "a"}]},
            "devices": {"a": {"apk_sha256": "a" * 64}, "b": {"apk_sha256": "a" * 64}},
            "logs": [{"device": "b", "pid": 123}], "source_fixture": {"sha256": "b" * 64}}
        self.capture = self.save("capture.raw", {})
        self.manifest["measurement_captures"] = [{**self.capture, "origin": "DEVICE_CAPTURE", "device": "b", "source": "a", "direction": "incoming", "pid": 123, "session": "session"}]
        self.original = self.save("manifest.json", self.manifest)
        self.spec = self.save("spec.json", {"reference": {"sha256": "b" * 64, "width": 64, "height": 64, "fps": 30},
                                           "observed": self.capture})
        self.entry = {"id": "markers", "kind": "visual_markers", "spec": self.spec,
            "scope": {"run_id": "run", "device": "b", "source": "a", "pid": 123,
                      "apk_sha256": "a" * 64, "session": "session"}}

    def save(self, name, value):
        path = self.base / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def score(self, result=None):
        contract = self.save("observations.json", {"schema": "benchmark-measurements-v1", "cases": [
            {"case_id": "case", "manifest": self.original, "adapters": [self.entry]}]})
        module = types.SimpleNamespace(analyze=lambda spec, base: result or {
            "schema": "benchmark-visual-markers-v1", "measurement_status": "MEASURED", "measurements": [
                {"name": "jitter", "family": "smoothness", "status": "MEASURED", "value": 4},
                {"name": "missing_frames", "family": "smoothness", "status": "FAIL", "value": 1}]})
        with patch.object(measurements.importlib, "import_module", return_value=module):
            return measurements.analyze(contract["path"], self.original["path"], self.manifest, self.base / "analysis")

    def test_diagnostic_values_do_not_become_acceptance_and_failure_is_kept(self):
        result = self.score()
        self.assertEqual(1, len(result["measurements"]))
        self.assertEqual("FAIL", result["measurements"][0]["status"])
        self.assertTrue((self.base / "analysis/markers.json").exists())

    def test_wrong_run_build_pid_source_or_input_cannot_pass(self):
        for field, value in (("run_id", "other"), ("apk_sha256", "c" * 64), ("pid", 456), ("source", "b")):
            previous = self.entry["scope"][field]
            self.entry["scope"][field] = value
            result = self.score()
            self.assertEqual("UNMEASURED", result["measurements"][0]["status"])
            self.assertTrue(result["errors"])
            self.entry["scope"][field] = previous
        self.manifest["source_fixture"]["sha256"] = "d" * 64
        self.assertTrue(self.score()["errors"])

    def test_changed_manifest_or_spec_bytes_are_rejected(self):
        Path(self.spec["path"]).write_text("{}")
        self.assertTrue(self.score()["errors"])
        Path(self.original["path"]).write_text("{}")
        with self.assertRaises(ValueError):
            self.score()

    def test_audio_never_uses_a_reference_that_was_not_injected(self):
        self.entry["kind"] = "audio_sync"
        self.assertTrue(self.score()["errors"])

    def test_screen_family_maps_to_picture(self):
        self.entry["kind"] = "screen_geometry"
        self.manifest["source_fixture"]["declared_metadata"] = {"sidecars": {"calibration_reference": self.capture}}
        self.entry["spec"] = self.save("screen-spec.json", {"calibration": {"width": 64, "height": 64, "reference": self.capture}, "capture": self.capture})
        result = self.score({"measurement_status": "MEASURED", "measurements": [
            {"name": "stretch", "family": "screen_geometry", "status": "FAIL", "value": 1.1}]})
        self.assertEqual("picture", result["measurements"][0]["family"])

    def test_no_contract_leaves_missing_adapters_unmeasured(self):
        self.assertEqual([], measurements.analyze(None, "unused", {}, "unused")["measurements"])

    def test_wrong_trace_session_cannot_join_execution(self):
        self.manifest["media_trace_session"] = "actual-session"
        self.assertTrue(self.score()["errors"])

    def test_generated_unregistered_capture_cannot_become_a_phone_gate(self):
        self.manifest["measurement_captures"] = []
        result = self.score()
        self.assertTrue(result["errors"])
        self.assertEqual("UNMEASURED", result["measurements"][0]["status"])

    def physical_observation(self, with_audio=False):
        capture = self.manifest["measurement_captures"][0]
        capture.update(origin="EXTERNAL_RECORDING", run_id="run", apk_sha256="a" * 64,
            capture_method="independent_camera_microphone", observes=["source_target", "receiver_screen", "receiver_speaker"],
            clock="external-container")
        spec = {"schema": "benchmark-physical-av-observation-v1", "recording": self.capture,
                "planned": {"clock": "external-container"}}
        self.entry.update(kind="physical_av")
        if with_audio:
            self.manifest["reference_audio"] = {"sha256": "e" * 64}
            events = self.save("physical-events.json", {})
            self.manifest["source_fixture"]["declared_metadata"] = {"sidecars": {"events": events}}
            wav = self.save("microphone.wav", {})
            microphone = {**wav, "clock": {"id": "external-container", "sample_zero_ns": 1000},
                          "timeline": {"continuity": "CONTINUOUS", "gaps": [], "overflow_samples": 0}}
            spec["audio"] = {"reference": {"wav": {"sha256": "e" * 64}, "events": events},
                             "capture": {"wav": wav, "role": "physical_microphone",
                                         "clock": microphone["clock"], "timeline": microphone["timeline"]}}
            self.entry["extraction"] = self.save("physical-extraction.json", {"schema": "benchmark-measurement-capture-v1",
                "measurement_status": "MEASURED", "input": self.capture, "audio": microphone})
        self.entry["spec"] = self.save("physical-spec.json", spec)
        self.original = self.save("manifest.json", self.manifest)
        return spec

    def test_physical_adapter_binds_actual_optical_path_without_opening_retired_captures(self):
        self.physical_observation()
        self.manifest["measurement_captures"].append({**self.manifest["measurement_captures"][0],
            "path": str(self.base / "retired.mp4"), "sha256": "c" * 64})
        bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.assertEqual("MEASURED", bound["capture_provenance"]["physical_observation_status"])
        self.assertEqual("external-container", bound["spec"]["_capture_provenance"]["clock"])

    def test_physical_adapter_rejects_proxy_generated_wrong_path_run_and_build(self):
        self.physical_observation()
        capture = self.manifest["measurement_captures"][0]
        for field, value in (("origin", "GENERATED"), ("run_id", "other"), ("apk_sha256", "c" * 64),
                             ("capture_method", "screen_capture"), ("observes", ["decoder_output"]),
                             ("source", "b"), ("pid", 456), ("session", "other")):
            previous = capture[field]
            capture[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
            capture[field] = previous

    def test_device_screen_capture_needs_a_named_independent_observer(self):
        self.physical_observation()
        capture = self.manifest["measurement_captures"][0]
        capture["origin"] = "DEVICE_CAPTURE"
        for recorder in (None, "a", "b"):
            capture["recorder"] = recorder
            with self.subTest(recorder=recorder), self.assertRaisesRegex(ValueError, "independent observing"):
                measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        capture["recorder"] = "independent-camera-phone"
        self.assertEqual("DEVICE_CAPTURE", measurements.validate_adapter(self.entry, self.manifest,
            Path(self.original["path"]), self.base)["capture_provenance"]["origin"])

    def test_physical_audio_requires_exact_original_recording_extraction_clock_and_microphone_role(self):
        spec = self.physical_observation(with_audio=True)
        bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.assertEqual(self.entry["scope"], bound["spec"]["audio"]["capture"]["execution_scope"])
        for field, value in (("role", "accepted_pcm"), ("clock", {"id": "other", "sample_zero_ns": 1000}),
                             ("sample_clock", self.capture)):
            changed = json.loads(json.dumps(spec))
            changed["audio"]["capture"][field] = value
            self.entry["spec"] = self.save("physical-spec.json", changed)
            with self.subTest(field=field), self.assertRaises(ValueError):
                measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.entry["spec"] = self.save("physical-spec.json", spec)
        receipt = json.loads(Path(self.entry["extraction"]["path"]).read_text())
        receipt["input"] = self.save("other-recording.mp4", {})
        self.entry["extraction"] = self.save("physical-extraction.json", receipt)
        with self.assertRaisesRegex(ValueError, "same registered"):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)

    def dual_microphone_observation(self):
        spec = self.physical_observation(with_audio=True)
        capture = self.manifest["measurement_captures"][0]
        capture["observes"].append("source_speaker")
        capture["source_emission_role"] = "source_reference_preview"
        receiver = json.loads(Path(self.entry["extraction"]["path"]).read_text())
        receiver["audio"].update(stream_index=1, sample_count=96000,
            channel_selection={"native_channels": 2, "channel_index": 1, "method": "Exact native s16 samples"})
        spec["audio"]["capture"]["channel_selection"] = receiver["audio"]["channel_selection"]
        self.entry["extraction"] = self.save("physical-extraction.json", receiver)
        source = json.loads(json.dumps(receiver))
        source["audio"].update(self.save("source-microphone.wav", {"channel": 0}))
        source["audio"]["channel_selection"]["channel_index"] = 0
        spec["source_audio"] = json.loads(json.dumps(spec["audio"]))
        spec["source_audio"]["capture"].update(wav={key: source["audio"][key] for key in ("path", "sha256")},
            role="physical_source_microphone", channel_selection=source["audio"]["channel_selection"])
        self.entry["source_extraction"] = self.save("source-extraction.json", source)
        self.entry["spec"] = self.save("physical-spec.json", spec)
        return spec, source

    def test_dual_microphone_binding_requires_separate_native_channels_of_same_adc(self):
        spec, source = self.dual_microphone_observation()
        bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.assertEqual("source_reference_preview", bound["capture_provenance"]["source_emission_role"])
        for field, value in (("stream_index", 2), ("sample_count", 94000), ("channel_index", 1)):
            changed = json.loads(json.dumps(source))
            if field == "channel_index":
                changed["audio"]["channel_selection"][field] = value
            else:
                changed["audio"][field] = value
            self.entry["source_extraction"] = self.save("source-extraction.json", changed)
            with self.subTest(field=field), self.assertRaises(ValueError):
                measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.entry["source_extraction"] = self.save("source-extraction.json", source)
        self.manifest["measurement_captures"][0]["source_emission_role"] = "source_acoustic_stimulus"
        with self.assertRaisesRegex(ValueError, "bypasses microphone"):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)

    def test_live_microphone_stimulus_binds_exact_reference_and_is_not_raw_encoder_dispatch(self):
        spec, _ = self.dual_microphone_observation()
        self.manifest.pop("reference_audio")
        self.manifest["source_kind"] = "live_camera"
        capture = self.manifest["measurement_captures"][0]
        capture["source_emission_role"] = "source_acoustic_stimulus"
        capture["source_stimulus"] = {"wav": self.save("actual-stimulus.wav", {"reference": "cue bytes"}),
                                      "events": spec["audio"]["reference"]["events"]}
        for field in ("audio", "source_audio"):
            spec[field]["reference"] = capture["source_stimulus"]
        self.entry["spec"] = self.save("physical-spec.json", spec)
        bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.assertEqual("source_acoustic_stimulus", bound["capture_provenance"]["source_emission_role"])
        self.manifest["source_kind"] = "raw_fixture_live_encoder"
        with self.assertRaisesRegex(ValueError, "actual live microphone"):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)

    def test_unused_registered_capture_files_do_not_block_selected_observation(self):
        self.manifest["measurement_captures"].append({**self.manifest["measurement_captures"][0],
            "path": str(self.base / "retired.raw"), "sha256": "c" * 64})
        self.manifest["media_traces"] = [{"device": "b", "role": "receiver", "kind": "video_access_units",
            "state": "MEASURED", "summary": {"session": "session", "pid": 123},
            "files": [{"path": str(self.base / "retired.ivf"), "sha256": "d" * 64}]}]
        bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.assertEqual("DEVICE_CAPTURE", bound["capture_provenance"]["origin"])
        selected = Path(self.capture["path"])
        selected.write_text("changed selected capture", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        selected.unlink()
        with self.assertRaises(FileNotFoundError):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)

    def test_unbound_sample_times_cannot_pass_smoothness(self):
        result = self.score({"measurement_status": "MEASURED", "measurements": [
            {"name": "observed_interval_p95_periods", "family": "smoothness", "status": "PASS", "value": 1}]})
        self.assertFalse(result["errors"])
        self.assertEqual("UNMEASURED", result["measurements"][0]["status"])

    def extracted_markers(self):
        timestamps = self.base / "original-times.csv"
        timestamps.write_text("sample_index,time_ns\n0,100\n1,200\n", encoding="utf-8")
        times = {"path": str(timestamps), "sha256": hashlib.sha256(timestamps.read_bytes()).hexdigest()}
        self.manifest["measurement_captures"][0].update(clock="observed-clock", timestamps=times)
        self.original = self.save("manifest.json", self.manifest)
        observed = {**self.save("derived.raw", {}), "width": 64, "height": 64,
                    "format": "RGB24", "frame_count": 2, "stage": "screen_capture"}
        receipt = self.save("receipt.json", {"schema": "benchmark-measurement-capture-v1",
            "measurement_status": "MEASURED", "input": self.capture,
            "video": {**observed, "clock": "observed-clock", "timestamps": times}})
        (self.base / "specs").mkdir()
        self.entry["spec"] = self.save("specs/markers.json", {
            "reference": {"sha256": "b" * 64, "width": 64, "height": 64, "fps": 30},
            "observed": observed, "samples": times, "planned": {"clock": "observed-clock"}})
        self.entry["extraction"] = {**receipt, "path": "receipt.json"}
        return times

    def test_relative_extraction_receipt_resolves_beside_measurement_sidecar(self):
        self.extracted_markers()
        result = self.score({"measurement_status": "MEASURED", "measurements": [
            {"name": "observed_interval_p95_periods", "family": "smoothness", "status": "PASS", "value": 1}]})
        self.assertEqual([], result["errors"])
        self.assertEqual("PASS", result["measurements"][0]["status"])

    def relocate(self, aliases):
        for source, destination in aliases.items():
            target = self.base / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            (self.base / source).replace(target)
        (self.base / "benchmarks/catalog").mkdir(parents=True, exist_ok=True)
        self.save("benchmarks/catalog/ledger.json", {"storage": {"paths": aliases}})

    def test_relocated_extraction_uses_original_receipt_and_spec_bases(self):
        self.extracted_markers()
        receipt = json.loads((self.base / "receipt.json").read_text())
        receipt["input"]["path"] = "capture.raw"
        receipt["video"]["timestamps"]["path"] = "original-times.csv"
        self.entry["extraction"] = {**self.save("receipt.json", receipt), "path": "receipt.json"}
        spec = json.loads((self.base / "specs/markers.json").read_text())
        spec["observed"]["path"] = "../derived.raw"
        spec["samples"]["path"] = "../original-times.csv"
        self.entry["spec"] = self.save("specs/markers.json", spec)
        aliases = {"manifest.json": "benchmarks/runs/id/manifest.json",
                   "receipt.json": "benchmarks/runs/id/extraction/capture.json",
                   "specs/markers.json": "benchmarks/runs/id/measurements/spec.json",
                   "capture.raw": "benchmarks/runs/id/media/original.raw",
                   "derived.raw": "benchmarks/runs/id/media/derived.raw",
                   "original-times.csv": "benchmarks/runs/id/clock/times.csv"}
        self.relocate(aliases)
        resolve = lambda path, base=None: layout.resolve(path, base=base, root=self.base)
        original = lambda path: layout.original(path, root=self.base)
        with patch.object(measurements, "resolve_input", resolve), patch.object(measurements, "original", original):
            bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
            self.assertEqual("MEASURED", bound["capture_provenance"]["timing_status"])
            (self.base / aliases["capture.raw"]).write_text("changed extraction source")
            with self.assertRaisesRegex(ValueError, "evidence changed"):
                measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)

    def test_relocated_sample_clock_keeps_exact_executed_phase_membership(self):
        wav = self.save("received.wav", {})
        chunks = self.save("received.csv", {})
        summary = self.save("received-summary.json", {"session": "session", "pid": 123})
        self.manifest["media_traces"] = [{"device": "b", "role": "receiver", "kind": "audio_pcm",
            "state": "MEASURED", "summary": {"session": "session", "pid": 123},
            "files": [{**record, "path": Path(record["path"]).name} for record in (wav, chunks, summary)]}]
        self.manifest["reference_audio"] = {"sha256": "e" * 64}
        clock = self.save("sample-clock.json", {"evidence": {
            name: {**record, "path": Path(record["path"]).name} for name, record in (("wav", wav), ("chunks", chunks), ("summary", summary))}})
        self.entry["kind"] = "audio_sync"
        self.entry["spec"] = self.save("audio-spec.json", {"reference": {"wav": {"sha256": "e" * 64}},
            "capture": {"wav": {**wav, "path": "received.wav"}, "sample_clock": {**clock, "path": "sample-clock.json"},
                        "clock": {"id": "run:b:123:System.nanoTime"}}})
        aliases = {"manifest.json": "benchmarks/runs/id/manifest.json", "audio-spec.json": "benchmarks/runs/id/measurements/spec.json",
                   "sample-clock.json": "benchmarks/runs/id/clock/sample-clock.json",
                   "received.wav": "benchmarks/runs/id/media/received.wav", "received.csv": "benchmarks/runs/id/media/received.csv",
                   "received-summary.json": "benchmarks/runs/id/media/received-summary.json"}
        self.relocate(aliases)
        resolve = lambda path, base=None: layout.resolve(path, base=base, root=self.base)
        original = lambda path: layout.original(path, root=self.base)
        with patch.object(measurements, "resolve_input", resolve), patch.object(measurements, "original", original):
            bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
            self.assertEqual("audio_pcm", bound["capture_provenance"]["origin"])
            (self.base / aliases["received.csv"]).write_text("changed clock phase")
            with self.assertRaisesRegex(ValueError, "evidence changed"):
                measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)

    def test_extraction_checks_only_its_selected_original_capture(self):
        self.extracted_markers()
        self.manifest["measurement_captures"].append({**self.manifest["measurement_captures"][0],
            "path": str(self.base / "retired.raw"), "sha256": "c" * 64})
        bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.assertEqual("MEASURED", bound["capture_provenance"]["timing_status"])
        selected = Path(self.capture["path"])
        selected.write_text("changed extraction source", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        selected.unlink()
        with self.assertRaises(FileNotFoundError):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)

    def test_extraction_cannot_invent_original_observation_clock(self):
        self.extracted_markers()
        self.entry["extraction"]["path"] = str(self.base / "receipt.json")
        scored = {"measurement_status": "MEASURED", "measurements": [
            {"name": "observed_interval_p95_periods", "family": "smoothness", "status": "PASS", "value": 1}]}
        del self.manifest["measurement_captures"][0]["clock"]
        result = self.score(scored)
        self.assertEqual("UNMEASURED", result["measurements"][0]["status"])

    def test_extraction_timestamps_cannot_replace_registered_original_timestamps(self):
        self.extracted_markers()
        self.entry["extraction"]["path"] = str(self.base / "receipt.json")
        different = self.base / "different-times.csv"
        different.write_text("sample_index,time_ns\n0,300\n1,400\n", encoding="utf-8")
        self.manifest["measurement_captures"][0]["timestamps"] = {
            "path": str(different), "sha256": hashlib.sha256(different.read_bytes()).hexdigest()}
        result = self.score({"measurement_status": "MEASURED", "measurements": [
            {"name": "observed_interval_p95_periods", "family": "smoothness", "status": "PASS", "value": 1}]})
        self.assertEqual("UNMEASURED", result["measurements"][0]["status"])

    def test_registered_audio_cannot_invent_an_unrecorded_clock_anchor(self):
        self.entry["kind"] = "audio_sync"
        self.manifest["reference_audio"] = {"sha256": "e" * 64}
        capture = {"wav": self.capture, "clock": {"id": "invented-clock", "sample_zero_ns": 0},
                   "timeline": {"continuity": "CONTINUOUS", "gaps": [], "overflow_samples": 0}}
        self.entry["spec"] = self.save("audio-spec.json", {"reference": {"wav": {"sha256": "e" * 64}},
                                                           "capture": capture})
        self.assertTrue(self.score()["errors"])
        self.manifest["measurement_captures"][0].update(clock=capture["clock"], timeline=capture["timeline"])
        self.assertFalse(self.score()["errors"])

    def test_audio_track_clock_cannot_borrow_another_phase_chunks_or_summary(self):
        wav = self.save("received.wav", {})
        chunks = self.save("received.csv", {})
        summary = self.save("received-summary.json", {"session": "session", "pid": 123})
        self.manifest["media_traces"] = [{"device": "b", "role": "receiver", "kind": "audio_pcm",
            "state": "MEASURED", "summary": {"session": "session", "pid": 123}, "files": [wav, chunks, summary]}]
        self.manifest["reference_audio"] = {"sha256": "e" * 64}
        clock = self.save("sample-clock.json", {"evidence": {"wav": wav,
            "chunks": self.save("foreign-chunks.csv", {}), "summary": summary}})
        self.entry["kind"] = "audio_sync"
        self.entry["spec"] = self.save("audio-spec.json", {"reference": {"wav": {"sha256": "e" * 64}},
            "capture": {"wav": wav, "sample_clock": clock, "clock": {"id": "run:b:123:System.nanoTime"}}})
        self.assertTrue(self.score()["errors"])
        clock = self.save("sample-clock.json", {"evidence": {"wav": wav, "chunks": chunks, "summary": summary}})
        self.entry["spec"] = self.save("audio-spec.json", {"reference": {"wav": {"sha256": "e" * 64}},
            "capture": {"wav": wav, "sample_clock": clock, "clock": {"id": "run:b:123:System.nanoTime"}}})
        self.assertFalse(self.score()["errors"])

    def test_audio_sample_clock_needs_only_its_declared_phase_files(self):
        wav = self.save("received.wav", {})
        chunks = self.save("received.csv", {})
        summary = self.save("received-summary.json", {"session": "session", "pid": 123})
        self.manifest["media_traces"] = [{"device": "b", "role": "receiver", "kind": "audio_pcm",
            "state": "MEASURED", "summary": {"session": "session", "pid": 123},
            "files": [wav, chunks, summary, {"path": str(self.base / "retired.bin"), "sha256": "c" * 64}]}]
        self.manifest["reference_audio"] = {"sha256": "e" * 64}
        clock = self.save("sample-clock.json", {"evidence": {"wav": wav, "chunks": chunks, "summary": summary}})
        self.entry["kind"] = "audio_sync"
        self.entry["spec"] = self.save("audio-spec.json", {"reference": {"wav": {"sha256": "e" * 64}},
            "capture": {"wav": wav, "sample_clock": clock, "clock": {"id": "run:b:123:System.nanoTime"}}})
        bound = measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        self.assertEqual("audio_pcm", bound["capture_provenance"]["origin"])
        selected = Path(summary["path"])
        selected.write_text("changed clock summary", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)
        selected.unlink()
        with self.assertRaises(FileNotFoundError):
            measurements.validate_adapter(self.entry, self.manifest, Path(self.original["path"]), self.base)

    def test_extracted_audio_cannot_shift_a_registered_sample_zero_anchor(self):
        self.entry["kind"] = "audio_sync"
        self.manifest["reference_audio"] = {"sha256": "e" * 64}
        self.manifest["measurement_captures"][0]["clock"] = {"id": "recorded-clock", "sample_zero_ns": 1000}
        audio = {**self.save("derived.wav", {}), "clock": {"id": "recorded-clock", "sample_zero_ns": 2000},
                 "timeline": {"continuity": "CONTINUOUS", "gaps": [], "overflow_samples": 0}}
        self.entry["extraction"] = self.save("receipt.json", {"schema": "benchmark-measurement-capture-v1",
            "measurement_status": "MEASURED", "input": self.capture, "audio": audio})
        self.entry["spec"] = self.save("audio-spec.json", {"reference": {"wav": {"sha256": "e" * 64}},
            "capture": {"wav": audio, "clock": audio["clock"], "timeline": audio["timeline"]}})
        self.assertTrue(self.score()["errors"])

    def test_malformed_adapter_objects_are_reported_unmeasured(self):
        self.entry["scope"] = []
        self.assertEqual("UNMEASURED", self.score()["measurements"][0]["status"])
        self.entry["scope"] = {"run_id": "run", "device": "b", "source": "a", "pid": 123,
                               "apk_sha256": "a" * 64, "session": "session"}
        self.entry["spec"] = self.save("null-spec.json", None)
        self.assertEqual("UNMEASURED", self.score()["measurements"][0]["status"])

    def test_foreign_lifecycle_without_traces_cannot_be_relabelled(self):
        self.entry["kind"] = "pipeline"
        events = self.save("lifecycle.json", {"events": [{"run_id": "foreign", "phone": "b", "pid": 123}]})
        self.entry["spec"] = self.save("pipeline.json", {"traces": [], "lifecycle_events": events})
        self.assertTrue(self.score()["errors"])

    def test_pipeline_must_use_the_actual_execution_log(self):
        log = self.save("phone.log", {})
        self.manifest["media_trace_session"] = "session"
        self.manifest["logs"][0]["path"] = log["path"]
        self.original = self.save("manifest.json", self.manifest)
        trace = {**log, "format": "logcat", "run_id": "run", "phone": "b", "source": "a",
                 "session": "session", "pid": 123, "clock": "System.nanoTime", "direction": "incoming", "stream_generation": 0}
        self.entry["kind"] = "pipeline"
        self.entry["spec"] = self.save("pipeline-spec.json", {"traces": [trace]})
        self.assertFalse(self.score()["errors"])
        trace.update(self.save("unrelated.log", {}))
        self.entry["spec"] = self.save("pipeline-spec.json", {"traces": [trace]})
        self.assertTrue(self.score()["errors"])

    def test_impairment_is_explicit_hashed_and_keeps_requested_profile(self):
        config = {"schema": "benchmark-media-impairment-v1", "seed": 7, "max_wait_ms": 100,
                  "phases": [{"id": "stall", "start_ms": 0, "end_ms": 1000, "delay_ms": 40,
                              "jitter_ms": 10, "rate_bps": 1_000_000, "drop_every": 0,
                              "burst_every": 0, "burst_length": 0}]}
        record = self.save("impairment.json", config)
        case = {"media_trace": True, "media_impairment": record, "profile": {"fps": 60, "bitrate_kbps": 1100}}
        self.assertEqual(config, media_impairment_metadata(case)["config"])
        self.assertEqual({"fps": 60, "bitrate_kbps": 1100}, case["profile"])
        case["media_trace"] = False
        with self.assertRaises(ValueError):
            media_impairment_metadata(case)
        case["media_trace"] = True
        Path(record["path"]).write_text("{}")
        with self.assertRaises(ValueError):
            media_impairment_metadata(case)

    def test_call_impairment_is_separate_for_each_direction(self):
        case = {"caller": "a", "callee": "b", "transport": "classic", "profiles": {"a": {}, "b": {}},
                "media_impairments": {"a": {"path": "a.json"}, "b": {"path": "b.json"}}}
        for phone in ("a", "b"):
            self.assertEqual(phone + ".json", broadcast_shape(case, phone)["media_impairment"]["path"])

    def test_reference_audio_requires_exact_whole_sample_video_duration(self):
        import wave
        raw = self.base / "source.i420"
        raw.write_bytes(bytes(6))
        wav = self.base / "reference.wav"
        with wave.open(str(wav), "wb") as handle:
            handle.setnchannels(1); handle.setsampwidth(2); handle.setframerate(48000)
            handle.writeframes(bytes(3200))
        case = {"raw_fixture_file": str(raw), "profile": {"width": 2, "height": 2, "fps": 30}, "media_trace": True,
                "reference_audio": {"path": str(wav), "sha256": hashlib.sha256(wav.read_bytes()).hexdigest()}}
        self.assertEqual(1600, reference_audio_metadata(case)["sample_count"])
        case["profile"]["fps"] = 7
        with self.assertRaises(ValueError):
            reference_audio_metadata(case)
        case["profile"]["fps"] = 30
        case["audio_enabled"] = False
        with self.assertRaises(ValueError):
            reference_audio_metadata(case)

    def test_both_call_audio_references_stay_with_their_outgoing_profile(self):
        case = {"caller": "a", "callee": "b", "transport": "gatt", "profiles": {"a": {}, "b": {}},
                "raw_fixture_files": {"a": "a.yuv", "b": "b.yuv"},
                "reference_audios": {"a": {"path": "a.wav"}, "b": {"path": "b.wav"}}}
        for phone in ("a", "b"):
            shaped = broadcast_shape(case, phone)
            self.assertEqual(phone + ".wav", shaped["reference_audio"]["path"])


if __name__ == "__main__":
    unittest.main()
