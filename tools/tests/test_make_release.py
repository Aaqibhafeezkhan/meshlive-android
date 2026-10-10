import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import unittest.mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import make_release as mr

GRADLE = """val appId = (project.findProperty("appId") as String?) ?: "org.example.testapp"
android { defaultConfig {
        applicationId = appId
        versionCode = 7
        versionName = "1.2"
} }
"""
CHANGELOGS = "fastlane/metadata/android/en-US/changelogs"
ABI_CODES = {"armeabi-v7a": 71, "arm64-v8a": 72, "universal": 70}


def certs_output(*digests):
    return "".join(f"Signer #{i} certificate DN: CN=x\nSigner #{i} certificate SHA-256 digest: "
                   f"{d.replace(':', '').lower()}\n" for i, d in enumerate(digests, 1))


def git(root, *args):
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=root,
                   check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def make_repo(root):
    root = Path(root)
    (root / "app").mkdir()
    (root / "app/build.gradle.kts").write_text(GRADLE, newline="\n")
    (root / "a.txt").write_bytes(b"alpha\n")
    (root / "dir").mkdir()
    (root / "dir/b b.bin").write_bytes(bytes(range(256)))
    (root / "empty").write_bytes(b"")
    (root / CHANGELOGS).mkdir(parents=True)
    for code in (71, 72):
        (root / CHANGELOGS / f"{code}.txt").write_text(f"Change {code}\n")
    (root / ".gitignore").write_text("/app/build/\n")
    git(root, "init", "-q")
    git(root, "config", "core.autocrlf", "false")
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "init")


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="make-release-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        make_repo(self.root)

    def test_manifest_hashes_committed_bytes_sorted_and_deterministic(self):
        text = mr.build_manifest(self.root, ("1.2", 7))
        self.assertEqual(text, mr.build_manifest(self.root, ("1.2", 7)))
        lines = text.splitlines()
        self.assertEqual(lines[0], "SOURCE-MANIFEST v1")
        self.assertIn("version: 1.2 (7)", lines)
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()
        self.assertIn(f"git-commit: {commit}", lines)
        self.assertIn("files: 7", lines)
        # The native sources are not tracked; their pins (tools/fetch_native_sources.py) are part of the provenance.
        self.assertTrue(any(l.startswith("native-libaom: ") and "0006e96c" in l for l in lines))
        self.assertTrue(any(l.startswith("native-libopus: ") and "sha256 65c1d2f7" in l for l in lines))
        rows = [l for l in lines[9:]]
        self.assertEqual([r.split("  ", 1)[1] for r in rows],
                         [".gitignore", "a.txt", "app/build.gradle.kts", "dir/b b.bin", "empty",
                          CHANGELOGS + "/71.txt", CHANGELOGS + "/72.txt"])
        self.assertIn(hashlib.sha256(b"alpha\n").hexdigest() + "  a.txt", rows)
        self.assertIn(hashlib.sha256(bytes(range(256))).hexdigest() + "  dir/b b.bin", rows)
        self.assertIn(hashlib.sha256(b"").hexdigest() + "  empty", rows)

    def test_manifest_ignores_worktree_edits_and_untracked_files(self):
        before = mr.build_manifest(self.root, ("1.2", 7))
        (self.root / "a.txt").write_bytes(b"changed\r\n")
        (self.root / "new.txt").write_bytes(b"x")
        self.assertEqual(before, mr.build_manifest(self.root, ("1.2", 7)))

    def test_read_version(self):
        self.assertEqual(mr.read_version(self.root), ("1.2", 7))


class FingerprintTest(unittest.TestCase):
    def test_match_with_and_without_colons_any_case(self):
        out = certs_output(mr.EXPECTED_CERT_SHA256)
        self.assertEqual(mr.require_expected_cert(out), mr.EXPECTED_CERT_SHA256)

    def test_mismatch_refused(self):
        other = "00:" + mr.EXPECTED_CERT_SHA256[3:]
        with self.assertRaisesRegex(mr.ReleaseError, "mismatch"):
            mr.require_expected_cert(certs_output(other))

    def test_any_extra_signer_mismatch_refused(self):
        other = "00:" + mr.EXPECTED_CERT_SHA256[3:]
        with self.assertRaisesRegex(mr.ReleaseError, "mismatch"):
            mr.require_expected_cert(certs_output(mr.EXPECTED_CERT_SHA256, other))

    def test_apksigner_37_v2_v3_format(self):
        digest = mr.EXPECTED_CERT_SHA256.replace(':', '').lower()
        text = "\n".join([f'V2 Signer: certificate DN: CN=x',
                          f'V2 Signer: certificate SHA-256 digest: {digest}',
                          f'V3 Signer: certificate SHA-256 digest: {digest}', ''])
        self.assertEqual(mr.require_expected_cert(text), mr.EXPECTED_CERT_SHA256)
        other = text.replace(digest[:2], '00', 1)
        with self.assertRaisesRegex(mr.ReleaseError, 'mismatch'):
            mr.require_expected_cert(other)

    def test_unsigned_output_refused(self):
        with self.assertRaisesRegex(mr.ReleaseError, "unsigned"):
            mr.require_expected_cert("DOES NOT VERIFY\n")

    def test_bad_fingerprint_text(self):
        with self.assertRaises(mr.ReleaseError):
            mr.normalize_fingerprint("12:34")


