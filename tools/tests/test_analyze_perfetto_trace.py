import csv
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from analyze_perfetto_trace import PACKAGE, analyze, reference, summarize, validate
import analyze_perfetto_trace as perfetto
import benchmark_layout as layout


def rows(records):
    out = io.StringIO()
    writer = csv.writer(out)
    writer.writerow(["row_hex"])
    for row in records:
        writer.writerow([json.dumps(row).encode("utf-8").hex().upper()])
    return out.getvalue()


class PerfettoAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.trace = self.root / "saved.pftrace"
        self.trace.write_bytes(b"retained trace")
        self.processor = self.root / "processor.exe"
        self.processor.write_bytes(b"frozen parser")
        self.manifest = self.root / "manifest.json"
        self.capture = self.root / "capture.json"
        self.manifest.write_text(json.dumps({"run_id": "saved-run", "devices": {"b": {"apk_sha256": "a" * 64}},
                                            "logs": [{"device": "b", "pid": 123}]}))
        self.meta = {"phone": "b", "pid": "123", "apk_sha256": "a" * 64, "state": "CAPTURED_NOT_YET_ANALYZED",
                     **reference(self.trace)}
        self.capture.write_text(json.dumps(self.meta))

    def check(self):
        return validate(self.manifest, self.capture, self.processor, reference(self.processor)["sha256"])

    def test_validates_saved_bytes_build_and_manifest_pid_before_running_parser(self):
        self.check()
        for field, value in (("pid", "124"), ("apk_sha256", "b" * 64), ("sha256", "c" * 64),
                             ("state", "RUNNING")):
            self.capture.write_text(json.dumps({**self.meta, field: value}))
            with patch("analyze_perfetto_trace.subprocess.run", side_effect=AssertionError("No parser on invalid input")):
                with self.assertRaises(ValueError):
                    analyze(self.manifest, self.capture, self.processor, reference(self.processor)["sha256"], self.root / "output")
            self.assertFalse((self.root / "output").exists())

    def test_frozen_parser_hash_is_required(self):
        with self.assertRaisesRegex(ValueError, "processor hash"):
            validate(self.manifest, self.capture, self.processor, "d" * 64)

    def test_relocated_capture_keeps_original_relative_trace_and_parser_hash(self):
        self.meta["path"] = "saved.pftrace"
        self.capture.write_text(json.dumps(self.meta))
        aliases = {"manifest.json": "benchmarks/runs/id/manifest.json", "capture.json": "benchmarks/runs/id/meta/capture.json",
                   "saved.pftrace": "benchmarks/runs/id/traces/phone.pftrace", "processor.exe": "benchmarks/builds/parser/processor.exe"}
        for source, destination in aliases.items():
            target = self.root / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            (self.root / source).replace(target)
        ledger = self.root / "benchmarks/catalog/ledger.json"
        ledger.parent.mkdir(parents=True)
        ledger.write_text(json.dumps({"storage": {"paths": aliases}}))
        resolve = lambda path, base=None: layout.resolve(path, base=base, root=self.root)
        original = lambda path: layout.original(path, root=self.root)
        with patch.object(perfetto, "resolve_input", resolve), patch.object(perfetto, "original", original):
            manifest, capture, trace, parser = self.check()
            self.assertEqual("saved-run", manifest["run_id"])
            self.assertEqual(self.meta, capture)
            self.assertEqual((self.root / aliases["saved.pftrace"]).resolve(), Path(trace["path"]))
            self.assertEqual((self.root / aliases["processor.exe"]).resolve(), Path(parser["path"]))
            (self.root / aliases["saved.pftrace"]).write_bytes(b"changed trace")
            with self.assertRaisesRegex(ValueError, "capture bytes/hash"):
                self.check()

    def records(self):
        return [{"kind": "bounds", "start_ts": 100, "end_ts": 1000000100},
                {"kind": "process", "pid": 123, "name": PACKAGE},
                {"kind": "thread", "tid": 124, "name": 'Bluetooth, read "owner"'},
                {"kind": "stats", "name": "traced_buf_bytes_overwritten", "severity": "info", "value": 99},
                {"kind": "clock", "ts": 150, "clock_value": 120, "clock_id": 3}]

    def test_hex_records_preserve_quoted_names_and_expose_parser_loss_and_clock_limits(self):
        result = summarize(rows(self.records()), 123)
        self.assertEqual('Bluetooth, read "owner"', result["thread"][0]["name"])
        self.assertEqual(1, len(result["parser_loss_or_error_stats"]))
        self.assertEqual([30, 30], result["clock_offset_min_max_ns"])
        self.assertIn("no extrapolation", result["clock_alignment"])

    def test_successful_analysis_keeps_state_rows_separate_from_diagnostic_status(self):
        records = self.records() + [{"kind": "state", "tid": 124, "state": "S", "open_rows": 1,
                                   "closed_total_ns": 20, "closed_max_ns": 20}]
        class Success:
            returncode, stdout, stderr = 0, rows(records), "saved parser output"
        with patch("analyze_perfetto_trace.subprocess.run", return_value=Success()):
            result = analyze(self.manifest, self.capture, self.processor, reference(self.processor)["sha256"], self.root / "analysis")
        self.assertEqual("DIAGNOSTIC", result["state"])
        self.assertEqual(1, len(result["thread_states"]))
        self.assertEqual(1, result["thread_states"][0]["open_rows"])

    def test_missing_or_reused_process_lifetime_cannot_be_summed_as_one_pid(self):
        records = self.records()
        for changed in ([row for row in records if row["kind"] != "process"], records + [records[1]]):
            with self.assertRaisesRegex(ValueError, "reused app PID"):
                summarize(rows(changed), 123)
        records[1]["name"] = "other-app"
        with self.assertRaisesRegex(ValueError, "expected app"):
            summarize(rows(records), 123)

    def test_failed_parser_retains_query_and_error_output(self):
        class Failure:
            returncode, stdout, stderr = 1, "", "parser failure"
        output = self.root / "analysis"
        with patch("analyze_perfetto_trace.subprocess.run", return_value=Failure()):
            with self.assertRaisesRegex(ValueError, "raw output retained"):
                analyze(self.manifest, self.capture, self.processor, reference(self.processor)["sha256"], output)
        self.assertEqual("parser failure", (output / "processor.log").read_text())
        self.assertTrue((output / "scheduler.sql").exists())
        self.assertFalse((output / "summary.json").exists())


if __name__ == "__main__":
    unittest.main()
