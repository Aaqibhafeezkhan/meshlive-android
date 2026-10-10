"""Supplemental native luma fidelity, with the generator's fixed marker mask.

The caller supplies one exact source file-frame index for every reconstructed
timeline frame. This module never selects a prefix, aligns by similarity, changes
geometry or claims that native code-value fidelity measures phone screen quality.
"""

from collections import defaultdict, deque
import concurrent.futures as futures
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import re
import threading

import numpy as np
from scipy.ndimage import convolve1d, minimum_filter

from benchmark_layout import resolve as resolve_input
from score_parallel import score_threads

from score_visual_markers import FRAME_FIELDS, csv_integer, decode_marker, file_record, integer, marker_bounds, read_csv


SCHEMA = "benchmark-reference-regions-v1"
ALGORITHM = {
    "version": "native-content-luma-v1",
    "psnr": "10*log10(255^2/MSE), masked native 8-bit Y code values; zero MSE => 'inf'",
    "ssim": "11x11 normalized Gaussian, sigma=1.5, population covariance, K1=0.01, K2=0.03, data_range=255",
    "ssim_edges": "Valid native windows only; exclude every window touching a declared marker pixel",
    "distribution": "Empirical inverse-CDF quantiles at 0.05, 0.50, 0.95, including perfect PSNR frames",
    "alignment": "Every provided source slot in order; no trim, resample, crop or nearest match",
}
_AXIS = np.arange(-5, 6, dtype=np.float64)
_KERNEL = np.exp(-_AXIS ** 2 / (2 * 1.5 ** 2))
_KERNEL /= _KERNEL.sum()


_VERIFIED = {}


def _fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     allow_nan=False).encode()).hexdigest()


def _path(path, base):
    return resolve_input(path, base=base)


def _marker_bounds(layout, width, height):
    if not isinstance(layout, dict) or layout.get("schema") != "vsg-marker-v1" or layout.get("status") not in {"AVAILABLE", "DISABLED", "UNMEASURED"}:
        raise ValueError("Expected explicit vsg-marker-v1 availability and native layout")
    cell = integer(layout.get("cell_size"), "cell_size", 2)
    if cell % 2 or layout.get("cells") != [26, 25] or layout.get("content_roi", {}).get("geometry") != [width, height]:
        raise ValueError("Marker geometry differs from the declared native profile")
    if layout["status"] != "AVAILABLE":
        if layout.get("bounds_xywh") is not None:
            raise ValueError("Unavailable marker cannot declare pixel bounds")
        return None
    return marker_bounds(layout, width, height)


def _content_mask(layout, width, height, segment_ids):
    expected = []
    bounds = _marker_bounds(layout, width, height)
    if bounds is not None:
        expected.append(bounds)
    cues = layout.get("visual_cues")
    if cues is not None:
        rect = cues.get("bounds_xywh") if isinstance(cues, dict) else None
        if not isinstance(cues, dict) or cues.get("schema") != "vsg-visual-cues-v1" or not isinstance(cues.get("events"), list) or not cues["events"] or not isinstance(rect, list) or len(rect) != 4 or any(type(n) is not int or n % 2 for n in rect):
            raise ValueError("Visual cue mask needs its complete predeclared native cue track")
        x, y, cw, ch = rect
        if min(x, y) < 0 or min(cw, ch) <= 0 or x + cw > width or y + ch > height or any(event.get("bounds_xywh") != rect for event in cues["events"]):
            raise ValueError("Visual cue bounds/events disagree with the exact native profile")
        expected.append(rect)
    tracks = layout.get("intrinsic_tracks", [])
    if not isinstance(tracks, list):
        raise ValueError("Intrinsic marker tracks must be explicitly listed")
    track_ids = set()
    for track in tracks:
        if not isinstance(track, dict) or track.get("segment_id") not in segment_ids or track["segment_id"] in track_ids:
            raise ValueError("Intrinsic marker track must identify one known, unique source segment")
        track_ids.add(track["segment_id"])
        bounds = _marker_bounds(track, width, height)
        if track["content_roi"].get("exclude_xywh") != ([bounds] if bounds is not None else []):
            raise ValueError("Intrinsic content mask differs from its declared marker bounds")
        if bounds is not None and bounds not in expected:
            expected.append(bounds)
    roi = layout["content_roi"]
    excluded = roi.get("exclude_xywh")
    if not isinstance(excluded, list) or any(not isinstance(rect, list) or len(rect) != 4 or
            any(type(value) is not int for value in rect) for rect in excluded):
        raise ValueError("Content ROI must explicitly list native integer marker rectangles")
    if sorted(excluded) != sorted(expected):
        raise ValueError("Content ROI differs from the predeclared external/intrinsic marker union")
    mask = np.ones((height, width), dtype=bool)
    for x, y, rw, rh in excluded:
        mask[y:y + rh, x:x + rw] = False
    included = int(mask.sum())
    if not included:
        raise ValueError("Content ROI must contain a nonempty native pixel mask")
    if roi.get("status") != "AVAILABLE" or type(roi.get("included_pixels")) is not int or roi["included_pixels"] != included:
        raise ValueError("Content ROI status/pixel count is missing or conflicts with native geometry")
    fraction = roi.get("excluded_fraction")
    if type(fraction) not in (int, float) or not math.isfinite(fraction) or abs(fraction - (1 - included / (width * height))) > 1e-12:
        raise ValueError("Content ROI excluded fraction conflicts with its fixed marker mask")
    windows = (minimum_filter(mask.astype(np.uint8), size=11, mode="constant", cval=0)[5:-5, 5:-5] != 0
               if width >= 11 and height >= 11 else np.zeros((0, 0), dtype=bool))
    return mask, windows


