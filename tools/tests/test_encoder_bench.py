import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import encoder_bench  # noqa: E402

SAMPLE = ("10-14 12:00:01.000  1  2 I BluetoothVideo: BENCHMARK encoder_bench: event=sample | codec=c2.x.av1 | "
          "requested=[codec=av1;mode=cbr] | effective=[codec=c2.x.av1;mode=cbr] | fps_out=29.97 | kbps_out=412.3 | "
          "enc_latency_ms_p50/p95=12.0/20.6 | keyframes=1 | dropped=0 | thermal=none | t_s=1")
SUMMARY = ("10-14 12:00:20.000  1  2 I BluetoothVideo: BENCHMARK encoder_bench: event=summary | codec=c2.x.av1 | "
           "requested=[codec=av1;mode=cbr] | effective=[codec=c2.x.av1;mode=cbr] | fps_out=29.9 | kbps_out=400.0 | "
           "enc_latency_ms_p50/p95=12.0/20.6 | keyframes=2 | dropped=0 | thermal=none | result=ok | reason=none | "
           "frames=598 | run_s=20.0 | ble_violations=0")


class GridTest(unittest.TestCase):
    def test_cells_are_the_ordered_product_and_null_drops_an_extra(self):
        grid = {"base": {"test_fps": 30}, "axes": {"test_bench_codec": ["av1", "vp9"],
                                                   "test_intra_refresh_frames": [None, 30]}}
        cells = encoder_bench.expand_grid(grid)
        self.assertEqual(len(cells), 4)
        self.assertEqual(cells[0], {"test_fps": 30, "test_bench_codec": "av1"})
        self.assertEqual(cells[1], {"test_fps": 30, "test_bench_codec": "av1", "test_intra_refresh_frames": 30})
        self.assertEqual(cells[3]["test_bench_codec"], "vp9")

    def test_hevc_and_avc_codec_tokens_pass_through_to_the_launch(self):
        cells = encoder_bench.expand_grid({"axes": {"test_bench_codec": ["hevc", "video/avc", "c2.qti.hevc.encoder"]}})
        self.assertEqual([c["test_bench_codec"] for c in cells], ["hevc", "video/avc", "c2.qti.hevc.encoder"])
        command = encoder_bench.launch_command("pkg", cells[0])
        self.assertIn("--es test_bench_codec hevc", " ".join(command))

    def test_an_empty_axis_or_a_non_test_extra_is_rejected(self):
        with self.assertRaises(ValueError):
            encoder_bench.expand_grid({"axes": {"test_fps": []}})
        with self.assertRaises(ValueError):
            encoder_bench.expand_grid({"base": {"not_a_test_extra": 1}})

    def test_no_axes_is_one_cell(self):
        self.assertEqual(encoder_bench.expand_grid({"base": {"test_fps": 24}}), [{"test_fps": 24}])


class LaunchTest(unittest.TestCase):
    def test_extras_use_the_matching_am_flag(self):
        command = encoder_bench.launch_command("pkg", {"test_fps": 30, "test_bench_codec": "av1", "test_x": False})
        self.assertEqual(command[:6], ["shell", "am", "start", "-W", "-n", f"pkg/{encoder_bench.ACTIVITY}"])
        self.assertIn("--ez test_encoder_bench true", " ".join(command))
        self.assertIn("--ei test_fps 30", " ".join(command))
        self.assertIn("--es test_bench_codec av1", " ".join(command))
        self.assertIn("--ez test_x false", " ".join(command))


class ParseTest(unittest.TestCase):
    def test_a_line_becomes_fields_with_bracketed_settings_as_dicts(self):
        fields = encoder_bench.parse_line(SAMPLE)
        self.assertEqual(fields["event"], "sample")
        self.assertEqual(fields["requested"], {"codec": "av1", "mode": "cbr"})
        self.assertEqual(fields["effective"]["codec"], "c2.x.av1")
        self.assertEqual(fields["enc_latency_ms_p50/p95"], "12.0/20.6")
        self.assertIsNone(encoder_bench.parse_line("something else"))

    def test_the_log_splits_into_samples_and_one_summary(self):
        samples, summary, violations = encoder_bench.parse_log("\n".join([SAMPLE, SAMPLE, SUMMARY]))
        self.assertEqual(len(samples), 2)
        self.assertEqual(summary["result"], "ok")
        self.assertEqual(violations, [])
        _, none, _ = encoder_bench.parse_log(SAMPLE)
        self.assertIsNone(none)


