"""One exactness scene on a PRODUCTION server folder (a released jar, no Gradle): the box side of tools/box_suite.py.
Plain Python 3, no dependencies, Windows or Linux.

    python box_exact.py <scene> --server DIR --jar sleepium.jar --datapack sleep-scenes.zip [--java PATH]
                        [--mode both|off|on|offoff] [--port 25640] [--ticks 2400] [--flags "-D... (on run)"]
                        [--monsters] [--heap 4G] [--out DIR] [--alone] [--log FILE] [--detach LOGFILE]

The same steps as tools/exactness.py on a dev server: a fresh flat world (the dev PROPS), forceload, tick freeze, step
to game time 300, `sleepium reseed`, `function sleep:lag_clear`, `function sleep:lag_<scene>`, `tick step <ticks>`, then
`sleepium hash` of the scene's area, with the same hash / row flags. The scenes are not in the release jar: they come as
a datapack (--datapack, built by box_suite.py) put into the fresh world's datapacks folder before the server starts.
"off" = the same jar with -Dlab.opt.defaults=false (every engine default off); "on" = the jar as players run it (the
production defaults) plus --flags. 1.20.1 (Forge, no vanilla /tick): -Dsleep.tickCommand=true gives the jar's own
tick command, and `sleepium settle` runs after the scene as on the dev 1.20.1 suite.

The server folder is put back afterwards: its server.properties, eula.txt, mods and config/sleepium* are moved to
<server>/boxsuite-saved during the run and back after it (a run that died leaves them there: the next run puts them back
first); the world is a world of its own (level-name boxsuite-world), deleted after. Only this run's own java is killed,
by its PID. It refuses to start when this folder already runs a server, and with --alone when any java runs on the
machine (a box shared with other tests).

Output (the last 4 lines as tools/exactness.py prints them, for the suite's summary):
    off region hash H  (block entities N, entities M)  K errors in the log
    on  region hash H  (block entities N, entities M)  K errors in the log
    entities compared: M; differing: 0
    EXACT | DIFFERENT
and 'BOXEXACT END rc=<0 exact, 1 different, 2 failed, 3 refused>' as the very last line of --log.
"""
import argparse
import base64
import collections
import glob
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import zipfile

ap = argparse.ArgumentParser()
ap.add_argument('scene')
ap.add_argument('--server', required=True, help='a production NeoForge 1.21.1 / Forge 1.20.1 server folder (installed: libraries/...)')
ap.add_argument('--jar', required=True, help='the Sleepium jar to test')
ap.add_argument('--datapack', required=True, help='the scenes datapack zip (box_suite.py builds it)')
ap.add_argument('--java', default='java')
ap.add_argument('--mode', default='both', choices=('both', 'off', 'on', 'offoff'))
ap.add_argument('--port', type=int, default=25640)
ap.add_argument('--ticks', type=int, default=2400)
ap.add_argument('--flags', default='', help='more JVM flags for the "on" run')
ap.add_argument('--jvm', default='', help='more JVM flags for every run')
ap.add_argument('--exclude', default='', help='entity types left out of the row comparison (comma separated)')
ap.add_argument('--monsters', action='store_true', help='keep summoned monsters (spawn-monsters=true)')
ap.add_argument('--heap', default='4G')
ap.add_argument('--out', default='', help='rows / console files (default: <server>/../boxsuite-out)')
ap.add_argument('--tag', default='', help='a name for this run\'s files (default: the scene)')
ap.add_argument('--alone', action='store_true', help='refuse when any java runs on this machine')
ap.add_argument('--keep', action='store_true', help='keep the world afterwards (debugging)')
ap.add_argument('--log', default='', help='write this output to a file too')
ap.add_argument('--detach', default='', metavar='LOGFILE', help='start this run detached (survives the ssh session), its output in LOGFILE; prints the PID')
a = ap.parse_args()

SERVER = os.path.abspath(a.server)
SAVED = os.path.join(SERVER, 'boxsuite-saved')
LOCK = os.path.join(SERVER, 'boxsuite.lock')
LEVEL = 'boxsuite-world'
OUT = os.path.abspath(a.out or os.path.join(os.path.dirname(SERVER), 'boxsuite-out'))
TAG = a.tag or a.scene
WIN = os.name == 'nt'

