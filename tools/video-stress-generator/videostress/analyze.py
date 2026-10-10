"""Selected software diagnostic encodes and matched native natural-reference demand."""

import csv
from fractions import Fraction
from pathlib import Path

from .color import planes
from .contract import digest, positive_int, read_json, validate_manifest, write_json
from .generate import measured_frame
from .marker import content_mask
from .measurelog import MeasureLog
from .media import check_native_probe, decode_frames, environment, probe, raw_input, run
from .metrics import ALGORITHM, measure, statistics
from .model import parse_mode
from .output import fresh_output
from .quality import ALGORITHM as QUALITY_ALGORITHM, frame_quality, psnr
from .validate import validate_generated


def select_fixture(manifest,selected):
    rows = manifest["fixtures"]
    if selected:
        geometry = parse_mode(selected)
        rows = [r for r in rows if (r["width"],r["height"],r["fps"])==geometry]
    if len(rows)!=1:
        raise ValueError("Select exactly one manifest mode with --mode WIDTHxHEIGHT@FPS")
    return rows[0]


def aligned_info(path,geometry,count):
    info = probe(path,frames=True)
    stream = check_native_probe(info,geometry)
    stamps = info.get("frames",[])
    timebase = Fraction(stream["time_base"])
    if len(stamps)!=count or any(Fraction(f["best_effort_timestamp"])*timebase!=Fraction(i,geometry[2]) for i,f in enumerate(stamps)):
        raise ValueError("Reference/diagnostic frame count or PTS differ; no trimming, frame dropping or alignment guessing")
    return info,stream


def encode_diagnostic(raw,path,geometry,codec,allocation,bitrate,buffer_bits,env):
    if codec!="libx264":
        raise ValueError("Diagnostic implementation supports libx264 only; other codecs remain unsupported")
    if codec not in env["encoders"]:
        raise ValueError("Optional libx264 encoder absent; diagnostic encode is UNMEASURED")
    positive_int(bitrate,"diagnostic bitrate")
    if allocation not in ("abr","capped"):
        raise ValueError("Explicit diagnostic allocation must be abr or capped; strict CBR is unsupported")
    if buffer_bits is not None:
        positive_int(buffer_bits,"buffer_bits")
        if allocation!="capped":
            raise ValueError("buffer_bits applies only to capped allocation")
    options = ["-preset","veryfast","-b:v",str(bitrate),"-g",str(geometry[2]*2),"-bf","0","-threads","1"]
    if allocation=="capped":
        options += ["-maxrate",str(bitrate),"-bufsize",str(buffer_bits or bitrate)]
    args = ["ffmpeg","-hide_banner","-loglevel","error","-nostdin","-n",*raw_input(raw,geometry),
            "-map","0:v:0","-an","-c:v",codec,*options,"-pix_fmt","yuv420p","-vf","setsar=1",
            "-color_range","tv","-color_primaries","bt709","-color_trc","bt709","-colorspace","bt709",
            "-chroma_sample_location","center","-fps_mode","passthrough",str(path)]
    run(args)
    return {"codec":codec,"allocation":allocation,"target_bits_per_second":bitrate,
            "buffer_bits":buffer_bits or bitrate if allocation=="capped" else None,"command":args,
            "semantics":"Single-pass average bitrate" if allocation=="abr" else "Average bitrate target with VBV maximum and explicit buffer; no filler padding and no strict CBR promise"}


