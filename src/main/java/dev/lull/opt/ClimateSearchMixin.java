package dev.lull.opt;

import dev.lull.optsupport.FlatRTree;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * ClimateSearch: the biome lookup's R-tree search (every quart of every chunk's biomes, structure checks) over flat
 * arrays, the same leaf as vanilla's walk (see FlatRTree). Only the default metric's entry point is replaced; a search
 * with another metric stays vanilla and shares the per-thread warm start.
 */
@Mixin(Climate.ParameterList.class)
public abstract class ClimateSearchMixin<T> {
    /** built on first use; FlatRTree's fields are final, so a racy publication is safe (two threads may both build) */
    @Unique private FlatRTree sleep$flat;

    /**
     * @author probe
     * @reason the same branch and bound over flat arrays (no Parameter objects, no interface metric per node)
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public T findValueIndex(Climate.TargetPoint targetPoint) {
        FlatRTree f = sleep$flat;
        if (f == null) sleep$flat = f = FlatRTree.of((Climate.ParameterList<?>) (Object) this);
        return (T) f.search(targetPoint);
    }
}
