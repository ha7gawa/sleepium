"""Does a bot (dev.lull.bots.BotLoad) cost the server what a real player costs? One dedicated server with the mobs scene;
first a real dev client (ClientAuto) joins and flies a circle around the scene (radius 160, 10 blocks/s, a position
every client tick), measured; then it leaves and one bot flies the same circle, measured the same way. Both windows:
vanilla's /perf, the bots' measure line with the real player's counters (-Dsleep.bots.tapReal: packets by kind, bytes,
the chunk rate the client asks for) and the PipeScout timers (tracker, sends, moves handled, chunk packets).

    MC_PORT=25601 python tools/bots_validate.py [--secs 30]
"""
import argparse
import os
import re
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from server import Server, RUN, ROOT, GRADLEW

ap = argparse.ArgumentParser()
ap.add_argument('--secs', type=int, default=30)
ap.add_argument('--orbit', default='730,-51,117,160,100', help='cx,cy,cz,radius,periodSeconds (ClientAuto orbit)')
ap.add_argument('--flags', default='')
a = ap.parse_args()
cx, cy, cz, r, per = [float(v) for v in a.orbit.split(',')]
speed = 2 * 3.141592653589793 * r / per
port = os.environ.get('MC_PORT', '25565')
jvm = ['-Dsleep.seedEntities=true', '-Dlab.opt.defaults=true', '-Dsleep.bots.tapReal=true', '-Dlab.pipescout=true',
       '-Dlab.opt.NetCoalesce=false', '-Dlab.opt.NetBulk=false', '-Dsleep.bots.modded=true'] + a.flags.split()
log = os.path.join(RUN, 'bots-validate.log')


def window(s, label):
    start = len(s.lines)
    s.cmd('sleep bots reset')
    time.sleep(1)
    s.cmd('sleep bots measure %d %s' % (a.secs, label))
    if a.secs >= 10:
        s.cmd('perf start')
    m = s.wait_for(r"sleep: (bots measure '%s'.*)" % label, timeout=a.secs + 60, start=start)
    st = s.ask('sleep bots stats', r'sleep: (bots stats: .*)', timeout=30)
    print('=== ' + label, flush=True)
    print('  ' + m.group(1).replace(' | ', '\n  '), flush=True)
    print('  ' + st.group(1).replace(' | ', '\n  '), flush=True)


with Server(jvm, {'spawn-monsters': 'false'}, log) as s:
    s.wait_for(r'Done \(')
    s.cmd('forceload add 700 100 760 135')
    s.cmd('tick freeze')
    s.cmd('tick step 300')
    time.sleep(18)
    s.cmd('sleep reseed')
    s.cmd('function sleep:lag_clear')
    s.cmd('function sleep:lag_mobs')
    s.cmd('tick unfreeze')
    s.cmd('op Dev')
    # the real client: joins, waits, flies the orbit through its measured window
    cjvm = ['-Xms2G', '-Xmx2G', '-Dsleep.clientAuto=botsval', '-Dsleep.clientAuto.server=127.0.0.1:' + port, '-Dsleep.clientAuto.steady=true',
            '-Dsleep.clientAuto.steadySeconds=%d' % (a.secs + 25), '-Dsleep.clientAuto.settle=20', '-Dsleep.clientAuto.gamemode=creative',
            '-Dsleep.clientAuto.netBench=false', '-Dsleep.clientAuto.orbit=' + a.orbit, '-Dsleep.clientAuto.asPlayers=true', '-Dlab.opt.defaults=true']
    client = subprocess.Popen([GRADLEW, 'runClient', '--console=plain', '-q', '-PrunJvmArgs=' + ' '.join(cjvm), '-PrunArgs=--width 854 --height 480'],
                              cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    clines = []
    clog = open(os.path.join(RUN, 'bots-validate-client.log'), 'w', encoding='utf-8')

    def read():
        for line in client.stdout:
            clines.append(line)
            clog.write(line)
    threading.Thread(target=read, daemon=True).start()
    s.wait_for(r'Dev joined the game', timeout=300)
    print('client joined', flush=True)
    s.cmd('tp Dev %g %g %g' % (cx + r, cy, cz))   # the orbit's start: its first position is no jump
    # (the client's console reaches us only when gradle ends: time from the join instead) the client settles 20 s, then
    # flies the orbit; 10 s more on the circle before the window
    time.sleep(30)
    if client.poll() is not None:
        raise SystemExit('the client ended early (see run/bots-validate-client.log)')
    window(s, 'real')
    client.wait(240)
    clog.close()
    time.sleep(3)
    s.ask('sleep bots add 1 %g %g 0' % (cx, cz), r'bots: \d+ joined', timeout=60)
    s.cmd('sleep bots move orbit %g %g' % (speed, r))
    time.sleep(9)
    window(s, 'bot')
