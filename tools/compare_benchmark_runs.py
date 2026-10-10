"""Compare finalized benchmark suite runs case by case so a gain on one pair cannot hide a loss on another.

Read-only over run directories; the only write is the optional --json result. The first run is the
baseline and every later run is compared with it. Join repeats of one build with '+' (RUN_A+RUN_B) to
pool them; repeats of one case inside a side are shown individually and compared by their mean.

Case identity: each NNNN-<hash> execution directory is joined to its plan.json job (the hash is
sha256(case id)[:12]) and to physical/manifest.json's scenario. A bound native-raw case ("raw-<24 hex>")
is mapped to the inventory case it was bound from through, in order: the job suite's own
input_binding.bindings, the binding-manifest.json beside that suite, then any binding manifest or
binding suite in the run tree's benchmarks/catalog. The match key is that original id plus the
scenario's tunables, so a tunable experiment never matches its untuned parent. An unresolvable raw id
stays its own exact key and is flagged.

States per metric: REGRESSED/IMPROVED/SAME outside/inside the noise band below; LOST when the baseline
measured it but the candidate did not, or one pooled candidate repeat did not while every baseline
execution did (e.g. a receiver that never connected); GAINED when only the candidate measured it.
A case is MISSING/NEW when it executed in only one side.

Noise bands (a delta inside the band is SAME), from the ledger's runs:
- Same-build repeats (fix-verification-2cbdafb1-r2/-r3, limits-celloff/-cellon-33e8755e) moved
  visible FPS <= 0.3, p95 <= 0.3 ms, p99 67-83 ms, VMAF <= 0.02 and first surface 57-966 ms.
- The nine requests repeated across trend-3641df77, gradient-9c76a1cc, kfshape-a99f1ef7 and
  callrate-454cf485 (each build changed rate control) moved steady directions by FPS <= 0.6, p95 by
  one 16.7 ms display refresh, VMAF 0.1-1.5 (one +2.9 with the gradient controller), first surface
  <= 260 ms; the same call requests on three builds moved continuity frames 455-470 and kept the
  negotiated 150/180 kbps exactly, with first surface within ~880 ms.
- Link-limited and mesh directions swung far beyond these bands (FPS 1-4, VMAF 3-30, p99 > 1 s,
  freezes 0 <-> 2) between builds and in verify-61405b96-r1/-r2, so a flag there needs a repeat:
  pool repeats with '+'.
Counters are strict: one more unexpected freeze or audio drop is a regression, although link-limited
same-build repeats varied by 8-13 audio drops; such a flag needs a repeat too. Encoder windows and
rate-adaptation counts are reported, not classified: a lower encoder rate is an adaptation choice whose
cost appears in the receiving direction.

Link context per direction (reported, never classified): every "[<child node>]: Child measured this
<transport> link at N kbps" line for the receiving phone's node, from whichever participant is its
parent (a relay logs its own child), and the original sender's "Rate adaptation: Link limited: sending
X of Y kbps video" lines (count, lowest X, whether the last one was "Link recovered"). A REGRESSED
direction whose candidate median measured link is >= 20% below the baseline's, or whose candidate
lowest sent rate is >= 20% below the baseline's (or cut when the baseline never cut), is shown as
"REGRESSED (link -N%)" and listed under REGRESSED WITH SLOWER LINK: the radio link varied (e.g.
callrate-454cf485 CoC 600 measured 461-535 kbps against kfshape-a99f1ef7's 660 kbps, so rate control
cut 600 -> 359 kbps and VMAF fell 87.4 -> 83.2), so repeat it before calling it a code regression.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics

from benchmark_layout import resolve


# name, label, better ("higher"/"lower"), absolute band, relative band (fraction of baseline) or None
METRICS = (
    ("visible_fps", "visible FPS", "higher", 0.7, None),
    ("surface_p95_ms", "surface p95 ms", "lower", 20.0, None),
    ("surface_p99_ms", "surface p99 ms", "lower", 100.0, None),
    ("unexpected_freeze_events", "unexpected freezes", "lower", 0.5, None),
    ("audio_drops", "audio drops", "lower", 0.5, None),
    # device-run-v4-user gates; bands are provisional (no repeat data yet), see THRESHOLDS.
    ("longest_stall_ms", "longest stall ms", "lower", 100.0, None),
    ("frozen_time_share_percent", "frozen time share %", "lower", 0.5, None),
    ("first_surface_ms", "first surface ms", "lower", 1000.0, 0.25),
    ("vmaf", "VMAF mean", "higher", 1.5, None),
    ("call_frames", "call continuity frames", "higher", 15.0, 0.03),
    ("call_freezes", "call continuity freezes", "lower", 0.5, None),
    ("call_freeze_ms", "call freeze ms", "lower", 250.0, None),
    ("effective_kbps", "effective sent kbps", "higher", 10.0, 0.05),
)
THRESHOLDS = {
    "visible_fps": "+-0.7 FPS (about 5% of 15 FPS, the suite's 0.95 visible-FPS fraction); steady repeats moved <= 0.6",
    "surface_p95_ms": "+-20 ms; surface intervals are display-refresh quantized and steady repeats flipped one 16.7 ms step",
    "surface_p99_ms": "+-100 ms; steady repeats moved up to ~85 ms (one 15 FPS frame plus refresh quantization)",
    "unexpected_freeze_events": "strict: any increase of 1 or more",
    "audio_drops": "strict: any increase of 1 or more",
    "longest_stall_ms": "PROVISIONAL +-100 ms (about one 15 FPS frame plus refresh quantization); no repeat data for this metric yet",
    "frozen_time_share_percent": "PROVISIONAL +-0.5 percentage points (half the 1% target); no repeat data for this metric yet",
    "first_surface_ms": "larger of 1000 ms or 25% of baseline; steady broadcasts moved <= 260 ms, calls <= ~880 ms, "
                        "one same-build classic scan repeat 966 ms",
    "vmaf": "+-1.5; steady repeats moved 0.0-1.5",
    "call_frames": "larger of 15 frames or 3%; the same call requests ranged 455-470 frames over three builds",
    "call_freezes": "strict: any increase of 1 or more",
    "call_freeze_ms": "+-250 ms (one freeze threshold); steady calls measured 0 ms",
    "effective_kbps": "larger of 10 kbps or 5%; the negotiated rate was identical across three builds",
}
SCORE_GATES = ("visible_fps", "surface_p95_ms", "surface_p99_ms", "unexpected_freeze_events", "audio_drops",
               "longest_stall_ms", "frozen_time_share_percent")
CONTINUITY = re.compile(r"Private call visible continuity FINAL: (\d+) frames \| (\d+) freeze event\(s\), ([\d.]+) ms")
AUDIO_REASONS = re.compile(r"Audio drop reasons: (.*)$")
EFFECTIVE = re.compile(r"BENCHMARK call effective: .*?\bbitrateKbps=(\d+)")
ENCODER = re.compile(r"Encoder output: ([\d.]+) FPS, ([\d.]+) kbps")
CHILD_LINK = re.compile(r"\[([0-9a-fA-F]{12})\]: Child measured this (.+?) link at (\d+) kbps")
LINK_LIMITED = re.compile(r"[Rr]ate adaptation: Link limited: sending (\d+) of (\d+) kbps")
LINK_RECOVERED = re.compile(r"[Rr]ate adaptation: Link recovered")
SLOWER_LINK = 0.8  # Candidate link or lowest sent rate at most 80% of the baseline's.
CASE_DIRECTORY = re.compile(r"(\d{4})-([0-9a-f]{12})")
ORDER = {"REGRESSED": 0, "REGRESSED_SLOWER_LINK": 1, "LOST": 2, "MISSING": 3, "NEW": 4, "GAINED": 5,
         "UNMEASURED": 6, "IMPROVED": 7, "SAME": 8}
ALARMS = ("REGRESSED", "REGRESSED_SLOWER_LINK", "LOST", "MISSING")


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def optional(path):
    try:
        return read(path)
    except (OSError, ValueError):
        return None


def binding_map(document):
    """Raw case id -> original id from a binding manifest or a suite carrying input_binding."""
    if not isinstance(document, dict):
        return {}
    entries = document.get("bindings")
    if entries is None:
        entries = (document.get("input_binding") or {}).get("bindings", [])
    return {item["case_id"]: item["original_case_id"] for item in entries or []
            if isinstance(item, dict) and item.get("case_id") and item.get("original_case_id")}


def bindings(path, cache):
    key = str(path)
    if key not in cache:
        cache[key] = binding_map(optional(path))
    return cache[key]


def catalog_bindings(run, cache):
    catalog = run.parent.parent / "catalog"
    key = ("catalog", str(catalog))
    if key not in cache:
        found = {}
        if catalog.is_dir():
            for path in sorted(catalog.rglob("*.json")):
                if path.name == "binding-manifest.json":
                    found.update(bindings(path, cache))
                elif "suite" in path.name:
                    try:
                        carries = b'"input_binding"' in path.read_bytes()
                    except OSError:
                        carries = False
                    if carries:
                        found.update(bindings(path, cache))
        cache[key] = found
    return cache[key]


def original_id(case_id, job, run, cache):
    if not case_id or not case_id.startswith("raw-"):
        return case_id, True
    if job and job.get("suite"):
        suite = resolve(job["suite"])
        for path in (suite, suite.parent / "binding-manifest.json"):
            found = bindings(path, cache).get(case_id)
            if found:
                return found, True
    found = catalog_bindings(run, cache).get(case_id)
    return (found, True) if found else (case_id, False)


def match_key(original, scenario):
    tunables = scenario.get("tunables") if isinstance(scenario, dict) else None
    if tunables:
        return f"{original} tunables={json.dumps(tunables, sort_keys=True, separators=(',', ':'))}"
    return original


def log_lines(physical, entries):
    """Application log lines per device; Bluetooth stack logs are never parsed as app evidence."""
    paths = {}
    for entry in entries:
        name = entry.get("path") if isinstance(entry, dict) else None
        if name and entry.get("device") and "-bluetooth-" not in Path(name).name:
            paths.setdefault(entry["device"], []).append(physical / name)
    if not paths:
        for path in sorted(physical.glob("*.log")):
            if "-bluetooth-" not in path.name and "-" in path.stem:
                paths.setdefault(path.stem.rsplit("-", 1)[0], []).append(path)
    lines = {}
    for device, items in paths.items():
        lines[device] = []
        for path in items:
            try:
                lines[device].extend(path.read_text(encoding="utf-8", errors="replace").splitlines())
            except OSError:
                pass
    return lines


def sender_summary(lines):
    windows = [[float(match[1]), float(match[2])] for line in lines if (match := ENCODER.search(line))]
    return {"encoder_windows": windows,
            "encoder_fps_mean": round(statistics.fmean(item[0] for item in windows), 2) if windows else None,
            "encoder_kbps_mean": round(statistics.fmean(item[1] for item in windows), 1) if windows else None,
            "rate_adaptations": sum("Rate adaptation:" in line for line in lines)}


def audio_reasons(lines):
    last = next((match for line in reversed(lines) if (match := AUDIO_REASONS.search(line))), None)
    if not last:
        return None
    reasons = {}
    for part in last[1].split(","):
        name, _, count = part.strip().rpartition(" ")
        if name and count.isdigit():
            reasons[name] = int(count)
    return reasons


def gate_value(gates, name):
    gate = gates.get(name) or {}
    value = gate.get("value")
    if gate.get("status") == "UNMEASURED" or isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def last_match(pattern, lines):
    return next((match for line in reversed(lines) if (match := pattern.search(line))), None)


def link_context(lines, child_node, sender):
    """Child-measured link rates for one receiving node (logged by its parent or relay) and the
    original sender's rate adaptation; context for a regression, never classified itself."""
    measured = [int(match[3]) for rows in lines.values() for line in rows
                if child_node and (match := CHILD_LINK.search(line)) and match[1].lower() == child_node.lower()]
    limited, last = [], None
    for line in lines.get(sender, []):
        if match := LINK_LIMITED.search(line):
            limited.append([int(match[1]), int(match[2])])
            last = "limited"
        elif LINK_RECOVERED.search(line):
            last = "recovered"
    return {"measured_kbps": measured, "limited": limited,
            "ended_recovered": None if last is None else last == "recovered"}


