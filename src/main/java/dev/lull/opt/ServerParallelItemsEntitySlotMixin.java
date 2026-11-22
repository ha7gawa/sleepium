package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** part of SpecTicks (lab): the entity's slot in the block being ticked (SpecSlot). */
@Mixin(Entity.class)
public abstract class ServerParallelItemsEntitySlotMixin implements dev.lull.optsupport.SpecSlot {
    @Unique private int sleep$slot = -1;
    @Unique private long sleep$slotEpoch = -1;

    @Override
    public int sleep$specSlot(long epoch) {
        return sleep$slotEpoch == epoch ? sleep$slot : -1;
    }

    @Override
    public void sleep$setSpecSlot(int slot, long epoch) {
        sleep$slot = slot;
        sleep$slotEpoch = epoch;
    }
}
