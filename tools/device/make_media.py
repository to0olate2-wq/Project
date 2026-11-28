#!/usr/bin/env python3
"""Pictures for the made-up provider (8.54): channel logos, posters and backdrops, 60 of each.

  python3 make_media.py <media dir>

The videos are made by ffmpeg in the workflow (live.ts, film.mp4, film-hevc.mkv).
"""
import colorsys
import os
import sys

from PIL import Image, ImageDraw, ImageFont

out = sys.argv[1] if len(sys.argv) > 1 else "media"
N = 60


def font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def colour(i, light=0.45):
    r, g, b = colorsys.hls_to_rgb((i * 0.137) % 1.0, light, 0.6)
    return int(r * 255), int(g * 255), int(b * 255)


for kind, size, label in (("logo", (240, 240), "CH"), ("poster", (300, 450), "FILM"), ("back", (1280, 720), "")):
    d = os.path.join(out, "img", kind)
    os.makedirs(d, exist_ok=True)
    for i in range(N):
        im = Image.new("RGB", size, colour(i, 0.35 if kind == "back" else 0.45))
        dr = ImageDraw.Draw(im)
        w, h = size
        # A soft band and a number, so pictures differ and a missing one shows.
        dr.rectangle([0, int(h * 0.62), w, int(h * 0.80)], fill=colour(i + 7, 0.6))
        text = "%s %d" % (label, i) if label else "Backdrop %d" % i
        f = font(int(min(w, h) * (0.16 if kind != "back" else 0.08)))
        tw = dr.textlength(text, font=f)
        dr.text(((w - tw) / 2, h * 0.3), text, fill=(255, 255, 255), font=f)
        im.save(os.path.join(d, "%d.png" % i), optimize=True)
print("pictures made in", out)
