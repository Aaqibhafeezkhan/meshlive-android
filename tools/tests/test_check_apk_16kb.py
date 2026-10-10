"""check_apk_16kb: synthetic ELF64 shared objects and APK zips."""
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_apk_16kb as check


def elf64(load_align, relro_end):
    """One PT_LOAD with the given alignment and one PT_GNU_RELRO ending at relro_end."""
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4] = 2
    header[5] = 1
    struct.pack_into("<Q", header, 0x20, 64)
    struct.pack_into("<HH", header, 0x36, 56, 2)
    load = struct.pack("<IIQQQQQQ", check.PT_LOAD, 5, 0, 0, 0, 4096, 4096, load_align)
    relro = struct.pack("<IIQQQQQQ", check.PT_GNU_RELRO, 4, 0, 0, 0, 0, relro_end, 1)
    return bytes(header) + load + relro


class Check16KbTest(unittest.TestCase):
    def test_elf_alignment_and_relro_end(self):
        self.assertEqual(([16384], [0], True), check.check_elf(elf64(16384, 0x8000)))
        self.assertFalse(check.check_elf(elf64(4096, 0x8000))[2])
        # The failing layout seen on the pre-fix Debug build: RELRO ended 8192 past a 16 KB boundary.
        aligns, remainders, ok = check.check_elf(elf64(16384, 0x6000))
        self.assertEqual([0x6000 % 16384], remainders)
        self.assertFalse(ok)

    def test_apk_zip_alignment_requires_stored_and_offset(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "a.apk"
            good = elf64(16384, 0x8000)
            with zipfile.ZipFile(apk, "w") as archive:
                # Filler sized so the next stored entry's data starts on a 16 KB boundary:
                # data offset = (30 + len("filler") + F) + 30 + len(name).
                name = "lib/arm64-v8a/libx.so"
                info = zipfile.ZipInfo(name)
                info.compress_type = zipfile.ZIP_STORED
                filler = 16384 - (30 + len("filler")) - 30 - len(name)
                archive.writestr("filler", bytes(filler), compress_type=zipfile.ZIP_STORED)
                archive.writestr(info, good)
            results = check.check_apk(str(apk))
            self.assertEqual(1, len(results))
            self.assertTrue(results[0]["elfOk"])
            self.assertTrue(results[0]["zipOk"], results[0])

            compressed = Path(folder) / "b.apk"
            with zipfile.ZipFile(compressed, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("lib/arm64-v8a/libx.so", good)
            # Compressed libraries are extracted at install: only the ELF layout matters.
            result = check.check_apk(str(compressed))[0]
            self.assertFalse(result["stored"])
            self.assertTrue(result["zipOk"])
            self.assertEqual(0, check.main([str(compressed)]))

            misaligned = Path(folder) / "c.apk"
            with zipfile.ZipFile(misaligned, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("filler", bytes(7))
                archive.writestr("lib/arm64-v8a/libx.so", good)
            self.assertFalse(check.check_apk(str(misaligned))[0]["zipOk"])
            self.assertEqual(1, check.main([str(misaligned)]))


if __name__ == "__main__":
    unittest.main()
