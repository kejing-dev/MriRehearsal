"""Contrast table (D13, ui-audit) measured on HD console screenshots: for each pair, the text / icon colour is the
darkest (or lightest, for light-on-dark) 3 % of the pixels in its box, the background is the median of a nearby box
without text. WCAG ratio; text needs 4.5, icons / large text 3.

  ~/.factory-venv/Scripts/python.exe tools/contrast.py PAIRS.json   -> Markdown table
PAIRS.json: [{"name": .., "shot": path, "fg": [x0,y0,x1,y1], "bg": [x0,y0,x1,y1], "light": false, "need": 4.5}, ...]
"""
import json
import sys

import numpy as np
from PIL import Image


def lin(c):
    c = c / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def Y(rgb):
    r, g, b = lin(np.asarray(rgb, dtype=float))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    ya, yb = sorted((Y(a), Y(b)), reverse=True)
    return (ya + 0.05) / (yb + 0.05)


def main():
    pairs = json.load(open(sys.argv[1], encoding="utf-8"))
    cache = {}
    print("| pair | foreground | background | ratio | needs | ok |")
    print("|---|---|---|---|---|---|")
    for p in pairs:
        if p["shot"] not in cache:
            cache[p["shot"]] = np.asarray(Image.open(p["shot"]).convert("RGB")).astype(float)
        a = cache[p["shot"]]
        x0, y0, x1, y1 = p["bg"]
        bg = np.median(a[y0:y1, x0:x1].reshape(-1, 3), axis=0)
        x0, y0, x1, y1 = p["fg"]
        px = a[y0:y1, x0:x1].reshape(-1, 3)
        lum = px @ np.array([0.299, 0.587, 0.114])
        k = max(1, int(len(px) * 0.03))
        idx = np.argsort(lum)[-k:] if p.get("light") else np.argsort(lum)[:k]
        fg = np.median(px[idx], axis=0)
        r = ratio(fg, bg)
        h = lambda c: "#%02X%02X%02X" % tuple(int(round(v)) for v in c)
        need = p.get("need", 4.5)
        print(f"| {p['name']} | {h(fg)} | {h(bg)} | {r:.2f}:1 | {need} | {'yes' if r >= need else 'NO'} |")


if __name__ == "__main__":
    main()
