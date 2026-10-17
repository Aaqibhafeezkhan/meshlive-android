import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import attention  # noqa: E402


class AttentionTest(unittest.TestCase):
    def test_wakes_and_vibrates_each_phone_without_touching_volume(self):
        with mock.patch.object(attention, "beep_host"), mock.patch.object(attention.subprocess, "run") as run:
            self.assertEqual(attention.main(["Tap Install", "--serial", "A", "--serial", "B", "--adb", "adb"]), 0)
        commands = [" ".join(call.args[0]) for call in run.call_args_list]
        self.assertEqual(sum("-s A" in c for c in commands), 2)
        self.assertTrue(any("KEYCODE_WAKEUP" in c for c in commands))
        self.assertTrue(any("vibrat" in c for c in commands))
        self.assertFalse(any("volume" in c.lower() for c in commands))

    def test_unreachable_phone_still_exits_zero(self):
        with mock.patch.object(attention, "beep_host"), \
                mock.patch.object(attention.subprocess, "run", side_effect=OSError("no adb")):
            self.assertEqual(attention.main(["x", "--serial", "A", "--adb", "adb"]), 0)


if __name__ == "__main__":
    unittest.main()
