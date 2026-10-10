"""Sample-indexed PCM16 reference synthesis; never app injection or measurement."""

from fractions import Fraction
import hashlib
import math
import wave

import numpy as np

from .contract import positive_int

RATE = 48000
KINDS = {"beep","chirp","sweep","noise"}


def sample_at(frame,fps,rate=RATE):
    return frame*rate//fps


def amplitude(value):
    if type(value) not in (int,float) or not math.isfinite(value) or not 0<value<=0.95:
        raise ValueError("Audio amplitude must be in (0,0.95]")
    return float(value)


def schedule(timeline,geometry):
    timeline = timeline.for_mode(geometry)
    cfg = timeline.audio
    if not isinstance(cfg,dict) or set(cfg)-{"enabled","amplitude","events","originals"}:
        raise ValueError("Audio options: enabled, amplitude, events, originals")
    enabled = cfg.get("enabled",False)
    if type(enabled) is not bool:
        raise ValueError("audio.enabled must be boolean")
    fps = geometry[2]
    count = sample_at(timeline.frame_count,fps)
    amp = amplitude(cfg.get("amplitude",0.4))
    events = cfg.get("events",[])
    originals = cfg.get("originals",[])
    if not isinstance(events,list) or not isinstance(originals,list):
        raise ValueError("Audio events and originals must be arrays")
    if not enabled and (events or originals):
        raise ValueError("Audio events/originals require audio.enabled=true")
    formats = [[RATE,1]] if enabled else []
    for row in originals:
        if not isinstance(row,dict) or set(row)-{"sample_rate","channels"}:
            raise ValueError("Audio original needs sample_rate and channels")
        rate,channels = positive_int(row.get("sample_rate"),"sample_rate"),positive_int(row.get("channels"),"channels")
        if channels not in (1,2) or rate<8000 or rate>192000:
            raise ValueError("Audio originals support 8000..192000 Hz, mono/stereo PCM16")
        if [rate,channels] in formats:
            raise ValueError("Duplicate audio format")
        formats.append([rate,channels])
    for rate,channels in formats:
        if sample_at(timeline.frame_count,fps,rate)*channels*2>2**32-36:
            raise ValueError("Requested PCM WAV exceeds the RIFF 4 GiB limit; select a shorter timeline or disable audio")
    if enabled and count==0:
        raise ValueError("Audio duration is less than one 48 kHz sample; request more frames or disable audio")
    normalized = []
    ids = set()
    for i,e in enumerate(events):
        if not isinstance(e,dict) or set(e)-{"id","frame","seconds","role","kind","duration_samples","amplitude","frequency","end_frequency","seed"}:
            raise ValueError("Invalid audio event options")
        if e.get("role","cue") not in ("cue","workload"):
            raise ValueError("Audio event role must be cue or workload")
        if ("frame" in e) == ("seconds" in e):
            raise ValueError("Declare exactly one of audio event frame or seconds")
        frame = e.get("frame")
        if "seconds" in e:
            value = e["seconds"]
            if type(value) not in (int,float,str):
                raise ValueError("Audio event seconds must be an exact nonnegative decimal or fraction")
            try:
                anchor = Fraction(str(value))*fps
            except (ValueError,ZeroDivisionError):
                raise ValueError("Audio event seconds must be an exact nonnegative decimal or fraction") from None
            if anchor < 0 or anchor.denominator != 1:
                raise ValueError(f"Audio event seconds must be exactly representable at {fps} FPS; no rounding")
            frame = anchor.numerator
        if type(frame) is not int or not 0<=frame<timeline.frame_count:
            raise ValueError("Audio event frame outside timeline")
        kind = e.get("kind","beep")
        if not isinstance(kind,str) or kind not in KINDS:
            raise ValueError(f"Unsupported audio kind {kind!r}")
        length = positive_int(e.get("duration_samples",480),"duration_samples")
        start = sample_at(frame,fps)
        if start+length>count:
            raise ValueError("Audio event exceeds exact file duration; shorten duration_samples")
        seed = e.get("seed",(timeline.seed+i)%2**64)
        if type(seed) is not int or not 0<=seed<2**64:
            raise ValueError("Audio seed must be an unsigned 64-bit integer")
        event = {"id":e.get("id",f"audio-{i:03}"),"kind":kind,"file_frame":frame,
                 "sample_index":start,"sample_count":length,"amplitude":amplitude(e.get("amplitude",amp)),
                 "frequency":e.get("frequency",900),"end_frequency":e.get("end_frequency",2900),"seed":seed}
        if "role" in e:
            event["role"] = e["role"]
        normalized.append(event)
    if enabled:
        if timeline.cues:
            for cue in timeline.cues["events"]:
                normalized.append({"id":cue["id"],"kind":"chirp","file_frame":cue["file_frame"],
                    "sample_index":sample_at(cue["file_frame"],fps),"sample_count":cue["duration_samples"],
                    "amplitude":amplitude(cue["amplitude"]),"frequency":cue["frequency"],
                    "end_frequency":cue["end_frequency"],"seed":timeline.seed,"visual_sync":True})
        for s in timeline.segments:
            children = s.panels or ({"pattern":s.pattern,"params":s.params,"seed":s.seed},)
            for child in children:
                if child["pattern"] != "visual_audio_sync":
                    continue
                for repeat in range(s.repeat):
                    for source in range(0,s.frames,child["params"]["interval_frames"]):
                        frame = s.start+repeat*s.frames+source
                        start = sample_at(frame,fps)
                        prefix = s.id+"-"+child["id"] if "id" in child else s.id
                        normalized.append({"id":f"sync-{prefix}-{repeat}-{source}","kind":"chirp","file_frame":frame,
                            "sample_index":start,"sample_count":min(480,count-start),"amplitude":amp,
                            "frequency":900,"end_frequency":2900,"seed":child["seed"],"visual_sync":True})
    for e in normalized:
        if e["sample_index"]+e["sample_count"]>count:
            raise ValueError("Audio cue exceeds exact file duration; shorten duration_samples")
        if not isinstance(e["id"],str) or not e["id"] or e["id"] in ids:
            raise ValueError("Audio event IDs must be nonempty and unique")
        ids.add(e["id"])
        if e["sample_count"]==0:
            raise ValueError("Synchronization event has no audio sample at this FPS")
        if any(e["sample_count"]*rate//RATE<1 for rate,_ in formats):
            raise ValueError("Audio original cannot represent a scheduled event; increase its sample rate or event duration")
        for frequency in (e["frequency"],e["end_frequency"]):
            if type(frequency) not in (int,float) or not math.isfinite(frequency) or not 0<frequency<min([r for r,_ in formats] or [RATE])/2:
                raise ValueError("Audio frequencies must be below Nyquist for every requested format")
        pts = Fraction(e["file_frame"],fps)
        e["pts"] = {"numerator":pts.numerator,"denominator":pts.denominator}
        error = Fraction(e["sample_index"],RATE)-pts
        e["quantization_error_seconds"] = {"numerator":error.numerator,"denominator":error.denominator}
    normalized.sort(key=lambda e:(e["sample_index"],e["id"]))
    # Reject possible clipping rather than quietly normalizing known reference amplitudes.
    edges = sorted([(e["sample_index"],1,e["amplitude"]) for e in normalized]+
                   [(e["sample_index"]+e["sample_count"],0,-e["amplitude"]) for e in normalized])
    level = 0.0
    for _,_,delta in edges:
        level += delta
        if level>0.95+1e-12:
            raise ValueError("Overlapping audio amplitudes can clip; lower amplitudes or separate events")
    return {"schema":"vsg-audio-schedule-v1","enabled":enabled,"sample_rate":RATE,"channels":1,
            "sample_count":count,"formats":formats,"events":normalized,
            "alignment":"start sample=floor(file_frame*sample_rate/fps); exact sub-sample quantization error retained",
            "originals":"Stereo originals duplicate the reference signal on both channels; separate sample clocks"}


def noise_samples(indices,seed):
    # SplitMix64 counter mapping: independent of chunk sizes, seeks and Python hash salt.
    z = indices.astype(np.uint64)+np.uint64(seed)
    z = z+np.uint64(0x9E3779B97F4A7C15)
    z = (z^(z>>np.uint64(30)))*np.uint64(0xBF58476D1CE4E5B9)
    z = (z^(z>>np.uint64(27)))*np.uint64(0x94D049BB133111EB)
    z ^= z>>np.uint64(31)
    return (z>>np.uint64(11)).astype(np.float64)/(2**53)*2-1


def chunk(schedule,start,count,rate=RATE,channels=1):
    signal = np.zeros(count,np.float64)
    for e in schedule["events"]:
        # Event file-frame PTS is the visual anchor; end is the declared 48 kHz duration.
        pts = Fraction(e["pts"]["numerator"],e["pts"]["denominator"])
        event_start = (pts.numerator*rate)//pts.denominator
        length = e["sample_count"]*rate//RATE
        if length<1:
            raise ValueError("Audio original cannot represent a scheduled event; increase its sample rate")
        lo,hi = max(start,event_start),min(start+count,event_start+length)
        if hi<=lo:
            continue
        indices = np.arange(lo-event_start,hi-event_start,dtype=np.int64)
        t = indices/rate
        f0 = e["frequency"]
        duration = max(1,length)/rate
        if e["kind"]=="noise":
            carrier = noise_samples(indices,e["seed"])
        else:
            slope = 0 if e["kind"]=="beep" else (e["end_frequency"]-f0)/duration
            carrier = np.sin(2*np.pi*(f0*t+slope*t*t/2))
        envelope = np.sin(np.pi*(indices+0.5)/length)**2
        signal[lo-start:hi-start] += carrier*envelope*e["amplitude"]
    pcm = np.rint(np.clip(signal,-1,1)*32767).astype("<i2")
    return np.repeat(pcm[:,None],channels,axis=1).tobytes()


def write_wav(path,schedule,frame_count,fps,rate=RATE,channels=1,chunk_size=4096):
    count = sample_at(frame_count,fps,rate)
    sha = hashlib.sha256()
    peak = 0
    with wave.open(str(path),"wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        for start in range(0,count,chunk_size):
            pcm = chunk(schedule,start,min(chunk_size,count-start),rate,channels)
            sha.update(pcm)
            peak = max(peak,int(np.max(np.abs(np.frombuffer(pcm,"<i2").astype(np.int32)))))
            wav.writeframesraw(pcm)
    return {"schema":"vsg-pcm-reference-v1","sample_rate":rate,"channels":channels,"sample_count":count,
            "format":"PCM signed 16-bit little-endian","pcm_sha256":sha.hexdigest(),"peak_pcm":peak,
            "synthesis":"vsg-audio-v1; phase-indexed sine/linear chirp, sin^2 envelope, counter-based SplitMix64 noise",
            "event_samples":[{"id":e["id"],"sample_index":e["pts"]["numerator"]*rate//e["pts"]["denominator"],
                              "sample_count":e["sample_count"]*rate//RATE} for e in schedule["events"]]}
