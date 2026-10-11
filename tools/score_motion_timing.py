"""Measure captured callback cadence separately from picture quality and optics.

The reference period is 1e9 / advertised source FPS, never observed receiver
FPS. RMS jitter is sqrt(mean((captured interval - reference period)**2)); it
includes cadence bias. Intervals retain signs, holes and notifications with
unknown/duplicate provenance. Scope/generation transitions are reported apart
from within-phase intervals. No new quality gate or overall score is created.
"""

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re

from benchmark_layout import original, output_path, resolve
from evidence_relocation import logical

if __package__:
    from .score_device_run import records, scope_records
    from .score_presentation_timing import distribution, parse_log
else:
    from score_device_run import records, scope_records
    from score_presentation_timing import distribution, parse_log


def advertised_fps(manifest):
    profile = manifest.get("scenario", {}).get("profile", {})
    value = profile.get("fps", manifest.get("expected_fps"))
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise ValueError("Advertised source FPS must be a finite positive number")
    return value


def cadence_summary(intervals, period_ns):
    values = [item["observed_interval_ns"] for item in intervals]
    deviations = [value - period_ns for value in values] if period_ns is not None else []
    return {
        "state": "MEASURED" if values else "UNMEASURED",
        "captured_notification_interval_ns": distribution(values),
        "reported_timestamp_interval_ns": distribution([item["reported_interval_ns"] for item in intervals]),
        "signed_period_deviation_ns": distribution(deviations),
        "absolute_period_deviation_ns": distribution([abs(value) for value in deviations]),
        "rms_jitter_ns": math.sqrt(sum(value * value for value in deviations) / len(deviations)) if deviations else None,
        "period_deviation_state": "MEASURED" if deviations else "UNMEASURED",
        "nonpositive_intervals": sum(value <= 0 for value in values),
        "intervals_crossing_unusable_trace": sum(item["crosses_unusable_trace"] for item in intervals),
        "intervals": intervals,
    }


def notification_phases(parsed, period_ns):
    phases, boundaries = [], []
    for scope in parsed["scopes"]:
        raw = [row for row in parsed["events"] if row["scope"] == scope["scope"]]
        usable = [row for row in raw if not row["identity_conflict"]]
        conflicts = {row["seq"] for row in raw if row["identity_conflict"]}
        current, phase_by_seq = None, {}
        scope_phases = []
        for row in usable:
            if current is None or current["generation"] != row["generation"]:
                current = {"scope": scope["scope"], "generation": row["generation"],
                           "segment": len(scope_phases) + 1, "rows": [], "intervals": {"CODEC": [], "TEXTURE": []}}
                scope_phases.append(current)
            current["rows"].append(row)
            phase_by_seq[row["seq"]] = current
        for kind in ("CODEC", "TEXTURE"):
            callbacks = [row for row in usable if row["kind"] == kind]
            for before, after in zip(callbacks, callbacks[1:]):
                from_phase, to_phase = phase_by_seq[before["seq"]], phase_by_seq[after["seq"]]
                holes = [hole for hole in scope["missing_event_sequence_ranges"]
                         if before["seq"] < hole["end"] and hole["start"] < after["seq"]]
                unusable = sorted(seq for seq in conflicts if before["seq"] < seq < after["seq"])
                interval = {
                    "kind": kind, "from_seq": before["seq"], "to_seq": after["seq"],
                    "from_observed_ns": before["observedNowNs"], "to_observed_ns": after["observedNowNs"],
                    "observed_interval_ns": after["observedNowNs"] - before["observedNowNs"],
                    "reported_interval_ns": after["reportedNs"] - before["reportedNs"]
                    if after["reportedNs"] is not None and before["reportedNs"] is not None else None,
                    "crosses_unusable_trace": bool(holes or unusable),
                    "missing_sequence_ranges": holes, "conflicting_sequences": unusable,
                }
                if from_phase is to_phase:
                    from_phase["intervals"][kind].append(interval)
                else:
                    boundaries.append({"scope": scope["scope"], "from_generation": from_phase["generation"],
                                       "to_generation": to_phase["generation"], "from_segment": from_phase["segment"],
                                       "to_segment": to_phase["segment"], **interval})
        for phase in scope_phases:
            rows = phase["rows"]
            kinds = {}
            for kind in ("CODEC", "TEXTURE"):
                callbacks = [row for row in rows if row["kind"] == kind]
                matched = [row for row in callbacks if row["match"] == "MATCHED"]
                elapsed = callbacks[-1]["observedNowNs"] - callbacks[0]["observedNowNs"] if len(callbacks) > 1 else 0
                kinds[kind] = {
                    "notification_count": len(callbacks), "matches": dict(Counter(row["match"] for row in callbacks)),
                    "unknown_identity_notifications": sum(row["match"] in {"UNMATCHED", "AMBIGUOUS", "STALE_GENERATION"} for row in callbacks),
                    "duplicate_notifications": sum(row["match"] == "DUPLICATE" for row in callbacks),
                    "captured_notification_rate_hz": (len(callbacks) - 1) * 1e9 / elapsed if elapsed > 0 else None,
                    "notification_cadence_proxy": cadence_summary(phase["intervals"][kind], period_ns),
                    "matched_receiver_clock": {
                        "callback_minus_target_ns": distribution([row["observedMinusTargetNs"] for row in matched]),
                        "reported_minus_target_ns": distribution([row["reportedMinusTargetNs"] for row in matched]),
                        "callback_minus_release_call_ns": distribution([
                            row["observedNowNs"] - row["releaseCallNs"] if row["releaseCallNs"] is not None else None
                            for row in matched]),
                        "callback_minus_reported_ns": distribution([
                            row["observedNowNs"] - row["reportedNs"] if row["reportedNs"] is not None else None
                            for row in matched]),
                    },
                }
            releases = [row for row in rows if row["kind"] == "RELEASE" and row["match"] == "RECORDED"]
            phases.append({
                "scope": phase["scope"], "generation": phase["generation"], "segment": phase["segment"],
                "first_seq": rows[0]["seq"], "last_seq": rows[-1]["seq"], "events": len(rows),
                "release_call_minus_target_ns": distribution([
                    row["releaseCallNs"] - row["targetNs"]
                    if row["releaseCallNs"] is not None and row["targetNs"] is not None else None for row in releases]),
                "callbacks": kinds,
            })
    return phases, boundaries


