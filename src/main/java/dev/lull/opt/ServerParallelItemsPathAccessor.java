package dev.lull.opt;

import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** part of SpecTicks (lab): the shared path type cache's arrays, for merging the runs' overlays. */
@Mixin(PathTypeCache.class)
public interface ServerParallelItemsPathAccessor {
    @Accessor("positions")
    long[] sleep$positions();

    @Accessor("pathTypes")
    PathType[] sleep$pathTypes();
}
