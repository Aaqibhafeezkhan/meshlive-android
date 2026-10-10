import hashlib
import io
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch_native_sources as fns  # noqa: E402


def tarball(entries):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, data in entries.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def spec_for(payload, strip="pkg-1.0"):
    return {"kind": "tarball", "url": "https://example.invalid/pkg.tar.gz", "sha256": hashlib.sha256(payload).hexdigest(),
            "strip": strip, "dest": "reference-repos/pkg", "marker": ("package_version", 'PACKAGE_VERSION="1.0"')}


class TarballTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="fetch-native-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_verified_tarball_is_extracted_without_its_top_folder_and_stamped(self):
        payload = tarball({"pkg-1.0/package_version": b'PACKAGE_VERSION="1.0"\n', "pkg-1.0/src/a.c": b"int a;\n"})
        spec = spec_for(payload)
        fns.fetch_tarball(spec, self.root, force=False, opener=lambda url, timeout: FakeResponse(payload))
        dest = self.root / "reference-repos/pkg"
        self.assertEqual((dest / "src/a.c").read_bytes(), b"int a;\n")
        self.assertEqual((dest / fns.STAMP).read_text(encoding="utf-8").strip(), spec["sha256"])

    def test_hash_mismatch_extracts_nothing(self):
        payload = tarball({"pkg-1.0/package_version": b"x"})
        spec = dict(spec_for(payload), sha256="0" * 64)
        with self.assertRaises(fns.FetchError):
            fns.fetch_tarball(spec, self.root, force=False, opener=lambda url, timeout: FakeResponse(payload))
        self.assertFalse((self.root / "reference-repos/pkg").exists())

    def test_entries_outside_the_top_folder_are_refused(self):
        payload = tarball({"pkg-1.0/ok": b"1", "pkg-1.0/../escape": b"2"})
        with self.assertRaises(fns.FetchError):
            fns.fetch_tarball(spec_for(payload), self.root, force=False,
                              opener=lambda url, timeout: FakeResponse(payload))
        self.assertFalse((self.root / "escape").exists())


class TreeManifestTest(unittest.TestCase):
    def test_manifest_hashes_committed_blobs_not_the_checkout(self):
        with tempfile.TemporaryDirectory(prefix="fetch-native-git-") as tmp:
            repo = Path(tmp)
            git = ["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "core.autocrlf=false"]
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            (repo / "a.txt").write_bytes(b"alpha\n")
            (repo / "dir").mkdir()
            (repo / "dir" / "b.bin").write_bytes(bytes(range(4)))
            subprocess.run(git + ["add", "-A"], cwd=repo, check=True)
            subprocess.run(git + ["commit", "-q", "-m", "x"], cwd=repo, check=True)
            expected = (f"100644 {hashlib.sha256(b'alpha' + bytes([10])).hexdigest()} a.txt\n"
                        f"100644 {hashlib.sha256(bytes(range(4))).hexdigest()} dir/b.bin\n")
            self.assertEqual(fns.tree_manifest(repo), expected)
            before = fns.tree_manifest_sha256(repo)
            (repo / "a.txt").write_bytes(b"changed")
            self.assertEqual(before, fns.tree_manifest_sha256(repo))


class PinsTest(unittest.TestCase):
    def test_pins_are_complete_and_recorded_for_releases(self):
        aom, opus = fns.NATIVE_SOURCES["libaom"], fns.NATIVE_SOURCES["libopus"]
        self.assertRegex(aom["commit"], r"^[0-9a-f]{40}$")
        self.assertRegex(aom["tree_sha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(opus["sha256"], r"^[0-9a-f]{64}$")
        lines = fns.native_provenance()
        self.assertIn(aom["commit"], lines[0])
        self.assertIn(opus["sha256"], lines[1])
        cmake = (Path(fns.ROOT) / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertIn(opus["sha256"], cmake)
        self.assertIn(opus["url"], cmake)


if __name__ == "__main__":
    unittest.main()
