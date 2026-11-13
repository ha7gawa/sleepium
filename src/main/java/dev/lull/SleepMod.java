package dev.lull;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/** Sleepium on Minecraft 1.20.1 / Forge: the port's entry point (the engine comes over from ../sleep step by step). */
@Mod(SleepMod.MOD_ID)
public final class SleepMod {
    public static final String MOD_ID = "sleepium";
    /** a dev run (not a built jar in a launcher): the tools' old names stay too (/sleep, /sleepclient) */
    public static final boolean DEV = !FMLEnvironment.production;
    static final Logger LOGGER = LogUtils.getLogger();

    public SleepMod() {
        IEventBus gameBus = MinecraftForge.EVENT_BUS;
        if (Boolean.getBoolean("lab.opt.HeapReturn")) LOGGER.info("sleep: {}", dev.lull.optsupport.HeapReturn.apply());   // memory back to the OS after bursts
        // classes the game loads only while played, learned and preloaded in the background (lab.opt.ClassPreload)
        gameBus.addListener(EventPriority.NORMAL, false, net.minecraftforge.event.server.ServerAboutToStartEvent.class, e -> dev.lull.optsupport.ClassPreload.preload());
        gameBus.addListener(EventPriority.NORMAL, false, net.minecraftforge.event.server.ServerStartedEvent.class, e -> dev.lull.optsupport.ClassPreload.started());
        gameBus.addListener(EventPriority.NORMAL, false, net.minecraftforge.event.server.ServerStoppedEvent.class, e -> dev.lull.optsupport.ClassPreload.stopped());
        TickTimer.INSTANCE.register(gameBus);
        EventCounter.INSTANCE.attach(gameBus);
        EventBench.registerListeners(gameBus);
        dev.lull.optsupport.DuplicateJars.find();   // two Sleepium jars in mods: said in the log (and in the chat on the client)
        gameBus.addListener(SleepCommand::register);
        gameBus.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.START) NetBench.onTickStart();   // 1.21.1: ServerTickEvent.Pre
        });
        // the bulk transfer's messages and the coalescing capability: Forge's optional channels (1.21.1: RegisterPayloadHandlersEvent)
        dev.lull.net.BulkPayloads.register();
        gameBus.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.END) dev.lull.net.Bulk.pump(e.getServer());   // 1.21.1: ServerTickEvent.Post
        });
        gameBus.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.END) dev.lull.api.Travel.tick();   // 1.21.1: ServerTickEvent.Post
        });
        gameBus.addListener(EventPriority.NORMAL, false, net.minecraftforge.event.level.ChunkEvent.Load.class, GenBench::onChunkLoad);
        gameBus.addListener(EventPriority.NORMAL, false, net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) dev.lull.net.Bulk.onLogout(sp);
        });
        dev.lull.net.BulkSelfTest.register();
        gameBus.addListener(EventPriority.NORMAL, false, net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p0) dev.lull.net.Coalesce.enable(p0);   // before anything big goes out (mods after it send theirs)
        });
        if (FMLEnvironment.dist.isClient()) {
            ClientMeter.register(gameBus);   // class is only loaded on the physical client
            dev.lull.ui.SleepUi.register(gameBus);   // the settings screen (mod list, Options)
            // the render cache's frame budget (1.21.1: RenderFrameEvent.Pre; Forge 1.20.1: RenderTickEvent START)
            gameBus.addListener(EventPriority.HIGHEST, false, TickEvent.RenderTickEvent.class, e -> {
                if (e.phase != TickEvent.Phase.START) return;
                dev.lull.optsupport.RenderCache.frame();
                dev.lull.shaders.ShaderPackCompileTest.maybeRun();   // the shaderpack pipeline: a pack requested or prepared over frames
                dev.lull.shaders.ShaderPipeline.loadConfigured();
            });
        }
        System.out.println("[sleep] Sleepium for 1.20.1 / Forge loaded");
    }
}
