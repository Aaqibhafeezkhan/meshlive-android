import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dex_api_scan  # noqa: E402

API_XML = """<?xml version="1.0" encoding="utf-8"?>
<api version="3">
  <class name="java/io/InputStream" since="1">
    <method name="read()I"/>
    <method name="nullInputStream()Ljava/io/InputStream;" since="33"/>
  </class>
  <class name="java/util/Random" since="1">
    <method name="nextBytes([B)V"/>
  </class>
  <class name="java/security/SecureRandom" since="1">
    <extends name="java/util/Random"/>
    <method name="nextBytes([B)V" since="35"/>
  </class>
  <class name="android/os/VibratorManager" since="31">
    <method name="getDefaultVibrator()Landroid/os/Vibrator;"/>
  </class>
</api>
"""

DUMP = """Class #0            -
  Class descriptor  : 'Lcom/example/bluetoothtest/BluetoothPrivateCallRelay;'
      0b3618: 7100 b243 0000                         |01c8: invoke-static {}, Ljava/io/InputStream;.nullInputStream:()Ljava/io/InputStream; // method@43b2
      0b3620: 6e10 0100 0100                         |01cc: invoke-virtual {v1}, Ljava/io/InputStream;.read:()I // method@0001
Class #1            -
  Class descriptor  : 'Lcom/example/bluetoothtest/Other;'
      0b3640: 6e20 0200 2100                         |0001: invoke-virtual {v1, v2}, Ljava/security/SecureRandom;.nextBytes:([B)V // method@0002
      0b3650: 7100 0300 0000                         |0002: invoke-static {}, Landroid/os/VibratorManager;.getDefaultVibrator:()Landroid/os/Vibrator; // method@0003
      0b3660: 7100 0400 0000                         |0003: invoke-static {}, Lcom/example/bluetoothtest/Own;.helper:()V // method@0004
"""


class DexApiScanTest(unittest.TestCase):
    def table(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "api-versions.xml"
            path.write_text(API_XML, encoding="utf-8")
            return dex_api_scan.ApiTable.load(path)

    def test_a_method_newer_than_min_sdk_is_found_and_an_old_one_is_not(self):
        table = self.table()
        self.assertEqual(table.too_new("java/io/InputStream", "nullInputStream", 29), "method since API 33")
        self.assertIsNone(table.too_new("java/io/InputStream", "nullInputStream", 33))
        self.assertIsNone(table.too_new("java/io/InputStream", "read", 29))
        self.assertIsNone(table.too_new("com/example/Own", "helper", 29))

    def test_an_override_added_later_resolves_to_the_inherited_method(self):
        self.assertIsNone(self.table().too_new("java/security/SecureRandom", "nextBytes", 29))

    def test_a_class_newer_than_min_sdk_is_reported(self):
        self.assertEqual(self.table().too_new("android/os/VibratorManager", "getDefaultVibrator", 29), "class since API 31")

    def test_scan_names_the_calling_app_class(self):
        hits = dex_api_scan.scan_dexdump(DUMP.splitlines(), self.table(), 29)
        self.assertEqual(
            {k: v for k, v in hits.items()},
            {
                ("java/io/InputStream", "nullInputStream", "method since API 33"): {
                    "Lcom/example/bluetoothtest/BluetoothPrivateCallRelay;"
                },
                ("android/os/VibratorManager", "getDefaultVibrator", "class since API 31"): {
                    "Lcom/example/bluetoothtest/Other;"
                },
            },
        )

    def test_the_allow_list_ignores_comments(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "allow.txt"
            path.write_text("# reviewed\njava/io/InputStream.nullInputStream  # guarded\n\n", encoding="utf-8")
            self.assertEqual(dex_api_scan.load_allow(str(path)), {"java/io/InputStream.nullInputStream"})
        self.assertEqual(dex_api_scan.load_allow(None), set())


if __name__ == "__main__":
    unittest.main()
