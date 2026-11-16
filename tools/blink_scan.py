"""Blink scan of a burst: a pixel blinks in frame k when it differs from both neighbours while they agree.
usage: blink_scan.py <burst dir> <label> [out sheet png]"""
import sys, glob, os, csv
import numpy as np
from PIL import Image

d, label = sys.argv[1], sys.argv[2]
files = sorted(glob.glob(os.path.join(d, f'burst-{label}-*.png')))
kinds = [f.rsplit('-', 1)[1][0] for f in files]
fr = [np.asarray(Image.open(f).convert('RGB'), dtype=np.int16) for f in files]
T, NEAR = 60, 12   # a change (sum over RGB) / neighbours alike
rows = []
for k in range(1, len(fr) - 1):
    a, b, c = fr[k - 1], fr[k], fr[k + 1]
    dab = np.abs(b - a).sum(2)
    dbc = np.abs(b - c).sum(2)
    dac = np.abs(a - c).sum(2)
    blink = (dab > T) & (dbc > T) & (dac < NEAR)
    n = int(blink.sum())
    moved = int((dab > T).sum())
    if n:
        ys, xs = np.nonzero(blink)
        box = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
    else:
        box = None
    rows.append((k, kinds[k], moved, n, box))
ns = [r[3] for r in rows]
print(f'{len(fr)} frames, kinds: {kinds.count("j")} recorded, {kinds.count("x")} extra; changed pixels per frame median {int(np.median([r[2] for r in rows]))}')
big = [r for r in rows if r[3] >= 30]
print(f'frames with a blink of >= 30 px: {len(big)}')
for r in sorted(big, key=lambda r: -r[3])[:12]:
    print(f'  frame {r[0]:3d} ({r[1]}): blink {r[3]} px in {r[4]}, changed {r[2]}')
if len(sys.argv) > 3:   # the worst blinks: neighbours side by side, cropped around the blink
    tiles = []
    for r in sorted(big, key=lambda r: -r[3])[:4]:
        x0, y0, x1, y1 = r[4]
        cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
        w = max(160, x1 - x0 + 60); h = max(120, y1 - y0 + 60)
        H, W = fr[0].shape[:2]
        bx0, by0 = max(0, cx - w // 2), max(0, cy - h // 2)
        bx1, by1 = min(W, bx0 + w), min(H, by0 + h)
        row = [Image.fromarray(fr[k][by0:by1, bx0:bx1].astype(np.uint8)).resize(((bx1 - bx0) * 2, (by1 - by0) * 2), Image.NEAREST) for k in (r[0] - 1, r[0], r[0] + 1)]
        tiles.append(row)
    if tiles:
        tw = max(sum(t.width for t in row) + 20 for row in tiles); th = sum(row[0].height + 10 for row in tiles)
        sheet = Image.new('RGB', (tw, th), (40, 40, 40))
        y = 0
        for row in tiles:
            x = 0
            for t in row:
                sheet.paste(t, (x, y)); x += t.width + 10
            y += row[0].height + 10
        sheet.save(sys.argv[3])
        print('sheet ->', sys.argv[3])
