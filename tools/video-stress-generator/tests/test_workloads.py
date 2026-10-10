import ast
from fractions import Fraction
from pathlib import Path

import numpy as np
import pytest

from videostress.audio import chunk, schedule
from videostress.color import planes, rgb_to_i420
from videostress.composition import calibration_chart
from videostress.contract import digest, read_json, write_json
from videostress.generate import estimate, generate, marker_plan
from videostress.events import event_document
from videostress.marker import content_mask, decode, mark
from videostress.model import Timeline
from videostress.patterns import render
from videostress.validate import validate


def config():
    return {"schema_version":1,"modes":["160x144@5"],"lossless":False,"reference_mode":"native",
            "timeline":[{"id":"motion","pattern":"crossing_objects","seconds":3}]}


def test_seconds_are_constant_across_selected_fps_and_roundtrip():
    c = config()
    c["modes"] = ["160x144@5","160x144@15","160x144@30","160x144@60"]
    t = Timeline(c)
    assert [m["frame_count"] for m in estimate(t)["modes"]]==[15,45,90,180]
    assert all(m["duration"]=={"numerator":3,"denominator":1} for m in estimate(t)["modes"])
    assert Timeline(t.config).variant==t.variant
    for geometry in t.modes:
        assert t.identity(geometry,2*geometry[2])["pts"]==Fraction(2)
        assert np.array_equal(t.frame(geometry,geometry[2]),t.frame(t.modes[0],5))


@pytest.mark.parametrize("seconds",[0,-1,True,"nan","1/7",{},"1/0"])
def test_invalid_or_unrepresentable_seconds_never_round(seconds):
    c=config()
    c["timeline"][0]["seconds"]=seconds
    with pytest.raises(ValueError):
        Timeline(c)


def test_seconds_and_frames_cannot_silently_override_each_other():
    c=config()
    c["timeline"][0]["frames"]=10
    with pytest.raises(ValueError,match="exactly one"):
        Timeline(c)


def test_sustained_animation_does_not_reset_to_half_second():
    c=config()
    c["timeline"][0]["seconds"]=60
    t=Timeline(c)
    g=t.modes[0]
    assert not np.array_equal(t.frame(g,150),t.frame(g,0))
    assert t.identity(g,150)["source_frame"]==150
    sequential={i:t.frame(g,i) for i in (0,15,150,299)}
    for i in (299,150,0,15):
        assert np.array_equal(t.frame(g,i),sequential[i])


@pytest.mark.parametrize("grid",[[2,2],[4,2],[5,2]])
def test_native_panels_have_even_exact_bounds_without_resizing(grid):
    c=config()
    c["modes"]=["182x146@5"]
    c["marker"]={"enabled":False}
    c["timeline"]=[{"id":"mixed","seconds":3,"grid":grid,"panels":[
        {"id":f"panel-{i}","pattern":"native_checkerboard","seed":i,"params":{"cell":1+i%2}}
        for i in range(grid[0]*grid[1])]}]
    t=Timeline(c)
    image=t.frame(t.modes[0],7)
    covered=np.zeros(image.shape[:2],np.uint8)
    for panel in t.segments[0].panels:
        x,y,w,h=panel["bounds_xywh"]
        assert all(v%2==0 for v in (x,y,w,h))
        assert np.array_equal(image[y:y+h,x:x+w],render(panel["pattern"],w,h,5,7,panel["seed"],panel["params"]))
        covered[y:y+h,x:x+w]+=1
    assert np.all(covered==1)


@pytest.mark.parametrize("bounds",[[1,0,80,72],[0,0,81,72],[0,0,162,72],[-2,0,80,72],[0,0,0,72]])
def test_invalid_native_panel_bounds_rejected_before_export(bounds):
    c=config()
    c["timeline"]=[{"seconds":3,"panels":[{"id":"one","pattern":"p14","bounds":bounds}]}]
    with pytest.raises(ValueError,match="bounds"):
        Timeline(c)


def test_overlapping_panels_and_duplicate_ids_rejected():
    c=config()
    panel={"id":"one","pattern":"p14","bounds":[0,0,80,72]}
    c["timeline"]=[{"seconds":3,"panels":[panel,{**panel,"id":"two"}]}]
    with pytest.raises(ValueError,match="overlap"):
        Timeline(c)
    c["timeline"][0]["panels"][1]["id"]="one"
    with pytest.raises(ValueError,match="unique"):
        Timeline(c)


def test_panel_local_sync_and_marker_events_are_not_lost(tmp_path,env):
    c=config()
    c["modes"]=["320x288@5"]
    c["timeline"]=[{"id":"mixed","seconds":1,"grid":[2,1],"panels":[
        {"id":"sync","pattern":"p39","params":{"interval_frames":2}},
        {"id":"marker","pattern":"p40"}]}]
    c["audio"]={"enabled":True}
    t=Timeline(c)
    audio=schedule(t,t.modes[0])
    visual=[e for e in event_document(t,t.modes[0],audio)["events"] if e["kind"]=="visual_sync"]
    assert [e["cue_id"] for e in visual]==[e["id"] for e in audio["events"]]
    out=tmp_path/"panels"
    report=generate(t,out,env=env)
    assert report["fixtures"][0]["marker"]["intrinsic_decoded_frames"]==5
    assert validate(out/"raw-fixtures.json")["status"]=="VERIFIED"


