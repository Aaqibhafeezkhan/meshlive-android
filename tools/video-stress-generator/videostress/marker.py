"""VSG1 binary visual marker: exact reference decoder, CRC32, triplicate bits."""

from fractions import Fraction
import struct
import zlib

import numpy as np

COLS, ROWS = 26, 25


def layout(width,height,cell_size=2,enabled=True,fps=None):
    if type(cell_size) is not int or cell_size<2 or cell_size%2:
        raise ValueError("Marker cell_size must be an even integer >=2")
    if type(enabled) is not bool:
        raise ValueError("Marker enabled must be boolean")
    fits = width >= COLS*cell_size and height >= ROWS*cell_size and (fps is None or fps<2**32)
    available = enabled and fits
    bounds = [0,0,COLS*cell_size,ROWS*cell_size] if available else None
    return {"schema":"vsg-marker-v1","status":"AVAILABLE" if available else "UNMEASURED" if enabled else "DISABLED",
            "reason":None if available else "Native dimensions/FPS exceed unambiguous VSG1 marker geometry/payload" if enabled else "Explicitly disabled",
            "cell_size":cell_size,"cells":[COLS,ROWS],"bounds_xywh":bounds,
            "area_fraction":COLS*ROWS*cell_size**2/(width*height) if available else 0,
            "minimum_dimensions":[COLS*cell_size,ROWS*cell_size],
            "payload":"big-endian magic uint16=0x5653, version uint8=1, frame uint32, reduced PTS numerator uint64, denominator uint32, CRC32 uint32",
            "redundancy":"Each payload bit repeated three consecutive times; majority followed by CRC32",
            "bounds_convention":"half-open x,y,width,height; fixed top-left, no rotation/scale search",
            "decoder":"Cell means <=80 black or >=175 white; intermediate cells and inconsistent border are ambiguous",
            "content_roi":{"geometry":[width,height],"exclude_xywh":[bounds] if available else [],
                           "si_stencil":"Exclude all 3x3 stencils touching excluded pixels; fixed before generation"},
            "limitations":["Exact native reference self-test only; no phone/capture/codec robustness certification",
                           "Two-pixel cells have limited display legibility; CRC is error detection, not proof against all collisions"]}


def bits(frame,fps):
    pts = Fraction(frame,fps)
    if not 0 <= frame < 2**32 or pts.denominator >= 2**32 or pts.numerator >= 2**64:
        raise ValueError("Marker payload capacity exceeded; disable markers for this FPS")
    payload = struct.pack(">HBIQI",0x5653,1,frame,pts.numerator,pts.denominator)
    payload += struct.pack(">I",zlib.crc32(payload))
    repeated = np.repeat(np.unpackbits(np.frombuffer(payload,np.uint8)),3).reshape(23,24)
    grid = np.zeros((ROWS,COLS),np.uint8)
    grid[1:-1,1:-1] = repeated
    grid[0,:] = 1
    grid[-1,:] = np.arange(COLS)%2
    grid[1:-1,-1] = np.arange(23)%2
    return grid


def mark(rgb,frame,fps,spec):
    if spec["status"] != "AVAILABLE":
        return rgb.copy()
    cell = spec["cell_size"]
    x,y,_,_ = spec["bounds_xywh"]
    patch = np.repeat(np.repeat(bits(frame,fps),cell,axis=0),cell,axis=1)*255
    a = rgb.copy()
    a[y:y+ROWS*cell,x:x+COLS*cell] = patch[...,None]
    return a


def decode(image,spec):
    if spec["status"] != "AVAILABLE":
        return {"status":"UNMEASURED","reason":spec["reason"]}
    cell = spec["cell_size"]
    x,y,_,_ = spec["bounds_xywh"]
    patch = np.asarray(image)[y:y+ROWS*cell,x:x+COLS*cell]
    if patch.ndim == 3:
        patch = patch.mean(axis=2)
    if patch.shape != (ROWS*cell,COLS*cell):
        return {"status":"AMBIGUOUS","reason":"Marker geometry mismatch"}
    means = patch.reshape(ROWS,cell,COLS,cell).mean(axis=(1,3))
    if np.any((means>80)&(means<175)):
        return {"status":"AMBIGUOUS","reason":"Intermediate cell luminance"}
    grid = (means>=175).astype(np.uint8)
    expected = bits(0,1)
    border = np.ones((ROWS,COLS),bool)
    border[1:-1,1:-1] = False
    if np.any(grid[border] != expected[border]):
        return {"status":"AMBIGUOUS","reason":"Invalid marker border/orientation"}
    triples = grid[1:-1,1:-1].ravel().reshape(-1,3)
    payload = np.packbits((triples.sum(axis=1)>=2).astype(np.uint8)).tobytes()
    if zlib.crc32(payload[:-4]) != struct.unpack(">I",payload[-4:])[0]:
        return {"status":"AMBIGUOUS","reason":"CRC32 mismatch"}
    magic,version,frame,num,den = struct.unpack(">HBIQI",payload[:-4])
    if magic!=0x5653 or version!=1 or den==0:
        return {"status":"AMBIGUOUS","reason":"Invalid payload fields"}
    return {"status":"DECODED","file_frame":frame,"pts_numerator":num,"pts_denominator":den,
            "corrected_triplets":int(np.sum(triples.max(axis=1)!=triples.min(axis=1)))}


def content_mask(spec):
    w,h = spec["content_roi"]["geometry"]
    mask = np.ones((h,w),bool)
    for x,y,rw,rh in spec["content_roi"]["exclude_xywh"]:
        mask[y:y+rh,x:x+rw] = False
    return mask
