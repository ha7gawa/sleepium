"""The exactness suite on a RELEASED jar across the test machines at once (tools/suite.py's scenes, production servers):
each machine runs tools/box_exact.py - one scene, off then on, on its own production server folder - one server per
machine at a time (the host more), the scenes shared out longest first, the results in one summary.

    python tools/box_suite.py --version 1.21.1 --jar F:/release-test/pub134/sleepium-1.3.4+mc1.21.1-neoforge.jar
    python tools/box_suite.py --version 1.20.1 --jar F:/release-test/pub134/sleepium-1.3.4+mc1.20.1-forge.jar
        [--machines host,x92,x103,n100,pi] [--host-slots 3] [--ticks 2400] [--tree DIR] [--ref SUMMARY] [scene ...]
    python tools/box_suite.py --version 1.21.1 --setup [--machines ...]    (the machines' server folders, from the host's)
    python tools/box_suite.py --version 1.21.1 --check [--machines ...]    (who is free / ready, nothing started)

The scenes come from the tree's src/scenes as a datapack (pack_format 48 for 1.21.1, 15 for 1.20.1; --tree: this tree
for 1.21.1, ../night-1.20.1 for 1.20.1). A machine is used only when no java runs on it (box_exact.py --alone checks
again before every scene: a box another test took meanwhile is dropped, its scene goes elsewhere); the N100 only on AC.
A scene that failed on a box (no hash) runs again elsewhere; a scene that differs runs off-vs-off on the same machine
(is the scene deterministic there at all). Summary lines (tools/suite.py's format) go to run/box-summary.txt; at the
end a table per scene with the off hash next to the dev suite's off hash (--ref, the tree's run/exactness-summary.txt):
the released jar's "off" against the dev server's plain run, and the hashes across machines.

Expected differences from the dev hashes (11-22, notes/90): the mob scenes (cramming, mobs, villagers, jobclash, zoo,
calmzoo) differ from the DEV off hash on every machine - the dev server runs a decompiled and recompiled Minecraft whose
float arithmetic is not always Mojang's (LookControl.getYRotD: `* 180.0 / 3.1415927` in two steps where Mojang's
bytecode multiplies once by 57.2957763671875), one-ulp turns that change the mobs' paths. Production runs Mojang's own
bytecode: there the off hash is vanilla's. For a production reference give --ref an earlier box summary
(run/box-summary.txt): production hashes are the same on every machine (host, Xeon, Pi aarch64 checked 11-22).
"""
import argparse
import os
import queue
import re
import shlex
import subprocess
import sys
import tarfile
import threading
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SCENES = ('restcarts items itemwake carts vehicles cramming physics hoppers furnaces smelters beactive bezoo pistons redstone '
          'crops fluids mobs villagers jobclash calmzoo zoo').split()
MOBSLEEP = '-Dlab.sleep.components=true -Dlab.sleep.mobRuntime=true -Dlab.opt.WriteBarrier=true'
# seconds per scene on the host dev suite (11-21), for longest-first; others ~325
COST = {'items': 450, 'itemwake': 364, 'restcarts': 359, 'carts': 333, 'beactive': 332, 'vehicles': 331}
PACK_FORMAT = {'1.21.1': 48, '1.20.1': 15}
DEV_CMD = re.compile(r'^\s*(?:sleep |execute .* run sleep )', re.M)
ADOPTIUM = r'C:\Users\ha7gawa\.gradle\jdks\eclipse_adoptium-%s-amd64-windows.2\bin\java.exe'

