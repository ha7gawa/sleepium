"""Dense entities of real mods on the production NeoForge server (11-18, the entity counterpart of be_dense.py): what
the mods' entities cost the server with and without this build - a check that the entity sleep does not cost more than
it saves for entity classes it has never seen.

    python tools/ent_dense.py --jar build/libs/sleepium-....jar --mods F:/compat/be121/mods --types ids.txt [--per 100]
                              [--nojar] [--warm 30] [--secs 30] [--label x]

Each entity type gets a glass pen in a 3 x 3 chunk block of its own (pens 4 chunks apart) and --per of it summoned in
the pen; mob spawning off. After --warm seconds a /tick query every 5 s for --secs seconds: mspt (mean of the windows'
averages, P50/P95/P99). The server's mods, world and properties are put back afterwards.
"""
import argparse, os, random, re, shutil, subprocess, threading, time

ap = argparse.ArgumentParser()
ap.add_argument('--jar', required=True)
ap.add_argument('--mods', required=True)
ap.add_argument('--types', required=True, help='a file of entity type ids, space separated')
ap.add_argument('--per', type=int, default=100)
ap.add_argument('--warm', type=int, default=30)
ap.add_argument('--secs', type=int, default=30)
ap.add_argument('--server', default=r'F:\release-test\server')
ap.add_argument('--java', default=r'C:\Users\ha7gawa\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe')
ap.add_argument('--neoforge', default='21.1.251')
ap.add_argument('--flags', default='')
ap.add_argument('--label', default='')
ap.add_argument('--heap', default='6G')
ap.add_argument('--nojar', action='store_true', help='without our jar (vanilla behaviour)')
a = ap.parse_args()

D = a.server
PROPS = os.path.join(D, 'server.properties')
FLAT = ('{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
        '{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structure_overrides":[],"lakes":false,"features":false}')
props = {'online-mode': 'false', 'level-type': 'minecraft\\:flat', 'level-seed': '20260924', 'view-distance': '6',
         'level-name': 'entdense-world', 'simulation-distance': '6', 'server-port': '25605', 'spawn-protection': '0',
         'max-tick-time': '-1', 'generator-settings': FLAT, 'difficulty': 'easy'}
AVG = re.compile(r'Average time per tick: ([\d.,]+) ?ms')
PCT = re.compile(r'P50: ([\d.,]+) ?ms,? P95: ([\d.,]+) ?ms,? P99: ([\d.,]+) ?ms')


def num(s):
    return float(s.replace(',', '.'))


ids = open(a.types).read().split()
print('%d entity types' % len(ids), flush=True)
saved_props = open(PROPS, encoding='utf-8').read()
keep = os.path.join(D, 'saved-mods-entdense')
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
    p = subprocess.Popen([a.java, '-Xmx' + a.heap] + a.flags.split() + ['@user_jvm_args.txt',
                          '@libraries/net/neoforged/neoforge/%s/win_args.txt' % a.neoforge, 'nogui'],
                         cwd=D, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
                         errors='replace', bufsize=1)
    threading.Thread(target=lambda: [lines.append(l.rstrip('\n')) for l in p.stdout], daemon=True).start()

    def wait(rx, t):
        t0 = time.time()
        while time.time() - t0 < t:
            if any(re.search(rx, l) for l in lines):
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
        cmd('gamerule doMobSpawning false')
        rnd = random.Random(7)
        for k, e in enumerate(ids):
            x0 = 1000 + k * 64   # a pen every 4 chunks, its 3 x 3 chunks force-loaded
            cmd('forceload add %d -16 %d 31' % (x0 - 16, x0 + 31))
            cmd('fill %d -60 -1 %d -57 16 glass hollow' % (x0 - 1, x0 + 16))
            for i in range(a.per):
                cmd('summon %s %.1f -59 %.1f' % (e, x0 + 1 + rnd.random() * 14, 1 + rnd.random() * 14))
        time.sleep(a.warm)
        wins = []
        t0 = time.time()
        while time.time() - t0 < a.secs:
            n0 = len(lines)
            cmd('tick query')
            time.sleep(5)
            seg = '\n'.join(lines[n0:])
            m, q = AVG.search(seg), PCT.search(seg)
            if m and q:
                wins.append((num(m.group(1)), num(q.group(1)), num(q.group(2)), num(q.group(3))))
        cmd('stop')
        try:
            p.wait(timeout=300)
        except subprocess.TimeoutExpired:
            p.kill()
        if wins:
            avg = sum(w[0] for w in wins) / len(wins)
            result = (len(ids), len(wins), avg, sorted(w[1] for w in wins)[len(wins) // 2], max(w[2] for w in wins), max(w[3] for w in wins))
finally:
    with open(PROPS, 'w', encoding='utf-8') as f:
        f.write(saved_props)
    for f in os.listdir(os.path.join(D, 'mods')):
        os.remove(os.path.join(D, 'mods', f))
    for f in os.listdir(keep):
        shutil.move(os.path.join(keep, f), os.path.join(D, 'mods', f))

if result:
    print('%s: %d entity types x %d, %d windows, mspt avg %.2f, p50 %.2f, p95 %.2f, p99 %.2f' % ((a.label or 'run', result[0], a.per) + result[1:]))
else:
    print('no result; last lines:\n' + '\n'.join(l[:200] for l in lines[-25:]))
errs = [l for l in lines if '/ERROR' in l or 'Exception' in l]
print('%d error / exception lines' % len(errs))
for l in errs[:8]:
    print(l[:220])