# the dev suite's world (tools/server.py PROPS); 1.20.1 adds its classic flat layers without structures
PROPS = {
    'level-type': 'minecraft\\:flat', 'level-seed': '20260924', 'online-mode': 'false', 'spawn-protection': '0',
    'view-distance': '8', 'simulation-distance': '8', 'sync-chunk-writes': 'false', 'max-tick-time': '-1',
    'pause-when-empty-seconds': '-1', 'spawn-monsters': 'false',
}
FLAT_1201 = ('{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
             '{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structure_overrides":[],'
             '"lakes":false,"features":false}')
# not errors of the engine (tools/server.py KNOWN, both versions)
KNOWN = re.compile(r"No key layers|lag_create|Unknown block type 'create:|client_cursedfate|'cursedfate:|sleep:cf_vfx")


# ---------------------------------------------------------------------------------------------------------- output
class Out:
    def __init__(self, path):
        self.f = open(path, 'a', encoding='utf-8') if path else None

    def __call__(self, text=''):
        for target in (sys.stdout, self.f):
            if target is None:
                continue
            try:
                target.write(text + '\n')
                target.flush()
            except Exception:
                pass


say = Out(a.log)


def end(rc):
    say('BOXEXACT END rc=%d' % rc)
    sys.exit(rc)


# ---------------------------------------------------------------------------------------------------------- detach
if a.detach:
    argv, skip = [], False
    for x in sys.argv[1:]:
        if skip:
            skip = False
            continue
        if x == '--detach':
            skip = True
            continue
        if x.startswith('--detach='):
            continue
        argv.append(x)
    log = os.path.abspath(a.detach)
    os.makedirs(os.path.dirname(log), exist_ok=True)
    if os.path.exists(log):
        os.remove(log)
    args = [sys.executable, '-u', os.path.abspath(__file__)] + argv + ['--log', log]
    if WIN:
        # a process made by WMI is outside the ssh session's job: it lives on when the session ends
        cl = subprocess.list2cmdline(args)
        ps = ("$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='%s'; CurrentDirectory='%s'};"
              " Write-Output ('detached pid ' + $r.ProcessId + ' rc ' + $r.ReturnValue)") % (cl.replace("'", "''"), os.getcwd().replace("'", "''"))
        r = subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-EncodedCommand',
                            base64.b64encode(ps.encode('utf-16-le')).decode()], capture_output=True, text=True)
        print((r.stdout + r.stderr).strip())
    else:
        p = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True, cwd=os.getcwd())
        print('detached pid %d rc 0' % p.pid)
    sys.exit(0)


# ---------------------------------------------------------------------------------------------------------- processes
def java_processes():
    """[(pid, command line)] of every java on this machine"""
    out = []
    if WIN:
        ps = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | "
              "ForEach-Object { '' + $_.ProcessId + ' ' + $_.CommandLine }")
        try:
            r = subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', ps], capture_output=True, text=True,
                               encoding='utf-8', errors='replace', timeout=120)
            for l in r.stdout.splitlines():
                if l.strip():
                    pid, _, cmd = l.strip().partition(' ')
                    out.append((int(pid), cmd))
        except Exception as e:
            say('(java process list failed: %s)' % e)
    else:
        for d in glob.glob('/proc/[0-9]*'):
            try:
                with open(d + '/cmdline', 'rb') as f:
                    cmd = f.read().replace(b'\0', b' ').decode('utf-8', 'replace').strip()
                with open(d + '/comm') as f:
                    comm = f.read().strip()
            except OSError:
                continue
            if comm == 'java' or cmd.split(' ')[0].endswith('/java'):
                out.append((int(os.path.basename(d)), cmd))
    return out


def alive(pid):
    if WIN:
        r = subprocess.run(['tasklist', '/fi', 'PID eq %d' % pid, '/nh'], capture_output=True, text=True, errors='replace')
        return str(pid) in r.stdout
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def mark():
    return '-Dboxsuite.dir=' + SERVER.replace('\\', '/')


