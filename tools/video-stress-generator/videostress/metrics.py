"""VSG SI/TI v1: explicit legacy-style signal-domain Sobel/difference statistics."""

import numpy as np

ALGORITHM = {
    "version":"vsg-siti-v1",
    "si":"Population standard deviation of unnormalized 3x3 Sobel gradient magnitude on valid interior luma",
    "ti":"Population standard deviation of signed current-minus-previous luma on fixed content mask",
    "luma":"limited Y mapped to float64 full 0..255 via clip(Y-16,0,219)*255/219; full-range unchanged",
    "edges":"One-pixel border excluded for SI; every stencil touching excluded marker pixels excluded",
    "quantile":"NumPy percentile linear interpolation; median, p95, peak; missing/first-frame TI excluded",
    "scope":"Signal-domain engineering statistic; no claim of current P.910 certification or natural-footage equivalence",
    "ffmpeg_difference":"FFmpeg legacy siti uses integer truncation in range expansion; VSG keeps fractional values",
}


def normalized_luma(y,range_name):
    if range_name not in ("limited","full"):
        raise ValueError("Luma range is UNSPECIFIED; declare limited or full before SI/TI analysis")
    a = y.astype(np.float64)
    return np.clip(a-16,0,219)*255/219 if range_name=="limited" else a


def measure(y,previous=None,mask=None,range_name="limited"):
    a = normalized_luma(y,range_name)
    if mask is None:
        mask = np.ones(a.shape,bool)
    if mask.shape != a.shape or not mask.any():
        raise ValueError("Analysis ROI must contain pixels at declared geometry")
    si = None
    if min(a.shape)>=3:
        tl,tc,tr = a[:-2,:-2],a[:-2,1:-1],a[:-2,2:]
        ml,mr = a[1:-1,:-2],a[1:-1,2:]
        bl,bc,br = a[2:,:-2],a[2:,1:-1],a[2:,2:]
        gx = -tl+tr-2*ml+2*mr-bl+br
        gy = -tl-2*tc-tr+bl+2*bc+br
        valid = np.logical_and.reduce([mask[dy:dy+a.shape[0]-2,dx:dx+a.shape[1]-2]
                                       for dy in range(3) for dx in range(3)])
        if valid.any():
            si = float(np.std(np.hypot(gx,gy)[valid]))
    ti,delta,changed = None,None,None
    if previous is not None:
        if previous.shape != y.shape:
            raise ValueError("TI requires unchanged geometry")
        difference = (a-normalized_luma(previous,range_name))[mask]
        ti = float(np.std(difference))
        delta = float(np.mean(np.abs(difference)))
        changed = float(np.mean(difference!=0))
    codes = np.clip(a[mask],0,255).astype(np.uint8)
    probabilities = np.bincount(codes,minlength=256)/codes.size
    probabilities = probabilities[probabilities>0]
    return {"si":si,"ti":ti,"mean_absolute_delta":delta,"changed_fraction":changed,
            "entropy_bits":float(-np.sum(probabilities*np.log2(probabilities))),
            "mean_luma":float(np.mean(a[mask]))}


def statistics(values):
    a = np.array([v for v in values if v is not None],np.float64)
    if not len(a):
        return {"status":"UNMEASURED","count":0,"median":None,"p95":None,"peak":None}
    return {"status":"MEASURED","count":len(a),"median":float(np.median(a)),
            "p95":float(np.percentile(a,95)),"peak":float(np.max(a))}


def summarize(rows):
    return {"si":statistics(r["si"] for r in rows),
            "ti_including_cuts_seams":statistics(r["ti"] for r in rows),
            "ti_excluding_cuts_seams":statistics(r["ti"] for r in rows if not r.get("boundary",False)),
            "mean_absolute_delta":statistics(r["mean_absolute_delta"] for r in rows),
            "entropy_bits":statistics(r["entropy_bits"] for r in rows)}
