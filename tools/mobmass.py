"""Mob mass runs (notes/98): one scene of tools/gen_mobmass.py on one server, its tick time after a warm-up.

    python tools/mobmass.py pigs30k --dev [--off] [--flags "..."]           a dev server of this tree (gradle runServer)
    python tools/mobmass.py pigs30k --prod --jar X.jar [--flags "..."]       a production server folder with that jar
    python tools/mobmass.py pigs30k --prod --vanilla                         ... with no mod at all (NeoForge alone)
        [--warm 45] [--secs 60] [--perf] [--jfr 20] [--heap 6G] [--label NAME] [--port 25640]

One server per call. Steps: start; every `forceload add` line of the scene issued and the chunks waited for (`execute if
loaded` on each forceload tile's corners); `function sleep:lag_<scene>`; the mob count (`execute if entity`); --warm
seconds; the window of --secs: `debug start` .. `debug stop` (TPS over the window) with `tick query` every 5 s (each the
mean and P50/P95/P99 of the last 100 ticks: vanilla's own numbers, the same in every jar), and on the dev server / a jar
with BotLoad (-Dsleep.bots=true) `bots measure` over the same window (every tick's time: mean/p50/p95/max, CPU per
thread group); the count again; the heap (jcmd GC.heap_info, then after a full GC: the live set); --perf: vanilla's
/perf profile of 10 s after that (phases as tools/tick_profile.py prints them); --jfr N: a JFR recording (1 ms
execution samples) of N seconds starting with the window.
A line per run appended to run/mobmass/results.txt; the console in run/mobmass/<label>.log.

Production: the folder's mods, server.properties, eula.txt and config/sleepium* go to <server>/mobmass-saved for the run
and back after it; the world is its own (level-name mobmass-world, deleted after); only this run's java is killed, by
its PID; refuses when the folder already runs a server or another tool's saved state is there.
"""
import argparse
import glob
import io
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SCENES = os.path.join(ROOT, 'src', 'scenes', 'resources', 'data', 'sleep', 'function')
OUTDIR = os.path.join(ROOT, 'run', 'mobmass')
ADOPTIUM21 = r'C:\Users\ha7gawa\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe'

ap = argparse.ArgumentParser()
ap.add_argument('scene')
ap.add_argument('--dev', action='store_true')
ap.add_argument('--prod', action='store_true')
ap.add_argument('--off', action='store_true', help='dev: without the engine defaults (-Dlab.opt.defaults=true left out)')
ap.add_argument('--jar', default='')
ap.add_argument('--vanilla', action='store_true', help='prod: no mod jar at all')
ap.add_argument('--server', default='F:/release-test/server-mobmass')
ap.add_argument('--java', default=ADOPTIUM21)
ap.add_argument('--flags', default='')
ap.add_argument('--warm', type=int, default=45)
ap.add_argument('--secs', type=int, default=60)
ap.add_argument('--perf', action='store_true')
ap.add_argument('--jfr', type=int, default=0)
ap.add_argument('--jfrdelay', type=int, default=110, help='seconds after the JVM start (start + chunks + warm-up)')
ap.add_argument('--heap', default='4G')
ap.add_argument('--label', default='')
ap.add_argument('--port', type=int, default=25640)
ap.add_argument('--monsters', action='store_true', help='spawn-monsters=true (the farm scene sets it itself)')
a = ap.parse_args()
if a.dev == a.prod:
    raise SystemExit('--dev or --prod')
os.makedirs(OUTDIR, exist_ok=True)
LABEL = a.label or '%s-%s' % (a.scene, 'dev-off' if a.dev and a.off else 'dev-on' if a.dev else 'vanilla' if a.vanilla else os.path.basename(a.jar).split('+')[0])
SCENE_FILE = os.path.join(SCENES, 'lag_%s.mcfunction' % a.scene)
lines = open(SCENE_FILE, encoding='utf-8').read().splitlines()
FORCE = [tuple(int(v) for v in l.split()[2:6]) for l in lines if l.startswith('forceload add')]
MONSTERS = a.monsters or a.scene == 'farm'
WIN = os.name == 'nt'
t_start = time.time()


def say(s):
    print(s, flush=True)