MACHINES = {
    'host': dict(addr=None, os='win', slots=3, heap='3G', port=25641, python=sys.executable,
                 base='F:/release-test/boxsuite',
                 servers={'1.21.1': ['F:/release-test/suite-srv121-%d' % i for i in (1, 2, 3)],
                          '1.20.1': ['F:/release-test/suite-srv1201-%d' % i for i in (1, 2, 3)]},
                 java={'1.21.1': ADOPTIUM % 21, '1.20.1': ADOPTIUM % 17}),
    'x92': dict(addr='root@x92', os='win', slots=1, heap='4G', port=25640, python=r'C:\Python314\python.exe',
                base=r'C:\mcbench\boxsuite', servers={'1.21.1': [r'C:\mcbench\suite121'], '1.20.1': [r'C:\mcbench\suite1201']},
                java={'1.21.1': r'C:\mcbench\jdk-21.0.12.1+1\bin\java.exe', '1.20.1': r'C:\mcbench\jdk-17.0.20.1+1\bin\java.exe'}),
    'x103': dict(addr='root@x103', os='win', slots=1, heap='4G', port=25640, python=r'C:\Python314\python.exe',
                 base=r'C:\mcbench\boxsuite', servers={'1.21.1': [r'C:\mcbench\suite121'], '1.20.1': [r'C:\mcbench\suite1201']},
                 java={'1.21.1': r'C:\mcbench\jdk-21.0.12.1+1\bin\java.exe', '1.20.1': r'C:\mcbench\jdk-17.0.20.1+1\bin\java.exe'}),
    'n100': dict(addr='admin@n100', os='win', slots=1, heap='4G', port=25640, python=r'C:\Python314\python.exe', battery=True,
                 slow=True, base=r'C:\mcbench\boxsuite', servers={'1.21.1': [r'C:\mcbench\suite121'], '1.20.1': [r'C:\mcbench\suite1201']},
                 java={'1.21.1': r'C:\mcbench\jdk-temurin\jdk-21.0.13+9\bin\java.exe', '1.20.1': r'C:\mcbench\jdk-17.0.20.1+1\bin\java.exe'}),
    'pi': dict(addr='test@pi', os='linux', slots=1, heap='3G', port=25640, python='python3', slow=True,
               base='/home/test/mcbench/boxsuite',
               servers={'1.21.1': ['/home/test/mcbench/suite121'], '1.20.1': ['/home/test/mcbench/suite1201']},
               java={'1.21.1': '/usr/bin/java', '1.20.1': '/home/test/mcbench/jdk-17.0.21+9/bin/java'}),
}
SSH = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=4',
       '-o', 'LogLevel=ERROR']

ap = argparse.ArgumentParser()
ap.add_argument('scenes', nargs='*')
ap.add_argument('--version', required=True, choices=('1.21.1', '1.20.1'))
ap.add_argument('--jar', help='the Sleepium jar to test (a release / candidate jar)')
ap.add_argument('--machines', default='host,x92,x103,n100,pi')
ap.add_argument('--host-slots', type=int, default=3, help='servers at once on the host (at most 3: three server folders)')
ap.add_argument('--ticks', type=int, default=2400)
ap.add_argument('--tree', help='the source tree whose src/scenes are packed (default: this tree for 1.21.1, ../night-1.20.1 for 1.20.1)')
ap.add_argument('--ref', help='a dev suite summary to compare the off hashes with (default: <tree>/run/exactness-summary.txt)')
ap.add_argument('--summary', default=os.path.join(ROOT, 'run', 'box-summary.txt'))
ap.add_argument('--setup', action='store_true', help='make the machines\' server folders (the host\'s copy sent over) and stop')
ap.add_argument('--check', action='store_true', help='only report which machines are free and ready')
ap.add_argument('--no-mobsleep', action='store_true', help='without the extra mob sleep runs of mobs / calmzoo')
ap.add_argument('--flags', default='', help='more JVM flags for every "on" run (a lab feature on top of the jar defaults)')
a = ap.parse_args()

V = a.version
TREE = os.path.abspath(a.tree or (ROOT if V == '1.21.1' else os.path.join(os.path.dirname(ROOT), 'night-1.20.1')))
WORK = os.path.join(ROOT, 'run', 'boxsuite')
os.makedirs(WORK, exist_ok=True)
plock = threading.Lock()


def log(text):
    with plock:
        print(time.strftime('%H:%M:%S ') + text, flush=True)


def summary(text):
    with plock:
        os.makedirs(os.path.dirname(a.summary), exist_ok=True)
        with open(a.summary, 'a', encoding='utf-8') as f:
            f.write(text + '\n')
        print(text, flush=True)


