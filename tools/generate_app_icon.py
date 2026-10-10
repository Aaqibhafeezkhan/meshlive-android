"""Generate every MeshLive app-icon asset from one geometry definition.

The icon is a "play" triangle built from a mesh: two relay nodes and one larger
live node at the tip, joined by links, with a halo around the live node that
opens toward the incoming links. An earlier draft with stacked broadcast arcs
was dropped because it read as the Wi-Fi symbol.

Colour schemes (--scheme): "black" (shipped; design decision 2026-10-10: a black
and white icon) is a pure black tile with a white mark; "white" is the inverse;
"field-radio" is the first amber/teal/ink version on the call-ground gradient,
kept as an alternative. Relay style (--relays): "hollow" (shipped) draws the
relays as rings so the solid live node is the only filled dot; "solid" fills
them. All outputs are written
from the primitives in GEOMETRY so the Android vectors, the SVG master and the
PNG renders cannot drift apart.

Usage:
    python -B tools/generate_app_icon.py [--scheme black|white|field-radio]
        [--relays hollow|solid] [--previews DIR]

Outputs (relative to the project root):
    app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml
    app/src/main/res/drawable/ic_stat_meshlive.xml        (notification small icon)
    docs/brand/meshlive-icon.svg                          (master, full-bleed square)
    docs/brand/meshlive-icon-1024.png                     (iOS asset catalog)
    fastlane/metadata/android/en-US/images/icon.png       (512 px listing)

Rendering uses Pillow only (supersampled), so no native SVG library is needed.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]

CANVAS = 108.0  # adaptive-icon canvas in dp; the guaranteed-visible safe zone is a 66 dp circle
SCHEMES = {
    # name: ground top, ground bottom, link, relay, live node + halo
    "black": ("#000000", "#000000", "#FFFFFF", "#FFFFFF", "#FFFFFF"),
    "white": ("#FFFFFF", "#FFFFFF", "#000000", "#000000", "#000000"),
    "field-radio": ("#1C2A33", "#0E1418", "#46C2B4", "#E6EBEE", "#F2A93B"),  # call_ground, teal, ink, amber
}
DEFAULT_SCHEME = "black"
DEFAULT_RELAYS = "hollow"

LIVE = (65.0, 54.0)
TOP = (34.75, 36.0)
BOTTOM = (34.75, 72.0)
NODE_R = 6.75  # outer radius of a relay node
RELAY_RING_W = 2.75  # hollow relays: ring stroke, leaving a 4 dp hole
LIVE_R = 8.0
LINK_W = 5.5
HALO_R = 14.5
HALO_W = 3.75
HALO_FACING = 0.0  # degrees, 0 = pointing right (screen coordinates, y down)
HALO_HALF_SPAN = 118.0  # the halo leaves a gap where the two links arrive

GROUND_TOP = GROUND_BOTTOM = ""
GEOMETRY: list = []


def _toward(a, b, d):
    """Point at distance d from a toward b."""
    length = math.dist(a, b)
    return (a[0] + (b[0] - a[0]) * d / length, a[1] + (b[1] - a[1]) * d / length)


def use_scheme(scheme=DEFAULT_SCHEME, relays=DEFAULT_RELAYS):
    """Build the ordered paint list (links, halo, then nodes on top) for a colour scheme."""
    global GROUND_TOP, GROUND_BOTTOM, GEOMETRY
    GROUND_TOP, GROUND_BOTTOM, link, relay, live = SCHEMES[scheme]
    if relays == "solid":
        GEOMETRY = [
            ("line", TOP, LIVE, LINK_W, link),
            ("line", BOTTOM, LIVE, LINK_W, link),
            ("line", TOP, BOTTOM, LINK_W, link),
            ("arc", LIVE, HALO_R, HALO_W, HALO_FACING, HALO_HALF_SPAN, live),
            ("dot", TOP, NODE_R, relay),
            ("dot", BOTTOM, NODE_R, relay),
            ("dot", LIVE, LIVE_R, live),
        ]
        return
    # Hollow relays: butt-capped links stop at the ring's centre line so the holes stay clean in
    # every layer, including the transparent monochrome one.
    ring_mid = NODE_R - RELAY_RING_W / 2
    GEOMETRY = [
        ("seg", _toward(TOP, LIVE, ring_mid), LIVE, LINK_W, link),
        ("seg", _toward(BOTTOM, LIVE, ring_mid), LIVE, LINK_W, link),
        ("seg", _toward(TOP, BOTTOM, ring_mid), _toward(BOTTOM, TOP, ring_mid), LINK_W, link),
        ("arc", LIVE, HALO_R, HALO_W, HALO_FACING, HALO_HALF_SPAN, live),
        ("ring", TOP, ring_mid, RELAY_RING_W, relay),
        ("ring", BOTTOM, ring_mid, RELAY_RING_W, relay),
        ("dot", LIVE, LIVE_R, live),
    ]


use_scheme()

# Square exports (listing, iOS, SVG master) show this many canvas units edge to edge.
# Launchers show the central 72 dp; 80 keeps the glyph as large as on a launcher with a margin.
SQUARE_CROP = 80.0


def _f(v: float) -> str:
    return f"{v:.3f}".rstrip("0").rstrip(".")


def _arc_points(c, r, facing, half_span):
    a0 = math.radians(facing - half_span)
    a1 = math.radians(facing + half_span)
    return (c[0] + r * math.cos(a0), c[1] + r * math.sin(a0)), (c[0] + r * math.cos(a1), c[1] + r * math.sin(a1))


def _dot_path(c, r):
    x, y = c
    return f"M{_f(x - r)},{_f(y)}a{_f(r)},{_f(r)} 0 1,0 {_f(2 * r)},0a{_f(r)},{_f(r)} 0 1,0 {_f(-2 * r)},0Z"


def _path_for(item):
    kind = item[0]
    if kind in ("line", "seg"):
        _, a, b, w, color = item
        cap = "round" if kind == "line" else "butt"
        return f"M{_f(a[0])},{_f(a[1])}L{_f(b[0])},{_f(b[1])}", w, color, "stroke", cap
    if kind == "arc":
        _, c, r, w, facing, span, color = item
        p0, p1 = _arc_points(c, r, facing, span)
        large = 1 if span > 90 else 0
        path = f"M{_f(p0[0])},{_f(p0[1])}A{_f(r)},{_f(r)} 0 {large},1 {_f(p1[0])},{_f(p1[1])}"
        return path, w, color, "stroke", "round"
    if kind == "ring":
        _, c, r, w, color = item
        return _dot_path(c, r), w, color, "stroke", "round"
    _, c, r, color = item
    return _dot_path(c, r), None, color, "fill", None


# ---------------------------------------------------------------- Android vectors
def _vector_header(size_dp, vw, vh, extra_ns=""):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/generate_app_icon.py; edit the geometry there, not here. -->\n"
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{extra_ns}\n'
        f'    android:width="{size_dp}dp"\n    android:height="{size_dp}dp"\n'
        f'    android:viewportWidth="{_f(vw)}"\n    android:viewportHeight="{_f(vh)}"'
    )


def _vector_paths(mono_color=None, indent="    "):
    out = []
    for item in GEOMETRY:
        data, w, color, mode, cap = _path_for(item)
        color = mono_color or color
        if mode == "fill":
            out.append(f'{indent}<path\n{indent}    android:fillColor="{color}"\n{indent}    android:pathData="{data}" />')
        else:
            out.append(
                f'{indent}<path\n{indent}    android:pathData="{data}"\n'
                f'{indent}    android:strokeColor="{color}"\n{indent}    android:strokeWidth="{_f(w)}"\n'
                f'{indent}    android:strokeLineCap="{cap}"\n{indent}    android:strokeLineJoin="round" />'
            )
    return "\n".join(out)


def android_foreground(mono_color=None):
    return _vector_header(108, CANVAS, CANVAS) + ">\n" + _vector_paths(mono_color) + "\n</vector>\n"


def android_background():
    if GROUND_TOP == GROUND_BOTTOM:
        return (
            _vector_header(108, CANVAS, CANVAS)
            + ">\n"
            + f'    <path\n        android:fillColor="{GROUND_TOP}"\n        android:pathData="M0,0h108v108h-108z" />\n'
            + "</vector>\n"
        )
    return (
        _vector_header(108, CANVAS, CANVAS, '\n    xmlns:aapt="http://schemas.android.com/aapt"')
        + ">\n"
        + '    <path android:pathData="M0,0h108v108h-108z">\n'
        + '        <aapt:attr name="android:fillColor">\n'
        + '            <gradient\n                android:type="linear"\n'
        + '                android:startX="54"\n                android:startY="0"\n'
        + '                android:endX="54"\n                android:endY="108"\n'
        + f'                android:startColor="{GROUND_TOP}"\n                android:endColor="{GROUND_BOTTOM}" />\n'
        + "        </aapt:attr>\n    </path>\n</vector>\n"
    )


def android_notification():
    # Tight crop around the glyph so the status-bar silhouette is full size. Status-bar icons are
    # drawn as an alpha mask, so a single white colour is correct.
    x0, y0, x1, y1 = _bbox()
    side = max(x1 - x0, y1 - y0) * 1.2  # 20 dp live area inside the 24 dp status-bar icon
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    tx, ty = side / 2 - cx, side / 2 - cy
    return (
        _vector_header(24, side, side)
        + ">\n"
        + f'    <group\n        android:translateX="{_f(tx)}"\n        android:translateY="{_f(ty)}">\n'
        + _vector_paths("#FFFFFFFF", indent="        ")
        + "\n    </group>\n</vector>\n"
    )


def _bbox():
    xs, ys = [], []
    for item in GEOMETRY:
        kind = item[0]
        if kind in ("line", "seg"):
            _, a, b, w, _c = item
            for p in (a, b):
                xs += [p[0] - w / 2, p[0] + w / 2]
                ys += [p[1] - w / 2, p[1] + w / 2]
        elif kind == "arc":
            _, c, r, w, facing, span, _c = item
            for k in range(0, 361, 3):  # sample the swept arc
                a = facing - span + 2 * span * k / 360
                x, y = c[0] + r * math.cos(math.radians(a)), c[1] + r * math.sin(math.radians(a))
                xs += [x - w / 2, x + w / 2]
                ys += [y - w / 2, y + w / 2]
        else:
            c, r = item[1], item[2] + (item[3] / 2 if kind == "ring" else 0)
            xs += [c[0] - r, c[0] + r]
            ys += [c[1] - r, c[1] + r]
    return min(xs), min(ys), max(xs), max(ys)


# ---------------------------------------------------------------- SVG master
def svg_master():
    o = (CANVAS - SQUARE_CROP) / 2
    lines = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" '
        f'viewBox="{_f(o)} {_f(o)} {_f(SQUARE_CROP)} {_f(SQUARE_CROP)}">',
        "  <!-- MeshLive icon master. Generated by tools/generate_app_icon.py. Full-bleed square:",
        "       iOS and the stores apply their own corner mask. Android uses the adaptive layers. -->",
        '  <defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0" stop-color="{GROUND_TOP}"/><stop offset="1" stop-color="{GROUND_BOTTOM}"/>'
        "</linearGradient></defs>",
        '  <rect x="0" y="0" width="108" height="108" fill="url(#g)"/>',
    ]
    for item in GEOMETRY:
        data, w, color, mode, cap = _path_for(item)
        if mode == "fill":
            lines.append(f'  <path d="{data}" fill="{color}"/>')
        else:
            lines.append(
                f'  <path d="{data}" fill="none" stroke="{color}" stroke-width="{_f(w)}" '
                f'stroke-linecap="{cap}" stroke-linejoin="round"/>'
            )
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- Pillow renderer
def _rgba(hex_color):
    h = hex_color.lstrip("#")
    if len(h) == 8:
        h = h[2:]  # Android AARRGGBB; alpha unused here
    return tuple(int(h[i : i + 2], 16) for i in (0, 2, 4)) + (255,)


def render_layers(px, crop=CANVAS, mono=None, ss=8):
    """Render (background, foreground) RGBA images of `crop` canvas units at `px` pixels."""
    n = px * ss
    s = n / crop
    o = (CANVAS - crop) / 2

    def m(p):
        return ((p[0] - o) * s, (p[1] - o) * s)

    bg = Image.new("RGBA", (n, n))
    top, bot = _rgba(GROUND_TOP), _rgba(GROUND_BOTTOM)
    bd = ImageDraw.Draw(bg)
    for y in range(n):
        t = (y / s + o) / CANVAS
        bd.line([(0, y), (n, y)], fill=tuple(round(top[i] + (bot[i] - top[i]) * t) for i in range(4)))

    fg = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg)

    def disc(c, r, col):
        x, y = m(c)
        d.ellipse([x - r * s, y - r * s, x + r * s, y + r * s], fill=col)

    for item in GEOMETRY:
        kind = item[0]
        if kind in ("line", "seg"):
            _, a, b, w, color = item
            col = _rgba(mono or color)
            d.line([m(a), m(b)], fill=col, width=round(w * s))
            if kind == "line":
                disc(a, w / 2, col)
                disc(b, w / 2, col)
        elif kind == "arc":
            _, c, r, w, facing, span, color = item
            col = _rgba(mono or color)
            x, y = m(c)
            ro = (r + w / 2) * s
            d.arc([x - ro, y - ro, x + ro, y + ro], facing - span, facing + span, fill=col, width=round(w * s))
            for p in _arc_points(c, r, facing, span):
                disc(p, w / 2, col)
        elif kind == "ring":
            _, c, r, w, color = item
            x, y = m(c)
            ro = (r + w / 2) * s
            d.ellipse([x - ro, y - ro, x + ro, y + ro], outline=_rgba(mono or color), width=round(w * s))
        else:
            _, c, r, color = item
            disc(c, r, _rgba(mono or color))
    return (bg.resize((px, px), Image.LANCZOS), fg.resize((px, px), Image.LANCZOS))


def render_square(px):
    bg, fg = render_layers(px, crop=SQUARE_CROP)
    return Image.alpha_composite(bg, fg).convert("RGB")


def _mask(px, shape, ss=8):
    n = px * ss
    mk = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(mk)
    if shape == "circle":
        d.ellipse([0, 0, n - 1, n - 1], fill=255)
    else:  # squircle-ish rounded square, like Pixel / One UI masks
        d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.30), fill=255)
    return mk.resize((px, px), Image.LANCZOS)


def render_launcher(px, shape="circle", mono_tint=None):
    """Simulate a launcher: central 72 dp of the 108 dp canvas, masked."""
    if mono_tint:
        _, fg = render_layers(px, crop=72.0, mono=mono_tint[1])
        bg = Image.new("RGBA", (px, px), _rgba(mono_tint[0]))
    else:
        bg, fg = render_layers(px, crop=72.0)
    img = Image.alpha_composite(bg, fg)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.paste(img, (0, 0), _mask(px, shape))
    return out


def contact_sheet(path, rows):
    """rows: (scheme, relays) pairs. Each row shows launcher circles on a light and a dark
    wallpaper, the Android 13 themed (monochrome) icon, and the squircle-masked listing PNG."""
    sizes = (48, 96, 192)
    pad = 20
    col_w = sum(sizes) + pad * (len(sizes) + 1)
    row_h = max(sizes) + pad * 2
    sheet = Image.new("RGB", (col_w * 4, row_h * len(rows)), "white")
    cells = (
        ("#F2F4F5", lambda sz: render_launcher(sz)),
        ("#12181D", lambda sz: render_launcher(sz)),
        ("#F2F4F5", lambda sz: render_launcher(sz, mono_tint=("#DCE3E8", "#2B3A44"))),
        ("#12181D", lambda sz: _listing(sz)),
    )
    for ri, (scheme, relays) in enumerate(rows):
        use_scheme(scheme, relays)
        for ci, (gcol, make) in enumerate(cells):
            tile = Image.new("RGBA", (col_w, row_h), _rgba(gcol))
            x = pad
            for sz in sizes:
                tile.alpha_composite(make(sz), (x, pad + (max(sizes) - sz) // 2))
                x += sz + pad
            sheet.paste(tile.convert("RGB"), (ci * col_w, ri * row_h))
    sheet.save(path)


def _listing(sz):
    icon = render_square(sz).convert("RGBA")
    icon.putalpha(_mask(sz, "squircle"))
    return icon


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--scheme", choices=sorted(SCHEMES), default=DEFAULT_SCHEME)
    ap.add_argument("--relays", choices=("hollow", "solid"), default=DEFAULT_RELAYS)
    ap.add_argument("--previews", type=Path, help="also write launcher previews and a contact sheet here")
    args = ap.parse_args()
    use_scheme(args.scheme, args.relays)

    res = ROOT / "app/src/main/res/drawable"
    (res / "ic_launcher_background.xml").write_text(android_background(), encoding="utf-8")
    (res / "ic_launcher_foreground.xml").write_text(android_foreground(), encoding="utf-8")
    (res / "ic_launcher_monochrome.xml").write_text(android_foreground("#FFFFFFFF"), encoding="utf-8")
    # The 24 dp status-bar silhouette keeps solid relays: ring holes would be about 1.5 dp there.
    use_scheme(args.scheme, "solid")
    (res / "ic_stat_meshlive.xml").write_text(android_notification(), encoding="utf-8")
    use_scheme(args.scheme, args.relays)

    brand = ROOT / "docs/brand"
    brand.mkdir(parents=True, exist_ok=True)
    (brand / "meshlive-icon.svg").write_text(svg_master(), encoding="utf-8")
    render_square(1024).save(brand / "meshlive-icon-1024.png", optimize=True)
    listing = ROOT / "fastlane/metadata/android/en-US/images"
    listing.mkdir(parents=True, exist_ok=True)
    render_square(512).save(listing / "icon.png", optimize=True)

    if args.previews:
        args.previews.mkdir(parents=True, exist_ok=True)
        for g, gname in (("#F2F4F5", "light"), ("#12181D", "dark")):
            for sz in (48, 96, 192):
                tile = Image.new("RGBA", (sz + 32, sz + 32), _rgba(g))
                tile.alpha_composite(render_launcher(sz), (16, 16))
                tile.convert("RGB").save(args.previews / f"launcher-{gname}-{sz}.png")
        contact_sheet(
            args.previews / "contact-sheet.png",
            [("black", "hollow"), ("black", "solid"), ("white", "hollow"), ("white", "solid"), ("field-radio", "solid")],
        )


if __name__ == "__main__":
    main()
