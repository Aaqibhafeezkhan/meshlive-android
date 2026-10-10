"""Timeline uses integer frames and exact Fraction PTS; seek has no mutable state."""

from dataclasses import dataclass
from copy import copy, deepcopy
from fractions import Fraction
import hashlib
import json

from .contract import mode, positive_int
from .composition import calibration_chart, cue_plan, descriptor, frames_for_seconds, overlay_cues, panels_for, render_panels
from .patterns import parameters, render, resolve, source_identity
from .provenance import code_identity

PRESETS = {"sd": (640,480,30), "720p": (1280,720,30), "1080p": (1920,1080,30),
           "4k": (3840,2160,30), "tiny": (160,96,5)}


def parse_mode(text):
    if text in PRESETS:
        return PRESETS[text]
    try:
        geometry, fps = text.lower().split("@")
        w, h = geometry.split("x")
        return mode({"width": int(w), "height": int(h), "fps": int(fps)})
    except (ValueError, AttributeError):
        raise ValueError(f"Invalid mode {text!r}; use WIDTHxHEIGHT@INTEGER_FPS or a preset") from None


@dataclass(frozen=True)
class Segment:
    id: str
    pattern: str
    start: int
    frames: int
    repeat: int
    params: dict
    seed: int
    panels: tuple = ()


