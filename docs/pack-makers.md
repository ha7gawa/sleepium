# Sleepium for modpack makers and mod authors

Sleepium decides most things by itself: it proves what it can and gives way to other mods where their changes meet
its own. Since 1.2.3 you can see every one of those decisions and change them, per mod, from the game or from a
file you ship with your pack.

## See what Sleepium does to each mod

Options -> Sleepium -> **Mods**: every mod with a short line - the rules set for it, Sleepium's changes that gave way
to it, how many kinds of its blocks and entities sleep. Open a mod to see why each of its blocks or entities sleeps or
not ("can't prove its tick idle - calls ..."), and to change its rules.

**Measure 15 s** times every block entity, entity, event listener and entity drawing per mod while you play - no
sampling, exact - and the list sorts by cost. It also lists **where** the server's time went: the ten costliest chunks
with what is in them (`overworld x 8 z 8: 0.37 ms/tick - boat 0.37`).

On a dedicated server: `/sleepium profile 15`, then `/sleepium report`. **Write report** (or the command) saves all of
it to `config/sleepium-report.md` - attach that file when you report a problem.

## Rules per mod

| rule | values | what it does |
|---|---|---|
| `sleep.mod.<modid>.sleep` | on / observed / off | its block entities sleep when proven idle / only while watched closely (64 idle ticks first, all their state compared) / never |
| `sleep.mod.<modid>.entitySleep` | on / off | its entities sleep when idle / never |
| `sleep.mod.<modid>.renderCache` | on / off | its entities drawn from Sleepium's render cache / always the mod's own way |
| `sleep.mod.<modid>.parallelRender` | on / off | its entities drawn on Sleepium's helper threads / always on the render thread |
| `sleep.mod.<modid>.giveWay` | conflicts / always / never | where Sleepium's change and the mod's meet: Sleepium gives way on a real conflict (default) / wherever they touch the same method / never - Sleepium's change wins and the mod's conflicting mixin is taken out (check that the mod still works) |
| `sleep.yield.<SleepiumMixin>` | conflicts / always / never | the same for one of Sleepium's changes |

Lists of single types still work: `sleep.compat.noSleep=mymod:clock,othermod:*` (`modid:*` = every type of that
mod), also `noEntitySleep`, `noRenderCache`, `noParallelRender`.

## Ship your choices with the pack

Set things up in the game (the Mods page, the other pages), then **Mods -> Save as pack settings**: it writes
`config/sleepium-pack.properties`. Put that file in your pack's `config` folder. Players can still change anything you
did not lock; lock a key with `lock.<key>=true`:

```properties
# config/sleepium-pack.properties
sleep.mod.create.renderCache=off
sleep.mod.somemod.giveWay=never
lab.opt.SoundNoWaitMixin=false
lock.lab.opt.SoundNoWaitMixin=true
```

The order, strongest first: a JVM launch flag (`-Dkey=value`), a key the pack locked, the player's own
`config/sleepium.properties`, the pack's file, the mods' own declarations, Sleepium's defaults. The settings screen
shows where each value comes from.

## For mod authors: declare it in your mods.toml

No dependency on Sleepium - the loader keeps the table, Sleepium reads it when it is there:

```toml
[modproperties.mymod]
sleepium = { sleep = "observed", entitySleep = "off", noRenderCache = ["mymod:clock"] }
```

A mod speaks only for itself: ids outside its namespace are ignored. A pack's or a player's choice wins over a
declaration. For code there is also `dev.lull.api.Compat` (`excludeFromSleep`, `excludeFromRenderCache`, ...).

## Something is wrong and you do not know which part

Options -> Sleepium -> **Find the cause**: each round turns half of the suspects off until the next start; you restart,
check, and answer "Still happens" or "Gone". About 7-8 restarts for all of Sleepium's settings. The one found stays off,
with the line for your pack file. Please tell us what it was (GitHub issues) so we can fix it.

## The HUD

Options -> Sleepium -> **HUD**: frames and a frame graph, stutters taken apart (client tick, GC, the rest), the
server's tick, GC pauses, CPU per thread, memory, the mods that cost most right now, what sleeps. Each widget goes in
the corner you choose; `config/sleepium-hud.properties` keeps it (a pack can ship its own). Stutters are written to
`logs/sleepium-stutters.log` while that widget is shown.
