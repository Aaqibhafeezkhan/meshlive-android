"""Score a fixed fixture after transport, using CSV source PTS rather than IVF ordinals.

Quality uses FFmpeg's reference decoder, not phone-decoded/displayed pixels. The
reference is tightly packed I420, limited-range BT.709, beginning at frame zero.
Every planned frame remains in the scored timeline, including missing tail frames.
Run-manifest mode scores all finalized raw-input phases using their source-clock
exclusive ends. Legacy or unfinished phases remain unmeasured.
"""

import argparse
import concurrent.futures as futures
import csv
import hashlib
import json
import math
import os
import re
import shutil
import struct
import subprocess
import tempfile
from pathlib import Path

from benchmark_layout import original, output_path, resolve
from score_parallel import score_threads

import numpy as np


def planned_pts(frames: int, fps: int, origin_us: int = 0) -> list[int]:
    if frames <= 0 or not 0 < fps <= 1_000_000:
        raise ValueError("Planned frames and FPS must be positive, with FPS <= 1000000")
    return [origin_us + index * 1_000_000 // fps for index in range(frames)]


def read_trace(path: Path) -> list[tuple[int, str]]:
    path = resolve(path)
    with path.open(newline="", encoding="utf-8-sig") as source:
        reader = csv.DictReader(source)
        if not {"pts_us", "sha256"}.issubset(reader.fieldnames or []):
            raise ValueError(f"{path}: required columns are pts_us and sha256")
        rows = []
        for row in reader:
            digest = row["sha256"].lower()
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError(f"{path}: invalid SHA256 at PTS {row['pts_us']}")
            rows.append((int(row["pts_us"]), digest))
        return rows


def align_delivery(
    sender: list[tuple[int, str]],
    receiver: list[tuple[int, str]],
    expected: list[int],
) -> tuple[list[int | None], dict]:
    def unique(rows: list[tuple[int, str]], label: str) -> dict[int, str]:
        result = dict(rows)
        if len(result) != len(rows):
            raise ValueError(f"Duplicate {label} PTS")
        if not result.keys() <= set(expected):
            raise ValueError(f"{label} PTS does not belong to the explicit fixture timeline")
        return result

    sent = unique(sender, "sender")
    received = unique(receiver, "receiver")
    for pts, digest in received.items():
        if pts not in sent:
            raise ValueError(f"Receiver PTS {pts} is absent from the sender trace")
        if sent[pts] != digest:
            raise ValueError(f"Sender/receiver SHA256 mismatch at PTS {pts}")
    decoded_index = {pts: index for index, (pts, _) in enumerate(receiver)}
    indices: list[int | None] = []
    timeline = []
    previous = None
    for frame, pts in enumerate(expected):
        present = pts in decoded_index
        if present:
            previous = decoded_index[pts]
        indices.append(previous)
        timeline.append({
            "source_frame": frame, "pts_us": pts, "decoded_receiver_index": previous,
            "kind": "received" if present else "black" if previous is None else "hold",
        })
    return indices, {
        "planned_frames": len(expected),
        "encoded_frames": len(sender),
        "received_frames": len(receiver),
        "encoder_missing_frames": len(expected) - len(sender),
        "transport_missing_frames": len(sender) - len(receiver),
        "total_missing_frames": len(expected) - len(receiver),
        "held_frames": sum(row["kind"] == "hold" for row in timeline),
        "black_frames": sum(row["kind"] == "black" for row in timeline),
        "trailing_missing_frames": len(expected) - 1 - max(
            (frame for frame, pts in enumerate(expected) if pts in received), default=-1,
        ),
        "fixture_received_coverage_percent": len(receiver) * 100.0 / len(expected),
        "encoded_delivery_percent": len(receiver) * 100.0 / len(sender) if sender else None,
        "timeline": timeline,
    }


def verify_ivf(path: Path, rows: list[tuple[int, str]], width: int, height: int) -> tuple[bytes, int]:
    path = resolve(path)
    with path.open("rb") as source:
        header = source.read(32)
        if len(header) != 32:
            raise ValueError(f"{path}: truncated IVF header")
        magic, version, header_size, codec, w, h, rate, scale, declared, _ = struct.unpack("<4sHH4sHHIIII", header)
        if magic != b"DKIF" or version != 0 or header_size < 32 or codec not in (b"VP90", b"AV01"):
            raise ValueError(f"{path}: unsupported IVF header")
        if (w, h) != (width, height) or not rate or not scale:
            raise ValueError(f"{path}: IVF format does not match the fixture")
        if len(source.read(header_size - 32)) != header_size - 32:
            raise ValueError(f"{path}: truncated extended header")
        count = 0
        payload_bytes = 0
        while frame_header := source.read(12):
            if len(frame_header) != 12:
                raise ValueError(f"{path}: truncated frame header")
            size, _ = struct.unpack("<IQ", frame_header)
            payload = source.read(size)
            if not size or len(payload) != size or count >= len(rows):
                raise ValueError(f"{path}: invalid payload or CSV/AU count mismatch")
            if hashlib.sha256(payload).hexdigest() != rows[count][1]:
                raise ValueError(f"{path}: payload SHA256 disagrees with CSV at AU {count}")
            payload_bytes += size
            count += 1
        if count != len(rows) or declared not in (0, count):
            raise ValueError(f"{path}: IVF/CSV frame count mismatch")
        return codec, payload_bytes


def write_timeline(decoded: Path, output: Path, indices: list[int | None], width: int, height: int, received_frames: int) -> None:
    frame_size = width * height * 3 // 2
    if received_frames and decoded.stat().st_size != received_frames * frame_size:
        raise ValueError("Decoded frame count/geometry differs from receiver AUs; alignment is ambiguous")
    frames = np.memmap(decoded, dtype=np.uint8, mode="r").reshape(received_frames, frame_size) if received_frames else None
    black = bytes([16]) * (width * height) + bytes([128]) * (width * height // 2)
    with output.open("wb") as destination:
        for index in indices:
            destination.write(black if index is None else frames[index].tobytes())


def run(command: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=True, text=True, capture_output=True, cwd=cwd)


def score_quality(delivered: Path, reference: Path, width: int, height: int, fps: int, frames: int, directory: Path) -> dict:
    raw = ["-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{width}x{height}", "-r", str(fps)]
    inputs = [*raw, "-i", str(delivered), *raw, "-i", str(reference)]
    color = "setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709"
    chain = f"[0:v]{color}[dist];[1:v]trim=end_frame={frames},setpts=PTS-STARTPTS,{color}[ref];[dist][ref]"
    base = ["ffmpeg", "-hide_banner", *inputs, "-lavfi"]
    ending = ["-frames:v", str(frames), "-f", "null", "-"]
    # libvmaf defaults to one thread; its per-frame scores and pooled mean do not depend on n_threads.
    # The three metric passes read the same files independently, so they run side by side.
    threads = score_threads()
    vmaf_options = "libvmaf=log_fmt=json:log_path=vmaf.json" + (f":n_threads={threads}" if threads > 1 else "")
    jobs = [([*base, chain + vmaf_options, *ending], directory),
            ([*base, chain + "ssim", *ending], None), ([*base, chain + "psnr", *ending], None)]
    if threads > 1:
        with futures.ThreadPoolExecutor(max_workers=len(jobs)) as pool:
            vmaf_run, ssim_run, psnr_run = [future.result() for future in
                                             [pool.submit(run, command, cwd) for command, cwd in jobs]]
    else:
        vmaf_run, ssim_run, psnr_run = (run(command, cwd) for command, cwd in jobs)
    vmaf = json.loads((directory / "vmaf.json").read_text())
    if len(vmaf["frames"]) != frames:
        raise ValueError("VMAF did not score the complete planned timeline")
    ssim_text, psnr_text = ssim_run.stderr, psnr_run.stderr
    ssim_match = re.search(r"All:([0-9.eE+-]+)", ssim_text)
    psnr_match = re.search(r"average:(inf|[0-9.eE+-]+)", psnr_text)
    if not ssim_match or not psnr_match:
        raise ValueError("Could not parse SSIM/PSNR output")
    psnr = float(psnr_match.group(1))
    return {"scored_frames": frames, "vmaf": vmaf["pooled_metrics"]["vmaf"]["mean"],
            "ssim": float(ssim_match.group(1)), "psnr_db": psnr if math.isfinite(psnr) else None,
            "psnr_perfect": math.isinf(psnr),
            "colour_basis": "Decoded I420 code values; limited BT709 tags used for metric interpretation",
            "encoder_decoder_colour_application": "UNMEASURED"}


def score_delivery(reference: Path, sender_csv: Path, sender_ivf: Path, receiver_csv: Path,
                   receiver_ivf: Path, width: int, height: int, fps: int, frames: int,
                   output_directory: Path, pts_origin_us: int = 0) -> dict:
    reference, sender_csv, sender_ivf, receiver_csv, receiver_ivf = map(
        resolve, (reference, sender_csv, sender_ivf, receiver_csv, receiver_ivf))
    if width <= 0 or height <= 0 or width % 2 or height % 2:
        raise ValueError("I420 fixture width/height must be positive even numbers")
    expected = planned_pts(frames, fps, pts_origin_us)
    frame_size = width * height * 3 // 2
    if reference.stat().st_size % frame_size or reference.stat().st_size < frames * frame_size:
        raise ValueError("Reference must contain at least the complete planned I420 timeline")
    sender, receiver = read_trace(sender_csv), read_trace(receiver_csv)
    indices, result = align_delivery(sender, receiver, expected)
    sender_codec, sent_bytes = verify_ivf(sender_ivf, sender, width, height)
    receiver_codec, received_bytes = verify_ivf(receiver_ivf, receiver, width, height)
    if sender_codec != receiver_codec:
        raise ValueError("Sender/receiver codecs differ")
    decoded = output_directory / "received-decoded.yuv"
    delivered = output_directory / "full-delivery-timeline.yuv"
    inputs = {path.resolve() for path in (reference, sender_csv, sender_ivf, receiver_csv, receiver_ivf)}
    outputs = {path.resolve() for path in (decoded, delivered, output_directory / "vmaf.json", output_directory / "delivery-result.json")}
    if inputs & outputs:
        raise ValueError("Scorer output would overwrite an input artifact")
    output_directory.mkdir(parents=True, exist_ok=True)
    if receiver:
        run(["ffmpeg", "-y", "-v", "error", "-i", str(receiver_ivf.resolve()), "-map", "0:v:0",
             "-fps_mode", "passthrough", "-pix_fmt", "yuv420p", "-f", "rawvideo", str(decoded.resolve())])
    write_timeline(decoded, delivered, indices, width, height, len(receiver))
    duration = frames / fps
    result.update({
        "status": "ok",
        "scope": "reference-decoder-after-transport; not phone-pixel/display quality, motion cadence, audio, or glass-to-glass latency",
        "alignment": "exact CSV source PTS; IVF sequential timestamps are not source frame identities",
        "reconstruction": "previous decoded frame held for missing slots; limited-range neutral black before first received frame",
        "codec": sender_codec.decode(), "width": width, "height": height, "fps": fps,
        "pts_origin_us": pts_origin_us, "planned_duration_seconds": duration,
        "received_content_duration_seconds": len(receiver) / fps,
        "missing_content_duration_seconds": (frames - len(receiver)) / fps,
        "sender_payload_bytes": sent_bytes, "receiver_payload_bytes": received_bytes,
        "sender_payload_kbps_over_planned_duration": sent_bytes * 8 / duration / 1000,
        "receiver_payload_kbps_over_planned_duration": received_bytes * 8 / duration / 1000,
        "quality": score_quality(delivered.resolve(), reference.resolve(), width, height, fps, frames, output_directory.resolve()),
    })
    (output_directory / "delivery-result.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return result


def active_phase_plan(phases: list[dict], fixture_frames: int) -> tuple[list[int], list[int], list[dict]]:
    if fixture_frames <= 0:
        raise ValueError("Fixture frame count must be positive")
    expected, reference_indices, counts = [], [], []
    previous_end = None
    for phase in sorted(phases, key=lambda value: value["raw_timeline"]["origin_pts_us"]):
        raw = phase["raw_timeline"]
        origin, end, fps = int(raw["origin_pts_us"]), int(raw["end_pts_exclusive_us"]), int(phase["fps"])
        if end <= origin or previous_end is not None and origin < previous_end or not 0 < fps <= 1_000_000:
            raise ValueError("Source phases overlap or have an invalid clock/FPS range")
        frames = ((end - origin) * fps + 999_999) // 1_000_000
        pts = planned_pts(frames, fps, origin) if frames else []
        counts.append({"phase": phase["phase"], "origin_pts_us": origin, "end_pts_exclusive_us": end,
                       "planned_frames": frames, "timeline_start_index": len(expected),
                       "preceding_source_phase_gap_us": origin - previous_end if previous_end is not None else 0})
        expected.extend(pts)
        reference_indices.extend(index % fixture_frames for index in range(frames))
        previous_end = end
    return expected, reference_indices, counts


def verified_phase(trace: dict, base: Path, width: int, height: int, fps: int) -> tuple[list[tuple[int, str]], int, Path]:
    phase, summary = trace["phase"], trace["summary"]
    if (summary.get("phase") != phase or summary.get("role") != trace["role"] or
            summary.get("csv") != phase + ".csv" or summary.get("ivf") != phase + ".ivf" or
            (summary.get("width"), summary.get("height"), summary.get("fps")) != (width, height, fps)):
        raise ValueError(f"{phase}: phase identity/geometry/FPS disagrees with the selected fixture")
    paths = {}
    names = {phase + ".csv": "csv", phase + ".ivf": "ivf", phase + "-summary.json": "summary"}
    for artifact in trace.get("files", []):
        path = resolve(artifact["path"], base)
        name = artifact.get("remote_name", path.name)
        if name not in names or names[name] in paths:
            raise ValueError(f"{phase}: ambiguous or unowned phase artifact")
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if digest != artifact["sha256"] or path.stat().st_size != artifact["bytes"]:
            raise ValueError(f"{phase}: recorded file hash/bytes changed")
        paths[names[name]] = path
    if set(paths) != {"csv", "ivf", "summary"} or json.loads(paths["summary"].read_text(encoding="utf-8-sig")) != summary:
        raise ValueError(f"{phase}: complete matching summary/CSV/IVF evidence required")
    rows = read_trace(paths["csv"])
    codec, payload_bytes = verify_ivf(paths["ivf"], rows, width, height)
    if (summary.get("frames") != len(rows) or summary.get("encoded_bytes") != payload_bytes or
            codec != {"vp9": b"VP90", "av1": b"AV01"}.get(summary.get("codec"))):
        raise ValueError(f"{phase}: finalized summary disagrees with CSV/IVF payloads")
    return rows, payload_bytes, paths["ivf"]


def score_phase_delivery(manifest: dict, receiver_device: str, output_directory: Path,
                         base_path: Path = Path("."), sender_device: str | None = None) -> dict:
    source = sender_device or manifest.get("scenario", {}).get("source")
    fixture = (manifest.get("source_fixture") if source == manifest.get("scenario", {}).get("source") else
               manifest.get("source_fixtures", {}).get(source))
    traces = [trace for trace in manifest.get("media_traces", []) if trace.get("kind") != "audio_pcm" and
              (trace.get("device"), trace.get("role")) in {(source, "sender"), (receiver_device, "receiver")}]
    sender = [trace for trace in traces if trace["role"] == "sender"]
    receiver = [trace for trace in traces if trace["role"] == "receiver"]
    inputs = {resolve(file["path"], base_path) for trace in traces for file in trace.get("files", [])}
    if fixture and fixture.get("path"):
        inputs.add(resolve(fixture["path"], base_path))
    if inputs & {(output_directory / name).resolve() for name in ("vmaf.json", "delivery-result.json")}:
        raise ValueError("Scorer output would overwrite input evidence")
    result = {"run_id": manifest.get("run_id"), "sender": source, "receiver": receiver_device,
              "scope": "All recorded active raw-input phases; reference-decoder after transport, not phone pixels, camera/blur, motion cadence, audio or glass-to-glass latency",
              "source_phase_gaps": "Not scored as content frames; lifecycle/recovery and source-inactive intervals require separate evidence",
              "whole_wall_session_quality": "UNMEASURED", "runner_error": manifest.get("runner_error")}
    missing = []
    if not fixture or not sender or not receiver:
        missing.append("Exact sender fixture and every sender/receiver trace phase are required")
    for trace in traces:
        summary = trace.get("summary", {})
        raw = summary.get("raw_timeline")
        if trace.get("state") != "MEASURED" or summary.get("finalized") is not True:
            missing.append(f"{trace.get('phase', trace['role'])}: phase is not verified and finalized")
        elif not all(key in summary for key in ("width", "height", "fps")) or trace["role"] == "sender" and (
            not isinstance(raw, dict) or not all(key in raw for key in ("file", "fixture_frames", "origin_pts_us", "end_pts_exclusive_us"))
        ):
            missing.append(f"{trace.get('phase')}: explicit geometry/raw scheduling bounds absent")
    if missing:
        result.update(status="unmeasured", measurement_status="UNMEASURED", reasons=missing)
        output_directory.mkdir(parents=True, exist_ok=True)
        (output_directory / "delivery-result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        return result
    session = re.sub(r"[^A-Za-z0-9_.-]", "_", manifest["run_id"])[:80]
    if any(trace["summary"].get("session") != session or not re.fullmatch(
        re.escape(session + "-" + trace["role"]) + r"-\d+", trace["phase"]
    ) for trace in traces):
        raise ValueError("Trace phases do not belong to the exact run session/role")
    width, height = fixture["width"], fixture["height"]
    fps = sender[0]["summary"]["fps"]
    profile = manifest.get("scenario", {}).get("profile", manifest.get("scenario", {}).get("profiles", {}).get(source, {}))
    if (fixture.get("format") != "I420" or fixture.get("loops") is not True or width <= 0 or height <= 0 or width % 2 or height % 2 or
            (profile.get("width"), profile.get("height"), profile.get("fps")) != (width, height, fps)):
        raise ValueError("Exact selected I420 geometry/FPS and cyclic fixture evidence required; no substitution")
    reference = resolve(fixture["path"], base_path)
    with reference.open("rb") as handle:
        if hashlib.file_digest(handle, "sha256").hexdigest() != fixture["sha256"]:
            raise ValueError("Raw reference identity changed")
    if reference.stat().st_size != fixture["frames"] * width * height * 3 // 2 or reference.stat().st_size != fixture["bytes"]:
        raise ValueError("Raw reference does not match exact fixture frames/geometry/bytes")
    sender = sorted(sender, key=lambda trace: trace["summary"]["raw_timeline"]["origin_pts_us"])
    for trace in sender:
        raw = trace["summary"]["raw_timeline"]
        if raw["fixture_frames"] != fixture["frames"] or raw["file"] != "benchmark-raw-" + fixture["sha256"] + ".yuv":
            raise ValueError("Actual raw-input file/frame count does not match the hash-verified cyclic fixture")
    expected, reference_indices, phase_counts = active_phase_plan([trace["summary"] for trace in sender], fixture["frames"])
    if not expected:
        raise ValueError("No active planned raw-input slots were measured")
    sender_rows, sender_bytes = [], 0
    for trace in sender:
        rows, payload_bytes, _ = verified_phase(trace, base_path, width, height, fps)
        sender_rows.extend(rows)
        sender_bytes += payload_bytes
    receiver_phases = [(*verified_phase(trace, base_path, width, height, fps), trace["phase"]) for trace in receiver]
    receiver_phases.sort(key=lambda phase: phase[0][0][0] if phase[0] else float("inf"))
    receiver_rows = [row for rows, _, _, _ in receiver_phases for row in rows]
    indices, delivery = align_delivery(sender_rows, receiver_rows, expected)
    result.update(delivery)
    for phase in phase_counts:
        start, count = phase["timeline_start_index"], phase["planned_frames"]
        phase_pts = expected[start:start + count]
        selected_pts = set(phase_pts)
        _, phase_delivery = align_delivery([row for row in sender_rows if row[0] in selected_pts],
                                            [row for row in receiver_rows if row[0] in selected_pts], phase_pts)
        phase.update({key: value for key, value in phase_delivery.items() if key != "timeline"})
        active_rows = result["timeline"][start:start + count]
        phase["held_frames"] = sum(row["kind"] == "hold" for row in active_rows)
        phase["black_frames"] = sum(row["kind"] == "black" for row in active_rows)
        for index in range(count):
            result["timeline"][start + index].update(source_phase=phase["phase"], phase_source_frame=index,
                                                    fixture_frame=reference_indices[start + index])
    output_directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="phase-delivery-", dir=output_directory.resolve()) as directory:
        work = Path(directory).resolve()
        work.relative_to(output_directory.resolve())  # Exact owned scratch tree before recursive cleanup.
        decoded, delivered, cyclic_reference = [work / name for name in ("decoded.yuv", "delivered.yuv", "reference.yuv")]
        decoded_phases = []
        for index, (rows, _, ivf, _) in enumerate(receiver_phases):
            if not rows:
                continue
            phase_decoded = work / f"receiver-{index}.yuv"
            run(["ffmpeg", "-y", "-v", "error", "-i", str(ivf), "-map", "0:v:0", "-fps_mode", "passthrough",
                 "-pix_fmt", "yuv420p", "-f", "rawvideo", str(phase_decoded)])
            if phase_decoded.stat().st_size != len(rows) * width * height * 3 // 2:
                raise ValueError("A receiver phase cannot map every AU to a decoded frame; no easy-prefix selection")
            decoded_phases.append(phase_decoded)
        if len(decoded_phases) == 1:
            os.replace(decoded_phases[0], decoded)  # One phase is already the whole decoded stream: no 150 MB+ copy
        else:
            with decoded.open("wb") as destination:
                for phase_decoded in decoded_phases:
                    with phase_decoded.open("rb") as source_file:
                        shutil.copyfileobj(source_file, destination)
        write_timeline(decoded, delivered, indices, width, height, len(receiver_rows))
        write_timeline(reference, cyclic_reference, reference_indices, width, height, fixture["frames"])
        result["quality"] = score_quality(delivered, cyclic_reference, width, height, fps, len(expected), output_directory.resolve())
        sidecars = (fixture.get("declared_metadata") or {}).get("sidecars", {})
        result["content_regions"] = {"measurement_status": "UNMEASURED", "acceptance_status": "UNMEASURED",
                                     "reason": "Native frame/marker-layout sidecars not supplied"}
        if {"frames", "marker_layout"} <= sidecars.keys():
            try:
                from score_reference_regions import analyze as score_regions
                regions = score_regions(reference, delivered, width, height, fps, reference_indices,
                                        sidecars["frames"], sidecars["marker_layout"], base_path, panels_record=sidecars.get("panels"))
                regions["evidence"]["delivered"].pop("path", None)
                regions["evidence"]["delivered"]["reconstruction"] = "Recreate every source slot from delivery-result.json timeline and original captured phases"
                path = output_directory / "reference-regions.json"
                path.write_text(json.dumps(regions, indent=2, allow_nan=False) + "\n", encoding="utf-8")
                result["content_regions"] = {"measurement_status": regions["measurement_status"], "acceptance_status": "UNMEASURED",
                                             "evidence": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            except (ValueError, OSError, KeyError, TypeError) as error:
                result["content_regions"]["reason"] = str(error)
    duration = len(expected) / fps
    clock_duration = sum(phase["end_pts_exclusive_us"] - phase["origin_pts_us"] for phase in phase_counts) / 1_000_000
    result.update(status="ok", measurement_status="MEASURED", phases=phase_counts, width=width, height=height, fps=fps,
                  planned_active_duration_seconds=duration, sender_payload_bytes=sender_bytes,
                  active_source_clock_duration_seconds=clock_duration,
                  receiver_payload_bytes=sum(phase[1] for phase in receiver_phases),
                  sender_payload_kbps_over_planned_duration=sender_bytes * 8 / duration / 1000,
                  sender_payload_kbps_over_active_source_clock_duration=sender_bytes * 8 / clock_duration / 1000,
                  alignment="Every source-clock slot before each explicit exclusive phase end; cyclic reference restarts only with actual source phases",
                  reconstruction="Previous decoded frame held for every missing slot; neutral black before first reception",
                  decoder_phases=[{"phase": phase[3], "received_aus": len(phase[0])} for phase in receiver_phases],
                  working_files="Exact owned temporary raw files removed after scoring; original trace/reference evidence preserved")
    (output_directory / "delivery-result.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("reference", "sender-csv", "sender-ivf", "receiver-csv", "receiver-ivf", "output-directory"):
        parser.add_argument("--" + name, type=Path, required=name == "output-directory")
    for name in ("width", "height", "fps", "frames"):
        parser.add_argument("--" + name, type=int)
    parser.add_argument("--pts-origin-us", type=int, default=0, help="Explicit fixture origin; never inferred from the first received frame")
    parser.add_argument("--run-manifest", type=Path, help="All actual finalized raw-input phases; no received-prefix selection")
    parser.add_argument("--sender", help="Explicit source endpoint for independent private-call fixtures")
    parser.add_argument("--receiver", help="Receiver alias in the run manifest")
    args = parser.parse_args()
    args.output_directory = output_path(args.output_directory, "runs")
    if args.run_manifest:
        args.run_manifest = resolve(args.run_manifest)
        if not args.receiver or any(getattr(args, name) is not None for name in (
            "reference", "sender_csv", "sender_ivf", "receiver_csv", "receiver_ivf", "width", "height", "fps", "frames"
        )) or args.pts_origin_us:
            parser.error("Run-manifest mode needs --receiver and cannot mix fixed-window inputs")
        if args.run_manifest.resolve() in {(args.output_directory / name).resolve() for name in ("vmaf.json", "delivery-result.json")}:
            parser.error("Scorer output would overwrite its input manifest")
        result = score_phase_delivery(json.loads(args.run_manifest.read_text(encoding="utf-8-sig")), args.receiver,
                                      args.output_directory, original(args.run_manifest).parent, args.sender)
    else:
        if args.sender or args.receiver or any(getattr(args, name) is None for name in (
            "reference", "sender_csv", "sender_ivf", "receiver_csv", "receiver_ivf", "width", "height", "fps", "frames"
        )):
            parser.error("Fixed-window mode requires reference, both CSV/IVF pairs, width/height/FPS/frames")
        result = score_delivery(args.reference, args.sender_csv, args.sender_ivf, args.receiver_csv,
                                args.receiver_ivf, args.width, args.height, args.fps, args.frames,
                                args.output_directory, args.pts_origin_us)
    print(json.dumps({key: result[key] for key in (
        "scope", "planned_frames", "received_frames", "total_missing_frames",
        "fixture_received_coverage_percent", "quality", "measurement_status", "reasons",
    ) if key in result}, indent=2, allow_nan=False))
    print(f"Full alignment evidence: {args.output_directory / 'delivery-result.json'}")


if __name__ == "__main__":
    main()
