"""Device-policy report: the exported registry is consistent and the Python matcher agrees with Kotlin."""
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import device_policy_report as report

REGISTRY = json.loads(report.DEFAULT_REGISTRY.read_text(encoding="utf-8"))


def run(*argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = report.main(list(argv))
    return code, out.getvalue()


class DevicePolicyReportTest(unittest.TestCase):
    def test_export_passes_its_consistency_check(self):
        self.assertEqual([], report.check(REGISTRY))
        code, out = run("--check")
        self.assertEqual(0, code, out)

    def test_every_benchmark_phone_is_exported(self):
        aliases = sorted(d["alias"] for d in REGISTRY["known_devices"])
        devices = json.loads((report.ROOT / "tools" / "benchmark_devices.example.json").read_text(encoding="utf-8"))
        self.assertEqual(sorted(devices["devices"]), aliases)

    def test_python_resolution_matches_the_kotlin_export(self):
        for device in REGISTRY["known_devices"]:
            traits = report.complete_traits({k: v for k, v in device["traits"].items()
                                             if k not in ("soc_family", "skin", "stack_lineage")})
            resolved = report.resolve(REGISTRY, traits)
            self.assertEqual(device["active"], resolved["active"], device["alias"])
            self.assertEqual(device["component_rules"], resolved["component_rules"], device["alias"])

    def test_msmnile_traits_activate_the_legacy_entries_and_switches_remove_them(self):
        traits = report.parse_traits("hardware=qcom,board=SM8150-ac,sdk=32,manufacturer=asus")
        active = report.resolve(REGISTRY, traits)["active"]
        self.assertIn("qcom-msmnile-call", active)
        self.assertIn("qcom-legacy-gatt-call", active)
        self.assertNotIn("qcom-atoll-coc-call", active)
        self.assertNotIn("gatt-context-map-race", active)  # API 32
        off = report.resolve(REGISTRY, traits, {"qcom-msmnile-call"})
        self.assertNotIn("qcom-msmnile-call", off["active"])
        self.assertEqual(["qcom-msmnile-call"], off["switched_off"])
        self.assertEqual([], report.resolve(REGISTRY, traits, {"all"})["active"])
        newer = report.resolve(REGISTRY, report.parse_traits("hardware=qcom,board=msmnile,sdk=33"))["active"]
        self.assertFalse([eid for eid in newer if eid.startswith("qcom-")])

    def test_derivations_match_the_kotlin_rules(self):
        self.assertEqual("QUALCOMM", report.derive_soc_family("QTI", "x"))
        self.assertEqual("MEDIATEK", report.derive_soc_family("", "mt6765"))
        self.assertEqual("EXYNOS", report.derive_soc_family("", "s5e9945"))
        self.assertEqual("UNKNOWN", report.derive_soc_family("", "gs101"))
        self.assertEqual("COLOROS", report.derive_skin("OPPO"))
        self.assertEqual("BLUETOOTH_MODULE", report.derive_stack_lineage(33))
        self.assertEqual("PLATFORM_SYSTEM_BT", report.derive_stack_lineage(32))

    def test_stack_eras_and_umbrellas_match_the_kotlin_covers_rule(self):
        eras = {29: "LEGACY_29_30", 30: "LEGACY_29_30", 31: "LEGACY_GD_OPTIONAL_31_32", 32: "LEGACY_GD_OPTIONAL_31_32",
                33: "GD_CORE_33", 34: "GD_FLAGGED_34", 35: "ACONFIG_35_PLUS", 36: "ACONFIG_35_PLUS"}
        for sdk, era in eras.items():
            self.assertEqual(era, report.derive_stack_era(sdk))
        self.assertTrue(report.lineage_covers("PLATFORM_SYSTEM_BT", "LEGACY_GD_OPTIONAL_31_32"))
        self.assertFalse(report.lineage_covers("PLATFORM_SYSTEM_BT", "GD_CORE_33"))
        self.assertTrue(report.lineage_covers("BLUETOOTH_MODULE", "ACONFIG_35_PLUS"))
        self.assertTrue(report.lineage_covers("GD_FLAGGED_34", "GD_FLAGGED_34"))
        self.assertFalse(report.lineage_covers("GD_FLAGGED_34", "GD_CORE_33"))
        matcher = dict(REGISTRY["entries"][0]["matcher"], patch_min="2024-05-01")
        traits = report.complete_traits({"sdk": 34})
        self.assertFalse(report.matcher_matches(matcher, traits), "an unread patch level never satisfies a constraint")

    def test_markdown_lists_every_entry_its_sources_and_gaps(self):
        code, out = run()
        self.assertEqual(0, code)
        for entry in REGISTRY["entries"]:
            self.assertIn(entry["id"], out)
        self.assertIn("## Gaps", out)
        if any(e["source_status"] == "EVIDENCE_ONLY" for e in REGISTRY["entries"]):
            self.assertIn("evidence-only, needs source", out)
        code, gaps = run("--section", "gaps", "--format", "text")
        self.assertEqual(0, code)
        self.assertNotIn("| Entry |", gaps)

    def test_check_reports_a_drifted_export(self):
        broken = copy.deepcopy(REGISTRY)
        broken["known_devices"][0]["active"] = ["not-a-real-entry"]
        broken["entries"][0]["sources"] = []
        broken["entries"][0]["source_status"] = "OFFICIAL"
        problems = report.check(broken)
        self.assertTrue(any("Kotlin exported" in p for p in problems), problems)
        self.assertTrue(any("OFFICIAL without sources" in p for p in problems), problems)

    def test_log_lines_are_parsed(self):
        line = ("10-09 12:00:00.000 1 2 I BluetoothVideo: BENCHMARK device_policy: revision=r | traits=a=b;c=d | "
                "active=x,y | componentRules=none | switchedOff=none | matchingProposals=p | entries=3")
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "log.txt"
            log.write_text("noise\n" + line + "\n", encoding="utf-8")
            code, out = run("--log", str(log))
        self.assertEqual(0, code)
        record = json.loads(out)[0]
        self.assertEqual(["x", "y"], record["active"])
        self.assertEqual([], record["switchedOff"])
        self.assertEqual(["p"], record["matchingProposals"])

    def test_traits_cli_prints_the_resolution(self):
        code, out = run("--traits", "hardware=mt6765,board=mt6765,sdk=29,manufacturer=OPPO")
        self.assertEqual(0, code)
        result = json.loads(out)
        self.assertIn("gatt-context-map-race", result["active"])


if __name__ == "__main__":
    unittest.main()
