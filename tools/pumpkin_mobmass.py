"""The mob mass scene on Pumpkin (notes/99, head-to-head): tools/mobmass.py's scene rebuilt by console commands on any
terrain - the scene's area forceloaded, a flat grass platform at Y with a glass rim, the scene's summons at Y + 1 - then
--warm seconds, a window of --secs with `debug start` / `debug stop` (TPS) and `tps` / `tick query` every 5 s (whatever
Pumpkin prints is logged). Run on the machine, next to pumpkin.exe:

    python pumpkin_mobmass.py DIR SCENE_FILE [--y 200] [--warm 30] [--secs 40] [--label NAME] [--probe]
--probe: only asks `tick query`, `tps`, `debug start` / `debug stop` on an empty world (what the outputs look like).
"""
import argparse
import os
import re
import subprocess
import threading
import time

ap = argparse.ArgumentParser()
ap.add_argument('dir')
ap.add_argument('scene', nargs='?')
ap.add_argument('--y', type=int, default=200)
ap.add_argument('--warm', type=int, default=30)
ap.add_argument('--secs', type=int, default=40)
ap.add_argument('--label', default='pumpkin')
ap.add_argument('--probe', action='store_true')
ap.add_argument('--sample', type=int, default=0, help='after the window: N pigs positions, then again after 30 s (do they move?)')
a = ap.parse_args()

lines = []
lock = threading.Lock()
logf = open(os.path.join(a.dir, a.label + '.log'), 'w', encoding='utf-8')
p = subprocess.Popen([os.path.join(a.dir, 'pumpkin.exe')], cwd=a.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1)
ANSI = re.compile(r'\x1b\[[0-9;]*m')


def read():
    for line in p.stdout:
        line = ANSI.sub('', line)
        with lock:
            lines.append(line.rstrip('\n'))
        logf.write(line)
        logf.flush()


threading.Thread(target=read, daemon=True).start()


def cmd(c):
    p.stdin.write(c + '\n')
    p.stdin.flush()


def mark():
    with lock:
        return len(lines)


def since(m):
    with lock:
        return lines[m:]


def say(s):
    print(time.strftime('%H:%M:%S ') + s, flush=True)


t0 = time.time()
while time.time() - t0 < 120:
    with lock:
        if any('listening' in l.lower() for l in lines):
            break
    time.sleep(0.2)
