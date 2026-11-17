"""Whitespace of the main window in console screenshots (build.md 空白的量法, profile D44):
(content top - window top + window bottom - content bottom) / window height, plus the largest blank band inside the
content (> 20 % of the window = tighten). The window is found from its uniform glass along an interior column / row
(the window does not move between shots of one cold start); content = rows with text, icons or buttons (pixels that
differ from the glass). Also prints the window's px per dp (640 x 620 dp) for the control-height table.

  ~/.factory-venv/Scripts/python.exe tools/whitespace.py [--box x0,y0,x1,y1] SHOT.png ...  -> one Markdown row per shot
  (--box: the window rectangle from a clean shot of the same cold start; the bore behind the window in run states
  confuses the automatic search)
"""
import os
import sys

import numpy as np
from PIL import Image

W_DP, H_DP = 640, 620


def lum(a):
    return (a[..., 0] * 299 + a[..., 1] * 587 + a[..., 2] * 114) // 1000


def window_box(a):
    l = lum(a)
    h, w = l.shape
    # the glass is the brightest flat grey around the centre; take the mode of luminance in a central patch
    patch = l[h // 2 - 300:h // 2 + 300, w // 2 - 300:w // 2 + 300].ravel()
    glass = int(np.bincount(patch).argmax())
    g = np.abs(l - glass) <= 4
    # interior column / row through the left margin and the top margin: longest glass runs
    def run(line):
        best, cur, start, bs = 0, 0, 0, 0
        for i, v in enumerate(line):
            if v:
                if cur == 0:
                    start = i
                cur += 1
                if cur > best:
                    best, bs = cur, start
            else:
                cur = 0
        return bs, bs + best
    cy = h // 2
    x0, x1 = run(g[cy, :])  # may stop at content; widen with several rows
    for y in range(cy - 400, cy + 400, 25):
        a0, a1 = run(g[y, :])
        if a1 - a0 > x1 - x0:
            x0, x1 = a0, a1
    y0, y1 = run(g[:, x0 + 12])
    return x0, y0, x1, y1, glass


BOX = None


def measure(path):
    a = np.asarray(Image.open(path).convert("RGB")).astype(int)
    x0, y0, x1, y1, glass = window_box(a)
    l = lum(a)
    if BOX:
        x0, y0, x1, y1 = BOX
        glass = int(np.median(l[y0 + 10:y1 - 10, x0 + 6:x0 + 12]))
    sat = a.max(axis=2) - a.min(axis=2)
    inset = 14
    sub_l = l[y0:y1, x0 + inset:x1 - inset]
    sub_s = sat[y0:y1, x0 + inset:x1 - inset]
    content = ((np.abs(sub_l - glass) > 22) | (sub_s > 40)).sum(axis=1) >= 3
    content[:10] = False  # the glass's own rim highlight along the top and bottom edges
    content[-10:] = False
    rows = np.nonzero(content)[0]
    hgt = y1 - y0
    if len(rows) == 0:
        return os.path.basename(path), hgt, None
    top, bot = rows[0], rows[-1]
    outer = (top + (hgt - 1 - bot)) / hgt
    # biggest blank band inside the content
    gaps = np.diff(rows)
    inner = (gaps.max() - 1) / hgt if len(gaps) else 0
    px_dp = (x1 - x0) / W_DP
    return os.path.basename(path), hgt, (outer, inner, top / hgt, (hgt - 1 - bot) / hgt, px_dp, (x0, y0, x1, y1))


def main():
    print("| shot | window px (h) | outer whitespace | top / bottom | largest inner band | px per dp | ok (≤ 25 %, band ≤ 20 %) |")
    print("|---|---|---|---|---|---|---|")
    global BOX
    args = sys.argv[1:]
    if args and args[0] == "--box":
        BOX = tuple(int(v) for v in args[1].split(","))
        args = args[2:]
    for p in args:
        name, hgt, m = measure(p)
        if m is None:
            print(f"| {name} | {hgt} | no content found | | | | ? |")
            continue
        outer, inner, t, b, pxdp, box = m
        ok = "yes" if outer <= 0.25 and inner <= 0.20 else "NO"
        print(f"| {name} | {hgt} | {outer:.0%} | {t:.0%} / {b:.0%} | {inner:.0%} | {pxdp:.2f} | {ok} |")


if __name__ == "__main__":
    main()