def _frames(record, width, height, fps):
    rows = read_csv(record, FRAME_FIELDS)
    if not rows:
        raise ValueError("Frame sidecar must describe a nonempty whole native reference")
    ids, variants, segment_patterns = set(), set(), {}
    for index, row in enumerate(rows):
        for name in ("file_frame", "source_frame", "repeat_index", "pts_numerator", "pts_denominator"):
            row[name] = csv_integer(row[name], name, 1 if name == "pts_denominator" else 0)
        pts = Fraction(row["pts_numerator"], row["pts_denominator"])
        if row["file_frame"] != index or pts != Fraction(index, fps) or (pts.numerator, pts.denominator) != (row["pts_numerator"], row["pts_denominator"]):
            raise ValueError("Frame sidecar must preserve sequential file indices and reduced native PTS")
        match = re.fullmatch(r"([0-9a-f]{64}):" + re.escape(f"{width}x{height}@{fps}:{index}"), row["frame_id"])
        if not match or row["frame_id"] in ids:
            raise ValueError("Frame ID conflicts with the native variant/profile or is duplicated")
        if not re.fullmatch(r"[0-9a-f]{64}", row["source_frame_id"]) or not row["segment_id"] or not row["pattern_id"]:
            raise ValueError("Frame sidecar must declare exact source identity, segment and pattern")
        if row["segment_id"] in segment_patterns and segment_patterns[row["segment_id"]] != row["pattern_id"]:
            raise ValueError("A source segment cannot silently change pattern identity")
        segment_patterns[row["segment_id"]] = row["pattern_id"]
        if any(not re.fullmatch(r"[0-9a-fA-F]{64}", row[name]) for name in FRAME_FIELDS if name.endswith("sha256")):
            raise ValueError("Every frame needs exact native pixel SHA256 declarations")
        ids.add(row["frame_id"])
        variants.add(match[1])
    if len(variants) != 1:
        raise ValueError("One native reference cannot mix variant identities")
    return rows, variants.pop()


def _gaussian(image):
    return convolve1d(convolve1d(image, _KERNEL, axis=1, mode="constant"),
                      _KERNEL, axis=0, mode="constant")[5:-5, 5:-5]


def _pair_reference(reference, delivered, mask, windows):
    """Original allocation-per-operation formulation; kept as the bit-exact oracle for _pair."""
    a, b = reference.astype(np.float64), delivered.astype(np.float64)
    error = (a - b)[mask]
    mse = float(np.mean(error * error))
    if not windows.any():
        return mse, None
    ma, mb = _gaussian(a), _gaussian(b)
    va = np.maximum(0, _gaussian(a * a) - ma * ma)
    vb = np.maximum(0, _gaussian(b * b) - mb * mb)
    covariance = _gaussian(a * b) - ma * mb
    c1, c2 = (.01 * 255) ** 2, (.03 * 255) ** 2
    ssim = ((2 * ma * mb + c1) * (2 * covariance + c2) /
            ((ma * ma + mb * mb + c1) * (va + vb + c2)))
    return mse, float(np.mean(ssim[windows]))


