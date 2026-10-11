import contextlib
import io
import json
import re
import shlex
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import debug_verify_contacts as tool

FP_A = "a1" * 32
FP_B = "b2" * 32


def identity_line(fingerprint):
    return f"10-11 10:00:00.000  900  900 I BluetoothVideo: BENCHMARK identity fingerprint={fingerprint} node={fingerprint[:32]}"


class FakePhones:
    """Fake adb: per-serial logcat text and an app-private files directory written by the run-as script."""

    def __init__(self, logs, debuggable=True):
        self.logs = logs
        self.files = {serial: {} for serial in logs}
        self.calls = []
        self.debuggable = debuggable

    def __call__(self, serial, args, timeout=30):
        self.calls.append((serial, list(args)))
        if args[:1] == ["logcat"]:
            return 0, self.logs[serial]
        if args[:4] == ["shell", "run-as", tool.PACKAGE, "sh"]:
            if not self.debuggable:
                return 1, ""
            script = shlex.split(args[5])[0]
            body = re.search(r"\{ (.*); \} > files/(\S+)$", script)
            lines = [shlex.split(part)[1] for part in body[1].split("; ")]
            self.files[serial][body[2]] = "\n".join(lines) + "\n"
            return 0, ""
        if args[:4] == ["shell", "run-as", tool.PACKAGE, "cat"]:
            name = args[4].split("/", 1)[1]
            return (0, self.files[serial][name]) if name in self.files[serial] else (1, "")
        raise AssertionError(f"unexpected adb call {args}")


def run_main(argv, phones):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = tool.main(argv, run=phones)
    return code, out.getvalue(), err.getvalue()


class DebugVerifyContactsTest(unittest.TestCase):
    def test_each_phone_gets_the_other_phones_fingerprint_and_a_distinct_token(self):
        phones = FakePhones({"SA": "noise\n" + identity_line(FP_A) + "\n", "SB": identity_line(FP_B) + "\n"})
        code, out, err = run_main(["--phones", "SA,SB", "--devices", "none.json"], phones)
        self.assertEqual(0, code, err)
        report = json.loads(out)
        by_phone = {entry["phone"]: entry for entry in report["phones"]}
        self.assertEqual({"SA", "SB"}, set(by_phone))
        self.assertNotEqual(by_phone["SA"]["token"], by_phone["SB"]["token"])
        for entry, own, peer in ((by_phone["SA"], "SA", FP_B), (by_phone["SB"], "SB", FP_A)):
            written = phones.files[own]["debug-verify-contact"].split("\n")
            self.assertEqual([entry["token"], peer], written[:2])
            self.assertRegex(entry["token"], r"^[0-9a-f]{32}$")
            self.assertEqual(["--es", "test_verify_contact", entry["token"]], entry["launch_extras"])
        self.assertEqual("verify", report["kind"])

    def test_the_most_recent_identity_line_wins_after_a_wipe(self):
        old, new = "c3" * 32, FP_A
        phones = FakePhones({"SA": identity_line(old) + "\n" + identity_line(new) + "\n", "SB": identity_line(FP_B) + "\n"})
        code, out, _ = run_main(["--phones", "SA,SB", "--devices", "none.json"], phones)
        self.assertEqual(0, code)
        self.assertEqual(FP_A, phones.files["SB"]["debug-verify-contact"].split("\n")[1])

    def test_forget_writes_the_forget_files_and_extras(self):
        phones = FakePhones({"SA": identity_line(FP_A), "SB": identity_line(FP_B)})
        code, out, _ = run_main(["--phones", "SA,SB", "--forget", "--devices", "none.json"], phones)
        self.assertEqual(0, code)
        report = json.loads(out)
        self.assertEqual("forget", report["kind"])
        self.assertIn("debug-forget-contact", phones.files["SA"])
        self.assertNotIn("debug-verify-contact", phones.files["SA"])
        self.assertEqual("test_forget_contact", report["phones"][0]["launch_extras"][1])

    def test_only_logcat_and_run_as_are_used_so_no_other_phone_action_happens(self):
        phones = FakePhones({"SA": identity_line(FP_A), "SB": identity_line(FP_B)})
        run_main(["--phones", "SA,SB", "--devices", "none.json"], phones)
        for _, args in phones.calls:
            self.assertTrue(args[0] == "logcat" or args[:3] == ["shell", "run-as", tool.PACKAGE], args)

    def test_missing_identity_line_fails_before_any_write(self):
        phones = FakePhones({"SA": identity_line(FP_A), "SB": "nothing here\n"})
        code, _, err = run_main(["--phones", "SA,SB", "--devices", "none.json"], phones)
        self.assertEqual(2, code)
        self.assertIn("launch the DEBUG app", err)
        self.assertEqual({}, phones.files["SA"])

    def test_a_non_debuggable_build_fails_the_write(self):
        phones = FakePhones({"SA": identity_line(FP_A), "SB": identity_line(FP_B)}, debuggable=False)
        code, _, err = run_main(["--phones", "SA,SB", "--devices", "none.json"], phones)
        self.assertEqual(2, code)
        self.assertIn("debuggable", err)

    def test_exactly_two_distinct_phones_are_required(self):
        phones = FakePhones({"SA": identity_line(FP_A)})
        for argument in ("SA", "SA,SA", "SA,SB,SC"):
            code, _, err = run_main(["--phones", argument, "--devices", "none.json"], phones)
            self.assertEqual(2, code)
            self.assertIn("exactly two", err)

    def test_registry_aliases_resolve_and_a_stale_prefix_is_only_a_warning(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            registry = Path(directory) / "devices.json"
            registry.write_text(json.dumps({"devices": {
                "alpha": {"serial": "SA", "node_prefix": FP_A[:12]},
                "beta": {"serial": "SB", "node_prefix": "000000000000"},
            }}), encoding="utf-8")
            phones = FakePhones({"SA": identity_line(FP_A), "SB": identity_line(FP_B)})
            code, out, err = run_main(["--phones", "alpha,beta", "--devices", str(registry)], phones)
        self.assertEqual(0, code)
        self.assertEqual({"alpha", "beta"}, {entry["phone"] for entry in json.loads(out)["phones"]})
        self.assertIn("beta", err)
        self.assertIn("registry node_prefix", err)

    def test_display_name_is_sanitised(self):
        phones = FakePhones({"SA": identity_line(FP_A), "SB": identity_line(FP_B)})
        run_main(["--phones", "SA,SB", "--name", "Bob; rm -rf $HOME", "--devices", "none.json"], phones)
        self.assertEqual("Bob rm -rf HOME", phones.files["SA"]["debug-verify-contact"].split("\n")[2])


if __name__ == "__main__":
    unittest.main()
