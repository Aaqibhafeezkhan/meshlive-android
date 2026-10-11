"""Extract supplied recording samples in their original container clock, offline.

The input is hash-bound. Stream selection, native geometry and RGB conversion
are explicit. No frame-rate synthesis, autorotation, resizing, audio resampling
or channel mixing is performed. This is capture ingestion, not app acceptance.
"""

import argparse
import csv
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import wave

from benchmark_layout import original, output_path, resolve as resolve_input
from evidence_relocation import logical


def integer(value, name, minimum=None):
    if type(value) is not int or minimum is not None and value < minimum:
        raise ValueError(f"{name} must be an integer" + (f" >= {minimum}" if minimum is not None else ""))
    return value


def digest(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def file_record(spec, base):
    if not isinstance(spec, dict) or not isinstance(spec.get("path"), str) or not spec["path"]:
        raise ValueError("Input needs a nonempty path")
    expected = spec.get("sha256", "")
    if not isinstance(expected, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", expected):
        raise ValueError("Input needs its exact SHA256")
    path = resolve_input(spec["path"], base=base)
    actual = digest(path)
    if actual != expected.lower():
        raise ValueError(f"Input SHA256 mismatch: {path}")
    return {"path": str(path), "sha256": actual, "bytes": path.stat().st_size}


def tool(name):
    path = shutil.which(str(name))
    if path is None:
        raise ValueError(f"Required local tool is missing: {name}")
    version = subprocess.run([path, "-version"], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True).stdout
    return {"path": str(Path(path).resolve()), "sha256": digest(path), "version": version}


def command(args, commands):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    commands.append({"argv": [str(arg) for arg in args], "returncode": result.returncode,
                     "stderr": result.stderr})
    if result.returncode != 0 or result.stderr.strip():
        raise ValueError(f"Media command failed or reported a decoding error: {result.stderr.strip() or result.returncode}")
    return result.stdout


def rational(value, name):
    try:
        result = Fraction(value)
    except (ValueError, ZeroDivisionError, TypeError):
        raise ValueError(f"Missing/invalid rational {name}") from None
    if result <= 0:
        raise ValueError(f"{name} must be positive")
    return result


def rational_record(value):
    value = Fraction(value)
    return {"numerator": value.numerator, "denominator": value.denominator}


def timestamp(row, timebase):
    value = row.get("best_effort_timestamp")
    if type(value) is not int:
        raise ValueError("Every decoded frame needs its exact best_effort_timestamp; no timing inference")
    return value * timebase


def to_ns(value):
    exact = value * 1_000_000_000
    rounded = exact.numerator // exact.denominator
    return rounded, rational_record(exact - rounded)


def stream(document, index, kind):
    index = integer(index, f"{kind}.stream_index", 0)
    rows = [row for row in document.get("streams", []) if row.get("index") == index]
    if len(rows) != 1 or rows[0].get("codec_type") != kind:
        raise ValueError(f"Explicit stream {index} is absent or is not {kind}")
    return rows[0]


def rotations(row):
    values = [row.get("tags", {}).get("rotate", 0)]
    values.extend(data["rotation"] for data in row.get("side_data_list", []) if "rotation" in data)
    for value in values:
        try:
            if float(value) != 0:
                raise ValueError("Rotated display metadata requires a separate declared adapter; no autorotation")
        except (TypeError, ValueError):
            raise ValueError("Rotated/invalid display metadata is unsupported; no autorotation") from None


def video_declarations(spec, info, frames):
    width, height = (integer(spec.get(name), "video." + name, 1) for name in ("width", "height"))
    if (width, height) != (info.get("width"), info.get("height")):
        raise ValueError("Video native geometry differs from the explicit request")
    if spec.get("rgb_conversion") != "ffmpeg_native_rgb24":
        raise ValueError("Explicit video.rgb_conversion=ffmpeg_native_rgb24 is required")
    declarations = spec.get("source_color")
    names = {"range": "color_range", "matrix": "color_space", "primaries": "color_primaries", "transfer": "color_transfer"}
    if not isinstance(declarations, dict) or any(not isinstance(declarations.get(name), str) or not declarations[name] for name in names):
        raise ValueError("RGB conversion needs explicit original range/matrix/primaries/transfer declarations")
    unknown = []
    for name, key in names.items():
        actual = info.get(key, "UNSPECIFIED")
        if actual in {"unknown", "unspecified", "reserved", "UNSPECIFIED"}:
            unknown.append(name)
            if declarations[name] != "UNSPECIFIED":
                raise ValueError("Unknown original color must remain UNSPECIFIED; no invented tags")
        elif declarations[name] != actual:
            raise ValueError(f"Declared source color {name} disagrees with recording metadata")
    if unknown and spec.get("allow_unspecified_color") is not True:
        raise ValueError("Unknown color conversion needs explicit allow_unspecified_color=true; fidelity remains unmeasured")
    rotations(info)
    fmt = info.get("pix_fmt")
    if not isinstance(fmt, str) or not fmt:
        raise ValueError("Original decoded video pixel format must be explicit")
    for frame in frames:
        rotations(frame)
        if frame.get("width") != width or frame.get("height") != height or frame.get("pix_fmt") != fmt:
            raise ValueError("Decoded video changes geometry/pixel format; no hidden conversion/substitution")
        if any(("UNSPECIFIED" if frame[key] in {"unknown", "unspecified", "reserved"} else frame[key]) !=
               ("UNSPECIFIED" if info.get(key, "UNSPECIFIED") in {"unknown", "unspecified", "reserved"} else info.get(key, "UNSPECIFIED"))
               for key in names.values() if key in frame):
            raise ValueError("Decoded video color declarations change within the recording")
    return width, height, {"source": declarations, "original_pixel_format": fmt,
                          "method": "FFmpeg native RGB24 conversion; source tags retained, no geometry filter",
                          "fidelity_status": "UNMEASURED", "unspecified_fields": unknown}


def coverage(interval, start, end):
    if interval is None:
        return {"status": "UNMEASURED", "reason": "No explicit required recording interval", "prefix_ns": None, "tail_ns": None}
    beginning = integer(interval.get("start_ns"), "expected_interval.start_ns")
    ending = integer(interval.get("end_ns"), "expected_interval.end_ns")
    if ending <= beginning:
        raise ValueError("Expected interval must be nonempty and in the original container clock")
    if end is None:
        return {"status": "UNMEASURED", "reason": "Final decoded frame duration absent; terminal coverage unknown",
                "prefix_ns": max(0, to_ns(start)[0] - beginning), "tail_ns": None,
                "expected_start_ns": beginning, "expected_end_ns": ending}
    prefix_exact, tail_exact = max(Fraction(0), start * 1_000_000_000 - beginning), max(Fraction(0), ending - end * 1_000_000_000)
    prefix, tail = to_ns(prefix_exact / 1_000_000_000)[0], to_ns(tail_exact / 1_000_000_000)[0]
    return {"status": "COMPLETE" if prefix_exact == tail_exact == 0 else "INCOMPLETE", "prefix_ns": prefix, "tail_ns": tail,
            "prefix_exact_ns": rational_record(prefix_exact), "tail_exact_ns": rational_record(tail_exact),
            "expected_start_ns": beginning, "expected_end_ns": ending}


def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8", newline="") as source:
        writer = csv.DictWriter(source, fields)
        writer.writeheader()
        writer.writerows(rows)
    return {"path": str(logical(path)), "sha256": digest(path), "bytes": path.stat().st_size}


def extract(spec, base, output):
    if not isinstance(spec, dict) or spec.get("schema") != "benchmark-capture-extract-v1":
        raise ValueError("Expected benchmark-capture-extract-v1 extraction specification")
    input_record = file_record(spec.get("input"), base)
    clock = spec.get("clock")
    if not isinstance(clock, str) or not clock:
        raise ValueError("One declared original container clock ID is required")
    output = output_path(output, "runs")
    if output.exists() or Path(input_record["path"]).is_relative_to(output):
        raise ValueError("Output must be fresh and cannot contain original input evidence")
    ffmpeg, ffprobe = tool(spec.get("ffmpeg", "ffmpeg")), tool(spec.get("ffprobe", "ffprobe"))
    commands = []
    info = json.loads(command([ffprobe["path"], "-v", "error", "-show_streams", "-show_format", "-of", "json", input_record["path"]], commands))
    video_spec = spec.get("video")
    if not isinstance(video_spec, dict):
        raise ValueError("Explicit video stream/declarations are required")
    video = stream(info, video_spec.get("stream_index"), "video")
    video_frames = json.loads(command([ffprobe["path"], "-v", "error", "-select_streams", str(video["index"]), "-show_frames", "-of", "json", input_record["path"]], commands)).get("frames", [])
    if not video_frames:
        raise ValueError("Selected video stream contains no decoded frames")
    width, height, color = video_declarations(video_spec, video, video_frames)
    tb = rational(video.get("time_base"), "video.time_base")
    rows, video_times = [], []
    for index, frame in enumerate(video_frames):
        pts = timestamp(frame, tb)
        value, uncertainty = to_ns(pts)
        if video_times and pts <= video_times[-1]:
            raise ValueError("Video decoded PTS must strictly increase; duplicate/backward/missing timestamps stay invalid")
        video_times.append(pts)
        rows.append({"sample_index": index, "best_effort_timestamp": frame["best_effort_timestamp"],
                     "timebase_numerator": tb.numerator, "timebase_denominator": tb.denominator,
                     "time_ns": value, "rounding_numerator_ns": uncertainty["numerator"],
                     "rounding_denominator_ns": uncertainty["denominator"]})
    duration = video_frames[-1].get("duration", video_frames[-1].get("pkt_duration"))
    video_end = video_times[-1] + duration * tb if type(duration) is int and duration > 0 else None
    audio_spec, audio, audio_frames, audio_rows, sample_count, audio_gaps, audio_zero, audio_end = spec.get("audio"), None, [], [], 0, [], None, None
    if audio_spec is not None:
        if not isinstance(audio_spec, dict) or any(audio_spec.get(k) != v or type(audio_spec.get(k)) is not type(v)
                for k, v in (("sample_rate", 48000), ("sample_format", "s16"))):
            raise ValueError("Audio request must explicitly be native s16 48000 Hz")
        native_channels = integer(audio_spec.get("channels"), "audio.channels", 1)
        channel_index = audio_spec.get("channel_index", 0 if native_channels == 1 else None)
        if type(channel_index) is not int or not 0 <= channel_index < native_channels:
            raise ValueError("Multichannel audio requires an explicit native channel_index; mixing is not authorized")
        audio = stream(info, audio_spec.get("stream_index"), "audio")
        audio_frames = json.loads(command([ffprobe["path"], "-v", "error", "-select_streams", str(audio["index"]), "-show_frames", "-of", "json", input_record["path"]], commands)).get("frames", [])
        if not audio_frames or audio.get("sample_fmt") != "s16" or str(audio.get("sample_rate")) != "48000" or audio.get("channels") != native_channels:
            raise ValueError("Actual decoded audio must match declared native s16 channels at 48000 Hz; resampling/channel mixing/quantization is not authorized")
        audio_tb = rational(audio.get("time_base"), "audio.time_base")
        previous_audio_pts = None
        for index, frame in enumerate(audio_frames):
            if frame.get("sample_fmt") != "s16" or frame.get("channels") != native_channels or str(frame.get("sample_rate", audio.get("sample_rate"))) != "48000":
                raise ValueError("Decoded audio format/rate/channels changed within the selected stream")
            pts = timestamp(frame, audio_tb)
            if audio_zero is None:
                audio_zero = pts
            nb = integer(frame.get("nb_samples"), "audio.nb_samples", 1)
            expected = audio_zero + Fraction(sample_count, 48000)
            difference = pts - expected
            if previous_audio_pts is not None and pts <= previous_audio_pts:
                raise ValueError("Decoded audio PTS must strictly increase; missing/backward clocks are unsupported")
            previous_audio_pts = pts
            if abs(difference) > audio_tb:
                audio_gaps.append({"frame_index": index, "sample_index": sample_count,
                                   "signed_delta_ns": rational_record(difference * 1_000_000_000)})
            value, rounding = to_ns(pts)
            audio_rows.append({"frame_index": index, "sample_index": sample_count, "nb_samples": nb,
                               "best_effort_timestamp": frame["best_effort_timestamp"],
                               "timebase_numerator": audio_tb.numerator, "timebase_denominator": audio_tb.denominator,
                               "time_ns": value, "rounding_numerator_ns": rounding["numerator"],
                               "rounding_denominator_ns": rounding["denominator"]})
            sample_count += nb
            audio_end = pts + Fraction(nb, 48000)
    output.mkdir(parents=True, exist_ok=False)
    result = {"schema": "benchmark-measurement-capture-v1", "measurement_status": "INTERRUPTED",
              "scope": {"clock": clock, "timestamp_basis": "Each decoded frame's original best_effort_timestamp * original stream time_base",
                        "context": spec.get("context"), "physical_acceptance": "UNMEASURED"},
              "input": input_record, "tools": {"ffmpeg": ffmpeg, "ffprobe": ffprobe}, "commands": commands,
              "original_streams": info.get("streams", []), "original_format": info.get("format"),
              "limitations": ["No phone capture or physical app acceptance; imported recording provenance must be verified separately.",
                              "Best-effort timestamps are container playback timestamps, not synchronized phone or wall clocks.",
                              "RGB conversion is declared; original source color fidelity remains a separate measurement.",
                              "Video final duration and expected recording interval must be present to establish terminal coverage.",
                              "Audio sample zero comes from first audio PTS; sample-clock mapping is invalid across explicit gaps.",
                              "No resizing, cropping, autorotation, frame-rate synthesis, audio resampling or channel mixing."]}
    try:
        capture = output / "video.rgb24"
        command([ffmpeg["path"], "-hide_banner", "-loglevel", "error", "-nostdin", "-n", "-noautorotate", "-i", input_record["path"],
                 "-map", f"0:{video['index']}", "-an", "-sn", "-dn", "-pix_fmt", "rgb24", "-fps_mode", "passthrough", "-f", "rawvideo", capture], commands)
        if capture.stat().st_size != len(video_frames) * width * height * 3:
            raise ValueError("Decoded RGB byte/frame count differs from exact probed native frames")
        timestamps = write_csv(output / "video-timestamps.csv", tuple(rows[0]), rows)
        result["video"] = {"path": str(capture), "sha256": digest(capture), "bytes": capture.stat().st_size,
                           "format": "RGB24", "width": width, "height": height, "frame_count": len(video_frames),
                           "clock": clock, "timestamps": timestamps, "first_pts": rational_record(video_times[0]),
                           "exclusive_end_pts": rational_record(video_end) if video_end is not None else None,
                           "color_conversion": color, "coverage": coverage(spec.get("expected_interval"), video_times[0], video_end),
                           "intervals_ns": [rational_record((b - a) * 1_000_000_000) for a, b in zip(video_times, video_times[1:])],
                           "sample_timestamp_rounding": "Floor to signed integer ns; exact sub-ns remainder retained per sample"}
        if audio is not None:
            wav_path = output / "audio.wav"
            native_pcm = output / "audio-native.pcm" if native_channels > 1 else wav_path
            command([ffmpeg["path"], "-hide_banner", "-loglevel", "error", "-nostdin", "-n", "-i", input_record["path"],
                     "-map", f"0:{audio['index']}", "-vn", "-sn", "-dn", "-c:a", "pcm_s16le", "-f",
                     "s16le" if native_channels > 1 else "wav", native_pcm], commands)
            if native_channels > 1:
                stride = native_channels * 2
                if native_pcm.stat().st_size != sample_count * stride:
                    raise ValueError("Decoded native PCM sample count differs from the probed stream")
                with native_pcm.open("rb") as source, wave.open(str(wav_path), "wb") as selected:
                    selected.setparams((1, 2, 48000, 0, "NONE", "not compressed"))
                    while data := source.read(262144 * stride):
                        if len(data) % stride:
                            raise ValueError("Truncated native multichannel PCM")
                        # Copy the selected little-endian samples exactly; never sum or normalize channels.
                        pcm = bytearray(len(data) // native_channels)
                        pcm[0::2] = data[channel_index * 2::stride]
                        pcm[1::2] = data[channel_index * 2 + 1::stride]
                        selected.writeframesraw(pcm)
                native_pcm.unlink()
            with wave.open(str(wav_path), "rb") as source:
                if (source.getframerate(), source.getnchannels(), source.getsampwidth(), source.getnframes()) != (48000, 1, 2, sample_count):
                    raise ValueError("Extracted PCM rate/channels/depth/sample count differs from exact decoded audio")
                pcm_hash = hashlib.sha256()
                remaining = sample_count
                while remaining:
                    data = source.readframes(min(remaining, 262144))
                    if not data or len(data) % 2:
                        raise ValueError("Truncated extracted PCM payload")
                    pcm_hash.update(data)
                    remaining -= len(data) // 2
            zero_ns, zero_remainder = to_ns(audio_zero)
            result["audio"] = {"path": str(wav_path), "sha256": digest(wav_path), "pcm_sha256": pcm_hash.hexdigest(),
                               "format": "s16", "sample_rate": 48000, "channels": 1, "sample_count": sample_count,
                               "stream_index": audio["index"],
                               "channel_selection": {"native_channels": native_channels, "channel_index": channel_index,
                                                     "method": "Exact native s16 samples; no mixing or resampling"},
                               "clock": {"id": clock, "sample_zero_ns": zero_ns, "rounding_remainder_ns": zero_remainder,
                                         "container_tick_ns": rational_record(audio_tb * 1_000_000_000)},
                               "timeline": {"continuity": "SPARSE" if audio_gaps else "CONTINUOUS", "gaps": audio_gaps,
                                            "overflow_samples": 0, "scope": "Decoded file sample accounting; producer hardware overflow UNMEASURED"},
                               "timestamps": write_csv(output / "audio-timestamps.csv", tuple(audio_rows[0]), audio_rows),
                               "first_pts": rational_record(audio_zero), "exclusive_end_pts": rational_record(audio_end),
                               "coverage": coverage(spec.get("expected_interval"), audio_zero, audio_end)}
        else:
            result["audio"] = {"measurement_status": "UNMEASURED", "reason": "No explicit audio stream selected; no audio extraction or sync proof"}
        if digest(input_record["path"]) != input_record["sha256"]:
            raise ValueError("Original recording changed during extraction")
        result["measurement_status"] = "MEASURED"
        result["scope"]["video_timing_status"] = "MEASURED"
        result["scope"]["audio_timing_status"] = "UNMEASURED" if audio is None or audio_gaps else "MEASURED"
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        (output / "capture.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        spec_path = resolve_input(args.spec)
        data = spec_path.read_bytes()
        if spec_path.is_relative_to(logical(args.output)):
            raise ValueError("Fresh output cannot contain its original specification")
        result = extract(json.loads(data.decode("utf-8-sig")), original(spec_path).parent, args.output)
        result["spec"] = {"path": str(spec_path), "sha256": hashlib.sha256(data).hexdigest()}
        (args.output / "capture.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.error(str(error))
    print(f"{result['measurement_status']}: {result['video']['frame_count']} native frames; physical acceptance UNMEASURED; {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
