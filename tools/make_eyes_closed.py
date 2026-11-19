# SPDX-License-Identifier: GPL-3.0-or-later
# Builds assets/brand/hero-eyes-closed.png from assets/brand/hero.png: identical pixels everywhere except inside the
# two eyes (feature masks come from tools/make_brand.py), where a closed eyelid is drawn.
#   python tools/make_eyes_closed.py qwen   <near.png> <far.png> [out]   transplant IrisEcho edits (Qwen Image 2.1 edit,
#                                                                         prompts in assets/prompts/rig-eyes-closed-*.json)
#   python tools/make_eyes_closed.py proc   [out]                        procedural lids (Pillow + numpy)
# Only needed when hero.png changes; make_brand.py just reads the result.
import math
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_brand as mb  # noqa: E402

ID = (1.0, 0.0, 0.0)
LANCZOS = Image.LANCZOS


def eye_masks(size):
    return {k: np.asarray(mb.feature_mask(k, size, ID), float)[..., None] / 255 for k in ("near", "far")}


def qwen(near_png, far_png, dest):
    hero = Image.open(mb.HERO).convert("RGBA")
    a = np.asarray(hero).astype(float)
    res = a.copy()
    ms = eye_masks(hero.size)
    for kind, f in (("near", near_png), ("far", far_png)):
        q = np.asarray(Image.open(f).convert("RGB").resize(hero.size, LANCZOS), float)
        res[..., :3] = q * ms[kind] + res[..., :3] * (1 - ms[kind])
    # the model paints its own background colour into the semi-transparent silhouette pixels: refill them from the
    # solid lid pixels next to them (normalised convolution)
    core = np.asarray(Image.fromarray((a[..., 3] >= 250).astype(np.uint8) * 255).filter(ImageFilter.MinFilter(19))) > 0
    solid = core[..., None] & (np.maximum(ms["near"], ms["far"]) > 0.5)
    wgt = solid.astype(float)
    num, den = _blur(res[..., :3] * wgt, 5), _blur(np.repeat(wgt, 3, 2), 5)
    fill = num / np.maximum(den, 1e-3)
    edge = ~core[..., None] & (np.maximum(ms["near"], ms["far"]) > 0) & (den > 1e-3)
    res[..., :3] = np.where(edge, fill, res[..., :3])
    Image.fromarray(res.round().astype(np.uint8)).save(dest, optimize=True)


def _blur(arr, s):
    """Separable gaussian blur of an HxWx3 float array (reflect padding)."""
    r = int(3 * s)
    k = np.exp(-(np.arange(-r, r + 1) ** 2) / (2 * s * s))
    k /= k.sum()
    h, w = arr.shape[:2]
    pad = np.pad(arr, ((r, r), (r, r), (0, 0)), mode="reflect")
    tmp = sum(k[i] * pad[i:i + h] for i in range(2 * r + 1))
    return sum(k[i] * tmp[:, i:i + w] for i in range(2 * r + 1))


def _donor(hero_rgb, box, size, seed):
    x0, y0, x1, y1 = box
    patch = hero_rgb[y0:y1, x0:x1]
    res = patch - _blur(patch, 3)
    rng = np.random.default_rng(seed)
    h, w = size
    out = np.zeros((h, w, 3))
    ph, pw = res.shape[:2]
    for y in range(0, h, ph - 8):
        for x in range(0, w, pw - 8):
            t = res
            if rng.random() < .5:
                t = t[:, ::-1]
            if rng.random() < .5:
                t = t[::-1]
            hh, ww = min(ph, h - y), min(pw, w - x)
            out[y:y + hh, x:x + ww] = t[:hh, :ww]
    return out


def proc(dest):
    hero = Image.open(mb.HERO).convert("RGBA")
    a = np.asarray(hero).astype(float)
    rgb = a[..., :3]
    H, W = rgb.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W].astype(float)
    tex = _donor(rgb, (150, 215, 300, 290), (H, W), 3)
    ms = eye_masks(hero.size)
    res = a.copy()
    L = np.array([-0.45, -0.55, 0.70]); L /= np.linalg.norm(L)

    def lid(kind, cx, cy, ax, ay, deg, skin_box, crease):
        c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
        dx, dy = xx - cx, yy - cy
        u = (dx * c + dy * s) / ax
        v = (-dx * s + dy * c) / ay
        r2 = np.clip(u * u + v * v, 0, 1)
        nz = np.sqrt(1 - r2)
        # normal in image axes (undo tilt)
        nx = u * c - v * s
        ny = u * s + v * c
        lam = np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)
        x0, y0, x1, y1 = skin_box
        sk = rgb[y0:y1, x0:x1].reshape(-1, 3)
        sk = sk[(sk[:, 0] < sk[:, 1]) & (sk[:, 2] < sk[:, 1] + 20)]   # mint skin only: no spots, no iris
        base = np.median(sk, 0)
        shade = 0.74 + 0.40 * lam - 0.14 * np.clip(v, 0, 1) * r2
        col = base * shade[..., None]
        spec = np.exp(-(((u + 0.38) ** 2 + (v + 0.42) ** 2) / 0.05)) * 38
        col = col + spec[..., None] * np.array([1, 1, .95]) + tex * 0.9
        # crease: darker line low on the dome, soft lighter lip just under it
        a0, a1, a2 = crease
        f = a0 + a1 * u * u
        d = np.abs(v - f) * ay
        fade = np.clip(1 - (np.abs(u) / 0.92) ** 4, 0, 1)
        dark = np.exp(-(d / 1.5) ** 2) * fade * 0.55
        lip = np.exp(-(((v - f) * ay - 3.5) / 2.2) ** 2) * fade * 0.10
        col = col * (1 - dark[..., None]) + 255 * lip[..., None] * 0.5
        # match the skin right at the rim so the edge disappears
        m = ms[kind]
        res[..., :3] = np.clip(col, 0, 255) * m + res[..., :3] * (1 - m)

    cx, cy, ax, ay, deg = mb.NEAR_EYE
    lid("near", cx, cy, ax, ay, deg, (520, 120, 600, 250), (0.22, 0.16, 0))
    fx, fy, fr = mb.FAR_EYE_CIRCLE
    lid("far", fx, fy, fr, fr, 0, (150, 20, 260, 100), (0.05, 0.15, 0))
    Image.fromarray(res.round().astype(np.uint8)).save(dest, optimize=True)


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "qwen":
        qwen(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else mb.HERO_CLOSED)
    else:
        proc(sys.argv[2] if len(sys.argv) > 2 else mb.HERO_CLOSED)
