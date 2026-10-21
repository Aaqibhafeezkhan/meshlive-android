import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import analyze_call_renewal as analyzer  # noqa: E402


def write_execution(root, name, *, renewal, stall, a_log, b_log, end=True, freezes=(0, 0), runner_error=None):
    folder = root / name
    folder.mkdir(parents=True)
    scenario = {"id": name, "caller": "m31", "callee": "a15s", "tunables": {"call_link_renewal": renewal}}
    if stall:
        scenario["phone_tunables"] = {"m31": {"call_tx_stall_after_s": 20}}
    (folder / "m31-1.log").write_text(a_log, encoding="utf-8")
    (folder / "a15s-2.log").write_text(b_log, encoding="utf-8")
    manifest = {
        "scenario": scenario,
        "runner_error": runner_error,
        "logs": [{"device": "m31", "path": "m31-1.log"}, {"device": "a15s", "path": "a15s-2.log"}],
        "control_events": [{"action": "end", "terminal_evidence": {"m31": {}, "a15s": {}}}] if end else [],
    }
    (folder / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    score = {"devices": [{"device": "m31", "incoming": {"freezes": {"unexpected": freezes[0]}}},
                         {"device": "a15s", "incoming": {"freezes": {"unexpected": freezes[1]}}}]}
    (folder / "score.json").write_text(json.dumps(score), encoding="utf-8")
    return folder


STALLED = "BENCHMARK call_debug_tx_stall: afterS=20 | link=first\n"
ARMED_BOUND = ("BENCHMARK call_renewal: role=armed | phase=offer | attempt=1\n"
               "BENCHMARK call_renewal: role=armed | phase=bound | connection=1 | ms=2100\n"
               "BENCHMARK call ended_ui: message=Call ended\n")
OFFERER_BOUND = ("BENCHMARK call_renewal: role=offerer | phase=offer | attempt=1 | silentMs=4010\n"
                 "BENCHMARK call_renewal: role=offerer | phase=bound | ms=2600 | attempt=1\n"
                 "BENCHMARK call_renewal_silence: role=offerer | ms=6650\n"
                 "BENCHMARK call ended_ui: message=Peer ended the call\n")


class AnalyzeCallRenewalTest(unittest.TestCase):
    def test_a_renewed_call_survives_and_reports_its_silence(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = write_execution(Path(directory), "sr-on-1", renewal=True, stall=True,
                                     a_log=STALLED + ARMED_BOUND, b_log=OFFERER_BOUND, freezes=(1, 0))
            row = analyzer.execution(folder)
        self.assertEqual("renewal-on", row["arm"])
        self.assertEqual("m31", row["stalled_phone"])
        self.assertTrue(row["survived"])
        self.assertTrue(row["renewal_bound"])
        self.assertEqual([6650], row["silence_ms"])
        self.assertEqual(1, row["unexpected_freezes"])
        self.assertTrue(row["stall_hook_fired"]["m31"])

    def test_a_call_lost_to_the_stall_did_not_survive(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = write_execution(
                Path(directory), "sr-off-1", renewal=False, stall=True,
                a_log=STALLED + "BENCHMARK call ended_ui: message=Call ended: this phone's sound and video stopped reaching the other phone\n",
                b_log="BENCHMARK call ended_ui: message=Call link lost: Call peer stopped responding\n",
                end=False, runner_error="planned end not reached",
            )
            row = analyzer.execution(folder)
        self.assertEqual("renewal-off", row["arm"])
        self.assertFalse(row["survived"])
        self.assertFalse(row["renewal_bound"])

    def test_an_offer_in_the_control_arm_is_a_false_renewal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            clean = write_execution(root, "sr-ctl-1", renewal=True, stall=False,
                                    a_log="BENCHMARK call ended_ui: message=Call ended\n",
                                    b_log="BENCHMARK call ended_ui: message=Peer ended the call\n")
            false = write_execution(root, "sr-ctl-2", renewal=True, stall=False,
                                    a_log=ARMED_BOUND, b_log=OFFERER_BOUND)
            rows = [analyzer.execution(clean), analyzer.execution(false)]
        self.assertEqual(["control", "control"], [row["arm"] for row in rows])
        self.assertEqual([False, True], [row["false_renewal"] for row in rows])
        summary = analyzer.summarize(rows)
        self.assertEqual(1, len(summary))
        self.assertEqual(2, summary[0]["calls"])
        self.assertEqual(1, summary[0]["false_renewals"])

    def test_repeats_in_separate_run_folders_are_summarised_together(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            for number in (1, 2):
                write_execution(runs / f"sr-ab-x-r0{number}-on" / "case", "sr-on-stall", renewal=True, stall=True,
                                a_log=STALLED + ARMED_BOUND, b_log=OFFERER_BOUND)
            write_execution(runs / "other-run" / "case", "unrelated", renewal=False, stall=False,
                            a_log="", b_log="")
            roots = analyzer.run_roots([runs], "sr-ab-x-*")
            self.assertEqual(2, len(roots))
            rows = [analyzer.execution(folder) for folder in analyzer.call_folders(roots)]
        summary = analyzer.summarize(rows)
        self.assertEqual([("renewal-on", 2, 2)], [(item["arm"], item["calls"], item["survived"]) for item in summary])

    def test_arms_are_summarised_separately(self):
        rows = [
            {"arm": "renewal-on", "stalled_phone": "m31", "survived": True, "renewal_bound": True, "false_renewal": False,
             "silence_ms": [6000], "unexpected_freezes": 1},
            {"arm": "renewal-on", "stalled_phone": "m31", "survived": True, "renewal_bound": True, "false_renewal": False,
             "silence_ms": [8000], "unexpected_freezes": 0},
            {"arm": "renewal-off", "stalled_phone": "m31", "survived": False, "renewal_bound": False, "false_renewal": False,
             "silence_ms": [], "unexpected_freezes": None},
        ]
        summary = {item["arm"]: item for item in analyzer.summarize(rows)}
        self.assertEqual(2, summary["renewal-on"]["survived"])
        self.assertEqual(7000, summary["renewal-on"]["silence_ms_median"])
        self.assertEqual(8000, summary["renewal-on"]["silence_ms_max"])
        self.assertEqual(0, summary["renewal-off"]["survived"])
        self.assertIsNone(summary["renewal-off"]["unexpected_freezes_total"])


if __name__ == "__main__":
    unittest.main()
