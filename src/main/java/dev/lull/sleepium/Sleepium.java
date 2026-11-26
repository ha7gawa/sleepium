package dev.lull.sleepium;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Sleepium for Minecraft 1.7.10 / Forge (lab): the engine's ideas rebuilt on 1.7.10's code. */
@Mod(modid = "sleepium", name = "Sleepium", version = Tags.VERSION, acceptableRemoteVersions = "*")
public class Sleepium {
    public static final Logger LOGGER = LogManager.getLogger("Sleepium");

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        LOGGER.info("[sleep] Sleepium {} for 1.7.10 (alpha)", Tags.VERSION);
        if (e.getSide().isClient()) dev.lull.sleepium.bench.ClientBench.register();   // -Dsleep.bench
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new dev.lull.sleepium.bench.GenBench());
    }

    /** -Dsleep.genbench="x_z_r[;x_z_r...]": the generation bench runs by itself on the first server tick, then the server stops */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (System.getProperty("sleep.genbench") != null) FMLCommonHandler.instance().bus().register(new GenAuto());
    }

    public static final class GenAuto {
        private boolean done;

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent e) {
            if (done || e.phase != TickEvent.Phase.END) return;
            done = true;
            MinecraftServer srv = MinecraftServer.getServer();
            try {
                for (String run : System.getProperty("sleep.genbench").split(";")) {   // "x_z_r" (Gradle's JVM args split at spaces)
                    String[] a = run.trim().split("[_ ]+");
                    dev.lull.sleepium.bench.GenBench.run(srv, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), null);
                }
            } catch (RuntimeException ex) {
                LOGGER.error("[sleepbench] gen failed", ex);
            } finally {
                srv.initiateShutdown();   // a bench run never stays up
            }
        }
    }
}
