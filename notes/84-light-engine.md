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