say('up after %.1f s' % (time.time() - t0))
try:
    if a.probe:
        for c in ('tick query', 'tps', 'debug start'):
            m = mark()
            cmd(c)
            time.sleep(3)
            say('%s -> %s' % (c, ' | '.join(since(m))[:600]))
        time.sleep(5)
        m = mark()
        cmd('debug stop')
        time.sleep(3)
        say('debug stop -> %s' % ' | '.join(since(m))[:600])
    else:
        src = [l.strip() for l in open(a.scene, encoding='utf-8') if l.strip() and not l.startswith('#')]
        force = [l for l in src if l.startswith('forceload add')]
        xs = []
        for l in force:
            x1, z1, x2, z2 = (int(v) for v in l.split()[2:6])
            xs.append((x1, z1, x2, z2))
        for l in src:
            if l.startswith('gamerule') or l.startswith('time') or l.startswith('weather'):
                cmd(l)
        t1 = time.time()
        for l in force:
            cmd(l)
        x0, z0 = min(v[0] for v in xs), min(v[1] for v in xs)
        x1, z1 = max(v[2] for v in xs), max(v[3] for v in xs)
        time.sleep(20)   # (forceloaded chunks generate)
        say('forceloaded %d boxes; area %d..%d x %d..%d' % (len(force), x0, x1, z0, z1))
        # the platform: grass at Y in 128 x 128 pieces (vanilla's fill limit is 32768 blocks), a glass rim at Y + 1
        y = a.y
        for x in range(x0, x1 + 1, 128):
            for z in range(z0, z1 + 1, 128):
                cmd('fill %d %d %d %d %d %d minecraft:grass_block' % (x, y, z, min(x + 127, x1), y, min(z + 127, z1)))
        for (ax, az, bx, bz) in ((x0, z0, x1, z0), (x0, z1, x1, z1), (x0, z0, x0, z1), (x1, z0, x1, z1)):
            for s in range(0, max(bx - ax, bz - az) + 1, 128):
                if ax == bx:
                    cmd('fill %d %d %d %d %d %d minecraft:glass' % (ax, y + 1, az + s, bx, y + 2, min(az + s + 127, bz)))
                else:
                    cmd('fill %d %d %d %d %d %d minecraft:glass' % (ax + s, y + 1, az, min(ax + s + 127, bx), y + 2, bz))
        time.sleep(5)
        n = 0
        t2 = time.time()
        for l in src:
            if l.startswith('summon'):
                parts = l.split(' ')
                parts[3] = str(y + 1)   # (the scene's Y: the platform's top)
                cmd(' '.join(parts))
                n += 1
        say('platform at %d, %d summons sent in %.1f s' % (y, n, time.time() - t2))
        time.sleep(a.warm)
        m = mark()
        cmd('debug start')
        tw = time.time()
        while time.time() - tw < a.secs:
            time.sleep(5)
            cmd('tps')
            cmd('tick query')
        cmd('debug stop')
        time.sleep(3)
        # are they there, and do they move? (count; one pig's position twice, 5 s apart)
        q = mark()
        cmd('execute if entity @e[type=minecraft:pig]')
        cmd('execute positioned 4400 201 4400 run data get entity @e[type=minecraft:pig,limit=1,sort=nearest] Pos')
        cmd('execute positioned 4400 201 4400 run data get entity @e[type=minecraft:pig,limit=1,sort=nearest] Motion')
        time.sleep(5)
        cmd('execute positioned 4400 201 4400 run data get entity @e[type=minecraft:pig,limit=1,sort=nearest] Pos')
        cmd('execute positioned 4300 201 4300 run data get entity @e[type=minecraft:pig,limit=1,sort=nearest] Pos')
        time.sleep(5)
        cmd('execute positioned 4300 201 4300 run data get entity @e[type=minecraft:pig,limit=1,sort=nearest] Pos')
        time.sleep(3)
        say('checks: ' + ' | '.join(since(q))[:1500])
        if a.sample:
            pts = [(4150 + (i * 97) % 520, 4150 + (i * 53) % 520) for i in range(a.sample)]
            def positions():
                m0 = mark()
                for (px, pz) in pts:
                    cmd('execute positioned %d 201 %d run data get entity @e[type=minecraft:pig,limit=1,sort=nearest] Pos' % (px, pz))
                time.sleep(4)
                return [l.split('data: ')[-1] for l in since(m0) if 'entity data' in l]
            first = positions()
            time.sleep(30)
            second = positions()
            moved = sum(1 for x, y in zip(first, second) if x != y)
            say('sample: %d of %d pigs moved in 30 s; first %s; second %s' % (moved, len(first), first[:4], second[:4]))
        # the process' CPU over 10 s (all its threads)
        try:
            ps = lambda: float(subprocess.run(['powershell', '-NoProfile', '-Command', '(Get-Process -Id %d).TotalProcessorTime.TotalSeconds' % p.pid],
                                              capture_output=True, text=True).stdout.strip().replace(',', '.'))
            c0 = ps(); time.sleep(10); c1 = ps()
            say('cpu: %.2f cores' % ((c1 - c0) / 10))
        except Exception as ex:
            say('cpu: ' + str(ex))
        out = since(m)
        say('window: ' + ' | '.join(l for l in out if re.search(r'(?i)tps|tick|ms|profil', l))[-1500:])
finally:
    cmd('stop')
    try:
        p.wait(120)
    except subprocess.TimeoutExpired:
        p.kill()
