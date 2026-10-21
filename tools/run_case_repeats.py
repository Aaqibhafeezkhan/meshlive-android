"""Repeat exact benchmark cases as separate executions, each into its own fresh run folder.

The suite runner executes each exact request once per run and skips a verified PASS when given a state, so identical
requests cannot be listed twice in one bundle (bind_benchmark_inputs.py refuses duplicate raw requests). This driver
calls `run_benchmark_suite.py run` once per case and round, in the given order (for an A/B: alternate the arms within a
round), each time with a new direct `benchmarks/runs/PREFIX-rNN-CASE` folder and without a state file, so every repeat
is its own execution with its own manifest, purpose review and cleanup.

Dry run by default: it prints the commands. `--execute` runs them one after another and prints each exit code. All
other arguments after `--` go to every run unchanged (devices, owner, phones, APK hash, adb, parallel ...).

    python -B tools/run_case_repeats.py --suite BUNDLE --case A --case B --rounds 8 --case-rounds C=4 \\
        --run-prefix sr-renewal-ab-1a2b3c4d -- --devices tools/benchmark_devices.json --owner NAME \\
        --phones m31,a15s --apk-sha256 SHA --adb ADB --parallel 1
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESERVED = {"--suite", "--case", "--output", "--state"}


def plan(cases, rounds, case_rounds, prefix):
    """The (round, case, output) list in execution order: round by round, cases in the given order."""
    if not cases:
        raise ValueError("At least one --case is required")
    if len(set(cases)) != len(cases):
        raise ValueError("A case is listed twice; repeats come from --rounds")
    unknown = set(case_rounds) - set(cases)
    if unknown:
        raise ValueError(f"--case-rounds names cases that are not selected: {sorted(unknown)}")
    if rounds < 1 or any(value < 0 for value in case_rounds.values()):
        raise ValueError("Rounds must be positive")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", prefix):
        raise ValueError("The run prefix must be a plain folder name")
    steps = []
    for number in range(1, max([rounds, *case_rounds.values()]) + 1):
        for case in cases:
            if number <= case_rounds.get(case, rounds):
                steps.append((number, case, f"benchmarks/runs/{prefix}-r{number:02d}-{safe(case)}"))
    return steps


def translate(cases, case_rounds, binding_manifest):
    """Source case IDs (in their A/B order) to the bound IDs of a bind_benchmark_inputs.py binding manifest."""
    mapping = {}
    for row in binding_manifest.get("bindings", []):
        if row.get("original_case_id") in mapping:
            raise ValueError(f"{row['original_case_id']} is bound twice")
        mapping[row["original_case_id"]] = row["case_id"]
    missing = [case for case in [*cases, *case_rounds] if case not in mapping]
    if missing:
        raise ValueError(f"Not in the binding manifest: {missing}")
    return [mapping[case] for case in cases], {mapping[case]: rounds for case, rounds in case_rounds.items()}


def safe(case):
    return re.sub(r"[^A-Za-z0-9._-]", "_", case)


def command(suite, case, output, passthrough):
    return [sys.executable, "-B", str(ROOT / "tools/run_benchmark_suite.py"), "run", "--suite", suite, "--case", case,
            "--output", output, *passthrough]


def parse_case_rounds(values):
    result = {}
    for value in values:
        name, _, count = value.partition("=")
        if not name or not count.isdigit():
            raise ValueError(f"--case-rounds wants CASE=N, got {value!r}")
        result[name] = int(count)
    return result


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    passthrough = []
    if "--" in argv:
        index = argv.index("--")
        argv, passthrough = argv[:index], argv[index + 1:]
    if RESERVED & set(passthrough):
        raise SystemExit(f"These are set per execution and cannot be passed through: {sorted(RESERVED & set(passthrough))}")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--case", action="append", default=[], help="Exact case ID; the order is the order within a round")
    parser.add_argument("--rounds", type=int, required=True)
    parser.add_argument("--case-rounds", action="append", default=[], help="CASE=N: this case runs only in rounds 1..N")
    parser.add_argument("--run-prefix", required=True, help="Run folder prefix, e.g. sr-renewal-ab-<first 8 of the APK hash>")
    parser.add_argument("--binding", type=Path,
                        help="binding-manifest.json of a bound suite: --case and --case-rounds then name the source cases")
    parser.add_argument("--execute", action="store_true", help="Run the commands (default: print them)")
    parser.add_argument("--stop-on-failure", action="store_true", help="Stop at the first non-zero exit")
    args = parser.parse_args(argv)
    cases, case_rounds = args.case, parse_case_rounds(args.case_rounds)
    if args.binding:
        cases, case_rounds = translate(cases, case_rounds, json.loads(args.binding.read_text(encoding="utf-8")))
    steps = plan(cases, args.rounds, case_rounds, args.run_prefix)
    for number, case, output in steps:
        existing = ROOT / output
        if existing.exists():
            raise SystemExit(f"{output} exists: every repeat needs a fresh run folder")
    failures = 0
    for number, case, output in steps:
        cmd = command(args.suite, case, output, passthrough)
        print(f"round {number:02d} {case} -> {output}", flush=True)
        if not args.execute:
            print("  " + " ".join(cmd), flush=True)
            continue
        code = subprocess.call(cmd, cwd=ROOT)
        print(f"  exit={code}", flush=True)
        if code:
            failures += 1
            if args.stop_on_failure:
                return code
    print(f"executions={len(steps)} | nonzero={failures} | executed={args.execute}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
