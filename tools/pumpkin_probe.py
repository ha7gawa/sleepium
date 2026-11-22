"""Pumpkin (Rust Minecraft server) driver for head-to-heads (notes/99): runs pumpkin.exe in its folder, reads its console
on a thread, sends commands, stops it. One server at a time; only this run's process is stopped (by its handle).

    python pumpkin_probe.py DIR probe                 first start: config files made, 'help' asked, stopped
    python pumpkin_probe.py DIR pigs N [--secs S]     N pigs summoned around spawn in a square, then S seconds watched
"""
import os
import re
import subprocess
import sys
import threading
import time

d = sys.argv[1]
mode = sys.argv[2]
lines = []
lock = threading.Lock()
log = open(os.path.join(d, 'probe-%s.log' % mode), 'w', encoding='utf-8')
p = subprocess.Popen([os.path.join(d, 'pumpkin.exe')], cwd=d, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                     text=True, encoding='utf-8', errors='replace', bufsize=1)


def read():
    for line in p.stdout:
        with lock:
            lines.append(line.rstrip('\n'))
        log.write(line)
        log.flush()


threading.Thread(target=read, daemon=True).start()


def cmd(c):
    p.stdin.write(c + '\n')
    p.stdin.flush()


def wait(pattern, timeout):
    rx = re.compile(pattern)
    t0 = time.time()
    while time.time() - t0 < timeout:
        with lock:
            for l in lines:
                if rx.search(l):
                    return l
        if p.poll() is not None:
            return None
        time.sleep(0.2)
    return None


t0 = time.time()
ready = wait(r'(?i)started|done|listening|ready', 180)
print('ready after %.1f s: %s' % (time.time() - t0, ready))
try:
    if mode == 'probe':
        for page in range(1, 15):
            cmd('help %d' % page)
            time.sleep(1.5)
    elif mode == 'pigs':
        n = int(sys.argv[3])
        secs = int(sys.argv[sys.argv.index('--secs') + 1]) if '--secs' in sys.argv else 60
        side = int(n ** 0.5) + 1
        t1 = time.time()
        k = 0
        for i in range(side):
            for j in range(side):
                if k >= n:
                    break
                cmd('summon minecraft:pig %d 70 %d' % (i * 3 - side * 3 // 2, j * 3 - side * 3 // 2))
                k += 1
        print('summon commands sent: %d in %.1f s' % (k, time.time() - t1))
        time.sleep(secs)
finally:
    cmd('stop')
    try:
        p.wait(60)
    except subprocess.TimeoutExpired:
        p.kill()
    time.sleep(1)
    with lock:
        print('\n'.join(lines[-60:]))
