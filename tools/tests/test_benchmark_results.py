import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_results import generate


class BenchmarkResultsTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.output = self.root / "benchmarks/index.html"
        self.state = {"schema": "benchmark-case-state-v1", "candidate_apk_sha256": "a" * 64,
                      "cases": [{"case_id": "new", "state": "NOT RUN"}], "summary": {"NOT RUN": 1}}
        self.pointers = {}
        for key, value in (("inventory", {"remaining": ["Optical adapter unmeasured"]}), ("bundle", {}), ("input_requirements", {}), ("state", self.state)):
            self.pointers[key] = self.write_pointer(f"data/{key}.json", value)
        self.report = {"schema": "benchmark-suite-v1", "generated_at": "2026-10-04T00:00:00Z", "state_counts": {"FAIL": 1},
            "results": [{"case_id": "<script>old</script>", "status": "FAIL", "origin": "RECORDED_EXECUTION_ANALYSIS", "families": {"camera": {"status": "UNMEASURED"}}}]}
        report = self.write_pointer("old analysis/suite-result.json", self.report)
        report["state_counts"] = {"FAIL": 1}
        (self.root / "old analysis/report.html").write_text("Original report", encoding="utf-8")
        self.ledger = {"current_inventory": {**self.pointers, "states": {"NOT RUN": 1}, "case_count": 1,
            "required_phones": ["s24", "s22"], "candidate_apk_sha256": "a" * 64}, "unified_reports": [report], "executions": []}
        self.ledger_path = self.root / "ledger.json"
        self.save_ledger()

    def write_pointer(self, path, value):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(value), encoding="utf-8")
        return {"path": path, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}

    def save_ledger(self):
        self.ledger_path.write_text(json.dumps(self.ledger), encoding="utf-8")

    def test_view_preserves_originals_and_does_not_count_historical_result_as_current(self):
        originals = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        result = generate(self.ledger_path, self.output, self.root)
        self.assertEqual({"NOT RUN": 1}, result["states"])
        page = self.output.read_text(encoding="utf-8")
        self.assertIn("camera: UNMEASURED", page)
        self.assertIn("&lt;script&gt;old&lt;/script&gt;", page)
        self.assertIn("../old%20analysis/report.html", page)
        for path, content in originals.items():
            self.assertEqual(content, path.read_bytes())

    def test_changed_indexed_evidence_does_not_replace_existing_view(self):
        generate(self.ledger_path, self.output, self.root)
        before = self.output.read_bytes()
        (self.root / "data/state.json").write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Indexed evidence changed"):
            generate(self.ledger_path, self.output, self.root)
        self.assertEqual(before, self.output.read_bytes())

    def test_media_retirement_is_hash_bound_without_changing_recorded_results(self):
        pointer = self.write_pointer("data/media-audit.json", {"privacy_cleanup": {"removed_files": 39}})
        self.ledger["media_retirement"] = pointer
        self.save_ledger()
        original = self.ledger_path.read_bytes()
        result = generate(self.ledger_path, self.output, self.root)
        self.assertEqual({"NOT RUN": 1}, result["states"])
        self.assertEqual(original, self.ledger_path.read_bytes())
        self.assertIn('href="../data/media-audit.json"', self.output.read_text(encoding="utf-8"))
        before = self.output.read_bytes()
        (self.root / pointer["path"]).write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Indexed evidence changed"):
            generate(self.ledger_path, self.output, self.root)
        self.assertEqual(before, self.output.read_bytes())

    def test_stale_ledger_counts_cannot_show_completed_coverage(self):
        self.ledger["current_inventory"]["states"] = {"PASS": 1}
        self.save_ledger()
        with self.assertRaisesRegex(ValueError, "disagree"):
            generate(self.ledger_path, self.output, self.root)
        self.assertFalse(self.output.exists())

    def test_suite_counts_must_match_real_rows(self):
        self.ledger["unified_reports"][0]["state_counts"] = {"PASS": 1}
        self.save_ledger()
        with self.assertRaisesRegex(ValueError, "counts disagree"):
            generate(self.ledger_path, self.output, self.root)

    def test_candidate_build_disagreement_cannot_label_state_as_another_apk(self):
        self.ledger["current_inventory"]["candidate_apk_sha256"] = "b" * 64
        self.save_ledger()
        with self.assertRaisesRegex(ValueError, "candidate APK disagree"):
            generate(self.ledger_path, self.output, self.root)
        self.assertFalse(self.output.exists())

    def test_original_reports_and_unrelated_files_are_not_overwritten(self):
        with self.assertRaisesRegex(ValueError, "named index.html"):
            generate(self.ledger_path, self.root / "old analysis/report.html", self.root)
        self.output.parent.mkdir(parents=True)
        self.output.write_text("Other content", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "unrelated HTML"):
            generate(self.ledger_path, self.output, self.root)
        self.assertEqual("Other content", self.output.read_text(encoding="utf-8"))

    def test_missing_original_historical_score_blocks_view_refresh(self):
        self.ledger["executions"] = [{"id": "old", "state": "FAIL", "evidence": {"manifest": "missing.json", "score": "missing-score.json"}}]
        self.save_ledger()
        with self.assertRaisesRegex(ValueError, "Indexed evidence is missing"):
            generate(self.ledger_path, self.output, self.root)


if __name__ == "__main__":
    unittest.main()