def measure(directory, scenario, manifest, score):
    """Receiving-direction metrics plus per-device sender log summaries for one execution."""
    physical, analysis = directory / "physical", directory / "analysis"
    lines = log_lines(physical, (manifest or {}).get("logs", []))
    nodes = {name: info.get("node_prefix") for name, info in ((manifest or {}).get("devices") or {}).items()
             if isinstance(info, dict)}
    senders = {device: summary for device, rows in lines.items()
               if (summary := sender_summary(rows))["encoder_windows"] or summary["rate_adaptations"]}
    devices = [item for item in score.get("devices", []) if isinstance(item, dict)]
    calls = bool(devices) and all("setup_role" in item for item in devices)
    directions = {}
    for item in devices:
        device, values = item.get("device"), {}
        if calls:
            peer = item.get("peer")
            for name in SCORE_GATES:
                if (value := gate_value((item.get("incoming") or {}).get("gates") or {}, name)) is not None:
                    values[name] = value
            first = gate_value(item.get("gates") or {}, "setup_to_first_surface_ms")
            if final := last_match(CONTINUITY, lines.get(device, [])):
                values.update(call_frames=float(final[1]), call_freezes=float(final[2]), call_freeze_ms=float(final[3]))
            if effective := last_match(EFFECTIVE, lines.get(peer, [])):
                values["effective_kbps"] = float(effective[1])
            else:
                profiles = next((other.get("effective_profiles") for other in devices
                                 if other.get("device") == peer and other.get("effective_profiles")), None)
                if profiles and str(profiles[-1].get("bitrateKbps", "")).isdigit():
                    values["effective_kbps"] = float(profiles[-1]["bitrateKbps"])
        elif item.get("role") == "receiver":
            peer = scenario.get("source")
            for name in SCORE_GATES:
                if (value := gate_value(item.get("gates") or {}, name)) is not None:
                    values[name] = value
            first = gate_value(item.get("gates") or {}, "selection_to_first_surface_ms")
        else:
            continue
        if first is not None:
            values["first_surface_ms"] = first
        direction = f"{peer}-to-{device}"
        reasons = audio_reasons(lines.get(device, []))
        if "audio_drops" not in values and reasons is not None:
            values["audio_drops"] = float(sum(reasons.values()))
        picture = (optional(analysis / "picture" / direction / "vmaf.json") or {}).get("pooled_metrics") or {}
        if isinstance((picture.get("vmaf") or {}).get("mean"), (int, float)):
            values["vmaf"] = float(picture["vmaf"]["mean"])
        directions[direction] = {"values": values, "audio_drop_reasons": reasons,
                                 "link": link_context(lines, nodes.get(device), peer)}
    return directions, senders


