# Changelog

Versions follow [Semantic Versioning](https://semver.org): a patch release fixes, a minor release adds, a major
release changes the API (`dev.lull.api`) or the settings in a way that needs attention. Jars are named
`sleepium-<version>+mc<minecraft version>-<loader>.jar` (before 1.1.0 without the loader).

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
