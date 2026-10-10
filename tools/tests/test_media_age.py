"""Cross-phone media-age regressions for the pipeline adapter; offline, no phone operations."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import score_pipeline_trace as pipeline

SENDER_CLOCK, RECEIVER_CLOCK = "System.nanoTime", "System.nanoTime"
FRAME_NS = 1_000_000_000 // 30


class MediaAgeTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="media-age-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        base = {"run_id": "run-1", "source": "a", "stream_generation": 1}
        self.sender = {**base, "phone": "a", "session": "s-a", "pid": 11, "clock": SENDER_CLOCK, "direction": "outgoing"}
        self.receiver = {**base, "phone": "b", "session": "s-b", "pid": 22, "clock": RECEIVER_CLOCK, "direction": "incoming"}

    def document(self, name, value):
        (self.folder / name).write_text(json.dumps(value), encoding="utf-8")
        return {"path": name, "sha256": hashlib.sha256((self.folder / name).read_bytes()).hexdigest()}

    def rows(self, identity, stage, offset_ns, delay_ns, frames=30, skip=(), spike=None):
        rows, sequence = [], 0
        for index in range(frames):
            if index in skip:
                continue
            sequence += 1
            at = index * FRAME_NS
            extra = spike[1] if spike and spike[0] == index else 0
            rows.append({**identity, "scope": "scope", "sequence": sequence, "stage": stage, "pts_us": at // 1000,
                         "at_ns": at + offset_ns + delay_ns + extra, "bytes": 0, "final": False})
        rows.append({**identity, "scope": "scope", "sequence": sequence + 1, "stage": "final", "pts_us": 0,
                     "at_ns": frames * FRAME_NS + offset_ns + 10**9, "bytes": 0, "final": True})
        return rows

    def spec(self, offset_ns=7_000_000_000, delay_ns=150_000_000, anchored=True, limit=400, uncertainty_ns=2_000_000, **kwargs):
        sender_rows = self.rows(self.sender, "raw_capture", 0, 0)
        receiver_rows = self.rows(self.receiver, "release", offset_ns, delay_ns, **kwargs)
        spec = {"schema": "benchmark-pipeline-observation-v1", "traces": [
            {**self.sender, **self.document("a.json", {"schema": "benchmark-stage-events-v1", "events": sender_rows}), "format": "json"},
            {**self.receiver, **self.document("b.json", {"schema": "benchmark-stage-events-v1", "events": receiver_rows}), "format": "json"}],
            "plans": [], "acceptance": {"media_age_p95_ms": limit, "media_age_max_ms": limit},
            "media_age": [{"sender": self.sender, "receiver": self.receiver, "from_stage": "raw_capture", "to_stage": "release"}]}
        if anchored:
            def anchor(identity, local_offset):
                return {"run_id": "run-1", "phone": identity["phone"], "pid": identity["pid"], "clock": identity["clock"],
                        "basis": "measured_pairs", "common_clock": "reference", "method": "test-bracket",
                        "pairs": [{"local_ns": common + local_offset, "common_ns": common, "uncertainty_ns": uncertainty_ns}
                                  for common in (0, 5_000_000_000, 20_000_000_000)]}
            spec["clock_anchors"] = self.document("anchors.json", {"schema": "benchmark-clock-anchors-v1", "anchors": [
                anchor(self.sender, 0), anchor(self.receiver, offset_ns)]})
        return spec

    @staticmethod
    def gate(result, name):
        return next(row for row in result["measurements"] if row["name"] == name)

    def test_anchored_age_is_measured_and_compared_as_an_upper_bound(self):
        result = pipeline.analyze(self.spec(), self.folder)
        outcome = result["diagnostics"]["media_age"][0]
        self.assertEqual("COMPLETE", outcome["status"])
        self.assertEqual(30, outcome["samples"])
        self.assertAlmostEqual(150.0, outcome["age_ms"]["p50"], places=3)
        p95 = self.gate(result, "media_age_p95_ms")
        self.assertEqual("media_age", p95["family"])
        self.assertEqual("PASS", p95["status"])
        self.assertAlmostEqual(154.0, p95["value"], places=3)
        self.assertAlmostEqual(150.0, p95["nominal_ms"], places=3)

    def test_age_over_the_declared_target_fails(self):
        result = pipeline.analyze(self.spec(delay_ns=450_000_000), self.folder)
        self.assertEqual("FAIL", self.gate(result, "media_age_max_ms")["status"])

    def test_one_late_frame_sets_the_maximum_not_the_p95(self):
        result = pipeline.analyze(self.spec(spike=(10, 700_000_000)), self.folder)
        self.assertEqual("FAIL", self.gate(result, "media_age_max_ms")["status"])
        self.assertEqual("PASS", self.gate(result, "media_age_p95_ms")["status"])

    def test_unanchored_phone_clocks_are_never_compared(self):
        result = pipeline.analyze(self.spec(anchored=False), self.folder)
        self.assertEqual("INCOMPLETE", result["diagnostics"]["media_age"][0]["status"])
        self.assertIn("unaligned_clocks", result["diagnostics"]["media_age"][0]["issues"])
        self.assertEqual("UNMEASURED", self.gate(result, "media_age_p95_ms")["status"])
        self.assertIsNone(self.gate(result, "media_age_p95_ms")["value"])

    def test_no_target_means_a_measured_value_but_no_verdict(self):
        spec = self.spec()
        spec["acceptance"] = {}
        gate = self.gate(pipeline.analyze(spec, self.folder), "media_age_p95_ms")
        self.assertEqual("UNMEASURED", gate["status"])
        self.assertAlmostEqual(154.0, gate["value"], places=3)

    def test_unplayed_frames_are_counted_not_aged(self):
        result = pipeline.analyze(self.spec(skip=(3, 4)), self.folder)
        outcome = result["diagnostics"]["media_age"][0]
        self.assertEqual(2, outcome["sent_never_played_pts"])
        self.assertEqual(28, outcome["samples"])

    def test_sides_must_be_two_phones_of_one_run_and_source(self):
        spec = self.spec()
        spec["media_age"][0]["receiver"] = {**self.receiver, "phone": "a"}
        with self.assertRaises(ValueError):
            pipeline.analyze(spec, self.folder)

    def test_without_a_media_age_plan_nothing_new_is_reported(self):
        spec = self.spec()
        del spec["media_age"]
        result = pipeline.analyze(spec, self.folder)
        self.assertEqual([], result["diagnostics"]["media_age"])
        self.assertFalse([row for row in result["measurements"] if row["name"].startswith("media_age")])


if __name__ == "__main__":
    unittest.main()
