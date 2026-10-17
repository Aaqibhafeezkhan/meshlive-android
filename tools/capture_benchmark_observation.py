"""Run-owned independent camera/microphone recording for the phone suite.

Planning only validates declarations and hashes; it never opens hardware.
The suite starts/stops the recorder inside its exclusive resource lease.
Container timestamps are retained; host process brackets are not sensor clocks.
"""

import argparse
import copy
from fractions import Fraction
import json
import os
from pathlib import Path
import subprocess
import time

from benchmark_layout import original, output_path, resolve
from child_process_guard import guard
from extract_measurement_capture import digest, file_record, integer
from score_audio_sync import number
from score_physical_av import controls

STORAGE_PIXELS = {"yuyv422": "yuv422p", "uyvy422": "yuv422p", "nv12": "yuv420p",
                  "yuv420p": "yuv420p", "yuv422p": "yuv422p", "yuv444p": "yuv444p",
                  "rgb24": "bgr0", "bgr24": "bgr0", "bgr0": "bgr0", "gray": "gray"}


class RecorderStillRunning(RuntimeError):
    pass


def name(value):
    if not isinstance(value, str) or not value.strip() or any(c in value for c in ':"\r\n\0'):
        raise ValueError("Explicit DirectShow device names must not contain separators or control characters")
    return value


def prepare(pointer, base):
    record = file_record(pointer, base)
    spec = json.loads(Path(record["path"]).read_text(encoding="utf-8-sig"))
    if spec.get("schema") != "benchmark-observation-capture-v1" or spec.get("controlled_nonprivate_target") is not True:
        raise ValueError("Independent capture requires benchmark-observation-capture-v1 and a controlled nonprivate target")
    if not isinstance(spec.get("clock"), str) or not spec["clock"] or not isinstance(spec.get("recorder"), str) or not spec["recorder"]:
        raise ValueError("An independent recorder identity and original container clock ID are required")
    tools = {key: file_record(spec.get(key), original(record["path"]).parent) for key in ("ffmpeg", "ffprobe")}
    video = spec.get("video", {})
    camera = name(video.get("device"))
    for key in ("width", "height", "fps"):
        integer(video.get(key), "video." + key, 1)
    if video.get("pixel_format") not in STORAGE_PIXELS:
        raise ValueError("Declare a supported native 8-bit camera pixel_format; other formats need a measured adapter")
    resources = ["external-camera:" + camera]
    audio = spec.get("audio")
    if audio is not None:
        if not isinstance(audio, dict) or audio.get("sample_rate") != 48000 or audio.get("sample_format") != "s16":
            raise ValueError("Physical audio capture requires declared native s16 at 48000 Hz")
        integer(audio.get("channels"), "audio.channels", 1)
        resources.append("external-microphone:" + name(audio.get("device")))
    directions = spec.get("directions")
    if not isinstance(directions, list) or not directions:
        raise ValueError("Declare the actual observed incoming source/receiver directions")
    identities = set()
    for row in directions:
        pair = row.get("source"), row.get("device")
        if any(not isinstance(item, str) or not item for item in pair) or pair[0] == pair[1] or pair in identities:
            raise ValueError("Observation directions must be unique distinct source/receiver aliases")
        identities.add(pair)
        observes = row.get("observes")
        if not isinstance(observes, list) or set(observes) - {"source_target", "receiver_screen", "receiver_speaker", "source_speaker"} or not {"source_target", "receiver_screen"} <= set(observes):
            raise ValueError("Declare actual source target and receiver screen observations")
        if {"receiver_speaker", "source_speaker"}.intersection(observes) and audio is None:
            raise ValueError("A speaker observation requires an independent microphone")
        if not isinstance(row.get("conditions"), dict):
            raise ValueError("Each direction needs declared capture conditions")
        plan = row.get("observation_plan")
        if not isinstance(plan, dict):
            raise ValueError("observation_plan must predeclare physical controls before acquisition")
        try:
            declared = controls(plan)
        except (KeyError, TypeError):
            raise ValueError("observation_plan requires complete physical ROI/search/detector/interval controls") from None
        if any(not isinstance(declared[key], dict) for key in ("delay_search", "detector", "planned")):
            raise ValueError("observation_plan search, detector and planned interval must be objects")
        for key in ("source_roi", "receiver_roi"):
            box = declared[key]
            if (not isinstance(box, (list, tuple)) or len(box) != 4 or any(type(value) is not int for value in box)
                    or min(box[:2]) < 0 or min(box[2:]) <= 0
                    or box[0] + box[2] > video["width"] or box[1] + box[3] > video["height"]):
                raise ValueError("observation_plan ROIs must fit the exact native recording geometry")
        source, receiver = declared["source_roi"], declared["receiver_roi"]
        if (max(source[0], receiver[0]) < min(source[0] + source[2], receiver[0] + receiver[2])
                and max(source[1], receiver[1]) < min(source[1] + source[3], receiver[1] + receiver[3])):
            raise ValueError("observation_plan source target and receiver screen ROIs must be disjoint")
        search = declared["delay_search"]
        minimum = number(search.get("minimum_ms"), "observation_plan.minimum_ms", 0)
        number(search.get("maximum_ms"), "observation_plan.maximum_ms", minimum)
        detector = declared["detector"]
        for key in ("source_threshold", "receiver_threshold", "minimum_contrast"):
            value = number(detector.get(key), "observation_plan." + key, 0, 255)
            if value == 0 or key != "minimum_contrast" and value == 255:
                raise ValueError("observation_plan detector levels must match native grayscale thresholds/contrast")
        planned = declared["planned"]
        if planned.get("clock") != spec["clock"]:
            raise ValueError("observation_plan clock must match the original recording container")
        start = integer(planned.get("start_ns"), "observation_plan.start_ns")
        end = integer(planned.get("end_ns"), "observation_plan.end_ns")
        minimum_events = integer(planned.get("minimum_events"), "observation_plan.minimum_events", 1)
        expected_events = integer(planned.get("expected_source_events"), "observation_plan.expected_source_events", 1)
        integer(planned.get("max_frame_gap_ns"), "observation_plan.max_frame_gap_ns", 1)
        if end <= start or minimum_events > expected_events:
            raise ValueError("observation_plan requires a complete nonempty interval and consistent event counts")
        if "source_stimulus" in row:
            stimulus = row["source_stimulus"]
            if not isinstance(stimulus, dict):
                raise ValueError("Source acoustic stimulus needs exact WAV/events records")
            row["source_stimulus"] = {key: file_record(stimulus.get(key), original(record["path"]).parent)
                                      for key in ("wav", "events")}
    return {"spec": spec, "spec_record": record, "tools": tools, "resources": sorted(resources)}


