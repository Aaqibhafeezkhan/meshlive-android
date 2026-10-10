from fractions import Fraction
import json
from pathlib import Path
import wave

import numpy as np
import pytest

from videostress.audio import chunk, schedule, sample_at, write_wav
from videostress.color import planes, rgb_to_i420
from videostress.marker import bits, decode, layout, mark
from videostress.model import Timeline


@pytest.mark.parametrize("width,height,fps",[(160,96,5),(176,144,3),(176,144,7),(82,54,11),(52,50,30),(94,94,8)])
def test_native_marker_roundtrip_rgb_and_actual_i420(width,height,fps):
    spec = layout(width,height)
    for frame in [0,1,13,65539]:
        a = mark(np.zeros((height,width,3),np.uint8),frame,fps,spec)
        for source in [a,planes(rgb_to_i420(a),width,height)[0]]:
            result = decode(source,spec)
            assert result["status"]=="DECODED"
            assert result["file_frame"]==frame
            assert Fraction(result["pts_numerator"],result["pts_denominator"])==Fraction(frame,fps)


def test_marker_corrects_one_cell_but_rejects_uncorrectable_or_ambiguous():
    spec = layout(160,96)
    original = mark(np.zeros((96,160,3),np.uint8),19,7,spec)
    damaged = original.copy()
    damaged[2:4,2:4] = 255-damaged[2:4,2:4]
    result = decode(damaged,spec)
    assert result["status"]=="DECODED" and result["corrected_triplets"]==1
    damaged[2:4,4:6] = 255-damaged[2:4,4:6]
    assert decode(damaged,spec)["status"]=="AMBIGUOUS"
    damaged = original.copy()
    damaged[8:10,10:12] = 128
    assert decode(damaged,spec)["status"]=="AMBIGUOUS"
    damaged = original.copy()
    damaged[0:2,0:2] = 0
    assert decode(damaged,spec)["status"]=="AMBIGUOUS"


def test_tiny_marker_unmeasured_and_bound_mask_predeclared():
    spec = layout(16,16)
    assert spec["status"]=="UNMEASURED" and spec["bounds_xywh"] is None
    assert decode(np.zeros((16,16)),spec)["status"]=="UNMEASURED"
    with pytest.raises(ValueError):
        layout(160,96,3)


def audio_timeline(kind="beep"):
    return Timeline({"schema_version":1,"modes":["160x96@7"],"seed":77,
        "timeline":[{"pattern":"visual_audio_sync","frames":15,"params":{"interval_frames":7}}],
        "audio":{"enabled":True,"originals":[{"sample_rate":44100,"channels":2}],
                 "events":[{"id":"custom","frame":3,"kind":kind,"duration_samples":2000}]}})


@pytest.mark.parametrize("kind",["beep","chirp","sweep","noise"])
def test_audio_exact_sample_indices_count_hash_and_chunk_seek(tmp_path,kind):
    t = audio_timeline(kind)
    s = schedule(t,t.modes[0])
    assert s["sample_count"]==15*48000//7
    for e in s["events"]:
        assert e["sample_index"]==e["file_frame"]*48000//7
        error = Fraction(**{"numerator":e["quantization_error_seconds"]["numerator"],
                            "denominator":e["quantization_error_seconds"]["denominator"]})
        assert -Fraction(1,48000)<error<=0
    path = tmp_path/"audio.wav"
    a = write_wav(path,s,t.frame_count,7,chunk_size=4096)
    b = write_wav(tmp_path/"other.wav",s,t.frame_count,7,chunk_size=733)
    assert a["pcm_sha256"]==b["pcm_sha256"]
    assert 0<a["peak_pcm"]<=0.4*32767+1
    with wave.open(str(path),"rb") as f:
        assert (f.getframerate(),f.getnchannels(),f.getnframes())==(48000,1,15*48000//7)
        f.setpos(19000)
        assert f.readframes(3000)==chunk(s,19000,3000)
    info = write_wav(tmp_path/"stereo.wav",s,t.frame_count,7,44100,2)
    assert info["sample_count"]==15*44100//7


def test_audio_bad_event_and_optional_disabled():
    t = audio_timeline()
    t.audio["events"][0]["frame"] = 15
    with pytest.raises(ValueError,match="outside"):
        schedule(t,t.modes[0])
    t = audio_timeline()
    t.audio["enabled"] = False
    with pytest.raises(ValueError,match="require"):
        schedule(t,t.modes[0])


def test_audio_rejects_clipping_and_nyquist():
    t = audio_timeline()
    t.audio["events"] = [{"frame":1,"amplitude":0.9},{"frame":1,"amplitude":0.9}]
    with pytest.raises(ValueError,match="clip"):
        schedule(t,t.modes[0])
    t.audio["events"] = [{"frame":1,"frequency":23000}]
    with pytest.raises(ValueError,match="Nyquist"):
        schedule(t,t.modes[0])


@pytest.mark.parametrize("fps",[7,15,30,60])
@pytest.mark.parametrize("recipe",["sustained","mosaic"])
def test_sustained_audio_has_native_sample_duration_and_quiet_drain(recipe,fps):
    cfg=json.loads((Path(__file__).parents[1]/"examples"/(recipe+".json")).read_text())
    t=Timeline(cfg,modes=[f"160x144@{fps}"])
    s=schedule(t,t.modes[0])
    bed=next(e for e in s["events"] if e["id"]=="sustained_audio")
    assert bed["role"]=="workload"
    assert (bed["file_frame"],bed["sample_index"],bed["sample_count"])==(10*fps,480000,1920000)
    assert len([e for e in s["events"] if e.get("visual_sync")])==20
    assert s["sample_count"]==60*48000
    assert not any(chunk(s,5*48000,4096))
    assert not any(chunk(s,56*48000,4096))
    active=np.frombuffer(chunk(s,20*48000,4096),"<i2")
    assert np.count_nonzero(active)>4000
    assert np.max(np.abs(active.astype(np.int32)))<=0.12*32767+1
    assert chunk(s,20*48000,4096)==chunk(s,20*48000,1024)+chunk(s,20*48000+1024,3072)


@pytest.mark.parametrize("anchor",[True,-1,"nan","inf","1/7",{},"1/0"])
def test_audio_seconds_anchor_rejects_invalid_or_unrepresentable_values(anchor):
    t=Timeline({"schema_version":1,"modes":["160x96@30"],"timeline":[{"pattern":"grayscale_gradient","seconds":2}],
        "audio":{"enabled":True,"events":[{"seconds":anchor}]}})
    with pytest.raises(ValueError):
        schedule(t,t.modes[0])


def test_audio_anchor_is_explicit_and_seconds_can_start_at_zero():
    t=audio_timeline()
    for event in ({"frame":1,"seconds":1},{}):
        t.audio["events"]=[event]
        with pytest.raises(ValueError,match="exactly one"):
            schedule(t,t.modes[0])
    t.audio["events"]=[{"seconds":"0","kind":"noise","duration_samples":480}]
    assert schedule(t,t.modes[0])["events"][0]["sample_index"]==0
    t.audio["events"]=[{"seconds":0,"amplitude":0.9},{"seconds":0,"amplitude":0.9}]
    with pytest.raises(ValueError,match="clip"):
        schedule(t,t.modes[0])
    t.audio["events"]=[{"seconds":0,"role":"unknown"}]
    with pytest.raises(ValueError,match="role"):
        schedule(t,t.modes[0])