def load_run(path, cache):
    run = Path(path).resolve()
    if not run.is_dir():
        raise ValueError(f"Run directory not found: {path}")
    jobs = (optional(run / "plan.json") or {}).get("jobs", [])
    by_hash = {hashlib.sha256(job["case"]["id"].encode()).hexdigest()[:12]: job for job in jobs
               if isinstance(job, dict) and isinstance(job.get("case"), dict) and job["case"].get("id")}
    statuses = {Path(result["evidence_directory"]).name: result.get("status")
                for result in (optional(run / "suite-result.json") or {}).get("results", [])
                if isinstance(result, dict) and result.get("evidence_directory")}
    executions = []
    for directory in sorted(item for item in run.iterdir() if item.is_dir() and CASE_DIRECTORY.fullmatch(item.name)):
        job = by_hash.get(CASE_DIRECTORY.fullmatch(directory.name)[2])
        manifest = optional(directory / "physical/manifest.json")
        scenario = (manifest or {}).get("scenario") or (job or {}).get("case") or {}
        case_id = scenario.get("id")
        score = optional(directory / "physical/score.json")
        original, resolved = original_id(case_id, job, run, cache) if case_id else (None, False)
        directions, senders = measure(directory, scenario, manifest, score) if case_id and score else ({}, {})
        executions.append({
            "run": run.name, "directory": directory.name, "case_id": case_id, "original_case_id": original,
            "key": match_key(original, scenario) if case_id else f"unidentified {run.name}/{directory.name}",
            "resolved": resolved, "status": statuses.get(directory.name) or ("SCORED" if score else "NO SCORE"),
            "mode": (manifest or {}).get("mode") or (job or {}).get("mode") or (
                "private_call" if "caller" in scenario else "broadcast" if scenario else None),
            "transport": scenario.get("transport"), "directions": directions, "senders": senders})
    return {"run": run.name, "path": str(run),
            "apk_sha256": (optional(run / "execution.json") or {}).get("frozen_apk_sha256"),
            "executions": executions}