# ---------------------------------------------------------------------------------------------------------- servers
class Console:
    """a server's console: lines read on a thread, commands written"""
    def start(self, args, cwd, log):
        self.lines = []
        self.lock = threading.Lock()
        self.logf = open(log, 'w', encoding='utf-8')
        flags = 0x08000000 if WIN else 0
        self.p = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding='utf-8', errors='replace', bufsize=1, creationflags=flags)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.p.stdout:
            with self.lock:
                self.lines.append(line.rstrip('\n'))
            self.logf.write(line)
            self.logf.flush()

    def cmd(self, c):
        self.p.stdin.write(c + '\n')
        self.p.stdin.flush()

    def mark(self):
        with self.lock:
            return len(self.lines)

    def wait_for(self, pattern, timeout=900, start=0):
        rx = re.compile(pattern)
        t0 = time.time()
        while time.time() - t0 < timeout:
            with self.lock:
                for line in self.lines[start:]:
                    m = rx.search(line)
                    if m:
                        return m
            if self.p.poll() is not None:
                raise RuntimeError('the server stopped before "%s"' % pattern)
            time.sleep(0.2)
        raise RuntimeError('timed out waiting for "%s"' % pattern)

    def ask(self, c, pattern, timeout=120):
        start = self.mark()
        self.cmd(c)
        return self.wait_for(pattern, timeout, start)


def ps(cmd):
    r = subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', cmd], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=120)
    return r.stdout


def java_pids(substr, also=''):
    if not WIN:
        r = subprocess.run(['pgrep', '-af', 'java'], capture_output=True, text=True, errors='replace')
        return [int(l.split()[0]) for l in r.stdout.splitlines() if l.strip() and substr in l and also in l]
    out = ps("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ForEach-Object { '' + $_.ProcessId + ' ' + $_.CommandLine + ' ' }")
    return [int(l.split()[0]) for l in out.splitlines() if l.strip() and substr in l and also in l]


class Dev(Console):
    """a dev server of this tree: gradle runServer in run/ (this worktree's own folder)"""
    def __init__(self, jvm):
        self.tag = 'mobmass-%d' % os.getpid()
        self.jvm = jvm + ['-Dsleep.test.run=' + self.tag]
        self.cmdroot = 'sleep'

    def __enter__(self):
        run = os.path.join(ROOT, 'run')
        if os.path.islink(run):
            raise SystemExit('run/ is a link (another tree\'s folder): make it a folder of this tree')
        os.makedirs(run, exist_ok=True)
        shutil.rmtree(os.path.join(run, 'world'), ignore_errors=True)
        with open(os.path.join(run, 'eula.txt'), 'w') as f:
            f.write('eula=true\n')
        with open(os.path.join(run, 'server.properties'), 'w') as f:
            f.writelines('%s=%s\n' % kv for kv in props().items())
        gradlew = os.path.join(ROOT, 'gradlew.bat' if WIN else 'gradlew')
        self.start([gradlew, 'runServer', '--console=plain', '-q', '-PrunJvmArgs=' + ' '.join(self.jvm)], ROOT,
                   os.path.join(OUTDIR, LABEL + '.log'))
        return self

    def pid(self):
        p = java_pids('fml.modFolders', os.path.basename(ROOT))
        return p[0] if p else None

    def __exit__(self, *x):
        try:
            if self.p.poll() is None:
                self.cmd('stop')
                self.p.wait(240)
        except Exception:
            pass
        for pid in java_pids('fml.modFolders', os.path.basename(ROOT)):   # our own JVM only (this tree's mod folders)
            say('killing our java pid %d' % pid)
            subprocess.run(['taskkill', '/F', '/PID', str(pid)] if WIN else ['kill', '-9', str(pid)], capture_output=True)
        if self.p.poll() is None:
            self.p.kill()
        self.logf.close()


SERVER = os.path.abspath(a.server)
SAVED = os.path.join(SERVER, 'mobmass-saved')
LEVEL = 'mobmass-world'