def _line(stamp, pid, event):
    return (f"{stamp}  {pid}  {pid + 1} I BluetoothVideo: BENCHMARK encoder_bench: event={event} | codec=c2.x.av1 | "
            "requested=[codec=av1;mode=cbr] | effective=[codec=c2.x.av1;mode=cbr] | fps_out=15.0 | result=ok")


class _FakePhone:
    """A phone whose logcat still holds the previous cell's process lines (logcat -c did not clear them)."""

    def __init__(self, dump, pids="9001", now="10-15 04:37:09"):
        self.dump, self.pids, self.now = dump, pids, now
        self.dry_run = False
        self.sleeps = 0
        self.launched = False

    def sleep(self, seconds):
        self.sleeps += 1

    def adb(self, *args, timeout=30):
        if args[:2] == ("logcat", "-d"):
            return self.dump
        if args[:3] == ("shell", "am", "start"):
            self.launched = True
        if args[:2] == ("shell", "pidof"):
            return self.pids if self.launched else ""  # nothing runs between force-stop and the launch
        if args[0] == "shell" and args[1].startswith("date"):
            return self.now + "\n"
        return ""


class CellHarvestTest(unittest.TestCase):
    PREVIOUS_SUMMARY = _line("10-15 04:37:08.048", 7670, "summary")
    PREVIOUS_SAMPLE = _line("10-15 04:37:07.003", 7670, "sample")

    def test_lines_of_another_process_or_before_the_launch_are_dropped(self):
        own = _line("10-15 04:37:12.022", 9001, "sample")
        old_time_same_pid = _line("10-15 04:37:08.500", 9001, "sample")
        kept, dropped = encoder_bench.select_cell_lines(
            [self.PREVIOUS_SAMPLE, self.PREVIOUS_SUMMARY, old_time_same_pid, own, "no timestamp"],
            {9001}, "10-15 04:37:09.000")
        self.assertEqual(kept, [own])
        self.assertEqual(len(dropped), 4)
        self.assertEqual(encoder_bench.select_cell_lines(["x", own], None, None), (["x", own], []))
        # Only the time window known (no pidof on the phone): the previous process is still excluded by time.
        kept, _ = encoder_bench.select_cell_lines([self.PREVIOUS_SUMMARY, own], set(), "10-15 04:37:09.000")
        self.assertEqual(kept, [own])

    def test_a_cell_stopped_early_does_not_inherit_the_previous_cells_summary(self):
        own_samples = [_line(f"10-15 04:37:1{i}.022", 9001, "sample") for i in range(3)]
        dump = "\n".join([self.PREVIOUS_SAMPLE, self.PREVIOUS_SUMMARY, *own_samples])
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "cases").mkdir()
            phone = _FakePhone(dump)
            with mock.patch.object(encoder_bench, "SUMMARY_WAIT_MARGIN_S", -1000):  # give up at the first poll
                record = encoder_bench.run_cell(phone, "pkg", {"test_bench_duration_s": 5}, out, 2)
            self.assertFalse(record["complete"])
            self.assertIsNone(record["summary"])
            self.assertEqual(record["samples"], 3)
            self.assertEqual(record["pids"], [9001])
            self.assertEqual(record["foreign_lines_dropped"], 2)
            self.assertIn("own process", record["incomplete_reason"])
            log = (out / "cases" / "case-0002.log").read_text(encoding="utf-8")
            self.assertNotIn("7670", log)
            self.assertEqual(log.count("event=sample"), 3)

    def test_a_cell_with_its_own_summary_is_complete(self):
        dump = "\n".join([self.PREVIOUS_SUMMARY, _line("10-15 04:37:12.022", 9001, "sample"),
                          _line("10-15 04:37:30.000", 9001, "summary")])
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "cases").mkdir()
            record = encoder_bench.run_cell(_FakePhone(dump), "pkg", {"test_bench_duration_s": 5}, out, 1)
            self.assertTrue(record["complete"])
            self.assertEqual(record["summary"]["event"], "summary")
            self.assertNotIn("incomplete_reason", record)


