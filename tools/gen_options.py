"""The settings screen's catalogue: every engine flag of defaults.txt (plus the experimental ones below) with its group,
a one-line description taken from its class's javadoc, and the flag that really switches it when a plugin gates it
by another (OptMixinPlugin: "if (simple.equals("A") ...) on = ... lab.opt.X"). Writes
src/main/resources/assets/sleepium/options.tsv:
    key <TAB> group <TAB> default (true/false) <TAB> description <TAB> part of (flags, comma-separated; empty: its own)
Run after adding a flag:  python tools/gen_options.py

1.20.1: a flag whose class is still left out of the compile (port-pending.txt) is not listed - the screen shows what
this build can switch; it comes back when its part is ported and this runs again.
"""
import fnmatch
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'main', 'java')
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'sleepium', 'options.tsv')

# first matching rule wins: (group, name regex)
GROUPS = [
    ('lighting', r'^(Light|ShaderLight|ClientLight)'),
    ('chunks', r'^(Chunk(Multi|Section|Shader|Upload)|Section(Layer|Lists|ModCount)|BlockMesh|VertexSort|SamplerUnit|Uniform|ShaderSampler|Frame|RenderCache|RenderCall|OverworldFog|AmbientParticle|ItemFrame|Texture|ParallelReload|ClientBiome|ClientChunkCacheBiome)'),
    ('entities_render', r'^(EntityBatch|ItemGpu|QuadGpu|Part|ModelPart|PoseStack|EntityCull|EntityLight|BlockEntityCull|Vec3FromRgb)'),
    ('animation', r'^(Geo|Molang|Keyframe|AnimStream)'),
    ('threads', r'^(MainReactor|Reactor|ParallelEntities|LabPools|PersistentPools|MailboxBatch|ForgeBus|RenderPriority)'),
    ('network', r'^Net'),
    ('worldgen', r'^(Worldgen|GenScout|CubicSampler|ParallelWorldgen|StructurePlace|OffThreadEntity)'),
    ('saving', r'^(AsyncChunkSave|ChunkIo|ChunkCache|UnloadRecheck|AllocTrim|PoiColumn|SectionStorage)'),
    ('ticks', r'.'),   # the rest: server ticks, mobs, collisions, redstone, fluids, events
]

# test tools in defaults.txt that the jar leaves out (build.gradle's processResources filter)
TEST_TOOLS = {'NetLagMixin'}
# flags of the animation framework built on the engine (its mixins are not in this jar): nothing to switch here
FRAMEWORK_ONLY = {'GeoAnimMixin', 'GeoAnimControllerMixin', 'GeoAnimProcessorMixin', 'GeoMarkersMixin', 'KeyframeInternMixin'}
# meta flags: expanded into the individual ones at start (shown one by one instead)
META = {'universal'}

# flags that switch several mixins (no class of their own), described here
DESCRIPTIONS = {
    'QuadGpuMixin': "Baked models that block entities and other renderers write every frame (machines' moving parts: Immersive Engineering's crushers, turrets...) drawn from GPU memory: a pose per part instead of its vertices, the same picture. About 1.5-1.7x fps with hundreds of working machines.",
    'ItemGpuMixin': 'Items in the world (on the ground, in frames, in hands) drawn from GPU memory: a pose per item each frame instead of its vertices, no sorting of their quads. About 2x fps with a thousand dropped items.',
    'FrameGarbageMixin': 'The per-frame garbage fixes in one switch: fog colour, frustum and light checks of entities and block entities, item frames, memoized lookups - without allocating every frame.',
    'GeoParallelMixin': 'GeckoLib models rendered on Sleepium\'s render workers (with ParallelEntities): their model and Molang work off the render thread.',
    'GeoPartsMixin': 'GeckoLib bones drawn from GPU memory (with PartBatch): only bone poses are uploaded per frame, not their vertices.',
    'NetBulk': 'Large server data sent once by content hash: cached on the client\'s disk, resumed, deltas only, paced to the connection.',
    'NetCoalesce': 'A flush\'s packets sent as one frame of a per-connection compressed stream (clients with Sleepium only): 45-52 % fewer bytes on movement traffic.',
    'OffThreadEntity': 'Entities that a mod adds to the world from another thread (a mob spawn handler during world generation) go where they belong: into the chunk being generated, or onto the server thread. Mods that add entities the normal way are not affected.',
    'SleepMixin': 'Sleeping block entities: an idle block entity\'s tick is skipped (proven from its bytecode and observed) and woken at the exact tick vanilla would act.',
    'WorldgenAlloc': 'World generation without per-call allocation: noise, surface rules, ores, density functions and random sources reuse their buffers.',
    'WorldgenFeatureAlloc': 'Feature placement and joints without per-call allocation.',
    'WorldgenShare': 'World generation shares work between neighbouring chunks: biomes, coordinates, region noise and surface slices computed once.',
    'WorldgenSkip': 'World generation skips work that cannot change the result: aquifer cells, fills and the beardifier where nothing is near.',
}

# GeoMixinPlugin's gates (a different shape from OptMixinPlugin's)
GEO_PART_OF = {'GeoModelWorkerMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'], 'MolangActorMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'],
               'MolangVariableMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'], 'KeyframeJournalMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'],
               'GeoTextureDimsMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'], 'GeoCubesMixin': ['GeoPartsMixin', 'PartBatchMixin']}

