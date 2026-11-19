# SPDX-License-Identifier: GPL-3.0-or-later
"""README banner: the mascot and the wordmark over the night-canopy backdrop.

Reads assets/brand/banner-bg.(png|jpg) and assets/brand/hero.png, sets the wordmark and tagline in
the app's own fonts, and writes docs/banner.jpg (2400x900, the README header) and
docs/social-preview.jpg (1280x640, GitHub's 2:1 link card), with no metadata. Pillow + numpy only.

The banner is read at GitHub's ~830 px column and on ~390 px phones, so the text is sized for the
phone (the tagline stays >= 13 px tall there) and must sit on the dark side: the script measures the
contrast between each text colour and the backdrop behind it and fails below 7:1. The 2:1 card
uses the same layout, scaled to the width and centred vertically, inside GitHub's 40 px safe border.
usage: python tools/make_banner.py
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INK, TEXT, TAG, MINT = (6, 17, 15), (238, 243, 232), (214, 230, 222), (127, 224, 196)
TEXT_RIGHT = 0.74  # the text block must end left of this fraction of the width
MIN_CONTRAST = 7.0


def p(*parts):
    return os.path.join(ROOT, *parts)


def font(name, size, weight):
    f = ImageFont.truetype(p("ui", "src", "main", "res", "font", name), size)
    f.set_variation_by_axes([weight if a["name"] == b"Weight" else a["default"] for a in f.get_variation_axes()])
    return f


def cover(img, w, h):
    s = max(w / img.width, h / img.height)
    img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
    x, y = (img.width - w) // 2, (img.height - h) // 2
    return img.crop((x, y, x + w, y + h))


def luminance(rgb):
    c = np.asarray(rgb, dtype=np.float64) / 255.0
    c = np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return c[..., 0] * 0.2126 + c[..., 1] * 0.7152 + c[..., 2] * 0.0722


def render(W, H, out, final=None):
    k = min(W / 2400, H / 900)  # the layout was designed at 2400x900
    oy = (H - 900 * k) / 2

    def S(v):
        return round(v * k)

    bg_path = next(q for q in (p("assets", "brand", "banner-bg.png"), p("assets", "brand", "banner-bg.jpg"))
                   if os.path.exists(q))
    canvas = cover(Image.open(bg_path).convert("RGB"), W, H).convert("RGBA")

    # Night-canopy shade: solid under the text, fading out so the foliage on the right stays bright.
    ramp = np.clip((W * 0.86 - np.arange(W)) / (W * 0.86 - W * TEXT_RIGHT), 0, 1) ** 1.2
    shade = Image.fromarray(np.tile((ramp * 235).astype(np.uint8), (H, 1)))
    canvas = Image.composite(Image.new("RGBA", (W, H), INK + (255,)), canvas, shade)

    # Mascot with a soft mint glow behind it.
    hero = Image.open(p("assets", "brand", "hero.png")).convert("RGBA")
    hh = S(620)
    hero = hero.resize((round(hero.width * hh / hero.height), hh), Image.LANCZOS)
    hx, hy = max(S(70), round(W * 0.035)), round(oy) + (S(900) - hh) // 2 + S(10)
    glow = Image.new("RGBA", (W, H))
    ImageDraw.Draw(glow).ellipse((hx, hy + S(80), hx + hero.width, hy + hh), fill=MINT + (60,))
    canvas = Image.alpha_composite(canvas, glow.filter(ImageFilter.GaussianBlur(S(110))))
    canvas.alpha_composite(hero, (hx, hy))

    title_f = font("bricolage_grotesque.ttf", S(270), 800)
    tag_f = font("atkinson_hyperlegible_next.ttf", S(96), 600)
    lines = [("Boophis", title_f, TEXT), ("Your own videos,", tag_f, TAG), ("on your phone", tag_f, TAG),
             ("and in the car.", tag_f, TAG)]
    tx = hx + hero.width + S(40)
    ys = [round(oy) + S(y) for y in (95, 420, 535, 650)]

    # Text, with a soft dark halo so it holds up over any pixel of the backdrop.
    halo = Image.new("RGBA", (W, H))
    hd = ImageDraw.Draw(halo)
    for (s, f, _), y in zip(lines, ys):
        hd.text((tx, y), s, font=f, fill=INK + (200,), stroke_width=S(14), stroke_fill=INK + (200,))
    canvas = Image.alpha_composite(canvas, halo.filter(ImageFilter.GaussianBlur(S(22))))
    backdrop = np.asarray(canvas.convert("RGB"))
    d = ImageDraw.Draw(canvas)
    worst = []
    for (s, f, col), y in zip(lines, ys):
        x0, y0, x1, y1 = d.textbbox((tx, y), s, font=f)
        if x1 > W * TEXT_RIGHT:
            sys.exit(f"text '{s}' ends at {x1}px, past {TEXT_RIGHT:.0%} of the width; shorten it")
        mask = Image.new("L", (W, H))
        ImageDraw.Draw(mask).text((tx, y), s, font=f, fill=255)
        under = backdrop[np.asarray(mask) > 128]
        bg_l = np.percentile(luminance(under), 98)  # the brightest backdrop pixels behind the glyphs
        worst.append(((luminance(np.array(col)) + 0.05) / (bg_l + 0.05), s))
        d.text((tx, y), s, font=f, fill=col)
    for ratio, s in worst:
        print(f"contrast {ratio:5.1f}:1  {s}")
    if min(r for r, _ in worst) < MIN_CONTRAST:
        sys.exit(f"contrast below {MIN_CONTRAST}:1")

    img = canvas.convert("RGB")
    if final:
        img = img.resize(final, Image.LANCZOS)
    img.save(out, quality=88, optimize=True, progressive=True)
    print(out, img.size, os.path.getsize(out) // 1024, "KB")


def main():
    render(2400, 900, p("docs", "banner.jpg"))
    render(2560, 1280, p("docs", "social-preview.jpg"), final=(1280, 640))


if __name__ == "__main__":
    main()
