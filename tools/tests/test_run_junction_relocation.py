"""A finished run moved behind benchmarks/runs/<name> -> evidence/runs/<name> must behave like a plain run directory."""
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_layout as layout
import evidence_relocation as relocation
import test_benchmark_checkpoint as checkpoint_fixture

SPEC = importlib.util.spec_from_file_location("junction_suite", Path(__file__).parents[1] / "run_benchmark_suite.py")
suite = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(suite)


def junction(link, target):
    result = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)], capture_output=True, text=True)
    if result.returncode:
        raise unittest.SkipTest(f"Cannot create a directory junction here: {result.stdout}{result.stderr}")


@unittest.skipUnless(os.name == "nt", "Directory junctions are Windows-only")
class RunJunctionTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="run-junction-test-")
        self.addCleanup(self.scratch.cleanup)
        base = Path(self.scratch.name).resolve()
        self.root, self.evidence = base / "project", base / "evidence"
        (self.root / "benchmarks/runs").mkdir(parents=True)
        (self.root / "benchmarks/catalog").mkdir()
        (self.evidence / "runs").mkdir(parents=True)
        patcher = patch.multiple(relocation, PROJECT=self.root, RELOCATED={
            "benchmarks/runs": self.evidence / "runs", "benchmarks/inputs": self.evidence / "inputs"})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.runs = self.root / "benchmarks/runs"
        self.moved = self.add_junction("moved")
        self.plain = self.runs / "plain"
        self.plain.mkdir()

    def add_junction(self, name, target=None):
        target = target or self.evidence / "runs" / name
        target.mkdir(parents=True, exist_ok=True)
        junction(self.runs / name, target)
        return self.runs / name

    def test_only_a_single_declared_run_entry_is_an_accepted_junction(self):
        declared = relocation.declared_junction
        self.assertTrue(declared(self.moved))
        self.assertFalse(declared(self.plain))
        self.assertFalse(declared(self.runs))
        wrong = self.add_junction("wrong", self.evidence / "runs/elsewhere")
        self.assertFalse(declared(wrong))
        (self.evidence / "runs/moved/nested").mkdir()
        junction(self.evidence / "runs/moved/inner", self.evidence)
        self.assertFalse(declared(self.moved / "inner"))
        outside = self.root / "benchmarks/other"
        junction(outside, self.evidence / "runs/moved")
        self.assertFalse(declared(outside))
        with self.assertRaisesRegex(ValueError, "link|junction"):
            layout.output_path(wrong / "x.json", "runs", root=self.root)

    def test_layout_paths_match_a_plain_run(self):
        for name in ("moved", "plain"):
            run = self.runs / name
            (run / "sub").mkdir(exist_ok=True)
            (run / "sub/f.json").write_text("{}", encoding="utf-8")
            self.assertEqual(run, layout.output_path(run, "runs", root=self.root))
            self.assertEqual(run / "sub/new.json", layout.output_path(run / "sub/new.json", "runs", root=self.root))
            self.assertEqual(run / "sub/f.json", layout.resolve(run / "sub/f.json", root=self.root))
            self.assertEqual(run / "sub/f.json", layout.resolve(f"benchmarks/runs/{name}/sub/f.json", root=self.root))
            self.assertEqual(run / "sub/f.json", layout.original(run / "sub/f.json", root=self.root))
            self.assertEqual(run / "sub/f.json", relocation.logical(run / "sub/f.json"))

    def test_checkpoint_and_index_record_the_project_path(self):
        fixture = checkpoint_fixture.BenchmarkCheckpointTest("test_check_is_read_only_and_never_uses_phone_preflight")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        # The fixture's temp project differs from ours: put its two runs under its own junction layout.
        root = fixture.root
        (root / "benchmarks/runs").mkdir(parents=True)
        (self.evidence / "runs/moved2").mkdir()
        junction(root / "benchmarks/runs/moved2", self.evidence / "runs/moved2")
        relocation.PROJECT = root
        relocation.RELOCATED["benchmarks/runs"] = self.evidence / "runs"
        reports = []
        for name in ("plain2", "moved2"):
            folder = root / "benchmarks/runs" / name
            folder.mkdir(exist_ok=True)
            (folder / "suite-result.json").write_text(json.dumps(fixture.report), encoding="utf-8")
            (folder / "report.html").write_text("Report", encoding="utf-8")
            reports.append(folder / "suite-result.json")
        fixture.update(reports=reports)
        recorded = [row["path"] for row in json.loads(fixture.ledger_path.read_text())["unified_reports"]]
        self.assertIn("benchmarks/runs/plain2/suite-result.json", recorded)
        self.assertIn("benchmarks/runs/moved2/suite-result.json", recorded)
        page = fixture.output.read_text(encoding="utf-8")
        self.assertIn('href="runs/plain2/report.html"', page)
        self.assertIn('href="runs/moved2/report.html"', page)
        self.assertNotIn(str(self.evidence), page)

    def build_suite_run(self, output, source):
        suite.ROOT = self.root
        trace = output / "physical/receiver.log"
        trace.parent.mkdir(parents=True, exist_ok=True)
        trace.write_text("needed trace sample", encoding="utf-8")
        manifest = output / "physical/manifest.json"
        suite.write(manifest, {"logs": [{"path": str(trace)}], "source": str(source)})
        row = {"case_id": "one", "origin": "LIVE_ATTEMPT", "status": "FAIL", "comparison_key": "k",
               "families": {name: dict(suite.UNKNOWN) for name in suite.FAMILIES},
               "manifest": str(manifest), "manifest_sha256": suite.digest(manifest), "evidence_directory": str(output)}
        suite.report(output, [row], None)
        for name in ("setup.log", "ui.xml"):
            (output / name).write_bytes(b"reviewed setup scratch")
        suite.finalize_artifacts(output)

    def test_suite_finalize_and_review_match_a_plain_run(self):
        original_root = suite.ROOT
        self.addCleanup(setattr, suite, "ROOT", original_root)
        source = self.root / "source.mp4"
        source.write_bytes(b"original source outside the output")
        payloads = {}
        for name in ("plain", "moved"):
            output = self.runs / name
            self.build_suite_run(output, source)
            suite.review_artifacts(output, consume=["setup.log"], purpose="Scratch reviewed")
            self.assertFalse((output / "setup.log").exists())
            self.assertTrue((output / "ui.xml").exists())
            text = (output / "suite-result.json").read_text(encoding="utf-8").replace(name, "RUN")
            text = re.sub(r"[0-9a-f]{64}", "HASH", text)  # manifest bytes embed the run name
            text = re.sub(r"\d{4}-\d\d-\d\dT[0-9:.+-]+", "TIME", text)
            payload = json.loads(text)
            payload.pop("generated_at")
            payloads[name] = payload
            # Recorded paths use the project location, never the evidence drive.
            self.assertNotIn(str(self.evidence).replace("\\", "\\\\"), (output / "suite-result.json").read_text(encoding="utf-8"))
        self.assertEqual(payloads["plain"], payloads["moved"])
        self.assertTrue((self.evidence / "runs/moved/physical/manifest.json").is_file())

    def test_comparison_loads_a_moved_run_by_its_project_path(self):
        import compare_benchmark_runs as compare
        for name in ("plain", "moved"):
            (self.runs / name / "case").mkdir(exist_ok=True)
            loaded = compare.load_run(self.runs / name, {})
            self.assertEqual(str(self.runs / name), loaded["path"])
            self.assertEqual(name, loaded["run"])
        self.assertEqual(self.runs.parent / "catalog", compare.logical(self.runs / "moved").parent.parent / "catalog")

    def test_recorded_evidence_paths_use_the_project_location(self):
        import extract_measurement_capture as capture
        for name in ("plain", "moved"):
            record = capture.write_csv(self.runs / name / "samples.csv", ["a"], [{"a": 1}])
            self.assertEqual(str(self.runs / name / "samples.csv"), record["path"])


if __name__ == "__main__":
    unittest.main()
