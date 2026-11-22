# 84 - Light engine: measured, designed, first exact pieces (11-21, branch lab-light-1.21.1)

Plan 1.3.4 (notes/83): "light: region / section propagation that waits for neighbours (own parallel light engine)",
measure the barrier first. Done here on the Xeon .103 (4 cores / 8 threads, 4 Worker-Main threads, all 1.3.3
generation defaults on; NeoForge 1.21.1 prod server C:\mcbench\neo121, genbench 32 x 32 = 1024 chunks, rounds r1 / r2
after a 16 x 16 warm-up) and the .103 production client (fresh world, ClientAuto's two teleports).

## Tools added (measurement only, off unless asked)
- `-Dlab.light.probe=true` (dev/lull/LightProbe + mixins LightProbe*): per light batch (ThreadedLevelLightEngine.runUpdate)
  pre / BFS / post time and the queue at its start, the actor's busy time against wall time; per engine (block, sky):
  runLightUpdates, checkNode count, decrease pass (time, entries), increase pass (time, entries), publish
  (markNewInconsistencies + swapSectionMap), propagateLightSources, setLightEnabled; calls (initializeLight, lightChunk,
  checkBlock, updateSectionStatus, waitForPendingTasks) and the latency of their futures (call -> done). Printed after
  each genbench area and after each ClientAuto teleport ("server light idle at" = when the actor had nothing left).
  With -Dlab.gen.timeline the timeline rows get "light NN% q N" (busy share since the last row, tasks queued).
- `-Dlab.light.fixcheck=true` (dev/lull/LightCheck): when a genbench area is FULL, waits until the light actor is idle
  (10 ticks), lights all the area's chunks again from scratch in a private vanilla LevelLightEngine (all sections'
  status, light enabled, every chunk's sources, one propagation) and compares the DataLayers of the chunks >= 2 inside
  the area (their light depends only on area chunks) nibble by nibble with the level's; also writes per-chunk light
  hashes (genlight-<label>.txt). `LightCheck.ALT` is the hook for comparing another engine instead of vanilla's.
- scratchpad light/light_jfr.py: JFR samples under ThreadedLevelLightEngine.runUpdate (streamed jfr print).

## Measurements
### Generation (genbench 32 x 32, .103, base = 1.3.3 night defaults)
| run | chunks/s r1 / r2 | light actor busy | per FULL chunk | batches (tasks a batch) | lightChunk latency mean | initializeLight latency mean |
|---|---|---|---|---|---|---|
| lp1 (probe, fixcheck) | 81.6 / 93.9 | 25 % / 22 % | 3.12 / 2.31 ms | 4445 / 5986 (2) | 1223 / 1163 ms | 2429 / 2213 ms |
| lp2 (probe, JFR) | 75.9 / 99.5 | | | | | |
| lb3 (probe, fixcheck) | 79.9 / 106.0 | 23 % / 24 % | 2.83 / 2.25 ms | 4254 / 5267 (2) | 1142 / 718 ms | 2793 / 1481 ms |
| lb4 (probe) | 87.7 / 114.7 | | | | 1199 / 135 ms | 2220 / 287 ms |

Where light time goes (lp1 r1 / r2, per FULL chunk): sky increase pass 1.78 / 1.23 ms (6.0 / 4.8 M entries a round,
~4700 a chunk), block increase 0.64 / 0.32 ms (1.6 / 0.8 M entries), sources (propagateLightSources) block 0.22 / 0.20,
sky 0.13 / 0.11, publish 0.18 / 0.26, decrease passes ~0.01 (checkNode 2 a chunk: features writing into lit neighbours,
fluids), POST 0.05. JFR (lp2, 595 samples under runUpdate = 6.7 % of all samples): SkyLightEngine.propagateIncrease
54 % incl., BlockLightEngine.propagateIncrease 14 %, findBlockLightSources (palette scan) 8.6 %, sky sources 5.7 %,
swapSectionMap 4.4 %; inside them the section hash lookups DataLayerStorageMap.getLayer 11.6 % (self 10.6 %, every
getStoredLevel / setStoredLevel / storingLightForSection asks the map), block states (getState / PalettedContainer.get)
13 %, SectionPos.aroundAndAtBlockPos (affected-section set) 5 %.

**The surprise: the light actor is idle three quarters of the time, yet a chunk waits ~1.2 s for its LIGHT step and
~2.2 s for INITIALIZE_LIGHT.** The queue in front of the actor (lightTasks) is ~2 at every batch start: the tasks wait
in the ChunkTaskPriorityQueueSorter, which forwards one chunk's tasks at a time to the light mailbox and waits for them
to be taken before it pops the next chunk - two or three hops over the busy worker pool per chunk. The timeline (lb3 r2)
shows it: at 7.5 s 766 chunks sit at initialize_light (waiting for their LIGHT) and the actor then drains them at ~95 %
for 2.1 s (7.5-9.6 s) - the "serial light tail" of notes/82 is this queue plus the serial compute; in lp1 r2 the last
0.9 s ran with 1 of 4 workers busy (the light actor), 8 % of the area's time.

### Play (fresh world, production client, two teleports 2000 / -2000)
Teleport 1: server FULL at 11.5 s, light idle at 11.5 s; teleport 2: FULL 8.1 s, light idle 9.1 s. Actor busy 17 % /
19 %; lightChunk latency mean 3.9 ms (p99 < 32 ms), initializeLight 25 / 17 ms; 850 / 650 chunks lit; checkBlock
22 k / 10 k (fluids, player). In play with a player's tickets the sorter queue stays short: light does not delay
chunks noticeably today (generation does); it matters for the batch-generation shape (pregeneration, flying, many
players).

### Exactness of vanilla's own light (the basis of every design below)
fixcheck in every run so far: 0 of 40 768 sections (block + sky) differ per 784-chunk area (r1, r2), 0 of 7 488 in
the warm-up - in lp1, lb3 (base), ld1, ldt1 (LightDirect, + own thread), lpv1, lpp1, lfx1 (LightPar verify / committed). **After generation the level's light equals the
from-scratch fixed point of its blocks**: the order in which chunks were lit, the batching, the sorter's priorities
do not show in the result.

## Exactness argument (what may run in which order)
Vanilla's engine per batch: PRE tasks in order (section status changes, light enabled, data queued, sources), then per
engine (block, then sky): checkNode for changed blocks, the decrease pass, the increase pass, publish; then POST tasks.
- **Increase pass: order independent** (max-plus relaxation). It only raises values; an entry (pos, level, directions)
  is processed when the stored value still equals its level; a neighbour gets level - opacity when that is higher and
  the faces do not occlude. Costs depend on blocks and on which sections store light, both fixed during the pass. Every
  final value is max over seeds of (seed level - cheapest path cost), whatever the order: a skipped entry is dominated
  by the higher entry that replaced it (same cell, every direction except the one back to a brighter cell), and the
  directions an entry leaves out point at cells at least as bright (back to the source; sky sources' neighbours that
  are sources themselves). Vanilla never exceeds that closure (raises come from relaxations; base values are below it).
- **The one exception**: SkyLightEngine.propagateFromEmptySections in increase mode writes the level into stored
  sections below unstored ones WITHOUT a max - it can lower a cell another path made brighter, and then that path's
  entry is skipped (stored != level). Order dependent. Detectable: if no such write lowered a value and every such cell
  ends at exactly the written level, no order could have lowered it (vanilla <= closure) and the result is the closure.
- **Decrease pass: order dependent in general** (it zeroes everything that could have come from the removed light and
  re-seeds from the border; which border cells it meets depends on the order). It is ~0.01 ms a chunk in generation
  (checkNode 2 a chunk) - keep it serial, in vanilla's order, before the increase pass.
- **Structure changes** (updateSectionStatus -> initializeSection -> createDataLayer copies the layer above for sky;
  setLightEnabled fills empty sky layers with 15; queued data; removals): order dependent (a section created after
  light passed copies whatever is above at that moment) - keep them serial in task order, as PRE tasks are today. The
  fixcheck shows that in generation their order does not change the final light, but nothing guarantees it in general.
- **Cross-chunk task order** (which chunk's tasks reach the actor first): vanilla's own order already varies with
  timing and the sorter's priorities, and the final light after generation does not depend on it (fixcheck). Per chunk
  the submission order must hold (PRE before POST, a chunk's checkBlock after its initializeLight).
- **Light-update packets**: the sections notified (sectionsAffectedByLightUpdates -> ChunkHolder.sectionLightChanged ->
  ClientboundLightUpdatePacket) are those around every cell written; a different relaxation order writes the same
  cells (a cell is written iff its value rises; the empty-section writes are the exception and those batches fall back)
  - same packets content (values are read when the packet is built), possibly a different packet split in time.
- The client's light engine is not touched (it applies server data and its own block changes).

## Design: an own parallel light engine (staged)
Unit of work: **the chunk column** (seeds grouped by chunk; light from a seed reaches <= 14 blocks, so a chunk's work
reads and writes only its 3 x 3 chunks). Dependencies: two chunks' work conflicts iff their 3 x 3 areas overlap
(Chebyshev distance <= 2) - a graph whose edges are "neighbour light can flow in from". Scheduling: 9 colours
(cx mod 3, cz mod 3) give disjoint areas per colour without locks; a GenGraph-style scheduler (a node per chunk's light
work, edges to overlapping nodes, run when no overlapping node runs) avoids the colour barriers later. Sections, not
chunks, would be finer but the 26-neighbour section graph costs more bookkeeping than the work (~1 ms a chunk).

Stages, each exact on its own and measurable:
1. **LightDirect** (built, below): light tasks bypass the priority sorter - straight into the light mailbox in
   submission order. Removes the per-chunk round trips; bigger batches form by themselves when the actor is busy.
2. **LightPar** (built, below): the increase pass of a batch on several threads, chunk groups in 9 colours, private
   copies of sections committed at the end; order-dependent sky writes detected -> vanilla's serial pass instead.
3. Block and sky engines of a batch in parallel (they share nothing but block reads): another up to 1.3x on the BFS
   (sky ~75 %, block ~20 % of it) - small, cheap.
4. Sources in parallel: propagateLightSources of different chunks write only their own column (sky: the column's
   layers; block: queue entries) - per-chunk PRE work into per-chunk queues, merged in task order (8-15 % of light).
5. Flat storage: the hash lookups (getLayer 11.6 % self, sectionStates, aroundAndAtBlockPos) replaced by a per-batch
   section index (the 3 x 3 x height sections of a group resolved once into an array) - helps serial and parallel.
6. Publishing: swapSectionMap per batch (LightSnapshot already makes it a delta) - per batch, not per task, once batches
   are bigger (LightDirect: 15-64 tasks a batch instead of 2).
7. Later, play: checkBlock bursts (explosions, pistons, fluids) - decreases stay serial per batch; the increase part
   parallel as in 2.

Plugging into vanilla's API: everything stays inside ThreadedLevelLightEngine / LightEngine: the same tasks, the same
storage objects (LayerLightSectionStorage's updatingSectionData / changedSections / sectionsAffectedByLightUpdates,
DataLayerStorageMap.setLayer so LightSnapshot and the layer cache see every write), the same futures, the same publish
and the same notifications for light-update packets. Mods that call LevelLightEngine (checkBlock, getLayerListener,
queueSectionData) see vanilla's API; mods that replace the engine (Starlight / ScalableLux ports) must make ours yield
(MixinYield).

## Prototype state
### LightDirect (-Dlab.opt.LightDirect, opt/LightDirectMixin)
Same tasks, straight into the light mailbox in submission order (the lambda the sorter would deliver, no reply needed).
Exact: fixcheck 0 differences in every area; carvers hashes identical in all runs (r1 3d3701ca..., r2 0880a251...,
every configuration below).

| run (interleaved order) | chunks/s r1 / r2 (mean) | actor busy | batches (tasks a batch) | lightChunk latency | initializeLight latency |
|---|---|---|---|---|---|
| lp1 base | 81.6 / 93.9 (87.8) | 25 / 22 % | 2 | 1223 / 1163 ms | 2429 / 2213 ms |
| ld1 LightDirect | 97.0 / 117.4 (107.2) | 24 / 21 % | 64 / 15 | 440 / 38 ms | 1457 / 61 ms |
| lb3 base | 79.9 / 106.0 (93.0) | 23 / 24 % | 2 | 1142 / 718 ms | 2793 / 1481 ms |
| ld2 LightDirect | 96.4 / 118.5 (107.5) | 22 / 18 % | 126 / 26 | 811 / 295 ms | 2225 / 1281 ms |
| ldt1 LightDirect + ownThread | 100.0 / 120.1 (110.0) | 31 / 27 % | 6 / 5 | 17 / 7 ms | 7 / 3 ms |
| lb4 base | 87.7 / 114.7 (101.2) | | 2 | 1199 / 135 ms | 2220 / 287 ms |
| ldt2 LightDirect + ownThread | 92.5 / 120.7 (106.6) | 29 / 25 % | 5 | 5-14 ms | 2-5 ms |
| lb5 base | 77.9 / 97.6 (87.8) | 22 / 22 % | 2 | 485-1183 ms | 1342-4106 ms |
Over the 4 base and 4 LightDirect runs (interleaved, same box, same jar): base 87.8 / 93.0 / 101.2 / 87.8 (mean 92.5)
vs LightDirect 107.2 / 107.5 / 110.0 / 106.6 (mean 107.8): **+16.5 % chunks/s**, r2 alone 103.1 -> 119.2 (+16 %).
The spread is smaller with LightDirect (106.6-110.0 vs 87.8-101.2): the light queue was a source of run-to-run noise. The timeline shows the shape change: base lb3 r2 has 766 chunks waiting at initialize_light at 7.5 s and a
2.1 s light drain at the end; LightDirect ld1 r2 lights chunks while generation runs (FULL 371 at 4.6 s vs 36 at 5.4 s
in base) and its end has all 4 workers busy - no light tail. Batches grow when the actor is behind (15-126 tasks
instead of 2), so the per-batch costs (publish, swap) fall: 1.5-1.75 ms of light per FULL chunk (r2) vs 2.25-2.3.

Remaining latency with LightDirect alone comes from the light mailbox waiting for a turn on the busy worker pool; on its
own thread (-Dlab.light.ownThread, opt/LightDirectThreadMixin + optsupport/LightThread: one daemon thread, parked when
idle, shared by the levels) it falls to milliseconds: lightChunk 7-17 ms mean, p99 < 64-128 ms. (notes/82's "light
lane" test gave nothing because the sorter stayed in front of it.)

### LightPar (-Dlab.opt.LightPar, optsupport/LightPar + opt/LightParMixin; -Dlab.lightpar.verify)
Built as designed above (stage 2). Commit path and verify path:
- `-Dlab.lightpar.verify=true` (lpv1, warm-up + 2 areas, with LightDirect): 86 batches went parallel (3323 chunk
  groups, 1.11 M entries; 3066 batches too small: < 2 groups or < 2048 entries), each then recomputed by vanilla's
  serial pass and compared section by section over the groups' 3 x 3 areas: **0 of 219 492 sections differ**;
  0 fallbacks (no order-dependent sky write met), 0 errors.
- Committed (lpp1, lfx1): fixcheck 0 differences in every area; carvers hashes unchanged.
- Raw parallel speed (lfx1, -Dlab.light.fixcheck.par: the whole 32 x 32 area relit from scratch in a private engine,
  once serial, once with LightPar, workers idle at that moment): 638 -> 372 ms and 864 -> 473 ms for the whole relight
  (1.7-1.8x on 4 cores / 8 threads; the PRE work - section status, sources - stays serial; the increase passes
  themselves 153 / 226 ms in parallel, 1534 / 1592 groups run by helpers). Exact: 0 differences against the level.
- In generation on .103 it does not help: helpers are queued on the worker pool behind generation work and ran 0-5
  groups of 3664 (the light thread did them all, plus the copying): lpp1 102.8, lfx1 105.4 chunks/s - LightDirect's
  range. With LightDirect light is off the critical path at 4 workers (actor 18-24 % busy); LightPar is for where light
  is the ceiling (>= 400 chunks/s on .103's cores at ~2.3 ms a chunk, ~800 on the host), for pregeneration and for big
  relights (/fill, explosions, chunk upgrades), on its own helper threads rather than the generation pool.

## Next
1. Interleave more (base vs LightDirect + ownThread) on .103 and on the host / .92 (16 / 8 threads: is light the tail
   there?), then the play side: teleports and flying with LightDirect + ownThread (light latency was small in play
   already: lightChunk 4 ms; check that FIFO instead of the sorter's priorities does not delay the player's nearest
   chunks when many are queued - a two-level FIFO (ticket level <= 33 first) keeps it exact too).
2. LightDirect (+ ownThread) as a 1.3.4 default candidate: small, exact (task semantics unchanged, light values the
   fixed point), the generation tail gone. Check mods that hook ThreadedLevelLightEngine.addTask / the sorter (yield).
3. LightPar on its own helper threads (not the generation pool), stage 3 (block and sky engines of a batch at once) and
   stage 5 (flat per-batch section index instead of hash lookups: getLayer is 10.6 % self of light time) - worth it
   when light is the ceiling (pregeneration at >= 400 chunks/s) or in big relights.
4. 1.20.1: the same engine (1.20's light engine is the same design: LightEngine / QueueEntry, ThreadedLevelLightEngine
   with the sorter) - port after 1.21.1 is settled (one version first).

## 11-21 evening: LightDirect toward a 1.3.4 default (agent, lab-light-1.21.1)
Boxes: Xeon .103 (E3-1230 v2, 4c/8t) and the N100 (4c/4t; a NeoForge 21.1.251 server copied from .103's
C:\mcbench\neo121 to the N100's C:\mcbench\neo121 - it had none). Same jar per series, variants interleaved, genbench
32 x 32 (r1, r2 after a 16 x 16 warm-up), -Dlab.light.probe + -Dlab.light.fixcheck in every genbench run.
No 16-thread number: the host was not used (still missing).

### Priority: LightLanes (vanilla's sorter order without its round trips)
Found on the N100: plain FIFO LightDirect is NOT always exact. fixcheck differences in 3 of ~19 FIFO runs, all in the
N100's warm-up area (cold JIT, the most backlog): ngd3 2 chunks / 269 nibbles, n9f1 1 chunk / 688, hf8 4 chunks / 880 -
block light only, always the level LOWER than from-scratch, by exactly 1 over a whole light field. hf8's dump (new:
LightCheck lists the light sources within 15 of the first difference): flowing lava (64089,-22,64124 level=4, emission
15) whose own cell holds 14 - that source was never propagated (lit only by its neighbour). Base: 0 in 17+ runs (and the
previous agent's runs). Mechanism not pinned down to the line; what FIFO changes is the order vanilla's sorter enforces
by priority: updateSectionStatus / queueSectionData / retainData / updateChunkStatus are submitted at queue level 0 (they
overtake everything queued, even the same chunk's earlier tasks), the rest by the chunk's ticket level.
- New optsupport/LightLanes (LightDirect's default now; -Dlab.light.prio=false = the old FIFO): one lock-free queue per
  queue level (ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT), the level read from the sorter message's own supplier at
  submission; each submitted task tells the light mailbox one drain, a drain runs the first task of the lowest
  non-empty level. Same lambdas, still one at a time on the light actor, no sorter round trips. Not copied from the
  sorter: its per-chunk grouping within a level and re-sorting when a chunk's level changes while queued.
- **LightLanes does NOT fix it.** Warm-up hunts on the N100 (fresh server each run, the 16 x 16 warm-up area only, the
  case where FIFO failed), all runs of the evening counted (genbench warm areas included):
  | variant (N100) | runs with differences |
  |---|---|
  | base | **0 / 22** |
  | LightDirect FIFO | 3 / 16 (ngd3, n9f1, hf8) |
  | LightLanes | 5 / 52 (hp13, hp15, hp17, nvp3, ntp1: 1-10 chunks, 100-880 nibbles; the later np1-10 0 / 10) |
  | FIFO + ownThread | 0 / 3 |
  | LightLanes + ownThread | **0 / 15** (+ Nether and End genbench: 0) |
  | Xeon .103, every variant | 0 / ~40 |
  Without its own thread LightDirect differs in ~12 % of N100 warm-ups (8 / 68), base never (0 / 22): a real effect. With
  ownThread 0 / 18 (P = 0.88^18 = 10 % if the rate were the same) - NOT proof, and the mechanism is unknown.
- What the hits look like: always block light, always the level lower, by exactly 1 over a field, always near lava
  (springs and lakes, y -20 .. -55); a flowing-lava cell holding 14 in some hits (never propagated as a source), in
  others every source at 15 and the field one step short (a path one block longer - as if a cell was opaque when the
  light passed and nothing re-checked it). Probes: (1) LightProbeSectionMixin - block-light sections created next to
  an already-lit section (start dark, vanilla never pulls light in): 0 in every run; (2) LightProbeCheckNodeMixin -
  checkNode of a light source with its section not stored or light off: only in chunks before LIGHT (normal: features
  writing into a neighbour at INITIALIZE_LIGHT; that chunk's lightChunk scans its sources later), never after LIGHT;
  (3) -Dlab.light.trace=box (every checkBlock submitted, checkNode run with the state it read, section status, light
  enabled, sources propagated in a box, in order and per thread): at the hf8 / nvp3 spot (lava spring 64087,-22,64124,
  seed 20260924, warm-up area) every lava checkNode ran with the section stored, light on, stored 14 -> emission 15
  (correct); ntp1's hit was elsewhere (a lava lake at y -55). So no check is skipped; the leading hypothesis is a
  read race: the light pass reads blocks of chunks whose neighbours' FEATURES still write into them (PalettedContainer
  reads during a resize can return a wrong state for a cell nobody changed - no checkBlock ever follows for it). Vanilla
  has the same race but its sorter delays light ~1 s behind generation, so the passes rarely overlap the writes;
  LightDirect lights immediately. Why ownThread would avoid it is open (smaller batches: 5-6 tasks instead of 15-126,
  shorter passes). A second candidate: a block written into a neighbour without a checkBlock (ProtoChunk.setBlockState
  sends one only once the chunk's status is INITIALIZE_LIGHT or later) after LightDirect already propagated light into
  that neighbour's sections - vanilla's ordering of the generation steps should forbid it, but GenGraph schedules the
  steps itself; the sorter's delay would hide such a window. SNAPSHOT A/B below.

### Throughput (chunks/s, mean of r1 / r2 per run)
| box | base | LightDirect FIFO | LightLanes | LightLanes + ownThread | FIFO + ownThread |
|---|---|---|---|---|---|
| Xeon .103 | 81.7 / 94.7 / 87.1 / 88.5 = **88.0** (r2 97.0) | 106.5 / 114.2 / 105.2 / 104.3 = 107.5 | 110.0 / 108.1 / 107.2 = **108.4** (r2 121.1) | 108.8 / 109.0 / 108.4 = **108.7** (r2 119.4) | |
| N100 | 69.0 / 67.6 / 70.7 / 73.2 / 64.1 / 72.8 / 68.6 = **69.4** (r2 75.5) | 73.3 / 75.2 / 76.2 / 70.8 / 71.9 / 75.0 = 73.7 | 77.2 / 72.3 / 71.9 = **73.8** (r2 79.2) | 72.0 / 71.7 / 72.2 = **72.0** (r2 80.3) | 73.1 / 71.8 / 75.0 = 73.3 |
Xeon +23 % (as the first runs' +16.5 %), N100 +4-6 %: the N100's 4 hardware threads are all busy generating - light was
not on its critical path; the latency fell anyway (lightChunk future, r1 / r2: N100 1.0 / 0.6 s -> 86 / 52 ms, with
ownThread 7 ms; Xeon 0.8 / 1.1 s -> 77 / 80 ms, ownThread 8-10 ms). ownThread adds no throughput at 4 cores; it makes the
latency milliseconds (no turn on the busy pool).

### Play: does the light queue delay what the player sees? (.103 production client, fresh world)
Teleports (ClientAuto tp to 2000 / -2000; new: "near (r 4) chunks received", and the client-side times counted only once
the client's player is at the target - the old "-1.9 s" values were the old place's chunks):
| variant | near (r <= 4) received, s | all received, s | server FULL, s |
|---|---|---|---|
| base (tpb1-2, tqb1-2) | 2.1, 2.3, 2.0, 2.0, 1.2, 2.1, 5.2 | 8.0-12.6 | 7.6-13.0 |
| FIFO (tpd1-2) | 1.3, 2.1, 1.6 | 7.5-11.8 | 7.7-11.5 |
| FIFO + thread (tpt1-2) | 1.1, 1.6 | 7.1-7.4 | 7.1-7.7 |
| LightLanes (tqp1-2) | 2.1, 1.8, 1.6, 1.7 | 7.5-12.0 | 7.7-12.1 |
| LightLanes + thread (tqt1-2) | 1.7, 1.5, 2.5, 2.7 | 7.8-13.7 | 7.9-13.8 |
lightChunk latency in play is 3-6 ms in every variant (initializeLight 10-30 ms base, < 1 ms with ownThread): in play the
sorter's queue is short and light never holds a chunk back. (The "terrain generated + meshed" end sometimes runs long -
26-35 s instead of ~18 - in base and LightDirect alike, tqb1 34.0 / 26.2: late light-update packets re-dirty sections
after everything arrived; not a LightDirect effect.)
Flight (new: -Dsleep.clientAuto.orbit=0,150,0,0,100000,0,0,40 = straight +x at 40 blocks/s for the 40 s steady window,
fresh world; -Dsleep.clientAuto.holes now also counts the holes within 8 and within 4 chunks of the player):
| variant | holes / s (all) | within 8 chunks | within 4 |
|---|---|---|---|
| base (6 runs) | 75.0, 57.9, 57.7, 67.9, 50.2, 75.2 (64.0) | 5.15, 3.03, 2.75, 5.05, 2.40, 5.50 (3.98) | 0 |
| FIFO (2) | 104.5, 69.6 | 18.45, 4.10 | 0.68, 0 |
| FIFO + thread (2) | 62.8, 68.4 | 3.68, 3.98 | 0 |
| LightLanes (4) | 76.9, 74.9, 71.0, 57.8 (70.2) | 4.23, 5.53, 2.93, 2.75 (3.86) | 0 |
| LightLanes + thread (4) | 50.0, 62.7, 71.0, 55.5 (59.8) | 1.18, 2.95, 3.63, 1.88 (2.41) | 0 |
At 40 blocks/s .103 (client + server on 4 cores) cannot generate fast enough: holes at the view's edge in every variant;
the light latency in the flight is 3-4.5 ms everywhere, the holes are generation's. No variant delays the nearest chunks;
LightLanes + ownThread has the fewest holes within 8 chunks.

### Exactness suite (light-sensitive scenes)
Not run: tools/exactness.py needs the host's dev setup (not used here). Scenes where game logic reads light or light
changes a lot: crops (growth, saplings, grass need light >= 9), zoo (zombies / skeletons burn by sky light), redstone (32
lamps toggling: block-light bursts), furnaces / smelters (lit state), fluids (lava). LightDirect changes only when queued
light tasks reach the actor (same tasks, vanilla's priority order); with an idle server the sorter forwards them at once
too. Run those five with -Dlab.opt.LightDirect=true -Dlab.light.ownThread=true on the host before the default goes in.

### Compatibility
Mixin targets of the light mods in F:/compat (scratchpad light/mixscan.py over 488 1.21.1 jars, nested jars included):
- Moonrise (NeoForge 0.1.0-beta.15): @Overwrite of ThreadedLevelLightEngine.addTask (both), runUpdate, tryScheduleUpdate,
  initializeLight, lightChunk, waitForPendingTasks, checkBlock, updateSectionStatus ... (its Starlight engine and chunk
  system). Our @Redirect inside addTask would be refused by Mixin: must give way.
- ScalableLux 0.3.0 (top121) / 0.1.0.1: @Redirect of `new ThreadedLevelLightEngine` in ChunkMap.<init> (its own
  ThreadedLevelLightEngineVanillaInterface subclass: overrides initializeLight / lightChunk / checkBlock /
  waitForPendingTasks and never calls addTask) + @WrapOperation in runUpdate / tryScheduleUpdate.
- C2ME 0.4.0-alpha.0.124: threading-lighting's MixinServerLightingProvider on tryScheduleUpdate, an invoker for addTask,
  its chunk-system rewrite on the ticket manager.
- Nothing else among the 488 jars touches ThreadedLevelLightEngine or the sorter (alltheleaks only names it in a debug
  mixin; Lithium / Radium / ModernFix / Noisium / DH / Chunky do not).
New rule in MixinYield (lightScheduling): every LightDirect* / LightPar* mixin gives way when ANY foreign mixin changes a
method of ThreadedLevelLightEngine (not only on a method-level clash: our thread and order assume vanilla's actor, which
those mods replace or reschedule); no culprits are named to the rules, so a "never give way" rule cannot take mixins out
of another mod's light engine. Checked on the N100 (production server, our jar with LightDirect + ownThread + probe,
genbench 12 x 12): alone all three LightDirect mixins apply; with ScalableLux 0.3.0, Moonrise beta.15 or C2ME 0.4.0
each, all LightDirect* and LightPar* mixins print "not applied: another mod changes the light engine's scheduling
(ThreadedLevelLightEngine) - by <mod> (<mixin>)", the server starts and generates (25.5 / 20.1 / 12.5 chunks/s), no
exception of ours (C2ME's own optional-target warnings only). The existing rules also took LightProbeThreadedMixin out
under Moonrise (runUpdate overwritten) and MainReactorLightNotifyMixin under C2ME (onLightUpdate changed).

### SNAPSHOT A/B and GenGraph off (N100 warm-up hunts, LightLanes without ownThread)
LightLanes with LightSnapshot + LightLayerCache off (nk1-10) 0 / 10, the interleaved control (np1-10) 0 / 10 -
inconclusive (the control did not hit either; ~12 % rate). LightLanes with GenGraph off (ng1-12): 0 / 12 - suggestive
(P = 0.88^12 = 21 % at the same rate), not proof. Both worth repeating with ~40 runs each: GenGraph's own step order
(direct dependencies instead of vanilla's layers: a chunk can reach LIGHT while a neighbour two away has not run FEATURES
yet, whose writes then land in light already propagated) together with light that no longer waits ~1 s in the sorter
is the most specific lead.

### Recommendation for 1.3.4
(Superseded 11-22, see "the -1 near lava was fixcheck's own race" below: the differences counted in this section came
from the check, not from LightDirect.)
**Not a default yet.** The gain is real and play is not hurt (Xeon +23 % chunks/s, N100 +4-6 %, lightChunk latency
~1 s -> ~80 ms, with ownThread ~8 ms; no delay of the nearest chunks in teleports or a 40 b/s flight; mod conflicts
handled), but LightDirect without its own thread leaves block light below vanilla's in ~12 % of N100 warm-ups (base
never), and the cause is not found. LightDirect + ownThread had 0 differences in 18 N100 runs + every Xeon run + Nether /
End - the candidate if anything, but "0 of 18" at a ~12 % base rate with an unknown mechanism is not the bit-exact bar.
Next, in order: (1) find the mechanism - the N100 warm-up of seed 20260924 reproduces it at a few fixed spots (lava
spring 64087,-22,64124; lava lake ~64073,-55,64132; 64036,-40,64055; 64195,8,64182): -Dlab.light.trace on those boxes,
plus a record of the block reads of the light pass in the box (state read vs state at the end) to test the read-race
hypothesis; (2) then fix it inside LightDirect (or prove ownThread structurally closes the window) and re-run the hunt
until >= 60 clean N100 warm-ups per variant; (3) the host's 16-thread genbench and the exactness suite's light scenes
(crops, zoo, redstone, furnaces / smelters, fluids); (4) only then defaults.txt:
`-Dlab.opt.LightDirect=true` and `-Dlab.light.ownThread=true` (lab.light.prio stays default on).

## 11-22: the "-1 near lava" was fixcheck's own race, not the engine
What vanilla guarantees, and whether LightDirect needs more: LIGHT requires the neighbours at INITIALIZE_LIGHT (radius
1), INITIALIZE_LIGHT nothing, FEATURES CARVERS at radius 1 and writes radius 1. So when X reaches LIGHT a chunk two
away may still run FEATURES - but it can write only into X's neighbours, which are past INITIALIZE_LIGHT, and every
such write sends updateSectionStatus / checkBlock (ProtoChunk.setBlockState: status >= INITIALIZE_LIGHT); a chunk's
light reaches only its 3 x 3, all readable (getChunkForLighting needs FEATURES done). The step future of
INITIALIZE_LIGHT completes in the light actor's POST, after which setPersistedStatus runs - before any neighbour's LIGHT.
Nothing there leans on the sorter's ~1 s delay; GenGraph keeps the same direct edges. So no dependency had to be added.

The probes found where the differences came from (light15 .. light19, notes/84 tools):
- published (visible) block light = the actor's own (updating) map in every run, hits included: not a publication bug.
- -Dlab.light.history: 0 reads of an unavailable chunk (no BEDROCK reads), every checkNode of the differing lava ran
  with its section stored and light on; the level's light in a differing region is internally consistent (no cell more
  than one below a neighbour across a transparent face - "0 entry points"); the differing cell is the newest lava of a
  flow, holding 14: its checkBlock submitted, not yet run.
- the check runs at the END of a server tick (GenBench.onTickEnd) and relit from the blocks of that very tick: a lava
  flow (or a falling block) of that tick was in the blocks but not yet in the light. "Light idle for 10 ticks" did not
  see it: the task waits in LightDirect's queues / the light mailbox, outside lightTasks. Vanilla's light is just as
  asynchronous; with LightDirect on the busy worker pool the window was longest (ownThread: microseconds).
- Fix (the tool, not the engine): LightCheck.ready now freezes the game's ticks (tickRateManager, -Dlab.light.fixcheck.
  freeze=false = the old behaviour), submits a marker task (waitForPendingTasks: every light task submitted before it
  has run, whichever queue it waited in), then 10 idle ticks; the ticks resume after the check.
- A/B on the N100 (LightLanes, no ownThread, history on), warm-ups interleaved: frozen check 0 / 25, old check 1 / 25
  (nn7: the newest lava cell 64188,-18,64124 level=2 at 14, region self-consistent). The hit rate of the old check fell
  from ~10 % (11-21 jars) to ~2-4 % with the probes of 11-22 in the jar (nq4, nr4, nn7, ntp1, nvp3 hits; 0 / 30 in a
  further old-check hunt nrc with -Dlab.light.fixcheck.recheck, so the "gone after freezing" proof did not come up) -
  consistent with a timing window, not with a logic error. Hit spots repeat (lava spring 64087,-22,64124 in hf8 / nvp3
  / nq4; lava lake ~64073,-55,64132 in ntp1 / nr4): places where lava keeps flowing after the area is FULL.

### Validation with the frozen check (warm-up areas, fresh server each, fixcheck 0 = every block / sky nibble of 144
inner chunks equal to vanilla's from-scratch light)
| variant | N100 | Xeon .103 | total |
|---|---|---|---|
| LightLanes (LightDirect, no ownThread) | 15 (nvl) + 25 (nf, history on) = 40 / 40 clean | 60 / 60 (xvl) | **100 / 100** |
| LightLanes + ownThread | 30 / 30 (nvt) | 60 / 60 (xvt) | **90 / 90** |
| base (1.3.3 defaults) | - | 10 / 10 (xvb) | 10 / 10 |
At the old check's measured rate for LightLanes on the N100 (~2-10 %), 40 clean runs there alone would happen by
chance with P = 0.98^40 = 45 % .. 0.9^40 = 1.5 %; with the old check it was the N100 warm-up and nothing else that
ever differed, and the frozen check's difference count is 0 in all 190 runs. Every run also compared the published
block light with the actor's (0 sections differ in all). Nether / End on the N100 with the frozen check: LightLanes +
ownThread 141.9 / 268.5 chunks/s, LightLanes 144.3 / 263.1, fixcheck 0 in every area.

Full genbench with the frozen check, Xeon .103 (light20 jar, 32 x 32, r1 / r2): base 93.4 / 95.8, 79.8 / 112.2 (95.3);
LightLanes 97.1 / 117.3, 106.1 / 123.1 (110.9, +16 %); LightLanes + ownThread 97.0 / 120.1, 102.9 / 124.6 (111.2, +17 %);
fixcheck 0 in every area, carvers equal in all six (r1 3d3701ca..., r2 0880a251...). Nether / End (Xeon, frozen check):
LightLanes 198.2 / 424.8 chunks/s, + ownThread 201.3 / 444.6, fixcheck 0 in every area.

### Recommendation (11-22)
LightDirect is exact as far as every check can see: the only differences ever found were the check's own race, gone
with a frozen, caught-up check. Recommended for 1.3.5: `-Dlab.opt.LightDirect=true` (LightLanes order, the default of
the flag) **with** `-Dlab.light.ownThread=true` - same throughput at 4 cores, lightChunk latency ~8 ms instead of ~80,
the lightest load on the worker pool. Before the defaults change, the host checks (asked of the coordinator): the 1.21.1
exactness suite's light scenes (crops, zoo, redstone, furnaces, smelters, fluids) with both flags, and a 16-thread
genbench base vs LightDirect + ownThread (fixcheck + carvers). 1.20.1: port after that (one version first).

## 11-22: SIMD octave noise (NoiseBatch, lab-simd-1.21.1 ce69442e) on the N100 - the AVX2 check
The N100 (Gracemont, AVX2, 4c/4t) is the only AVX2 box besides the host; .103's Ivy Bridge has AVX only (notes/82:
corners -18 % there). Production NeoForge server C:\mcbench\neo121, genbench 32 x 32, interleaved, the 1.3.3 night
defaults on in both, -Dlab.dfc.batch=true vs off:
| run | chunks/s r1 / r2 (mean) | noise.corners us / call (r2) | corners ms per FULL chunk (r2) | doFill ms per FULL chunk (r2) |
|---|---|---|---|---|
| sb1 batch | 67.5 / 80.3 (73.9) | 482.7 | 3.28 | 14.78 |
| so1 off | 53.6 / 56.1 (54.9) | 800.8 | 6.47 | 18.62 |
| sb2 batch | 63.3 / 78.8 (71.0) | 507.9 | 3.69 | 16.65 |
| so2 off | 58.4 / 71.4 (64.9) | 770.3 | 6.23 | 19.50 |
| sb3 batch | 63.7 / 76.1 (69.9) | 639.5 | 4.59 | 16.28 |
| so3 off | 54.3 / 55.8 (55.0) | 837.5 | 6.77 | 20.04 |
Batch on: 71.6 chunks/s vs 58.3 off (+23 %; r2 78.4 vs 61.1, +28 %); corners -33 % a call, -45 % per FULL chunk (more
of them answered from blocks already filled); doFill per FULL chunk -16 %. Much more than on .103 (AVX, -18 % corners,
chunks/s within noise): the kernel's 4-double vectors run at full width on the N100's AVX2 units, and the N100 is
noise-bound at 4 threads. Exact: carvers hashes of all 7 runs equal (r1 3d3701ca..., r2 0880a251..., the same as every
light run of notes/84); sv1 (batch + -Dlab.dfc.verify=true, every compiled value compared with vanilla's fillArray)
104 857 636 values, 0 differ. -> NoiseBatch can be a 1.3.5 default from the AVX2 side (host 16-thread number and the
1.20.1 port still to do; JIT: C2's superword is on by default in every JDK 17 / 21 build players use).

## 11-22 morning: host 16 threads (LightDirect + ownThread, NoiseBatch)
Production server F:/release-test/server (NeoForge 21.1.251, JDK 21, heap 4 G - the host's commit charge was 39.7 of
47.4 GB), genbench 32 x 32, interleaved, 8 pairs each (3 at ~03:35, 5 from 04:20). The host was NOT quiet: other
agents' work kept it at 75-98 % CPU (Get-Counter) through both rounds, so chunks/s swung 70 .. 284 for one jar.
| pair | base / LightDirect + ownThread (chunks/s, mean r1 r2) | SIMD off / batch |
|---|---|---|
| 1 | 109.8 / 283.8 | 95.8 / 123.3 |
| 2 | 95.8 / 199.1 | 171.4 / 141.6 |
| 3 | 223.9 / 97.8 | 254.5 / 249.3 |
| 4 | 133.2 / 108.4 | 105.7 / 159.8 |
| 5 | 70.8 / 118.0 | 164.4 / 93.7 |
| 6 | 203.5 / 171.2 | 91.1 / 234.0 |
| 7 | 83.9 / 165.2 | 147.4 / 93.9 |
| 8 | 211.0 / 226.6 | 159.5 / 110.9 |
Throughput: not measurable on this host now - LightDirect + ownThread geometric mean of the pair ratios x1.24 (median
121 -> 168 chunks/s), SIMD x1.0; the spread is the other load. What the noise does not hide: lightChunk latency
270-1240 ms (base) -> 6-28 ms (LightDirect + ownThread) in every pair; noise.corners per call (r2) batch / off
0.53, 0.83, 0.67, 0.51, 0.92, 0.31, 0.77, 1.21 (median -28 %). Exact: fixcheck 0 in every area of all 16 light runs,
carvers hashes equal in all 29 host runs (r1 3d3701ca..., r2 0880a251...), batch + dfc.verify 118 489 104 values, 0
differ. A clean 16-thread throughput number needs a quiet host window.

## 11-22: LightDirect on 1.20.1 (lab-light-1.20.1 from night-1.20.1)
Port (752e4f1f): LightDirect + LightLanes + ownThread + the MixinYield rule, the light probe / fixcheck tools. 1.20.1
has no /tick freeze: its fixcheck holds the server thread instead and drains the light (sorter idle, LightLanes and
the light mailbox empty, no queued batch, no light work, 50 ms in a row; main-thread chunk tasks keep being polled).
Dependency argument for 1.20.1's scheduling (per (chunk, status) futures, no GenGraph): the same radii - LIGHT range 1
on INITIALIZE_LIGHT, FEATURES writes radius 1 (WorldGenRegion(..., 1)), ProtoChunk.setBlockState sends checkBlock /
updateSectionStatus from INITIALIZE_LIGHT on, the status set after the step's future (ChunkStatus.generate).
**But one dependency is timing in 1.20.1**: the light engine reads chunks through the VISIBLE chunk map
(ServerChunkCache.getChunkForLighting), and 1.20.1 starts a holder's generation inside the distance-manager update
(ChunkHolder.updateFutures), before runDistanceManagerUpdates promotes the map. A chunk that goes fast (loaded back from
the stored ring) reaches initializeLight / lightChunk while not visible: propagateLightSources finds no chunk - its block
sources stay unlit, its sky is set as if the column were empty (fixcheck: block lower, sky higher, 4-27 chunks a
1024-chunk area). Probe line (each hit): "sources of [4058, 4011] asked with no chunk for lighting: visible holder
false, updating holder true, level 31, futures ... initialize_light=chunk light=pending". With LightDirect + ownThread on
.103 it hit 6 of 7 full genbench runs (r1 mostly; without ownThread 0 / 1, with the stored ring off 0 / 1; the N100's
1.20.1 runs 0 / 3 - its slower generation leaves the window shut); with vanilla's sorter path it is rarer but
**vanilla 1.20.1 does it too** (base
x4b1: 8 chunks differ, the same probe line). 1.21.1 runs generation tasks after the promotion
(runDistanceManagerUpdates: runAllUpdates, promoteChunkMap, runGenerationTasks) - no such window there.
Fix (eb9f3f15): LightLanes holds a task while its chunk is not visible and no promotion of that chunk map happened since
the task came (LightDirectPromoteMixin counts promotions and drains again); a holder that existed at submission is
visible after one promotion, one still absent is really absent (an unloaded chunk's last tasks run as before). This
makes 1.20.1's light deterministic (always the lit outcome, the one vanilla gets when its timing is kind).
-Dlab.light.waitVisible=false = without it (x4w1: 6 probe hits, this time no difference).
After the fix on .103 (l1201c jar, full genbench): LightDirect + ownThread 0 / 4, LightDirect 0 / 4 differences, 0
probe hits; base 1 / 4 runs differed (x4b1) this morning.

Validation, the built SRG jar (l1201c = eb9f3f15) on the production Forge 1.20.1-47.4.9 servers (C:\mcbench\forge1201 on
both boxes, JDK 17), fixcheck with the drained server thread:
| | Xeon .103 | N100 |
|---|---|---|
| genbench 32 x 32, mean of r1 / r2, 5 interleaved triples: base | 74.6 / 76.1 / 74.9 / 74.4 / 77.2 = **75.4** | 50.1 / 50.0 / 50.1 / 50.5 / 47.9 = **49.7** |
| LightDirect + ownThread | 78.1 / 77.2 / 78.0 / 80.7 / 86.2 = **80.0 (+6 %)** | 51.0 / 52.7 / 49.2 / 52.7 / 51.5 = **51.4 (+3 %)** |
| LightDirect | 76.8 / 79.1 / 72.5 / 77.2 / 78.2 = 76.8 (+2 %) | 51.0 / 51.0 / 53.5 / 44.0 / 56.5 = 51.2 (+3 %) |
| fixcheck, every area of the 15 runs | 0 | 0 |
| Nether LightDirect / + ownThread (chunks/s, fixcheck) | 145.8 / 140.0, 0 | 87.7 / 93.7, 0 |
| End LightDirect / + ownThread | 270.1 / 264.6, 0 | 168.6 / 158.2, 0 |
| warm-ups (fresh server, warm-up area): LightDirect + ownThread | 20 / 20 clean | 20 / 20 clean |
| LightDirect | 20 / 20 clean | 20 / 20 clean |
Carvers hashes equal in all 58 1.20.1 overworld genbench runs of both boxes and all jars of the day (r1 d05c7f4a...,
r2 10797483...). 1.20.1 gains little: its generation (no GenGraph, no reactor) is the bottleneck at 4 cores, light
is not - the same picture as the N100 on 1.21.1. Recommendation for 1.20.1: LightDirect + ownThread may follow 1.21.1
into the defaults with the visibility wait (it is what makes it exact there); it is a correctness fix of a vanilla
1.20.1 race as much as a speed-up.
