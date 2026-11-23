"""A scene ticking for a while with given JVM flags, the server's log lines matching a pattern printed (lab measurements
that report into the log, e.g. ServerParallelItems' "[sleep/spi]"; no /perf: the profiler stays inactive).

    python tools/scene_run.py <scene> [--secs 40] [--warm 10] [--off] [--flags "..."] [--grep "sleep/spi"]
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from server import Server, scene_area, RUN

ap = argparse.ArgumentParser()
ap.add_argument('scene')
ap.add_argument('--secs', type=int, default=40)
ap.add_argument('--warm', type=int, default=10)
ap.add_argument('--off', action='store_true')
ap.add_argument('--flags', default='')
ap.add_argument('--grep', default=r'\[sleep/')
ap.add_argument('--cmd', action='append', default=[], help='console commands sent at the end (repeatable)')
a = ap.parse_args()

x1, z1, x2, z2 = scene_area(a.scene)
jvm = ['-Dsleep.seedEntities=true'] + ([] if a.off else ['-Dlab.opt.defaults=true']) + a.flags.split()
log = os.path.join(RUN, 'scenerun-%s.log' % a.scene)
with Server(jvm, {'spawn-monsters': 'false'}, log) as s:
    s.wait_for(r'Done \(')
    s.cmd('forceload add %d %d %d %d' % (x1, z1, x2, z2))
    s.cmd('tick freeze')
    s.cmd('tick step 300')
    time.sleep(18)
    s.cmd('sleep reseed')
    s.cmd('function sleep:lag_clear')
    s.cmd('function sleep:lag_%s' % a.scene)
    s.cmd('tick unfreeze')
    time.sleep(a.warm + a.secs)
    for c in a.cmd:
        s.cmd(c)
        time.sleep(2)
    s.cmd('tick query')
    time.sleep(2)
    lines = list(s.lines)
pat = re.compile(a.grep)
hits = [l.rstrip() for l in lines if pat.search(l)]
for l in hits[-12:]:
    print(l[:400])
errs = [l for l in lines if 'Exception' in l or '/ERROR]' in l]
print('errors in the log: %d (see %s)' % (len(errs), log))
