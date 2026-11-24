# Picking up Sleepium

For whoever continues this project: a maintainer of the official repository, someone porting it to another Minecraft
version or loader, or someone taking ideas into their own mod. It records the state on 2026-11-24 (release 1.4.6),
where to start, the work that is open, and what we learned the hard way: the traps, the measurements that misled us,
and the ideas that were tried and gave nothing.

Read [CONTRIBUTING.md](../CONTRIBUTING.md), [how-it-works.md](how-it-works.md) and [testing.md](testing.md) first. This
page does not repeat them.

## State on 2026-11-24

| | 1.21.1 NeoForge | 1.20.1 Forge |
|---|---|---|
| branch | `1.21.1-neoforge` (main line) | `1.20.1-forge` (long-term) |
| last release | 1.4.6 | 1.4.6 |
| exactness suite (23 scenes) | identical to vanilla, defaults and parallel entity ticks | the same, checked on a production server |
| Maximum speed (`lab.fast`) | experimental, off by default | experimental, off by default |

- **Two modes.** The exact mode (the default) changes nothing the game computes, and every release is checked for
  that. *Maximum speed* (`lab.fast`, Options > Sleepium... > Maximum speed) is allowed to differ from vanilla: mobs
  tick in parallel without undo, each mob has its own random stream, pushes are summed and applied after a batch, and
  so on. It started in 1.4.0. Its design and numbers are in the 1.4.x changelog entries.
- **Fabric does not exist yet.** It was planned as the last item of 1.4 and never started.
- **The settings screen** (Options > Sleepium...) is generated from `src/main/resources/assets/sleepium/options.tsv`:
  one row per switch, with its group, default, description and dependencies. The defaults are in `defaults.txt` at the
  branch root. The thread budget (server / client / background cores) is in `Cores.java` and is saved to
  `config/sleepium-cores.properties`.
- **Open issues on GitHub:**
  - #4 (NullPointerExceptions, fixed in 1.1.9), #10 (deadlock with C2ME, fixed in 1.3.1) and #12 (crash with FTB
    Quests, fixed in 1.3.4) have their fixes released. They are still open, waiting for the reporters to confirm, and
    can be closed if nobody answers.
  - #11 (screen flicker) has fixes in 1.4.5 and 1.4.6 and still has an open end, listed under "Open work".
  - #13 is the pinned status issue: people ask for things or offer help there.

## Where to start

1. Build both branches (`./gradlew build`). Run `./gradlew runClient` and open Options > Sleepium... to see every
   switch with its description.
2. Run the exactness suite once on your machine before changing anything: `python tools/suite.py --workers 3`. Every
   scene must say `EXACT`. If one does not on a clean tree, your machine or JDK differs from ours. Find out why before
   you trust any later result.
3. Read the code for the feature you touch together with its comments. The comments say what was measured and what
   breaks without that line. A line that looks redundant usually has a reason written next to it.
4. To bring a change to the other version, use `python tools/notported.py` on `main`. It lists what one version has
   and the other does not.

## Releasing

A release is one version number for every supported Minecraft version, built from each version's branch.

1. Develop on a lab branch. Never commit experiments to the release branches.
2. Checks, before every release:
   - the exactness suite on both versions: `tools/suite.py`, and on a **production** server with the built jar
     (`tools/box_suite.py --jar <jar>`);
   - a production **client** start on both versions with the built jar (`tools/prod_client.py`). Twice we shipped a
     jar whose dev runs were fine: 1.4.1 did not start on clients, and from 1.1.0 the settings screen was locked. See
     "Traps" below.
   - For Maximum speed: `tools/fastcheck.py` (5-10 minutes), the mob scenes of `tools/mobmass.py`, and a run with 100
     bot players in a normal world on both versions (`tools/tick_profile.py mobs --bots 100 --normal` on 1.21.1,
     `tools/bots_soak.py --normal` on 1.20.1). The test scenes have no players, so they never load or unload chunks
     around a moving player. Two Maximum speed crashes in 1.4.x only showed up with bots.
     Pass flags as `"--flags=-Dlab.fast=true"`: argparse takes a bare `-D...` value for an option.
3. Merge the candidate into the release branch with `--no-ff`, tag `v<version>`, and attach both jars to the GitHub
   release.
