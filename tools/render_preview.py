"""Renders reference images of the Frost Fold effect on the Fold 7 inner screen.

Mirrors the drawing in FrostView.java and the timing in FrostService.java, so the
images match what the app draws at a given hinge angle. Output: docs/images/.

    python3 tools/render_preview.py
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SCALE = 0.25                      # render at 1/4 of the real panel
W, H = int(1968 * SCALE), int(2184 * SCALE)
DP = 2.625 * SCALE                # 420 dpi panel
INNER_FROST_ANGLE, INNER_CLEAR_ANGLE = 95.0, 178.0
MAX_BLUR = 110                    # app default "Frost strength"
OVERLAY_ALPHA = 0.8               # Android's max opacity for a touch-through overlay
OUT = Path(__file__).resolve().parent.parent / "docs" / "images"


def progress(angle):
    t = (INNER_CLEAR_ANGLE - angle) / (INNER_CLEAR_ANGLE - INNER_FROST_ANGLE)
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def wallpaper():
    """A stand-in home screen: colour wallpaper plus a grid of app icons."""
    y, x = np.mgrid[0:H, 0:W] / np.array([H, W]).reshape(2, 1, 1)
    r = 40 + 180 * x
    g = 70 + 120 * (1 - y)
    b = 200 - 60 * x + 50 * y
    img = Image.fromarray(np.dstack([r, g, b]).clip(0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    colors = [(255, 94, 87), (255, 189, 46), (40, 201, 64), (10, 132, 255),
              (191, 90, 242), (255, 55, 95), (100, 210, 255), (255, 159, 10)]
    size, gap = 0.13 * W, 0.075 * W
    left = (W - (5 * size + 4 * gap)) / 2
    for row in range(6):
        for col in range(5):
            x0 = left + col * (size + gap)
            y0 = 0.12 * H + row * (size + gap * 1.6)
            d.rounded_rectangle([x0, y0, x0 + size, y0 + size], radius=size * 0.25,
                                fill=colors[(row * 5 + col) % len(colors)])
            d.rounded_rectangle([x0 + size * 0.2, y0 + size + 6 * SCALE * 4,
                                 x0 + size * 0.8, y0 + size + 10 * SCALE * 4],
                                radius=2, fill=(255, 255, 255))
    return img


def ramp(n, a0, a1):
    return np.linspace(a0, a1, n)


def frost_layer(f, angle):
    """RGBA overlay exactly as FrostView.onDraw paints it (vertical hinge)."""
    rgb = np.zeros((H, W, 3))
    a = np.zeros((H, W))

    def over(color, alpha):
        nonlocal rgb, a
        alpha = np.broadcast_to(alpha, (H, W))
        out_a = alpha + a * (1 - alpha)
        safe = np.where(out_a == 0, 1, out_a)
        rgb = (np.array(color) * alpha[..., None] + rgb * (a * (1 - alpha))[..., None]) / safe[..., None]
        a = out_a

    mid = W / 2
    xs = np.arange(W) + 0.5
    tilt = max(0.0, min(1.0, (180 - angle) / 90))

    # 1. even dim
    over((12, 12, 18), np.full((H, W), 0.30 * f))
    # 2. each half shaded towards the hinge by how far it is tilted
    depth = 0.38 * tilt * f
    shade = np.where(xs < mid, xs / mid, (W - xs) / mid) * depth
    over((0, 0, 6), np.tile(shade, (H, 1)))
    # 3. soft crease shadow
    band = (8 + 40 * tilt) * DP
    t = (xs - (mid - band)) / (2 * band)
    crease = np.where((t >= 0) & (t <= 1), np.interp(t, [0, 0.5, 1], [0, 0.32 * f, 0]), 0)
    over((0, 0, 0), np.tile(crease, (H, 1)))

    return rgb, a * OVERLAY_ALPHA


def render(angle, base):
    f = progress(angle)
    blur = MAX_BLUR * f * SCALE
    under = base.filter(ImageFilter.GaussianBlur(blur / 2)) if blur > 0 else base
    rgb, alpha = frost_layer(f, angle)
    out = np.asarray(under, float) * (1 - alpha[..., None]) + rgb * alpha[..., None]
    return Image.fromarray(out.clip(0, 255).astype(np.uint8))


def label(img, text):
    pad = int(36 * SCALE * 4)
    framed = Image.new("RGB", (img.width, img.height + pad), (18, 18, 22))
    framed.paste(img, (0, 0))
    ImageDraw.Draw(framed).text((10, img.height + pad * 0.3), text, fill=(235, 235, 240))
    return framed


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    base = wallpaper()
    angles = [90, 120, 145, 165, 178]
    frames = []
    for angle in angles:
        img = render(angle, base)
        img.save(OUT / f"inner_{angle:03d}deg.png")
        frames.append(label(img, f"Hinge {angle}°  ·  frost {progress(angle):.0%}"))

    gap = 12
    strip = Image.new("RGB", (len(frames) * frames[0].width + gap * (len(frames) - 1), frames[0].height), (0, 0, 0))
    for i, fr in enumerate(frames):
        strip.paste(fr, (i * (fr.width + gap), 0))
    strip.save(OUT / "opening_sequence.png")

    # Animated GIF: closed → flat, then a short hold.
    seq = [render(a, base).resize((W // 2, H // 2)) for a in range(80, 181, 3)]
    seq += [seq[-1]] * 8
    seq[0].save(OUT / "opening.gif", save_all=True, append_images=seq[1:], duration=40, loop=0)
    print("Wrote", *sorted(p.name for p in OUT.iterdir()))


if __name__ == "__main__":
    main()
