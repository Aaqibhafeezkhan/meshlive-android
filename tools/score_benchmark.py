import argparse
import csv
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from benchmark_layout import original, output_path, resolve

import numpy as np


def read_timestamps(path: Path) -> list[int]:
    path = resolve(path)
    with path.open(newline="") as source:
        return [int(row["presentation_time_us"]) for row in csv.DictReader(source)]


def run(command: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=True, text=True, capture_output=True, cwd=cwd)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("case_dir", type=Path)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--fps", type=int, default=30)
    args = parser.parse_args()

    output_directory = output_path(args.output_directory, "runs")
    output_directory.mkdir(parents=True, exist_ok=False)
    case_dir = original(resolve(args.case_dir / "result.json")).parent
    frame_size = args.width * args.height * 3 // 2
    luma_size = args.width * args.height
    reference_path = resolve(case_dir / "reference.yuv")
    capture_path = resolve(case_dir / "capture.ivf")
    decoded_path = output_directory / "decoded.yuv"
    aligned_reference_path = output_directory / "aligned-reference.yuv"
    aligned_decoded_path = output_directory / "aligned-decoded.yuv"

    run([
        "ffmpeg", "-y", "-v", "error", "-i", str(capture_path),
        "-pix_fmt", "yuv420p", "-f", "rawvideo", str(decoded_path),
    ])

    reference = np.memmap(reference_path, dtype=np.uint8, mode="r").reshape(-1, frame_size)
    decoded = np.memmap(decoded_path, dtype=np.uint8, mode="r").reshape(-1, frame_size)
    reference_times = read_timestamps(case_dir / "reference.timestamps.csv")
    capture_times = read_timestamps(case_dir / "capture.timestamps.csv")
    if len(decoded) != len(capture_times):
        raise RuntimeError(f"Decoded {len(decoded)} frames but found {len(capture_times)} timestamps")

    candidates = []
    sample_reference_indices = np.linspace(
        0,
        len(reference_times) - 1,
        min(24, len(reference_times)),
        dtype=int,
    )
    alignment_tolerance_us = int(450_000 / args.fps)
    for first_capture_index in range(min(60, len(decoded))):
        clock_offset = reference_times[0] - capture_times[first_capture_index]
        matches = []
        previous_capture_index = -1
        for reference_index, reference_time in enumerate(reference_times):
            capture_index = min(
                range(previous_capture_index + 1, len(capture_times)),
                key=lambda index: abs(capture_times[index] + clock_offset - reference_time),
                default=-1,
            )
            if capture_index < 0:
                matches = []
                break
            residual = capture_times[capture_index] + clock_offset - reference_time
            matches.append((reference_index, capture_index, residual))
            previous_capture_index = capture_index
        if not matches:
            continue
        maximum_residual = max(abs(residual) for _, _, residual in matches)
        if maximum_residual > alignment_tolerance_us:
            continue
        sampled_mse = []
        for reference_index in sample_reference_indices:
            capture_index = matches[reference_index][1]
            difference = (
                reference[reference_index, :luma_size:16].astype(np.int16) -
                decoded[capture_index, :luma_size:16].astype(np.int16)
            )
            sampled_mse.append(float(np.mean(difference.astype(np.float32) ** 2)))
        candidates.append((float(np.mean(sampled_mse)), first_capture_index, matches))
    if not candidates:
        raise RuntimeError(
            f"Could not find a unique timestamp alignment within {alignment_tolerance_us} us"
        )
    _, first_capture_index, matches = min(candidates, key=lambda candidate: candidate[0])
    maximum_residual = max(abs(residual) for _, _, residual in matches)

    with aligned_reference_path.open("wb") as reference_output, aligned_decoded_path.open("wb") as decoded_output:
        for reference_index, capture_index, _ in matches:
            reference_output.write(reference[reference_index].tobytes())
            decoded_output.write(decoded[capture_index].tobytes())

    input_arguments = [
        "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{args.width}x{args.height}",
        "-r", str(args.fps),
    ]
    vmaf_path = output_directory / "vmaf.json"
    run([
        "ffmpeg", "-v", "error",
        *input_arguments, "-i", str(aligned_decoded_path),
        *input_arguments, "-i", str(aligned_reference_path),
        "-lavfi", "[0:v][1:v]libvmaf=log_fmt=json:log_path=vmaf.json",
        "-f", "null", "NUL",
    ], cwd=output_directory)
    vmaf_data = json.loads(vmaf_path.read_text())
    vmaf = float(vmaf_data["pooled_metrics"]["vmaf"]["mean"])

    ssim_result = run([
        "ffmpeg", "-hide_banner",
        *input_arguments, "-i", str(aligned_decoded_path),
        *input_arguments, "-i", str(aligned_reference_path),
        "-lavfi", "[0:v][1:v]ssim", "-f", "null", "NUL",
    ])
    psnr_result = run([
        "ffmpeg", "-hide_banner",
        *input_arguments, "-i", str(aligned_decoded_path),
        *input_arguments, "-i", str(aligned_reference_path),
        "-lavfi", "[0:v][1:v]psnr", "-f", "null", "NUL",
    ])
    ssim_match = re.search(r"All:([0-9.]+)", ssim_result.stderr)
    psnr_match = re.search(r"average:([0-9.]+)", psnr_result.stderr)
    if not ssim_match or not psnr_match:
        raise RuntimeError("Could not parse SSIM/PSNR output")

    duration_seconds = (capture_times[-1] - capture_times[0]) / 1_000_000
    payload_bytes = capture_path.stat().st_size - 32 - 12 * len(capture_times)
    result = {
        "reference_frames": len(matches),
        "captured_frames": len(capture_times),
        "measured_fps": (len(capture_times) - 1) / duration_seconds,
        "actual_kbps": payload_bytes * 8 / duration_seconds / 1_000,
        "alignment_first_capture_frame": first_capture_index,
        "alignment_max_residual_us": maximum_residual,
        "vmaf": vmaf,
        "ssim": float(ssim_match.group(1)),
        "psnr_db": float(psnr_match.group(1)),
    }
    (output_directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
