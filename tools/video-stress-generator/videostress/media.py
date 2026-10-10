"""FFmpeg file operations, capability inventory and exact native decode checks."""

from contextlib import contextmanager
from fractions import Fraction
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile


def run(args):
    try:
        process = subprocess.Popen([str(a) for a in args],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    except FileNotFoundError:
        raise ValueError(f"Required tool {args[0]} is missing; install FFmpeg/ffprobe or disable the requested optional feature") from None
    try:
        stdout,stderr = process.communicate()
    except BaseException:
        if process.poll() is None:
            process.terminate()
        process.communicate()
        raise
    if process.returncode:
        error = stderr.decode("utf-8",errors="replace")[-4000:]
        raise ValueError(f"{args[0]} failed ({process.returncode}): {error.strip()}")
    return stdout.decode("utf-8",errors="replace")


def environment():
    result = {"schema":"vsg-environment-v1","python":sys.version,"platform":platform.platform(),
              "libraries":{},"tools":{},"optional_formats":"UNSUPPORTED: rational FPS, HDR, alpha, non-I420 delivery and >8-bit"}
    for name in ("numpy","Pillow","opencv-python","pytest","scipy","scikit-image"):
        try:
            result["libraries"][name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            result["libraries"][name] = "UNAVAILABLE"
    for name in ("ffmpeg","ffprobe"):
        executable = shutil.which(name)
        result["tools"][name] = {"path":executable,"status":"AVAILABLE" if executable else "UNAVAILABLE"}
        if executable:
            result["tools"][name]["version"] = run([executable,"-hide_banner","-version"]).strip()
    if result["tools"]["ffmpeg"]["path"]:
        result["tools"]["ffmpeg"]["license"] = run(["ffmpeg","-hide_banner","-L"]).strip()
        for option,key,flags in [("-encoders","encoders",r"[VAS][A-Z.]{5}"),("-filters","filters",r"[TSC.]{1,3}")]:
            lines = run(["ffmpeg","-hide_banner",option]).splitlines()
            result[key] = [parts[1] for line in lines if len(parts:=line.split())>1 and parts[1]!="=" and re.fullmatch(flags,parts[0])]
    else:
        result.update(encoders=[],filters=[])
    return result


def require_lossless(env):
    if "ffv1" not in env["encoders"] or not env["tools"]["ffprobe"]["path"]:
        raise ValueError("FFV1 encoder and ffprobe required for lossless container references; use lossless:false for exact raw-only references")


def raw_input(path,geometry):
    w,h,fps = geometry
    return ["-f","rawvideo","-pixel_format","yuv420p","-video_size",f"{w}x{h}",
            "-framerate",str(fps),"-color_range","tv","-color_primaries","bt709",
            "-color_trc","bt709","-colorspace","bt709","-chroma_sample_location","center","-i",str(path)]


def encode_lossless(raw,path,geometry):
    # NUT retains native rational timestamps, including unusual integer rates.
    args = ["ffmpeg","-hide_banner","-loglevel","error","-nostdin","-n",*raw_input(raw,geometry),
            "-map","0:v:0","-an","-c:v","ffv1","-level","3","-coder","1","-context","1",
            "-slicecrc","1","-threads","1","-pix_fmt","yuv420p","-vf","setsar=1",
            "-fps_mode","passthrough",str(path)]
    run(args)
    return args


def probe(path,frames=False,packets=False):
    args = ["ffprobe","-v","error","-select_streams","v:0","-show_streams","-show_format"]
    if frames:
        args += ["-show_frames","-show_entries","frame=best_effort_timestamp,best_effort_timestamp_time,pkt_duration"]
    if packets:
        args += ["-show_packets","-show_entries","packet=pts,dts,size,duration,flags"]
    return json.loads(run([*args,"-of","json",str(path)]))


def check_native_probe(info,geometry,pixel_format="yuv420p"):
    streams = info.get("streams",[])
    if len(streams)!=1:
        raise ValueError("Expected one video stream")
    s = streams[0]
    if (s.get("width"),s.get("height"))!=geometry[:2] or s.get("pix_fmt")!=pixel_format:
        raise ValueError("Decoded geometry/pixel layout differs from native reference; no conversion allowed")
    rate = s.get("avg_frame_rate","0/1")
    if Fraction(rate)==0:
        rate = s.get("r_frame_rate","0/1")
    if Fraction(rate)!=geometry[2]:
        raise ValueError(f"FPS mismatch: declared {geometry[2]}, probed {rate}")
    if s.get("sample_aspect_ratio","1:1") not in ("1:1","0:1","N/A"):
        raise ValueError("Pixel aspect ratio differs from square native pixels")
    return s


@contextmanager
def decode_frames(path,geometry):
    size = geometry[0]*geometry[1]*3//2
    args = ["ffmpeg","-v","error","-nostdin","-i",str(path),"-map","0:v:0","-an",
            "-fps_mode","passthrough","-c:v","rawvideo","-pix_fmt","yuv420p","-f","rawvideo","pipe:1"]
    with tempfile.TemporaryFile() as error:
        process = subprocess.Popen(args,stdout=subprocess.PIPE,stderr=error)
        try:
            def frames():
                while True:
                    frame = bytearray()
                    while len(frame)<size:
                        chunk = process.stdout.read(size-len(frame))
                        if not chunk:
                            break
                        frame.extend(chunk)
                    if not frame:
                        break
                    if len(frame)!=size:
                        raise ValueError("Decoder returned a truncated frame")
                    yield bytes(frame)
                if process.wait():
                    error.seek(0)
                    raise ValueError("FFmpeg decode failed: "+error.read().decode(errors="replace")[-4000:])
            yield frames()
        finally:
            process.stdout.close()
            if process.poll() is None:
                process.terminate()
                process.wait()


def verify_lossless(path,raw,geometry,count):
    info = probe(path,frames=True)
    stream = check_native_probe(info,geometry)
    sha = hashlib.sha256()
    decoded_count = 0
    with decode_frames(path,geometry) as frames:
        for data in frames:
            sha.update(data)
            decoded_count += 1
    from .contract import digest
    if decoded_count!=count or sha.hexdigest()!=digest(raw):
        raise ValueError("Lossless container did not round-trip to identical I420 pixels/count")
    timestamps = info.get("frames",[])
    timebase = Fraction(stream["time_base"])
    if len(timestamps)!=count or any(Fraction(f["best_effort_timestamp"])*timebase!=Fraction(i,geometry[2])
                                     for i,f in enumerate(timestamps)):
        raise ValueError("Lossless native timestamp check failed")
    return {"status":"VERIFIED","decoded_sha256":sha.hexdigest(),"frame_count":decoded_count,
            "stream":stream,"timestamp_tolerance_seconds":"0","container":"NUT","commands":"FFV1 v3, single thread, slice CRC",
            "color_tags":"NUT/FFV1 may omit stream color tags; authoritative generated color/conversion declarations are in the hashed provenance sidecar"}
