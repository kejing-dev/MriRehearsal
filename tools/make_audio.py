"""MRI Rehearsal scan noise, rendered offline (design `audio.source` fallback track; no CC0 recording was fetched).

    python tools/make_audio.py

Writes 16-bit 44.1 kHz mono WAVs to app/src/main/assets/audio/. Standard library only (no numpy on the build PCs), so
the filters are written out sample by sample. Every sound is "machine", not "beeper" (design R1-11): a fundamental with
its 2nd and 3rd harmonics, a 120 Hz gantry body that rings after each hit, about 0.3 s of room reverb (Schroeder:
four combs, two all-passes) and +-5 ms random timing jitter. Loops are a whole number of pulse periods long and are
rendered twice with the second copy kept, so the reverb tail of the end wraps into the start.

| file     | seconds | sound (design `model.sequences.table`)                                    | RMS dBFS |
| loc.wav  | 20      | 2.5 Hz knocks: 60 ms band-pass noise 300-800 Hz                           | -25 |
| t1.wav   | 10 loop | 4 Hz pulses: 40 ms of 500 Hz + 2nd + 3rd harmonics, 1.2 kHz click          | -21 |
| t2.wav   | 10 loop | 1.2 Hz triple knocks, 50 ms apart, 900 Hz + harmonics                       | -21 |
| epi.wav  | 10 loop | 14 Hz buzz: 800 Hz sawtooth amplitude-modulated, 120 Hz resonance (loudest) | -17 |
| gap.wav  | 8 loop  | cold-head pump: a soft 2 kHz chirp every 1.0 s over a low room hum          | -36 |
| tick.wav | 0.06    | the "tock" of a big movement                                               | peak 0.45 |
| call.wav | 0.55    | soft two-tone of the call ball                                             | peak 0.40 |
| motor.wav| 4 loop  | table motor: low hum with a slow wobble                                     | peak 0.30 |
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


def body(seconds=0.18, freq=120.0, decay=0.05):
    """The gantry's 120 Hz resonance that rings after each hit."""
    n = int(seconds * SR)
    return [math.sin(TAU * freq * i / SR) * math.exp(-i / (decay * SR)) for i in range(n)]


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


def click(seconds=0.008, freq=1200.0):
    n = int(seconds * SR)
    return [math.sin(TAU * freq * i / SR) * (1 - i / n) for i in range(n)]


def reverb(x, mix=0.22):
    """Schroeder reverb, ~0.3 s: four parallel combs, two all-passes."""
    combs = [(int(SR * d), g) for d, g in ((0.0297, 0.78), (0.0371, 0.76), (0.0411, 0.74), (0.0437, 0.72))]
    n = len(x)
    wet = [0.0] * n
    for d, g in combs:
        buf = [0.0] * d
        k = 0
        for i in range(n):
            y = buf[k]
            buf[k] = x[i] + y * g
            k += 1
            if k == d:
                k = 0
            wet[i] += y
    for d, g in ((int(SR * 0.005), 0.7), (int(SR * 0.0017), 0.7)):
        buf = [0.0] * d
        k = 0
        for i in range(n):
            b = buf[k]
            y = -g * wet[i] + b
            buf[k] = wet[i] + g * y
            k += 1
            if k == d:
                k = 0
            wet[i] = y
    return [(1 - mix) * a + mix * 0.25 * b for a, b in zip(x, wet)]


def loop_render(seconds, place, seed):
    """Renders [place] twice through the reverb and keeps the second copy (seamless loop with its tail)."""
    rnd = random.Random(seed)
    one = silence(seconds)
    place(one, rnd)
    two = reverb(one + one)
    return two[len(one):]


def jitter(rnd):
    return rnd.uniform(-0.005, 0.005)


def loc(buf, rnd):
    period = 1 / 2.5
    for k in range(int(len(buf) / SR / period)):
        t = k * period + jitter(rnd)
        add(buf, t, bandpass_noise(0.06, 300, 800, rnd), 0.9)
        add(buf, t, body(), 0.35)


def t1(buf, rnd):
    period = 1 / 4.0
    for k in range(int(len(buf) / SR / period)):
        t = k * period + jitter(rnd)
        add(buf, t, tone_burst(500, 0.04, rnd=rnd, phase_jitter=0.3), 0.7)
        add(buf, t, click(), 0.45)
        add(buf, t, body(), 0.4)


