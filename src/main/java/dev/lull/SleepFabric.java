package dev.lull;

/** Fabric (lab-fabric-1.21.1): the parts of SleepMod's constructor the Fabric build has. */
public final class SleepFabric implements net.fabricmc.api.ModInitializer {
    @Override
    public void onInitialize() {
        // vanilla race: OctahedralGroup.rotate(Direction) publishes its lazily built map before filling it - a second
        // worldgen thread rotating a jigsaw at the same time read null. Filled here once (the NeoForge branch does the same).
        for (com.mojang.math.OctahedralGroup g : com.mojang.math.OctahedralGroup.values())
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) g.rotate(d);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(s -> Loader.server = s);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> Loader.server = null);
        SleepMod.LOGGER.info("sleep: Sleepium (Fabric lab) - world generation and entity rendering parts");
    }
}
