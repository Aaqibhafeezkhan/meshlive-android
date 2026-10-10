"""Private-call scoring under device-run-v4-user; reuses the call-log builder of the existing call tests."""
import copy
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_private_call_benchmark as runner
import test_run_private_call_benchmark as existing

V4 = {"version": "device-run-v4-user"}


class AcceptanceCallsTest(unittest.TestCase):
    def setUp(self):
        self.base = existing.PrivateCallBenchmarkTest("test_independent_profiles_and_duplicate_identity_validation")
        self.base.setUp()

    def score(self, thresholds=None, impaired=False, drops=0):
        case = copy.deepcopy(self.base.case)
        if impaired:
            case["media_impairments"] = {"a": {"path": "x", "sha256": "y"}}
        logs = {phone: self.base.log(phone, 15 if phone == "a" else 30, 30 if phone == "a" else 15) for phone in "ab"}
        for phone in "ab":
            logs[phone] = logs[phone].replace("drops 0", f"drops {drops}")
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for phone, text in logs.items():
                (folder / f"{phone}.log").write_text(text, encoding="utf-8")
            manifest = {"scenario": case, "devices": self.base.devices,
                        "logs": [{"device": phone, "path": phone + ".log", "pid": 123} for phone in "ab"],
                        "setup_anchors": [{"device": phone, "time": "10-03 00:00:00.500"} for phone in "ab"]}
            return runner.score_call_manifest(manifest, folder, thresholds)

    def test_default_call_scoring_is_v3_and_adds_nothing(self):
        result = self.score()
        self.assertEqual("device-run-v3", result["thresholds_version"])
        incoming = result["devices"][0]["incoming"]
        self.assertNotIn("longest_stall_ms", incoming["gates"])
        self.assertNotEqual("DIAGNOSTIC", incoming["gates"]["visible_fps"]["status"])

    def test_v4_call_uses_the_call_stall_limit_and_demotes_fps_fraction(self):
        result = self.score(V4)
        self.assertEqual("device-run-v4-user", result["thresholds_version"])
        incoming = result["devices"][0]["incoming"]
        self.assertEqual(500, incoming["gates"]["longest_stall_ms"]["limit"])
        self.assertEqual("call", incoming["gates"]["longest_stall_ms"]["applies_to"])
        self.assertEqual("DIAGNOSTIC", incoming["gates"]["visible_fps"]["status"])
        self.assertEqual("healthy", incoming["acceptance"]["link_class"])
        self.assertNotIn("longest_stall_ms", result["devices"][0]["outgoing"]["gates"])

    def test_impaired_call_demotes_loss_counters_but_keeps_audio_drops_decisive(self):
        healthy = self.score(V4, drops=2)
        impaired = self.score(V4, impaired=True, drops=2)
        self.assertEqual("impaired", impaired["devices"][0]["incoming"]["acceptance"]["link_class"])
        self.assertEqual("FAIL", healthy["devices"][0]["incoming"]["gates"]["decoder_drops"]["status"])
        self.assertEqual("DIAGNOSTIC", impaired["devices"][0]["incoming"]["gates"]["decoder_drops"]["status"])
        self.assertEqual("DIAGNOSTIC", impaired["devices"][0]["outgoing"]["gates"]["sender_drops"]["status"])
        self.assertEqual("FAIL", impaired["devices"][0]["incoming"]["gates"]["audio_drops"]["status"])
        self.assertEqual("FAIL", impaired["devices"][0]["incoming"]["log_status"])


if __name__ == "__main__":
    unittest.main()
