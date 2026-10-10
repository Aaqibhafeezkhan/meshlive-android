"""Command-line entry points: no phone/network/device orchestration."""

import argparse
import json
from pathlib import Path
import signal
import sys
import time

from . import __version__
from .contract import read_json, requirements
from .generate import estimate, generate
from .media import environment
from .model import PRESETS, Timeline
from .patterns import PATTERNS
from .validate import validate


def parser():
    p = argparse.ArgumentParser(prog="vsg",description="Deterministic native SDR source-media generator; Android streaming remains UNMEASURED")
    p.add_argument("--version",action="version",version=__version__)
    commands = p.add_subparsers(dest="command",required=True)
    catalogue = commands.add_parser("list",help="List original patterns, groups, presets and selected consumer requirements")
    catalogue.add_argument("--group",choices=sorted({p["group"] for p in PATTERNS.values()}))
    catalogue.add_argument("--requirements",type=Path)
    catalogue.add_argument("--environment",action="store_true",help="Include tools/libraries/encoders/filters/license inventory")
    for name in ("dry-run","generate"):
        q = commands.add_parser(name,help="Estimate only" if name=="dry-run" else "Generate a new native variant with references")
        source = q.add_mutually_exclusive_group(required=True)
        source.add_argument("--config",type=Path,help="JSON timeline")
        source.add_argument("--pattern",help="Single pattern name, p01..p40 or framing_calibration")
        q.add_argument("--mode",action="append",help="WIDTHxHEIGHT@INTEGER_FPS or preset; repeat to select modes")
        q.add_argument("--frames",type=int,help="Single-pattern integer frame count")
        q.add_argument("--seconds",help="Single-pattern exact duration; mutually exclusive with --frames")
        q.add_argument("--reference-mode",choices=["full","native"],help="Keep full RGB references (default), or only native I420 plus an optional one-frame calibration RGB reference")
        q.add_argument("--seed",type=int,help="Single-pattern unsigned 64-bit seed")
        q.add_argument("--params",help="Single-pattern JSON parameter object")
        q.add_argument("--requirements",type=Path,help="Check selected modes against any requirements JSON; never renders all rows automatically")
        if name=="generate":
            q.add_argument("--output",required=True,type=Path,help="Unused output directory; no overwrite")
    check = commands.add_parser("validate",help="Verify hashes, file contract, exact reference pixels, sidecars and audio")
    check.add_argument("manifest",type=Path)
    check.add_argument("--report",type=Path,help="Optional fresh JSON report file")
    analysis = commands.add_parser("analyze",help="Selected software diagnostic encode and/or matching camera-reference SI/TI")
    analysis.add_argument("manifest",type=Path)
    analysis.add_argument("--output",type=Path,required=True)
    analysis.add_argument("--mode")
    analysis.add_argument("--codec",choices=["libx264"])
    analysis.add_argument("--allocation",choices=["abr","capped"])
    analysis.add_argument("--bitrate",type=int,help="Explicit target bits/second")
    analysis.add_argument("--buffer-bits",type=int,help="Capped allocation buffer; default=one second of target bitrate")
    analysis.add_argument("--natural-reference",type=Path,help="Supplied native 8-bit I420 container at matching geometry/FPS/count/PTS")
    analysis.add_argument("--natural-range",choices=["limited","full"],help="Source-evidenced override for unspecified range")
    analysis.add_argument("--natural-cuts",type=Path,help="Declared vsg-natural-cuts-v1 cut_frames JSON")
    return p


def timeline_args(args):
    if args.config:
        if any(v is not None for v in (args.frames,args.seconds,args.seed,args.params)):
            raise ValueError("--frames/--seconds/--seed/--params apply only to --pattern; edit the timeline JSON")
        config = read_json(args.config)
    else:
        if (args.frames is None)==(args.seconds is None):
            raise ValueError("--pattern requires exactly one of --frames or --seconds; generation is always explicitly bounded")
        config = {"schema_version":1,"seed":args.seed if args.seed is not None else 0,
                  "timeline":[{"pattern":args.pattern,**({"frames":args.frames} if args.frames is not None else {"seconds":args.seconds}),"params":json.loads(args.params) if args.params else {}}]}
    if args.reference_mode:
        config["reference_mode"] = args.reference_mode
    timeline = Timeline(config,args.mode)
    if args.requirements:
        allowed = requirements(args.requirements)
        missing = set(timeline.modes)-allowed
        if missing:
            raise ValueError(f"Selected native modes not in requirements: {sorted(missing)}; no substitution")
    return timeline


def emit(value):
    print(json.dumps(value,indent=2,allow_nan=False))


def _main(argv=None):
    args = parser().parse_args(argv)
    try:
        if args.command=="list":
            result = {"schema":"vsg-catalogue-v1","patterns":[{k:v for k,v in p.items() if k!="render"}
                for p in PATTERNS.values() if args.group is None or p["group"]==args.group],"presets":PRESETS}
            if args.requirements:
                result["consumer_modes"] = sorted(requirements(args.requirements))
            if args.environment:
                result["environment"] = environment()
            emit(result)
        elif args.command in ("dry-run","generate"):
            timeline = timeline_args(args)
            env = environment()
            plan = estimate(timeline,env)
            if args.command=="dry-run":
                emit({**plan,"environment":env})
            else:
                print(f"Selected {len(timeline.modes)} mode(s), frame counts {[m['frame_count'] for m in plan['modes']]}; estimated disk {plan['total_disk_estimate_bytes']:,} bytes",file=sys.stderr)
                last = [0.0]
                def progress(geometry,done,count):
                    now = time.monotonic()
                    if done==count or now-last[0]>=1:
                        print(f"{geometry[0]}x{geometry[1]}@{geometry[2]}: {done}/{count} frames",file=sys.stderr,flush=True)
                        last[0] = now
                result = generate(timeline,args.output,progress,env)
                emit({"status":result["status"],"variant_id":result["variant_id"],"manifest":str(args.output/"raw-fixtures.json"),
                      "manifest_sha256":result["manifest_sha256"],"fixtures":len(result["fixtures"]),"report":str(args.output/"report.json")})
        elif args.command=="validate":
            if args.report and args.report.exists():
                raise FileExistsError(f"Fresh report path required: {args.report}")
            result = validate(args.manifest)
            if args.report:
                with args.report.open("x",encoding="utf-8") as f:
                    json.dump(result,f,indent=2,allow_nan=False)
                    f.write("\n")
            emit(result)
        else:
            from .analyze import analyze
            result = analyze(args.manifest,args.output,args.mode,args.codec,args.allocation,args.bitrate,args.buffer_bits,
                             args.natural_reference,args.natural_range,args.natural_cuts)
            emit({"status":"ANALYZED","mode":result["mode"],"report":str(args.output/"report.json"),
                  "app_streaming_performance":"UNMEASURED"})
        return 0
    except KeyboardInterrupt:
        print("Interrupted; registered unfinished output files were cleaned up. Existing/user-created files are preserved.",file=sys.stderr)
        return 130
    except (ValueError,OSError) as error:
        print(f"error: {error}",file=sys.stderr)
        return 2


def main(argv=None):
    def cancel(signum,frame):
        raise KeyboardInterrupt()
    previous = signal.signal(signal.SIGTERM,cancel)
    try:
        return _main(argv)
    finally:
        signal.signal(signal.SIGTERM,previous)
