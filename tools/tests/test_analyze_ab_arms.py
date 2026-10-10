"""Bounded-age vs no-loss arm analysis on synthetic run folders; offline, no phone operations."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import analyze_ab_arms as ab

PREFIX = "10-09 04:16:50.000 100 200 I BluetoothVideo: "


def scenario(bounded, caller="m31", callee="asus", transport="gatt", kbps=600, case_id="x"):
    return {"id": case_id, "caller": caller, "callee": callee, "transport": transport, "duration_s": 45,
            "profiles": {caller: {"bitrate_kbps": kbps, "fps": 15}, callee: {"bitrate_kbps": kbps, "fps": 15}},
            "tunables": {"bounded_media_age": bounded}, "state": "NOT RUN", "eligibility": "x", "environment": "y"}


def phone_log(policy, tx_ages, held_ages, longest_stall, frozen_pct, freezes, shed=0, pause=0, keyframes=0,
              rate_steps=(), audio_drops=0):
    lines = [f"{PREFIX}BENCHMARK media_age_policy: policy={policy}"]
    for age in tx_ages:
        lines.append(
            f"{PREFIX}BENCHMARK media_age: role=call_tx | callId=c | final=false | policy={policy} | "
            f"videoQueueAgeMaxMs={age} | audioQueueAgeMaxMs=5 | videoQueueAgeNowMs=0 | peerAddedDelayMs=0 | "
            f"budgetMs=100 | shedEvents={shed} | shedFrames={shed * 3} | thinned=0 | videoPaused=false | "
            f"pauseEvents={pause} | pausedMs=0 | pausedFrames=0 | criticalOverflowDrops=0")
    for held in held_ages:
        lines.append(
            f"{PREFIX}BENCHMARK media_age: role=call_rx | callId=c | final=false | policy={policy} | "
            f"playoutDelayMs=500 | addedDelayMs=0 | lateMaxMs=0 | heldAgeMaxMs={held} | videoPackets=1 | audioPackets=1")
    for frames in (1, 80):
        lines.append(
            f"{PREFIX}BENCHMARK continuity: role=call_rx | final=false | policy={policy} | frames={frames} | "
            f"freezeEvents={freezes} | frozenMs=10.0 | frozenPct={frozen_pct:.2f} | "
            f"longestStallMs={longest_stall:.1f} | activeMs=5000.0")
    lines.append(f"{PREFIX}BENCHMARK call rx: callId=c | final=true | audioDrops={audio_drops} | decoderDrops=0 | "
                 f"videoPackets=1 | audioPackets=1")
    lines += [f"{PREFIX}Call camera: Requested a recovery keyframe"] * keyframes
    lines += [f"{PREFIX}Call rate adaptation: sending {sending} of 600 kbps video at 15 FPS (backlog 0 ms)"
              for sending in rate_steps]
    return "\n".join(lines) + "\n"


class AnalyzeArmsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ab-arms-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write_case(self, run, index, sc, logs=None, cleanup="CONFIRMED", blocked=None, apk="a" * 64):
        case = self.root / run / f"{index:04d}-case"
        (case / "physical").mkdir(parents=True)
        if blocked:
            (case / "attempt.json").write_text(json.dumps(
                {"case_id": sc["id"], "scenario": sc, "status": "BLOCKED", "reason": blocked}), encoding="utf-8")
            return case
        manifest_scenario = {**sc, "expected_apk_sha256": apk}
        (case / "physical" / "manifest.json").write_text(json.dumps({
            "scenario": manifest_scenario, "devices": {
                sc["caller"]: {"apk_sha256": apk, "node_prefix": "aa"},
                sc["callee"]: {"apk_sha256": apk, "node_prefix": "bb"}},
            "phone_cleanup": {"status": cleanup}}), encoding="utf-8")
        (case / "cleanup.json").write_text(json.dumps({"status": cleanup}), encoding="utf-8")
        devices = []
        for phone, text in (logs or {}).items():
            (case / "physical" / f"{phone}-1.log").write_text(text, encoding="utf-8")
            devices.append({"device": phone, "path": f"{phone}-1.log", "incoming": {
                "gates": {"visible_fps": {"value": 14.5}}, "freezes": {"observed": 0},
                "freeze_duration_ms": {"observed": 0.0}}})
        (case / "physical" / "score.json").write_text(json.dumps(
            {"overall_status": "FAIL", "devices": devices}), encoding="utf-8")
        return case

    def arm_logs(self, bounded, tx, held, stall, frozen, freezes, **kw):
        policy = "bounded-experimental" if bounded else "noloss"
        log = phone_log(policy, tx, held, stall, frozen, freezes, **kw)
        return {"m31": log, "asus": log}

    def test_parse_log_formats(self):
        fields = ab.parse_fields("role=call_tx | final=false | videoQueueAgeMaxMs=21 | frozenPct=0.55")
        self.assertEqual(fields["role"], "call_tx")
        self.assertEqual(fields["videoQueueAgeMaxMs"], 21)
        self.assertEqual(fields["frozenPct"], 0.55)
        self.assertEqual(ab.percentile([1, 2, 3, 4], 0.5), 2.5)

    def test_pairing_ignores_id_apk_and_arm_only(self):
        a, b = scenario(True, case_id="one"), scenario(False, case_id="two")
        self.assertEqual(ab.request_key(a), ab.request_key({**b, "expected_apk_sha256": "f" * 64}))
        self.assertNotEqual(ab.request_key(a), ab.request_key(scenario(False, kbps=300)))
        self.assertNotEqual(ab.request_key(a), ab.request_key(scenario(False, transport="ble")))

    def test_single_repeat_is_never_conclusive_and_failures_stay_separate(self):
        self.write_case("r1", 1, scenario(True, case_id="b1"), self.arm_logs(
            True, [3, 40], [500, 700], 300.0, 1.5, 2, shed=2, pause=1, keyframes=3, rate_steps=(400, 200, 300)))
        self.write_case("r1", 2, scenario(False, case_id="n1"), self.arm_logs(
            False, [3, 900], [500, 1400], 900.0, 4.0, 5, audio_drops=1))
        self.write_case("r1", 3, scenario(False, transport="ble", case_id="n2"),
                        blocked="Another suite owns a participating physical phone")
        self.write_case("r1", 4, scenario(True, transport="ble", case_id="b2"), self.arm_logs(
            True, [1], [500], 80.0, 0.0, 0), cleanup="UNCONFIRMED")
        self.write_case("r1", 5, scenario(True, transport="gatt", kbps=300, case_id="b3"),
                        self.arm_logs(False, [1], [500], 80.0, 0.0, 0))  # logs say no-loss for a bounded request
        report = ab.analyze([self.root / "r1"])
        self.assertEqual(report["case_counts"], {"total": 5, "measured": 2, "excluded": 3})
        self.assertEqual(report["setup_failures"]["by_class"],
                         {"setup_failure": 1, "quarantined": 1, "arm_mismatch": 1})
        measured = [pair for pair in report["pairs"] if pair["repeats"]["bounded"] and pair["repeats"]["noloss"]]
        self.assertEqual(len(measured), 1)
        direction = measured[0]["directions"]["m31->asus"]
        stall = direction["rx_longest_stall_ms"]
        self.assertEqual((stall["bounded"]["mean"], stall["noloss"]["mean"], stall["delta"]), (300.0, 900.0, -600.0))
        self.assertIn("single run", stall["verdict"])
        self.assertIn("not conclusive", stall["verdict"])
        self.assertEqual(direction["tx_shed_events"]["bounded"]["mean"], 2)
        self.assertEqual(direction["tx_pause_events"]["bounded"]["mean"], 1)
        self.assertEqual(direction["tx_keyframe_requests"]["bounded"]["mean"], 3)
        self.assertEqual(direction["tx_rate_cuts"]["bounded"]["mean"], 2)  # 600 -> 400 -> 200 -> 300: two cuts
        self.assertEqual(direction["rx_audio_drops"]["noloss"]["mean"], 1)
        self.assertEqual(direction["rx_visible_fps"]["bounded"]["mean"], 14.5)
        verdict = report["metric_verdicts"]["rx_longest_stall_ms"]
        self.assertEqual(verdict["max_repeats"], 1)
        self.assertTrue(verdict["verdict"].startswith("NOT CONCLUSIVE"))

    def test_repeat_spread_separates_signal_from_noise(self):
        for run, bounded_stall, noloss_stall in (("r1", 300.0, 900.0), ("r2", 340.0, 1000.0), ("r3", 320.0, 880.0)):
            self.write_case(run, 1, scenario(True), self.arm_logs(True, [3], [500], bounded_stall, 1.0, 1))
            self.write_case(run, 2, scenario(False), self.arm_logs(False, [3], [500], noloss_stall, 1.0, 1))
        report = ab.analyze([self.root / run for run in ("r1", "r2", "r3")])
        self.assertEqual(len(report["pairs"]), 1)
        direction = report["pairs"][0]["directions"]["m31->asus"]
        stall = direction["rx_longest_stall_ms"]
        self.assertEqual(stall["verdict"], "bounded better")
        self.assertEqual(stall["confidence"], "consistent")
        self.assertEqual(direction["rx_frozen_pct"]["verdict"], "within noise")
        self.assertEqual(report["metric_verdicts"]["rx_longest_stall_ms"]["max_repeats"], 3)
        self.assertIn("received PCM", " ".join(report["caveats"]))

    def test_overlapping_ranges_are_noise_even_with_a_mean_gap(self):
        for run, bounded_stall, noloss_stall in (("r1", 300.0, 500.0), ("r2", 700.0, 400.0)):
            self.write_case(run, 1, scenario(True), self.arm_logs(True, [3], [500], bounded_stall, 1.0, 1))
            self.write_case(run, 2, scenario(False), self.arm_logs(False, [3], [500], noloss_stall, 1.0, 1))
        report = ab.analyze([self.root / "r1", self.root / "r2"])
        stall = report["pairs"][0]["directions"]["m31->asus"]["rx_longest_stall_ms"]
        self.assertEqual(stall["verdict"], "within noise")
        self.assertEqual(stall["confidence"], "suggestive")

    def test_different_builds_are_flagged(self):
        self.write_case("r1", 1, scenario(True), self.arm_logs(True, [3], [500], 100.0, 0.0, 0), apk="a" * 64)
        self.write_case("r1", 2, scenario(False), self.arm_logs(False, [3], [500], 100.0, 0.0, 0), apk="b" * 64)
        report = ab.analyze([self.root / "r1"])
        self.assertTrue(any("different APK" in caveat for caveat in report["caveats"]))

    def test_cli_writes_summary_and_per_pair_json(self):
        self.write_case("r1", 1, scenario(True), self.arm_logs(True, [3], [500], 100.0, 0.0, 0))
        self.write_case("r1", 2, scenario(False), self.arm_logs(False, [3], [500], 120.0, 0.0, 0))
        out = self.root / "out"
        stdout = sys.stdout
        try:
            with open(self.root / "stdout.txt", "w", encoding="utf-8") as sink:
                sys.stdout = sink
                self.assertEqual(ab.main([str(self.root / "r1"), "--out", str(out)]), 0)
        finally:
            sys.stdout = stdout
        summary = json.loads((out / "ab-arms-summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["schema"], ab.SCHEMA)
        self.assertEqual(len(list((out / "pairs").glob("*.json"))), 1)
        self.assertIn("single repeat", (out / "ab-arms-summary.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
