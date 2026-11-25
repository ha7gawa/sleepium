package dev.lull.sleepium;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Sleepium for Minecraft 1.12.2 / Forge (lab): the engine's ideas rebuilt on 1.12.2's code. */
@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION)
public class Sleepium {
    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("[sleep] Sleepium {} for 1.12.2 (lab)", Tags.VERSION);
        if (event.getSide().isClient()) {
            dev.lull.sleepium.bench.ClientBench.register();
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dev.lull.sleepium.render.ClientEvents.class);
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new dev.lull.sleepium.bench.GenBench());
    }

    /** -Dsleep.genbench="x z radius[;x z radius...]": the generation bench runs by itself after the start, then the server stops */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        String spec = System.getProperty("sleep.genbench");
        if (spec == null) return;
        net.minecraft.server.MinecraftServer srv = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        srv.addScheduledTask(() -> {
            try {
                for (String run : spec.split(";")) {   // "x_z_r" or "x z r" (Gradle's extra_jvm_args splits at spaces)
                    String[] a = run.trim().split("[_ ]+");
                    dev.lull.sleepium.bench.GenBench.run(srv, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), null);
                }
            } catch (RuntimeException ex) {
                LOGGER.error("[sleepbench] gen failed", ex);
            } finally {
                srv.initiateShutdown();   // a bench run never stays up
            }
        });
    }
}
