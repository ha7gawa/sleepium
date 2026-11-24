"""World generation speed on the production server (11-18, the 1.3.1 check): /sleepium genbench in a fresh normal world,
one warm-up area then measured areas, chunks per second for each.

    python tools/gen_prod.py --jar sleepium-....jar [--off] [--side 24] [--rounds 2] [--label L]
                             [--server F:\\release-test\\server] [--launch <loader args file>] [--java ...]

--off: the same jar with -Dlab.opt.defaults=false (every engine default off: vanilla's generation, the command still
there). The server's mods, world and properties are put back afterwards.
"""
import argparse, os, re, shutil, subprocess, threading, time

ap = argparse.ArgumentParser()
ap.add_argument('--jar', required=True)
ap.add_argument('--off', action='store_true')
ap.add_argument('--side', type=int, default=24)
ap.add_argument('--rounds', type=int, default=2)
ap.add_argument('--label', default='')
ap.add_argument('--server', default=r'F:\release-test\server')
ap.add_argument('--java', default=r'C:\Users\ha7gawa\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe')
ap.add_argument('--launch', default='libraries/net/neoforged/neoforge/21.1.251/win_args.txt')
ap.add_argument('--heap', default='6G')
ap.add_argument('--flags', default='')
ap.add_argument('--extra', default='', help='jars of other mods put beside ours (comma separated; compat runs)')
a = ap.parse_args()

D = a.server
PROPS = os.path.join(D, 'server.properties')
props = {'online-mode': 'false', 'level-seed': '20260924', 'view-distance': '6', 'level-name': 'genprod-world',
         'simulation-distance': '6', 'server-port': '25606', 'spawn-protection': '0', 'max-tick-time': '-1'}
RX = re.compile(r"genbench '([^']+)': (\d+)/(\d+) chunks FULL in ([\d.]+) s \((\d+) ticks\) = ([\d.]+) chunks/s")

saved_props = open(PROPS, encoding='utf-8').read()
keep = os.path.join(D, 'saved-mods-genprod')
os.makedirs(keep, exist_ok=True)
for f in os.listdir(os.path.join(D, 'mods')):
    shutil.move(os.path.join(D, 'mods', f), os.path.join(keep, f))
lines = []
res = []
label = a.label or ('off' if a.off else 'on')
try:
    shutil.copy(a.jar, os.path.join(D, 'mods'))
    for x in filter(None, a.extra.split(',')):
        shutil.copy(x, os.path.join(D, 'mods'))
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
        for r in range(a.rounds + 1):   # round 0: the JIT's warm-up
            n0 = len(lines)
            name = '%s-%s' % (label, 'warm' if r == 0 else 'r%d' % r)
            cmd('sleepium genbench %d %s' % (a.side if r else 16, name))
            m = wait(RX, 420, n0)
            if not m:
                print('no genbench result for ' + name + '; lines after the command:')
                print('\n'.join(l[:200] for l in lines[n0:n0 + 25]))
                break
            print('%s: %s/%s chunks in %s s = %s chunks/s' % (name, m.group(2), m.group(3), m.group(4), m.group(6)), flush=True)
            if r:
                res.append(float(m.group(6)))
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

if res:
    print('%s: %d rounds, chunks/s %s, mean %.1f' % (label, len(res), ' '.join('%.1f' % x for x in res), sum(res) / len(res)))
errs = [l for l in lines if '/ERROR' in l or 'Exception' in l]
print('%d error / exception lines' % len(errs))
for l in errs[:6]:
    print(l[:220])
