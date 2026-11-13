"""Exactness for mods: every block of some mods placed and driven the way other mods' pipes drive them, the scene run with
the optimisations off and on (tools/exactness.py's discipline: seeded, frozen, stepped), every block entity's data
compared, and per class whether it slept at all - a class that never slept proves nothing.

    python tools/mod_exactness.py --mods F:/compat/x/mods [--ns modid,modid] [--ticks 2400] [--flags=-D...] [--control] [--offoff]

1.20.1: the mods (with their dependencies, released jars) reach the dev server through MC_MODS (build.gradle -PtestMods:
remapped from SRG onto the dev classpath); the scene runs on TickControl's freeze / step (1.20.1 has no vanilla /tick).
The blocks come from the
mods' lang files (block.<ns>.<name>), checked on the server (a lang key is not always a block, and one wrong id fails
the whole scene function), placed every 3 blocks. testmods.stimulus.Stimulator (-Dsleep.test.stimulate) drives them:
quiet / items, energy, water in / quiet / items out and a redstone block switched next to each, repeated every 1200
ticks. --control drops the engine's container / changed wakes (-Dsleep.test.dropWakes): a scene that still comes out
EXACT then cannot catch a missed wake. --offoff runs both without the optimisations: rows that differ there are the
mods' own nondeterminism (UUIDs, clocks, threads), not ours.
"""
import argparse
import collections
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from server import Server

ap = argparse.ArgumentParser()
ap.add_argument('--mods', required=True, help='a folder of mod jars (with their dependencies)')
ap.add_argument('--ns', default='', help='block namespaces to place (default: every namespace of the mods\' lang files)')
ap.add_argument('--ticks', type=int, default=2400)
ap.add_argument('--flags', default='', help='more JVM flags for the "on" run (e.g. -Dlab.sleep.ObservedModSleep=true)')
ap.add_argument('--control', action='store_true', help='negative control: the engine drops container / changed wakes')
ap.add_argument('--offoff', action='store_true', help='both runs without the optimisations (the mods\' own nondeterminism)')
ap.add_argument('--max', type=int, default=1200, help='at most this many blocks')
a = ap.parse_args()

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
RUN = os.path.join(ROOT, 'run')
SCENE = os.path.join(ROOT, 'src', 'scenes', 'resources', 'data', 'sleep', 'functions', 'lag_mods.mcfunction')
X0, Z0, SIDE, STEP = 3000, 3000, 40, 3

ids = []
want = set(x for x in a.ns.split(',') if x)
for f in sorted(os.listdir(a.mods)):
    if not f.endswith('.jar'):
        continue
    with zipfile.ZipFile(os.path.join(a.mods, f)) as z:
        for n in z.namelist():
            if re.match(r'assets/[^/]+/lang/en_us\.json$', n):
                for ns, name in re.findall(r'"block\.([a-z0-9_]+)\.([a-z0-9_]+)"', z.read(n).decode('utf-8', 'replace')):
                    if (not want or ns in want) and (ns + ':' + name) not in ids:
                        ids.append(ns + ':' + name)
            # a mod whose names come from another jar's lang (Thermal Expansion: no lang of its own): its blockstates
            m = re.match(r'assets/([a-z0-9_]+)/blockstates/([a-z0-9_]+)\.json$', n)
            if m and (not want or m.group(1) in want) and (m.group(1) + ':' + m.group(2)) not in ids:
                ids.append(m.group(1) + ':' + m.group(2))


# every loaded saved data written at each save (testmods SaveAllDataMixin): a mod's unwritable data shows at once; the
# save check's blocks driven as the scene drives them (fed from every side from the start: Functional Storage's ender
# drawer puts its null key into its saved data only once items went in)
SAVE_ALL = ['-Dsleep.test.saveAllData=true', '-Dsleep.test.stimulate=8,8,44,50', '-Dsleep.test.stimulate.work=true']


def check(ids):
    """the ids the server knows as blocks"""
    good = []
    srv = Server(SAVE_ALL).__enter__()   # save_breakers stops it (or its successors)
    try:
        srv.wait_for(r'Done \(')
        for b in ids:
            try:
                m = srv.ask('setblock 0 -50 0 ' + b, r'Changed the block|Could not set the block|Unknown block type|Incorrect argument|Unknown or incomplete|threw an exception', 20)
                r = srv.ask('setblock 0 -50 0 minecraft:air', r'Changed the block|Could not set the block|threw an exception', 20)
            except SystemExit as e:   # an answer of another kind (or none): the id stays out, the check goes on
                if srv.p.poll() is not None:
                    raise
                print('  skipped %s: %s' % (b, e), flush=True)
                continue
            # a block whose placing or removing throws (a mod's own bug, e.g. PneumaticCraft's programmable controller) stays out
            if m.group(0) in ('Changed the block', 'Could not set the block') and r.group(0) != 'threw an exception':
                good.append(b)
    except BaseException:
        srv.__exit__(None, None, None)
        raise
    # the blocks found to break the save are kept per mod set (the same mods' jars: the same answer) - the four runs of a
    # check (offoff, default, observed, control) would each bisect again, a fresh server per failed save (22 min, 11-11)
    import hashlib
    mods_key = hashlib.sha1('|'.join('%s:%d' % (f, os.path.getsize(os.path.join(a.mods, f))) for f in sorted(os.listdir(a.mods))
                                    if f.endswith('.jar')).encode()).hexdigest()[:12]
    cache = os.path.join(RUN, 'mod_exactness-save-breakers-%s.txt' % mods_key)
    if os.path.exists(cache):
        srv.__exit__(None, None, None)
        bad = [l.strip() for l in open(cache, encoding='utf-8') if l.strip()]
        for b in bad:
            print('  left out %s: the world does not save once its block entity ticked (found before for these mods)' % b, flush=True)
    else:
        bad = save_breakers(good, srv)
        with open(cache, 'w', encoding='utf-8') as f:
            f.write(''.join(b + chr(10) for b in bad))
    return [b for b in good if b not in bad]


