"""Check or checkpoint exact offline state and its derived index; never contact phones."""
import argparse
import collections
import copy
import hashlib
import json
from pathlib import Path
import tempfile

from benchmark_results import LEDGER, ROOT, render
from benchmark_layout import resolve


def read(path, root=ROOT):
    return json.loads(resolve(path, root=root).read_text(encoding="utf-8-sig"))


def reference(path, root):
    path = resolve(path, root=root)
    return {"path": path.relative_to(root).as_posix(),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def impacts(state):
    changes = state["changes"] if "changes" in state else [item for row in state["cases"] for item in row.get("changes", [])]
    result = {}
    for change in changes:
        change = {key: value for key, value in change.items() if key != "needs_retest"}
        if change["id"] in result and result[change["id"]] != change:
            raise ValueError(f"Changed definition for existing impact: {change['id']}")
        result[change["id"]] = change
    return result


def validate_state(ledger, root, state=None):
    from run_benchmark_suite import load_jobs, request_key, select_remaining, verify_result
    current = ledger["current_inventory"]
    inventory = read(root / current["inventory"]["path"], root)
    if state is None:
        state = read(root / current["state"]["path"], root)
    if state.get("schema") != "benchmark-case-state-v1":
        raise ValueError("Unsupported case-state schema")
    if not set(inventory["required_phones"]) <= set(inventory["selected_phones"]):
        raise ValueError("Shared checkpoints require the complete required phone inventory")
    registry = read(root / "tools/benchmark_devices.json", root)["devices"]
    jobs = load_jobs(root / current["bundle"]["path"], registry)
    rows = {row["case_id"]: row for row in state["cases"]}
    if len(rows) != len(state["cases"]) or set(rows) != {job["case"]["id"] for job in jobs}:
        raise ValueError("Current state must contain every exact generated case once")
    select_remaining(state, ())  # Validates states; an empty selection never means completion.
    for job in jobs:
        row = rows[job["case"]["id"]]
        if row["request_key"] != request_key(job["case"]) or set(row["phones"]) != set(job["phones"]):
            raise ValueError(f"Current state request differs: {job['case']['id']}")
        if row["state"] == "PASS" and row.get("freshness") == "NEEDS RETEST":
            raise ValueError(f"Stale result cannot be a current PASS: {job['case']['id']}")
        if row["state"] == "PASS" and not verify_result(row.get("last_result") or {}, job,
                                                          expected_apk=state.get("candidate_apk_sha256")):
            raise ValueError(f"Unverified current PASS: {job['case']['id']}")
    counts = dict(collections.Counter(row["state"] for row in state["cases"]))
    if counts != state["summary"]:
        raise ValueError("Exact state summary disagrees with its cases")
    return inventory, state, counts


def checkpoint(ledger_path, output, state_path=None, inventory_dir=None, reports=(), root=ROOT,
               replace_state=False, phones=None):
    root, output = Path(root).resolve(), Path(output).resolve()
    ledger_path = resolve(ledger_path, root=root)
    original = ledger_path.read_bytes()
    ledger = json.loads(original)
    render(ledger_path, output, root, ledger)  # Reject changed earlier evidence before updating anything.
    candidate = copy.deepcopy(ledger)
    current = candidate["current_inventory"]
    state_bytes = None
    state_target = resolve(current["state"]["path"], root=root)
    previous_state_bytes = state_target.read_bytes()
    if state_path is None and (inventory_dir is not None or reports):
        raise ValueError("An update requires a freshly joined exact state")
    if state_path is not None:
        directory = resolve(inventory_dir, root=root) if inventory_dir else resolve(current["inventory"]["path"], root=root).parent
        for key, name in (("inventory", "inventory.json"), ("bundle", "benchmark-bundle.json"),
                          ("input_requirements", "input_requirements.json")):
            current[key] = reference(directory / name, root)
        if replace_state:
            state_bytes = resolve(state_path, root=root).read_bytes()
            current["state"] = {"path": state_target.relative_to(root).as_posix(),
                                "sha256": hashlib.sha256(state_bytes).hexdigest()}
        else:
            current["state"] = reference(state_path, root)
    inventory, state, counts = validate_state(candidate, root, json.loads(state_bytes) if state_bytes is not None else None)
    previous_state = json.loads(previous_state_bytes)
    previous_rows = {row["case_id"]: row for row in previous_state["cases"]}
    for row in state["cases"]:
        previous = previous_rows.get(row["case_id"], {})
        history_fields = ("last_result", "last_measured_result", "last_verified_apk", "last_verified_apk_evidence")
        if previous.get("request_key") == row["request_key"] and any(
                previous.get(field) and not row.get(field) for field in history_fields):
            raise ValueError("Checkpoint would discard a last measured result; join prior reports as well")
    candidate_impacts = impacts(state)
    if any(candidate_impacts.get(key) != value for key, value in impacts(previous_state).items()):
        raise ValueError("Checkpoint would discard prior change impacts; join the accumulated changes as well")
    if not set(ledger["current_inventory"]["required_phones"]) <= set(inventory["required_phones"]):
        raise ValueError("Required phones cannot disappear from a shared checkpoint")
    if current["bundle"] != ledger["current_inventory"]["bundle"]:
        removed = {row["case_id"] for row in previous_state["cases"]} - {row["case_id"] for row in state["cases"]}
        retired = {row["id"] for row in inventory.get("retired_requirements", []) if row.get("reason", "").strip()}
        if removed - retired:
            raise ValueError("Removed exact requests require explicit retired_requirements with reasons")
    current.update(required_phones=inventory["required_phones"], selected_phones=inventory["selected_phones"],
                   case_count=len(state["cases"]), states=counts, candidate_apk_sha256=state.get("candidate_apk_sha256"),
                   freshness=dict(collections.Counter(row.get("freshness", "NOT RUN") for row in state["cases"])))
    if state_path is not None:
        candidate.setdefault("historical_capability_bases", copy.deepcopy(candidate.get("capability_bases", {})))
        candidate["capability_bases"] = inventory["capability_provenance"]
        candidate["schema_version"] = 2
        indexed = {item["path"] for item in candidate.get("unified_reports", [])}
        for path in reports:
            pointer = reference(path, root)
            report = read(path, root)
            if any(item.get("status") == "PENDING_ANALYSIS" or item.get("status") == "ANALYZING"
                   and "deferred_analysis" in item for item in report.get("results", [])):
                raise ValueError("Report has cases pending analysis; run finish-analysis before checkpointing")
            review = report.get("artifact_review")
            if review is not None and (review.get("status") != "COMPLETE" or review.get("pending_images") or any(
                    row.get("state") in {"PENDING", "CONSUMED"} for row in review.get("files", {}).values())):
                raise ValueError("Complete the new report's artifact purpose review before checkpointing")
            if review is not None:
                from run_benchmark_suite import validate_artifact_inventory
                validate_artifact_inventory(resolve(path, root=root).parent, review)
            if pointer["path"] not in indexed:
                candidate.setdefault("unified_reports", []).append({**pointer, "state_counts": report["state_counts"],
                    "scope": "Recorded analysis; only exact case/request matches may update current state"})
                indexed.add(pointer["path"])
    page, result = render(ledger_path, output, root, candidate, state_bytes=state_bytes)
    if state_path is None:
        return {"action": "CHECKED", **result, "phone_operations": 0}
    # Both complete documents are prepared and verified before either canonical file changes.
    staged = []
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        documents = [(ledger_path, (json.dumps(candidate, indent=2, allow_nan=False) + "\n").encode("utf-8")),
                     (output, page.encode("utf-8"))]
        if state_bytes is not None:
            documents.insert(0, (state_target, state_bytes))
            if phones is not None:
                from benchmark_case_state import select_remaining
                documents.insert(1, (state_target.with_name("remaining.json"), json.dumps({
                    "state": "NOT RUN", "case_ids": [row["case_id"] for row in select_remaining(state, phones)],
                    "available_owned_phones": sorted(phones),
                    "blocked_cases": [row["case_id"] for row in state["cases"] if row["state"] == "BLOCKED"],
                    "reason": "Selection is not execution; BLOCKED cases require a newly proved prerequisite."
                }, indent=2, allow_nan=False).encode("utf-8")))
        for target, raw in documents:
            temporary = target.with_name(target.name + ".checkpoint.tmp")
            with temporary.open("xb") as handle:
                staged.append((temporary, target))
                handle.write(raw)
        if ledger_path.read_bytes() != original:
            raise ValueError("Ledger changed during preparation; retry under exclusive root ownership")
        if state_target.read_bytes() != previous_state_bytes:
            raise ValueError("State changed during preparation; retry under exclusive root ownership")
        for temporary, target in staged:
            temporary.replace(target)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)
    return {"action": "UPDATED", **result, "phone_operations": 0,
            "ledger": reference(ledger_path, root), "index": reference(output, root)}