_SCRATCH = threading.local()
_SCRATCH_NAMES = ("a", "b", "p", "t1", "t2", "t3", "ma", "mb", "va", "vb", "cv")


def _buffers(shape):
    """Per-thread float64 work arrays: the same arithmetic without a page-faulting allocation per operation."""
    cache = getattr(_SCRATCH, "cache", None)
    if cache is None or len(cache) > 6:
        cache = _SCRATCH.cache = {}
    buffers = cache.get(shape)
    if buffers is None:
        buffers = cache[shape] = {name: np.empty(shape, np.float64) for name in _SCRATCH_NAMES}
    return buffers


def _smooth(image, scratch, output):
    convolve1d(image, _KERNEL, axis=1, output=scratch, mode="constant")
    convolve1d(scratch, _KERNEL, axis=0, output=output, mode="constant")
    return output[5:-5, 5:-5]


def _ssim_map(buffers):
    """Local SSIM map of buffers a/b into buffers["t3"][:H-10, :W-10] (same operations/order as _pair_reference)."""
    a, b, p, t1, t2, t3 = (buffers[name] for name in ("a", "b", "p", "t1", "t2", "t3"))
    ma, mb = _smooth(a, t1, buffers["ma"]), _smooth(b, t1, buffers["mb"])
    np.multiply(a, a, out=p)
    va = _smooth(p, t1, buffers["va"])
    np.multiply(b, b, out=p)
    vb = _smooth(p, t1, buffers["vb"])
    np.multiply(a, b, out=p)
    covariance = _smooth(p, t1, buffers["cv"])
    rows, columns = ma.shape
    m2, m3 = t2[:rows, :columns], t3[:rows, :columns]
    np.multiply(ma, ma, out=m2)
    np.subtract(va, m2, out=va)
    np.maximum(0, va, out=va)
    np.multiply(mb, mb, out=m2)
    np.subtract(vb, m2, out=vb)
    np.maximum(0, vb, out=vb)
    np.multiply(ma, mb, out=m3)
    np.subtract(covariance, m3, out=covariance)
    c1, c2 = (.01 * 255) ** 2, (.03 * 255) ** 2
    np.multiply(2, ma, out=m3)
    np.multiply(m3, mb, out=m3)
    np.add(m3, c1, out=m3)
    np.multiply(2, covariance, out=covariance)
    np.add(covariance, c2, out=covariance)
    np.multiply(m3, covariance, out=m3)  # numerator
    np.multiply(ma, ma, out=m2)
    np.multiply(mb, mb, out=ma)
    np.add(m2, ma, out=m2)
    np.add(m2, c1, out=m2)
    np.add(va, vb, out=va)
    np.add(va, c2, out=va)
    np.multiply(m2, va, out=m2)  # denominator
    np.divide(m3, m2, out=m3)
    return m3


def _load(buffers, reference, delivered):
    a, b, p = buffers["a"], buffers["b"], buffers["p"]
    a[...] = reference
    b[...] = delivered
    np.subtract(a, b, out=p)
    return p


def _masked_mse(difference, mask):
    error = difference[mask]
    return float(np.mean(error * error))


def _pair(reference, delivered, mask, windows):
    """(masked MSE, mean SSIM); every floating-point operation and its order equal _pair_reference."""
    buffers = _buffers(reference.shape)
    mse = _masked_mse(_load(buffers, reference, delivered), mask)
    if not windows.any():
        return mse, None
    return mse, float(np.mean(_ssim_map(buffers)[windows]))


def _score_slot_reference(a, b, mask, windows, panels):
    """Original per-panel formulation (each panel smoothed on its own); oracle for _score_slot."""
    full = _pair_reference(a, b, mask, windows)
    parts = []
    for panel in panels:
        x, y, pw, ph = panel["declared"]["bounds_xywh"]
        parts.append(_pair_reference(a[y:y + ph, x:x + pw], b[y:y + ph, x:x + pw], panel["mask"], panel["windows"]))
    return full, parts