class Prod(Console):
    """a production server folder (NeoForge installed), the jar alone in mods (or no mod), a world of its own"""
    def __init__(self, jvm):
        self.jvm = ['-Xms' + a.heap, '-Xmx' + a.heap, '-Dmobmass.dir=' + SERVER.replace('\\', '/')] + jvm
        self.cmdroot = 'sleepium'

    def __enter__(self):
        if java_pids('mobmass.dir=' + SERVER.replace('\\', '/')) or java_pids('boxsuite.dir=' + SERVER.replace('\\', '/')):
            raise SystemExit('a server of %s runs already' % SERVER)
        for other in ('boxsuite-saved', 'boxsuite.lock'):
            if os.path.exists(os.path.join(SERVER, other)):
                raise SystemExit('%s has %s: another tool uses it' % (SERVER, other))
        if os.path.isdir(SAVED):
            say('a previous run left %s: putting it back first' % SAVED)
            put_back()
        take()
        try:
            shutil.rmtree(os.path.join(SERVER, LEVEL), ignore_errors=True)
            os.makedirs(os.path.join(SERVER, LEVEL, 'datapacks'))
            datapack(os.path.join(SERVER, LEVEL, 'datapacks', 'sleep-scenes.zip'))
            mods = os.path.join(SERVER, 'mods')
            os.makedirs(mods, exist_ok=True)
            if a.jar:
                shutil.copy2(a.jar, os.path.join(mods, os.path.basename(a.jar)))
            p = props()
            p.update({'level-name': LEVEL, 'initial-enabled-packs': 'vanilla,file/sleep-scenes.zip'})
            with open(os.path.join(SERVER, 'server.properties'), 'w', encoding='utf-8') as f:
                f.writelines('%s=%s\n' % kv for kv in p.items())
            with open(os.path.join(SERVER, 'eula.txt'), 'w') as f:
                f.write('eula=true\n')
            launch = sorted(glob.glob(os.path.join(SERVER, 'libraries/net/neoforged/neoforge/*/%s_args.txt' % ('win' if WIN else 'unix'))))[-1]
            launch = os.path.relpath(launch, SERVER).replace('\\', '/')
            self.start([a.java] + self.jvm + ['@' + launch, 'nogui'], SERVER, os.path.join(OUTDIR, LABEL + '.log'))
            say('java pid %d' % self.p.pid)
        except Exception:
            put_back()
            raise
        return self

    def pid(self):
        return self.p.pid

    def __exit__(self, *x):
        try:
            if self.p.poll() is None:
                self.cmd('stop')
                self.p.wait(240)
        except Exception:
            pass
        if self.p.poll() is None:
            say('server did not stop: killing java pid %d' % self.p.pid)
            self.p.kill()
            try:
                self.p.wait(60)
            except Exception:
                pass
        self.logf.close()
        put_back()


def take():
    os.makedirs(os.path.join(SAVED, 'mods'))
    os.makedirs(os.path.join(SAVED, 'config'))
    mods = os.path.join(SERVER, 'mods')
    if os.path.isdir(mods):
        for f in os.listdir(mods):
            shutil.move(os.path.join(mods, f), os.path.join(SAVED, 'mods', f))
    for p in glob.glob(os.path.join(SERVER, 'config', 'sleepium*')):
        shutil.move(p, os.path.join(SAVED, 'config', os.path.basename(p)))
    for f in ('server.properties', 'eula.txt'):
        src = os.path.join(SERVER, f)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(SAVED, f))
        else:
            open(os.path.join(SAVED, f + '.absent'), 'w').close()


def put_back():
    if not os.path.isdir(SAVED):
        return
    shutil.rmtree(os.path.join(SERVER, LEVEL), ignore_errors=True)
    mods = os.path.join(SERVER, 'mods')
    if os.path.isdir(mods):
        for f in os.listdir(mods):
            p = os.path.join(mods, f)
            shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    os.makedirs(mods, exist_ok=True)
    for f in os.listdir(os.path.join(SAVED, 'mods')):
        shutil.move(os.path.join(SAVED, 'mods', f), os.path.join(mods, f))
    cfg = os.path.join(SERVER, 'config')
    for p in glob.glob(os.path.join(cfg, 'sleepium*')):
        shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    os.makedirs(cfg, exist_ok=True)
    for f in os.listdir(os.path.join(SAVED, 'config')):
        shutil.move(os.path.join(SAVED, 'config', f), os.path.join(cfg, f))
    for f in ('server.properties', 'eula.txt'):
        dst = os.path.join(SERVER, f)
        if os.path.exists(os.path.join(SAVED, f)):
            shutil.move(os.path.join(SAVED, f), dst)
        elif os.path.exists(os.path.join(SAVED, f + '.absent')) and os.path.exists(dst):
            os.remove(dst)
    shutil.rmtree(SAVED, ignore_errors=True)


def datapack(path):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('pack.mcmeta', '{"pack": {"pack_format": 48, "description": "Sleepium mob mass scenes"}}')
        z.write(SCENE_FILE, 'data/sleep/function/lag_%s.mcfunction' % a.scene)


