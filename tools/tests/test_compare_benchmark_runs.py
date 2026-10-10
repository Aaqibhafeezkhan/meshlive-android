"""Case-by-case run regression comparison over tiny synthetic run trees; never touches phones or real runs."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import compare_benchmark_runs as compare


def gate(value):
    return {"status": "UNMEASURED", "value": None} if value is None else {"status": "PASS", "value": value}


def receiver(device, fps=15.0, p95=70.0, p99=90.0, freezes=0, audio=0, first=1800.0):
    return {"device": device, "role": "receiver", "gates": {
        "visible_fps": gate(fps), "surface_p95_ms": gate(p95), "surface_p99_ms": gate(p99),
        "unexpected_freeze_events": gate(freezes), "audio_drops": gate(audio),
        "selection_to_first_surface_ms": gate(first), "decoder_drops": gate(0)}}


def broadcast(case_id, source="m31", receivers=("asus",), tunables=None):
    case = {"id": case_id, "source": source, "transport": "gatt", "duration_s": 30,
            "receivers": [{"phone": phone, "parent": source} for phone in receivers]}
    return {**case, "tunables": tunables} if tunables else case


NODES = {"s24": "f0e1d2c3b4a5", "m31": "9c8d7e6f5a40", "asus": "a1b2c3d4e5f6"}


def child_link(child, kbps, transport="BLE L2CAP CoC - Experimental"):
    return f"x I BluetoothVideo: Nearby device [{NODES[child]}]: Child measured this {transport} link at {kbps} kbps\n"


def rate(*sent, requested=600, recovered=False):
    text = "".join(f"x I BluetoothVideo: Rate adaptation: Link limited: sending {kbps} of {requested} kbps video\n"
                   for kbps in sent)
    return text + (f"x I BluetoothVideo: Rate adaptation: Link recovered: sending the selected {requested} kbps video\n"
                   if recovered else "")


def call(case_id, caller="asus", callee="m31"):
    return {"id": case_id, "caller": caller, "callee": callee, "transport": "gatt", "duration_s": 30}


class CompareBenchmarkRunsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.catalog = self.root / "benchmarks/catalog"
        self.runs = self.root / "benchmarks/runs"

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value), encoding="utf-8")
        return path

    def bind(self, name, pairs, embedded=False):
        """A bound suite; pairs map raw id -> original id, recorded beside it or embedded in it."""
        bindings = [{"case_id": raw, "original_case_id": original} for raw, original in pairs.items()]
        suite = self.catalog / name / "raw-suite.json"
        if embedded:
            self.write(suite, {"schema_version": 1, "cases": [], "input_binding": {"bindings": bindings}})
        else:
            self.write(suite, {"schema_version": 1, "cases": []})
            self.write(suite.parent / "binding-manifest.json", {"schema": "benchmark-input-binding-v1",
                                                                "bindings": bindings})
        return suite

    def make_run(self, name, cases, apk="a"):
        """cases: list of dicts with case, suite, devices (score devices), logs {name: text}, vmaf {direction: mean}."""
        run = self.runs / name
        jobs = []
        for index, item in enumerate(cases):
            case = item["case"]
            mode = "private_call" if "caller" in case else "broadcast"
            jobs.append({"case": case, "mode": mode, "suite": str(item.get("suite", self.catalog / "suite.json"))})
            directory = run / f"{index + 1:04d}-{hashlib.sha256(case['id'].encode()).hexdigest()[:12]}"
            directory.mkdir(parents=True)
            if item.get("attempt_only"):
                self.write(directory / "attempt.json", {"status": "FAIL"})
                continue
            logs = item.get("logs", {})
            self.write(directory / "physical/manifest.json", {
                "scenario": case, "mode": mode, "devices": {name: {"node_prefix": node} for name, node in NODES.items()},
                "logs": [{"device": log.split("-")[0], "path": log} for log in logs if "-bluetooth-" not in log]})
            for log, text in logs.items():
                self.write(directory / "physical" / log, text)
            self.write(directory / "physical/score.json", {"devices": item["devices"]})
            for direction, mean in item.get("vmaf", {}).items():
                self.write(directory / "analysis/picture" / direction / "vmaf.json",
                           {"pooled_metrics": {"vmaf": {"mean": mean, "min": 0.0}}})
        self.write(run / "plan.json", {"state": "NOT RUN", "jobs": jobs})
        self.write(run / "execution.json", {"frozen_apk_sha256": apk * 64})
        return run

    def comparison(self, *runs):
        output = self.root / "scratch/result.json"
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = compare.main([*map(str, runs), "--json", str(output)])
        return code, stdout.getvalue(), json.loads(output.read_text(encoding="utf-8"))["comparisons"]

    def test_bound_cases_match_by_original_id_and_regressions_are_listed_first(self):
        suite = self.bind("bound", {"raw-" + "1" * 24: "pair-gatt", "raw-" + "2" * 24: "pair-gatt",
                                    "raw-" + "3" * 24: "steady", "raw-" + "4" * 24: "steady"})
        sender = "x I BluetoothVideo: Encoder output: 15.0 FPS, 600 kbps, PTS\ny Rate adaptation: Link limited\n"
        baseline = self.make_run("base", [
            {"case": broadcast("raw-" + "1" * 24), "suite": suite, "devices": [receiver("asus")],
             "vmaf": {"m31-to-asus": 88.0}, "logs": {"m31-1.log": sender}},
            {"case": broadcast("raw-" + "3" * 24, source="s24", receivers=("m31",)), "suite": suite,
             "devices": [receiver("m31")], "vmaf": {"s24-to-m31": 90.0}}])
        candidate = self.make_run("cand", [
            {"case": broadcast("raw-" + "4" * 24, source="s24", receivers=("m31",)), "suite": suite,
             "devices": [receiver("m31", fps=14.5, p99=180.0)], "vmaf": {"s24-to-m31": 89.0}},
            {"case": broadcast("raw-" + "2" * 24), "suite": suite,
             "devices": [receiver("asus", fps=13.0, freezes=1, audio=2)],
             "vmaf": {"m31-to-asus": 88.5}, "logs": {"m31-1.log": sender * 2}}], apk="b")
        code, text, [result] = self.comparison(baseline, candidate)
        self.assertEqual(2, code)
        self.assertEqual({"REGRESSED": 1, "SAME": 1}, result["counts"])
        regressed, steady = result["cases"]
        self.assertEqual(("pair-gatt", "REGRESSED"), (regressed["key"], regressed["state"]))
        states = {row["metric"]: row["state"] for row in regressed["directions"][0]["metrics"]}
        self.assertEqual({"visible_fps": "REGRESSED", "unexpected_freeze_events": "REGRESSED",
                          "audio_drops": "REGRESSED", "vmaf": "SAME", "surface_p95_ms": "SAME",
                          "surface_p99_ms": "SAME", "first_surface_ms": "SAME"}, states)
        self.assertEqual("m31-to-asus", regressed["directions"][0]["direction"])
        windows = regressed["senders"]["m31"]["candidate"]
        self.assertEqual(([[[15.0, 600.0], [15.0, 600.0]]], [2]), (windows["encoder_windows"], windows["rate_adaptations"]))
        self.assertEqual("SAME", steady["state"])  # 0.5 FPS, +90 ms p99 and -1.0 VMAF stay inside the bands.
        summary = text[text.index("SUMMARY"):]
        self.assertLess(summary.index("REGRESSED (1)"), summary.index("SAME (1)"))
        self.assertIn("m31-to-asus visible FPS 15 -> 13 REGRESSED", summary)

    def test_tunables_keep_an_experiment_apart_from_its_parent(self):
        suite = self.bind("bound", {"raw-" + "1" * 24: "pair", "raw-" + "2" * 24: "pair"})
        baseline = self.make_run("base", [{"case": broadcast("raw-" + "1" * 24), "suite": suite, "devices": [receiver("asus")]}])
        candidate = self.make_run("cand", [{"case": broadcast("raw-" + "2" * 24, tunables={"keyframe_shaping": 0.33}),
                                       "suite": suite, "devices": [receiver("asus")]}])
        code, text, [result] = self.comparison(baseline, candidate)
        self.assertEqual(2, code)  # A dropped case is an alarm: it could hide a regression.
        self.assertEqual({"MISSING": 1, "NEW": 1}, result["counts"])
        self.assertIn('pair tunables={"keyframe_shaping":0.33}', [case["key"] for case in result["cases"]])
        self.assertIn("MISSING (executed only in baseline) (1):\n  pair [raw-", text)

    def test_repeats_are_pooled_and_an_unmeasured_repeat_is_lost(self):
        suite = self.bind("bound", {"raw-" + "1" * 24: "pair", "raw-" + "2" * 24: "pair"})
        baseline = self.make_run("base", [
            {"case": broadcast("raw-" + "1" * 24), "suite": suite, "devices": [receiver("asus", fps=15.0)]},
            {"case": broadcast("raw-" + "2" * 24), "suite": suite, "devices": [receiver("asus", fps=14.0)]}])
        first = self.make_run("cand-r1", [{"case": broadcast("raw-" + "1" * 24), "suite": suite,
                                      "devices": [receiver("asus", fps=14.6)]}])
        never_connected = receiver("asus", fps=None, p95=None, p99=None, freezes=None, audio=None, first=None)
        second = self.make_run("cand-r2", [{"case": broadcast("raw-" + "1" * 24), "suite": suite,
                                       "devices": [never_connected]}])
        code, text, [result] = self.comparison(baseline, f"{first}+{second}")
        self.assertEqual(2, code)
        case = result["cases"][0]
        self.assertEqual(("LOST", "cand-r1+cand-r2"), (case["state"], result["candidate"]))
        fps = case["directions"][0]["metrics"][0]
        self.assertEqual(([15.0, 14.0], 14.5), (fps["baseline"]["samples"], fps["baseline"]["mean"]))
        self.assertEqual(([14.6, None], "LOST"), (fps["candidate"]["samples"], fps["state"]))
        self.assertIn("14.50 (15, 14) -> 14.60 (14.60, -)", text)
        self.assertEqual(2, len(case["candidate_executions"]))

    def test_receiver_that_never_connected_or_scored_is_lost_not_hidden(self):
        baseline = self.make_run("base", [{"case": broadcast("pair"), "devices": [receiver("asus")], "vmaf": {"m31-to-asus": 88.0}},
                                     {"case": broadcast("other"), "devices": [receiver("asus")]}])
        candidate = self.make_run("cand", [{"case": broadcast("pair"), "devices": [
            receiver("asus", fps=None, p95=None, p99=None, freezes=None, audio=None, first=None)]},
            {"case": broadcast("other"), "attempt_only": True}])
        code, text, [result] = self.comparison(baseline, candidate)
        self.assertEqual((2, {"LOST": 2}), (code, result["counts"]))
        for case in result["cases"]:
            self.assertTrue(all(row["state"] == "LOST" for row in case["directions"][0]["metrics"]))
        attempt = next(case for case in result["cases"] if case["key"] == "other")
        self.assertEqual(["NO SCORE"], [item["status"] for item in attempt["candidate_executions"]])
        self.assertIn("m31-to-asus VMAF mean 88 -> - (measured only in baseline) LOST", text)

    def test_private_call_directions_use_incoming_gates_and_application_logs(self):
        def devices(asus_fps, m31_fps):
            return [{"device": "m31", "setup_role": "callee", "peer": "asus",
                     "gates": {"setup_to_first_surface_ms": gate(3500.0)},
                     "incoming": {"gates": {"visible_fps": gate(m31_fps), "unexpected_freeze_events": gate(0)}},
                     "effective_profiles": [{"bitrateKbps": "170"}]},
                    {"device": "asus", "setup_role": "caller", "peer": "m31",
                     "gates": {"setup_to_first_surface_ms": gate(4000.0)},
                     "incoming": {"gates": {"visible_fps": gate(asus_fps), "audio_drops": gate(None)}},
                     "effective_profiles": [{"bitrateKbps": "140"}]}]

        def logs(m31_frames, asus_kbps):
            final = "x BluetoothVideo: Private call visible continuity FINAL: {} frames | {} freeze event(s), {} ms, 0.00% | threshold 250.0 ms\n"
            return {"m31-10.log": final.format(m31_frames, 0, "0.0") + "Encoder output: 15.0 FPS, 180 kbps,\n",
                    "asus-20.log": "x BENCHMARK call effective: encoder=e | codec=2 | bitrateKbps={} | fps=15\n".format(asus_kbps)
                                   + final.format(455, 1, "300.0")
                                   + "Audio drop reasons: late encoded 1, encoded queue 0, PCM queue 2, late startup 0, partial write 0\n",
                    "asus-bluetooth-30.log": final.format(1, 9, "9999.0")}

        baseline = self.make_run("base", [{"case": call("call-pair"), "devices": devices(15.0, 15.0), "logs": logs(466, 150)}])
        candidate = self.make_run("cand", [{"case": call("call-pair"), "devices": devices(14.9, 15.0), "logs": logs(430, 120)}])
        code, _, [result] = self.comparison(baseline, candidate)
        directions = {item["direction"]: {row["metric"]: row for row in item["metrics"]} for item in result["cases"][0]["directions"]}
        incoming = directions["asus-to-m31"]
        self.assertEqual((466.0, 430.0, "REGRESSED"), (incoming["call_frames"]["baseline"]["mean"],
                                                       incoming["call_frames"]["candidate"]["mean"], incoming["call_frames"]["state"]))
        self.assertEqual((150.0, 120.0, "REGRESSED"), (incoming["effective_kbps"]["baseline"]["mean"],
                                                       incoming["effective_kbps"]["candidate"]["mean"], incoming["effective_kbps"]["state"]))
        self.assertEqual(3500.0, incoming["first_surface_ms"]["baseline"]["mean"])
        outgoing = directions["m31-to-asus"]  # Stack-log continuity lines are ignored.
        self.assertEqual((455.0, 1.0, 300.0), tuple(outgoing[name]["baseline"]["mean"]
                                                    for name in ("call_frames", "call_freezes", "call_freeze_ms")))
        self.assertEqual((3.0, 170.0, "SAME"), (outgoing["audio_drops"]["baseline"]["mean"],
                                                outgoing["effective_kbps"]["baseline"]["mean"], outgoing["visible_fps"]["state"]))
        self.assertEqual(("REGRESSED", 2), (result["cases"][0]["state"], code))

    def test_original_ids_resolve_from_embedded_bindings_and_catalog_and_flag_unresolved(self):
        embedded = self.bind("embedded", {"raw-" + "1" * 24: "embedded-case"}, embedded=True)
        self.bind("elsewhere", {"raw-" + "2" * 24: "catalog-case"})
        moved = self.catalog / "moved/raw-suite.json"  # Recorded suite path no longer present.
        cases = [{"case": broadcast("raw-" + "1" * 24), "suite": embedded, "devices": [receiver("asus")]},
                 {"case": broadcast("raw-" + "2" * 24), "suite": moved, "devices": [receiver("asus")]},
                 {"case": broadcast("raw-" + "9" * 24), "suite": moved, "devices": [receiver("asus")]}]
        code, text, [result] = self.comparison(self.make_run("base", cases), self.make_run("cand", cases))
        self.assertEqual(0, code)
        self.assertEqual(["catalog-case", "embedded-case", "raw-" + "9" * 24], sorted(case["key"] for case in result["cases"]))
        self.assertIn("raw-" + "9" * 24 + " [original id UNRESOLVED]", text)

    def test_regression_on_a_slower_link_is_listed_after_plain_regressions(self):
        baseline = self.make_run("base", [
            {"case": broadcast("coc-600", source="asus", receivers=("m31",)), "devices": [receiver("m31")],
             "vmaf": {"asus-to-m31": 87.4}, "logs": {"asus-1.log": child_link("m31", 660)}},
            {"case": broadcast("gatt-900", source="asus", receivers=("m31",)), "devices": [receiver("m31")],
             "logs": {"asus-1.log": child_link("m31", 820) + rate(560, requested=900)}}])
        candidate = self.make_run("cand", [
            {"case": broadcast("coc-600", source="asus", receivers=("m31",)), "devices": [receiver("m31", fps=13.3)],
             "vmaf": {"asus-to-m31": 83.2},
             "logs": {"asus-1.log": child_link("m31", 461) + rate(359, 419) + child_link("m31", 535)}},
            {"case": broadcast("gatt-900", source="asus", receivers=("m31",)), "devices": [receiver("m31", freezes=2)],
             "logs": {"asus-1.log": child_link("m31", 800) + rate(500, requested=900, recovered=True)}}])
        code, text, [result] = self.comparison(baseline, candidate)
        self.assertEqual((2, {"REGRESSED": 1, "REGRESSED_SLOWER_LINK": 1}), (code, result["counts"]))
        plain, slower = result["cases"]  # Plain code regressions sort first.
        self.assertEqual(("gatt-900", "REGRESSED"), (plain["key"], plain["directions"][0]["state"]))
        self.assertNotIn("slower_link", plain["directions"][0])  # 820 -> 800 kbps and 560 -> 500 kbps stay inside 20%.
        self.assertEqual([True], plain["directions"][0]["link"]["candidate"]["ended_recovered"])
        direction = slower["directions"][0]
        self.assertEqual(("coc-600", "REGRESSED_SLOWER_LINK", "link -25%, rate cut to 359 kbps; baseline never cut"),
                         (slower["key"], direction["state"], direction["slower_link"]))
        link = direction["link"]["candidate"]
        self.assertEqual((498.0, 461, [2], 359, 600, [False]),
                         (link["measured_median_kbps"], link["measured_min_kbps"], link["limited_lines"],
                          link["lowest_sent_kbps"], link["requested_kbps"], link["ended_recovered"]))
        self.assertEqual("REGRESSED", direction["metrics"][0]["state"])
        summary = text[text.index("SUMMARY"):]
        self.assertLess(summary.index("REGRESSED (1):\n  gatt-900"),
                        summary.index("REGRESSED WITH SLOWER LINK (repeat to confirm) (1):\n  coc-600"))
        self.assertIn("asus-to-m31 VMAF mean 87.40 -> 83.20 REGRESSED (link -25%, rate cut to 359 kbps; baseline never cut)", summary)
        self.assertIn("link (info): measured median 660 min 660 kbps, 0 rate limits -> measured median 498 min 461 kbps, "
                      "2 rate limits (lowest 359 of 600), ended limited", text)

    def test_measured_link_follows_the_child_node_through_a_relay(self):
        def chain(relay_kbps):
            return {"case": broadcast("chain", source="s24", receivers=("m31", "asus")),
                    "devices": [receiver("m31"), receiver("asus", fps=15.0 if relay_kbps > 500 else 12.0)],
                    "logs": {"s24-1.log": child_link("m31", 700, "BLE GATT") + rate(250, requested=300),
                             "m31-2.log": child_link("asus", relay_kbps, "BLE GATT"),
                             "asus-bluetooth-3.log": child_link("asus", 1)}}

        code, _, [result] = self.comparison(self.make_run("base", [chain(600)]), self.make_run("cand", [chain(400)]))
        directions = {item["direction"]: item for item in result["cases"][0]["directions"]}
        self.assertEqual([[600]], directions["s24-to-asus"]["link"]["baseline"]["measured_kbps"])
        self.assertEqual([[700]], directions["s24-to-m31"]["link"]["candidate"]["measured_kbps"])
        self.assertEqual(250, directions["s24-to-asus"]["link"]["candidate"]["lowest_sent_kbps"])  # Original sender's rate.
        self.assertEqual(("REGRESSED_SLOWER_LINK", "link -33%"),
                         (directions["s24-to-asus"]["state"], directions["s24-to-asus"]["slower_link"]))
        self.assertEqual(("SAME", 2), (directions["s24-to-m31"]["state"], code))

    def test_noise_bands_are_inclusive_and_startup_band_scales(self):
        self.assertEqual("SAME", compare.classify("visible_fps", 15.0, 14.3))
        self.assertEqual("REGRESSED", compare.classify("visible_fps", 15.0, 14.29))
        self.assertEqual("REGRESSED", compare.classify("unexpected_freeze_events", 0, 1))
        self.assertEqual("IMPROVED", compare.classify("audio_drops", 3, 2))
        self.assertEqual("SAME", compare.classify("first_surface_ms", 8000.0, 9900.0))
        self.assertEqual("REGRESSED", compare.classify("first_surface_ms", 2000.0, 3100.0))
        self.assertEqual("REGRESSED", compare.classify("vmaf", 88.0, 86.4))

    def test_runs_are_read_only(self):
        baseline = self.make_run("base", [{"case": broadcast("pair"), "devices": [receiver("asus")]}])
        candidate = self.make_run("cand", [{"case": broadcast("pair"), "devices": [receiver("asus", fps=12.0)]}])
        before = {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(2, compare.main([str(baseline), str(candidate)]))
        self.assertEqual(before, {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()})
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            compare.main([str(baseline), str(self.runs / "absent")])


if __name__ == "__main__":
    unittest.main()
