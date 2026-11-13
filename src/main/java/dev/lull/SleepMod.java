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
        // Which layer/loader owns the bus? Mixin can only transform classes of the GAME layer (TransformingClassLoader).
        LOGGER.info("sleep: bus class {} module {} loader {}; game class loader {}", gameBus.getClass().getName(),
                gameBus.getClass().getModule(), gameBus.getClass().getClassLoader(),
                net.minecraft.server.MinecraftServer.class.getClassLoader());
        if (Boolean.getBoolean("lab.opt.HeapReturn")) LOGGER.info("sleep: {}", dev.lull.optsupport.HeapReturn.apply());   // memory back to the OS after bursts
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
        modBus.addListener(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class, dev.lull.net.BulkPayloads::register);
        gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> dev.lull.net.Bulk.pump(e.getServer()));
        gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> dev.lull.api.Travel.tick());
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
