"""Native panel geometry, framing chart and declared audiovisual cue overlays."""

from fractions import Fraction
import math

import numpy as np

from .contract import positive_int
from .patterns import parameters, render, resolve


def seconds(value, label="seconds"):
    if type(value) not in (int, float, str) or isinstance(value, bool):
        raise ValueError(f"{label} must be an exact positive decimal or fraction")
    try:
        result = Fraction(str(value))
    except (ValueError, ZeroDivisionError):
        raise ValueError(f"{label} must be an exact positive decimal or fraction") from None
    if result <= 0:
        raise ValueError(f"{label} must be positive")
    return result


def frames_for_seconds(value, fps, label="seconds"):
    count = seconds(value, label) * fps
    if count.denominator != 1:
        raise ValueError(f"{label} must be exactly representable at {fps} FPS; no rounding")
    return positive_int(count.numerator, label)


def descriptor(name):
    if name in ("mosaic", "framing_calibration"):
        return {"id": name, "name": name, "group": "composition" if name == "mosaic" else "calibration"}
    return resolve(name)


def panels_for(row, geometry, seed):
    rows = row.get("panels")
    if not isinstance(rows, list) or not rows:
        raise ValueError("mosaic needs nonempty panels")
    w, h, _ = geometry
    grid = row.get("grid")
    if grid is not None:
        if not isinstance(grid, list) or len(grid) != 2:
            raise ValueError("grid must be [columns,rows]")
        cols, nrows = [positive_int(v, "grid dimension") for v in grid]
        if cols*nrows != len(rows) or cols > w//2 or nrows > h//2:
            raise ValueError("grid must exactly fit the panels and even native cells")
        xs = [2*(i*(w//2)//cols) for i in range(cols+1)]
        ys = [2*(i*(h//2)//nrows) for i in range(nrows+1)]
    result, ids = [], set()
    for i, panel in enumerate(rows):
        if not isinstance(panel, dict) or set(panel)-{"id","pattern","params","seed","bounds"}:
            raise ValueError("Invalid mosaic panel options")
        pid = panel.get("id")
        if not isinstance(pid, str) or not pid or pid in ids:
            raise ValueError("Panel IDs must be nonempty and unique within a segment")
        ids.add(pid)
        p = resolve(panel.get("pattern"))
        bounds = panel.get("bounds")
        if grid is not None:
            if bounds is not None:
                raise ValueError("Declare either a grid or explicit panel bounds")
            x, y = i % cols, i // cols
            bounds = [xs[x], ys[y], xs[x+1]-xs[x], ys[y+1]-ys[y]]
        if not isinstance(bounds, list) or len(bounds) != 4 or any(type(v) is not int or v % 2 for v in bounds):
            raise ValueError("Panel bounds must be even native [x,y,width,height]")
        x, y, pw, ph = bounds
        if x < 0 or y < 0 or pw < 2 or ph < 2 or x+pw > w or y+ph > h:
            raise ValueError("Panel bounds exceed native geometry")
        for previous in result:
            ox, oy, ow, oh = previous["bounds_xywh"]
            if x < ox+ow and ox < x+pw and y < oy+oh and oy < y+ph:
                raise ValueError("Native panel bounds overlap")
        panel_seed = panel.get("seed", (seed+i) % 2**64)
        if type(panel_seed) is not int or not 0 <= panel_seed < 2**64:
            raise ValueError("Panel seed must be unsigned 64-bit integer")
        result.append({"id":pid,"pattern":p["name"],"pattern_id":p["id"],"bounds_xywh":bounds,
                       "seed":panel_seed,"params":parameters(p,panel.get("params",{})),"render_mode":"native"})
    return result


def render_panels(panels, geometry, frame):
    w, h, fps = geometry
    image = np.zeros((h,w,3),np.uint8)
    for panel in panels:
        x, y, pw, ph = panel["bounds_xywh"]
        image[y:y+ph,x:x+pw] = render(panel["pattern"],pw,ph,fps,frame,panel["seed"],panel["params"])
    return image


def calibration_chart(geometry):
    w, h, _ = geometry
    if w < 160 or h < 144:
        raise ValueError("framing_calibration needs native dimensions at least 160x144")
    p = max(6,2*(min(w,h)//24))
    t = 2
    landmarks = [{"id":name,"bounds":[x,y,p,p],"rgb":rgb} for name,x,y,rgb in (
        ("top_left",2*p,2*p,[255,0,0]), ("top_right",w-3*p,2*p,[0,255,0]),
        ("bottom_left",2*p,h-3*p,[0,0,255]), ("bottom_right",w-3*p,h-3*p,[255,255,0]))]
    square = {"bounds":[w//3-p,h//2-p,2*p,2*p],"rgb":[255,0,255]}
    circle = {"center":[2*w//3,h//2],"radius":p,"rgb":[0,255,255]}
    image = np.full((h,w,3),16,np.uint8)
    border = {"thickness":t,"rgb":[128,128,128]}
    image[:t] = image[-t:] = border["rgb"]
    image[:,:t] = image[:,-t:] = border["rgb"]
    for shape in [*landmarks,square]:
        x, y, pw, ph = shape["bounds"]
        image[y:y+ph,x:x+pw] = shape["rgb"]
    cx, cy = circle["center"]
    yy, xx = np.mgrid[:h,:w]
    image[(xx+0.5-cx)**2+(yy+0.5-cy)**2 <= p*p] = circle["rgb"]
    return image,{"width":w,"height":h,"pixel_aspect":[1,1],"border":border,
                  "landmarks":landmarks,"square":square,"circle":circle}


def cue_plan(config, geometry, frame_count):
    if not config:
        return None
    allowed = {"interval_seconds","on_frames","duration_samples","amplitude","frequency","frequency_step","end_frequency_offset"}
    if not isinstance(config,dict) or set(config)-allowed:
        raise ValueError("Invalid audiovisual cue options")
    w, h, fps = geometry
    interval = frames_for_seconds(config.get("interval_seconds",3),fps,"cue interval_seconds")
    on = positive_int(config.get("on_frames",2),"cue on_frames")
    if on >= interval:
        raise ValueError("Cue on_frames must leave an off interval")
    pw, ph = max(2,2*(w//16)),max(2,2*(h//16))
    bounds = [w-pw-2,h-ph-2,pw,ph]
    if min(bounds[:2]) < 0:
        raise ValueError("Native geometry cannot fit the cue patch")
    duration = positive_int(config.get("duration_samples",4800),"cue duration_samples")
    values = {k:config.get(k,v) for k,v in (("amplitude",0.25),("frequency",900),("frequency_step",67),("end_frequency_offset",700))}
    if any(type(v) not in (int,float) or not math.isfinite(v) for v in values.values()):
        raise ValueError("Cue audio parameters must be finite numbers")
    events=[]
    for i, frame in enumerate(range(0,frame_count,interval)):
        pts = Fraction(frame,fps)
        frequency = values["frequency"]+i*values["frequency_step"]
        events.append({"id":f"cue-{i:03}","file_frame":frame,"pts":{"numerator":pts.numerator,"denominator":pts.denominator},
                       "bounds_xywh":bounds,"on_frames":min(on,frame_count-frame),"rgb":[255,255,255],
                       "duration_samples":duration,"frequency":frequency,"end_frequency":frequency+values["end_frequency_offset"],
                       "amplitude":values["amplitude"]})
    return {"schema":"vsg-visual-cues-v1","status":"AVAILABLE","bounds_xywh":bounds,"off_rgb":[0,0,0],"on_rgb":[255,255,255],"events":events,
            "identity":"Source file cue IDs; actual stream generation and file-loop iteration belong to the executing consumer"}


def overlay_cues(image, plan, frame):
    if plan is None:
        return image
    x, y, w, h = plan["bounds_xywh"]
    result = image.copy()
    on = any(e["file_frame"] <= frame < e["file_frame"]+e["on_frames"] for e in plan["events"])
    result[y:y+h,x:x+w] = plan["on_rgb"] if on else plan["off_rgb"]
    return result
