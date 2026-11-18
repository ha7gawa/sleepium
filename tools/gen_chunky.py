"""World generation speed measured by Chunky (11-18, the 1.3.1 check): independent of Sleepium's own genbench, so the
same tool runs on a server without Sleepium (true vanilla) and with it.

    python tools/gen_chunky.py --chunky Chunky-NeoForge-1.4.23.jar [--jar sleepium-....jar] [--radius 400] [--rounds 2]
                               [--label L] [--server F:\\release-test\\server] [--launch <loader args file>] [--java ...]

A fresh normal world (seed 20260924), a warm-up task (radius 160 blocks) then --rounds tasks of --radius blocks around
fresh centres far from spawn (64000 + 2000 * i, 64000). Per task: Chunky's total time and chunk count, and its
progress lines' rates (every few seconds) as the per-period trace. The server's mods, world, properties and Chunky's
config are put back afterwards.
"""
import argparse, os, re, shutil, subprocess, threading, time

ap = argparse.ArgumentParser()
ap.add_argument('--chunky', required=True)
ap.add_argument('--jar', default='', help='Sleepium jar; none = vanilla + Chunky')
ap.add_argument('--off', action='store_true', help='the Sleepium jar with -Dlab.opt.defaults=false')
ap.add_argument('--radius', type=int, default=400)
ap.add_argument('--rounds', type=int, default=2)
ap.add_argument('--label', default='')
ap.add_argument('--server', default=r'F:\release-test\server')
ap.add_argument('--java', default=r'C:\Users\ha7gawa\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe')
ap.add_argument('--launch', default='libraries/net/neoforged/neoforge/21.1.251/win_args.txt')
ap.add_argument('--heap', default='6G')
ap.add_argument('--flags', default='')
a = ap.parse_args()

D = a.server
PROPS = os.path.join(D, 'server.properties')
props = {'online-mode': 'false', 'level-seed': '20260924', 'view-distance': '6', 'level-name': 'genchunky-world',
         'simulation-distance': '6', 'server-port': '25606', 'spawn-protection': '0', 'max-tick-time': '-1'}
FIN = re.compile(r'Task finished for [\w:]+\. Processed: (\d+) chunks \(([\d.,]+)%\), Total time: (\d+):(\d+):(\d+)')
RATE = re.compile(r'Task running for [\w:]+\. Processed: (\d+) chunks .*Rate: ([\d.,]+) cps')

saved_props = open(PROPS, encoding='utf-8').read()
keep = os.path.join(D, 'saved-mods-genchunky')
os.makedirs(keep, exist_ok=True)
for f in os.listdir(os.path.join(D, 'mods')):
    shutil.move(os.path.join(D, 'mods', f), os.path.join(keep, f))
cfg = os.path.join(D, 'config', 'chunky')
cfg_keep = os.path.join(D, 'saved-config-chunky')
if os.path.isdir(cfg):
    shutil.move(cfg, cfg_keep)
lines = []
res = []
label = a.label or ('vanilla' if not a.jar else 'off' if a.off else 'on')
try:
    shutil.copy(a.chunky, os.path.join(D, 'mods'))
    if a.jar:
        shutil.copy(a.jar, os.path.join(D, 'mods'))
    shutil.rmtree(os.path.join(D, props['level-name']), ignore_errors=True)
    with open(PROPS, 'w') as f:
        f.writelines('%s=%s\n' % kv for kv in props.items())
    jvm = ['-Xms' + a.heap, '-Xmx' + a.heap] + (['-Dlab.opt.defaults=false'] if a.off else []) + a.flags.split()
    p = subprocess.Popen([a.java] + jvm + ['@user_jvm_args.txt', '@' + a.launch, 'nogui'], cwd=D, stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1)
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
            time.sleep(0.5)
        return None

    def cmd(c):
        p.stdin.write(c + '\n')
        p.stdin.flush()

    if not wait(r'Done \(', 600):
        print('server did not start; last lines:\n' + '\n'.join(lines[-15:]))
    else:
        time.sleep(5)
        cmd('chunky world minecraft:overworld')
        cmd('chunky shape square')
        for r in range(a.rounds + 1):   # round 0: the JIT's warm-up
            name = '%s-%s' % (label, 'warm' if r == 0 else 'r%d' % r)
            cmd('chunky center %d 64000' % (64000 + 2000 * r))
            cmd('chunky radius %d' % (160 if r == 0 else a.radius))
            time.sleep(1)
            n0 = len(lines)
            cmd('chunky start')
            m = wait(FIN.pattern, 900, n0)
            if not m:
                print('no finish for ' + name + '; lines after the start:')
                print('\n'.join(l[:200] for l in lines[n0:n0 + 25]))
                break
            secs = int(m.group(3)) * 3600 + int(m.group(4)) * 60 + int(m.group(5))
            rates = [float(x.group(2).replace(',', '.')) for x in (RATE.search(l) for l in lines[n0:]) if x]
            cps = int(m.group(1)) / max(secs, 1)
            print('%s: %s chunks in %d s = %.1f chunks/s; progress rates %s' % (name, m.group(1), secs, cps,
                  ' '.join('%.0f' % x for x in rates)), flush=True)
            if r:
                res.append(cps)
        cmd('stop')
        try:
            p.wait(timeout=300)
        except subprocess.TimeoutExpired:
            p.kill()
finally:
    with open(PROPS, 'w', encoding='utf-8') as f:
        f.write(saved_props)
    for f in os.listdir(os.path.join(D, 'mods')):
        os.remove(os.path.join(D, 'mods', f))
    for f in os.listdir(keep):
        shutil.move(os.path.join(keep, f), os.path.join(D, 'mods', f))
    os.rmdir(keep)
    shutil.rmtree(cfg, ignore_errors=True)
    if os.path.isdir(cfg_keep):
        shutil.move(cfg_keep, cfg)

if res:
    print('%s: %d rounds, chunks/s %s, mean %.1f' % (label, len(res), ' '.join('%.1f' % x for x in res), sum(res) / len(res)))
errs = [l for l in lines if ('/ERROR' in l or 'Exception' in l) and 'UniExceptionally' not in l]
print('%d error / exception lines' % len(errs))
for l in errs[:6]:
    print(l[:220])
