"""Offline listen-check of the rendered WAVs (lesson 13): level, the main partials (Goertzel), and the 100 ms RMS
envelope's period, so a block-periodic hum or a missing pulse train shows up without ears.

    python tools/check_audio.py
"""
import math, os, struct, wave

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
DIR = os.path.join(ROOT, "app", "src", "main", "assets", "audio")


def read(name):
    with wave.open(os.path.join(DIR, name)) as w:
        n = w.getnframes()
        sr = w.getframerate()
        data = struct.unpack("<%dh" % n, w.readframes(n))
    return [v / 32768.0 for v in data], sr


def goertzel(x, sr, f):
    k = 2 * math.cos(2 * math.pi * f / sr)
    s1 = s2 = 0.0
    for v in x:
        s0 = v + k * s1 - s2
        s2, s1 = s1, s0
    return math.sqrt(max(0.0, s1 * s1 + s2 * s2 - k * s1 * s2)) / len(x)


def envelope_period(x, sr, hop=0.005):
    h = int(sr * hop)
    env = [math.sqrt(sum(v * v for v in x[i:i + h]) / h) for i in range(0, len(x) - h, h)]
    m = sum(env) / len(env)
    e = [v - m for v in env]
    best, lag = 0.0, 0
    for L in range(int(0.05 / hop), int(1.5 / hop)):
        c = sum(e[i] * e[i + L] for i in range(len(e) - L)) / (len(e) - L)
        if c > best:
            best, lag = c, L
    return lag * hop, best / (sum(v * v for v in e) / len(e) + 1e-12)


for name, freqs in [("loc.wav", (120, 550, 1000)), ("t1.wav", (120, 500, 1000, 1500, 1200)), ("t2.wav", (120, 900, 1800, 2700)),
                    ("epi.wav", (120, 800, 1600, 2400)), ("gap.wav", (50, 1800, 2000, 2200)), ("tick.wav", (1000,)), ("call.wav", (660, 880)), ("motor.wav", (90, 180, 270))]:
    x, sr = read(name)
    peak = max(abs(v) for v in x)
    rms = math.sqrt(sum(v * v for v in x) / len(x))
    seg = x[: min(len(x), sr * 2)]
    parts = " ".join("%dHz=%.4f" % (f, goertzel(seg, sr, f)) for f in freqs)
    line = "%-9s %5.2fs peak %.2f rms %.3f (%5.1f dBFS) | %s" % (name, len(x) / sr, peak, rms, 20 * math.log10(rms + 1e-12), parts)
    if len(x) > sr:
        p, strength = envelope_period(x[: sr * 4], sr)
        line += " | envelope period %.3fs (%.2f)" % (p, strength)
    # loop seam: jump between the last and the first sample
    line += " | seam %.3f" % abs(x[-1] - x[0])
    print(line)