def load_side(spec, cache):
    """One comparison side: a run directory, or same-build repeats joined with '+'."""
    paths = [spec] if Path(spec).is_dir() else [item for item in spec.split("+") if item]
    runs = [load_run(path, cache) for path in paths]
    return {"run": "+".join(run["run"] for run in runs), "paths": [run["path"] for run in runs],
            "apk_sha256": sorted({run["apk_sha256"] or "unknown" for run in runs}),
            "executions": [execution for run in runs for execution in run["executions"]]}


def pooled(executions):
    """Per direction and metric: one sample per execution (None where unmeasured) and the measured mean."""
    names = {}
    for execution in executions:
        for direction, data in execution["directions"].items():
            names.setdefault(direction, set()).update(data["values"])
    directions = {}
    for direction, metrics in names.items():
        directions[direction] = {}
        for name in metrics:
            samples = [execution["directions"].get(direction, {}).get("values", {}).get(name) for execution in executions]
            measured = [value for value in samples if value is not None]
            directions[direction][name] = {"samples": samples, "mean": statistics.fmean(measured)}
    senders = {}
    for execution in executions:
        for device, data in execution["senders"].items():
            target = senders.setdefault(device, {"encoder_windows": [], "encoder_fps_mean": [],
                                                 "encoder_kbps_mean": [], "rate_adaptations": []})
            target["encoder_windows"].append(data["encoder_windows"])
            for name in ("encoder_fps_mean", "encoder_kbps_mean", "rate_adaptations"):
                if data[name] is not None:
                    target[name].append(data[name])
    return directions, senders