def _score_slot(a, b, mask, windows, panels):
    """Full-frame and per-panel (mse, ssim) for one slot; pure, so it may run on any thread.

    A panel's 11x11 windows lie wholly inside the panel, and every Gaussian output depends only on its own
    window (fixed tap order), so the full-frame SSIM map already holds each panel window's exact value; the
    panels read it instead of re-smoothing every panel. A frame with no valid window falls back to _pair.
    """
    buffers = _buffers(a.shape)
    difference = _load(buffers, a, b)
    full_mse = _masked_mse(difference, mask)
    parts = []
    for panel in panels:  # Before the SSIM passes reuse the difference buffer
        x, y, pw, ph = panel["declared"]["bounds_xywh"]
        parts.append([_masked_mse(difference[y:y + ph, x:x + pw], panel["mask"]), None])
    if not windows.any():
        for panel, part in zip(panels, parts):
            x, y, pw, ph = panel["declared"]["bounds_xywh"]
            part[1] = _pair(a[y:y + ph, x:x + pw], b[y:y + ph, x:x + pw], panel["mask"], panel["windows"])[1]
        return (full_mse, None), [tuple(part) for part in parts]
    ssim = _ssim_map(buffers)
    for panel, part in zip(panels, parts):
        x, y, pw, ph = panel["declared"]["bounds_xywh"]
        panel_windows = panel["windows"]
        if panel_windows.any():
            part[1] = float(np.mean(ssim[y:y + ph - 10, x:x + pw - 10][panel_windows]))
    return (full_mse, float(np.mean(ssim[windows]))), [tuple(part) for part in parts]


def _psnr(mse):
    return "inf" if mse == 0 else 10 * math.log10(255 * 255 / mse)


def _distribution(values):
    measured = [value for value in values if value is not None]
    ordered = sorted(math.inf if value == "inf" else value for value in measured)
    result = {"count": len(measured), "unmeasured_count": len(values) - len(measured),
              "perfect_count": sum(value == "inf" for value in measured)}
    for name, percentile in (("min", 0), ("p05", .05), ("p50", .5), ("p95", .95), ("max", 1)):
        value = ordered[max(0, math.ceil(percentile * len(ordered)) - 1)] if ordered else None
        result[name] = "inf" if value == math.inf else value
    return result


def _summary(rows):
    mse = float(np.mean([row["luma_mse"] for row in rows])) if rows else None
    ssim = [row["luma_ssim"] for row in rows if row["luma_ssim"] is not None]
    return {"measurement_status": "MEASURED" if rows else "UNMEASURED", "scored_frames": len(rows),
            "pooled_luma_mse": mse, "pooled_luma_psnr_db": _psnr(mse) if mse is not None else None,
            "mean_luma_ssim": float(np.mean(ssim)) if ssim else None,
            "luma_psnr_db_distribution": _distribution([row["luma_psnr_db"] for row in rows]),
            "luma_ssim_distribution": _distribution([row["luma_ssim"] for row in rows]),
            "luma_mse_distribution": _distribution([row["luma_mse"] for row in rows]),
            "reason": None if rows else "Source group has no frames in the caller's provided planned timeline"}


