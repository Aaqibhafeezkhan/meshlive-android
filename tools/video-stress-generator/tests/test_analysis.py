import copy
from pathlib import Path

import numpy as np
import pytest

from videostress.analyze import analyze, encode_diagnostic
from videostress.color import rgb_to_i420
from videostress.contract import read_json
from videostress.generate import generate
from videostress.media import check_native_probe
from videostress.model import Timeline
from videostress.quality import frame_quality


def test_psnr_ssim_identical_and_changed_native_samples():
    rng = np.random.default_rng(77)
    data = rgb_to_i420(rng.integers(0,256,(32,48,3),np.uint8))
    mask = np.ones((32,48),bool)
    a = frame_quality(data,data,48,32,mask)
    assert a["psnr_i420"]=="inf" and a["ssim_y"]==pytest.approx(1)
    corrupt = bytes(np.clip(np.frombuffer(data,np.uint8).astype(int)+5,0,255).astype(np.uint8))
    b = frame_quality(data,corrupt,48,32,mask)
    assert b["psnr_i420"]==pytest.approx(34.15140352195873)
    assert 0<b["ssim_y"]<1


def test_ssim_tiny_roi_not_invented():
    data = rgb_to_i420(np.zeros((4,6,3),np.uint8))
    result = frame_quality(data,data,6,4,np.zeros((4,6),bool))
    assert result["ssim_y"] is None and result["content_psnr_y"] is None


@pytest.mark.parametrize("change",[{"width":54},{"avg_frame_rate":"8/1"},{"pix_fmt":"yuv420p10le"},{"sample_aspect_ratio":"2:1"}])
def test_native_probe_rejects_wrong_reference_geometry_rate_depth(change):
    stream = {"width":82,"height":54,"pix_fmt":"yuv420p","avg_frame_rate":"7/1","sample_aspect_ratio":"1:1"}
    with pytest.raises(ValueError):
        check_native_probe({"streams":[{**stream,**change}]},(82,54,7))


def test_optional_diagnostic_absence_and_no_implicit_allocation(tmp_path,env):
    missing = copy.deepcopy(env)
    missing["encoders"] = []
    with pytest.raises(ValueError,match="absent"):
        encode_diagnostic(tmp_path/"in",tmp_path/"out",(82,54,7),"libx264","abr",123456,None,missing)
    with pytest.raises(ValueError,match="libx264 only"):
        encode_diagnostic(tmp_path/"in",tmp_path/"out",(82,54,7),"libx265","abr",123456,None,env)
    with pytest.raises(ValueError,match="CBR"):
        encode_diagnostic(tmp_path/"in",tmp_path/"out",(82,54,7),"libx264","cbr",123456,None,env)


def test_actual_software_encode_payload_quality_and_matching_reference(tmp_path,env):
    if "libx264" not in env["encoders"] or "ffv1" not in env["encoders"]:
        pytest.skip("Optional encoders unavailable")
    t = Timeline({"schema_version":1,"modes":["160x96@7"],"timeline":[
        {"pattern":"moving_gradients","frames":5},{"pattern":"colored_noise","frames":4}]})
    out = tmp_path/"source"
    generate(t,out,env=env)
    report = analyze(out/"raw-fixtures.json",tmp_path/"analysis",codec="libx264",allocation="capped",bitrate=180000)
    quality = report["diagnostic"]["quality"]
    assert quality["frames_scored"]==9 and quality["ssim_y"]["status"]=="MEASURED"
    payload = report["diagnostic"]["payload"]
    assert payload["packet_payload_bytes"]>0
    assert payload["distributions"]["per_frame_bps"]["count"]==9
    assert payload["distributions"]["100ms_bps"]["count"]==13
    assert payload["distributions"]["1000ms_bps"]["count"]==2
    manifest = read_json(out/"raw-fixtures.json")
    reference = out/manifest["fixtures"][0]["sidecars"]["reference"]["path"]
    unknown = analyze(out/"raw-fixtures.json",tmp_path/"unknown",natural=reference)
    assert unknown["natural_reference"]["status"]=="UNMEASURED"
    matched = analyze(out/"raw-fixtures.json",tmp_path/"matched",natural=reference,natural_range="limited")
    natural = matched["natural_reference"]
    assert natural["status"]=="MEASURED"
    assert natural["synthetic_shortfalls"]["si"]["median"]==pytest.approx(0)
    assert natural["synthetic_shortfalls"]["ti_including_cuts_seams"]["median"]>=0
    assert natural["summary"]["ti_excluding_cuts_seams"]["status"]=="UNMEASURED"


def test_ssim_matches_independent_gaussian_implementation_when_installed():
    skimage = pytest.importorskip("skimage.metrics")
    rng = np.random.default_rng(45)
    data = rgb_to_i420(rng.integers(0,256,(32,48,3),np.uint8))
    altered = bytearray(data)
    altered[15*48:16*48] = bytes([170]*48)
    result = frame_quality(data,bytes(altered),48,32,np.ones((32,48),bool))
    a = np.frombuffer(data,np.uint8)[:32*48].reshape(32,48)
    b = np.frombuffer(bytes(altered),np.uint8)[:32*48].reshape(32,48)
    score = skimage.structural_similarity(a,b,data_range=255,gaussian_weights=True,sigma=1.5,use_sample_covariance=False)
    assert result["ssim_y"]==pytest.approx(score,abs=1e-12)


def test_natural_geometry_mismatch_rejects_and_cleans_output(tmp_path,env):
    if "ffv1" not in env["encoders"]:
        pytest.skip("Optional FFV1 unavailable")
    references = []
    for index,mode in enumerate(["160x96@7","176x144@7"]):
        t = Timeline({"schema_version":1,"modes":[mode],"timeline":[{"pattern":"color_bars","frames":3}]})
        out = tmp_path/str(index)
        generate(t,out,env=env)
        row = read_json(out/"raw-fixtures.json")["fixtures"][0]
        references.append((out,out/row["sidecars"]["reference"]["path"]))
    with pytest.raises(ValueError,match="geometry"):
        analyze(references[0][0]/"raw-fixtures.json",tmp_path/"bad",natural=references[1][1],natural_range="limited")
    assert not (tmp_path/"bad").exists()