def validate_directions(prepared, case):
    if "caller" in case:
        allowed = {(case["caller"], case["callee"]), (case["callee"], case["caller"])}
    else:
        allowed = {(case["source"], row["phone"]) for row in case["receivers"]}
    for row in prepared["spec"]["directions"]:
        if (row["source"], row["device"]) not in allowed or prepared["spec"]["recorder"] in {row["source"], row["device"]}:
            raise ValueError("Observation must name an executed incoming direction and an independent recorder")


def argv(prepared, output):
    spec, tools = prepared["spec"], prepared["tools"]
    video, audio = spec["video"], spec.get("audio")
    args = [tools["ffmpeg"]["path"], "-hide_banner", "-loglevel", "warning", "-nostats",
            "-progress", "pipe:2", "-stats_period", "0.1", "-n", "-f", "dshow",
            "-video_size", f"{video['width']}x{video['height']}", "-framerate", str(video["fps"]),
            "-pixel_format", video["pixel_format"]]
    source = "video=" + video["device"]
    if audio:
        args += ["-sample_rate", "48000", "-sample_size", "16", "-channels", str(audio["channels"])]
        source += ":audio=" + audio["device"]
    args += ["-i", source, "-map", "0:v:0", "-c:v", "ffv1",
             "-pix_fmt", STORAGE_PIXELS[video["pixel_format"]], "-fps_mode", "passthrough"]
    args += ["-map", "0:a:0", "-c:a", "copy"] if audio else ["-an"]
    return args + ["-f", "nut", str(output)]