POWER_SAMPLE = ("[current_now_raw=-450000;current_now_ma=-450.0;current_now_units=ua;current_avg_raw=unknown;"
                "current_avg_ma=unknown;current_avg_units=unknown;sign=aosp;voltage_mv=3900;power_mw=1755;"
                "plugged=none;status=discharging;temp_c=31.2;power_valid=true;power_reason=ok]")
POWER_SUMMARY = ("[power_valid=false;power_reason=plugged;samples=2;valid_samples=1;plugged_samples=1;"
                 "power_mw_mean=1755;power_mw_p95=1755;energy_mj=1755;mj_per_frame=58.50;sign=aosp;"
                 "current_now_units=ua;voltage_mv_mean=3900;temp_c_start=31.2;temp_c_end=31.4;temp_c_max=31.4]")
POWER_SAMPLE_UNKNOWN = (POWER_SAMPLE.replace("current_now_raw=-450000", "current_now_raw=unknown")
                        .replace("current_now_ma=-450.0", "current_now_ma=unknown")
                        .replace("power_mw=1755", "power_mw=unknown")
                        .replace("power_valid=true;power_reason=ok", "power_valid=false;power_reason=current_unknown"))


def _power_line(stamp, pid, event, power, extra=""):
    return (f"{stamp}  {pid}  {pid + 1} I BluetoothVideo: BENCHMARK encoder_bench: event={event} | codec=c2.x.av1 | "
            "requested=[codec=av1;mode=cbr] | effective=[codec=c2.x.av1;mode=cbr] | fps_out=30.00 | thermal=none | "
            f"power={power}{extra}")


class PowerTest(unittest.TestCase):
    def test_power_fields_parse_into_the_cell_record_with_unknown_as_none(self):
        dump = "\n".join([
            _power_line("10-15 04:37:12.022", 9001, "sample", POWER_SAMPLE, " | t_s=1"),
            _power_line("10-15 04:37:13.022", 9001, "sample", POWER_SAMPLE_UNKNOWN, " | t_s=2"),
            _power_line("10-15 04:37:14.022", 9001, "summary", POWER_SUMMARY, " | result=ok | reason=none"),
        ])
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "cases").mkdir()
            record = encoder_bench.run_cell(_FakePhone(dump), "pkg", {"test_bench_duration_s": 5}, out, 1)
        self.assertTrue(record["complete"])
        power = record["power"]
        self.assertFalse(power["power_valid"])
        self.assertEqual(power["power_reason"], "plugged")
        self.assertEqual(power["power_mw_mean"], 1755.0)
        self.assertEqual(power["power_mw_p95"], 1755.0)
        self.assertEqual(power["mj_per_frame"], 58.5)
        self.assertEqual(power["valid_samples"], 1.0)
        self.assertEqual(power["temp_c_max"], 31.4)
        self.assertEqual(power["sign"], "aosp")
        series = power["sample_series"]
        self.assertEqual([row["t_s"] for row in series], [1.0, 2.0])
        self.assertEqual(series[0]["power_mw"], 1755.0)
        self.assertTrue(series[0]["valid"])
        self.assertIsNone(series[1]["power_mw"])
        self.assertIsNone(series[1]["current_now_ma"])
        self.assertFalse(series[1]["valid"])
        json.dumps(record)  # results.jsonl must serialise it

    def test_a_cell_without_power_fields_has_no_power_record(self):
        samples, summary, _ = encoder_bench.parse_log("\n".join([SAMPLE, SUMMARY]))
        self.assertIsNone(encoder_bench.power_record(samples, summary))

    def test_samples_without_a_summary_are_reported_invalid(self):
        samples, summary, _ = encoder_bench.parse_log(_power_line("10-15 04:37:12.022", 9001, "sample", POWER_SAMPLE))
        record = encoder_bench.power_record(samples, summary)
        self.assertFalse(record["power_valid"])
        self.assertEqual(record["power_reason"], "no_summary")
        self.assertEqual(len(record["sample_series"]), 1)


