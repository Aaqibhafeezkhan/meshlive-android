import copy
import csv
from pathlib import Path
import subprocess
import sys
import wave

import numpy as np
import pytest

from videostress.cli import main
from videostress.contract import digest, read_json, validate_manifest, write_json
from videostress.generate import estimate, generate
from videostress.model import Timeline
from videostress.output import fresh_output
from videostress.validate import validate
from tools.verify_catalogue import consumer_functions


def timeline(geometry="82x54@7",lossless=False):
    return Timeline({"schema_version":1,"modes":[geometry],"seed":5,"lossless":lossless,
        "timeline":[{"id":"detail","pattern":"native_checkerboard","frames":2,"repeat":2},
                    {"id":"sync","pattern":"visual_audio_sync","frames":2}],
        "audio":{"enabled":True}})


def export(tmp_path,env):
    out = tmp_path/"fresh"
    generate(timeline(),out,env=env)
    return out,out/"raw-fixtures.json"


def test_end_to_end_exact_references_counts_events_hashes_and_audio(tmp_path,env):
    out,manifest = export(tmp_path,env)
    result = validate(manifest)
    assert result["fixtures"][0]["frames_rerendered"]==6
    row = read_json(manifest)["fixtures"][0]
    assert (out/row["path"]).stat().st_size==82*54*3//2*6
    for record in row["sidecars"].values():
        assert digest(out/record["path"])==record["sha256"]
    events = read_json(out/row["sidecars"]["events"]["path"])
    seam = [e for e in events["events"] if e["kind"]=="repeat_seam"][0]
    assert seam["file_frame"]==2 and seam["audio_sample_index_48k"]==2*48000//7
    cut = [e for e in events["events"] if e["kind"]=="hard_cut"][0]
    assert cut["file_frame"]==4
    loop = events["events"][-1]
    assert loop["next_file_frame"]==0 and loop["conditional_on_consumer_loop"] is True
    with (out/row["sidecars"]["frames"]["path"]).open(newline="") as f:
        frames = list(csv.DictReader(f))
    assert [(r["pts_numerator"],r["pts_denominator"]) for r in frames]==[("0","1"),("1","7"),("2","7"),("3","7"),("4","7"),("5","7")]
    assert frames[0]["source_frame_id"]==frames[2]["source_frame_id"]
    with wave.open(str(out/row["sidecars"]["reference_audio"]["path"]),"rb") as f:
        assert f.getnframes()==6*48000//7


@pytest.mark.parametrize("geometry",["160x96@5","176x144@3","176x144@7","82x82@11","192x88@30","2x2@1"])
def test_real_ffv1_native_geometry_fps_pixels_roundtrip(tmp_path,env,geometry):
    if "ffv1" not in env["encoders"]:
        pytest.skip("Optional FFV1 unavailable")
    t = Timeline({"schema_version":1,"modes":[geometry],"timeline":[{"pattern":"color_bars","frames":3}]})
    out = tmp_path/"new"
    report = generate(t,out,env=env)
    assert report["fixtures"][0]["lossless_roundtrip"]["timestamp_tolerance_seconds"]=="0"
    assert validate(out/"raw-fixtures.json")["status"]=="VERIFIED"


def test_fresh_output_and_interrupt_only_registered_unfinished_files(tmp_path,env):
    existing = tmp_path/"existing"
    existing.mkdir()
    (existing/"user.txt").write_text("keep")
    with pytest.raises(FileExistsError):
        generate(timeline(),existing,env=env)
    out = tmp_path/"interrupted"
    def cancel(*args):
        raise KeyboardInterrupt()
    with pytest.raises(KeyboardInterrupt):
        generate(timeline(),out,progress=cancel,env=env)
    assert not out.exists()
    with pytest.raises(KeyboardInterrupt):
        with fresh_output(tmp_path/"owned") as owned:
            owned.file("partial/data").write_bytes(b"unfinished")
            (owned.path/"user-added.txt").write_text("keep")
            raise KeyboardInterrupt()
    assert (tmp_path/"owned"/"user-added.txt").read_text()=="keep"
    assert not (tmp_path/"owned"/"partial").exists()
    assert (existing/"user.txt").read_text()=="keep"


def test_missing_optional_encoder_does_not_substitute(tmp_path,env):
    missing = copy.deepcopy(env)
    missing["encoders"] = []
    t = timeline(lossless=True)
    with pytest.raises(ValueError,match="FFV1"):
        generate(t,tmp_path/"new",env=missing)
    assert not (tmp_path/"new").exists()
    t = timeline(lossless=False)
    generate(t,tmp_path/"raw-only",env=missing)
    assert validate(tmp_path/"raw-only"/"raw-fixtures.json")["status"]=="VERIFIED"


