package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** part of SpecTicks, lab.fast (FastPush): an entity's summed push impulse of a run, applied after it */
@Mixin(Entity.class)
public abstract class ServerParallelItemsPushAccMixin implements dev.lull.optsupport.FastPush.Acc {
    @Unique private double sleep$pdx, sleep$pdz;
    @Unique private boolean sleep$pending;

    @Override public double sleep$pdx() { return sleep$pdx; }
    @Override public double sleep$pdz() { return sleep$pdz; }
    @Override public void sleep$pset(double dx, double dz) { sleep$pdx = dx; sleep$pdz = dz; }
    @Override public boolean sleep$pending() { return sleep$pending; }
    @Override public void sleep$setPending(boolean p) { sleep$pending = p; }
}
