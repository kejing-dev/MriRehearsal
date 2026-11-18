"""MRI Rehearsal UI cues (合成（UI）): the big-movement "tock" (tick.wav) and the call ball's two-tone (call.wav).

    python tools/make_audio.py

The scan-room sounds (loc / t1 / t2 / epi / gap / motor) are CC0 recordings from freesound, cut into loops by
tools/cut_cc0.py (v0.1.1, profile D51); nothing in the scan room is synthesised any more.
"""
import math
import os
import random
import struct
import wave

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "audio")
SR = 44100
TAU = 2 * math.pi


def silence(seconds):
    return [0.0] * int(round(seconds * SR))


def add(buf, at, src, gain=1.0):
    i0 = int(round(at * SR))
    n = len(buf)
    for k, v in enumerate(src):
        j = (i0 + k) % n  # wrap both ways: loops are periodic (a hit jittered before 0 ends the loop)
        buf[j] += v * gain


def envelope(n, attack, decay):
    a = max(1, int(attack * SR))
    out = []
    for i in range(n):
        e = i / a if i < a else math.exp(-(i - a) / (decay * SR))
        out.append(e)
    return out


def tone_burst(freq, seconds, harmonics=(1.0, 0.55, 0.32), attack=0.002, decay=0.012, phase_jitter=0.0, rnd=None):
    n = int(seconds * SR)
    env = envelope(n, attack, decay)
    ph = [rnd.random() * TAU * phase_jitter if rnd else 0.0 for _ in harmonics]
    out = []
    for i in range(n):
        t = i / SR
        v = 0.0
        for h, (amp, p) in enumerate(zip(harmonics, ph), start=1):
            v += amp * math.sin(TAU * freq * h * t + p)
        out.append(v * env[i])
    return out


def bandpass_noise(seconds, lo, hi, rnd, decay=0.02):
    """White noise through a two-pole resonator centred between lo and hi (bandwidth hi - lo)."""
    n = int(seconds * SR)
    f0 = math.sqrt(lo * hi)
    bw = hi - lo
    r = math.exp(-math.pi * bw / SR)
    c = 2 * r * math.cos(TAU * f0 / SR)
    y1 = y2 = 0.0
    env = envelope(n, 0.003, decay)
    out = []
    for i in range(n):
        x = rnd.uniform(-1, 1)
        y = (1 - r) * x + c * y1 - r * r * y2
        y2, y1 = y1, y
        out.append(y * env[i] * 6.0)
    return out


def normalise(x, peak):
    m = max(1e-9, max(abs(v) for v in x))
    return [v * peak / m for v in x]


def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in samples))
    print("wrote", path, f"{len(samples) / SR:.2f}s")


def main():
    # the two UI cues only (合成（UI）); the scanner sounds are CC0 recordings cut by tools/cut_cc0.py
    rnd = random.Random(6)
    tick = bandpass_noise(0.06, 700, 1600, rnd, decay=0.012)
    write("tick.wav", normalise(tick, 0.45))
    call = silence(0.55)
    add(call, 0.0, tone_burst(660, 0.22, harmonics=(1.0, 0.2), attack=0.01, decay=0.09), 1.0)
    add(call, 0.24, tone_burst(880, 0.3, harmonics=(1.0, 0.2), attack=0.01, decay=0.12), 1.0)
    write("call.wav", normalise(call, 0.40))


if __name__ == "__main__":
    main()