def save_breakers(ids, first):
    """blocks whose block entity, once it ticked, leaves the world unsaveable - a mod's own bug the one-block check above
    cannot see (Functional Storage 1.20.1's ender drawer placed without its item's frequency puts a null key into its
    saved data on its first tick, and every save after it throws: the server dies at the next autosave). All placed at
    once, 2 s of ticks, save-all; a failing group is split, each failed save costing a fresh server (the bad data stays
    in the old one). first: the check's server, used until a save fails; stopped here."""
    W, Y, STEP_ = 12, -50, 3
    srv = [first]

    def ok(group):
        if srv[0] is None:
            srv[0] = Server(SAVE_ALL).__enter__()
            srv[0].wait_for(r'Done \(')
        s = srv[0]
        try:
            span = ((len(group) + W - 1) // W) * STEP_
            s.ask('forceload add 8 8 %d %d' % (8 + W * STEP_, 8 + span), r'force load|forceload|Unknown|Incorrect', 20)
            for i, b in enumerate(group):
                s.ask('setblock %d %d %d %s' % (8 + (i % W) * STEP_, Y, 8 + (i // W) * STEP_, b),
                      r'Changed the block|Could not set the block|threw an exception|Unknown|Incorrect', 20)
            t0 = int(s.ask('time query gametime', r'The time is (\d+)', 20).group(1))
            while int(s.ask('time query gametime', r'The time is (\d+)', 20).group(1)) < t0 + 45:
                time.sleep(0.5)
            with s.lock:
                at = len(s.lines)
            # flush: 1.21.1 writes saved data asynchronously - its exception only shows in the log (and hangs the stop)
            m = s.ask('save-all flush', r'Saved the game|Saving failed|unexpected error|threw an exception', 180)
            time.sleep(1.0)
            with s.lock:
                thrown = [l for l in s.lines[at:] if re.search(r'Exception|/ERROR\]', l)]
            if m.group(0) == 'Saved the game' and not thrown:
                s.ask('fill 8 %d 8 %d %d %d minecraft:air' % (Y, 8 + W * STEP_, Y, 8 + span),
                      r'Successfully filled|No blocks were filled|threw an exception|Unknown|Incorrect|too many', 20)
                return True
        except SystemExit as e:
            print('  save check: %s' % e, flush=True)
        s.__exit__(None, None, None)   # its own save on stop throws as well: logged, the process ends
        srv[0] = None
        return False

    bad = []

    def find(group, failed=False):
        if not failed and ok(group):
            return
        if len(group) == 1:
            print('  left out %s: the world does not save once its block entity ticked (the mod\'s own bug)' % group[0], flush=True)
            bad.append(group[0])
            return
        h = len(group) // 2
        if ok(group[:h]):
            find(group[h:], True)
        else:
            find(group[:h], True)
            find(group[h:])

    find(ids)
    if srv[0] is not None:
        srv[0].cmd('stop')
        srv[0].p.wait(timeout=180)
        srv[0].__exit__(None, None, None)
    return bad


def scene(ids):
    rows = (len(ids) + SIDE - 1) // SIDE
    x1, z1 = X0 + SIDE * STEP, Z0 + rows * STEP + 2
    lines = ['# sleep:lag_mods - generated by tools/mod_exactness.py (not kept): every block of some mods every %d blocks' % STEP,
             'gamerule doMobSpawning false', 'gamerule randomTickSpeed 0', 'time set noon', 'weather clear',
             'forceload add %d %d %d %d' % (X0, Z0, x1, z1),
             'kill @e[type=!minecraft:player,x=%d,y=-61,z=%d,dx=%d,dy=8,dz=%d]' % (X0, Z0, x1 - X0, z1 - Z0)]
    for zz in range(Z0, z1 + 1, 16):   # fill is limited to 32768 blocks per call
        lines.append('fill %d -60 %d %d -54 %d minecraft:air' % (X0, zz, x1, min(z1, zz + 15)))
    for i, b in enumerate(ids):
        lines.append('setblock %d -60 %d %s' % (X0 + (i % SIDE) * STEP, Z0 + (i // SIDE) * STEP, b))
    os.makedirs(os.path.dirname(SCENE), exist_ok=True)
    with open(SCENE, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    return x1, z1


os.environ['MC_MODS'] = os.path.abspath(a.mods)   # every server of this run (here and in exactness.py) gets them remapped
out = ''
try:
    print('checking %d ids on the server ...' % len(ids), flush=True)
    ids = check(ids)[:a.max]
    x1, z1 = scene(ids)
    print('%d blocks, area %d %d %d %d' % (len(ids), X0, Z0, x1, z1), flush=True)
    jvm = '-Dsleep.test.stimulate=%d,%d,%d,%d -Dsleep.test.coverage=true' % (X0, Z0, x1, z1)
    if a.control:
        jvm += ' -Dsleep.test.dropWakes=container,changed'
    cmd = [sys.executable, os.path.join(ROOT, 'tools', 'exactness.py'), 'mods', '--ticks', str(a.ticks), '--jvm=' + jvm]
    if a.flags:
        cmd.append('--flags=' + a.flags)
    if a.offoff:
        cmd.append('--offoff')
    for mode in ('off', 'on'):   # no rows of an earlier run may stand in for a run that did not happen
        stale = os.path.join(RUN, 'exactness-mods-%s-berows.txt' % mode)
        if os.path.exists(stale):
            os.remove(stale)
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
finally:
    if os.path.exists(SCENE):
        os.remove(SCENE)
print(out.strip()[-1500:])
if 'stopped before' in out or not all(os.path.exists(os.path.join(RUN, 'exactness-mods-%s-berows.txt' % m)) for m in ('off', 'on')):
    print('VERDICT: NO RESULT - a server did not start or did not finish (see run/exactness-mods-off.log / -on.log)')
    sys.exit(2)


def berows(mode):
    p = os.path.join(RUN, 'exactness-mods-%s-berows.txt' % mode)
    d = {}
    if os.path.exists(p):
        for l in open(p, encoding='utf-8', errors='replace'):
            m = re.match(r'(-?\d+), (-?\d+), (-?\d+) (Block\{[^}]+\})', l)
            if m:
                d['%s,%s,%s' % m.groups()[:3]] = (m.group(4), l.rstrip('\n'))
    return d


log = open(os.path.join(RUN, 'exactness-mods-on.log'), encoding='utf-8', errors='replace').read()
cls_at = dict(re.findall(r'\[sleep/stimulus\] be (-?\d+,-?\d+,-?\d+) (\S+)', log))
cover = {c: (int(s), int(k), int(w)) for c, s, k, w in re.findall(r'\[sleep/coverage\] (\S+) sleeps (\d+) skipped (\d+) wakes (\d+)', log)}
verdict = {}
for name, v, why in re.findall(r'\[sleep/sleep\] (\w+): (ELIGIBLE|no:) ?([^\[\n]{0,90})', log):
    verdict.setdefault(name, 'eligible' if v == 'ELIGIBLE' else 'refused: ' + why.strip())
stim = re.findall(r'\[sleep/stimulus\] block entities[^\n]*', log)
off, on = berows('off'), berows('on')
bad = collections.Counter()
for pos in sorted(set(off) | set(on)):
    if off.get(pos, (None, None))[1] != on.get(pos, (None, None))[1]:
        bad[cls_at.get(pos, (on.get(pos) or off.get(pos))[0])] += 1
classes = sorted(set(cls_at.values()) | set(bad))
# the mods' own nondeterminism: an --offoff run remembers the classes that differ without any optimisation; other runs
# show them as "noise" (still listed: a real difference could hide under the noise of the same class)
NOISE = os.path.join(RUN, 'modexact-noise.txt')
if a.offoff:   # added to the list kept over the runs of every mod group
    known = set(l.strip() for l in open(NOISE, encoding='utf-8') if l.strip()) if os.path.exists(NOISE) else set()
    with open(NOISE, 'w', encoding='utf-8') as f:
        f.write(''.join(c + '\n' for c in sorted(known | set(bad))))
noise = set(l.strip() for l in open(NOISE, encoding='utf-8')) if os.path.exists(NOISE) and not a.offoff else set()
print('\n' + (stim[0] if stim else 'no stimulus report'))
print('%-60s %-9s %8s %10s %7s  %s' % ('class', 'rows', 'sleeps', 'skipped', 'wakes', 'analysis'))
tested = 0
for c in classes:
    s, k, w = cover.get(c, (0, 0, 0))
    tested += s > 0 and w > 0
    rows_ = (('noise %d' if c in noise else 'DIFF %d') % bad[c]) if bad[c] else 'same'
    print('%-60s %-9s %8d %10d %7d  %s' % (c[-60:], rows_, s, k, w, verdict.get(c.rsplit('.', 1)[-1], '?')[:70]))
real = [c for c in classes if bad[c] and c not in noise]
print('\nclasses %d, slept and woke (tested) %d, differing %d (%d of them the mods\' own noise)'
      % (len(classes), tested, sum(1 for c in classes if bad[c]), sum(1 for c in classes if bad[c] and c in noise)))
print('VERDICT: ' + ('EXACT beyond the mods\' own noise' if not real else 'DIFFERENT: ' + ', '.join(c.rsplit('.', 1)[-1] for c in real)))