def pooled_link(executions, direction):
    contexts = [execution["directions"][direction]["link"] for execution in executions
                if "link" in execution["directions"].get(direction, {})]
    measured = [value for item in contexts for value in item["measured_kbps"]]
    limited = [pair for item in contexts for pair in item["limited"]]
    return {"executions": len(contexts), "measured_kbps": [item["measured_kbps"] for item in contexts],
            "measured_median_kbps": statistics.median(measured) if measured else None,
            "measured_min_kbps": min(measured) if measured else None,
            "limited_lines": [len(item["limited"]) for item in contexts],
            "lowest_sent_kbps": min(pair[0] for pair in limited) if limited else None,
            "requested_kbps": max(pair[1] for pair in limited) if limited else None,
            "ended_recovered": [item["ended_recovered"] for item in contexts]}


def slower_link(before, after):
    """Why a candidate regression may be the radio link rather than the code, or None."""
    notes = []
    base, cand = before["measured_median_kbps"], after["measured_median_kbps"]
    if base and cand is not None and cand <= base * SLOWER_LINK:
        notes.append(f"link {round((cand / base - 1) * 100):+d}%")
    low_base, low_cand = before["lowest_sent_kbps"], after["lowest_sent_kbps"]
    if low_cand is not None and low_base is None and before["executions"]:
        notes.append(f"rate cut to {low_cand} kbps; baseline never cut")
    elif low_cand is not None and low_base and low_cand <= low_base * SLOWER_LINK:
        notes.append(f"rate min {low_base}->{low_cand} kbps")
    return ", ".join(notes) or None