# off by default, shown in their groups (key, group, description); 1.20.1: lab.chunkmd.occlusion waits for chunk multi-draw
EXPERIMENTAL = [
    ('lab.sleep.ObservedModSleep', 'ticks', 'Machines of mods whose tick the analysis cannot follow (Industrial Foregoing, Functional Storage, PneumaticCraft...) sleep on what Sleepium observes, as before 1.1.8: idle machines cost less, but their sleep is not proven exact.'),
]

ANNOTATIONS = r'(?:@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*'


def pending_globs():
    globs = []
    for line in open(os.path.join(ROOT, 'port-pending.txt'), encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#'):
            globs.append(line)
    return globs


def class_path(name):
    """the class's source path under src/main/java (forward slashes), or None"""
    for d, _, files in os.walk(SRC):
        if name + '.java' in files:
            return os.path.relpath(os.path.join(d, name + '.java'), SRC).replace(os.sep, '/')
    return None


# flags with no class of their own whose whole part is pending (GeoMixinPlugin's switches)
PENDING_META = {'GeoParallelMixin': 'dev/lull/geomixin/**', 'GeoPartsMixin': 'dev/lull/geomixin/**'}


def pending(name, globs):
    """whether the flag's class (Name or NameMixin) is left out of this build's compile"""
    if PENDING_META.get(name) in globs:
        return True
    for n in (name, name + 'Mixin'):
        path = class_path(n)
        if path is not None:
            return any(fnmatch.fnmatchcase(path, g.replace('**', '*')) for g in globs)
    return False


def javadoc_of(name):
    for d, _, files in os.walk(SRC):
        if name + '.java' in files:
            text = open(os.path.join(d, name + '.java'), encoding='utf-8').read()
            m = re.search(r'/\*\*((?:(?!\*/).)*)\*/\s*' + ANNOTATIONS + r'(?:public\s+)?(?:abstract\s+|final\s+)*(?:class|interface|enum)\s+' + name + r'\b',
                          text, re.S)
            if not m:
                return ''
            doc = re.sub(r'^\s*\*\s?', '', m.group(1), flags=re.M)
            doc = re.sub(r'\{@(?:code|link|linkplain)\s+([^}]*)\}', r'\1', doc)
            doc = re.sub(r'<[^>]+>', ' ', doc)
            doc = ' '.join(doc.split())
            s = re.match(r'(.+?[.!?])(\s|$)', doc)
            line = s.group(1) if s else doc
            return line[:300]
    return ''


def part_of_rules():
    """OptMixinPlugin's gates: [(names equal, name prefixes, flags)] in order (a later rule wins)"""
    text = open(os.path.join(SRC, 'dev', 'lull', 'opt', 'OptMixinPlugin.java'), encoding='utf-8').read()
    rules = []
    for m in re.finditer(r'if \(((?:[^;{])*?)\)\s*(?://[^\n]*)?\s*on\s*\|?=\s*([^;]*);', text, re.S):
        cond, expr = m.group(1), m.group(2)
        eq = re.findall(r'simple\.equals\("(\w+)"\)', cond)
        pre = re.findall(r'simple\.startsWith\("(\w+)"\)', cond)
        flags = [f for f in re.findall(r'getBoolean\("lab\.opt\.(\w+)"\)', expr) if f != 'all']
        if (eq or pre) and flags:
            rules.append((eq, pre, flags))
    return rules


def part_of(name, rules):
    if name in GEO_PART_OF:
        return GEO_PART_OF[name]
    found = None
    for eq, pre, flags in rules:
        if name in eq or any(name.startswith(p) for p in pre):
            found = flags
    if not found or name in found:
        return []
    return found


def main():
    rules = part_of_rules()
    globs = pending_globs()
    rows = []
    skipped = []
    for line in open(os.path.join(ROOT, 'defaults.txt'), encoding='utf-8'):
        line = line.strip()
        if not line.startswith('-D') or '=' not in line:
            continue
        key, value = line[2:].split('=', 1)
        name = key.rsplit('.', 1)[-1]
        if name in TEST_TOOLS or name in FRAMEWORK_ONLY or name in META:
            continue
        if pending(name, globs):
            skipped.append(name)
            continue
        group = next(g for g, rx in GROUPS if re.search(rx, name))
        desc = DESCRIPTIONS.get(name) or javadoc_of(name) or javadoc_of(name + 'Mixin')
        rows.append((key, group, value, desc, ','.join('lab.opt.' + f for f in part_of(name, rules))))
    for key, group, desc in EXPERIMENTAL:
        rows.append((key, group, 'false', desc, ''))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        for r in rows:
            f.write('\t'.join(c.replace('\t', ' ').replace('\n', ' ') for c in r) + '\n')
    counts = {}
    for r in rows:
        counts[r[1]] = counts.get(r[1], 0) + 1
    missing = [r[0] for r in rows if not r[3]]
    governed = sum(1 for r in rows if r[4])
    print(f'{len(rows)} options -> {OUT}; groups {counts}; switched by another flag: {governed}; without a description: {len(missing)} {missing}')
    print(f'left out (pending on 1.20.1): {len(skipped)} {skipped}')


if __name__ == '__main__':
    sys.exit(main())
