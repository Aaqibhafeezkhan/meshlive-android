#!/usr/bin/env python3
"""Find platform calls in a built APK that the app's minSdk does not have (an offline NewApi check).

Why: the JVM that runs the unit tests has every Java 11+ method, D8 does not desugar all of them, and a missing
platform method is a NoSuchMethodError, an Error that `catch (Exception)` does not see and that ends the app
process. rotid-958c8a53-p4-relayed-calls-gatt-probe: the first on-demand relay called
`InputStream.nullInputStream()` (API 33, minSdk 29) and crashed the helper phone (ASUS, Android 11). Lint's
NewApi check finds this class of bug but the project's builds do not run lint; this reads the dex instead.

Method: `dexdump -d` lists every invoke with its declaring class; the SDK's `data/api-versions.xml` says
since which API level each class and method exists. A call whose class or method (and every inherited
method of the same name) is newer than --min-sdk is reported. It cannot see an `SDK_INT` guard, so:
  * java/ and javax/ calls from the app's own classes FAIL (exit 1): a guard around a Java library call is rare
    and should be reviewed, then allow-listed with --allow FILE (one `owner.method` per line, # comments);
  * android/ calls are listed as INFO: they are normally guarded with Build.VERSION.SDK_INT (lint territory).

Usage (from the repository root):
    python -B tools/dex_api_scan.py app-debug.apk [more.apk|.dex ...] [--min-sdk 29] [--package com/example/bluetoothtest]
        [--api-xml PATH] [--dexdump PATH] [--allow FILE] [--json]
Defaults are found under ANDROID_HOME (newest build-tools dexdump, newest platform's data/api-versions.xml).
Scratch is the OS temporary directory. Exit 0 = clean, 1 = unreviewed java/javax findings, 2 = usage error.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

INVOKE = re.compile(r"invoke-\w+(?:/range)? \{[^}]*\}, (L[^;]+;)\.([^:]+):\(")
CLASS_LINE = re.compile(r"Class descriptor\s*:\s*'(L[^;]+;)'")


class ApiTable:
    """Class and method introduction levels from an SDK api-versions.xml."""

    def __init__(self) -> None:
        self.classes: dict[str, dict] = {}

    @classmethod
    def load(cls, path: Path) -> "ApiTable":
        table = cls()
        current = None
        for event, element in ET.iterparse(str(path), events=("start", "end")):
            if event == "start" and element.tag == "class":
                since = int(float(element.get("since", "1")))
                current = {"since": since, "methods": {}, "supers": []}
                table.classes[element.get("name")] = current
            elif event == "end" and current is not None:
                if element.tag == "method":
                    name = element.get("name").split("(")[0]
                    current["methods"].setdefault(name, []).append(int(float(element.get("since", current["since"]))))
                elif element.tag in ("extends", "implements"):
                    current["supers"].append(element.get("name"))
                if element.tag == "class":
                    current = None
                    element.clear()
        return table

    def too_new(self, owner: str, method: str, min_sdk: int) -> str | None:
        """Why the call needs more than min_sdk, or None when it is fine or unknown."""
        info = self.classes.get(owner)
        if info is None:
            return None
        if info["since"] > min_sdk:
            return f"class since API {info['since']}"
        levels = info["methods"].get(method)
        if not levels or min(levels) <= min_sdk:
            return None
        # An override added to a subclass resolves to an older inherited method (SecureRandom.nextBytes -> Random).
        seen = set()
        pending = list(info["supers"])
        while pending:
            name = pending.pop()
            if name in seen:
                continue
            seen.add(name)
            parent = self.classes.get(name)
            if parent is None:
                continue
            inherited = parent["methods"].get(method)
            if inherited and min(inherited) <= min_sdk and parent["since"] <= min_sdk:
                return None
            pending.extend(parent["supers"])
        return f"method since API {min(levels)}"


def scan_dexdump(lines, table: ApiTable, min_sdk: int) -> dict[tuple[str, str, str], set[str]]:
    """(owner, method, reason) -> the classes that call it, from `dexdump -d` text."""
    hits: dict[tuple[str, str, str], set[str]] = {}
    caller = ""
    for line in lines:
        header = CLASS_LINE.search(line)
        if header:
            caller = header.group(1)
            continue
        call = INVOKE.search(line)
        if not call:
            continue
        owner = call.group(1)[1:-1]
        if not owner.startswith(("java/", "javax/", "android/")):
            continue
        reason = table.too_new(owner, call.group(2), min_sdk)
        if reason:
            hits.setdefault((owner, call.group(2), reason), set()).add(caller)
    return hits


def find_sdk_file(kind: str) -> Path | None:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        return None
    root = Path(home)
    if kind == "dexdump":
        for tools in sorted((root / "build-tools").glob("*"), reverse=True):
            for name in ("dexdump.exe", "dexdump"):
                if (tools / name).is_file():
                    return tools / name
        return None
    def level(path: Path) -> int:
        match = re.search(r"android-(\d+)", path.parent.parent.name)
        return int(match.group(1)) if match else 0
    candidates = sorted((root / "platforms").glob("android-*/data/api-versions.xml"), key=level, reverse=True)
    return candidates[0] if candidates else None


def load_allow(path: str | None) -> set[str]:
    if not path:
        return set()
    return {
        line.split("#")[0].strip()
        for line in Path(path).read_text(encoding="utf-8").splitlines()
        if line.split("#")[0].strip()
    }


def scan_inputs(inputs, dexdump: Path, table: ApiTable, min_sdk: int) -> dict[tuple[str, str, str], set[str]]:
    merged: dict[tuple[str, str, str], set[str]] = {}
    with tempfile.TemporaryDirectory(prefix="dex-api-scan-") as scratch:
        dexes = []
        for item in inputs:
            path = Path(item)
            if path.suffix.lower() == ".dex":
                dexes.append(path)
            else:
                with zipfile.ZipFile(path) as apk:
                    for name in apk.namelist():
                        if re.fullmatch(r"classes\d*\.dex", name):
                            target = Path(scratch) / f"{path.stem}-{name}"
                            target.write_bytes(apk.read(name))
                            dexes.append(target)
        for dex in dexes:
            process = subprocess.Popen(
                [str(dexdump), "-d", str(dex)], stdout=subprocess.PIPE, text=True, errors="replace", bufsize=1 << 20,
            )
            for key, callers in scan_dexdump(process.stdout, table, min_sdk).items():
                merged.setdefault(key, set()).update(callers)
            process.wait()
    return merged


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", nargs="+", help="APK or dex files")
    parser.add_argument("--min-sdk", type=int, default=29)
    parser.add_argument("--package", default="com/example/bluetoothtest", help="the app's package, slash form")
    parser.add_argument("--api-xml")
    parser.add_argument("--dexdump")
    parser.add_argument("--allow", help="reviewed java/javax calls: one owner.method per line")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    dexdump = Path(args.dexdump) if args.dexdump else find_sdk_file("dexdump")
    api_xml = Path(args.api_xml) if args.api_xml else find_sdk_file("api")
    if not dexdump or not api_xml or not dexdump.is_file() or not api_xml.is_file():
        print("dexdump or api-versions.xml not found (set ANDROID_HOME or pass --dexdump / --api-xml)", file=sys.stderr)
        return 2
    allowed = load_allow(args.allow)
    prefix = "L" + args.package.strip("/") + "/"
    hits = scan_inputs(args.inputs, dexdump, ApiTable.load(api_xml), args.min_sdk)
    findings, info = [], []
    for (owner, method, reason), callers in sorted(hits.items()):
        app = sorted(c for c in callers if c.startswith(prefix))
        if not app:
            continue
        row = {"call": f"{owner}.{method}", "reason": reason, "app_classes": [c[1:-1] for c in app]}
        if owner.startswith(("java/", "javax/")) and row["call"] not in allowed:
            findings.append(row)
        else:
            info.append(row)
    if args.json:
        print(json.dumps({"min_sdk": args.min_sdk, "findings": findings, "info": info}, indent=2))
    else:
        for row in findings:
            print(f"FAIL {row['call']} ({row['reason']}) called from {', '.join(row['app_classes'][:5])}")
        for row in info:
            print(f"info {row['call']} ({row['reason']}) in {', '.join(row['app_classes'][:3])}")
        print(f"{len(findings)} unreviewed java/javax finding(s), {len(info)} info (min SDK {args.min_sdk})")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
