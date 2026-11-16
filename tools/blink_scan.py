"""Blink scan of a frame burst (sleep.clientAuto.burst): a pixel blinks in frame k when it differs from both
neighbours while they agree (something shown for one frame only, or missing for one frame only).
For each frame the densest 40x40 cluster of blinking pixels is found; the worst clusters are cut out five frames wide
(k-2 .. k+2), enlarged, into a sheet - look at it: animation shows as small moves, a blink as a thing that is there in
one frame only (or gone in one frame only).

usage: blink_scan.py <burst dir> <label> [sheet.png] [--min N]"""
import sys, glob, os
import numpy as np
from PIL import Image

args = [a for a in sys.argv[1:] if not a.startswith('--')]
MIN = int(sys.argv[sys.argv.index('--min') + 1]) if '--min' in sys.argv else 25
if '--min' in sys.argv:
    args = [a for a in args if a != str(MIN)]
d, label = args[0], args[1]
sheet_path = args[2] if len(args) > 2 else None
files = sorted(glob.glob(os.path.join(d, f'burst-{label}-*.png')))
kinds = [f.rsplit('-', 1)[1][0] for f in files]
fr = [np.asarray(Image.open(f).convert('RGB'), dtype=np.int16) for f in files]
H, W = fr[0].shape[:2]
T, NEAR, WIN = 60, 12, 40


def densest(mask):
    """the WIN x WIN window with the most set pixels: (count, cx, cy)"""
    c = np.pad(mask.astype(np.int32).cumsum(0).cumsum(1), ((1, 0), (1, 0)))
    s = c[WIN:, WIN:] - c[:-WIN, WIN:] - c[WIN:, :-WIN] + c[:-WIN, :-WIN]
    y, x = np.unravel_index(np.argmax(s), s.shape)
    return int(s[y, x]), int(x + WIN // 2), int(y + WIN // 2)


rows = []
for k in range(1, len(fr) - 1):
    a, b, c = fr[k - 1], fr[k], fr[k + 1]
    blink = (np.abs(b - a).sum(2) > T) & (np.abs(b - c).sum(2) > T) & (np.abs(a - c).sum(2) < NEAR)
    n, cx, cy = densest(blink)
    rows.append((k, kinds[k], int(blink.sum()), n, cx, cy))
print(f'{len(fr)} frames ({kinds.count("j")} recorded, {kinds.count("x")} extra); blinking pixels per frame median {int(np.median([r[2] for r in rows]))}')
big = sorted([r for r in rows if r[3] >= MIN], key=lambda r: -r[3])
print(f'frames with a blink cluster of >= {MIN} px in {WIN}x{WIN}: {len(big)}')
for r in big[:15]:
    print(f'  frame {r[0]:3d} ({r[1]}): cluster {r[3]} px at ({r[4]}, {r[5]}), blinking in the frame {r[2]}')
if sheet_path and big:
    tiles = []
    for r in big[:6]:
        k, cx, cy = r[0], r[4], r[5]
        x0, y0 = max(0, min(W - 120, cx - 60)), max(0, min(H - 80, cy - 40))
        row = []
        for i in range(k - 2, k + 3):
            if 0 <= i < len(fr):
                row.append(Image.fromarray(fr[i][y0:y0 + 80, x0:x0 + 120].astype(np.uint8)).resize((480, 320), Image.NEAREST))
        tiles.append(row)
    sw = max(sum(t.width + 8 for t in row) for row in tiles)
    sh = sum(row[0].height + 8 for row in tiles)
    sheet = Image.new('RGB', (sw, sh), (30, 30, 30))
    y = 0
    for row in tiles:
        x = 0
        for t in row:
            sheet.paste(t, (x, y))
            x += t.width + 8
        y += row[0].height + 8
    sheet.save(sheet_path)
    print('sheet ->', sheet_path, '(rows: the worst clusters; columns: frames k-2 .. k+2)')
