import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import device_log_capture as capture
import run_device_benchmark as runner
import run_private_call_benchmark as call_runner


def row(second, message, pid=900):
    return f"10-09 10:00:{second:06.3f}  {pid}  {pid} I BluetoothVideo: {message}"


class MergeDumpTest(unittest.TestCase):
    def test_only_missing_lines_of_this_pid_inside_the_window_are_added_in_time_order(self):
        live = ["--------- beginning of main", row(1, "a"), row(5, "e")]
        dump = [row(0.5, "before the capture"), row(1, "a"), row(2, "b"), row(3, "other pid", pid=77),
                row(4, "d"), row(5, "e"), row(9, "after the end")]
        merged, added = capture.merge_dump(live, dump, 900, start="10-09 10:00:01.000", end="10-09 10:00:06.000")
        self.assertEqual([row(2, "b"), row(4, "d")], added)
        self.assertEqual(["--------- beginning of main", row(1, "a"), row(2, "b"), row(4, "d"), row(5, "e")], merged)

    def test_repeated_identical_lines_are_kept_as_often_as_the_device_logged_them(self):
        live = [row(1, "same")]
        merged, added = capture.merge_dump(live, [row(1, "same"), row(1, "same")], 900)
        self.assertEqual([row(1, "same")], added)
        self.assertEqual([row(1, "same"), row(1, "same")], merged)

    def test_live_order_is_never_rearranged_and_a_complete_live_log_is_unchanged(self):
        live = [row(3, "late"), row(2, "out of order")]
        merged, added = capture.merge_dump(live, list(live), 900)
        self.assertEqual((live, []), (merged, added))


FAKE_ADB = textwrap.dedent('''
    import pathlib, sys, time
    state = pathlib.Path(sys.argv[1])
    since = sys.argv[2]
    runs = int(state.read_text()) if state.exists() else 0
    state.write_text(str(runs + 1))
    def row(s, m): return f"10-09 10:00:{s:06.3f}  900  900 I BluetoothVideo: {m}"
    if runs == 0:
        print("--------- beginning of main"); print(row(1, "one")); print(row(2, "two"), flush=True)
        if sys.argv[3] == "exit":
            sys.exit(3)
        time.sleep(60)
    else:
        print("--------- beginning of main")
        assert since == "10-09 10:00:02.000", since
        print(row(2, "two")); print(row(3, "three"), flush=True)
        time.sleep(60)
''')


class FakeCapture(capture.LogCapture):
    def command(self, since):
        return [sys.executable, str(self.script), str(self.state), since, self.mode]


class LogCaptureTest(unittest.TestCase):
    def start(self, mode):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)  # runs after the reader is stopped (cleanups are last-in first-out)
        directory = Path(scratch.name)
        script = directory / "fake_adb.py"
        script.write_text(FAKE_ADB, encoding="utf-8")
        FakeCapture.script, FakeCapture.state, FakeCapture.mode = script, directory / "state", mode
        live = FakeCapture("adb", "serial", 900, "10-09 10:00:00.000", directory / "a-900.log")
        self.addCleanup(live.stop)
        live.text = lambda: (directory / "a-900.log").read_text(encoding="utf-8").splitlines()
        return live

    @staticmethod
    def wait_until(check, seconds=10):
        end = time.time() + seconds
        while time.time() < end and not check():
            time.sleep(0.05)

    def test_a_dead_reader_is_restarted_from_its_last_timestamp_without_duplicates(self):
        live = self.start("exit")
        self.wait_until(lambda: live.process.poll() is not None and live.lines >= 3)
        record = live.supervise(lambda: True)
        self.assertIn("exited with 3", record["reason"])
        self.assertEqual("10-09 10:00:02.000", record["since"])
        self.wait_until(lambda: row(3, "three") in live.text())
        self.assertEqual(["--------- beginning of main", row(1, "one"), row(2, "two"), row(3, "three")], live.text())

    def test_a_silent_reader_is_restarted_only_while_the_app_is_alive(self):
        live = self.start("hang")
        self.wait_until(lambda: live.lines >= 3)
        live.STALL_S = 0.1
        time.sleep(0.3)
        self.assertIsNone(live.supervise(lambda: False))  # a stopped app is expected to be silent
        self.assertEqual(0, len(live.restarts))
        record = live.supervise(lambda: True)
        self.assertIn("no line for", record["reason"])
        self.wait_until(lambda: row(3, "three") in live.text())
        self.assertEqual(1, live.text().count(row(2, "two")))
        self.assertEqual(row(3, "three"), live.text()[-1])
        self.assertIsNone(live.supervise(lambda: True))  # minimum gap between restarts

    def test_it_stands_in_for_the_popen_and_handle_pair_the_runners_keep(self):
        live = self.start("hang")
        self.assertIsNone(live.poll())
        live.terminate()
        live.wait(timeout=3)
        live.close()
        self.assertIsNotNone(live.poll())


