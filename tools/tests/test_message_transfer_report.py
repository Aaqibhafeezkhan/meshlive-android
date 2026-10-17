"""Phase 0 message-transfer report: parses the app's BENCHMARK message_transfer lines offline."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
SPEC = importlib.util.spec_from_file_location("message_transfer_report", Path(__file__).parents[1] / "message_transfer_report.py")
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)

SESSION = (
    "10-12 10:00:01.123  100  200 I BluetoothVideo: BENCHMARK message_transfer session peer=abc initiator=true outcome=COMPLETE "
    "opened=0 completed=0 dup=0 poisoned=0 transport=GATT setupMs=2300 helloMs=40 durationMs=9000 chunksOut=40 bytesOut=327000 "
    "chunksIn=0 bytesIn=0 wireOut=330000 wireIn=900 resumedChunks=0 firstOutMs=300 lastOutMs=8900 firstInMs=-1 lastInMs=-1 "
    "goodputOutKbps=304 goodputInKbps=0 sessionOutKbps=290 sessionInKbps=0 appState=importance=foreground(100),interactive=1,doze=0,powersave=0->importance=foreground(100),interactive=1,doze=0,powersave=0"
)
BACKGROUND = SESSION.replace("foreground(100)", "cached(400)").replace("interactive=1", "interactive=0")
APP = "10-12 10:00:05.000  100  200 I BluetoothVideo: BENCHMARK message_transfer app event=pause importance=cached(400),interactive=0,doze=1,powersave=0 atNs=5"
BUNDLE = "x BENCHMARK message_transfer bundle dir=out id=0123456789abcdef class=2 chunks=40/40 chunkBytes=327000 firstMs=300 lastMs=8900 complete=true transport=GATT goodputKbps=304"


class MessageTransferReportTest(unittest.TestCase):
    def write(self, directory, name, lines):
        path = Path(directory) / name
        path.write_text("\n".join(lines) + "\nunrelated line\n", encoding="utf-8")
        return path

    def test_parse_and_summarise_per_transport(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "a.log", [SESSION, BUNDLE, APP, BACKGROUND])
            rows = report.parse_log(path, "s24")
            self.assertEqual([r["kind"] for r in rows], ["session", "bundle", "app", "session"])
            self.assertEqual(rows[0]["bytesOut"], 327000)
            self.assertEqual(rows[0]["transport"], "GATT")
            self.assertEqual(rows[0]["logTime"], "10-12 10:00:01.123")
            summary = report.summarise(rows)
            self.assertEqual(summary["sessions"], 2)
            gatt = summary["transports"]["GATT"]
            self.assertEqual(gatt["exchanges"], 2)
            self.assertEqual(gatt["goodputKbpsMedian"], 304)
            self.assertEqual(gatt["setupMsMedian"], 2300)
            self.assertEqual(len(summary["exchangesWithAppStateNotForeground"]), 1)
            self.assertEqual(summary["lifecycleEvents"][0]["event"], "pause")

    def test_chat_session_reports_the_active_rate_next_to_the_diluted_goodput(self):
        # b1-mv2 asus chat session: 1 MB in ~28 s inside a 266 s span of one text every 3 s.
        chat = SESSION.replace("goodputOutKbps=304", "goodputOutKbps=32").replace(
            "sessionInKbps=0 appState", "sessionInKbps=0 activeOutKbps=290 activeInKbps=0 bundles=41 appState")
        with tempfile.TemporaryDirectory() as directory:
            rows = report.parse_log(self.write(directory, "a.log", [chat, SESSION]), "asus")
            self.assertEqual(rows[0]["activeOutKbps"], 290)
            gatt = report.summarise(rows)["transports"]["GATT"]
            self.assertEqual(gatt["activeKbpsMedian"], 290)
            self.assertEqual(gatt["goodputKbpsMin"], 32)

    def test_incomplete_and_tiny_exchanges_do_not_set_a_rate(self):
        tiny = SESSION.replace("bytesOut=327000", "bytesOut=1000")
        interrupted = SESSION.replace("outcome=COMPLETE", "outcome=INTERRUPTED")
        with tempfile.TemporaryDirectory() as directory:
            rows = report.parse_log(self.write(directory, "a.log", [tiny, interrupted]), "m31")
            self.assertEqual(report.summarise(rows)["transports"], {})

    def test_cli_writes_a_fresh_output_only(self):
        with tempfile.TemporaryDirectory() as directory:
            log = self.write(directory, "a.log", [SESSION])
            out = Path(directory) / "report.json"
            self.assertEqual(report.main(["--log", f"s24={log}", "--output", str(out)]), 0)
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(data["summary"]["sessions"], 1)
            with self.assertRaises(SystemExit):
                report.main(["--log", f"s24={log}", "--output", str(out)])


if __name__ == "__main__":
    unittest.main()
