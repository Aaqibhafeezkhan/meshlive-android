"""Verify executed whole-media impairment at the pre-encryption callback.

This measures a requested application simulation, not Bluetooth radio loss or
socket completion. Intentional loss is never a pass of a zero-loss media gate.
"""

import argparse
import hashlib
import json
import math
import re
from collections import Counter
from pathlib import Path

from benchmark_layout import original, output_path, resolve as resolve_input
from evidence_relocation import logical


PREFIX = "BENCHMARK impairment v1 "
SCHEMA = "benchmark-media-impairment-v1"
DECISIONS = {"SEND", "BYPASS", "DROP_PATTERN", "DROP_MAX_WAIT", "DROP_ADMISSION", "DROP_CLOSED", "DROP_INTERRUPTED", "SEND_FAILED"}
PHASE_KEYS = {"id", "start_ms", "end_ms", "delay_ms", "jitter_ms", "rate_bps", "drop_every", "burst_every", "burst_length"}


def _integer(value, name, minimum=0, maximum=None):
    if type(value) is not int or value < minimum or maximum is not None and value > maximum:
        raise ValueError(f"{name} must be an integer in {minimum}..{maximum or 'unbounded'}")
    return value


def _file(entry, base, evidence, name):
    if not isinstance(entry, dict) or not entry.get("path") or not entry.get("sha256"):
        raise ValueError(f"missing hashed {name}")
    path = resolve_input(entry["path"], base=base)
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b""):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    if digest != str(entry["sha256"]).lower():
        raise ValueError(f"{name} SHA256 mismatch")
    evidence[name] = {"path": str(logical(path)), "sha256": digest}
    return path