4. On `main`: add a `CHANGELOG.md` entry and update `update.json`. The Forge / NeoForge mod list reads that file, so
   update both the promotions and the per-version lines.
5. Upload to Modrinth and CurseForge. A CurseForge upload that drops the connection may still have succeeded. Look at
   the project's files page before uploading again, or you get duplicates.

## Porting and backports

The 1.20.1 branch is a port of 1.21.1, made in November 2026. These are the differences that cost us time:

- **Names.** In production, 1.20.1 member names are SRG (`m_12345_`, `f_12345_`); class names are Mojang's. Dev runs
  use Mojang names, so anything looked up by name at run time works in dev and fails in production. Use
  `ObfuscationReflectionHelper` or the branch's `Names` helper, and **always run the built jar** on a production server
  and client.
- **Do not load game classes from mixin-plugin or preset code.** `Class.forName("net.minecraft.client.Minecraft")` in
  the defaults code loaded `BlockableEventLoop` before mixins were applied, and every client with Maximum speed stopped
  at start (1.4.1). To check whether a class exists, use `getResource("net/minecraft/client/Minecraft.class")`, which
  does not load it.
- **Forge capability providers.** An entity carrying a capability must not sleep: the provider has state Sleepium
  does not copy.
- **The event bus** is eventbus 6 on 1.20.1 and NeoForge's bus on 1.21.1. Sleepium replaces both with
  `CompositeEventBus`: listeners are called directly, and while parallel batches run, the posts that have listeners are
  done under one lock.
- **The renderer** uses the old `BufferBuilder.begin` / `endVertex` API on 1.20.1. Every vertex consumer you write
  exists twice, once per API (see `RenderCache.NOWHERE` in both branches).
- **Mods that wrap buffers.** Embeddium on 1.20.1 wraps vertex consumers. Raw byte copies must only unwrap the
  wrappers we know (`getOriginalBufferBuilder`). Any other wrapper gets ordinary vertex calls through its own API.
- **Float math differs in 1.21.1 dev runs.** The dev server runs recompiled Minecraft, and some float expressions
  compile differently from Mojang's jar. For example, `LookControl.getYRotD` uses two steps where the production jar
  uses one multiply. So a mob scene's dev "off" hash is not the production "off" hash. Off == on still holds in each.
  Production is the reference. 1.20.1 dev matches production.

**For a new Minecraft version:** start from the newer branch, keep the mod id `sleepium` for a straight port, and port
in this order: the event bus, then the renderer, then block-entity sleep, then entity sleep, and harness polish last.
The engine-level pieces are where the version differences are. A port is only "exact" once `tools/suite.py` passes on
its production server.

**For Fabric:** nothing is started. The core (sleep, memos, parallel ticks, render cache) is loader-independent Java
plus mixins. The loader-specific parts are the event bus replacement (Fabric has no central bus; mods use callbacks),
the config screen, the mod-compat adapters, and the per-mod rules that read `mods.toml`.

## Open work

In rough order of value.

**Issue #11** (flicker and stray textures on 1.20.1 with some mods; fixes in 1.4.5 and 1.4.6, waiting for the
reporter): the reporter's whole-screen black frame with the HUD (their test A) was never reproduced. Our test mod
(`-Dsleep.test.escape`, `-Dsleep.test.escape.badquad`) reproduces the stray textures and the crashes, not that frame.

**Maximum speed (`lab.fast`)**, as measured in 1.4.x:
- Mods' machines sleep less than they could: the exact sleep checks an idle mod machine with a hash of everything it
  owns on every tick, and classes where that check is too expensive never sleep. Maximum speed may trust "nothing
  changed for N ticks" instead. Ticking mods' machines on several threads is not an option: Create, AE2 and Mekanism
  keep shared networks (see "Tried and dropped").
- Natural spawning is still serial inside the chunk ticks (random ticks are parallel since 1.4.6).
- With Lithium (1.21.1), Canary or Radium (1.20.1), Maximum speed runs without its parallel passes. These mods
  overwrite `ServerChunkCache.getChunk`, `PoiManager` queries, `ClassInstanceMultiMap.find` and `Raid.updateBossbar`, so
  the hooks the parallel passes depend on give way (MixinYield), and since 1.4.7 the passes stay serial instead of
  hanging (`HookFlags`). To get the parallelism back, those hooks need places these mods do not overwrite (for chunks,
  `Level.getChunk(int, int, ChunkStatus, boolean)` is a candidate), then the same 100-bot checks.
