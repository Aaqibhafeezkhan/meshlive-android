"""Score the receiver's accepted AudioTrack PCM against the speech reference; decoded-PCM path only.

Input is one finalized receiver DEBUG PCM trace (<phase>.wav, <phase>.csv, <phase>-summary.json from DebugAudioTrace) and
the reference WAV plus the speech-v1 provenance sidecar that names its segments. Chunks are placed on a presentation-time
timeline (source_pts_us), so lost frames appear as zero-filled gaps instead of being hidden by concatenation. The reference
is looped exactly as the app loops it; the loop offset is found by cross-correlation (a sender-trace origin can be passed
only as a consistency check), then refined per segment.

Reported: gap/dropout share, speech level error (active-RMS-v1, dB) and the loudness-step response, plus perceptual scores.
ViSQOL is run only through --visqol-binary (no reproducible Windows build); the always-available documented fallbacks are
PESQ P.862.2 wideband (`pesq` package, 16 kHz) and STOI/ESTOI (`pystoi`). Each is labelled with its tool and version, is only
valid on speech segments, and carries no acceptance tolerance: they are diagnostics, not a pass. Speaker, microphone, air path
and mouth-to-ear quality stay UNMEASURED.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import wave

import numpy as np
from scipy.signal import fftconvolve, resample_poly

from benchmark_layout import output_path, resolve as resolve_input

RATE = 48000
FRAME = RATE // 50                 # 20 ms analysis frame
CSV_FIELDS = ("sample_start", "sample_count", "source_pts_us", "stage", "at_ns")
MAX_SPAN_S = 900                    # refuse a PTS span that would need an unreasonable timeline
GAP_MIN_SAMPLES = 96                # 2 ms; smaller holes are PTS rounding, not loss
LOCAL_SEARCH_MS = 60
MIN_PERCEPTUAL_S = 2.0
MIN_CORRELATION = 0.12
MIN_PEAK_RATIO = 1.3
ACTIVE_FLOOR_DBFS = -70.0
LIMITATIONS = [
    "Decoded accepted PCM only: AudioTrack write path, not speaker output, microphone capture, air path or mouth-to-ear quality.",
    "PESQ and STOI are intrusive speech metrics for narrowband/wideband codecs; they are not valid on music, silence or "
    "unequal-level steps (PESQ normalises level) and have no validated acceptance tolerance here, so they are diagnostics.",
    "Time alignment uses the declared loop and cross-correlation; lost frames are placed by PTS, so a receiver that stretches "
    "or conceals audio is scored on what it accepted, not on what it played.",
    "Short items (under about 8 s) give noisier PESQ/STOI than the standards assume.",
]


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def read_wav(path):
    with wave.open(str(path), "rb") as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, RATE, "NONE"):
            raise ValueError(f"{path}: expected PCM16 mono 48000 Hz")
        raw = wav.readframes(wav.getnframes())
        if len(raw) != wav.getnframes() * 2:
            raise ValueError(f"{path}: truncated PCM payload")
    return np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0


def db_power(power):
    return 10 * math.log10(max(float(power), 1e-24))


def frames_of(x):
    count = len(x) // FRAME
    return x[:count * FRAME].reshape(count, FRAME)


def active_mask(reference):
    """Frames within 30 dB of the loudest reference frame and above the absolute floor."""
    power = (frames_of(reference) ** 2).mean(axis=1)
    if not len(power) or power.max() <= 0:
        return np.zeros(len(power), dtype=bool)
    return (power >= power.max() * 1e-3) & (10 * np.log10(np.maximum(power, 1e-24)) >= ACTIVE_FLOOR_DBFS)


# ---------------------------------------------------------------- trace
def load_trace(wav_path, csv_path, summary_path, stage="track_accepted"):
    issues = []
    summary = json.loads(Path(summary_path).read_text(encoding="utf-8-sig"))
    if summary.get("schema") != "benchmark-pcm-trace-v1" or summary.get("role") != "receiver":
        issues.append("summary_is_not_a_receiver_benchmark_pcm_trace_v1")
    if summary.get("finalized") is not True or summary.get("error") is not None or summary.get("trace_overflow_chunks") != 0:
        issues.append("trace_not_finalized_or_has_errors_or_overflow")
    pcm = read_wav(wav_path)
    rows = []
    with Path(csv_path).open(encoding="utf-8-sig") as source:
        lines = [line.strip() for line in source if line.strip()]
    header = lines[0].split(",")
    if any(field not in header for field in CSV_FIELDS):
        raise ValueError("PCM chunk CSV lacks the DebugAudioTrace columns")
    index = {name: header.index(name) for name in CSV_FIELDS}
    for line in lines[1:]:
        cells = line.split(",")
        rows.append({"start": int(cells[index["sample_start"]]), "count": int(cells[index["sample_count"]]),
                     "pts_us": int(cells[index["source_pts_us"]]), "stage": cells[index["stage"]]})
    if summary.get("sample_count") != len(pcm):
        issues.append("summary_sample_count_differs_from_wav")
    cursor = 0
    for row in rows:
        if row["start"] != cursor or row["count"] <= 0:
            issues.append("chunk_rows_are_not_contiguous_in_the_wav")
            break
        cursor += row["count"]
    if cursor != len(pcm):
        issues.append("chunk_rows_do_not_cover_the_wav")
    chosen = [row for row in rows if row["stage"] == stage]
    if not chosen:
        issues.append(f"no_{stage}_rows")
    return pcm, chosen, summary, issues


def place_on_timeline(pcm, rows):
    """Zero-filled presentation-time timeline plus a coverage mask; returns None when there is nothing to place."""
    if not rows:
        return None
    origin = min(row["pts_us"] for row in rows)
    positions = [int(round((row["pts_us"] - origin) * RATE / 1_000_000)) for row in rows]
    length = max(position + row["count"] for position, row in zip(positions, rows))
    if length > MAX_SPAN_S * RATE:
        raise ValueError(f"PTS span of {length / RATE:.0f} s exceeds the {MAX_SPAN_S} s limit; trace mixes sessions or restarts")
    timeline = np.zeros(length)
    covered = np.zeros(length, dtype=bool)
    overlaps, backwards, previous_end = 0, 0, 0
    for position, row in zip(positions, rows):
        if position < previous_end:
            overlaps += int(min(previous_end - position, row["count"]))
            if previous_end - position > RATE // 10:
                backwards += 1
        timeline[position:position + row["count"]] = pcm[row["start"]:row["start"] + row["count"]]
        covered[position:position + row["count"]] = True
        previous_end = max(previous_end, position + row["count"])
    return {"samples": timeline, "covered": covered, "origin_pts_us": origin, "overlap_samples": overlaps,
            "backward_pts_events": backwards}


def gap_runs(covered, minimum=GAP_MIN_SAMPLES):
    runs, start = [], None
    for i in np.flatnonzero(np.diff(np.concatenate(([True], covered, [True])).astype(np.int8))):
        if start is None:
            start = i
        else:
            if i - start >= minimum:
                runs.append((int(start), int(i - start)))
            start = None
    return runs


# ---------------------------------------------------------------- alignment
def to16(x):
    return resample_poly(x, 1, 3)


def estimate_loop_offset(timeline, reference, search_s=40):
    """Return (lag_samples, correlation, peak_ratio): timeline[t] ~ reference[(t + lag) % N]."""
    length = len(reference)
    window = timeline[:min(len(timeline), search_s * RATE)]
    r16, ref16 = to16(window), to16(reference)
    if float(np.dot(r16, r16)) <= 0:
        return 0, 0.0, 0.0
    tile = np.tile(ref16, 2 + len(r16) // len(ref16))[:len(ref16) + len(r16) - 1]
    corr = fftconvolve(tile, r16[::-1], mode="valid")[:len(ref16)]
    energy = np.concatenate(([0.0], np.cumsum(tile ** 2)))
    local = energy[len(r16):len(r16) + len(ref16)] - energy[:len(ref16)]
    rho = corr / np.sqrt(np.maximum(local, 1e-12) * float(np.dot(r16, r16)))
    best = int(np.argmax(rho))
    guard = 320
    mask = np.ones(len(rho), dtype=bool)
    mask[np.arange(best - guard, best + guard + 1) % len(rho)] = False
    second = float(np.max(rho[mask])) if mask.any() else 0.0
    peak = float(rho[best])
    coarse = best * 3
    # refine at 48 kHz around the 16 kHz peak using the loudest 10 s of the window
    hop = FRAME
    power = (frames_of(window) ** 2).mean(axis=1)
    quiet = max(0, int(np.argmax(np.convolve(power, np.ones(500), "valid")))) if len(power) >= 500 else 0
    start = quiet * hop
    probe = window[start:start + 10 * RATE]
    best_lag, best_score = coarse % length, -2.0
    for delta in range(-6, 7):
        lag = (coarse + delta) % length
        index = (np.arange(len(probe)) + start + lag) % length
        reference_slice = reference[index]
        score = float(np.dot(probe, reference_slice) / math.sqrt(max(float(np.dot(probe, probe) * np.dot(reference_slice, reference_slice)), 1e-24)))
        if score > best_score:
            best_lag, best_score = lag, score
    return best_lag, peak, (peak / second if second > 0 else float("inf"))


def local_shift(reference, timeline, start, search=LOCAL_SEARCH_MS * RATE // 1000):
    """Shift (samples) of the best-matching timeline position near `start`, by normalised 16 kHz cross-correlation."""
    length = len(reference)
    if length < 4800 or float(np.dot(reference, reference)) <= 0:
        return 0
    lo, hi = max(0, start - search), min(len(timeline), start + length + search)
    a, b = to16(reference), to16(timeline[lo:hi])
    if len(b) < len(a):
        return 0
    corr = fftconvolve(b, a[::-1], mode="valid")
    energy = np.concatenate(([0.0], np.cumsum(b ** 2)))
    local = energy[len(a):len(a) + len(corr)] - energy[:len(corr)]
    score = corr / np.sqrt(np.maximum(local, 1e-12))
    return int(lo + 3 * int(np.argmax(score)) - start)


# ---------------------------------------------------------------- metrics
def lsd_db(reference, degraded, mask):
    n = 1024
    window = np.hanning(n)
    values = []
    for index in np.flatnonzero(mask):
        start = index * FRAME
        if start + n > len(reference):
            continue
        r = np.abs(np.fft.rfft(reference[start:start + n] * window)) ** 2 + 1e-10
        d = np.abs(np.fft.rfft(degraded[start:start + n] * window)) ** 2 + 1e-10
        values.append(math.sqrt(float(np.mean((10 * np.log10(r / d)) ** 2))))
    return float(np.mean(values)) if values else None


def perceptual(reference, degraded, visqol=None):
    """PESQ WB, STOI, ESTOI (and optional ViSQOL) on 16 kHz copies; each failure is recorded, not hidden."""
    result = {}
    r16, d16 = to16(reference), to16(degraded)
    try:
        import pesq
        value = float(pesq.pesq(16000, r16.astype(np.float32), d16.astype(np.float32), "wb"))
        result["pesq_wb"] = {"status": "MEASURED", "value": value, "tool": "pesq PyPI package (ITU-T P.862.2 wideband, MOS-LQO)"}
    except ImportError:
        result["pesq_wb"] = {"status": "UNMEASURED", "reason": "pesq package not installed (opt-in, licence-restricted: pip install -r tools/requirements-audio-pesq.txt)"}
    except Exception as error:  # the package raises library-specific errors such as NoUtterancesError
        result["pesq_wb"] = {"status": "UNMEASURED", "reason": f"{type(error).__name__}: {error}"}
    try:
        from pystoi import stoi
        result["stoi"] = {"status": "MEASURED", "value": float(stoi(r16, d16, 16000, extended=False)), "tool": "pystoi STOI"}
        result["estoi"] = {"status": "MEASURED", "value": float(stoi(r16, d16, 16000, extended=True)), "tool": "pystoi ESTOI"}
    except ImportError:
        reason = {"status": "UNMEASURED", "reason": "pystoi package not installed (pip install -r tools/requirements-audio.txt)"}
        result["stoi"], result["estoi"] = reason, dict(reason)
    except Exception as error:
        reason = {"status": "UNMEASURED", "reason": f"{type(error).__name__}: {error}"}
        result["stoi"], result["estoi"] = reason, dict(reason)
    if visqol:
        result["visqol_speech"] = visqol_score(visqol, r16, d16)
    return result


def visqol_score(command, reference16, degraded16):
    """Run an externally supplied ViSQOL CLI in speech mode on 16 kHz mono PCM files; parse its MOS-LQO line."""
    with tempfile.TemporaryDirectory(prefix="visqol-") as scratch:
        files = []
        for name, x in (("ref.wav", reference16), ("deg.wav", degraded16)):
            path = Path(scratch) / name
            with wave.open(str(path), "wb") as target:
                target.setnchannels(1)
                target.setsampwidth(2)
                target.setframerate(16000)
                target.writeframes(np.round(np.clip(x, -1, 32767 / 32768) * 32768).astype("<i2").tobytes())
            files.append(str(path))
        try:
            done = subprocess.run([*command, "--reference_file", files[0], "--degraded_file", files[1], "--use_speech_scoring"],
                                  capture_output=True, text=True, timeout=300)
        except (OSError, subprocess.TimeoutExpired) as error:
            return {"status": "UNMEASURED", "reason": f"ViSQOL could not run: {error}"}
    for line in done.stdout.splitlines():
        if line.strip().startswith("MOS-LQO"):
            try:
                return {"status": "MEASURED", "value": float(line.split(":")[1].split()[0]), "tool": "ViSQOL v3 speech mode (external binary)"}
            except (IndexError, ValueError):
                break
    return {"status": "UNMEASURED", "reason": f"ViSQOL returned {done.returncode} without a MOS-LQO line"}


def instance_metrics(segment, loop, ref, deg, covered, local):
    mask = active_mask(ref)
    ref_frames, deg_frames, cover_frames = frames_of(ref), frames_of(deg), frames_of(covered.astype(np.float64))
    out = {"segment": segment["id"], "kind": segment["kind"], "scoring": segment["scoring"], "loop": loop,
           "duration_s": len(ref) / RATE, "local_offset_ms": local * 1000 / RATE,
           "uncovered_share": float(1 - covered.mean()) if len(covered) else 1.0}
    zero = (deg_frames == 0).all(axis=1) if len(deg_frames) else np.zeros(0, dtype=bool)
    lost = (cover_frames.mean(axis=1) < 0.5) | zero if len(deg_frames) else zero
    n = min(len(mask), len(lost))
    mask, lost = mask[:n], lost[:n]
    out["active_frames"] = int(mask.sum())
    out["dropout_share_of_active_frames"] = float((mask & lost).sum() / mask.sum()) if mask.any() else None
    usable = mask & ~lost
    if usable.any():
        r_power = (ref_frames[:n][usable] ** 2).mean()
        d_power = (deg_frames[:n][usable] ** 2).mean()
        out["reference_active_dbfs"], out["received_active_dbfs"] = db_power(r_power), db_power(d_power)
        out["level_error_db"] = db_power(d_power) - db_power(r_power)
        out["lsd_db"] = lsd_db(ref, deg, usable)
        r, d = ref[:n * FRAME], deg[:n * FRAME]
        out["correlation"] = float(np.dot(r, d) / math.sqrt(max(float(np.dot(r, r) * np.dot(d, d)), 1e-24)))
    elif segment["scoring"] == "silence":
        out["reference_rms_dbfs"] = db_power(np.mean(ref ** 2))
        out["received_rms_dbfs"] = db_power(np.mean(deg ** 2))
        out["noise_floor_error_db"] = out["received_rms_dbfs"] - out["reference_rms_dbfs"]
        out["received_digital_zero_share"] = float((deg == 0).mean())
    return out


# ---------------------------------------------------------------- analysis
def analyze(reference_path, provenance_path, trace, variant=None, origin_pts_us=None, visqol=None, expected=None):
    reference_path, provenance_path = Path(reference_path), Path(provenance_path)
    reference = read_wav(reference_path)
    provenance = json.loads(provenance_path.read_text(encoding="utf-8-sig"))
    reference_sha = sha256_file(reference_path)
    matches = [(name, row) for name, row in provenance.get("variants", {}).items() if row["sha256"] == reference_sha]
    if variant:
        matches = [m for m in matches if m[0] == variant]
    if len(matches) != 1:
        raise ValueError("Reference WAV matches no single variant of the supplied provenance (SHA256 mismatch)")
    name, row = matches[0]
    if row["sample_count"] != len(reference):
        raise ValueError("Provenance sample count differs from the reference WAV")
    evidence = {"reference": {"path": str(reference_path), "sha256": reference_sha},
                "provenance": {"path": str(provenance_path), "sha256": sha256_file(provenance_path)}}
    for label in ("wav", "csv", "summary"):
        evidence[label] = {"path": str(trace[label]), "sha256": sha256_file(trace[label])}
    for label, digest in (expected or {}).items():
        if label not in evidence or evidence[label]["sha256"] != digest.lower():
            raise ValueError(f"Expected SHA256 for {label} does not match the supplied file")
    pcm, rows, summary, issues = load_trace(trace["wav"], trace["csv"], trace["summary"])
    result = {"schema": "benchmark-audio-quality-v1", "measurement_status": "UNMEASURED", "reference_variant": name,
              "evidence": evidence, "trace_scope": {k: summary.get(k) for k in ("session", "phase", "pid", "role", "clock")},
              "issues": issues, "limitations": LIMITATIONS, "metrics": {}, "segments": [], "perceptual": {},
              "physical_audio_quality": "UNMEASURED"}
    if issues:
        result["issues"].append("trace_rejected_nothing_scored")
        return result
    placed = place_on_timeline(pcm, rows)
    timeline, covered = placed["samples"], placed["covered"]
    runs = gap_runs(covered)
    result["metrics"]["timeline"] = {"seconds": len(timeline) / RATE, "origin_pts_us": placed["origin_pts_us"],
                                     "overlap_samples": placed["overlap_samples"], "backward_pts_events": placed["backward_pts_events"]}
    result["metrics"]["dropout"] = {"gap_events": len(runs), "gap_share": float(1 - covered.mean()),
                                    "max_gap_ms": max((n for _, n in runs), default=0) * 1000 / RATE,
                                    "gap_ms": [round(n * 1000 / RATE, 2) for _, n in runs[:200]],
                                    "gap_definition": f"holes of at least {GAP_MIN_SAMPLES} samples between accepted chunks on the PTS timeline"}
    lag, rho, ratio = estimate_loop_offset(timeline, reference)
    ok = rho >= MIN_CORRELATION and ratio >= MIN_PEAK_RATIO
    result["metrics"]["alignment"] = {"loop_offset_samples": int(lag), "loop_offset_ms": lag * 1000 / RATE,
                                      "loop_offset_signed_samples": int(lag - len(reference) if lag > len(reference) // 2 else lag),
                                      "peak_correlation": rho,
                                      "peak_to_second_peak": ratio if math.isfinite(ratio) else None, "confident": bool(ok),
                                      "method": "16 kHz FFT cross-correlation against the looped reference, refined at 48 kHz"}
    if origin_pts_us is not None:
        predicted = int(round((placed["origin_pts_us"] - origin_pts_us) * RATE / 1_000_000)) % len(reference)
        miss = (lag - predicted + len(reference) // 2) % len(reference) - len(reference) // 2
        result["metrics"]["alignment"]["sender_origin_check_ms"] = miss * 1000 / RATE
    if not ok:
        result["issues"].append("alignment_not_confident_trace_does_not_match_reference_loop")
        return result
    period = len(reference)
    instances = []
    for segment in row["segments"]:
        length = segment["end_sample"] - segment["start_sample"]
        loops = 0
        while True:
            start = segment["start_sample"] - lag + loops * period
            loops += 1
            if start + length <= 0:
                continue
            if start >= len(timeline):
                break
            if start < 0 or start + length > len(timeline):
                result["segments"].append({"segment": segment["id"], "loop": loops - 1, "status": "PARTIAL_COVERAGE_NOT_SCORED"})
                continue
            ref = reference[segment["start_sample"]:segment["end_sample"]]
            shift = local_shift(ref, timeline, start) if segment["scoring"] != "silence" else 0
            lo = start + shift
            if lo < 0 or lo + length > len(timeline):
                shift, lo = 0, start
            deg, cov = timeline[lo:lo + length], covered[lo:lo + length]
            metrics = instance_metrics(segment, loops - 1, ref, deg, cov, shift)
            instances.append((segment, metrics, ref, deg))
            result["segments"].append(metrics)
    scored = [m for _, m, _, _ in instances]
    if not scored:
        result["issues"].append("no_segment_instance_fully_covered")
        return result
    result["measurement_status"] = "MEASURED"
    speech = [m for m in scored if m["scoring"] == "speech" and m.get("level_error_db") is not None]
    level = result["metrics"]["level"] = {}
    if speech:
        weights = np.array([m["duration_s"] for m in speech])
        errors = np.array([m["level_error_db"] for m in speech])
        level.update(speech_level_error_db_mean=float(np.average(errors, weights=weights)),
                     speech_level_error_db_worst=float(errors[np.argmax(np.abs(errors))]),
                     definition="received minus reference active-RMS-v1 level on reference-active frames that were not dropped")
    steps = [m for m in scored if m["kind"] == "level_step" and m.get("received_active_dbfs") is not None]
    if len(steps) >= 2 and np.ptp([m["reference_active_dbfs"] for m in steps]) > 6:
        x = np.array([m["reference_active_dbfs"] for m in steps])
        y = np.array([m["received_active_dbfs"] for m in steps])
        slope, intercept = np.polyfit(x, y, 1)
        level["loudness_step_response"] = {"slope": float(slope), "intercept_db": float(intercept),
                                           "max_abs_residual_db": float(np.max(np.abs(y - (slope * x + intercept)))), "steps": len(steps),
                                           "note": "slope 1.0 means the stack preserved the -30..-6 dBFS peak steps; <1 indicates AGC/limiting/gating"}
    active = sum(m["active_frames"] for m in scored if m["scoring"] == "speech")
    lost = sum((m.get("dropout_share_of_active_frames") or 0) * m["active_frames"] for m in scored if m["scoring"] == "speech")
    result["metrics"]["dropout"]["speech_active_frame_dropout_share"] = float(lost / active) if active else None
    offsets = [m["local_offset_ms"] for m in scored if m["scoring"] != "silence" and "local_offset_ms" in m]
    if offsets:
        result["metrics"]["alignment"]["local_offset_ms"] = {"min": float(min(offsets)), "max": float(max(offsets)),
                                                           "note": "per-segment residual after the loop offset; wander shows playout stretch or drift"}
    # Perceptual scores: one item per speech segment (or group) per loop, never on music/silence.
    items = {}
    for segment, metrics, ref, deg in instances:
        if segment["scoring"] != "speech":
            continue
        key = (segment.get("group") or segment["id"], metrics["loop"])
        items.setdefault(key, []).append((ref, deg))
    rows_out, values = [], {"pesq_wb": [], "stoi": [], "estoi": [], "visqol_speech": []}
    for (label, loop), parts in sorted(items.items(), key=lambda kv: (str(kv[0][0]), kv[0][1])):
        ref = np.concatenate([p[0] for p in parts])
        deg = np.concatenate([p[1] for p in parts])
        if len(ref) < MIN_PERCEPTUAL_S * RATE:
            rows_out.append({"item": label, "loop": loop, "status": "UNMEASURED", "reason": f"shorter than {MIN_PERCEPTUAL_S:.0f} s"})
            continue
        scores = perceptual(ref, deg, visqol)
        rows_out.append({"item": label, "loop": loop, "duration_s": len(ref) / RATE, **scores})
        for metric in values:
            if scores.get(metric, {}).get("status") == "MEASURED":
                values[metric].append((len(ref) / RATE, scores[metric]["value"]))
    result["perceptual"]["items"] = rows_out
    summary_rows = {}
    for metric, pairs in values.items():
        if pairs:
            weights = np.array([w for w, _ in pairs])
            numbers = np.array([v for _, v in pairs])
            summary_rows[metric] = {"status": "MEASURED", "duration_weighted_mean": float(np.average(numbers, weights=weights)),
                                    "min": float(numbers.min()), "items": len(pairs)}
        elif metric != "visqol_speech" or visqol:
            reasons = {r[metric].get("reason") for r in rows_out if isinstance(r.get(metric), dict) and r[metric].get("reason")}
            summary_rows[metric] = {"status": "UNMEASURED", "reason": "; ".join(sorted(map(str, reasons))) or "no eligible speech item"}
    if not visqol:
        summary_rows["visqol_speech"] = {"status": "UNMEASURED", "reason": "no reproducible Windows ViSQOL build; pass --visqol-binary to run an external one"}
    result["perceptual"]["summary"] = summary_rows
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reference", type=Path, required=True, help="speech-v1 WAV that was injected")
    parser.add_argument("--provenance", type=Path, required=True, help="speech-v1.provenance.json beside the build")
    parser.add_argument("--trace", type=Path, help="receiver PCM trace prefix without extension (.wav/.csv/-summary.json)")
    parser.add_argument("--wav", type=Path)
    parser.add_argument("--csv", type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--variant")
    parser.add_argument("--origin-pts-us", type=int, help="sender reference-audio origin_us from the log, used only as a check")
    parser.add_argument("--visqol-binary", type=str, help="external ViSQOL v3 executable (optional)")
    parser.add_argument("--expect-sha256", action="append", default=[], metavar="NAME=HEX",
                        help="bind evidence: reference, provenance, wav, csv or summary")
    parser.add_argument("--output", type=Path, required=True, help="fresh result JSON under benchmarks/runs or OS temporary scratch")
    args = parser.parse_args(argv)
    try:
        if args.trace:
            trace = {"wav": Path(f"{args.trace}.wav"), "csv": Path(f"{args.trace}.csv"), "summary": Path(f"{args.trace}-summary.json")}
        elif args.wav and args.csv and args.summary:
            trace = {"wav": args.wav, "csv": args.csv, "summary": args.summary}
        else:
            parser.error("give --trace PREFIX or all of --wav --csv --summary")
        trace = {k: resolve_input(v) for k, v in trace.items()}
        expected = dict(item.split("=", 1) for item in args.expect_sha256)
        output = output_path(args.output, "runs")
        if output.exists():
            raise ValueError("Output already exists; use a fresh explicit path")
        result = analyze(resolve_input(args.reference), resolve_input(args.provenance), trace, args.variant, args.origin_pts_us,
                         [args.visqol_binary] if args.visqol_binary else None, expected)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    except (ValueError, OSError, KeyError, wave.Error, json.JSONDecodeError) as error:
        parser.error(str(error))
    summary = result["perceptual"].get("summary", {})
    pesq_row = summary.get("pesq_wb", {})
    print(f"{result['measurement_status']}: PESQ-WB {pesq_row.get('duration_weighted_mean', pesq_row.get('reason', 'n/a'))}; "
          f"gap share {result['metrics'].get('dropout', {}).get('gap_share', 'n/a')}; physical audio quality UNMEASURED. {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