def refuse(why):
    say('REFUSED: ' + why)
    end(3)


# ---------------------------------------------------------------------------------------------------------- the folder
def loader():
    """('1.21.1' | '1.20.1', the launch args file relative to the server folder)"""
    name = 'win_args.txt' if WIN else 'unix_args.txt'
    for ver, pat in (('1.21.1', 'libraries/net/neoforged/neoforge/*/' + name), ('1.20.1', 'libraries/net/minecraftforge/forge/1.20.1-*/' + name)):
        hits = sorted(glob.glob(os.path.join(SERVER, pat)))
        if hits:
            return ver, os.path.relpath(hits[-1], SERVER).replace('\\', '/')
    refuse('no NeoForge / Forge launch file (%s) under %s/libraries' % (name, SERVER))


def put_back():
    """the folder as it was: our world, config and mods out, the saved ones back"""
    if not os.path.isdir(SAVED):
        return
    if not a.keep:
        shutil.rmtree(os.path.join(SERVER, LEVEL), ignore_errors=True)
    mods = os.path.join(SERVER, 'mods')
    if os.path.isdir(os.path.join(SAVED, 'mods')):
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
    if os.path.isdir(os.path.join(SAVED, 'config')):
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


def take():
    """the folder's own state aside (put_back() restores it)"""
    os.makedirs(os.path.join(SAVED, 'mods'))
    os.makedirs(os.path.join(SAVED, 'config'))
    mods = os.path.join(SERVER, 'mods')
    if os.path.isdir(mods):
        for f in os.listdir(mods):
            shutil.move(os.path.join(mods, f), os.path.join(SAVED, 'mods', f))
    for p in glob.glob(os.path.join(SERVER, 'config', 'sleepium*')):   # a player's settings would change the run
        shutil.move(p, os.path.join(SAVED, 'config', os.path.basename(p)))
    for f in ('server.properties', 'eula.txt'):
        src = os.path.join(SERVER, f)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(SAVED, f))
        else:
            open(os.path.join(SAVED, f + '.absent'), 'w').close()


def scene_area(zip_path, scene):
    with zipfile.ZipFile(zip_path) as z:
        for folder in ('function', 'functions'):
            name = 'data/sleep/%s/lag_%s.mcfunction' % (folder, scene)
            if name in z.namelist():
                for line in z.read(name).decode('utf-8').splitlines():
                    if line.startswith('forceload add'):
                        return tuple(int(v) for v in line.split()[2:6])
    refuse('scene %s: no lag_%s.mcfunction with a forceload line in %s' % (scene, scene, zip_path))


# ---------------------------------------------------------------------------------------------------------- one server
class Server:
    def __init__(self, cmdline, console):
        self.lines = []
        self.lock = threading.Lock()
        self.logf = open(console, 'w', encoding='utf-8')
        flags = 0x08000000 if WIN else 0   # CREATE_NO_WINDOW
        self.p = subprocess.Popen(cmdline, cwd=SERVER, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding='utf-8', errors='replace', bufsize=1, creationflags=flags)
        say('java pid %d' % self.p.pid)
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
        with self.lock:
            start = len(self.lines)
        self.cmd(c)
        return self.wait_for(pattern, timeout, start)

    def errors(self):
        with self.lock:
            return [l for l in self.lines if re.search(r'Exception|/ERROR\]|/FATAL\]', l) and not KNOWN.search(l)]

    def close(self):
        try:
            if self.p.poll() is None:
                self.cmd('stop')
                self.p.wait(180)
        except Exception:
            pass
        if self.p.poll() is None:   # our own java only, by its PID
            say('server did not stop: killing java pid %d' % self.p.pid)
            self.p.kill()
            try:
                self.p.wait(60)
            except Exception:
                pass
        self.logf.close()


def game_time(s):
    return int(s.ask('time query gametime', r'The time is (\d+)').group(1))


def wait_time(s, target, limit):
    t0 = time.time()
    while game_time(s) < target:
        if time.time() - t0 > limit:
            raise RuntimeError('game time did not reach %d in %d s' % (target, limit))
        time.sleep(1)