@pytest.mark.parametrize("change",[{"fps":8},{"width":54,"height":82},{"color":{"range":"full","primaries":"bt709","matrix":"bt709","transfer":"bt709","chroma_location":"center"}}])
def test_rehashed_same_byte_size_wrong_geometry_fps_color_rejected(tmp_path,env,change):
    out,manifest = export(tmp_path,env)
    data = read_json(manifest)
    data["fixtures"][0].update(change)
    write_json(manifest,data)
    with pytest.raises(ValueError):
        validate(manifest)


@pytest.mark.parametrize("sidecar",["events","frames","marker_layout","measurements","report"])
def test_rehashed_false_sidecar_semantics_rejected(tmp_path,env,sidecar):
    out,manifest = export(tmp_path,env)
    data = read_json(manifest)
    record = data["fixtures"][0]["sidecars"][sidecar]
    path = out/record["path"]
    if sidecar=="measurements":
        lines = path.read_text().splitlines()
        columns = lines[1].split(",")
        columns[2] = "99999"
        lines[1] = ",".join(columns)
        path.write_text("\n".join(lines)+"\n")
    elif sidecar=="frames":
        path.write_text(path.read_text().replace(",0,1,",",0,2,",1))
    else:
        doc = read_json(path)
        if sidecar=="events":
            doc["events"][0]["audio_sample_index_48k"] = 1
        elif sidecar=="marker_layout":
            doc["content_roi"]["exclude_xywh"] = []
        else:
            doc["summary"]["si"]["median"] = 99999
        write_json(path,doc)
    record["sha256"] = digest(path)
    write_json(manifest,data)
    with pytest.raises(ValueError):
        validate(manifest)


def test_rehashed_wrong_planar_layout_is_not_verified(tmp_path,env):
    out,manifest = export(tmp_path,env)
    data = read_json(manifest)
    row = data["fixtures"][0]
    raw = out/row["path"]
    a = bytearray(raw.read_bytes())
    # Same-size NV12-like interleaving is not I420 despite the declaration/hash.
    frame_size = 82*54*3//2
    y_size = 82*54
    for offset in range(0,len(a),frame_size):
        u = a[offset+y_size:offset+y_size+y_size//4]
        v = a[offset+y_size+y_size//4:offset+frame_size]
        a[offset+y_size:offset+frame_size] = bytes(value for pair in zip(u,v) for value in pair)
    # This timeline is grayscale, so force one incorrect chroma sample as well.
    a[y_size] = 0
    raw.write_bytes(a)
    row["sha256"] = digest(raw)
    for record in row["sidecars"].values():
        if record["path"]==row["path"]:
            record["sha256"] = row["sha256"]
    write_json(manifest,data)
    with pytest.raises(ValueError,match="pixels/layout/hash"):
        validate(manifest)


def test_readonly_real_consumer_contract_functions_accept_output(tmp_path,env):
    source = Path(r"C:\StudioProjects\Bluetoothtest\tools\bind_benchmark_inputs.py")
    if not source.exists():
        pytest.skip("Consumer file is external; verification script accepts --consumer path")
    before = digest(source)
    # Compile only pure contract/path functions; no app imports, binder main or device operations.
    namespace = consumer_functions(source)
    out,manifest = export(tmp_path,env)
    index = namespace["fixture_index"](read_json(manifest))
    assert list(index)==[(82,54,7)]
    verified = namespace["validate_fixture"](index[(82,54,7)],out)
    assert verified["bytes"]==82*54*3//2*6 and verified["sidecars"]
    assert digest(source)==before


def test_estimate_selects_only_requested_modes_and_native_sizes():
    t = timeline()
    plan = estimate(t)
    assert len(plan["modes"])==1
    assert plan["modes"][0]["primary_i420_bytes"]==82*54*3//2*6
    assert plan["modes"][0]["duration"]=={"numerator":6,"denominator":7}


def test_cli_list_group_and_errors(capsys,tmp_path):
    assert main(["list","--group","timing"])==0
    assert '"p40"' in capsys.readouterr().out
    assert main(["generate","--pattern","p01","--frames","1","--output",str(tmp_path/"missing-mode")])==2
    assert "Select at least one mode" in capsys.readouterr().err


def test_cancellation_stops_real_owned_subprocess(monkeypatch):
    from videostress import media
    real_popen = subprocess.Popen
    captured = []
    def launch(*args,**kwargs):
        process = real_popen(*args,**kwargs)
        captured.append(process)
        communicate = process.communicate
        calls = [0]
        def interrupt(*args,**kwargs):
            calls[0] += 1
            if calls[0]==1:
                raise KeyboardInterrupt()
            return communicate(*args,**kwargs)
        process.communicate = interrupt
        return process
    monkeypatch.setattr(media.subprocess,"Popen",launch)
    try:
        with pytest.raises(KeyboardInterrupt):
            media.run([sys.executable,"-c","import time; time.sleep(60)"])
        assert len(captured)==1 and captured[0].poll() is not None
    finally:
        for process in captured:
            if process.poll() is None:
                process.kill()
                process.wait()
