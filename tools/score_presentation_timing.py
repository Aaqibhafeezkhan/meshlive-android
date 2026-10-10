"""Summarize DEBUG presentation v1 snapshots without claiming phone-pixel quality.

Only (scope, seq) identifies a logged event. Observer matches are retained as
logged; this tool neither rematches timestamps nor labels absent trace rows as
media loss. All timestamp differences remain signed nanoseconds.
"""

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path

from benchmark_layout import output_path, resolve


PREFIX = "BENCHMARK video presentation v1 "
COLUMNS = (
    "seq", "kind", "match", "generation", "ptsUs", "targetNs", "releaseCallNs",
    "reportedNs", "observedNowNs", "observedMinusTargetNs", "reportedMinusTargetNs",
    "codecListenerDelayNs", "unseenEarlierTextureReleases",
)
KINDS = {"RELEASE", "CODEC", "TEXTURE"}
MATCHES = {"RECORDED", "MATCHED", "UNMATCHED", "AMBIGUOUS", "DUPLICATE", "STALE_GENERATION"}
NULLABLE = set(COLUMNS[4:8]) | set(COLUMNS[9:12])
META_NUMBERS = (
    "generation", "rollingSamples", "capacity", "unobservedCodec", "unobservedTexture",
    "releaseOverwrites", "unobservedCodecOverflows", "unobservedTextureOverflows",
    "eventOverwrites", "generationDiscarded",
)
META_FIELDS = {"scope", "report", "final", "decoder", "counts", "generationScope", "optical", *META_NUMBERS}
LIMITATIONS = [
    "Rolling history, missing trace rows and unobserved releases do not prove media loss.",
    "Exact timestamp equality does not prove buffer provenance or physical phone pixels.",
    "Generation tags identify the listener or current decoder, not a physical buffer epoch.",
    "recordRelease follows releaseOutputBuffer; a receiver callback can precede release history.",
    "META counts are cumulative within a scope, not per generation; timing uses unconflicted MATCHED events only.",
]


def integer(value, field, line, nullable=False, minimum=None):
    if nullable and value in ("NA", "null"):
        return None
    try:
        result = int(value)
    except ValueError:
        raise ValueError(f"line {line}: {field} must be an integer, got {value!r}") from None
    if minimum is not None and result < minimum:
        raise ValueError(f"line {line}: {field} must be >= {minimum}, got {result}")
    return result


def key_values(payload, line):
    result = {}
    for token in payload.split():
        key, separator, value = token.partition("=")
        if not separator or not key or not value or key in result:
            raise ValueError(f"line {line}: invalid or repeated key/value token {token!r}")
        result[key] = value
    return result


def missing_ranges(observed, last):
    ranges = []
    previous = 0
    for value in sorted(set(observed)):
        if value > previous + 1:
            ranges.append({"start": previous + 1, "end": value - 1, "count": value - previous - 1})
        previous = value
    if last > previous:
        ranges.append({"start": previous + 1, "end": last, "count": last - previous})
    return ranges


def distribution(values):
    present = sorted(value for value in values if value is not None)
    result = {"unit": "ns", "samples": len(present), "null_samples": len(values) - len(present)}
    def percentile(fraction):
        if not present:
            return None
        index = (len(present) - 1) * fraction
        lower, upper = math.floor(index), math.ceil(index)
        return present[lower] + (present[upper] - present[lower]) * (index - lower)
    result.update({
        "min": min(present) if present else None,
        "p50": percentile(0.50), "p95": percentile(0.95), "p99": percentile(0.99),
        "mean": sum(present) / len(present) if present else None,
        "max": max(present) if present else None,
        "negative_samples": sum(value < 0 for value in present),
    })
    return result


