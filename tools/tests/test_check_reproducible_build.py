import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_reproducible_build as crb


def write_apk(path, entries, date=(2009, 1, 1, 0, 0, 0), order=None):
    names = order or list(entries)
    with zipfile.ZipFile(path, "w") as archive:
        for name in names:
            info = zipfile.ZipInfo(name, date)
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, entries[name])


BASE = {"classes.dex": b"dex" * 20, "lib/arm64-v8a/libx.so": b"\x7fELF" + b"A" * 40,
        "resources.arsc": b"arsc", "META-INF/CERT.SF": b"sig-a", "META-INF/CERT.RSA": b"rsa-a"}


class CompareTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="crb-test-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))

    def apk(self, name, entries=None, **kw):
        path = self.dir / name
        write_apk(path, entries if entries is not None else BASE, **kw)
        return path

    def test_identical(self):
        result = crb.compare_apks(self.apk("a.apk"), self.apk("b.apk"))
        self.assertTrue(result["bit_identical"] and result["content_identical"])
        self.assertIn("BIT-IDENTICAL", crb.format_report(result))

    def test_signature_files_ignored(self):
        other = dict(BASE, **{"META-INF/CERT.SF": b"sig-b", "META-INF/CERT.RSA": b"rsa-b"})
        result = crb.compare_apks(self.apk("a.apk"), self.apk("b.apk", other))
        self.assertTrue(result["content_identical"])
        self.assertFalse(result["bit_identical"])
        strict = crb.compare_apks(self.apk("a.apk"), self.apk("b.apk", other), ignore_signature=False)
        self.assertFalse(strict["content_identical"])

    def test_content_difference_reports_build_path(self):
        tree_a, tree_b = Path("D:/trees/a"), Path("D:/trees/b")
        a = dict(BASE, **{"lib/arm64-v8a/libx.so": b"\x7fELF" + b"D:/trees/a/src/x.c\0"})
        b = dict(BASE, **{"lib/arm64-v8a/libx.so": b"\x7fELF" + b"D:/trees/b/src/x.c\0"})
        result = crb.compare_apks(self.apk("a.apk", a), self.apk("b.apk", b), roots=(tree_a, tree_b))
        self.assertFalse(result["content_identical"])
        diff, = result["content_differences"]
        self.assertEqual(diff["name"], "lib/arm64-v8a/libx.so")
        self.assertEqual(diff["first_difference"], 4 + len("D:/trees/") )
        self.assertTrue(diff["path_hints_a"] and diff["path_hints_b"])
        self.assertIn("DIFFERENT", crb.format_report(result))

    def test_missing_entry_and_metadata_only_difference(self):
        smaller = {k: v for k, v in BASE.items() if k != "resources.arsc"}
        result = crb.compare_apks(self.apk("a.apk"), self.apk("b.apk", smaller))
        self.assertEqual(result["only_in_a"], ["resources.arsc"])
        later = self.apk("c.apk", date=(2020, 5, 5, 1, 2, 4))
        meta = crb.compare_apks(self.apk("a.apk"), later)
        self.assertTrue(meta["content_identical"])
        self.assertFalse(meta["bit_identical"])
        self.assertEqual({d["fields"][0] for d in meta["metadata_differences"]}, {"date_time"})

    def test_entry_order_flagged(self):
        result = crb.compare_apks(self.apk("a.apk"), self.apk("b.apk", order=list(reversed(list(BASE)))))
        self.assertTrue(result["order_differs"])
        self.assertTrue(result["content_identical"])


class BuildTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="crb-test-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        self.a, self.b = self.dir / "a", self.dir / "b"

    def fake_runner(self, content_for):
        calls = []

        def run(command, cwd):
            calls.append((command, Path(cwd)))
            out = Path(cwd) / "app/build/outputs/apk/release"
            out.mkdir(parents=True)
            write_apk(out / "app-arm64-v8a-release-unsigned.apk", content_for(Path(cwd)))
            return SimpleNamespace(returncode=0)
        return run, calls

    def test_builds_sequentially_unsigned_and_compares(self):
        runner, calls = self.fake_runner(lambda tree: BASE)
        results = crb.run_build_check(self.a, self.b, ":app:assembleRelease", "app/build/outputs/apk/release/*.apk",
                                      runner=runner, ram=lambda: 50.0, log=lambda *_: None)
        self.assertEqual([c[1] for c in calls], [self.a.resolve(), self.b.resolve()])
        command = calls[0][0]
        self.assertIn("--no-daemon", command)
        self.assertIn("--no-build-cache", command)
        self.assertTrue(any(arg.startswith("-PreleaseKeystoreProperties=") and "no-release-keystore" in arg
                            for arg in command))
        self.assertTrue(results[0]["bit_identical"])

    def test_refuses_high_ram_stale_apks_and_same_tree(self):
        runner, _ = self.fake_runner(lambda tree: BASE)
        with self.assertRaisesRegex(crb.ReproError, "RAM 90%"):
            crb.run_build_check(self.a, self.b, "t", "*.apk", runner=runner, ram=lambda: 90.0, log=lambda *_: None)
        with self.assertRaisesRegex(crb.ReproError, "different directories"):
            crb.run_build_check(self.a, self.a, "t", "*.apk", runner=runner, ram=lambda: 1.0, log=lambda *_: None)
        self.a.mkdir()
        (self.a / "old.apk").write_bytes(b"x")
        with self.assertRaisesRegex(crb.ReproError, "clean checkout"):
            crb.run_build_check(self.a, self.b, "t", "*.apk", runner=runner, ram=lambda: 1.0, log=lambda *_: None)

    def test_resume_builds_only_missing_tree(self):
        runner, calls = self.fake_runner(lambda tree: BASE)
        out = self.a / "app/build/outputs/apk/release"
        out.mkdir(parents=True)
        write_apk(out / "app-arm64-v8a-release-unsigned.apk", BASE)
        results = crb.run_build_check(self.a, self.b, "t", "app/build/outputs/apk/release/*.apk", runner=runner,
                                      ram=lambda: 50.0, log=lambda *_: None, resume=True)
        self.assertEqual([c[1] for c in calls], [self.b.resolve()])
        self.assertTrue(results[0]["bit_identical"])

    def test_gradle_failure(self):
        with self.assertRaisesRegex(crb.ReproError, "exit 3"):
            crb.build_tree(self.a, "t", runner=lambda c, cwd: SimpleNamespace(returncode=3), ram=lambda: 1.0)


if __name__ == "__main__":
    unittest.main()
