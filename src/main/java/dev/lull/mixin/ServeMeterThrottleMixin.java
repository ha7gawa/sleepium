package dev.lull.mixin;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * ServeMeter (notes/92, an experiment): -Dlab.serve.ticketThrottle=N - how many players' chunk tickets the
 * DistanceManager lets be in flight at once (vanilla 4; each slot is released when its chunk is entity-ticking).
 */
@Mixin(DistanceManager.class)
public abstract class ServeMeterThrottleMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 4))
    private int sleep$throttle(int four) {
        return Integer.getInteger("lab.serve.ticketThrottle", four);
    }
}
