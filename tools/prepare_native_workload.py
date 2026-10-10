"""Plan or prepare one frozen native workload offline; no phone or settings work."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from benchmark_layout import output_path

# The in-repo test-video generator (tools/video-stress-generator); VIDEO_STRESS_GENERATOR overrides it.
PRODUCER = Path(os.environ.get("VIDEO_STRESS_GENERATOR") or Path(__file__).resolve().parent / "video-stress-generator")
SOURCE_SHA256 = "19c0ef90c4937ab8246976867b29da2069e3e8f5ad00ad1e4cc573effbf618b6"
RECIPES = {
    "sustained": (60,"1d889561f9977ae9b291d88a58e6f155d0159a2caa5fb5b52d2c4da48a25d316"),
    "mosaic": (60,"d965b6ff67e530970930cadfc216b4a493c77524bbb4e070c0624e273ad0621a"),
    "framing": (12,"98ad00b9abc1aeb0faf0d5b4adf5d28bdf1994ee67e75bd5f3190d518b85ac77"),
    # Same four competing panels as "mosaic", 20 s: every content type at once for quick baselines.
    "quick-mosaic": (20,"b136e8f498727bbaab8a4c82597684bae49a285caeec0d92a01a5538842be74b"),
    # All 40 patterns in 20 s: four 5 s ten-panel mosaics (calibration/timing, detail/cuts,
    # motion/fades, entropy/complex), so every run covers the whole generator catalogue.
    "coverage": (20,"e0ce9a6be4979866c042d1511fb0651bf4ec45965b59c0051e42c700e692b2e9"),
}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def frozen_inputs(producer, recipe):
    producer = Path(producer).resolve()
    files = {p.name:digest(p) for p in sorted((producer/"videostress").glob("*.py"))}
    source = hashlib.sha256("\n".join(f"{name}:{sha}" for name,sha in files.items()).encode()).hexdigest()
    if source != SOURCE_SHA256:
        raise ValueError("Producer source differs from the frozen workload implementation")
    recipe_path = producer/"examples"/f"{recipe}.json"
    if digest(recipe_path) != RECIPES[recipe][1]:
        raise ValueError("Recipe differs from its frozen workload declaration")
    return producer, recipe_path


def run_producer(producer, *arguments):
    command = [sys.executable,"-B","-m","videostress",*map(str,arguments)]
    result = subprocess.run(command,cwd=producer,env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1"},
                            capture_output=True,text=True,encoding="utf-8")
    if result.returncode:
        raise ValueError(f"Producer {arguments[0]} failed: {result.stderr.strip()}")
    return json.loads(result.stdout)


def prepare(recipe, mode, action="plan", producer=PRODUCER, output=None, max_bytes=None):
    if recipe not in RECIPES or action not in ("plan","prepare"):
        raise ValueError("Choose a frozen recipe and plan/prepare action")
    parsed = re.fullmatch(r"(\d+)x(\d+)@(\d+)",str(mode).lower())
    if not parsed:
        raise ValueError("Mode must be native WIDTHxHEIGHT@INTEGER_FPS")
    w,h,fps = map(int,parsed.groups())
    if min(w,h,fps) <= 0 or w % 2 or h % 2:
        raise ValueError("Native mode needs positive even dimensions and positive integer FPS")
    mode = f"{w}x{h}@{fps}"
    if max_bytes is not None and (type(max_bytes) is not int or max_bytes <= 0):
        raise ValueError("max_bytes must be a positive integer resource budget")
    if action == "prepare":
        if output is None or max_bytes is None:
            raise ValueError("Preparation requires an explicit output and max_bytes budget")
        output = output_path(output,"inputs")
        if output.exists():
            raise ValueError("Preparation output already exists; use a fresh input identity")
    elif output is not None:
        raise ValueError("Plan does not accept an output or write files")
    producer, recipe_path = frozen_inputs(producer,recipe)
    plan = run_producer(producer,"dry-run","--config",recipe_path,"--mode",mode)
    frozen_inputs(producer,recipe)
    rows = plan.get("modes",[])
    duration = RECIPES[recipe][0]
    if (plan.get("schema") != "vsg-plan-v1" or len(rows) != 1 or
            [rows[0].get(k) for k in ("width","height","fps","frame_count")] != [w,h,fps,duration*fps]):
        raise ValueError("Producer plan differs from the exact selected native mode/duration")
    estimate = plan.get("total_disk_estimate_bytes")
    if type(estimate) is not int or estimate <= 0:
        raise ValueError("Producer plan lacks a positive byte estimate")
    if max_bytes is not None and estimate > max_bytes:
        raise ValueError(f"Estimated {estimate} bytes exceeds the {max_bytes} byte budget; no output written")
    result = {"schema":"benchmark-native-preparation-v1","action":"PLAN","recipe":recipe,
              "recipe_sha256":RECIPES[recipe][1],"producer":str(producer),"producer_source_sha256":SOURCE_SHA256,
              "mode":{"width":w,"height":h,"fps":fps},"frame_count":duration*fps,"duration_seconds":duration,
              "variant_id":plan.get("variant_id"),"disk_estimate_bytes":estimate,"max_bytes":max_bytes,
              "working_frame_memory_estimate_bytes":rows[0].get("working_frame_memory_estimate_bytes"),
              "budget_scope":"Producer disk estimate; scalar sidecars extra. This is not a strict byte quota.",
              "scope":"Native source preparation only; app and physical screen/speaker performance UNMEASURED","phone_operations":0}
    if action == "plan":
        return result
    frozen_inputs(producer,recipe)
    run_producer(producer,"generate","--config",recipe_path,"--mode",mode,"--output",output)
    frozen_inputs(producer,recipe)
    validation = run_producer(producer,"validate",output/"raw-fixtures.json")
    frozen_inputs(producer,recipe)
    if validation.get("status") != "VERIFIED":
        raise ValueError("Generated input validation is incomplete")
    result.update(action="PREPARED",output=str(output),manifest_sha256=digest(output/"raw-fixtures.json"),
                  validation=validation)
    report_path = output/"report.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("variant_id") != result["variant_id"]:
        raise ValueError("Generated variant differs from the approved resource plan")
    report["preparation"] = result
    report_path.write_text(json.dumps(report,indent=2,allow_nan=False)+"\n",encoding="utf-8")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",nargs="?",default="plan",choices=("plan","prepare"))
    parser.add_argument("--recipe",required=True,choices=sorted(RECIPES))
    parser.add_argument("--mode",required=True)
    parser.add_argument("--producer",type=Path,default=PRODUCER)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--max-bytes",type=int)
    args = parser.parse_args(argv)
    try:
        result = prepare(args.recipe,args.mode,args.action,args.producer,args.output,args.max_bytes)
        print(json.dumps(result,indent=2,allow_nan=False))
        return 0
    except (ValueError,OSError) as error:
        parser.exit(2,f"Native preparation blocked: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