def run(mode, version, launch, area):
    x1, z1, x2, z2 = area
    rows = os.path.join(OUT, '%s-%s-rows.txt' % (TAG, mode)).replace('\\', '/')
    berows = os.path.join(OUT, '%s-%s-berows.txt' % (TAG, mode)).replace('\\', '/')
    for f in (rows, berows):
        if os.path.exists(f):
            os.remove(f)
    jvm = ['-Xms' + a.heap, '-Xmx' + a.heap, mark(),
           '-Dsleep.seedEntities=true', '-Dsleep.hashEntities=true', '-Dsleep.hashEntitiesDeep=true', '-Dsleep.hashRows=' + rows,
           '-Dsleep.hashClientNbt=true', '-Dsleep.hashBeRows=' + berows] + a.jvm.split()
    if version == '1.20.1':
        jvm.append('-Dsleep.tickCommand=true')
    if mode.startswith('off'):
        jvm.append('-Dlab.opt.defaults=false')
    else:
        jvm += a.flags.split()
    # a fresh world with the scenes datapack in it, the jar alone in mods
    shutil.rmtree(os.path.join(SERVER, LEVEL), ignore_errors=True)
    os.makedirs(os.path.join(SERVER, LEVEL, 'datapacks'))
    shutil.copy2(a.datapack, os.path.join(SERVER, LEVEL, 'datapacks', 'sleep-scenes.zip'))
    mods = os.path.join(SERVER, 'mods')
    os.makedirs(mods, exist_ok=True)
    for f in os.listdir(mods):
        os.remove(os.path.join(mods, f))
    shutil.copy2(a.jar, os.path.join(mods, os.path.basename(a.jar)))
    for p in glob.glob(os.path.join(SERVER, 'config', 'sleepium*')):   # the previous run's settings file
        shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    props = dict(PROPS)
    if version == '1.20.1':
        props['generator-settings'] = FLAT_1201
    props.update({'level-name': LEVEL, 'server-port': str(a.port), 'spawn-monsters': 'true' if a.monsters else 'false',
                  'initial-enabled-packs': 'vanilla,file/sleep-scenes.zip'})
    with open(os.path.join(SERVER, 'server.properties'), 'w', encoding='utf-8') as f:
        f.writelines('%s=%s\n' % kv for kv in props.items())
    with open(os.path.join(SERVER, 'eula.txt'), 'w') as f:
        f.write('eula=true\n')

    t0 = time.time()
    s = Server([a.java] + jvm + ['@' + launch, 'nogui'], os.path.join(OUT, '%s-%s-console.log' % (TAG, mode)))
    try:
        s.wait_for(r'Done \(', 900)
        t_up = time.time() - t0
        s.cmd('forceload add %d %d %d %d' % (x1, z1, x2, z2))
        s.cmd('tick freeze')
        now = game_time(s)
        if now < 300:
            s.cmd('tick step %d' % (300 - now))
            wait_time(s, 300, 600)
        # sent back to back as tools/exactness.py does (the same server loop turns), checked after
        with s.lock:
            start = len(s.lines)
        s.cmd('sleepium reseed')
        s.cmd('function sleep:lag_clear')
        s.cmd('function sleep:lag_%s' % a.scene)
        if version == '1.20.1':
            s.cmd('sleepium settle')   # the scene's light updates done before it steps (mobs walk by light)
        s.cmd('tick step %d' % a.ticks)
        bad = r"Unknown function|Unknown or incomplete command|Incorrect argument for command"
        s.wait_for(r'randoms reseeded|' + bad, 300, start)
        for fn in ('lag_clear', 'lag_' + a.scene):
            # 1.20.1: "Executed 52 command(s) from function 'sleep:x'"; 1.21.1: "Running function sleep:x"
            m = s.wait_for(r"Executed (?:\d+ )?command(?:\(s\)|s)? from function '?sleep:%s'|Running function sleep:%s\b|%s" % (fn, fn, bad), 300, start)
            if m.group(0).startswith(('Unknown', 'Incorrect')):
                raise RuntimeError('function sleep:%s did not run (%s): is the scenes datapack loaded?' % (fn, m.group(0)))
        if version == '1.20.1':
            s.wait_for(r'light settled', 300, start)
        wait_time(s, 300 + a.ticks, 3600)
        h = s.ask('sleepium hash %d -64 %d %d -40 %d' % (x1, z1, x2, z2), r'region hash (\w+) \(block entities (\d+), entities (\d+)', 600)
        errs = s.errors()
    finally:
        s.close()
    took = time.time() - t0
    return (h.group(1), int(h.group(2)), int(h.group(3)), rows, len(errs), errs[:5], t_up, took)


