import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_tools  # noqa: E402


class DefaultAdbTest(unittest.TestCase):
    def test_path_wins(self):
        with mock.patch("shutil.which", return_value="/x/adb"):
            self.assertEqual(android_tools.default_adb(), "/x/adb")

    def test_sdk_environment_then_plain_name(self):
        name = "adb.exe" if os.name == "nt" else "adb"
        with tempfile.TemporaryDirectory() as tmp:
            tools = Path(tmp) / "platform-tools"
            tools.mkdir()
            (tools / name).write_bytes(b"")
            with mock.patch("shutil.which", return_value=None), \
                 mock.patch.dict(os.environ, {"ANDROID_HOME": tmp}, clear=False):
                self.assertEqual(android_tools.default_adb(), str(tools / name))
            with mock.patch("shutil.which", return_value=None), \
                 mock.patch.dict(os.environ, {"ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""}, clear=False):
                self.assertEqual(android_tools.default_adb(), "adb")


if __name__ == "__main__":
    unittest.main()
