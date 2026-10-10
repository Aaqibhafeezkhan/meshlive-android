"""Exact native input binding and provenance; tiny byte fixtures never operate/render phones."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import wave
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bind_benchmark_inputs as binding
from benchmark_case_state import request_key
from run_benchmark_suite import load_jobs

COLOR = {"range": "limited", "primaries": "bt709", "matrix": "bt709",
         "transfer": "bt709", "chroma_location": "left"}


def profile(width=2, height=2, fps=10):
    return {"width": width, "height": height, "fps": fps, "bitrate_kbps": 100,
            "codec": "encoder", "rate_control": "cbr", "playback": "balanced", "thermal_reduction": False}


def broadcast():
    return {"id": "camera-source", "state": "NOT RUN", "source": "s24", "transport": "classic",
            "profile": profile(), "receivers": [{"phone": "s22", "parent": "s24"}], "duration_s": 90}


class BindBenchmarkInputsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name).resolve()
        self.devices = {p: {"serial": "offline-" + p, "node_prefix": f"{i + 1:012x}"}
                        for i, p in enumerate(("s24", "s22", "m31"))}
        self.registry = self.folder / "registry.json"
        self.registry.write_text(json.dumps({"devices": self.devices}), encoding="utf-8")

    def fixture(self, width=2, height=2, fps=10, suffix="", data=None):
        path = self.folder / f"native-{width}x{height}-{fps}{suffix}.yuv"
        if data is None:
            data = bytes(i % 251 for i in range(width * height * 3))  # Two tiny raw frame byte layouts.
        path.write_bytes(data)
        return {"width": width, "height": height, "fps": fps, "format": "I420", "bit_depth": 8,
                "path": path.name, "sha256": hashlib.sha256(data).hexdigest(), "frame_count": 2,
                "color": copy.deepcopy(COLOR)}

    def source(self, cases):
        suite = self.folder / "source-suite.json"
        suite.write_text(json.dumps({"schema_version": 1, "cases": cases}), encoding="utf-8")
        return suite

    def run_binding(self, cases, fixtures, name="new"):
        suite = self.source(cases)
        manifest = self.folder / "fixtures.json"
        manifest.write_text(json.dumps({"schema_version": 1, "fixtures": fixtures}), encoding="utf-8")
        arguments = ["--suite", str(suite), "--fixtures", str(manifest), "--devices", str(self.registry),
                     "--output", str(self.folder / name)]
        for case in cases:
            arguments.extend(("--case", case["id"]))
        with patch("subprocess.run", side_effect=AssertionError("Device/media process forbidden")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, binding.main(arguments))
        return self.folder / name

    def test_native_broadcast_preserves_original_and_uses_new_deterministic_exact_identity(self):
        original = broadcast()
        fixture = self.fixture()
        first = self.run_binding([original], [fixture], "first")
        second = self.run_binding([original], [fixture], "second")
        a = binding.read(first / "raw-suite.json")["cases"][0]
        b = binding.read(second / "raw-suite.json")["cases"][0]
        self.assertEqual(a["id"], b["id"])
        self.assertEqual("raw-" + request_key(a)[:24], a["id"])
        self.assertNotEqual(original["id"], a["id"])
        self.assertEqual(original, binding.read(self.folder / "source-suite.json")["cases"][0])
        self.assertEqual(original["profile"], a["profile"])
        self.assertEqual("NOT RUN", a["state"])
        self.assertEqual(fixture["sha256"], a["raw_fixture_sha256"])
        self.assertEqual(COLOR, a["raw_fixture_metadata"]["color"])
        self.assertEqual(str((self.folder / fixture["path"]).resolve()), a["raw_fixture_file"])
        report = binding.read(first / "binding-manifest.json")
        self.assertEqual({"UNMEASURED"}, set(report["adapters"].values()))
        self.assertEqual(0, report["measured_executions"])
        self.assertEqual(0, report["passes"])
        self.assertEqual((self.folder / "fixtures.json").read_bytes(), (first / "source-fixtures.json").read_bytes())

    def test_asymmetric_call_binds_each_outgoing_profile_to_its_native_input(self):
        case = {"id": "camera-call", "caller": "s24", "callee": "s22", "transport": "gatt",
                "profiles": {"s24": profile(), "s22": profile(4, 2, 20)}, "setup_action": "accept", "duration_s": 90}
        fixtures = [self.fixture(), self.fixture(4, 2, 20)]
        result = self.run_binding([case], fixtures)
        bound = binding.read(result / "raw-suite.json")["cases"][0]
        self.assertEqual(case["profiles"], bound["profiles"])
        for phone, fixture in zip(("s24", "s22"), fixtures):
            self.assertEqual(fixture["sha256"], bound["raw_fixture_sha256s"][phone])
            self.assertEqual(fixture["fps"], bound["raw_fixture_metadatas"][phone]["fps"])
            self.assertEqual(str((self.folder / fixture["path"]).resolve()), bound["raw_fixture_files"][phone])
        with patch("subprocess.run", side_effect=AssertionError("Device I/O forbidden")):
            jobs = load_jobs(result / "benchmark-bundle.json", self.devices)
        self.assertEqual(1, len(jobs))
        self.assertEqual("private_call", jobs[0]["mode"])

    def test_chain_binds_only_original_source_input_without_changing_requested_parents(self):
        case = broadcast()
        case["receivers"].append({"phone": "m31", "parent": "s22"})
        result = self.run_binding([case], [self.fixture()])
        bound = binding.read(result / "raw-suite.json")["cases"][0]
        self.assertEqual(case["receivers"], bound["receivers"])
        self.assertEqual({"s24"}, set(binding.read(result / "binding-manifest.json")["bindings"][0]["source_inputs"]))

    def test_missing_native_dimension_and_fps_are_rejected_without_output_or_substitution(self):
        for name, fixture in (("dimension", self.fixture(4, 2)), ("fps", self.fixture(fps=20))):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "missing native"):
                self.run_binding([broadcast()], [fixture], name)
            self.assertFalse((self.folder / name).exists())

    def test_tampered_expected_hash_is_rejected(self):
        fixture = self.fixture()
        fixture["sha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            self.run_binding([broadcast()], [fixture])
        self.assertFalse((self.folder / "new").exists())

    def test_empty_partial_and_incorrect_declared_frame_count_are_rejected(self):
        for name, data in (("empty", b""), ("partial", b"1234567"), ("count", b"123456")):
            fixture = self.fixture(suffix=name, data=data)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "whole I420 frame count"):
                self.run_binding([broadcast()], [fixture], name)
            self.assertFalse((self.folder / name).exists())

    def test_undeclared_color_fractional_fps_and_unsupported_layout_do_not_gain_defaults(self):
        for name, change, reason in (("color", {"color": {}}, "Explicit color"),
                                    ("fps", {"fps": 29.97}, "positive integers"),
                                    ("depth", {"bit_depth": 10}, "8-bit I420"),
                                    ("layout", {"format": "yuv422p"}, "8-bit I420")):
            fixture = {**self.fixture(), **change}
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, reason):
                self.run_binding([broadcast()], [fixture], name)

    def test_sidecar_paths_hashes_and_original_manifest_are_preserved_without_measurement_claim(self):
        fixture = self.fixture()
        sidecar = self.folder / "frames.csv"
        sidecar.write_bytes(b"frame,time_num,time_den\n0,0,10\n1,1,10\n")
        fixture["sidecars"] = {"frames": {"path": sidecar.name, "sha256": hashlib.sha256(sidecar.read_bytes()).hexdigest()}}
        result = self.run_binding([broadcast()], [fixture])
        bound = binding.read(result / "raw-suite.json")["cases"][0]
        preserved = bound["raw_fixture_metadata"]["sidecars"]["frames"]
        self.assertEqual(str(sidecar), preserved["path"])
        self.assertEqual(fixture["sidecars"]["frames"]["sha256"], preserved["sha256"])
        fixture["sidecars"]["frames"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            self.run_binding([broadcast()], [fixture], "tampered-sidecar")

    def test_changing_raw_bytes_or_absolute_location_creates_a_new_exact_request(self):
        fixture = self.fixture()
        first = self.run_binding([broadcast()], [fixture], "first")
        moved = self.fixture(suffix="-moved")
        second = self.run_binding([broadcast()], [moved], "second")
        changed = self.fixture(suffix="-changed", data=bytes([1] * 12))
        third = self.run_binding([broadcast()], [changed], "third")
        identities = {binding.read(p / "raw-suite.json")["cases"][0]["id"] for p in (first, second, third)}
        self.assertEqual(3, len(identities))

    def test_camera_flip_replay_and_existing_raw_cases_are_explicitly_rejected(self):
        fixture = self.fixture()
        flip = broadcast()
        flip["actions"] = [{"at_s": 20, "phone": "s24", "action": "flip"}]
        with self.assertRaisesRegex(ValueError, "physical camera flip"):
            self.run_binding([flip], [fixture], "flip")
        call_flip = {"id": "camera-call-flip", "caller": "s24", "callee": "s22", "transport": "gatt",
            "profiles": {"s24": profile(), "s22": profile()}, "setup_action": "accept", "duration_s": 90,
            "actions": [{"at_s": 20, "phone": "s22", "action": "flip"}]}
        with self.assertRaisesRegex(ValueError, "physical camera flip"):
            self.run_binding([call_flip], [fixture], "call-flip")
        raw = broadcast()
        raw["raw_fixture_file"] = str(self.folder / fixture["path"])
        with self.assertRaisesRegex(ValueError, "not replaced"):
            self.run_binding([raw], [fixture], "existing")
        job = {"case": {**broadcast(), "replay_file": "retained.ivf"}}
        with self.assertRaisesRegex(ValueError, "not replaced"):
            binding.bind_jobs([job], {}, self.folder, self.devices)

    def test_only_explicit_selected_cases_are_bound_and_existing_outputs_are_untouched(self):
        fixture = self.fixture()
        selected, other = broadcast(), broadcast()
        other["id"] = "another-camera-source"
        other["profile"] = profile(4, 2, 20)
        suite = self.source([selected, other])
        manifest = self.folder / "fixtures.json"
        manifest.write_text(json.dumps({"schema_version": 1, "fixtures": [fixture]}), encoding="utf-8")
        output = self.folder / "selected"
        arguments = ["--suite", str(suite), "--case", selected["id"], "--fixtures", str(manifest),
                     "--devices", str(self.registry), "--output", str(output)]
        with patch("subprocess.run", side_effect=AssertionError("Device I/O forbidden")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, binding.main(arguments))
            with self.assertRaises(FileExistsError):
                binding.main(arguments)
        self.assertEqual(1, len(binding.read(output / "raw-suite.json")["cases"]))
        self.assertEqual([selected, other], binding.read(suite)["cases"])

    def test_reference_audio_is_an_explicit_separate_request_with_exact_timeline(self):
        source = broadcast()
        fixture = self.fixture()
        wav = self.folder / "reference.wav"
        with wave.open(str(wav), "wb") as handle:
            handle.setnchannels(1); handle.setsampwidth(2); handle.setframerate(48000)
            handle.writeframes(bytes(9600 * 2))
        fixture["sidecars"] = {"reference_audio": {"path": wav.name, "sha256": hashlib.sha256(wav.read_bytes()).hexdigest()}}
        jobs = load_jobs(self.source([source]), self.devices)
        index = binding.fixture_index({"schema_version": 1, "fixtures": [fixture]})
        video, _, _ = binding.bind_jobs(jobs, index, self.folder, self.devices)
        av, _, _ = binding.bind_jobs(jobs, index, self.folder, self.devices, with_reference_audio=True)
        self.assertNotEqual(video[0]["id"], av[0]["id"])
        self.assertTrue(av[0]["media_trace"])
        self.assertEqual(source["profile"], av[0]["profile"])
        self.assertEqual(fixture["sidecars"]["reference_audio"]["sha256"], av[0]["reference_audio"]["sha256"])
        self.assertNotIn("reference_audio", video[0])

    def test_requested_audio_binding_never_falls_back_to_microphone_or_changes_disabled_audio(self):
        source = broadcast()
        fixture = self.fixture()
        jobs = load_jobs(self.source([source]), self.devices)
        index = binding.fixture_index({"schema_version": 1, "fixtures": [fixture]})
        with self.assertRaisesRegex(ValueError, "reference audio is missing"):
            binding.bind_jobs(jobs, index, self.folder, self.devices, with_reference_audio=True)


if __name__ == "__main__":
    unittest.main()