def props():
    return {'level-type': 'minecraft\\:flat', 'level-seed': '20260924', 'online-mode': 'false', 'spawn-protection': '0',
            'view-distance': '8', 'simulation-distance': '8', 'sync-chunk-writes': 'false', 'max-tick-time': '-1',
            'pause-when-empty-seconds': '-1', 'spawn-monsters': 'true' if MONSTERS else 'false', 'server-port': str(a.port),
            'difficulty': 'easy', 'generate-structures': 'false'}


# ---------------------------------------------------------------------------------------------------------- the run
NUM = r'(\d+(?:[.,]\d+)?)'


def num(s):
    return float(s.replace(',', '.'))


def count(s, sel):
    m = s.ask('execute if entity %s' % sel, r'Test passed, count: (\d+)|Test failed', 120)
    return int(m.group(1)) if m.group(1) else 0


def heap(pid):
    jcmd = os.path.join(os.path.dirname(a.java), 'jcmd.exe' if WIN else 'jcmd')

    def used():
        r = subprocess.run([jcmd, str(pid), 'GC.heap_info'], capture_output=True, text=True, errors='replace', timeout=120)
        m = re.search(r'total (?:reserved )?\d+K?, (?:committed \d+K, )?used (\d+)K', r.stdout)
        m = m or re.search(r'used (\d+)K', r.stdout)
        return int(m.group(1)) / 1024 if m else -1
    before = used()
    subprocess.run([jcmd, str(pid), 'GC.run'], capture_output=True, timeout=180)
    return before, used()


