import argparse
import hashlib
import json
import math
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from benchmark_layout import output_path, resolve


def run(command: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=True, text=True, capture_output=True, cwd=cwd)


def score_range(
    encoded: Path,
    reference: Path,
    raw_input: list[str],
    scale: str,
    output_directory: Path,
    log_name: str,
    start_frame: int,
    end_frame: int,
) -> dict[str, float | int | str]:
    trim = f"trim=start_frame={start_frame}:end_frame={end_frame},setpts=PTS-STARTPTS,"
    color_params = "range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709"
    metric_inputs = (
        f"[0:v]{scale}{trim}setparams={color_params}[dist];"
        f"[1:v]{trim}setparams={color_params}[ref]"
    )
    selected_frames = end_frame - start_frame
    run([
        "ffmpeg", "-v", "error", "-i", str(encoded), *raw_input,
        "-lavfi", f"{metric_inputs};[dist][ref]libvmaf=log_fmt=json:log_path={log_name}",
        "-frames:v", str(selected_frames), "-f", "null", "NUL",
    ], cwd=output_directory)
    vmaf_data = json.loads((output_directory / log_name).read_text())
    if len(vmaf_data["frames"]) != selected_frames:
        raise ValueError("Metric output did not cover every requested comparison frame")
    ssim = run([
        "ffmpeg", "-hide_banner", "-i", str(encoded), *raw_input,
        "-lavfi", f"{metric_inputs};[dist][ref]ssim",
        "-frames:v", str(selected_frames), "-f", "null", "NUL",
    ]).stderr
    psnr = run([
        "ffmpeg", "-hide_banner", "-i", str(encoded), *raw_input,
        "-lavfi", f"{metric_inputs};[dist][ref]psnr",
        "-frames:v", str(selected_frames), "-f", "null", "NUL",
    ]).stderr
    ssim_match = re.search(r"All:([0-9.]+)", ssim)
    psnr_match = re.search(r"average:([0-9.]+|inf)\b", psnr)
    if not ssim_match or not psnr_match:
        raise RuntimeError("Could not parse SSIM/PSNR output")
    return {
        "start_frame": start_frame,
        "end_frame_exclusive": end_frame,
        "frames": selected_frames,
        "vmaf": float(vmaf_data["pooled_metrics"]["vmaf"]["mean"]),
        "ssim": float(ssim_match.group(1)),
        "psnr_db": "inf" if psnr_match.group(1) == "inf" else float(psnr_match.group(1)),
    }


def main(argv=None) -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("encoded", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--output-directory", required=True, type=Path)
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--reference-width", type=int)
    parser.add_argument("--reference-height", type=int)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--motion-start-seconds", type=float)
    parser.add_argument("--motion-duration-seconds", type=float)
    args = parser.parse_args(argv)
    output_directory = output_path(args.output_directory, "runs")
    if output_directory.exists():
        raise FileExistsError(output_directory)
    reference_width = args.reference_width or args.width
    reference_height = args.reference_height or args.height
    if args.fps <= 0 or any(value <= 0 or value % 2 for value in (
            args.width, args.height, reference_width, reference_height)):
        raise ValueError("I420 dimensions must be positive and even; FPS must be positive")
    if args.motion_start_seconds is not None or args.motion_duration_seconds is not None:
        if args.motion_start_seconds is None or args.motion_duration_seconds is None:
            raise ValueError("Both motion range arguments are required")
        if (not math.isfinite(args.motion_start_seconds) or not math.isfinite(args.motion_duration_seconds) or
                args.motion_start_seconds < 0 or args.motion_duration_seconds <= 0):
            raise ValueError("Motion start must be non-negative and duration must be positive")
    encoded = resolve(args.encoded)
    reference = resolve(args.reference)
    log_name = f"{encoded.stem}-vmaf.json"

    probe = json.loads(run([
        "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=nb_read_frames,width,height", "-of", "json", str(encoded),
    ]).stdout)
    stream = probe["streams"][0]
    frames = int(stream["nb_read_frames"])
    if (stream["width"], stream["height"]) != (args.width, args.height):
        raise ValueError("Encoded geometry differs from the declared encoder dimensions")
    frame_bytes = reference_width * reference_height * 3 // 2
    reference_frames, remainder = divmod(reference.stat().st_size, frame_bytes)
    if remainder or frames <= 0 or reference_frames < frames:
        raise ValueError("Reference must contain whole I420 frames covering the entire encoded comparison")
    def source_ref(path):
        with path.open("rb") as source:
            return {"path": str(path), "sha256": hashlib.file_digest(source, "sha256").hexdigest()}
    sources = {"encoded": source_ref(encoded), "reference": source_ref(reference)}
    output_directory.mkdir(parents=True, exist_ok=False)
    raw_input = [
        "-f", "rawvideo", "-pix_fmt", "yuv420p",
        "-s", f"{reference_width}x{reference_height}",
        "-r", str(args.fps), "-i", str(reference),
    ]
    scale = ""
    if (args.width, args.height) != (reference_width, reference_height):
        scale = f"scale={reference_width}:{reference_height}:flags=lanczos,"
    overall = score_range(encoded, reference, raw_input, scale, output_directory, log_name, 0, frames)

    result = {
        "frames": frames,
        "reference_frames": reference_frames,
        "sources": sources,
        "scope": "Codec file diagnostic over the encoded frame count; not physical playback or full-run acceptance",
        "metric_colour_basis": "Limited BT.709 tags for metric calculation; actual encoder colour application unmeasured",
        "reference_fps": args.fps,
        "comparison_geometry": [reference_width, reference_height],
        "encoded_scaled_for_comparison": bool(scale),
        "actual_kbps": encoded.stat().st_size * 8 / (frames / args.fps) / 1_000,
        "vmaf": overall["vmaf"],
        "ssim": overall["ssim"],
        "psnr_db": overall["psnr_db"],
    }
    if (args.motion_start_seconds is not None or args.motion_duration_seconds is not None):
        motion_start = round(args.motion_start_seconds * args.fps)
        motion_end = min(frames, motion_start + round(args.motion_duration_seconds * args.fps))
        if motion_start >= motion_end:
            raise ValueError("Motion range does not overlap the encoded file")
        result["motion_section"] = score_range(
            encoded,
            reference,
            raw_input,
            scale,
            output_directory,
            f"{encoded.stem}-motion-vmaf.json",
            motion_start,
            motion_end,
        )
    result_path = output_directory / f"{encoded.stem}-result.json"
    if sources != {"encoded": source_ref(encoded), "reference": source_ref(reference)}:
        raise ValueError("Source bytes changed during scoring")
    result_path.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
