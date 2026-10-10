"""Declared file events. Consumer loop iteration and stream generation remain external."""

from fractions import Fraction
from copy import copy
from dataclasses import replace
import math

from .audio import sample_at
from .composition import descriptor

BOUNDARIES = {"hard_cut","repeat_seam","pattern_loop_seam","abrupt_luminance_change"}


def frame_events(timeline,geometry,frame):
    timeline = timeline.for_mode(geometry)
    s,repeat,source = timeline.locate(frame)
    kinds = []
    if source==0:
        if repeat:
            kinds.append("repeat_seam")
        else:
            kinds.append("segment_start")
            if frame:
                kinds.append("hard_cut")
        if descriptor(s.pattern)["group"] in ("motion","complex","timing","composition"):
            kinds.append("motion_transition")
    if s.pattern=="hard_cuts" and source and source%s.params["hold_frames"]==0:
        kinds.append("hard_cut")
    if s.pattern=="blinking_rectangles" and source%s.params["hold_frames"]==0:
        kinds.append("rectangle_state_change")
    if s.pattern=="luminance_transitions" and source%s.params["hold_frames"]==0:
        kinds.append("abrupt_luminance_change")
    if s.pattern=="dust_explosion" and source and math.floor(source*s.params["speed"]/s.params["period_frames"])!=math.floor((source-1)*s.params["speed"]/s.params["period_frames"]):
        kinds.append("pattern_loop_seam")
    if s.pattern=="dissolves_fades":
        phase = source%s.params["period_frames"]
        if phase==0:
            kinds.append("dissolve_start")
            if source:
                kinds.append("pattern_loop_seam")
        if phase==math.ceil(s.params["period_frames"]/3):
            kinds.append("fade_start")
        if phase in (math.ceil(s.params["period_frames"]*2/3),math.ceil(s.params["period_frames"]*5/6)):
            kinds.append("abrupt_luminance_change")
    if s.pattern=="visual_audio_sync" and source%s.params["interval_frames"]==0:
        kinds.append("visual_sync")
    pts = Fraction(frame,geometry[2])
    events = [{"kind":kind,"file_frame":frame,"segment_id":s.id,"pattern_id":descriptor(s.pattern)["id"],
             "source_frame":source,"repeat_index":repeat,"pts":{"numerator":pts.numerator,"denominator":pts.denominator},
             "audio_sample_index_48k":sample_at(frame,geometry[2]),"audio_reference_emitted":timeline.audio.get("enabled",False),
             **({"full_screen":s.params["full_screen"],"transitions_per_second":str(Fraction(geometry[2],s.params["hold_frames"]))}
                if s.pattern=="luminance_transitions" else {})} for kind in kinds]
    if timeline.cues:
        for cue in timeline.cues["events"]:
            if cue["file_frame"] == frame:
                events.append({"kind":"visual_sync","cue_id":cue["id"],"file_frame":frame,"segment_id":s.id,
                    "pattern_id":descriptor(s.pattern)["id"],"source_frame":source,"repeat_index":repeat,
                    "pts":cue["pts"],"bounds_xywh":cue["bounds_xywh"],"on_frames":cue["on_frames"],"rgb":cue["rgb"],
                    "audio_sample_index_48k":sample_at(frame,geometry[2]),"audio_reference_emitted":timeline.audio.get("enabled",False)})
    for panel in s.panels:
        child = copy(timeline)
        child.segments = [replace(s,pattern=panel["pattern"],params=panel["params"],seed=panel["seed"],panels=())]
        child.cues = None
        for event in frame_events(child,geometry,frame):
            event.update(panel_id=panel["id"],bounds_xywh=panel["bounds_xywh"])
            if event["kind"]=="visual_sync":
                event["cue_id"] = f"sync-{s.id}-{panel['id']}-{repeat}-{source}"
            events.append(event)
    return events


def event_document(timeline,geometry,audio):
    timeline = timeline.for_mode(geometry)
    events = []
    for frame in range(timeline.frame_count):
        events.extend(frame_events(timeline,geometry,frame))
    duration = Fraction(timeline.frame_count,geometry[2])
    events.append({"kind":"file_loop_seam","boundary_after_file_frame":timeline.frame_count-1,"next_file_frame":0,
                   "at_file_time":{"numerator":duration.numerator,"denominator":duration.denominator},
                   "audio_sample_index_48k":sample_at(timeline.frame_count,geometry[2]),"conditional_on_consumer_loop":True})
    return {"schema":"vsg-events-v1","events":events,"audio_schedule":audio,"visual_cues":timeline.cues,
            "identity":"File loops repeat these file-frame IDs. Harness owns loop iteration and stream-generation identity.",
            "scope":"Expected source events, not captured/injected/observed application events"}
