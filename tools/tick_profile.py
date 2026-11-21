"""Where the server tick's time goes, per scene: vanilla's own profiler (/debug start .. /debug stop) over a window of
normal ticking (/perf start: 10 s, a zip in debug/ with the profiler's text), the section tree flattened to "ms per tick" (1.3.4: the server tick as a graph - measure first).

    python tools/tick_profile.py [scene ...] [--secs 10] [--off] [--flags "..."] [--depth 4]

Each scene: a fresh flat world, the scene built as tools/exactness.py does (frozen clock, game time 300, reseeded), then
the clock unfrozen, a few seconds of warm-up, the profiled window. The engine's defaults are on unless --off.
Prints per scene the tick's sections (path, ms per tick, % of the tick) down to --depth, the largest first.
"""
import argparse
import io
import zipfile
import glob
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from server import Server, scene_area, RUN

ap = argparse.ArgumentParser()
ap.add_argument('scenes', nargs='*', default=['mobs', 'villagers', 'zoo', 'hoppers', 'items', 'fluids', 'redstone', 'beactive'])
ap.add_argument('--secs', type=int, default=10)
ap.add_argument('--warm', type=int, default=8)
ap.add_argument('--off', action='store_true')
ap.add_argument('--flags', default='')
ap.add_argument('--depth', type=int, default=4)
ap.add_argument('--top', type=int, default=18)
a = ap.parse_args()

LINE = re.compile(r'^\[(\d+)\]\s*([| ]*)(\S.*?)\((\d+)/(\d+)\)\s*-\s*([\d.]+)%/([\d.]+)%')


def parse(path):
    """vanilla's profile-results text: "[02] |   levels(201/1) - 80.73%/76.79%" -> (path, depth, % of total)"""
    stack, out = [], []
    tick_pct = None
    for line in open(path, encoding='utf-8', errors='replace'):
        m = LINE.match(line.rstrip())
        if not m:
            continue
        depth = int(m.group(1))
        name = m.group(3).strip()
        stack = stack[:depth] + [name]
        total = float(m.group(7))
        p = '/'.join(stack)
        out.append((p, depth, total, int(m.group(4))))
        if tick_pct is None and name == 'tick':
            tick_pct = total
    m = re.search(r'Time span: (\d+) ms', open(path, encoding='utf-8', errors='replace').read())
    m2 = re.search(r'Tick span: (\d+) ticks', open(path, encoding='utf-8', errors='replace').read())
    return out, (int(m.group(1)) if m else None), (int(m2.group(1)) if m2 else None)


for scene in a.scenes:
    x1, z1, x2, z2 = scene_area(scene)
    jvm = ['-Dsleep.seedEntities=true'] + ([] if a.off else ['-Dlab.opt.defaults=true']) + a.flags.split()
    log = os.path.join(RUN, 'tickprof-%s.log' % scene)
    before = set(glob.glob(os.path.join(RUN, 'debug', 'profiling', '*.zip')))
    with Server(jvm, {'spawn-monsters': 'false'}, log) as s:
        s.wait_for(r'Done \(')
        s.cmd('forceload add %d %d %d %d' % (x1, z1, x2, z2))
        s.cmd('tick freeze')
        s.cmd('tick step 300')
        time.sleep(18)
        s.cmd('sleep reseed')
        s.cmd('function sleep:lag_clear')
        s.cmd('function sleep:lag_%s' % scene)
        s.cmd('tick unfreeze')
        time.sleep(a.warm)
        s.cmd('perf start')
        s.wait_for(r'Created debug report', timeout=60, start=len(s.lines))
        time.sleep(3)
    after = sorted(set(glob.glob(os.path.join(RUN, 'debug', 'profiling', '*.zip'))) - before)
    if not after:
        print('%s: no profile written (see %s)' % (scene, log), flush=True)
        continue
    z = zipfile.ZipFile(after[-1])
    names = [n for n in z.namelist() if n.endswith('profiling.txt')]
    if not names:
        print('%s: no profiling.txt in %s: %s' % (scene, after[-1], z.namelist()[:10]), flush=True)
        continue
    txt = os.path.join(RUN, 'tickprof-%s-profiling.txt' % scene)
    open(txt, 'wb').write(z.read(names[0]))
    rows, span_ms, ticks = parse(txt)
    if not rows or not ticks:
        print('%s: profile not parsed: %s' % (scene, after[-1]), flush=True)
        continue
    ms_total = span_ms if span_ms else 0
    print('=== %s (%s): %d ticks in %d ms; profile %s' % (scene, 'off' if a.off else 'on', ticks, ms_total, os.path.basename(after[-1])), flush=True)
    shown = [r for r in rows if r[1] <= a.depth]
    shown.sort(key=lambda r: -r[2])
    for p, depth, pct, calls in shown[:a.top]:
        ms_per_tick = pct / 100.0 * ms_total / ticks
        print('  %6.2f ms/tick  %5.1f%%  %s' % (ms_per_tick, pct, p), flush=True)
