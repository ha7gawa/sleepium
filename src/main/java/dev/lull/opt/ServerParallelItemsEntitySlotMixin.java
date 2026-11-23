package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** part of SpecTicks (lab): the entity's slot in the block being ticked (SpecSlot). */
@Mixin(Entity.class)
public abstract class ServerParallelItemsEntitySlotMixin implements dev.lull.optsupport.SpecSlot {
    /** {slot, epoch} in an array: an array store is not a field write - no write barrier on the hot path */
    @Unique private final long[] sleep$slot = {-1, -1};

    @Override
    public int sleep$specSlot(long epoch) {
        long[] a = sleep$slot;
        return a[1] == epoch ? (int) a[0] : -1;
    }

    @Override
    public void sleep$setSpecSlot(int slot, long epoch) {
        long[] a = sleep$slot;
        a[0] = slot;
        a[1] = epoch;
    }
}
