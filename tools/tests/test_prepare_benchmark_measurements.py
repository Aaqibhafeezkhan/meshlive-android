"""Offline observation binding must preserve evidence, explicit scopes and failures."""

from contextlib import redirect_stderr, redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_measurements as measurements
import benchmark_layout as layout
import prepare_benchmark_measurements as prepare


class PrepareMeasurementsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="measurement-bindings-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.capture = self.write("received.raw", {})
        self.manifest = {"run_id": "actual-run", "media_trace_session": "actual-session",
            "scenario": {"id": "exact-case", "source": "a", "profile": {"width": 64, "height": 64, "fps": 30}},
            "devices": {"a": {"apk_sha256": "a" * 64}, "b": {"apk_sha256": "a" * 64}},
            "logs": [{"device": "b", "pid": 123}], "source_fixture": {"sha256": "b" * 64},
            "measurement_captures": [{**self.capture, "origin": "DEVICE_CAPTURE", "device": "b", "source": "a",
                "direction": "incoming", "pid": 123, "session": "actual-session"}]}
        self.manifest_path = Path(self.write("manifest.json", self.manifest)["path"])
        self.spec = self.write("specs/markers.json", {"reference": {"sha256": "b" * 64, "width": 64, "height": 64, "fps": 30},
                                                  "observed": self.capture})
        self.entry = {"id": "markers", "kind": "visual_markers", "spec": "specs/markers.json",
                      "scope": {"device": "b", "source": "a", "pid": 123, "session": "actual-session"}}

    def write(self, name, value):
        path = self.folder / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")
        return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def run_prepare(self, entries=None, name="prepared"):
        bindings = self.write("bindings.json", {"schema": "benchmark-measurement-bindings-v1",
                                              "adapters": entries if entries is not None else [self.entry]})
        return prepare.prepare(self.manifest_path, bindings["path"], self.folder / name)

    def test_hashes_and_execution_identity_are_prepared_without_mutating_inputs(self):
        originals = {path: path.read_bytes() for path in (self.manifest_path, Path(self.spec["path"]))}
        result = self.run_prepare()
        self.assertEqual("READY", result["binding_status"])
        self.assertEqual("UNMEASURED", result["measurement_status"])
        self.assertEqual("UNMEASURED", result["adapters"][0]["capture_provenance"]["timing_status"])
        sidecar = json.loads(Path(result["measurements"]["path"]).read_text())
        case = sidecar["cases"][0]
        self.assertEqual("exact-case", case["case_id"])
        self.assertEqual(self.spec["sha256"], case["adapters"][0]["spec"]["sha256"])
        self.assertEqual(Path(self.spec["path"]).resolve(), Path(case["adapters"][0]["spec"]["path"]))
        self.assertEqual("actual-run", case["adapters"][0]["scope"]["run_id"])
        self.assertEqual("a" * 64, case["adapters"][0]["scope"]["apk_sha256"])
        module = types.SimpleNamespace(analyze=lambda spec, base: {"measurement_status": "MEASURED", "measurements": []})
        with patch.object(measurements.importlib, "import_module", return_value=module):
            scored = measurements.analyze(result["measurements"]["path"], self.manifest_path,
                                          self.manifest, self.folder / "analysis")
        self.assertEqual([], scored["errors"])
        for path, data in originals.items():
            self.assertEqual(data, path.read_bytes())

    def test_relocated_manifest_bindings_spec_and_capture_keep_original_relative_bases(self):
        aliases = {"artifacts/old/run/manifest.json": "benchmarks/runs/run-id/manifest.json",
                   "artifacts/old/run/received.raw": "benchmarks/runs/run-id/media/received.raw",
                   "artifacts/old/bindings.json": "benchmarks/runs/run-id/bindings.json",
                   "artifacts/old/specs/markers.json": "benchmarks/runs/run-id/measurements/spec.json"}
        capture = {**self.capture, "path": "received.raw"}
        self.manifest["measurement_captures"][0].update(capture)
        observed = {**capture, "path": "../run/received.raw"}
        spec = {"reference": {"sha256": "b" * 64, "width": 64, "height": 64, "fps": 30},
                "observed": observed}
        records = {aliases["artifacts/old/run/manifest.json"]: self.manifest,
                   aliases["artifacts/old/run/received.raw"]: {},
                   aliases["artifacts/old/bindings.json"]: {"schema": "benchmark-measurement-bindings-v1", "adapters": [self.entry]},
                   aliases["artifacts/old/specs/markers.json"]: spec}
        for name, value in records.items():
            self.write(name, value)
        self.write("benchmarks/catalog/ledger.json", {"storage": {"paths": aliases}})
        before = {self.folder / name: (self.folder / name).read_bytes() for name in records}
        resolve = lambda path, base=None: layout.resolve(path, base=base, root=self.folder)
        original = lambda path: layout.original(path, root=self.folder)
        manifest_path = self.folder / aliases["artifacts/old/run/manifest.json"]
        with patch.object(prepare, "resolve_input", resolve, create=True), patch.object(prepare, "original", original, create=True), \
                patch.object(measurements, "resolve_input", resolve, create=True), patch.object(measurements, "original", original, create=True):
            result = prepare.prepare(manifest_path, self.folder / aliases["artifacts/old/bindings.json"], self.folder / "prepared")
            self.assertEqual("READY", result["binding_status"])
            sidecar = json.loads(Path(result["measurements"]["path"]).read_text())
            scored_bases = []
            module = types.SimpleNamespace(analyze=lambda spec, base: (scored_bases.append(base) or {
                "measurement_status": "MEASURED", "measurements": []}))
            with patch.object(measurements.importlib, "import_module", return_value=module):
                scored = measurements.analyze(result["measurements"]["path"], manifest_path, self.manifest, self.folder / "analysis")
            self.assertEqual([], scored["errors"])
            self.assertEqual([(self.folder / "artifacts/old/specs").resolve()], scored_bases)
            for path, data in before.items():
                self.assertEqual(data, path.read_bytes())
            (self.folder / aliases["artifacts/old/run/received.raw"]).write_text("tampered capture")
            with self.assertRaisesRegex(ValueError, "evidence changed"):
                measurements.validate_adapter(sidecar["cases"][0]["adapters"][0], self.manifest, manifest_path, self.folder / "prepared")

    def test_foreign_identity_or_input_is_retained_as_invalid_and_never_corrected(self):
        for field, value in (("run_id", "foreign"), ("apk_sha256", "c" * 64), ("pid", 456), ("source", "b")):
            with self.subTest(field=field):
                entry = {**self.entry, "scope": {**self.entry["scope"], field: value}}
                result = self.run_prepare([entry], field)
                self.assertEqual("INVALID", result["binding_status"])
                self.assertTrue(result["adapters"][0]["reason"])
                sidecar = json.loads(Path(result["measurements"]["path"]).read_text())
                self.assertEqual(value, sidecar["cases"][0]["adapters"][0]["scope"][field])
                scored = measurements.analyze(result["measurements"]["path"], self.manifest_path,
                                              self.manifest, self.folder / (field + "-analysis"))
                self.assertEqual("UNMEASURED", scored["measurements"][0]["status"])

    def test_missing_explicit_source_pid_or_session_is_not_inferred_from_manifest(self):
        for field in ("source", "pid", "session"):
            entry = {**self.entry, "scope": {key: value for key, value in self.entry["scope"].items() if key != field}}
            result = self.run_prepare([entry], field)
            self.assertEqual("INVALID", result["binding_status"])
            sidecar = json.loads(Path(result["measurements"]["path"]).read_text())
            self.assertNotIn(field, sidecar["cases"][0]["adapters"][0]["scope"])

    def test_physical_capture_preparation_binds_original_identity_without_claiming_calibration(self):
        self.manifest["measurement_captures"][0].update(origin="EXTERNAL_RECORDING", run_id="actual-run",
            apk_sha256="a" * 64, capture_method="independent_camera",
            observes=["source_target", "receiver_screen"], clock="original-container")
        self.write("manifest.json", self.manifest)
        spec = self.write("physical.json", {"schema": "benchmark-physical-av-observation-v1",
            "recording": self.capture, "planned": {"clock": "original-container"}})
        result = self.run_prepare([{**self.entry, "kind": "physical_av", "spec": spec}])
        self.assertEqual("READY", result["binding_status"])
        self.assertEqual("UNMEASURED", result["measurement_status"])
        proof = result["adapters"][0]["capture_provenance"]
        self.assertIsNone(proof["observation_plan"])
        self.assertEqual("MEASURED", proof["physical_observation_status"])

    def test_missing_spec_and_declared_hash_mismatch_remain_visible(self):
        bad = [{**self.entry, "id": "missing", "spec": "missing.json"},
               {**self.entry, "id": "changed", "spec": {**self.spec, "sha256": "c" * 64}}]
        result = self.run_prepare(bad)
        self.assertEqual("INVALID", result["binding_status"])
        self.assertEqual(2, len(result["adapters"]))
        self.assertTrue(all(row.get("reason") for row in result["adapters"]))
        sidecar = json.loads(Path(result["measurements"]["path"]).read_text())
        self.assertEqual(["missing", "changed"], [row["id"] for row in sidecar["cases"][0]["adapters"]])

    def test_malformed_adapter_kind_is_preserved_without_crashing_analysis(self):
        result = self.run_prepare([{**self.entry, "kind": []}])
        self.assertEqual("INVALID", result["binding_status"])
        scored = measurements.analyze(result["measurements"]["path"], self.manifest_path,
                                      self.manifest, self.folder / "analysis")
        self.assertEqual("UNMEASURED", scored["measurements"][0]["status"])
        self.assertTrue(scored["errors"])

    def test_duplicate_adapters_block_before_output_and_existing_output_is_preserved(self):
        with self.assertRaisesRegex(ValueError, "unique"):
            self.run_prepare([self.entry, self.entry])
        self.assertFalse((self.folder / "prepared").exists())
        result = self.run_prepare()
        saved = Path(result["measurements"]["path"]).read_bytes()
        with self.assertRaisesRegex(ValueError, "fresh"):
            self.run_prepare()
        self.assertEqual(saved, Path(result["measurements"]["path"]).read_bytes())

    def test_cli_invalid_binding_returns_failure_with_reviewable_receipt(self):
        bindings = self.write("bindings.json", {"schema": "benchmark-measurement-bindings-v1",
                                              "adapters": [{**self.entry, "scope": []}]})
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            result = prepare.main(["--manifest", str(self.manifest_path), "--bindings", bindings["path"],
                                   "--output", str(self.folder / "invalid")])
        self.assertEqual(1, result)
        receipt = json.loads((self.folder / "invalid/preparation.json").read_text())
        self.assertEqual("INVALID", receipt["binding_status"])
        self.assertEqual("UNMEASURED", receipt["measurement_status"])


if __name__ == "__main__":
    unittest.main()
