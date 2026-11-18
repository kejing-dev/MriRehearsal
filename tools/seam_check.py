"""Clicks in a loopback recording: per 10 ms, the largest sample step over the median step of the surrounding second,
and the >8 kHz energy (the loops are low-passed at 6 kHz, so energy up there is a click). Prints the strongest events.
    python -I seam_check.py REC.wav [top]"""
import sys
import wave

import numpy as np
from scipy.signal import butter, sosfilt

w = wave.open(sys.argv[1])
sr = w.getframerate()
ch = w.getnchannels()
x = np.frombuffer(w.readframes(w.getnframes()), "<i2").reshape(-1, ch).astype(float).mean(1) / 32768
top = int(sys.argv[2]) if len(sys.argv) > 2 else 12
hf = sosfilt(butter(8, 8000, "hp", fs=sr, output="sos"), x)
k = int(0.01 * sr)
n = len(x) // k
d = np.abs(np.diff(x, append=x[-1]))
step = np.array([d[i * k:(i + 1) * k].max() for i in range(n)])
med = np.array([np.median(d[max(0, (i - 50) * k):(i + 50) * k]) + 1e-6 for i in range(n)])
hfe = 20 * np.log10(np.array([np.sqrt(np.mean(hf[i * k:(i + 1) * k] ** 2)) for i in range(n)]) + 1e-9)
hfmed = np.array([np.median(hfe[max(0, i - 50):i + 50]) for i in range(n)])
score = step / med
order = np.argsort(score)[::-1]
print(f"{'t s':>7} {'step/med':>8} {'HF over local dB':>16}")
shown = []
for i in order:
    if any(abs(i - j) < 5 for j in shown):
        continue
    shown.append(i)
    print(f"{i * 0.01:7.2f} {score[i]:8.1f} {hfe[i] - hfmed[i]:16.1f}")
    if len(shown) >= top:
        break
