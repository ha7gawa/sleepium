"""Where the server tick's time goes, per scene: vanilla's own profiler (/debug start .. /debug stop) over a window of
normal ticking (/perf start: 10 s, a zip in debug/ with the profiler's text), the section tree flattened to "ms per tick" (1.3.4: the server tick as a graph - measure first).

    python tools/tick_profile.py [scene ...] [--secs 10] [--off] [--flags "..."] [--depth 4]
    python tools/tick_profile.py mobs --bots 50 [--botmode mix] [--botspread 48]   (bot players: dev.lull.bots.BotLoad)

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
ap.add_argument('--bots', default='0', help='bot players (BotLoad) around the scene, moving by --botmode while profiled; a list (0,10,50,100) = one window per count in one server')
ap.add_argument('--botmode', default='mix', help='mix | idle | "circle 4.3 24" | "orbit 10 160" | "line 15" | "tp 15 2000"')
ap.add_argument('--botspread', type=float, default=48)
ap.add_argument('--botwarm', type=int, default=20, help='seconds of bot movement before the profiled window (their first chunks)')
ap.add_argument('--ab', help='a runtime switch (/sleep bots ab NAME on|off): windows alternate off, on, off, on ... (--reps pairs) at each bot count')
ap.add_argument('--reps', type=int, default=3)
ap.add_argument('--normal', action='store_true', help='a normal (noise) world instead of the flat one: real terrain to generate and send')
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


KEY = [('tick', 'tick'), ('entities', '/tick/entities'), ('chunkSource', '/tick/chunkSource'),
       ('chunks', '/tick/chunkSource/chunks'), ('tracker+', '/tick/chunkSource/chunks/unspecified'),
       ('spawnAndTick', '/tick/chunkSource/chunks/pollingChunks/spawnAndTick'), ('unload', '/tick/chunkSource/unload'),
       ('connection', 'tick/connection'), ('players', 'tick/players'), ('send chunks', 'tick/send chunks'),
       ('tasks', 'nextTickWait/')]


def report(scene, n, zpath, bots_line):
    z = zipfile.ZipFile(zpath)
    names = [x for x in z.namelist() if x.endswith('profiling.txt')]
    if not names:
        print('%s: no profiling.txt in %s' % (scene, zpath), flush=True)
        return
    txt = os.path.join(RUN, 'tickprof-%s-%dbots-profiling.txt' % (scene, n))
    open(txt, 'wb').write(z.read(names[0]))
    rows, span_ms, ticks = parse(txt)
    if not rows or not ticks:
        print('%s: profile not parsed: %s' % (scene, zpath), flush=True)
        return
    ms_total = span_ms or 0
    ms = lambda pct: pct / 100.0 * ms_total / ticks
    print('=== %s (%s, %d bots %s): %d ticks in %d ms; profile %s' % (scene, 'off' if a.off else 'on', n, a.botmode if n else '', ticks, ms_total, os.path.basename(zpath)), flush=True)
    print('  ' + bots_line, flush=True)
    # the key phases in one line (the overworld's; tasks = work between ticks: packets handled, minus idle "unspecified")
    ow = 'tick/levels/ServerLevel[world] minecraft:overworld'
    key = []
    for name, suffix in KEY:
        if name == 'tasks':
            v = sum(ms(r[2]) for r in rows if r[0].startswith('nextTickWait/') and r[1] == 1 and not r[0].endswith('/unspecified'))
        elif suffix.startswith('/'):
            v = sum(ms(r[2]) for r in rows if r[0] == ow + suffix)
        else:
            v = sum(ms(r[2]) for r in rows if r[0] == suffix)
        key.append('%s %.2f' % (name, v))
    print('  key ms/tick: ' + '; '.join(key), flush=True)
    shown = [r for r in rows if r[1] <= a.depth]
    shown.sort(key=lambda r: -r[2])
    for pth, depth, pct, calls in shown[:a.top]:
        print('  %6.2f ms/tick  %5.1f%%  %s' % (ms(pct), pct, pth), flush=True)


steps = [int(v) for v in str(a.bots).split(',')]
for scene in a.scenes:
    x1, z1, x2, z2 = scene_area(scene)
    jvm = ['-Dsleep.seedEntities=true'] + ([] if a.off else ['-Dlab.opt.defaults=true']) + a.flags.split()
    log = os.path.join(RUN, 'tickprof-%s.log' % scene)
    props = {'spawn-monsters': 'false'}
    if a.normal:
        props['level-type'] = 'minecraft\:normal'
    with Server(jvm, props, log) as s:
        s.wait_for(r'Done \(')
        s.cmd('forceload add %d %d %d %d' % (x1, z1, x2, z2))
        s.cmd('tick freeze')
        s.cmd('tick step 300')
        time.sleep(18)
        s.cmd('sleep reseed')
        s.cmd('function sleep:lag_clear')
        s.cmd('function sleep:lag_%s' % scene)
        s.cmd('tick unfreeze')
        have = 0
        for i, n in enumerate(steps):
            if n < have:
                s.ask('sleep bots remove', r'bots: \d+ removed', timeout=60)
                have = 0
            if n > have:
                s.ask('sleep bots add %d %d %d %g' % (n - have, (x1 + x2) // 2, (z1 + z2) // 2, a.botspread), r'bots: \d+ joined', timeout=120)
                s.cmd('sleep bots move %s' % a.botmode)
                have = n
                time.sleep(a.botwarm)
            time.sleep(a.warm if i == 0 else 3)
            for state in (['off', 'on'] * a.reps if a.ab else [None]):
                if state:
                    s.ask('sleep bots ab %s %s' % (a.ab, state), r'bots: switch', timeout=30)
                    time.sleep(2)
                before = set(glob.glob(os.path.join(RUN, 'debug', 'profiling', '*.zip')))
                start = len(s.lines)
                s.cmd('sleep bots measure %d %s-%dbots%s' % (a.secs, scene, n, '-%s-%s' % (a.ab, state) if state else ''))
                s.cmd('perf start')
                s.wait_for(r'Created debug report', timeout=60, start=start)
                m = s.wait_for(r"sleep: (bots measure '.*)", timeout=30, start=start)
                st = s.ask('sleep bots stats', r'sleep: (bots stats: .*)', timeout=30)
                after = sorted(set(glob.glob(os.path.join(RUN, 'debug', 'profiling', '*.zip'))) - before)
                if not after:
                    print('%s: no profile written (see %s)' % (scene, log), flush=True)
                    continue
                report(scene, n, after[-1], m.group(1) + '\n  ' + st.group(1)[:600])
