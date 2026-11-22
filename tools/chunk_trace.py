"""Where a moving player's chunks wait (notes/92): a dev server of this build, N bot players (dev.lull.bots.BotLoad)
flying a straight line into new terrain, the chunk timeline (dev.lull.ChunkTimeline: ticket asked -> ticket added ->
noise -> lit -> full -> ready -> sent) and the per-second census over one measured window. One result line per run,
appended to run/chunk-trace.txt.

    python tools/chunk_trace.py LABEL [--slow 100] [--bots 1] [--speed 20] [--secs 30] [--warm 8] [--flags "..."] [--normal]
MC_PORT (default 25611) is the server's port.
"""
import argparse
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from server import Server, ROOT  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument('label')
ap.add_argument('--slow', type=int, default=0)
ap.add_argument('--bots', type=int, default=1)
ap.add_argument('--speed', type=float, default=20)
ap.add_argument('--secs', type=int, default=30)
ap.add_argument('--warm', type=int, default=8)
ap.add_argument('--flags', default='')
ap.add_argument('--off', action='store_true', help='without the engine defaults')
ap.add_argument('--normal', action='store_true', help='a noise world (real terrain) instead of the flat one')
a = ap.parse_args()
os.environ.setdefault('MC_PORT', '25611')
jvm = ['-Xmx1500m', '-Dsleep.serveMeter=true', '-Dsleep.serveMeter.timeline=true'] + ([] if a.off else ['-Dlab.opt.defaults=true']) + a.flags.split()
if a.slow:
    jvm.append('-Dlab.serve.slowTick=%d' % a.slow)
props = {'level-type': 'minecraft\\:normal'} if a.normal else {}
if a.normal:
    jvm.append('-Dsleep.bots.y=200')
log = os.path.join(ROOT, 'run', 'chunk-trace-%s.log' % a.label)
with Server(jvm, props, log) as s:
    s.wait_for(r'Done \(', timeout=300)
    s.ask('sleep bots add %d 0 0 0' % a.bots, r'bots: \d+ joined', timeout=120)
    s.cmd('sleep bots move line %g' % a.speed)
    time.sleep(a.warm)
    start = len(s.lines)
    s.cmd('sleep bots measure %d %s' % (a.secs, a.label))
    m = s.wait_for(r"sleep: (bots measure '.*)", timeout=a.secs + 120, start=start)
    line = m.group(1)
    tick = re.search(r'\d+ ticks \([\d.]+ tps\), tick mean [\d.]+', line)
    chunks = re.search(r'[\d.]+ chunks/s', line)
    tl = re.search(r'chunk timeline: .*', line)
    res = '== %s %s slow=%d bots=%d speed=%g flags=%s off=%s normal=%s\n  %s, %s\n  %s' % (
        time.strftime('%F %T'), a.label, a.slow, a.bots, a.speed, a.flags, a.off, a.normal,
        tick.group(0) if tick else '?', chunks.group(0) if chunks else '?', tl.group(0) if tl else line[-600:])
    print(res, flush=True)
    with open(os.path.join(ROOT, 'run', 'chunk-trace.txt'), 'a', encoding='utf-8') as f:
        f.write(res + '\n')
