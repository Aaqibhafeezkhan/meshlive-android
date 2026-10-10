import numpy as np
import pytest

from videostress.color import planes, rgb_to_i420
from videostress.metrics import measure, summarize
from videostress.model import Timeline
from videostress.patterns import PATTERNS, render


COMPLEX_NAMES = [p["name"] for p in PATTERNS.values() if p["group"] == "complex"]


@pytest.mark.parametrize("name",COMPLEX_NAMES)
def test_complex_independent_change_and_seed(name):
    a = render(name,160,96,7,1,seed=22)
    b = render(name,160,96,7,5,seed=22)
    assert np.mean(a!=b)>0.02
    assert not np.array_equal(a,render(name,160,96,7,1,seed=23))
    y = planes(rgb_to_i420(b),160,96)[0]
    prev = planes(rgb_to_i420(a),160,96)[0]
    assert measure(y,prev)["mean_absolute_delta"]>0.1


@pytest.mark.parametrize("name",COMPLEX_NAMES)
@pytest.mark.parametrize("control,low,high",[("density",0.5,2.0),("speed",0.2,1.7),("detail",0.5,2.0),("entropy",0.0,1.0)])
def test_complex_controls_change_measured_pixels(name,control,low,high):
    rows = []
    signatures = []
    for value in [low,high]:
        previous = None
        collected = []
        signature = []
        for i in range(6):
            y = planes(rgb_to_i420(render(name,160,96,7,i,33,{control:value})),160,96)[0]
            signature.append(y.tobytes())
            collected.append(measure(y,previous))
            previous = y
        rows.append(summarize(collected))
        signatures.append(b"".join(signature))
    assert signatures[0] != signatures[1]
    assert any(abs(rows[0][metric]["median"]-rows[1][metric]["median"])>1e-5
               for metric in ["si","ti_including_cuts_seams","mean_absolute_delta","entropy_bits"])


def test_flashing_default_and_explicit_gate():
    config = {"schema_version":1,"modes":["160x96@30"],
              "timeline":[{"pattern":"luminance_transitions","frames":5,
                           "params":{"hold_frames":1,"full_screen":True}}]}
    with pytest.raises(ValueError,match="flashing"):
        Timeline(config)
    config["timeline"][0]["params"]["allow_high_rate_flash"] = True
    Timeline(config)
    default = render("luminance_transitions",160,96,30,0)
    assert np.any(default[:,:,0] == 80)


def test_cut_does_not_claim_sustained_ti():
    y = np.zeros((10,10),np.uint8)+16
    other = y.copy()
    other[:5] = 235
    row = {**measure(other,y),"boundary":True}
    summary = summarize([row])
    assert summary["ti_including_cuts_seams"]["peak"]>0
    assert summary["ti_excluding_cuts_seams"]["status"] == "UNMEASURED"


def test_sobel_and_temporal_algorithm_known_pixels():
    y = np.full((8,8),16,np.uint8)
    current = y.copy()
    current[:,4:] = 235
    measured = measure(current,y)
    assert measured["ti"] == pytest.approx(127.5)
    assert measured["si"] == pytest.approx(np.std([0,0,1020,1020,0,0]))
    assert measure(y,y)["ti"]==0
    assert measure(y)["ti"] is None


def test_roi_excludes_marker_and_its_sobel_stencil():
    y = np.full((20,20),100,np.uint8)
    mask = np.ones((20,20),bool)
    mask[:10,:10] = False
    previous = y.copy()
    y[:10,:10] = 235
    result = measure(y,previous,mask)
    assert result["si"]==0 and result["ti"]==0


@pytest.mark.parametrize("name,a,b",[("precipitation",{"style":"snow"},{"style":"rain"}),
                                    ("independent_particles",{"trails":1},{"trails":4})])
def test_rain_variant_and_optional_particle_trails(name,a,b):
    first = render(name,160,96,7,5,42,a)
    second = render(name,160,96,7,5,42,b)
    assert np.mean(first!=second)>0.03
    y,other = planes(rgb_to_i420(first),160,96)[0],planes(rgb_to_i420(second),160,96)[0]
    assert measure(y)["si"]!=measure(other)["si"]
