# SPDX-License-Identifier: GPL-3.0-or-later
"""WCAG 2.x contrast check over Boophis's day and night palettes (ui/.../Theme.kt).

usage: python tools/contrast.py      exits 1 if a required pair is below its minimum
Text pairs need 4.5:1 (AA, normal text); large titles and icons/graphics need 3:1.
"""
import re, sys, os

THEME = os.path.join(os.path.dirname(__file__), "..", "ui", "src", "main", "kotlin", "app", "boophis", "ui", "Theme.kt")


def palettes():
    src = open(THEME, encoding="utf-8").read()
    out = {}
    for name in ("NightPalette", "DayPalette"):
        body = src[src.index(f"val {name} = Palette("):]
        body = body[: body.index("\n)")]
        out[name] = {k: int(v, 16) for k, v in re.findall(r"(\w+) = Color\(0x([0-9A-Fa-f]{8})\)", body)}
    return out


def rgba(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255, ((c >> 24) & 255) / 255)


def over(fg, bg):
    r, g, b, a = rgba(fg)
    R, G, B, _ = rgba(bg)
    return 0xFF000000 | (round(r * a + R * (1 - a)) << 16) | (round(g * a + G * (1 - a)) << 8) | round(b * a + B * (1 - a))


def lum(c):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b, _ = rgba(c)
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def ratio(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


# (foreground, background, minimum, what)
PAIRS = [
    ("textPrimary", "background", 4.5, "body text"),
    ("textPrimary", "surface", 4.5, "text on cards"),
    ("textPrimary", "surfaceHigh", 4.5, "text on raised cards"),
    ("textSecondary", "background", 4.5, "secondary text"),
    ("textSecondary", "surface", 4.5, "secondary text on cards"),
    ("textSecondary", "surfaceHigh", 4.5, "secondary text on raised cards"),
    ("onAccent", "accent", 4.5, "button label on mint"),
    ("onAccent", "accent2", 4.5, "button label on deep mint (gradient end)"),
    ("accent", "background", 3.0, "mint title gradient start / icons"),
    ("accent2", "background", 3.0, "mint title gradient end / icons"),
    ("accent", "surface", 3.0, "mint icons on cards"),
    ("ring", "background", 3.0, "progress ring"),
    ("coral", "background", 3.0, "coral badges/spots"),
    ("coral", "surface", 3.0, "coral badges on cards"),
    ("iris", "background", 3.0, "eye badge iris"),
]


def main():
    bad = 0
    for name, p in palettes().items():
        print(name)
        for fg, bg, need, what in PAIRS:
            f, b = p[fg], p[bg]
            if (b >> 24) < 255:
                b = over(b, p["background"])
            f = over(f, b)
            r = ratio(f, b)
            flag = "ok " if r >= need else "LOW"
            bad += r < need
            print(f"  {flag} {r:5.2f} (need {need}) {fg} on {bg}: {what}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
