#!/usr/bin/env python3
"""Generate test tones (16-bit mono WAV, 48 kHz) for playing through studio monitors at the tablet.

    scripts/tones.py out_dir
    aplay -q out_dir/saw220.wav      # rich 220 Hz source: harmonics, overtone count, ring band
    aplay -q out_dir/tone440.wav     # pure A4 for pitch/cents and calibration

Files: tone440 (sine), saw220 (30 harmonics, −6 dB/oct), voice82 (E2, 40 harmonics, −9 dB/oct),
voice1047 (C6, 12 harmonics), vib220 (5.5 Hz ±70 ¢ vibrato), sweep (80 Hz → 8 kHz, 8 s).
"""
import math
import os
import struct
import sys
import wave

FS = 48000


def write(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(FS)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples))


def envelope(i, n, fade=0.05):
    t = i / FS
    return min(1.0, t / fade, (n / FS - t) / fade)


def harmonic(hz_at, seconds, amp, harmonics, tilt_db):
    n = int(seconds * FS)
    amps = [10 ** (tilt_db * math.log2(k) / 20) for k in range(1, harmonics + 1)]
    phases = [0.0] * harmonics
    out = []
    for i in range(n):
        hz = hz_at(i / FS)
        s = 0.0
        for k in range(1, harmonics + 1):
            if hz * k < FS / 2 * 0.9:
                phases[k - 1] += 2 * math.pi * hz * k / FS
                s += amps[k - 1] * math.sin(phases[k - 1])
        out.append(s * amp * envelope(i, n))
    peak = max(abs(v) for v in out) or 1.0
    return [v / peak * amp for v in out]


def main(out):
    os.makedirs(out, exist_ok=True)
    write(f"{out}/tone440.wav", harmonic(lambda t: 440.0, 6.0, 0.3, 1, 0.0))
    write(f"{out}/saw220.wav", harmonic(lambda t: 220.0, 6.0, 0.3, 30, -6.0))
    write(f"{out}/voice82.wav", harmonic(lambda t: 82.41, 6.0, 0.3, 40, -9.0))
    write(f"{out}/voice1047.wav", harmonic(lambda t: 1046.5, 6.0, 0.25, 12, -9.0))
    write(f"{out}/vib220.wav", harmonic(lambda t: 220.0 * 2 ** (70 * math.sin(2 * math.pi * 5.5 * t) / 1200), 6.0, 0.3, 20, -9.0))
    write(f"{out}/sweep.wav", harmonic(lambda t: 80.0 * (8000.0 / 80.0) ** (t / 8.0), 8.0, 0.3, 1, 0.0))
    print("written to", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/tones")