- The path budget (`lab.fast.pathBudget=N`) is built and off. At 32: 30k pigs 39.2 -> 33.4 ms a tick, but strolls are
  2-4x rarer. Whether that is acceptable is a design decision, not a measurement.
- The client side of Maximum speed (`lab.fast.client`) costs frames until frames are decoupled from the client tick:
  2050 -> 1469 fps, 2.2 -> 4.2 cores. It needs the decoupled frame thread first.

**The exact mode:**
- Villager brains in parallel. Today they stay serial in the exact mode because they draw from the level's random
  every tick.
- The light engine and the server tick as dependency graphs, like world generation already is.
- Block entities in parallel, exactly. The options here are an ordering protocol or rollback, and rollback was
  measured as too slow.

**Ports:** Fabric; newer Minecraft versions.

## Traps we fell into

Each of these cost at least one bad release or one lost night.

**Mixins**
- A cancellable `@Inject` allocates a `CallbackInfo` on every call. In a hot path, use `@Redirect`, `@WrapOperation`,
  or an inject that is not cancellable.
- Helper classes (records, iterators, small call objects) go outside the mixin package. A nested class of a mixin can
  fail at start with `IllegalClassLoadError` ("is in a defined mixin package"), depending on load order.
- A server-side mixin listed in the config's `client` list applies in single-player and silently never on a dedicated
  server. Check the dedicated server's log for the mixin being applied.
- Injection order between mods and our own mixins matters. For #11, the guards had to run *before* another of our
  mixins on the same method, so they got explicit priorities (400 and 500).
- A hook in a base method does not run when a subclass overrides that method without calling `super`. Vanilla does
  this in places (1.21.1 `MinecraftServer.waitForTasks`), and so do methods other mods add (ModernFix's override of
  the same method on 1.20.1). Our wake-up hook was skipped, and server tasks waited for the next tick. The fix
  (`OverrideHooks`) copies such hooks into every override.
- After editing mixins or resources, run `processResources`. A classes-only build keeps the old mixin list.

**Resources**
- 1.1.0 and 1.1.1 shipped `options.tsv` with CRLF line ends: the worktrees were checked out with `core.autocrlf=true`,
  and Gradle packs the working copy. Every switch on the settings screen was locked and off until a player reported
  it. Since 1.1.2 the file is split on any line end and `.gitattributes` forces `eol=lf` for the jar's text
  resources. Check the built jar, not the tree.

**Exactness**
- An exactness check proves the result is the same. It says nothing about cost. From 1.2.0 to 1.3.0, sleep for mod
  machines hashed everything a block entity owns on every tick it checked. A dense scene of 80 mod block types took
  203 ms a tick instead of 11.75, with every exactness check green. Since then, a check that costs more than half of
  the tick turns sleep off for that class. Release checks need a cost check too: `be_dense.py` / `ent_dense.py` with
  the feature on and off.
- A parallel feature that keeps a "trap" (fall back to serial when something unusual happens) can quietly fall back
  every tick. Count how often it falls back, not only whether the result matches.

**Measurement**
- JFR's attribution overstates. Twice a JFR "hot spot" was not slower when timed directly. Time the suspect code
  before building a fix for it.
- JIT warm-up dominates on weak CPUs. On an Intel N100, the first world-generation benchmark gave 7 chunks/s and a warm
  one 23.
- In-process A/B flips are biased. The first one or two windows after a switch are still warming up, so throw them
  away.
- A noisy machine is fine if A and B run interleaved under the same noise (ABBA). Absolute numbers from a noisy run
  cannot be compared with older quiet runs.
- Compare every new number with the published ones. An unexpected baseline is a finding in itself, not something to
  measure around.
- Watch the screen of a client run. A crash dialog or a menu over the world makes a run look like a hang or a fast
  frame rate.