def classify(name, baseline, candidate):
    _, _, better, absolute, relative = next(item for item in METRICS if item[0] == name)
    band = max(absolute, abs(baseline) * relative) if relative else absolute
    delta = candidate - baseline
    if (delta > band) if better == "lower" else (delta < -band):
        return "REGRESSED"
    if (delta < -band) if better == "lower" else (delta > band):
        return "IMPROVED"
    return "SAME"


def metric_row(name, label, before, after):
    row = {"metric": name, "label": label, "baseline": before, "candidate": after}
    if before is None:
        return {**row, "state": "GAINED", "reason": "measured only in candidate"}
    if after is None:
        return {**row, "state": "LOST", "reason": "measured only in baseline"}
    row["delta"] = after["mean"] - before["mean"]
    row["state"] = classify(name, before["mean"], after["mean"])
    if None in after["samples"] and None not in before["samples"] and row["state"] != "REGRESSED":
        row.update(state="LOST", reason="unmeasured in a candidate repeat")
    return row


def compare(baseline, candidate):
    groups = ({}, {})
    for target, side in zip(groups, (baseline, candidate)):
        for execution in side["executions"]:
            target.setdefault(execution["key"], []).append(execution)
    cases = []
    for key in sorted(set(groups[0]) | set(groups[1])):
        base, cand = groups[0].get(key, []), groups[1].get(key, [])
        sample = (cand or base)[0]
        case = {"key": key, "original_case_id": sample["original_case_id"], "mode": sample["mode"],
                "transport": sample["transport"],
                "baseline_executions": [{name: item[name] for name in ("run", "directory", "case_id", "status", "resolved")}
                                        for item in base],
                "candidate_executions": [{name: item[name] for name in ("run", "directory", "case_id", "status", "resolved")}
                                         for item in cand],
                "directions": [], "senders": {}}
        (base_dirs, base_senders), (cand_dirs, cand_senders) = pooled(base), pooled(cand)
        for direction in sorted(set(base_dirs) | set(cand_dirs)):
            before, after = base_dirs.get(direction, {}), cand_dirs.get(direction, {})
            if base and cand:
                rows = [metric_row(name, label, before.get(name), after.get(name))
                        for name, label, *_ in METRICS if name in before or name in after]
            else:  # One-sided case: show its values; nothing to classify.
                rows = [{"metric": name, "label": label, "baseline": before.get(name), "candidate": after.get(name),
                         "state": "NEW" if cand else "MISSING"} for name, label, *_ in METRICS if name in before or name in after]
            item = {"direction": direction, "metrics": rows,
                    "state": min((row["state"] for row in rows), key=ORDER.get, default="UNMEASURED"),
                    "link": {"baseline": pooled_link(base, direction), "candidate": pooled_link(cand, direction)}}
            if item["state"] == "REGRESSED" and (note := slower_link(item["link"]["baseline"], item["link"]["candidate"])):
                item.update(state="REGRESSED_SLOWER_LINK", slower_link=note)
            case["directions"].append(item)
        if base and cand:
            case["state"] = min((item["state"] for item in case["directions"]), key=ORDER.get, default="UNMEASURED")
        else:
            case["state"] = "NEW" if cand else "MISSING"
        case["senders"] = {device: {"baseline": base_senders.get(device), "candidate": cand_senders.get(device)}
                           for device in sorted(set(base_senders) | set(cand_senders))}
        cases.append(case)
    cases.sort(key=lambda item: (ORDER[item["state"]], item["key"]))
    counts = {}
    for case in cases:
        counts[case["state"]] = counts.get(case["state"], 0) + 1
    return {"baseline": baseline["run"], "candidate": candidate["run"],
            "baseline_apk_sha256": baseline["apk_sha256"], "candidate_apk_sha256": candidate["apk_sha256"],
            "counts": counts, "cases": cases}


