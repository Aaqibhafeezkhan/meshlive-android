"""Inspect one retained app scheduler trace offline with a frozen parser and exact PID."""
import os
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import subprocess

from benchmark_layout import original, output_path, resolve as resolve_input

PACKAGE = os.environ.get("MESHLIVE_APP_ID", "io.github.meshlive.app")


def reference(path):
    path = resolve_input(path)
    with path.open("rb") as handle:
        digest = hashlib.file_digest(handle, "sha256").hexdigest()
    return {"path": str(path), "sha256": digest, "bytes": path.stat().st_size}


def validate(manifest_path, capture_path, processor, processor_sha256):
    manifest_path, capture_path = resolve_input(manifest_path), resolve_input(capture_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    capture = json.loads(capture_path.read_text(encoding="utf-8-sig"))
    if capture.get("state") != "CAPTURED_NOT_YET_ANALYZED":
        raise ValueError("Expected an original finalized scheduler capture")
    phone, pid = capture["phone"], int(capture["pid"])
    if pid <= 0 or phone not in manifest["devices"]:
        raise ValueError("Capture participant/PID is absent from the executed manifest")
    if not any(row.get("device") == phone and int(row.get("pid", 0)) == pid for row in manifest.get("logs", [])):
        raise ValueError("Capture PID is not an executed manifest log PID")
    if capture["apk_sha256"].lower() != manifest["devices"][phone]["apk_sha256"].lower():
        raise ValueError("Capture APK differs from the executed participant")
    trace = resolve_input(capture["path"], base=original(capture_path).parent)
    trace_ref, processor_ref = reference(trace), reference(processor)
    if trace_ref["sha256"] != capture["sha256"] or trace_ref["bytes"] != capture["bytes"]:
        raise ValueError("Scheduler capture bytes/hash changed")
    if processor_ref["sha256"] != processor_sha256.lower():
        raise ValueError("Trace processor hash differs from the frozen parser")
    return manifest, capture, trace_ref, processor_ref


def query_sql(pid):
    # A single query loads the trace once. Hex-encoded JSON avoids the shell CSV
    # writer's ambiguous quoting of thread names and embedded JSON quotes.
    app = f"FROM thread t JOIN process p USING(upid) WHERE p.pid={pid}"
    states = f"FROM thread_state s JOIN thread t USING(utid) JOIN process p USING(upid) WHERE p.pid={pid}"
    return f"""SELECT hex(json_object('kind','bounds','start_ts',start_ts,'end_ts',end_ts)) AS row_hex FROM trace_bounds
UNION ALL SELECT hex(json_object('kind','stats','name',name,'idx',idx,'severity',severity,'source',source,'value',value))
 FROM stats WHERE value!=0 AND (severity!='info' OR name LIKE 'ftrace%' OR name LIKE 'traced%')
UNION ALL SELECT hex(json_object('kind','process','upid',upid,'pid',pid,'name',name,'start_ts',start_ts,'end_ts',end_ts))
 FROM process WHERE pid={pid}
UNION ALL SELECT hex(json_object('kind','thread','utid',t.utid,'tid',t.tid,'name',t.name)) {app}
UNION ALL SELECT hex(json_object('kind','clock','ts',ts,'clock_id',clock_id,'clock_name',clock_name,'clock_value',clock_value,'snapshot_id',snapshot_id))
 FROM clock_snapshot WHERE clock_id IN (1,3,6)
UNION ALL SELECT hex(json_object('kind','state','utid',t.utid,'tid',t.tid,'name',t.name,'state',s.state,
 'rows',count(*),'open_rows',sum(s.dur<0),'closed_total_ns',sum(CASE WHEN s.dur>=0 THEN s.dur ELSE 0 END),
 'closed_max_ns',max(CASE WHEN s.dur>=0 THEN s.dur ELSE NULL END))) {states} GROUP BY t.utid,s.state
UNION ALL SELECT hex(json_object('kind','wait','ts',ts,'dur',dur,'tid',tid,'name',name,'state',state,'io_wait',io_wait,
 'blocked_function',blocked_function,'waker_tid',waker_tid,'waker_name',waker_name)) FROM (
 SELECT s.ts,s.dur,t.tid,t.name,s.state,s.io_wait,s.blocked_function,w.tid waker_tid,w.name waker_name,
 row_number() OVER (PARTITION BY t.utid ORDER BY s.dur DESC) wait_rank
 FROM thread_state s JOIN thread t USING(utid) JOIN process p USING(upid) LEFT JOIN thread w ON w.utid=s.waker_utid
 WHERE p.pid={pid} AND s.dur>=0 AND s.state!='Running') WHERE wait_rank<=3
UNION ALL SELECT hex(json_object('kind','slice','name',CASE WHEN s.name GLOB 'Choreographer#doFrame*' THEN 'Choreographer#doFrame'
 WHEN s.name GLOB 'DrawFrame*' THEN 'DrawFrame' ELSE s.name END,'count',count(*),'open_rows',sum(s.dur<0),
 'closed_max_ns',max(CASE WHEN s.dur>=0 THEN s.dur ELSE NULL END)))
 FROM slice s JOIN thread_track tr ON s.track_id=tr.id JOIN thread t ON tr.utid=t.utid JOIN process p USING(upid)
 WHERE p.pid={pid} AND (s.name GLOB 'Choreographer#doFrame*' OR s.name GLOB 'DrawFrame*' OR s.name='draw')
 GROUP BY CASE WHEN s.name GLOB 'Choreographer#doFrame*' THEN 'Choreographer#doFrame'
 WHEN s.name GLOB 'DrawFrame*' THEN 'DrawFrame' ELSE s.name END;
"""


def summarize(raw, pid):
    records = [json.loads(bytes.fromhex(row["row_hex"]).decode("utf-8")) for row in csv.DictReader(io.StringIO(raw))]
    grouped = {name: [row for row in records if row["kind"] == name]
               for name in ("bounds", "stats", "process", "thread", "clock", "state", "wait", "slice")}
    if len(grouped["bounds"]) != 1 or len(grouped["process"]) != 1:
        raise ValueError("Missing or reused app PID in the trace; separate process lifetimes are required")
    if grouped["process"][0]["name"] != PACKAGE or grouped["process"][0]["pid"] != pid:
        raise ValueError("Trace PID is not the expected app process")
    bounds = grouped.pop("bounds")[0]
    if bounds["end_ts"] <= bounds["start_ts"] or not grouped["thread"]:
        raise ValueError("Empty trace coverage for the app PID")
    offsets = [row["ts"] - row["clock_value"] for row in grouped["clock"] if row["clock_id"] == 3]
    loss_terms = ("overrun", "dropped", "overwrit", "discard", "loss", "error")
    loss = [row for row in grouped["stats"] if row["severity"] != "info" or any(word in row["name"] for word in loss_terms)]
    states = grouped.pop("state")
    return {"trace_bounds_ns": [bounds["start_ts"], bounds["end_ts"]],
            "duration_s": (bounds["end_ts"] - bounds["start_ts"]) / 1e9,
            "parser_loss_or_error_stats": loss, "clock_offset_min_max_ns": [min(offsets), max(offsets)] if offsets else None,
            "clock_alignment": "Recorded snapshots only; no extrapolation to application events outside measured brackets",
            "thread_states": states,
            **grouped}


def analyze(manifest_path, capture_path, processor, processor_sha256, output):
    processor = resolve_input(processor)
    manifest, capture, trace, parser = validate(manifest_path, capture_path, processor, processor_sha256)
    output = output_path(output, "runs")
    output.mkdir(parents=True, exist_ok=False)
    sql = query_sql(int(capture["pid"]))
    query_path = output / "scheduler.sql"
    query_path.write_text(sql, encoding="utf-8")
    command = [str(processor), "query", "-f", str(query_path), trace["path"]]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=60)
    (output / "scheduler.csv").write_text(result.stdout, encoding="utf-8")
    (output / "processor.log").write_text(result.stderr, encoding="utf-8")
    if result.returncode:
        raise ValueError(f"Trace processor failed with code {result.returncode}; raw output retained in {output}")
    summary = summarize(result.stdout, int(capture["pid"]))
    version = subprocess.run([str(processor), "--version"], capture_output=True, text=True, encoding="utf-8", timeout=10)
    summary.update(schema="benchmark-perfetto-analysis-v1", state="DIAGNOSTIC", run_id=manifest["run_id"],
                   phone=capture["phone"], pid=int(capture["pid"]), apk_sha256=capture["apk_sha256"],
                   manifest=reference(manifest_path), capture=reference(capture_path), trace=trace,
                   processor={**parser, "version": version.stdout.strip()}, query=reference(query_path),
                   rows=reference(output / "scheduler.csv"),
                   limitations=["Scheduler sleep, runnable waits and wakers do not identify RF/socket/lock causes.",
                                "Open state/slice rows are counted but excluded from duration totals and maxima.",
                                "Partial capture, observer overhead and overlapping radio sessions retain their original limits.",
                                "No physical frame cadence, optical latency, phone pass or policy promotion."])
    (output / "summary.json").write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("manifest", "capture", "processor", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--processor-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        result = analyze(args.manifest, args.capture, args.processor, args.processor_sha256, args.output)
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired) as error:
        parser.exit(2, f"Offline scheduler analysis blocked: {error}\n")
    print(json.dumps({key: result[key] for key in ("state", "run_id", "phone", "pid", "duration_s", "parser_loss_or_error_stats")}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
