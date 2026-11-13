"""A dedicated server of this build (./gradlew runServer) driven through its console, for the test tools.

    with Server(jvm_args, props) as s:
        s.wait_for(r'Done \\(')
        s.cmd('tick freeze')
        line = s.ask('time query gametime', r'The time is (\\d+)')

MC_PORT gives a second server (another copy of the project, its own run folder) its own port. MC_MODS=<folder> puts
released mods on the server (build.gradle -PtestMods: remapped), for every tool that starts one (compatibility runs).
"""
import os
import re
import shutil
import subprocess
import threading
import time
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN = os.path.join(ROOT, 'run')
GRADLEW = os.path.join(ROOT, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
SCENES = os.path.join(ROOT, 'src', 'scenes', 'resources', 'data', 'sleep', 'functions')

PROPS = {
    'level-type': 'minecraft\\:flat', 'level-seed': '20260924', 'online-mode': 'false', 'spawn-protection': '0',
    'view-distance': '8', 'simulation-distance': '8', 'sync-chunk-writes': 'false', 'max-tick-time': '-1',
    'pause-when-empty-seconds': '-1', 'spawn-monsters': 'false',
    # vanilla's classic flat layers (grass at y -61) with no structures: without settings 1.20.1 falls back to the
    # preset with villages, whose villagers - made on worldgen threads while a scene runs - draw from the static
    # random every brain sensor is timed from (Sensor.RANDOM), so a scene's villagers scanned at per-run times
    'generator-settings': '{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
                          '{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structure_overrides":[],'
                          '"lakes":false,"features":false}',
    'server-port': os.environ.get('MC_PORT', '25565'),   # a second server (another copy) beside a running suite
}


def scene_area(scene):
    """the scene's area (x1, z1, x2, z2) from its first forceload line"""
    with open(os.path.join(SCENES, 'lag_%s.mcfunction' % scene), encoding='utf-8') as f:
        for line in f:
            if line.startswith('forceload add'):
                return tuple(int(v) for v in line.split()[2:6])
    raise SystemExit('scene %s has no forceload line' % scene)


# not errors of the engine: vanilla's flat world without generator settings, the scenes for the Create mod without it
KNOWN = re.compile(r"No key layers|lag_create|Unknown block type 'create:|client_cursedfate|'cursedfate:|sleep:cf_vfx")


class Server:
    def __init__(self, jvm_args, props=None, log=None, world=None):
        self.tag = uuid.uuid4().hex[:12]   # marks this server's JVM (see _kill_jvm)
        self.jvm_args = list(jvm_args) + ['-Dsleep.test.run=' + self.tag]
        self.world = world   # a saved world to start from (copied to run/world) instead of a fresh flat one
        self.props = dict(PROPS, **(props or {}))
        self.log = log
        self.lines = []
        self.lock = threading.Lock()

    def __enter__(self):
        os.makedirs(RUN, exist_ok=True)
        shutil.rmtree(os.path.join(RUN, 'world'), ignore_errors=True)
        if self.world:
            shutil.copytree(self.world, os.path.join(RUN, 'world'), ignore=shutil.ignore_patterns('session.lock'))
        with open(os.path.join(RUN, 'eula.txt'), 'w') as f:
            f.write('eula=true\n')
        with open(os.path.join(RUN, 'server.properties'), 'w') as f:
            f.writelines('%s=%s\n' % kv for kv in self.props.items())
        extra = os.environ.get('MC_JVM_EXTRA', '').split()   # (debug: e.g. -Xlog:exceptions=info:file=...)
        args = [GRADLEW, 'runServer', '--console=plain', '-q', '-PrunJvmArgs=' + ' '.join(list(self.jvm_args) + extra)]
        if os.environ.get('MC_MODS'):
            args.append('-PtestMods=' + os.environ['MC_MODS'])
        self.p = subprocess.Popen(args, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding='utf-8', errors='replace', bufsize=1)
        self.logf = open(self.log, 'w', encoding='utf-8') if self.log else None
        threading.Thread(target=self._read, daemon=True).start()
        return self

    def _read(self):
        for line in self.p.stdout:
            with self.lock:
                self.lines.append(line.rstrip('\n'))
            if self.logf:
                self.logf.write(line)

    def cmd(self, c):
        self.p.stdin.write(c + '\n')
        self.p.stdin.flush()

    def wait_for(self, pattern, timeout=900, start=0):
        rx = re.compile(pattern)
        t0 = time.time()
        while time.time() - t0 < timeout:
            with self.lock:
                for line in self.lines[start:]:
                    m = rx.search(line)
                    if m:
                        return m
            if self.p.poll() is not None:
                raise SystemExit('the server stopped before "%s"' % pattern)
            time.sleep(0.2)
        raise SystemExit('timed out waiting for "%s"' % pattern)

    def ask(self, c, pattern, timeout=60):
        with self.lock:
            start = len(self.lines)
        self.cmd(c)
        return self.wait_for(pattern, timeout, start)

    def errors(self):
        with self.lock:
            return [l for l in self.lines if re.search(r'Exception|/ERROR\]|/FATAL\]', l) and not KNOWN.search(l)]

    def _kill_jvm(self):
        """the server's JVM itself: gradle runs it under its daemon, so killing gradlew alone leaves it running (11-11:
        three servers whose stop hung - a mod's saved data throwing - stayed behind, 1 GB each)"""
        if os.name != 'nt':
            return
        ps = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like "
              "'*sleep.test.run=%s*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" % self.tag)
        try:
            subprocess.run(['powershell', '-NoProfile', '-Command', ps], capture_output=True, timeout=60)
        except Exception:
            pass

    def __exit__(self, *a):
        try:
            if self.p.poll() is None:
                self.cmd('stop')
                self.p.wait(120)
        except Exception:
            self.p.kill()
            self._kill_jvm()
        if self.logf:
            self.logf.close()