def validate_config(config):
    if not isinstance(config, dict) or set(config) != {"schema", "seed", "max_wait_ms", "phases"} or config["schema"] != SCHEMA:
        raise ValueError("missing, unknown or invalid impairment config keys/schema")
    _integer(config["seed"], "seed", 0, 2147483646)
    _integer(config["max_wait_ms"], "max_wait_ms", 1, 5000)
    phases = config["phases"]
    if not isinstance(phases, list) or not 1 <= len(phases) <= 100:
        raise ValueError("phases must contain 1..100 entries")
    ids, previous = set(), 0
    for phase in phases:
        if not isinstance(phase, dict) or set(phase) != PHASE_KEYS:
            raise ValueError("missing or unknown impairment phase keys")
        if not isinstance(phase["id"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", phase["id"]) or phase["id"] in ids:
            raise ValueError("invalid or repeated impairment phase id")
        ids.add(phase["id"])
        start = _integer(phase["start_ms"], "start_ms", 0, 86400000)
        end = _integer(phase["end_ms"], "end_ms", 1, 86400000)
        if start < previous or start >= end:
            raise ValueError("impairment phases overlap or are not ordered")
        previous = end
        for key in ("delay_ms", "jitter_ms"):
            _integer(phase[key], key, 0, 5000)
        _integer(phase["rate_bps"], "rate_bps", 0, 1000000000)
        for key in ("drop_every", "burst_every"):
            _integer(phase[key], key, 0, 1000000)
        _integer(phase["burst_length"], "burst_length", 0, phase["burst_every"])
        if bool(phase["burst_every"]) != bool(phase["burst_length"]):
            raise ValueError("burst period and length must both be enabled")
    return config


def _eligible(flags):
    return 0 <= flags <= 127 and not flags & (1|4)


def _distribution(values):
    values = sorted(values)
    def percentile(fraction):
        if not values:
            return None
        position = (len(values)-1) * fraction
        lower, upper = math.floor(position), math.ceil(position)
        return values[lower] + (values[upper]-values[lower]) * (position-lower)
    return {"samples":len(values), "min":min(values) if values else None, "p50":percentile(.5),
            "p95":percentile(.95), "max":max(values) if values else None}


def analyze(spec, base):
    result = {"schema":"benchmark-impairment-trace-v1", "measurement_status":"UNMEASURED", "verdict":"UNMEASURED",
              "evidence":{}, "measurements":[], "scope":"outgoing whole-media admission before encryption",
              "limitations":["This is application-level media simulation, not Bluetooth radio loss or controller behavior.",
                             "Producer blocking changes offered source cadence; byte rates exclude transport/encryption overhead.",
                             "SEND means callback invocation/completion, not socket transmission or receiver delivery.",
                             "Expected simulated loss does not pass zero-unexpected-loss picture, timing or lifecycle gates."]}
    def measurement(name, value=None, passed=None, reason=""):
        result["measurements"].append({"name":name,"family":"transport","value":value,
            "status":"UNMEASURED" if passed is None else "PASS" if passed else "FAIL", "reason":reason})
    try:
        if not isinstance(spec, dict):
            raise ValueError("impairment spec must be an object")
        config_path = _file(spec.get("config"), Path(base), result["evidence"], "config")
        log_path = _file(spec.get("log"), Path(base), result["evidence"], "log")
        config = validate_config(json.loads(config_path.read_text(encoding="utf-8")))
        for key in ("scope", "session"):
            if not isinstance(spec.get(key), str) or not spec[key].strip():
                raise ValueError(f"missing exact {key}")
        pid = _integer(spec.get("pid"), "pid", 1)
        tolerance = _integer(spec.get("timing_tolerance_ns", 0), "timing_tolerance_ns", 0, 1000000000)
        maximum_gap = _integer(spec.get("maximum_offer_gap_ms"), "maximum_offer_gap_ms", 1, 60000) * 1000000
        rows = []
        for line_number, line in enumerate(log_path.read_text(encoding="utf-8").splitlines(), 1):
            if PREFIX not in line:
                continue
            row = json.loads(line.split(PREFIX,1)[1])
            if not isinstance(row, dict):
                raise ValueError(f"line {line_number}: impairment trace row is not an object")
            if row.get("scope") != spec["scope"]:
                continue
            if row.get("session") != spec["session"] or row.get("pid") != pid or row.get("clock") != "System.nanoTime":
                raise ValueError(f"line {line_number}: scope/session/PID/clock identity mismatch")
            if row.get("kind") not in {"BEGIN","OFFER","RESULT","FINAL"}:
                raise ValueError(f"line {line_number}: unknown impairment trace kind")
            rows.append(row)
        begin = [row for row in rows if row["kind"] == "BEGIN"]
        finals = [row for row in rows if row["kind"] == "FINAL"]
        if len(begin) != 1 or len(finals) != 1:
            raise ValueError("exactly one BEGIN and finalized FINAL are required")
        if rows[0]["kind"] != "BEGIN" or rows[-1]["kind"] != "FINAL":
            raise ValueError("impairment events appear outside the BEGIN/FINAL interval")
        first, final = begin[0], finals[0]
        sha = result["evidence"]["config"]["sha256"]
        if any(row.get("schema") != SCHEMA or row.get("config_sha256") != sha for row in (first,final)):
            raise ValueError("trace config schema/hash mismatch")
        if first.get("position") != "outgoing-whole-media-before-encryption" or first.get("radio_impairment") is not False or first.get("producer_blocking") is not True:
            raise ValueError("unknown impairment placement/behavior")
        offers, completed = {}, {}
        packet_fields = ("sequence","pts_us","flags","bytes","offered_ns")
        for row in rows:
            if row["kind"] not in ("OFFER","RESULT"):
                continue
            seq = _integer(row.get("sequence"), "sequence", 1)
            _integer(row.get("pts_us"), "pts_us")
            flags = _integer(row.get("flags"), "flags", 0, 127)
            _integer(row.get("bytes"), "bytes", 0, 4*1024*1024)
            _integer(row.get("offered_ns"), "offered_ns")
            target = offers if row["kind"] == "OFFER" else completed
            if seq in target:
                raise ValueError(f"duplicate {row['kind']} sequence {seq}")
            target[seq] = row
            if row["kind"] == "OFFER":
                if row.get("eligible") is not _eligible(flags):
                    raise ValueError("offered eligibility disagrees with protocol flags")
            elif row.get("decision") not in DECISIONS:
                raise ValueError("unknown impairment result decision")
        if not offers or set(offers) != set(completed) or set(offers) != set(range(1,len(offers)+1)):
            raise ValueError("missing/unmatched OFFER or RESULT sequence")
        for seq, row in completed.items():
            if any(row.get(key) != offers[seq].get(key) for key in packet_fields):
                raise ValueError(f"packet identity mismatch at sequence {seq}")
            _integer(row.get("completed_ns"), "completed_ns", row["offered_ns"])
        counts = Counter(row["decision"] for row in completed.values())
        expected_counts = {"offered":len(offers), "sent":counts["SEND"], "bypassed":counts["BYPASS"],
                           "dropped":sum(v for k,v in counts.items() if k.startswith("DROP_")),
                           "send_failed":counts["SEND_FAILED"], "in_flight":0}
        if any(type(final.get(key)) is not int or final[key] != value for key,value in expected_counts.items()):
            raise ValueError("FINAL cumulative counters disagree with completed events")
        eligible = [row for row in offers.values() if row["eligible"]]
        if not eligible:
            raise ValueError("no eligible offered media; controls alone cannot execute a profile")
        origin = min(row["offered_ns"] for row in eligible)
        if type(final.get("origin_ns")) is not int or final.get("origin_ns") != origin:
            raise ValueError("FINAL origin is not the first eligible media invocation")
        close = _integer(final.get("closed_ns"), "closed_ns", max(row["completed_ns"] for row in completed.values()))
        indexes = sorted(row["plan_index"] for row in completed.values() if row.get("plan_index") is not None)
        if indexes != list(range(1,len(indexes)+1)):
            raise ValueError("missing or duplicated planner index")
        planned = sorted((row for row in completed.values() if row.get("plan_index") is not None), key=lambda row:row["plan_index"])
        last_release, media_index, phase_indexes, mismatches = -1, 0, [0]*len(config["phases"]), []
        previous_actual = -1
        for row in planned:
            if not _eligible(row["flags"]):
                raise ValueError("control/configuration incorrectly entered the media planner")
            offered_ns, size = row["offered_ns"], row["bytes"]
            media_index += 1
            phase_number = next((i for i,p in enumerate(config["phases"]) if p["start_ms"]*1000000 <= offered_ns-origin < p["end_ms"]*1000000), None)
            phase_id, phase_index, jitter, drop = None, None, 0, False
            release = max(offered_ns,last_release)
            if phase_number is not None:
                phase = config["phases"][phase_number]
                phase_indexes[phase_number] += 1
                phase_id, phase_index = phase["id"], phase_indexes[phase_number]
                random = (config["seed"]+(phase_index%2147483647)*1103515245+phase_number*12345)%2147483647
                jitter = random%(2*phase["jitter_ms"]+1)-phase["jitter_ms"] if phase["jitter_ms"] else 0
                drop = bool(phase["drop_every"] and phase_index%phase["drop_every"] == 0 or
                            phase["burst_every"] and (phase_index-1)%phase["burst_every"] < phase["burst_length"])
                service = (size*8*1000000000+phase["rate_bps"]-1)//phase["rate_bps"] if phase["rate_bps"] else 0
                release = None if drop else max(offered_ns+max(0,phase["delay_ms"]+jitter)*1000000,last_release)+service
            expected_decision = "DROP_PATTERN" if drop else "DROP_MAX_WAIT" if release-offered_ns > config["max_wait_ms"]*1000000 else "SEND"
            expected = {"media_index":media_index,"phase":phase_id,"phase_index":phase_index,"jitter_ms":jitter,
                        "planned_release_ns":release,"plan_decision":expected_decision}
            for key,value in expected.items():
                if row.get(key) != value:
                    mismatches.append({"sequence":row["sequence"],"field":key,"expected":value,"actual":row.get(key)})
            if expected_decision == "SEND":
                last_release = release
            actual = row.get("actual_release_ns")
            if row["decision"] == "SEND":
                if type(actual) is not int or actual < offered_ns or actual > row["completed_ns"]:
                    raise ValueError("SEND lacks valid callback release/completion timestamps")
                if expected_decision != "SEND" or actual+tolerance < release or actual > offered_ns+config["max_wait_ms"]*1000000+tolerance:
                    mismatches.append({"sequence":row["sequence"],"field":"actual_release_ns","reason":"release violates planned target or max wait"})
                if actual < previous_actual:
                    mismatches.append({"sequence":row["sequence"],"field":"actual_order","reason":"eligible media reordered"})
                previous_actual = actual
                last_release = max(last_release,actual)
            elif expected_decision != "SEND" and row["decision"] != expected_decision:
                mismatches.append({"sequence":row["sequence"],"field":"decision","expected":expected_decision,"actual":row["decision"]})
            elif row["decision"] not in (expected_decision,"DROP_CLOSED","DROP_INTERRUPTED","DROP_MAX_WAIT","SEND_FAILED"):
                mismatches.append({"sequence":row["sequence"],"field":"decision","expected":expected_decision,"actual":row["decision"]})
            elif row["decision"].startswith("DROP_") and actual is not None:
                raise ValueError("dropped media has a release timestamp")
        for row in completed.values():
            if row.get("plan_index") is None:
                if row["decision"] == "BYPASS":
                    if _eligible(row["flags"]):
                        mismatches.append({"sequence":row["sequence"],"field":"decision","reason":"eligible media bypassed"})
                    _integer(row.get("actual_release_ns"), "bypass release", row["offered_ns"], row["completed_ns"])
                elif row["decision"] not in ("DROP_ADMISSION","DROP_CLOSED","DROP_INTERRUPTED","SEND_FAILED"):
                    raise ValueError("eligible media result lacks its planner record")
                elif row.get("actual_release_ns") is not None and row["decision"] != "SEND_FAILED":
                    raise ValueError("unadmitted dropped media has a release timestamp")
                if row["decision"] == "DROP_ADMISSION" and row["completed_ns"]-row["offered_ns"]+tolerance < config["max_wait_ms"]*1000000:
                    mismatches.append({"sequence":row["sequence"],"field":"decision","reason":"admission timeout reported before max_wait"})
        result["counts"], result["plan_mismatches"] = dict(counts), mismatches
        result["producer_callback_duration_ms"] = _distribution([
            (row["completed_ns"]-row["actual_release_ns"])/1000000 for row in completed.values()
            if type(row.get("actual_release_ns")) is int])
        result["origin_ns"], result["closed_ns"] = origin, close
        phase_results, coverage_missing = [], []
        for phase in config["phases"]:
            start, end = origin+phase["start_ms"]*1000000, origin+phase["end_ms"]*1000000
            phase_rows = [completed[row["sequence"]] for row in eligible if start <= row["offered_ns"] < end]
            times = sorted(row["offered_ns"] for row in phase_rows)
            gaps = [b-a for a,b in zip([start,*times],[*times,end])]
            coverage = bool(times) and close >= end and max(gaps) <= maximum_gap
            if not coverage:
                coverage_missing.append(phase["id"])
            delays = [(row["actual_release_ns"]-row["offered_ns"])/1000000 for row in phase_rows if row["decision"] == "SEND"]
            phase_results.append({"id":phase["id"],"start_ns":start,"end_ns":end,"offered_media":len(phase_rows),
                "coverage":"MEASURED" if coverage else "UNMEASURED", "maximum_offer_gap_ms":max(gaps)/1000000,
                "offered_payload_bps":sum(row["bytes"] for row in phase_rows)*8*1000/(phase["end_ms"]-phase["start_ms"]),
                "delay_ms":_distribution(delays),"decisions":dict(Counter(row["decision"] for row in phase_rows))})
        result["phases"] = phase_results
        measurement("impairment_trace_integrity", expected_counts, True)
        measurement("impairment_actual_plan_matches",len(mismatches),not mismatches)
        measurement("impairment_full_phase_coverage",coverage_missing,True if not coverage_missing else None,
                    "" if not coverage_missing else "offered media does not cover every configured phase edge/interval")
        unexpected = sum(counts[key] for key in ("DROP_ADMISSION","DROP_CLOSED","DROP_INTERRUPTED","SEND_FAILED"))
        measurement("impairment_unexpected_harness_outcomes",unexpected,unexpected == 0)
        result["measurement_status"] = "MEASURED" if not coverage_missing else "UNMEASURED"
        result["verdict"] = "FAIL" if any(row["status"] == "FAIL" for row in result["measurements"]) else "UNMEASURED" if coverage_missing else "PASS"
    except (ValueError,OSError,TypeError,KeyError,IndexError) as error:
        measurement("impairment_trace_prerequisites",reason=str(error))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args = parser.parse_args(argv)
    args.output = output_path(args.output, "runs")
    try:
        if args.output.exists():
            raise ValueError("output exists; use a new run-owned result")
        spec_path = resolve_input(args.spec)
        result = analyze(json.loads(spec_path.read_text(encoding="utf-8")),original(spec_path).parent)
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(result,indent=2,allow_nan=False)+"\n",encoding="utf-8")
    except (ValueError,OSError) as error:
        parser.error(str(error))
    print(f"{result['verdict']}: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