**Rendering**
- The entity render cache records what a renderer writes and replays it. Three cases break that: a renderer that writes
  nothing (it draws into its own VBOs, as Immersive Engineering's water wheels do), a mod's event listener drawing into
  the game's own buffer source instead of the one it was given (#11), and a wrapped vertex consumer. 1.4.5 handles all
  three. A new render cache case should get a test mod first. `dev/compatprobe/` has the existing ones.
- Intel GL (UHD, N100): no SSBO in the paths we use. Test on Intel before calling a GPU path universal.
- In our own core shaders, samplers must be named `Sampler0`..`Sampler11`, and some uniform names are reserved by the
  game.

**Threads**
- The client and the server have separate pools, and their budgets are set in `Cores.java`. In single-player both run
  in one process, so a budget that takes every core for one side starves the other.
- Never spin idle threads. A pool that busy-waits wins benchmarks and loses on players' machines, laptops and
  four-core CPUs.

## Tried and dropped

- **Block entities in parallel by region** (measured 2026-11-24, not built): vanilla block entities are already cheap
  because idle ones sleep (1024 hoppers 0.9 ms a tick, 5120 smelting blocks 3.2 ms), so parallel ticks would save
  1-2 ms. The real cost is mods' machines (a dense scene of 556 machine types: 270 of 290 ms a tick), but Create, AE2
  and Mekanism keep shared networks that two threads would break, and nothing tells safe mods from unsafe ones
  without knowing each mod. In that scene Sophisticated Storage blocks packed side by side overflow the stack even on
  a server without Sleepium: exclude them from dense tests.

So nobody spends a week on them again. All numbers are from our machines.

- **AI level of detail** (mobs far from players think less often): 30k pigs -9 %, a farm 0 %. Kept as a flag only
  (`lab.fast.aiLod`).
- **A big shared path cache** (65536 entries) and **per-thread path memory across batches**: 279 M hits, no faster.
  A stroll path costs by its nodes, about 1 ms of thread time.
- **A fast random for every entity** in Maximum speed broke mob sleep, which relies on the random state. Entities keep
  vanilla's generator. Per-thread level randoms are in.
- **An exact quarter sine table:** slower everywhere. The sign arithmetic costs more than the cache it saves. A faster
  non-exact sine would change rotations and trajectories.
- **JDK Vector API for noise:** three traps each made the vector code 2-10x slower than scalar until fixed. GraalVM's
  JIT has no Vector API intrinsics (17x slower). Net gain on the host was within noise.
- **#11, first attempts:**
  - Redirecting the frame's buffer source into the recording for all code caught Sleepium's own batched entity draws
    and crashed.
  - Rolling back the buffers after an escaped write left them inconsistent.
  - Drawing every mob the vanilla way while render listeners exist cost about 75 % of the frame rate.
- **ECS / struct-of-arrays entities, and full RCU of the world:** refused. They are neither exact nor universal (every
  mod's entity would need porting).

## Tools

All in each version's `tools/`, documented in their headers:

| Tool | What it does |
|---|---|
| `exactness.py <scene>` | off vs on on a dev server, world and entity hashes |
| `suite.py` | the 23 exactness scenes on several servers at once |
| `box_suite.py` | the same suite on production servers with the built jar, on several machines |
| `fastcheck.py` | a 5-10 minute check: each scene sprinted, compared with stored "off" hashes |
| `mobmass.py`, `gen_mobmass.py` | mob-mass scenes (pens, farms, raiders) for Maximum speed timing |
| `be_dense.py`, `ent_dense.py` | dense mod scenes, on vs off: the cost check exactness cannot give |
| `prod_client.py` | a production client launched the way a launcher does, with the built jar |
| `client_bench.py`, `blink_scan.py` | client frame timing; scans for single-frame flashes |
| `mod_exactness.py`, `mod_entities.py`, `mod_blockentities.py` | per-mod checks |

**ClientAuto** (`-Dsleep.clientAuto=<label>`) drives a client without a person: it loads a world, builds a scene,
teleports, holds still, flips a switch for an A/B (`-Dsleep.clientAuto.ab=<class>#<static boolean>@<seconds>`), and
takes screenshots and frame hashes. Its system properties are listed at the top of `ClientAuto.java`.

**Test probes** are debug system properties that make Sleepium behave like a misbehaving mod, so a compatibility
fix can be tested without that mod:
- `-Dsleep.test.escape` (+ `.badquad`, `.good`, `.cached`): a render listener writing into the game's buffers;
- `-Dsleep.test.tickListener`: an entity tick listener with its own state;
- `-Dsleep.test.stimulate=x1,z1,x2,z2`: keeps machines in an area working.
