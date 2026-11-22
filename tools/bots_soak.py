"""A soak with bots: one dedicated server, the mobs scene, N bots moving ("mix" by default) for --mins minutes, a
"bots measure" window every minute (the lab features' counters with them: verify mismatches, ...), errors counted.

    MC_PORT=25601 python tools/bots_soak.py --mins 10 --bots 100 --normal --flags "-Dlab.opt.TrackFast=true -Dlab.opt.TrackFastVerify=true"
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from server import Server, scene_area, RUN

ap = argparse.ArgumentParser()
ap.add_argument('--mins', type=int, default=10)
ap.add_argument('--bots', type=int, default=100)
ap.add_argument('--botmode', default='mix')
ap.add_argument('--scene', default='mobs')
ap.add_argument('--normal', action='store_true')
ap.add_argument('--flags', default='')
ap.add_argument('--ab', help='a BotLoad switch flipped off / on every window (interleaved A/B)')
ap.add_argument('--window', type=int, default=58)
a = ap.parse_args()
x1, z1, x2, z2 = scene_area(a.scene)
jvm = ['-Dsleep.seedEntities=true', '-Dlab.opt.defaults=true', '-Dsleep.bots.y=200' if a.normal else ''] + a.flags.split()
props = {'spawn-monsters': 'false'}
if a.normal:
    props['level-type'] = 'minecraft\\:normal'
log = os.path.join(RUN, 'bots-soak.log')
with Server([j for j in jvm if j], props, log) as s:
    s.wait_for(r'Done \(', timeout=300)
    s.cmd('forceload add %d %d %d %d' % (x1, z1, x2, z2))
    s.cmd('function sleep:lag_clear')
    s.cmd('function sleep:lag_%s' % a.scene)
    s.ask('sleep bots add %d %d %d 48' % (a.bots, (x1 + x2) // 2, (z1 + z2) // 2), r'bots: \d+ joined', timeout=180)
    s.cmd('sleep bots move %s' % a.botmode)
    print('bots joined; soak %d min' % a.mins, flush=True)
    for i in range(a.mins):
        if a.ab:
            s.ask('sleep bots ab %s %s' % (a.ab, 'off' if i % 2 == 0 else 'on'), r'bots: switch', timeout=30)
            time.sleep(2)
        start = len(s.lines)
        s.cmd('sleep bots measure %d soak%d' % (a.window, i + 1))
        m = s.wait_for(r"sleep: (bots measure 'soak%d'.*)" % (i + 1), timeout=200, start=start)
        line = m.group(1)
        keep = [p for p in line.split(' | ') if p.startswith(('bots measure', 'trackfast', 'chunksend', 'trackindex', 'netorder', 'pipescout'))]
        print('  ' + '\n    '.join(k[:420] for k in keep), flush=True)
        time.sleep(1)
    st = s.ask('sleep bots stats', r'sleep: (bots stats: .*)', timeout=30)
    print('  ' + st.group(1)[:300], flush=True)
    errs = s.errors()
    print('errors in the log (known ones excluded): %d' % len(errs), flush=True)
    for e in errs[:15]:
        print('  ' + e[:250], flush=True)
