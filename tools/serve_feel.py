"""What a player feels of a slow server (notes/92): a dedicated dev server of this build (tools/server.py) with a test
load, and a ClientAuto client (from a copy of this project: --client-dir, its own run folder) joined to it, acting as a
player (dev.lull.ServeFeel: blocks placed / broken, chat, a command) and timing the answers. Prints the client's
serveFeel line and the server's serve line (tick times, queue wait per packet type) and appends both to
run/serve-feel.txt.

    python tools/serve_feel.py LABEL [--slow 80] [--jitter 0] [--secs 30] [--server-flags "..."] [--client-flags "..."]
        [--off] (server without the engine's defaults) [--scene mobs] (a lag scene built near the player)
        [--stall-at 10:2000] (a one-off stall S seconds into the window, MS long) [--orbit ...] (a flight: chunks)
MC_PORT (default 25611) is the server's port.
"""
import argparse
import os
import re
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from server import Server, ROOT  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument('label')
ap.add_argument('--slow', type=int, default=0, help='ms slept in every overworld tick (-Dlab.serve.slowTick)')
ap.add_argument('--jitter', type=int, default=0)
ap.add_argument('--secs', type=int, default=30)
ap.add_argument('--settle', type=int, default=20)
ap.add_argument('--off', action='store_true', help='server without the engine defaults')
ap.add_argument('--server-flags', default='')
ap.add_argument('--client-flags', default='')
ap.add_argument('--scene', help='a lag scene (sleep:lag_<scene>) built before the client joins')
ap.add_argument('--stall-at', help='S:MS - /sleep stall MS, S seconds after the client joined')
ap.add_argument('--orbit', help='ClientAuto orbit (a flight) - actions off')
ap.add_argument('--setup', help='commands after the join, run at the player ("|" between; e.g. machines of a mod next to it)')
ap.add_argument('--mods', help='a folder of mod jars copied into both run/mods (server and client copy) for this run')
ap.add_argument('--client-dir', default=os.path.join(os.path.dirname(ROOT), 'work-dscli'))
ap.add_argument('--client-heap', default='2G')
ap.add_argument('--no-cows', action='store_true', help='no cows summoned next to the player (they can stand where the blocks are placed)')
ap.add_argument('--bots', type=int, default=0, help='N bot players (BotLoad, dev) around the player, moving ("mix"): a loaded server')
ap.add_argument('--cmd-at', help='S:COMMAND - a server console command S seconds into the window (e.g. 15:save-all)')
a = ap.parse_args()
port = os.environ.setdefault('MC_PORT', '25611')

sjvm = ['-Xmx2G', '-Dsleep.serveMeter=true'] + (['-Dlab.opt.defaults=true'] if not a.off else []) + a.server_flags.split()
if a.slow:
    sjvm.append('-Dlab.serve.slowTick=%d' % a.slow)
if a.jitter:
    sjvm.append('-Dlab.serve.slowTickJitter=%d' % a.jitter)
cjvm = ['-Xmx' + a.client_heap, '-Dsleep.clientAuto=' + a.label, '-Dsleep.clientAuto.asPlayers=true', '-Dsleep.clientAuto.server=localhost:' + port,
        '-Dsleep.clientAuto.steady=true', '-Dsleep.clientAuto.steadySeconds=%d' % a.secs, '-Dsleep.clientAuto.settle=%d' % a.settle,
        '-Dsleep.clientAuto.gamemode=creative', '-Dsleep.serveFeel=true', '-Dlab.opt.defaults=true', '-Dsleep.clientAuto.distance=8']
if a.orbit:
    cjvm += ['-Dsleep.clientAuto.orbit=' + a.orbit, '-Dsleep.serveFeel.actions=false', '-Dsleep.clientAuto.holes=true']
cjvm += a.client_flags.split()

log = os.path.join(ROOT, 'run', 'serve-feel-%s.server.log' % a.label)
for d in (os.path.join(ROOT, 'run', 'mods'), os.path.join(a.client_dir, 'run', 'mods')):   # the run's mods only (none without --mods)
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d):
        if f.endswith('.jar'):
            os.remove(os.path.join(d, f))
    if a.mods:
        import shutil
        for f in os.listdir(a.mods):
            if f.endswith('.jar'):
                shutil.copy(os.path.join(a.mods, f), d)