def number(value):
    if value is None:
        return "-"
    return f"{value:.0f}" if abs(value) >= 100 or float(value).is_integer() else f"{value:.2f}"


def side_text(entry):
    if entry is None:
        return "-"
    text = number(entry["mean"])
    if len(entry["samples"]) > 1:
        text += " (" + ", ".join(number(item) for item in entry["samples"]) + ")"
    return text


def sender_text(data):
    if not data:
        return "-"
    windows = sum(len(item) for item in data["encoder_windows"])
    fps = statistics.fmean(data["encoder_fps_mean"]) if data["encoder_fps_mean"] else None
    kbps = statistics.fmean(data["encoder_kbps_mean"]) if data["encoder_kbps_mean"] else None
    adaptations = statistics.fmean(data["rate_adaptations"]) if data["rate_adaptations"] else 0
    return f"{windows} windows {number(fps)} FPS {number(kbps)} kbps, {number(adaptations)} rate adaptations"


def link_text(data):
    if not data or not data["executions"]:
        return "-"
    text = (f"measured median {number(data['measured_median_kbps'])} min {number(data['measured_min_kbps'])} kbps"
            if data["measured_median_kbps"] is not None else "measured -")
    text += f", {sum(data['limited_lines'])} rate limits"
    if data["lowest_sent_kbps"] is not None:
        text += f" (lowest {data['lowest_sent_kbps']} of {data['requested_kbps']})"
    ended = [item for item in data["ended_recovered"] if item is not None]
    return text + (", ended " + "/".join("recovered" if item else "limited" for item in ended) if ended else "")


def direction_state(item):
    return f"REGRESSED ({item['slower_link']})" if item.get("slower_link") else item["state"]


def details(case, states, regressed_in=("REGRESSED", "REGRESSED_SLOWER_LINK")):
    """Rows in the given states; REGRESSED rows only from directions in regressed_in."""
    found = [f"{item['direction']} {row['label']} {side_text(row['baseline'])} -> {side_text(row['candidate'])}"
             + (f" ({row['reason']})" if "reason" in row else "")
             + f" {direction_state(item) if row['state'] == 'REGRESSED' else row['state']}"
             for item in case["directions"] for row in item["metrics"]
             if row["state"] in states and (row["state"] != "REGRESSED" or item["state"] in regressed_in)]
    return found + [f"{item['direction']} no metrics on either side" for item in case["directions"]
                    if not item["metrics"] and "UNMEASURED" in states]


def describe(case):
    executions = case["candidate_executions"] or case["baseline_executions"]
    raw = sorted({item["case_id"] for item in executions if item["case_id"] and item["case_id"] != case["original_case_id"]})
    text = case["key"] + (f" [{', '.join(raw)}]" if raw else "")
    return text + (" [original id UNRESOLVED]" if any(not item["resolved"] for item in executions) else "")


