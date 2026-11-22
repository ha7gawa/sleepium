"""Dense block entities of real mods on the production NeoForge server: what a modded factory's ticking machines cost the
server's main thread (the user, 11-18: multiblocks put 200-1000 ticking block entities in a chunk).

    python tools/be_dense.py --jar build/libs/sleepium-....jar --mods F:/compat/be121/mods [--per 512] [--secs 40]
                             [--flags "..."] [--label x] [--limit N]

Pass 1: every block of the mods' lang files placed once; at server tick 200 the level's tickers are dumped
(-Dlab.dumpTickers) and the blocks whose position has a ticker are the ticking ones. Pass 2: each ticking block fills a
16 x (per / 256) x 16 slab in a chunk of its own; after --warm seconds, a /tick query every 5 s for --secs seconds.
Prints mspt (mean of the windows' averages, P50/P95/P99), the ticker count, and appends to run/be_dense.csv. The
server's mods, world and properties are put back afterwards (as tools/mod_blockentities.py).
"""
import argparse, os, re, shutil, subprocess, threading, time, zipfile

ap = argparse.ArgumentParser()
ap.add_argument('--jar', required=True)
ap.add_argument('--mods', required=True)
ap.add_argument('--ns', default='')
ap.add_argument('--perf', action='store_true', help='after the windows: /perf start (10 s); the tick tree from its profiling.txt printed in ms per tick')
ap.add_argument('--per', type=int, default=512, help='block entities a chunk (a multiple of 256)')
ap.add_argument('--warm', type=int, default=30)
ap.add_argument('--secs', type=int, default=40)
ap.add_argument('--limit', type=int, default=0, help='at most this many ticking block types (0: all)')
ap.add_argument('--exclude', default=r'sign|banner|bed$|_head$|skull', help='ticking block ids skipped (regex)')
ap.add_argument('--server', default=r'F:\release-test\server')
ap.add_argument('--java', default=r'C:\Users\ha7gawa\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe')
ap.add_argument('--neoforge', default='21.1.251')
ap.add_argument('--launch', default='', help='the loader args file (default: NeoForge --neoforge); Forge 1.20.1: libraries/net/minecraftforge/forge/1.20.1-47.4.9/win_args.txt')
ap.add_argument('--flags', default='')
ap.add_argument('--label', default='')
ap.add_argument('--heap', default='6G')
ap.add_argument('--log', default='', help='write every server line here')
ap.add_argument('--types', default='', help='a file of block ids (space separated) to place: no first pass')
ap.add_argument('--nojar', action='store_true', help='without our jar (vanilla behaviour; needs --types)')
a = ap.parse_args()

D = a.server
PROPS = os.path.join(D, 'server.properties')
FLAT = ('{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
        '{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structure_overrides":[],"lakes":false,"features":false}')
props = {'online-mode': 'false', 'level-type': 'minecraft\\:flat', 'level-seed': '20260924', 'view-distance': '6',
         'level-name': 'bedense-world', 'simulation-distance': '6', 'server-port': os.environ.get('MC_PORT', '25604'), 'spawn-protection': '0',
         'max-tick-time': '-1', 'generator-settings': FLAT}
AVG = re.compile(r'Average time per tick: ([\d.,]+) ?ms')
PCT = re.compile(r'P50: ([\d.,]+) ?ms,? P95: ([\d.,]+) ?ms,? P99: ([\d.,]+) ?ms')
num = lambda s: float(s.replace(',', '.'))

ids = []
want = set(x for x in a.ns.split(',') if x)
for f in sorted(os.listdir(a.mods)):
    if not f.endswith('.jar'):
        continue
    with zipfile.ZipFile(os.path.join(a.mods, f)) as z:
        for n in z.namelist():
            if re.match(r'assets/[^/]+/lang/en_us\.json$', n):
                for ns, name in re.findall(r'"block\.([a-z0-9_]+)\.([a-z0-9_]+)"', z.read(n).decode('utf-8', 'replace')):
                    if (not want or ns in want) and (ns + ':' + name) not in ids:
                        ids.append(ns + ':' + name)
print('%d blocks' % len(ids), flush=True)