# ---------------------------------------------------------------------------------------------------------- machines
class Machine:
    def __init__(self, name, cfg):
        self.name, self.cfg = name, cfg
        self.win = cfg['os'] == 'win'
        self.remote = cfg['addr'] is not None
        self.ready = False
        self.why = ''
        self.busy_s = 0.0
        self.jobs_done = 0
        self.fails = 0

    def path(self, *parts):
        sep = '\\' if self.win else '/'
        return sep.join([self.cfg['base'].rstrip('\\/')] + list(parts))

    def sh(self, command, timeout=120):
        """(rc, output) of a command on the machine (cmd on Windows, sh on Linux)"""
        if not self.remote:
            r = subprocess.run(command, shell=True, capture_output=True, timeout=timeout)
        else:
            r = subprocess.run(SSH + [self.cfg['addr'], command], capture_output=True, timeout=timeout)
        out = (r.stdout + r.stderr).decode('cp866' if self.win and self.remote else 'utf-8', 'replace')
        return r.returncode, out

    def put(self, local, remote_path, timeout=900):
        if not self.remote:
            import shutil
            shutil.copy2(local, remote_path)
            return True
        dst = remote_path.replace('\\', '/')
        r = subprocess.run(['scp', '-q', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'LogLevel=ERROR', local,
                            '%s:%s' % (self.cfg['addr'], dst)], capture_output=True, timeout=timeout)
        if r.returncode:
            log('%s: scp %s failed: %s' % (self.name, os.path.basename(local), (r.stdout + r.stderr).decode('utf-8', 'replace').strip()[:200]))
        return r.returncode == 0

    def get(self, remote_path, local, timeout=300):
        if not self.remote:
            import shutil
            shutil.copy2(remote_path, local)
            return True
        r = subprocess.run(['scp', '-q', '-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR', '%s:%s' % (self.cfg['addr'], remote_path.replace('\\', '/')),
                            local], capture_output=True, timeout=timeout)
        return r.returncode == 0

    def javas(self):
        """the java processes running on the machine (text), '' when none; None when unreachable"""
        try:
            if self.win:
                rc, out = self.sh('tasklist /fi "imagename eq java.exe" /nh', 60)
                lines = [l for l in out.splitlines() if 'java.exe' in l.lower()]
            else:
                rc, out = self.sh('pgrep -a java || true', 60)
                lines = [l for l in out.splitlines() if l.strip()]
        except subprocess.TimeoutExpired:
            return None
        if rc not in (0, 1) and not lines:
            return None
        return ' | '.join(l.strip() for l in lines)

    def on_ac(self):
        rc, out = self.sh('powershell -NoProfile -Command "(Get-CimInstance Win32_Battery | Select-Object -First 1).BatteryStatus"', 60)
        return out.strip().splitlines()[-1:] == ['2'] or out.strip() == ''

    def server_ready(self, server):
        sub = 'libraries/net/neoforged/neoforge' if V == '1.21.1' else 'libraries/net/minecraftforge/forge'
        if not self.remote:
            return os.path.isdir(os.path.join(server, sub))
        if self.win:
            rc, out = self.sh('if exist "%s\\%s" (echo READY) else (echo MISSING)' % (server, sub.replace('/', '\\')), 60)
        else:
            rc, out = self.sh('test -d %s/%s && echo READY || echo MISSING' % (server, sub), 60)
        return 'READY' in out

    def preflight(self, upload):
        """free (no java), on AC, server folder there, files uploaded"""
        try:
            j = self.javas()
            if j is None:
                self.why = 'unreachable'
                return False
            if self.remote and j:
                self.why = 'busy (java running: %s)' % j[:160]
                return False
            if self.cfg.get('battery') and not self.on_ac():
                self.why = 'on battery'
                return False
            for s in self.cfg['servers'][V]:
                if not self.server_ready(s):
                    self.why = 'no %s server folder %s (--setup)' % (V, s)
                    return False
            if upload:
                if self.remote:
                    if self.win:
                        self.sh('if not exist "{0}\\out" mkdir "{0}\\out"'.format(self.cfg['base']))
                    else:
                        self.sh('mkdir -p %s/out' % self.cfg['base'])
                else:
                    os.makedirs(os.path.join(self.cfg['base'], 'out'), exist_ok=True)
                for local, name in ((os.path.join(HERE, 'box_exact.py'), 'box_exact.py'), (a.jar, 'sleepium-%s.jar' % V),
                                    (PACK, 'sleep-scenes-%s.zip' % V)):
                    if not self.put(local, self.path(name)):
                        self.why = 'upload failed'
                        return False
            self.ready = True
            return True
        except Exception as e:
            self.why = 'preflight error %r' % e
            return False


