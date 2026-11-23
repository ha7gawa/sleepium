"""Mob mass runs (tools/mobmass.py --prod) on the test machines of tools/box_suite.py, one server per machine at a time.

    python tools/box_mobmass.py --machines x92,x103,n100,pi --jar X.jar [--scenes pigs5k,pigs10k] [--warm 30] [--secs 40]

Per machine: mobmass.py and the scene files go to <base>/mobmass on it, the jar too; then for each scene a vanilla run
(NeoForge alone) and a run with the jar, in turn, on the machine's 1.21.1 suite server folder (mobmass.py saves and puts
back the folder's own mods / properties). Every machine runs its list on its own thread; each finished run's line is
appended to run/mobmass/box-results.txt here with the machine's name, and its console log is fetched beside it.
"""
import argparse
import os
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.argv, _argv = sys.argv[:1] + ['--version', '1.20.1', '--check'], sys.argv
import box_suite as B   # noqa: E402  (its machine table and ssh helpers; its own argument parsing satisfied above)
sys.argv = _argv

ap = argparse.ArgumentParser()
ap.add_argument('--machines', default='x92,x103,n100,pi')
ap.add_argument('--jar', required=True)
ap.add_argument('--scenes', default='pigs5k,pigs10k')
ap.add_argument('--variants', default='vanilla,jar', help='vanilla, jar, or jar:NAME=-Dx=y;-Dz=w (the jar with those flags, label suffix NAME)')
ap.add_argument('--sep', default=',', help='separator between variants (JVM options with commas: another one)')
ap.add_argument('--rounds', type=int, default=1, help='the scene x variants list this many times (interleaved A/B)')
ap.add_argument('--warm', type=int, default=30)
ap.add_argument('--secs', type=int, default=40)
ap.add_argument('--flags', default='')
a = ap.parse_args()
OUT = os.path.join(ROOT, 'run', 'mobmass')
os.makedirs(OUT, exist_ok=True)
LOCK = threading.Lock()


def log(s):
    with LOCK:
        print(time.strftime('%H:%M:%S ') + s, flush=True)


def machine(name):
    m = B.Machine(name, B.MACHINES[name])
    base = m.cfg['base'].rsplit('boxsuite', 1)[0] + 'mobmass'
    sep = '\\' if m.win else '/'
    j = lambda *p: sep.join((base,) + p)
    srv = m.cfg['servers']['1.20.1'][0]
    java = m.cfg['java']['1.20.1']
    jav = m.javas()
    if jav is None:
        log('%s: unreachable' % name)
        return
    if jav:
        log('%s: java already running, skipped: %s' % (name, jav[:200]))
        return
    if not m.server_ready(srv):
        log('%s: no server folder %s (box_suite --setup)' % (name, srv))
        return
    fn = j('src', 'scenes', 'resources', 'data', 'sleep', 'functions')
    if m.win:
        m.sh('mkdir "%s" "%s" "%s" 2>nul' % (j('tools'), fn, j('run', 'mobmass')))
    else:
        m.sh('mkdir -p %s %s %s' % (j('tools'), fn, j('run', 'mobmass')))
    m.put(os.path.join(HERE, 'mobmass.py'), j('tools', 'mobmass.py'))
    scenes = a.scenes.split(',')
    for sc in scenes:
        m.put(os.path.join(ROOT, 'src', 'scenes', 'resources', 'data', 'sleep', 'functions', 'lag_%s.mcfunction' % sc), fn + sep + 'lag_%s.mcfunction' % sc)
    jar = j(os.path.basename(a.jar))
    m.put(a.jar, jar)
    heap = m.cfg['heap']
    for rnd in range(a.rounds):
      for sc in scenes:
        for v in a.variants.split(a.sep):
            vflags, suffix = a.flags, ''
            if v.startswith('jar:'):
                suffix, _, extra = v[4:].partition('=')
                vflags = (vflags + ' ' + extra.replace(';', ' ')).strip()
                suffix = '-' + suffix
            label = '%s-%s-%s%s%s' % (name, sc, 'vanilla' if v == 'vanilla' else os.path.basename(a.jar).split('+')[0], suffix, '-r%d' % rnd if a.rounds > 1 else '')
            var = '--vanilla' if v == 'vanilla' else '--jar "%s"' % jar
            flags = (' "--flags=%s"' % vflags) if vflags and v != 'vanilla' else ''
            cmd = '%s -u "%s" %s --prod %s --server "%s" --java "%s" --heap %s --warm %d --secs %d --label %s%s' % (
                m.cfg['python'], j('tools', 'mobmass.py'), sc, var, srv, java, heap, a.warm, a.secs, label, flags)
            if not m.win:
                cmd = cmd.replace('"', "'")
            log('%s: %s start' % (name, label))
            t0 = time.time()
            try:
                rc, out = m.sh(cmd, timeout=3600)
            except subprocess.TimeoutExpired:
                log('%s: %s timed out' % (name, label))
                return
            tail = [l for l in out.splitlines() if l.strip()][-3:]
            log('%s: %s rc %d in %.0f s: %s' % (name, label, rc, time.time() - t0, ' | '.join(tail)[:400]))
            m.get(j('run', 'mobmass', label + '.log'), os.path.join(OUT, label + '.log'))
            res = os.path.join(OUT, 'box-tmp-%s.txt' % name)
            if m.get(j('run', 'mobmass', 'results.txt'), res):
                lines = [l for l in open(res, encoding='utf-8', errors='replace').read().splitlines() if label in l]
                if lines:
                    with LOCK, open(os.path.join(OUT, 'box-results.txt'), 'a', encoding='utf-8') as f:
                        f.write('%s %s\n' % (name, lines[-1]))
                    log('%s: %s' % (name, lines[-1][:300]))


threads = [threading.Thread(target=machine, args=(n,)) for n in a.machines.split(',')]
for t in threads:
    t.start()
for t in threads:
    t.join()
log('all done')
