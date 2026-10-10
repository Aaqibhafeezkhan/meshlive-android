"""Suite analysis under device-run-v3 (default) and device-run-v4-user; offline, no phone operations."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_benchmark_suite as suite

PASS = {"status": "PASS", "value": 0, "limit": 0}
V4 = "device-run-v4-user"


class AcceptanceSuiteTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="acceptance-suite-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name).resolve()
        profile = {"codec": "encoder-a", "width": 320, "height": 240, "fps": 15, "bitrate_kbps": 500,
                   "rate_control": "cbr", "playback": "balanced", "thermal_reduction": False}
        self.manifest = {"run_id": "offline", "source_kind": "raw_fixture_live_encoder",
                         "devices": {"a": {"node_prefix": "000000000001"}, "b": {"node_prefix": "000000000002"}},
                         "scenario": {"id": "offline", "source": "a", "transport": "gatt", "profile": profile,
                                      "receivers": [{"phone": "b", "parent": "a"}]}}
        self.score = {"overall_status": "PASS", "devices": [
            {"device": "b", "role": "receiver", "gates": {
                "longest_stall_ms": PASS, "frozen_time_share_percent": PASS, "effective_profile_fps": PASS,
                "visible_fps": {"status": "DIAGNOSTIC", "measured_status": "FAIL", "value": 10, "limit": 14.25},
                "audio_drops": PASS}}]}

    def analyze(self, required, profile=None, vmaf=60, adapters=None):
        path = self.folder / "manifest.json"
        path.write_text(json.dumps(self.manifest), encoding="utf-8")
        quality = {"status": "ok", "measurement_status": "MEASURED", "planned_frames": 300, "encoded_frames": 300,
                   "received_frames": 300, "quality": {"vmaf": vmaf, "ssim": 0.99, "psnr_db": 40}, "phases": []}
        stack = [patch.object(suite, "score_manifest", return_value=self.score),
                 patch.object(suite, "score_phase_delivery", return_value=quality),
                 patch.object(suite, "score_motion", return_value={"timing_state": "UNMEASURED", "capture_state": "COMPLETE"})]
        if adapters is not None:
            stack.append(patch.object(suite.benchmark_measurements, "analyze", return_value=adapters))
        for item in stack:
            item.start()
            self.addCleanup(item.stop)
        output = self.folder / f"analysis-{profile}-{len(list(self.folder.iterdir()))}"
        return suite.analyze(path, output, required, None, profile)

    def test_v3_default_is_unchanged_and_low_vmaf_still_fails_picture(self):
        result = self.analyze(suite.FAMILIES)
        self.assertEqual("device-run-v3", result["thresholds_version"])
        self.assertEqual(set(suite.FAMILIES), set(result["families"]))
        self.assertEqual("FAIL", result["families"]["picture"]["status"])
        self.assertEqual("FAIL", result["status"])

    def test_v4_fidelity_is_reported_but_never_decides(self):
        result = self.analyze(("picture", "smoothness", "transport"), V4)
        self.assertEqual(V4, result["thresholds_version"])
        picture = result["families"]["picture"]
        self.assertNotEqual("FAIL", picture["status"])
        vmaf = next(row for row in picture["measurements"] if row["name"] == "vmaf")
        self.assertEqual(("DIAGNOSTIC", "FAIL", 60), (vmaf["status"], vmaf["measured_status"], vmaf["value"]))
        self.assertEqual("PASS", result["families"]["smoothness"]["status"])
        self.assertEqual("PASS", result["families"]["transport"]["status"])
        diagnostic = next(row for row in result["families"]["smoothness"]["measurements"] if row["name"] == "visible_fps")
        self.assertEqual("DIAGNOSTIC", diagnostic["status"])

    def test_v4_failed_user_gate_still_fails_and_user_families_stay_unmeasured_with_reasons(self):
        self.score["devices"][0]["gates"]["longest_stall_ms"] = {"status": "FAIL", "value": 1500, "limit": 1000}
        result = self.analyze(("smoothness",), V4)
        self.assertEqual("FAIL", result["families"]["smoothness"]["status"])
        self.assertEqual("FAIL", result["status"])
        for name in ("media_age", "task_success", "speech_comprehension"):
            self.assertEqual("UNMEASURED", result["families"][name]["status"])
            self.assertTrue(result["families"][name]["reason"])
        self.assertNotEqual("PASS", result["full_experience_status"])

    def test_v4_full_requirement_cannot_pass_while_user_families_are_unmeasured(self):
        result = self.analyze(suite.acceptance.FAMILIES_V4, V4)
        self.assertNotEqual("PASS", result["status"])
        self.assertEqual("UNMEASURED", result["families"]["task_success"]["status"])

    def test_v3_run_rejects_v4_only_required_families(self):
        with self.assertRaises(ValueError):
            self.analyze(("media_age",))

    def test_media_age_rows_land_in_their_family_only_under_v4(self):
        adapters = {"adapters": [], "errors": [], "measurements": [
            {"name": "media_age_p95_ms", "family": "media_age", "status": "PASS", "value": 250, "limit": 400}]}
        under_v4 = self.analyze(("smoothness", "media_age"), V4, adapters=adapters)
        self.assertEqual("PASS", under_v4["families"]["media_age"]["status"])
        self.assertEqual("media_age_p95_ms", under_v4["families"]["media_age"]["measurements"][0]["name"])
        (self.folder / "analysis").mkdir(exist_ok=True)
        under_v3 = self.analyze(("transport",), None, adapters=adapters)
        self.assertNotIn("media_age", under_v3["families"])
        moved = [row for row in under_v3["families"]["transport"]["measurements"] if row["name"] == "media_age_p95_ms"]
        self.assertEqual("media_age", moved[0]["family_requested"])

    def test_status_and_family_routing(self):
        self.assertEqual("PASS", suite.status([{"status": "PASS"}, {"status": "DIAGNOSTIC"}]))
        self.assertEqual("UNMEASURED", suite.status([{"status": "DIAGNOSTIC"}]))
        for name in ("longest_stall_ms", "frozen_time_share_percent", "effective_profile_fps",
                     "requested_profile_shortfall", "degraded_time_share_percent"):
            self.assertEqual("smoothness", suite.family_for(name))
        self.assertEqual("media_age", suite.family_for("media_age_p95_ms"))
        self.assertEqual("transport", suite.family_for("queue_age_proxy_ms"))
        self.assertEqual("transport", suite.family_for("audio_drops"))

    def test_report_and_html_cope_with_mixed_profiles_and_never_relabel_old_results(self):
        old = {"case_id": "old", "status": "PASS", "thresholds_version": "device-run-v3",
               "families": {name: {"status": "PASS"} for name in suite.FAMILIES}}
        new = {"case_id": "new", "status": "FAIL", "thresholds_version": V4,
               "families": {name: {"status": "UNMEASURED"} for name in suite.acceptance.FAMILIES_V4}}
        output = self.folder / "report"
        output.mkdir()
        payload = suite.report(output, [old, new])
        self.assertEqual("mixed", payload["thresholds"]["version"])
        self.assertEqual("PASS", old["status"])
        self.assertIn("task_success", (output / "report.html").read_text(encoding="utf-8"))
        only_new = suite.report(output, [new])
        self.assertEqual(V4, only_new["thresholds"]["version"])
        self.assertEqual(500, only_new["thresholds"]["max_stall_ms_call"])
        self.assertEqual("device-run-v3", suite.report(output, [old])["thresholds"]["version"])

    def test_bundle_group_profile_reaches_jobs_and_defaults_the_right_families(self):
        suite_file = self.folder / "suite.json"
        case = {"id": "case", "source": "a", "transport": "gatt", "duration_s": 45,
                "profile": {"codec": "c", "width": 320, "height": 240, "fps": 15, "bitrate_kbps": 300,
                            "rate_control": "cbr", "playback": "balanced"},
                "receivers": [{"phone": "b", "parent": "a"}], "actions": []}
        suite_file.write_text(json.dumps({"schema_version": 1, "cases": [case]}), encoding="utf-8")
        bundle = self.folder / "bundle.json"
        devices = {"a": {"serial": "x", "node_prefix": "000000000001"}, "b": {"serial": "y", "node_prefix": "000000000002"}}
        with patch.object(suite, "participants", return_value={"a", "b"}):
            bundle.write_text(json.dumps({"suites": [{"path": str(suite_file), "execution": "concurrent",
                                                      "acceptance_profile": V4}]}), encoding="utf-8")
            job = suite.load_jobs(bundle, devices)[0]
            self.assertEqual(V4, job["acceptance_profile"])
            self.assertEqual(list(suite.acceptance.FAMILIES_V4), job["required_families"])
            bundle.write_text(json.dumps({"suites": [{"path": str(suite_file), "execution": "concurrent"}]}), encoding="utf-8")
            job = suite.load_jobs(bundle, devices)[0]
            self.assertNotIn("acceptance_profile", job)
            self.assertEqual(list(suite.FAMILIES), job["required_families"])
            bundle.write_text(json.dumps({"suites": [{"path": str(suite_file), "execution": "concurrent",
                                                      "acceptance_profile": "device-run-v9"}]}), encoding="utf-8")
            with self.assertRaises(ValueError):
                suite.load_jobs(bundle, devices)


if __name__ == "__main__":
    unittest.main()