def join_indexed(ledger, phones, reports=(), changes=(), expected_apk=None, root=ROOT, inventory_dir=None):
    """Reuse all indexed results and impacts; availability never shrinks the inventory."""
    from benchmark_case_state import build_state
    from run_benchmark_suite import load_jobs
    root = Path(root).resolve()
    current = ledger["current_inventory"]
    registry = read(root / "tools/benchmark_devices.json", root)["devices"]
    phones = set(phones)
    if phones - set(registry):
        raise ValueError(f"Unknown available aliases: {sorted(phones - set(registry))}")
    previous = read(current["state"]["path"], root)
    accumulated = impacts(previous)
    for change in [item for path in changes for item in read(path, root)["changes"]]:
        change = {key: value for key, value in change.items() if key != "needs_retest"}
        if change["id"] in accumulated and accumulated[change["id"]] != change:
            raise ValueError(f"Changed definition for existing impact: {change['id']}")
        accumulated[change["id"]] = change
    paths = {resolve(item["path"], root=root) for item in ledger.get("unified_reports", [])}
    paths.update(resolve(path, root=root) for path in reports)
    bundle = resolve(inventory_dir, root=root) / "benchmark-bundle.json" if inventory_dir else resolve(current["bundle"]["path"], root=root)
    state = build_state(load_jobs(bundle, registry), [read(path, root) for path in sorted(paths)],
                        list(accumulated.values()), expected_apk=expected_apk or current["candidate_apk_sha256"])
    for row in state["cases"]:
        row["availability"] = {"all_participants_available": set(row["phones"]) <= phones,
                               "missing_phones": sorted(set(row["phones"]) - phones), "preflight": "UNMEASURED"}
    return state


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "update"))
    parser.add_argument("--state", type=Path, help="Explicit freshly joined complete state.json")
    parser.add_argument("--phones", help="Refresh indexed state in place for these owned aliases; empty means none assigned")
    parser.add_argument("--apk-sha256", help="Candidate for indexed refresh; defaults to the indexed candidate")
    parser.add_argument("--changes", type=Path, action="append", default=[], help="Additional change impacts (repeatable)")
    parser.add_argument("--inventory", type=Path, help="New complete inventory; defaults to the indexed inventory")
    parser.add_argument("--report", action="append", type=Path, default=[], help="Append finalized suite-result.json (repeatable)")
    args = parser.parse_args(argv)
    if args.action == "update" and (args.state is None) == (args.phones is None):
        parser.error("Update requires either --phones for indexed refresh or --state for an explicit join")
    if args.state is not None and (args.changes or args.apk_sha256):
        parser.error("Explicit state must already contain the candidate and change impacts")
    if args.action == "check" and (args.state or args.inventory or args.report or args.phones is not None or args.changes or args.apk_sha256):
        parser.error("Check reads indexed evidence only")
    try:
        if args.phones is None:
            result = checkpoint(LEDGER, ROOT / "benchmarks/index.html", args.state, args.inventory, args.report,
                                replace_state=args.state is not None)
        else:
            checkpoint(LEDGER, ROOT / "benchmarks/index.html")
            phones = set(filter(None, (name.strip() for name in args.phones.split(","))))
            state = join_indexed(read(LEDGER), phones, args.report, args.changes, args.apk_sha256,
                                 inventory_dir=args.inventory)
            with tempfile.TemporaryDirectory(prefix="bluetooth-state-") as scratch:
                path = Path(scratch) / "state.json"
                path.write_text(json.dumps(state, indent=2, allow_nan=False) + "\n", encoding="utf-8")
                result = checkpoint(LEDGER, ROOT / "benchmarks/index.html", path, args.inventory, args.report,
                                    replace_state=True, phones=phones)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, f"Benchmark checkpoint blocked: {error}\n")
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
