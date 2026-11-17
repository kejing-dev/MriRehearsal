"""Finds the buttons of the main window in an HD console screenshot (for real-pointer taps with tools/harness/emu.sh):
blue primary buttons (interaction #0A7CFF-ish) and dark grey secondary buttons, as boxes in screenshot pixels and the
tap point in emu.sh's 2160-wide framebuffer coordinates (x 0.84375).

  ~/.factory-venv/Scripts/python.exe tools/buttons.py SHOT.png [x0,y0,x1,y1 window box]
"""
import sys

import numpy as np
from PIL import Image

K = 2160 / 2560


def blobs(mask, min_area=2000):
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    out = []
    ys, xs = np.nonzero(mask)
    for y, x in zip(ys[::97], xs[::97]):
        if seen[y, x]:
            continue
        # flood fill on a coarse grid (step 3) for speed
        stack = [(y, x)]
        minx = maxx = x
        miny = maxy = y
        n = 0
        while stack:
            cy, cx = stack.pop()
            if cy < 0 or cx < 0 or cy >= h or cx >= w or seen[cy, cx] or not mask[cy, cx]:
                continue
            seen[cy, cx] = True
            n += 1
            minx, maxx, miny, maxy = min(minx, cx), max(maxx, cx), min(miny, cy), max(maxy, cy)
            stack += [(cy + 3, cx), (cy - 3, cx), (cy, cx + 3), (cy, cx - 3)]
        if n * 9 >= min_area:
            out.append((minx, miny, maxx, maxy))
    return out


def main():
    a = np.asarray(Image.open(sys.argv[1]).convert("RGB")).astype(int)
    box = tuple(int(v) for v in sys.argv[2].split(",")) if len(sys.argv) > 2 else (0, 0, a.shape[1], a.shape[0])
    x0, y0, x1, y1 = box
    sub = np.zeros(a.shape[:2], dtype=bool)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    blue = (b > 200) & (r < 80) & (g > 90) & (g < 170)
    grey = (abs(r - g) < 8) & (abs(g - b) < 8) & (r > 70) & (r < 110)
    for name, m in (("primary", blue), ("secondary", grey)):
        mm = sub.copy()
        mm[y0:y1, x0:x1] = m[y0:y1, x0:x1]
        kept = []
        for bx in sorted(blobs(mm), key=lambda q: (q[1], q[0])):
            if any(abs(bx[0] - k[0]) <= 6 and abs(bx[1] - k[1]) <= 6 for k in kept):
                continue  # the coarse flood fill finds each button from a few seeds
            kept.append(tuple(int(v) for v in bx))
        for bx in kept:
            cx, cy = (bx[0] + bx[2]) // 2, (bx[1] + bx[3]) // 2
            print(f"{name} box={bx} h={bx[3] - bx[1]}px tap={round(cx * K)},{round(cy * K)}")


if __name__ == "__main__":
    main()