os.makedirs(os.path.dirname(log), exist_ok=True)
t0 = time.time()
with Server(sjvm, {'enforce-secure-profile': 'false', 'view-distance': '8'}, log) as s:
    s.wait_for(r'Done \(', timeout=300)
    print('server up %.0f s' % (time.time() - t0), flush=True)
    s.cmd('gamerule sendCommandFeedback true')
    if a.scene:
        for sc in a.scene.split(','):   # several at once: a heavier real tick
            s.cmd('function sleep:lag_%s' % sc)
    gw = os.path.join(a.client_dir, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
    cal = os.path.join(a.client_dir, 'run', 'config', 'sleepium-calibration.properties')
    if os.path.isfile(cal):
        os.remove(cal)
    cout = os.path.join(ROOT, 'run', 'serve-feel-%s.client.log' % a.label)
    with open(cout, 'w', encoding='utf-8') as cf:
        c = subprocess.Popen([gw, 'runClient', '--console=plain', '-q', '-PrunJvmArgs=' + ' '.join(cjvm), '-PrunArgs=--width 854 --height 480'],
                             cwd=a.client_dir, stdout=cf, stderr=subprocess.STDOUT)
        def op():   # after the join: before it the name may resolve to an online profile (another UUID than the offline player's)
            s.wait_for(r'Dev joined the game', timeout=600)
            s.cmd('op Dev')
            if a.orbit:   # the flight starts where the player is (a jump there would be a 'moved too quickly' of its own)
                o = [float(v) for v in a.orbit.split(',')]
                s.cmd('tp Dev %s %s %s' % (o[0] + o[3], o[1], o[2]))
            for c in (a.setup.split('|') if a.setup else []):
                s.cmd('execute at Dev run ' + c.strip())
            if a.bots:   # other players around this one: the tick's sends (tracker, block updates) to many connections
                m = s.ask('data get entity Dev Pos', r'Dev has the following entity data: \[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]', timeout=30)
                s.ask('sleep bots add %d %d %d 32' % (a.bots, int(float(m.group(1))), int(float(m.group(3)))), r'bots: \d+ joined', timeout=300)
                s.cmd('sleep bots move mix')
            for i in range(0 if a.no_cows else 8):   # something that moves near the player: the world's update cadence as the client sees it
                s.cmd('execute at Dev run summon minecraft:cow ~%d ~ ~%d {NoAI:0b}' % (4 + i % 4, -3 + i))
        threading.Thread(target=op, daemon=True).start()
        if a.cmd_at:
            sec2, command = a.cmd_at.split(':', 1)

            def later():
                s.wait_for(r'joined the game', timeout=600)
                if a.bots:
                    s.wait_for(r'bots: \d+ joined', timeout=600)
                time.sleep(a.settle + 3 + int(sec2))
                print('command: %s' % command, flush=True)
                s.cmd(command)
            threading.Thread(target=later, daemon=True).start()
        if a.stall_at:
            sec, ms = a.stall_at.split(':')

            def stall():
                s.wait_for(r'joined the game', timeout=600)
                time.sleep(a.settle + 3 + int(sec))
                print('stall %s ms' % ms, flush=True)
                s.cmd('sleep stall %s' % ms)
            threading.Thread(target=stall, daemon=True).start()
        try:
            c.wait(timeout=420)
        except subprocess.TimeoutExpired:
            print('client timed out')
            c.kill()
    out = open(cout, encoding='utf-8', errors='replace').read()
    feel = re.search(r"serveFeel '.*", out)
    with s.lock:
        lines = list(s.lines)
    srv = [l for l in lines if "net '%s'" % a.label in l and 'serve:' in l]
    res = ['== %s %s slow=%d jitter=%d off=%s scene=%s stall=%s orbit=%s' % (time.strftime('%F %T'), a.label, a.slow, a.jitter, a.off, a.scene, a.stall_at, a.orbit),
           feel.group(0) if feel else 'client: no serveFeel line (see %s)' % cout,
           re.sub(r'.*?(serve: )', r'\1', srv[-1]) if srv and 'serve:' in srv[-1] else 'server: no serve line']
    holes = re.search(r'holes: [^;]*', out)
    if holes:
        res.append('client ' + holes.group(0))
    if a.bots:
        with s.lock:
            bl = [l for l in s.lines if 'bots: ' in l and 'joined' in l]
        res.append('bots: %s' % (bl[-1].split('sleep: ')[-1][:120] if bl else '?'))
    print('\n'.join(res), flush=True)
    with open(os.path.join(ROOT, 'run', 'serve-feel.txt'), 'a', encoding='utf-8') as f:
        f.write('\n'.join(res) + '\n')
    for l in lines:
        if 'moved too quickly' in l or 'moved wrongly' in l or 'Timed out' in l or 'lost connection' in l:
            print('server:', l)
