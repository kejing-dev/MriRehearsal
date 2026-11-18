"""Scan-room loops from the CC0 freesound recordings (profile D51: real recordings, clean, seamless).

    python -I tools/cut_cc0.py FFMPEG MRI_MP3 BED_MP3 WORKDIR

Sources (both Creative Commons 0, anonymous HQ previews):
  - MRI_MP3: https://freesound.org/people/parabolix/sounds/320532/ "MRI fsk robot servo.wav" by parabolix, a GE Signa
    scanner running its sequences, cooling system audible at the end (281.8 s).
  - BED_MP3: https://freesound.org/people/kedarda/sounds/613865/ "motor adjustable bed.wav" by kedarda, an electric
    adjustable bed's motor (9.2 s).

Per loop: decode mono 44.1 kHz -> ffmpeg afftdn (denoise) -> highpass 120 Hz -> lowpass 6 kHz on the segment plus 1 s
margins; the loop is cut from the middle with a length of whole rhythm periods, picked where the waveform after the
loop end best matches the loop start (cross-correlation); the seam gets an equal-power crossfade from the material that
follows the loop end, so the last sample runs straight into the first. Peak set to PEAK_DBFS (loudness per B20 is checked
by tools/loudness.py at the default volume). Writes 16-bit mono WAVs into app/src/main/assets/audio/.
"""
import os
import subprocess
import sys
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "audio")
SR = 44100

# name: (source, start s, end s, loop seconds wanted, rhythm Hz or None, crossfade s, denoise nr dB, peak dBFS)
# times found with an envelope-autocorrelation scan of the recording (rhythm in Hz per 4 s block)
LOOPS = {
    "loc": ("mri", 233.5, 240.0, 4.8, 2.50, 0.08, 12, -1.5),  # 2.5 Hz knocks: the localiser
    "t1": ("mri", 129.5, 149.0, 9.6, 4.17, 0.06, 12, -1.5),  # 4.17 Hz pulses
    "t2": ("mri", 182.5, 210.5, 9.9, 11.11, 0.05, 12, -1.5),  # 11.1 Hz pattern, a different texture
    "epi": ("mri", 3.0, 16.0, 10.0, None, 0.12, 12, -1.5),  # the loud tonal buzz at the start (diffusion)
    "gap": ("mri", 261.0, 281.0, 8.0, None, 0.40, 4, -16.0),  # cooling system / quiet floor between sequences
    "motor": ("bed", 0.15, 2.35, 1.5, None, 0.12, 10, -2.0),  # the bed's motor, steady part before the clicks
}


def decode(ffmpeg, src, start, end, nr):
    a = max(0.0, start - 1.0)
    dur = end - start + 2.0
    af = f"afftdn=nr={nr}:nf=-60,highpass=f=120,lowpass=f=6000"
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", f"{a:.3f}", "-t", f"{dur:.3f}", "-i", src,
           "-ac", "1", "-ar", str(SR), "-af", af, "-f", "s16le", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    x = np.frombuffer(raw, dtype="<i2").astype(float) / 32768
    off = int((start - a) * SR)
    return x[off:off + int((end - start) * SR)]


def best_loop(x, want, rhythm, xf):
    """Start a and length L (samples): L near want (whole periods when rhythmic), x[a+L:...] most like x[a:...]."""
    W = int(0.25 * SR)
    a = min(int(0.4 * SR), len(x) // 20)
    lens = []
    if rhythm:
        per = SR / rhythm
        n0 = max(1, round(want * rhythm))
        lens = [int(round(k * per)) for k in range(n0 - 2, n0 + 3)]
    else:
        lens = [int((want + d) * SR) for d in np.arange(-0.3, 0.31, 0.01)]
    head = x[a:a + W]
    best = None
    for L in lens:
        for da in range(-int(0.02 * SR), int(0.02 * SR) + 1, 8):  # fine search around the nominal length
            b = a + L + da
            if b + W + int(xf * SR) > len(x):
                continue
            tail = x[b:b + W]
            c = float(np.dot(head, tail) / (np.linalg.norm(head) * np.linalg.norm(tail) + 1e-12))
            if best is None or c > best[0]:
                best = (c, L + da)
    if best is None:
        raise SystemExit("segment too short")
    return a, best[1], best[0]


def make(name, src_path, ffmpeg):
    _, start, end, want, rhythm, xf, nr, peak = LOOPS[name]
    x = decode(ffmpeg, src_path, start, end, nr)
    a, L, corr = best_loop(x, want, rhythm, xf)
    X = int(xf * SR)
    loop = x[a:a + L].copy()
    t = np.linspace(0, np.pi / 2, X)
    fade_in, fade_out = np.sin(t), np.cos(t)
    loop[:X] = x[a:a + X] * fade_in + x[a + L:a + L + X] * fade_out
    loop *= 10 ** (peak / 20) / (np.abs(loop).max() + 1e-12)
    seam = abs(loop[-1] - loop[0])
    data = (np.clip(loop, -1, 1) * 32767).astype("<i2")
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())
    rms = 20 * np.log10(np.sqrt(np.mean(loop ** 2)) + 1e-12)
    print(f"{name:6s} {L / SR:6.3f} s  match {corr:.2f}  seam step {seam:.4f}  peak {peak:.1f}  rms {rms:.1f} dBFS  "
          f"from {start + a / SR:.2f} s")


def main():
    ffmpeg, mri, bed, _work = sys.argv[1:5]
    for name, spec in LOOPS.items():
        make(name, mri if spec[0] == "mri" else bed, ffmpeg)


if __name__ == "__main__":
    main()