saved_props = open(PROPS, encoding='utf-8').read()
keep = os.path.join(D, 'saved-mods-bedense')
os.makedirs(keep, exist_ok=True)
for f in os.listdir(os.path.join(D, 'mods')):
    shutil.move(os.path.join(D, 'mods', f), os.path.join(keep, f))
lines = []
result = None
try:
    if not a.nojar:
        shutil.copy(a.jar, os.path.join(D, 'mods'))
    for f in os.listdir(a.mods):
        if f.endswith('.jar'):
            shutil.copy(os.path.join(a.mods, f), os.path.join(D, 'mods'))
    shutil.rmtree(os.path.join(D, props['level-name']), ignore_errors=True)
    with open(PROPS, 'w') as f:
        f.writelines('%s=%s\n' % kv for kv in props.items())
    p = subprocess.Popen([a.java, '-Xmx' + a.heap, '-Dlab.dumpTickers=200'] + a.flags.split() + ['@user_jvm_args.txt',
                          '@' + (a.launch or 'libraries/net/neoforged/neoforge/%s/win_args.txt' % a.neoforge), 'nogui'],
                         cwd=D, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
                         errors='replace', bufsize=1)
    threading.Thread(target=lambda: [lines.append(l.rstrip('\n')) for l in p.stdout], daemon=True).start()

    def wait(rx, t, start=0):
        t0 = time.time()
        while time.time() - t0 < t:
            if any(re.search(rx, l) for l in lines[start:]):
                return True
            if p.poll() is not None:
                return False
            time.sleep(0.5)
        return False

    def cmd(c):
        p.stdin.write(c + '\n')
        p.stdin.flush()

    if not wait(r'Done \(', 600):
        print('server did not start; last lines:\n' + '\n'.join(lines[-15:]))
    else:
        print('server up', flush=True)
        side = 40
        if a.types:
            ticking = open(a.types).read().split()
        else:
            cmd('forceload add 0 0 %d %d' % (side * 3, (len(ids) // side + 1) * 3))
            where = {}
            for i, e in enumerate(ids):
                x, z = (i % side) * 3, (i // side) * 3
                where[(x, -60, z)] = e
                cmd('setblock %d -60 %d %s' % (x, z, e))
            wait(r'\[sleep/tickers\] minecraft:overworld total', 120)
            ticking = []
            for l in lines:
                m = re.search(r'\[sleep/tickers\] minecraft:overworld (-?\d+) (-?\d+) (-?\d+) (\S+)', l)
                if m:
                    e = where.get((int(m.group(1)), int(m.group(2)), int(m.group(3))))
                    if e and e not in ticking:
                        ticking.append(e)
            print('%d ticking block types: %s' % (len(ticking), ' '.join(ticking[:60])), flush=True)
            cmd('forceload remove all')
            cmd('fill 0 -60 0 %d -60 %d air' % (side * 3, (len(ids) // side + 1) * 3))
            ticking = [e for e in ticking if not re.search(a.exclude, e)]
            if a.limit:
                # spread over the namespaces: round-robin by mod
                by = {}
                for e in ticking:
                    by.setdefault(e.split(':')[0], []).append(e)
                rr = []
                while len(rr) < a.limit and any(by.values()):
                    for ns in list(by):
                        if by[ns] and len(rr) < a.limit:
                            rr.append(by[ns].pop(0))
                ticking = rr
        print('placing %d types: %s' % (len(ticking), ' '.join(ticking)), flush=True)
        layers = max(1, a.per // 256)
        # each type in a chunk of its own, chunks in a row 2 apart (x = 1000 + k * 32)
        for k, e in enumerate(ticking):
            cx = 1000 // 16 + k * 2
            x0 = cx * 16
            cmd('forceload add %d 0' % x0)
            if a.per < 256:   # rows of 16 on one layer
                cmd('fill %d -59 0 %d -59 %d %s' % (x0, x0 + 15, max(1, a.per // 16) - 1, e))
            else:
                cmd('fill %d -59 0 %d %d 15 %s' % (x0, x0 + 15, -59 + layers - 1, e))
        start = len(lines)
        time.sleep(a.warm)
        wins = []
        t0 = time.time()
        while time.time() - t0 < a.secs:
            n0 = len(lines)
            cmd('forge tps' if a.launch else 'tick query')   # 1.20.1 has no /tick
            time.sleep(5)
            seg = '\n'.join(lines[n0:])
            m, q = AVG.search(seg), PCT.search(seg)
            if m and q:
                wins.append((num(m.group(1)), num(q.group(1)), num(q.group(2)), num(q.group(3))))
            else:
                f = re.search(r'Overall\s*:\s*Mean tick time: ([\d.,]+) ?ms', seg)
                if f:
                    v = num(f.group(1))
                    wins.append((v, v, v, v))
        cmd('tick query')
        time.sleep(1)
        if a.perf:   # vanilla's profiler over 10 s of this factory: which phase holds the tick (1.3.4 server-tick graph)
            import glob, zipfile
            before = set(glob.glob(os.path.join(D, 'debug', 'profiling', '*.zip')))
            n0 = len(lines)
            cmd('perf start')
            t0 = time.time()
            while time.time() - t0 < 60 and not any('Created debug report' in l for l in lines[n0:]):
                time.sleep(1)
            after = sorted(set(glob.glob(os.path.join(D, 'debug', 'profiling', '*.zip'))) - before)
            if after:
                z = zipfile.ZipFile(after[-1])
                txt = z.read([n for n in z.namelist() if n.endswith('profiling.txt')][0]).decode('utf-8', 'replace')
                span = int(re.search(r'Time span: (\d+) ms', txt).group(1)); ticks = int(re.search(r'Tick span: (\d+) ticks', txt).group(1))
                rows = []
                stack = []
                for l in txt.splitlines():
                    mm = re.match(r'^\[(\d+)\]\s*[| ]*(\S.*?)\((\d+)/(\d+)\)\s*-\s*([\d.]+)%/([\d.]+)%', l)
                    if mm:
                        d = int(mm.group(1)); stack = stack[:d] + [mm.group(2).strip()]
                        if d <= 6: rows.append((float(mm.group(6)), '/'.join(stack)))
                rows.sort(reverse=True)
                print('perf %s: %d ticks in %d ms' % (a.label or 'run', ticks, span))
                for pct, path in rows[:16]:
                    print('  %6.2f ms/tick  %5.1f%%  %s' % (pct / 100 * span / ticks, pct, path.replace('ServerLevel[world] ', '')))
        cmd('stop')
        try:
            p.wait(timeout=600)
        except subprocess.TimeoutExpired:
            p.kill()
        if wins:
            avg = sum(w[0] for w in wins) / len(wins)
            result = (len(ticking), len(wins), avg, sorted(w[1] for w in wins)[len(wins) // 2], max(w[2] for w in wins), max(w[3] for w in wins))
finally:
    with open(PROPS, 'w', encoding='utf-8') as f:
        f.write(saved_props)
    for f in os.listdir(os.path.join(D, 'mods')):
        os.remove(os.path.join(D, 'mods', f))
    for f in os.listdir(keep):
        shutil.move(os.path.join(keep, f), os.path.join(D, 'mods', f))

if a.log:
    open(a.log, 'w', encoding='utf-8').write('\n'.join(lines))
if result:
    print('%s: %d ticking types x %d a chunk, %d windows, mspt avg %.2f, p50 %.2f, p95 %.2f, p99 %.2f' % ((a.label or 'run', result[0], a.per) + result[1:]))
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'run', 'be_dense.csv'), 'a') as f:
        f.write('%s,%d,%d,%d,%.2f,%.2f,%.2f,%.2f\n' % ((a.label or 'run', result[0], a.per) + result[1:]))
else:
    print('no result; last lines:\n' + '\n'.join(l[:200] for l in lines[-25:]))
for l in lines:
    if 'server profile' in l or 'tick profile' in l:
        print(l[l.find('profile'):][:3000])
cens = [l for l in lines if '[sleep/census]' in l]
if cens:
    print(cens[-1][cens[-1].index('[sleep/census]'):])
errs = [l for l in lines if '/ERROR' in l or 'Exception' in l]
print('%d error / exception lines' % len(errs))
for l in errs[:8]:
    print(l[:220])