def _panels(document, frames, layout, width, height, fps):
    if document.get("schema") != "vsg-panels-v1" or document.get("geometry") != {"width": width, "height": height, "fps": fps} or document.get("content_roi") != layout["content_roi"]:
        raise ValueError("Panels must match the exact native profile and predeclared content mask")
    segments, result = document.get("segments"), {}
    if not isinstance(segments, list) or not segments:
        raise ValueError("Panels need explicit native mosaic segments")
    for segment in segments:
        identity = segment.get("segment_id")
        members = [row for row in frames if row["segment_id"] == identity]
        if not members or identity in result or any(row["pattern_id"] != "mosaic" for row in members):
            raise ValueError("Each panel segment must identify one exact mosaic source segment")
        start, end = members[0]["file_frame"], members[-1]["file_frame"] + 1
        count, repeat = integer(segment.get("source_frame_count"), "source_frame_count", 1), integer(segment.get("repeat"), "repeat", 1)
        if segment.get("file_frame_start") != start or segment.get("file_frame_end") != end or len(members) != end - start or count * repeat != end - start:
            raise ValueError("Panel segment window/repetitions differ from the complete native frame identities")
        if any(row["source_frame"] != offset % count or row["repeat_index"] != offset // count for offset, row in enumerate(members)):
            raise ValueError("Panel source-frame identity differs from its declared repetitions")
        panels, occupied, ids = segment.get("panels"), np.zeros((height, width), bool), set()
        if not isinstance(panels, list) or not panels:
            raise ValueError("A mosaic needs explicit panels")
        result[identity] = []
        for panel in panels:
            if not isinstance(panel, dict) or not isinstance(panel.get("id"), str) or not panel["id"] or panel["id"] in ids or panel.get("render_mode") != "native" or type(panel.get("seed")) is not int or not isinstance(panel.get("params"), dict) or not panel.get("pattern_id"):
                raise ValueError("Panels need unique IDs, native rendering and complete deterministic declarations")
            ids.add(panel["id"])
            rect = panel.get("bounds_xywh")
            if not isinstance(rect, list) or len(rect) != 4 or any(type(n) is not int or n % 2 for n in rect):
                raise ValueError("Panel bounds must be native even I420 rectangles")
            x, y, pw, ph = rect
            if min(x, y) < 0 or min(pw, ph) <= 0 or x + pw > width or y + ph > height or occupied[y:y + ph, x:x + pw].any():
                raise ValueError("Panel bounds overlap or exceed native geometry")
            occupied[y:y + ph, x:x + pw] = True
            excluded = []
            for mx, my, mw, mh in layout["content_roi"]["exclude_xywh"]:
                left, top, right, bottom = max(x, mx), max(y, my), min(x + pw, mx + mw), min(y + ph, my + mh)
                if right > left and bottom > top:
                    excluded.append([left, top, right - left, bottom - top])
            if sorted(panel.get("exclude_xywh", [])) != sorted(excluded):
                raise ValueError("Panel exclusions must exactly intersect the predeclared content mask")
            mask = np.ones((ph, pw), bool)
            for mx, my, mw, mh in excluded:
                mask[my - y:my - y + mh, mx - x:mx - x + mw] = False
            windows = (minimum_filter(mask.astype(np.uint8), size=11, mode="constant", cval=0)[5:-5, 5:-5] != 0
                       if pw >= 11 and ph >= 11 else np.zeros((0, 0), bool))
            result[identity].append({"declared": panel, "mask": mask, "windows": windows, "scores": []})
        if not occupied.all():
            raise ValueError("Declared native mosaic panels must cover the whole frame")
    if set(result) != {row["segment_id"] for row in frames if row["pattern_id"] == "mosaic"}:
        raise ValueError("Panels must describe every native mosaic segment")
    return result


def analyze(reference_path, delivered_path, width, height, fps, reference_indices,
            frames_record, layout_record, base, panels_record=None, threads=None):
    """Score every reconstructed native source slot; reject ambiguous input binding.

    threads (default BENCH_SCORE_THREADS or 4; 1 = serial) only parallelizes the pure per-frame metrics.
    """
    width, height, fps = (integer(value, name, 1) for value, name in ((width, "width"), (height, "height"), (fps, "fps")))
    if width % 2 or height % 2:
        raise ValueError("Native I420 requires positive even width and height")
    if not isinstance(reference_indices, list) or not reference_indices:
        raise ValueError("Every planned timeline slot needs an explicit source file-frame index")
    source, delivered = _path(reference_path, base), _path(delivered_path, base)
    records = {"frames": file_record(frames_record, base), "marker_layout": file_record(layout_record, base)}
    frames, variant = _frames(records["frames"], width, height, fps)
    for index in reference_indices:
        if integer(index, "reference index") >= len(frames):
            raise ValueError("Source mapping points outside the hash-bound native reference")
    frame_size, pixels = width * height * 3 // 2, width * height
    if source.stat().st_size != len(frames) * frame_size:
        raise ValueError("Reference byte count must exactly match the full native frame sidecar")
    if delivered.stat().st_size != len(reference_indices) * frame_size:
        raise ValueError("Delivered byte count must exactly cover every provided planned slot; no prefix/tail selection")
    layout = json.loads(Path(records["marker_layout"]["path"]).read_text(encoding="utf-8-sig"))
    mask, windows = _content_mask(layout, width, height, {row["segment_id"] for row in frames})
    panels = {}
    if panels_record is not None:
        records["panels"] = file_record(panels_record, base)
        panels = _panels(json.loads(Path(records["panels"]["path"]).read_text(encoding="utf-8-sig")), frames, layout, width, height, fps)
    # The full-reference check (every frame SHA256 + marker decode) is identical for both call
    # directions and every case that shares one native fixture, so a process verifies it once per
    # exact file identity (path, size, mtime) and sidecar bytes. Per-slot reads below still re-hash.
    stat = source.stat()
    verified_key = (str(source), stat.st_size, stat.st_mtime_ns, records["frames"]["sha256"],
                    records["marker_layout"]["sha256"], width, height, fps)
    source_digest = _VERIFIED.get(verified_key)
    if source_digest is None:
        source_hash = hashlib.sha256()
        with source.open("rb") as stream:
            for row in frames:
                raw = stream.read(frame_size)
                if len(raw) != frame_size or hashlib.sha256(raw).hexdigest() != row["i420_sha256"].lower():
                    raise ValueError("Original reference bytes disagree with a frame pixel SHA256")
                source_hash.update(raw)
                if layout["status"] == "AVAILABLE":
                    marker = decode_marker(np.frombuffer(raw, np.uint8, pixels).reshape(height, width), layout)
                    if marker.get("status") != "DECODED" or any(marker[name] != row[name] for name in ("file_frame", "pts_numerator", "pts_denominator")):
                        raise ValueError("Native reference marker disagrees with exact frame identity/PTS")
        source_digest = _VERIFIED[verified_key] = source_hash.hexdigest()
    scores, delivered_hash = [], hashlib.sha256()
    workers = score_threads(threads)
    cache, pending = {}, deque()
    pool = futures.ThreadPoolExecutor(max_workers=workers) if workers > 1 else None

    def slot_panels(row):
        return [panel for panel in panels.get(row["segment_id"], []) if panel["mask"].any()]

    def finish(item):
        timeline_frame, file_frame, row, slot = item
        (mse, ssim), parts = slot.result() if pool else slot
        pts = Fraction(timeline_frame, fps)
        scores.append({"timeline_frame": timeline_frame, "reference_file_frame": file_frame,
                       **{name: row[name] for name in ("frame_id", "source_frame_id", "source_frame", "segment_id", "pattern_id", "repeat_index")},
                       "reference_pts": {"numerator": row["pts_numerator"], "denominator": row["pts_denominator"]},
                       "timeline_pts": {"numerator": pts.numerator, "denominator": pts.denominator},
                       "luma_mse": mse, "luma_psnr_db": _psnr(mse), "luma_ssim": ssim})
        for panel, (pmse, pssim) in zip(slot_panels(row), parts):
            panel["scores"].append({"timeline_frame": timeline_frame, "reference_file_frame": file_frame,
                "frame_id": row["frame_id"], "luma_mse": pmse, "luma_psnr_db": _psnr(pmse), "luma_ssim": pssim})

    try:
        with source.open("rb") as reference, delivered.open("rb") as observed:
            for timeline_frame, file_frame in enumerate(reference_indices):
                row = frames[file_frame]
                reference.seek(file_frame * frame_size)
                raw, reconstructed = reference.read(frame_size), observed.read(frame_size)
                reference_digest = hashlib.sha256(raw).digest()
                if len(raw) != frame_size or reference_digest.hex() != row["i420_sha256"].lower() or len(reconstructed) != frame_size:
                    raise ValueError("Native inputs changed or were truncated during scoring")
                delivered_hash.update(reconstructed)
                # Identical reference pixels + identical delivered pixels (a held frame over a repeated
                # source frame) give an identical pure result for the same panel layout: score it once.
                key = (reference_digest, hashlib.sha256(reconstructed).digest(), row["segment_id"])
                slot = cache.get(key)
                if slot is None:
                    a = np.frombuffer(raw, np.uint8, pixels).reshape(height, width)
                    b = np.frombuffer(reconstructed, np.uint8, pixels).reshape(height, width)
                    active = slot_panels(row)
                    slot = cache[key] = (pool.submit(_score_slot, a, b, mask, windows, active) if pool
                                         else _score_slot(a, b, mask, windows, active))
                pending.append((timeline_frame, file_frame, row, slot))
                while len(pending) > 4 * workers:  # Bounded window keeps only a few frames in flight
                    finish(pending.popleft())
            if observed.read(1):
                raise ValueError("Delivered native input gained an unplanned tail during scoring")
        while pending:
            finish(pending.popleft())
    finally:
        if pool:
            pool.shutdown(wait=True, cancel_futures=True)
    profile = {"width": width, "height": height, "fps": fps, "format": "I420", "bit_depth": 8}
    profile_hash = _fingerprint(profile)
    groups = {}
    for family, key in (("patterns", "pattern_id"), ("segments", "segment_id")):
        original, reconstructed = defaultdict(list), defaultdict(list)
        for row in frames:
            original[row[key]].append(row)
        for row in scores:
            reconstructed[row[key]].append(row)
        groups[family] = [{key: identity, "pattern_ids": sorted({row["pattern_id"] for row in members}),
                          "profile_sha256": profile_hash, "reference_members_sha256": _fingerprint(members),
                          "source_frames": len(members), "unique_source_frames_scored": len({row["reference_file_frame"] for row in reconstructed[identity]}),
                          **_summary(reconstructed[identity])} for identity, members in original.items()]
    summary = _summary(scores)
    summary.update(reference_frames=len(frames), unique_reference_frames_scored=len(set(reference_indices)),
                   reference_frames_not_in_provided_plan=sorted(set(range(len(frames))) - set(reference_indices)))
    return {"schema": SCHEMA, "measurement_status": "MEASURED", "acceptance_status": "UNMEASURED",
            "scope": {"stage": "reference_decoder_reconstructed_native_timeline", "full_provided_timeline": True,
                      "planned_slots_scored": len(reference_indices), "native_profile": profile, "profile_sha256": profile_hash,
                      "variant_id": variant, "roi_acceptance": "UNMEASURED", "encoder_decoder_colour_application": "UNMEASURED"},
            "evidence": {**records, "reference": {"path": str(source), "sha256": source_digest, "bytes": source.stat().st_size},
                         "delivered": {"path": str(delivered), "sha256": delivered_hash.hexdigest(), "bytes": delivered.stat().st_size},
                         "reference_indices_sha256": _fingerprint(reference_indices),
                         "mask_sha256": hashlib.sha256(mask.tobytes()).hexdigest()},
            "algorithm": ALGORITHM, "content_roi": layout["content_roi"], "ssim_windows": int(windows.sum()),
            "summary": summary, **groups, "frames": scores,
            "panels": [{"segment_id": identity, **panel["declared"], "included_pixels": int(panel["mask"].sum()),
                        "ssim_windows": int(panel["windows"].sum()), "acceptance_status": "UNMEASURED",
                        **_summary(panel["scores"]), "frames": panel["scores"]}
                       for identity, members in panels.items() for panel in members],
            "measurements": [{"name": "content_luma_psnr_db", "family": "picture", "status": "MEASURED",
                              "value": summary["pooled_luma_psnr_db"], "reason": "Supplemental fixed-ROI native luma code-value diagnostic"},
                             {"name": "content_luma_ssim", "family": "picture", "status": "MEASURED" if summary["mean_luma_ssim"] is not None else "UNMEASURED",
                              "value": summary["mean_luma_ssim"], "reason": "Supplemental Gaussian-local luma diagnostic" if windows.any() else "No 11x11 native windows lie fully inside the declared content mask"}],
            "limitations": ["ROI acceptance remains UNMEASURED: thresholds and perceptual suitability have not been validated.",
                            "Supplemental luma diagnostics do not replace full-frame PSNR, SSIM or VMAF; masked VMAF is not claimed.",
                            "The caller owns planned lifecycle completeness and source-slot mapping; no missing planned prefix/tail can be inferred from two raw files.",
                            "Reference-index resets do not identify loop iterations or stream generations; retain the caller's phase metadata.",
                            "Source SHA identities are retained; generator seed/parameter/provenance semantics require their separate hash-bound records.",
                            "Native Y code values do not establish chroma fidelity, encoder colour application, physical screen detail, pacing, acoustic quality or latency."]}