class _FakeDevice:
    """A phone driven by a clock: `am start` runs a bench process that logs one sample per second and then a summary;
    `logcat -c` clears nothing (`clear_works=False`), everything (True) or all but the last two seconds ("partial",
    the lane11 ASUS pattern: cell 1's final sample and summary stay in the buffer); `date` can lack %N."""

    BASE = (10, 15, 7, 49, 0)  # month, day, h, m, s of device time zero
    FIRST_PID = 6794

    def __init__(self, clear_works=False, date_has_nanos=True, zombie_s=0.0, adb_step_s=0.3):
        self.t = 0.0
        self.clear_works, self.date_has_nanos, self.zombie_s, self.adb_step_s = clear_works, date_has_nanos, zombie_s, adb_step_s
        self.log = []  # (device time, pid, event text)
        self.proc = None  # {"pid", "start", "duration", "stop", "emitted"}
        self.zombie = None  # (pid, until) a killed process still listed by pidof
        self.next_pid = self.FIRST_PID
        self.starts = []

    def _stamp(self, t):
        month, day, hour, minute, second = self.BASE
        total = second + int(t // 1) + minute * 60 + hour * 3600
        ms = int(round((t % 1) * 1000))
        return (f"{month:02d}-{day:02d} {total // 3600:02d}:{total // 60 % 60:02d}:{total % 60:02d}", ms)

    def _emit_until(self, now):
        proc = self.proc
        if not proc or proc["stop"] is not None:
            return
        while True:
            at = proc["start"] + 1.2 + proc["emitted"]
            if proc["emitted"] < proc["duration"]:
                if at > now:
                    return
                proc["emitted"] += 1
                self.log.append((at, proc["pid"], "sample"))
            else:
                if at > now:
                    return
                self.log.append((at + 0.03, proc["pid"], "summary"))
                proc["stop"] = at  # the bench activity finished; the process lingers until force-stop
                return

    def advance(self, seconds):
        self.t += seconds
        self._emit_until(self.t)

    def sleep(self, seconds):
        self.advance(seconds)

    def run(self, command, **_kwargs):
        self.advance(self.adb_step_s)
        args = command[3:]  # adb -s SERIAL ...
        out = ""
        if args[:2] == ["logcat", "-c"]:
            if self.clear_works == "partial":  # the last second or two before the clear survives (lane11 ASUS)
                self.log = [row for row in self.log if row[0] > self.t - 2.0]
            elif self.clear_works:
                self.log = []
        elif args[:2] == ["logcat", "-d"]:
            lines = []
            for at, pid, event in sorted(self.log, key=lambda row: row[0]):
                text, ms = self._stamp(at)
                lines.append(f"{text}.{ms:03d}  {pid}  {pid + 1} I BluetoothVideo: BENCHMARK encoder_bench: event={event} | "
                             "codec=OMX.qcom.video.encoder.hevc | requested=[codec=hevc;mode=vbr] | "
                             "effective=[codec=hevc;mode=vbr] | fps_out=15.0 | thermal=none | result=ok")
            out = "\n".join(lines) + "\n"
        elif args[:3] == ["shell", "am", "force-stop"]:
            if self.proc and not self.proc.get("dead"):
                self.proc["dead"] = True
                if self.zombie_s:
                    self.zombie = (self.proc["pid"], self.t + self.zombie_s)
        elif args[:2] == ["shell", "pidof"]:
            if self.proc and not self.proc.get("dead"):
                out = f"{self.proc['pid']}\n"
            elif self.zombie and self.t < self.zombie[1]:
                out = f"{self.zombie[0]}\n"
        elif args[0] == "shell" and args[1].startswith("date"):
            text, ms = self._stamp(self.t)
            if "%N" in args[1]:
                out = (f"{text}.{ms:03d}123456\n" if self.date_has_nanos else f"{text}.%N\n")
            else:
                out = text + "\n"
        elif args[:3] == ["shell", "am", "start"]:
            duration = 20
            for i, token in enumerate(args):
                if token == "test_bench_duration_s":
                    duration = int(args[i + 1])
            self.proc = {"pid": self.next_pid, "start": self.t, "duration": duration, "stop": None, "emitted": 0}
            self.next_pid += 235
            self.starts.append(self.t)
            self.advance(1.0)  # -W waits for the launch
        return mock.Mock(returncode=0, stdout=out, stderr="")


class MultiCellGridTest(unittest.TestCase):
    """lane11 f1-asus-bench-vbr: cells 2 and 4 of a grid kept 3 samples and the previous cell's summary."""

    DURATION = 12

    def _run_grid(self, device, cells=4):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "cases").mkdir()
            phone = encoder_bench.Phone("adb", "SERIAL", run=device.run, sleep=device.sleep)
            records = [encoder_bench.run_cell(phone, "pkg", {"test_bench_duration_s": self.DURATION}, out, index)
                       for index in range(1, cells + 1)]
            logs = [(out / "cases" / f"case-{i:04d}.log").read_text(encoding="utf-8") for i in range(1, cells + 1)]
        return records, logs

    def _assert_every_cell_is_whole(self, records, logs, device):
        for record, log in zip(records, logs):
            self.assertTrue(record["complete"], record)
            self.assertEqual(record["samples"], self.DURATION, record)
            pids = {line.split()[2] for line in log.splitlines()}
            self.assertEqual(len(pids), 1, "a cell holds lines of its own process only")
            self.assertEqual(log.count("event=summary"), 1)
        self.assertEqual(len({r["pids"][0] for r in records}), len(records), "one fresh process per cell")

    def test_every_cell_of_a_multi_cell_grid_is_whole_when_logcat_clear_keeps_old_lines(self):
        for mode in (False, "partial"):
            with self.subTest(clear=mode):
                device = _FakeDevice(clear_works=mode)
                records, logs = self._run_grid(device)
                self._assert_every_cell_is_whole(records, logs, device)
                self.assertGreater(sum(record["foreign_lines_dropped"] for record in records[1:]), 0,
                                   "the previous cells' lines were in the buffer and were dropped")

    def test_the_unfiltered_poll_reproduces_the_alternating_truncation(self):
        device = _FakeDevice(clear_works="partial")
        with mock.patch.object(encoder_bench, "select_cell_lines", lambda lines, pids=None, since=None: (list(lines), [])):
            records, logs = self._run_grid(device)
        own = [sum(1 for line in log.splitlines() if "event=sample" in line and int(line.split()[2]) in record["pids"])
               for record, log in zip(records, logs)]
        # Cell 1 is whole; cell 2 ends on cell 1's surviving summary after one or two polls (about 3 own samples);
        # cell 3 has no stale summary and runs its full length; cell 4 repeats cell 2.
        self.assertEqual([count >= self.DURATION for count in own], [True, False, True, False], own)
        self.assertLessEqual(own[1], 4)
        self.assertLessEqual(own[3], 4)

    def test_a_stale_line_in_the_launch_second_is_dropped_by_the_millisecond_window(self):
        # No pidof answer at all (pids unknown): only the time window protects the cell. With a whole-second launch
        # time the previous summary, logged in the same device second as the next launch, slipped in for these
        # step/duration pairs (cells of [8, 2, 9], [10, 10, 2] and [12, 2, 12] samples); milliseconds drop it.
        for step, duration in ((0.05, 8), (0.05, 10), (0.02, 12)):
            with self.subTest(adb_step_s=step, duration=duration):
                device = _FakeDevice(clear_works="partial", adb_step_s=step)
                real = device.run

                def run(command, real=real, **kwargs):
                    out = real(command, **kwargs)
                    if command[3:5] == ["shell", "pidof"]:
                        return mock.Mock(returncode=0, stdout="", stderr="")
                    return out

                phone = encoder_bench.Phone("adb", "SERIAL", run=run, sleep=device.sleep)
                with tempfile.TemporaryDirectory() as tmp:
                    out = Path(tmp)
                    (out / "cases").mkdir()
                    records = [encoder_bench.run_cell(phone, "pkg", {"test_bench_duration_s": duration}, out, i)
                               for i in (1, 2, 3)]
                self.assertEqual([record["samples"] for record in records], [duration] * 3)
                self.assertTrue(all(record["complete"] for record in records))

    def test_whole_second_phones_fall_back_to_the_pid_filter(self):
        device = _FakeDevice(clear_works=False, date_has_nanos=False)
        records, logs = self._run_grid(device)
        self._assert_every_cell_is_whole(records, logs, device)
        self.assertTrue(all(record["launch_window_start"].endswith(".000") for record in records))

    def test_a_process_that_outlives_force_stop_is_waited_for_and_never_adopted(self):
        device = _FakeDevice(clear_works=False, zombie_s=1.5)
        records, logs = self._run_grid(device)
        self._assert_every_cell_is_whole(records, logs, device)
        for record in records:
            self.assertEqual(record["stale_pids_after_stop"], [])

    def test_a_grid_through_main_writes_whole_cells(self):
        device = _FakeDevice(clear_works=False)
        script = {
            "getprop": "[ro.product.model]: [ASUS_I01WD]\n",
            "pm path": "package:/data/app/base.apk\n",
            "sha256sum": "abc  /data/app/base.apk\n",
        }
        real = device.run

        def run(command, **kwargs):
            tail = " ".join(command[3:])
            for key, answer in script.items():
                if tail.startswith("shell getprop") and key == "getprop" or key in tail and key != "getprop":
                    real(command, **kwargs)
                    return mock.Mock(returncode=0, stdout=answer, stderr="")
            return real(command, **kwargs)

        original = encoder_bench.Phone
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(encoder_bench, "Phone", lambda adb, serial, dry_run=False: original(
                    adb, serial, dry_run=dry_run, run=run, sleep=device.sleep)):
            grid = Path(tmp) / "grid.json"
            grid.write_text(json.dumps({"base": {"test_bench_duration_s": self.DURATION},
                                        "axes": {"test_bench_codec": ["hevc", "avc"],
                                                 "test_bench_bitrate_mode": ["vbr", "cbr"]}}), encoding="utf-8")
            out = Path(tmp) / "out"
            argv = ["--serial", "SERIAL", "--out", str(out), "--grid", str(grid), "--raw-remote", "/sdcard/x.yuv",
                    "--raw-width", "640", "--raw-height", "360", "--adb", "adb"]
            self.assertEqual(encoder_bench.main(argv), 0)
            records = [json.loads(line) for line in (out / "results.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual([record["samples"] for record in records], [self.DURATION] * 4)
        self.assertTrue(all(record["complete"] for record in records))


class DryRunTest(unittest.TestCase):
    def test_a_dry_run_touches_no_phone_and_needs_a_fresh_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            grid = Path(tmp) / "grid.json"
            grid.write_text(json.dumps({"base": {"test_bench_duration_s": 5},
                                        "axes": {"test_bench_codec": ["av1", "vp9"]}}), encoding="utf-8")
            out = Path(tmp) / "out"
            argv = ["--serial", "SERIAL", "--out", str(out), "--grid", str(grid), "--raw-remote", "/sdcard/x.yuv",
                    "--raw-width", "640", "--raw-height", "360", "--dry-run", "--adb", "adb-not-run"]
            self.assertEqual(encoder_bench.main(argv), 0)
            manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
            self.assertTrue(manifest["dry_run"])
            self.assertEqual(manifest["cells"], 2)
            records = [json.loads(line) for line in (out / "results.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertEqual([r["complete"] for r in records], [False, False])
            self.assertEqual(records[1]["extras"]["test_bench_codec"], "vp9")
            self.assertEqual(records[0]["extras"]["test_raw_yuv"], "/sdcard/x.yuv")
            with self.assertRaises(SystemExit):
                encoder_bench.main(argv)  # the directory is no longer empty


if __name__ == "__main__":
    unittest.main()


class AppPidsNoProcessTest(unittest.TestCase):
    def test_pidof_exit_1_means_no_pids(self):
        class Phone:
            def adb(self, *args, **kwargs):
                raise RuntimeError("adb shell pidof io.github.meshlive.app exited 1: ")
        self.assertEqual(encoder_bench.app_pids(Phone(), "io.github.meshlive.app"), set())

    def test_other_adb_failures_still_raise(self):
        class Phone:
            def adb(self, *args, **kwargs):
                raise RuntimeError("adb shell pidof io.github.meshlive.app exited 255: device offline")
        with self.assertRaises(RuntimeError):
            encoder_bench.app_pids(Phone(), "io.github.meshlive.app")
