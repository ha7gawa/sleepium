package dev.lull.sleepium;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Sleepium for Minecraft 1.7.10 / Forge (lab): the engine's ideas rebuilt on 1.7.10's code. */
@Mod(modid = "sleepium", name = "Sleepium", version = Tags.VERSION, acceptableRemoteVersions = "*")
public class Sleepium {
    public static final Logger LOGGER = LogManager.getLogger("Sleepium");

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        LOGGER.info("[sleep] Sleepium {} for 1.7.10 (lab)", Tags.VERSION);
    }
}
