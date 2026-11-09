"""Client speed: a self-driving dev client of this build (./gradlew runClient) on one scene, the engine's optimisations
on (its default flags) or off (the same build, no flags: vanilla behaviour).

    python tools/client_bench.py on client_entities [--label L] [--settle 40] [--secs 30] [--heap 3G] [--flags "..."]
    python tools/client_bench.py on - --world "path/to/save" [--tp "x y z yaw pitch"]   (a saved world, copied to run/saves)

ClientAuto (-Dsleep.clientAuto) makes a singleplayer world (fixed seed), waits --settle seconds for the terrain, builds
the scene, measures --secs seconds at a fixed position (vsync off, unlimited fps) and quits. Window 1280x720, heap
pre-touched. Prints the result line and appends it to run/client-bench.csv:
label,scene,fps,frame p50 ms,frame p99 ms,KiB per frame,process cores
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLEW = os.path.join(ROOT, 'gradlew.bat' if os.name == 'nt' else 'gradlew')

ap = argparse.ArgumentParser()
ap.add_argument('mode', choices=('on', 'off'))
ap.add_argument('scene')
ap.add_argument('--label', default='')
ap.add_argument('--settle', type=int, default=40)
ap.add_argument('--secs', type=int, default=30)
ap.add_argument('--heap', default='3G')
ap.add_argument('--flags', default='')
ap.add_argument('--world', help='a saved world to open (copied to run/saves) instead of a scene in a fresh world')
ap.add_argument('--tp', help='where to look from in the saved world: "x y z yaw pitch"')
ap.add_argument('--mods', help='a folder of released mods put on the dev run (build.gradle -PtestMods: remapped), e.g. F:/compat/jars-1.20.1')
a = ap.parse_args()
name = os.path.basename(os.path.normpath(a.world)).replace(' ', '_') if a.world else None   # JVM flags split on spaces
label = a.label or '%s-%s' % ((name or a.scene).replace(' ', '_'), a.mode)
# as in a player's game: vanilla's development-only paths off (a dev client logs warnings players never see - 11-09: a
# full sound pool logged 472 k lines in 15 s, 130 fps instead of 246); -Dsleep.clientAuto.asPlayers=false to keep them
jvm = ['-Xms' + a.heap, '-Xmx' + a.heap, '-XX:+AlwaysPreTouch', '-Dsleep.clientAuto=' + label, '-Dsleep.clientAuto.asPlayers=true',
       '-Dsleep.clientAuto.steady=true', '-Dsleep.clientAuto.steadySeconds=%d' % a.secs, '-Dsleep.clientAuto.settle=%d' % a.settle]
if a.world:
    saves = os.path.join(ROOT, 'run', 'saves')
    shutil.rmtree(os.path.join(saves, name), ignore_errors=True)
    shutil.copytree(a.world, os.path.join(saves, name), ignore=shutil.ignore_patterns('session.lock'))
    jvm.append('-Dsleep.clientAuto.world=' + name)
    if a.tp:
        jvm.append('-Dsleep.clientAuto.tp=' + a.tp.replace(' ', ','))
else:
    jvm.append('-Dsleep.clientAuto.scene=' + a.scene)
jvm += (['-Dlab.opt.defaults=true'] if a.mode == 'on' else []) + a.flags.split()
p = subprocess.run([GRADLEW, 'runClient', '--console=plain', '-q', '-PrunJvmArgs=' + ' '.join(jvm), '-PrunArgs=--width 1280 --height 720'] + (['-PtestMods=' + a.mods] if a.mods else []),
                   cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
out = p.stdout + p.stderr
with open(os.path.join(ROOT, 'run', 'client-bench-%s.out' % label), 'w', encoding='utf-8') as f:   # the run's whole console
    f.write(out)
m = re.search(r"sleepclient '%s': (\d+) fps, frame p50 ([\d.]+) / p99 ([\d.]+) ms, (\d+) KiB/frame, CPU ([\d.]+) cores" % re.escape(label), out)
if not m:
    print('no result; the end of the log:\n' + '\n'.join(out.splitlines()[-30:]))
    sys.exit(1)
row = ','.join([label, name or a.scene] + list(m.groups()))
print(row)
with open(os.path.join(ROOT, 'run', 'client-bench.csv'), 'a') as f:
    f.write(row + '\n')
