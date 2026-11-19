# SPDX-License-Identifier: GPL-3.0-or-later
# Boophis brand asset derivation. Reads assets/brand/hero.png and
# assets/brand/empty-state.png (both RGBA, native alpha) and writes every icon,
# mark and notification asset from their pixels. Pillow + numpy only. Idempotent.
import os
import sys

import math

import cv2
import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HERO = os.path.join(ROOT, "assets", "brand", "hero.png")
EMPTY = os.path.join(ROOT, "assets", "brand", "empty-state.png")
HERO_CLOSED = os.path.join(ROOT, "assets", "brand", "hero-eyes-closed.png")
LANCZOS = Image.LANCZOS
# "night canopy" icon background: vertical gradient + mint glow behind head/upper body + faint blue glow at the eye side
TOP, BOTTOM = (0x0F, 0x24, 0x21), (0x06, 0x11, 0x0F)
MINT, MINT_A, MINT_C, MINT_R = (0x7F, 0xE0, 0xC4), 0.16, (0.47, 0.42), 0.36
BLUE, BLUE_A, BLUE_C, BLUE_R = (0x2F, 0x6B, 0xFF), 0.08, (0.36, 0.35), 0.17
# notification icon design: "head" (head-and-eye profile) or "full" (whole body with an eye hole)
STAT_MODE = os.environ.get("BOOPHIS_STAT_MODE", "full")
CANVAS_DP, VIEW_DP, SAFE_DP = 108, 72, 33
ALPHA_THR = 26          # alpha >= 0.1
# unsharp mask per canvas size: (gaussian radius px, amount)
SHARP = {48: (0.5, 0.35), 72: (0.5, 0.35), 96: (0.55, 0.4), 108: (0.5, 0.3), 144: (0.6, 0.4), 162: (0.6, 0.4),
         192: (0.6, 0.45), 216: (0.7, 0.45), 324: (0.8, 0.5), 432: (0.9, 0.5), 648: (1.0, 0.5)}
# eye treatment (fractions of the near-eye ellipse radius): solid pupil disc up to EYE_PUPIL, transparent ring to EYE_RING
EYE_PUPIL, EYE_RING = 0.45, 0.80

# ---- 2.5D rig features, defined ONCE in hero pixel space (1159x1114) ----
# near eye: tilted ellipse = outer edge of the blue ring (cx, cy, semi-axis a, semi-axis b, rotation deg clockwise)
NEAR_EYE = (412.4, 196.5, 105.5, 100.2, 8.1)
# far eye: circle fitted to its silhouette (cx, cy, r), clipped on the right by the skin edge (blue ring's outer edge)
FAR_EYE_CIRCLE = (154.7, 105.6, 85.3)
FAR_EYE_CLIP = [(152, 10), (149.5, 20), (139.5, 30), (134.5, 40), (128.5, 50), (124.5, 60), (119.5, 70),
                (114.5, 80), (110.5, 90), (106.5, 100), (102.5, 110), (98.5, 120), (94, 129), (30, 129), (30, 10)]
# near-eye catch-light (the white blob in the iris): cx, cy, radius
GLINT = (402.3, 143.8, 10.7)
# throat (gular) region: cream area under the lower-jaw band, above the front legs; top edge = jaw line
THROAT = [(122, 334), (300, 376), (400, 401), (468, 414), (474, 450), (468, 520), (450, 562), (262, 566)]
THROAT_PIVOT_Y = 376.0   # jaw line at the middle of the throat; the pulse scales vertically about this y
THROAT_FEATHER = 0.04    # gaussian sigma as a fraction of the patch short side (~8% soft edge)
# hero -> empty-state.png (1600x1200): x' = x*s + tx (found by minimising pixel error; mean abs diff ~1.4/255)
HERO_TO_EMPTY_STATE = (0.8605, 39.5, 211.75)
# head base: square crop of the hero (may extend past the edges), mapped to 256x256
HEAD_CROP = (-10, -10, 570, 570)
# head base: soft fade-out toward the bottom and right crop edges (fraction of the side where it starts)
HEAD_FADE_BOTTOM, HEAD_FADE_RIGHT = 0.70, 0.94


def p(*parts):
    return os.path.join(ROOT, *parts)


