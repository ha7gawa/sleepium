"""The exactness suite with several servers at once: each worker is a copy of this project (../work-n<N>: sources and
tools synced from here, its own build and run folders, its own port), the scenes are shared out between them.
Per scene as tools/exactness_all.sh: optimisations off vs on; a scene that differs runs off vs off too (the scene's
own nondeterminism); mobs and calmzoo also with the mob sleep flags. Summary lines go to run/exactness-summary.txt
as each scene ends.
    python tools/suite.py [--workers 3] [--ticks 2400] [scene ...]"""
import argparse
import os
import queue
import shutil
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCENES = ('restcarts items itemwake carts vehicles cramming physics hoppers furnaces smelters beactive bezoo pistons redstone '
          'crops fluids mobs villagers jobclash calmzoo zoo').split()
MOBSLEEP = '-Dlab.sleep.components=true -Dlab.sleep.mobRuntime=true -Dlab.opt.WriteBarrier=true'
# what a worker copy needs: everything but the build, run and Gradle state folders
SKIP = {'build', 'run', '.gradle'}

ap = argparse.ArgumentParser()
ap.add_argument('scenes', nargs='*')
ap.add_argument('--workers', type=int, default=3)
ap.add_argument('--ticks', type=int, default=2400)
ap.add_argument('--copies', default='work-n', help='the worker copies: ../<copies><N> (a suite of another tree at the same time: its own name)')
ap.add_argument('--port', type=int, default=25580, help='worker N listens on port + N')
ap.add_argument('--flags', default='', help='more JVM flags for every "on" run (lab features to check)')
a = ap.parse_args()
scenes = a.scenes or SCENES
summary = os.path.join(ROOT, 'run', 'exactness-summary.txt')
os.makedirs(os.path.dirname(summary), exist_ok=True)
lock = threading.Lock()


def sync(dst):
    """dst mirrors this project's sources and tools; its build / run / .gradle folders stay"""
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


def line(text):
    with lock:
        with open(summary, 'a', encoding='utf-8') as f:
            f.write(text + '\n')
        print(text, flush=True)


def exact(copy, port, scene, extra):
    env = dict(os.environ, MC_PORT=str(port))
    r = subprocess.run([sys.executable, os.path.join('tools', 'exactness.py'), scene, '--ticks', str(a.ticks)] + extra, cwd=copy,
                       env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
    tail = ' '.join((r.stdout + r.stderr).strip().splitlines()[-4:])
    return 'EXACT' in tail.split()[-1:], tail


def worker(i, jobs):
    copy = os.path.join(os.path.dirname(ROOT), '%s%d' % (a.copies, i))
    sync(copy)
    port = a.port + i
    while True:
        try:
            scene = jobs.get_nowait()
        except queue.Empty:
            return
        mon = ['--monsters'] if scene == 'zoo' else []
        fl = ['--flags=' + a.flags] if a.flags else []   # (= : a value starting with -D is not an option)
        runs = [('defaults' + (' + ' + a.flags if a.flags else ''), mon + fl)] + ([('defaults+mob sleep', ['--flags=' + (MOBSLEEP + ' ' + a.flags).strip()])] if scene in ('mobs', 'calmzoo') else [])
        for label, extra in runs:
            t0 = time.time()
            ok, tail = exact(copy, port, scene, extra)
            line('%s %s | %s  [%.0f s, p%d]' % (scene, label, tail, time.time() - t0, i))
            if not ok:
                ok2, tail2 = exact(copy, port, scene, ['--offoff'] + mon)
                line('%s off-vs-off | %s  [p%d]' % (scene, tail2, i))


jobs = queue.Queue()
for s in scenes:
    jobs.put(s)
line('=== %s  suite: %d scenes, %d workers, %d ticks' % (time.strftime('%F %T'), len(scenes), a.workers, a.ticks))
threads = [threading.Thread(target=worker, args=(i + 1, jobs)) for i in range(min(a.workers, len(scenes)))]
for t in threads:
    t.start()
    time.sleep(20)   # the copies' first builds one after another
for t in threads:
    t.join()
line('=== suite done %s' % time.strftime('%F %T'))