def bitrate_report(path,geometry,count,owned):
    info = probe(path,packets=True)
    stream = check_native_probe(info,geometry)
    timebase = Fraction(stream["time_base"])
    bits_by_frame = [0]*count
    for packet in info.get("packets",[]):
        pts = Fraction(packet["pts"])*timebase
        frame = pts*geometry[2]
        if frame.denominator!=1 or not 0<=frame<count:
            raise ValueError("Encoded packets have unmatched PTS; payload demand UNMEASURED")
        bits_by_frame[int(frame)] += int(packet["size"])*8
    if any(bits<=0 for bits in bits_by_frame):
        raise ValueError("Diagnostic has missing packet payload for a planned frame")
    with owned.file("payload-frames.csv").open("w",newline="",encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["file_frame","pts_numerator","pts_denominator","payload_bits","bits_per_second_over_frame_interval"])
        for frame,bits in enumerate(bits_by_frame):
            pts = Fraction(frame,geometry[2])
            writer.writerow([frame,pts.numerator,pts.denominator,bits,bits*geometry[2]])
    distributions = {"per_frame_bps":statistics(bits*geometry[2] for bits in bits_by_frame)}
    duration = Fraction(count,geometry[2])
    for milliseconds in (100,1000):
        window = Fraction(milliseconds,1000)
        bins = [0]*((duration.numerator*window.denominator+duration.denominator*window.numerator-1)//(duration.denominator*window.numerator))
        for frame,bits in enumerate(bits_by_frame):
            bins[int(Fraction(frame,geometry[2])//window)] += bits
        rates = []
        with owned.file(f"payload-{milliseconds}ms.csv").open("w",newline="",encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["bin","start_numerator","start_denominator","duration_numerator","duration_denominator","payload_bits","bits_per_second"])
            for index,bits in enumerate(bins):
                start = index*window
                span = min(window,duration-start)
                rate = float(bits/span)
                rates.append(rate)
                writer.writerow([index,start.numerator,start.denominator,span.numerator,span.denominator,bits,rate])
        distributions[f"{milliseconds}ms_bps"] = statistics(rates)
    return {"status":"MEASURED","packet_payload_bytes":sum(bits_by_frame)//8,"container_bytes":path.stat().st_size,
            "mean_payload_bits_per_second":float(sum(bits_by_frame)/duration),"distributions":distributions,
            "method":"Video packet sizes by exact PTS; half-open bins anchored at zero; zero bins retained; final partial bin uses actual duration. Includes codec headers inside packets, excludes container overhead.",
            "limits":"Low FPS yields empty 100 ms bins. Packet demand is this software encoder result; no artificial file padding, transport overhead or universal CBR spike claim."}


def quality_report(raw,decoded,geometry,count,mask,owned):
    results = []
    with raw.open("rb") as ref, decode_frames(decoded,geometry) as frames, owned.file("quality-frames.csv").open("w",newline="",encoding="utf-8") as f:
        writer = None
        for index,data in enumerate(frames):
            if index>=count:
                raise ValueError("Diagnostic decode has extra frames")
            original = ref.read(len(data))
            values = frame_quality(original,data,geometry[0],geometry[1],mask)
            if writer is None:
                writer = csv.DictWriter(f,["file_frame",*values])
                writer.writeheader()
            writer.writerow({"file_frame":index,**values})
            results.append(values)
        if len(results)!=count or ref.read(1):
            raise ValueError("Diagnostic/reference frame count mismatch")
    mse = sum(r["mse_i420"] for r in results)/count
    y_mse = sum(r["mse_y"] for r in results)/count
    content = [r["content_mse_y"] for r in results if r["content_mse_y"] is not None]
    return {"status":"MEASURED","algorithm":QUALITY_ALGORITHM,"frames_scored":count,
            "psnr_i420_from_pooled_mse":psnr(mse),"psnr_y_from_pooled_mse":psnr(y_mse),
            "ssim_y":statistics(r["ssim_y"] for r in results),
            "content_psnr_y_from_pooled_mse":psnr(sum(content)/len(content)) if content else "UNMEASURED",
            "content_ssim_y":statistics(r["content_ssim_y"] for r in results),
            "vmaf":{"status":"UNMEASURED","reason":"Optional VMAF adapter/model not implemented; never inferred from PSNR/SSIM"}}


def natural_report(path,geometry,count,mask,synthetic,owned,range_override=None,cuts_path=None):
    info,stream = aligned_info(path,geometry,count)
    names = {"range":"color_range","primaries":"color_primaries","matrix":"color_space","transfer":"color_transfer","chroma_location":"chroma_location"}
    color = {k:stream.get(v,"UNSPECIFIED") for k,v in names.items()}
    color["range"] = {"tv":"limited","pc":"full"}.get(color["range"],"UNSPECIFIED")
    if range_override:
        color["range"] = range_override
    record = {"path":str(path.resolve()),"sha256":digest(path),"geometry":list(geometry),"color":color,
              "range_override":range_override,"conversion_history":"Decoded native declared 8-bit I420; no conversion, crop, scale or FPS change",
              "algorithm":ALGORITHM,"cuts":"Not supplied; sustained demand comparison has unknown natural cut classification"}
    if color["range"]=="UNSPECIFIED":
        return {**record,"status":"UNMEASURED","reason":"Imported luma range is UNSPECIFIED; supply --natural-range from source evidence"}
    cuts = set()
    if cuts_path:
        data = read_json(cuts_path)
        if data.get("schema")!="vsg-natural-cuts-v1" or not isinstance(data.get("cut_frames"),list):
            raise ValueError("Natural cuts need schema vsg-natural-cuts-v1 and cut_frames array")
        for frame in data["cut_frames"]:
            if type(frame) is not int or not 0<frame<count or frame in cuts:
                raise ValueError("Natural cut indices must be unique integer file-frame indices in (0,count)")
            cuts.add(frame)
        record["cuts"] = {"path":str(cuts_path.resolve()),"sha256":digest(cuts_path),"cut_frames":sorted(cuts)}
    database = owned.file(".natural-stats.sqlite")
    log = MeasureLog(database)
    previous,first = None,None
    decoded_count = 0
    try:
        with decode_frames(path,geometry) as frames, owned.file("natural-metrics.csv").open("w",newline="",encoding="utf-8") as f:
            writer = None
            for frame,data in enumerate(frames):
                y = planes(data,geometry[0],geometry[1])[0]
                values = measure(y,previous,mask,color["range"]) if mask.any() else measured_frame(y,previous,mask)
                values["boundary"] = frame in cuts
                if writer is None:
                    writer = csv.DictWriter(f,["file_frame",*values])
                    writer.writeheader()
                writer.writerow({"file_frame":frame,**values})
                log.append("natural",values)
                if first is None:
                    first = y.copy()
                previous = y.copy()
                decoded_count += 1
        if decoded_count!=count:
            raise ValueError("Natural decode count differs from declared matched frame count")
        seam = {**(measure(first,previous,mask,color["range"]) if mask.any() else measured_frame(first,previous,mask)),"boundary":True}
        log.append("__file_loop__",{**seam,"si":None,"entropy_bits":None})
        summary = log.summary()
    finally:
        log.close()
    database.unlink()
    if not cuts_path:
        summary["ti_excluding_cuts_seams"] = {"status":"UNMEASURED","count":0,"median":None,"p95":None,"peak":None,
                                             "reason":"Natural scene-cut classification was not supplied"}
    shortfalls = {}
    for metric in ("si","ti_including_cuts_seams","ti_excluding_cuts_seams"):
        values = {}
        for stat in ("median","p95","peak"):
            a,b = synthetic[metric][stat],summary[metric][stat]
            values[stat] = max(0,b-a) if a is not None and b is not None else None
        shortfalls[metric] = values
    return {**record,"status":"MEASURED","summary":summary,"loop_seam":{**seam,"conditional_on_consumer_loop":True},"synthetic_shortfalls":shortfalls,
            "interpretation":"Positive shortfall=max(0,natural statistic-synthetic statistic), spatial AND temporal; this does not establish matching content or quality"}


def analyze(manifest_path,output,selected=None,codec=None,allocation=None,bitrate=None,buffer_bits=None,
            natural=None,natural_range=None,natural_cuts=None):
    manifest_path = Path(manifest_path)
    manifest = validate_manifest(manifest_path)
    row = select_fixture(manifest,selected)
    geometry = row["width"],row["height"],row["fps"]
    base = manifest_path.parent
    for name in ("marker_layout","report"):
        if name not in row.get("sidecars",{}):
            raise ValueError(f"Analyze requires VSG {name} sidecar")
    validate_generated(row,base)
    spec = read_json(base/row["sidecars"]["marker_layout"]["path"])
    mask = content_mask(spec)
    reference_report = read_json(base/row["sidecars"]["report"]["path"])
    if not codec and not natural:
        raise ValueError("Select --codec and/or --natural-reference explicitly")
    if codec and (bitrate is None or allocation is None):
        raise ValueError("Diagnostic requires explicit --bitrate and --allocation")
    env = environment()
    with fresh_output(output) as owned:
        result = {"schema":"vsg-analysis-report-v1","mode":list(geometry),"manifest_sha256":digest(manifest_path),
                  "raw_sha256":row["sha256"],"frame_count":row["frame_count"],"analysis_roi":spec["content_roi"],
                  "environment":env,"diagnostic":"UNMEASURED","natural_reference":"UNMEASURED","app_streaming_performance":"UNMEASURED"}
        if codec:
            path = owned.file("diagnostic.mp4")
            settings = encode_diagnostic(base/row["path"],path,geometry,codec,allocation,bitrate,buffer_bits,env)
            aligned_info(path,geometry,row["frame_count"])
            result["diagnostic"] = {"settings":settings,"path":path.name,"sha256":digest(path),
                                    "payload":bitrate_report(path,geometry,row["frame_count"],owned),
                                    "quality":quality_report(base/row["path"],path,geometry,row["frame_count"],mask,owned)}
        if natural:
            result["natural_reference"] = natural_report(Path(natural),geometry,row["frame_count"],mask,
                reference_report["summary"],owned,natural_range,Path(natural_cuts) if natural_cuts else None)
        write_json(owned.file("report.json"),result)
        return result
