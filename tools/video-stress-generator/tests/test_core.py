import copy
from fractions import Fraction

import numpy as np
import pytest

from videostress.color import planes, rgb_to_i420
from videostress.contract import digest, validate_manifest, write_json, requirements
from videostress.model import Timeline, parse_mode


def config():
    return {"schema_version":1,"seed":7,"modes":["176x144@7"],
            "timeline":[{"pattern":"color_bars","frames":3,"repeat":2}]}


def fixture(tmp_path):
    raw = tmp_path / "source.yuv"
    raw.write_bytes(rgb_to_i420(np.zeros((4,6,3),np.uint8)))
    row = {"width":6,"height":4,"fps":3,"format":"I420","bit_depth":8,
           "frame_count":1,"path":raw.name,"sha256":digest(raw),
           "color":dict.fromkeys(("range","primaries","matrix","transfer","chroma_location"),"UNSPECIFIED")}
    return {"schema_version":1,"fixtures":[row]}


def test_native_conversion_primaries_and_plane_order():
    expected = [(16,128,128),(235,128,128),(63,102,240),(173,42,26),(32,240,118)]
    for color, values in zip([(0,0,0),(255,255,255),(255,0,0),(0,255,0),(0,0,255)],expected):
        data = rgb_to_i420(np.full((4,6,3),color,np.uint8))
        assert len(data) == 36
        assert [int(p[0,0]) for p in planes(data,6,4)] == list(values)
        assert all(np.all(p == v) for p,v in zip(planes(data,6,4),values))


def test_centered_chroma_averages_before_rounding():
    a = np.zeros((2,2,3),np.uint8)
    a[0,0] = [255,0,0]
    y,u,v = planes(rgb_to_i420(a),2,2)
    assert y.tolist() == [[63,16],[16,16]]
    assert (int(u[0,0]),int(v[0,0])) == (122,156)


def test_seek_repeat_native_geometry_and_rational_pts():
    t = Timeline(config())
    assert t.frame_count == 6
    sequential = [t.frame(t.modes[0],i) for i in range(6)]
    for i in [5,1,4,0,3,2]:
        assert np.array_equal(t.frame(t.modes[0],i),sequential[i])
        assert sequential[i].shape == (144,176,3)
        assert t.identity(t.modes[0],i)["pts"] == Fraction(i,7)
    assert t.identity(t.modes[0],0)["source_frame_id"] == t.identity(t.modes[0],3)["source_frame_id"]
    assert t.identity(t.modes[0],0)["frame_id"] != t.identity(t.modes[0],3)["frame_id"]
    assert Timeline(config()).variant == t.variant


@pytest.mark.parametrize("text",["1x2@3","2x3@7","2x2@0","2x2@30000/1001","2x2@-5"])
def test_bad_modes(text):
    with pytest.raises(ValueError):
        parse_mode(text)


@pytest.mark.parametrize("change",[
    {"timeline":[]},{"modes":[]},{"seed":True},{"lossless":"yes"},
    {"timeline":[{"pattern":"missing","frames":2}]},
    {"timeline":[{"pattern":"color_bars","frames":0}]},
    {"timeline":[{"pattern":"color_bars","frames":2,"params":{"wrong":1}}]},
    {"modes":["176x144@7","176x144@7"]},{"pixel_format":"yuv420p10le"}])
def test_bad_timelines(change):
    with pytest.raises(ValueError):
        Timeline({**config(),**change})


def test_contract_valid_and_tampering(tmp_path):
    doc = fixture(tmp_path)
    path = tmp_path/"raw-fixtures.json"
    write_json(path,doc)
    assert validate_manifest(path) == doc
    (tmp_path/"source.yuv").write_bytes(b"broken")
    with pytest.raises(ValueError,match="SHA256"):
        validate_manifest(path)


@pytest.mark.parametrize("change",[
    {"fps":0},{"fps":7.5},{"width":5},{"height":True},{"format":"NV12"},
    {"bit_depth":10},{"bit_depth":True},{"frame_count":2},{"width":8},
    {"sha256":"invalid"},{"color":{}},{"path":"../source.yuv"},{"sidecars":[]}])
def test_contract_negative(tmp_path,change):
    doc = fixture(tmp_path)
    doc["fixtures"][0].update(change)
    write_json(tmp_path/"raw-fixtures.json",doc)
    with pytest.raises(ValueError):
        validate_manifest(tmp_path/"raw-fixtures.json")


def test_duplicate_rows_and_truncation_with_correct_hash(tmp_path):
    doc = fixture(tmp_path)
    doc["fixtures"].append(copy.deepcopy(doc["fixtures"][0]))
    write_json(tmp_path/"raw-fixtures.json",doc)
    with pytest.raises(ValueError,match="duplicate"):
        validate_manifest(tmp_path/"raw-fixtures.json")
    doc["fixtures"].pop()
    raw = tmp_path/"source.yuv"
    raw.write_bytes(raw.read_bytes()[:-1])
    doc["fixtures"][0]["sha256"] = digest(raw)
    write_json(tmp_path/"raw-fixtures.json",doc)
    with pytest.raises(ValueError,match="Truncated"):
        validate_manifest(tmp_path/"raw-fixtures.json")


def test_new_requirements_no_phone_hardcoding(tmp_path):
    path = tmp_path/"new.json"
    write_json(path,{"schema":"benchmark-input-requirements-v1","dimensions":[
        {"width":82,"height":54,"fps":11,"source_phones":["future-device"],"frame_bytes":6642}]})
    assert requirements(path) == {(82,54,11)}


@pytest.mark.parametrize("bad",[[1,2],"176x144@7",[{"width":2,"height":2,"fps":True}], [1]])
def test_malformed_mode_collection(bad):
    with pytest.raises(ValueError):
        Timeline({**config(),"modes":bad})


def test_nonobject_manifest_has_actionable_error(tmp_path):
    path = tmp_path/"wrong.json"
    path.write_text("[]")
    with pytest.raises(ValueError,match="JSON object"):
        validate_manifest(path)
