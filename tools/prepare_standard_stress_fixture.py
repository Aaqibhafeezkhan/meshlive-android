"""Prepare one of two fixed reference profiles from the licensed BBB 60 FPS remaster."""

import argparse
import csv
import hashlib
import json
import re
import subprocess
from fractions import Fraction
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version

from debug_image_lifecycle import DebugImages
from benchmark_layout import output_path, resolve


SCENES = [("detail-and-cuts", 320), ("grass-and-subject-motion", 360), ("fast-action", 430)]
ATTRIBUTION = (
    "(c) 2008 Blender Foundation / www.bigbuckbunny.org; "
    "Sunflower version: Janus Bager Kristensen 2013. CC BY 3.0. "
    "Modified engineering fixture: excerpts, declared crop/scale/frame selection, frame markers."
)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--profile", choices=["720x48030", "640x36015"], default="720x48030")
    parser.add_argument("--contact-sheet", action="store_true", help="Create a temporary preview for review, then consume it with debug_image_lifecycle.py")
    args = parser.parse_args()
    WIDTH, HEIGHT, FPS = (640, 360, 15) if args.profile == "640x36015" else (720, 480, 30)
    FRAME_BYTES = WIDTH * HEIGHT * 3 // 2
    scene_frames = FPS * 5
    total_frames = scene_frames * len(SCENES)
    stride = 60 // FPS
    crop = [1920, 1080] if FPS == 15 else [1620, 1080]
    crop_offset = [0, 0] if FPS == 15 else [150, 0]
    crop_filter = "" if FPS == 15 else "crop=1620:1080:150:0,"
    output = output_path(args.output_directory, "inputs")
    args.source = resolve(args.source)
    output.mkdir(parents=True, exist_ok=False)
    commands = []

    def run(command, name):
        command = [str(value) for value in command]
        commands.append({"name": name, "argv": command})
        result = subprocess.run(command, capture_output=True, check=True)
        (output / f"{name}.stderr.txt").write_bytes(result.stderr)
        return result.stdout

    probe = json.loads(run([
        "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", args.source
    ], "source-probe"))
    (output / "source-ffprobe.json").write_text(json.dumps(probe, indent=2), encoding="utf-8")
    video = next(stream for stream in probe["streams"] if stream["codec_type"] == "video")
    if (video["width"], video["height"]) != (1920, 1080):
        raise ValueError("Expected the official 1920x1080 Sunflower source")
    if any(Fraction(video[key]) != 60 for key in ["r_frame_rate", "avg_frame_rate"]):
        raise ValueError("Native 60 FPS is required; no silent frame-rate conversion")
    time_base = Fraction(video["time_base"])
    source_first_pts = int(video["start_pts"])
    mapping, source_stats = [], []
    reference = output / f"reference-unmarked-{WIDTH}x{HEIGHT}-{total_frames}.yuv"
    with reference.open("wb") as target:
        for scene_index, (name, start) in enumerate(SCENES):
            native = output / f"scene-{scene_index + 1}-native60.yuv"
            decode_name = f"scene-{scene_index + 1}-decode"
            run([
                "ffmpeg", "-hide_banner", "-n", "-threads", "4", "-copyts", "-ss", start,
                "-i", args.source, "-map", "0:v:0", "-an", "-frames:v", "300", "-vf",
                crop_filter + f"scale={WIDTH}:{HEIGHT}:flags=lanczos:in_range=tv:out_range=tv:"
                "in_color_matrix=bt709:out_color_matrix=bt709,format=yuv420p,trim=end_frame=300,showinfo",
                "-fps_mode", "passthrough", "-pix_fmt", "yuv420p", "-f", "rawvideo", native,
            ], decode_name)
            log = (output / f"{decode_name}.stderr.txt").read_text(encoding="utf-8")
            stamps = [(int(n), int(pts)) for n, pts in re.findall(
                r"n:\s*(\d+)\s+pts:\s*(-?\d+)\s+pts_time:", log)]
            if native.stat().st_size != 300 * FRAME_BYTES or len(stamps) != 300:
                raise ValueError(f"{name}: expected exactly 300 native frames")
            if [n for n, _ in stamps] != list(range(300)):
                raise ValueError(f"{name}: decoded frame sequence is incomplete")
            intervals = [Fraction(b[1] - a[1]) * time_base for a, b in zip(stamps, stamps[1:])]
            if set(intervals) != {Fraction(1, 60)}:
                raise ValueError(f"{name}: source timestamps are not uniformly 60 FPS")
            frames = np.memmap(native, dtype=np.uint8, mode="r", shape=(300, FRAME_BYTES))
            previous, duplicates, differences = None, 0, []
            for local_index, (_, pts) in enumerate(stamps):
                frame = frames[local_index]
                if previous is not None:
                    duplicates += int(np.array_equal(frame, previous))
                    differences.append(float(np.abs(
                        frame[:WIDTH * HEIGHT].astype(np.int16) - previous[:WIDTH * HEIGHT]
                    ).mean()))
                previous = frame
                if local_index % stride == 0:
                    target.write(frame.tobytes())
                    absolute_index = Fraction(pts - source_first_pts) * time_base * 60
                    if absolute_index.denominator != 1:
                        raise ValueError("Source timestamp does not identify an exact source frame")
                    output_index = len(mapping)
                    mapping.append({
                        "output_frame": output_index, "output_pts_us": (output_index * 1000000) // FPS,
                        "scene": name, "source_frame": int(absolute_index), "source_pts": pts,
                        "source_time_base": str(time_base), "source_seconds": float(pts * time_base),
                        "marker_x": (output_index * 4) % WIDTH,
                        "reference_frame_sha256": hashlib.sha256(frame).hexdigest(),
                    })
            source_stats.append({
                "scene": name, "requested_seek_seconds": start, "native_frames": 300,
                "native_interval_seconds": str(intervals[0]), "consecutive_exact_repeats": duplicates,
                "native_luma_absdiff_mean": float(np.mean(differences)),
                "native_luma_absdiff_p95": float(np.percentile(differences, 95)),
                "native_sha256": sha256(native),
            })
            del frames
    with (output / "frame-mapping.csv").open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=list(mapping[0]))
        writer.writeheader()
        writer.writerows(mapping)

    fixture = output / f"standard-stress-{WIDTH}x{HEIGHT}-{FPS}fps-{total_frames}.yuv"
    frames = np.memmap(reference, dtype=np.uint8, mode="r", shape=(total_frames, FRAME_BYTES))
    font = ImageFont.load_default(size=16)
    selected_repeats, marker_failures = 0, 0
    with fixture.open("wb") as target:
        for index, frame in enumerate(frames):
            if index and index % scene_frames:
                selected_repeats += int(np.array_equal(frame, frames[index - 1]))
            marked = frame.copy()
            luma = Image.fromarray(marked[:WIDTH * HEIGHT].reshape(HEIGHT, WIDTH))
            draw = ImageDraw.Draw(luma)
            draw.rectangle((8, 8, 163, 33), fill=16)
            draw.text((12, 11), f"F{index:03d}  S{index // scene_frames + 1}  {FPS}FPS", font=font, fill=235)
            x = mapping[index]["marker_x"]
            draw.rectangle((x, HEIGHT - 8, x + 3, HEIGHT - 1), fill=235)
            marked[:WIDTH * HEIGHT] = np.asarray(luma).ravel()
            for chroma_offset in [WIDTH * HEIGHT, WIDTH * HEIGHT * 5 // 4]:
                plane = marked[chroma_offset:chroma_offset + WIDTH * HEIGHT // 4].reshape(
                    HEIGHT // 2, WIDTH // 2)
                plane[4:17, 4:82] = 128
                plane[(HEIGHT - 8) // 2:HEIGHT // 2, x // 2:(x + 4) // 2] = 128
            actual_marker = marked[:WIDTH * HEIGHT].reshape(HEIGHT, WIDTH)[HEIGHT - 8:, x:x + 4]
            marker_failures += int(not np.all(actual_marker == 235))
            target.write(marked.tobytes())
    if fixture.stat().st_size != total_frames * FRAME_BYTES or marker_failures:
        raise ValueError("Fixture geometry, frame count, or marker validation failed")

    preview = output / "standard-stress-lossless-preview.mp4"
    run([
        "ffmpeg", "-hide_banner", "-n", "-threads", "4", "-f", "rawvideo", "-pix_fmt", "yuv420p",
        "-video_size", f"{WIDTH}x{HEIGHT}", "-framerate", FPS, "-color_range", "tv", "-colorspace", "bt709",
        "-color_trc", "bt709", "-color_primaries", "bt709", "-i", fixture, "-an", "-c:v", "libx264",
        "-threads", "4", "-crf", "0", "-preset", "medium", "-pix_fmt", "yuv420p",
        "-color_range", "tv", "-colorspace", "bt709", "-color_trc", "bt709", "-color_primaries", "bt709",
        "-metadata", "title=Modified BBB engineering stress fixture", "-metadata", f"comment={ATTRIBUTION}",
        "-movflags", "+faststart", preview,
    ], "encode-lossless-preview")
    decoded = run([
        "ffmpeg", "-v", "error", "-i", preview, "-map", "0:v:0", "-an", "-pix_fmt", "yuv420p",
        "-fps_mode", "passthrough", "-f", "rawvideo", "pipe:1",
    ], "validate-preview-pixels")
    preview_pixels_equal = len(decoded) == fixture.stat().st_size and hashlib.sha256(decoded).hexdigest() == sha256(fixture)
    if not preview_pixels_equal:
        raise ValueError("Lossless preview does not decode to the fixture bytes")
    del decoded
    preview_probe = json.loads(run([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_streams", "-show_frames",
        "-show_entries", "frame=best_effort_timestamp", "-of", "json", preview,
    ], "preview-probe"))
    (output / "preview-ffprobe.json").write_text(json.dumps(preview_probe, indent=2), encoding="utf-8")
    preview_base = Fraction(preview_probe["streams"][0]["time_base"])
    preview_pts = [int(frame["best_effort_timestamp"]) for frame in preview_probe["frames"]]
    if len(preview_pts) != total_frames or any(
        (b - a) * preview_base != Fraction(1, FPS) for a, b in zip(preview_pts, preview_pts[1:])
    ):
        raise ValueError(f"Preview timestamps do not have exact {FPS} FPS cadence")

    if args.contact_sheet:
        selected = [scene * scene_frames + offset for scene in range(3)
                    for offset in [FPS // 2, FPS * 5 // 2, FPS * 9 // 2]]
        sheet_height = round(320 * HEIGHT / WIDTH)
        rgb = run([
            "ffmpeg", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-video_size", f"{WIDTH}x{HEIGHT}",
            "-framerate", FPS, "-i", fixture, "-vf",
            "select='" + "+".join(f"eq(n,{index})" for index in selected) + f"',scale=320:{sheet_height}:in_color_matrix=bt709",
            "-fps_mode", "passthrough", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1",
        ], "contact-sheet-frames")
        sheet = Image.new("RGB", (960, 3 * (sheet_height + 28)), "#161616")
        draw = ImageDraw.Draw(sheet)
        for position, index in enumerate(selected):
            x, y = (position % 3) * 320, (position // 3) * (sheet_height + 28)
            image = Image.frombytes("RGB", (320, sheet_height), rgb[position * 320 * sheet_height * 3:(position + 1) * 320 * sheet_height * 3])
            sheet.paste(image, (x, y + 28))
            label = ["early", "middle", "late"][position % 3]
            draw.text((x + 5, y + 6), f"S{position // 3 + 1} {label} F{index:03d} | source {mapping[index]['source_seconds']:.3f}s", fill="white")
        debug_images = DebugImages(output)
        sheet.save(debug_images.reserve("contact-sheet.png", "Inspect prepared native input; raw reference remains canonical"))
        debug_images.register("contact-sheet.png")

    metadata = {
        "status": "GENERATED_FIXTURE_NOT_A_PHONE_PASS", "width": WIDTH, "height": HEIGHT,
        "fps": FPS, "frames": total_frames, "seconds": 15, "pixel_format": "I420/yuv420p", "audio": "none",
        "attribution": ATTRIBUTION, "source_path": str(args.source), "source_sha256": sha256(args.source),
        "source_kind": "already-compressed H.264 detailed animation; not pristine camera footage",
        "crop": {"source": [1920, 1080], "center_crop": crop, "offset": crop_offset, "scale": [WIDTH, HEIGHT]},
        "temporal_selection": f"each scene: native frames 0,{stride},...,{300 - stride}; no interpolation or inserted duplicates",
        "color": "BT.709 limited-range; source has no explicit matrix tag; conversion declared",
        "source_validation": source_stats, "selected_consecutive_repeats_within_scenes": selected_repeats,
        "marker": {"x": f"(output_frame * 4) % {WIDTH}", "width": 4, "height": 8, "y": HEIGHT - 8,
                   "wrap_every_frames": WIDTH // 4, "validation_failures": marker_failures},
        "preview_decodes_byte_identically": preview_pixels_equal,
        "preview_hardware_playback": "UNMEASURED; lossless H.264 profile may be unsupported by Android players",
        "fixture_bytes": fixture.stat().st_size, "fixture_sha256": sha256(fixture),
        "unmarked_reference_sha256": sha256(reference), "preview_sha256": sha256(preview),
        "ffmpeg_version": run(["ffmpeg", "-version"], "ffmpeg-version").decode().splitlines()[0],
        "pillow_version": pillow_version, "numpy_version": np.__version__,
        "scope": "Additional app encoding/delivery and callback timing can be tested against these fixed pixels; lifecycle, optics and total camera-to-screen lag remain unmeasured.",
    }
    (output / "commands.json").write_text(json.dumps(commands, indent=2), encoding="utf-8")
    (output / "fixture.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
