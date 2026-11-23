package dev.lull;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * The sleep engine (mod id {@code sleep}): optimisations, the sleep engines, the event bus, networking, the public
 * API (dev.lull.api), measurements. Its measuring commands are under {@code /sleep}:
 * <ul>
 *   <li>{@code /sleep start}  – reset tick stats, start counting every event type posted on the game bus</li>
 *   <li>{@code /sleep status} – short summary in chat</li>
 *   <li>{@code /sleep stop}   – stop counting, detach counting listeners, write report to {@code <gamedir>/probe/}</li>
 *   <li>{@code /sleep bench [n]} – microbenchmark of {@code IEventBus.post} inside the live game</li>
 *   <li>{@code /sleep world}  – entity / block entity / chunk counts per level</li>
 * </ul>
 */
@Mod(SleepMod.MOD_ID)
public final class SleepMod {
    public static final String MOD_ID = "sleepium";
    /** a dev run (not a built jar in a launcher): the tools' old names stay too (/sleep, /sleepclient) */
    public static final boolean DEV = !net.neoforged.fml.loading.FMLLoader.isProduction();
    static final Logger LOGGER = LogUtils.getLogger();

    public SleepMod(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        IEventBus gameBus = NeoForge.EVENT_BUS;
        // vanilla race (1.20.1 and 1.21.1): OctahedralGroup.rotate(Direction) publishes its lazily built rotatedDirections map before
        // filling it, so a second worldgen thread rotating a jigsaw at the same time reads null (NPE in FrontAndTop: "Exception
        // generating new chunk" on a fresh world; parallel structure generation makes it likely). Filled here once, on one
        // thread: the same values vanilla computes, no race left (notes/91).
        for (com.mojang.math.OctahedralGroup g : com.mojang.math.OctahedralGroup.values())
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) g.rotate(d);
        // Which layer/loader owns the bus? Mixin can only transform classes of the GAME layer (TransformingClassLoader).
        LOGGER.info("sleep: bus class {} module {} loader {}; game class loader {}", gameBus.getClass().getName(),
                gameBus.getClass().getModule(), gameBus.getClass().getClassLoader(),
                net.minecraft.server.MinecraftServer.class.getClassLoader());
        if (Boolean.getBoolean("lab.opt.HeapReturn")) LOGGER.info("sleep: {}", dev.lull.optsupport.HeapReturn.apply());   // memory back to the OS after bursts
        if (dev.lull.optsupport.PipeScout.ON) dev.lull.optsupport.PipeScout.register();   // measurement only: per-player tick work with /sleep bots measure
        if (Boolean.getBoolean("sleep.test.tickListener")) dev.compatprobe.TickListenerProbe.register();
        if (Boolean.getBoolean("sleep.test.escape") && net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) dev.compatprobe.EscapeProbe.register();   // (issue #11 test: a listener writing into the game's buffers)   // test: a thread-unsafe tick listener (Maximum speed's run lock)
        if (Boolean.getBoolean("lab.opt.TrackFast")) dev.lull.optsupport.TrackFast.register();   // A/B switch and counters with /sleep bots
        if (Boolean.getBoolean("lab.opt.ChunkSendPar")) dev.lull.optsupport.ChunkSendPar.register();   // A/B switch and counters with /sleep bots
        if (dev.lull.optsupport.TrackIndex.ON) dev.lull.optsupport.TrackIndex.register();   // A/B switch and counters with /sleep bots
        if (dev.lull.optsupport.TrackDeliver.ON) dev.lull.optsupport.TrackDeliver.register();   // A/B switch and counters with /sleep bots
        if (dev.lull.optsupport.NetOrderVerify.ON) dev.lull.optsupport.NetOrderVerify.register();   // measurement only: the order check with /sleep bots measure
        // classes the game loads only while played, learned and preloaded in the background (lab.opt.ClassPreload)
        gameBus.addListener(net.neoforged.neoforge.event.server.ServerAboutToStartEvent.class, e -> dev.lull.optsupport.ClassPreload.preload());
        gameBus.addListener(net.neoforged.neoforge.event.server.ServerStartedEvent.class, e -> dev.lull.optsupport.ClassPreload.started());
        gameBus.addListener(net.neoforged.neoforge.event.server.ServerStoppedEvent.class, e -> dev.lull.optsupport.ClassPreload.stopped());
        TickTimer.INSTANCE.register(gameBus);
        EventCounter.INSTANCE.attach(gameBus);
        EventBench.registerListeners(gameBus);
        dev.lull.optsupport.DuplicateJars.find();   // two Sleepium jars in mods: said in the log (and in the chat on the client)
        gameBus.addListener(SleepCommand::register);
        gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre.class, e -> NetBench.onTickStart());
        if (ServeMeter.ON) {   // what a slow tick does to the players (notes/92): measurement only
            gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre.class, e -> ServeMeter.tickStart());
            gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> ServeMeter.tickEnd(e.getServer()));
        }
        if (ChunkTimeline.ON) dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {   // with bots' measure windows too
            public void start() {
                ChunkTimeline.start();
            }

            public String end(long ticks) {
                return ChunkTimeline.report() + " | " + ServeMeter.chunkCensus();
            }
        });
        if (dev.lull.optsupport.ChunkOrder.ON)   // where the players are heading, for the chunk queues (notes/93)
            gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> dev.lull.optsupport.ChunkOrder.update(e.getServer()));
        if (ServeMeter.ON || ServeMeter.SLOW_TICK > 0)   // the test load: a slow overworld tick, /sleep stall
            gameBus.addListener(net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, e -> {
                if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel l && l.dimension() == net.minecraft.world.level.Level.OVERWORLD) ServeMeter.levelTick(l);
            });
        modBus.addListener(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class, dev.lull.net.BulkPayloads::register);
        gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> dev.lull.net.Bulk.pump(e.getServer()));
        gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> dev.lull.api.Travel.tick());
        if (dev.lull.optsupport.TickerDump.AT > 0 || dev.lull.optsupport.BeCensus.ON) gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> dev.lull.optsupport.TickerDump.tick(e.getServer()));
        gameBus.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) dev.lull.net.Bulk.onLogout(sp);
        });
        dev.lull.net.BulkSelfTest.register();
        gameBus.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p0) dev.lull.net.Coalesce.enable(p0);   // before anything big goes out (mods after it send theirs)
        });
        gameBus.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Load.class, GenBench::onChunkLoad);
        if (FMLEnvironment.dist.isClient()) {
            ClientMeter.register(gameBus);   // class is only loaded on the physical client
            dev.lull.ui.SleepUi.register(container, gameBus);   // the settings screen (mod list, Options)
            gameBus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class,
                    e -> {
                        dev.lull.optsupport.RenderCache.frame();
                        dev.lull.shaders.ShaderPackCompileTest.maybeRun();
                        dev.lull.shaders.ShaderPipeline.loadConfigured();
                    });
        }
    }
}