def received_inactive_ranges(rows):
    anchors, ranges = [], []
    paused = None
    for row in rows:
        match = re.fullmatch(r"BENCHMARK received stream control: state=(LIVE|PAUSED|ENDED) nowNs=(\d+)", row["message"])
        if row["tag"] != "BluetoothVideo" or not match:
            continue
        state, now_ns = match[1], int(match[2])
        anchor = {"state": state, "now_ns": now_ns, "pid": row["pid"], "line": row["line"]}
        if anchors and now_ns < anchors[-1]["now_ns"]:
            return anchors + [anchor], [], "Receiver control clock moved backwards"
        anchors.append(anchor)
        if state == "PAUSED" and paused is None:
            paused = now_ns
        elif state in {"LIVE", "ENDED"} and paused is not None:
            ranges.append({"state": "PAUSED", "start_ns": paused, "end_ns": now_ns})
            paused = None
        if state == "ENDED":
            ranges.append({"state": "ENDED", "start_ns": now_ns, "end_ns": None})
    if paused is not None:
        return anchors, ranges, "Received Pause has no captured Resume or End"
    return anchors, ranges, None


def active_cadence(phases, inactive, period_ns):
    summaries, excluded = [], []
    for phase in phases:
        for kind, callback in phase["callbacks"].items():
            active = []
            for interval in callback["notification_cadence_proxy"]["intervals"]:
                reasons = [item for item in inactive if item["start_ns"] <= interval["to_observed_ns"]
                           and (item["end_ns"] is None or item["end_ns"] >= interval["from_observed_ns"])]
                if reasons:
                    excluded.append({"scope": phase["scope"], "generation": phase["generation"],
                                     "segment": phase["segment"], "kind": kind,
                                     "from_seq": interval["from_seq"], "to_seq": interval["to_seq"],
                                     "observed_interval_ns": interval["observed_interval_ns"], "received_states": reasons})
                else:
                    active.append(interval)
            summary = cadence_summary(active, period_ns)
            summary.pop("intervals")
            summaries.append({"scope": phase["scope"], "generation": phase["generation"],
                              "segment": phase["segment"], "kind": kind, **summary})
    return summaries, excluded


