#!/usr/bin/env python3
"""Reproducible-build check: compare two APKs entry by entry, or build the same commit twice and compare.

F-Droid's reproducible-build verification (https://f-droid.org/docs/Reproducible_Builds/) strips the signature
from the developer's APK and compares it with its own build of the same source: every entry other than the
META-INF signature files must have identical bytes. This tool does the same comparison locally, plus the
checks that explain a mismatch (entry order, zip timestamps, compression, build paths embedded in entries).

Usage (from the repository root):
    python -B tools/check_reproducible_build.py compare A.apk B.apk [--tree-a DIR --tree-b DIR]
    python -B tools/check_reproducible_build.py build --tree-a DIR_A --tree-b DIR_B [--task :app:assembleFdroidRelease]
        [--apk-glob "app/build/outputs/apk/fdroid/release/*.apk"] [-- extra gradle args]

`build` runs the Gradle task in each tree one after the other (never concurrently: the machine-wide build slot
and RAM limits apply), with the build cache off, forcing an unsigned release (-PreleaseKeystoreProperties=<missing file>, so the release key is never
read), then compares every APK the glob finds in the two trees. Both trees must be separate checkouts of the same
commit at different absolute paths (for example two `git worktree add` folders): a path-dependent build then shows
up as a difference. Exit 0 = identical; 1 = differences; 2 = usage or build error.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

# Entries the signer writes: F-Droid ignores these when it compares its build with the developer's signed APK.
SIGNATURE_RE = re.compile(r"^META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$", re.IGNORECASE)
RAM_LIMIT_PERCENT = 80.0


class ReproError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def read_entries(apk: Path) -> dict:
    """name -> metadata and content hash of one APK (directory entries excluded)."""
    entries = {}
    with zipfile.ZipFile(apk) as archive:
        for order, info in enumerate(archive.infolist()):
            if info.is_dir():
                continue
            entries[info.filename] = {
                "order": order,
                "sha256": sha256_bytes(archive.read(info)),
                "size": info.file_size,
                "compress_type": info.compress_type,
                "date_time": info.date_time,
                "extra": info.extra,
                "external_attr": info.external_attr,
            }
    return entries


def first_difference(data_a: bytes, data_b: bytes) -> int | None:
    if data_a == data_b:
        return None
    for index, (x, y) in enumerate(zip(data_a, data_b)):
        if x != y:
            return index
    return min(len(data_a), len(data_b))


def path_hints(data: bytes, roots) -> list:
    """Which of the given build-tree paths occur in data (as UTF-8, forward and back slashes, URI form)."""
    found = []
    for root in roots:
        text = str(root)
        variants = {text, text.replace("\\", "/"), text.replace("/", "\\"), "file:///" + text.replace("\\", "/")}
        if any(variant.encode("utf-8", "replace") in data for variant in variants):
            found.append(text)
    return found


def compare_apks(apk_a: Path, apk_b: Path, roots=(), ignore_signature: bool = True) -> dict:
    """Entry-level comparison. 'content' = differing bytes (what F-Droid checks); the rest is metadata only."""
    ea, eb = read_entries(apk_a), read_entries(apk_b)
    skip = (lambda name: bool(SIGNATURE_RE.match(name))) if ignore_signature else (lambda name: False)
    names_a = {n for n in ea if not skip(n)}
    names_b = {n for n in eb if not skip(n)}
    only_a, only_b = sorted(names_a - names_b), sorted(names_b - names_a)
    content, metadata = [], []
    with zipfile.ZipFile(apk_a) as za, zipfile.ZipFile(apk_b) as zb:
        for name in sorted(names_a & names_b):
            a, b = ea[name], eb[name]
            if a["sha256"] != b["sha256"]:
                data_a, data_b = za.read(name), zb.read(name)
                content.append({
                    "name": name,
                    "size_a": a["size"],
                    "size_b": b["size"],
                    "first_difference": first_difference(data_a, data_b),
                    "path_hints_a": path_hints(data_a, roots[:1]) if roots else [],
                    "path_hints_b": path_hints(data_b, roots[1:2]) if len(roots) > 1 else [],
                })
            else:
                changed = [key for key in ("compress_type", "date_time", "extra", "external_attr")
                           if a[key] != b[key]]
                if changed:
                    metadata.append({"name": name, "fields": changed})
    order_a = [n for n in sorted(names_a & names_b, key=lambda n: ea[n]["order"])]
    order_b = [n for n in sorted(names_a & names_b, key=lambda n: eb[n]["order"])]
    return {
        "apk_a": str(apk_a), "apk_b": str(apk_b),
        "file_sha256_a": sha256_file(apk_a), "file_sha256_b": sha256_file(apk_b),
        "entries_compared": len(names_a & names_b),
        "only_in_a": only_a, "only_in_b": only_b,
        "content_differences": content,
        "metadata_differences": metadata,
        "order_differs": order_a != order_b,
        "content_identical": not (only_a or only_b or content),
        "bit_identical": sha256_file(apk_a) == sha256_file(apk_b),
    }


def format_report(result: dict) -> str:
    lines = [f"A: {result['apk_a']}  sha256 {result['file_sha256_a']}",
             f"B: {result['apk_b']}  sha256 {result['file_sha256_b']}",
             f"entries compared (signature files excluded): {result['entries_compared']}"]
    for name in result["only_in_a"]:
        lines.append(f"  only in A: {name}")
    for name in result["only_in_b"]:
        lines.append(f"  only in B: {name}")
    for diff in result["content_differences"]:
        hints = diff["path_hints_a"] + diff["path_hints_b"]
        lines.append(f"  CONTENT {diff['name']}: {diff['size_a']} vs {diff['size_b']} bytes, first difference at "
                     f"{diff['first_difference']}" + (f", contains build path {hints}" if hints else ""))
    for diff in result["metadata_differences"]:
        lines.append(f"  metadata {diff['name']}: {', '.join(diff['fields'])}")
    if result["order_differs"]:
        lines.append("  entry order differs")
    verdict = ("BIT-IDENTICAL" if result["bit_identical"]
               else "IDENTICAL CONTENT (container or signature bytes differ)" if result["content_identical"]
               else "DIFFERENT")
    lines.append(f"verdict: {verdict}")
    return "\n".join(lines)


# --- build mode --------------------------------------------------------------

def ram_used_percent() -> float:
    """Used physical memory in percent (Windows GlobalMemoryStatusEx, Linux /proc/meminfo)."""
    if os.name == "nt":
        import ctypes

        class Status(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong),
                        ("total", ctypes.c_ulonglong), ("avail", ctypes.c_ulonglong),
                        ("page_total", ctypes.c_ulonglong), ("page_avail", ctypes.c_ulonglong),
                        ("virt_total", ctypes.c_ulonglong), ("virt_avail", ctypes.c_ulonglong),
                        ("ext", ctypes.c_ulonglong)]

        status = Status()
        status.length = ctypes.sizeof(status)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
        return 100.0 * (1 - status.avail / status.total)
    values = {}
    for line in Path("/proc/meminfo").read_text().splitlines():
        key, _, rest = line.partition(":")
        values[key] = int(rest.split()[0])
    return 100.0 * (1 - values["MemAvailable"] / values["MemTotal"])


def gradle_command(tree: Path, task: str, extra=()) -> list:
    wrapper = tree / ("gradlew.bat" if os.name == "nt" else "gradlew")
    missing_keys = tree / "build" / "no-release-keystore.properties"  # absent on purpose: Gradle then builds unsigned
    # --no-build-cache: gradle.properties enables the build cache, and a cache hit from the first tree would hide
    # a path-dependent output in the second one.
    return [str(wrapper), "--no-daemon", "--no-build-cache", "-Pkotlin.compiler.execution.strategy=in-process",
            f"-PreleaseKeystoreProperties={missing_keys}", task, *extra]


def build_tree(tree: Path, task: str, extra=(), runner=subprocess.run, ram=ram_used_percent) -> None:
    used = ram()
    if used > RAM_LIMIT_PERCENT:
        raise ReproError(f"RAM {used:.0f}% used (limit {RAM_LIMIT_PERCENT:.0f}%): wait and rerun")
    done = runner(gradle_command(tree, task, extra), cwd=tree)
    if done.returncode != 0:
        raise ReproError(f"Gradle failed in {tree} (exit {done.returncode})")


def find_apks(tree: Path, pattern: str) -> list:
    return sorted(tree.glob(pattern))


def run_build_check(tree_a: Path, tree_b: Path, task: str, pattern: str, extra=(), runner=subprocess.run,
                    ram=ram_used_percent, log=print, resume: bool = False) -> list:
    tree_a, tree_b = Path(tree_a).resolve(), Path(tree_b).resolve()
    if tree_a == tree_b:
        raise ReproError("--tree-a and --tree-b must be different directories")
    for tree in (tree_a, tree_b):
        stale = find_apks(tree, pattern)
        if stale and not resume:
            raise ReproError(f"{tree} already has {len(stale)} APK(s) matching {pattern}; start from a clean checkout")
    for tree in (tree_a, tree_b):  # sequential on purpose
        if resume and find_apks(tree, pattern):
            log(f"{tree}: APKs already built, not rebuilding (--resume)")
            continue
        log(f"building {task} in {tree}")
        build_tree(tree, task, extra, runner, ram)
    apks_a, apks_b = find_apks(tree_a, pattern), find_apks(tree_b, pattern)
    if not apks_a or [p.name for p in apks_a] != [p.name for p in apks_b]:
        raise ReproError(f"APK sets differ or are empty: {[p.name for p in apks_a]} vs {[p.name for p in apks_b]}")
    return [compare_apks(a, b, roots=(tree_a, tree_b)) for a, b in zip(apks_a, apks_b)]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    cmp_parser = sub.add_parser("compare")
    cmp_parser.add_argument("apk_a", type=Path)
    cmp_parser.add_argument("apk_b", type=Path)
    cmp_parser.add_argument("--tree-a", type=Path, help="build tree of A, searched for in differing entries")
    cmp_parser.add_argument("--tree-b", type=Path)
    cmp_parser.add_argument("--with-signature", action="store_true", help="also compare META-INF signature files")
    build_parser = sub.add_parser("build")
    build_parser.add_argument("--tree-a", type=Path, required=True)
    build_parser.add_argument("--tree-b", type=Path, required=True)
    build_parser.add_argument("--task", default=":app:assembleFdroidRelease")
    build_parser.add_argument("--apk-glob", default="app/build/outputs/apk/fdroid/release/*.apk")
    build_parser.add_argument("--resume", action="store_true",
                              help="keep APKs already built in a tree (after a refused or interrupted run) and build only the rest")
    build_parser.add_argument("gradle_args", nargs="*", help="extra Gradle arguments after --")
    args = parser.parse_args(argv)
    try:
        if args.mode == "compare":
            roots = [p for p in (args.tree_a, args.tree_b) if p]
            results = [compare_apks(args.apk_a, args.apk_b, roots=roots, ignore_signature=not args.with_signature)]
        else:
            results = run_build_check(args.tree_a, args.tree_b, args.task, args.apk_glob, args.gradle_args,
                                      resume=args.resume)
    except (ReproError, OSError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    for result in results:
        print(format_report(result))
    return 0 if all(r["content_identical"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
