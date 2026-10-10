"""Prepare explicit broadcast/chain/call I420 profiles from licensed Xiph/UVG clips."""

import argparse
import csv
import hashlib
import json
import re
import subprocess
from fractions import Fraction
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from debug_image_lifecycle import DebugImages
from benchmark_layout import original, output_path, resolve
from score_input_complexity import frame_complexity


CLIPS = {
    "park_joy": (1280, 720, 50, 500, "SVT"),
    "ducks_take_off": (1280, 720, 50, 500, "SVT"),
    "crowd_run": (1920, 1080, 50, 500, "SVT"),
    "ShakeNDry": (1920, 1080, 120, 300, "UVG CC BY-NC 3.0"),
    "ReadySetGo": (1920, 1080, 120, 600, "UVG CC BY-NC 3.0"),
}


def sha256(path):
    with resolve(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fixture_input(document, value):
    path = Path(value)
    base = (Path(__file__).resolve().parents[1] if path.parts and path.parts[0] in {"benchmarks", "artifacts"}
            else original(document).parent)
    return resolve(path, base)


def summary(values):
    data = np.asarray([value for value in values if value is not None], dtype=np.float64)
    if not len(data):
        return {"n": 0}
    return {"n": len(data), "min": float(data.min()), "mean": float(data.mean()),
            "median": float(np.median(data)), "p95": float(np.percentile(data, 95)), "max": float(data.max())}


def measure_luma(path, width, height, fps, destination, known_cuts=()):
    """Fixed classic Sobel/frame-difference protocol, without range/EOTF guesses."""
    path = resolve(path)
    frame_bytes = width * height * 3 // 2
    size = Path(path).stat().st_size
    if not size or size % frame_bytes:
        raise ValueError("Native file must contain a positive whole I420 frame count")
    count = size // frame_bytes
    frames = np.memmap(path, dtype=np.uint8, mode="r", shape=(count, frame_bytes))
    rows, previous = [], None
    for index, frame in enumerate(frames):
        luma = frame[:width * height].reshape(height, width).astype(np.float64)
        si, ti = frame_complexity(luma, previous)
        rows.append({"frame": index, "time_seconds": index / fps, "si": si, "ti": ti,
                     "known_cut": index in known_cuts, "initial_two_seconds": index < 2 * fps})
        previous = luma
    with Path(destination).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return {"method": "Unscaled 3x3 Sobel magnitude std; successive raw 8-bit luma difference std; 1-pixel border excluded; population std. No range normalization/EOTF. Classic P.910 shape, not current-version certification.",
            "frames": count, "si_all": summary([row["si"] for row in rows]),
            "ti_all_except_first": summary([row["ti"] for row in rows]),
            "ti_known_cuts_excluded": summary([row["ti"] for row in rows if not row["known_cut"]]),
            "initial_two_seconds": {key: summary([row[key] for row in rows if row["initial_two_seconds"]]) for key in ["si", "ti"]},
            "after_initial_two_seconds_cuts_excluded": {key: summary([row[key] for row in rows if not row["initial_two_seconds"] and not row["known_cut"]]) for key in ["si", "ti"]},
            "known_cut_frames": list(known_cuts), "unannotated_intrinsic_cuts": "retained; no high-motion frame automatically removed"}


def measure_bursts(fixture, destination, bitrate_kbps=1100):
    """Host libvpx comparison; not an Android encoder or transport measurement."""
    destination = output_path(destination, "runs")
    destination.mkdir(parents=True, exist_ok=False)
    fixture = resolve(fixture)
    record = json.loads(fixture.read_text(encoding="utf-8"))
    width, height, fps = record["width"], record["height"], record["fps"]
    results = []
    for mode in ["cbr", "vbr"]:
        encoded = destination / f"{mode}-{bitrate_kbps}k.ivf"
        rate_options = [] if mode == "vbr" else ["-minrate", f"{bitrate_kbps}k", "-maxrate", f"{bitrate_kbps}k", "-bufsize", f"{bitrate_kbps}k"]
        command = ["ffmpeg", "-hide_banner", "-n", "-f", "rawvideo", "-pixel_format", "yuv420p",
                   "-video_size", f"{width}x{height}", "-framerate", str(fps), "-i", str(fixture_input(fixture, record["unmarked_path"])),
                   "-an", "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "4", "-lag-in-frames", "0",
                   "-row-mt", "1", "-threads", "2", "-g", str(2 * fps), "-b:v", f"{bitrate_kbps}k", *rate_options, "-f", "ivf", str(encoded)]
        result = subprocess.run(command, capture_output=True, check=True)
        (destination / f"{mode}.stderr.txt").write_bytes(result.stderr)
        probe_command = ["ffprobe", "-v", "error", "-show_packets", "-show_entries", "packet=pts_time,duration_time,size,flags", "-of", "json", str(encoded)]
        packets = json.loads(subprocess.run(probe_command, capture_output=True, check=True).stdout)["packets"]
        if len(packets) != record["frames"]:
            raise ValueError("Host lab encoder changed the planned frame count")
        sizes = np.asarray([int(packet["size"]) for packet in packets], dtype=np.float64)
        rows = [{"frame": index, "pts_seconds": packet["pts_time"], "bytes": int(packet["size"]),
                 "keyframe": "K" in packet["flags"], "complete_rolling_1s_kbps": None if index < fps - 1 else float(sizes[index - fps + 1:index + 1].sum() * 8 / 1000)}
                for index, packet in enumerate(packets)]
        with (destination / f"{mode}-frames.csv").open("w", newline="", encoding="utf-8") as target:
            writer = csv.DictWriter(target, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        rolling = [row["complete_rolling_1s_kbps"] for row in rows]
        results.append({"status": "MEASURED_HOST_LAB_NOT_PHONE_EVIDENCE", "mode": mode,
                        "target_kbps": bitrate_kbps, "actual_mean_kbps": float(sizes.sum() * 8 / record["duration_seconds"] / 1000),
                        "frame_bytes": summary(sizes), "inter_frame_bytes": summary([row["bytes"] for row in rows if not row["keyframe"]]),
                        "complete_rolling_1s_kbps": summary(rolling), "command": command, "probe_command": probe_command,
                        "encoded_path": str(encoded), "encoded_sha256": sha256(encoded),
                        "reference_sha256": record["unmarked_sha256"], "width": width, "height": height, "fps": fps,
                        "frames": len(packets), "known_cuts": record.get("known_cut_frames", []),
                        "scope": "One traversal; no repeated-loop cut, forced padding, audio or Bluetooth. GOP2s and host realtime libvpx are declared lab settings, not Samsung/Android preservation evidence."})
    (destination / "burst-summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    return results


def prepare_variable_montage(root, destination=None):
    """One fixed natural easy/hard sequence, retaining each clip's exact frame map."""
    root = Path(root)
    destination = output_path(destination if destination is not None else root / "natural_variable", "inputs")
    destination.mkdir(parents=True, exist_ok=False)
    results = []
    for width, height, fps in [(720, 480, 30), (640, 360, 15), (320, 240, 15)]:
        profile = f"{width}x{height}-{fps}fps"
        folder = destination / profile
        folder.mkdir()
        documents = [resolve(root / name / profile / "fixture.json")
                     for name in ["ShakeNDry", "ReadySetGo", "park_joy"]]
        clips = [json.loads(document.read_text(encoding="utf-8")) for document in documents]
        count = sum(clip["frames"] for clip in clips)
        paths = {kind: folder / f"natural-variable-{width}x{height}-{fps}fps-{count}-{kind}.yuv" for kind in ["unmarked", "marked"]}
        mapping, cuts, offset = [], [], 0
        for document, clip in zip(documents, clips):
            if offset:
                cuts.append(offset)
            source_mapping = resolve(original(fixture_input(document, clip["unmarked_path"])).with_name("frame-mapping.csv"))
            with source_mapping.open(encoding="utf-8") as stream:
                for row in csv.DictReader(stream):
                    row["clip_frame"] = row.pop("output_frame")
                    row["output_frame"] = offset + int(row["clip_frame"])
                    row["output_time_num"], row["output_time_den"] = row["output_frame"], fps
                    row["clip"] = clip["clip"]
                    row["known_cut"] = row["output_frame"] in cuts
                    mapping.append(row)
            offset += clip["frames"]
        for kind, target in paths.items():
            with target.open("wb") as stream:
                for document, clip in zip(documents, clips):
                    with fixture_input(document, clip[f"{kind}_path"]).open("rb") as source:
                        while chunk := source.read(1024 * 1024):
                            stream.write(chunk)
        with (folder / "frame-mapping.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(mapping[0]))
            writer.writeheader()
            writer.writerows(mapping)
        metrics = measure_luma(paths["unmarked"], width, height, fps, folder / "si-ti-frames.csv", cuts)
        result = {"status": "GENERATED_REFERENCE_NOT_PHONE_PASS", "clip": "natural_variable", "width": width, "height": height,
                  "fps": fps, "frames": count, "duration_seconds": count / fps, "bytes": count * width * height * 3 // 2,
                  "pixel_format": "I420/yuv420p", "audio": "none", "known_cut_frames": cuts, "loop_boundary_after_frame": count - 1,
                  "sequence": [{key: clip[key] for key in ["clip", "frames", "source_sha256", "unmarked_sha256", "marked_sha256", "motion_cadence_note", "crop", "scale", "fractional_tail_trim_seconds"]} for clip in clips],
                  "cadence_caution": "ShakeNDry and ReadySetGo retain integer-stride120FPS motion; ParkJoy retains uneven50-to-30/15 content selection. Cuts and full montage loop are intentional content changes.",
                  "color_processing": "Byte concatenation of the derived planar YUV profiles; no new matrix/range/EOTF assumption, stretching, interpolation or retiming.",
                  "marker_processing": "Existing clip names/local frame IDs and markers unchanged; local counters restart at each declared clip cut.",
                  "unmarked_path": str(paths["unmarked"]), "unmarked_sha256": sha256(paths["unmarked"]),
                  "marked_path": str(paths["marked"]), "marked_sha256": sha256(paths["marked"]), "si_ti": metrics,
                  "phone_validation": "UNMEASURED", "optical_lag": "UNMEASURED", "lifecycle": "UNMEASURED"}
        (folder / "fixture.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        results.append(result)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clip", choices=list(CLIPS), required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--profile", action="append", choices=["720x480-30", "640x360-15", "320x240-15"], help="Prepare only this named profile; repeat for several")
    parser.add_argument("--contact-sheet", action="store_true", help="Create a temporary preview for review, then consume it with debug_image_lifecycle.py")
    args = parser.parse_args()
    source_width, source_height, native_fps, native_count, license_name = CLIPS[args.clip]
    output = output_path(args.output_directory, "inputs")
    args.source = resolve(args.source)
    output.mkdir(parents=True, exist_ok=False)
    commands = []

    def run(command, name, folder=output):
        command = [str(value) for value in command]
        commands.append({"name": name, "argv": command})
        result = subprocess.run(command, capture_output=True, check=True)
        (folder / f"{name}.stderr.txt").write_bytes(result.stderr)
        return result.stdout

    raw_source = args.source.suffix.lower() == ".yuv"
    input_options = []
    if raw_source:
        input_options = ["-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", f"{source_width}x{source_height}", "-framerate", native_fps]
        if args.source.stat().st_size != native_count * source_width * source_height * 3 // 2:
            raise ValueError("UVG extracted file has an unexpected exact frame count")
    source_probe = json.loads(run(["ffprobe", "-v", "error", *input_options, "-show_streams", "-show_format", "-of", "json", args.source], "source-probe"))
    (output / "source-ffprobe.json").write_text(json.dumps(source_probe, indent=2), encoding="utf-8")
    stream = source_probe["streams"][0]
    if (stream["width"], stream["height"], Fraction(stream["r_frame_rate"])) != (source_width, source_height, native_fps):
        raise ValueError("Source geometry or frame rate differs from the declared clip")
    header = None
    if not raw_source:
        with args.source.open("rb") as source:
            header = source.readline().decode("ascii").strip()
    source_hash = sha256(args.source)
    for width, height, fps in [(720, 480, 30), (640, 360, 15), (320, 240, 15)]:
        if args.profile and f"{width}x{height}-{fps}" not in args.profile:
            continue
        folder = output / f"{width}x{height}-{fps}fps"
        folder.mkdir()
        count = native_count * fps // native_fps
        frame_bytes = width * height * 3 // 2
        crop_width = source_height * width // height
        crop_x = (source_width - crop_width) // 2
        crop_filter = f"crop={crop_width}:{source_height}:{crop_x}:0," if crop_width != source_width else ""
        native = folder / "native-scaled-unmarked.yuv"
        run(["ffmpeg", "-hide_banner", "-n", "-threads", "4", *input_options, "-i", args.source, "-an",
             "-frames:v", native_count, "-vf", crop_filter + f"scale={width}:{height}:flags=lanczos,setsar=1,format=yuv420p,trim=end_frame={native_count},showinfo",
             "-fps_mode", "passthrough", "-f", "rawvideo", native], "native-decode", folder)
        log = (folder / "native-decode.stderr.txt").read_text(encoding="utf-8")
        stamps = [(int(n), int(pts)) for n, pts in re.findall(r"n:\s*(\d+)\s+pts:\s*(-?\d+)\s+pts_time:", log)]
        time_base_match = re.search(r"config in time_base:\s*(\d+)/(\d+)", log)
        time_base = Fraction(int(time_base_match[1]), int(time_base_match[2]))
        if len(stamps) != native_count or native.stat().st_size != native_count * frame_bytes:
            raise ValueError("Native decode has unexpected frames or bytes")
        if any((b[1] - a[1]) * time_base != Fraction(1, native_fps) for a, b in zip(stamps, stamps[1:])):
            raise ValueError("Native timestamps are not uniform")
        source_frames = np.memmap(native, dtype=np.uint8, mode="r", shape=(native_count, frame_bytes))
        unmarked = folder / f"{args.clip}-{width}x{height}-{fps}fps-{count}-unmarked.yuv"
        mapping = []
        with unmarked.open("wb") as target:
            for index in range(count):
                source_index = (2 * index * native_fps + fps) // (2 * fps)
                frame = source_frames[source_index]
                target.write(frame.tobytes())
                mapping.append({"output_frame": index, "output_time_num": index, "output_time_den": fps,
                                "source_frame": source_index, "source_pts": stamps[source_index][1], "source_time_base": str(time_base),
                                "source_time_seconds": float(stamps[source_index][1] * time_base),
                                "sampling_error_ms": 1000 * (source_index / native_fps - index / fps),
                                "unmarked_frame_sha256": hashlib.sha256(frame).hexdigest()})
        del source_frames
        with (folder / "frame-mapping.csv").open("w", newline="", encoding="utf-8") as target:
            writer = csv.DictWriter(target, fieldnames=list(mapping[0]))
            writer.writeheader()
            writer.writerows(mapping)
        metrics = measure_luma(unmarked, width, height, fps, folder / "si-ti-frames.csv")
        (folder / "si-ti-summary.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
        frames = np.memmap(unmarked, dtype=np.uint8, mode="r", shape=(count, frame_bytes))
        marked = folder / f"{args.clip}-{width}x{height}-{fps}fps-{count}-marked.yuv"
        font = ImageFont.load_default(size=14)
        with marked.open("wb") as target:
            for index, frame in enumerate(frames):
                data = frame.copy()
                image = Image.fromarray(data[:width * height].reshape(height, width))
                draw = ImageDraw.Draw(image)
                draw.rectangle((8, 8, 219, 29), fill=16)
                draw.text((12, 10), f"{args.clip} F{index:03d} {fps}FPS", font=font, fill=235)
                x = (index * 4) % width
                draw.rectangle((x, height - 8, x + 3, height - 1), fill=235)
                data[:width * height] = np.asarray(image).ravel()
                for start in [width * height, width * height * 5 // 4]:
                    plane = data[start:start + width * height // 4].reshape(height // 2, width // 2)
                    plane[4:15, 4:110] = 128
                    plane[(height - 8) // 2:, x // 2:(x + 4) // 2] = 128
                target.write(data.tobytes())
        duplicate_count = sum(np.array_equal(a, b) for a, b in zip(frames, frames[1:]))
        if args.contact_sheet:
            selected = [max(0, count // 10), count // 2, min(count - 1, count * 9 // 10)]
            thumb_height = round(320 * height / width)
            pixels = run(["ffmpeg", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-video_size", f"{width}x{height}",
                          "-framerate", fps, "-i", marked, "-vf", "select='" + "+".join(f"eq(n,{n})" for n in selected) + f"',scale=320:{thumb_height}",
                          "-fps_mode", "passthrough", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"], "contact-frames", folder)
            sheet = Image.new("RGB", (960, thumb_height + 28), "#161616")
            draw = ImageDraw.Draw(sheet)
            for position, index in enumerate(selected):
                step = 320 * thumb_height * 3
                sheet.paste(Image.frombytes("RGB", (320, thumb_height), pixels[position * step:(position + 1) * step]), (position * 320, 28))
                draw.text((position * 320 + 5, 6), f"{['early','middle','late'][position]} F{index} src {mapping[index]['source_time_seconds']:.3f}s", fill="white")
            debug_images = DebugImages(folder)
            sheet.save(debug_images.reserve("contact-sheet.png", "Inspect prepared native input; raw reference remains canonical"))
            debug_images.register("contact-sheet.png")
        intervals = sorted(set((b["source_frame"] - a["source_frame"]) / native_fps for a, b in zip(mapping, mapping[1:])))
        record = {"status": "GENERATED_REFERENCE_NOT_PHONE_PASS", "clip": args.clip, "license": license_name,
                  "source_path": str(args.source), "source_sha256": source_hash, "source_y4m_header": header,
                  "source_native_fps": native_fps, "source_native_frames": native_count, "source_pts_origin": "official raw cadence synthesized" if raw_source else "Y4M cadence",
                  "source_color_metadata": {key: stream.get(key, "UNSPECIFIED") for key in ["color_range", "color_space", "color_primaries", "color_transfer", "chroma_location"]},
                  "color_processing": "No explicit range, matrix, gamma or RGB transform; planar YUV scaling only. Unspecified source tags remain unspecified; contact-sheet RGB rendering uses FFmpeg defaults.",
                  "width": width, "height": height, "fps": fps, "frames": count, "duration_seconds": count / fps, "pixel_format": "I420/yuv420p", "audio": "none",
                  "crop": [crop_width, source_height, crop_x, 0], "scale": [width, height],
                  "temporal_selection": "nearest original frame, ties upward; no interpolation or duplicates inserted",
                  "content_time_intervals_seconds": intervals, "motion_cadence_caution": native_fps % fps != 0,
                  "motion_cadence_note": "50-to-30/15 selection has intrinsically uneven content motion; do not attribute it to app jitter" if native_fps % fps else "integer native-to-output stride; clean content cadence",
                  "fractional_tail_trim_seconds": native_count / native_fps - count / fps,
                  "known_cut_frames": [],
                  "loop_boundary": "Next frame after the final output frame returns to source frame0; intentional content discontinuity, not an app freeze",
                  "selected_consecutive_exact_duplicates": int(duplicate_count),
                  "unmarked_path": str(unmarked), "unmarked_sha256": sha256(unmarked), "marked_path": str(marked), "marked_sha256": sha256(marked), "bytes": count * frame_bytes,
                  "si_ti": metrics, "phone_validation": "UNMEASURED", "optical_lag": "UNMEASURED", "lifecycle": "UNMEASURED"}
        (folder / "fixture.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
        (output / "commands.json").write_text(json.dumps(commands, indent=2), encoding="utf-8")
        print(json.dumps({key: record[key] for key in ["clip","width","height","fps","frames","bytes","unmarked_path","unmarked_sha256","marked_path","marked_sha256","motion_cadence_note","si_ti"]}, indent=2), flush=True)
    (output / "profiles.json").write_text(json.dumps([json.loads(path.read_text(encoding="utf-8")) for path in sorted(output.glob("*/fixture.json"))], indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