def parse_log(text):
    reports = {}
    trace_lines = 0
    for line_number, line in enumerate(text.splitlines(), 1):
        if PREFIX not in line:
            continue
        trace_lines += 1
        payload = line.split(PREFIX, 1)[1].strip()
        kind, separator, payload = payload.partition(" ")
        if not separator or kind not in {"META", "COLUMNS", "DATA"}:
            raise ValueError(f"line {line_number}: expected META, COLUMNS or DATA")
        if kind == "COLUMNS":
            header, separator, remainder = payload.partition(" ")
            if not separator:
                raise ValueError(f"line {line_number}: COLUMNS has no report/schema")
            report_token, separator, columns = remainder.partition(" ")
            if not separator:
                raise ValueError(f"line {line_number}: COLUMNS has no schema")
            fields = key_values(header + " " + report_token, line_number)
        else:
            fields = key_values(payload, line_number)
        if "scope" not in fields or "report" not in fields:
            raise ValueError(f"line {line_number}: missing scope or report")
        scope = integer(fields["scope"], "scope", line_number)
        report_id = integer(fields["report"], "report", line_number, minimum=1)
        report = reports.setdefault((scope, report_id), {"meta": [], "columns": [], "data": []})
        if kind == "COLUMNS":
            schema = columns.split(",")
            if schema not in report["columns"]:
                report["columns"].append(schema)
        elif kind == "META":
            meta = dict(fields, scope=scope, report=report_id)
            for field in META_NUMBERS:
                if field in fields:
                    meta[field] = integer(fields[field], field, line_number,
                                          minimum=1 if field in {"generation", "capacity"} else 0)
            if "final" in fields:
                if fields["final"] not in {"true", "false"}:
                    raise ValueError(f"line {line_number}: final must be true or false")
                meta["final"] = fields["final"] == "true"
            if "counts" in fields:
                counts = {}
                for count in ([] if fields["counts"] == "NONE" else fields["counts"].split(",")):
                    identity, separator, value = count.partition(":")
                    event_kind, dot, match = identity.partition(".")
                    if not separator or not dot or event_kind not in KINDS or match not in MATCHES or identity in counts:
                        raise ValueError(f"line {line_number}: invalid cumulative count {count!r}")
                    counts[identity] = integer(value, "counts." + identity, line_number, minimum=0)
                meta["counts"] = counts
            if meta not in report["meta"]:
                report["meta"].append(meta)
        else:
            if "part" not in fields or "rows" not in fields:
                raise ValueError(f"line {line_number}: DATA requires part and rows")
            part, separator, total = fields["part"].partition("/")
            if not separator:
                raise ValueError(f"line {line_number}: DATA part must be number/total")
            part = integer(part, "part", line_number, minimum=1)
            total = integer(total, "part total", line_number, minimum=1)
            if part > total:
                raise ValueError(f"line {line_number}: DATA part exceeds total")
            report["data"].append({"part": part, "total": total, "rows": fields["rows"], "line": line_number})

    gaps = []
    events = {}
    report_summaries = defaultdict(list)
    repeated_rows = 0
    def gap(scope, code, **detail):
        gaps.append({"scope": scope, "code": code, **detail})

    for (scope, report_id), report in sorted(reports.items()):
        meta = report["meta"][0] if len(report["meta"]) == 1 else None
        if not report["meta"]:
            gap(scope, "missing_meta", report=report_id)
        elif meta is None:
            gap(scope, "conflicting_meta", report=report_id)
        elif META_FIELDS - meta.keys():
            gap(scope, "incomplete_meta", report=report_id, missing_fields=sorted(META_FIELDS - meta.keys()))
        if meta and (meta.get("generationScope") != "listener_or_current_decoder_not_buffer_epoch" or meta.get("optical") != "UNMEASURED"):
            gap(scope, "unsupported_meta_semantics", report=report_id)
        schema_valid = report["columns"] == [list(COLUMNS)]
        if not schema_valid:
            gap(scope, "missing_columns" if not report["columns"] else "unsupported_or_conflicting_schema", report=report_id)
        totals = sorted({part["total"] for part in report["data"]})
        parts = sorted({part["part"] for part in report["data"]})
        missing_parts = missing_ranges(parts, max(totals, default=0))
        if len(totals) > 1:
            gap(scope, "conflicting_part_totals", report=report_id, totals=totals)
        if missing_parts:
            gap(scope, "missing_parts", report=report_id, ranges=missing_parts)
        if meta and meta.get("rollingSamples", 0) > 0 and not parts:
            gap(scope, "missing_all_data_parts", report=report_id)
        for part in parts:
            if len({entry["rows"] for entry in report["data"] if entry["part"] == part}) > 1:
                gap(scope, "conflicting_part_payload", report=report_id, part=part)
        report_sequences = set()
        if schema_valid:
            for part in report["data"]:
                for raw_row in part["rows"].split(";"):
                    values = raw_row.split(",")
                    if len(values) != len(COLUMNS):
                        raise ValueError(f"line {part['line']}: expected {len(COLUMNS)} event columns, got {len(values)}")
                    row = dict(zip(COLUMNS, values))
                    if row["kind"] not in KINDS or row["match"] not in MATCHES:
                        raise ValueError(f"line {part['line']}: invalid event kind/match {row['kind']}.{row['match']}")
                    for field in COLUMNS:
                        if field not in {"kind", "match"}:
                            row[field] = integer(row[field], field, part["line"], nullable=field in NULLABLE,
                                                 minimum=1 if field in {"seq", "generation"} else 0 if field == "unseenEarlierTextureReleases" else None)
                    identity = (scope, row["seq"])
                    report_sequences.add(row["seq"])
                    variants = events.setdefault(identity, [])
                    existing = next((entry for entry in variants if entry["event"] == row), None)
                    if existing is None:
                        existing = {"event": row, "reports": [], "sources": []}
                        variants.append(existing)
                    else:
                        repeated_rows += 1
                    if report_id not in existing["reports"]:
                        existing["reports"].append(report_id)
                    existing["sources"].append({"report": report_id, "part": part["part"], "line": part["line"]})
        if schema_valid and meta and "rollingSamples" in meta and len(report_sequences) != meta["rollingSamples"]:
            gap(scope, "rolling_sample_count_mismatch", report=report_id, expected=meta["rollingSamples"], observed=len(report_sequences))
        report_summaries[scope].append({
            "report": report_id, "meta": meta, "meta_variants": report["meta"] if meta is None else [],
            "complete": not any(entry["scope"] == scope and entry.get("report") == report_id for entry in gaps),
            "columns": report["columns"], "part_totals": totals, "observed_parts": parts,
            "missing_part_ranges": missing_parts, "observed_event_identities": len(report_sequences),
            "raw_data": report["data"] if not schema_valid else [],
        })

    raw_events = []
    valid_events = defaultdict(list)
    for (scope, sequence), variants in sorted(events.items()):
        conflict = len(variants) > 1
        if conflict:
            gap(scope, "conflicting_event_identity", seq=sequence, variants=len(variants))
        for variant in variants:
            row = {"scope": scope, **variant["event"], "identity_conflict": conflict,
                   "source_reports": variant["reports"], "sources": variant["sources"]}
            raw_events.append(row)
            if not conflict:
                valid_events[scope].append(row)

    scopes = []
    for scope, scope_reports in sorted(report_summaries.items()):
        report_holes = missing_ranges([report["report"] for report in scope_reports], scope_reports[-1]["report"])
        if report_holes:
            gap(scope, "missing_reports", ranges=report_holes)
        metas = [report["meta"] for report in scope_reports if report["meta"] is not None]
        final_metas = [meta for meta in metas if meta.get("final") is True]
        latest_meta = metas[-1] if metas else None
        final_meta = final_metas[-1] if final_metas else None
        if final_meta is None:
            gap(scope, "missing_final_meta")
        elif final_meta["report"] != scope_reports[-1]["report"]:
            gap(scope, "reports_after_final_meta")
        sequences = [sequence for event_scope, sequence in events if event_scope == scope]
        total = sum(latest_meta["counts"].values()) if latest_meta and "counts" in latest_meta else None
        sequence_holes = missing_ranges(sequences, max(max(sequences, default=0), total or 0))
        if sequence_holes:
            gap(scope, "missing_event_sequences", ranges=sequence_holes)
        if total is not None and max(sequences, default=0) > total:
            gap(scope, "event_sequence_exceeds_cumulative_total", cumulative_total=total)
        groups = defaultdict(list)
        for row in valid_events[scope]:
            groups[(row["generation"], row["kind"], row["match"])].append(row)
        summaries = []
        for (generation, kind, match), rows in sorted(groups.items()):
            timing = None
            if match == "MATCHED" and kind in {"CODEC", "TEXTURE"}:
                timing = {
                    "reportedMinusTargetNs": distribution([row["reportedMinusTargetNs"] for row in rows]),
                    "observedMinusTargetNs": distribution([row["observedMinusTargetNs"] for row in rows]),
                    "reportedToCallbackNs": distribution([
                        row["codecListenerDelayNs"] if kind == "CODEC" else
                        row["observedNowNs"] - row["reportedNs"] if row["reportedNs"] is not None else None
                        for row in rows
                    ]),
                }
            summaries.append({"generation": generation, "kind": kind, "match": match, "events": len(rows), "timing": timing})
        scope_gaps = [entry for entry in gaps if entry["scope"] == scope]
        scopes.append({
            "scope": scope,
            "state": "UNMEASURED" if not valid_events[scope] else "MEASURED_WITH_GAPS" if scope_gaps else "MEASURED",
            "timing_state": "MEASURED" if any(group["timing"] and group["timing"]["reportedMinusTargetNs"]["samples"] for group in summaries) else "UNMEASURED",
            "reports": scope_reports, "latest_meta": latest_meta, "final_meta": final_meta,
            "cumulative_event_total": total, "observed_event_identities": len(sequences),
            "usable_events": len(valid_events[scope]), "missing_event_sequence_ranges": sequence_holes,
            "groups": summaries, "measurement_gaps": scope_gaps,
        })
    usable = sum(len(rows) for rows in valid_events.values())
    return {
        "schema": "video-presentation-v1", "optical": "UNMEASURED",
        "state": "UNMEASURED" if not usable else "MEASURED_WITH_GAPS" if gaps else "MEASURED",
        "units": {"ptsUs": "us", "timestamps": "ns", "differences": "signed ns"},
        "timing_fields": {
            "reportedMinusTargetNs": "reported codec/texture timestamp minus planned release target",
            "observedMinusTargetNs": "callback's sampled System.nanoTime minus planned release target",
            "reportedToCallbackNs": "callback time minus reported timestamp; CODEC uses logged codecListenerDelayNs",
        },
        "percentiles": "linear interpolation over sorted signed samples",
        "limitations": LIMITATIONS,
        "trace_lines": trace_lines, "reports": len(reports), "scopes": scopes,
        "observed_event_identities": len(events), "usable_events": usable,
        "identical_repeated_event_rows": repeated_rows,
        "conflicting_event_identities": sum(len(variants) > 1 for variants in events.values()),
        "measurement_gaps": gaps, "events": raw_events,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        args.output = output_path(args.output, "runs")
        args.log = resolve(args.log)
        result = parse_log(args.log.read_text(encoding="utf-8-sig"))
        result["input_log"] = str(args.log.resolve())
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    except (ValueError, OSError, UnicodeError) as error:
        parser.error(str(error))
    print(f"{result['state']}: {result['usable_events']} usable events, {len(result['scopes'])} scopes, "
          f"{len(result['measurement_gaps'])} measurement gaps; optical UNMEASURED. Output: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