class Timeline:
    def __init__(self, config, modes=None):
        if not isinstance(config, dict) or type(config.get("schema_version")) is not int or config["schema_version"] != 1:
            raise ValueError("Timeline needs schema_version 1")
        allowed = {"schema_version", "seed", "modes", "timeline", "marker", "audio", "lossless", "cues", "reference_mode"}
        if set(config) - allowed:
            raise ValueError(f"Unsupported timeline options: {sorted(set(config)-allowed)}")
        self.seed = config.get("seed", 0)
        if type(self.seed) is not int or not 0 <= self.seed < 2**64:
            raise ValueError("seed must be an unsigned 64-bit integer")
        rows = modes if modes is not None else config.get("modes", [])
        if not isinstance(rows,list) or not rows:
            raise ValueError("Select at least one mode explicitly; requirements never render themselves")
        self.modes = [parse_mode(r) if isinstance(r,str) else mode(r) for r in rows]
        if len(self.modes) != len(set(self.modes)):
            raise ValueError("Ambiguous duplicate mode; use separate variants")
        self.lossless = config.get("lossless", True)
        if type(self.lossless) is not bool:
            raise ValueError("lossless must be boolean")
        self.marker = config.get("marker", {"enabled": True, "cell_size": 2})
        self.audio = config.get("audio", {"enabled": False})
        self.reference_mode = config.get("reference_mode","full")
        if self.reference_mode not in ("full","native"):
            raise ValueError("reference_mode must be full or native")
        self.cue_config = config.get("cues",{})
        if not isinstance(self.cue_config,dict):
            raise ValueError("cues must be an object")
        rows = config.get("timeline")
        if not isinstance(rows,list) or not rows:
            raise ValueError("timeline must contain at least one segment")
        self._segments = {geometry:self._compile(rows,geometry) for geometry in self.modes}
        self._cues = {geometry:cue_plan(self.cue_config,geometry,sum(s.frames*s.repeat for s in segments))
                      for geometry,segments in self._segments.items()}
        self.geometry = self.modes[0]
        self.segments = self._segments[self.geometry]
        self.frame_count = sum(s.frames*s.repeat for s in self.segments)
        self.cues = self._cues[self.geometry]
        self.config = deepcopy(config)
        self.config.update(modes=[dict(zip(("width","height","fps"),m)) for m in self.modes],
                           seed=self.seed,marker=self.marker,audio=self.audio,lossless=self.lossless,
                           reference_mode=self.reference_mode,cues=self.cue_config)
        self.normalized = {"schema_version":1,"generator":code_identity(),"config":self.config,
            "resolved_modes":[{"mode":list(m),"segments":[vars(s) for s in segments]} for m,segments in self._segments.items()]}
        self.variant = hashlib.sha256(json.dumps(self.normalized,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()

    def _compile(self, rows, geometry):
        offset, ids, segments = 0, set(), []
        for i, row in enumerate(rows):
            if not isinstance(row,dict) or set(row)-{"id","pattern","frames","seconds","repeat","params","seed","panels","grid"}:
                raise ValueError("Invalid timeline segment options")
            sid = row.get("id", f"s{i:03}")
            if not isinstance(sid,str) or not sid or sid in ids:
                raise ValueError("Segment IDs must be nonempty unique strings")
            ids.add(sid)
            mosaic = "panels" in row
            if not mosaic and ("grid" in row or row.get("pattern")=="mosaic"):
                raise ValueError("A mosaic grid requires panels")
            if mosaic and row.get("pattern","mosaic") != "mosaic":
                raise ValueError("A mosaic declares panels instead of a single pattern")
            p = descriptor("mosaic" if mosaic else row.get("pattern"))
            if ("frames" in row) == ("seconds" in row):
                raise ValueError("Declare exactly one of segment frames or seconds")
            count = (frames_for_seconds(row["seconds"],geometry[2],"segment seconds") if "seconds" in row
                     else positive_int(row["frames"], "segment frames"))
            repeat = positive_int(row.get("repeat",1), "repeat")
            seed = row.get("seed",self.seed)
            if type(seed) is not int or not 0 <= seed < 2**64:
                raise ValueError("Segment seed must be unsigned 64-bit integer")
            panels = panels_for(row,geometry,seed) if mosaic else []
            for panel in panels:
                pc = panel["params"]
                if panel["pattern"]=="luminance_transitions" and pc["full_screen"] and not pc["allow_high_rate_flash"] and geometry[2]/pc["hold_frames"]>3:
                    raise ValueError("High-rate full-panel flashing requires explicit allow_high_rate_flash=true")
            if p["name"] in ("mosaic","framing_calibration"):
                if row.get("params",{}):
                    raise ValueError("Composition parameters belong to individual panels")
                params = {}
                if p["name"] == "framing_calibration":
                    calibration_chart(geometry)
            else:
                if "grid" in row:
                    raise ValueError("grid requires mosaic panels")
                params = parameters(p, row.get("params",{}))
            if p["name"] == "luminance_transitions" and params["full_screen"] and not params["allow_high_rate_flash"]:
                if any(m[2]/params["hold_frames"]>3 for m in self.modes):
                    raise ValueError("High-rate full-screen flashing disabled; explicit allow_high_rate_flash=true required")
            segments.append(Segment(sid,p["name"],offset,count,repeat,params,seed,tuple(panels)))
            offset += count * repeat
        if offset >= 2**32:
            raise ValueError("Timeline exceeds 32-bit file-frame marker capacity")
        return segments

    def for_mode(self, geometry):
        if geometry not in self._segments:
            raise ValueError("Mode is not selected in this timeline")
        if geometry == self.geometry:
            return self
        result = copy(self)
        result.geometry = geometry
        result.segments = self._segments[geometry]
        result.frame_count = sum(s.frames*s.repeat for s in result.segments)
        result.cues = self._cues[geometry]
        return result

    def locate(self, frame):
        if type(frame) is not int or not 0 <= frame < self.frame_count:
            raise ValueError("File-frame index outside timeline")
        for s in self.segments:
            if frame < s.start + s.frames * s.repeat:
                iteration, source = divmod(frame-s.start,s.frames)
                return s, iteration, source
        raise AssertionError("Unreachable frame")

    def frame(self, geometry, frame):
        timeline = self.for_mode(geometry)
        s, _, source = timeline.locate(frame)
        if s.pattern == "mosaic":
            image = render_panels(s.panels,geometry,source)
        elif s.pattern == "framing_calibration":
            image = calibration_chart(geometry)[0]
        else:
            image = render(s.pattern,*geometry,source,s.seed,s.params)
        return overlay_cues(image,timeline.cues,frame)

    def identity(self, geometry, frame):
        timeline = self.for_mode(geometry)
        s, iteration, source = timeline.locate(frame)
        params = {**s.params,"panels":s.panels} if s.panels else s.params
        if timeline.cues:
            params = {**params,"cue_config":self.cue_config,"cue_file_frame":frame}
        source_id = source_identity(s.pattern,geometry,source,s.seed,params)
        return {"file_frame":frame,"frame_id":f"{self.variant}:{geometry[0]}x{geometry[1]}@{geometry[2]}:{frame}",
                "source_frame_id":source_id,"source_frame":source,"segment_id":s.id,
                "pattern_id":descriptor(s.pattern)["id"],"repeat_index":iteration,
                "pts":Fraction(frame,geometry[2])}
