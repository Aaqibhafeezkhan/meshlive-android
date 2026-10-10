import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import prepare_native_workload as preparation


class NativePreparationTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory(prefix="native-preparation-test-")
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.producer = self.root/"producer"
        (self.producer/"videostress").mkdir(parents=True)
        (self.producer/"examples").mkdir()
        self.source = self.producer/"videostress/__init__.py"
        self.source.write_bytes(b"# frozen test producer\n")
        self.recipe = self.producer/"examples/framing.json"
        self.recipe.write_bytes(b'{"schema_version":1,"test":"frozen recipe"}\n')
        source_sha = hashlib.sha256(f"__init__.py:{hashlib.sha256(self.source.read_bytes()).hexdigest()}".encode()).hexdigest()
        self.addCleanup(patch.stopall)
        patch.object(preparation,"SOURCE_SHA256",source_sha).start()
        patch.object(preparation,"RECIPES",{"framing":(12,hashlib.sha256(self.recipe.read_bytes()).hexdigest())}).start()
        self.plan = {"schema":"vsg-plan-v1","variant_id":"c"*64,"total_disk_estimate_bytes":6000000,
                     "modes":[{"width":160,"height":144,"fps":5,"frame_count":60,
                               "working_frame_memory_estimate_bytes":9000000}]}
        self.output = self.root/"new-input"

    def call(self, **kwargs):
        return preparation.prepare("framing","160x144@5",producer=self.producer,**kwargs)

    def test_default_plan_is_readonly_and_returns_exact_input_identities(self):
        before = sorted(p.relative_to(self.root).as_posix() for p in self.root.rglob("*"))
        with patch.object(preparation,"run_producer",return_value=self.plan) as run:
            result = self.call()
        self.assertEqual("PLAN",result["action"])
        self.assertEqual(60,result["frame_count"])
        self.assertEqual(preparation.SOURCE_SHA256,result["producer_source_sha256"])
        self.assertEqual(preparation.RECIPES["framing"][1],result["recipe_sha256"])
        self.assertEqual(before,sorted(p.relative_to(self.root).as_posix() for p in self.root.rglob("*")))
        self.assertEqual("dry-run",run.call_args.args[1])

    def test_changed_producer_or_recipe_blocks_before_cli_or_output(self):
        for target in (self.source,self.recipe):
            original = target.read_bytes()
            target.write_bytes(original+b"changed")
            with patch.object(preparation,"run_producer") as run, self.assertRaisesRegex(ValueError,"differs"):
                self.call(action="prepare",output=self.output,max_bytes=6000000)
            run.assert_not_called()
            self.assertFalse(self.output.exists())
            target.write_bytes(original)

    def test_invalid_modes_rejected_before_cli(self):
        for mode in ("161x144@5","160x145@5","160x144@0","160x144@30000/1001","160x144@-5","wrong"):
            with self.subTest(mode=mode), patch.object(preparation,"run_producer") as run, self.assertRaises(ValueError):
                preparation.prepare("framing",mode,producer=self.producer)
            run.assert_not_called()

    def test_oversized_estimate_blocks_generation_without_writing(self):
        with patch.object(preparation,"run_producer",return_value=self.plan) as run, self.assertRaisesRegex(ValueError,"exceeds"):
            self.call(action="prepare",output=self.output,max_bytes=5999999)
        self.assertEqual(1,run.call_count)
        self.assertFalse(self.output.exists())

    def test_prepare_requires_positive_explicit_budget_and_fresh_output(self):
        for budget in (None,0,-1,True,6.5):
            with self.subTest(budget=budget), patch.object(preparation,"run_producer") as run, self.assertRaises(ValueError):
                self.call(action="prepare",output=self.output,max_bytes=budget)
            run.assert_not_called()
        self.output.mkdir()
        marker = self.output/"keep.txt"
        marker.write_text("important",encoding="utf-8")
        with patch.object(preparation,"run_producer") as run, self.assertRaisesRegex(ValueError,"already exists"):
            self.call(action="prepare",output=self.output,max_bytes=6000000)
        run.assert_not_called()
        self.assertEqual("important",marker.read_text(encoding="utf-8"))

    def test_output_guard_rejects_wrong_project_group(self):
        with patch.object(preparation,"run_producer") as run, self.assertRaisesRegex(ValueError,"inputs"):
            self.call(action="prepare",output=Path(__file__).resolve().parents[2]/"benchmarks/runs/new-native",
                      max_bytes=6000000)
        run.assert_not_called()

    def test_plan_cannot_write_and_substituted_mode_or_duration_is_rejected(self):
        with self.assertRaisesRegex(ValueError,"does not accept"):
            self.call(output=self.output)
        for field,value in (("width",320),("fps",30),("frame_count",59)):
            plan=json.loads(json.dumps(self.plan))
            plan["modes"][0][field]=value
            with patch.object(preparation,"run_producer",return_value=plan), self.assertRaisesRegex(ValueError,"differs"):
                self.call()

    def test_producer_changed_during_dry_run_blocks_before_generate(self):
        def dry_run(*args):
            self.source.write_bytes(b"changed during plan")
            return self.plan
        with patch.object(preparation,"run_producer",side_effect=dry_run) as run, self.assertRaisesRegex(ValueError,"differs"):
            self.call(action="prepare",output=self.output,max_bytes=6000000)
        self.assertEqual(1,run.call_count)
        self.assertFalse(self.output.exists())

    def generate(self, producer, command, *args):
        if command == "dry-run":
            return self.plan
        if command == "generate":
            self.output.mkdir()
            (self.output/"raw-fixtures.json").write_text('{"schema_version":1}\n',encoding="utf-8")
            (self.output/"report.json").write_text(json.dumps({"schema":"vsg-generation-report-v1",
                "variant_id":self.plan["variant_id"]}),encoding="utf-8")
            return {"status":"GENERATED_AND_SELF_VERIFIED"}
        self.assertEqual("validate",command)
        return {"status":"VERIFIED","fixtures":[{"frames_rerendered":60}]}

    def test_prepare_reuses_existing_generation_report_with_verified_identity(self):
        with patch.object(preparation,"run_producer",side_effect=self.generate) as run:
            result=self.call(action="prepare",output=self.output,max_bytes=6000000)
        self.assertEqual(["dry-run","generate","validate"],[c.args[1] for c in run.call_args_list])
        self.assertEqual("PREPARED",result["action"])
        self.assertEqual(result,json.loads((self.output/"report.json").read_text(encoding="utf-8"))["preparation"])
        self.assertEqual({"raw-fixtures.json","report.json"},{p.name for p in self.output.iterdir()})

    def test_failed_validation_never_promotes_generated_output(self):
        def invalid(*args):
            result=self.generate(*args)
            return {"status":"FAILED"} if args[1]=="validate" else result
        with patch.object(preparation,"run_producer",side_effect=invalid), self.assertRaisesRegex(ValueError,"incomplete"):
            self.call(action="prepare",output=self.output,max_bytes=6000000)
        self.assertNotIn("preparation",json.loads((self.output/"report.json").read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