def render(result):
    builds = lambda hashes: ",".join(item[:12] for item in hashes)
    lines = [f"== {result['candidate']} (APK {builds(result['candidate_apk_sha256'])}) vs baseline "
             f"{result['baseline']} (APK {builds(result['baseline_apk_sha256'])})"]
    for case in result["cases"]:
        statuses = "/".join(item["status"] for item in case["baseline_executions"]) or "-"
        statuses += " -> " + ("/".join(item["status"] for item in case["candidate_executions"]) or "-")
        state = "REGRESSED (slower link)" if case["state"] == "REGRESSED_SLOWER_LINK" else case["state"]
        lines += ["", f"{state:<10} {describe(case)}  ({case['mode'] or 'unknown'} {case['transport'] or '-'}; "
                      f"suite status {statuses})"]
        for item in case["directions"]:
            lines.append(f"  {item['direction']}: {direction_state(item)}")
            for row in item["metrics"]:
                delta = f"{row['delta']:+.2f}" if "delta" in row else ""
                lines.append(f"    {row['label']:<24}{side_text(row['baseline']):>18} -> {side_text(row['candidate']):<18}"
                             f"{delta:>9}  {row['state']}")
            link = item.get("link") or {}
            if any(data and (data["measured_median_kbps"] is not None or any(data["limited_lines"])
                             or any(flag is not None for flag in data["ended_recovered"])) for data in link.values()):
                lines.append(f"    link (info): {link_text(link['baseline'])} -> {link_text(link['candidate'])}")
        for device, data in case["senders"].items():
            lines.append(f"  sender {device} (info): {sender_text(data['baseline'])} -> {sender_text(data['candidate'])}")
    counts = ", ".join(f"{state.replace('_', ' ')} {count}"
                       for state, count in sorted(result["counts"].items(), key=lambda item: ORDER[item[0]]))
    lines += ["", f"SUMMARY {result['candidate']} vs {result['baseline']}: {counts}"]
    selected = [case for case in result["cases"] if case["state"] == "REGRESSED"]
    lines.append(f"REGRESSED ({len(selected)}):" + ("" if selected else " none"))
    for case in selected:
        lines.append(f"  {describe(case)}")
        lines += [f"    {detail}" for detail in details(case, ("REGRESSED", "LOST"), ("REGRESSED",))]
    selected = [case for case in result["cases"] if any(item["state"] == "REGRESSED_SLOWER_LINK" for item in case["directions"])]
    lines.append(f"REGRESSED WITH SLOWER LINK (repeat to confirm) ({len(selected)}):" + ("" if selected else " none"))
    for case in selected:
        lines.append(f"  {describe(case)}")
        shown = ("REGRESSED",) if case["state"] == "REGRESSED" else ("REGRESSED", "LOST")
        lines += [f"    {detail}" for detail in details(case, shown, ("REGRESSED_SLOWER_LINK",))]
    sections = (("LOST", ("LOST",)), ("MISSING", ()), ("NEW", ()), ("GAINED", ("GAINED",)), ("UNMEASURED", ("UNMEASURED",)))
    for state, shown in sections:
        selected = [case for case in result["cases"] if case["state"] == state]
        label = {"MISSING": "MISSING (executed only in baseline)", "NEW": "NEW (executed only in candidate)",
                 "LOST": "LOST MEASUREMENT", "GAINED": "GAINED MEASUREMENT"}.get(state, state)
        lines.append(f"{label} ({len(selected)}):" + ("" if selected else " none"))
        for case in selected:
            lines.append(f"  {describe(case)}")
            lines += [f"    {detail}" for detail in details(case, shown)]
    for state in ("IMPROVED", "SAME"):
        selected = [describe(case) for case in result["cases"] if case["state"] == state]
        lines.append(f"{state} ({len(selected)}):" + ("" if selected else " none"))
        lines += [f"  {name}" for name in selected]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Compare benchmark runs case by case; every later run is compared with the first (baseline). "
                    "Join same-build repeats with '+'. Exit 2 when any case is REGRESSED (with or without a "
                    "slower link), LOST or MISSING.",
        epilog="Noise bands: " + "; ".join(f"{name}: {text}" for name, text in THRESHOLDS.items()))
    parser.add_argument("baseline", help="Baseline benchmarks/runs/EXECUTION directory (or RUN+RUN)")
    parser.add_argument("candidates", nargs="+", help="Candidate run directories, each compared with the baseline")
    parser.add_argument("--json", help="Write the machine-readable comparison here")
    args = parser.parse_args(argv)
    cache = {}
    try:
        baseline = load_side(args.baseline, cache)
        results = [compare(baseline, load_side(spec, cache)) for spec in args.candidates]
    except ValueError as error:
        parser.exit(2, f"Comparison blocked: {error}\n")
    print("\n\n".join(render(result) for result in results))
    if args.json:
        output = Path(args.json)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps({"schema": "benchmark-run-comparison-v1", "thresholds": THRESHOLDS,
                                      "comparisons": results}, indent=2), encoding="utf-8")
    return 2 if any(result["counts"].get(state) for result in results for state in ALARMS) else 0


if __name__ == "__main__":
    raise SystemExit(main())
