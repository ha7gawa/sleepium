package dev.lull.opt;

import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SaveDirect (optsupport/SavePalettes): a container's registry and strategy, the arguments of its pack. */
@Mixin(PalettedContainer.class)
public interface SaveDirectAccessor {
    @Accessor("registry")
    IdMap<?> sleep$registry();

    @Accessor("strategy")
    PalettedContainer.Strategy sleep$strategy();
}