def out(path, im):
    full = p(path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    im.save(full, optimize=True)


def trim(im, thr=1):
    bb = im.getchannel("A").point(lambda v: 255 if v >= thr else 0).getbbox()
    return im.crop(bb)


def centroid_offset(im):
    """Optical centre: half way between bbox centre and alpha centroid."""
    a = np.asarray(im.getchannel("A"), dtype=float)
    ys, xs = np.mgrid[0:a.shape[0], 0:a.shape[1]]
    s = a.sum()
    cx, cy = (a * xs).sum() / s, (a * ys).sum() / s
    return 0.5 * (cx - a.shape[1] / 2), 0.5 * (cy - a.shape[0] / 2)


def place(bird, size, frac):
    """Bird (trimmed RGBA) scaled so its longest side is frac*size, optically centred."""
    s = frac * size / max(bird.size)
    b = bird.resize((max(1, round(bird.width * s)), max(1, round(bird.height * s))), LANCZOS)
    ox, oy = centroid_offset(b)
    c = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c.paste(b, (round((size - b.width) / 2 - ox), round((size - b.height) / 2 - oy)), b)
    return c


def background(size):
    y = np.linspace(0, 1, size)[:, None, None]
    top, bot = np.array(TOP, float), np.array(BOTTOM, float)
    base = np.broadcast_to(top + (bot - top) * y, (size, size, 3)).copy()
    yy, xx = np.mgrid[0:size, 0:size] / size
    for col, a, (cx, cy), r in ((MINT, MINT_A, MINT_C, MINT_R), (BLUE, BLUE_A, BLUE_C, BLUE_R)):
        d = np.hypot(xx - cx, yy - cy) / r
        g = (np.clip(1 - d, 0, 1) ** 1.5 * a)[..., None]
        base = base * (1 - g) + np.array(col, float) * g
    return Image.fromarray(base.round().astype(np.uint8)).convert("RGBA")


def shape_mask(size, kind):
    k = 4
    m = Image.new("L", (size * k, size * k), 0)
    d = ImageDraw.Draw(m)
    if kind == "round":
        d.ellipse((0, 0, size * k - 1, size * k - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, size * k - 1, size * k - 1), radius=0.22 * size * k, fill=255)
    return m.resize((size, size), LANCZOS)


def composite_icon(bird, size, frac, kind=None):
    im = background(size)
    im.alpha_composite(place(bird, size, frac))
    if kind:
        im.putalpha(shape_mask(size, kind))
    return im


def white_from_alpha(im, crisp):
    a = np.asarray(im.getchannel("A"), dtype=float)
    if crisp:
        a = np.clip((a - 96) * 255 / 64, 0, 255)
    w = np.zeros(a.shape + (4,), np.uint8)
    w[..., :3] = 255
    w[..., 3] = a.round().astype(np.uint8)
    return Image.fromarray(w)


def warp_fit(im, xf, size):
    """Like warp() but for transforms whose sub-pixel offset can exceed the pad (ceil/floor box)."""
    s, tx, ty = xf
    x0, y0 = max(0, math.ceil(tx)), max(0, math.ceil(ty))
    x1, y1 = min(size[0], math.floor(tx + im.width * s)), min(size[1], math.floor(ty + im.height * s))
    c = Image.new("RGBA", size, (0, 0, 0, 0))
    c.paste(im.resize((x1 - x0, y1 - y0), LANCZOS, box=((x0 - tx) / s, (y0 - ty) / s, (x1 - tx) / s, (y1 - ty) / s)), (x0, y0))
    return c


# ---------------------------------------------------------------- fitting / sharpening
def _alpha_points(im, thr=ALPHA_THR):
    ys, xs = np.nonzero(np.asarray(im.getchannel("A")) >= thr)
    return np.stack([xs + 0.5, ys + 0.5], 1).astype(np.float32), np.asarray(im.getchannel("A"), float)


def fit_xf(hero, size, frac, bias=0.5):
    """(s, tx, ty): frog scaled so its farthest pixel with alpha>=0.1 is at frac*size from the canvas centre.
    Centre = min enclosing circle centre blended with the alpha centroid (optical centring)."""
    pts, a = _alpha_points(hero)
    hull = cv2.convexHull(pts)
    (ex, ey), _ = cv2.minEnclosingCircle(hull)
    ys, xs = np.mgrid[0:a.shape[0], 0:a.shape[1]]
    cx, cy = (a * (xs + 0.5)).sum() / a.sum(), (a * (ys + 0.5)).sum() / a.sum()
    c = np.array([ex + bias * (cx - ex), ey + bias * (cy - ey)])
    rmax = np.hypot(hull[:, 0, 0] - c[0], hull[:, 0, 1] - c[1]).max()
    s = frac * size / rmax * 0.998
    xf = (s, size / 2 - c[0] * s, size / 2 - c[1] * s)
    # verify on the real raster (resampling ringing) and shrink until inside
    for _ in range(40):
        if radius_px(warp_fit(hero, xf, (size, size)), size) <= frac * size:
            break
        s *= 0.997
        xf = (s, size / 2 - c[0] * s, size / 2 - c[1] * s)
    return xf


def radius_px(im, size):
    ys, xs = np.nonzero(np.asarray(im.getchannel("A")) >= ALPHA_THR)
    return float(np.hypot(xs + 0.5 - size / 2, ys + 0.5 - size / 2).max())


def radius_dp(im, canvas_dp):
    return radius_px(im, im.width) * canvas_dp / im.width


def sharpen(im, key):
    """Light unsharp mask on colour only (alpha-weighted blur so transparent black does not leak into the edge)."""
    radius, amount = SHARP[key]
    a = np.asarray(im.getchannel("A"), np.float32) / 255
    rgb = np.asarray(im.convert("RGB"), np.float32)
    ba = cv2.GaussianBlur(a, (0, 0), radius)
    blur = cv2.GaussianBlur(rgb * a[..., None], (0, 0), radius) / np.maximum(ba, 1e-4)[..., None]
    res = np.where(a[..., None] > 0, np.clip(rgb + amount * (rgb - blur), 0, 255), rgb)
    o = np.dstack([res.round().astype(np.uint8), np.asarray(im.getchannel("A"))])
    return Image.fromarray(o)


def _eye_u(shape):
    """Normalised radial coordinate of every hero pixel in the near-eye ellipse (1.0 = outer edge of the blue ring)."""
    cx, cy, a, b, deg = NEAR_EYE
    yy, xx = np.mgrid[0:shape[0], 0:shape[1]]
    dx, dy = xx + 0.5 - cx, yy + 0.5 - cy
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return np.hypot((dx * c + dy * s) / a, (-dx * s + dy * c) / b)


def carve_eye(hero):
    """White silhouette of the hero with the near eye as a transparent ring around a solid pupil disc."""
    a = np.asarray(hero.getchannel("A"), np.float32)
    u = _eye_u(a.shape)
    w = 0.03
    ring = np.clip((u - EYE_PUPIL) / w + 0.5, 0, 1) * np.clip((EYE_RING - u) / w + 0.5, 0, 1)   # 1 in the gap
    pupil = np.clip((EYE_PUPIL - u) / w + 0.5, 0, 1)
    a = np.maximum(a * (1 - ring), pupil * 255)
    o = np.zeros(a.shape + (4,), np.uint8)
    o[..., :3] = 255
    o[..., 3] = a.round().astype(np.uint8)
    return Image.fromarray(o)


def write_car_xml():
    full = p("car/src/main/res/drawable/ic_car_app.xml")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("""<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_car_app_background" />
    <foreground android:drawable="@drawable/ic_car_app_foreground" />
</adaptive-icon>
""")
    src = open(p("app/src/main/res/drawable/ic_launcher_background.xml"), encoding="utf-8").read()
    src = src.replace("?>\n", "?>\n<!-- SPDX-License-Identifier: GPL-3.0-or-later (copy of the launcher background) -->\n", 1)
    with open(p("car/src/main/res/drawable/ic_car_app_background.xml"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(src)
    old = p("car/src/main/res/drawable-nodpi/ic_car_app.png")
    if os.path.exists(old):
        os.remove(old)


# ---------------------------------------------------------------- notification vector
STAT_EYE = (0.42, 0.78)     # pupil / ring radii as fractions of the near-eye radius
HEAD_CUT_JAW = ((-20, 300), (250, 480), (585, 440))            # quadratic: chin -> throat (control, end)


def _catmull(pts):
    """Closed Catmull-Rom through pts -> list of (P1, c1, c2, P2) cubic segments."""
    n = len(pts)
    segs = []
    for i in range(n):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        segs.append((p1, p1 + (p2 - p0) / 6, p2 - (p3 - p1) / 6, p2))
    return segs


def _bez(seg, n=12):
    p1, c1, c2, p2 = seg
    t = np.linspace(0, 1, n, endpoint=False)[:, None]
    return (1 - t) ** 3 * p1 + 3 * (1 - t) ** 2 * t * c1 + 3 * (1 - t) * t ** 2 * c2 + t ** 3 * p2


def _num(v):
    t = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if t in ("-0", "") else t


def stat_shape(mode):
    """-> (pathData, [flattened subpath polygons in the 24x24 viewport])"""
    hero = Image.open(HERO).convert("RGBA")
    m = (np.asarray(hero.getchannel("A")) >= 128).astype(np.uint8) * 255
    if mode == "head":
        reg = np.zeros_like(m)
        t = np.linspace(0, 1, 60)[:, None]
        p0, pc, pe = (np.array(v, float) for v in HEAD_CUT_JAW)
        jaw = (1 - t) ** 2 * p0 + 2 * (1 - t) * t * pc + t ** 2 * pe
        poly = [(-20, -20), (640, -20), (640, 300)] + [tuple(x) for x in jaw[::-1]]
        cv2.fillPoly(reg, [np.round(np.array(poly)).astype(np.int32)], 255)
        m = cv2.bitwise_and(m, reg)
        sigma, eps = 5.0, 0.10
    else:
        sigma, eps = 12.0, 0.14
    m = np.pad(m, 30)
    m = cv2.GaussianBlur(m, (0, 0), sigma)
    m = (m >= 128).astype(np.uint8) * 255
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    c = max(cs, key=cv2.contourArea)[:, 0, :].astype(np.float32) - 30 + 0.5
    x0, y0 = c.min(0)
    x1, y1 = c.max(0)
    k = 22.0 / max(x1 - x0, y1 - y0)
    ox, oy = 12 - k * (x0 + x1) / 2, 12 - k * (y0 + y1) / 2
    tr = lambda pts: np.asarray(pts, float) * k + (ox, oy)
    ap = cv2.approxPolyDP(c.reshape(-1, 1, 2), eps / k, True)[:, 0, :]
    P = tr(ap)
    segs = _catmull(P)
    d = "M%s,%s" % (_num(P[0][0]), _num(P[0][1]))
    for _, c1, c2, p2 in segs:
        d += " C%s,%s %s,%s %s,%s" % tuple(_num(v) for v in (*c1, *c2, *p2))
    d += " Z"
    polys = [np.vstack([_bez(sg) for sg in segs])]
    ex, ey = tr([[NEAR_EYE[0], NEAR_EYE[1]]])[0]
    er = (NEAR_EYE[2] + NEAR_EYE[3]) / 2 * k * (1.0 if mode == "head" else 1.35)   # full body: eye drawn oversize to read at 24px
    for fr in STAT_EYE[::-1]:
        r = er * fr
        d += " M%s,%s A%s,%s 0 1 0 %s,%s A%s,%s 0 1 0 %s,%s Z" % tuple(_num(v) for v in (
            ex - r, ey, r, r, ex + r, ey, r, r, ex - r, ey))
        ang = np.linspace(0, 2 * math.pi, 96, endpoint=False)
        polys.append(np.stack([ex + r * np.cos(ang), ey + r * np.sin(ang)], 1))
    return d, polys


def raster_stat(polys, px, k=16):
    """Even-odd fill of the flattened subpaths at px x px (24x24 viewport), as an alpha mask (supersampled)."""
    n = px * k
    par = np.zeros((n, n), np.uint8)
    for poly in polys:
        m = Image.new("L", (n, n), 0)
        ImageDraw.Draw(m).polygon([(x * n / 24, y * n / 24) for x, y in poly], fill=1)
        par ^= np.asarray(m)
    a = par.astype(np.float32).reshape(px, k, px, k).mean((1, 3))
    return Image.fromarray((a * 255).round().astype(np.uint8))


def stat_icon(polys, px, tint=(255, 255, 255)):
    im = Image.new("RGBA", (px, px), tint + (0,))
    im.putalpha(raster_stat(polys, px))
    return im


def write_stat_vector():
    d, _ = stat_shape(STAT_MODE)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- SPDX-License-Identifier: GPL-3.0-or-later. Generated by tools/make_brand.py (mode: %s) -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp"\n    android:height="24dp"\n    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
           '    <path\n        android:fillColor="#FFFFFFFF"\n        android:fillType="evenOdd"\n        android:pathData="%s" />\n'
           '</vector>\n') % (STAT_MODE, d)
    full = p("core-player/src/main/res/drawable/ic_stat_boophis.xml")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(xml)
    for dens in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        old = p(f"core-player/src/main/res/drawable-{dens}/ic_stat_boophis.png")
        if os.path.exists(old):
            os.remove(old)


def main():
    hero = trim(Image.open(HERO).convert("RGBA"), 8)

    # in-app mark
    side = max(hero.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(hero, ((side - hero.width) // 2, (side - hero.height) // 2), hero)
    out("ui/src/main/res/drawable-nodpi/boophis_mark.png", sq.resize((512, 512), LANCZOS))

    heroF = Image.open(HERO).convert("RGBA")
    max_dp = 0.0

    # adaptive layers (108dp canvas): frog fitted to the 66dp-diameter safe circle
    mono_src = carve_eye(heroF)
    for dens, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        xf = fit_xf(heroF, px, SAFE_DP / CANVAS_DP)
        fg = sharpen(warp_fit(heroF, xf, (px, px)), px)
        max_dp = max(max_dp, radius_dp(fg, CANVAS_DP))
        out(f"app/src/main/res/mipmap-{dens}/ic_launcher_foreground.png", fg)
        mono = white_from_alpha(warp_fit(mono_src, xf, (px, px)), True)
        max_dp = max(max_dp, radius_dp(mono, CANVAS_DP))
        out(f"app/src/main/res/mipmap-{dens}/ic_launcher_monochrome.png", mono)
    print("adaptive layers: max frog radius (alpha>=0.1) = %.2f dp (safe circle %d dp)" % (max_dp, SAFE_DP))

    # legacy icons (frog inside the shape with a margin)
    for dens, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        for name, kind, rf in (("ic_launcher", "squircle", 0.45), ("ic_launcher_round", "round", 0.43)):
            im = background(px)
            im.alpha_composite(sharpen(warp_fit(heroF, fit_xf(heroF, px, rf), (px, px)), px))
            im.putalpha(shape_mask(px, kind))
            out(f"app/src/main/res/mipmap-{dens}/{name}.png", im)

    # car icon: adaptive (background xml + 648px foreground, same placement as the launcher)
    xf = fit_xf(heroF, 648, SAFE_DP / CANVAS_DP)
    cfg = sharpen(warp_fit(heroF, xf, (648, 648)), 648)
    print("car foreground: max frog radius = %.2f dp" % radius_dp(cfg, CANVAS_DP))
    out("car/src/main/res/drawable-nodpi/ic_car_app_foreground.png", cfg)
    write_car_xml()

    # notification small icon: vector (head-and-eye profile), see stat_icon()
    write_stat_vector()

    # empty state 800x600, ~6% margin each side
    es = trim(Image.open(EMPTY).convert("RGBA"), 8)
    s = min(800 * 0.88 / es.width, 600 * 0.88 / es.height)
    es = es.resize((round(es.width * s), round(es.height * s)), LANCZOS)
    c = Image.new("RGBA", (800, 600), (0, 0, 0, 0))
    c.paste(es, ((800 - es.width) // 2, (600 - es.height) // 2), es)
    out("ui/src/main/res/drawable-nodpi/empty_library.png", c)

    rig()
    sheet()
    before_after()
    print("brand assets written (notification: %s)" % STAT_MODE)

# ---------------------------------------------------------------- rig
def _ellipse_pts(cx, cy, a, b, deg, n=180):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [(cx + a * math.cos(u) * c - b * math.sin(u) * s, cy + a * math.cos(u) * s + b * math.sin(u) * c)
            for u in (2 * math.pi * i / n for i in range(n))]


def _poly_mask(size, pts, xf, k=4):
    """Anti-aliased polygon mask (hero-space points -> dest pixels via xf=(s, tx, ty))."""
    s, tx, ty = xf
    m = Image.new("L", (size[0] * k, size[1] * k), 0)
    ImageDraw.Draw(m).polygon([((x * s + tx) * k, (y * s + ty) * k) for x, y in pts], fill=255)
    return m.resize(size, LANCZOS)


def feature_mask(kind, size, xf):
    """Eye shapes: 'near' ellipse, 'far' visible part. Fully 255 inside, ~2px falloff outside."""
    if kind == "near":
        m = _poly_mask(size, _ellipse_pts(*NEAR_EYE), xf)
    else:
        cx, cy, r = FAR_EYE_CIRCLE
        m = ImageChops.multiply(_poly_mask(size, _ellipse_pts(cx, cy, r, r, 0, 360), xf),
                                _poly_mask(size, FAR_EYE_CLIP, xf))
    return m.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.GaussianBlur(0.7))


def _bbox_of(pts, xf, margin, size):
    s, tx, ty = xf
    xs = [x * s + tx for x, _ in pts]
    ys = [y * s + ty for _, y in pts]
    return (max(0, math.floor(min(xs)) - margin), max(0, math.floor(min(ys)) - margin),
            min(size[0], math.ceil(max(xs)) + margin), min(size[1], math.ceil(max(ys)) + margin))


PAD = 8


def warp(im, xf, size):
    """Resample a hero-sized RGBA into a base's pixel space: dest = src*s + t (Lanczos, float box)."""
    s, tx, ty = xf
    padded = Image.new("RGBA", (im.width + 2 * PAD, im.height + 2 * PAD), (0, 0, 0, 0))
    padded.paste(im, (PAD, PAD))
    x0, y0 = max(0, math.floor(tx)), max(0, math.floor(ty))
    x1, y1 = min(size[0], math.ceil(tx + im.width * s)), min(size[1], math.ceil(ty + im.height * s))
    box = ((x0 - tx) / s + PAD, (y0 - ty) / s + PAD, (x1 - tx) / s + PAD, (y1 - ty) / s + PAD)
    c = Image.new("RGBA", size, (0, 0, 0, 0))
    c.paste(padded.resize((x1 - x0, y1 - y0), LANCZOS, box=box), (x0, y0))
    return c


def head_fade(size):
    """Alpha factor that fades the head crop out toward its bottom and right edges (no hard cut)."""
    w, h = size
    yy, xx = np.mgrid[0:h, 0:w]
    fy = np.clip((h - 1 - yy) / ((1 - HEAD_FADE_BOTTOM) * h), 0, 1)
    fx = np.clip((w - 1 - xx) / ((1 - HEAD_FADE_RIGHT) * w), 0, 1)
    f = (fy * fx) ** 1.5
    return Image.fromarray((f * 255).round().astype(np.uint8))


def _bases():
    """name -> (base image, hero->base transform (s, tx, ty), path)"""
    hero = Image.open(HERO).convert("RGBA")
    bb = hero.getchannel("A").point(lambda v: 255 if v >= 8 else 0).getbbox()
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    side = max(w, h)
    k = 512 / side
    mark_xf = (k, ((side - w) // 2 - bb[0]) * k, ((side - h) // 2 - bb[1]) * k)
    mark_path = "ui/src/main/res/drawable-nodpi/boophis_mark.png"
    mark = Image.open(p(mark_path)).convert("RGBA")

    # head: crop of the hero, transparent outside the frog
    l, t, r, b = HEAD_CROP
    ks = 256 / (r - l)
    head_xf = (ks, -l * ks, -t * ks)
    head = warp(hero, head_xf, (256, 256))
    head.putalpha(ImageChops.multiply(head.getchannel("A"), head_fade(head.size)))
    head_path = "ui/src/main/res/drawable-nodpi/boophis_mark_head.png"
    out(head_path, head)

    # empty: hero -> empty-state -> trimmed, scaled and centred as in main()
    es = Image.open(EMPTY).convert("RGBA")
    ebb = es.getchannel("A").point(lambda v: 255 if v >= 8 else 0).getbbox()
    ew, eh = ebb[2] - ebb[0], ebb[3] - ebb[1]
    s2 = min(800 * 0.88 / ew, 600 * 0.88 / eh)
    rw, rh = round(ew * s2), round(eh * s2)
    sx, sy = rw / ew, rh / eh
    s1, tx1, ty1 = HERO_TO_EMPTY_STATE
    kk = (sx + sy) / 2
    px, py = (800 - rw) // 2, (600 - rh) // 2
    empty_xf = (s1 * kk, (tx1 - ebb[0]) * sx + px, (ty1 - ebb[1]) * sy + py)
    empty_path = "ui/src/main/res/drawable-nodpi/empty_library.png"
    empty = Image.open(p(empty_path)).convert("RGBA")
    return {"mark": (mark, mark_xf, mark_path), "head": (head, head_xf, head_path),
            "empty": (empty, empty_xf, empty_path)}


def _throat_patch(base, xf):
    W, H = base.size
    box = _bbox_of(THROAT, xf, 0, base.size)
    bw, bh = box[2] - box[0], box[3] - box[1]
    sigma = THROAT_FEATHER * min(bw, bh)
    k = int(round(2 * sigma))
    pad = 3 * k + 4
    m = _poly_mask(base.size, THROAT, xf)
    solid = base.getchannel("A").point(lambda v: 255 if v >= 254 else 0)
    m = ImageChops.multiply(m, solid)
    big = Image.new("L", (W + 2 * pad, H + 2 * pad), 0)
    big.paste(m, (pad, pad))
    big = big.filter(ImageFilter.MinFilter(2 * k + 1)).filter(ImageFilter.GaussianBlur(sigma))
    m = big.crop((pad, pad, pad + W, pad + H)).crop(box)
    patch = base.crop(box)
    patch.putalpha(m)
    return patch, box


def rig():
    closed = Image.open(HERO_CLOSED).convert("RGBA")
    hero = Image.open(HERO).convert("RGBA")
    assert closed.size == hero.size, "hero-eyes-closed.png must match hero.png"
    bases = _bases()
    spec = {}
    res = "ui/src/main/res/drawable-nodpi/"
    for name, (base, xf, path) in bases.items():
        W, H = base.size
        wc = warp(closed, xf, base.size)
        if name == "head":
            wc.putalpha(ImageChops.multiply(wc.getchannel("A"), head_fade(base.size)))
        rects = {}
        comp = base.copy()
        for kind in ("near", "far"):
            if kind == "near":
                box = _bbox_of(_ellipse_pts(*NEAR_EYE), xf, 4, base.size)
            else:
                box = _bbox_of(FAR_EYE_CLIP, xf, 4, base.size)
                cb = _bbox_of(_ellipse_pts(FAR_EYE_CIRCLE[0], FAR_EYE_CIRCLE[1], FAR_EYE_CIRCLE[2],
                                           FAR_EYE_CIRCLE[2], 0), xf, 4, base.size)
                box = (max(box[0], cb[0]), max(box[1], cb[1]), min(box[2], cb[2]), min(box[3], cb[3]))
            m = feature_mask(kind, base.size, xf)
            lid = wc.copy()
            lid.putalpha(ImageChops.multiply(lid.getchannel("A"), m))
            lid = lid.crop(box)
            out(res + f"rig_{name}_lid_{kind}.png", lid)
            comp.alpha_composite(lid, box[:2])
            rects[kind] = (box[0] / W, box[1] / H, (box[2] - box[0]) / W, (box[3] - box[1]) / H)
            # leak check: iris-like pixels (blue ring, orange iris, black pupil) inside the mask after compositing
            a = np.asarray(comp).astype(int)
            inside = np.asarray(m) > 200
            blueish = (a[..., 2] > a[..., 0] + 50) & (a[..., 2] > a[..., 1] + 10)
            orange = (a[..., 0] > a[..., 1] + 70) & (a[..., 0] > 150)
            dark = a[..., :3].max(2) < 40
            leak = ((blueish | orange | dark) & inside & (a[..., 3] > 200)).sum()
            print(f"  {name}/{kind}: iris-like pixels left inside lid mask: {leak}")
        patch, tb = _throat_patch(base, xf)
        out(res + f"rig_{name}_throat.png", patch)
        chk = base.copy()
        chk.alpha_composite(patch, tb[:2])
        diff = np.abs(np.asarray(chk).astype(int) - np.asarray(base).astype(int)).max()
        print(f"  {name}: throat patch at scale 1.0 vs base, max abs diff = {diff}")
        s, tx, ty = xf
        piv = THROAT_PIVOT_Y * s + ty
        gx, gy, gr = GLINT
        spec[name] = (rects, (tb[0] / W, tb[1] / H, (tb[2] - tb[0]) / W, (tb[3] - tb[1]) / H),
                      (piv - tb[1]) / (tb[3] - tb[1]), ((gx * s + tx) / W, (gy * s + ty) / H, gr * s / W))
        if name == "empty":
            # residual of the hero->empty transform on the frog (left of the reel)
            wh = np.asarray(warp(hero, xf, base.size)).astype(float)
            b = np.asarray(base).astype(float)
            mm = (wh[..., 3] > 250) & (b[..., 3] > 250)
            mm[:, int(0.52 * W):] = False
            print("  hero->empty transform: scale %.5f tx %.2f ty %.2f; mean abs RGB residual %.2f/255 over %d px"
                  % (s, tx, ty, np.abs(wh[..., :3] - b[..., :3]).mean(2)[mm].mean(), mm.sum()))
    write_spec(spec)
    rig_sheet(bases, spec)


def _f(v):
    return "%.4ff" % v


def _rect(r):
    return "null" if r is None else "RigRect(%s, %s, %s, %s)" % tuple(_f(v) for v in r)


def write_spec(spec):
    lines = ["// SPDX-License-Identifier: GPL-3.0-or-later",
             "// Generated by tools/make_brand.py — do not edit.",
             "package app.boophis.ui", "",
             "/** Fractions of the base image (left, top, width, height). */",
             "internal data class RigRect(val l: Float, val t: Float, val w: Float, val h: Float)", "",
             "internal data class RigSpec(",
             "    val lidNear: RigRect, val lidFar: RigRect?, val throat: RigRect,",
             "    /** Fraction of the throat patch height where the pulse is anchored (jaw line). */",
             "    val throatPivotY: Float,",
             "    /** Near-eye catch-light: centre (fractions of base width, height) and radius (fraction of width). */",
             "    val glintX: Float, val glintY: Float, val glintR: Float,",
             ")", "", "internal object FrogRig {"]
    for name, key in (("Mark", "mark"), ("Head", "head"), ("Empty", "empty")):
        rects, throat, piv, (gx, gy, gr) = spec[key]
        lines += [f"    val {name} = RigSpec(",
                  f"        lidNear = {_rect(rects['near'])},",
                  f"        lidFar = {_rect(rects['far'])},",
                  f"        throat = {_rect(throat)},",
                  f"        throatPivotY = {_f(piv)},",
                  f"        glintX = {_f(gx)}, glintY = {_f(gy)}, glintR = {_f(gr)},", "    )"]
    lines += ["}", ""]
    full = p("ui/src/main/kotlin/app/boophis/ui/FrogRigSpec.kt")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))


def rig_sheet(bases, spec):
    BG = (0x07, 0x12, 0x0F, 255)
    res = "ui/src/main/res/drawable-nodpi/"
    PW = 500
    panels = []
    for name in ("mark", "head", "empty"):
        base, xf, _ = bases[name]
        W, H = base.size
        row = [base.copy(), base.copy(), base.copy()]
        for kind in ("near", "far"):
            r = spec[name][0][kind]
            if r is None:
                continue
            lid = Image.open(p(res + f"rig_{name}_lid_{kind}.png")).convert("RGBA")
            row[1].alpha_composite(lid, (round(r[0] * W), round(r[1] * H)))
        th = Image.open(p(res + f"rig_{name}_throat.png")).convert("RGBA")
        tr, piv = spec[name][1], spec[name][2]
        tl, tt = round(tr[0] * W), round(tr[1] * H)
        nh = round(th.height * 1.06)
        st = th.resize((th.width, nh), LANCZOS)
        row[2].alpha_composite(st, (tl, round(tt + piv * th.height - piv * nh)))
        k = PW / W
        out_row = []
        for i, im in enumerate(row):
            bg = Image.new("RGBA", im.size, BG)
            bg.alpha_composite(im)
            bg = bg.resize((PW, round(H * k)), LANCZOS)
            if i == 2:
                d = ImageDraw.Draw(bg)
                s, tx, ty = xf
                pts = [((x * s + tx) * k, (y * s + ty) * k) for x, y in THROAT]
                d.line(pts + [pts[0]], fill=(255, 40, 40, 255), width=1)
            out_row.append(bg)
        panels.append(out_row)
    sizes = (34, 44, 96)
    top = 24
    height = top + sum(r[0].height + top for r in panels) + 140 + top
    S = Image.new("RGBA", (PW * 3 + 40, height), BG)
    d = ImageDraw.Draw(S)
    y = top
    for name, row in zip(("mark", "head", "empty"), panels):
        for i, (im, lab) in enumerate(zip(row, ("as is", "lids closed", "throat x1.06 from pivot + outline"))):
            S.alpha_composite(im, (i * (PW + 20), y))
            d.text((i * (PW + 20) + 4, y - 14), f"{name}: {lab}", fill=(220, 230, 225, 255))
        y += row[0].height + top
    x = 4
    d.text((4, y - 14), "head vs mark at 34 / 44 / 96 px actual size", fill=(220, 230, 225, 255))
    for nm in ("head", "mark"):
        im = bases[nm][0]
        for sz in sizes:
            bgc = Image.new("RGBA", (sz, sz), BG)
            bgc.alpha_composite(im.resize((sz, sz), LANCZOS))
            S.alpha_composite(bgc, (x, y))
            x += sz + 14
        x += 30
    os.makedirs(p("docs/screens/brand"), exist_ok=True)
    S.convert("RGB").save(p("docs/screens/brand/rig-sheet.png"))


def on(bg, im, xy):
    bg.alpha_composite(im.convert("RGBA"), xy)


def _tint(im, rgb):
    im = im.convert("RGBA")
    a = im.getchannel("A")
    o = Image.new("RGBA", im.size, tuple(rgb) + (255,))
    o.putalpha(a)
    return o


def _viewport_circle_or_squircle(canvas, out_px, kind, safe=False):
    """canvas: RGBA 108dp canvas (any pixel size) -> the 72dp visible viewport masked, resized to out_px."""
    w = canvas.width
    big = canvas.resize((round(out_px * CANVAS_DP / VIEW_DP),) * 2, LANCZOS)
    m = round((big.width - out_px) / 2)
    vp = big.crop((m, m, m + out_px, m + out_px))
    if safe:
        k = 4
        ov = Image.new("RGBA", (out_px * k, out_px * k), (0, 0, 0, 0))
        r = SAFE_DP / VIEW_DP * out_px * k
        c = out_px * k / 2
        ImageDraw.Draw(ov).ellipse((c - r, c - r, c + r, c + r), outline=(255, 255, 255, 110), width=k)
        vp.alpha_composite(ov.resize((out_px, out_px), LANCZOS))
    o = Image.new("RGBA", (out_px, out_px), (0, 0, 0, 0))
    o.paste(vp, (0, 0), shape_mask(out_px, kind))
    return o


def _adaptive(fg, bgsize=None):
    ad = background(fg.width)
    ad.alpha_composite(fg)
    return ad


def _mono_tile(mono, out_px, kind, tile, tint):
    bg = Image.new("RGBA", mono.size, tile + (255,))
    bg.alpha_composite(_tint(mono, tint))
    return _viewport_circle_or_squircle(bg, out_px, kind)


def _car_circle(canvas, px):
    return _viewport_circle_or_squircle(canvas, px, "round")


def _car_before(png, px):
    """Android Auto showed the full-bleed square PNG as a dark square inset in a white disc."""
    k = 4
    disc = Image.new("RGBA", (px * k, px * k), (0, 0, 0, 0))
    ImageDraw.Draw(disc).ellipse((0, 0, px * k - 1, px * k - 1), fill=(255, 255, 255, 255))
    disc = disc.resize((px, px), LANCZOS)
    inner = round(px * 0.70)
    disc.alpha_composite(png.convert("RGBA").resize((inner, inner), LANCZOS), ((px - inner) // 2, (px - inner) // 2))
    return disc


def _strip(items, bg, pad=12, gap=12, minh=0):
    w = pad * 2 + sum(i.width for i in items) + gap * (len(items) - 1)
    h = max(max(i.height for i in items) + 2 * pad, minh)
    s = Image.new("RGBA", (w, h), bg)
    x = pad
    for i in items:
        s.alpha_composite(i, (x, (h - i.height) // 2))
        x += i.width + gap
    return s


# The icons-before-after sheet compares against the last commit before the 2026-11-18 icon pass.
BEFORE_REF = "bd437c0"


def _git(path):
    import io
    import subprocess
    data = subprocess.run(["git", "show", BEFORE_REF + ":" + path], cwd=ROOT, check=True, capture_output=True).stdout
    return Image.open(io.BytesIO(data)).convert("RGBA")


def before_after():
    SB = (40, 40, 48, 255)
    mip = "app/src/main/res/mipmap-xxxhdpi/"
    stat_px = (24, 36, 48, 72)
    stat_den = {24: "mdpi", 36: "hdpi", 48: "xhdpi", 72: "xxhdpi"}
    _, polys = stat_shape(STAT_MODE)
    before = {
        "fg": _git(mip + "ic_launcher_foreground.png"), "mono": _git(mip + "ic_launcher_monochrome.png"),
        "leg": _git(mip + "ic_launcher.png"), "legr": _git(mip + "ic_launcher_round.png"),
        "car": _git("car/src/main/res/drawable-nodpi/ic_car_app.png"),
        "stat": lambda px: _git(f"core-player/src/main/res/drawable-{stat_den[px]}/ic_stat_boophis.png") if px in stat_den
        else _git("core-player/src/main/res/drawable-mdpi/ic_stat_boophis.png").resize((px, px), LANCZOS),
    }
    cfg = Image.open(p("car/src/main/res/drawable-nodpi/ic_car_app_foreground.png")).convert("RGBA")
    cbg = background(cfg.width)
    cbg.alpha_composite(cfg)
    after = {
        "fg": Image.open(p(mip + "ic_launcher_foreground.png")).convert("RGBA"),
        "mono": Image.open(p(mip + "ic_launcher_monochrome.png")).convert("RGBA"),
        "leg": Image.open(p(mip + "ic_launcher.png")).convert("RGBA"),
        "legr": Image.open(p(mip + "ic_launcher_round.png")).convert("RGBA"),
        "stat": lambda px: stat_icon(polys, px),
    }
    rows = []

    def launcher(src):
        ad = _adaptive(src["fg"])
        return _strip([_viewport_circle_or_squircle(ad, 48, "round"), _viewport_circle_or_squircle(ad, 96, "round", True),
                       _viewport_circle_or_squircle(ad, 48, "squircle"), _viewport_circle_or_squircle(ad, 96, "squircle")], SB)

    def themed(src):
        m = src["mono"]
        return _strip([_mono_tile(m, 48, "round", (0xD9, 0xE2, 0xF5), (0x1B, 0x2A, 0x4A)),
                       _mono_tile(m, 96, "round", (0xD9, 0xE2, 0xF5), (0x1B, 0x2A, 0x4A)),
                       _mono_tile(m, 48, "round", (0x2B, 0x30, 0x40), (0xC9, 0xD8, 0xFF)),
                       _mono_tile(m, 96, "round", (0x2B, 0x30, 0x40), (0xC9, 0xD8, 0xFF))], SB)

    def legacy(src):
        f = lambda im, n: im.resize((n, n), LANCZOS)
        return _strip([f(src["leg"], 48), f(src["legr"], 48), f(src["leg"], 96), f(src["legr"], 96)], SB)

    def car(src, before_png=None):
        mk = (lambda px: _car_before(before_png, px)) if before_png is not None else (lambda px: _car_circle(cbg, px))
        dark = _strip([mk(56), mk(96)], (0, 0, 0, 255))
        light = _strip([mk(56), mk(96)], (0xB8, 0xC4, 0xCC, 255))
        return _strip([dark, light], SB, pad=0, gap=8)

    def notif(src):
        blocks = []
        for bar, ink in (((0x20, 0x21, 0x24, 255), (255, 255, 255)), ((0xF1, 0xF3, 0xF4, 255), (0x20, 0x21, 0x24))):
            blocks.append(_strip([_tint(src["stat"](n), ink) for n in stat_px], bar, pad=10, gap=10, minh=96))
        blocks.append(_strip([_tint(src["stat"](20), (0x7F, 0xE0, 0xC4))], (0x47, 0x4D, 0x4D, 255), pad=14, minh=96))
        return _strip(blocks, SB, pad=0, gap=8)

    def splash(src):
        ad = _adaptive(src["fg"] if "fg" in src else None)
        big = ad.resize((240, 240), LANCZOS)
        m = Image.new("L", (240 * 4, 240 * 4), 0)
        ImageDraw.Draw(m).ellipse((40 * 4, 40 * 4, 200 * 4 - 1, 200 * 4 - 1), fill=255)   # 160dp circle
        circ = Image.new("RGBA", (240, 240), (0, 0, 0, 0))
        circ.paste(big, (0, 0), m.resize((240, 240), LANCZOS))
        return _strip([circ], (0x06, 0x11, 0x0F, 255), pad=0, minh=240)

    rows = [("launcher: circle / squircle,\n72dp viewport at 48 + 96dp\n(safe circle 33dp stamped)", launcher(before), launcher(after)),
            ("themed monochrome (Android 13):\nlight pastel tile + dark tint,\ndark tile + light tint", themed(before), themed(after)),
            ("legacy square / round\n48px and 96px", legacy(before), legacy(after)),
            ("car icon as Android Auto shows it\n(circle) 56 / 96px on #000 rail\nand light #B8C4CC rail", car(before, before["car"]), car(after)),
            ("notification icon 24/36/48/72px\non dark + light status bar;\nAA media card ~20px mint on #474D4D", notif(before), notif(after)),
            ("Android 12+ splash: icon in a\n160dp circle on night canopy #06110F", splash(before), splash(after))]
    LW, G = 230, 14
    wb = max(r[1].width for r in rows)
    wa = max(r[2].width for r in rows)
    W = LW + wb + wa + 3 * G
    H = 40 + sum(max(r[1].height, r[2].height) + G for r in rows) + G
    S = Image.new("RGBA", (W, H), (28, 28, 34, 255))
    d = ImageDraw.Draw(S)
    d.text((LW + G, 14), "BEFORE (" + BEFORE_REF + ")", fill=(255, 200, 120, 255))
    d.text((LW + 2 * G + wb, 14), "AFTER (this tree)  stat icon design: " + STAT_MODE, fill=(140, 235, 200, 255))
    y = 40
    for lab, b, a in rows:
        h = max(b.height, a.height)
        d.multiline_text((8, y + 6), lab, fill=(225, 225, 225, 255), spacing=3)
        S.alpha_composite(b, (LW + G, y))
        S.alpha_composite(a, (LW + 2 * G + wb, y))
        y += h + G
    assert S.width <= 2000, S.width
    S.convert("RGB").save(p("docs/screens/brand/icons-before-after.png"))
    print("icons-before-after.png %dx%d" % S.size)


def sheet():
    S = Image.new("RGBA", (1600, 1200), (40, 40, 48, 255))
    d = ImageDraw.Draw(S)

    def lab(t, x, y):
        d.text((x, y), t, fill=(230, 230, 230, 255))

    hero = Image.open(HERO).convert("RGBA")
    h = Image.new("RGBA", (380, 380), (7, 7, 12, 255))
    t = hero.copy()
    t.thumbnail((360, 360))
    h.alpha_composite(t, ((380 - t.width) // 2, (380 - t.height) // 2))
    on(S, h, (20, 30)); lab("hero on dark", 20, 12)
    mark = Image.open(p("ui/src/main/res/drawable-nodpi/boophis_mark.png"))
    m = Image.new("RGBA", (256, 256), (7, 7, 12, 255))
    m.alpha_composite(mark.resize((256, 256), LANCZOS))
    on(S, m, (420, 30)); lab("mark 512 (shown 256)", 420, 12)
    fg = Image.open(p("app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png")).convert("RGBA")
    ad = _adaptive(fg)
    for i, kind in enumerate(("round", "squircle")):
        on(S, _viewport_circle_or_squircle(ad, 216, kind, safe=(i == 0)), (720 + i * 230, 30))
    lab("adaptive: masks on the 72dp visible viewport (33dp safe circle)", 720, 12)
    mono = Image.open(p("app/src/main/res/mipmap-xxxhdpi/ic_launcher_monochrome.png")).convert("RGBA")
    on(S, _mono_tile(mono, 216, "round", (128, 128, 128), (255, 255, 255)), (1200, 30)); lab("monochrome on grey", 1200, 12)
    leg = Image.open(p("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"))
    legr = Image.open(p("app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"))
    on(S, leg, (720, 290)); on(S, legr, (940, 290)); lab("legacy square / round (192)", 720, 272)
    cfg = Image.open(p("car/src/main/res/drawable-nodpi/ic_car_app_foreground.png")).convert("RGBA")
    on(S, _car_circle(_adaptive(cfg), 216), (1200, 280)); lab("car icon (adaptive), circle mask", 1200, 262)
    es = Image.new("RGBA", (800, 600), (7, 7, 12, 255))
    es.alpha_composite(Image.open(p("ui/src/main/res/drawable-nodpi/empty_library.png")))
    on(S, es, (20, 560)); lab("empty state on #07070C", 20, 542)
    _, polys = stat_shape(STAT_MODE)
    gg = Image.new("RGBA", (384, 384), (90, 90, 96, 255))
    gg.alpha_composite(stat_icon(polys, 96).resize((384, 384), Image.NEAREST))
    on(S, gg, (860, 580)); lab("notification vector rasterised at 96px, 4x zoom, on grey", 860, 562)
    for i, bgc in enumerate(((90, 90, 96, 255), (30, 30, 36, 255))):
        g24 = Image.new("RGBA", (24, 24), bgc); g24.alpha_composite(stat_icon(polys, 24))
        on(S, g24, (860 + i * 40, 1000))
    lab("24 px actual (grey / dark)", 940, 1006)
    os.makedirs(p("docs/screens/brand"), exist_ok=True)
    S.convert("RGB").save(p("docs/screens/brand/brand-sheet.png"))


if __name__ == "__main__":
    sys.exit(main())
