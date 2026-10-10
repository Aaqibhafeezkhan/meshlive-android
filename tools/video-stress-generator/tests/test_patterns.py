import hashlib

import numpy as np
import pytest

from videostress.color import planes, rgb_to_i420
from videostress.patterns import PATTERNS, render


@pytest.mark.parametrize("name", list(PATTERNS))
def test_native_seek_all_registered_patterns(name):
    frames = [render(name,82,54,7,i,123) for i in range(4)]
    for i in [3,0,2,1]:
        assert np.array_equal(frames[i],render(name,82,54,7,i,123))
        assert frames[i].shape == (54,82,3)
        assert frames[i].dtype == np.uint8
        assert len(rgb_to_i420(frames[i])) == 6642


def test_patterns_have_distinct_pixel_sequences():
    hashes = []
    for name in PATTERNS:
        # Include internal cuts and held states, rather than only their first hold.
        data = b"".join(render(name,160,96,7,i,12).tobytes() for i in range(18))
        hashes.append(hashlib.sha256(data).hexdigest())
    assert len(set(hashes)) == len(PATTERNS)


@pytest.mark.parametrize("cell",[1,2])
def test_exact_native_detail_and_motion(cell):
    for frame in range(4):
        a = render("native_checkerboard",82,54,3,frame,params={"cell":cell})
        x,y = np.meshgrid(np.arange(82),np.arange(54))
        expected = (((x-frame)//cell+y//cell)%2)*255
        assert np.array_equal(a[:,:,0],expected)
        y,u,v = planes(rgb_to_i420(a),82,54)
        assert set(np.unique(y)) == {16,235}
        assert np.all(u==128) and np.all(v==128)


def test_gradient_has_native_endpoints_and_no_border():
    a = render("grayscale_gradient",82,54,11,0)
    assert np.all(a[:,0]==0) and np.all(a[:,-1]==255)
    assert np.array_equal(a[0],a[-1])
    assert np.all(np.diff(a[0,:,0].astype(int))>=0)


@pytest.mark.parametrize("name",["moving_gradients","rotating_wedges","zone_plate","diagonals_grids",
    "native_checkerboard","pulsing_grid","breathing_circles","zoom_checkerboard","rotating_polygons",
    "independent_bars","bouncing_balls","crossing_objects"])
def test_required_motion_changes_pixels(name):
    assert not np.array_equal(render(name,160,96,7,0,9),render(name,160,96,7,3,9))


@pytest.mark.parametrize("name",list(PATTERNS))
def test_smallest_native_geometry_and_maximum_seed(name):
    for frame in (0,1,6):
        a = render(name,2,2,1,frame,2**64-1)
        assert a.shape==(2,2,3) and len(rgb_to_i420(a))==6