def main():
    jvm = ['-Xms' + a.heap, '-Xmx' + a.heap] if a.dev else []
    if a.dev:
        jvm += [] if a.off else ['-Dlab.opt.defaults=true']
    if a.jfr:
        rec = os.path.join(OUTDIR, LABEL + '.jfr').replace('\\', '/')
        jvm += ['-XX:StartFlightRecording=name=mobmass,settings=profile,jdk.ExecutionSample#period=1ms,jdk.JavaMonitorEnter#threshold=50us,jdk.ThreadPark#threshold=50us,delay=%ds,duration=%ds,filename=%s' % (a.jfrdelay, a.jfr, rec),
                '-XX:FlightRecorderOptions=stackdepth=128']
    jvm += a.flags.split()
    S = Dev(jvm) if a.dev else Prod(jvm)
    res = {}
    with S as s:
        s.wait_for(r'Done \(', 900)
        res['up'] = time.time() - t_start
        root = s.cmdroot
        has_mod = a.dev or bool(a.jar)
        bots = a.dev or '-Dsleep.bots=true' in a.flags
        for f in FORCE:
            s.cmd('forceload add %d %d %d %d' % f)
        t0 = time.time()
        pts = []
        for x1, z1, x2, z2 in FORCE:
            pts += [(x1, z1), (x2, z2), (x1, z2), (x2, z1), ((x1 + x2) // 2, (z1 + z2) // 2)]
        left = list(pts)
        while left:
            left = [p for p in left if not s.ask('execute if loaded %d -60 %d' % p, r'Test (passed|failed)', 60).group(1) == 'passed']
            if left:
                if time.time() - t0 > 600:
                    raise RuntimeError('chunks not loaded after 600 s: %s' % left[:5])
                time.sleep(2)
        res['load'] = time.time() - t0
        if a.jfr:
            pass
        st = s.mark()
        t1 = time.time()
        s.cmd('function sleep:lag_%s' % a.scene)
        sel = '@e[type=minecraft:pig]' if a.scene.startswith('pig') else '@e[type=!minecraft:player]'
        res['n0'] = count(s, sel)
        res['build'] = time.time() - t1
        bad = [l for l in s.lines[st:] if re.search(r'Unknown function|Unknown or incomplete|Incorrect argument|not loaded', l)]
        if bad:
            say('scene errors: %s' % bad[:3])
        say('[%s] up %.0f s, chunks %.0f s, scene %d mobs in %.1f s' % (LABEL, res['up'], res['load'], res['n0'], res['build']))
        time.sleep(a.warm)
        # the window
        st = s.mark()
        s.cmd('debug start')
        if bots:
            s.cmd('%s bots measure %d %s' % (root, a.secs, LABEL))
        q = []
        tw = time.time()
        while time.time() - tw < a.secs:
            time.sleep(5)
            m0 = s.mark()
            s.cmd('tick query')
            try:
                m1 = s.wait_for(r'Average time per tick: ' + NUM, 30, m0)
                m2 = s.wait_for(r'P50: ' + NUM + r' ?ms,? P95: ' + NUM + r' ?ms,? P99: ' + NUM, 30, m0)
                q.append((num(m1.group(1)), num(m2.group(1)), num(m2.group(2)), num(m2.group(3))))
            except RuntimeError as e:
                say('tick query: %s' % e)
        try:
            m = s.ask('debug stop', r'Stopped tick profiling after ' + NUM + r' seconds? and (\d+) ticks? \(' + NUM, 60)
            res['tps'] = num(m.group(3))
        except RuntimeError as e:
            say('debug stop: %s' % e)
            res['tps'] = -1
        res['q'] = q
        if bots:
            mb = s.wait_for(r"bots measure '[^']*': (.*)", 120, st)
            res['bots'] = mb.group(1)
        res['n1'] = count(s, sel)
        if has_mod:
            s.cmd('%s sleepents' % root)   # the entity sleep report (asleep / awake per type) into the console log
            time.sleep(2)
        pid = s.pid()
        res['heap'] = heap(pid) if pid else (-1, -1)
        if a.perf:
            before = set(glob.glob(os.path.join(prof_dir(), '*.zip')))
            m0 = s.mark()
            s.cmd('perf start')
            s.wait_for(r'Created debug report', 90, m0)
            time.sleep(2)
            after = sorted(set(glob.glob(os.path.join(prof_dir(), '*.zip'))) - before)
            res['perf'] = phases(after[-1]) if after else 'no profile'
    report(res)


def prof_dir():
    return os.path.join(ROOT, 'run', 'debug', 'profiling') if a.dev else os.path.join(SERVER, 'debug', 'profiling')


LINE = re.compile(r'^\[(\d+)\]\s*([| ]*)(\S.*?)\((\d+)/(\d+)\)\s*-\s*([\d.]+)%/([\d.]+)%')


def phases(zpath):
    z = zipfile.ZipFile(zpath)
    names = [x for x in z.namelist() if x.endswith('profiling.txt')]
    if not names:
        return 'no profiling.txt'
    txt = z.read(names[0]).decode('utf-8', 'replace')
    open(os.path.join(OUTDIR, LABEL + '-profiling.txt'), 'w', encoding='utf-8').write(txt)
    stack, rows = [], []
    for line in txt.splitlines():
        m = LINE.match(line.rstrip())
        if not m:
            continue
        depth = int(m.group(1))
        stack = stack[:depth] + [m.group(3).strip()]
        rows.append(('/'.join(stack), depth, float(m.group(7))))
    span = re.search(r'Time span: (\d+) ms', txt)
    ticks = re.search(r'Tick span: (\d+) ticks', txt)
    if not (span and ticks):
        return 'not parsed'
    per = int(span.group(1)) / int(ticks.group(1))
    ow = 'tick/levels/ServerLevel[world] minecraft:overworld'
    out = ['perf: %d ticks in %s ms (%.1f ms/tick with the profiler)' % (int(ticks.group(1)), span.group(1), per)]
    shown = sorted([r for r in rows if r[1] <= 6 and r[0].startswith(ow)], key=lambda r: -r[2])[:22]
    for p, d, pct in shown:
        out.append('  %7.2f ms/tick %5.1f%%  %s' % (pct / 100 * per, pct, p[len(ow):] or '(overworld)'))
    return '\n'.join(out)


def report(res):
    q = res['q']
    mean = sum(x[0] for x in q) / len(q) if q else -1
    p50 = sorted(x[1] for x in q)[len(q) // 2] if q else -1
    p95 = sum(x[2] for x in q) / len(q) if q else -1
    p99 = max(x[3] for x in q) if q else -1
    line = ('%s | %s | mobs %d -> %d | TPS %.2f | mspt mean %.2f p50 %.2f p95 %.2f p99max %.2f (tick query x%d) | heap used %.0f MB, live %.0f MB'
            ' | warm %d s, window %d s, flags %s' % (time.strftime('%m-%d %H:%M'), LABEL, res['n0'], res['n1'], res['tps'], mean, p50, p95, p99,
                                                    len(q), res['heap'][0], res['heap'][1], a.warm, a.secs, a.flags or '-'))
    if 'bots' in res:
        line += '\n    ' + res['bots']
    if 'perf' in res:
        line += '\n' + res['perf']
    say(line)
    with open(os.path.join(OUTDIR, 'results.txt'), 'a', encoding='utf-8') as f:
        f.write(line + '\n')


if __name__ == '__main__':
    main()