def fake_apk(path, abis):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", b"dex")
        for abi in abis:
            archive.writestr(f"lib/{abi}/libx.so", b"so-" + abi.encode())


def ok_inspector(apk):
    with zipfile.ZipFile(apk) as archive:
        return [{"library": n, "ok": True} for n in archive.namelist() if n.endswith(".so")]


def write_gradle_outputs(root, universal=True, codes=None, unsigned=False):
    codes = codes or ABI_CODES
    folder = Path(root) / "app/build/outputs/apk/release"
    folder.mkdir(parents=True, exist_ok=True)
    elements = []
    for abi in ("arm64-v8a", "armeabi-v7a") + (("universal",) if universal else ()):
        name = f"app-{abi}-release" + ("-unsigned" if unsigned else "") + ".apk"
        fake_apk(folder / name, ["arm64-v8a", "armeabi-v7a"] if abi == "universal" else [abi])
        filters = [] if abi == "universal" else [{"filterType": "ABI", "value": abi}]
        elements.append({"filters": filters, "versionCode": codes[abi], "outputFile": name})
    (folder / "output-metadata.json").write_text(json.dumps({"elements": elements}))


class ApksignerTest(unittest.TestCase):
    def test_picks_highest_version_and_reports_missing(self):
        files = {"/sdk/build-tools": ["34.0.0", "36.1.0-rc1", "9.0.0", "37.0.0"],
                 "/sdk/build-tools/37.0.0/apksigner.bat": None,
                 "/sdk/build-tools/36.1.0-rc1/apksigner.bat": None}
        exists = lambda p: p.replace("\\", "/") in files
        listing = lambda p: files[p.replace("\\", "/")]
        found = mr.find_apksigner({"ANDROID_HOME": "/sdk"}, exists, listing, windows=True)
        self.assertEqual(found.replace("\\", "/"), "/sdk/build-tools/37.0.0/apksigner.bat")
        with self.assertRaisesRegex(mr.ReleaseError, "apksigner not found"):
            mr.find_apksigner({"ANDROID_HOME": "/none"}, lambda p: False, listing, windows=True)
        with self.assertRaisesRegex(mr.ReleaseError, "apksigner not found"):
            mr.find_apksigner({}, lambda p: False, listing, windows=True)


