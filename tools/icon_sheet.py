"""(from panel-fit, + <group> transforms) Renders every res/drawable/ic_*.xml vector into one contact sheet PNG (taste D23: QA checks meaning, direction and
holes). A small SVG path rasterizer (M L H V C S Q T A Z, absolute and relative) on PIL; subpaths combine even-odd.
Usage: python tools/icon_sheet.py <out.png>"""
import glob
import math
import os
import re
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont

CELL = 128
SCALE = 4  # px per viewport unit before downsampling (24 -> 96)


def tokens(d):
    for m in re.finditer(r"[MmLlHhVvCcSsQqTtAaZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", d):
        yield m.group(0)


def arc_points(x0, y0, rx, ry, phi, large, sweep, x1, y1, n=24):
    if rx == 0 or ry == 0:
        return [(x1, y1)]
    phi = math.radians(phi)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(num / den, 0)) * (-1 if large == sweep else 1)
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx, cy = cp * cxp - sp * cyp + (x0 + x1) / 2, sp * cxp + cp * cyp + (y0 + y1) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    pts = []
    for i in range(1, n + 1):
        t = t1 + dt * i / n
        pts.append((cx + rx * math.cos(t) * cp - ry * math.sin(t) * sp, cy + rx * math.cos(t) * sp + ry * math.sin(t) * cp))
    return pts


def subpaths(d):
    toks = list(tokens(d))
    i = 0
    cmd = None
    x = y = sx = sy = 0.0
    lcx = lcy = None
    cur = []
    out = []

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                if cur:
                    out.append(cur)
                cur = []
                x, y = sx, sy
                continue
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            nx, ny = num(), num()
            if rel:
                nx, ny = x + nx, y + ny
            if cur:
                out.append(cur)
            cur = [(nx, ny)]
            x, y, sx, sy = nx, ny, nx, ny
            cmd = "l" if rel else "L"
            lcx = None
        elif c == "L":
            nx, ny = num(), num()
            if rel:
                nx, ny = x + nx, y + ny
            cur.append((nx, ny))
            x, y = nx, ny
            lcx = None
        elif c == "H":
            nx = num()
            x = x + nx if rel else nx
            cur.append((x, y))
            lcx = None
        elif c == "V":
            ny = num()
            y = y + ny if rel else ny
            cur.append((x, y))
            lcx = None
        elif c in "CS":
            if c == "C":
                x1, y1 = num(), num()
                if rel:
                    x1, y1 = x + x1, y + y1
            else:
                x1, y1 = (2 * x - lcx, 2 * y - lcy) if lcx is not None else (x, y)
            x2, y2, ex, ey = num(), num(), num(), num()
            if rel:
                x2, y2, ex, ey = x + x2, y + y2, x + ex, y + ey
            for k in range(1, 13):
                tt = k / 12
                a = (1 - tt) ** 3
                b = 3 * (1 - tt) ** 2 * tt
                cc = 3 * (1 - tt) * tt ** 2
                e = tt ** 3
                cur.append((a * x + b * x1 + cc * x2 + e * ex, a * y + b * y1 + cc * y2 + e * ey))
            lcx, lcy = x2, y2
            x, y = ex, ey
        elif c in "QT":
            if c == "Q":
                x1, y1 = num(), num()
                if rel:
                    x1, y1 = x + x1, y + y1
            else:
                x1, y1 = (2 * x - lcx, 2 * y - lcy) if lcx is not None else (x, y)
            ex, ey = num(), num()
            if rel:
                ex, ey = x + ex, y + ey
            for k in range(1, 9):
                tt = k / 8
                cur.append(((1 - tt) ** 2 * x + 2 * (1 - tt) * tt * x1 + tt ** 2 * ex, (1 - tt) ** 2 * y + 2 * (1 - tt) * tt * y1 + tt ** 2 * ey))
            lcx, lcy = x1, y1
            x, y = ex, ey
        elif c == "A":
            rx, ry, phi, large, sweep, ex, ey = num(), num(), num(), num(), num(), num(), num()
            if rel:
                ex, ey = x + ex, y + ey
            cur.extend(arc_points(x, y, rx, ry, phi, int(large), int(sweep), ex, ey))
            x, y = ex, ey
            lcx = None
    if cur:
        out.append(cur)
    return out


def group_transform(xml):
    """One <group> level (translateX/Y, rotation about pivotX/Y): Material Symbols 960 icons and ic_lie use it."""
    m = re.search(r"<group([^>]*)>", xml)
    if not m:
        return lambda x, y: (x, y)
    attrs = dict(re.findall(r'android:(\w+)="([-\d.]+)"', m.group(1)))
    tx, ty = float(attrs.get("translateX", 0)), float(attrs.get("translateY", 0))
    rot = math.radians(float(attrs.get("rotation", 0)))
    px, py = float(attrs.get("pivotX", 0)), float(attrs.get("pivotY", 0))
    c, s = math.cos(rot), math.sin(rot)

    def f(x, y):
        x, y = x - px, y - py
        x, y = x * c - y * s, x * s + y * c
        return x + px + tx, y + py + ty

    return f


def render(path, size):
    xml = open(path, encoding="utf-8").read()
    vw = float(re.search(r'viewportWidth="([\d.]+)"', xml).group(1))
    k = size / vw
    g = group_transform(xml)
    img = Image.new("L", (size, size), 0)
    for d in re.findall(r'pathData="([^"]+)"', xml):
        mask = Image.new("L", (size, size), 0)
        for sp in subpaths(d):
            if len(sp) < 3:
                continue
            one = Image.new("L", (size, size), 0)
            ImageDraw.Draw(one).polygon([(g(px, py)[0] * k, g(px, py)[1] * k) for px, py in sp], fill=255)
            mask = ImageChops.logical_xor(mask.convert("1"), one.convert("1")).convert("L")
        img = ImageChops.lighter(img, mask)
    return img


def main(out):
    files = sorted(f for f in glob.glob(os.path.join("app", "src", "main", "res", "drawable", "ic_*.xml")) if "launcher" not in f)
    cols = 8
    rows = (len(files) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * CELL, rows * (CELL + 20)), (40, 44, 52))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    for n, f in enumerate(files):
        big = render(f, 24 * SCALE).resize((72, 72), Image.LANCZOS)
        r, c = divmod(n, cols)
        x0, y0 = c * CELL + (CELL - 72) // 2, r * (CELL + 20) + 16
        sheet.paste((255, 255, 255), (x0, y0, x0 + 72, y0 + 72), big)
        name = os.path.basename(f)[3:-4]
        draw.text((c * CELL + 6, y0 + 80), name, fill=(220, 220, 220), font=font)
    sheet.save(out)
    print("wrote", out, len(files), "icons")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "icons.png")