class RunnerDumpMergeTest(unittest.TestCase):
    def runner(self, directory):
        devices = {"a": {"serial": "a", "node_prefix": "000000000001"}, "b": {"serial": "b", "node_prefix": "000000000002"}}
        case = {"id": "x", "source": "a", "receivers": [{"phone": "b", "parent": "a"}], "transport": "classic",
                "profile": {"codec": "vp9", "width": 640, "height": 360, "fps": 15, "bitrate_kbps": 150,
                            "playback": "balanced", "rate_control": "cbr", "thermal_reduction": False},
                "duration_s": 60}
        instance = runner.Runner("adb", devices, case, Path(directory) / "run")
        instance.output.mkdir(parents=True)
        return instance

    def test_the_case_end_merge_completes_the_physical_log_and_records_how_much_came_from_the_dump(self):
        # xpair-asusa15s-915d7390b: the live A15s capture kept 20-40 lines while logd held thousands.
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            (instance.output / "a-900.log").write_text("--------- beginning of main\n" + row(1, "x") + "\n", encoding="utf-8")
            (instance.output / "a-bluetooth-5.log").write_text(row(1, "stack", pid=5) + "\n", encoding="utf-8")
            instance.logs = [{"device": "a", "pid": 900, "start": "10-09 10:00:00.000", "end": "10-09 10:00:09.000",
                              "path": "a-900.log"}]
            instance.stack_logs = [{"device": "a", "process": "com.android.bluetooth", "pid": 5,
                                    "start": "10-09 10:00:00.000", "path": "a-bluetooth-5.log"},
                                   {"device": "a", "process": None, "reason": "Bluetooth process not found"}]
            dumps = {"--pid=900": "\n".join([row(1, "x"), row(2, "y"), row(3, "other", pid=1), row(20, "after end")]),
                     "--pid=5": row(1, "stack", pid=5) + "\n" + row(4, "stack two", pid=5)}
            with patch.object(instance, "adb", side_effect=lambda phone, *args, **kw: dumps[args[2]]):
                instance.merge_device_logs()
            lines = (instance.output / "a-900.log").read_text(encoding="utf-8").splitlines()
            self.assertEqual(["--------- merged 1 line(s) from logcat -d --pid=900; the live capture held 1",
                              "--------- beginning of main", row(1, "x"), row(2, "y")], lines)
            self.assertEqual((1, 1), (instance.logs[0]["live_lines"], instance.logs[0]["dump_merged_lines"]))
            stack = (instance.output / "a-bluetooth-5.log").read_text(encoding="utf-8").splitlines()
            self.assertEqual(["--------- merged 1 line(s) from logcat -d --pid=5; the live capture held 1",
                              row(1, "stack", pid=5), row(4, "stack two", pid=5)], stack)
            self.assertEqual(["log_dump_merged"] * 2, [event["action"] for event in instance.events])

    def test_an_unreadable_dump_leaves_the_live_log_and_records_the_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            (instance.output / "a-900.log").write_text(row(1, "x") + "\n", encoding="utf-8")
            instance.logs = [{"device": "a", "pid": 900, "start": "10-09 10:00:00.000", "path": "a-900.log"}]
            with patch.object(instance, "adb", side_effect=RuntimeError("offline")):
                instance.merge_device_logs()
            self.assertEqual(row(1, "x") + "\n", (instance.output / "a-900.log").read_text(encoding="utf-8"))
            self.assertEqual("log_dump_merge_failed", instance.events[-1]["action"])

    def test_supervision_restarts_a_stalled_reader_and_records_the_event(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.runner(directory)
            instance.logs = [{"device": "a", "pid": 900, "path": "a-900.log"}]
            stub = capture.LogCapture.__new__(capture.LogCapture)
            stub.supervise = lambda alive: {"reason": "no line for 12 s while the app was alive", "since": "t", "restart": 1} \
                if alive() else None
            instance.captures = [(stub, stub)]
            with patch.object(instance, "adb", return_value="900"):
                instance.supervise_captures()
            self.assertEqual("log_capture_restarted", instance.events[-1]["action"])
            self.assertEqual(1, instance.events[-1]["restart"])


class EvidenceWaitPollTest(unittest.TestCase):
    def test_a_silent_live_stream_is_polled_against_the_device_buffer_before_the_deadline(self):
        devices = {"a": {"serial": "a", "node_prefix": "000000000001"}, "b": {"serial": "b", "node_prefix": "000000000002"}}
        profile = {"codec": "e", "width": 640, "height": 360, "fps": 15, "bitrate_kbps": 150, "playback": "balanced",
                   "rate_control": "cbr", "thermal_reduction": False}
        case = {"id": "t", "caller": "a", "callee": "b", "transport": "classic", "duration_s": 60,
                "profiles": {"a": profile, "b": profile}}
        with tempfile.TemporaryDirectory() as directory:
            instance = call_runner.CallRunner("adb", devices, case, Path(directory))
            instance.output.mkdir(parents=True, exist_ok=True)
            (instance.output / "b-7.log").write_text("", encoding="utf-8")
            instance.logs = [{"device": "b", "pid": 7, "path": "b-7.log"}]
            instance.pids["b"] = "7"
            stub = capture.LogCapture.__new__(capture.LogCapture)
            stub.silent_s = lambda: 4.0
            stub.supervise = lambda alive: None
            instance.captures = [(stub, stub)]
            applied = row(12.893, "BENCHMARK control accept applied", pid=7)
            clock = iter(range(100))
            with patch.object(instance, "adb", return_value=applied) as adb, \
                 patch.object(call_runner.time, "monotonic", side_effect=lambda: next(clock) * 0.1), \
                 patch.object(call_runner.time, "sleep"):
                found = instance.wait_for("b", r"BENCHMARK control accept (?:applied|rejected)", "10-09 10:00:12.579", timeout=30)
            self.assertEqual(("logcat_dump", "BENCHMARK control accept applied"), (found["source"], found["message"]))
            self.assertEqual("evidence_from_dump_poll", instance.events[-1]["action"])
            self.assertEqual(1, adb.call_count)  # found by the first poll, long before the 30 s deadline

    def test_a_live_stream_that_is_still_talking_is_not_dumped(self):
        devices = {"a": {"serial": "a", "node_prefix": "000000000001"}, "b": {"serial": "b", "node_prefix": "000000000002"}}
        profile = {"codec": "e", "width": 640, "height": 360, "fps": 15, "bitrate_kbps": 150, "playback": "balanced",
                   "rate_control": "cbr", "thermal_reduction": False}
        case = {"id": "t", "caller": "a", "callee": "b", "transport": "classic", "duration_s": 60,
                "profiles": {"a": profile, "b": profile}}
        with tempfile.TemporaryDirectory() as directory:
            instance = call_runner.CallRunner("adb", devices, case, Path(directory))
            instance.logs = [{"device": "b", "pid": 7, "path": "b-7.log"}]
            stub = capture.LogCapture.__new__(capture.LogCapture)
            stub.silent_s = lambda: 0.5
            instance.captures = [(stub, stub)]
            with patch.object(instance, "adb") as adb:
                self.assertIsNone(instance.poll_dump_evidence("b", "x", None))
            adb.assert_not_called()


if __name__ == "__main__":
    unittest.main()