# ---------------------------------------------------------------------------------------------------------- the datapack
def pack(scenes):
    """the datapack: lag_clear, the scenes run and every function they call or schedule (sleep:...), nothing else (the
    other functions - client and mod scenes - use the dev-only `sleep ...` commands or mod blocks and would only add
    load errors on a production server)"""
    folder = 'function' if V == '1.21.1' else 'functions'
    src = os.path.join(TREE, 'src', 'scenes', 'resources')
    fdir = os.path.join(src, 'data', 'sleep', folder)
    if not os.path.isdir(fdir):
        raise SystemExit('no %s scenes in %s (data/sleep/%s)' % (V, src, folder))
    todo, files = ['lag_clear'] + ['lag_' + s for s in scenes], {}
    while todo:
        name = todo.pop()
        if name in files:
            continue
        path = os.path.join(fdir, name + '.mcfunction')
        if not os.path.exists(path):
            raise SystemExit('no function sleep:%s in %s' % (name, fdir))
        files[name] = open(path, encoding='utf-8').read()
        if DEV_CMD.search(files[name]):
            raise SystemExit('sleep:%s uses a dev-only `sleep ...` command: it cannot run on a production server' % name)
        todo += re.findall(r'function sleep:(\w+)', files[name])
    out = os.path.join(WORK, 'sleep-scenes-%s.zip' % V)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('pack.mcmeta', '{"pack": {"pack_format": %d, "description": "Sleepium exactness scenes (box_suite)"}}' % PACK_FORMAT[V])
        for name in sorted(files):
            z.writestr('data/sleep/%s/%s.mcfunction' % (folder, name), files[name])
    return out


def setup(m):
    """the host's first server folder (libraries + user_jvm_args.txt) as the machine's server folder"""
    tar = os.path.join(WORK, 'srv-%s.tar' % V)
    if not os.path.exists(tar):
        src = MACHINES['host']['servers'][V][0]
        with tarfile.open(tar, 'w') as t:
            for name in ('libraries', 'user_jvm_args.txt'):
                t.add(os.path.join(src, name), name)
    for s in m.cfg['servers'][V]:
        if m.server_ready(s):
            log('%s: %s there already' % (m.name, s))
            continue
        if m.win:
            m.sh('if not exist "{0}" mkdir "{0}"'.format(m.cfg['base']))
        else:
            m.sh('mkdir -p %s' % m.cfg['base'])
        t0 = time.time()
        if not m.put(tar, m.path('srv.tar'), 1800):
            log('%s: upload failed' % m.name)
            continue
        if m.win:
            rc, out = m.sh('mkdir "{0}" 2>nul & tar -xf "{1}" -C "{0}" && del "{1}" && echo DONE'.format(s, m.path('srv.tar')), 900)
        else:
            rc, out = m.sh('mkdir -p {0} && tar -xf {1} -C {0} && rm {1} && echo DONE'.format(s, m.path('srv.tar')), 900)
        log('%s: %s %s (%.0f s)' % (m.name, s, 'made' if 'DONE' in out else 'FAILED: ' + out.strip()[:200], time.time() - t0))


# ---------------------------------------------------------------------------------------------------------- one job
RESULT = re.compile(r'^(off|on)\s+region hash|^entities compared|^(EXACT|DIFFERENT)$')


def with_flags(extra):
    """--flags added to the job's "on" run (merged with a job's own --flags=, e.g. the mob sleep runs'; none for offoff)"""
    if not a.flags or '--mode' in extra:
        return extra
    own = [x for x in extra if x.startswith('--flags=')]
    rest = [x for x in extra if not x.startswith('--flags=')]
    return rest + ['--flags=' + ' '.join([x[len('--flags='):] for x in own] + [a.flags]).strip()]


