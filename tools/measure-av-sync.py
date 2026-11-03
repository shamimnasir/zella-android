#!/usr/bin/env python3
"""Measure A/V sync in a recording of the debug Sync test screen.

The screen flashes white and beeps at the same instant every 2 s. This finds each flash in the
video (mean brightness jump) and each beep onset in the audio (energy jump), pairs them, and
prints audio minus video in ms. Positive means sound is late.

Usage: tools/measure-av-sync.py recording.mp4 [--max-ms 40]
Needs ffmpeg and ffprobe on PATH. Exit code 1 if any pair exceeds --max-ms or nothing pairs.
"""
import argparse, json, subprocess, sys, struct

def video_flashes(path):
    # One mean-luma value per decoded frame, with its exact timestamp. VFR-safe: no fixed rate.
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-f", "lavfi", "-i",
         f"movie={path},signalstats", "-show_entries", "frame=pts_time:frame_tags=lavfi.signalstats.YAVG",
         "-of", "json"], capture_output=True, text=True, check=True).stdout
    frames = [(float(f["pts_time"]), float(f["tags"]["lavfi.signalstats.YAVG"]))
              for f in json.loads(out)["frames"] if "tags" in f]
    hits, prev = [], 255.0
    for t, y in frames:
        if y > 180 and prev < 100:
            hits.append(t)
        prev = y
    return hits

def audio_beeps(path, rate=48000):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0:a:0", "-ac", "1", "-ar", str(rate),
                          "-f", "s16le", "-"], capture_output=True, check=True).stdout
    # The decoder output starts at the audio stream's start time, which can differ from video's.
    start = float(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                                  "stream=start_time", "-of", "csv=p=0", path],
                                 capture_output=True, text=True, check=True).stdout.strip() or 0)
    n = len(raw) // 2
    pcm = struct.unpack(f"<{n}h", raw[: n * 2])
    win = rate // 1000  # 1 ms windows
    energy = [sum(abs(x) for x in pcm[i:i + win]) / win for i in range(0, n - win, win)]
    floor = sorted(energy)[len(energy) // 2] + 1
    hits, quiet_ms = [], 1000
    for ms, e in enumerate(energy):
        if e > floor * 8 and e > 300:
            if quiet_ms >= 500:
                hits.append(start + ms / 1000)
            quiet_ms = 0
        else:
            quiet_ms += 1
    return hits

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--max-ms", type=float, default=40)
    a = ap.parse_args()
    v, s = video_flashes(a.file), audio_beeps(a.file)
    print(f"flashes {len(v)}, beeps {len(s)}")
    pairs = []
    for t in v:
        near = min(s, key=lambda x: abs(x - t), default=None)
        if near is not None and abs(near - t) < 0.5:
            pairs.append((t, (near - t) * 1000))
    for t, d in pairs:
        print(f"  flash at {t:7.3f} s  sound {d:+6.1f} ms")
    if not pairs:
        print("FAIL: no flash and beep pairs found"); sys.exit(1)
    ds = [d for _, d in pairs]
    print(f"pairs {len(ds)}  mean {sum(ds)/len(ds):+.1f} ms  worst {max(ds, key=abs):+.1f} ms  limit {a.max_ms} ms")
    sys.exit(0 if all(abs(d) <= a.max_ms for d in ds) else 1)

if __name__ == "__main__":
    main()
