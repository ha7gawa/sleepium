"""Server speed, deterministic: jar against jar on the production NeoForge server, the same ticks in both.

    python tools/detbench.py --jar old=F:/rel/sleepium-1.1.4.jar --jar new=build/libs/sleepium-1.1.5.jar mobs zoo items
           (a jar's own flags: --jar nosup=build/libs/x.jar::-Dlab.opt.collideKernelSupport=false)
           [--rounds 2] [--warm 200] [--measure 600] [--server F:/release-test/server]

tools/bench.py times a scene by the clock: the scene is built at whatever tick the server reached, the entities get
other ids, chunks load at other ticks - mobs diverge from the first seconds and a mob scene moves +-30 % from run to
run (11-07, the 1.1.5 checks). Here every run follows exactness.py's discipline: a fresh flat world, the entities'
randoms seeded from their ids (-Dsleep.seedEntities), the clock frozen and stepped to tick 300, the randoms reseeded,
then the scenes in the given order, each built, stepped --warm ticks, then --measure ticks stepped while /tick query
reads vanilla's 100-tick windows. After each scene its region is hashed: equal hashes across the jars mean both ran
the same ticks with the same entities doing the same things - the time difference is the code's. A scene whose hashes
differ is marked "not comparable" (a nondeterministic scene, or an optimisation that changed the result: run
tools/exactness.py on it).

Rounds interleave the jars (old new old new ...). Per scene: the mean of every window of every round, the spread of
the rounds' means (max - min, the noise to compare a difference with), the hashes. Appends to run/detbench.csv.
The server's own world, mods and properties are put back afterwards.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import threading
import time

ap = argparse.ArgumentParser()
ap.add_argument('scenes', nargs='+')
ap.add_argument('--jar', action='append', required=True, help='label=path of a Sleepium jar (repeat for each build)')
ap.add_argument('--rounds', type=int, default=2)
ap.add_argument('--warm', type=int, default=200, help='ticks stepped after the scene is built, before measuring')
ap.add_argument('--measure', type=int, default=600, help='ticks stepped while measuring (100-tick windows)')
ap.add_argument('--server', default=r'F:\release-test\server')
ap.add_argument('--java', default=r'C:\Users\ha7gawa\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe')
ap.add_argument('--neoforge', default='21.1.251')
ap.add_argument('--heap', default='3G')
ap.add_argument('--flags', default='', help='more JVM flags for every run')
a = ap.parse_args()

HERE = os.path.dirname(os.path.abspath(__file__))
SCENES = os.path.join(HERE, '..', 'src', 'scenes', 'resources', 'data')
JARS = []
JFLAGS = {}
for j in a.jar:
    label, path = j.split('=', 1)
    path, _, JFLAGS[label] = path.partition('::')   # label=path::-Dx=y - flags for this jar's runs only
    JARS.append((label, os.path.abspath(path)))
D = a.server
PROPS = os.path.join(D, 'server.properties')
FLAT = ('{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
        '{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structure_overrides":[],"lakes":false,"features":false}')
props = {'online-mode': 'false', 'level-type': 'minecraft\\:flat', 'level-seed': '20260924', 'view-distance': '8',
         'level-name': 'detbench-world', 'simulation-distance': '8', 'server-port': '25601', 'spawn-protection': '0',
         'spawn-monsters': 'false', 'max-tick-time': '-1', 'generator-settings': FLAT}
AVG = re.compile(r'Average time per tick: ([\d.,]+) ?ms')


def area(scene):
    with open(os.path.join(SCENES, 'sleep', 'function', 'lag_%s.mcfunction' % scene), encoding='utf-8') as f:
        for line in f:
            if line.startswith('forceload add'):
                return tuple(int(v) for v in line.split()[2:6])
    raise SystemExit('scene %s has no forceload line' % scene)


def run(label, jar):
    mods = os.path.join(D, 'mods')
    for f in os.listdir(mods):
        if f.endswith('.jar'):
            os.remove(os.path.join(mods, f))
    shutil.copy(jar, mods)
    W = os.path.join(D, 'detbench-world')
    shutil.rmtree(W, ignore_errors=True)
    dp = os.path.join(W, 'datapacks', 'sleep-scenes')
    shutil.copytree(SCENES, os.path.join(dp, 'data'))
    with open(os.path.join(dp, 'pack.mcmeta'), 'w') as f:
        f.write('{"pack":{"pack_format":48,"description":"sleep scenes"}}')
    with open(PROPS, 'w') as f:
        f.writelines('%s=%s\n' % kv for kv in props.items())
    jvm = ['-Xms' + a.heap, '-Xmx' + a.heap, '-XX:+AlwaysPreTouch', '-Dsleep.seedEntities=true'] + a.flags.split() + JFLAGS[label].split()
    p = subprocess.Popen([a.java] + jvm + ['@user_jvm_args.txt', '@libraries/net/neoforged/neoforge/%s/win_args.txt' % a.neoforge, 'nogui'],
                         cwd=D, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
                         errors='replace', bufsize=1)
    lines = []
    threading.Thread(target=lambda: [lines.append(l.rstrip('\n')) for l in p.stdout], daemon=True).start()

    def wait(rx, t, start=0):
        t0 = time.time()
        while time.time() - t0 < t:
            for l in lines[start:]:
                m = re.search(rx, l)
                if m:
                    return m
            if p.poll() is not None:
                return None
            time.sleep(0.2)
        return None

    def cmd(c):
        p.stdin.write(c + '\n')
        p.stdin.flush()

    def ask(c, rx, t=60):
        n = len(lines)
        cmd(c)
        return wait(rx, t, n)

    def game_time():
        m = ask('time query gametime', r'The time is (\d+)')
        return int(m.group(1)) if m else -1

    def step_to(target):
        while True:
            now = game_time()
            if now < 0 or now >= target:
                return now
            cmd('tick step %d' % (target - now))
            time.sleep(min(10, max(1, (target - now) / 20 / 2)))

    out = {}
    if not wait(r'Done \(', 400):
        print(label, 'the server did not start', flush=True)
    else:
        cmd('tick freeze')
        step_to(300)
        cmd('sleepium reseed')
        for scene in a.scenes:
            x1, z1, x2, z2 = area(scene)
            cmd('function sleep:lag_clear')
            cmd('function sleep:lag_%s' % scene)
            t = game_time()
            step_to(t + a.warm)
            vals = []
            t = game_time()
            for w in range(a.measure // 100):
                step_to(t + 100 * (w + 1))
                m = ask('tick query', AVG.pattern, 30)
                if m:
                    vals.append(float(m.group(1).replace(',', '.')))
            h = ask('sleepium hash %d -64 %d %d -40 %d' % (x1, z1, x2, z2), r'region hash (\w+)', 60)
            out[scene] = (vals, h.group(1) if h else '?')
            print('  %-6s %-10s %s ms/tick (%d windows), hash %s' % (label, scene, '%.3f' % (sum(vals) / len(vals)) if vals else '-', len(vals),
                                                                      out[scene][1]), flush=True)
    cmd('stop')
    try:
        p.wait(120)
    except subprocess.TimeoutExpired:
        p.kill()
    errs = [l for l in lines if re.search(r'Exception|/ERROR\]|/FATAL\]|Mixin apply failed', l)
            and not re.search(r"create:|lag_create|client_create", l)]   # the Create scenes, without Create installed
    return out, errs


saved_props = open(PROPS, encoding='utf-8').read()
keep = os.path.join(D, 'saved-mods-detbench')
os.makedirs(keep, exist_ok=True)
for f in os.listdir(os.path.join(D, 'mods')):
    shutil.move(os.path.join(D, 'mods', f), os.path.join(keep, f))
res = {label: [] for label, _ in JARS}
try:
    for r in range(a.rounds):
        for label, jar in (JARS if r % 2 == 0 else list(reversed(JARS))):
            out, errs = run(label, jar)
            res[label].append(out)
            print('round %d %s: errors %d' % (r + 1, label, len(errs)), flush=True)
finally:
    with open(PROPS, 'w', encoding='utf-8') as f:
        f.write(saved_props)
    for f in os.listdir(os.path.join(D, 'mods')):
        os.remove(os.path.join(D, 'mods', f))
    for f in os.listdir(keep):
        shutil.move(os.path.join(keep, f), os.path.join(D, 'mods', f))

labels = [label for label, _ in JARS]
print('\n%-12s %s  hashes' % ('scene', '  '.join('%22s' % ('%s ms (spread)' % l) for l in labels)))
csv = os.path.join(HERE, '..', 'run', 'detbench.csv')
os.makedirs(os.path.dirname(csv), exist_ok=True)
with open(csv, 'a', encoding='utf-8') as f:
    for scene in a.scenes:
        cells, hashes = [], set()
        for l in labels:
            rounds = [o[scene] for o in res[l] if scene in o]
            allv = [v for vals, _ in rounds for v in vals]
            means = [sum(vals) / len(vals) for vals, _ in rounds if vals]
            hashes.update(hh for _, hh in rounds)
            cells.append('%22s' % ('%.3f (%.3f)' % (sum(allv) / len(allv), max(means) - min(means)) if allv else '-'))
            if allv:
                f.write('%s,%s,%s,%.4f,%.4f,%d\n' % (time.strftime('%Y-%m-%d %H:%M'), l, scene, sum(allv) / len(allv), max(means) - min(means), len(rounds)))
        note = 'same' if len(hashes) == 1 else 'NOT comparable (%d hashes)' % len(hashes)
        print('%-12s %s  %s' % (scene, '  '.join(cells), note))