def run_job(m, slot, scene, label, extra, tag):
    """box_exact.py on machine m: (rc, the result lines, all lines)"""
    server = m.cfg['servers'][V][slot]
    args = [scene, '--server', server, '--jar', m.path('sleepium-%s.jar' % V), '--datapack', m.path('sleep-scenes-%s.zip' % V),
            '--java', m.cfg['java'][V], '--port', str(m.cfg['port'] + slot), '--ticks', str(a.ticks), '--heap', m.cfg['heap'],
            '--out', m.path('out'), '--tag', tag] + with_flags(extra)
    logf = m.path('out', tag + '.log')
    if not m.remote:
        lines = []
        p = subprocess.Popen([m.cfg['python'], '-u', os.path.join(HERE, 'box_exact.py')] + args + ['--log', logf.replace('/', os.sep)],
                             stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        p.wait()
        text = open(logf, encoding='utf-8', errors='replace').read() if os.path.exists(logf) else ''
    else:
        args += ['--alone']
        q = subprocess.list2cmdline if m.win else lambda xs: ' '.join(shlex.quote(x) for x in xs)
        rc, out = m.sh(q([m.cfg['python'], '-u', m.path('box_exact.py')] + args + ['--detach', logf]), 120)
        if 'detached pid' not in out:
            return 2, ['FAILED: could not start: ' + out.strip()[:200]], []
        text, misses, t0 = '', 0, time.time()
        read = ('type "%s"' % logf) if m.win else ('cat %s' % logf)
        while 'BOXEXACT END' not in text:
            time.sleep(15)
            try:
                rc, out = m.sh(read, 60)
                if rc == 0:
                    text, misses = out, 0
                else:
                    misses += 1
            except subprocess.TimeoutExpired:
                misses += 1
            if misses > 40 or time.time() - t0 > 3600:
                return 2, ['FAILED: lost the run (%s)' % ('no contact' if misses > 40 else 'over an hour')], text.splitlines()
    lines = [l.rstrip('\r') for l in text.splitlines()]
    end = [l for l in lines if l.startswith('BOXEXACT END rc=')]
    rc = int(end[-1].split('=')[1]) if end else 2
    res = [l for l in lines if RESULT.search(l)]
    if rc == 2 or not res:
        res = [l for l in lines if l.startswith('FAILED') or l.startswith('REFUSED')] or ['FAILED: no result']
    return rc, res, lines


# ---------------------------------------------------------------------------------------------------------- the suite
def jobs_for(scenes):
    out = []
    for s in scenes:
        mon = ['--monsters'] if s == 'zoo' else []
        out.append((COST.get(s, 325), s, 'defaults', mon))
        if s in ('mobs', 'calmzoo') and not a.no_mobsleep:
            out.append((COST.get(s, 325), s, 'defaults+mob sleep', ['--flags=' + MOBSLEEP]))
    out.sort(key=lambda j: -j[0])
    return out


class Board:
    def __init__(self, jobs):
        self.lock = threading.Lock()
        self.jobs = list(jobs)   # longest first
        self.running = 0
        self.results = {}   # (scene, label) -> dict
        self.tried = {}

    def take(self, m):
        """the next job for machine m (slow machines take the shortest), None when all are done"""
        while True:
            with self.lock:
                for i in (range(len(self.jobs) - 1, -1, -1) if m.cfg.get('slow') else range(len(self.jobs))):
                    j = self.jobs[i]
                    if m.name in self.tried.get((j[1], j[2]), ()) and any(x.ready for x in MACH.values() if x is not m):
                        continue   # failed here before: another machine
                    self.jobs.pop(i)
                    self.running += 1
                    return j
                if not self.jobs:
                    return None
            time.sleep(5)   # only jobs that failed here, waiting for another machine

    def give_back(self, m, j):
        with self.lock:
            self.tried.setdefault((j[1], j[2]), set()).add(m.name)
            self.jobs.insert(0, j)
            self.running -= 1

    def done(self, j, res):
        with self.lock:
            self.results[(j[1], j[2])] = res
            self.running -= 1


def worker(m, slot, board):
    n = 0
    while m.ready:
        j = board.take(m)
        if j is None:   # nothing queued: done when nothing runs either (a failed run elsewhere comes back to the queue)
            with board.lock:
                if not board.running:
                    return
            time.sleep(10)
            continue
        _, scene, label, extra = j
        n += 1
        tag = '%s-%s-%s' % (scene, 'ms' if 'mob' in label else 'd', m.name)
        t0 = time.time()
        log('%s/%d: %s %s started' % (m.name, slot + 1, scene, label))
        rc, res, lines = run_job(m, slot, scene, label, extra, tag)
        took = time.time() - t0
        m.busy_s += took
        where = '%s%s' % (m.name, '/%d' % (slot + 1) if m.cfg['slots'] > 1 else '')
        if rc == 3:   # another test took the box: leave it
            log('%s: %s - leaving this machine, %s %s goes elsewhere' % (m.name, ' '.join(res)[:200], scene, label))
            m.ready, m.why = False, 'taken by another test during the suite'
            board.give_back(m, j)
            return
        if rc == 2:
            m.fails += 1
            summary('%s %s | %s  [%.0f s, %s] - will run again elsewhere' % (scene, label, ' '.join(res)[:400], took, where))
            board.give_back(m, j)
            if m.fails >= 2:
                m.ready, m.why = False, 'two failed runs'
                return
            continue
        m.jobs_done += 1
        tail = ' '.join(res[-4:])
        summary('%s %s | %s  [%.0f s, %s]' % (scene, label, tail, took, where))
        hashes = re.findall(r'(off|on)\s+region hash (\w+)', tail)
        r = dict(rc=rc, tail=tail, machine=where, secs=took, off=dict(hashes).get('off'), on=dict(hashes).get('on'), offoff=None)
        if rc == 1:   # the scene's own nondeterminism on this machine?
            for f in ('-off-rows.txt', '-on-rows.txt'):
                m.get(m.path('out', tag + f), os.path.join(WORK, tag + f))
            rc2, res2, _ = run_job(m, slot, scene, label, ['--mode', 'offoff'] + [x for x in extra if x == '--monsters'], tag + '-offoff')
            r['offoff'] = ' '.join(res2[-4:])
            summary('%s off-vs-off | %s  [%s]' % (scene, r['offoff'], where))
        board.done(j, r)


def ref_hashes():
    """(scene, label) -> off hash of the last suite in the dev summary"""
    path = a.ref or os.path.join(TREE, 'run', 'exactness-summary.txt')
    if not a.ref and not os.path.exists(path):   # (a lab tree: the night tree's dev suite)
        path = os.path.join(os.path.dirname(ROOT), 'night-' + V, 'run', 'exactness-summary.txt')
    out = {}
    if not os.path.exists(path):
        return out, path
    # the last suite block: a dev suite ("suite: 21 scenes") or an earlier box suite ("box suite 1.21.1: ...")
    blocks = [b for b in open(path, encoding='utf-8').read().split('\n=== ') if re.search(r'suite( [\d.]+)?:', b.split('\n', 1)[0])]
    text = blocks[-1] if blocks else ''
    for l in text.splitlines():
        m = re.match(r'(\w+) (defaults(?:\+mob sleep)?) \| off\s+region hash (\w+)', l)
        if m:
            out[(m.group(1), m.group(2))] = m.group(3)
    return out, path


MACH = {}


def main():
    global PACK
    names = [n.strip() for n in a.machines.split(',') if n.strip()]
    for n in names:
        if n not in MACHINES:
            raise SystemExit('unknown machine %s (%s)' % (n, ', '.join(MACHINES)))
        MACH[n] = Machine(n, MACHINES[n])
    if 'host' in MACH:
        MACH['host'].cfg = dict(MACH['host'].cfg, slots=max(1, min(a.host_slots, 3)))
    if a.setup:
        for m in MACH.values():
            if m.remote:
                j = m.javas()
                if j:
                    log('%s: busy (%s) - not touched' % (m.name, j[:120]))
                    continue
                setup(m)
        return
    if not a.check and not a.jar:
        raise SystemExit('--jar is needed')
    scenes = a.scenes or SCENES
    PACK = pack(scenes)
    log('datapack %s (%d bytes, pack_format %d) from %s' % (PACK, os.path.getsize(PACK), PACK_FORMAT[V], TREE))
    threads = [threading.Thread(target=m.preflight, args=(not a.check,)) for m in MACH.values()]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    for m in MACH.values():
        log('%-5s %s' % (m.name, 'ready (%d slot%s)' % (m.cfg['slots'], 's' if m.cfg['slots'] > 1 else '') if m.ready else 'SKIPPED: ' + m.why))
    if a.check:
        return
    ready = [m for m in MACH.values() if m.ready]
    if not ready:
        raise SystemExit('no machine ready')
    board = Board(jobs_for(scenes))
    t0 = time.time()
    summary('=== %s  box suite %s: %d scenes (%d runs), machines %s, jar %s, %d ticks' % (
        time.strftime('%F %T'), V, len(scenes), len(board.jobs), ' '.join('%s(%d)' % (m.name, m.cfg['slots']) for m in ready),
        os.path.basename(a.jar), a.ticks))
    threads = []

    def start(m):
        for slot in range(m.cfg['slots']):
            t = threading.Thread(target=worker, args=(m, slot, board))
            t.start()
            threads.append(t)
            time.sleep(3)

    for m in ready:
        start(m)
    # late joiners: a machine that was busy (another test) or on battery joins when it is free while scenes still wait
    late = [m for m in MACH.values() if not m.ready and m.why.startswith(('busy', 'on battery'))]
    while any(t.is_alive() for t in threads):
        with board.lock:
            waiting = len(board.jobs)
        for m in list(late):
            if waiting and m.preflight(True):
                summary('(%s: free now - joins the suite at %s)' % (m.name, time.strftime('%T')))
                late.remove(m)
                ready.append(m)
                start(m)
        deadline = time.time() + 60
        while time.time() < deadline and any(t.is_alive() for t in threads):
            time.sleep(2)
    wall = time.time() - t0

    # leftovers: no java of ours anywhere
    for m in ready:
        if m.remote:
            j = m.javas()
            if j:
                log('WARNING %s: java still running after the suite: %s' % (m.name, j[:200]))
    if 'host' in MACH:
        ps = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*boxsuite.dir=*' } | "
              "ForEach-Object { $_.ProcessId }")
        r = subprocess.run(['powershell', '-NoProfile', '-Command', ps], capture_output=True, text=True)
        if r.stdout.strip():
            log('WARNING host: box suite java still running: %s' % r.stdout.split())

    ref, refpath = ref_hashes()
    summary('--- per scene (off hash vs the dev suite\'s off hash from %s)' % refpath)
    exact = 0
    for _, s, label, _ in jobs_for(scenes):
        r = board.results.get((s, label))
        if not r:
            summary('%-10s %-18s NO RESULT' % (s, label))
            continue
        exact += r['rc'] == 0
        d = ref.get((s, label))
        summary('%-10s %-18s %-9s off %s on %s  dev off %s %s  [%s, %.0f s]' % (
            s, label, 'EXACT' if r['rc'] == 0 else 'DIFFERENT', r['off'], r['on'], d or '-',
            '(=)' if d and d == r['off'] else ('(DIFFERS from dev)' if d else ''), r['machine'], r['secs']))
    for m in MACH.values():
        if m.ready or m.jobs_done:
            summary('machine %-5s %2d runs, busy %4.0f s%s' % (m.name, m.jobs_done, m.busy_s,
                                                              ', %.0f s per scene' % (m.busy_s / m.jobs_done) if m.jobs_done else ''))
        else:
            summary('machine %-5s not used: %s' % (m.name, m.why))
    summary('=== box suite done %s: %d/%d EXACT, wall %.1f min' % (time.strftime('%F %T'), exact, len(jobs_for(scenes)), wall / 60))


PACK = None
if __name__ == "__main__":
    main()
