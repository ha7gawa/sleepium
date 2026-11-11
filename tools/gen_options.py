"""The settings screen's catalogue: every engine flag of defaults.txt (plus the experimental ones below) with its group,
a one-line description taken from its class's javadoc, and the flag that really switches it when a plugin gates it
by another (OptMixinPlugin: "if (simple.equals("A") ...) on = ... lab.opt.X"). Writes
src/main/resources/assets/sleepium/options.tsv:
    key <TAB> group <TAB> default (true/false) <TAB> description <TAB> part of (flags, comma-separated; empty: its own)
Run after adding a flag:  python tools/gen_options.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'main', 'java')
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'sleepium', 'options.tsv')

# first matching rule wins: (group, name regex)
GROUPS = [
    ('lighting', r'^(Light|ShaderLight|ClientLight)'),
    ('chunks', r'^(Chunk(Multi|Section|Shader|Upload)|Section(Layer|Lists|ModCount)|BlockMesh|VertexSort|SamplerUnit|Uniform|ShaderSampler|Frame|RenderCache|RenderCall|OverworldFog|AmbientParticle|ItemFrame|Texture|ParallelReload|ClientBiome|ClientChunkCacheBiome|GpuAnim)'),
    ('entities_render', r'^(EntityBatch|ItemGpu|QuadGpu|Part|ParallelParticles|ModelPart|PoseStack|EntityCull|EntityLight|BlockEntityCull|Vec3FromRgb)'),
    ('animation', r'^(Geo|Molang|Keyframe|AnimStream)'),
    ('threads', r'^(MainReactor|Reactor|ParallelEntities|ParallelEntityTicks|LabPools|PersistentPools|MailboxBatch|NeoForgeBus|RenderPriority)'),
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
    'QuadGpuMixin': "Baked models that block entities and other renderers write every frame (machines' moving parts: Immersive Engineering's crushers, turrets...) drawn from GPU memory: a pose per part instead of its vertices, the same picture. About 1.7-2.8x fps with hundreds of working machines.",
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
    'ParticleGpuMixin': 'Particles drawn from compact records on the GPU instead of their vertices, with their light kept per block. Particle-heavy scenes (hundreds of working machines) about 2x fps; on an Intel N100 1.5 -> 53 fps.',
    'ParallelParticlesMixin': "Particles ticked on Sleepium's worker threads when their tick proves to touch only the particle itself; the rest tick as before.",
    'ParticleShapeMemoMixin': "Particles' block-shape questions answered once per tick and block instead of once per particle (with parallel particles).",
    'ParticleLightMemoMixin': "A particle's light read once per block position per frame (with the particle records).",
    'FireCheckLoopMixin': "Every entity's move checks the blocks around it for fire and lava without building a stream of positions: the same blocks, the same answer.",
    'FluidScanDryMixin': 'Entities skip the per-block fluid lookup when every chunk section around them holds no fluid at all: the same answer, no lookups.',
    'ParallelEntityTicksMixin': "Dropped items and other non-living entities tick on Sleepium's worker threads in long runs, each exactly as vanilla ticks it; their moves in the entity storage are made in order after the run. A pile of 2 500 items: client tick 13.1 -> 11.5 ms; on an Intel N100 42.6 -> 60.9 fps.",
    'ObservedModSleep': "Machines of mods whose tick the analysis cannot follow (Industrial Foregoing, Functional Storage, PneumaticCraft...) sleep while Sleepium sees them idle and wake on any change it observes: idle machines cost less. Checked exact on the mods we test; if a machine of some mod stops or misbehaves, turn this off and please tell us which one.",
    'GpuAnim': "Animated textures (water, lava, fire, mods' animated blocks) change frames by a GPU copy instead of an upload from the CPU: less work on the render thread, the same picture.",
}

# GeoMixinPlugin's gates (a different shape from OptMixinPlugin's)
GEO_PART_OF = {'GeoModelWorkerMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'], 'MolangActorMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'],
               'MolangVariableMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'], 'KeyframeJournalMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'],
               'GeoTextureDimsMixin': ['GeoParallelMixin', 'ParallelEntitiesMixin'], 'GeoCubesMixin': ['GeoPartsMixin', 'PartBatchMixin']}

# off by default, shown in their groups (key, group, description)
EXPERIMENTAL = [
    ('lab.voxel.stream', 'chunks', 'GPU terrain: chunk sections meshed on the graphics card from the blocks as the server sends them, far terrain as coarser cells (level of detail beyond 6 chunks, coarser again beyond 10). Less CPU per frame and fewer triangles: on an Intel N100 a flight 88 -> 166 fps. Near terrain looks the same; far terrain is simplified. Blocks whose look mods compute from the world are drawn the vanilla way. Stands aside (vanilla terrain) when another mod replaces the chunk renderer: Sodium, Embeddium and the shader mods on them.'),
    ('lab.voxel.stream.lodNear', 'chunks', 'GPU terrain: level of detail closer (from 4 chunks, coarser from 8) - for weak graphics; the middle distance looks coarser.', 'lab.voxel.stream'),
    ('lab.voxel.stream.cone', 'chunks', 'GPU terrain: terrain toward the screen edges and behind goes coarse sooner. A few more fps; after a fast turn detail fills in over a moment.', 'lab.voxel.stream'),
    ('lab.chunkmd.occlusion', 'chunks', 'GPU occlusion culling of chunk sections (two phases, exact). Pays off where most of the loaded terrain is hidden.'),
]

ANNOTATIONS = r'(?:@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*'


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
    rows = []
    for line in open(os.path.join(ROOT, 'defaults.txt'), encoding='utf-8'):
        line = line.strip()
        if not line.startswith('-D') or '=' not in line:
            continue
        key, value = line[2:].split('=', 1)
        name = key.rsplit('.', 1)[-1]
        if name in TEST_TOOLS or name in FRAMEWORK_ONLY or name in META:
            continue
        group = next(g for g, rx in GROUPS if re.search(rx, name))
        desc = DESCRIPTIONS.get(name) or javadoc_of(name) or javadoc_of(name + 'Mixin')
        rows.append((key, group, value, desc, ','.join('lab.opt.' + f for f in part_of(name, rules))))
    for key, group, desc, *part in EXPERIMENTAL:   # (key, group, description[, the flag it is part of])
        rows.append((key, group, 'false', desc, part[0] if part else ''))
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


if __name__ == '__main__':
    sys.exit(main())
