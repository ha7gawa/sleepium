package dev.lull.opt;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** lab.fast chunk ticks: the level's neighbour updater swapped for the pass (SpecTicks.DeferredNeighbors), no cost outside it */
@Mixin(Level.class)
public interface ServerParallelItemsNeighborAccessor {
    @Accessor("neighborUpdater")
    NeighborUpdater sleep$neighborUpdater();

    @Mutable
    @Accessor("neighborUpdater")
    void sleep$setNeighborUpdater(NeighborUpdater u);
}