# ---------------------------------------------------------------------------------------------------------- main
def main():
    os.makedirs(OUT, exist_ok=True)
    if not os.path.isdir(SERVER):
        refuse('no server folder ' + SERVER)
    for f in (a.jar, a.datapack):
        if not os.path.isfile(f):
            refuse('missing ' + f)
    version, launch = loader()
    javas = java_processes()
    here = mark()
    mine = [pid for pid, cmd in javas if here in cmd]
    if mine:
        refuse('a server of this folder runs already (java pid %s)' % mine)
    if os.path.exists(LOCK):
        try:
            other = int(open(LOCK).read().split()[0])
        except Exception:
            other = 0
        if other and other != os.getpid() and alive(other):
            refuse('another box_exact (pid %d) uses %s' % (other, SERVER))
    if a.alone and javas:
        refuse('java runs on this machine: %s' % ' '.join(str(pid) for pid, _ in javas))
    with open(LOCK, 'w') as f:
        f.write('%d\n' % os.getpid())
    area = scene_area(a.datapack, a.scene)
    say('boxexact pid %d: %s %s on %s (%s, %s), mode %s, port %d' % (os.getpid(), TAG, a.scene, SERVER, version, os.path.basename(a.jar),
                                                                    a.mode, a.port))
    if os.path.isdir(SAVED):
        say('a previous run left %s: putting it back first' % SAVED)
        put_back()
    modes = {'both': ('off', 'on'), 'off': ('off',), 'on': ('on',), 'offoff': ('off', 'off2')}[a.mode]
    results = {}
    rc = 2
    take()
    try:
        for mode in modes:
            r = run(mode, version, launch, area)
            results[mode] = r
            say('[%s run: up in %.0f s, %.0f s in all]' % (mode, r[6], r[7]))
            for e in r[5]:
                say('  error: ' + e[:220])
        for mode in modes:
            h, be, ent, rows, nerr, _, _, _ = results[mode]
            say('%-3s region hash %s  (block entities %d, entities %d)%s' % (mode[:3], h, be, ent, '  %d errors in the log' % nerr if nerr else ''))
        if len(modes) == 1:
            rc = 0
        else:
            skip = {'minecraft:' + t.strip() for t in a.exclude.split(',') if t.strip()}

            def load(path):
                if not os.path.exists(path):
                    return collections.Counter()
                return collections.Counter(l for l in open(path, encoding='utf-8').read().splitlines() if l.split(' ', 1)[0] not in skip)

            x, y = load(results[modes[0]][3]), load(results[modes[1]][3])
            diff = (x - y) + (y - x)
            by_type = collections.Counter(l.split(' ', 1)[0] for l in (x - y).elements())
            same = results[modes[0]][0] == results[modes[1]][0]
            say('entities compared: %d; differing: %d %s' % (sum(x.values()), sum(diff.values()) // 2, dict(by_type) if by_type else ''))
            ok = not diff and (same or skip)
            say('EXACT' if ok else 'DIFFERENT')
            rc = 0 if ok else 1
    except Exception as e:
        say('FAILED: %s' % e)
        rc = 2
    finally:
        put_back()
        try:
            os.remove(LOCK)
        except OSError:
            pass
    end(rc)


try:
    main()
except SystemExit:
    raise
except BaseException as e:   # (Ctrl-C) the folder back as it was
    say('FAILED: %r' % e)
    try:
        put_back()
    finally:
        end(2)
