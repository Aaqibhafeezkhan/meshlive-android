import contextlib
import copy
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from tools.score_impairment_trace import PREFIX, SCHEMA, analyze, main, validate_config


class ImpairmentTraceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.config = {"schema":SCHEMA,"seed":7,"max_wait_ms":300,"phases":[{
            "id":"motion","start_ms":0,"end_ms":1000,"delay_ms":20,"jitter_ms":0,
            "rate_bps":0,"drop_every":0,"burst_every":0,"burst_length":0}]}
        self.identity = {"scope":"phone-run","session":"exact-case-source","pid":2345,"clock":"System.nanoTime"}
        self.spec = {"session":self.identity["session"],"scope":self.identity["scope"],"pid":2345,"maximum_offer_gap_ms":110}
        self.save_config()
        self.make_trace()

    def entry(self,name):
        return {"path":name,"sha256":hashlib.sha256((self.base/name).read_bytes()).hexdigest()}

    def save_config(self):
        (self.base/"config.json").write_text(json.dumps(self.config),encoding="utf-8")
        self.spec["config"] = self.entry("config.json")

    def make_trace(self, actual_shift=0, finish=True):
        """Independent finite execution fixture, not a scorer/planner call."""
        phase = self.config["phases"][0]
        config_hash = self.spec["config"]["sha256"]
        self.rows = [{**self.identity,"kind":"BEGIN","schema":SCHEMA,"config_sha256":config_hash,
                     "position":"outgoing-whole-media-before-encryption","producer_blocking":True,"radio_impairment":False}]
        counts = {"offered":0,"sent":0,"bypassed":0,"dropped":0,"send_failed":0,"in_flight":0}
        previous, origin = -1, 2_000_000_000
        for index in range(1,11):
            offered = origin+(index-1)*100_000_000
            packet = {**self.identity,"sequence":index,"flags":0,"pts_us":(index-1)*100_000,"bytes":100,"offered_ns":offered}
            self.rows.append({**packet,"kind":"OFFER","eligible":True})
            noise = (self.config["seed"]+index*1103515245)%2147483647
            jitter = noise%(2*phase["jitter_ms"]+1)-phase["jitter_ms"] if phase["jitter_ms"] else 0
            drop = phase["drop_every"] and index%phase["drop_every"] == 0 or phase["burst_every"] and (index-1)%phase["burst_every"] < phase["burst_length"]
            service = (100*8*1_000_000_000+phase["rate_bps"]-1)//phase["rate_bps"] if phase["rate_bps"] else 0
            target = None if drop else max(offered+max(0,phase["delay_ms"]+jitter)*1_000_000,previous)+service
            decision = "DROP_PATTERN" if drop else "DROP_MAX_WAIT" if target-offered > self.config["max_wait_ms"]*1_000_000 else "SEND"
            actual = target+actual_shift if decision == "SEND" else None
            self.rows.append({**packet,"kind":"RESULT","decision":decision,"planned_release_ns":target,"actual_release_ns":actual,
                "completed_ns":max(offered,actual or 0),"phase":"motion","phase_index":index,"plan_index":index,
                "media_index":index,"jitter_ms":jitter,"plan_decision":decision})
            if decision == "SEND":
                previous = max(target,actual)
                counts["sent"] += 1
            else:
                counts["dropped"] += 1
            counts["offered"] += 1
        if finish:
            self.rows.append({**self.identity,"kind":"FINAL","schema":SCHEMA,"config_sha256":config_hash,
                              "origin_ns":origin,"closed_ns":origin+1_000_000_000,**counts})
        self.save_trace()

    def save_trace(self):
        (self.base/"trace.log").write_text("\n".join("I BluetoothVideo: "+PREFIX+json.dumps(row) for row in self.rows)+"\n",encoding="utf-8")
        self.spec["log"] = self.entry("trace.log")

    def score(self):
        return analyze(self.spec,self.base)

    def test_actual_finalized_trace_measures_delay_load_and_whole_profile(self):
        result = self.score()
        self.assertEqual("MEASURED",result["measurement_status"])
        self.assertEqual("PASS",result["verdict"])
        self.assertEqual(20,result["phases"][0]["delay_ms"]["p50"])
        self.assertEqual(8000,result["phases"][0]["offered_payload_bps"])
        self.assertEqual({"SEND":10},result["counts"])
        self.assertTrue(any("not Bluetooth radio" in text for text in result["limitations"]))

    def test_periodic_and_burst_loss_validate_expected_simulation_not_stream_quality(self):
        self.config["phases"][0].update(drop_every=4,burst_every=6,burst_length=2)
        self.save_config()
        self.make_trace()
        result = self.score()
        self.assertEqual("MEASURED",result["measurement_status"])
        self.assertEqual(5,result["counts"]["DROP_PATTERN"])
        self.assertEqual("PASS",result["verdict"])
        self.assertTrue(any("does not pass zero" in text for text in result["limitations"]))

    def test_seeded_jitter_and_byte_rate_recompute_against_actual_releases(self):
        self.config["phases"][0].update(jitter_ms=10,rate_bps=16000)
        self.save_config()
        self.make_trace(actual_shift=50_000)
        result = self.score()
        self.assertEqual("PASS",result["verdict"])
        self.assertGreater(result["phases"][0]["delay_ms"]["p50"],50)
        self.assertEqual([],result["plan_mismatches"])

    def test_excessive_rate_demand_has_explicit_whole_media_maxwait_drop(self):
        self.config["phases"][0].update(rate_bps=1000)
        self.save_config()
        self.make_trace()
        result = self.score()
        self.assertEqual("PASS",result["verdict"])
        self.assertEqual({"DROP_MAX_WAIT":10},result["counts"])
        self.assertIsNone(result["phases"][0]["delay_ms"]["p50"])

    def test_multiple_phases_use_new_indices_and_unimpaired_gap_without_reordering(self):
        self.config["phases"][0]["end_ms"] = 500
        self.config["phases"].append(self.config["phases"][0]|{"id":"loss","start_ms":600,"end_ms":1000,"delay_ms":0,"drop_every":2})
        self.save_config()
        sha = self.spec["config"]["sha256"]
        self.rows[0]["config_sha256"] = self.rows[-1]["config_sha256"] = sha
        for row in self.rows:
            if row["kind"] != "RESULT" or row["sequence"] <= 5:
                continue
            index = row["sequence"]
            row["phase"] = None if index == 6 else "loss"
            row["phase_index"] = None if index == 6 else index-6
            drop = index in (8,10)
            row["decision"] = row["plan_decision"] = "DROP_PATTERN" if drop else "SEND"
            row["actual_release_ns"] = row["planned_release_ns"] = None if drop else row["offered_ns"]
            row["completed_ns"] = row["offered_ns"]
        self.rows[-1].update(sent=8,dropped=2)
        self.save_trace()
        result = self.score()
        self.assertEqual("PASS",result["verdict"])
        self.assertEqual([5,4],[phase["offered_media"] for phase in result["phases"]])
        self.assertEqual(2,result["phases"][1]["decisions"]["DROP_PATTERN"])

    def test_actual_order_violation_and_pattern_drop_substitution_are_measured_failures(self):
        original = copy.deepcopy(self.rows)
        # An incorrectly released later frame must not hide behind its own PTS.
        self.rows[2]["actual_release_ns"] = self.rows[2]["completed_ns"] = 2_150_000_000
        self.save_trace()
        result = self.score()
        self.assertEqual("FAIL",result["verdict"])
        self.assertTrue(any(row["field"] == "actual_order" for row in result["plan_mismatches"]))
        self.rows = original
        self.config["phases"][0]["drop_every"] = 2
        self.save_config()
        self.make_trace()
        self.rows[4]["decision"] = "DROP_MAX_WAIT"
        self.save_trace()
        self.assertEqual("FAIL",self.score()["verdict"])

    def test_missing_final_missing_offer_duplicate_sequence_and_identity_are_unmeasured(self):
        original = copy.deepcopy(self.rows)
        for change in ("final","offer","duplicate","identity"):
            with self.subTest(change=change):
                self.rows = copy.deepcopy(original)
                if change == "final": self.rows.pop()
                elif change == "offer": self.rows.pop(1)
                elif change == "duplicate": self.rows.insert(2,copy.deepcopy(self.rows[1]))
                else: self.rows[2]["pid"] = 999
                self.save_trace()
                self.assertEqual("UNMEASURED",self.score()["verdict"])

    def test_generated_config_alone_cannot_pass_execution(self):
        self.rows = [self.rows[0],self.rows[-1]]
        self.save_trace()
        result = self.score()
        self.assertEqual("UNMEASURED",result["measurement_status"])
        self.assertIn("missing/unmatched",result["measurements"][-1]["reason"])

    def test_missing_terminal_tail_or_internal_offers_do_not_pass_profile_coverage(self):
        original = copy.deepcopy(self.rows)
        for mode in ("tail","gap"):
            with self.subTest(mode=mode):
                self.rows = copy.deepcopy(original)
                if mode == "tail":
                    self.rows[-1]["closed_ns"] = 2_950_000_000
                else:
                    # A complete trace honestly records producer starvation by
                    # changing offered times; no rows/counters are discarded.
                    for row in self.rows:
                        if row.get("sequence",0) == 5:
                            row["offered_ns"] = 2_450_000_000
                            if row["kind"] == "RESULT":
                                row["planned_release_ns"] = row["actual_release_ns"] = row["completed_ns"] = 2_470_000_000
                self.save_trace()
                result = self.score()
                self.assertEqual("UNMEASURED",result["verdict"])
                self.assertEqual(["motion"],result["measurements"][2]["value"])

    def test_early_release_bad_jitter_and_nonmatching_drop_fail_measured_simulation(self):
        original = copy.deepcopy(self.rows)
        for mode in ("release","jitter","drop"):
            with self.subTest(mode=mode):
                self.rows = copy.deepcopy(original)
                if mode == "release": self.rows[2]["actual_release_ns"] -= 1_000_000
                elif mode == "jitter": self.rows[2]["jitter_ms"] += 1
                else:
                    self.rows[2]["decision"] = "DROP_PATTERN"
                    self.rows[2]["actual_release_ns"] = None
                    self.rows[-1].update(sent=9,dropped=1)
                self.save_trace()
                result = self.score()
                self.assertEqual("MEASURED",result["measurement_status"])
                self.assertEqual("FAIL",result["verdict"])
                self.assertTrue(result["plan_mismatches"])

    def test_codec_configuration_bypasses_delay_without_counting_as_media(self):
        self.rows.insert(-1,{**self.identity,"kind":"OFFER","sequence":11,"flags":1,"pts_us":0,"bytes":20,"offered_ns":2_950_000_000,"eligible":False})
        self.rows.insert(-1,{**self.identity,"kind":"RESULT","sequence":11,"flags":1,"pts_us":0,"bytes":20,"offered_ns":2_950_000_000,
            "decision":"BYPASS","planned_release_ns":None,"actual_release_ns":2_950_000_001,"completed_ns":2_950_000_002,
            "phase":None,"phase_index":None,"plan_index":None,"media_index":None,"jitter_ms":0,"plan_decision":None})
        self.rows[-1].update(offered=11,bypassed=1)
        self.save_trace()
        result = self.score()
        self.assertEqual("PASS",result["verdict"])
        self.assertEqual(10,result["phases"][0]["offered_media"])
        self.assertEqual(8000,result["phases"][0]["offered_payload_bps"])

    def test_unrecorded_release_or_changed_counters_is_unmeasured(self):
        self.rows[2]["actual_release_ns"] = None
        self.save_trace()
        self.assertEqual("UNMEASURED",self.score()["verdict"])
        self.make_trace()
        self.rows[-1]["sent"] = 100
        self.save_trace()
        self.assertIn("counters",self.score()["measurements"][-1]["reason"])

    def test_config_and_log_hashes_and_exact_scope_are_prerequisites(self):
        for key in ("config","log"):
            with self.subTest(key=key):
                original = copy.deepcopy(self.spec[key])
                self.spec[key]["sha256"] = "0"*64
                self.assertIn("SHA256 mismatch",self.score()["measurements"][-1]["reason"])
                self.spec[key] = original
        self.spec["scope"] = "different-scope"
        self.assertEqual("UNMEASURED",self.score()["verdict"])

    def test_validation_rejects_unknown_fractional_boolean_overlap_and_invalid_ranges(self):
        bad = [self.config|{"extra":1},self.config|{"seed":1.0},self.config|{"max_wait_ms":True},self.config|{"max_wait_ms":5001}]
        for key,value in (("rate_bps",-1),("end_ms",0),("burst_every",3),("id","invalid space"),("extra",1)):
            config = copy.deepcopy(self.config)
            config["phases"][0][key] = value
            bad.append(config)
        config = copy.deepcopy(self.config)
        config["phases"].append(config["phases"][0]|{"id":"later","start_ms":500})
        bad.append(config)
        for config in bad:
            with self.subTest(config=config),self.assertRaises(ValueError):
                validate_config(config)

    def test_closed_interrupted_admission_and_failed_sends_are_not_hidden_as_expected_loss(self):
        original = copy.deepcopy(self.rows)
        for decision in ("DROP_CLOSED","DROP_INTERRUPTED","DROP_ADMISSION","SEND_FAILED"):
            with self.subTest(decision=decision):
                self.rows = copy.deepcopy(original)
                row = self.rows[-2]
                row["decision"] = decision
                row["actual_release_ns"] = None
                if decision == "DROP_ADMISSION":
                    row["plan_index"] = row["media_index"] = row["phase_index"] = None
                    row["phase"] = row["plan_decision"] = row["planned_release_ns"] = None
                    row["completed_ns"] = row["offered_ns"]+300_000_000
                    self.rows[-1]["closed_ns"] = row["completed_ns"]
                self.rows[-1].update(sent=9,dropped=1 if decision != "SEND_FAILED" else 0,send_failed=1 if decision == "SEND_FAILED" else 0)
                self.save_trace()
                result = self.score()
                self.assertEqual("FAIL",result["verdict"])
                self.assertEqual(1,result["measurements"][-1]["value"])

    def test_cli_keeps_input_bytes_unchanged_and_refuses_output_overwrite(self):
        path = self.base/"spec.json"
        path.write_text(json.dumps(self.spec),encoding="utf-8")
        original = path.read_bytes()
        output = self.base/"result.json"
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,main(["--spec",str(path),"--output",str(output)]))
        self.assertEqual("benchmark-impairment-trace-v1",json.loads(output.read_text())["schema"])
        self.assertEqual(original,path.read_bytes())
        saved = output.read_bytes()
        with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):
            main(["--spec",str(path),"--output",str(output)])
        self.assertEqual(saved,output.read_bytes())


if __name__ == "__main__":
    unittest.main()