class ObservationRecorder:
    def __init__(self, prepared, output):
        self.prepared = copy.deepcopy(prepared)
        self.output = output_path(output, "runs")
        self.process, self.log = None, None
        self.result = {"schema": "benchmark-observation-recording-v1", "status": "INTERRUPTED",
                       "spec": prepared["spec_record"], "tools": prepared["tools"],
                       "declared": prepared["spec"], "resources": prepared["resources"],
                       "pixel_repacking": {"input": prepared["spec"]["video"]["pixel_format"],
                           "stored": STORAGE_PIXELS[prepared["spec"]["video"]["pixel_format"]],
                           "scope": "Lossless packing/channel order only; native sample depth/chroma unchanged; colour calibration unmeasured"},
                       "limitations": ["Process brackets do not anchor sensor timestamps or phone clocks.",
                                       "Container acquisition/sensor skew and exposure require independent instrument validation.",
                                       "Recording alone does not pass any phone measurement."]}

    def save(self):
        (self.output / "recording.json").write_text(json.dumps(self.result, indent=2, allow_nan=False) + "\n", encoding="utf-8")

    def start(self, timeout_seconds=10):
        if self.output.exists():
            raise ValueError("Observation output must be fresh")
        # Check frozen declarations/binaries again immediately before opening hardware.
        file_record(self.prepared["spec_record"], self.output)
        for row in self.prepared["tools"].values():
            file_record(row, self.output)
        for row in self.prepared["spec"]["directions"]:
            for asset in row.get("source_stimulus", {}).values():
                file_record(asset, self.output)
        self.output.mkdir(parents=True, exist_ok=False)
        args = argv(self.prepared, self.output / "recording.nut")
        self.result["argv"] = args
        self.log = (self.output / "recorder.log").open("wb")
        try:
            self.result["process_start_bracket_ns"] = [time.monotonic_ns(), None]
            self.process = guard(subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=self.log,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0))
            self.result["process_start_bracket_ns"][1] = time.monotonic_ns()
            self.result["process_pid"] = self.process.pid
            deadline = time.monotonic() + timeout_seconds
            while True:
                if self.process.poll() is not None:
                    raise ValueError("Independent recorder exited during startup; inspect recorder.log")
                progress = (self.output / "recorder.log").read_text(encoding="utf-8", errors="replace")
                frames = [int(line[6:]) for line in progress.splitlines()
                          if line.startswith("frame=") and line[6:].isdigit()]
                recording = self.output / "recording.nut"
                # FFmpeg may buffer valid packets before the OS file size changes.
                if frames and frames[-1] > 0 and recording.is_file():
                    self.result.update(capture_ready_ns=time.monotonic_ns(), startup_video_frames=frames[-1],
                                       startup_recording_bytes=recording.stat().st_size)
                    break
                if time.monotonic() >= deadline:
                    raise ValueError("Independent recorder produced no video output before its startup deadline; inspect recorder.log")
                time.sleep(0.05)
            self.save()
            return self
        except BaseException:
            self.stop()
            raise

    def stop(self):
        if self.process is not None:
            self.result["stop_requested_ns"] = time.monotonic_ns()
            forced = []
            if self.process.poll() is None:
                try:
                    self.process.stdin.write(b"q\n")
                    self.process.stdin.flush()
                except (OSError, BrokenPipeError):
                    pass
                for action, seconds in ((None, 5), ("terminate", 2), ("kill", 2)):
                    if action:
                        forced.append(action)
                        getattr(self.process, action)()
                    try:
                        self.process.wait(timeout=seconds)
                        break
                    except subprocess.TimeoutExpired:
                        if action == "kill":
                            self.result.update(status="RUNNING_UNCONFIRMED", reason="Recorder did not stop; resource cannot be safely released")
                            if self.log is not None:
                                self.log.close()
                                self.log = None
                            self.save()
                            raise RecorderStillRunning(self.result["reason"])
            if self.process.stdin is not None:
                self.process.stdin.close()
            self.result.update(returncode=self.process.returncode, forced_stop=forced,
                               process_stopped_ns=time.monotonic_ns())
        if self.log is not None:
            self.log.close()
            self.log = None
        if not self.output.exists():
            return self.result
        try:
            path = self.output / "recording.nut"
            self.result["recording"] = {"path": str(path), "sha256": digest(path), "bytes": path.stat().st_size}
            if self.result.get("returncode") != 0 or self.result.get("forced_stop"):
                raise ValueError("Recording stopped incompletely; retain raw bytes for diagnosis")
            if "capture_ready_ns" not in self.result:
                raise ValueError("Recording never confirmed startup video output; retain raw bytes for diagnosis")
            file_record(self.prepared["tools"]["ffprobe"], self.output)
            probe = subprocess.run([self.prepared["tools"]["ffprobe"]["path"], "-v", "error", "-show_streams", "-of", "json", str(path)],
                                   capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
            if probe.returncode or probe.stderr.strip():
                raise ValueError("Recording probe failed: " + probe.stderr.strip())
            streams = json.loads(probe.stdout)["streams"]
            self.result["actual_streams"] = streams
            video = [row for row in streams if row["codec_type"] == "video"]
            requested = self.prepared["spec"]["video"]
            if (len(video) != 1 or any(video[0].get(k) != requested[k] for k in ("width", "height"))
                    or Fraction(video[0].get("r_frame_rate", "0")) != requested["fps"]
                    or video[0].get("codec_name") != "ffv1"
                    or video[0].get("pix_fmt") != STORAGE_PIXELS[requested["pixel_format"]]):
                raise ValueError("Actual camera geometry/rate/storage format differs from the exact request")
            audio = [row for row in streams if row["codec_type"] == "audio"]
            requested_audio = self.prepared["spec"].get("audio")
            if requested_audio and (len(audio) != 1 or audio[0].get("sample_fmt") != "s16" or str(audio[0].get("sample_rate")) != "48000" or audio[0].get("channels") != requested_audio["channels"]):
                raise ValueError("Actual microphone format differs from the exact native request")
            self.result["status"] = "CAPTURED"
        except (ValueError, OSError, KeyError, ZeroDivisionError, subprocess.SubprocessError) as error:
            self.result.update(status="INTERRUPTED", reason=str(error))
        if (self.output / "recorder.log").exists():
            self.result["log"] = {"path": str(self.output / "recorder.log"), "sha256": digest(self.output / "recorder.log")}
        self.save()
        return self.result

    def attach(self, manifest):
        """Called once on a new execution before scoring or freezing its hash."""
        manifest.setdefault("observation_recordings", []).append({"path": str(self.output / "recording.json"),
            "sha256": digest(self.output / "recording.json"), "status": self.result["status"]})
        if self.result["status"] != "CAPTURED":
            return
        spec = self.prepared["spec"]
        captures = manifest.setdefault("measurement_captures", [])
        for row in spec["directions"]:
            pids = {str(log["pid"]) for log in manifest.get("logs", []) if log.get("device") == row["device"] and log.get("pid") is not None}
            identities = {(trace.get("summary", {}).get("pid"), trace.get("summary", {}).get("session"))
                for trace in manifest.get("media_traces", []) if trace.get("device") == row["device"]
                and trace.get("role") == "receiver" and trace.get("state") == "MEASURED"}
            for pid, session in sorted(identities, key=str):
                if str(pid) not in pids or not session or session != manifest.get("media_trace_session"):
                    continue
                captures.append({**copy.deepcopy(row), **self.result["recording"], "origin": "EXTERNAL_RECORDING",
                    "capture_method": "independent_camera_microphone" if spec.get("audio") else "independent_camera",
                    "recorder": spec["recorder"], "clock": spec["clock"], "pid": pid, "session": session,
                    "direction": "incoming", "run_id": manifest["run_id"],
                    "apk_sha256": manifest["devices"][row["device"]]["apk_sha256"],
                    "recording_receipt": manifest["observation_recordings"][-1], "capture_spec": self.prepared["spec_record"]})


def main(argv_input=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True)
    args = parser.parse_args(argv_input)
    path = resolve(args.spec)
    try:
        prepared = prepare({"path": str(path), "sha256": digest(path)}, original(path).parent)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.error(str(error))
    print(json.dumps({"status": "NOT RUN", "resources": prepared["resources"], "spec": prepared["spec_record"],
                      "directions": prepared["spec"]["directions"], "reason": "Offline plan only; suite execution owns hardware"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