def score_motion(manifest, existing_score, base_path=Path(".")):
    fps = advertised_fps(manifest)
    period_ns = 1e9 / fps if fps is not None else None
    incomplete = []
    logs = manifest.get("logs", [])
    if not logs:
        incomplete.append("Manifest contains no log scopes")
    score_matches = existing_score is not None and existing_score.get("run_id") == manifest.get("run_id")
    if not score_matches:
        incomplete.append("Matching existing score.json is absent")
    if any(not spec.get("end") for spec in logs):
        incomplete.append("At least one captured log has no final end")
    scenario = manifest.get("scenario", {})
    if scenario.get("media_trace"):
        traces = [trace for trace in manifest.get("media_traces", []) if trace.get("kind") != "audio_pcm"]
        participants = {scenario.get("source"), *(node["phone"] for node in scenario.get("receivers", []))}
        finalized = {trace.get("device") for trace in traces
                     if trace.get("state") == "MEASURED" and trace.get("summary", {}).get("finalized") is True}
        if not traces or not participants <= finalized or any(
            trace.get("state") != "MEASURED" or trace.get("summary", {}).get("finalized") is not True for trace in traces
        ):
            incomplete.append("Requested access-unit phases are absent or unfinalized")
    devices, gaps = [], []
    if fps is None:
        gaps.append({"code": "advertised_source_fps_absent"})
    elif manifest.get("expected_fps") is not None and manifest["expected_fps"] != fps:
        gaps.append({"code": "advertised_fps_conflict", "source_profile_fps": fps, "manifest_expected_fps": manifest["expected_fps"]})
    for spec in logs:
        path = resolve(spec["path"], base_path)
        if not path.exists():
            incomplete.append(f"Captured log is absent: {path}")
            continue
        log_bytes = path.read_bytes()
        text = log_bytes.decode("utf-8-sig")
        selected, log_scope = scope_records(records(text), spec)
        selected_lines = {row["line"] for row in selected}
        parsed = parse_log("\n".join(line if number in selected_lines else ""
                                     for number, line in enumerate(text.splitlines(), 1)))
        if any(scope["final_meta"] is None for scope in parsed["scopes"]):
            incomplete.append(f"Presentation observer lacks final META for {spec.get('device')}")
        phases, boundaries = notification_phases(parsed, period_ns)
        anchors, inactive, control_clock_error = received_inactive_ranges(selected)
        active_summaries, inactive_spans = active_cadence(phases, inactive, period_ns) if inactive else ([], [])
        prior = [device for device in (existing_score or {}).get("devices", [])
                 if device.get("device") == spec.get("device") and device.get("role") == spec.get("role")
                 and device.get("scope", {}).get("pid") == log_scope.get("pid")
                 and device.get("scope", {}).get("start") == spec.get("start")
                 and device.get("scope", {}).get("end") == spec.get("end")]
        if score_matches and len(prior) != 1:
            incomplete.append(f"Existing score lacks an exact device/PID/time scope for {spec.get('device')}")
        controls = [dict(item, manifest_field="planned_intervals") for item in manifest.get("planned_intervals", [])
                    if item.get("device") in {spec.get("device"), scenario.get("source")}]
        controls.extend(dict(item, manifest_field="topology_changes") for item in manifest.get("topology_changes", []))
        controls.extend(dict(item, manifest_field="selection_events") for item in manifest.get("selection_events", [])
                        if item.get("device") in {spec.get("device"), scenario.get("source")}
                        and item.get("action", "").lower() in {"pause", "end", "kill", "force_stop"})
        device_gaps = list(parsed["measurement_gaps"])
        if control_clock_error:
            device_gaps.append({"code": "received_control_clock_incomplete", "detail": control_clock_error})
        if log_scope["status"] != "MEASURED":
            device_gaps.append({"code": "unmeasured_pid_time_scope", "detail": log_scope})
        if not parsed["scopes"] and spec.get("role") != "sender" and scenario.get("video_presentation_timing"):
            device_gaps.append({"code": "requested_presentation_observer_absent"})
        if controls and parsed["scopes"]:
            device_gaps.append({"code": "control_wall_time_to_receiver_clock_unmeasured"})
        gaps.extend(dict(item, device=spec.get("device"), pid=log_scope.get("pid")) for item in device_gaps)
        devices.append({
            "device": spec.get("device"), "role": spec.get("role"), "path": str(path),
            "sha256": hashlib.sha256(log_bytes).hexdigest(), "log_scope": log_scope,
            "trace_state": parsed["state"], "identical_snapshot_repeats": parsed["identical_repeated_event_rows"],
            "conflicting_event_identities": parsed["conflicting_event_identities"],
            "scope_summaries": [{key: scope[key] for key in (
                "scope", "final_meta", "latest_meta", "cumulative_event_total", "observed_event_identities",
                "missing_event_sequence_ranges", "measurement_gaps")} for scope in parsed["scopes"]],
            "phases": phases, "boundary_spans": boundaries, "raw_events": parsed["events"],
            "received_state_cadence": {
                "state": "MEASURED_WITH_GAPS" if control_clock_error else "MEASURED" if anchors else "UNMEASURED",
                "anchors": anchors, "inactive_ranges": inactive, "active_summaries": active_summaries,
                "excluded_spans": inactive_spans,
                "scope": "Callback intervals outside exact receiver-clock PAUSED/ENDED ranges; original whole-phase intervals and gates retained. This does not synchronize source commands or prove physical screen timing.",
            },
            "control_attribution": {
                "state": "UNMEASURED" if controls else "NOT_REQUIRED", "controls": controls,
                "reason": "No exact receiver System.nanoTime to control wall-time anchor; retain spans without new unexpected labels",
                "new_excluded_intervals": [],
                "existing_score_exclusions": prior[0].get("evaluation_intervals", []) if len(prior) == 1 and score_matches else [],
            },
            "measurement_gaps": device_gaps,
        })
    measured = any(kind["notification_cadence_proxy"]["state"] == "MEASURED"
                   for device in devices for phase in device["phases"] for kind in phase["callbacks"].values())
    return {
        "schema": "motion-timing-v1", "run_id": manifest.get("run_id"), "advertised_source_fps": fps,
        "advertised_fps_source": "scenario.profile.fps" if "fps" in scenario.get("profile", {}) else "manifest.expected_fps",
        "frame_period_ns": period_ns, "units": {"timing": "signed ns", "notification_rate": "Hz", "existing_score": "unchanged original units"},
        "capture_state": "PARTIAL" if incomplete else "COMPLETE", "incomplete_reasons": incomplete,
        "timing_state": "UNMEASURED" if not measured else "MEASURED_WITH_GAPS" if gaps or incomplete else "MEASURED",
        "measurement_gaps": gaps, "devices": devices, "existing_score": existing_score,
        "existing_score_matches_run": score_matches,
        "glass_to_glass_latency": {"state": "UNMEASURED", "value": None},
        "source_to_screen_latency": {"state": "UNMEASURED", "value": None},
        "definitions": {
            "rms_jitter_ns": "sqrt(mean((captured notification interval - 1e9/advertised source FPS)^2)); includes bias",
            "phase": "maximal event-sequence segment with one scope and observational generation in one PID/time log",
            "cadence": "all unconflicted captured notifications, including unknown/duplicate identities; not unique physical frames",
            "holes": "retain the full captured time span and its trace-gap flag; never interpolate or divide by estimated missing frames",
        },
        "limitations": [
            "Callback cadence is an app notification proxy, not optical smoothness or physical display timing.",
            "Generation transitions are observational, not buffer epochs; boundary spans are retained outside phase statistics.",
            "MATCHED receiver-clock deltas use only logged exact observer associations, not timestamp rematching.",
            "recordRelease follows releaseOutputBuffer; callbacks may precede release history. Exact equality does not prove provenance.",
            "Unsynchronized source and receiver phone clocks cannot establish source-to-screen or glass-to-glass lag.",
            "Original FPS/freezes/startup/rejoin/loss/quality gates remain in existing_score unchanged; no new overall timing PASS.",
        ],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        args.output = output_path(args.output, "runs")
        args.manifest = resolve(args.manifest)
        score_path = resolve(original(args.manifest).with_name("score.json"))
        if logical(args.output) in {logical(args.manifest), logical(score_path)}:
            raise ValueError("Output must differ from the existing manifest and score")
        manifest_bytes = args.manifest.read_bytes()
        score_bytes = score_path.read_bytes() if score_path.exists() else None
        manifest = json.loads(manifest_bytes)
        existing = json.loads(score_bytes) if score_bytes is not None else None
        result = score_motion(manifest, existing, original(args.manifest).parent)
        result["manifest_path"] = str(logical(args.manifest))
        result["manifest_sha256"] = hashlib.sha256(manifest_bytes).hexdigest()
        result["existing_score_path"] = str(logical(score_path))
        result["existing_score_sha256"] = hashlib.sha256(score_bytes).hexdigest() if score_bytes is not None else None
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(f"{result['capture_state']} / {result['timing_state']}: {result['run_id']}; optical lag UNMEASURED. Output: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