def t2(buf, rnd):
    period = 1 / 1.2
    for k in range(int(len(buf) / SR / period)):
        t0 = k * period + jitter(rnd)
        for j in range(3):
            add(buf, t0 + j * 0.05 + jitter(rnd) * 0.2, tone_burst(900, 0.035, decay=0.009, rnd=rnd, phase_jitter=0.3), 0.75)
            add(buf, t0 + j * 0.05, body(0.12), 0.3)


def epi(buf, rnd):
    n = len(buf)
    phase = 0.0
    for i in range(n):
        t = i / SR
        # 800 Hz sawtooth (band-limited to 5 partials), gated at 14 Hz with a sharp-ish duty cycle
        saw = sum(math.sin(TAU * 800 * h * t) / h for h in range(1, 6))
        gate_phase = (t * 14.0) % 1.0
        gate = 1.0 if gate_phase < 0.55 else 0.0
        gate *= min(1.0, gate_phase / 0.04) if gate_phase < 0.04 else 1.0
        buf[i] += 0.55 * saw * gate + 0.28 * math.sin(TAU * 120 * t) * (0.6 + 0.4 * gate)
    # a little random amplitude wander so it is not a perfect synth tone
    w = 1.0
    for i in range(0, n, 441):
        w += rnd.uniform(-0.02, 0.02)
        w = min(1.08, max(0.92, w))
        for j in range(i, min(n, i + 441)):
            buf[j] *= w


def gap(buf, rnd):
    n = len(buf)
    # low room hum: 60 Hz-ish rumble of filtered noise plus a faint 50 Hz line
    y = 0.0
    a = math.exp(-TAU * 90 / SR)
    for i in range(n):
        y = a * y + (1 - a) * rnd.uniform(-1, 1)
        buf[i] += y * 2.2 + 0.05 * math.sin(TAU * 50 * i / SR)
    for k in range(int(n / SR)):
        chirp_n = int(0.09 * SR)
        chirp = []
        for i in range(chirp_n):
            t = i / SR
            f = 1800 + 400 * (i / chirp_n)
            chirp.append(math.sin(TAU * f * t) * math.sin(math.pi * i / chirp_n) ** 2)
        add(buf, k * 1.0 + jitter(rnd), chirp, 0.35)


def normalise(x, peak):
    m = max(1e-9, max(abs(v) for v in x))
    return [v * peak / m for v in x]


def loudness(x, rms_dbfs, ceiling=0.95):
    """Scales to an RMS target (the design's loudness steps), then a soft limiter keeps the pulses' peaks under
    [ceiling] (pulsed beds have a 20+ dB crest factor; peak normalising made the localiser 19 dB quieter than EPI)."""
    rms = math.sqrt(sum(v * v for v in x) / len(x)) + 1e-12
    g = (10 ** (rms_dbfs / 20)) / rms
    knee = 0.5 * ceiling

    def soft(u):
        a = abs(u)
        if a <= knee:
            return u
        # slope 1 at the knee, never above the ceiling
        return math.copysign(knee + knee * math.tanh((a - knee) / knee), u)

    return [soft(v * g) for v in x]


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
    # loudness steps 2 / 3 / 3 / 4 / 0 of the design table, about 4 dB apart
    write("loc.wav", loudness(loop_render(20.0, loc, 1), -25.0))
    write("t1.wav", loudness(loop_render(10.0, t1, 2), -21.0))
    write("t2.wav", loudness(loop_render(10.0, t2, 3), -21.0))
    write("epi.wav", loudness(loop_render(10.0, epi, 4), -17.0))
    write("gap.wav", loudness(loop_render(8.0, gap, 5), -36.0))
    rnd = random.Random(6)
    tick = bandpass_noise(0.06, 700, 1600, rnd, decay=0.012)
    write("tick.wav", normalise(tick, 0.45))
    call = silence(0.55)
    add(call, 0.0, tone_burst(660, 0.22, harmonics=(1.0, 0.2), attack=0.01, decay=0.09), 1.0)
    add(call, 0.24, tone_burst(880, 0.3, harmonics=(1.0, 0.2), attack=0.01, decay=0.12), 1.0)
    write("call.wav", normalise(call, 0.40))

    def motor(buf, rnd):
        for i in range(len(buf)):
            t = i / SR
            wob = 1.0 + 0.15 * math.sin(TAU * 0.5 * t)
            buf[i] += (math.sin(TAU * 90 * t) + 0.4 * math.sin(TAU * 180 * t) + 0.15 * math.sin(TAU * 270 * t)) * wob
            buf[i] += 0.05 * rnd.uniform(-1, 1)

    write("motor.wav", normalise(loop_render(4.0, motor, 7), 0.30))


if __name__ == "__main__":
    main()
