"""Loudness evidence (B20, factory gate `loudness`): renders what the app plays at the default volume (0.8, ScanAudio
gains: beds x volume, motor x volume x 1.0, cues x volume) in the order of a real run, and measures it the way
bin/ui_gates.py does (20 ms windows, only windows above peak - 30 dB = the sounding part).

Rendered, not recorded: the Windows emulator host has no audio loopback capture here (build.md "只有短促反馈音的应用"
rule: render from the playback code at the default gain and say so).

    python -I tools/loudness.py OUT.wav [volume]

The run: table motor 4 s (sliding in), localiser 8 s, break 3 s, T1 6 s, diffusion 6 s, a big-movement tick and the
call two-tone at the end (both cues are short UI-like signals of the scanner room), 30 s in all.
"""
import array
import math
import os
import sys
import wave

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
AUDIO = os.path.join(ROOT, "app", "src", "main", "assets", "audio")
SR = 44100
MOTOR_GAIN = 1.0  # ScanAudio.MOTOR_GAIN


def load(name):
    with wave.open(os.path.join(AUDIO, name + ".wav")) as w:
        assert w.getframerate() == SR and w.getsampwidth() == 2
        data = array.array("h", w.readframes(w.getnframes()))
    return [v / 32768.0 for v in data]


def loop(x, seconds):
    n = int(seconds * SR)
    return [x[i % len(x)] for i in range(n)]


def levels(samples):
    """(rms_dbfs, peak_dbfs) as ui_gates.wav_levels: 20 ms windows above peak - 30 dB."""
    data = [int(max(-32768, min(32767, round(v * 32767)))) for v in samples]
    peak = max(abs(v) for v in data) or 1
    win = 882
    energies = [sum(v * v for v in data[k:k + win]) / win for k in range(0, len(data) - win + 1, win)]
    thr = (peak * 0.0316) ** 2
    voiced = [e for e in energies if e >= thr] or energies
    rms = math.sqrt(sum(voiced) / len(voiced))
    return 20 * math.log10(rms / 32768), 20 * math.log10(peak / 32768)


def main():
    out = sys.argv[1]
    vol = float(sys.argv[2]) if len(sys.argv) > 2 else 0.8
    track = []
    track += [v * vol * MOTOR_GAIN for v in loop(load("motor"), 4)]
    track += [v * vol for v in loop(load("loc"), 8)]
    track += [v * vol for v in loop(load("gap"), 3)]
    track += [v * vol for v in loop(load("t1"), 6)]
    track += [v * vol for v in loop(load("epi"), 6)]
    for cue in ("tick", "call"):
        track += [v * vol for v in load(cue)]
        track += [0.0] * int(0.4 * SR)
    track = track[: 30 * SR] + [0.0] * max(0, 30 * SR - len(track))
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(array.array("h", [int(max(-32768, min(32767, round(v * 32767)))) for v in track]).tobytes())
    rms, peak = levels(track)
    print("%s volume=%.2f sounding RMS %.1f dBFS, peak %.1f dBFS (rendered, not recorded)" % (os.path.basename(out), vol, rms, peak))
    for name in ("motor", "loc", "gap", "t1", "t2", "epi", "tick", "call"):
        g = vol * (MOTOR_GAIN if name == "motor" else 1.0)
        r, p = levels([v * g for v in load(name)])
        print("  %-6s at x%.2f: sounding RMS %.1f dBFS, peak %.1f dBFS" % (name, g, r, p))


if __name__ == "__main__":
    main()
