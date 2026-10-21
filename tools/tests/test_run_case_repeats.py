import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run_case_repeats as repeats  # noqa: E402


class RunCaseRepeatsTest(unittest.TestCase):
    def test_rounds_alternate_the_cases_in_order_and_controls_stop_early(self):
        steps = repeats.plan(["off", "on", "ctl"], 3, {"ctl": 2}, "sr-ab-1")
        self.assertEqual(
            [(1, "off"), (1, "on"), (1, "ctl"), (2, "off"), (2, "on"), (2, "ctl"), (3, "off"), (3, "on")],
            [(number, case) for number, case, _ in steps],
        )
        outputs = [output for _, _, output in steps]
        self.assertEqual(len(outputs), len(set(outputs)), "every repeat gets its own folder")
        self.assertTrue(all(output.startswith("benchmarks/runs/sr-ab-1-r0") for output in outputs))

    def test_source_cases_translate_through_the_binding_manifest(self):
        manifest = {"bindings": [{"original_case_id": "off", "case_id": "raw-1"}, {"original_case_id": "on", "case_id": "raw-2"},
                                 {"original_case_id": "ctl", "case_id": "raw-3"}]}
        cases, rounds = repeats.translate(["off", "on", "ctl"], {"ctl": 4}, manifest)
        self.assertEqual(["raw-1", "raw-2", "raw-3"], cases)
        self.assertEqual({"raw-3": 4}, rounds)
        with self.assertRaises(ValueError):
            repeats.translate(["missing"], {}, manifest)

    def test_bad_requests_are_refused(self):
        for cases, rounds, extra, prefix in (([], 1, {}, "p"), (["a", "a"], 1, {}, "p"), (["a"], 1, {"b": 1}, "p"),
                                             (["a"], 0, {}, "p"), (["a"], 1, {}, "../escape")):
            with self.subTest(cases=cases, rounds=rounds, extra=extra, prefix=prefix), self.assertRaises(ValueError):
                repeats.plan(cases, rounds, extra, prefix)

    def test_per_execution_options_cannot_be_passed_through(self):
        with self.assertRaises(SystemExit):
            repeats.main(["--suite", "s", "--case", "a", "--rounds", "1", "--run-prefix", "p", "--", "--state", "x"])

    def test_each_command_runs_one_case_into_its_own_output(self):
        cmd = repeats.command("bundle.json", "case-1", "benchmarks/runs/p-r01-case-1", ["--owner", "me"])
        self.assertEqual(["run", "--suite", "bundle.json", "--case", "case-1", "--output", "benchmarks/runs/p-r01-case-1",
                          "--owner", "me"], cmd[3:])
        self.assertNotIn("--state", cmd)


if __name__ == "__main__":
    unittest.main()
