package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** TickSlice: the tick an entity was last ticked in, on the entity itself (entities tick on several threads). */
@Mixin(Entity.class)
public abstract class TickSliceEntityMixin implements dev.lull.optsupport.TickSlice.Marked {
    @Unique private int sleep$sliceRound;

    @Override
    public int sleep$sliceRound() {
        return sleep$sliceRound;
    }

    @Override
    public void sleep$setSliceRound(int r) {
        sleep$sliceRound = r;
    }
}
