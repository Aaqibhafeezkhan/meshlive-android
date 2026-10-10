"""rotid_phase0: joins adv_set_churn (advertiser) with adv_address_seen (scanner) lines."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import rotid_phase0 as phase0

NODE = "0a1b2c3d4e5f"


def churn(event, wall, label="BLE control", set_id="s1"):
    return (f"10-10 07:00:00.000  1  2 I BluetoothVideo: BENCHMARK adv_set_churn: label={label} | event={event} | "
            f"set={set_id} | replacement=false | elapsedMs=1 | wallMs={wall}\n")


def seen(address, previous, wall, node=NODE):
    return (f"10-10 07:00:00.000  1  2 I BluetoothVideo: BENCHMARK adv_address_seen: scanner=ab | node={node} | "
            f"version=2 | address={address} | previous={previous} | elapsedMs=1 | wallMs={wall}\n")


class RotidPhase0Test(unittest.TestCase):
    def run_logs(self, advertiser, scanner, **kwargs):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "a.txt").write_text("".join(advertiser), encoding="utf-8")
            (folder / "b.txt").write_text("".join(scanner), encoding="utf-8")
            return phase0.analyse(
                phase0.read_events(folder / "a.txt", "adv_set_churn"),
                phase0.read_events(folder / "b.txt", "adv_address_seen"),
                NODE, kwargs.get("label"), kwargs.get("window_ms", 30_000), kwargs.get("skew_ms", 0),
            )

    def test_restart_with_new_address_and_a_timer_rotation(self):
        advertiser = [churn("start", 1_000), churn("stop", 100_000), churn("start", 102_000, set_id="s2")]
        scanner = [
            seen("AA:01", "none", 2_000),
            seen("AA:02", "AA:01", 50_000),  # stack timer, no restart nearby
            seen("AA:03", "AA:02", 103_000),  # after the restart
        ]
        result = self.run_logs(advertiser, scanner)
        self.assertEqual(result["counts"]["FIRST_SIGHTING"], 1)
        self.assertEqual(result["counts"]["NEW_ADDRESS"], 1)
        self.assertTrue(result["restartChangesAddress"])
        self.assertEqual(result["timerRotations"], 1)
        self.assertEqual(result["status"], "MEASURED")

    def test_same_address_after_restart_is_reported(self):
        advertiser = [churn("start", 1_000), churn("stop", 100_000), churn("start", 101_000, set_id="s2")]
        scanner = [seen("AA:01", "none", 2_000), seen("AA:01", "none", 101_500)]
        result = self.run_logs(advertiser, scanner)
        self.assertEqual(result["counts"]["SAME_ADDRESS"], 1)
        self.assertFalse(result["restartChangesAddress"])

    def test_unseen_and_other_nodes_and_labels_stay_unmeasured(self):
        advertiser = [churn("start", 1_000, label="Discovery")]
        scanner = [seen("BB:01", "none", 2_000, node="ffffffffffff")]
        result = self.run_logs(advertiser, scanner, label="BLE control")
        self.assertEqual(result["restarts"], [])
        self.assertEqual(result["status"], "UNMEASURED")
        result = self.run_logs(advertiser, scanner)
        self.assertEqual(result["counts"]["UNSEEN"], 1)
        self.assertIsNone(result["restartChangesAddress"])

    def test_cli_writes_json(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "a.txt").write_text(churn("start", 1_000), encoding="utf-8")
            (folder / "b.txt").write_text(seen("AA:01", "none", 1_500), encoding="utf-8")
            out = folder / "r.json"
            self.assertEqual(phase0.main(["--advertiser", str(folder / "a.txt"), "--scanner", str(folder / "b.txt"),
                                          "--node", NODE.upper(), "--output", str(out)]), 0)
            self.assertIn('"FIRST_SIGHTING": 1', out.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
