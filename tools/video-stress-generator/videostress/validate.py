"""Consumer integrity plus generated sidecar/geometry/pixel/audio round-trip validation."""

from contextlib import ExitStack
import csv
from fractions import Fraction
import hashlib
from pathlib import Path
import tempfile
import wave

from .audio import chunk, schedule
from .color import COLOR, CONVERSION, planes, rgb_to_i420
from .contract import digest, read_json, validate_manifest
from .events import BOUNDARIES, event_document, frame_events
from .generate import FRAME_FIELDS, METRIC_FIELDS, hash_bytes, marker_plan, measured_frame, panel_document
from .composition import calibration_chart
from .marker import content_mask, decode, mark
from .measurelog import MeasureLog
from .media import verify_lossless
from .metrics import ALGORITHM
from .model import Timeline
from .provenance import code_identity


def validate_generated(row,base):
    sidecars = row.get("sidecars",{})
    if "provenance" not in sidecars:
        return {"status":"CONTRACT_INTEGRITY_VERIFIED","sidecar_semantics":"UNMEASURED",
                "reason":"Third-party manifest has no VSG provenance; geometry/color cannot be inferred from byte size alone"}
    def path(name):
        if name not in sidecars:
            raise ValueError(f"Generated output missing required sidecar {name}")
        return base/sidecars[name]["path"]
    provenance = read_json(path("provenance"))
    if provenance.get("schema")!="vsg-provenance-v1" or provenance.get("generator")!="video-stress-generator":
        raise ValueError("Unsupported provenance schema/generator; sidecar semantics UNMEASURED")
    if provenance.get("source_code")!=code_identity():
        raise ValueError("Generator source version differs; use the recorded generator source for exact re-render validation")
    geometry = row["width"],row["height"],row["fps"]
    timeline = Timeline(provenance["timeline_config"])
    if provenance["geometry"]!=dict(zip(("width","height","fps"),geometry)) or geometry not in timeline.modes:
        raise ValueError("Manifest geometry/FPS differs from declared reference/provenance")
    timeline = timeline.for_mode(geometry)
    if timeline.variant!=provenance.get("variant_id") or row.get("variant_id")!=timeline.variant or row["frame_count"]!=timeline.frame_count:
        raise ValueError("Variant identity/frame count differs from generator provenance")
    if row["color"]!=COLOR or provenance.get("color")!=COLOR or provenance.get("conversion")!=CONVERSION:
        raise ValueError("Generated color/conversion declarations differ from implemented pipeline")
    spec = marker_plan(timeline,geometry)
    if read_json(path("marker_layout"))!=spec:
        raise ValueError("Marker ROI/layout differs from predeclared generator configuration")
    audio = schedule(timeline,geometry)
    if read_json(path("events"))!=event_document(timeline,geometry,audio):
        raise ValueError("Events/audio sample indices differ from exact timeline schedule")
    if any(s.panels for s in timeline.segments) and read_json(path("panels"))!=panel_document(timeline,geometry,spec):
        raise ValueError("Native panel coordinates/parameters differ from declared composition")
    calibration_segments = [s for s in timeline.segments if s.pattern=="framing_calibration"]
    if calibration_segments:
        frame = calibration_segments[0].start
        calibration_raw = path("calibration_reference")
        if calibration_raw.read_bytes()!=mark(timeline.frame(geometry,frame),frame,geometry[2],spec).tobytes():
            raise ValueError("Calibration reference is not the executed native chart frame")
        _,chart = calibration_chart(geometry)
        chart.update(schema="vsg-framing-calibration-v1",source_file_frame=frame,
                     reference={"path":calibration_raw.name,"sha256":digest(calibration_raw),"format":"RGB24","file_frame":0},
                     marker=spec["bounds_xywh"],visual_cues=timeline.cues,
                     scope="Declared native chart only; detector tolerances and captured-screen validation belong to the consumer")
        if read_json(path("calibration"))!=chart:
            raise ValueError("Calibration geometry differs from declared native chart")
    markers = 0
    previous,first = None,None
    mask = content_mask(spec)
    with ExitStack() as stack:
        temporary = stack.enter_context(tempfile.TemporaryDirectory(prefix="vsg-validate-"))
        log = MeasureLog(Path(temporary)/"measurements.sqlite")
        stack.callback(log.close)
        metrics_csv = stack.enter_context(path("measurements").open(newline="",encoding="utf-8"))
        metrics_reader = csv.DictReader(metrics_csv)
        if metrics_reader.fieldnames!=METRIC_FIELDS:
            raise ValueError("Unexpected measurements CSV schema")
        def verify_metrics(expected):
            actual = next(metrics_reader,None)
            if actual is None or any(actual[k]!=(str(v) if v is not None else "") for k,v in expected.items()):
                raise ValueError("Per-frame measurement/ROI/boundary mismatch")
        frames_csv = stack.enter_context(path("frames").open(newline="",encoding="utf-8"))
        reader = csv.DictReader(frames_csv)
        if reader.fieldnames!=FRAME_FIELDS:
            raise ValueError("Unexpected frame CSV schema")
        raw = stack.enter_context((base/row["path"]).open("rb"))
        unmarked = stack.enter_context(path("unmarked_reference").open("rb"))
        rgb = stack.enter_context(path("rgb_reference").open("rb")) if timeline.reference_mode=="full" else None
        marked_rgb = stack.enter_context(path("marked_rgb").open("rb")) if timeline.reference_mode=="full" else None
        size = geometry[0]*geometry[1]*3//2
        for i in range(timeline.frame_count):
            record = next(reader,None)
            if record is None:
                raise ValueError("Frame CSV is truncated")
            identity = timeline.identity(geometry,i)
            pts = identity.pop("pts")
            expected = {**identity,"pts_numerator":pts.numerator,"pts_denominator":pts.denominator}
            if any(str(record[k])!=str(v) for k,v in expected.items()):
                raise ValueError(f"Frame identity/rational timestamp mismatch at {i}")
            source = timeline.frame(geometry,i)
            tagged = mark(source,i,geometry[2],spec)
            blobs = {"i420_sha256":raw.read(size),"unmarked_i420_sha256":unmarked.read(size)}
            if rgb:
                blobs.update(rgb_sha256=rgb.read(geometry[0]*geometry[1]*3),marked_rgb_sha256=marked_rgb.read(geometry[0]*geometry[1]*3))
            expected_blobs = {"i420_sha256":rgb_to_i420(tagged),"unmarked_i420_sha256":rgb_to_i420(source),
                              "rgb_sha256":source.tobytes(),"marked_rgb_sha256":tagged.tobytes()}
            if any(hash_bytes(v)!=record[k] or (k in blobs and blobs[k]!=v) for k,v in expected_blobs.items()):
                raise ValueError(f"Reference pixels/layout/hash mismatch at file-frame {i}")
            y = planes(blobs["i420_sha256"],geometry[0],geometry[1])[0]
            values = measured_frame(y,previous,mask)
            values["boundary"] = any(e["kind"] in BOUNDARIES for e in frame_events(timeline,geometry,i))
            verify_metrics({"file_frame":i,"segment_id":identity["segment_id"],"conditional_loop":False,**values})
            log.append(identity["segment_id"],values)
            if first is None:
                first = y.copy()
            previous = y.copy()
            if spec["status"]=="AVAILABLE":
                result = decode(y,spec)
                if result["status"]!="DECODED" or result["file_frame"]!=i or Fraction(result["pts_numerator"],result["pts_denominator"])!=pts:
                    raise ValueError(f"Marker ambiguity/identity mismatch at {i}")
                markers += 1
        if next(reader,None) is not None or any(f.read(1) for f in (raw,unmarked,rgb,marked_rgb) if f):
            raise ValueError("Unexpected extra reference frames/CSV rows")
        seam = {**measured_frame(first,previous,mask),"boundary":True}
        verify_metrics({"file_frame":0,"segment_id":"__file_loop__","conditional_loop":True,**seam})
        if next(metrics_reader,None) is not None:
            raise ValueError("Unexpected extra measurement CSV rows")
        log.append("__file_loop__",{**seam,"si":None,"entropy_bits":None})
        report = read_json(path("report"))
        expected_report = {"schema":"vsg-media-report-v1","mode":dict(zip(("width","height","fps"),geometry)),
            "frame_count":timeline.frame_count,"raw_sha256":row["sha256"],"raw_bytes":geometry[0]*geometry[1]*3//2*timeline.frame_count,
            "algorithm":ALGORITHM,"analysis_roi":spec["content_roi"],"summary":log.summary(),
            "segments":{s.id:log.summary(s.id) for s in timeline.segments},"loop_seam":{**seam,"conditional_on_consumer_loop":True}}
        if any(report.get(k)!=v for k,v in expected_report.items()):
            raise ValueError("Measurement summary/report differs from verified source pixels and fixed ROI")
    expected_audio = {"reference_audio" if (rate,channels)==(48000,1) else f"audio_{rate}_{channels}ch" for rate,channels in audio["formats"]}
    if set(provenance.get("audio",{}))!=expected_audio:
        raise ValueError("Requested audio references are missing from provenance")
    for name,declared in provenance.get("audio",{}).items():
        with wave.open(str(path(name)),"rb") as wav:
            if (wav.getframerate(),wav.getnchannels(),wav.getsampwidth(),wav.getnframes())!=(declared["sample_rate"],declared["channels"],2,declared["sample_count"]):
                raise ValueError("WAV format/sample count differs from declared PCM reference")
            sha = hashlib.sha256()
            for start in range(0,wav.getnframes(),4096):
                count = min(4096,wav.getnframes()-start)
                pcm = wav.readframes(count)
                sha.update(pcm)
                if pcm!=chunk(audio,start,count,wav.getframerate(),wav.getnchannels()):
                    raise ValueError("PCM reference samples differ from deterministic event synthesis")
            if sha.hexdigest()!=declared["pcm_sha256"]:
                raise ValueError("PCM payload SHA256 mismatch")
    roundtrip = verify_lossless(path("reference"),base/row["path"],geometry,timeline.frame_count) if timeline.lossless else {"status":"VERIFIED","kind":"Raw I420"}
    return {"status":"VERIFIED","mode":list(geometry),"frames_rerendered":timeline.frame_count,
            "markers_decoded":markers,"marker_limitations":spec["limitations"],"lossless_roundtrip":roundtrip,
            "audio_references_verified":len(provenance.get("audio",{})),"raw_sha256":digest(base/row["path"])}


def validate(path):
    path = Path(path)
    manifest = validate_manifest(path)
    return {"schema":"vsg-validation-report-v1","status":"VERIFIED",
            "fixtures":[validate_generated(row,path.parent) for row in manifest["fixtures"]],
            "scope":"Source-media integrity and declared reference semantics only; app streaming UNMEASURED"}
