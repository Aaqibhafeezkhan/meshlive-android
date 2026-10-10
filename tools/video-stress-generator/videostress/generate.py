"""Bounded-frame generation with exact references, sidecars and fresh variants."""

from contextlib import ExitStack
import csv
from fractions import Fraction
import hashlib
from pathlib import Path
import shutil

from . import __version__
from .audio import schedule, write_wav
from .color import COLOR, CONVERSION, planes, rgb_to_i420
from .composition import calibration_chart, descriptor
from .contract import digest, write_json
from .events import BOUNDARIES, event_document, frame_events
from .marker import content_mask, decode, layout, mark
from .measurelog import MeasureLog
from .media import encode_lossless, environment, require_lossless, verify_lossless
from .metrics import ALGORITHM, measure
from .output import fresh_output
from .provenance import code_identity

FRAME_FIELDS = ["file_frame","frame_id","source_frame_id","source_frame","segment_id","pattern_id","repeat_index",
                "pts_numerator","pts_denominator","i420_sha256","unmarked_i420_sha256","rgb_sha256","marked_rgb_sha256"]
METRIC_FIELDS = ["file_frame","segment_id","si","ti","mean_absolute_delta","changed_fraction","entropy_bits","mean_luma","boundary","conditional_loop"]


def marker_plan(timeline,geometry):
    timeline = timeline.for_mode(geometry)
    cfg = timeline.marker
    if not isinstance(cfg,dict) or set(cfg)-{"enabled","cell_size"}:
        raise ValueError("Marker options: enabled, cell_size")
    w,h,fps = geometry
    spec = layout(w,h,cfg.get("cell_size",2),cfg.get("enabled",True),fps)
    if any(s.pattern=="framing_calibration" for s in timeline.segments) and spec["status"]=="AVAILABLE":
        _,_,mw,mh = spec["bounds_xywh"]
        if mh+2 >= h//2-max(6,2*(min(w,h)//24)):
            raise ValueError("Calibration marker would overlap the shapes; use a smaller marker cell_size or larger native mode")
        spec["bounds_xywh"] = [2*((w-mw)//4),2,mw,mh]
        spec["content_roi"]["exclude_xywh"] = [spec["bounds_xywh"]]
        spec["bounds_convention"] = "half-open x,y,width,height; declared native inset for framing chart, no rotation/scale search"
    tracks = []
    exclusions = list(spec["content_roi"]["exclude_xywh"])
    for s in timeline.segments:
        children = [{"pattern":s.pattern,"params":s.params,"bounds_xywh":[0,0,w,h]}] if not s.panels else s.panels
        for child in children:
            if child["pattern"]!="machine_sync_track":
                continue
            x,y,pw,ph = child["bounds_xywh"]
            track = layout(pw,ph,child["params"]["cell_size"],fps=fps)
            if track["bounds_xywh"]:
                tx,ty,tw,th = track["bounds_xywh"]
                track["bounds_xywh"] = [x+tx,y+ty,tw,th]
                track["content_roi"]["exclude_xywh"] = [track["bounds_xywh"]]
            track["content_roi"]["geometry"] = [w,h]
            tracks.append({"segment_id":s.id,"panel_id":child.get("id"),"identity":"Local source frame/PTS in unmarked reference; external marker carries file-frame identity",**track})
            for rect in track["content_roi"]["exclude_xywh"]:
                if rect not in exclusions:
                    exclusions.append(rect)
    if timeline.cues:
        exclusions.append(timeline.cues["bounds_xywh"])
    spec["visual_cues"] = timeline.cues
    spec["intrinsic_tracks"] = tracks
    spec["content_roi"]["exclude_xywh"] = exclusions
    mask = content_mask(spec)
    spec["content_roi"]["included_pixels"] = int(mask.sum())
    spec["content_roi"]["status"] = "AVAILABLE" if mask.any() else "UNMEASURED"
    spec["content_roi"]["excluded_fraction"] = 1-float(mask.mean())
    if not mask.any():
        spec["limitations"].append("Marker covers all native content; content SI/TI are UNMEASURED")
    elif 1-float(mask.mean())>0.25:
        spec["limitations"].append("Marker excludes more than 25% of this native image; content coverage is limited")
    return spec


def estimate(timeline,env=None):
    if env is not None and timeline.lossless:
        require_lossless(env)
    modes = []
    for geometry in timeline.modes:
        selected = timeline.for_mode(geometry)
        w,h,fps = geometry
        spec = marker_plan(timeline,geometry)
        audio = schedule(timeline,geometry)
        marked = spec["status"]=="AVAILABLE"
        raw = w*h*3//2*selected.frame_count
        rgb = w*h*3*selected.frame_count
        reference_bytes = raw+(raw if marked else 0)+(rgb*(2 if marked else 1) if timeline.reference_mode=="full" else 0)
        if any(s.pattern=="framing_calibration" for s in selected.segments):
            reference_bytes += w*h*3
        wav_bytes = sum(selected.frame_count*rate//fps*channels*2+44 for rate,channels in audio["formats"])
        disk = reference_bytes+wav_bytes+(raw*2+1048576 if timeline.lossless else 0)
        pixel_frames,procedural_work,particle_steps = 0,0,0
        for s in selected.segments:
            frames = s.frames*s.repeat
            pixel_frames += w*h*frames
            children = s.panels or ({"pattern":s.pattern,"params":s.params,"bounds_xywh":[0,0,w,h]},)
            for child in children:
                _,_,pw,ph = child["bounds_xywh"]
                params = child["params"]
                if child["pattern"] in ("swirling_ink","turbulent_clouds"):
                    procedural_work += pw*ph*frames*(3*params["density"]+3)
                if descriptor(child["pattern"])["id"] in ("p29","p30","p31","p32"):
                    particle_steps += frames*max(1,round(pw*ph/130*params["density"]))*params.get("trails",1)
        duration = Fraction(selected.frame_count,fps)
        modes.append({"width":w,"height":h,"fps":fps,"frame_count":selected.frame_count,
                      "duration":{"numerator":duration.numerator,"denominator":duration.denominator},
                      "primary_i420_bytes":raw,"exact_raw_rgb_reference_bytes":reference_bytes,"wav_bytes":wav_bytes,
                      "disk_estimate_bytes":disk,"disk_note":"Includes conservative 2x raw FFV1 allowance, 1 MiB container allowance; scalar sidecars/SQLite extra",
                      "working_frame_memory_estimate_bytes":w*h*256+2097152,
                      "native_pixel_frames":pixel_frames,"procedural_wave_pixel_evaluations_estimate":int(procedural_work),
                      "particle_draw_steps_estimate":particle_steps,"marker":spec,
                      "audio_sample_count_48k":audio["sample_count"] if audio["enabled"] else None})
    return {"schema":"vsg-plan-v1","variant_id":timeline.variant,"modes":modes,
            "total_disk_estimate_bytes":sum(m["disk_estimate_bytes"] for m in modes),
            "memory":"One native frame plus working arrays; CSV streamed; exact quantiles use disk-backed SQLite with 2 MiB cache. Event schedules are metadata.",
            "scope":"Only selected combinations; no Android performance/transport/capture certification",
            "unsupported":["Fractional delivery FPS","HDR","10-bit","4:2:2/4:4:4/RGB/alpha delivery","VMAF measurement (availability recorded only)"]}


def hash_bytes(data):
    return hashlib.sha256(data).hexdigest()


def measured_frame(y,previous,mask):
    if mask.any():
        return measure(y,previous,mask)
    return dict.fromkeys(("si","ti","mean_absolute_delta","changed_fraction","entropy_bits","mean_luma"))


def generate_mode(owned,timeline,geometry,env,progress=None):
    timeline = timeline.for_mode(geometry)
    w,h,fps = geometry
    prefix = f"{w}x{h}-{fps}fps"
    def output(name):
        return owned.file(f"{prefix}/{name}")
    spec = marker_plan(timeline,geometry)
    mask = content_mask(spec)
    audio = schedule(timeline,geometry)
    raw = output("source.i420")
    source_rgb = output("reference.rgb24") if timeline.reference_mode=="full" else None
    unmarked = output("unmarked.i420") if spec["status"]=="AVAILABLE" else raw
    marked_rgb = output("marked.rgb24") if source_rgb and spec["status"]=="AVAILABLE" else source_rgb
    frames_path,metrics_path = output("frames.csv"),output("metrics.csv")
    stats_path = output(".measurements.sqlite")
    log = MeasureLog(stats_path)
    first,previous = None,None
    seek_samples = {}
    marker_count = 0
    intrinsic_count = 0
    try:
        with ExitStack() as stack:
            raw_file = stack.enter_context(raw.open("wb"))
            rgb_file = stack.enter_context(source_rgb.open("wb")) if source_rgb else None
            unmarked_file = stack.enter_context(unmarked.open("wb")) if unmarked!=raw else None
            marked_file = stack.enter_context(marked_rgb.open("wb")) if marked_rgb and marked_rgb!=source_rgb else None
            frames_file = stack.enter_context(frames_path.open("w",newline="",encoding="utf-8"))
            metrics_file = stack.enter_context(metrics_path.open("w",newline="",encoding="utf-8"))
            frames_writer = csv.DictWriter(frames_file,FRAME_FIELDS)
            metrics_writer = csv.DictWriter(metrics_file,METRIC_FIELDS)
            frames_writer.writeheader()
            metrics_writer.writeheader()
            for frame in range(timeline.frame_count):
                source = timeline.frame(geometry,frame)
                tagged = mark(source,frame,fps,spec)
                data = rgb_to_i420(tagged)
                original_data = rgb_to_i420(source) if unmarked_file else data
                raw_file.write(data)
                if rgb_file:
                    rgb_file.write(source.tobytes())
                if unmarked_file:
                    unmarked_file.write(original_data)
                    if marked_file:
                        marked_file.write(tagged.tobytes())
                row = timeline.identity(geometry,frame)
                pts = row.pop("pts")
                row.update(pts_numerator=pts.numerator,pts_denominator=pts.denominator,
                           i420_sha256=hash_bytes(data),unmarked_i420_sha256=hash_bytes(original_data),
                           rgb_sha256=hash_bytes(source.tobytes()),marked_rgb_sha256=hash_bytes(tagged.tobytes()))
                frames_writer.writerow(row)
                y = planes(data,w,h)[0]
                segment,_,source_index = timeline.locate(frame)
                for track in spec["intrinsic_tracks"]:
                    if track["segment_id"]!=segment.id:
                        continue
                    if track["status"]=="AVAILABLE":
                        intrinsic = decode(planes(original_data,w,h)[0],track)
                        if intrinsic["status"]!="DECODED" or intrinsic["file_frame"]!=source_index:
                            raise ValueError(f"Intrinsic machine synchronization track failed at {frame}")
                        intrinsic_count += 1
                if spec["status"]=="AVAILABLE":
                    decoded = decode(y,spec)
                    if decoded["status"]!="DECODED" or decoded["file_frame"]!=frame or Fraction(decoded["pts_numerator"],decoded["pts_denominator"])!=Fraction(frame,fps):
                        raise ValueError(f"Unambiguous I420 marker self-test failed at file-frame {frame}")
                    marker_count += 1
                values = measured_frame(y,previous,mask)
                values["boundary"] = any(e["kind"] in BOUNDARIES for e in frame_events(timeline,geometry,frame))
                metrics_writer.writerow({"file_frame":frame,"segment_id":row["segment_id"],"conditional_loop":False,**values})
                log.append(row["segment_id"],values)
                if first is None:
                    first = y.copy()
                previous = y.copy()
                if frame in (0,timeline.frame_count//2,timeline.frame_count-1):
                    seek_samples[frame] = row["rgb_sha256"]
                if progress:
                    progress(geometry,frame+1,timeline.frame_count)
            seam = {**measured_frame(first,previous,mask),"boundary":True}
            metrics_writer.writerow({"file_frame":0,"segment_id":"__file_loop__","conditional_loop":True,**seam})
            log.append("__file_loop__",{**seam,"si":None,"entropy_bits":None})
        summary = log.summary()
        segments = {s.id:log.summary(s.id) for s in timeline.segments}
    finally:
        log.close()
    stats_path.unlink()
    for frame,expected in seek_samples.items():
        if hash_bytes(timeline.frame(geometry,frame).tobytes())!=expected:
            raise ValueError(f"Seek/re-render pixel mismatch at {frame}")
    audio_files,audio_records = {},{}
    for rate,channels in audio["formats"]:
        key = "reference_audio" if (rate,channels)==(48000,1) else f"audio_{rate}_{channels}ch"
        path = output(f"{key}.wav")
        audio_records[key] = write_wav(path,audio,timeline.frame_count,fps,rate,channels)
        audio_files[key] = path
    events_path,marker_path,provenance_path = output("events.json"),output("marker-layout.json"),output("provenance.json")
    write_json(events_path,event_document(timeline,geometry,audio))
    write_json(marker_path,spec)
    reference = output("reference.ffv1.nut") if timeline.lossless else raw
    roundtrip = {"status":"VERIFIED","kind":"Raw I420 reference","decoded_sha256":digest(raw)}
    commands = []
    if timeline.lossless:
        commands = encode_lossless(raw,reference,geometry)
        roundtrip = verify_lossless(reference,raw,geometry,timeline.frame_count)
    write_json(provenance_path,{"schema":"vsg-provenance-v1","generator":"video-stress-generator","version":__version__,
        "variant_id":timeline.variant,"source_code":code_identity(),"geometry":dict(zip(("width","height","fps"),geometry)),"frame_count":timeline.frame_count,
        "timeline_config":timeline.config,"color":COLOR,"conversion":CONVERSION,"environment":env,"audio":audio_records,
        "reference_mode":timeline.reference_mode,
        "references":{"primary":"Exactly the marked bytes exported to the importer" if spec["status"]=="AVAILABLE" else "Native unmarked I420",
                      "rgb_reference":"Full-range BT.709 nonlinear RGB24, unmarked external overlay; a separate reference from I420" if source_rgb else "NOT_EXPORTED; frame hashes and deterministic re-render retained",
                      "marked_rgb":"Same original RGB with the predeclared external marker overlay" if marked_rgb else "NOT_EXPORTED; frame hashes and deterministic re-render retained",
                      "unmarked_reference":"Exact I420 before external marker overlay; intrinsic p40 track remains content",
                      "reference":"Lossless FFV1/I420 container" if timeline.lossless else "Exact raw I420"},
        "lossless_encode_command":commands,"pixel_reproducibility":"Same source/version/seed/parameters/frame/platform/library versions. Floating trig and NumPy RNG/font raster versions may differ across environments; container byte identity is not promised.",
        "license":"Original implementations and bitmap font: MIT; no third-party clip assets; external tools retain their licenses"})
    report = {"schema":"vsg-media-report-v1","mode":dict(zip(("width","height","fps"),geometry)),
              "frame_count":timeline.frame_count,"raw_bytes":raw.stat().st_size,"raw_sha256":digest(raw),
              "algorithm":ALGORITHM,"analysis_roi":spec["content_roi"],"summary":summary,"segments":segments,
              "loop_seam":{**seam,"conditional_on_consumer_loop":True},"seek_checks":list(seek_samples),
              "marker":{"status":"VERIFIED" if marker_count else spec["status"],"decoded_frames":marker_count,"intrinsic_decoded_frames":intrinsic_count,
                        "intrinsic_tracks":spec["intrinsic_tracks"],
                        "limitations":spec["limitations"]},"lossless_roundtrip":roundtrip,
              "audio":audio_records,"quality":{"psnr":"UNMEASURED","ssim":"UNMEASURED","vmaf":"UNMEASURED",
              "reason":"No selected lossy diagnostic encode; use analyze for PSNR/SSIM","vmaf_filter_available":"libvmaf" in env["filters"]},
              "natural_reference":"UNMEASURED","app_streaming_performance":"UNMEASURED"}
    report_path = output("report.json")
    write_json(report_path,report)
    sidecars = {"reference":reference,"rgb_reference":source_rgb,"marked_rgb":marked_rgb,"unmarked_reference":unmarked,
                "frames":frames_path,"events":events_path,"marker_layout":marker_path,"provenance":provenance_path,
                "measurements":metrics_path,"report":report_path,**audio_files}
    if any(s.panels for s in timeline.segments):
        panels_path = output("panels.json")
        write_json(panels_path,panel_document(timeline,geometry,spec))
        sidecars["panels"] = panels_path
    calibration_segments = [s for s in timeline.segments if s.pattern=="framing_calibration"]
    if calibration_segments:
        frame = calibration_segments[0].start
        calibration_raw = output("calibration.rgb24")
        calibration_raw.write_bytes(mark(timeline.frame(geometry,frame),frame,fps,spec).tobytes())
        _,chart = calibration_chart(geometry)
        chart.update(schema="vsg-framing-calibration-v1",source_file_frame=frame,
                     reference={"path":calibration_raw.name,"sha256":digest(calibration_raw),"format":"RGB24","file_frame":0},
                     marker=spec["bounds_xywh"],visual_cues=timeline.cues,
                     scope="Declared native chart only; detector tolerances and captured-screen validation belong to the consumer")
        calibration_path = output("calibration.json")
        write_json(calibration_path,chart)
        sidecars.update(calibration_reference=calibration_raw,calibration=calibration_path)
    sidecars = {name:path for name,path in sidecars.items() if path is not None}
    fixture = {"width":w,"height":h,"fps":fps,"format":"I420","bit_depth":8,"path":raw.relative_to(owned.path).as_posix(),
               "sha256":digest(raw),"frame_count":timeline.frame_count,"color":COLOR,
               "variant_id":timeline.variant,"sidecars":{name:{"path":p.relative_to(owned.path).as_posix(),"sha256":digest(p)} for name,p in sidecars.items()}}
    return fixture,report


def panel_document(timeline,geometry,marker):
    segments=[]
    for segment in timeline.for_mode(geometry).segments:
        if not segment.panels:
            continue
        panels=[]
        for panel in segment.panels:
            x,y,w,h = panel["bounds_xywh"]
            exclusions=[]
            for ex,ey,ew,eh in marker["content_roi"]["exclude_xywh"]:
                x0,y0,x1,y1 = max(x,ex),max(y,ey),min(x+w,ex+ew),min(y+h,ey+eh)
                if x1>x0 and y1>y0:
                    exclusions.append([x0,y0,x1-x0,y1-y0])
            panels.append({**panel,"exclude_xywh":exclusions})
        segments.append({"segment_id":segment.id,"file_frame_start":segment.start,
            "file_frame_end":segment.start+segment.frames*segment.repeat,"source_frame_count":segment.frames,
            "repeat":segment.repeat,"panels":panels})
    return {"schema":"vsg-panels-v1","geometry":dict(zip(("width","height","fps"),geometry)),
            "bounds_convention":"half-open native x,y,width,height; no scale/crop/interpolation",
            "segments":segments,"content_roi":marker["content_roi"],
            "scope":"Fixed diagnostic regions; full-frame quality remains primary, no panel acceptance implied"}


def generate(timeline,output,progress=None,env=None):
    output = Path(output)
    if output.exists():
        raise FileExistsError(f"Fresh output required: {output}; use a new variant directory")
    env = env if env is not None else environment()
    plan = estimate(timeline,env)
    ancestor = output.resolve().parent
    while not ancestor.exists():
        ancestor = ancestor.parent
    if shutil.disk_usage(ancestor).free<plan["total_disk_estimate_bytes"]:
        raise ValueError("Insufficient free disk for requested raw/reference estimate; select fewer frames/modes")
    with fresh_output(output) as owned:
        write_json(owned.file("plan.json"),plan)
        write_json(owned.file("environment.json"),env)
        fixtures,reports = [],[]
        for geometry in timeline.modes:
            fixture,report = generate_mode(owned,timeline,geometry,env,progress)
            fixtures.append(fixture)
            reports.append(report)
        manifest = owned.file("raw-fixtures.json")
        write_json(manifest,{"schema_version":1,"fixtures":fixtures})
        result = {"schema":"vsg-generation-report-v1","status":"GENERATED_AND_SELF_VERIFIED","variant_id":timeline.variant,
                  "manifest_sha256":digest(manifest),"fixtures":reports,"app_streaming_performance":"UNMEASURED"}
        write_json(owned.file("report.json"),result)
        return result
