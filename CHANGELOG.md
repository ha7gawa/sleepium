# Changelog

Versions follow [Semantic Versioning](https://semver.org): a patch release fixes, a minor release adds, a major
release changes the API (`dev.lull.api`) or the settings in a way that needs attention. Jars are named
`sleepium-<version>+mc<minecraft version>-<loader>.jar` (before 1.1.0 without the loader).

## 1.4.10 - 2026-11-25

- **Maximum speed keeps mods' code off the worker threads also when it sits inside vanilla mobs and blocks.** Mods often
  change a vanilla mob's or block's tick with a mixin (seasons changing crop growth, mods adding behaviour to a vanilla
  animal), and that code usually keeps state that is not made for several threads. Such a mob now stays on the server
  thread (unless *Maximum speed for mods' mobs* is on), and such a block's random tick runs after the parallel pass,
  on the server thread. Found with test mods that do exactly this: before, most of their calls ran on the workers and
  lost updates; now none do. Without such mods nothing changes.
- Checked at the same time: a mod that follows entity ticks with an event listener sees every tick in the exact mode,
  the same count as in vanilla, also where Sleepium puts entities to sleep.

The exact modes are unchanged: the exactness checks stay identical to vanilla, with parallel entity ticks too.

## 1.4.9 - 2026-11-24

- **Safer Maximum speed and parallel entity ticks next to other mods.** Parallel ticks rely on several of Sleepium's
  guards: chunk lookups for the worker threads, the entity lists' lock, spawns and block changes kept for the end of a
  batch, the points-of-interest lock, raids, creeper explosions. When another mod changes the same code, Sleepium's
  guard steps aside. 1.4.7 handled this for the chunk lookups (the hang with Lithium); now every guard is checked: the
  batches stay on one thread without the general ones, villagers and golems share one thread without the
  points-of-interest lock, raiders and creepers stay on the server thread without theirs. The log says once which
  guard is missing and why. Without such mods nothing changes.
- With Lithium (1.21.1), Canary or Radium (1.20.1), Maximum speed therefore runs without its parallel batches: these
  mods change the same code. Everything else works as before.

The exact modes are unchanged: the exactness checks stay identical to vanilla, with parallel entity ticks too.

## 1.4.8 - 2026-11-24

- **Fixed: world generation with C2ME was slower than with C2ME alone.** One of Sleepium's generation optimisations (a
  per-column memo of the climate values) put nodes into the density function trees that C2ME's compiler does not know,
  so C2ME fell back to its slower path. With C2ME installed that optimisation now steps aside. Chunky, a normal world,
  16 threads: Sleepium + C2ME 113 -> 149 chunks/s (C2ME alone 168).
- **On world generation in general:** Sleepium alone generates faster than C2ME (307 against 168 chunks/s in the same
  test, vanilla 58.5), and with C2ME installed most of Sleepium's generation work gives way to C2ME's. If world
  generation speed is what you want, Sleepium without C2ME is the faster choice. ModernFix and Sleepium together are
  fine (330 chunks/s).

The exact modes are unchanged: the exactness checks stay identical to vanilla.

## 1.4.7 - 2026-11-24

- **Fixed (1.21.1): with Lithium, some chunks were not saved** (since 1.3.5). Sleepium's faster save of a chunk's
  scheduled block ticks expected vanilla's own tick queue; Lithium keeps those ticks in a structure of its own, the save
  failed ("Failed to save chunk" in the log) and the chunk was not written. Such chunks are now saved the way Lithium
  saves them. If you played 1.21.1 with Sleepium 1.3.5-1.4.6 and Lithium, check your log for "Failed to save chunk":
  changes in those chunks since they were last saved successfully may be lost.
- **Fixed: Maximum speed and parallel entity ticks could stop the server's tick for good with Lithium** (or any mod that
  replaces the server's chunk lookup). Sleepium's hook that lets the worker threads read chunks gave way to the other
  mod, and a worker waited for the server thread while the server thread waited for the workers. Parallel ticks now run
  only when that hook is in place; otherwise they stay on one thread and the log says why.
- **Fixed: a dedicated server could stop when a mod's machine holds a field of a client-only class.** Sleepium's sleep
  check for mods' machines now leaves such a machine awake instead.

The exact modes are unchanged: the exactness checks stay identical to vanilla.

## 1.4.6 - 2026-11-24

- **Maximum speed: chunk ticks in parallel.** Random ticks (crops, grass, leaves and the like), snow, ice and lightning
  are worked out for all ticking chunks at once on the worker threads; the block changes, spawns, sounds and neighbour
  updates they make are applied right after, in order. Mods' blocks keep their random ticks on the server thread.
  100 players moving around a normal world: 30.3 -> 25.6 ms a tick on 1.21.1 (about 7 % less on 1.20.1).
- **Fixed (Maximum speed): the server stopped when the game's profiler was on** (`/debug start`, `/perf start`).
- **Fixed (Maximum speed): the server could stop when a mob at the edge of the loaded area wandered towards an
  unloaded chunk.** For that mob's tick the unloaded chunk now counts as empty.
- **Issue #11 follow-ups:** when a mod draws into the game's own buffers from a render worker, the work still moves to
  the render thread, but without an error and a stack trace in the log; `-Dsleep.glTrace` now only reads and reports
  by default (its retries need `-Dsleep.glTrace.retry=true`).

The exact modes are unchanged: the exactness checks stay identical to vanilla by default and with parallel entity ticks
on.

## 1.4.5 - 2026-11-24

- **Fixed: flicker, stray stretched textures and dark frames with some mods (issue #11).** A mod that draws in an
  entity's render event into the game's own buffers instead of the ones it is given (health bars, name tags and the
  like) confused the entity render cache (its vertices landed at the camera) and, on 1.20.1, raced the render
  threads ("Not filled all elements of the vertex" crashes). Now such drawing is recorded with the entity when it can
  be - the cache stays on, with no frame-rate cost - and otherwise that mob is drawn the normal way; a renderer that
  still draws past its buffers is switched to a safe mode for good (noted once in the chat and in Options > Sleepium...
  > Mods). Embeddium keeps its fast path; other buffer wrappers are written to through their own API. Thanks to
  swordeatingrabbit-max for the detailed analysis.
- **Maximum speed: raiders tick in parallel** - witches, pillagers, vindicators, evokers and ravagers (witch and raid
  farms). Their raid's bookkeeping (joining, leaving, the leader, the boss bar) waits for the end of each batch.
  700 raiders around iron golems: 5.8 -> 1.7 ms a tick.
- **New option: Maximum speed for mods' mobs** (off): with it, mobs added by mods are ticked on the worker threads too.
  A mod's mob may use the mod's own state that is not made for several threads, so it is a separate switch - turn it
  on in a pack where it runs well.
- **1.20.1:** entities nobody can see skip their per-tick network update with Maximum speed (as on 1.21.1).

The exact modes are unchanged: the exactness suite stays identical to vanilla by default and with parallel entity
ticks on.

## 1.4.4 - 2026-11-24

- **Maximum speed: villagers and iron golems tick on all threads.** They used to share one thread per batch, because
  the game's points-of-interest manager (beds, job sites) is not thread-safe; now its lookups run one at a time under a
  lock while the mobs around them tick in parallel. A farm with villagers, zombies and iron golems on a 4-core Xeon:
  29 -> 22 ms a tick.
- **Maximum speed: creepers tick in parallel** too; their explosion happens at the end of the batch.
- **Maximum speed: parallel dropped items no longer switch themselves off** when an item crossed into another chunk
  section in a long session.
- **Less overhead** for the worker threads of parallel ticks (their per-thread state is a field of the thread).

The exact modes are unchanged: the exactness suite stays identical to vanilla by default and with parallel entity
ticks on.

## 1.4.3 - 2026-11-23

- **Fixed: a server with parallel entity ticks or Maximum speed could stop while new chunks generated** (since 1.3.6,
  both options are off by default). World generation that places a structure with mobs (a village's cat) asked for a
  chunk the way a parallel entity tick does and stopped the server with "a run tick needs chunk ... that is not
  loaded".
- **Fixed: a rare `ConcurrentModificationException` with parallel entity ticks or Maximum speed** when dropped items
  merged in parallel (two threads filled a chunk section's per-class entity list at once).
- **Fixed (1.20.1): parallel entity ticks ran mods' LivingTickEvent listeners on worker threads.** With such a mod
  installed the exact parallel ticks now tick the usual way.
- **Maximum speed with mods:** a mod listening to entity tick events no longer turns the parallel ticks off. Mods'
  event listeners run one at a time under a lock while the mobs around them tick in parallel. Tested with a listener
  that is not thread-safe on purpose: 50 million calls, no overlap.
- **Maximum speed, crowds:** pushes between crowded mobs are summed on the worker threads instead of being applied one
  by one afterwards, and crowded chunk sections get a spatial grid for each tick instead of a full scan for every
  mob's neighbour query - pig pens (5,000) on a 4-core Xeon 41 -> 25 ms a tick; mobs that move inside their chunk
  section cost nothing extra; on 1.21.1 entities nobody can see skip the per-tick network update; after a server stall, a
  player's queued movement is no longer rubber-banded (1.21.1).

The exact modes are unchanged otherwise: the exactness suite stays identical to vanilla by default and with parallel
entity ticks on.

## 1.4.2 - 2026-11-23

- **Fixed: the game stopped at start with Maximum speed on (1.4.1, clients only).** Choosing the worker threads looked
  up whether the game has a client in a way that loaded game classes too early (`MixinTargetAlreadyLoadedException`
  on `BlockableEventLoop`). Dedicated servers were not affected. If 1.4.1 stopped at start for you, update; or set
  `lab.fast=false` in `config/sleepium.properties`.

## 1.4.1 - 2026-11-23

- **Maximum speed uses all your cores.** It ran on at most 4 worker threads, like the exact modes; now it takes every
  core but one (two in single-player, where the game draws frames too). 30,000 pigs, server milliseconds per tick:
  an 8-core desktop 85 -> 50 (20 TPS), a 4-core Xeon 118 -> 103. The cores budget below chooses another count.
- **New: a cores budget** in Options -> Sleepium... -> threads: how many worker threads each of Sleepium's pools takes -
  the server (parallel entity ticks, Maximum speed), the client (parallel rendering, client entities, particles) and
  the background (world generation) - Auto or a number, with a summary of what each pool takes on your machine.
  Applied at the next start.
- **Maximum speed in mixed worlds.** Mobs were ticked in parallel only in long unbroken stretches of the tick order; a
  dropped item, an arrow or a creeper between them cut the stretch. Now all the mobs it can take are gathered into one
  parallel batch each tick, and the rest tick after them.
- **Maximum speed: less overhead.** It no longer carries the exact mode's write tracking (-8 % of the parallel work with
  30,000 pigs), and the level's random numbers come from a stream per thread instead of one stream behind a lock.
- **Maximum speed on 1.21.1: chunks at the view edge stay loaded** a little longer (2 chunks past the view distance),
  so walking or flying back and forth along the edge no longer unloads, saves, loads and resends the same chunks.
  Server side only; works with vanilla clients.

The exact modes are unchanged: the exactness suite stays identical to vanilla by default and with parallel entity
ticks on.

## 1.4.0 - 2026-11-23

**A note from the author:** for health reasons I'll be away for about a year - see the
[README](https://github.com/ha7gawa/sleepium#a-note-from-the-author). More releases come out this week; if there's
something you'd like to see in them, tell me in an issue or a comment in the next few days.

- **New: Maximum speed (experimental, NOT exact).** A new option in Options -> Sleepium... (threads), off by default:
  mobs - animals, monsters, villagers and other brain-driven mobs - are ticked on worker threads without vanilla's
  strict order. Moves, block changes, spawns, pushes and item pickups made by those ticks are applied at the end of
  each batch, in order. The world stays plausible and farms keep working, but it is no longer tick-for-tick vanilla:
  pushes between crowded mobs and the order of events inside a tick differ. Server milliseconds per tick, engine
  alone -> Maximum speed: 30,000 pigs on a 4-core Xeon 315 -> 104; pig pens (5,000) 90 -> 42; a mixed farm with
  villagers, zombies and iron golems 56 -> 30 (87 -> 47 on an Intel N100). Measured with the thread count set to 7
  on the 4-core machines (3 on the N100); with the default count the 30,000 pigs take 118. Checked for crashes and for
  mobs lost or duplicated on 23 test scenes and in 8-12 minute runs of large scenes on both versions and on ARM.
  Creepers still tick the usual way.
- **Parallel entity ticks (exact)** now also tick dropped items in parallel (they ticked one by one with the option on).
- **Fixed:** a possible crash with parallel entity ticks on (`NullPointerException` in `quietGameEvent`): mob steps
  checked for game event listeners in a way that wrote to the chunk from several threads at once.

The exact modes are unchanged: the exactness suite stays identical to vanilla by default and with parallel entity
ticks on.

## 1.3.6 - 2026-11-23

**A note from the author:** for health reasons I'll be away for about a year - see the
[README](https://github.com/ha7gawa/sleepium#a-note-from-the-author). Several more releases come out this week; if
there's something you'd like to see in them, tell me in an issue or a comment in the next few days.

- **New: parallel entity ticks (experimental, exact).** A new option in Options -> Sleepium... (threads): the server's
  animals and monsters are ticked on worker threads. Every tick is checked and committed in vanilla's order, and a
  tick that met another is undone and run again in turn, so the world stays exactly as vanilla's (checked with world
  hashes on four machines - Intel, AMD and ARM - on both Minecraft versions). Crowded pens and farms tick the usual
  way, because that's cheaper there. Not covered yet: villagers and other brain-driven mobs, modded entities. Off by
  default; needs a restart. 30,000 pigs on a 4-core Xeon: 315 -> 192 ms a tick; 10,000 pigs on an Intel N100: 161 ->
  116 ms.
- **Decoupled frames with other mods.** The experimental Decoupled frames option now works with mods that expect
  rendering and game ticks to run on one thread: with other mods installed, a frame is drawn at a safe point of the
  tick instead of alongside it, so mods' render code never runs at the same time as their tick code; frames still
  don't wait for a long tick to finish. Fixes `ConcurrentModificationException` crashes from mods' render handlers
  (furnace_open) and `Image is not allocated` crashes from textures queued and freed before the frame used them.
  Thanks to Topirh for testing it in large modpacks.
- **HUD.** A Size setting (50-200 %) in Options -> Sleepium... -> HUD, and four new widgets for any corner: Position
  (coordinates, facing, biome), System (CPU and RAM), Network (ping, packets) and Clock (game and real time).
- **Faster:** mobs check whether they're stuck in a wall without scanning blocks when nothing solid is around them
  (-4 % server tick time with 30,000 mobs); the mob movement memo backs off for mobs it rarely helps (the farm
  slowdown of 1.3.5, +11 %, is down to about +3 %); a tick's packets leave in one batch at its end.
- **Fixed:** a rare hang when creating a world (a race in parallel structure generation).

Exactly vanilla: the exactness suite - 23 scenes identical to vanilla - on production servers with the released jars,
both versions, by default and with parallel entity ticks on.

## 1.3.5 - 2026-11-22

Servers with players, modded factories, chunk saving and world generation - and still exactly vanilla's game.

- **Many players on one server.** Every movement packet a player sends makes the server check every tracked entity
  against that player; Sleepium now checks the distance first (vanilla's answer for a pair out of range is known
  without the rest). With 100 players flying in a normal world this hidden cost (it does not show in /perf) went from
  33 to 10 ms a tick, 9 -> 15 TPS. Gives way to mods that change the same check (Moonrise, Sable, Steam 'n' Rails).
- **Block actions answered sooner on a slow server.** The acknowledgement of a placed or broken block goes out between
  ticks (with the block updates around it - the same packets in the same order) instead of a tick later: at 100 ms
  ticks 162 -> 53 ms. During a long server stall players are kept connected instead of "Timed out" after 30 s.
- **Modded factories.** Vanilla walks every loaded chunk every tick to see whether it is due for saving; Sleepium skips
  the chunks for which that walk would do nothing. A world with 562 kinds of modded machines: 18.4 -> 14.0 ms a tick
  (vanilla 17.9). Chunk saves with Immersive Engineering or Mekanism installed are prepared off the main thread again
  (their save listeners only add their own keys): 314 -> 81 us of main-thread time per save.
- **Far less disk load.** Region files are written through, as vanilla writes them, with neighbouring records in one
  write instead of flushing the drive's whole cache twice per batch; chunks at the edge of generation stay in a small
  memory ring instead of being written, read back and written again. Pregenerating with Chunky: the world disk 15 ->
  6 % busy (NVMe), 13 -> 9 % (SATA SSD), 19 -> 15 % (a USB stick). The same files and the same safety as vanilla.
- **World generation.** New chunks are lit without the round trips through vanilla's light task sorter, on a light
  thread of its own: x1.38 chunks/s with 16 threads (Ryzen 7 5800X), x1.16 on a 4-core Xeon; Chunky 451 -> 494
  chunks/s. 1.21.1: terrain noise computed octave by octave for blocks of columns, vectorised (+23 % on an Intel N100).
  1.20.1: this also fixes a vanilla race where a chunk could be lit before the light engine could see it.
- **Mobs.** A standing mob's movement is not computed again while nothing it reads has changed, and a villager's check
  for a rival at its job site skips its walk over every nearby mob when nobody else holds the site (farm animals -6 %
  on an N100, more on fast CPUs; villagers -10 %).
- **Experimental switches** (off; Options -> Sleepium): chunks keep reaching players on a busy server (new chunks let
  in by the server's own tick time - holes in view 80 -> 0 at 10 TPS, 3-4 ms a tick of cost); a tick's packets handed
  to the network once at the tick's end (1.21.1, -4..-8 ms a tick with 100 players); movement after a server stall
  judged by when the packets arrived (1.21.1, changes vanilla's check: no pull-backs after a lag spike).
- **Fixes:** a dedicated server no longer stays running after a failed stop (a mod's saved data throwing at shutdown);
  such a failure now names the saved data's class and file in the log (1.21.1).

Exactly vanilla: each change is checked against vanilla's own code (hundreds of millions of comparisons, no
difference) and by the exactness suite - 23 scenes identical to vanilla, now on production servers with the released
jar, on both versions. Tested on: Ryzen 7 5800X + RTX 4070 SUPER, two Xeon E3 servers (RX 570, HD 7870), Intel N100,
Raspberry Pi 4.

## 1.3.4 - 2026-11-21

Fixes.

- **A crash with Bosses of Mass Destruction** (#12, 1.20.1, seen in FTB Quests next to Hostile Neural Networks): a
  hook on entity movement asked a mob for its box while the mob was still being built, before the mod had set up its
  custom hitbox. The hook no longer calls into an entity that is not in a world yet. The crash screen's "disable this
  one" now switches off both parts that use that hook (block entity sleep and entity sleep); before, it switched off
  only one and the game kept crashing.
- **Linux: no ~12 s freeze** when the view area is rebuilt (render distance or dimension change, resource reload) and a
  faster exit. A chunk buffer closed before its first use deleted buffer 0, and Linux drivers answered every such
  delete with two GL errors (about 217 000 at render distance 12, ~30 MB of log). Since 1.3.2.

## 1.3.3 - 2026-11-21

Much faster world generation - still exactly vanilla's terrain - and where each mod's time goes.

- **New chunks generate up to 4.6x faster than vanilla.** Pregenerating 5 929 chunks with Chunky (1.21.1, Ryzen 7
  5800X): vanilla 77 chunks/s, 1.3.2 228, 1.3.3 359. A 4-core Xeon server: 47.6 -> 87.8 chunks/s over 1.3.2. A
  teleport into new terrain (render distance 12): vanilla about 10 s until every chunk around you is generated, 1.3.3
  about 2 s.
- **Generation scheduled by its real dependencies (1.21.1).** Vanilla 1.21 generates a status for a whole layer of
  chunks and waits for all of it before the next status; 1.3.3 starts each chunk's next step as soon as its own
  neighbours are ready, so every generation thread stays busy. On 1.20.1 the steps no longer queue through one worldgen
  mailbox and the main thread (+23 %).
- **Less work per chunk:** terrain blocks written straight into the chunk's storage, surface rules planned per column,
  the density-function trees compiled to straight code, ore placement and biome checks in features without repeated
  lookups. Worker CPU per chunk: about -35 % on 1.21.1, -31 % on 1.20.1 (-48 % in the Nether).
- **Exactly vanilla's terrain:** each change keeps vanilla's arithmetic in vanilla's order. Per-chunk terrain hashes are
  identical to vanilla in the Overworld, the Nether and the End, also with Terralith, Tectonic and Biomes O' Plenty;
  each change compares itself with vanilla's code inside the game (tens of millions of checks, no difference).
- **Mods page: where each mod's time goes** (a player's suggestion). After "Measure 15 s" a mod's page opens with its
  cost by kind - its blocks, its mobs (server and client), drawing its mobs, drawing its blocks (new), its event
  handlers - each with its share and the costliest types inside. The HUD's list shows the top 5 mods and what each
  mostly spends on.
- **Intel graphics:** terrain drawn without a slow per-vertex read on Intel UHD, and the frame-pacing fence placed right
  before the swap. Intel N100 against 1.3.2: standing +11.7 % (1.21.1) / +10.4 % (1.20.1), moving +7.2 % / +5.5 %,
  slowest 1 % of frames 10.5 -> 6.4 ms / 16.5 -> 9.2 ms.

## 1.3.2 - 2026-11-21

Smoother frames on Intel graphics, and fixes.

- **Fix: short freezes on Intel integrated graphics.** On some Intel drivers the game stopped for about a quarter of a
  second, several times a second, even with the view standing still. Sleepium draws the terrain with very few calls, so
  the driver queued dozens of frames ahead of the graphics card and then made the game wait until the card caught up.
  Sleepium now checks once per PC whether the driver queues that deep, and where it does, keeps the game at most 3
  frames ahead (the choice is remembered). Intel N100 (UHD Graphics), the slowest 1 % of frames: 257 -> 16 ms. On
  the AMD and NVIDIA cards tested nothing changes (the check leaves them alone). Switch: `lab.gpu.framesInFlight.auto`.
- **Fix: with very many machines of one kind, the cost check from 1.3.1 could decide too early** that their sleep does
  not pay off - before any of them had been idle long enough to fall asleep. It now watches a kind of machine at least
  that long first.
- **Sleepium's self-check of its extra render threads now covers GeckoLib mobs and items drawn from the graphics
  card** too (the check runs next to mods that wrap the entity draw call, like Entity Model Features). Before, these
  were never checked.
- 1.20.1 servers: no more "Attempted to load class ... for invalid dist" errors in the log with the experimental mob
  sleep switches on.

Tested before release on five machines and both versions:
- Windows: an RTX 4070 SUPER desktop, an Intel N100 mini PC (UHD Graphics), two older 4-core Xeon PCs with a Radeon
  RX 570 and a Radeon HD 7870; Linux: a Raspberry Pi 4 (ARM, its own Mesa V3D graphics) as client and as server.
- Every exactness scene (23 per version: mobs, villagers, redstone, pistons, hoppers, items, carts, fluids, crops
  and more) gives the same world as without Sleepium, bit for bit; the frames are identical to 1.3.1 bit for bit.
- Frame rates against 1.3.1 on every machine, standing and in flight; a test world with 562 kinds of modded machines
  (as fast as 1.3.1, about vanilla's speed); the settings screen on the release jars.

## 1.3.1 - 2026-11-19

Fixes: modded machines that got slower with Sleepium, a rare deadlock at world generation, Entity Model Features heads
and skulls, items in item frames, vanilla hoppers, and a quieter log.

- **Fix: worlds with many modded machines ran slower with Sleepium than without it (since 1.2.0).** Sleepium lets a
  machine "sleep" while nothing in it changes, and for mods' machines it checks every tick that nothing changed. For
  machines whose own tick is cheap, that check cost more than the tick it saved. Now Sleepium measures the check per
  kind of machine and stops it where it does not pay off. A test world with 562 kinds of block entities from 13 mods
  (Create, Mekanism, Immersive Engineering, Industrial Foregoing, Thermal, ...) x 32 each: 1.21.1 150 -> 16.4 ms a tick
  (15.6 without Sleepium), 1.20.1 135 -> 19.5 ms (19.8 without Sleepium).
- **Fix: a rare freeze (deadlock) while chunks were generated** with C2ME, ModernFix and Chunky (GitHub issue #10).
  Sleepium's analysis passed some classes through Mixin's transformer while the game ran, and two threads could take
  Mixin's locks in opposite order. It no longer does that while the game runs; classes it has not seen yet are
  analysed from the next start on.
- **Fix: Entity Model Features turned off its custom heads and skulls** in bigger worlds ("created more than 64
  times" in the log). Sleepium's extra render threads each built their own skull models; now they are built once per
  thread set. Switch: `lab.opt.SkullSetShareMixin`.
- **Fix: shields, chests, heads and banners in item frames or on the ground** were drawn by the extra render threads
  with the main thread's renderer at the same time. That failed now and then (Sleepium then drew them again on the main
  thread, so it cost time). Each render thread set now uses its own. Switch: `lab.opt.ItemRendererSetBewlrMixin`.
- **Vanilla hoppers sleep fully again** (since 1.2.0 they slept only with the per-tick check, which cost as much as
  the hopper's tick). Hopper test world on a low-power Intel N100 PC: 1.20.1 7.4 -> 2.4 ms a tick (7.1 without
  Sleepium), 1.21.1 1.9 -> 0.85 ms (3.2 without Sleepium).
- **Chunk saves stay on their own thread with Immersive Engineering and Mekanism.** These mods listen to chunk saves
  only to keep their own data, which works the same on Sleepium's save thread (checked on 1.5 million chunk sections).
  Switch: `lab.asyncsave.listeners`.
- **Quieter log** (GitHub issue #8): entity sleep prints one summary line from time to time instead of a line for every
  kind of mob. `-Dsleep.entsleep.verbose=true` brings the per-kind lines back; `/sleepium report` says why a kind cannot
  sleep.

## 1.3.0 - 2026-11-18

Much smoother flight over large worlds, Android at render distances above 7, an experimental decoupled-frames mode,
and a fix for freezes in big mod packs. The start of a new cycle: chunk loading in flight is the next one's subject.

- **Smoother flight at far view distances.** Flying in circles at 32 chunks while turning the camera and going up and
  down, compared with 1.2.6 on the same path: 162 -> 806-857 fps, the slowest 1 % of frames 39 -> 150-157 fps, the
  worst frame 47 -> 37 ms, and 8.9 -> 6.4 CPU cores. With the camera standing and turning: 715 -> 1120 fps, the
  slowest 1 % 213 -> 440. What changed, each with the same picture as before (checked frame by frame or value by
  value against the game's own code):
  - the lists of terrain to draw are built on several threads;
  - **Biome Blend** colours are worked out with running sums: the same colours for a fraction of the work (chunk
    meshing at the largest Biome Blend took 6 cores, now 2);
  - crossing a chunk border no longer re-reads all ~50 000 chunk sections in view nor compares the whole chunk grid;
  - a chunk's mesh no longer binds a GPU object it never uses (the most expensive part of putting a new chunk on
    screen).
  Switches (Options -> Sleepium): `lab.chunkmd.parallelLists`, `lab.tintBlend`, `lab.viewAreaStrips`,
  `lab.opt.UploadBindMixin`.
- **Android: render distance above 7 no longer crashes** (Adreno phones through MojoLauncher / LTW; every earlier
  version died within half a minute at 8 or more). The game made three GPU objects for every chunk section in view,
  empty or not - about 150 000 at distance 12, more than these drivers take. Now a section gets them only when it has
  something to draw: at distance 12, 1 617 instead of 75 000, and the phone runs there at 110 fps. On the desktop the
  same, with the picture checked bit for bit (about 1.5 million fewer GPU objects at 32 chunks). Switch:
  `lab.opt.LazyChunkBufferMixin`.
- **New, experimental: Decoupled frames** (Options -> Sleepium, off by default, at your own risk). The frame is drawn
  on its own thread with its own clock, and mobs are ticked on worker threads and drawn from tick-rate poses, so a long
  tick no longer holds a frame back. It helps where long ticks freeze the picture: in a 225-mod pack the longest
  freeze went from 1.8 s to 0.15 s (the average fps was lower, 255 -> 195); in a 474-mod pack flying over the world
  the slowest 1 % of frames went from 91 to 185-203 fps (average 280 -> 312-345). In scenes full of mobs it can be
  slower all round (the 225-mod pack's mob zoo: 122 -> 87 fps), so try it in your own world and keep whichever feels
  better. It touches how the game is scheduled, so some mods may not expect it: if something looks wrong, turn it off -
  a crash report that names it offers to turn it off at the next start. Works on Android launchers too.
- **Fix: freezes of up to seconds in big mod packs, with a mod that looks up a class every tick** (reported with
  Punchy and Better Combat 1.9.0 in a 250-mod pack). Sleepium's background analysis of mobs re-read their classes
  through Mixin, which holds a lock every other class lookup waits on. It now keeps those classes as the game loaded
  them, checks types from the class files without loading them, and never takes that lock for them; the few other
  classes it needs are remembered from the first start. In a 474-mod pack the time it held the lock went from 10.9 s
  to 0.04 s on the first start and to 0 after.
- **Fix: the same Mixin warnings printed again and again** by other mods (GitHub issue #8): each class goes through
  Mixin's transformer at most once.
- Less garbage while fluids settle in new chunks.

## 1.2.6 - 2026-11-15

Three fixes.

- **Fix: CustomNPCs could not register its script events.** CustomNPCs finds a private method of Forge's and
  NeoForge's event bus by reflection to register its events. Sleepium's faster event bus did not have that method, so
  registration failed (`NoSuchMethodException: dev.lull.optsupport.CompositeEventBus.register` in the log) and none of
  those events reached CustomNPCs. Scripted NPCs did not react to clicks. Sleepium's event bus now has the same
  registration methods as the loader's. This also fixes other mods that register events the same way. Checked with
  CustomNPCs-Unofficial on 1.20.1 Forge and 1.21.1 NeoForge.
- **Fix (1.20.1): event counting switched itself off on Forge 47.4.28 and newer**, with an error in the log (reported
  with Blood Magic). Forge's newer event bus guards its listener cache with a different kind of lock; Sleepium now
  handles both kinds.
- **Fix: a rare crash while drawing animated mobs** (`ArrayIndexOutOfBoundsException` in `ModelPart.compile`). When
  the render thread built a model part's geometry, it could share the part's reusable list with a worker thread drawing
  the same mob type at that moment. The render thread now uses its own list.

## 1.2.5 - 2026-11-14

Smooth camera turns at far view distances, and two fixes for the experimental GPU terrain.

- **No stutter when you turn the camera at far view distances.** Every time the camera turns by 2 degrees, the game
  works out again which chunk sections are in view. It walked a linked list of every loaded section and read each
  section's box, so the processor waited on memory for each one: about 7 ms a turn at 32 chunks. Sleepium now keeps
  the sections' positions in flat arrays and tests whole 32-block regions at once. It picks the same sections in the
  same order (frame hashes unchanged). The lists of sections to draw are rebuilt from compact per-section records too,
  without reading every section again. Turning the camera at 32 chunks on a desktop: 167 -> 1 124 fps, stutters
  38.6 -> 0.06 %; with the GPU terrain 131 -> 379 fps. A phone at 6 chunks: about +7 %. No change while the camera
  stands still. Switch: `lab.opt.FrustumRegionMixin`.
- **Fix: GPU terrain left a cross of missing chunks at view distance 31 and above** (since 1.2.0). A section's place in
  the GPU's grid did not fit in its field from 31 chunks on, and three columns and three rows of chunks were drawn over
  others.
- **Fix: turning on the GPU terrain on a device without OpenGL 4.5 crashed the game** (Android launchers with OpenGL
  3.3). The GPU terrain now checks the driver first and leaves the normal terrain when it cannot run. The same happens
  when its grid does not fit in video memory, for example a very tall world (JJThunder To The Max) at a far view
  distance.

## 1.2.4 - 2026-11-14

A fix for servers with Krypton.

- **Fix: players were disconnected from a server with Krypton** ("sleep coalesce: a frame cut inside a container",
  since 1.2.3; GitHub issue #7). Krypton writes a packet's length and its body separately; Sleepium's packet batching
  on the server could close a batch between them, and the 1.2.3 client found the packet cut in two. The server now
  closes a batch only between whole packets, and the client keeps a cut packet until its rest arrives - so a 1.2.4
  client also plays on a 1.2.3 server with Krypton. Checked with Krypton FNP on 1.21.1, both sides, old and new
  versions mixed. (1.20.1 with Krypton Reno was not affected - it writes a packet in one piece - and works the same.)
- If you cannot update the server yet: `lab.opt.NetCoalesce=false` in its `config/sleepium.properties` turns off only
  the packet batching.

## 1.2.3 - 2026-11-13

Tools for modpack makers, server owners and players: see what Sleepium does to each mod, what each mod costs and where
the server's time goes, change it per mod, ship your choices with the pack - and a fix for logins with Krypton.

- **Fix: joining a server with Krypton on the client failed** ("VarInt too long", GitHub issue #7). Krypton installs its
  network cipher its own way; Sleepium's network decoder now sits where it always sees decrypted data, whoever set up
  encryption. Checked with Krypton FNP (1.21.1) and Krypton Reno (1.20.1) against encrypted logins.
- **Options -> Sleepium -> Mods.** Every mod with what Sleepium does to it: the rules set for it, Sleepium's changes
  that gave way to it, which of its blocks and entities sleep - and why not ("can't prove its tick idle", "another mod
  changes it", ...). Per mod: sleep of its blocks (on / watched / off), sleep of its entities, render cache, helper
  threads, and whether Sleepium gives way to it (on a conflict / always / never - never: Sleepium's change wins and the
  mod's conflicting mixin is taken out). Each value says where it comes from.
- **Measure 15 s** (or `/sleepium profile 15` on a server): every block entity, entity, event listener and entity
  drawing timed per mod - exact, no sampling - and the ten chunks where the server's time went, with what is in them.
  **Write report** / `/sleepium report`: all of it in `config/sleepium-report.md`.
- **For pack makers: `config/sleepium-pack.properties`.** Save as pack settings writes it from your choices; ship it,
  lock keys with `lock.<key>=true`. Mod authors can declare rules about their own content in their mods.toml
  (`[modproperties.<modid>] sleepium = {...}`), no dependency on Sleepium. The order: launch flag, pack lock, the
  player's file, the pack's file, the mod's declaration, the defaults.
- **Find the cause.** When something goes wrong and you do not know which part: each round turns half of the suspects
  off until the next start, you answer Still happens / Gone; about 7-8 restarts for every setting.
- **After a crash in Sleepium** the title screen names the part and offers to start with it off.
- **HUD.** Frames and a frame graph, stutters taken apart (client tick, GC, the rest - also in
  `logs/sleepium-stutters.log`), the server's tick, GC pauses, CPU per thread, memory, the mods that cost most right
  now, what sleeps - each widget in the corner you choose. Drawn a few times a second into a texture; data only for the
  widgets shown.
- Several Sleepium jars in the mods folder: a warning (the log, the chat) naming the one used.
- The sound setting (sounds without waiting for the sound thread) is in the settings screen now.

The picture is the same as 1.2.2 bit for bit (frame hashes on both versions).

## 1.2.2 - 2026-11-13

A fix first. Since 1.2.0 machines of some mods could stop: a Thermal dynamo given fuel stayed off. Sleepium lets a mod's
machine sleep while it watches it idle when its code cannot be proven (the "machines of mods" setting, on by default);
for machines whose tick runs through the mod's own interface that watching was skipped, and they slept unwatched.
Found from a player's question about energy mods. Also: no waiting on the sound thread, measured choices kept per
machine instead of measured in the running game, and the log names the mod Sleepium gave way to.

| | before | 1.2.2 |
|---|---:|---:|
| Thermal dynamos fed fuel (mod exactness, 2 400 ticks) | stay off | run as without Sleepium |
| a 229-mod pack with Sound Physics Remastered, a burning zombie horde (desktop) | 11 fps | 41 fps |
| the same pack, its own shaders (Complementary), at spawn | 268 fps without Sleepium | 310 fps |
| Intel N100, joining a world: the longest frame from Sleepium's chunk buffers | up to 89 ms | 1.2 ms |
| a phone (MojoLauncher LTW), 355 armoured mobs: the slowest frames (p99) | 76 ms | 45-50 ms |

- **Mod machines sleep safely.** A machine whose tick Sleepium does not follow to the end now sleeps only watched: all
  of its own state is compared on every skipped tick, it falls asleep only after 64 idle ticks (mods act on their own
  timers - Thermal's dynamos check every 10 ticks whether to start), and it also watches the blocks around it it sends
  to or takes from (a transmitter idle because its target is full wakes when the target drains). Checked with the
  Thermal series, Draconic Evolution and Flux Networks (Draconic's energy blocks and Flux Networks' devices never slept:
  untouched), and with Mekanism, Immersive Engineering, Industrial Foregoing, Functional Storage, Applied Energistics 2,
  PneumaticCraft, Create and Sophisticated Storage: the world comes out the same with and without Sleepium.
- **Sounds no longer stall the game.** Starting a sound made the game wait for the sound thread to hand it a channel;
  with a mod that does heavy work there (Sound Physics Remastered traces rays for every sound) a crowd of mobs held each
  frame up. Now the sound starts without waiting, in the same order on the sound thread; when that thread is far behind,
  a new sound is dropped the way the game drops one when no channel is free. Where the sound thread keeps up nothing
  changes (Intel N100, a vanilla horde: the same fps).
- **Measured once per machine.** The choices Sleepium measures (entities through the render cache or the helper threads,
  model parts on the helper threads, animated textures blended on the graphics card or the processor, chunks drawn by
  indirect or direct multi-draw) are kept in `config/sleepium-calibration.properties` for this machine and graphics
  driver: no more seconds of the slower way when a world opens. The blend is measured in the main menu. Options ->
  Sleepium -> Recalibrate measures again. The entity choice now compares whole frames, one choice at a time.
- **Chunks: indirect or direct multi-draw, by what is faster here.** Measured in the game on the first world (desktop
  graphics: indirect; Intel integrated: direct), kept per machine. The same picture either way.
- **Intel: no stalls from Sleepium's chunk buffers.** A region's buffer that grew was deleted while the graphics card
  was still copying from it, and Intel's driver waited: up to 89 ms in one frame while joining; now 1.2 ms.
- **Animated textures blended on the graphics card** no longer rebuild their tables over and over (more than 256
  distinct blend steps cleared them all): a ring of 384 kept; the client tick 20-25 % shorter on an Intel N100.
- **Mod packs: the log says who Sleepium gave way to.** Each "not applied" line names the other mod and its mixin, for
  example `ParallelEntitiesMixin not applied: ... - by figura (LevelRendererMixin)`.
- **Dedicated servers:** no more "Attempted to load class ... ClientLevel for invalid dist" error at start (harmless,
  but it looked like a crash).
- New setting, off: entities next to mods that hook their drawing (Shoulder Surfing, Entity Model Features...) can
  go to the helper threads when Sleepium finds at start that the mod passes nothing to the models they read.

## 1.2.1 - 2026-11-13

Android and older graphics. 1.2.0 crashed at start on any graphics driver without OpenGL 4.3 - phones and tablets
running Minecraft through a launcher such as MojoLauncher, older or translated drivers. Sleepium now asks the driver
what it really supports and picks a path that works there, and most of its graphics work has a path for OpenGL 3.3.
Measured on a phone (Snapdragon 855+, Android 12, MojoLauncher with the LTW renderer, Forge 1.20.1, view distance 6),
an Intel N100 laptop and a desktop (Ryzen 7 5800X + RTX 4070 SUPER):

| | vanilla | Sleepium 1.2.0 | Sleepium 1.2.1 |
|---|---:|---:|---:|
| phone, 355 armoured mobs with swords, item frames, chests | 8 | crashes | 42-58 |
| phone, the same, the slowest frames (p99) | | | 33-36 ms |
| phone, plain terrain view | 147 (p99 18 ms) | crashes | 222 (p99 10 ms) |
| N100, plain terrain view | 88 | 117 | 118 |
| N100, armour stands, item frames, chests, signs, skulls | 36-42 | 133 | 132 |
| N100, a pile of dropped items | 49-56 | 183 | 183 |
| N100, a horde of mobs | 112-119 | 182 | 183 |
| desktop 1.21.1, F3 open | | 986 | ~1 300 |

Desktops and laptops keep 1.2.0's speed (the N100 rows) and draw the same picture: frames from 1.2.0 and 1.2.1 compared
bit for bit on both Minecraft versions.

- **Android: Sleepium runs, and runs fast.** Tested with MojoLauncher's LTW renderer (OpenGL 3.3 over the phone's
  OpenGL ES). Every feature that needs more than the driver has now checks first - by compiling a test shader, not by
  trusting what the driver claims (LTW reports storage buffers in vertex shaders that it cannot compile) - and uses an
  OpenGL 3.3 path or the game's own. On the phone: the entity scene above 8 -> 42-58 fps (the phone's temperature
  decides where in that range), the client tick 19.5 -> 6-7 ms. GL4ES (OpenGL 2.1) starts but is slow even without
  Sleepium; Zink and Freedreno are not tested yet.
- **Terrain drawn per region where the driver has no per-draw data in shaders** (phones): a region's sections share one
  buffer and one draw call. Phone terrain: 2.74 -> 0.58 ms a frame. The same picture to a fraction of a pixel at
  geometry edges.
- **Model parts, held and framed items and particles drawn from graphics memory on OpenGL 3.3** too, through texture
  buffers and instancing instead of storage buffers.
- **Animated textures that blend between frames** (crimson and warped stems, prismarine, sculk, campfires, furnaces,
  command blocks...) can be blended on the graphics card instead of on the processor and uploaded. Same pixels, checked
  against the processor's blend; before it touches the texture atlas, the first blend is drawn into a scratch texture
  and checked. On the phone: the slowest client ticks 31 -> 10 ms, the slowest frames 62 -> 36 ms.
- **Choices by measurement.** Some of the above help one machine and cost another, so Sleepium measures on the running
  game and keeps what is faster there: the render cache or the helper threads for entities (a phone's slower cores:
  the threads, 23 -> 39 fps; desktops: the cache), model parts from graphics memory for entities drawn on the helper
  threads (desktop 1.21.1, cache off: 558 -> 759 fps; Intel's integrated graphics: off - its driver handles the many
  small draws slowly), blending animated textures on the graphics card (the phone: yes; desktops and Intel: no). Each
  takes a few seconds of measuring when a world opens or a crowd appears, and again after 5 minutes or when the number
  of entities halves or doubles; the log says what was chosen and why.
- **F3 no longer halves the frame rate.** Its lines are gathered 10 times a second instead of every frame, and the text
  is laid out once per refresh (1.21.1: 986 -> ~1 300 fps with F3 open, 1 890 closed; 1.20.1: 648 -> 760-793). Sleepium's
  HUD shows the F3 entity and chunk counts, for testers who open F3 only for them.
- **Crowds in armour and with items in hand** (helper threads): item quads written without a per-quad memory-stack
  lookup, and on 1.20.1 armour texture paths kept instead of formatted for every piece of every mob every frame
  (desktop, many armoured mobs on the helper threads: +5-15 %). Same picture, pixel for pixel.
- **Fixed:** the render cache's records could be read by the helper threads while being changed (a rare race); a shader
  that failed to compile and was retried lost its imports; JOML's direct matrix writes are not used (they fail on
  Android's Java).
- **Known, for 1.2.2:** the measuring above happens in the running game - a few seconds of the slower way when a world
  opens or a large crowd appears, which can look like a short stutter (most visible on a phone: about 5 s). 1.2.2 will
  remember the results per machine and measure what it can in the main menu, so it happens once, out of sight.
- **Android renderers:** tested with MojoLauncher's LTW (OpenGL 3.3 over OpenGL ES). Zink / Turnip and Freedreno (full
  OpenGL 4.6 on Adreno) are not tested yet; GL4ES (OpenGL 2.1) starts but is slow even without Sleepium. The aim of
  this release is to run everywhere first - Sleepium checks what each driver can do and falls back to the game's own
  way where it cannot.
- For testing: thread placement on Linux / Android (`lab.prender.pin`, `lab.prender.cpuStats`), off by default (it
  made no difference on the phone); each measured choice has a switch to fix it (`lab.rcache.tune`,
  `lab.partbatch.partsTune`, `lab.gpuAnim.interp=gpu|cpu`).

## 1.2.0 - 2026-11-12

Final numbers on the release build, against the same game without Sleepium's optimisations: an Intel N100 laptop
(Intel UHD Graphics) and a desktop (Ryzen 7 5800X + RTX 4070 SUPER), Minecraft 1.21.1 unless noted.

| | vanilla | Sleepium 1.1.11 | Sleepium 1.2.0 |
|---|---:|---:|---:|
| N100, plain terrain view, fps | 88 | 81-82 | 118-120 |
| N100, the same with the experimental GPU terrain | | | 181-184 |
| N100, armour stands, item frames, chests, signs, skulls | 36-42 | 109-110 | 134-135 |
| N100, a pile of dropped items | 49-56 | 140-141 | 182 |
| N100, a horde of mobs | 112-119 | 137-142 | 161-180 |
| desktop, the same entity scene | 69 | | 2 152 |
| desktop, terrain view | 332 | | 3 739 |
| desktop, 60 working Immersive Engineering crushers | 177 | | 1 786 |
| desktop, 500 working crushers (with their particles) | 265 | | 2 103 |
| desktop, 500 crushers piling ~2 000 moving items | 32 (p99 94 ms) | | 423 (p99 15 ms) |
| 1.20.1, 1 440 mobs with Entity Model Features + Fresh Animations | 11 | 25 | 43 |
| 1.20.1, 144 such mobs | 42 | 541 | 594 |
| 1.20.1, Biohazard (474 mods, Embeddium + Oculus) | 427-430 | 466-472 | 461-482 |
| world generation, chunks per second (desktop / N100) | 54 / 13 | | 107 / 35 |

- **Laptops and mini PCs with Intel graphics: about 1.4x faster on terrain.** On Intel's integrated graphics the way
  Sleepium hands chunks to the graphics card in one call (the "indirect" draw) cost the graphics chip more than it saved
  the processor: on an Intel N100 the plain terrain view ran at 82 fps with Sleepium against vanilla's 88. Sleepium now
  uses the direct form of the same call there: the terrain view 82 -> 120 fps, a view over water 151 -> 210, and the
  entity, item and mob scenes above gained 12-30 % from it too. Same picture. Other graphics keep the indirect form (on
  an RTX 4070 SUPER it is the faster one: 3 660 vs 2 040 fps); -Dlab.chunkmd.indirect=true / false overrides.
- **1.20.1: mobs with Entity Model Features drawn on several threads.** EMF 3.3.12 with Entity Texture Features 7.2.x
  (the versions in many current 1.20.1 packs) kept every entity on the main thread: both mods keep "the entity being
  drawn" in shared fields. Sleepium now gives each drawing thread its own copy of those fields and makes their caches
  safe to share - only when the two mods are exactly the versions it was built against (anything else: as before, and
  the log says why). 1 440 mobs with Fresh Animations: 25 -> 43 fps (vanilla 11). Animations are EMF's own; far mobs
  are animated every frame on the helper threads instead of skipping frames, so their poses are not bit-for-bit those
  of the main thread. Other EMF versions (3.2.4 in Biohazard, Better MC) and 1.21.1: later.
- **Particles: up to 2x faster where there are many.** Each particle list is drawn in one go from a small record per
  particle instead of four corners written per particle every frame; long lists are ticked on several threads (only
  particles the game can prove touch nothing but themselves; the rest the usual way, in the usual order); a particle's
  collision shapes and light are looked up once per block instead of every tick. 500 working Immersive Engineering
  crushers, which throw a lot of particles: 94 -> 192 fps from the threads alone, 253-277 -> 305-323 with the records.
- **Moving parts of machines drawn from graphics memory.** Machines whose renderers draw baked models every frame
  (crushers, turrets, ...) hand the graphics card a position per part instead of all its corners. 60 working crushers:
  vanilla 133, 1.1.11 560, now 979 fps. Same picture, pixel for pixel.
- **Smoother frames.** On Windows the render thread and the singleplayer server run above normal priority, so background
  programs and the game's own helper threads do not push them aside (Intel N100: stutters 4.49 % -> 1.35 % of frames,
  the slowest frames 41.8 -> 22.2 ms; a setting, Threads). Together with the rest, the slowest frames (p99) on the N100
  scenes above went from 15-21 ms with 1.1.11 to 11-15 ms.
- **Machines of mods sleep when idle again, now on by default.** Machines whose tick Sleepium's analysis cannot follow
  (Industrial Foregoing, Functional Storage, PneumaticCraft and other Titanium-based mods) are put to sleep while
  Sleepium sees them idle and woken by any change it observes. We checked them exact on the mods we test, and a test
  that removes a wake on purpose shows the difference, so the check works. Mods are many, though: **if a machine of
  some mod stops, slows down or behaves differently, turn off lab.sleep.ObservedModSleep (Options -> Sleepium -> Ticks
  & mobs) and please tell us which mod and machine.**
- **Fixed: a crash next to Bons and Furious** (and any mod that changes the same entity-storage call). When Sleepium gave
  way to such a mod in one place, a part that depended on it still applied and the server stopped at the first entity
  move. Contributed by BonsUnleashed (GitHub #6) - thank you. The log now names the whole chain of what was left out.
- **Entities in modpacks: drawn on several threads again.** Many mods listen to "an entity is being drawn" events
  (Immersive Engineering does). Until now one such listener kept every entity on the main thread (1.21.1); now only
  entities whose renderer actually sends that event stay there. Dropped items with IE installed: 1 579 -> 2 233 fps.
- **Next to Iris / Oculus with shaders off: mobs drawn on several threads.** A horde of mobs with Sodium and Iris: 796 ->
  1 012 fps (Sodium alone: 1 087). With a shaderpack on, entities stay on the main thread (the shader needs each
  entity's id there).
- **Fixed: Immersive Engineering's water wheels, windmills and bucket wheels were invisible.** Their renderers draw into
  IE's own buffers later in the frame; the render cache saw an empty drawing and skipped them. A renderer that writes
  nothing is now drawn the vanilla way.
- **Dropped items and other entities: cheaper physics.** Entities on the client tick on several threads (dropped items,
  arrows, boats - not mobs), and the checks every moving entity makes each tick ("am I touching fire or lava", "am I in
  water", "what am I standing on") no longer build lists of positions or ask about every block when there is nothing to
  find. A pile of 2 500 dropped items: client tick 13.1 -> 11.3-11.6 ms (1.21.1).
- **Animated textures (water, lava, fire, portals, mods' animated blocks) updated on the graphics card.** A frame change
  is copied from the texture itself instead of being uploaded from memory again.
- **New, experimental, off by default: GPU terrain with level of detail.** Chunks are built into geometry on the graphics
  card, and far terrain is drawn as larger cells (beyond 6 chunks, coarser beyond 10; a "closer" option uses 4 / 8 for
  weak graphics). Near terrain looks the same; far terrain is simplified. Intel N100: the terrain view 88 (vanilla) ->
  181-184 fps. Options -> Sleepium -> Terrain: "GPU terrain", plus "level of detail closer" and "LOD cone". Blocks whose
  look depends on their neighbours (connected textures, Create's casings) are drawn the vanilla way near you. It turns
  itself off next to mods that replace the terrain renderer (Sodium, Embeddium and the shader mods on them) and says so
  in the log.
- **Machines of more mods are checked before they sleep.** The analysis that decides whether a block entity can be put to
  sleep finds tick functions created by helper classes, and no longer trusts state it cannot verify.
- **1.20.1: the command is /sleepium** (as on 1.21.1).
- **Tested together with:** ModernFix, Fast Noise, Lithostitched (world generation 54-76 -> 107-131 chunks per second,
  with or without them), Bons and Furious (both versions: no crash,
  Sleepium steps aside in 14 / 17 places), Industrial Foregoing and Functional Storage (exact, with and without the
  machines' sleep). Exact: the world and frame checks (23 scenes on each version) as before.

## 1.1.11 - 2026-11-09

- **World generation with datapacks like JJThunder To The Max: up to 2x faster.** To pick a biome, the game works out a
  few "climate" values for every 4x4x4 block cell. Most of them do not depend on height, and vanilla's own world
  generation marks those so they are computed once per column. Datapacks that leave the mark out make the game compute
  them again for every cell up the column - in JJThunder's 2 000-block-tall world that is about 500 times per column,
  over 60 % of all generation time. Sleepium now finds the parts that do not depend on height by reading the datapack's
  own functions and computes them once per column. The result is the same world, block for block and biome for biome
  (checked by comparing every generated chunk with the change on and off). JJThunder with Chunky: 108-116 -> 195-233
  chunks per second (vanilla 37). A normal world and Biomes O' Plenty are already marked: no change there. A setting
  (World generation -> WorldgenColumnMemoMixin), on by default; both 1.21.1 and 1.20.1.

## 1.1.10 - 2026-11-09

- **Fixed: world generation far from the world's centre was slower than vanilla on 1.21.1.** Each mob placed in a new
  chunk is first created at the world's centre and then moved to its place; Sleepium looked at everything between the
  two points to see who that move might wake. Far out that is a huge area: 64 000 blocks from the centre, generation
  ran at about half vanilla's speed (20-34 chunks per second against 55). Now only the old and the new place are looked
  at: 123-126 chunks per second there, and next to ModernFix, Fast Noise and Lithostitched 105-126 (those mods alone:
  66-81). Long teleports were slowed the same way, less (20 armour stands sent 28 000 blocks: about one extra server
  tick per teleport; the farther, the more), now they are not. 1.20.1 did not show the slowdown in the same test; it
  gets the same fix.
- **Fixed: blocks moved by pistons could show the light of a moment ago for a frame.** Their drawing is cached, and the
  cache learned about light changes only after the new light was already in use. Now it knows before. (In our check
  the mismatches fell 17 times on 1.21.1 and 4 times on 1.20.1; what is left comes from the light being computed in
  the background while a frame is drawn.)
- **1.20.1: moving blocks up to 1.9x faster.** Blocks pushed by pistons and falling blocks are now drawn from that cache,
  as on 1.21.1: a scene with many moving pistons 790-979 -> 1 515-1 552 fps, the slowest frames (p99) 5.7-6.4 ->
  2.9-3.3 ms.

## 1.1.9 - 2026-11-09

- **Fixed: 1.1.8 was slower next to ImmediatelyFast** (and other mods that batch the drawing the same way; ImmediatelyFast
  is in most modpacks). Sleepium prepared dropped items for the graphics card, could not hand them to that mod's
  buffers and then drew them again the normal way - twice the work. Now items, and on 1.20.1 also entities drawn with
  transparency, go to the graphics card next to such mods too, and anything Sleepium adds to a batch is drawn exactly
  once even when the mod draws that batch in several pieces. 1 128 dropped items + 1 081 experience orbs next to
  ImmediatelyFast, 1.21.1: 1.1.7 1 057 fps, 1.1.8 498, 1.1.9 1 765. 1.20.1, the same items among more entities: 1.1.8
  159 fps, 1.1.9 718 (681 without ImmediatelyFast). The picture is the same, up to a few pixels of graphics-card
  rounding.
- **Entities that a mod adds from another thread go where they belong** (GitHub #4, I3AT5). Some mods react to the
  starting mobs of a newly generated chunk by adding extra mobs straight into the live world from a world-generation
  thread (Easy Mob Spawn Control; Cupboard warns "add an entity from offthread"), which can occasionally cause
  trouble, with or without Sleepium. Now such an entity goes into the chunk being generated, or is added on the server
  thread. Mods that add entities the normal way are not affected. A setting (World generation -> OffThreadEntity), on
  by default.
- **"Pose stack not empty" crashes name their cause** (GitHub #5). When an entity's rendering leaves a pose pushed
  (a mod's renderer, or another mod's change to it), the game stops with this error after the entities are drawn,
  without saying who. Sleepium now writes one line to the log first - "[sleep/opt] pose stack: ..." with the entity,
  its renderer and the other mods' mixins in that renderer. Nothing else changes.
- **New setting, off by default: ObservedModSleep** (Ticks & mobs). Mods' machines whose tick Sleepium's analysis
  cannot follow (Industrial Foregoing, Functional Storage, PneumaticCraft...) sleep on what Sleepium observes, as they
  did before 1.1.8. Faster for idle machines; in our checks the result matched vanilla, but it is not proven, so it
  stays off unless you turn it on.
- **Checking Sleepium against mods, first steps** (see "Help wanted" in 1.1.8): every block of a mod is placed, fed
  and drained through the standard item / energy / fluid connections, put to sleep and woken, and compared with
  vanilla tick by tick; a deliberately broken wake-up must be caught. First results on Industrial Foregoing,
  Functional Storage, PneumaticCraft and Farmer's Delight: no differences caused by Sleepium.

## 1.1.8 - 2026-11-08

- **Dropped items are drawn by the graphics card.** Items on the ground, in item frames and in mobs' hands no longer
  rebuild their 3D model every frame: the model is kept in video memory and only its position is sent. A field of
  1 128 dropped items and 1 081 experience orbs: 1 066 -> 2 094 fps (1.21.1), 980 -> 1 621 fps (1.20.1); vanilla 182 / 128.
  The picture can differ from vanilla in a few pixels (float rounding on the graphics card). It switches itself off
  next to Iris and ImmediatelyFast (items are then drawn the vanilla way) and can be turned off in the settings
  (Entity rendering -> ItemGpu).
- **Experience orbs and other moving entities collide with blocks for less.** Sleepium now knows, per chunk section,
  which blocks can never collide (air, grass, flowers...) and skips them without reading them; experience orbs, which
  ask about collisions every tick without saying who asks, now use Sleepium's collision memory too; and an entity's
  move no longer builds lists and objects for every block it passes. Every result is the same as vanilla's, checked
  against vanilla tens of millions of times. A crowd of 2 000 orbs and 1 500 items being picked up: 194 -> 215 fps
  (vanilla: 1 fps); on an Intel N100 laptop-class PC 23 -> 25 fps (vanilla: 1 fps). Servers: 7-13 % less time per tick
  with many orbs, items, mobs or villagers (11-14 % on the N100).
- **Tip: Sleepium and [Clumps](https://modrinth.com/mod/clumps) work well together.** Clumps merges experience orbs
  (it changes how orbs behave, which Sleepium itself never does). In the orb crowd above: Sleepium + Clumps 996 fps,
  Clumps alone 128; on the Intel N100 89 fps against 29 with Clumps alone.
- **Less garbage per frame:** shader values, name tags of entities whose name is not shown, and the vertices of every
  renderer no longer create throwaway objects.
- **Servers: `config/sleepium.properties` is created on the first start** (it was only written by the in-game
  settings screen, which servers do not have), with every switch, what it does and its default, all commented out so
  new defaults keep reaching you. Remove the `#` in front of a line to change it, then restart (GitHub issue by I3AT5).
- **1.20.1: the settings screen was missing 56 switches** (chunk rendering, entity batching, GeckoLib and others): they
  were on, but could not be switched off. They are all there now.
- **Entity analysis could fail next to a mod that patches the bytecode library (ASM)** ("analysis failed:
  method.tryCatchBlocks is null" in the log; those entities then simply ticked the vanilla way). Sleepium now handles
  it (reported by I3AT5).
- **Block-entity sleep: mods' machines are analysed the whole way.** A mod's ticker that calls its block entity
  through an interface (Industrial Foregoing and Functional Storage machines, PneumaticCraft blocks) was skipped by
  the analysis, and those machines were allowed to sleep on what Sleepium observed while they ran alone. They kept
  working the same as in vanilla (checked), but without a proof; now their tick is analysed, and a machine sleeps only
  when it is proven it may. Found by placing every block of 13 popular mods (1 843 blocks) and reading the verdicts.
- **Block-entity sleep is checked by value where a mod's block entity bypasses Sleepium's hooks**, so a sleeping
  machine wakes up exactly when its items change, whatever way the mod changes them; a block entity whose items
  cannot be read that way does not sleep.
- **Two small costs found in a 200-mod pack's profile:** Sleepium's tick timer built the name of every dimension as
  a string every tick, and its event bus read one flag the slow way on every event (together about 1.5 % of the
  server thread in that pack).
- **Help wanted: checking Sleepium against mods.** Sleepium lets a block entity sleep only when it can prove from the code that skipping its tick changes nothing,
  and every release is checked against vanilla scene by scene. Mods are where that proof is hardest: a mod's machine
  may keep its state in its own objects, use its own inventories and energy, or tick in ways our tests have not seen
  yet. This release found and closed one such gap (machines whose tick goes through an interface) by placing every
  block of 13 popular mods and reading what the analysis made of them. Next we want an automatic check that works for
  any mod: every machine fed and drained through the standard item / energy / fluid connections, put to sleep and
  woken in every way a player can, and compared with vanilla tick by tick.

  If you know a mod whose machines behave differently with Sleepium, know a good way to test this kind of thing, or
  want to help run such checks on your pack, please open an issue on GitHub or leave a comment - we will be grateful.
  A link to the mod or the pack is enough: we download mods only from their official pages.

## 1.1.7 - 2026-11-07

- **1.20.1 with ModernFix: the server reacted up to a tick late** (servers and singleplayer). ModernFix replaces the
  server's idle wait, and Sleepium's wake-up saver (it skips waking a thread that is not waiting) never saw that wait,
  so everything handed to the server thread - players' actions, finished chunks - waited for the next tick: 23 ms on
  average instead of 0.1 ms. Sleepium now adds its hooks to methods that another mod or the game overrides, after every
  other mod's changes, so this kind of conflict cannot happen silently again. Measured with ModernFix: tasks 23 ms ->
  0.08 ms; the spawn area of a new world prepared in 17.0 s instead of 19.5 s (2.6 s instead of 7.0 s on a flat world).
  1.21.1 was not affected.
- **1.20.1: the game could crash at startup next to some mods** (reported with Immersive Fixes): a mod that touches
  Forge's event bus while other mods are still loading in parallel hit a race in how Sleepium sets up its event bus
  ("Index ... out of bounds", "Could not initialize class MinecraftForge"). Sleepium no longer does the step that
  raced. It happened mostly in bigger packs and not every time.
- **1.21.1: a crash when drawing the first entity with EntityBatch switched off** in the settings (or when it steps
  aside for another mod). Parts that other optimisations rely on are now kept on whenever any of them is.

## 1.1.6 - 2026-11-07

- **Singleplayer: joining a world could take minutes next to ModernFix and mods that wait for server data while
  loading** (reported with Advanced Loot Info + JEI in Project Infinity: 120 s instead of 30). While the game is on
  its loading or pause screen, the built-in server does not run its normal tick, and ModernFix replaces the server's
  idle wait. Those were the two moments Sleepium's network batching sent what it had collected, so packets sent then
  waited until the game was unpaused: ALI asked for its loot data three times and gave up. Sleepium now batches only
  inside the server tick, where the send at its end always comes; everything the server sends outside a tick goes out
  at once, as without Sleepium. Tested in Project Infinity: ALI gets its data the same second, the world opens in
  38 s. On servers nearly every packet is still batched (1 278 of 1 279 in our test).
- **Settings screen:** a switch marked "(part of X)" now takes you to X when clicked, instead of being a grey button
  that does nothing.

## 1.1.5 - 2026-11-07

- **1.21.1: joining online-mode servers could hang on "Encrypting..." and time out** (GitHub issue #2). On the
  client, Sleepium's network decoder ended up in front of the decryption step and read encrypted bytes as packet
  frames. It now sits behind the decryption, as it already did on 1.20.1. Tested with a real encrypted login: the
  1.1.4 client timed out, 1.1.5 joins every time, and Sleepium's network compression still works after it.
- **Crash at startup next to mods that change the same entity render call** (a mod with `mixins.marrelics.json`,
  reported on CurseForge): Sleepium's entity and block entity render caches now wrap that call instead of replacing
  it, so other mods' changes to it keep working and the caches stay on (before, the fix would have been to switch the
  cache off: 1 443 -> 255 fps on our test scene; now 1 411). For Sleepium's other call replacements, another mod
  changing the same call's arguments now makes Sleepium step aside instead of crashing.
- **Crash next to Gnetum** (and other mods that reach into the event bus' internals): Sleepium's event bus is now a
  real event bus of the loader, not a wrapper around one, so such mods find what they expect.

## 1.1.4 - 2026-11-07

> **If you played with Sleepium before 1.1.4 together with C2ME** (or another mod that saves chunks on its own
> threads): chunks saved during that time may be broken - parts of them missing, the log saying
> `No key palette in MapLike[{}]`, terrain that looks hollow or does not appear. 1.1.4 saves them correctly from now on
> (checked by generating with C2ME, restarting and reading every chunk back: no errors), but it cannot bring back data
> that was never written. Regenerate those chunks: delete them with MCA Selector (or the region files of that area in
> `saves/<world>/region`), or start a new world. Anything built in those chunks is lost when they regenerate.

- **World creation crashed with datapacks that make the world very tall** (found testing JJThunder To The Max):
  `ArrayIndexOutOfBoundsException` in the aquifer generation once the world reaches past y 2047. Sleepium's aquifer
  shortcut read grid points whose height no longer fits the game's packed block positions; such cells now take the
  game's own path. The generated terrain is the same as without Sleepium (per-chunk hashes compared).
- **Next to C2ME, chunk saving could fail** and world generation stall (crash reports, "Failed to save chunk"): C2ME
  saves chunks on its own threads, and Sleepium's off-thread chunk saving shared its state between threads. Each
  thread now has its own; checked byte for byte against the game's own encoding (286 628 sections, 0 differences).
- With JJThunder To The Max on a 16-thread CPU (pre-generating with Chunky): vanilla 30 chunks/s, C2ME 48,
  C2ME + Sleepium 56, Sleepium alone 93.

## 1.1.3 - 2026-11-06

- **Event hooks next to other mods: no more conflicts, and the speed-up is back.** Sleepium rewrites the loader's
  event posts so an event nobody listens to costs nothing. It now does this after every other mod's changes to the
  class (including MixinExtras' wraps), so a post another mod wraps simply stays theirs and every other post is still
  rewritten. 1.1.1 avoided the crash by leaving whole methods alone - next to Collections Of Optimizations that was
  every event of the client hooks. The log now names the methods whose events other mods wrap and the mods' mixin
  configs, e.g. `posts other mods wrap, left to them: onLivingTick (collections_of_optimizations.mixins.json)`.
- **Entity sleep next to mods that inject into entities** (GitHub issue #1, Collections Of Optimizations): the
  analysis of 15 entity kinds (items, minecarts, item frames...) failed with "Insufficient maximum stack size", so
  those entities never slept. The analysis now sizes the code other mods changed itself.
- The render cache learns, by default, which entity fields change without changing what is drawn (a mod's
  per-tick field re-recorded every entity every frame): the client_entities world with Collections Of
  Optimizations 94-188 -> about 2 190 fps.

## 1.1.2 - 2026-11-06

- **The settings screen works again** (broken since 1.1.0, reported on CurseForge): every switch was locked and shown
  OFF, with "(part of ␍)" next to the names. The screen's catalogue went into the jar with Windows line ends and
  each option was read as part of a flag named by a stray line end. The optimisations themselves were never off -
  only the screen showed them wrong and could not change them. The catalogue is read with any line ends now, and the
  jar's text files are packed as the repository has them.

## 1.1.1 - 2026-11-06

- **A NeoForge server did not start next to some mods** (reported on CurseForge): `VerifyError: Operand stack
  underflow` in `CommonHooks.onEntityIncomingDamage`. Another mod wraps that method's event post (MixinExtras), and
  Sleepium's rewrite of NeoForge's event posts replaced the very instruction the wrap is built around. Sleepium now
  leaves every such method alone: a method another mod's mixin changes keeps NeoForge's own post. Reproduced with a
  stand-in mod (the same crash before, a started server after); without such mods nothing changes. Workaround for
  1.1.0: `-Dlab.opt.EventSiteMixin=false` in the server's `user_jvm_args.txt`.
- Entity Model Features: the hook of the animation cut is optional again - an EMF version without that spot runs
  without the cut instead of not starting.

## 1.1.0 - 2026-11-06

**Minecraft 1.20.1 / Forge is supported** (`sleepium-1.1.0+mc1.20.1-forge.jar`, Forge 47.4.9 or newer, Java 17 or
newer), next to 1.21.1 / NeoForge (`sleepium-1.1.0+mc1.21.1-neoforge.jar`). Jars now name the loader too.

Both versions:

- **Next to other optimisation mods, the gains are no longer lost.** Tested one mod at a time and as a pack:
  - Entity sleep works next to mods that change `Entity` (Lithium, Radium, Canary - most packs): before, no item ever
    slept with them. The analyses now read what other mods' mixins change as it runs. Items with Lithium 4.32 -> 3.37
    ms per tick; with Radium on 1.20.1 3.92 ms against Radium's own 5.58. Exact (world hashes).
  - ModernFix no longer turns off the collision shape cache and with it the collision memo (mobs with ModernFix 4.13 ->
    3.17 ms per tick on 1.20.1).
  - Iris / Oculus (without a shaderpack), Embeddium and ImmediatelyFast no longer keep the render cache on its slow
    path: client_entities with Embeddium 137 -> 845 fps, Oculus 238 -> 440-464, ImmediatelyFast 371 -> 681 (1.20.1);
    Iris + Sodium 377 -> 570, ImmediatelyFast 809 -> 1 149 (1.21.1). Frames as before, bit for bit.
- **Entity Model Features / Fresh Animations.** EMF before 3.3 compiles each model's animation into one method too
  large for the JIT, so it ran in the interpreter (35 % of a frame): Sleepium cuts it into parts the JIT compiles - the
  same instructions, the same results bit for bit (checked on 26 496 runs). EMF's model parts are drawn from video
  memory like vanilla's. 144 Fresh Animations mobs among a test world's 450 other entities, Sleepium before this ->
  with it: EMF 3.2 186 -> 391 fps, EMF 3.3 ~320 -> ~440 (1.20.1); EMF 3.3 ~370 -> ~500 (1.21.1); without Sleepium
  32-46. Entities whose rendering EMF, ETF or Iris / Oculus take part in stay on the render thread: those mods keep the
  entity being drawn in globals.
- The parallel renderer's per-thread renderer sets are built only when an entity goes to the workers: next to mods
  that keep every entity on the render thread (Oculus, EMF) the world no longer pauses when it opens (16 s in the
  Biohazard pack).
- **Crash at start with TerraBlender fixed** (1.0.x, both versions): TerraBlender makes methods Sleepium replaces public,
  and Mixin refused Sleepium's narrower ones. TerraBlender is in many packs (Biomes O' Plenty, Regions Unexplored, ...).
  The whole Biohazard pack (473 mods, Fresh Animations included), a new world: 351-379 -> 571-578 fps (1.20.1).
- A mod whose mixin config lists an empty (`null`) entry no longer hides the mods after it from Sleepium's check of
  other mods' changes (Sleep Tight's does): in a large pack Sleepium then did not step aside for Radium and the game
  did not start. Tested with the whole Biohazard pack (473 mods).
- Burning entities are drawn on the parallel render workers (the flame where vanilla draws it).
- Interpolated animated textures (`interpolate` in .mcmeta) are blended over the images' memory - pixel for pixel as
  vanilla, several times faster (a mod's 512x512 effect texture: 12 % -> 3.8 % of the render thread).
- Translucent blocks are drawn from a kept list of sections (no pass over every visible section).

1.20.1 only - what the port needed beyond the 1.21.1 engine:

- Entity sleep on Forge (entities are capability providers), the collision memo with production (SRG) names, entity
  data read without 1.20.1's lock and from an array.
- On an Intel N100 production server: items 114.9 -> 10.7 ms per tick, mobs 22.2 -> 13.25. A Cursed Fate fight on the
  client: 87 -> 600-646 fps (with Oculus + Embeddium 289 -> 299: the entities stay on the render thread).
- Not on 1.20.1: the adapters for Veil / Sable / Create Aeronautics (those mods are 1.21.1 only).

The repository now keeps a branch per Minecraft version and loader; `main` is the front page with the documentation
for all versions (`docs/`).

## 1.0.3+mc1.21.1 - 2026-11-03

- Compatibility with other optimisation mods. Tested together with 22 of them and a 13-mod pack (Sodium, Iris,
  Embeddium, Lithium, Moonrise, C2ME, ModernFix, FerriteCore, ImmediatelyFast, EntityCulling, ScalableLux, Radium and
  more). Five crashed or broke with 1.0.2 and run now: Embeddium (its mixins come from a plugin, not its config list),
  ModernFix, Lithium, Moonrise and C2ME (a mixin of ours that gave way left out an interface other mixins of ours
  used; a method another mod adds too; C2ME's random). Where another mod changes the same code, Sleepium steps aside
  there and says so in the log. With Lithium or Moonrise installed, Sleepium's changes are still exact (world hashes).
- Water, glass and ice are drawn in one multi-draw, in vanilla's order: the terrain view was bound by the graphics
  driver's work for vanilla's per-section translucent draws. A view over a river: 1227-1383 -> 2635-2833 fps (RTX 4070
  SUPER); on an Intel N100 the client's CPU use dropped from 1.67 to 0.52 cores. Frames unchanged (frame hashes).
  Shaderpacks are unchanged (Complementary Reimagined: 604-638 fps on 1.0.2, 607-640 on 1.0.3).
- Water takes light from the light volumes like the other blocks: a light change no longer rebuilds every section
  with water (CPU 3.6 -> 2.6 cores on a scene of lights switching over water).
- Item frames, chests and signs drawn from video memory (no per-frame copy); layers drawn again from kept lists keep
  their draw data in place.
- Players without Sleepium can join a server with it (tested).

## 1.0.2+mc1.21.1 - 2026-11-02

- A new settings screen: a sidebar of pages (Overview, the feature groups, Shaders, All flags) and a panel describing
  what the pointer is on. The Overview shows the engine at work: FPS and frame times, the singleplayer server's
  MSPT / TPS, block entities asleep and ticks saved per second, the shaderpack's state; presets (Maximum, Default,
  Compatible, Off), the feature groups' switches, live stats in the game corner, and a report to copy for bug
  reports. Also opened from the pause menu.
- Shaderpack options: each pack's own settings screen (its screens, sliders, profiles and labels, as Iris shows
  them), saved to shaderpacks/<pack>.txt. Applying prepares the new settings beside the pack in use and swaps them in
  one frame: no vanilla frames meanwhile, and the world's meshes are kept when the pack's block ids stay the same.
- Diagnostics for the render thread (-Dlab.rcache.time, -Dlab.prender.why), off by default.

## 1.0.1+mc1.21.1 - 2026-11-02

- Shaderpacks: a pack is now prepared over frames and switched on when ready, with the progress in the corner; the game
  draws vanilla meanwhile instead of stopping. A driver's first compile of a pack is long (Intel UHD, Complementary:
  57 s frozen before); a pack chosen beforehand is prepared on the title screen.
- Settings screen: flags a plugin switches by another flag are shown as "part of" it (their own buttons did nothing);
  a flag switched off stays off even when the universal set is on; every flag has a description; the network lag
  test tool is no longer listed.
- The mod list shows Sleepium's logo, its page and issue tracker, and tells of new versions (Modrinth).

## 1.0.0+mc1.21.1 - 2026-11-02

The first public release.

- Server: sleeping block entities and entities (idle ticks skipped, woken at the exact tick vanilla acts), the event
  bus with compiled listener lists and allocation-free empty posts, exact memos and indexes (collisions, shapes,
  entity sections, neighbour updates, scheduled ticks), parallel world generation, chunk saving and loading off the
  main thread, batched network sends.
- Client: terrain in indirect multi-draws with packed vertices, entities batched and posed on the GPU, a render cache
  for unchanged objects, client light off the render thread, GeckoLib models with parallel bones and compiled Molang.
- Shaderpacks (beta): OptiFine / Iris-format packs run by Sleepium's own renderer, switched without a restart.
- A settings screen (Options → Sleepium...): features by group, the shaderpack, every flag with search; saved to
  `config/sleepium.properties`.
- Every default optimisation checked against vanilla by world and frame hashes (see README, Exactness).
- License: LGPL-3.0-only.
