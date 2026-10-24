"""Renders reference images of the Frost Fold effect on the Fold 7 inner screen.

Runs the same optics as GlassShader.java on a stand-in home screen: the
content stays flat, a frosted pane lifts off it from the crease, and each
pixel looks through the pane down to the content, blurred and darkened by
the pane's height there. Output goes to docs/images/.

    python3 tools/render_preview.py      # needs numpy and Pillow
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SCALE = 0.25                         # render at 1/4 of the real panel
W, H = int(1968 * SCALE), int(2184 * SCALE)
XDPI = 367.5 * SCALE                 # inner panel pixel density
TILT_DEG = 30.0                      # app defaults
FROST_PX = 70.0 * SCALE
DARKNESS = 0.45
EYE_CM = 30.0
OUT = Path(__file__).resolve().parent.parent / "docs" / "images"


def home_screen():
    """A stand-in home screen: colour wallpaper plus a grid of app icons."""
    y, x = np.mgrid[0:H, 0:W] / np.array([H, W]).reshape(2, 1, 1)
    rgb = np.dstack([40 + 180 * x, 70 + 120 * (1 - y), 200 - 60 * x + 50 * y])
    img = Image.fromarray(rgb.clip(0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    colors = [(255, 94, 87), (255, 189, 46), (40, 201, 64), (10, 132, 255),
              (191, 90, 242), (255, 55, 95), (100, 210, 255), (255, 159, 10)]
    size, gap = 0.11 * W, 0.07 * W
    left = (W - (6 * size + 5 * gap)) / 2
    for row in range(6):
        for col in range(6):
            x0 = left + col * (size + gap)
            y0 = 0.1 * H + row * (size + gap * 1.5)
            d.rounded_rectangle([x0, y0, x0 + size, y0 + size], radius=size * 0.25,
                                fill=colors[(row * 6 + col) % len(colors)])
    return img


def render(progress, base, stack):
    """One frame, as GlassShader draws it (both panes lifting, vertical hinge)."""
    if progress <= 0:
        return base.copy()
    theta = math.radians(TILT_DEG) * progress
    max_height = (W / 2) * math.sin(math.radians(TILT_DEG))
    blur_per_px = FROST_PX / max_height
    dark_per_px = DARKNESS / max_height
    eye = EYE_CM / 2.54 * XDPI
    mid = W / 2

    out = np.zeros((H, W, 3))
    ys = np.arange(H) + 0.5
    for x in range(W):
        q = x + 0.5
        side = 1 if q >= mid else -1
        dist = abs(q - mid)
        height = dist * math.sin(theta)
        glass_x = mid + side * dist * math.cos(theta)
        k = eye / (eye - height)
        hx = mid + (glass_x - mid) * k
        hy = H / 2 + (ys - H / 2) * k
        if not 0 <= hx < W:
            continue
        src = stack[min(int(round(blur_per_px * height)), len(stack) - 1)]
        light = max(1 - dark_per_px * height, 0)
        inside = (hy >= 0) & (hy < H)
        out[inside, x] = src[hy[inside].astype(int), int(hx)] * light
    return Image.fromarray(out.clip(0, 255).astype(np.uint8))


def label(img, text):
    pad = 36
    framed = Image.new("RGB", (img.width, img.height + pad), (18, 18, 22))
    framed.paste(img, (0, 0))
    ImageDraw.Draw(framed).text((10, img.height + 11), text, fill=(235, 235, 240))
    return framed


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("inner_*.png"):
        old.unlink()
    base = home_screen()
    # Pre-blurred copies of the content; each column picks the one for its radius.
    stack = [np.asarray(base.filter(ImageFilter.GaussianBlur(r / 2)) if r else base, float)
             for r in range(int(FROST_PX) + 2)]

    # The opening, as the app plays it on the inner screen.
    stages = [(1.0, "inner lights up"), (0.45, "half open, waits"),
              (0.2, "flattening"), (0.0, "flat, sharp")]
    frames = []
    for p, what in stages:
        img = render(p, base, stack)
        img.save(OUT / f"inner_{int(p * 100):03d}.png")
        frames.append(label(img, f"glass {p:.0%}  -  {what}"))
    gap = 12
    strip = Image.new("RGB", (len(frames) * (frames[0].width + gap) - gap, frames[0].height))
    for i, fr in enumerate(frames):
        strip.paste(fr, (i * (fr.width + gap), 0))
    strip.save(OUT / "opening_sequence.png")

    # Animated: settle from full to half, hold, then clear as the phone goes flat.
    curve = [1 - 0.55 * (1 - (1 - t / 10) ** 2) for t in range(11)]
    curve += [0.45] * 6
    curve += [0.45 * (1 - t / 14) ** 2 for t in range(15)]
    curve += [0.0] * 8
    seq = [render(p, base, stack).resize((W // 2, H // 2)) for p in curve]
    seq[0].save(OUT / "opening.gif", save_all=True, append_images=seq[1:], duration=45, loop=0)
    print("Wrote", *sorted(p.name for p in OUT.iterdir()))


if __name__ == "__main__":
    main()
