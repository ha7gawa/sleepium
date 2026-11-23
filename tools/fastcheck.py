"""The fast exactness check (5-10 min, 11-23): tools/suite.py's scenes on dev copies of this tree, each scene's ticks
run back to back (`tick sprint`) and only the "on" run per scene - its region hash compared with the scene's "off" hash
kept from an earlier sprint run (run/fastcheck-off.txt; a scene without one gets its off run first, once).

    python tools/fastcheck.py [--workers 4] [--flags "..."] [--refresh] [scene ...]

--refresh: the off hashes made again (after a Minecraft / scene change). Sprint hashes differ from the step hashes of
tools/suite.py (vanilla's own timing-dependent paths), but off and on agree within the mode - a difference is a finding.
Copies: <tree>/../work-fast<N> (their build / run kept). Summary lines in run/fastcheck-summary.txt.
"""
import argparse
import os
import queue
import shutil
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
SCENES = ('restcarts items itemwake carts vehicles cramming physics hoppers furnaces smelters beactive bezoo pistons redstone '
          'crops fluids mobs villagers jobclash calmzoo zoo').split()
MOBSLEEP = '-Dlab.sleep.components=true -Dlab.sleep.mobRuntime=true -Dlab.opt.WriteBarrier=true'
SKIP = {'build', 'run', '.gradle'}

ap = argparse.ArgumentParser()
ap.add_argument('scenes', nargs='*')
ap.add_argument('--workers', type=int, default=4)
ap.add_argument('--port', type=int, default=25660)
ap.add_argument('--ticks', type=int, default=2400)
ap.add_argument('--flags', default='')
ap.add_argument('--refresh', action='store_true')
ap.add_argument('--no-mobsleep', action='store_true')
a = ap.parse_args()
scenes = a.scenes or SCENES
OFF = os.path.join(ROOT, 'run', 'fastcheck-off.txt')
SUMMARY = os.path.join(ROOT, 'run', 'fastcheck-summary.txt')
lock = threading.Lock()
offs = {}
if os.path.exists(OFF) and not a.refresh:
    for line in open(OFF, encoding='utf-8'):
        k, _, v = line.strip().partition(' ')
        if v: offs[k] = v


def line(text):
    with lock:
        with open(SUMMARY, 'a', encoding='utf-8') as f:
            f.write(text + '\n')
        print(text, flush=True)


def sync(dst):
    os.makedirs(dst, exist_ok=True)
    for name in os.listdir(dst):
        if name not in SKIP:
            p = os.path.join(dst, name)
            shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    for name in os.listdir(ROOT):
        if name in SKIP or name.startswith('run-'):
            continue
        s, d = os.path.join(ROOT, name), os.path.join(dst, name)
        shutil.copytree(s, d) if os.path.isdir(s) else shutil.copy2(s, d)


def run(copy, port, scene, mode, flags):
    env = dict(os.environ, MC_PORT=str(port))
    args = [sys.executable, os.path.join('tools', 'exactness.py'), scene, '--ticks', str(a.ticks), '--sprint', '--only', mode]
    if scene == 'zoo':
        args.append('--monsters')
    if flags:
        args.append('--flags=' + flags)
    r = subprocess.run(args, cwd=copy, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    for l in out.splitlines():
        if l.startswith(mode) and 'region hash' in l:
            return l.split('region hash')[1].split()[0]
    return None


def worker(i, jobs):
    copy = os.path.join(os.path.dirname(ROOT), 'work-fast%d' % i)
    sync(copy)
    port = a.port + i
    while True:
        try:
            key, scene, flags = jobs.get_nowait()
        except queue.Empty:
            return
        t0 = time.time()
        off_key = key + ('|mobsleep' if MOBSLEEP in flags else '')
        if off_key not in offs:
            h = run(copy, port, scene, 'off', '')
            with lock:
                offs[off_key] = h
                with open(OFF, 'a', encoding='utf-8') as f:
                    f.write('%s %s\n' % (off_key, h))
        on = run(copy, port, scene, 'on', flags)
        off = offs[off_key]
        verdict = 'EXACT' if on and off and on == off else 'DIFFERENT' if on and off else 'FAILED'
        line('%-10s %-10s off %s on %s  %s  [%.0f s, w%d]' % (scene, 'mob sleep' if MOBSLEEP in flags else '', off, on, verdict, time.time() - t0, i))


jobs = queue.Queue()
for s in scenes:
    jobs.put((s, s, a.flags))
    if not a.no_mobsleep and s in ('mobs', 'calmzoo'):
        jobs.put((s, s, (MOBSLEEP + ' ' + a.flags).strip()))
t0 = time.time()
line('=== %s  fastcheck: %d runs, %d workers, flags %s' % (time.strftime('%F %T'), jobs.qsize(), a.workers, a.flags or '-'))
ts = [threading.Thread(target=worker, args=(i + 1, jobs)) for i in range(min(a.workers, jobs.qsize()))]
for t in ts:
    t.start()
    time.sleep(5)
for t in ts:
    t.join()
line('=== fastcheck done in %.1f min' % ((time.time() - t0) / 60))
