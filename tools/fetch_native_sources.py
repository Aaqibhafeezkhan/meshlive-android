#!/usr/bin/env python3
"""Fetch the pinned native sources (libaom, libvpx, libopus) into the git-ignored reference-repos/ folder.

The Android native library (app/src/main/cpp/CMakeLists.txt) statically links three third-party
sources that are not stored in this repository:

* libaom (AV1 encoder): https://aomedia.googlesource.com/aom at a pinned commit. The commit is
  fetched with git and verified twice: `git rev-parse HEAD` must equal the pinned commit, and the
  SHA-256 of a deterministic tree manifest (mode, SHA-256 of every blob, path; see
  tree_manifest_sha256) must equal NATIVE_SOURCES["libaom"]["tree_sha256"]. The manifest hashes the
  committed blob bytes, so it is identical on every OS and checkout setting.
* libvpx v1.17.0 (VP9 encoder only, DECISIONS "Design VX"): https://chromium.googlesource.com/webm/libvpx
  at the commit the v1.17.0 tag points to, verified the same way as libaom. Its build configuration is
  generated from this source by tools/generate_libvpx_config.py and checked in under app/src/main/cpp/libvpx_config/.
* libopus 1.5.2 (experimental native call audio): the official release tarball, verified against the
  SHA-256 published in https://downloads.xiph.org/releases/opus/SHA256SUMS.txt before extraction.

Usage (from the repository root; needs git and network access once):
    python tools/fetch_native_sources.py              # fetch whatever is missing, verify everything
    python tools/fetch_native_sources.py --verify-only
    python tools/fetch_native_sources.py --force      # replace existing folders after verification fails

tools/make_release.py records native_provenance() in SOURCE-MANIFEST.txt, and the F-Droid recipe in
distribution/fdroid/ pins the same sources.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = "reference-repos"

NATIVE_SOURCES = {
    "libaom": {
        "kind": "git",
        "url": "https://aomedia.googlesource.com/aom",
        "commit": "0006e96cdb595ff88950a2c664115335ca5517bf",
        "tree_sha256": "941c31896fe12d3f8a6be1e60e3987a33f2d676475d08fc07235af80f52968a9",
        "dest": f"{REFERENCE}/libaom",
        "licence": "BSD-2-Clause plus the Alliance for Open Media Patent License 1.0",
    },
    "libvpx": {
        "kind": "git",
        "url": "https://chromium.googlesource.com/webm/libvpx",
        # Tag v1.17.0 (2026-08-20, annotated tag object 82615ff0733cd4063838c352bc2e2ab225f37ade).
        "commit": "6df3ec34557879fff673706f4a1d9fbd0f3a6f0e",
        "tree_sha256": "92a551aba58c9a12929a5a2e86e90f2550503673126c706734c42fcc913b2984",
        "dest": f"{REFERENCE}/libvpx",
        "licence": "BSD-3-Clause plus the WebM project's additional VP8/VP9 patent rights grant (PATENTS)",
    },
    "libopus": {
        "kind": "tarball",
        "url": "https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz",
        "sha256": "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1",
        "strip": "opus-1.5.2",
        "dest": f"{REFERENCE}/libopus",
        "licence": "BSD-3-Clause",
        "marker": ("package_version", 'PACKAGE_VERSION="1.5.2"'),
    },
}
STAMP = ".meshlive-source-sha256"


class FetchError(RuntimeError):
    pass


def _git(args, cwd, input_bytes=None):
    done = subprocess.run(["git", *args], cwd=cwd, input=input_bytes, capture_output=True)
    if done.returncode != 0:
        tail = done.stderr.decode("utf-8", "replace").strip()[-400:]
        raise FetchError(f"git {' '.join(args[:3])} failed ({done.returncode}): {tail}")
    return done.stdout


def tree_manifest(repo: Path, rev: str = "HEAD") -> str:
    """'<mode> <sha256> <path>' per committed blob, sorted by path, from the git objects (not the checkout)."""
    raw = _git(["ls-tree", "-r", "-z", rev], repo)
    entries = []
    for record in raw.split(b"\0"):
        if not record:
            continue
        meta, path = record.split(b"\t", 1)
        mode, kind, sha = meta.decode().split()
        if kind == "blob":
            entries.append((path.decode("utf-8"), mode, sha))
    unique = sorted({sha for _, _, sha in entries})
    out = _git(["cat-file", "--batch"], repo, ("\n".join(unique) + "\n").encode() if unique else b"")
    digests, pos = {}, 0
    for sha in unique:
        eol = out.index(b"\n", pos)
        header = out[pos:eol].decode().split()
        if len(header) != 3 or header[0] != sha or header[1] != "blob":
            raise FetchError(f"unexpected cat-file header for {sha}: {header}")
        size = int(header[2])
        digests[sha] = hashlib.sha256(out[eol + 1:eol + 1 + size]).hexdigest()
        pos = eol + 1 + size + 1
    return "".join(f"{mode} {digests[sha]} {path}\n" for path, mode, sha in sorted(entries))


def tree_manifest_sha256(repo: Path, rev: str = "HEAD") -> str:
    return hashlib.sha256(tree_manifest(repo, rev).encode("utf-8")).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# --- verification ------------------------------------------------------------

def verify(name: str, root: Path = ROOT) -> str | None:
    """None when the folder holds exactly the pinned source, else a reason."""
    spec = NATIVE_SOURCES[name]
    dest = root / spec["dest"]
    if not dest.is_dir():
        return f"{spec['dest']} is missing"
    if spec["kind"] == "git":
        if not (dest / ".git").exists():
            return f"{spec['dest']} is not a git checkout"
        try:
            head = _git(["rev-parse", "HEAD"], dest).decode().strip()
            if head != spec["commit"]:
                return f"{spec['dest']} is at {head}, expected {spec['commit']}"
            got = tree_manifest_sha256(dest)
            dirty = _git(["status", "--porcelain", "--untracked-files=no"], dest).decode().strip()
        except FetchError as error:
            return str(error)
        if got != spec["tree_sha256"]:
            return f"{spec['dest']} tree manifest SHA-256 {got} != pinned {spec['tree_sha256']}"
        if dirty:
            return f"{spec['dest']} has modified tracked files"
        return None
    stamp = dest / STAMP
    if not stamp.is_file() or stamp.read_text(encoding="utf-8").strip() != spec["sha256"]:
        return f"{spec['dest']} was not extracted from the pinned tarball (missing or different {STAMP})"
    marker, expected = spec["marker"]
    if expected not in (dest / marker).read_text(encoding="utf-8", errors="replace"):
        return f"{spec['dest']}/{marker} does not contain {expected}"
    return None


# --- fetching ----------------------------------------------------------------

def _replace(dest: Path, force: bool):
    if dest.exists():
        if not force:
            raise FetchError(f"{dest} exists but does not verify; rerun with --force to replace it")
        shutil.rmtree(dest)


def fetch_git(spec: dict, root: Path, force: bool):
    dest = root / spec["dest"]
    _replace(dest, force)
    dest.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".fetch-", dir=dest.parent))
    try:
        _git(["init", "-q"], staging)
        _git(["config", "core.autocrlf", "false"], staging)
        _git(["fetch", "-q", "--depth", "1", spec["url"], spec["commit"]], staging)
        _git(["checkout", "-q", "--detach", "FETCH_HEAD"], staging)
        staging.rename(dest)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def _safe_members(archive: tarfile.TarFile, strip: str):
    for member in archive.getmembers():
        parts = Path(member.name).parts
        if not parts or parts[0] != strip:
            raise FetchError(f"unexpected tarball entry {member.name!r}")
        relative = Path(*parts[1:]) if len(parts) > 1 else None
        if relative is None:
            continue
        if relative.is_absolute() or ".." in relative.parts:
            raise FetchError(f"unsafe tarball entry {member.name!r}")
        if not (member.isfile() or member.isdir()):
            raise FetchError(f"tarball entry {member.name!r} is not a regular file or folder")
        member.name = relative.as_posix()
        yield member


def fetch_tarball(spec: dict, root: Path, force: bool, opener=urllib.request.urlopen):
    dest = root / spec["dest"]
    with opener(spec["url"], timeout=120) as response:
        payload = response.read()
    got = hashlib.sha256(payload).hexdigest()
    if got != spec["sha256"]:
        raise FetchError(f"{spec['url']} SHA-256 {got} != pinned {spec['sha256']}; nothing was extracted")
    _replace(dest, force)
    dest.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".fetch-", dir=dest.parent))
    try:
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
            members = list(_safe_members(archive, spec["strip"]))
            if hasattr(tarfile, "data_filter"):
                archive.extractall(staging, members=members, filter="data")
            else:  # Python < 3.11.4; members were checked above
                archive.extractall(staging, members=members)
        (staging / STAMP).write_text(spec["sha256"] + "\n", encoding="utf-8")
        staging.rename(dest)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def ensure(name: str, root: Path = ROOT, force: bool = False, log=print) -> None:
    reason = verify(name, root)
    if reason is None:
        log(f"{name}: OK ({NATIVE_SOURCES[name]['dest']})")
        return
    log(f"{name}: {reason}; fetching")
    spec = NATIVE_SOURCES[name]
    (fetch_git if spec["kind"] == "git" else fetch_tarball)(spec, root, force or not (root / spec["dest"]).exists())
    reason = verify(name, root)
    if reason is not None:
        raise FetchError(f"{name}: fetched source does not verify: {reason}")
    log(f"{name}: fetched and verified")


def native_provenance() -> list[str]:
    """Lines for the release SOURCE-MANIFEST (tools/make_release.py)."""
    aom, vpx, opus = NATIVE_SOURCES["libaom"], NATIVE_SOURCES["libvpx"], NATIVE_SOURCES["libopus"]
    return [
        f"native-libaom: {aom['url']} commit {aom['commit']} tree-manifest-sha256 {aom['tree_sha256']}",
        f"native-libvpx: {vpx['url']} commit {vpx['commit']} tree-manifest-sha256 {vpx['tree_sha256']}",
        f"native-libopus: {opus['url']} sha256 {opus['sha256']}",
    ]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--only", choices=sorted(NATIVE_SOURCES), action="append",
                        help="limit to one source (repeatable)")
    parser.add_argument("--verify-only", action="store_true", help="check, never download")
    parser.add_argument("--force", action="store_true", help="replace folders that fail verification")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    parser.add_argument("--print-tree-sha256", type=Path, metavar="REPO", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if args.print_tree_sha256:
        print(tree_manifest_sha256(args.print_tree_sha256))
        return 0
    names = args.only or sorted(NATIVE_SOURCES)
    failed = False
    for name in names:
        try:
            if args.verify_only:
                reason = verify(name, args.root)
                print(f"{name}: {'OK' if reason is None else 'FAIL: ' + reason}")
                failed |= reason is not None
            else:
                ensure(name, args.root, args.force)
        except (FetchError, OSError) as error:
            print(f"{name}: ERROR: {error}", file=sys.stderr)
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
