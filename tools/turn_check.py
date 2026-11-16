"""Camera turn smoothness from a turn log (lab.frameThread.turnLog, sleepium/turnlog.csv) under the test mouse
(sleep.test.turnDegPerSec=D): every frame's yaw against a steady turn. The yaw minus its rolling median (200 ms) is the
jitter - a turn applied in steps at the tick rate, or a frame turned twice or not at all, shows as spikes.

usage: turn_check.py <turnlog.csv> <deg per sec> [--from S] [--to S]"""
import sys, csv
import numpy as np

path, dps = sys.argv[1], float(sys.argv[2])
t0 = float(sys.argv[sys.argv.index('--from') + 1]) if '--from' in sys.argv else None
t1 = float(sys.argv[sys.argv.index('--to') + 1]) if '--to' in sys.argv else None
rows = list(csv.DictReader(open(path)))
t = np.array([float(r['t_ms']) for r in rows]) / 1000.0
yaw = np.array([float(r['yaw']) for r in rows])
extra = np.array([r['extra'] == '1' for r in rows])
if t0 is None:   # the last 40 % of the log (after the scene's teleports)
    t0 = t[0] + (t[-1] - t[0]) * 0.6
m = (t >= t0) & ((t <= t1) if t1 is not None else True)
t, yaw, extra = t[m], yaw[m], extra[m]
d = np.diff(yaw)
jumps = np.abs(d - np.median(d)) > 30   # a teleport / wrap: cut there
if jumps.any():
    k = int(np.argmax(jumps)) + 1
    t, yaw, extra = (t[k:], yaw[k:], extra[k:]) if k < len(t) / 2 else (t[:k], yaw[:k], extra[:k])
lin = yaw - dps * t
w = max(5, int(0.2 / max(1e-4, np.median(np.diff(t)))))   # frames in 200 ms
med = np.array([np.median(lin[max(0, i - w // 2): i + w // 2 + 1]) for i in range(len(lin))])
r = lin - med
rate = np.diff(yaw) / np.maximum(1e-6, np.diff(t))
print(f'{len(t)} frames over {t[-1] - t[0]:.1f} s ({extra.sum()} extra), mean rate {np.polyfit(t, yaw, 1)[0]:.1f} deg/s (asked {dps})')
print(f'jitter (yaw minus rolling median): p50 {np.percentile(np.abs(r), 50):.3f}, p99 {np.percentile(np.abs(r), 99):.3f}, max {np.abs(r).max():.3f} deg')
print(f'frame-to-frame step vs steady: p99 {np.percentile(np.abs(np.diff(r)), 99):.3f}, max {np.abs(np.diff(r)).max():.3f} deg; '
      f'frames with no turn at all: {(np.abs(np.diff(yaw)) < 1e-4).sum()}')