class MakeReleaseTest(unittest.TestCase):
    def setUp(self):
        # The fake repository has no reference-repos/; native pin verification is covered by test_fetch_native_sources.
        patcher = unittest.mock.patch.object(mr, "verify_native_sources", lambda root: None)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.tmp = tempfile.TemporaryDirectory(prefix="make-release-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "repo"
        self.root.mkdir()
        make_repo(self.root)
        self.out = Path(self.tmp.name) / "out"
        self.calls = []
        self.certs = certs_output(mr.EXPECTED_CERT_SHA256)

    def runner(self, args, cwd, input_bytes=None):
        self.calls.append(list(args))
        if "assembleRelease" in " ".join(args):
            write_gradle_outputs(cwd, universal="-PuniversalApk=true" in args)
            return b""
        if args[1:2] == ["verify"]:
            return self.certs.encode()
        return mr.run(args, cwd, input_bytes)

    def make(self, **kw):
        kw.setdefault("inspector", ok_inspector)
        return mr.make_release(self.root, out_base=self.out, runner=self.runner,
                               apksigner="apksigner", log=lambda *_: None, **kw)

    def test_dirty_tree_refused_before_build(self):
        (self.root / "a.txt").write_bytes(b"dirty\n")
        with self.assertRaisesRegex(mr.ReleaseError, "dirty"):
            self.make()
        self.assertFalse(any("assembleRelease" in " ".join(c) for c in self.calls))

    def test_untracked_file_counts_as_dirty(self):
        (self.root / "stray.txt").write_bytes(b"x")
        with self.assertRaisesRegex(mr.ReleaseError, "dirty"):
            self.make()

    def test_success_writes_all_outputs(self):
        folder = self.make()
        self.assertEqual(folder, self.out / "1.2-7")
        names = {"arm64-v8a": "MeshLive-1.2-arm64-v8a.apk",
                 "armeabi-v7a": "MeshLive-1.2-armeabi-v7a.apk",
                 "universal": "MeshLive-1.2-universal.apk"}
        sums = (folder / "SHA256SUMS").read_text()
        for name in names.values():
            digest = hashlib.sha256((folder / name).read_bytes()).hexdigest()
            self.assertIn(f"{digest}  {name}\n", sums)
        manifest_digest = hashlib.sha256((folder / "SOURCE-MANIFEST.txt").read_bytes()).hexdigest()
        self.assertIn(f"{manifest_digest}  SOURCE-MANIFEST.txt\n", sums)
        self.assertEqual((folder / "signing-cert.sha256").read_text().strip(), mr.EXPECTED_CERT_SHA256)
        certs = (folder / "apksigner-certs.txt").read_text()
        self.assertEqual(certs.count("certificate SHA-256 digest"), 3)
        self.assertEqual((folder / "SOURCE-MANIFEST.txt").read_text(),
                         mr.build_manifest(self.root, ("1.2", 7)))
        notes = (folder / "RELEASE-NOTES.md").read_text()
        self.assertIn(mr.EXPECTED_CERT_SHA256, notes)
        self.assertIn("org.example.testapp", notes)
        self.assertIn("GPL-3.0-or-later", notes)
        self.assertIn("Change 72", notes)
        self.assertIn(names["armeabi-v7a"], notes)
        gradle = [c for c in self.calls if "assembleRelease" in " ".join(c)][0]
        for flag in ("--no-daemon", "-PsplitAbis=true", "-PenableArmeabiV7a=true"):
            self.assertIn(flag, gradle)

    def test_no_universal(self):
        folder = self.make(universal=False)
        self.assertFalse(list(folder.glob("*universal*")))
        self.assertEqual(sorted(p.name for p in folder.glob("*.apk")),
                         ["MeshLive-1.2-arm64-v8a.apk", "MeshLive-1.2-armeabi-v7a.apk", "MeshLive.apk"])
        self.assertEqual((folder / "MeshLive.apk").read_bytes(), (folder / "MeshLive-1.2-arm64-v8a.apk").read_bytes())

    def test_wrong_version_code_refused(self):
        def runner(args, cwd, input_bytes=None):
            if "assembleRelease" in " ".join(args):
                write_gradle_outputs(cwd, codes={**ABI_CODES, "arm64-v8a": 77})
                return b""
            return mr.run(args, cwd, input_bytes)
        with self.assertRaisesRegex(mr.ReleaseError, "expected 72"):
            mr.make_release(self.root, out_base=self.out, runner=runner, apksigner="x",
                            inspector=ok_inspector, log=lambda *_: None)

    def test_missing_changelog_refused(self):
        (self.root / CHANGELOGS / "71.txt").unlink()
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "-m", "drop changelog")
        with self.assertRaisesRegex(mr.ReleaseError, "71.txt"):
            self.make()

    def test_wrong_abi_inside_apk_refused(self):
        def runner(args, cwd, input_bytes=None):
            if "assembleRelease" in " ".join(args):
                write_gradle_outputs(cwd)
                fake_apk(Path(cwd) / "app/build/outputs/apk/release/app-arm64-v8a-release.apk", ["armeabi-v7a"])
                return b""
            if args[1:2] == ["verify"]:
                return self.certs.encode()
            return mr.run(args, cwd, input_bytes)
        with self.assertRaisesRegex(mr.ReleaseError, "native ABIs"):
            mr.make_release(self.root, out_base=self.out, runner=runner, apksigner="apksigner",
                            inspector=ok_inspector, log=lambda *_: None)

    def fake_one(self, abi):
        path = Path(self.tmp.name) / f"{abi}.apk"
        fake_apk(path, [abi])
        return path

    def test_arm64_16kb_failure_refused_but_32bit_is_not_gated(self):
        bad = lambda apk: [{"library": "lib/arm64-v8a/libx.so", "ok": False}]
        with self.assertRaisesRegex(mr.ReleaseError, "16 KB"):
            mr.check_native_libs(self.fake_one("arm64-v8a"), "arm64-v8a", bad)
        only32 = lambda apk: [{"library": "lib/armeabi-v7a/libx.so", "ok": False}]
        mr.check_native_libs(self.fake_one("armeabi-v7a"), "armeabi-v7a", only32)

    def test_app_id_read(self):
        self.assertEqual(mr.read_app_id(self.root), "org.example.testapp")

    def test_fingerprint_mismatch_leaves_no_release_folder(self):
        self.certs = certs_output("00:" + mr.EXPECTED_CERT_SHA256[3:])
        with self.assertRaisesRegex(mr.ReleaseError, "mismatch"):
            self.make()
        self.assertFalse((self.out / "1.2-7").exists())

    def test_existing_folder_needs_force(self):
        self.make()
        with self.assertRaisesRegex(mr.ReleaseError, "exists"):
            self.make()
        self.make(force=True)

    def test_unsigned_apk_reported(self):
        def runner(args, cwd, input_bytes=None):
            if "assembleRelease" in " ".join(args):
                write_gradle_outputs(cwd, unsigned=True)
                return b""
            return mr.run(args, cwd, input_bytes)
        with self.assertRaisesRegex(mr.ReleaseError, "signing config is missing"):
            mr.make_release(self.root, out_base=self.out, runner=runner, apksigner="x",
                            log=lambda *_: None)

    def test_build_that_dirties_tree_refused(self):
        def runner(args, cwd, input_bytes=None):
            if "assembleRelease" in " ".join(args):
                (Path(cwd) / "a.txt").write_bytes(b"build touched me\n")
                return b""
            return mr.run(args, cwd, input_bytes)
        with self.assertRaisesRegex(mr.ReleaseError, "dirty"):
            mr.make_release(self.root, out_base=self.out, runner=runner, apksigner="x",
                            log=lambda *_: None)


if __name__ == "__main__":
    unittest.main()