def test_unique_visual_audio_cues_have_native_mask_and_exact_sample_anchors():
    c=config()
    c["timeline"][0]["seconds"]=60
    c["audio"]={"enabled":True}
    c["cues"]={"interval_seconds":3,"on_frames":2,"duration_samples":4800}
    t=Timeline(c)
    g=t.modes[0]
    audio=schedule(t,g)
    assert audio["sample_count"]==60*48000
    assert len(audio["events"])==20
    assert len({(e["frequency"],e["end_frequency"]) for e in audio["events"]})==20
    mask=content_mask(marker_plan(t,g))
    x,y,w,h=t.cues["bounds_xywh"]
    assert not mask[y:y+h,x:x+w].any()
    for visual,acoustic in zip(t.cues["events"],audio["events"]):
        assert visual["id"]==acoustic["id"]
        assert acoustic["sample_index"]==visual["file_frame"]*48000//g[2]
        assert acoustic["sample_count"]==4800
    assert np.all(t.frame(g,0)[y:y+h,x:x+w]==255)
    assert np.all(t.frame(g,1)[y:y+h,x:x+w]==255)
    assert np.all(t.frame(g,2)[y:y+h,x:x+w]==0)
    assert chunk(audio,0,10000)==chunk(audio,0,3333)+chunk(audio,3333,6667)


def test_calibration_and_shifted_marker_match_actual_consumer_chart_validation():
    c=config()
    c["timeline"]=[{"id":"framing","pattern":"framing_calibration","seconds":3}]
    c["cues"]={"interval_seconds":3}
    t=Timeline(c)
    g=t.modes[0]
    spec=marker_plan(t,g)
    tagged=mark(t.frame(g,0),0,g[2],spec)
    assert spec["bounds_xywh"][:2] != [0,0]
    assert decode(planes(rgb_to_i420(tagged),g[0],g[1])[0],spec)["status"]=="DECODED"
    consumer=Path(r"C:\StudioProjects\Bluetoothtest\tools\score_screen_geometry.py")
    if not consumer.exists():
        pytest.skip("External geometry consumer not present")
    names={"_validate_chart","_integer","_number","_rectangle","_rgb"}
    tree=ast.parse(consumer.read_text(encoding="utf-8-sig"))
    nodes=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in names]
    namespace={"np":np,"math":__import__("math")}
    exec(compile(ast.Module(body=nodes,type_ignores=[]),str(consumer),"exec"),namespace)
    assert len(namespace["_validate_chart"](calibration_chart(g)[1],tagged))==6


def test_native_only_multi_fps_export_retains_full_hash_identity_without_rgb_duplicates(tmp_path,env):
    c=config()
    c["modes"]=["82x54@5","82x54@7"]
    c["timeline"][0]["seconds"]=1
    out=tmp_path/"native"
    generate(Timeline(c),out,env=env)
    assert validate(out/"raw-fixtures.json")["status"]=="VERIFIED"
    rows=read_json(out/"raw-fixtures.json")["fixtures"]
    assert [r["frame_count"] for r in rows]==[5,7]
    for row in rows:
        assert "rgb_reference" not in row["sidecars"] and "marked_rgb" not in row["sidecars"]
        assert {"reference","unmarked_reference","frames","marker_layout"} <= row["sidecars"].keys()
    native=estimate(Timeline(c))["total_disk_estimate_bytes"]
    c["reference_mode"]="full"
    assert estimate(Timeline(c))["total_disk_estimate_bytes"]>native


@pytest.mark.parametrize("kind",["panels","calibration","calibration_reference"])
def test_rehashed_false_composition_metadata_and_pixels_rejected(tmp_path,env,kind):
    c=config()
    if kind=="panels":
        c["timeline"]=[{"seconds":1,"grid":[2,2],"panels":[{"id":str(i),"pattern":"p14"} for i in range(4)]}]
    else:
        c["timeline"]=[{"pattern":"framing_calibration","seconds":1}]
    out=tmp_path/"source"
    generate(Timeline(c),out,env=env)
    manifest=out/"raw-fixtures.json"
    doc=read_json(manifest)
    record=doc["fixtures"][0]["sidecars"][kind]
    path=out/record["path"]
    if kind=="calibration_reference":
        data=bytearray(path.read_bytes())
        data[0]=0
        path.write_bytes(data)
    else:
        data=read_json(path)
        if kind=="panels":
            data["segments"][0]["panels"][0]["bounds_xywh"][2]-=2
        else:
            data["square"]["bounds"][2]-=2
        write_json(path,data)
    record["sha256"]=digest(path)
    write_json(manifest,doc)
    with pytest.raises(ValueError):
        validate(manifest)


def test_short_recipes_declare_one_minute_stress_and_twelve_second_calibration():
    base=Path(__file__).resolve().parents[1]/"examples"
    for name,duration,cues in (("sustained",60,20),("mosaic",60,20),("framing",12,4)):
        timeline=Timeline(read_json(base/f"{name}.json"))
        assert timeline.frame_count==duration*30
        assert len([e for e in schedule(timeline,timeline.modes[0])["events"] if e.get("visual_sync")])==cues
        timeline.frame(timeline.modes[0],timeline.frame_count-1)
