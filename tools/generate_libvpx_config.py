#!/usr/bin/env python3
"""Generate the checked-in libvpx build configuration for the bundled VP9 encoder (DECISIONS "Design VX").

libvpx builds with its own `configure` + make, not CMake. Android (external/libvpx) and Chromium
(third_party/libvpx) both run that configure once per target with `--enable-external-build`, which writes
the configuration headers and lists the enabled sources without probing a compiler, and then compile the
listed files with their own build system. This tool does the same for MeshLive, from the pinned source
that tools/fetch_native_sources.py verifies, and writes per ABI into app/src/main/cpp/libvpx_config/<abi>/:

* vpx_config.h, vpx_config.c, vpx_version.h        (configure / version.sh)
* vp9_rtcd.h, vpx_dsp_rtcd.h, vpx_scale_rtcd.h      (build/make/rtcd.pl: run-time CPU dispatch tables)
* sources.cmake                                     (the enabled .c files, `make libvpx_srcs.txt`)

app/src/main/cpp/CMakeLists.txt compiles exactly those files. The configuration is VP9 encoder only, real-time
only, no VP8, no decoder, no high bit depth, no tools/examples, a 16384x16384 size limit, and Arm NEON
(arm64-v8a: NEON baseline plus dotprod/i8mm kernels picked at run time; armeabi-v7a: NEON intrinsics (no ADS assembly), which every
armeabi-v7a build of the NDK already requires).

Usage (repository root; needs bash and GNU make, e.g. Git for Windows' bash and the NDK's make.exe):
    python tools/generate_libvpx_config.py            # regenerate the checked-in files
    python tools/generate_libvpx_config.py --check    # regenerate in a temp folder and compare
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import fetch_native_sources  # noqa: E402

OUT = ROOT / "app" / "src" / "main" / "cpp" / "libvpx_config"

COMMON_FLAGS = [
    "--enable-external-build",
    "--disable-examples", "--disable-tools", "--disable-docs", "--disable-unit-tests",
    "--disable-install-docs", "--disable-install-bins", "--disable-install-libs", "--disable-install-srcs",
    "--disable-vp8", "--disable-vp9-decoder", "--enable-vp9-encoder", "--disable-vp9-highbitdepth",
    "--enable-realtime-only", "--disable-webm-io", "--disable-libyuv", "--enable-pic",
    "--enable-multithread", "--size-limit=16384x16384",
]

# ABI -> configure target and target-specific flags. Only the ABIs app/build.gradle.kts can produce.
TARGETS = {
    "arm64-v8a": ["--target=armv8-linux-gcc", "--enable-runtime-cpu-detect", "--disable-sve", "--disable-sve2"],
    "armeabi-v7a": ["--target=armv7-linux-gcc", "--enable-neon", "--disable-neon-asm", "--disable-runtime-cpu-detect"],
}

GENERATED = ["vpx_config.h", "vpx_config.c", "vpx_version.h", "vp9_rtcd.h", "vpx_dsp_rtcd.h", "vpx_scale_rtcd.h"]
MAKE_TARGETS = ["libvpx_srcs.txt", "vpx_version.h", "vp9_rtcd.h", "vpx_dsp_rtcd.h", "vpx_scale_rtcd.h"]


class GenerateError(RuntimeError):
    pass


def find_make() -> str:
    found = shutil.which("make")
    if found:
        return found
    props = ROOT / "local.properties"
    sdk = None
    if props.is_file():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk = Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    sdk = sdk or Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    for ndk in sorted((sdk / "ndk").glob("*"), reverse=True) if sdk and (sdk / "ndk").is_dir() else []:
        for host in ("windows-x86_64", "linux-x86_64", "darwin-x86_64"):
            candidate = ndk / "prebuilt" / host / "bin" / ("make.exe" if host.startswith("windows") else "make")
            if candidate.is_file():
                return str(candidate)
    raise GenerateError("GNU make not found (PATH or the Android NDK's prebuilt make)")


def find_bash() -> str:
    # On Windows, PATH's bash.exe may be the WSL launcher, which cannot see Windows paths the same way.
    git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git" / "bin" / "bash.exe"
    if os.name == "nt" and git_bash.is_file():
        return str(git_bash)
    found = shutil.which("bash")
    if not found:
        raise GenerateError("bash not found (Git for Windows' bash on Windows)")
    return found


def sources_cmake(abi: str, listing: str) -> str:
    files = sorted({line.strip() for line in listing.splitlines() if line.strip().endswith(".c")})
    files = [name for name in files if name != "vpx_config.c"]  # generated, compiled from the config folder
    unexpected = [line.strip() for line in listing.splitlines()
                  if line.strip().endswith((".S", ".s", ".asm", ".cc", ".cpp"))]
    if unexpected:
        raise GenerateError(f"{abi}: assembly/C++ sources are not handled: {unexpected[:5]}")
    body = "\n".join(f"    {name}" for name in files)
    return (
        "# Generated by tools/generate_libvpx_config.py from the pinned libvpx source. Do not edit.\n"
        f"# ABI {abi}: {len(files)} C files, relative to VPX_SOURCE_DIR.\n"
        f"set(VPX_SOURCES\n{body}\n)\n"
    )


def generate_abi(abi: str, source: Path, out_dir: Path, make: str, bash: str) -> None:
    with tempfile.TemporaryDirectory(prefix=f"libvpx-{abi}-", ignore_cleanup_errors=True) as work:
        source_arg = source.resolve().as_posix()
        args = [bash, f"{source_arg}/configure", *TARGETS[abi], *COMMON_FLAGS]
        done = subprocess.run(args, cwd=work, capture_output=True, text=True)
        if done.returncode != 0:
            raise GenerateError(f"{abi}: configure failed: {(done.stdout + done.stderr)[-800:]}")
        done = subprocess.run([make, *MAKE_TARGETS], cwd=work, capture_output=True, text=True)
        if done.returncode != 0:
            raise GenerateError(f"{abi}: make failed: {(done.stdout + done.stderr)[-800:]}")
        out_dir.mkdir(parents=True, exist_ok=True)
        for name in GENERATED:
            text = (Path(work) / name).read_text(encoding="utf-8").replace("\r\n", "\n")
            (out_dir / name).write_text(text, encoding="utf-8", newline="\n")
        listing = (Path(work) / "libvpx_srcs.txt").read_text(encoding="utf-8")
        (out_dir / "sources.cmake").write_text(sources_cmake(abi, listing), encoding="utf-8", newline="\n")


def generate(out: Path, source: Path, make: str, bash: str) -> None:
    for abi in TARGETS:
        generate_abi(abi, source, out / abi, make, bash)


def compare(expected: Path, actual: Path) -> list[str]:
    differences = []
    for abi in TARGETS:
        for name in [*GENERATED, "sources.cmake"]:
            a, b = expected / abi / name, actual / abi / name
            if not a.is_file():
                differences.append(f"{abi}/{name} is missing")
            elif a.read_bytes() != b.read_bytes():
                differences.append(f"{abi}/{name} differs from a fresh generation")
    return differences


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="regenerate in a temp folder and compare")
    parser.add_argument("--source", type=Path, default=ROOT / fetch_native_sources.NATIVE_SOURCES["libvpx"]["dest"])
    parser.add_argument("--make", help="GNU make (default: PATH, else the NDK's prebuilt make)")
    parser.add_argument("--bash", help="bash (default: Git for Windows' bash on Windows, else PATH)")
    args = parser.parse_args(argv)
    try:
        reason = fetch_native_sources.verify("libvpx", ROOT) if args.source == ROOT / fetch_native_sources.NATIVE_SOURCES["libvpx"]["dest"] else None
        if reason is not None:
            raise GenerateError(f"pinned libvpx does not verify: {reason}")
        make = args.make or find_make()
        bash = args.bash or find_bash()
        if args.check:
            with tempfile.TemporaryDirectory(prefix="libvpx-config-", ignore_cleanup_errors=True) as temp:
                generate(Path(temp), args.source, make, bash)
                differences = compare(OUT, Path(temp))
            for line in differences:
                print(line)
            print("libvpx config: " + ("FAIL" if differences else "OK"))
            return 1 if differences else 0
        generate(OUT, args.source, make, bash)
        print(f"libvpx config written to {OUT.relative_to(ROOT)}")
        return 0
    except (GenerateError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
